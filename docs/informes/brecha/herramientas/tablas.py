"""The corte's 18 tables: columns, Arrow schemas and sort keys (plan:
docs/superpowers/plans/2026-10-25-corte-analitico.md, § Tablas del corte; plan, Rulings de la ejecución, R13).

The column whitelists live once, in srtm.py and catastro.py; this module only adds types and order. The types
are written here, not read from srtm's model at run time: like the pinned pyarrow, a change in a corte's bytes
must be a deliberate change to this file. srtm's types follow model/model.json (DECIMAL, INTEGER, BOOLEAN,
DATE, DATETIME; relations are the related record's uuid) and pruebas/test_tablas.py fails if they drift
apart; the columns the model does not carry (the pseudonym, the per-contributor aggregates, the columns of
plan, Rulings de la ejecución, R6, #204's confianza) and every catastro column are typed by their meaning. A
column not named in _TIPOS is a string.
"""

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

# the non-string columns of each table
_TIPOS = {
    "srtm_predio": {},
    "srtm_contribuyente": {},
    "srtm_declaracion": {
        "anio": ENTERO,
        "porcentaje_condominio": DECIMAL,
        "area_terreno": DECIMAL,
        "area_construida": DECIMAL,
        "valor_terreno": DECIMAL,
        "valor_construccion": DECIMAL,
        "valor_otras_instalaciones": DECIMAL,
        "valor_autoavaluo": DECIMAL,
        "valor_condominio": DECIMAL,
        "deduccion": DECIMAL,
        "valor_afecto": DECIMAL,
        "arancel": DECIMAL,
        "autoavaluo_por_revisar": LOGICO,
    },
    "srtm_nivel": {
        "numero_piso": ENTERO,
        "anio_construccion": ENTERO,
        "area_construida": DECIMAL,
        "area_comun": DECIMAL,
        "valor_unitario": DECIMAL,
        "porcentaje_depreciacion": DECIMAL,
        "valor_total": DECIMAL,
    },
    "srtm_obra": {"anio_construccion": ENTERO, "cantidad": DECIMAL, "total_metrado": DECIMAL, "valor": DECIMAL},
    # impuesto_anual and cuotas are srtm.py's aggregates, not model fields
    "srtm_impuesto_contribuyente": {
        "anio": ENTERO,
        "impuesto_anual": DECIMAL,
        "cuotas": ENTERO,
        "fecha_calculo": FECHA,
    },
    "srtm_brecha_corrida": {
        "anio": ENTERO,
        "iniciado": INSTANTE,
        "terminado": INSTANTE,
        "total_predios": ENTERO,
        "declarado": DECIMAL,
        "catastro": DECIMAL,
        "delta_impuesto": DECIMAL,
        "delta_arbitrios": DECIMAL,
        # plan, Rulings de la ejecución, R6 (origen stays a string)
        "predios_confiable": ENTERO,
        "predios_dudoso": ENTERO,
        "predios_sin_levantar": ENTERO,
        "delta_impuesto_confiable": DECIMAL,
        "delta_impuesto_dudoso": DECIMAL,
        "delta_impuesto_sin_levantar": DECIMAL,
    },
    "srtm_brecha_predio": {
        "autoavaluo_declarado": DECIMAL,
        "autoavaluo_catastro": DECIMAL,
        "delta_autoavaluo": DECIMAL,
        "impuesto_declarado": DECIMAL,
        "impuesto_catastro": DECIMAL,
        "delta_impuesto": DECIMAL,
        "delta_arbitrios": DECIMAL,
        "estimado": LOGICO,
        "orden": ENTERO,
        # srtm-backend#247
        "area_terreno_declarada": DECIMAL,
        "area_terreno_catastro": DECIMAL,
        "area_construida_declarada": DECIMAL,
        "area_construida_catastro": DECIMAL,
        "niveles_comparados": ENTERO,
        "niveles_catastro_mayor": ENTERO,
        "delta_area_niveles": DECIMAL,
    },
    # srtm-backend#256 (estado stays a string)
    "srtm_brecha_nivel": {"piso": ENTERO, "area_rentas": DECIMAL, "area_catastro": DECIMAL},
    "srtm_parametro": {"vigencia_desde": FECHA, "vigencia_hasta": FECHA, "valor_numerico": DECIMAL},
    "srtm_arancel": {"anio": ENTERO, "valor_m2": DECIMAL},
    # catastro: areas, amounts, porcentaje and longitud_frente are DECIMAL; piso and seccion stay strings
    # (catastro writes floor codes as text); cantidad is DECIMAL like srtm's obra_complementaria.cantidad
    "catastro_ficha": {
        "area_terreno": DECIMAL,
        "longitud_frente": DECIMAL,
        "es_edificio": LOGICO,
        "fecha_levantamiento": FECHA,
        "vinculo_revisado": LOGICO,
    },
    "catastro_construccion": {"area_construida": DECIMAL, "anio_construccion": ENTERO, "techo_incluido": LOGICO},
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

# plan § Tablas del corte, "Orden"; parquet.escribir breaks any remaining tie with the other columns
ORDEN = {
    "srtm_predio": ("id",),
    "srtm_contribuyente": ("id",),
    "srtm_declaracion": ("id",),
    "srtm_nivel": ("id",),
    "srtm_obra": ("id",),
    "srtm_impuesto_contribuyente": ("contribuyente",),
    "srtm_brecha_corrida": ("id",),
    "srtm_brecha_predio": ("orden", "id"),
    "srtm_brecha_nivel": ("brecha_predio", "piso", "id"),
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


def _esquemas():
    esquemas = {}
    for tabla, columnas in COLUMNAS.items():
        # a typo in _TIPOS would silently leave a column as a string
        sobrantes = set(_TIPOS[tabla]) - set(columnas)
        if sobrantes:
            raise comun.ErrorCorte(f"{tabla}: columnas tipadas que no exporta: {', '.join(sorted(sobrantes))}")
        esquemas[tabla] = pa.schema([pa.field(c, _TIPOS[tabla].get(c, TEXTO)) for c in columnas])
    return esquemas


ESQUEMAS = _esquemas()
