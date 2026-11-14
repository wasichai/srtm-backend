"""The attributes the edición's textos cite, from indicadores.json (srtm-backend#301): {predios},
{delta-impuesto-confiable}, {pct-predios-dudoso}… Nothing in a texto is typed by hand: validar fails on a figure
that is not one of these. A reserved amount reads «reservado».
"""

from decimal import Decimal

from . import informe_comun as ic
from .informe_indicadores import ORDEN_CATEGORIA

RESERVADO = "reservado"

SUFIJO_CONFIANZA = {"CONFIABLE": "confiable", "DUDOSO": "dudoso", "SIN_LEVANTAR": "sin-levantar",
                    "SIN_DATO": "sin-dato"}
SUFIJO_NIVEL = {"PAR": "par", "SOLO_RENTAS": "solo-rentas", "SOLO_CATASTRO": "solo-catastro", "AMBIGUO": "ambiguo",
                "SIN_DATO": "sin-estado"}
NOMBRE_CATEGORIA = {
    "OMISO": "omiso",
    "SUBVALUADO": "subvaluado",
    "SOBREVALUADO": "sobrevaluado",
    "COINCIDE": "coincide",
    "SIN_VALOR": "sin valor",
    "SIN_CATASTRO": "sin catastro",
}


def _monto(valor, reservado, signo=False):
    return RESERVADO if reservado else ic.soles(valor, signo)


def _pct(parte, total, signo=False):
    if parte is None or not total:
        return "—"
    return ic.porcentaje(Decimal(str(parte)) / Decimal(str(total)) * 100, signo)


def _buscar(lista, clave):
    for c in lista:
        if c["clave"] == clave:
            return c
    return {"predios": 0, "reservado": False}


def cifras(ind, edicion, comparacion=None):
    c = {}
    corte = ind["corte"]
    c["organizacion"] = corte["organizacion"]
    c["municipalidad"] = edicion["municipalidad"]
    c["fecha-corte"] = ic.fecha_larga(corte["fecha_corte"])
    c["anio"] = str(corte["anio"])
    c["version-motor"] = corte["version_motor"] or "—"
    c["region"] = corte["region"] or "—"
    c["version-catastro"] = corte["version_catastro"] or "—"
    c["umbral-celda"] = str(ind["umbral"])
    corrida = ind["corrida"] or {}
    c["corrida-estado"] = corrida.get("estado") or "sin corrida"
    c["corrida-origen"] = corrida.get("origen") or "—"
    c["corrida-fecha"] = ic.fecha_larga(corrida.get("terminado")) if corrida.get("terminado") else "—"

    t = ind["totales"]
    r = t["reservado"]
    c["predios"] = ic.numero(t["predios"])
    c["predios-estimados"] = ic.numero(t["estimados"])
    c["autoavaluo-declarado"] = _monto(t["autoavaluo_declarado"], r)
    c["autoavaluo-catastro"] = _monto(t["autoavaluo_catastro"], r)
    c["delta-autoavaluo"] = _monto(t["delta_autoavaluo"], r, True)
    c["impuesto-declarado"] = _monto(t["impuesto_declarado"], r)
    c["impuesto-catastro"] = _monto(t["impuesto_catastro"], r)
    c["delta-impuesto"] = _monto(t["delta_impuesto"], r, True)
    c["pct-delta-impuesto"] = RESERVADO if r else _pct(t["delta_impuesto"], t["impuesto_declarado"], True)
    c["pct-delta-autoavaluo"] = RESERVADO if r else _pct(t["delta_autoavaluo"], t["autoavaluo_declarado"], True)

    for clave, sufijo in SUFIJO_CONFIANZA.items():
        g = _buscar(ind["confianza"], clave)
        c[f"predios-{sufijo}"] = ic.numero(g["predios"])
        c[f"pct-predios-{sufijo}"] = _pct(g["predios"], t["predios"])
        c[f"delta-impuesto-{sufijo}"] = _monto(g.get("delta_impuesto"), g["reservado"], True)
        c[f"delta-autoavaluo-{sufijo}"] = _monto(g.get("delta_autoavaluo"), g["reservado"], True)

    # the categoría with the largest Δ impuesto (and the smallest, the most negative) among the confiables
    confiables = [x for x in ind["categorias"] if x["confianza"] == "CONFIABLE" and x["delta_impuesto"] is not None]
    nombre = lambda k: NOMBRE_CATEGORIA.get(k, k.lower().replace("_", " "))  # noqa: E731
    if confiables:
        mayor = max(confiables, key=lambda x: (Decimal(x["delta_impuesto"]), x["clave"]))
        menor = min(confiables, key=lambda x: (Decimal(x["delta_impuesto"]), x["clave"]))
        c["categoria-mayor-delta"] = nombre(mayor["clave"])
        c["delta-categoria-mayor"] = ic.soles(mayor["delta_impuesto"], True)
        c["categoria-menor-delta"] = nombre(menor["clave"])
        c["delta-categoria-menor"] = ic.soles(menor["delta_impuesto"], True)
    else:
        for k in ("categoria-mayor-delta", "delta-categoria-mayor", "categoria-menor-delta", "delta-categoria-menor"):
            c[k] = "—"
    for k in ORDEN_CATEGORIA:
        g = _buscar([x for x in ind["categorias"] if x["confianza"] == "CONFIABLE"], k)
        sufijo = k.lower().replace("_", "-")
        c[f"predios-confiable-{sufijo}"] = ic.numero(g["predios"])
        c[f"delta-impuesto-confiable-{sufijo}"] = _monto(g.get("delta_impuesto"), g["reservado"], True)
    c["clases"] = ic.numero(len({x["clave"] for x in ind["clases"]}))

    p = ind["predial"]
    c["contribuyentes"] = ic.numero(p["contribuyentes"])
    c["predial-declarado"] = _monto(p["declarado"], p["reservado"])
    c["predial-motor-rentas"] = _monto(p["motor_rentas"], p["reservado"])
    c["predial-motor-catastro"] = _monto(p["motor_catastro"], p["reservado"])
    if p["reservado"] or None in (p["declarado"], p["motor_rentas"], p["motor_catastro"]):
        for k in ("predial-dif-rentas", "predial-dif-catastro", "pct-predial-dif-catastro"):
            c[k] = RESERVADO if p["reservado"] else "—"
    else:
        dif_rentas = Decimal(p["motor_rentas"]) - Decimal(p["declarado"])
        dif_catastro = Decimal(p["motor_catastro"]) - Decimal(p["motor_rentas"])
        c["predial-dif-rentas"] = ic.soles(dif_rentas, True)
        c["predial-dif-catastro"] = ic.soles(dif_catastro, True)
        c["pct-predial-dif-catastro"] = _pct(dif_catastro, p["motor_rentas"], True)

    total_niveles = sum(n["niveles"] for n in ind["niveles"])
    c["niveles"] = ic.numero(total_niveles)
    for clave, sufijo in SUFIJO_NIVEL.items():
        g = _buscar(ind["niveles"], clave)
        c[f"niveles-{sufijo}"] = ic.numero(g.get("niveles", 0))
        c[f"pct-niveles-{sufijo}"] = _pct(g.get("niveles", 0), total_niveles)
    m = ind["niveles_catastro_mayor"]
    c["niveles-catastro-mayor"] = ic.numero(m["niveles"])
    c["predios-catastro-mayor"] = ic.numero(m["predios"])
    c["delta-area-niveles"] = RESERVADO if m["reservado"] else (
        "—" if m["delta_area"] is None else ic.numero(m["delta_area"]) + " m²")
    c["avisos-corte"] = ic.numero(len(corte["avisos"]))

    if comparacion:
        c["comparacion"] = "sí"
        c["fecha-corte-anterior"] = ic.fecha_larga(comparacion["fecha_corte_anterior"])
        for fila in comparacion["filas"]:
            clave = _clave_fila(fila["indicador"])
            formato = ic.soles if fila["unidad"] == "soles" else ic.numero
            c[f"anterior-{clave}"] = RESERVADO if fila["anterior"] is None else formato(fila["anterior"])
            c[f"var-{clave}"] = RESERVADO if fila["variacion"] is None else formato(fila["variacion"], signo=True)
            pct = fila["variacion_pct"]
            c[f"pct-var-{clave}"] = "—" if pct is None else ic.porcentaje(pct, True)
    return c


def _clave_fila(indicador):
    """«Δ impuesto sin levantar» → delta-impuesto-sin-levantar."""
    texto = indicador.replace("Δ", "delta").lower().replace("ú", "u").replace("ó", "o").replace("í", "i")
    texto = texto.replace("(", "").replace(")", "")
    return "-".join(texto.split())


def adoc(c):
    """generado/cifras.adoc: the same attributes, for the person writing the textos to look up."""
    lineas = ["// Generado por informe.py: los atributos que citan los textos. No se edita a mano."]
    lineas += [f":{k}: {v}" for k, v in sorted(c.items())]
    return "\n".join(lineas) + "\n"

