"""The informe de brechas' charts, as SVG drawn from indicadores.json (srtm-backend#301).

The same conventions as recaudacion/herramientas/graficos.py (axes from zero, round steps, plain SVG elements,
no script) but themed differently: there every fill is a fixed hex for asciidoctor-pdf's prawn-svg, here each
fill and text reads a CSS custom property of the HTML page with its light value as fallback
(`style="fill:var(--serie-1,#2a78d6)"`), so the same inline SVG follows the page into dark mode and a lone .svg
file still draws in light colors. Colors are the validated reference palette's (categorical slots 1-3 pass the
all-pairs CVD checks in light and dark; dark steps are their own, set in the page's CSS). Each bar carries a
<title> (the browser's tooltip) and every chart is followed by its table in the report.
"""

import math
from decimal import Decimal
from xml.sax.saxutils import escape

from . import informe_comun as ic
from .informe_indicadores import ORDEN_CONFIANZA

FUENTE = "system-ui, -apple-system, 'Segoe UI', Helvetica, Arial, sans-serif"

# (CSS custom property, light fallback); the page redefines each for dark mode
COLOR = {
    "superficie": ("--superficie", "#fcfcfb"),
    "tinta": ("--tinta", "#0b0b0b"),
    "tenue": ("--tinta-2", "#52514e"),
    "rejilla": ("--rejilla", "#e1e0d9"),
    "eje": ("--eje", "#c3c2b7"),
    "serie-1": ("--serie-1", "#2a78d6"),
    "serie-2": ("--serie-2", "#eb6834"),
    "serie-3": ("--serie-3", "#1baf7a"),
    "neutro": ("--neutro", "#898781"),
    "anterior": ("--anterior", "#86b6ef"),
    "positivo": ("--positivo", "#2a78d6"),
    "negativo": ("--negativo", "#e34948"),
}
COLOR_CONFIANZA = {"CONFIABLE": "serie-1", "DUDOSO": "serie-2", "SIN_LEVANTAR": "serie-3", "SIN_DATO": "neutro"}
ETIQUETA_CONFIANZA = {"CONFIABLE": "Confiable", "DUDOSO": "Dudoso", "SIN_LEVANTAR": "Sin levantar",
                      "SIN_DATO": "Sin dato"}
ETIQUETA_CATEGORIA = {"OMISO": "Omiso", "SUBVALUADO": "Subvaluado", "SOBREVALUADO": "Sobrevaluado",
                      "COINCIDE": "Coincide", "SIN_VALOR": "Sin valor", "SIN_CATASTRO": "Sin catastro"}
ETIQUETA_CLASE = {"SIN CLASIFICACION": "Sin clasificación", "SIN CONSTRUCCION": "Sin construcción",
                  "SIN FICHA": "Sin ficha"}
ETIQUETA_NIVEL = {"PAR": "Par (en ambos)", "SOLO_RENTAS": "Solo en rentas", "SOLO_CATASTRO": "Solo en catastro",
                  "AMBIGUO": "Ambiguo", "SIN_DATO": "Sin estado"}


def etiqueta(diccionario, clave):
    return diccionario.get(clave, str(clave).replace("_", " ").capitalize())


def _c(nombre, propiedad="fill"):
    var, respaldo = COLOR[nombre]
    return f'style="{propiedad}:var({var},{respaldo})"'


def paso_redondo(maximo, divisiones=4):
    if maximo <= 0:
        return 1
    bruto = maximo / divisiones
    potencia = 10 ** math.floor(math.log10(bruto))
    for factor in (1, 2, 5, 10):
        if bruto <= factor * potencia:
            return factor * potencia
    return 10 * potencia


class Lienzo:
    def __init__(self, ancho, alto):
        self.ancho, self.alto = ancho, alto
        self.partes = []

    def texto(self, x, y, contenido, tam=12, color="tinta", ancla="start", peso="normal"):
        self.partes.append(
            f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FUENTE}" font-size="{tam}" {_c(color)} '
            f'text-anchor="{ancla}" font-weight="{peso}">{escape(contenido)}</text>'
        )

    def rect(self, x, y, ancho, alto, color, titulo=None):
        t = f"<title>{escape(titulo)}</title>" if titulo else ""
        self.partes.append(
            f'<rect x="{x:.1f}" y="{y:.1f}" width="{max(ancho, 0):.1f}" height="{max(alto, 0):.1f}" rx="2" '
            f"{_c(color)}>{t}</rect>"
        )

    def linea(self, x1, y1, x2, y2, color="rejilla", grosor=1):
        self.partes.append(
            f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" {_c(color, "stroke")} '
            f'stroke-width="{grosor}"/>'
        )

    def leyenda(self, x, y, items):
        for nombre, color in items:
            self.rect(x, y - 10, 11, 11, color)
            self.texto(x + 16, y, nombre, 11.5, "tenue")
            x += 16 + 7 * len(nombre) + 22

    def svg(self, descripcion):
        cuerpo = "\n".join(self.partes)
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.ancho} {self.alto}" role="img" '
            f'width="{self.ancho}" height="{self.alto}" aria-label="{escape(descripcion)}">\n'
            f"<title>{escape(descripcion)}</title>\n"
            f'<rect x="0" y="0" width="{self.ancho}" height="{self.alto}" {_c("superficie")}/>\n'
            f"{cuerpo}\n</svg>\n"
        )


def _escala(minimo, maximo, x0, x1):
    paso = paso_redondo(max(maximo, 0) - min(minimo, 0) or 1)
    bajo = paso * math.floor(min(minimo, 0) / paso)
    alto = paso * math.ceil(max(maximo, 0) / paso) if maximo > 0 else 0
    if alto == bajo:
        alto = bajo + paso
    return (lambda v: x0 + (v - bajo) / (alto - bajo) * (x1 - x0)), bajo, alto, paso


def barras(filas, descripcion, unidad, formato, ancho_etiqueta=170, ancho=680, alto_fila=34):
    """Horizontal bars from zero; filas = [(etiqueta, valor or None (reservado), color)]; values may be negative."""
    valores = [float(v) for _, v, _ in filas if v is not None] or [0.0]
    alto = 44 + alto_fila * len(filas) + 28
    lienzo = Lienzo(ancho, alto)
    x0, x1 = ancho_etiqueta, ancho - 110
    escala, bajo, tope, paso = _escala(min(valores), max(valores), x0, x1)
    y_ini, y_fin = 30, 30 + alto_fila * len(filas)
    v = bajo
    while v <= tope + 1e-9:
        lienzo.linea(escala(v), y_ini - 4, escala(v), y_fin, "eje" if abs(v) < 1e-9 else "rejilla")
        lienzo.texto(escala(v), y_fin + 15, formato(v), 10.5, "tenue", "middle")
        v += paso
    lienzo.texto(x1, 16, unidad, 10.5, "tenue", "end")
    for i, (texto, valor, color) in enumerate(filas):
        y = y_ini + i * alto_fila
        lienzo.texto(x0 - 10, y + alto_fila * 0.6, texto, 12, "tinta", "end")
        if valor is None:
            lienzo.texto(escala(0) + 6, y + alto_fila * 0.6, "reservado", 11, "tenue", "start")
            continue
        v = float(valor)
        izquierda, derecha = sorted((escala(0), escala(v)))
        lienzo.rect(izquierda, y + alto_fila * 0.22, derecha - izquierda, alto_fila * 0.56, color,
                    f"{texto}: {formato(valor)}")
        if v >= 0:
            lienzo.texto(derecha + 6, y + alto_fila * 0.62, formato(valor), 11.5, "tinta", "start", "bold")
        else:
            lienzo.texto(izquierda - 6, y + alto_fila * 0.62, formato(valor), 11.5, "tinta", "end", "bold")
    return lienzo.svg(descripcion)


def pares(filas, descripcion, unidad, formato, leyenda, ancho_etiqueta=170, ancho=680, alto_fila=40):
    """Two bars per row (anterior, actual), both from zero; filas = [(etiqueta, anterior, actual)]."""
    valores = [float(v) for _, a, b in filas for v in (a, b) if v is not None] or [0.0]
    alto = 44 + alto_fila * len(filas) + 50
    lienzo = Lienzo(ancho, alto)
    x0, x1 = ancho_etiqueta, ancho - 110
    escala, bajo, tope, paso = _escala(min(valores), max(valores), x0, x1)
    y_ini, y_fin = 30, 30 + alto_fila * len(filas)
    v = bajo
    while v <= tope + 1e-9:
        lienzo.linea(escala(v), y_ini - 4, escala(v), y_fin, "eje" if abs(v) < 1e-9 else "rejilla")
        lienzo.texto(escala(v), y_fin + 15, formato(v), 10.5, "tenue", "middle")
        v += paso
    lienzo.texto(x1, 16, unidad, 10.5, "tenue", "end")
    for i, (texto, anterior, actual) in enumerate(filas):
        y = y_ini + i * alto_fila
        lienzo.texto(x0 - 10, y + alto_fila * 0.55, texto, 12, "tinta", "end")
        for j, (valor, color, nombre) in enumerate(((anterior, "anterior", leyenda[0]), (actual, "serie-1",
                                                                                         leyenda[1]))):
            arriba = y + alto_fila * (0.12 + 0.40 * j)
            if valor is None:
                lienzo.texto(escala(0) + 6, arriba + alto_fila * 0.3, "reservado", 10, "tenue")
                continue
            izquierda, derecha = sorted((escala(0), escala(float(valor))))
            lienzo.rect(izquierda, arriba, derecha - izquierda, alto_fila * 0.34, color,
                        f"{texto}, {nombre}: {formato(valor)}")
            lienzo.texto(derecha + 5, arriba + alto_fila * 0.28, formato(valor), 10.5, "tinta")
    lienzo.leyenda(x0, alto - 10, [(leyenda[0], "anterior"), (leyenda[1], "serie-1")])
    return lienzo.svg(descripcion)


def _predios(v):
    return ic.numero(v)


def _soles(valores):
    """(unidad, formato) of an amount axis: thousands of soles, or soles when every value is under ten thousand."""
    grande = max((abs(float(v)) for v in valores if v is not None), default=0)
    if grande < 10000:
        return "S/", lambda v: ic.numero(v)
    return "miles de S/", lambda v: ic.numero(Decimal(str(v)) / 1000)


def todos(ind, comparacion=None, anterior=None):
    """{nombre: svg} of every chart the report places."""
    graficos = {}
    graficos["confianza"] = barras(
        [(etiqueta(ETIQUETA_CONFIANZA, c["clave"]), c["predios"], COLOR_CONFIANZA.get(c["clave"], "neutro"))
         for c in ind["confianza"]],
        "Predios de la corrida según la confianza del dato de catastro", "predios", _predios)
    confiables = [c for c in ind["categorias"] if c["confianza"] == "CONFIABLE"]
    graficos["categorias"] = barras(
        [(etiqueta(ETIQUETA_CATEGORIA, c["clave"]), c["delta_impuesto"],
          "positivo" if c["delta_impuesto"] is None or Decimal(c["delta_impuesto"]) >= 0 else "negativo")
         for c in confiables],
        "Δ impuesto de los predios confiables, por categoría",
        *_soles([c["delta_impuesto"] for c in confiables]))
    p = ind["predial"]
    graficos["predial"] = barras(
        [("Declarado", p["declarado"], "neutro"), ("Motor de rentas", p["motor_rentas"], "serie-1"),
         ("Motor con catastro", p["motor_catastro"], "serie-3")],
        "Impuesto predial del año en tres columnas",
        *_soles([p["declarado"], p["motor_rentas"], p["motor_catastro"]]))
    graficos["niveles"] = barras(
        [(etiqueta(ETIQUETA_NIVEL, n["clave"]), n["niveles"], "serie-1") for n in ind["niveles"]],
        "Niveles (pisos) comparados, según dónde aparecen", "niveles", _predios)
    if comparacion and anterior:
        claves = [k for k in ORDEN_CONFIANZA if any(c["clave"] == k for c in ind["confianza"] + anterior["confianza"])]
        buscar = lambda lista, k: next((c["predios"] for c in lista if c["clave"] == k), 0)  # noqa: E731
        graficos["comparacion"] = pares(
            [(etiqueta(ETIQUETA_CONFIANZA, k), buscar(anterior["confianza"], k), buscar(ind["confianza"], k))
             for k in claves],
            "Predios según confianza: corte anterior y actual", "predios", _predios,
            (f"Corte del {ic.fecha_larga(anterior['corte']['fecha_corte'])}",
             f"Corte del {ic.fecha_larga(ind['corte']['fecha_corte'])}"))
    return graficos
