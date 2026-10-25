#!/usr/bin/env python3
"""Import Ica's padrón (PADRON_CONSTRUCCION.xlsx) into a wasichai Core running srtm's model.

The workbook has two sheets. `padron` has one row per (predio, contribuyente): a declaración predial whose predio and
contribuyente repeat across rows. `construcciones` has one row per section of a nivel, joined to its declaración by
(codigo_predio, codigo_contribuyente). Ica's codes (vía, documento, condición, uso, material…) are read through
data/ica/codigos.json, none of them confirmed by the municipalidad yet.

Creates, in this order and skipping what Core already has (so a second run creates nothing): the via and unidad_urbana
catalogs, contribuyente (by codigo), predio (by codigo), declaracion_predial (by predio, contribuyente, año and
secuencia 001) and nivel_construccion (by its declaración, nivel, año and área, counting repeated sections). A SAT code
that groups distinct predios (address, area or value differ) gives one predio per distinct predio, coded <codigo>-<n>,
each with the SAT code in codigo_origen (#207). Every record is checked against model.json first: a row that does not
fit is rejected to the report, with what hangs from it, and the rest is loaded. The report (--report, a CSV) also lists
what needs a look: condominios without a %, shared codes, titulares in conflict, unmapped codes, niveles that do not add
up to the padrón.

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
# a nivel's valor_total_nivel against round((valor_unitario + incremento) x (1 - RNT %), 2) x área: the rounding is
# the padrón's, so what is left is the last cent
TOLERANCIA_FORMULA = Decimal("0.05")
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


def default_depreciacion_path():
    return os.path.join(HERE, "data", "depreciacion-rnt-2016.csv")


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


def read_depreciacion(path):
    """The RNT's depreciation tables 01-04 (data/depreciacion-rnt-2016.csv, a copy of catastro-backend's, itself a copy
    of normativa's) as (tabla, material, estado) -> [(antigüedad hasta, %)], in order; no limit is 'más de 50'."""
    with open(path, encoding="utf-8") as f:
        rows = csv.DictReader(line for line in f if not line.startswith("#"))
        tablas = defaultdict(list)
        for r in rows:
            hasta = int(r["antiguedad_hasta"]) if r["antiguedad_hasta"] else None
            tablas[(r["tabla"], r["material"], r["estado_conservacion"])].append((hasta, Decimal(r["porcentaje"])))
    return {k: sorted(v, key=lambda t: float("inf") if t[0] is None else t[0]) for k, v in tablas.items()}


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


def _centimos(value):
    """A number of the xlsx to the cent; empty or unreadable is None."""
    try:
        text = clean_decimal(value)
    except InvalidOperation:
        return None
    return None if text is None else Decimal(text)


def identidad(row):
    """What makes two rows of a code the same predio (#207): the address (direccion_del_predio_completa, else the vía,
    número, manzana and lote, upper case with single spaces), area_terreno and valor_predio to the cent. A SAT
    condominio repeats them verbatim, so they are compared exactly."""
    direccion = clean_text(row.get("direccion_del_predio_completa"))
    if direccion is None:
        via, numero, manzana, lote = (clean_text(row.get(k)) for k in ("nombre_de_la_via", "numero", "manzana", "lote"))
        direccion = clean_text(" ".join(p for p in (via, numero, manzana and f"MZ {manzana}", lote and f"LT {lote}") if p))
    return (direccion.upper() if direccion else None, _centimos(row.get("area_terreno")),
            _centimos(row.get("valor_predio")))


def _orden(code):
    # the importer's lowest code: numeric codes by their value
    return len(code or ""), code or ""


def codigos_derivados(rows):
    """Each (SAT code, contribuyente) -> its srtm code, and the shared codes. A code whose titulares are all the same
    predio keeps its code; one that groups distinct predios gives `<code>-<n>` to each, n from 1 in the order of their
    lowest contribuyente code, and none keeps the bare code. A shared code is listed as {codigo, fila (its first row),
    djs, predios}."""
    primeras = defaultdict(dict)  # SAT code -> contribuyente -> its first row
    for row in rows:
        code = codigo(row.get("codigo_predio"))
        if code is not None:
            primeras[code].setdefault(codigo(row.get("codigo_contribuyente")), row)
    srtm, compartidos = {}, []
    for code, titulares in primeras.items():
        grupos = defaultdict(list)  # identidad -> its contribuyentes
        for contribuyente, row in titulares.items():
            grupos[identidad(row)].append(contribuyente)
        if len(grupos) == 1:
            srtm.update({(code, c): code for c in titulares})
            continue
        ordenados = sorted(grupos.values(), key=lambda cs: min(map(_orden, cs)))
        derivados = [f"{code}-{n}" for n in range(1, len(ordenados) + 1)]
        for derivado, contribuyentes in zip(derivados, ordenados):
            srtm.update({(code, c): derivado for c in contribuyentes})
        compartidos.append({"codigo": code, "fila": next(iter(titulares.values()))["_fila"], "djs": len(titulares),
                            "predios": derivados})
    return srtm, compartidos


# the regla para varios códigos de vía (#226): a predio whose rows name several, all of one value / not
MISMO_VALOR = "varios_codigos_mismo_valor"
VALORES_DISTINTOS = "varios_codigos_valores_distintos"
URBANO = "1"


def regla_varios_codigos(filas):
    """The regla para varios códigos de vía (#226) over the rows of one predio: (codigo_via, regla, cuantos, valores).

    The urbano rows (tipo_predio 1) with a codigo_de_via are its frentes (the SAT code goes by frente de vía: RM
    514-2025-EF/15). One code, or none: regla None and codigo_via the first row's, as always. Several, each with one
    arancel > 0 and the same for all: MISMO_VALOR, and codigo_via the most frequent (of a tie, the lowest). Otherwise
    (values that differ, or a code without one): VALORES_DISTINTOS, codigo_via the first row's, left as it is and
    marked (predio.varios_codigos_via) for review. cuantos: code -> its rows; valores: code -> its arancel values."""
    primera = codigo(filas[0].get("codigo_de_via")) if filas else None
    cuantos, valores = Counter(), defaultdict(set)
    for fila in filas:
        cod_via = codigo(fila.get("codigo_de_via"))
        if cod_via is None or codigo(fila.get("tipo_predio")) != URBANO:
            continue
        cuantos[cod_via] += 1
        valor = _arancel(fila.get("arancel"))
        if valor is not None and valor > 0:
            valores[cod_via].add(valor)
    if len(cuantos) < 2:
        return primera, None, cuantos, valores
    if all(len(valores[c]) == 1 for c in cuantos) and len(set().union(*(valores[c] for c in cuantos))) == 1:
        return sorted(cuantos.items(), key=lambda kv: (-kv[1], _orden(kv[0])))[0][0], MISMO_VALOR, cuantos, valores
    return primera, VALORES_DISTINTOS, cuantos, valores


def _arancel(value):
    """The padrón's arancel as Decimal with two decimals (the xlsx carries float noise); None when blank or not a number."""
    text = clean_text(value)
    if text is None:
        return None
    try:
        return Decimal(text).quantize(CENTIMO, rounding=ROUND_HALF_UP)
    except InvalidOperation:
        return None


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


# the model's material and estado as the RNT writes them
RNT_MATERIAL = {"CONCRETO": "Concreto", "LADRILLO": "Ladrillo", "ADOBE": "Liviano/Adobe"}
RNT_ESTADO = {"MUY BUENO": "Muy Bueno", "BUENO": "Bueno", "REGULAR": "Regular", "MALO": "Malo"}


def depreciacion_rnt(tablas, clasificacion, material, estado, antiguedad):
    """The RNT's % for a nivel: Ica's clasificación 1-4 is the RNT's table 01-04. None when the RNT has no cell (a
    clasificación 0, a «*» cell the perito sets, a material it does not list)."""
    filas = tablas.get((f"0{clasificacion}", RNT_MATERIAL.get(material), RNT_ESTADO.get(estado)), [])
    return next((pct for hasta, pct in filas if hasta is None or antiguedad <= hasta), None)


def valor_total_nivel(unitario, incremento, depreciacion, area):
    """A nivel's total as the padrón computes it: the depreciated unit value rounded to the cent, times the área."""
    depreciado = ((unitario + incremento) * (1 - depreciacion / CIEN)).quantize(CENTIMO, rounding=ROUND_HALF_UP)
    return (depreciado * area).quantize(CENTIMO, rounding=ROUND_HALF_UP)


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
    """A declaración or a nivel: its row, the (predio, contribuyente) it belongs to and its attributes. codigo_predio is
    the srtm code, the SAT code or one derived from it (#207)."""
    fila: int
    codigo_predio: str
    codigo_contribuyente: str
    attributes: dict


@dataclass
class Dataset:
    vias: dict = field(default_factory=dict)  # (codigo, tipo, nombre, ubigeo) -> attributes
    unidades: dict = field(default_factory=dict)
    contribuyentes: dict = field(default_factory=dict)  # codigo -> attributes
    predios: dict = field(default_factory=dict)  # srtm codigo -> attributes
    declaraciones: list = field(default_factory=list)
    niveles: list = field(default_factory=list)
    filas: dict = field(default_factory=dict)  # ("contribuyente" | "predio", codigo) -> its first row
    reporte: list = field(default_factory=list)
    compartidos: list = field(default_factory=list)  # the SAT codes of distinct predios, as codigos_derivados lists them
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
    def __init__(self, anio, model, codigos, usos, depreciacion):
        self.anio = anio
        self.depreciacion = depreciacion
        self.model = model
        self.codigos = codigos
        self.usos = usos
        self.data = Dataset()
        self.sin_mapeo = Counter()  # (columna, valor) -> rows
        self.primera = {}  # (columna, valor) -> (hoja, fila)
        self.campos = defaultdict(set)  # object -> every attribute it was given
        self.rechazados = set()  # predios that do not fit the model
        self.srtm = {}  # (SAT code, contribuyente) -> srtm code; None when its group is not loaded

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
        for code in sorted(primeras, key=_orden):
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

    def derivados(self, rows):
        """The srtm code of every row (#207). A derived code the padrón already has as a code of its own is not loaded:
        its predio, DJ and niveles go to the report."""
        self.srtm, self.data.compartidos = codigos_derivados(rows)
        propios = {codigo(r.get("codigo_predio")) for r in rows}
        for c in self.data.compartidos:
            self.report("codigo_compartido", "padron", c["fila"], c["codigo"], None,
                        f"{c['djs']} DJ en {len(c['predios'])} predios distintos: {c['predios'][0]} a {c['predios'][-1]}")
            for derivado in c["predios"]:
                if derivado in propios:
                    self.report("codigo_derivado_repetido", "padron", c["fila"], c["codigo"], None,
                                f"el código derivado {derivado} ya es un código del padrón: ese predio no se carga, ni sus DJ "
                                "ni sus niveles")
                    self.srtm.update({par: None for par, code in self.srtm.items() if par[0] == c["codigo"] and code == derivado})

    def par(self, row):
        """The row's (SAT code, contribuyente) and its srtm code."""
        par = codigo(row.get("codigo_predio")), codigo(row.get("codigo_contribuyente"))
        return par, self.srtm.get(par)

    def predios(self, rows):
        filas_de = defaultdict(list)  # srtm code -> its rows, for the regla para varios códigos de vía
        for row in rows:
            code = self.par(row)[1]
            if code is not None:
                filas_de[code].append(row)
        for row in rows:
            (origen, _), code = self.par(row)
            self.catalogos(row)
            if code is None or ("predio", code) in self.data.filas or code in self.rechazados:
                continue
            attributes = self.predio(row, code, origen)
            cod_via, regla, cuantos, _ = regla_varios_codigos(filas_de[code])
            attributes["codigo_via"] = cod_via
            if regla == VALORES_DISTINTOS:
                attributes["varios_codigos_via"] = True
            if regla is not None:
                self.report(regla, "padron", row["_fila"], code, None,
                            f"{len(cuantos)} códigos de vía: " + (f"toma {cod_via}, el más frecuente" if regla == MISMO_VALOR
                                                                 else "valores distintos, no toma el arancel de su vía"))
            valid = self.valid("predio", attributes, "padron", row, code, None)
            if valid is None:
                self.rechazados.add(code)
                continue
            self.data.predios[code] = valid
            self.data.filas[("predio", code)] = row["_fila"]

    def predio(self, row, code, origen):
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
            "codigo_origen": origen,
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
        grupos = defaultdict(dict)  # srtm codigo_predio -> codigo_contribuyente -> its first row
        for row in rows:
            (_, contribuyente), code = self.par(row)
            grupos[code].setdefault(contribuyente, row)
        rellenos = []
        for row in rows:
            (origen, contribuyente), code = self.par(row)
            predio = code or origen  # what the report names
            titulares = list(grupos[code])
            missing = [f"{nombre} rechazado o sin código" for nombre, ok in (
                ("predio", code in self.data.predios), ("contribuyente", contribuyente in self.data.contribuyentes))
                if not ok]
            if grupos[code][contribuyente] is not row:
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
                self.data.declaraciones.append(Registro(row["_fila"], code, contribuyente, valid))
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
        # the titulares of one srtm predio share its address, area and value: a code of distinct predios was split
        self.report("condominio_100_n", "padron", fila, predio, None,
                    f"{len(grupo)} titulares del mismo predio y sin %: 100/{len(grupo)} cada uno")
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
            par, code = self.par(row)
            nombrado = (code or par[0], par[1])  # what the report names
            por_par[par].append(row)
            missing = [] if (code, par[1]) in declaradas else [
                "sin DJ: el par predio-contribuyente no está en el padrón" if par not in en_padron else "su DJ fue rechazada"]
            try:
                attributes = self.nivel(row)
            except (InvalidOperation, ValueError) as e:
                self.report("rechazada", "construcciones", row["_fila"], *nombrado, f"nivel_construccion: {e}")
                continue
            valid = self.valid("nivel_construccion", attributes, "construcciones", row, *nombrado, relations=missing)
            if valid is not None:
                self.data.niveles.append(Registro(row["_fila"], *nombrado, valid))
            self.formula(row, nombrado, attributes)
        for par, secciones in por_par.items():
            if par in en_padron:
                self.suma(en_padron[par], (self.srtm.get(par) or par[0], par[1]), secciones)

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

    def formula(self, row, par, attributes):
        """valor_total_nivel against the RNT's depreciation for the nivel's age (anio - año de construcción): the
        padrón's depreciacion column is often a tramo behind and is loaded as it comes, not taken as the truth. Without
        an RNT cell, the column is used."""
        unitario, incremento, columna, area, total = (_decimal(row.get(k)) for k in (
            "valor_unitario", "incremento_5", "depreciacion", "area_construida_propia", "valor_total_nivel"))
        if None in (unitario, incremento, columna, area, total) or attributes.get("anio_construccion") is None:
            return
        rnt = depreciacion_rnt(self.depreciacion, codigo(row.get("clasificacion_construccion")), attributes.get("material"),
                               attributes.get("estado_conservacion"), self.anio - attributes["anio_construccion"])
        if rnt is not None and rnt != columna:
            self.report("depreciacion_vs_rnt", "construcciones", row["_fila"], *par,
                        f"depreciacion {columna} y RNT {rnt} (antigüedad {self.anio - attributes['anio_construccion']}): "
                        "se carga la del padrón")
        calculado = valor_total_nivel(unitario, incremento, columna if rnt is None else rnt, area)
        if abs(calculado - total) > TOLERANCIA_FORMULA:
            fuente = f"RNT {rnt}" if rnt is not None else f"depreciacion del padrón {columna}, sin celda del RNT"
            self.report("formula_nivel", "construcciones", row["_fila"], *par,
                        f"valor_total_nivel {total} y round((valor_unitario + incremento) x (1 - %), 2) x área {calculado} "
                        f"con {fuente}")

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


def build_dataset(padron, construcciones, anio, model, codigos, usos, depreciacion):
    """The two sheets' rows -> what to load and what to report. Pure: reads nothing, calls nothing."""
    builder = _Builder(anio, model, codigos, usos, depreciacion)
    builder.contribuyentes(padron)
    builder.derivados(padron)
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
    document goes to data.reporte, and so does a shared code whose bare predio Core already has (#207): its derived
    predios, their DJ and their niveles are not sent."""
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
    # a load before #207 left the bare code with its DJ at 100/n: the derived predios would sit next to it until the
    # migration moves them
    en_core = [c for c in data.compartidos if c["codigo"] in predios]
    origenes = {c["codigo"] for c in en_core}
    fuera = {code for code, a in data.predios.items() if a.get("codigo_origen") in origenes}
    for c in en_core:
        data.reporte.append({"categoria": "codigo_compartido_en_core", "hoja": "padron", "fila": c["fila"],
                             "codigo_predio": c["codigo"], "codigo_contribuyente": None,
                             "detalle": f"Core ya tiene el predio {c['codigo']}: no se cargan {c['predios'][0]} a "
                                        f"{c['predios'][-1]} ni sus {c['djs']} DJ ni sus niveles, hasta migrarlo"})
    if en_core:
        print(f"predio: {len(en_core)} shared codes Core already has: skipped with their {len(fuera)} predios, DJ and "
              "niveles", flush=True)
    data_predios = {code: a for code, a in data.predios.items() if code not in fuera}
    todo = [(code, data.filas[("predio", code)], a) for code, a in data_predios.items() if code not in predios]
    new = post_all(client, "predio", todo, workers)
    predios.update(new)
    created, skipped = created + len(new), skipped + len(data_predios) - len(todo)
    print(f"predio: {len(new)} created, {len(data_predios) - len(todo)} skipped", flush=True)
    data_declaraciones = [d for d in data.declaraciones if d.codigo_predio not in fuera]
    data_niveles = [n for n in data.niveles if n.codigo_predio not in fuera]

    def key(predio_id, contribuyente_id):
        return str(predio_id), str(contribuyente_id), str(anio), SECUENCIA

    declaraciones = {
        key(r["attributes"].get("predio"), r["attributes"].get("contribuyente")): r["id"]
        for r in client.list_all("declaracion_predial", anio=anio) if r["attributes"].get("secuencia_uso") == SECUENCIA
    }
    todo = []
    for d in data_declaraciones:
        predio_id, contribuyente_id = predios[d.codigo_predio], contribuyentes[d.codigo_contribuyente]
        k = key(predio_id, contribuyente_id)
        if k not in declaraciones:
            todo.append((k, d.fila, {**d.attributes, "predio": predio_id, "contribuyente": contribuyente_id}))
    new = post_all(client, "declaracion_predial", todo, workers)
    declaraciones.update(new)
    created, skipped = created + len(new), skipped + len(data_declaraciones) - len(todo)
    print(f"declaracion_predial: {len(new)} created, {len(data_declaraciones) - len(todo)} skipped", flush=True)

    existing = Counter(_firma(r["attributes"].get("declaracion"), r["attributes"]) for r in client.list_all("nivel_construccion"))
    todo = []
    for i, n in enumerate(data_niveles):
        declaracion = declaraciones[key(predios[n.codigo_predio], contribuyentes[n.codigo_contribuyente])]
        firma = _firma(declaracion, n.attributes)
        if existing[firma]:
            existing[firma] -= 1
            continue
        todo.append((i, n.fila, {**n.attributes, "declaracion": declaracion}))
    new = post_all(client, "nivel_construccion", todo, workers)
    created, skipped = created + len(new), skipped + len(data_niveles) - len(todo)
    print(f"nivel_construccion: {len(new)} created, {len(data_niveles) - len(todo)} skipped", flush=True)
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
                         read_usos_srtm(default_usos_path()), read_depreciacion(default_depreciacion_path()))

    print(f"filas: {len(padron)} del padrón, {len(construcciones)} de construcciones")
    print(f"vías: {len(data.vias)}")
    print(f"unidades urbanas: {len(data.unidades)}")
    print(f"contribuyentes: {len(data.contribuyentes)}")
    print(f"predios: {len(data.predios)}")
    print(f"declaraciones: {len(data.declaraciones)}")
    print(f"niveles: {len(data.niveles)}")
    print(f"códigos compartidos: {len(data.compartidos)} ({sum(len(c['predios']) for c in data.compartidos)} predios, "
          f"{sum(c['djs'] for c in data.compartidos)} DJ)")
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
