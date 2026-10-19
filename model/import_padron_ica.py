#!/usr/bin/env python3
"""Import Ica's padrón (PADRON_CONSTRUCCION.xlsx) into a wasichai Core running srtm's model.

The workbook has two sheets. `padron` has one row per (predio, contribuyente): a declaración predial whose predio and
contribuyente repeat across rows. `construcciones` has one row per section of a nivel, joined to its declaración by
(codigo_predio, codigo_contribuyente). Ica's codes (vía, documento, condición, uso, material…) are read through
data/ica/codigos.json, none of them confirmed by the municipalidad yet.

Creates, in this order and skipping what Core already has (so a second run creates nothing): the via and unidad_urbana
catalogs, contribuyente (by codigo), predio (by codigo), declaracion_predial (by predio, contribuyente, año and
secuencia 001) and nivel_construccion (by its declaración, nivel, año and área, counting repeated sections). Every
record is checked against model.json first: a row that does not fit is rejected to the report, with what hangs from
it, and the rest is loaded. The report (--report, a CSV) also lists what needs a look: condominios without a %, titulares
in conflict, unmapped codes, niveles that do not add up to the padrón.

Stdlib only. See README.md («Cargar la Municipalidad de Ica»).

Run: python3 -I import_padron_ica.py --excel PADRON_CONSTRUCCION.xlsx --dry-run
Exit: 0 ok, 1 Core refused something, 2 the model lacks a field the importer fills (nothing is sent).
"""
import argparse
import csv
import json
import os
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation, ROUND_DOWN, ROUND_HALF_UP

from core_client import Client, CoreError
from import_predios import SUCESION, LoadError, clean_decimal, clean_text, post_all, read_xlsx

HERE = os.path.dirname(os.path.abspath(__file__))

# Ica's district: Ica, provincia Ica, departamento Ica (INEI), on the coast
UBIGEO = "110101"
UBICACION = {"ubigeo": UBIGEO, "region": "COSTA", "departamento": "ICA", "provincia": "ICA", "distrito": "ICA"}
SECUENCIA = "001"
CIEN = Decimal(100)
CENTIMO = Decimal("0.01")
# Excel's day 0 (its 1900 leap-year bug makes serials from 61 on exact)
EXCEL_EPOCH = date(1899, 12, 30)
# the padrón's filler for an unknown date of acquisition
FECHA_RELLENO = date(1900, 1, 1)
# Condominio.kt: besides propietario único, what a sole titular may declare itself
TITULAR_UNICO = frozenset({"PROPIETARIO UNICO", "SOCIEDAD CONYUGAL", "POSEEDOR"})
CONDOMINO = "CONDOMINO"
PROPIETARIO_UNICO = "PROPIETARIO UNICO"
# a nivel's valor_total_nivel against (valor_unitario + incremento) x (1 - depreciación) x área
TOLERANCIA_FORMULA = Decimal("1.00")
# the padrón's predio against the sum of its niveles
TOLERANCIA_VALOR = Decimal("1.00")
TOLERANCIA_AREA = Decimal("0.05")
LETRAS = {
    "muros_columnas": "muros_y_columnas", "techos": "techos", "pisos": "pisos", "puertas_ventanas": "puertas_y_ventanas",
    "revestimientos": "revestimientos", "banos": "banos", "instalaciones": "instalaciones_electricas_y_sanitarias",
}
REPORT_COLUMNS = ["categoria", "hoja", "fila", "codigo_predio", "codigo_contribuyente", "detalle"]


def default_model_path():
    return os.path.join(HERE, "model.json")


def default_codigos_path():
    return os.path.join(HERE, "data", "ica", "codigos.json")


def default_usos_path():
    return os.path.join(HERE, "data", "usos_predio.csv")


def load_model(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def load_codigos(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def read_usos_srtm(path):
    """data/usos_predio.csv as código -> descripción."""
    with open(path, encoding="utf-8") as f:
        return {r["codigo"].strip(): r["descripcion"].strip() for r in csv.DictReader(f)}


# ---------------------------------------------------------------------------
# mappings (pure)
# ---------------------------------------------------------------------------

def codigo(value):
    """A code as its text: the xlsx may carry 20 as '20.0'. Empty is None."""
    text = clean_text(value)
    if text is None:
        return None
    return text[:-2] if re.fullmatch(r"\d+\.0", text) else text


def documento(tipo, numero, codigos):
    """Ica's tipo_documento and nro_documento as (tipo_documento, numero_documento, report category or None). A DNI
    that lost its leading zeros is padded to 8 digits; a type without a number is SIN DOCUMENTO."""
    tipo, numero = codigo(tipo), clean_text(numero)
    if numero is None:
        return "SIN DOCUMENTO", None, "documento_sin_numero" if tipo not in (None, "0") else None
    if tipo is None:
        return "SIN DOCUMENTO", numero, None
    valores = codigos["tipo_documento"]["valores"]
    if tipo not in valores:
        return "OTROS", numero, "codigo_sin_mapeo"
    enum = valores[tipo]
    if enum == "DNI" and numero.isdigit() and len(numero) < 8:
        return enum, numero.zfill(8), "documento_rellenado"
    return enum, numero, None


def tipo_persona(tipo_documento, numero, nombre, condiciones, codigos=None):
    """A RUC that starts with 20 is a persona jurídica; a name that says SUCESION or a declaración as a sucesión
    (condición 2 or 6) is a sucesión; anyone else is a persona natural."""
    if tipo_documento == "RUC" and (numero or "").startswith("20"):
        return "JURIDICA"
    sucesion = set((codigos or {}).get("condicion_propiedad", {}).get("sucesion", ("2", "6")))
    if SUCESION.match(clean_text(nombre) or "") or condiciones & sucesion:
        return "SUCESION"
    return "NATURAL"


def porcentajes(n):
    """Each of n titulares' % of the predio: 100/n to the cent, the cents left over one each to the first ones, so they
    add up to 100 (Condominio.kt refuses more than 100 on the next edit)."""
    base = (CIEN / n).quantize(CENTIMO, rounding=ROUND_DOWN)
    sobra = int((CIEN - base * n) / CENTIMO)
    return [base + CENTIMO if i < sobra else base for i in range(n)]


def valores_condominio(autoavaluo, porcentaje):
    """(valor_condominio, valor_afecto) as Condominio.kt: the % of the autoavalúo to the cent, half up; with deducción
    0, valor_afecto is the same."""
    if autoavaluo is None or porcentaje is None:
        return None, None
    valor = (Decimal(autoavaluo) * porcentaje / CIEN).quantize(CENTIMO, rounding=ROUND_HALF_UP)
    return str(valor), str(max(valor, Decimal(0)).quantize(CENTIMO))


def condicion(code, titulares, codigos):
    """The declaración's condición as Condominio.kt leaves it: two or more titulares are condóminos; a sole one keeps
    its own if it is propietario único, sociedad conyugal or poseedor, and is propietario único otherwise."""
    if titulares > 1:
        return CONDOMINO
    propia = codigos["condicion_propiedad"]["valores"].get(codigo(code))
    return propia if propia in TITULAR_UNICO else PROPIETARIO_UNICO


def fecha(value):
    """An ISO date from the xlsx's text ('2004-01-01 00:00:00') or an Excel serial. The 1900-01-01 filler is None."""
    text = clean_text(value)
    if text is None:
        return None
    if re.fullmatch(r"\d+(\.\d+)?", text):
        parsed = EXCEL_EPOCH + timedelta(days=int(Decimal(text)))
    else:
        match = re.match(r"(\d{4})-(\d{2})-(\d{2})", text)
        if not match:
            raise ValueError(f"fecha '{text}' no es una fecha")
        parsed = date(*map(int, match.groups()))
    return None if parsed <= FECHA_RELLENO else parsed.isoformat()


def uso_srtm(code, usos):
    """An SRTM uso code as the declaración stores it: XX0000 is a clase, XXYY00 a sub clase, XXYYZZ a uso."""
    clase = usos[code[:2] + "0000"]
    sub_clase = usos[code[:4] + "00"] if code[2:4] != "00" else None
    uso = usos[code] if code[4:] != "00" else None
    return {"clase_uso": clase, "sub_clase_uso": sub_clase, "uso": uso}


def uso_ica(code, codigos, usos):
    """Ica's codigo_uso as {clase_uso, sub_clase_uso, uso}; None when it has no SRTM code."""
    entrada = codigos["uso"]["valores"].get(codigo(code))
    if not entrada or not entrada.get("srtm"):
        return None
    return uso_srtm(entrada["srtm"], usos)


def nivel(value, codigos):
    """Ica's nivel as (tipo_nivel, numero_piso): 0 is the sótano, n the piso n."""
    code = codigo(value)
    if code is None:
        return None, None
    especial = codigos["nivel"]["valores"].get(code)
    if especial:
        return especial["tipo_nivel"], especial["numero_piso"]
    return "PISO", int(code)


# ---------------------------------------------------------------------------
# the model (pure)
# ---------------------------------------------------------------------------

def _object(model, name):
    return next(o for o in model["objects"] if o["name"] == name)


def _relations(model, name):
    return [r for r in model.get("relationships", []) if r["source"] == name]


def unknown_fields(model, name, attributes):
    """The attributes the object has no field (or relationship) for: the model is older than the importer."""
    known = {f["name"] for f in _object(model, name)["fields"]} | {r["fieldName"] for r in _relations(model, name)}
    return [k for k in attributes if k not in known]


def check(model, name, attributes, relations=False):
    """What keeps a record from fitting the model: a required field empty, an option out of its enum. With relations,
    a required relationship too (left out until the ids are known)."""
    problems = []
    for f in _object(model, name)["fields"]:
        value = attributes.get(f["name"])
        if value is None:
            if f.get("required"):
                problems.append(f"{f['name']} es obligatorio")
        elif f.get("type") == "ENUM" and value not in model["enums"][f["enum"]]:
            problems.append(f"{f['name']} '{value}' no es una opción de {f['enum']}")
    if relations:
        problems += [f"{r['fieldName']} es obligatorio" for r in _relations(model, name)
                     if r.get("required") and attributes.get(r["fieldName"]) is None]
    return problems
