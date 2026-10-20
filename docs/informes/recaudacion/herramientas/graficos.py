"""Dibuja los gráficos del informe en SVG a partir de generado/indicadores.json y datos/padron/agregados.json.

Uso: EDICION=<carpeta> python3 -I herramientas/graficos.py

SVG plano (rect, line, text, polyline): lo dibujan igual asciidoctor-pdf (prawn-svg) y el navegador (incrustado en el
HTML). La fuente se pide como «Sans» (el nombre del tema del PDF) y luego «Source Sans 3» (la del HTML). Los ejes
de las barras empiezan en cero y las escalas usan pasos redondos: ningún gráfico exagera una diferencia.
"""

import json
import math
import os
import sys
from decimal import Decimal
from xml.sax.saxutils import escape

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from comun import edicion, parametros  # noqa: E402

FUENTE = "Sans, 'Source Sans 3', 'Segoe UI', Helvetica, Arial, sans-serif"
TINTA, TENUE, REJILLA, FONDO = "#2B2B2B", "#5F6368", "#E3E7EC", "#FFFFFF"
ACTUAL, PREVIO, NEUTRO, ACENTO = "#0D5FA8", "#9CC3E6", "#B8C2CC", "#B5462F"
GRUPOS = {"Ingresos propios": "#0D5FA8", "Transferencias": "#9CC3E6", "Financiamiento": "#C9CED4"}
MES = ["Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"]


def numero(valor, decimales=0):
    texto = f"{abs(valor):,.{decimales}f}".replace(",", " ").replace(".", ",")
    return ("−" if valor < 0 else "") + texto


def paso_redondo(maximo, divisiones=5):
    if maximo <= 0:
        return 1
    bruto = maximo / divisiones
    potencia = 10 ** math.floor(math.log10(bruto))
    for factor in (1, 2, 2.5, 5, 10):
        if bruto <= factor * potencia:
            return factor * potencia
    return 10 * potencia


class Lienzo:
    def __init__(self, ancho, alto):
        self.ancho, self.alto = ancho, alto
        self.partes = [f'<rect x="0" y="0" width="{ancho}" height="{alto}" fill="{FONDO}"/>']

    def texto(self, x, y, contenido, tam=11, color=TINTA, ancla="start", peso="normal"):
        self.partes.append(f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FUENTE}" font-size="{tam}" fill="{color}" '
                           f'text-anchor="{ancla}" font-weight="{peso}">{escape(contenido)}</text>')

    def rect(self, x, y, ancho, alto, color, borde=None):
        trazo = f' stroke="{borde}" stroke-width="0.8"' if borde else ""
        self.partes.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{max(ancho, 0):.1f}" height="{max(alto, 0):.1f}" '
                           f'fill="{color}"{trazo}/>')

    def linea(self, x1, y1, x2, y2, color=REJILLA, grosor=1, guiones=None):
        g = f' stroke-dasharray="{guiones}"' if guiones else ""
        self.partes.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="{color}" '
                           f'stroke-width="{grosor}"{g}/>')

    def polilinea(self, puntos, color, grosor=2.2, guiones=None):
        g = f' stroke-dasharray="{guiones}"' if guiones else ""
        p = " ".join(f"{x:.1f},{y:.1f}" for x, y in puntos)
        self.partes.append(f'<polyline points="{p}" fill="none" stroke="{color}" stroke-width="{grosor}"{g}/>')

    def circulo(self, x, y, r, color):
        self.partes.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r}" fill="{color}"/>')

    def leyenda(self, x, y, items):
        for nombre, color, tipo in items:
            if tipo == "linea":
                self.linea(x, y - 4, x + 18, y - 4, color, 2.4)
                x += 24
            elif tipo == "guiones":
                self.linea(x, y - 4, x + 18, y - 4, color, 1.6, "5,3")
                x += 24
            else:
                self.rect(x, y - 10, 11, 11, color)
                x += 16
            self.texto(x, y, nombre, 10.5, TENUE)
            x += 7 * len(nombre) + 22

    def guardar(self, ruta, descripcion):
        cuerpo = "\n".join(self.partes)
        ruta.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.ancho}" height="{self.alto}" '
                        f'viewBox="0 0 {self.ancho} {self.alto}" role="img">\n<title>{escape(descripcion)}</title>\n'
                        f"{cuerpo}\n</svg>\n", encoding="utf-8")


def eje_y(lienzo, x0, x1, y0, y1, maximo, unidad, divisor=1, decimales=0):
    """La rejilla horizontal y las marcas del eje, de 0 a un máximo redondo. Devuelve la escala (valor → y)."""
    paso = paso_redondo(maximo)
    tope = paso * math.ceil(maximo / paso) if maximo > 0 else paso
    escala = lambda v: y0 - (v / tope) * (y0 - y1)  # noqa: E731
    v = 0
    while v <= tope + 1e-9:
        y = escala(v)
        lienzo.linea(x0, y, x1, y, REJILLA if v else "#9AA5B1", 1)
        lienzo.texto(x0 - 6, y + 3.5, numero(v / divisor, decimales), 9.5, TENUE, "end")
        v += paso
    lienzo.texto(x0 - 6, y1 - 12, unidad, 9.5, TENUE, "end")
    return escala


def composicion(ind, p, ruta):
    """Barras apiladas por año: ingresos propios, transferencias y financiamiento (millones de soles)."""
    anios = [str(a) for a in p["anios_historicos"]] + [str(p["anio_comparable"]), str(p["anio"])]
    cat = ind["series"]["categorias"]
    grupos = {"Ingresos propios": ("Impuestos municipales", "Recursos directamente recaudados"),
              "Transferencias": ("FONCOMUN", "Canon, sobrecanon, regalías y participaciones", "Donaciones y transferencias"),
              "Financiamiento": ("Saldos de balance", "Endeudamiento")}
    valores = {a: {g: sum(float(cat[a].get(c, 0)) for c in cs) for g, cs in grupos.items()} for a in anios}
    lienzo = Lienzo(680, 330)
    x0, x1, y0, y1 = 70, 650, 280, 40
    maximo = max(sum(v.values()) for v in valores.values()) / 1e6
    escala = eje_y(lienzo, x0, x1, y0, y1, maximo, "millones S/", 1, 0)
    ancho = (x1 - x0) / len(anios)
    for i, a in enumerate(anios):
        x = x0 + i * ancho + ancho * 0.22
        base = 0
        for g in grupos:
            v = valores[a][g] / 1e6
            lienzo.rect(x, escala(base + v), ancho * 0.56, escala(base) - escala(base + v), GRUPOS[g])
            if v >= maximo * 0.06:
                color = FONDO if g == "Ingresos propios" else TINTA
                lienzo.texto(x + ancho * 0.28, escala(base + v / 2) + 4, numero(v, 1), 10, color, "middle")
            elif g == "Ingresos propios":
                # el tramo es demasiado delgado para llevar la cifra adentro: va al costado
                lienzo.texto(x + ancho * 0.56 + 4, escala(base + v / 2) + 4, numero(v, 1), 10, ACTUAL, "start", "bold")
            base += v
        lienzo.texto(x + ancho * 0.28, escala(base) - 6, numero(base, 1), 10.5, TINTA, "middle", "bold")
        etiqueta = f"{a} (al corte)" if a == str(p["anio"]) else a
        lienzo.texto(x + ancho * 0.28, y0 + 17, etiqueta, 11, TINTA, "middle")
    lienzo.leyenda(x0, 318, [(g, c, "caja") for g, c in GRUPOS.items()])
    lienzo.guardar(ruta, "Recaudado anual por grupo de ingreso")


def mensual_propios(ind, p, ruta):
    """Barras agrupadas por mes de los ingresos propios, año anterior y año en curso (miles de soles)."""
    serie = ind["series"]["mensual"]["Ingresos propios"]
    previo, actual = [float(v) / 1e3 for v in serie[str(p["anio_comparable"])]], \
        [float(v) / 1e3 for v in serie[str(p["anio"])]]
    ultimo = p["meses_completos"] + 1
    lienzo = Lienzo(680, 300)
    x0, x1, y0, y1 = 64, 660, 250, 34
    escala = eje_y(lienzo, x0, x1, y0, y1, max(previo + actual), "miles S/")
    ancho = (x1 - x0) / 12
    for m in range(12):
        x = x0 + m * ancho
        lienzo.rect(x + ancho * 0.12, escala(previo[m]), ancho * 0.36, y0 - escala(previo[m]), PREVIO)
        if m < ultimo:
            color = NEUTRO if m == ultimo - 1 else ACTUAL
            lienzo.rect(x + ancho * 0.5, escala(actual[m]), ancho * 0.36, y0 - escala(actual[m]), color)
        lienzo.texto(x + ancho * 0.5, y0 + 16, MES[m], 10.5, TINTA, "middle")
    lienzo.leyenda(x0, 290, [(str(p["anio_comparable"]), PREVIO, "caja"), (str(p["anio"]), ACTUAL, "caja"),
                             (f"{MES[ultimo - 1]} {p['anio']} (parcial, al corte)", NEUTRO, "caja")])
    lienzo.guardar(ruta, "Ingresos propios por mes")


def acumulado_propios(ind, p, ruta):
    """Líneas del acumulado de ingresos propios por mes y el PIM del año (miles de soles)."""
    serie = ind["series"]["mensual"]["Ingresos propios"]
    acumular = lambda vs: [sum(float(v) for v in vs[:i + 1]) / 1e3 for i in range(len(vs))]  # noqa: E731
    previo, actual = acumular(serie[str(p["anio_comparable"])]), acumular(serie[str(p["anio"])])
    pim = float(ind["series"]["pim_propios"]) / 1e3
    ultimo = p["meses_completos"]
    lienzo = Lienzo(680, 300)
    x0, x1, y0, y1 = 64, 650, 250, 34
    escala = eje_y(lienzo, x0, x1, y0, y1, max(previo + [pim]), "miles S/")
    paso = (x1 - x0) / 11
    lienzo.linea(x0, escala(pim), x1, escala(pim), ACENTO, 1.4, "6,4")
    lienzo.texto(x0 + 6, escala(pim) - 6, f"PIM {p['anio']}: {numero(pim)} mil", 10, ACENTO, "start")
    lienzo.polilinea([(x0 + i * paso, escala(v)) for i, v in enumerate(previo)], PREVIO, 2.4)
    lienzo.polilinea([(x0 + i * paso, escala(v)) for i, v in enumerate(actual[:ultimo])], ACTUAL, 2.6)
    lienzo.circulo(x0 + (ultimo - 1) * paso, escala(actual[ultimo - 1]), 3.5, ACTUAL)
    lienzo.texto(x0 + (ultimo - 1) * paso + 6, escala(actual[ultimo - 1]) + 15, numero(actual[ultimo - 1]), 10, ACTUAL,
                 "start", "bold")
    lienzo.circulo(x0 + (ultimo - 1) * paso, escala(previo[ultimo - 1]), 3.5, "#6F9CC6")
    lienzo.texto(x0 + (ultimo - 1) * paso - 6, escala(previo[ultimo - 1]) - 8, numero(previo[ultimo - 1]), 10, "#4F7FAD",
                 "end", "bold")
    for m in range(12):
        lienzo.texto(x0 + m * paso, y0 + 16, MES[m], 10.5, TINTA, "middle")
    lienzo.leyenda(x0, 290, [(f"Acumulado {p['anio_comparable']}", PREVIO, "linea"),
                             (f"Acumulado {p['anio']} (meses completos)", ACTUAL, "linea"),
                             (f"PIM {p['anio']}", ACENTO, "guiones")])
    lienzo.guardar(ruta, "Ingresos propios acumulados")


def barras_horizontales(ruta, descripcion, filas, leyenda, unidad, divisor, decimales=0, ancho_etiqueta=250, alto_fila=30,
                        notas_derecha=None):
    """Pares de barras horizontales (anterior, actual) por fila; filas = [(etiqueta, anterior, actual)]."""
    alto = 50 + alto_fila * len(filas) + 34
    lienzo = Lienzo(680, alto)
    x0, x1 = ancho_etiqueta, 600
    maximo = max(max(a or 0, b or 0) for _, a, b in filas) / divisor
    paso = paso_redondo(maximo, 4)
    tope = paso * math.ceil(maximo / paso)
    escala = lambda v: x0 + v / tope * (x1 - x0)  # noqa: E731
    y_fin = 30 + alto_fila * len(filas)
    v = 0
    while v <= tope + 1e-9:
        lienzo.linea(escala(v), 26, escala(v), y_fin, REJILLA if v else "#9AA5B1")
        lienzo.texto(escala(v), y_fin + 14, numero(v, decimales), 9.5, TENUE, "middle")
        v += paso
    lienzo.texto(x1, 16, unidad, 9.5, TENUE, "end")
    for i, (etiqueta, anterior, actual) in enumerate(filas):
        y = 30 + i * alto_fila
        lienzo.texto(x0 - 8, y + alto_fila * 0.55, etiqueta, 10.5, TINTA, "end")
        if anterior is not None:
            lienzo.rect(x0, y + alto_fila * 0.14, escala(anterior / divisor) - x0, alto_fila * 0.32, PREVIO)
        if actual is not None:
            # sin serie anterior, una sola barra centrada en la fila
            arriba, grosor = (0.48, 0.32) if anterior is not None else (0.2, 0.6)
            lienzo.rect(x0, y + alto_fila * arriba, escala(actual / divisor) - x0, alto_fila * grosor, ACTUAL)
            texto = notas_derecha[i] if notas_derecha else numero(actual / divisor, decimales)
            lienzo.texto(max(escala(actual / divisor), escala(anterior / divisor) if anterior else 0) + 5,
                         y + alto_fila * (0.72 if anterior is not None else 0.6), texto, 9.5, TINTA)
    lienzo.leyenda(x0, alto - 8, leyenda)
    lienzo.guardar(ruta, descripcion)


def conceptos(ind, p, ruta):
    datos = ind["series"]["conceptos"]
    filas = sorted(((k, float(v["ytd_prev"]), float(v["ytd"])) for k, v in datos.items()
                    if float(v["ytd"]) or float(v["ytd_prev"])), key=lambda f: -f[2])
    ytd = f"ene–{MES[p['meses_completos'] - 1].lower()}"
    barras_horizontales(ruta, "Ingresos propios por concepto", filas,
                        [(f"{ytd} {p['anio_comparable']}", PREVIO, "caja"), (f"{ytd} {p['anio']}", ACTUAL, "caja")],
                        "miles S/", 1e3, 0, 270, 28)


def predial(ind, p, ruta):
    d = {k: float(v) for k, v in ind["series"]["predial"].items()}
    lienzo = Lienzo(680, 230)
    x0, x1 = 250, 600
    maximo = d["emitido"] / 1e3
    paso = paso_redondo(maximo, 4)
    tope = paso * math.ceil(maximo / paso)
    escala = lambda v: x0 + v / tope * (x1 - x0)  # noqa: E731
    filas = [(f"Emitido {p['anio']} (padrón)", d["emitido"], NEUTRO),
             ("Exigible al corte, si fraccionan", d["exigible"], PREVIO),
             (f"Recaudado del año, al corte", d["recaudado_anio"], ACTUAL)]
    for i, (etiqueta, valor, color) in enumerate(filas):
        y = 30 + i * 44
        lienzo.texto(x0 - 8, y + 20, etiqueta, 10.5, TINTA, "end")
        lienzo.rect(x0, y + 6, escala(valor / 1e3) - x0, 22, color)
        lienzo.texto(escala(valor / 1e3) + 6, y + 21, numero(valor / 1e3), 10.5, TINTA, "start", "bold")
    y_fin = 30 + 3 * 44
    v = 0
    while v <= tope + 1e-9:
        lienzo.linea(escala(v), 26, escala(v), y_fin, REJILLA if v else "#9AA5B1")
        lienzo.texto(escala(v), y_fin + 14, numero(v), 9.5, TENUE, "middle")
        v += paso
    lienzo.texto(x1, 16, "miles S/", 9.5, TENUE, "end")
    lienzo.guardar(ruta, "Impuesto predial del año: emitido, exigible y recaudado")


def concentracion(padron, ruta):
    filas = [(f"{q['porcentaje_contribuyentes']} % ({numero(q['contribuyentes'])} contribuyentes)",
              None, float(q["participacion"])) for q in padron["concentracion"]]
    barras_horizontales(ruta, "Concentración del impuesto predial emitido", filas,
                        [("Parte del impuesto emitido que pagan", ACTUAL, "caja")], "% del impuesto emitido", 1, 0,
                        250, 28, [f"{numero(f[2], 1)} %" for f in filas])


def main():
    p = parametros()
    e = edicion()
    ind = json.loads((e / "generado" / "indicadores.json").read_text(encoding="utf-8"))
    padron = json.loads((e / "datos" / "padron" / "agregados.json").read_text(encoding="utf-8"))
    carpeta = e / "generado" / "graficos"
    carpeta.mkdir(parents=True, exist_ok=True)
    composicion(ind, p, carpeta / "composicion-anual.svg")
    mensual_propios(ind, p, carpeta / "mensual-propios.svg")
    acumulado_propios(ind, p, carpeta / "acumulado-propios.svg")
    conceptos(ind, p, carpeta / "conceptos.svg")
    predial(ind, p, carpeta / "predial.svg")
    concentracion(padron, carpeta / "concentracion.svg")
    print(f"gráficos: {len(list(carpeta.glob('*.svg')))} en {carpeta}")


if __name__ == "__main__":
    main()
