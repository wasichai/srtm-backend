"""The corte's 17 tables: columns, Arrow schemas and sort keys (contexto.md § Tablas; controller ruling R13).

The column whitelists live once, in srtm.py and catastro.py; this module only adds types and order. srtm's
columns take their type from srtm's own model (model/model.json, read-only): a relation is the related
record's uuid, so a string, and the few columns the model does not carry (the pseudonym, the per-contributor
aggregates, controller ruling R6's columns, #204's confianza) are typed here by hand. catastro's model is not
in this repository, so its columns are typed by their meaning; every column not named below is a string.
"""

import json

import pyarrow as pa

from . import catastro, comun, srtm

DECIMAL = pa.decimal128(38, 10)
ENTERO = pa.int64()
LOGICO = pa.bool_()
FECHA = pa.date32()
INSTANTE = pa.timestamp("us", tz="UTC")
REAL = pa.float64()
TEXTO = pa.string()

COLUMNAS = {**srtm.COLUMNAS, **catastro.COLUMNAS}

# model.json field types -> Arrow; TEXT, ENUM, UUID, LONG_TEXT... are all strings
_POR_TIPO_DEL_MODELO = {"DECIMAL": DECIMAL, "INTEGER": ENTERO, "BOOLEAN": LOGICO, "DATE": FECHA, "DATETIME": INSTANTE}

# the srtm object each srtm_* table is read from
_OBJETO_SRTM = {
    "srtm_predio": "predio",
    "srtm_contribuyente": "contribuyente",
    "srtm_declaracion": "declaracion_predial",
    "srtm_nivel": "nivel_construccion",
    "srtm_obra": "obra_complementaria",
    "srtm_impuesto_contribuyente": "cuota_predial",
    "srtm_brecha_corrida": "brecha_corrida",
    "srtm_brecha_predio": "brecha_predio",
    "srtm_parametro": "parametro_tributario",
    "srtm_arancel": "arancel",
}

# srtm columns that model.json does not (yet) carry; a type the model gives later wins over these
_SRTM_FUERA_DEL_MODELO = {
    "srtm_predio": {"codigo_origen": TEXTO},
    "srtm_contribuyente": {"documento_hmac": TEXTO},
    "srtm_impuesto_contribuyente": {"impuesto_anual": DECIMAL, "cuotas": ENTERO},
    "srtm_brecha_corrida": {
        "origen": TEXTO,
        "predios_confiable": ENTERO,
        "predios_dudoso": ENTERO,
        "predios_sin_levantar": ENTERO,
        "delta_impuesto_confiable": DECIMAL,
        "delta_impuesto_dudoso": DECIMAL,
        "delta_impuesto_sin_levantar": DECIMAL,
    },
    "srtm_brecha_predio": {"confianza": TEXTO, "confianza_motivo": TEXTO},
}

# catastro's non-string columns: areas, amounts, porcentaje and longitud_frente are DECIMAL; piso, seccion and
# anio_construccion are whole numbers in the fixtures and the API; cantidad stays DECIMAL like srtm's own
# obra_complementaria.cantidad (a metrado can be fractional)
_CATASTRO = {
    "catastro_ficha": {
        "area_terreno": DECIMAL,
        "longitud_frente": DECIMAL,
        "es_edificio": LOGICO,
        "fecha_levantamiento": FECHA,
        "vinculo_revisado": LOGICO,
    },
    "catastro_construccion": {
        "piso": ENTERO,
        "seccion": ENTERO,
        "area_construida": DECIMAL,
        "anio_construccion": ENTERO,
        "techo_incluido": LOGICO,
    },
    "catastro_obra": {"cantidad": DECIMAL, "anio_construccion": ENTERO},
    "catastro_titular": {"porcentaje": DECIMAL},
    "catastro_valuacion": {
        "area_terreno": DECIMAL,
        "area_construida": DECIMAL,
        "longitud_frente": DECIMAL,
        "terreno": DECIMAL,
        "construccion": DECIMAL,
        "obras": DECIMAL,
        "autoavaluo": DECIMAL,
        "fecha_levantamiento": FECHA,
        "vinculo_revisado": LOGICO,
    },
    "catastro_lote": {"lon": REAL, "lat": REAL},
    "catastro_cambio": {"en": INSTANTE},
}

# contexto.md § Tablas, "Orden"; parquet.escribir breaks any remaining tie with the other columns
ORDEN = {
    "srtm_predio": ("id",),
    "srtm_contribuyente": ("id",),
    "srtm_declaracion": ("id",),
    "srtm_nivel": ("id",),
    "srtm_obra": ("id",),
    "srtm_impuesto_contribuyente": ("contribuyente",),
    "srtm_brecha_corrida": ("id",),
    "srtm_brecha_predio": ("orden", "id"),
    "srtm_parametro": ("tipo", "clave", "vigencia_desde", "id"),
    "srtm_arancel": ("id_aranc", "id"),
    "catastro_ficha": ("codigo_municipal", "id"),
    "catastro_construccion": ("codigo_municipal", "piso", "seccion", "id"),
    "catastro_obra": ("codigo_municipal", "codigo_vu", "id"),
    "catastro_titular": COLUMNAS["catastro_titular"],
    "catastro_valuacion": ("codigo_municipal",),
    "catastro_lote": ("codigo_municipal",),
    "catastro_cambio": ("en", "codigo_municipal", "objeto", "operacion", "campos"),
}


def _tipos_srtm():
    """{tabla: {columna: tipo}} for the srtm tables, from model.json's fields and relationships."""
    modelo = json.loads((comun.REPO / "model" / "model.json").read_text(encoding="utf-8"))
    objetos = {objeto["name"]: objeto for objeto in modelo["objects"]}
    tipos = {}
    for tabla, nombre in _OBJETO_SRTM.items():
        campos = {campo["name"]: campo["type"] for campo in objetos[nombre]["fields"]}
        relaciones = {r["fieldName"] for r in modelo["relationships"] if r["source"] == nombre}
        extra = _SRTM_FUERA_DEL_MODELO.get(tabla, {})
        tipos[tabla] = {}
        for columna in COLUMNAS[tabla]:
            if columna in campos:
                tipos[tabla][columna] = _POR_TIPO_DEL_MODELO.get(campos[columna], TEXTO)
            elif columna == "id" or columna in relaciones:
                tipos[tabla][columna] = TEXTO
            elif columna in extra:
                tipos[tabla][columna] = extra[columna]
            else:
                # a new whitelist column must be typed on purpose, never fall back to a guess
                raise comun.ErrorCorte(f"{tabla}.{columna}: no está en model.json ni tiene tipo en tablas.py")
    return tipos


def _esquemas():
    # a typo in the hand-typed maps would silently leave a column as a string
    for mapa in (_SRTM_FUERA_DEL_MODELO, _CATASTRO):
        for tabla, columnas in mapa.items():
            sobrantes = set(columnas) - set(COLUMNAS[tabla])
            if sobrantes:
                raise comun.ErrorCorte(f"{tabla}: columnas tipadas que no exporta: {', '.join(sorted(sobrantes))}")
    tipos = _tipos_srtm()
    for tabla, columnas in catastro.COLUMNAS.items():
        tipos[tabla] = {columna: _CATASTRO.get(tabla, {}).get(columna, TEXTO) for columna in columnas}
    return {tabla: pa.schema([pa.field(c, tipos[tabla][c]) for c in COLUMNAS[tabla]]) for tabla in COLUMNAS}


ESQUEMAS = _esquemas()
