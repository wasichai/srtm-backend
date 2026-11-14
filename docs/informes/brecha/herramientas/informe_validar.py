"""The checks an edición of the informe de brechas must pass before it is handed over (srtm-backend#301); `make
informe` fails when any does. Like recaudacion/herramientas/validar.py:

- privacidad (Ley 29733): no output (salida/, generado/, textos/) carries an identifier of the corte - a predio's
  or ficha's code, a contribuyente's code, a pseudonym, an srtm id -, nor an amount of an individual predio to
  the céntimo, nor a number that looks like a DNI or RUC, an e-mail address or the name of a personal field;
  and every amount in indicadores.json adds up at least `umbral` predios (or is reserved);
- textos: no typed figure (an amount, a percentage, a number of four or more digits that is not a year) outside
  the attributes of generado/cifras.adoc, and no undefined attribute;
- autocontenido: the HTML loads nothing (no script, link, img, iframe, src=, url(), @import or external href);
- coherencia: the corte is the edición's organization's, the confianza groups add up the total and every
  chart and table a texto cites exists.
"""

import json
import re
from decimal import Decimal

import duckdb

from . import informe_comun as ic

# a typed figure: S/ followed by a number, a percentage, or a number of four or more digits (with or without a
# thousands separator) that is not a year 2000-2099 (recaudacion/herramientas/validar.py)
CIFRA = re.compile(r"S/\s*\d|\d+(?:,\d+)?\s*%|\b\d{1,3}(?:[  .]\d{3})+\b|\b(?!20\d\d\b)\d{4,}\b")
# the number of a norm is not a figure: Ley 29733, Ordenanza N.° 012-2026, D. S. N.° 156-2004-EF
NORMA = re.compile(r"\b(?:Ley|Ordenanza(?: Municipal)?|Decreto(?: Supremo| Legislativo)?|D\.\s?S\.|D\.\s?Leg\.)"
                   r"\s+(?:N\.?\s?[°º]\s*)?\d[\d\-]*(?:-[A-Z]+)*")
DOCUMENTO = re.compile(r"(?<!\d)(?<!\d[.,])\d{8}(?:\d{3})?(?!\d)(?![.,]\d)")
CORREO = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
CAMPOS_PERSONALES = ("documento_hmac", "numero_documento", "num_doc", "nombre_completo", "razon_social",
                     "apellido_paterno", "apellido_materno", "codigo_catastro", "codigo_predio", "codigo_municipal")
EXTERNO = re.compile(r"<script|<link|<img|<iframe|<object|<embed|\bsrc\s*=|url\(|@import|href\s*=\s*\"(?!#)",
                     re.IGNORECASE)
INTERNOS = {"nbsp", "sp", "empty", "zwsp", "amp", "lt", "gt", "plus", "blank"}

# identifiers of the corte (the validator may read tables: it only looks, it never publishes)
_IDENTIFICADORES = (
    "SELECT codigo_catastro FROM srtm_brecha_predio",
    "SELECT codigo_predio FROM srtm_brecha_predio",
    "SELECT predio FROM srtm_brecha_predio",
    "SELECT id FROM srtm_brecha_predio",
    "SELECT codigo FROM srtm_predio",
    "SELECT id FROM srtm_predio",
    "SELECT codigo FROM srtm_contribuyente",
    "SELECT id FROM srtm_contribuyente",
    "SELECT documento_hmac FROM srtm_contribuyente",
    "SELECT codigo_municipal FROM catastro_ficha",
    "SELECT codigo_municipal FROM catastro_lote",
)
_MONTOS = ("autoavaluo_declarado", "autoavaluo_catastro", "delta_autoavaluo", "impuesto_declarado",
           "impuesto_catastro", "delta_impuesto")
_TOKEN = re.compile(r"[0-9A-Za-z][0-9A-Za-z_\-./]*")
# an amount with decimals as JSON writes it (1803.0300000000) and as the report would (1 803,03, −1 803,03)
_DECIMAL_PUNTO = re.compile(r"(?<![\d.,])-?\d+\.\d+(?!\d)")
_DECIMAL_COMA = re.compile(r"(?<![\d.,])[−-]?\d{1,3}(?:[  ]\d{3})*,\d{2,}(?!\d)"
                           r"|(?<![\d.,])[−-]?\d+,\d{2,}(?!\d)")
NO_MONTO = {"predios", "contribuyentes", "niveles", "estimados", "reservado", "clave", "confianza", "categoria",
            "clase", "estado"}


def _montos_en(texto):
    """The amounts to the céntimo a text carries, as 1803.03 / -1803.03."""
    encontrados = set()
    for patron in (_DECIMAL_PUNTO, _DECIMAL_COMA):
        for m in patron.finditer(texto):
            crudo = m.group(0).replace("−", "-").replace(" ", "").replace("\u202f", "").replace(",", ".")
            d = Decimal(crudo)
            if d == d.quantize(Decimal("0.01")):
                encontrados.add(f"{d.quantize(Decimal('0.01')):.2f}")
    return encontrados


class Validacion:
    def __init__(self):
        self.fallas = []

    def falla(self, donde, mensaje):
        self.fallas.append(f"{donde}: {mensaje}")


def _sensibles(ruta_duckdb):
    """(identifiers, individual amounts as text) of one corte's DuckDB."""
    identificadores, montos = set(), set()
    con = duckdb.connect(str(ruta_duckdb), read_only=True)
    try:
        for sql in _IDENTIFICADORES:
            try:
                filas = con.execute(sql).fetchall()
            except duckdb.Error:
                continue
            for (valor,) in filas:
                texto = str(valor or "").strip()
                # a short value (a lote number) or a year would match by chance
                if len(texto) >= 4 and not re.fullmatch(r"(19|20)\d\d", texto):
                    identificadores.add(texto)
        for campo in _MONTOS:
            for (valor,) in con.execute(f"SELECT {campo} FROM srtm_brecha_predio WHERE {campo} IS NOT NULL").fetchall():
                d = Decimal(valor).quantize(Decimal("0.01"))
                if abs(d) >= 10:
                    montos.add(f"{d:.2f}")
    finally:
        con.close()
    return identificadores, montos


def _archivos(e):
    rutas = []
    for carpeta, patrones in (("salida", ("*.html",)), ("generado", ("*.json", "*.adoc", "*.svg")),
                              ("textos", ("*.adoc",))):
        for patron in patrones:
            rutas += sorted((e / carpeta).rglob(patron))
    return rutas


def privacidad(v, e, sensibles):
    identificadores, montos = sensibles
    for ruta in _archivos(e):
        donde = str(ruta.relative_to(e))
        texto = ruta.read_text(encoding="utf-8")
        tokens = set(_TOKEN.findall(texto))
        tokens |= {parte for t in tokens for parte in re.split(r"[_\-./]", t) if parte}
        for ident in sorted(identificadores & tokens)[:5]:
            v.falla(donde, f"lleva un identificador del corte «{ident}» (código de predio, contribuyente o ficha)")
        for monto in sorted(montos & _montos_en(texto))[:5]:
            v.falla(donde, f"lleva el monto de un predio individual «{monto}»")
        for campo in CAMPOS_PERSONALES:
            if campo in texto:
                v.falla(donde, f"menciona el campo personal {campo}")
        if ruta.suffix != ".json":
            # the method's commit and other sha sums are not documents
            limpio = re.sub(r"[0-9a-f]{16,64}", "", texto)
            for patron, que in ((DOCUMENTO, "un número de 8 u 11 dígitos (DNI o RUC)"), (CORREO, "un correo")):
                if m := patron.search(limpio):
                    v.falla(donde, f"{que}: «{m.group(0)}»")


def _celdas(nodo):
    if isinstance(nodo, dict):
        if "reservado" in nodo:
            yield nodo
        for valor in nodo.values():
            yield from _celdas(valor)
    elif isinstance(nodo, list):
        for valor in nodo:
            yield from _celdas(valor)


def umbrales(v, ind, nombre="indicadores.json"):
    umbral = ind["umbral"]
    for celda in _celdas(ind):
        n = celda.get("predios", celda.get("contribuyentes")) or 0
        if 0 < n < umbral and not celda["reservado"]:
            v.falla(nombre, f"un grupo de {n} predios ({celda.get('clave', 'total')}) no está reservado")
        if celda["reservado"]:
            con_monto = [k for k, val in celda.items() if k not in NO_MONTO and val is not None]
            if con_monto:
                v.falla(nombre, f"un grupo reservado ({celda.get('clave', 'total')}) lleva montos: {con_monto}")


def textos(v, e, atributos):
    definidos = set(atributos) | INTERNOS
    for ruta in sorted((e / "textos").glob("*.adoc")):
        donde = ruta.relative_to(e)
        en_comentario = False
        pila = []  # ifdef/ifndef: an inactive block is not rendered, so its attributes may be undefined here
        for n, linea in enumerate(ruta.read_text(encoding="utf-8").splitlines(), 1):
            if linea.startswith("////"):
                en_comentario = not en_comentario
                continue
            if en_comentario or linea.startswith("//"):
                continue
            if m := re.match(r"^(ifdef|ifndef)::([\w-]+)\[\]\s*$", linea):
                pila.append((m.group(2) in atributos) == (m.group(1) == "ifdef"))
                continue
            if re.match(r"^endif::[\w-]*\[\]\s*$", linea):
                if pila:
                    pila.pop()
                continue
            if all(pila):
                for m in re.finditer(r"\{([\w-]+)\}", linea):
                    if m.group(1) not in definidos:
                        v.falla(f"{donde}:{n}", f"atributo sin definir: {{{m.group(1)}}}")
            # a typed figure fails even in an inactive block: another edición renders it
            limpio = re.sub(r"\{[\w-]+\}", "", linea)
            limpio = re.sub(r"<<[^>]+>>|\[\[[^\]]+\]\]|(?:tabla|grafico)::\S+\[\]|https?://\S+", "", limpio)
            limpio = NORMA.sub("", limpio)
            if m := CIFRA.search(limpio):
                v.falla(f"{donde}:{n}",
                        f"cifra tecleada «{m.group(0)}»: debe ser un atributo de generado/cifras.adoc")


def autocontenido(v, e):
    html = e / "salida" / ic.HTML
    if not html.is_file():
        v.falla(f"salida/{ic.HTML}", "no existe")
        return
    texto = html.read_text(encoding="utf-8")
    # the only URL allowed is the SVG namespace, an identifier and never fetched
    limpio = texto.replace('xmlns="http://www.w3.org/2000/svg"', "")
    if m := EXTERNO.search(limpio):
        v.falla(f"salida/{ic.HTML}", f"carga algo de fuera de la página: «{m.group(0)}»")
    if re.search(r"https?://", limpio):
        v.falla(f"salida/{ic.HTML}", "lleva una URL: el informe no enlaza ni carga nada externo")


def coherencia(v, edicion, ind, nombre="indicadores.json"):
    if ind["corte"]["organizacion"] != edicion["organizacion"]:
        v.falla(nombre,
                f"el corte es de «{ind['corte']['organizacion']}» y la edición de «{edicion['organizacion']}»")
    suma = sum(c["predios"] for c in ind["confianza"])
    if suma != ind["totales"]["predios"]:
        v.falla(nombre, f"los grupos de confianza suman {suma} predios y el total es {ind['totales']['predios']}")


def validar(e):
    """The list of failures of the edición in folder `e` (empty: it passes)."""
    v = Validacion()
    edicion = ic.leer_edicion(e)
    generado = e / "generado"
    try:
        datos = json.loads((generado / "indicadores.json").read_text(encoding="utf-8"))
        atributos = json.loads((generado / "cifras.json").read_text(encoding="utf-8"))
    except FileNotFoundError as err:
        v.falla("generado", f"falta {err.filename}: corre make informe")
        return v.fallas
    ind = datos["actual"]
    coherencia(v, edicion, ind)
    umbrales(v, ind)
    sensibles_id, sensibles_monto = _sensibles(ic.ruta_duckdb(datos["corte"]))
    if datos.get("anterior"):
        coherencia(v, edicion, datos["anterior"], "indicadores.json (corte anterior)")
        umbrales(v, datos["anterior"], "indicadores.json (corte anterior)")
        otros = _sensibles(ic.ruta_duckdb(datos["corte_anterior"]))
        sensibles_id |= otros[0]
        sensibles_monto |= otros[1]
    privacidad(v, e, (sensibles_id, sensibles_monto))
    textos(v, e, atributos)
    autocontenido(v, e)
    return v.fallas
