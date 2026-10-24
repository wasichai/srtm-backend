"""srtm extraction for the corte (contexto.md § Tablas; controller ruling R6 for the two columns it adds).

`extraer_srtm` reads the ten `srtm_*` objects of one año and turns each into the exact row shape its table
needs: a column whitelist (contexto.md's minimization ruling - no names, no addresses, no free text), the
contributor's document seudonimizado instead of its number, and the brecha corrida chosen by its own ruling
(TERMINADA, most recent `terminado`, ties broken by the highest `id`). Every column defaults to `None` when
its attribute is missing - this is what makes confianza/confianza_motivo (srtm-backend#204, not in every
srtm) and codigo_origen/the brecha_corrida R6 columns (not in every srtm either) safe to read before they
exist everywhere.
"""

from dataclasses import dataclass

from .seudonimo import seudonimo

# contexto.md § Tablas, in the order given there; srtm_brecha_corrida's last 7 and srtm_predio's last 1 are
# controller ruling R6 (contratos.md §5, lane B): present in the row, null until lane B's columns exist
COLUMNAS = {
    "srtm_predio": (
        "id",
        "codigo",
        "tipo_predio",
        "sector_catastral",
        "manzana_catastral",
        "manzana",
        "lote",
        "sub_lote",
        "ubigeo",
        "codigo_cpu",
        "codigo_via",
        "habilitacion_urbana",
        "codigo_origen",
    ),
    "srtm_contribuyente": ("id", "codigo", "tipo_persona", "tipo_documento", "documento_hmac", "tipo_contribuyente"),
    "srtm_declaracion": (
        "id",
        "predio",
        "contribuyente",
        "anio",
        "estado",
        "condicion_propiedad",
        "porcentaje_condominio",
        "uso",
        "clase_uso",
        "clasificacion",
        "area_terreno",
        "area_construida",
        "valor_terreno",
        "valor_construccion",
        "valor_otras_instalaciones",
        "valor_autoavaluo",
        "valor_condominio",
        "deduccion",
        "valor_afecto",
        "arancel",
        "id_aranc",
        "autoavaluo_por_revisar",
        "autoavaluo_motivo",
        "sustituye",
    ),
    "srtm_nivel": (
        "id",
        "declaracion",
        "numero_piso",
        "anio_construccion",
        "material",
        "estado_conservacion",
        "clasificacion",
        "area_construida",
        "area_comun",
        "muros_columnas",
        "techos",
        "pisos",
        "puertas_ventanas",
        "revestimientos",
        "banos",
        "instalaciones",
        "valor_unitario",
        "porcentaje_depreciacion",
        "valor_total",
        "estado",
    ),
    "srtm_obra": (
        "id",
        "declaracion",
        "tipo_obra",
        "material",
        "estado_conservacion",
        "anio_construccion",
        "categoria",
        "cantidad",
        "total_metrado",
        "unidad_medida",
        "valor",
        "estado",
    ),
    "srtm_impuesto_contribuyente": ("contribuyente", "anio", "impuesto_anual", "cuotas", "fecha_calculo"),
    "srtm_brecha_corrida": (
        "id",
        "anio",
        "estado",
        "version_catastro",
        "iniciado",
        "terminado",
        "total_predios",
        "declarado",
        "catastro",
        "delta_impuesto",
        "delta_arbitrios",
        "origen",
        "predios_confiable",
        "predios_dudoso",
        "predios_sin_levantar",
        "delta_impuesto_confiable",
        "delta_impuesto_dudoso",
        "delta_impuesto_sin_levantar",
    ),
    "srtm_brecha_predio": (
        "id",
        "corrida",
        "predio",
        "codigo_catastro",
        "codigo_predio",
        "categoria",
        "sector",
        "manzana",
        "lote",
        "uso",
        "autoavaluo_declarado",
        "autoavaluo_catastro",
        "delta_autoavaluo",
        "impuesto_declarado",
        "impuesto_catastro",
        "delta_impuesto",
        "delta_arbitrios",
        "estimado",
        "motivo",
        "confianza",
        "confianza_motivo",
        "orden",
    ),
    "srtm_parametro": (
        "id",
        "tipo",
        "clave",
        "vigencia_desde",
        "vigencia_hasta",
        "valor_numerico",
        "texto",
        "norma",
        "estado",
    ),
    "srtm_arancel": ("id", "clave", "id_aranc", "anio", "valor_m2", "cod_via", "cuadra", "lado", "norma", "estado"),
}

_AVISO_SIN_CORRIDA = "sin brecha TERMINADA de {anio}"
_AVISO_SIN_CONFIANZA = (
    "brecha_predio sin el atributo confianza/confianza_motivo en algunas filas (srtm-backend#204): se exporta null"
)


@dataclass
class ExtraccionSrtm:
    tablas: dict[str, list[dict]]
    corrida: dict | None
    parametros: list[dict]
    avisos: list[str]


def _fila(columnas, registro):
    """A row with exactly `columnas`: every relation/attribute by its fieldName, `None` when it is absent -
    this is the whitelist (contexto.md) and the R6/#204 absence-tolerance at the same time."""
    atributos = registro.get("attributes", {})
    fila = {columna: atributos.get(columna) for columna in columnas}
    fila["id"] = registro["id"]
    return fila


def _fila_contribuyente(registro, clave):
    # the one column that is never a plain attribute: documento_hmac replaces numero_documento (no name either)
    atributos = registro.get("attributes", {})
    return {
        "id": registro["id"],
        "codigo": atributos.get("codigo"),
        "tipo_persona": atributos.get("tipo_persona"),
        "tipo_documento": atributos.get("tipo_documento"),
        "documento_hmac": seudonimo(clave, atributos.get("numero_documento")),
        "tipo_contribuyente": atributos.get("tipo_contribuyente"),
    }


def _elegir_corrida(corridas):
    # contexto.md ruling: TERMINADA, terminado más reciente; empate, el id mayor
    terminadas = [c for c in corridas if c["attributes"].get("estado") == "TERMINADA"]
    if not terminadas:
        return None
    return max(terminadas, key=lambda c: (c["attributes"].get("terminado") or "", c["id"]))


def _impuesto_por_contribuyente(cuotas):
    """srtm_impuesto_contribuyente: for each contribuyente, the sum of the highest `revision` of every
    `numero` (a revisión ausente cuenta 0), the count of distinct `numero` and the greatest `fecha_calculo`."""
    por_contribuyente = {}
    for cuota in cuotas:
        atributos = cuota["attributes"]
        por_contribuyente.setdefault(atributos.get("contribuyente"), []).append(atributos)

    filas = []
    for contribuyente, filas_cuota in por_contribuyente.items():
        mejor_por_numero = {}
        for atributos in filas_cuota:
            numero = atributos.get("numero")
            revision = atributos.get("revision") or 0
            anterior = mejor_por_numero.get(numero)
            if anterior is None or revision >= anterior[0]:
                mejor_por_numero[numero] = (revision, atributos.get("monto"))
        impuesto_anual = sum((monto for _, monto in mejor_por_numero.values() if monto is not None), 0)
        fechas = [a.get("fecha_calculo") for a in filas_cuota if a.get("fecha_calculo")]
        filas.append(
            {
                "contribuyente": contribuyente,
                "anio": filas_cuota[0].get("anio"),
                "impuesto_anual": impuesto_anual,
                "cuotas": len(mejor_por_numero),
                "fecha_calculo": max(fechas) if fechas else None,
            }
        )
    return filas


def _parametros_vigentes(parametros, anio):
    # contexto.md ruling: vigentes al 1 de enero del año, de todos los tipos, estado VIGENTE o vacío
    primero_enero = f"{anio}-01-01"
    vigentes = []
    for parametro in parametros:
        atributos = parametro["attributes"]
        if atributos.get("estado") not in (None, "", "VIGENTE"):
            continue
        desde = atributos.get("vigencia_desde")
        hasta = atributos.get("vigencia_hasta")
        if desde and desde > primero_enero:
            continue
        if hasta and hasta < primero_enero:
            continue
        vigentes.append(parametro)
    return vigentes


def extraer_srtm(cliente, anio, clave):
    predios = list(cliente.registros("predio"))
    contribuyentes_todos = list(cliente.registros("contribuyente"))
    declaraciones = list(cliente.registros("declaracion_predial", anio=anio))
    cuotas = list(cliente.registros("cuota_predial", anio=anio))
    niveles_todos = list(cliente.registros("nivel_construccion"))
    obras_todos = list(cliente.registros("obra_complementaria"))
    corridas_del_anio = list(cliente.registros("brecha_corrida", anio=anio))
    parametros_todos = list(cliente.registros("parametro_tributario"))
    arancel_del_anio = list(cliente.registros("arancel", anio=anio))

    declaracion_ids = {d["id"] for d in declaraciones}
    # minimization ruling: only the contribuyentes cited by a declaración or a cuota of this año
    citados = {d["attributes"].get("contribuyente") for d in declaraciones}
    citados |= {c["attributes"].get("contribuyente") for c in cuotas}
    contribuyentes = [c for c in contribuyentes_todos if c["id"] in citados]

    niveles = [n for n in niveles_todos if n["attributes"].get("declaracion") in declaracion_ids]
    obras = [o for o in obras_todos if o["attributes"].get("declaracion") in declaracion_ids]

    avisos = []
    tablas = {
        "srtm_predio": [_fila(COLUMNAS["srtm_predio"], p) for p in predios],
        "srtm_contribuyente": [_fila_contribuyente(c, clave) for c in contribuyentes],
        "srtm_declaracion": [_fila(COLUMNAS["srtm_declaracion"], d) for d in declaraciones],
        "srtm_nivel": [_fila(COLUMNAS["srtm_nivel"], n) for n in niveles],
        "srtm_obra": [_fila(COLUMNAS["srtm_obra"], o) for o in obras],
        "srtm_impuesto_contribuyente": _impuesto_por_contribuyente(cuotas),
    }

    corrida_elegida = _elegir_corrida(corridas_del_anio)
    if corrida_elegida is None:
        avisos.append(_AVISO_SIN_CORRIDA.format(anio=anio))
        tablas["srtm_brecha_corrida"] = []
        tablas["srtm_brecha_predio"] = []
        corrida = None
    else:
        fila_corrida = _fila(COLUMNAS["srtm_brecha_corrida"], corrida_elegida)
        tablas["srtm_brecha_corrida"] = [fila_corrida]
        corrida = fila_corrida
        brechas = list(cliente.registros("brecha_predio", corrida=corrida_elegida["id"]))
        if any("confianza" not in b["attributes"] for b in brechas):
            avisos.append(_AVISO_SIN_CONFIANZA)
        tablas["srtm_brecha_predio"] = [_fila(COLUMNAS["srtm_brecha_predio"], b) for b in brechas]

    parametros_vigentes = _parametros_vigentes(parametros_todos, anio)
    tablas["srtm_parametro"] = [_fila(COLUMNAS["srtm_parametro"], p) for p in parametros_vigentes]

    arancel_vigente = [a for a in arancel_del_anio if a["attributes"].get("estado") in (None, "", "VIGENTE")]
    tablas["srtm_arancel"] = [_fila(COLUMNAS["srtm_arancel"], a) for a in arancel_vigente]

    return ExtraccionSrtm(tablas=tablas, corrida=corrida, parametros=tablas["srtm_parametro"], avisos=avisos)
