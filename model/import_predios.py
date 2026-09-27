#!/usr/bin/env python3
"""Import the predios Excel (CODIGO DE PREDIOS AL <año>.xlsx) into a wasichai Core running srtm's model.

One Excel row is one declaration: a contributor on a predio. A row with no codigo_predio is a
co-owner of the predio in the row above. Creates contribuyente, predio and declaracion_predial
records through the REST API, skipping what already exists, so a second run creates nothing.

Stdlib only. See README.md.
"""
import argparse
import csv
import json
import os
import re
import sys
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from xml.etree import ElementTree as ET

from core_client import Client, CoreError

HERE = os.path.dirname(os.path.abspath(__file__))

XLSX_MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
XLSX_REL = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
XLSX_PKG_REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"

# Excel code -> model enum option
TIPO_DOCUMENTO = {"00": "SIN DOCUMENTO", "01": "DNI", "04": "CARNET DE EXTRANJERIA", "06": "RUC", "08": "SUCESION"}
CONDICION_PREDIO = {"PU": "URBANO", "PR": "RUSTICO"}
# Core's enum options allow no commas and at most 64 characters
CLASIFICACION = {
    "TIENDAS,DEPOSITOS,CENTROS DE RECREACION O ESPARCIMIENTO ,CLUB SOCIALES O INSTITUCIONES":
        "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES",
    "CLINICA,HOSPITALES,CINE,INDUSTRIAS,COLEGIOS,TALLER": "CLINICAS HOSPITALES CINES INDUSTRIAS COLEGIOS TALLERES",
}
# the padrón's grupo de uso -> the srtm's clase, sub clase and uso (data/usos_predio.csv). the ten grupos are its ten
# clases; only the residential one says more. the portal asks for what is missing when the declaración is edited
USOS_DEL_PADRON = {
    "RESIDENCIAL - CASA HABITACION": ("RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"),
    **{clase: (clase, None, None) for clase in (
        "COMERCIAL", "INDUSTRIA", "RECREACIONAL", "EQUIPAMIENTO URBANO", "INSTITUCIONAL", "TERRENO", "DESOCUPADO",
        "BIENES COMUNES",
    )},
    # the clase 09: GARAGE in the SNCP's codifier the srtm's catalog comes from
    "ESTACIONAMIENTO": ("GARAGE", None, None),
}
USO_FIELDS = ("clase_uso", "sub_clase_uso", "uso")

# words that start a surname without being one: DE LA CRUZ is one surname
SURNAME_PARTICLES = frozenset({"DE", "DEL", "LA", "LAS", "LOS", "SAN", "SANTA", "MC", "MAC", "VAN", "VON", "DI", "DA"})
WIDOW = frozenset({"VDA.", "VDA", "VIUDA"})
# first word of a name that is an organisation, not a person
INSTITUTIONAL = frozenset({
    "ASOCIACION", "ASOC.", "INSTITUCION", "IGLESIA", "MUNICIPALIDAD", "COMUNIDAD", "COOPERATIVA", "CLUB",
    "COMITE", "PATRIMONIO", "EMPRESA", "SOCIEDAD", "CONGREGACION", "PARROQUIA", "FUNDACION", "COLEGIO",
    "MINISTERIO", "GOBIERNO", "JUNTA", "ORGANIZACION", "CONSTRUCTORA", "INMOBILIARIA", "INVERSIONES",
    "CORPORACION", "CONSORCIO", "UNIVERSIDAD", "INSTITUTO", "ESCUELA", "MISION", "CENTRO",
})
LEGAL_SUFFIX = re.compile(r"(\bS\.A\.C\.?|\bSAC|\bS\.R\.L\.?|\bSRL|\bE\.I\.R\.L\.?|\bEIRL|\bS\.A\.)$")
SUCESION = re.compile(r"^(SUCESION|SUCESIÓN|SUC\.)")

ADDRESS_TAG = re.compile(r"(Nro\.Alt|Nro|Mz|Lt|Block|Dpto|Int|Km)\.:\s*(\S+)")
DOMICILIO_SUFFIX = re.compile(r"^(?P<dir>.*?)\s*,?\s*Dist\.\s*(?P<dist>.*?)\s+Prov\.\s*(?P<prov>.*?)\s+Dpto\.\s*(?P<dpto>.*?)\s*$")
SECTOR_MANZANA = re.compile(r"^\s*(\S+)\s*-\s*(\S+)\s*$")

# prefix as written in the padrón -> the model's option. the longest wins, so "ASOCIACION DE VIVIENDA" over
# "ASOCIACION". anything else keeps its whole text as the name: a vía under OTROS, a unidad urbana with no type. the
# abbreviations are also the ones the portal writes an address with (Reglas.kt in srtm-backend, forms/direccion.ts in
# srtm-ui): each must be read back
TIPOS_VIA = [
    ("PROLONGACION", "PROLONGACION"), ("CARROZABLE", "CARROZABLE"), ("CARRETERA", "CARRETERA"), ("AVENIDA", "AVENIDA"),
    ("MALECON", "MALECON"), ("ALAMEDA", "ALAMEDA"), ("PASAJE", "PASAJE"), ("TROCHA", "TROCHA"), ("CAMINO", "CAMINO"),
    ("JIRON", "JIRON"), ("CALLE", "CALLE"), ("PSJE.", "PASAJE"), ("PROL.", "PROLONGACION"), ("CARR.", "CARRETERA"),
    ("AV.", "AVENIDA"), ("JR.", "JIRON"), ("CA.", "CALLE"),
    # the padrón's misspellings of CARROZABLE
    ("CACARROZABLE", "CARROZABLE"), ("CORRAZABLE", "CARROZABLE"), ("CORROZABLE", "CARROZABLE"), ("CARROZBLE", "CARROZABLE"),
]


def read_tipos_unidad_urbana(path=os.path.join(HERE, "data", "tipos_unidad_urbana.csv")):
    """The srtm's tipos de unidad urbana, the catastro fiscal's TIPO_UU domain: one row per type with its codigo, its
    nombre (the model's option) and its ABREV_UU abreviatura (the one the portal writes an address with)."""
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


# the padrón's words for a unidad urbana the srtm names otherwise: an ANEXO is a CENTRO POBLADO, a HABILITACION URBANA
# an URBANIZACION and a CENTRO URBANO INFORMAL a POSESION INFORMAL, unless the name after them starts with its own type
# (split_tipo)
PALABRAS_DEL_PADRON = {
    "ANEXO": "CENTRO POBLADO", "HABILITACION URBANA": "URBANIZACION", "HABILITACION": "URBANIZACION",
    "CENTRO URBANO INFORMAL": "POSESION INFORMAL",
}


def _tipos_unidad_urbana(tipos):
    """Every type whole and abbreviated, the padrón's words, and AA.VV.: the ASOCIACION DE VIVIENDA the portal wrote
    before ABREV_UU. ASOC.VIS., which 48 and 53 share, is read as the first the file lists: ASOCIACION DE VIVIENDA DE
    INTERES SOCIAL."""
    abreviaturas = {}
    for t in tipos:
        abreviaturas.setdefault(t["abreviatura"], t["nombre"])
    return [
        *((t["nombre"], t["nombre"]) for t in tipos), *abreviaturas.items(), *PALABRAS_DEL_PADRON.items(),
        ("AA.VV.", "ASOCIACION DE VIVIENDA"),
    ]


TIPOS_UNIDAD_URBANA = _tipos_unidad_urbana(read_tipos_unidad_urbana())

# the padrón numbers the usos of a predio with three digits: 001, 002...
SECUENCIA_WIDTH = 3

PROGRESS_EVERY = 500


# ---------------------------------------------------------------------------
# xlsx
# ---------------------------------------------------------------------------

def _cell_value(cell, shared):
    kind = cell.get("t")
    if kind == "inlineStr":
        node = cell.find(f"{XLSX_MAIN}is")
        return "".join(t.text or "" for t in node.iter(f"{XLSX_MAIN}t")) if node is not None else None
    v = cell.find(f"{XLSX_MAIN}v")
    if v is None or v.text is None:
        return None
    if kind == "s":
        return shared[int(v.text)]
    return v.text


def read_xlsx(path, sheet=None):
    """Rows of one sheet as dicts keyed by the header row, plus `_fila` (the Excel row number).

    Stdlib only: an xlsx is a zip of xml. The first sheet unless `sheet` names another.
    """
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        shared = []
        if "xl/sharedStrings.xml" in names:
            for si in ET.fromstring(z.read("xl/sharedStrings.xml")).iter(f"{XLSX_MAIN}si"):
                shared.append("".join(t.text or "" for t in si.iter(f"{XLSX_MAIN}t")))
        workbook = ET.fromstring(z.read("xl/workbook.xml"))
        rels = {r.get("Id"): r.get("Target") for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels")).iter(f"{XLSX_PKG_REL}Relationship")}
        sheets = workbook.find(f"{XLSX_MAIN}sheets")
        chosen = next((s for s in sheets if sheet is None or s.get("name") == sheet), None)
        if chosen is None:
            raise ValueError(f"sheet '{sheet}' not found in {path}")
        target = rels[chosen.get(f"{XLSX_REL}id")]
        sheet_path = target.lstrip("/") if target.startswith("/") else f"xl/{target}"

        header = None
        rows = []
        with z.open(sheet_path) as f:
            for _, el in ET.iterparse(f):
                if el.tag != f"{XLSX_MAIN}row":
                    continue
                values = {}
                for cell in el.findall(f"{XLSX_MAIN}c"):
                    column = re.match(r"[A-Z]+", cell.get("r")).group()
                    values[column] = _cell_value(cell, shared)
                number = int(el.get("r"))
                el.clear()
                if header is None:
                    header = {col: (name or "").strip() for col, name in values.items() if name}
                    continue
                if not any(v not in (None, "") for v in values.values()):
                    continue
                row = {name: values.get(col) for col, name in header.items()}
                row["_fila"] = number
                rows.append(row)
        return rows


# ---------------------------------------------------------------------------
# cleaning (pure)
# ---------------------------------------------------------------------------

def clean_text(value):
    """Collapsed whitespace; empty or blank is None (Core answers 400 to "" on non-text fields)."""
    if value is None:
        return None
    text = " ".join(str(value).split())
    return text or None


def clean_decimal(value):
    """Two decimals, as text: the Excel carries float noise (613.82000000000005)."""
    text = clean_text(value)
    if text is None:
        return None
    return str(Decimal(text).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def clean_int(value):
    text = clean_text(value)
    if text is None:
        return None
    return int(Decimal(text))


def split_sector_manzana(value):
    match = SECTOR_MANZANA.match(value or "")
    return (match.group(1), match.group(2)) if match else (None, None)


def _tag_value(value):
    return None if value in (".", "-") else value


def parse_address(value):
    """`<vía> [Nro.: n] [Mz.: x] [Lt.: y] [Block/Dpto/Int/Km…] <habilitación urbana>`."""
    text = clean_text(value) or ""
    tags = list(ADDRESS_TAG.finditer(text))
    if not tags:
        return {"via": text or None, "numero": None, "manzana": None, "lote": None, "kilometro": None, "habilitacion_urbana": None}
    found = {}
    for m in tags:
        found.setdefault(m.group(1), _tag_value(m.group(2)))
    return {
        "via": text[:tags[0].start()].strip() or None,
        "numero": found.get("Nro"),
        "manzana": found.get("Mz"),
        "lote": found.get("Lt"),
        "kilometro": found.get("Km"),
        "habilitacion_urbana": text[tags[-1].end():].strip() or None,
    }


def find_tipo(text, tipos, anywhere=False):
    """The type split_tipo takes from text, as (where it starts, prefix, type, name); None when there is none. With
    anywhere, a type written whole (or a word of the padrón) comes before an abbreviation: the padrón writes its types
    whole, and its lot's leftovers may read like one ('LOT 2A CENTRO POBLADO X' is a CENTRO POBLADO)."""
    best = None
    for prefix, tipo in tipos:
        pattern = re.compile(r"(?:^|(?<=[\s(-]))" + re.escape(prefix) + (r"(?=\s)" if not prefix.endswith(".") else r""))
        match = pattern.search(text) if anywhere else pattern.match(text)
        if not match:
            continue
        name = text[match.end():].strip(" -")
        if not name:
            continue
        abreviatura = anywhere and prefix != tipo and prefix not in PALABRAS_DEL_PADRON
        candidate = (abreviatura, match.start(), -len(prefix), prefix, tipo, name)
        if best is None or candidate < best:
            best = candidate
    return None if best is None else (best[1], best[3], best[4], best[5])


def split_tipo(text, tipos, default="OTROS", anywhere=False):
    """'AVENIDA LOS OLIVOS' -> ('AVENIDA', 'LOS OLIVOS'). The type must be a whole word followed by a name.
    anywhere: the type may come after leftovers of the lot ('03-B CERCADO III MESETA'), which are dropped;
    the earliest type wins, the longest on a tie. No type found: the whole text is the name, under default.
    A word of the padrón (PALABRAS_DEL_PADRON) yields to a type its name starts with, written whole."""
    found = find_tipo(text, tipos, anywhere)
    if found is None:
        return default, text
    _, prefix, tipo, name = found
    propio = find_tipo(name, [(p, t) for p, t in tipos if p == t]) if prefix in PALABRAS_DEL_PADRON else None
    return (propio[2], propio[3]) if propio else (tipo, name)


def split_ubicacion(address):
    """parse_address's vía and habilitación urbana with their type split off, as the srtm's ubicación keeps them:
    'JIRON LIMA' is tipo_via JIRON, via LIMA; '03-B CERCADO III MESETA' is tipo_zona CERCADO, habilitacion_urbana
    III MESETA (the lot's leftovers dropped). What is missing stays missing, and a zona of no type has none."""
    out = dict(address)
    if address.get("via"):
        out["tipo_via"], out["via"] = split_tipo(address["via"], TIPOS_VIA)
    if address.get("habilitacion_urbana"):
        out["tipo_zona"], out["habilitacion_urbana"] = split_zona(address["habilitacion_urbana"])
    return out


def split_zona(text):
    """A habilitación of the padrón as (tipo de unidad urbana, name): 'ANEXO - CENTRO POBLADO MIRICHARO' is (CENTRO
    POBLADO, MIRICHARO), '03-B CERCADO III MESETA' (CERCADO, III MESETA); one of no type is (None, the whole text)."""
    return split_tipo(text, TIPOS_UNIDAD_URBANA, default=None, anywhere=True)


def uso_del_padron(grupo):
    """A grupo de uso of the padrón as the declaración stores it: {clase_uso, sub_clase_uso, uso}. None for anything
    else (an srtm uso included)."""
    usos = USOS_DEL_PADRON.get(grupo)
    return dict(zip(USO_FIELDS, usos)) if usos else None


def secuencia_uso(value):
    """The padrón's three digits: '1' is '001', none is the first. Anything but a number stays as written."""
    text = clean_text(value) or "1"
    return text.zfill(SECUENCIA_WIDTH) if text.isdigit() else text


def parse_domicilio(value):
    """Splits the `, Dist. X Prov. Y Dpto. Z` suffix off the fiscal address."""
    text = clean_text(value)
    match = DOMICILIO_SUFFIX.match(text or "")
    if not match:
        return {"domicilio_fiscal": text, "domicilio_distrito": None, "domicilio_provincia": None, "domicilio_departamento": None}
    return {
        "domicilio_fiscal": match.group("dir").rstrip(" ,") or None,
        "domicilio_distrito": match.group("dist") or None,
        "domicilio_provincia": match.group("prov") or None,
        "domicilio_departamento": match.group("dpto") or None,
    }


def classify_person(tipo_doc, name):
    text = clean_text(name) or ""
    if tipo_doc == "06":
        return "JURIDICA"
    if tipo_doc == "08" or SUCESION.match(text):
        return "SUCESION"
    if text.split(" ")[0] in INSTITUTIONAL or LEGAL_SUFFIX.search(text):
        return "JURIDICA"
    return "NATURAL"


def _take_surname(tokens, i, reserve=1):
    """Particles plus the word after them: DE LA CRUZ. Returns (surname, next index).

    Leaves `reserve` tokens alone: in AGUIRRE SANTA ENRIQUE, SANTA is the surname, not a particle.
    """
    if i >= len(tokens):
        return None, i
    j = i
    while j < len(tokens) - 1 - reserve and tokens[j] in SURNAME_PARTICLES:
        j += 1
    return " ".join(tokens[i:j + 1]), j + 1


def _starts_married(tokens, i):
    """VDA. DE X, or DE X with names still after it (FLORES DE TOSCANO HAYDEE)."""
    if i >= len(tokens):
        return False
    if tokens[i] in WIDOW:
        return True
    if tokens[i] == "DE":
        _, end = _take_surname(tokens, i)
        return end < len(tokens)
    return False


def _take_married(tokens, i):
    start = i
    if tokens[i] in WIDOW:
        i += 1
    if i < len(tokens) and tokens[i] == "DE":
        # a widow's surname takes what it needs; no names left marks the split as doubtful
        _, i = _take_surname(tokens, i, reserve=0)
    return " ".join(tokens[start:i]), i


def split_name(full):
    """`PATERNO MATERNO NOMBRES` -> (paterno, materno, nombres, reason or None).

    Best effort: the Excel keeps the full name in one column. A married name (VDA. DE X, DE X)
    stays with the materno. `.` stands for a missing materno. A non-None reason marks a split
    worth a look by hand; nombre_completo keeps the original either way.
    """
    text = clean_text(full) or ""
    tokens = text.split(" ") if text else []
    reasons = []
    if "," in text:
        reasons.append("tiene coma")
    if "Y" in tokens:
        reasons.append("tiene ' Y '")
    if len(tokens) < 3:
        reasons.append("menos de 3 palabras")

    paterno, i = _take_surname(tokens, 0)
    materno = None
    if i < len(tokens):
        if tokens[i] == ".":
            i += 1
        elif tokens[i] in WIDOW:
            materno, i = _take_married(tokens, i)
        else:
            materno, i = _take_surname(tokens, i)
            if _starts_married(tokens, i):
                married, i = _take_married(tokens, i)
                materno = f"{materno} {married}"
    nombres = " ".join(tokens[i:]) or None
    if nombres is None:
        reasons.append("sin nombres")
    elif tokens[-1] in ("SN", "S/N"):
        reasons.append("nombre 'SN'")
    return paterno, materno, nombres, "; ".join(reasons) or None


# ---------------------------------------------------------------------------
# dataset (pure)
# ---------------------------------------------------------------------------

@dataclass
class Declaracion:
    fila: int
    codigo_predio: str
    numero_documento: str
    attributes: dict


@dataclass
class Dataset:
    contribuyentes: dict = field(default_factory=dict)  # numero_documento -> attributes
    predios: dict = field(default_factory=dict)  # codigo -> attributes
    declaraciones: list = field(default_factory=list)
    filas: dict = field(default_factory=dict)  # ("contribuyente" | "predio", key) -> first Excel row
    problems: list = field(default_factory=list)
    doubtful_names: list = field(default_factory=list)


def load_enums(model_path):
    with open(model_path, encoding="utf-8") as f:
        return {name: set(options) for name, options in json.load(f)["enums"].items()}


def default_model_path():
    return os.path.join(HERE, "model.json")


def limit_rows(rows, limit):
    """The first `limit` rows, plus the co-owner rows that complete the last predio."""
    end = min(limit, len(rows))
    while end < len(rows) and not clean_text(rows[end].get("codigo_predio")):
        end += 1
    return rows[:end]


class _Mapper:
    """Maps Excel values to enum options, collecting what does not fit instead of stopping."""

    def __init__(self, enums, problems):
        self.enums = enums
        self.problems = problems

    def enum(self, row, column, enum_name, mapping=None):
        value = clean_text(row.get(column))
        if value is None:
            return None
        value = (mapping or {}).get(value, value)
        if value not in self.enums[enum_name]:
            self.problems.append(f"fila {row['_fila']}: {column} '{value}' is not an option of enum {enum_name}")
        return value

    def uso(self, row):
        grupo = clean_text(row.get("grupo_uso_desc"))
        usos = uso_del_padron(grupo) if grupo else None
        if grupo and usos is None:
            self.problems.append(f"fila {row['_fila']}: grupo_uso_desc '{grupo}' is not a grupo de uso of the padrón")
        return usos or dict.fromkeys(USO_FIELDS)


def _contribuyente(row, mapper, data):
    tipo_doc = clean_text(row.get("tipo_doc"))
    nombre = clean_text(row.get("nombre_contribuyente"))
    tipo_persona = classify_person(tipo_doc, nombre)
    paterno = materno = nombres = razon_social = None
    if tipo_persona == "NATURAL":
        paterno, materno, nombres, reason = split_name(nombre)
        if reason:
            data.doubtful_names.append({
                "fila": row["_fila"], "numero_documento": clean_text(row.get("num_doc")), "nombre_completo": nombre,
                "apellido_paterno": paterno, "apellido_materno": materno, "nombres": nombres, "motivo": reason,
            })
    else:
        razon_social = nombre
    return {
        "tipo_persona": tipo_persona,
        "tipo_documento": mapper.enum(row, "tipo_doc", "tipo_documento", TIPO_DOCUMENTO),
        "numero_documento": clean_text(row.get("num_doc")),
        "nombre_completo": nombre,
        "apellido_paterno": paterno,
        "apellido_materno": materno,
        "nombres": nombres,
        "razon_social": razon_social,
        **parse_domicilio(row.get("domicilio_fiscal")),
    }


def _predio(row, codigo, mapper):
    sector, manzana = split_sector_manzana(row.get("sector_manzana"))
    return {
        "codigo": codigo,
        "sector_catastral": sector,
        "manzana_catastral": manzana,
        "condicion": mapper.enum(row, "tipo_pupr_desc", "condicion_predio", CONDICION_PREDIO),
        "direccion": clean_text(row.get("direccion_predio")),
        **split_ubicacion(parse_address(row.get("direccion_predio"))),
        "ubicacion_area_verde": mapper.enum(row, "ubicacion_parque", "ubicacion_area_verde"),
    }


def _porcentaje(valor_condominio, valor_autoavaluo):
    if valor_condominio is None or valor_autoavaluo is None or Decimal(valor_autoavaluo) <= 0:
        return None
    ratio = Decimal(valor_condominio) / Decimal(valor_autoavaluo) * 100
    return str(ratio.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def _declaracion(row, anio, secuencia, mapper):
    valor_autoavaluo = clean_decimal(row.get("autoavaluo_total"))
    valor_condominio = clean_decimal(row.get("condominio"))
    return {
        "anio": anio,
        "secuencia_uso": secuencia,
        "porcentaje_condominio": _porcentaje(valor_condominio, valor_autoavaluo),
        **mapper.uso(row),
        "clasificacion": mapper.enum(row, "clasificacion_predio_desc", "clasificacion", CLASIFICACION),
        "estado_construccion": mapper.enum(row, "estado_construccion_desc", "estado_construccion"),
        "area_terreno": clean_decimal(row.get("area_terreno")),
        "area_construida": clean_decimal(row.get("area_construida")),
        "longitud_frente": clean_decimal(row.get("longitud_frente")),
        "numero_habitantes": clean_int(row.get("cantidad_habitantes")),
        "valor_autoavaluo": valor_autoavaluo,
        "valor_condominio": valor_condominio,
        "deduccion": clean_decimal(row.get("deduccion")),
        "valor_afecto": clean_decimal(row.get("autoavaluo_afecto")),
    }


def build_dataset(rows, anio, enums):
    """Excel rows -> contribuyentes, predios and declaraciones, plus what does not fit (problems)."""
    data = Dataset()
    mapper = _Mapper(enums, data.problems)
    group = None  # (codigo, secuencia) of the last row with a codigo
    group_size = {}
    seen = set()
    for row in rows:
        fila = row["_fila"]
        try:
            codigo = clean_text(row.get("codigo_predio"))
            if codigo:
                group = (codigo, secuencia_uso(row.get("secuencia_uso")))
            elif group is None:
                data.problems.append(f"fila {fila}: co-owner row with no predio above it")
                continue
            codigo, secuencia = group

            doc = clean_text(row.get("num_doc"))
            if doc is None:
                data.problems.append(f"fila {fila}: num_doc is empty")
                continue
            contribuyente = _contribuyente(row, mapper, data)
            known = data.contribuyentes.get(doc)
            if known is None:
                data.contribuyentes[doc] = contribuyente
                data.filas[("contribuyente", doc)] = fila
            elif known["nombre_completo"] != contribuyente["nombre_completo"]:
                data.problems.append(f"fila {fila}: num_doc {doc} has two names: '{known['nombre_completo']}' and '{contribuyente['nombre_completo']}'")

            if codigo not in data.predios:
                data.predios[codigo] = _predio(row, codigo, mapper)
                data.filas[("predio", codigo)] = fila

            key = (codigo, secuencia, doc)
            if key in seen:
                data.problems.append(f"fila {fila}: declaration {codigo} / {secuencia} / {doc} is repeated")
                continue
            seen.add(key)
            data.declaraciones.append(Declaracion(fila, codigo, doc, _declaracion(row, anio, secuencia, mapper)))
            group_size[group] = group_size.get(group, 0) + 1
        except (InvalidOperation, ValueError) as e:
            data.problems.append(f"fila {fila}: {e!r}")

    for decl in data.declaraciones:
        owners = group_size[(decl.codigo_predio, decl.attributes["secuencia_uso"])]
        decl.attributes["condicion_propiedad"] = "CONDOMINO" if owners > 1 else "PROPIETARIO UNICO"
    return data


def write_report(path, doubtful):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    columns = ["fila", "numero_documento", "nombre_completo", "apellido_paterno", "apellido_materno", "nombres", "motivo"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=columns)
        writer.writeheader()
        writer.writerows(doubtful)


# ---------------------------------------------------------------------------
# load
# ---------------------------------------------------------------------------

class LoadError(Exception):
    def __init__(self, object_name, fila, error):
        super().__init__(f"{object_name} fila {fila}: {error}")
        self.object_name = object_name
        self.fila = fila
        self.error = error


def _payload(attributes):
    return {"attributes": {k: v for k, v in attributes.items() if v is not None}}


def post_all(client, object_name, items, workers):
    """POSTs (key, fila, attributes) items; returns key -> record id. The first refusal stops it all."""
    ids = {}
    if not items:
        return ids

    def one(item):
        key, fila, attributes = item
        try:
            _, record = client.post(f"/api/objects/{object_name}/records", _payload(attributes))
        except CoreError as e:
            raise LoadError(object_name, fila, e)
        return key, record["id"]

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, item) for item in items]
        try:
            for done, future in enumerate(as_completed(futures), start=1):
                key, record_id = future.result()
                ids[key] = record_id
                if done % PROGRESS_EVERY == 0:
                    print(f"  {object_name}: {done}/{len(items)}", flush=True)
        except LoadError:
            for future in futures:
                future.cancel()
            raise
    return ids


def load(client, data, anio, workers):
    """Creates what is missing, in dependency order. Returns (created, skipped)."""
    created = skipped = 0

    contribuyentes = {r["attributes"]["numero_documento"]: r["id"] for r in client.list_all("contribuyente")}
    todo = [(doc, data.filas[("contribuyente", doc)], a) for doc, a in data.contribuyentes.items() if doc not in contribuyentes]
    skipped += len(data.contribuyentes) - len(todo)
    new = post_all(client, "contribuyente", todo, workers)
    contribuyentes.update(new)
    created += len(new)
    print(f"contribuyentes: {len(new)} created, {len(data.contribuyentes) - len(todo)} skipped", flush=True)

    predios = {r["attributes"]["codigo"]: r["id"] for r in client.list_all("predio")}
    todo = [(codigo, data.filas[("predio", codigo)], a) for codigo, a in data.predios.items() if codigo not in predios]
    skipped += len(data.predios) - len(todo)
    new = post_all(client, "predio", todo, workers)
    predios.update(new)
    created += len(new)
    print(f"predios: {len(new)} created, {len(data.predios) - len(todo)} skipped", flush=True)

    def key(predio_id, contribuyente_id, secuencia):
        return str(predio_id), str(contribuyente_id), str(anio), str(secuencia)

    existing = {
        key(a.get("predio"), a.get("contribuyente"), a.get("secuencia_uso"))
        for a in (r["attributes"] for r in client.list_all("declaracion_predial", anio=anio))
    }
    todo = []
    for d in data.declaraciones:
        predio_id = predios[d.codigo_predio]
        contribuyente_id = contribuyentes[d.numero_documento]
        k = key(predio_id, contribuyente_id, d.attributes["secuencia_uso"])
        if k in existing:
            continue
        todo.append((k, d.fila, {**d.attributes, "predio": predio_id, "contribuyente": contribuyente_id}))
    skipped += len(data.declaraciones) - len(todo)
    new = post_all(client, "declaracion_predial", todo, workers)
    created += len(new)
    print(f"declaraciones: {len(new)} created, {len(data.declaraciones) - len(todo)} skipped", flush=True)
    return created, skipped


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_args(argv):
    p = argparse.ArgumentParser(description="Import the predios Excel into srtm's wasichai Core.")
    p.add_argument("--excel", required=True, help="path to CODIGO DE PREDIOS AL <año>.xlsx")
    p.add_argument("--sheet", default=None, help="sheet name (default: the first)")
    p.add_argument("--anio", type=int, default=2026, help="year of the declarations (default 2026)")
    p.add_argument("--model", default=default_model_path())
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read and transform only; call nothing")
    p.add_argument("--limit", type=int, default=None, help="only the first N rows (plus the co-owners of the last predio)")
    p.add_argument("--workers", type=int, default=4, help="parallel POSTs (default 4)")
    p.add_argument("--report", default=os.path.join(HERE, "reports", "nombres_dudosos.csv"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    rows = read_xlsx(args.excel, args.sheet)
    if args.limit:
        rows = limit_rows(rows, args.limit)
    data = build_dataset(rows, args.anio, load_enums(args.model))

    print(f"filas: {len(rows)}")
    print(f"contribuyentes: {len(data.contribuyentes)}")
    print(f"predios: {len(data.predios)}")
    print(f"declaraciones: {len(data.declaraciones)}")
    write_report(args.report, data.doubtful_names)
    print(f"nombres dudosos: {len(data.doubtful_names)} -> {args.report}")

    if data.problems:
        for problem in data.problems[:50]:
            print(f"problem: {problem}", file=sys.stderr)
        print(f"{len(data.problems)} problems: nothing was sent to Core", file=sys.stderr)
        return 2
    if args.dry_run:
        return 0

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        created, skipped = load(client, data, args.anio, args.workers)
    except LoadError as e:
        print(f"error POST /api/objects/{e.object_name}/records (fila {e.fila}) -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    print(f"done: {created} created, {skipped} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
