"""The informe de brechas' indicators, computed only from the views of a corte's DuckDB (vistas.sql;
srtm-backend#301): the corrida's totals, the breakdown by confianza, the brecha by categoría and by clase, the
predial in three columns and the niveles by estado.

Ley 29733: the result only carries aggregates, and an amount (soles or m²) is published only when it adds up at
least `umbral` predios (contribuyentes for the predial). A smaller group keeps its count but its amounts are
reserved (None, "reservado": true); when a family of groups adds up to a published total and only one of them
is reserved, the next smallest is reserved too, so the reserved one cannot be recovered by subtraction.

Amounts are kept as text (str of a Decimal) so indicadores.json loses no céntimo; the report rounds them.
"""

from decimal import Decimal

import duckdb

ORDEN_CONFIANZA = ("CONFIABLE", "DUDOSO", "SIN_LEVANTAR", "SIN_DATO")
ORDEN_CATEGORIA = ("OMISO", "SUBVALUADO", "SOBREVALUADO", "COINCIDE", "SIN_VALOR", "SIN_CATASTRO")
ORDEN_NIVEL = ("PAR", "SOLO_RENTAS", "SOLO_CATASTRO", "AMBIGUO", "SIN_DATO")

MONTOS_PREDIO = (
    "autoavaluo_declarado",
    "autoavaluo_catastro",
    "delta_autoavaluo",
    "impuesto_declarado",
    "impuesto_catastro",
    "delta_impuesto",
)
MONTOS_CATEGORIA = ("delta_autoavaluo", "impuesto_declarado", "impuesto_catastro", "delta_impuesto")
MONTOS_CLASE = ("delta_autoavaluo", "delta_impuesto")
MONTOS_NIVEL = ("area_rentas", "area_catastro")
MONTOS_PREDIAL = ("declarado", "motor_rentas", "motor_catastro")
MONTOS_MAYOR = ("area_rentas", "area_catastro", "delta_area")


class ErrorIndicadores(Exception):
    """The DuckDB file cannot give the report's figures (missing, or without the views of vistas.sql)."""


def _texto(valor):
    if valor is None:
        return None
    if isinstance(valor, Decimal):
        return str(valor)
    return valor


def _filas(con, sql):
    resultado = con.execute(sql)
    columnas = [d[0] for d in resultado.description]
    return [{c: _texto(v) for c, v in zip(columnas, fila)} for fila in resultado.fetchall()]


def _orden(valor, orden):
    return (orden.index(valor), "") if valor in orden else (len(orden), str(valor))


def _reservar(celda, montos, contador, umbral):
    n = celda.get(contador) or 0
    celda["reservado"] = 0 < n < umbral
    if celda["reservado"]:
        for m in montos:
            celda[m] = None
    return celda


def _complementar(familia, montos, contador):
    """Secondary suppression: one reserved group among those that add up to a published total is recoverable."""
    reservadas = [c for c in familia if c["reservado"]]
    libres = [c for c in familia if not c["reservado"] and (c.get(contador) or 0) > 0]
    if len(reservadas) == 1 and libres:
        otra = min(libres, key=lambda c: (c[contador], str(c.get("clave"))))
        otra["reservado"] = True
        for m in montos:
            otra[m] = None


def abrir(ruta):
    if not ruta.is_file():
        raise ErrorIndicadores(f"no existe {ruta}: ármalo con make local CORTE=<carpeta del corte>")
    try:
        con = duckdb.connect(str(ruta), read_only=True)
    except duckdb.Error as e:
        raise ErrorIndicadores(f"no se pudo abrir {ruta}: {e}") from None
    faltan = [
        v
        for v in ("v_corte", "v_brecha_corrida", "v_brecha_totales", "v_brecha_por_confianza", "v_segmentos",
                  "v_brecha_por_clase", "v_predial_tres_columnas", "v_niveles_por_estado",
                  "v_niveles_catastro_mayor")
        if not con.execute("SELECT count(*) FROM duckdb_views() WHERE view_name = ?", [v]).fetchone()[0]
    ]
    if faltan:
        con.close()
        raise ErrorIndicadores(
            f"{ruta} no tiene las vistas {', '.join(faltan)}: vuelve a armarlo con make local (vistas.sql al día)"
        )
    return con


def calcular(con, umbral):
    """All the indicators of one corte, as a JSON-ready dict."""
    # TIMESTAMPTZ columns are read as text: the python duckdb client needs pytz to hand them over otherwise
    corte = _filas(
        con,
        "SELECT organizacion, CAST(fecha_corte AS VARCHAR) AS fecha_corte, tipo, anio, version_motor, region,"
        " version_catastro, avisos FROM v_corte",
    )
    if len(corte) != 1:
        raise ErrorIndicadores(f"v_corte debe tener una fila y tiene {len(corte)}")
    corte = corte[0]
    corte["avisos"] = [a for a in (corte["avisos"] or "").split("\n") if a]

    corrida = _filas(
        con,
        "SELECT anio, estado, origen, version_catastro, total_predios,"
        " left(CAST(terminado AS VARCHAR), 10) AS terminado FROM v_brecha_corrida",
    )
    corrida = corrida[0] if corrida else None

    totales = _reservar(_filas(con, "SELECT * FROM v_brecha_totales")[0], MONTOS_PREDIO + ("delta_arbitrios",),
                        "predios", umbral)

    confianza = sorted(_filas(con, "SELECT * FROM v_brecha_por_confianza"),
                       key=lambda c: _orden(c["confianza"], ORDEN_CONFIANZA))
    for c in confianza:
        c["clave"] = c["confianza"]
        _reservar(c, MONTOS_PREDIO, "predios", umbral)
    _complementar(confianza, MONTOS_PREDIO, "predios")

    categorias = sorted(
        _filas(con, "SELECT confianza, categoria, predios, delta_autoavaluo, impuesto_declarado, impuesto_catastro,"
                    " delta_impuesto FROM v_segmentos"),
        key=lambda c: (_orden(c["confianza"], ORDEN_CONFIANZA), _orden(c["categoria"], ORDEN_CATEGORIA)),
    )
    for c in categorias:
        c["categoria"] = c["categoria"] or "SIN_CATEGORIA"
        c["clave"] = c["categoria"]
        _reservar(c, MONTOS_CATEGORIA, "predios", umbral)
    for conf in {c["confianza"] for c in categorias}:
        _complementar([c for c in categorias if c["confianza"] == conf], MONTOS_CATEGORIA, "predios")

    clases = sorted(
        _filas(con, "SELECT confianza, coalesce(clase, 'SIN FICHA') AS clase, predios, delta_autoavaluo,"
                    " delta_impuesto FROM v_brecha_por_clase"),
        key=lambda c: (_orden(c["confianza"], ORDEN_CONFIANZA), c["clase"]),
    )
    for c in clases:
        c["clave"] = c["clase"]
        _reservar(c, MONTOS_CLASE, "predios", umbral)
    for conf in {c["confianza"] for c in clases}:
        _complementar([c for c in clases if c["confianza"] == conf], MONTOS_CLASE, "predios")

    predial = _reservar(_filas(con, "SELECT * FROM v_predial_tres_columnas")[0], MONTOS_PREDIAL, "contribuyentes",
                        umbral)

    niveles = sorted(_filas(con, "SELECT * FROM v_niveles_por_estado"),
                     key=lambda n: _orden(n["estado"], ORDEN_NIVEL))
    for n in niveles:
        n["clave"] = n["estado"]
        _reservar(n, MONTOS_NIVEL, "predios", umbral)

    # the whole of v_niveles_catastro_mayor, never by sector or manzana: a manzana is a handful of predios
    mayor = _filas(
        con,
        "SELECT coalesce(sum(predios), 0) AS predios, coalesce(sum(niveles), 0) AS niveles,"
        " sum(area_rentas) AS area_rentas, sum(area_catastro) AS area_catastro, sum(delta_area) AS delta_area"
        " FROM v_niveles_catastro_mayor",
    )[0]
    _reservar(mayor, MONTOS_MAYOR, "predios", umbral)

    return {
        "umbral": umbral,
        "corte": corte,
        "corrida": corrida,
        "totales": totales,
        "confianza": confianza,
        "categorias": categorias,
        "clases": clases,
        "predial": predial,
        "niveles": niveles,
        "niveles_catastro_mayor": mayor,
    }


def desde_archivo(ruta, umbral):
    con = abrir(ruta)
    try:
        return calcular(con, umbral)
    except duckdb.Error as e:
        # a .duckdb built with an older vistas.sql: its views have other columns
        raise ErrorIndicadores(f"{ruta}: {e}; vuelve a armarlo con make local (vistas.sql al día)") from None
    finally:
        con.close()


# --- comparison ----------------------------------------------------------------------------------------------


def _d(valor):
    return None if valor is None else Decimal(str(valor))


def _par(etiqueta, actual, anterior, unidad):
    """One comparison row: both values, the variation and its percentage; None wherever a side is reserved."""
    a, b = _d(actual), _d(anterior)
    variacion = None if a is None or b is None else a - b
    relativa = None if variacion is None or not b else variacion / abs(b) * 100
    return {
        "indicador": etiqueta,
        "unidad": unidad,
        "actual": _texto(a),
        "anterior": _texto(b),
        "variacion": _texto(variacion),
        "variacion_pct": None if relativa is None else str(round(relativa, 4)),
    }


def _buscar(lista, clave):
    for c in lista:
        if c["clave"] == clave:
            return c
    return {"predios": 0}


ETIQUETA_CONFIANZA = {"CONFIABLE": "confiable", "DUDOSO": "dudoso", "SIN_LEVANTAR": "sin levantar",
                      "SIN_DATO": "sin dato de confianza"}
ETIQUETA_NIVEL = {"PAR": "en ambos (par)", "SOLO_RENTAS": "solo en rentas", "SOLO_CATASTRO": "solo en catastro",
                  "AMBIGUO": "ambiguos", "SIN_DATO": "sin estado"}


def comparar(actual, anterior):
    """The same figures of two cortes side by side, with their variation."""
    filas = []
    t, u = actual["totales"], anterior["totales"]
    filas.append(_par("Predios en la corrida", t["predios"], u["predios"], "predios"))
    for campo, etiqueta in (
        ("autoavaluo_declarado", "Autoavalúo declarado"),
        ("autoavaluo_catastro", "Autoavalúo de catastro"),
        ("delta_autoavaluo", "Δ autoavalúo"),
        ("impuesto_declarado", "Impuesto declarado"),
        ("impuesto_catastro", "Impuesto con catastro"),
        ("delta_impuesto", "Δ impuesto"),
    ):
        filas.append(_par(etiqueta, t[campo], u[campo], "soles"))
    claves = [c for c in ORDEN_CONFIANZA if any(x["clave"] == c for x in actual["confianza"] + anterior["confianza"])]
    for c in claves:
        a, b = _buscar(actual["confianza"], c), _buscar(anterior["confianza"], c)
        filas.append(_par(f"Predios {ETIQUETA_CONFIANZA[c]}", a.get("predios"), b.get("predios"), "predios"))
        filas.append(_par(f"Δ impuesto {ETIQUETA_CONFIANZA[c]}", a.get("delta_impuesto"), b.get("delta_impuesto"),
                          "soles"))
    p, q = actual["predial"], anterior["predial"]
    filas.append(_par("Contribuyentes del predial", p["contribuyentes"], q["contribuyentes"], "contribuyentes"))
    for campo, etiqueta in (("declarado", "Predial declarado"), ("motor_rentas", "Predial del motor de rentas"),
                            ("motor_catastro", "Predial con catastro")):
        filas.append(_par(etiqueta, p[campo], q[campo], "soles"))
    estados = [e for e in ORDEN_NIVEL if any(x["clave"] == e for x in actual["niveles"] + anterior["niveles"])]
    for e in estados:
        a, b = _buscar(actual["niveles"], e), _buscar(anterior["niveles"], e)
        filas.append(_par(f"Niveles {ETIQUETA_NIVEL[e]}", a.get("niveles", 0), b.get("niveles", 0), "niveles"))
    categorias = []
    claves_cat = sorted(
        {c["clave"] for c in actual["categorias"] + anterior["categorias"] if c["confianza"] == "CONFIABLE"},
        key=lambda k: _orden(k, ORDEN_CATEGORIA),
    )
    confiables = lambda ind: [c for c in ind["categorias"] if c["confianza"] == "CONFIABLE"]  # noqa: E731
    for k in claves_cat:
        a, b = _buscar(confiables(actual), k), _buscar(confiables(anterior), k)
        categorias.append({
            "categoria": k,
            "predios": _par("predios", a.get("predios", 0), b.get("predios", 0), "predios"),
            "delta_impuesto": _par("Δ impuesto", a.get("delta_impuesto"), b.get("delta_impuesto"), "soles"),
        })
    return {
        "fecha_corte_anterior": anterior["corte"]["fecha_corte"],
        "filas": filas,
        "categorias_confiables": categorias,
    }
