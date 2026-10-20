"""Calcula los indicadores del informe y escribe las cifras y las tablas que la plantilla incluye.

Uso: EDICION=<carpeta> python3 -I herramientas/indicadores.py

Lee datos/mef/*.csv (mef.py), datos/padron/agregados.json (padron.py) y, si están, datos/ca/*.csv (recaudado mensual
de años anteriores de la Consulta Amigable) y datos/referencias.json (totales publicados, para conciliar). Escribe:

- generado/indicadores.json: todos los valores, sin redondear, y los controles de calidad;
- generado/cifras.adoc: un atributo por cifra que citan los textos ({rec-corte}, {var-propios}…);
- generado/tablas/*.adoc y *.csv: las tablas del informe, en AsciiDoc y en CSV agregado.

Ninguna cifra del informe se teclea: los textos usan los atributos y las tablas salen de aquí.
"""

import collections
import csv
import datetime
import email.utils
import json
import os
import sys
from decimal import ROUND_HALF_UP, Decimal

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from comun import edicion, escribir_csv, escribir_json, parametros  # noqa: E402

CERO = Decimal(0)
UMBRAL_PIM = Decimal(5000)
UMBRAL_AVANCE = Decimal("0.25")
MESES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
         "noviembre", "diciembre"]
MES_CORTO = ["ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"]

# --- clasificación ------------------------------------------------------------------------------------------------
# la genérica 9 (saldos de balance) y el rubro 19 (endeudamiento) no son ingresos del año aunque el SIAF los registre
# como recaudados; el resto se agrupa por rubro (fuente de financiamiento)
CATEGORIAS = {
    "08": "Impuestos municipales",
    "09": "Recursos directamente recaudados",
    "07": "FONCOMUN",
    "18": "Canon, sobrecanon, regalías y participaciones",
    "13": "Donaciones y transferencias",
}
GRUPOS = {
    "Impuestos municipales": "Ingresos propios",
    "Recursos directamente recaudados": "Ingresos propios",
    "FONCOMUN": "Transferencias",
    "Canon, sobrecanon, regalías y participaciones": "Transferencias",
    "Donaciones y transferencias": "Transferencias",
    "Saldos de balance": "Financiamiento",
    "Endeudamiento": "Financiamiento",
    "Otros": "Otros",
}
ORDEN_GRUPOS = ["Ingresos propios", "Transferencias", "Financiamiento", "Otros"]
ARBITRIOS = {"LIMPIEZA PUBLICA": "Limpieza pública", "SERENAZGO": "Serenazgo", "PARQUES Y JARDINES": "Parques y jardines"}


def categoria(f):
    if f["GENERICA"] == "9":
        return "Saldos de balance"
    if f["RUBRO"] == "19":
        return "Endeudamiento"
    return CATEGORIAS.get(f["RUBRO"], "Otros")


def concepto_propio(f):
    """El concepto de un ingreso propio (rubros 08 y 09), por su clasificador de ingresos."""
    nombre = f["ESPECIFICA_DET_NOMBRE"].strip().upper()
    detalle = f["SUBGENERICA_DET_NOMBRE"].strip().upper()
    if nombre == "PREDIAL":
        return "Impuesto predial del año"
    if nombre.startswith("PREDIAL - REGULARIZ"):
        return "Impuesto predial: regularización"
    if nombre.startswith("ALCABALA"):
        return "Impuesto de alcabala"
    if "INTERESES POR SANCIONES" in nombre or "FRACCIONAMIENTO" in nombre or "SANCIONES DEROGADAS" in nombre:
        return "Intereses, fraccionamiento y sanciones tributarias"
    if nombre in ARBITRIOS:
        return "Arbitrios municipales"
    if nombre == "VENTA DE AGUA":
        return "Venta de agua"
    # no recurrente: se separa para que no distorsione la tendencia
    if "EJECUCION DE GARANTIA" in nombre.replace("Ó", "O"):
        return "Ejecución de garantías"
    if f["GENERICA"] == "5" and f["SUBGENERICA"] == "2":
        return "Multas no tributarias"
    if "ALQUILER" in detalle:
        return "Alquileres"
    if f["GENERICA"] == "3" and f["SUBGENERICA"] == "2":
        return "Derechos y tasas administrativos"
    return "Otros ingresos propios"


# --- lectura ------------------------------------------------------------------------------------------------------

def leer(ruta):
    with ruta.open(encoding="utf-8") as entrada:
        return list(csv.DictReader(entrada))


def monto(texto):
    return Decimal(texto.strip() or "0")


class Serie:
    """Sumas de PIA, PIM y recaudado por año, mes y llave (la categoría, el concepto…)."""

    def __init__(self):
        self.rec = collections.defaultdict(Decimal)
        self.pia = collections.defaultdict(Decimal)
        self.pim = collections.defaultdict(Decimal)

    def sumar(self, anio, mes, llave, pia, pim, rec):
        self.pia[(anio, llave)] += pia
        self.pim[(anio, llave)] += pim
        self.rec[(anio, mes, llave)] += rec

    # sin meses, el año entero (los años del comparativo, que es anual, se guardan con mes 0)
    def recaudado(self, anio, llave=None, meses=None):
        return sum((v for (a, m, k), v in self.rec.items()
                    if a == anio and (meses is None or m in meses) and (llave is None or k == llave)), CERO)

    def previsto(self, cual, anio, llave=None):
        tabla = self.pia if cual == "pia" else self.pim
        return sum((v for (a, k), v in tabla.items() if a == anio and (llave is None or k == llave)), CERO)

    def llaves(self):
        return sorted({k for (_, _, k) in self.rec} | {k for (_, k) in self.pim})


# --- formato ------------------------------------------------------------------------------------------------------
# miles con espacio fino que no se corta y coma decimal, como el resto de la documentación

def miles(valor, decimales=0):
    valor = Decimal(valor).quantize(Decimal(1).scaleb(-decimales), rounding=ROUND_HALF_UP)
    signo = "−" if valor < 0 else ""
    entero, _, fraccion = f"{abs(valor):.{decimales}f}".partition(".")
    grupos = []
    while entero:
        grupos.insert(0, entero[-3:])
        entero = entero[:-3]
    texto = " ".join(grupos)
    return signo + texto + ("," + fraccion if decimales else "")


# el signo va delante del símbolo: −S/ 90 000, como +S/ 90 000
def soles(valor):
    valor = Decimal(valor)
    return ("−" if valor < 0 else "") + "S/\u00a0" + miles(abs(valor))


def millones(valor):
    valor = Decimal(valor)
    return ("−" if valor < 0 else "") + "S/\u00a0" + miles(abs(valor) / Decimal(10**6), 1) + "\u00a0millones"


def pct(valor, decimales=1):
    return "—" if valor is None else f"{miles(valor, decimales)} %"


# sin sentido con montos negativos (extornos) o sin base: «—»
def variacion(actual, anterior):
    if anterior is None or anterior <= 0 or actual < 0:
        return None
    return (actual - anterior) / abs(anterior) * 100


def participacion(parte, total):
    return None if not total else parte / total * 100


def con_signo(valor, formato):
    if valor is None:
        return "—"
    return ("+" if valor > 0 else "") + formato(valor)


# --- tablas -------------------------------------------------------------------------------------------------------

def tabla(carpeta, nombre, titulo, columnas, filas, alineacion, pie=None, notas=None):
    """Escribe <nombre>.adoc (con la tabla y su título) y <nombre>.csv (los mismos valores sin formato)."""
    lineas = [f"[[tabla-{nombre}]]", f".{titulo}",
              f'[cols="{",".join(alineacion)}",options="header{",footer" if pie else ""}",stripes=even]', "|==="]
    lineas.append("|" + " |".join(columnas))
    for fila in filas + ([pie] if pie else []):
        lineas.append("|" + " |".join(str(c[0]) for c in fila))
    lineas.append("|===")
    if notas:
        lineas.append(f"[.nota-tabla]\n{notas}")
    (carpeta / f"{nombre}.adoc").write_text("\n".join(lineas) + "\n", encoding="utf-8")
    escribir_csv(carpeta / f"{nombre}.csv", columnas, [[c[1] for c in fila] for fila in filas + ([pie] if pie else [])])


def celda(texto, valor=None):
    return (texto, texto if valor is None else valor)


# --- principal ----------------------------------------------------------------------------------------------------

def main():
    p = parametros()
    e = edicion()
    anio, previo, historicos = p["anio"], p["anio_comparable"], p["anios_historicos"]
    meses_ytd = range(1, p["meses_completos"] + 1)
    ultimo_mes = MESES[p["meses_completos"] - 1]
    mef = e / "datos" / "mef"
    generado = e / "generado"
    tablas = generado / "tablas"
    tablas.mkdir(parents=True, exist_ok=True)

    por_categoria, por_grupo, por_concepto, por_arbitrio = Serie(), Serie(), Serie(), Serie()
    calidad = {"negativos": [], "meses": {}, "filas": {}}
    for fuente in [f for f in p["fuentes_mef"] if f["formato"] == "mensual"]:
        filas = leer(mef / f"{fuente['nombre']}.csv")
        calidad["filas"][fuente["nombre"]] = len(filas)
        for f in filas:
            a, m = int(f["ANO_DOC"]), int(f["MES_DOC"])
            pia, pim, rec = monto(f["MONTO_PIA"]), monto(f["MONTO_PIM"]), monto(f["MONTO_RECAUDADO"])
            cat = categoria(f)
            por_categoria.sumar(a, m, cat, pia, pim, rec)
            por_grupo.sumar(a, m, GRUPOS[cat], pia, pim, rec)
            if GRUPOS[cat] == "Ingresos propios":
                por_concepto.sumar(a, m, concepto_propio(f), pia, pim, rec)
                nombre = f["ESPECIFICA_DET_NOMBRE"].strip().upper()
                if nombre in ARBITRIOS:
                    por_arbitrio.sumar(a, m, ARBITRIOS[nombre], pia, pim, rec)
            if rec < 0:
                calidad["negativos"].append({"anio": a, "mes": m, "concepto": f["ESPECIFICA_DET_NOMBRE"].strip(),
                                             "monto": str(rec)})
        calidad["meses"][fuente["nombre"]] = sorted({int(f["MES_DOC"]) for f in filas})

    # años anteriores: el comparativo (anual, por clasificador) y, si está, el mensual de la Consulta Amigable
    comparativo = leer(mef / "comparativo-2012-2025.csv")
    anual_hist = Serie()
    for f in comparativo:
        cat = categoria(f)
        for h in historicos:
            anual_hist.sumar(h, 0, GRUPOS[cat], monto(f[f"MONTO_PIA_{h}"]), monto(f[f"MONTO_PIM_{h}"]),
                             monto(f[f"MONTO_RECAUDADO_{h}"]))
            anual_hist.sumar(h, 0, cat, CERO, CERO, CERO)
    hist_cat = Serie()
    for f in comparativo:
        for h in historicos:
            hist_cat.sumar(h, 0, categoria(f), monto(f[f"MONTO_PIA_{h}"]), monto(f[f"MONTO_PIM_{h}"]),
                           monto(f[f"MONTO_RECAUDADO_{h}"]))
    hist_concepto = Serie()
    for f in comparativo:
        if GRUPOS[categoria(f)] == "Ingresos propios":
            for h in historicos:
                hist_concepto.sumar(h, 0, concepto_propio(f), CERO, CERO, monto(f[f"MONTO_RECAUDADO_{h}"]))

    # el mensual de años anteriores (consulta_amigable.py) viene por rubro, con los saldos de balance adentro: se
    # restan los del comparativo (su mes no se conoce; en los años con detalle se registran entre enero y junio)
    mensual_hist = Serie()
    ca = e / "datos" / "ca"
    excluidos = {int(k): v for k, v in p.get("proyeccion", {}).get("excluir", {}).items()}
    saldos_hist = {h: sum((monto(f[f"MONTO_RECAUDADO_{h}"]) for f in comparativo
                           if f["RUBRO"] in ("08", "09") and f["GENERICA"] == "9"), CERO) for h in historicos}
    anios_mensuales = [previo]
    for h in historicos:
        ruta = ca / f"recaudado-mensual-{h}.csv"
        if ruta.exists() and h not in excluidos:
            for f in leer(ruta):
                grupo = "Ingresos propios" if f["rubro"] in ("08", "09") else "Transferencias"
                mensual_hist.sumar(int(f["anio"]), int(f["mes"]), grupo, CERO, CERO, monto(f["recaudado"]))
            anios_mensuales.insert(0, h)

    # --- indicadores principales ----------------------------------------------------------------------------------
    total_corte = por_categoria.recaudado(anio)
    total_ytd = por_categoria.recaudado(anio, meses=meses_ytd)
    total_ytd_prev = por_categoria.recaudado(previo, meses=meses_ytd)
    g = {}
    for grupo in ORDEN_GRUPOS:
        g[grupo] = {
            "corte": por_grupo.recaudado(anio, grupo),
            "ytd": por_grupo.recaudado(anio, grupo, meses_ytd),
            "ytd_prev": por_grupo.recaudado(previo, grupo, meses_ytd),
            "anual_prev": por_grupo.recaudado(previo, grupo),
            "pia": por_grupo.previsto("pia", anio, grupo),
            "pim": por_grupo.previsto("pim", anio, grupo),
            "pim_prev": por_grupo.previsto("pim", previo, grupo),
        }
    sin_fin_ytd = total_ytd - g["Financiamiento"]["ytd"]
    sin_fin_ytd_prev = total_ytd_prev - g["Financiamiento"]["ytd_prev"]
    pim_total = por_categoria.previsto("pim", anio)
    pia_total = por_categoria.previsto("pia", anio)

    # el predial del año contra la emisión del padrón
    padron = json.loads((e / "datos" / "padron" / "agregados.json").read_text(encoding="utf-8"))
    emitido = Decimal(padron["totales"]["insoluto"])
    exigible = Decimal(padron["exigible_al_corte_si_fraccionan"])
    predial_corte = por_concepto.recaudado(anio, "Impuesto predial del año")
    predial_ytd = por_concepto.recaudado(anio, "Impuesto predial del año", meses_ytd)
    predial_prev_ytd = por_concepto.recaudado(previo, "Impuesto predial del año", meses_ytd)
    regularizacion_corte = por_concepto.recaudado(anio, "Impuesto predial: regularización")

    # proyección de los ingresos propios: lo recaudado a septiembre sobre la parte del año que, en los años con
    # serie mensual, se había recaudado a septiembre
    proporciones = {}
    for h in anios_mensuales:
        serie = por_grupo if h == previo else mensual_hist
        saldos = CERO if h == previo else saldos_hist[h]
        anual_h = serie.recaudado(h, "Ingresos propios") - saldos
        if anual_h:
            proporciones[h] = (serie.recaudado(h, "Ingresos propios", meses_ytd) - saldos) / anual_h
    proyeccion = None
    if proporciones:
        base = sum(proporciones.values()) / len(proporciones)
        ytd_propios = g["Ingresos propios"]["ytd"]
        proyeccion = {
            "anios": sorted(proporciones),
            "proporciones": {str(k): str(v) for k, v in proporciones.items()},
            "base": ytd_propios / base,
            "favorable": ytd_propios / min(proporciones.values()),
            "desfavorable": ytd_propios / max(proporciones.values()),
        }

    # --- conciliación con los totales publicados --------------------------------------------------------------------
    referencias = e / "datos" / "referencias.json"
    conciliacion = []
    if referencias.exists():
        for r in json.loads(referencias.read_text(encoding="utf-8"))["totales"]:
            if r["anio"] in (anio, previo):
                calculado = por_categoria.recaudado(r["anio"])
            else:
                calculado = sum((Decimal(f[f"MONTO_RECAUDADO_{r['anio']}"] or 0) for f in comparativo), CERO)
            conciliacion.append({**r, "calculado": str(calculado),
                                 "diferencia": str(calculado - Decimal(str(r["recaudado"])))})

    # --- cifras para los textos -------------------------------------------------------------------------------------
    c = {}

    def cifra(nombre, texto, valor=None):
        c[nombre] = {"texto": texto, "valor": None if valor is None else str(valor)}

    cifra("municipalidad", p["municipalidad"]["nombre"])
    cifra("anio", str(anio))
    cifra("anio-previo", str(previo))
    cifra("anio-siguiente", str(anio + 1))
    cifra("ultimo-vencimiento", MESES[3 * 4 - 2])
    cifra("corte", p["fecha_corte_texto"])
    cifra("ultimo-mes", ultimo_mes)
    cifra("periodo-ytd", f"enero a {ultimo_mes}")
    cifra("periodo", f"1 de enero al {p['fecha_corte_texto']}")
    cifra("version", p["version_documento"])
    cifra("elaboracion", p["fecha_elaboracion_texto"])
    cifra("sec-ejec", p["municipalidad"]["sec_ejec"])
    cifra("ubigeo", p["municipalidad"]["ubigeo"])
    manifiesto = json.loads((mef / "manifiesto.json").read_text(encoding="utf-8"))
    for f in manifiesto["fuentes"]:
        cifra(f"fuente-{f['nombre']}-url", f["url"])
        fecha = (email.utils.parsedate_to_datetime(f["last_modified"]) if f["last_modified"]
                 else datetime.datetime.fromisoformat(f["fecha_csv_en_zip"]))
        cifra(f"fuente-{f['nombre']}-fecha", f"{fecha.day} de {MESES[fecha.month - 1]} de {fecha.year}")
        cifra(f"fuente-{f['nombre']}-filas", miles(f["filas_guardadas"]), f["filas_guardadas"])
        cifra(f"fuente-{f['nombre']}-sha", f["sha256"][:16])
    cifra("rec-corte", millones(total_corte), total_corte)
    cifra("rec-ytd", millones(total_ytd), total_ytd)
    cifra("rec-ytd-prev", millones(total_ytd_prev), total_ytd_prev)
    cifra("var-total", con_signo(variacion(total_ytd, total_ytd_prev), pct), variacion(total_ytd, total_ytd_prev))
    cifra("rec-sin-fin-ytd", millones(sin_fin_ytd), sin_fin_ytd)
    cifra("rec-sin-fin-ytd-prev", millones(sin_fin_ytd_prev), sin_fin_ytd_prev)
    cifra("var-sin-fin", con_signo(variacion(sin_fin_ytd, sin_fin_ytd_prev), pct), variacion(sin_fin_ytd, sin_fin_ytd_prev))
    cifra("pim", millones(pim_total), pim_total)
    cifra("pia", millones(pia_total), pia_total)
    cifra("avance-pim", pct(participacion(total_corte, pim_total)), participacion(total_corte, pim_total))
    claves = {"Ingresos propios": "propios", "Transferencias": "transf", "Financiamiento": "fin"}
    for grupo, clave in claves.items():
        d = g[grupo]
        cifra(f"rec-{clave}-corte", millones(d["corte"]), d["corte"])
        cifra(f"rec-{clave}-corte-soles", soles(d["corte"]), d["corte"])
        cifra(f"rec-{clave}-ytd", millones(d["ytd"]), d["ytd"])
        cifra(f"rec-{clave}-ytd-soles", soles(d["ytd"]), d["ytd"])
        cifra(f"rec-{clave}-ytd-prev", millones(d["ytd_prev"]), d["ytd_prev"])
        cifra(f"rec-{clave}-ytd-prev-soles", soles(d["ytd_prev"]), d["ytd_prev"])
        cifra(f"var-{clave}", con_signo(variacion(d["ytd"], d["ytd_prev"]), pct), variacion(d["ytd"], d["ytd_prev"]))
        cifra(f"dif-{clave}", con_signo(d["ytd"] - d["ytd_prev"], soles), d["ytd"] - d["ytd_prev"])
        cifra(f"pim-{clave}", millones(d["pim"]), d["pim"])
        cifra(f"pim-{clave}-soles", soles(d["pim"]), d["pim"])
        cifra(f"avance-{clave}", pct(participacion(d["corte"], d["pim"])), participacion(d["corte"], d["pim"]))
        cifra(f"part-{clave}-ytd", pct(participacion(d["ytd"], sin_fin_ytd), 1), participacion(d["ytd"], sin_fin_ytd))
        cifra(f"part-{clave}-ytd-prev", pct(participacion(d["ytd_prev"], sin_fin_ytd_prev), 1),
              participacion(d["ytd_prev"], sin_fin_ytd_prev))
        cifra(f"rec-{clave}-anual-prev", millones(d["anual_prev"]), d["anual_prev"])
    for cat in CATEGORIAS.values():
        clave = {"Impuestos municipales": "imp", "Recursos directamente recaudados": "rdr", "FONCOMUN": "foncomun",
                 "Canon, sobrecanon, regalías y participaciones": "canon",
                 "Donaciones y transferencias": "donaciones"}[cat]
        ytd = por_categoria.recaudado(anio, cat, meses_ytd)
        ytd_prev = por_categoria.recaudado(previo, cat, meses_ytd)
        corte = por_categoria.recaudado(anio, cat)
        pim_cat = por_categoria.previsto("pim", anio, cat)
        cifra(f"rec-{clave}-ytd", millones(ytd), ytd)
        cifra(f"rec-{clave}-ytd-soles", soles(ytd), ytd)
        cifra(f"rec-{clave}-ytd-prev-soles", soles(ytd_prev), ytd_prev)
        cifra(f"rec-{clave}-corte-soles", soles(corte), corte)
        cifra(f"var-{clave}", con_signo(variacion(ytd, ytd_prev), pct), variacion(ytd, ytd_prev))
        cifra(f"pim-{clave}-soles", soles(pim_cat), pim_cat)
        cifra(f"avance-{clave}", pct(participacion(corte, pim_cat)), participacion(corte, pim_cat))
    for h in historicos:
        for grupo, clave in claves.items():
            v = anual_hist.recaudado(h, grupo)
            cifra(f"rec-{clave}-{h}", millones(v), v)
    for grupo, clave in claves.items():
        cifra(f"rec-{clave}-{previo}", millones(g[grupo]["anual_prev"]), g[grupo]["anual_prev"])

    cifra("emitido-predial", soles(emitido), emitido)
    cifra("emitido-predial-mill", millones(emitido), emitido)
    cifra("exigible-predial", soles(exigible), exigible)
    cifra("contribuyentes-padron", miles(padron["filas"]), padron["filas"])
    cifra("contribuyentes-con-impuesto", miles(padron["totales"]["con_impuesto"]), padron["totales"]["con_impuesto"])
    cifra("contribuyentes-minimo", miles(padron["por_tramo"]["Mínimo legal"]["contribuyentes"]),
          padron["por_tramo"]["Mínimo legal"]["contribuyentes"])
    cifra("cuotas-vencidas", str(padron["cuotas_vencidas_al_corte"]))
    cifra("norma-vencimientos", padron["norma_vencimientos"])
    for i, v in enumerate(padron["vencimientos"], 1):
        d = datetime.date.fromisoformat(v)
        cifra(f"venc-{i}", f"{d.day} de {MESES[d.month - 1]}")
    normas = p.get("normas_municipales", [])
    tabla(tablas, "normas", "Normas municipales consultadas",
          ["Norma", "Fecha", "Qué dispone"],
          [[celda(f"{n['url']}[{n['norma']}]", n["norma"]),
            celda(datetime.date.fromisoformat(n["fecha"]).strftime("%d-%m-%Y"), n["fecha"]), celda(n["dispone"])]
           for n in normas], ["3", "2", "6"], None,
          "Textos publicados en la plataforma gob.pe de la municipalidad. No se verificó su publicación en el diario.")
    eo = p.get("estructura_organica", {})
    cifra("rof", eo.get("rof", "no verificado"))
    cifra("rof-url", eo.get("rof_url", ""))
    cifra("estructura-en-uso", eo.get("en_uso", "no verificado"))
    fechas = [datetime.date.fromisoformat(v) for v in padron["vencimientos"]]
    cifra("vencimientos", ", ".join(f"{d.day} de {MESES[d.month - 1]}" for d in fechas[:-1]) +
          f" y {fechas[-1].day} de {MESES[fechas[-1].month - 1]}")
    cifra("base-afecta", millones(Decimal(padron["totales"]["base_afecta"])), padron["totales"]["base_afecta"])
    cifra("predial-corte", soles(predial_corte), predial_corte)
    cifra("predial-ytd", soles(predial_ytd), predial_ytd)
    cifra("predial-ytd-prev", soles(predial_prev_ytd), predial_prev_ytd)
    cifra("var-predial", con_signo(variacion(predial_ytd, predial_prev_ytd), pct), variacion(predial_ytd, predial_prev_ytd))
    cifra("regularizacion-corte", soles(regularizacion_corte), regularizacion_corte)
    cobro = participacion(predial_corte, emitido)
    cobro_exigible = participacion(predial_corte, exigible)
    cifra("cobro-predial", pct(cobro), cobro)
    cifra("cobro-predial-exigible", pct(cobro_exigible), cobro_exigible)
    cifra("pendiente-emitido", soles(emitido - predial_corte), emitido - predial_corte)
    cifra("pendiente-exigible", soles(exigible - predial_corte), exigible - predial_corte)
    for q in padron["concentracion"]:
        cifra(f"conc-{q['porcentaje_contribuyentes']}", pct(Decimal(q["participacion"])), q["participacion"])
        cifra(f"conc-{q['porcentaje_contribuyentes']}-n", miles(q["contribuyentes"]), q["contribuyentes"])
    cifra("mitad-n", miles(padron["contribuyentes_para_mitad_del_insoluto"]),
          padron["contribuyentes_para_mitad_del_insoluto"])
    cifra("recalculo-coinciden", miles(padron["control_recalculo"]["coinciden"]), padron["control_recalculo"]["coinciden"])
    cifra("uit", soles(Decimal(padron["parametros"]["uit"])), padron["parametros"]["uit"])
    alicuotas = [pct(Decimal(x), 1 if Decimal(x) % 1 else 0) for x in padron["parametros"]["alicuotas"]]
    cifra("alicuotas", ", ".join(alicuotas[:-1]) + " y " + alicuotas[-1])
    cifra("limites-uit", " y ".join(padron["parametros"]["limites_uit"]) + " UIT")
    minimo_pct = Decimal(padron["parametros"]["minimo"]) / Decimal(padron["parametros"]["uit"]) * 100
    cifra("minimo-pct", pct(minimo_pct), minimo_pct)
    for q in padron["concentracion"]:
        cifra(f"conc-{q['porcentaje_contribuyentes']}-pct", pct(Decimal(q["porcentaje_contribuyentes"]), 0))
    cifra("minimo-predial", f"S/ {miles(Decimal(padron['parametros']['minimo']), 2)}", padron["parametros"]["minimo"])
    arb_ytd = por_concepto.recaudado(anio, "Arbitrios municipales", meses_ytd)
    arb_prev = por_concepto.recaudado(previo, "Arbitrios municipales", meses_ytd)
    cifra("arbitrios-ytd", soles(arb_ytd), arb_ytd)
    cifra("arbitrios-ytd-prev", soles(arb_prev), arb_prev)
    cifra("var-arbitrios", con_signo(variacion(arb_ytd, arb_prev), pct), variacion(arb_ytd, arb_prev))
    for h in historicos:
        v = hist_concepto.recaudado(h, "Arbitrios municipales")
        cifra(f"arbitrios-{h}", soles(v), v)
    cifra(f"arbitrios-{previo}", soles(por_concepto.recaudado(previo, "Arbitrios municipales")),
          por_concepto.recaudado(previo, "Arbitrios municipales"))
    canon_minero = {a: sum((monto(f["MONTO_RECAUDADO"]) for f in leer(mef / f"ingreso-{a}.csv")
                            if "CANON MINERO" in f["ESPECIFICA_DET_NOMBRE"].upper() and int(f["MES_DOC"]) in meses_ytd),
                           CERO) for a in (anio, previo)}
    cifra("canon-minero-ytd", millones(canon_minero[anio]), canon_minero[anio])
    cifra("canon-minero-ytd-prev", millones(canon_minero[previo]), canon_minero[previo])
    cifra("var-canon-minero", con_signo(variacion(canon_minero[anio], canon_minero[previo]), pct),
          variacion(canon_minero[anio], canon_minero[previo]))
    # el canon minero llega en una transferencia anual: el mes y el monto de la mayor de cada año
    for a, clave in ((anio, "canon-mes"), (previo, "canon-mes-prev")):
        por_mes = collections.defaultdict(Decimal)
        for f in leer(mef / f"ingreso-{a}.csv"):
            if "CANON MINERO" in f["ESPECIFICA_DET_NOMBRE"].upper():
                por_mes[int(f["MES_DOC"])] += monto(f["MONTO_RECAUDADO"])
        mayor = max(por_mes, key=por_mes.get)
        cifra(clave, MESES[mayor - 1])
        cifra(f"{clave}-monto", millones(por_mes[mayor]), por_mes[mayor])
        cifra(f"{clave}-part", pct(participacion(por_mes[mayor], sum(por_mes.values(), CERO))),
              participacion(por_mes[mayor], sum(por_mes.values(), CERO)))
    mes_max = max(range(1, 13), key=lambda m: por_categoria.recaudado(anio, meses=[m]))
    cifra("mes-maximo", MESES[mes_max - 1])
    cifra("rec-mes-maximo", millones(por_categoria.recaudado(anio, meses=[mes_max])),
          por_categoria.recaudado(anio, meses=[mes_max]))
    if proyeccion:
        for k in ("base", "favorable", "desfavorable"):
            cifra(f"proy-{k}", millones(proyeccion[k]), proyeccion[k])
            cifra(f"proy-{k}-soles", soles(proyeccion[k]), proyeccion[k])
        pim_propios = g["Ingresos propios"]["pim"]
        for k in ("base", "favorable", "desfavorable"):
            cifra(f"proy-{k}-pim", pct(participacion(proyeccion[k], pim_propios)), participacion(proyeccion[k], pim_propios))
        anual_prev = g["Ingresos propios"]["anual_prev"]
        cifra("var-proy", con_signo(variacion(proyeccion["base"], anual_prev), pct), variacion(proyeccion["base"], anual_prev))
        for k in ("favorable", "desfavorable"):
            cifra(f"var-proy-{k}", con_signo(variacion(proyeccion[k], anual_prev), pct), variacion(proyeccion[k], anual_prev))
        falta = proyeccion["base"] - g["Ingresos propios"]["corte"]
        cifra("proy-falta", soles(falta), falta)
        for h, v in proporciones.items():
            cifra(f"proy-prop-{h}", pct(v * 100), v * 100)
        cifra("proy-anios", " y ".join(str(a) for a in proyeccion["anios"]))
        cifra("proy-excluidos", "; ".join(f"{k}: {v}" for k, v in excluidos.items()) or "ninguno")
        cifra("proy-n-anios", str(len(proyeccion["anios"])))
    # partidas de ingresos propios con previsión que casi no recaudan: PIM de al menos UMBRAL_PIM y avance menor que
    # UMBRAL_AVANCE al corte (umbrales fijos, antes de ver los datos: una cuarta parte del año transcurrido)
    partidas = collections.defaultdict(lambda: [CERO, CERO])
    for f in leer(mef / f"ingreso-{anio}.csv"):
        if GRUPOS[categoria(f)] == "Ingresos propios":
            k = f["ESPECIFICA_DET_NOMBRE"].strip()
            partidas[k][0] += monto(f["MONTO_PIM"])
            partidas[k][1] += monto(f["MONTO_RECAUDADO"])
    sin_recaudo = sorted(((k, pim_k, rec_k) for k, (pim_k, rec_k) in partidas.items()
                          if pim_k >= UMBRAL_PIM and rec_k < pim_k * UMBRAL_AVANCE), key=lambda x: -x[1])
    pim_sin = sum((x[1] for x in sin_recaudo), CERO)
    rec_sin = sum((x[2] for x in sin_recaudo), CERO)
    cifra("sin-recaudo-n", str(len(sin_recaudo)))
    cifra("sin-recaudo-pim", soles(pim_sin), pim_sin)
    cifra("sin-recaudo-rec", soles(rec_sin), rec_sin)
    cifra("sin-recaudo-part", pct(participacion(pim_sin, g["Ingresos propios"]["pim"])),
          participacion(pim_sin, g["Ingresos propios"]["pim"]))
    cifra("umbral-pim", soles(UMBRAL_PIM), UMBRAL_PIM)
    cifra("umbral-avance", pct(UMBRAL_AVANCE * 100, 0), UMBRAL_AVANCE * 100)
    if sin_recaudo:
        cifra("sin-recaudo-mayor", sin_recaudo[0][0].capitalize())
        cifra("sin-recaudo-mayor-pim", soles(sin_recaudo[0][1]), sin_recaudo[0][1])
        cifra("sin-recaudo-mayor-rec", soles(sin_recaudo[0][2]), sin_recaudo[0][2])
    tabla(tablas, "sin-recaudo", f"Partidas de ingresos propios previstas que recaudan poco, {anio} (soles)",
          ["Partida (específica de ingreso)", f"PIM {anio}", "Recaudado al corte", "Avance"],
          [[celda(k.capitalize()), celda(miles(a), a), celda(miles(b), b), celda(pct(participacion(b, a)), participacion(b, a))]
           for k, a, b in sin_recaudo], ["5", ">2", ">2", ">2"],
          [celda("Total"), celda(miles(pim_sin), pim_sin), celda(miles(rec_sin), rec_sin),
           celda(pct(participacion(rec_sin, pim_sin)), participacion(rec_sin, pim_sin))],
          f"Partidas con PIM de al menos {soles(UMBRAL_PIM)} y un avance menor que {pct(UMBRAL_AVANCE * 100, 0)} al corte.")

    minimo = padron["por_tramo"]["Mínimo legal"]
    cifra("minimo-insoluto", soles(Decimal(minimo["insoluto"])), minimo["insoluto"])
    cifra("minimo-part", pct(participacion(Decimal(minimo["insoluto"]), emitido)), participacion(Decimal(minimo["insoluto"]), emitido))
    cifra("minimo-part-contribuyentes", pct(participacion(Decimal(minimo["contribuyentes"]),
                                                          Decimal(padron["totales"]["con_impuesto"]))),
          participacion(Decimal(minimo["contribuyentes"]), Decimal(padron["totales"]["con_impuesto"])))
    predial_total_ytd = predial_ytd + por_concepto.recaudado(anio, "Impuesto predial: regularización", meses_ytd)
    cifra("part-predial-propios", pct(participacion(predial_total_ytd, g["Ingresos propios"]["ytd"])),
          participacion(predial_total_ytd, g["Ingresos propios"]["ytd"]))
    garantia = hist_concepto.recaudado(historicos[0], "Ejecución de garantías")
    cifra("garantia-hist", soles(garantia), garantia)
    cifra("anio-garantia", str(historicos[0]))
    sin_garantia = anual_hist.recaudado(historicos[0], "Ingresos propios") - garantia
    cifra("propios-hist0-sin-garantia", millones(sin_garantia), sin_garantia)
    for concepto, clave in (("Impuesto predial: regularización", "reg"), ("Derechos y tasas administrativos", "derechos"),
                            ("Venta de agua", "agua"), ("Impuesto de alcabala", "alcabala"),
                            ("Multas no tributarias", "multas")):
        ytd = por_concepto.recaudado(anio, concepto, meses_ytd)
        prev = por_concepto.recaudado(previo, concepto, meses_ytd)
        cifra(f"{clave}-ytd", soles(ytd), ytd)
        cifra(f"{clave}-ytd-prev", soles(prev), prev)
        cifra(f"var-{clave}", con_signo(variacion(ytd, prev), pct), variacion(ytd, prev))
        cifra(f"dif-{clave}", con_signo(ytd - prev, soles), ytd - prev)
    cifra("dif-predial", con_signo(predial_ytd - predial_prev_ytd, soles), predial_ytd - predial_prev_ytd)
    cifra("dif-arbitrios", con_signo(arb_ytd - arb_prev, soles), arb_ytd - arb_prev)
    # el endeudamiento del año anterior frente a su PIM, y el exceso del total sobre el PIM ese año
    deuda_prev = por_categoria.recaudado(previo, "Endeudamiento")
    deuda_pim_prev = por_categoria.previsto("pim", previo, "Endeudamiento")
    total_prev = por_categoria.recaudado(previo)
    pim_prev = por_categoria.previsto("pim", previo)
    cifra("deuda-prev", millones(deuda_prev), deuda_prev)
    cifra("deuda-exceso-prev", millones(deuda_prev - deuda_pim_prev), deuda_prev - deuda_pim_prev)
    cifra("total-exceso-prev", millones(total_prev - pim_prev), total_prev - pim_prev)
    cifra("pim-prev", millones(pim_prev), pim_prev)
    cifra("rec-total-prev", millones(total_prev), total_prev)
    # el tamaño de cada riesgo frente a lo que la municipalidad recauda por sí misma en un año
    anual_propios_prev = g["Ingresos propios"]["anual_prev"]
    cifra("pend-part-propios", pct(participacion(exigible - predial_corte, anual_propios_prev)),
          participacion(exigible - predial_corte, anual_propios_prev))
    cifra("sin-recaudo-part-anual", pct(participacion(pim_sin - rec_sin, anual_propios_prev)),
          participacion(pim_sin - rec_sin, anual_propios_prev))
    cifra("negativos-n", str(len(calidad["negativos"])))
    negativos = sum((Decimal(n["monto"]) for n in calidad["negativos"]), CERO)
    cifra("negativos-suma", soles(negativos), negativos)

    lineas = ["// generado por herramientas/indicadores.py: no editar a mano"]
    for nombre, v in c.items():
        lineas.append(f":{nombre}: {v['texto']}")
    (generado / "cifras.adoc").write_text("\n".join(lineas) + "\n", encoding="utf-8")

    # --- tablas -----------------------------------------------------------------------------------------------------
    ytd_txt = f"Ene–{MES_CORTO[p['meses_completos'] - 1].capitalize()}"

    filas = []
    for cat in list(CATEGORIAS.values()) + ["Saldos de balance", "Endeudamiento"]:
        pim_c = por_categoria.previsto("pim", anio, cat)
        corte = por_categoria.recaudado(anio, cat)
        ytd = por_categoria.recaudado(anio, cat, meses_ytd)
        prev = por_categoria.recaudado(previo, cat, meses_ytd)
        if not any((pim_c, corte, ytd, prev)):
            continue
        v = variacion(ytd, prev)
        filas.append([celda(cat), celda(miles(por_categoria.previsto("pia", anio, cat)), por_categoria.previsto("pia", anio, cat)),
                      celda(miles(pim_c), pim_c), celda(miles(corte), corte),
                      celda(pct(participacion(corte, pim_c)), participacion(corte, pim_c)),
                      celda(miles(prev), prev), celda(miles(ytd), ytd), celda(con_signo(v, pct), v)])
    pie = [celda("Total"), celda(miles(pia_total), pia_total), celda(miles(pim_total), pim_total),
           celda(miles(total_corte), total_corte), celda(pct(participacion(total_corte, pim_total)),
                                                         participacion(total_corte, pim_total)),
           celda(miles(total_ytd_prev), total_ytd_prev), celda(miles(total_ytd), total_ytd),
           celda(con_signo(variacion(total_ytd, total_ytd_prev), pct), variacion(total_ytd, total_ytd_prev))]
    tabla(tablas, "rubros", f"Ingresos por fuente: previsión {anio}, recaudado al corte y comparación {ytd_txt} (soles)",
          ["Fuente", f"PIA {anio}", f"PIM {anio}", "Recaudado al corte", "Avance del PIM",
           f"{ytd_txt} {previo}", f"{ytd_txt} {anio}", "Variación"],
          filas, ["3", ">2", ">2", ">2", ">2", ">2", ">2", ">2"], pie,
          f"Fuente: MEF, Portal de Datos Abiertos, Presupuesto y Ejecución de Ingreso ({anio} y {previo}). "
          f"Recaudado al corte: enero al {p['fecha_corte_texto']}. La comparación usa meses completos.")

    filas = []
    total_prop = g["Ingresos propios"]["ytd"]
    for concepto in sorted(por_concepto.llaves(), key=lambda k: -por_concepto.recaudado(anio, k, meses_ytd)):
        ytd = por_concepto.recaudado(anio, concepto, meses_ytd)
        prev = por_concepto.recaudado(previo, concepto, meses_ytd)
        pim_c = por_concepto.previsto("pim", anio, concepto)
        corte = por_concepto.recaudado(anio, concepto)
        if not any((ytd, prev, pim_c)):
            continue
        v = variacion(ytd, prev)
        filas.append([celda(concepto), celda(miles(prev), prev), celda(miles(ytd), ytd),
                      celda(con_signo(ytd - prev, miles), ytd - prev), celda(con_signo(v, pct), v),
                      celda(pct(participacion(ytd, total_prop)), participacion(ytd, total_prop)),
                      celda(miles(pim_c), pim_c), celda(pct(participacion(corte, pim_c)), participacion(corte, pim_c))])
    d = g["Ingresos propios"]
    pie = [celda("Total de ingresos propios"), celda(miles(d["ytd_prev"]), d["ytd_prev"]), celda(miles(d["ytd"]), d["ytd"]),
           celda(con_signo(d["ytd"] - d["ytd_prev"], miles), d["ytd"] - d["ytd_prev"]),
           celda(con_signo(variacion(d["ytd"], d["ytd_prev"]), pct), variacion(d["ytd"], d["ytd_prev"])),
           celda(pct(Decimal(100))), celda(miles(d["pim"]), d["pim"]),
           celda(pct(participacion(d["corte"], d["pim"])), participacion(d["corte"], d["pim"]))]
    tabla(tablas, "conceptos", f"Ingresos propios por concepto, {ytd_txt} {previo} y {anio} (soles)",
          ["Concepto", f"{ytd_txt} {previo}", f"{ytd_txt} {anio}", "Diferencia", "Variación", "Peso",
           f"PIM {anio}", "Avance al corte"],
          filas, ["4", ">2", ">2", ">2", ">2", ">2", ">2", ">2"], pie,
          "Ingresos propios: rubros 08 (impuestos municipales) y 09 (recursos directamente recaudados), sin saldos de "
          "balance. Avance al corte: lo recaudado hasta la fecha de corte sobre el PIM del concepto.")

    filas = []
    for concepto in sorted(set(por_concepto.llaves()) | set(hist_concepto.llaves()),
                           key=lambda k: -por_concepto.recaudado(previo, k)):
        valores = [hist_concepto.recaudado(h, concepto) for h in historicos] + [por_concepto.recaudado(previo, concepto),
                                                                               por_concepto.recaudado(anio, concepto)]
        if any(valores):
            filas.append([celda(concepto)] + [celda(miles(v), v) for v in valores])
    totales = [hist_concepto.recaudado(h) for h in historicos] + [por_concepto.recaudado(previo), por_concepto.recaudado(anio)]
    tabla(tablas, "conceptos-historico", "Ingresos propios por concepto y año (soles)",
          ["Concepto"] + [str(h) for h in historicos] + [str(previo), f"{anio} (al corte)"],
          filas, ["4"] + [">2"] * (len(historicos) + 2),
          [celda("Total de ingresos propios")] + [celda(miles(v), v) for v in totales],
          f"Años completos, salvo {anio}, hasta el {p['fecha_corte_texto']}. Sin saldos de balance.")

    filas = []
    for nombre in ARBITRIOS.values():
        valores = [hist_concepto_arbitrio(comparativo, h, nombre) for h in historicos]
        anual_prev = por_arbitrio.recaudado(previo, nombre)
        ytd = por_arbitrio.recaudado(anio, nombre, meses_ytd)
        prev = por_arbitrio.recaudado(previo, nombre, meses_ytd)
        v = variacion(ytd, prev)
        filas.append([celda(nombre)] + [celda(miles(x), x) for x in valores] +
                     [celda(miles(anual_prev), anual_prev), celda(miles(prev), prev), celda(miles(ytd), ytd),
                      celda(con_signo(v, pct), v)])
    tabla(tablas, "arbitrios", f"Recaudación de arbitrios municipales por servicio (soles)",
          ["Servicio"] + [str(h) for h in historicos] + [str(previo), f"{ytd_txt} {previo}", f"{ytd_txt} {anio}",
                                                         "Variación"],
          filas, ["3"] + [">2"] * (len(historicos) + 4), None,
          "Años completos según el comparativo del MEF y el archivo anual; el período parcial compara meses completos.")

    filas = []
    for h in historicos + [previo]:
        if h == previo:
            vals = {gr: por_grupo.recaudado(previo, gr) for gr in ORDEN_GRUPOS}
            pim_h = por_categoria.previsto("pim", previo)
        else:
            vals = {gr: anual_hist.recaudado(h, gr) for gr in ORDEN_GRUPOS}
            pim_h = anual_hist.previsto("pim", h)
        tot = sum(vals.values(), CERO)
        filas.append([celda(str(h))] + [celda(miles(vals[gr]), vals[gr]) for gr in ORDEN_GRUPOS[:3]] +
                     [celda(miles(tot), tot), celda(miles(pim_h), pim_h)])
    vals = {gr: g[gr]["corte"] for gr in ORDEN_GRUPOS}
    tot = sum(vals.values(), CERO)
    filas.append([celda(f"{anio} (al corte)")] + [celda(miles(vals[gr]), vals[gr]) for gr in ORDEN_GRUPOS[:3]] +
                 [celda(miles(tot), tot), celda(miles(pim_total), pim_total)])
    tabla(tablas, "historico", "Recaudado anual por grupo de ingreso (soles)",
          ["Año", "Ingresos propios", "Transferencias", "Financiamiento", "Total recaudado", "PIM"],
          filas, ["2", ">2", ">2", ">2", ">2", ">2"], None,
          f"{', '.join(str(h) for h in historicos)}: comparativo de ingresos 2012-{previo} del MEF; {previo} y {anio}: "
          "archivos anuales. Financiamiento: saldos de balance y endeudamiento. El grupo «Otros» no tiene montos.")

    filas = []
    for m in range(1, 13):
        fila = [celda(MESES[m - 1].capitalize())]
        for gr in ("Ingresos propios", "Transferencias", "Financiamiento"):
            prev = por_grupo.recaudado(previo, gr, [m])
            act = por_grupo.recaudado(anio, gr, [m]) if m <= p["meses_completos"] + 1 else None
            fila += [celda(miles(prev), prev), celda("—" if act is None else miles(act), act)]
        filas.append(fila)
    tabla(tablas, "mensual", f"Recaudado mensual por grupo, {previo} y {anio} (soles)",
          ["Mes", f"Propios {previo}", f"Propios {anio}", f"Transf. {previo}", f"Transf. {anio}",
           f"Financ. {previo}", f"Financ. {anio}"],
          filas, ["2", ">2", ">2", ">2", ">2", ">2", ">2"], None,
          f"{MESES[p['meses_completos']].capitalize()} de {anio}: hasta el {p['fecha_corte_texto']} (parcial). "
          "Mes de registro en el SIAF, no de pago del contribuyente.")

    filas = [[celda(k), celda(miles(v["contribuyentes"]), v["contribuyentes"]),
              celda("—" if v.get("suprimido") else miles(Decimal(v["insoluto"])), v.get("insoluto")),
              celda("—" if v.get("suprimido") else pct(participacion(Decimal(v["insoluto"]), emitido)),
                    None if v.get("suprimido") else participacion(Decimal(v["insoluto"]), emitido))]
             for k, v in padron["por_rango_de_impuesto"].items()]
    tabla(tablas, "rangos-predial", f"Emisión del impuesto predial {anio} por monto anual del contribuyente",
          ["Impuesto anual", "Contribuyentes", "Impuesto emitido (S/)", "Participación"],
          filas, ["4", ">2", ">3", ">2"],
          [celda("Total con impuesto"), celda(miles(padron["totales"]["con_impuesto"]), padron["totales"]["con_impuesto"]),
           celda(miles(emitido), emitido), celda(pct(Decimal(100)))],
          f"Fuente: padrón de contribuyentes {anio} (export del SRTM), agregado. Mínimo legal: "
          f"{c['minimo-predial']['texto']} (0,6 % de la UIT de {c['uit']['texto']}).")

    filas = [[celda(f"{q['porcentaje_contribuyentes']} % de mayor impuesto"),
              celda(miles(q["contribuyentes"]), q["contribuyentes"]), celda(miles(Decimal(q["insoluto"])), q["insoluto"]),
              celda(pct(Decimal(q["participacion"])), q["participacion"])] for q in padron["concentracion"]]
    tabla(tablas, "concentracion", f"Concentración del impuesto predial emitido {anio}",
          ["Contribuyentes", "Número", "Impuesto emitido (S/)", "Parte de la emisión"],
          filas, ["4", ">2", ">3", ">2"], None,
          "Contribuyentes con impuesto, ordenados de mayor a menor impuesto anual. Cada fila incluye a las anteriores.")

    filas = []
    for k, v in padron["por_condicion"].items():
        filas.append([celda(k.capitalize()), celda(miles(v["contribuyentes"]), v["contribuyentes"]),
                      celda("—" if v.get("suprimido") else miles(Decimal(v["insoluto"])), v.get("insoluto"))])
    tabla(tablas, "condicion", f"Contribuyentes del padrón {anio} por condición",
          ["Condición", "Contribuyentes", "Impuesto emitido (S/)"], filas, ["5", ">2", ">3"], None,
          f"Grupos de menos de {padron['grupo_minimo']} contribuyentes, sin monto. Fuente: padrón {anio}, agregado.")

    filas = []
    for r in conciliacion:
        filas.append([celda(str(r["anio"])), celda(r["fuente"]), celda(miles(Decimal(str(r["recaudado"])), 2), r["recaudado"]),
                      celda(miles(Decimal(r["calculado"]), 2), r["calculado"]),
                      celda(miles(Decimal(r["diferencia"]), 2), r["diferencia"])])
    if filas:
        tabla(tablas, "conciliacion", "Conciliación del recaudado total con la Consulta Amigable del MEF (soles)",
              ["Año", "Referencia publicada", "Publicado", "Calculado", "Diferencia"], filas,
              ["1", "4", ">2", ">2", ">2"], None,
              "Calculado: suma de las filas del archivo de datos abiertos de la municipalidad.")

    escribir_json(generado / "indicadores.json", {
        "generado": datetime.datetime.now().isoformat(timespec="seconds"),
        "cifras": c,
        "grupos": {k: {kk: str(vv) for kk, vv in v.items()} for k, v in g.items()},
        "proyeccion": None if not proyeccion else {k: (str(v) if isinstance(v, Decimal) else v)
                                                   for k, v in proyeccion.items()},
        "conciliacion": conciliacion,
        "calidad": calidad,
        "series": {
            "mensual": {gr: {str(a): [str(por_grupo.recaudado(a, gr, [m])) for m in range(1, 13)]
                             for a in (previo, anio)} for gr in ORDEN_GRUPOS},
            "historico": {str(h): {gr: str(anual_hist.recaudado(h, gr)) for gr in ORDEN_GRUPOS} for h in historicos},
            "categorias": {**{str(h): {k: str(hist_cat.recaudado(h, k)) for k in hist_cat.llaves()} for h in historicos},
                           str(previo): {k: str(por_categoria.recaudado(previo, k)) for k in por_categoria.llaves()},
                           str(anio): {k: str(por_categoria.recaudado(anio, k)) for k in por_categoria.llaves()}},
            "pim_propios": str(g["Ingresos propios"]["pim"]),
            "pim_propios_prev": str(g["Ingresos propios"]["pim_prev"]),
            "predial": {"emitido": str(emitido), "exigible": str(exigible), "recaudado_anio": str(predial_corte),
                        "regularizacion": str(regularizacion_corte)},
            "conceptos": {k: {"ytd": str(por_concepto.recaudado(anio, k, meses_ytd)),
                              "ytd_prev": str(por_concepto.recaudado(previo, k, meses_ytd))}
                          for k in por_concepto.llaves()},
        },
    })
    print(f"indicadores: {len(c)} cifras, {len(list(tablas.glob('*.adoc')))} tablas")


def hist_concepto_arbitrio(comparativo, anio, nombre):
    return sum((monto(f[f"MONTO_RECAUDADO_{anio}"]) for f in comparativo
                if ARBITRIOS.get(f["ESPECIFICA_DET_NOMBRE"].strip().upper()) == nombre), CERO)


if __name__ == "__main__":
    main()
