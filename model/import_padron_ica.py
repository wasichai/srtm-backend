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

HERE = os.path.dirname(os.path.abspath(__file__))
# under python3 -I (the xlsx is untrusted) neither the cwd nor the script's directory is on sys.path: put back only the
# latter, for core_client and import_predios
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from core_client import Client, CoreError  # noqa: E402
from import_predios import LoadError, clean_decimal, clean_text, post_all, read_xlsx  # noqa: E402

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
# a name that says it is a sucesión: SUC., SUC or SUCESION (not SUCRE)
SUCESION = re.compile(r"^SUC(\.|\s|ESI[OÓ]N\b)")
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
    """An ISO date from the xlsx's text ('2004-01-01 00:00:00' or '31/12/2004') or an Excel serial. The 1900-01-01
    filler is None."""
    text = clean_text(value)
    if text is None:
        return None
    iso, dmy = re.match(r"(\d{4})-(\d{2})-(\d{2})", text), re.fullmatch(r"(\d{1,2})/(\d{1,2})/(\d{4})", text)
    if re.fullmatch(r"\d+(\.\d+)?", text):
        parsed = EXCEL_EPOCH + timedelta(days=int(Decimal(text)))
    elif iso:
        parsed = date(*map(int, iso.groups()))
    elif dmy:
        dia, mes, anio = map(int, dmy.groups())
        parsed = date(anio, mes, dia)
    else:
        raise ValueError(f"fecha '{text}' no es una fecha")
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
    if not code.isdigit() or int(code) < 1:
        raise ValueError(f"nivel '{code}' no es un piso")
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


# ---------------------------------------------------------------------------
# dataset (pure)
# ---------------------------------------------------------------------------

@dataclass
class Registro:
    """A declaración or a nivel: its row, the (predio, contribuyente) it belongs to and its attributes."""
    fila: int
    codigo_predio: str
    codigo_contribuyente: str
    attributes: dict


@dataclass
class Dataset:
    vias: dict = field(default_factory=dict)  # (codigo, tipo, nombre, ubigeo) -> attributes
    unidades: dict = field(default_factory=dict)
    contribuyentes: dict = field(default_factory=dict)  # codigo -> attributes
    predios: dict = field(default_factory=dict)  # codigo -> attributes
    declaraciones: list = field(default_factory=list)
    niveles: list = field(default_factory=list)
    filas: dict = field(default_factory=dict)  # ("contribuyente" | "predio", codigo) -> its first row
    reporte: list = field(default_factory=list)
    errores: list = field(default_factory=list)  # the model lacks a field: nothing can be loaded


def _decimal(value):
    """A number of the xlsx for the checks of the report: empty is 0, unreadable is None (its row is rejected)."""
    try:
        return Decimal(clean_decimal(value) or "0")
    except InvalidOperation:
        return None


def _sum(values):
    return None if any(v is None for v in values) else sum(values, Decimal(0))


class _Builder:
    def __init__(self, anio, model, codigos, usos):
        self.anio = anio
        self.model = model
        self.codigos = codigos
        self.usos = usos
        self.data = Dataset()
        self.sin_mapeo = Counter()  # (columna, valor) -> rows
        self.primera = {}  # (columna, valor) -> (hoja, fila)
        self.campos = defaultdict(set)  # object -> every attribute it was given
        self.rechazados = set()  # predios that do not fit the model

    def report(self, categoria, hoja, fila, predio=None, contribuyente=None, detalle=""):
        self.data.reporte.append({"categoria": categoria, "hoja": hoja, "fila": fila, "codigo_predio": predio,
                                  "codigo_contribuyente": contribuyente, "detalle": detalle})

    def unmapped(self, hoja, row, columna):
        key = (columna, codigo(row.get(columna)))
        self.sin_mapeo[key] += 1
        self.primera.setdefault(key, (hoja, row["_fila"]))

    def mapped(self, hoja, row, tabla):
        """The option a code of a table maps to; one the table lacks counts as unmapped and is None."""
        columna = self.codigos[tabla]["columna"]
        code = codigo(row.get(columna))
        if code is None:
            return None
        valores = self.codigos[tabla]["valores"]
        if code not in valores:
            self.unmapped(hoja, row, columna)
            return None
        return valores[code]

    def valid(self, name, attributes, hoja, row, predio, contribuyente, relations=()):
        """attributes without the empty ones when they fit the model; None (and the row rejected) when not."""
        self.campos[name].update(k for k, v in attributes.items() if v is not None)
        problems = check(self.model, name, attributes) + [r for r in relations if r]
        if problems:
            self.report("rechazada", hoja, row["_fila"], predio, contribuyente, f"{name}: " + "; ".join(problems))
            return None
        return {k: v for k, v in attributes.items() if v is not None}

    # contribuyentes ----------------------------------------------------------

    def contribuyentes(self, rows):
        primeras = {}
        condiciones = defaultdict(set)
        for row in rows:
            code = codigo(row.get("codigo_contribuyente"))
            if code is None:
                continue
            primeras.setdefault(code, row)
            condiciones[code].add(codigo(row.get("condicion_predio")))
        documentos = {}  # numero -> the codigo that keeps it
        # the lowest code keeps a shared document: numeric codes by their value
        for code in sorted(primeras, key=lambda c: (len(c), c)):
            row = primeras[code]
            tipo, numero, motivo = documento(row.get("tipo_documento"), row.get("nro_documento"), self.codigos)
            if motivo == "codigo_sin_mapeo":
                self.unmapped("padron", row, "tipo_documento")
            elif motivo:
                self.report(motivo, "padron", row["_fila"], contribuyente=code,
                            detalle="DNI de menos de 8 dígitos: se rellena con ceros" if motivo == "documento_rellenado"
                            else "tiene tipo de documento y no número: SIN DOCUMENTO")
            if numero is not None and numero in documentos:
                self.report("documento_repetido", "padron", row["_fila"], contribuyente=code,
                            detalle=f"el número lo tiene el contribuyente {documentos[numero]}: se carga sin número")
                numero = None
            elif numero is not None:
                documentos[numero] = code
            attributes = self.contribuyente(row, code, tipo, numero, condiciones[code])
            valid = self.valid("contribuyente", attributes, "padron", row, None, code)
            if valid is not None:
                self.data.contribuyentes[code] = valid
                self.data.filas[("contribuyente", code)] = row["_fila"]

    def contribuyente(self, row, code, tipo, numero, condiciones):
        paterno, materno, nombres = (clean_text(row.get(k)) for k in ("apellido_paterno", "apellido_materno", "nombres"))
        nombre = clean_text(row.get("nombre_completo")) or clean_text(" ".join(x for x in (paterno, materno, nombres) if x))
        persona = tipo_persona(tipo, numero, nombre, condiciones, self.codigos)
        natural = persona != "JURIDICA"
        return {
            "codigo": code,
            "tipo_persona": persona,
            "tipo_contribuyente": "SUCESION INDIVISA" if persona == "SUCESION" else None,
            "tipo_documento": tipo,
            "numero_documento": numero,
            "nombre_completo": nombre,
            "apellido_paterno": paterno if natural else None,
            "apellido_materno": materno if natural else None,
            "nombres": nombres if natural else None,
            "razon_social": nombre if persona != "NATURAL" else None,
        }

    # predios and catalogs ----------------------------------------------------

    def predios(self, rows):
        for row in rows:
            code = codigo(row.get("codigo_predio"))
            self.catalogos(row)
            if code is None or ("predio", code) in self.data.filas or code in self.rechazados:
                continue
            attributes = self.predio(row, code)
            valid = self.valid("predio", attributes, "padron", row, code, None)
            if valid is None:
                self.rechazados.add(code)
                continue
            self.data.predios[code] = valid
            self.data.filas[("predio", code)] = row["_fila"]

    def predio(self, row, code):
        tipo_via = self.mapped("padron", row, "tipo_via") or self.codigos["tipo_via"]["por_defecto"]
        via, numero, manzana, lote = (clean_text(row.get(k)) for k in ("nombre_de_la_via", "numero", "manzana", "lote"))
        tipo_zona, zona = clean_text(row.get("tipo_de_unidad_urbana")), clean_text(row.get("nombre_de_unidad_urbana"))
        direccion = clean_text(row.get("direccion_del_predio_completa"))
        if direccion is None:
            partes = [tipo_via if tipo_via != "OTROS" else None, via, numero, manzana and f"MZ {manzana}",
                      lote and f"LT {lote}", tipo_zona, zona]
            direccion = clean_text(" ".join(p for p in partes if p)) or code
            self.report("direccion_compuesta", "padron", row["_fila"], code, None,
                        "sin direccion_del_predio_completa: se arma con la vía, el número, la manzana, el lote y la unidad urbana")
        return {
            "codigo": code,
            "direccion": direccion,
            "tipo_predio": self.mapped("padron", row, "tipo_predio"),
            "tipo_via": tipo_via,
            "via": via,
            "numero": numero,
            "manzana": manzana,
            "lote": lote,
            "tipo_zona": tipo_zona,
            "habilitacion_urbana": zona,
            **UBICACION,
            "codigo_via": codigo(row.get("codigo_de_via")),
            "codigo_unidad_urbana": codigo(row.get("codigo_de_unidad_urbana")),
            "codigo_hu_armonizacion": codigo(row.get("COD_HU_ARMONIZACION")),
            "codigo_via_armonizacion": codigo(row.get("COD_VIA_ARMONIZACION")),
        }

    def catalogos(self, row):
        """The row's vía and unidad urbana for the catalogs, by code: Ica gives one code to more than one name."""
        nombre = clean_text(row.get("nombre_de_la_via"))
        if nombre:
            tipo = self.codigos["tipo_via"]["valores"].get(codigo(row.get("tipo_de_via")), self.codigos["tipo_via"]["por_defecto"])
            via = {"codigo": codigo(row.get("codigo_de_via")), "tipo_via": tipo, "nombre": nombre, "ubigeo": UBIGEO}
            key = (via["codigo"], tipo, nombre, UBIGEO)
            if key not in self.data.vias:
                self.data.vias[key] = via
                self.data.filas[("via", key)] = row["_fila"]
        tipo, nombre = clean_text(row.get("tipo_de_unidad_urbana")), clean_text(row.get("nombre_de_unidad_urbana"))
        if tipo and nombre and tipo in self.model["enums"]["tipo_unidad_urbana"]:
            unidad = {"codigo": codigo(row.get("codigo_de_unidad_urbana")), "tipo_unidad_urbana": tipo, "nombre": nombre,
                      "ubigeo": UBIGEO}
            key = (unidad["codigo"], tipo, nombre, UBIGEO)
            if key not in self.data.unidades:
                self.data.unidades[key] = unidad
                self.data.filas[("unidad_urbana", key)] = row["_fila"]

    # declaraciones -----------------------------------------------------------

    def declaraciones(self, rows):
        # the predio's titulares, each once, in the sheet's order: a repeated row is rejected and is no titular
        grupos = defaultdict(dict)  # codigo_predio -> codigo_contribuyente -> its first row
        for row in rows:
            grupos[codigo(row.get("codigo_predio"))].setdefault(codigo(row.get("codigo_contribuyente")), row)
        rellenos = []
        for row in rows:
            predio, contribuyente = codigo(row.get("codigo_predio")), codigo(row.get("codigo_contribuyente"))
            titulares = list(grupos[predio])
            missing = [f"{nombre} rechazado o sin código" for nombre, ok in (
                ("predio", predio in self.data.predios), ("contribuyente", contribuyente in self.data.contribuyentes))
                if not ok]
            if grupos[predio][contribuyente] is not row:
                missing.append("DJ repetida (mismo predio y contribuyente)")
            try:
                attributes = self.declaracion(row, titulares.index(contribuyente), len(titulares))
            except (InvalidOperation, ValueError) as e:
                self.report("rechazada", "padron", row["_fila"], predio, contribuyente, f"declaracion_predial: {e}")
                continue
            try:
                attributes["fecha_adquisicion"] = fecha(row.get("fecha_adquisicion"))
                if attributes["fecha_adquisicion"] is None and clean_text(row.get("fecha_adquisicion")):
                    rellenos.append(row["_fila"])
            except ValueError as e:
                attributes["fecha_adquisicion"] = None
                self.report("fecha_invalida", "padron", row["_fila"], predio, contribuyente, f"{e}: se carga vacía")
            valid = self.valid("declaracion_predial", attributes, "padron", row, predio, contribuyente, missing)
            if valid is not None:
                self.data.declaraciones.append(Registro(row["_fila"], predio, contribuyente, valid))
            self.cuadre(row, predio, contribuyente)
        for predio, titulares in grupos.items():
            if predio is not None:
                self.condominio(predio, list(titulares.values()))
        for fila in rellenos:
            self.report("fecha_relleno", "padron", fila, detalle="fecha_adquisicion 1900-01-01: se carga vacía")

    def declaracion(self, row, posicion, titulares):
        porcentaje = porcentajes(titulares)[posicion]
        autoavaluo = clean_decimal(row.get("valor_predio"))
        valor_condominio, valor_afecto = valores_condominio(autoavaluo, porcentaje)
        uso = uso_ica(row.get("codigo_uso"), self.codigos, self.usos)
        if uso is None and codigo(row.get("codigo_uso")) is not None:
            self.unmapped("padron", row, "codigo_uso")
        return {
            "anio": self.anio,
            "secuencia_uso": SECUENCIA,
            "condicion_propiedad": condicion(row.get("condicion_predio"), titulares, self.codigos),
            "porcentaje_condominio": str(porcentaje),
            **(uso or {"clase_uso": None, "sub_clase_uso": None, "uso": None}),
            "area_terreno": clean_decimal(row.get("area_terreno")),
            "area_comun_terreno": clean_decimal(row.get("area_terreno_comun")),
            "area_construida": clean_decimal(row.get("area_construida")),
            "valor_autoavaluo": autoavaluo,
            "valor_condominio": valor_condominio,
            "deduccion": "0.00",
            "valor_afecto": valor_afecto,
            "arancel": clean_decimal(row.get("arancel")),
            "valor_terreno": clean_decimal(row.get("valor_terreno")),
            "valor_construccion": clean_decimal(row.get("valor_area_construida")),
            "valor_otras_instalaciones": clean_decimal(row.get("valor_otras_instalaciones")),
        }

    def cuadre(self, row, predio, contribuyente):
        partes = _sum([_decimal(row.get(k)) for k in ("valor_terreno", "valor_area_construida", "valor_otras_instalaciones")])
        total = _decimal(row.get("valor_predio"))
        if partes is not None and total is not None and abs(partes - total) > TOLERANCIA_VALOR:
            self.report("valor_predio_no_cuadra", "padron", row["_fila"], predio, contribuyente,
                        f"valor_predio {total} y terreno + construcción + otras instalaciones {partes}")

    def condominio(self, predio, grupo):
        fila = grupo[0]["_fila"]
        codes = [codigo(r.get("condicion_predio")) for r in grupo]
        condiciones = self.codigos["condicion_propiedad"]["valores"]
        if len(grupo) == 1:
            if condiciones.get(codes[0]) == CONDOMINO:
                self.report("condomino_solo", "padron", fila, predio, codigo(grupo[0].get("codigo_contribuyente")),
                            f"condición {codes[0]} (condómino) sin otros titulares en el padrón: se carga PROPIETARIO UNICO con 100 %")
            return
        valores = {_decimal(r.get("valor_predio")) for r in grupo}
        if len(valores) == 1:
            self.report("condominio_100_n", "padron", fila, predio, None,
                        f"{len(grupo)} titulares con el mismo valor_predio y sin %: 100/{len(grupo)} cada uno")
        else:
            self.report("condominio_valores_distintos", "padron", fila, predio, None,
                        f"{len(grupo)} titulares con valor_predio distintos ({len(valores)} valores): 100/{len(grupo)} cada uno, revisar")
        if any(condiciones.get(c) == PROPIETARIO_UNICO for c in codes):
            self.report("titulares_en_conflicto", "padron", fila, predio, None,
                        f"PROPIETARIO UNICO con otros titulares (condiciones {', '.join(sorted(set(c or '-' for c in codes)))})")

    # niveles -----------------------------------------------------------------

    def niveles(self, rows, padron):
        declaradas = {(d.codigo_predio, d.codigo_contribuyente) for d in self.data.declaraciones}
        en_padron = {}
        for r in padron:
            en_padron.setdefault((codigo(r.get("codigo_predio")), codigo(r.get("codigo_contribuyente"))), r)
        por_par = defaultdict(list)
        for row in rows:
            par = (codigo(row.get("codigo_predio")), codigo(row.get("codigo_contribuyente")))
            por_par[par].append(row)
            missing = [] if par in declaradas else [
                "sin DJ: el par predio-contribuyente no está en el padrón" if par not in en_padron else "su DJ fue rechazada"]
            try:
                attributes = self.nivel(row)
            except (InvalidOperation, ValueError) as e:
                self.report("rechazada", "construcciones", row["_fila"], *par, f"nivel_construccion: {e}")
                continue
            valid = self.valid("nivel_construccion", attributes, "construcciones", row, *par, relations=missing)
            if valid is not None:
                self.data.niveles.append(Registro(row["_fila"], *par, valid))
            self.formula(row, par)
        for par, secciones in por_par.items():
            if par in en_padron:
                self.suma(en_padron[par], par, secciones)

    def nivel(self, row):
        tipo_nivel, numero_piso = nivel(row.get("nivel"), self.codigos)
        anio = clean_text(row.get("ano_construccion"))
        return {
            "tipo_nivel": tipo_nivel,
            "numero_piso": numero_piso,
            "anio_construccion": int(Decimal(anio)) if anio else None,
            "material": self.mapped("construcciones", row, "material"),
            "estado_conservacion": self.mapped("construcciones", row, "estado_conservacion"),
            "clasificacion": self.mapped("construcciones", row, "clasificacion"),
            "area_construida": clean_decimal(row.get("area_construida_propia")),
            "area_comun": clean_decimal(row.get("area_construida_comun")),
            **{campo: clean_text(row.get(columna)) for campo, columna in LETRAS.items()},
            "valor_unitario": clean_decimal(row.get("valor_unitario")),
            "incremento": clean_decimal(row.get("incremento_5")),
            "porcentaje_depreciacion": clean_decimal(row.get("depreciacion")),
            "valor_total": clean_decimal(row.get("valor_total_nivel")),
        }

    def formula(self, row, par):
        unitario, incremento, depreciacion, area, total = (_decimal(row.get(k)) for k in (
            "valor_unitario", "incremento_5", "depreciacion", "area_construida_propia", "valor_total_nivel"))
        if None in (unitario, incremento, depreciacion, area, total):
            return
        calculado = (unitario + incremento) * (1 - depreciacion / CIEN) * area
        if abs(calculado - total) > TOLERANCIA_FORMULA:
            self.report("formula_nivel", "construcciones", row["_fila"], *par,
                        f"valor_total_nivel {total} y (valor_unitario + incremento) x (1 - depreciación) x área "
                        f"{calculado.quantize(CENTIMO, rounding=ROUND_HALF_UP)}")

    def suma(self, padron_row, par, secciones):
        """The padrón's construction against its niveles; a pair with an unreadable number is left out (its rows are
        rejected)."""
        detalles = []
        for columna, padron_columna, tolerancia, que in (
                ("valor_total_nivel", "valor_area_construida", TOLERANCIA_VALOR, "valor"),
                ("area_construida_propia", "area_construida", TOLERANCIA_AREA, "área")):
            niveles = _sum([_decimal(r.get(columna)) for r in secciones])
            declarado = _decimal(padron_row.get(padron_columna))
            if niveles is not None and declarado is not None and abs(niveles - declarado) > tolerancia:
                detalles.append(f"{que} de los niveles {niveles} y {padron_columna} {declarado}")
        if detalles:
            self.report("niveles_vs_padron", "padron", padron_row["_fila"], *par, "; ".join(detalles))

    # the end -----------------------------------------------------------------

    def finish(self):
        for (columna, valor), filas in sorted(self.sin_mapeo.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
            hoja, fila = self.primera[(columna, valor)]
            self.report("codigo_sin_mapeo", hoja, fila, detalle=f"{columna} '{valor}' no está en data/ica/codigos.json: {filas} filas")
        for name, campos in self.campos.items():
            for campo in unknown_fields(self.model, name, sorted(campos)):
                self.data.errores.append(f"{name}: el modelo no tiene el campo {campo}")
        return self.data


def build_dataset(padron, construcciones, anio, model, codigos, usos):
    """The two sheets' rows -> what to load and what to report. Pure: reads nothing, calls nothing."""
    builder = _Builder(anio, model, codigos, usos)
    builder.contribuyentes(padron)
    builder.predios(padron)
    builder.declaraciones(padron)
    builder.niveles(construcciones, padron)
    return builder.finish()


def limit_predios(padron, construcciones, limit):
    """The padrón rows of the first `limit` predios (each with all its titulares) and the niveles of their DJ."""
    predios = dict.fromkeys(codigo(r.get("codigo_predio")) for r in padron)
    keep = set(list(predios)[:limit])
    rows = [r for r in padron if codigo(r.get("codigo_predio")) in keep]
    pares = {(codigo(r.get("codigo_predio")), codigo(r.get("codigo_contribuyente"))) for r in rows}
    return rows, [r for r in construcciones if (codigo(r.get("codigo_predio")), codigo(r.get("codigo_contribuyente"))) in pares]


def write_report(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=REPORT_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


# ---------------------------------------------------------------------------
# load
# ---------------------------------------------------------------------------

def _text(value):
    return None if value is None else str(value)


def _firma(declaracion, attributes):
    """What tells a nivel from another of the same declaración: a nivel has no key of its own, and a piso may have
    several sections, so repeated ones are counted."""
    area = attributes.get("area_construida")
    return (str(declaracion), attributes.get("tipo_nivel"), _text(attributes.get("numero_piso")),
            _text(attributes.get("anio_construccion")), clean_decimal(_text(area)) if area is not None else None)


def _catalogo_key(attributes):
    """A vía or unidad urbana by its code, type, name and district: Ica gives one name to more than one code."""
    tipo = attributes.get("tipo_via") or attributes.get("tipo_unidad_urbana")
    return attributes.get("codigo"), tipo, attributes.get("nombre"), attributes.get("ubigeo")


def _catalogo(client, name, items, filas, workers):
    existing = {_catalogo_key(r["attributes"]) for r in client.list_all(name)}
    todo = [(k, filas[(name, k)], a) for k, a in items.items() if _catalogo_key(a) not in existing]
    new = post_all(client, name, todo, workers)
    print(f"{name}: {len(new)} created, {len(items) - len(todo)} skipped", flush=True)
    return len(new), len(items) - len(todo)


def load(client, data, anio, workers):
    """Creates what is missing, in dependency order. Returns (created, skipped). A contribuyente sent without its
    document goes to data.reporte."""
    created = skipped = 0
    for name, items in (("via", data.vias), ("unidad_urbana", data.unidades)):
        new, same = _catalogo(client, name, items, data.filas, workers)
        created, skipped = created + new, skipped + same

    records = client.list_all("contribuyente")
    contribuyentes = {r["attributes"].get("codigo"): r["id"] for r in records if r["attributes"].get("codigo")}
    documentos = {r["attributes"].get("numero_documento") for r in records} - {None}
    todo = []
    for code, attributes in data.contribuyentes.items():
        if code in contribuyentes:
            continue
        if attributes.get("numero_documento") in documentos:
            # numero_documento is unique: Core has it under another code (#125)
            attributes = {k: v for k, v in attributes.items() if k != "numero_documento"}
            data.reporte.append({"categoria": "documento_en_core", "hoja": "padron", "fila": data.filas[("contribuyente", code)],
                                 "codigo_predio": None, "codigo_contribuyente": code,
                                 "detalle": "Core ya tiene su número de documento con otro código: se envía sin número"})
        todo.append((code, data.filas[("contribuyente", code)], attributes))
    new = post_all(client, "contribuyente", todo, workers)
    contribuyentes.update(new)
    created, skipped = created + len(new), skipped + len(data.contribuyentes) - len(todo)
    print(f"contribuyente: {len(new)} created, {len(data.contribuyentes) - len(todo)} skipped", flush=True)
    sin_numero = sum(1 for r in data.reporte if r["categoria"] == "documento_en_core")
    if sin_numero:
        print(f"contribuyente: {sin_numero} sent sin número: Core has their document under another code", flush=True)

    predios = {r["attributes"].get("codigo"): r["id"] for r in client.list_all("predio")}
    todo = [(code, data.filas[("predio", code)], a) for code, a in data.predios.items() if code not in predios]
    new = post_all(client, "predio", todo, workers)
    predios.update(new)
    created, skipped = created + len(new), skipped + len(data.predios) - len(todo)
    print(f"predio: {len(new)} created, {len(data.predios) - len(todo)} skipped", flush=True)

    def key(predio_id, contribuyente_id):
        return str(predio_id), str(contribuyente_id), str(anio), SECUENCIA

    declaraciones = {
        key(r["attributes"].get("predio"), r["attributes"].get("contribuyente")): r["id"]
        for r in client.list_all("declaracion_predial", anio=anio) if r["attributes"].get("secuencia_uso") == SECUENCIA
    }
    todo = []
    for d in data.declaraciones:
        predio_id, contribuyente_id = predios[d.codigo_predio], contribuyentes[d.codigo_contribuyente]
        k = key(predio_id, contribuyente_id)
        if k not in declaraciones:
            todo.append((k, d.fila, {**d.attributes, "predio": predio_id, "contribuyente": contribuyente_id}))
    new = post_all(client, "declaracion_predial", todo, workers)
    declaraciones.update(new)
    created, skipped = created + len(new), skipped + len(data.declaraciones) - len(todo)
    print(f"declaracion_predial: {len(new)} created, {len(data.declaraciones) - len(todo)} skipped", flush=True)

    existing = Counter(_firma(r["attributes"].get("declaracion"), r["attributes"]) for r in client.list_all("nivel_construccion"))
    todo = []
    for i, n in enumerate(data.niveles):
        declaracion = declaraciones[key(predios[n.codigo_predio], contribuyentes[n.codigo_contribuyente])]
        firma = _firma(declaracion, n.attributes)
        if existing[firma]:
            existing[firma] -= 1
            continue
        todo.append((i, n.fila, {**n.attributes, "declaracion": declaracion}))
    new = post_all(client, "nivel_construccion", todo, workers)
    created, skipped = created + len(new), skipped + len(data.niveles) - len(todo)
    print(f"nivel_construccion: {len(new)} created, {len(data.niveles) - len(todo)} skipped", flush=True)
    return created, skipped


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_args(argv):
    p = argparse.ArgumentParser(description="Import Ica's padrón (PADRON_CONSTRUCCION.xlsx) into srtm's wasichai Core.")
    p.add_argument("--excel", required=True, help="path to PADRON_CONSTRUCCION.xlsx (sheets padron and construcciones)")
    p.add_argument("--anio", type=int, default=2026, help="year of the declarations (default 2026)")
    p.add_argument("--model", default=default_model_path())
    p.add_argument("--codigos", default=default_codigos_path(), help="Ica's code tables (default data/ica/codigos.json)")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read and transform only; call nothing")
    p.add_argument("--limit", type=int, default=None, help="only the first N predios, each with all its titulares")
    p.add_argument("--workers", type=int, default=4, help="parallel POSTs (default 4)")
    p.add_argument("--report", default=os.path.join(HERE, "reports", "padron_ica.csv"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    padron = read_xlsx(args.excel, "padron")
    construcciones = read_xlsx(args.excel, "construcciones")
    if args.limit:
        padron, construcciones = limit_predios(padron, construcciones, args.limit)
    data = build_dataset(padron, construcciones, args.anio, load_model(args.model), load_codigos(args.codigos),
                         read_usos_srtm(default_usos_path()))

    print(f"filas: {len(padron)} del padrón, {len(construcciones)} de construcciones")
    print(f"vías: {len(data.vias)}")
    print(f"unidades urbanas: {len(data.unidades)}")
    print(f"contribuyentes: {len(data.contribuyentes)}")
    print(f"predios: {len(data.predios)}")
    print(f"declaraciones: {len(data.declaraciones)}")
    print(f"niveles: {len(data.niveles)}")
    categorias = Counter(r["categoria"] for r in data.reporte)
    print(f"rechazadas: {categorias['rechazada']}")
    write_report(args.report, data.reporte)
    print(f"reporte: {len(data.reporte)} filas -> {args.report}")
    for categoria, n in sorted(categorias.items()):
        print(f"  {categoria}: {n}")

    if data.errores:
        for error in data.errores:
            print(f"error: {error}", file=sys.stderr)
        print("the model lacks fields the importer fills (apply #124's model.json): nothing was sent to Core", file=sys.stderr)
        return 2
    if args.dry_run:
        return 0

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        created, skipped = load(client, data, args.anio, args.workers)
    except LoadError as e:
        write_report(args.report, data.reporte)
        print(f"error POST /api/objects/{e.object_name}/records (fila {e.fila}) -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        write_report(args.report, data.reporte)
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    write_report(args.report, data.reporte)  # with what the load added
    print(f"done: {created} created, {skipped} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
