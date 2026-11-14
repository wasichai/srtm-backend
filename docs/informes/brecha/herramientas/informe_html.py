"""The informe de brechas as one self-contained HTML file (srtm-backend#301): the edición's textos rendered from a
small AsciiDoc subset, the tables and the inline SVG charts, with its CSS inline - no script, no font, no image
and no stylesheet from anywhere else, so the page makes no request at all. Light and dark follow the reader's
system (prefers-color-scheme) unless the page is stamped with data-theme; @media print gives the PDF from the
browser (Imprimir → Guardar como PDF).

The subset the textos use (plantilla/textos/*.adoc):

    == Sección / === Subsección / ==== Apartado      headings (a [[ancla]] line above sets its id)
    párrafos separados por una línea en blanco       ([.lead] above one makes it the lead paragraph)
    * viñeta / . paso numerado / Término:: definición
    NOTE: … / IMPORTANT: … / TIP: … / WARNING: …     one-paragraph admonitions
    *negrita*, _cursiva_, `código`, <<ancla,texto>>, {atributo}
    tabla::<nombre>[] / grafico::<nombre>[]          a generated table or chart (TABLAS, informe_graficos)
    ifdef::comparacion[] … endif::[]                 only when there is a corte anterior (ifndef:: the opposite)
    // comentario, //// bloque de comentario ////    never rendered
"""

import re
from decimal import Decimal
from html import escape

from . import informe_comun as ic
from .informe_graficos import (
    ETIQUETA_CATEGORIA,
    ETIQUETA_CLASE,
    ETIQUETA_CONFIANZA,
    ETIQUETA_NIVEL,
    etiqueta,
)
from .informe_indicadores import ORDEN_CONFIANZA


class ErrorTexto(Exception):
    """A texto that cannot be rendered (an unknown table or chart, an unclosed ifdef)."""


ADMONICION = {"NOTE": "Nota", "IMPORTANT": "Importante", "TIP": "Sugerencia", "WARNING": "Advertencia",
              "CAUTION": "Atención"}
RESERVADO = '<span class="reservado">reservado</span>'


# --- inline ---------------------------------------------------------------------------------------------------


def en_linea(texto, atributos):
    enlaces = []

    def guardar(m):
        enlaces.append((m.group(1), m.group(2)))
        return f"\x00{len(enlaces) - 1}\x00"

    texto = re.sub(r"<<([\w-]+)(?:,([^>]+))?>>", guardar, texto)
    texto = escape(texto, quote=False)
    texto = re.sub(r"\{([\w-]+)\}", lambda m: escape(atributos[m.group(1)], quote=False)
                   if m.group(1) in atributos else m.group(0), texto)
    texto = re.sub(r"`([^`]+)`", r"<code>\1</code>", texto)
    texto = re.sub(r"(?<![\w*])\*(\S(?:[^*]*?\S)?)\*(?![\w*])", r"<strong>\1</strong>", texto)
    texto = re.sub(r"(?<![\w_])_(\S(?:[^_]*?\S)?)_(?![\w_])", r"<em>\1</em>", texto)

    def enlace(m):
        ancla, rotulo = enlaces[int(m.group(1))]
        if not rotulo:
            # the heading's own title, filled in by armar() once every texto is rendered
            return f'<a href="#{escape(ancla)}" data-xref="">{escape(ancla)}</a>'
        return f'<a href="#{escape(ancla)}">{en_linea(rotulo, atributos)}</a>'

    return re.sub(r"\x00(\d+)\x00", enlace, texto)


# --- blocks ---------------------------------------------------------------------------------------------------


class Documento:
    def __init__(self, atributos, tablas, graficos):
        self.atributos = atributos
        self.tablas = tablas
        self.graficos = graficos
        self.partes = []
        self.secciones = []  # (id, título) of each == heading, for the table of contents
        self.n_tabla = 0
        self.n_grafico = 0

    def _id(self, titulo, propio):
        if propio:
            return propio
        llano = titulo.lower().translate(str.maketrans("áéíóúñü", "aeiounu"))
        base = re.sub(r"[^a-z0-9]+", "-", llano).strip("-")
        return f"_{base}"

    def bloque_tabla(self, nombre):
        if nombre not in self.tablas:
            raise ErrorTexto(f"tabla desconocida: tabla::{nombre}[] (hay: {', '.join(sorted(self.tablas))})")
        titulo, html = self.tablas[nombre]
        self.n_tabla += 1
        self.partes.append(
            f'<figure class="tabla" id="tabla-{escape(nombre)}"><figcaption>Tabla {self.n_tabla}. '
            f"{escape(titulo)}</figcaption>{html}</figure>"
        )

    def bloque_grafico(self, nombre):
        if nombre not in self.graficos:
            raise ErrorTexto(f"gráfico desconocido: grafico::{nombre}[] (hay: {', '.join(sorted(self.graficos))})")
        svg = self.graficos[nombre]
        titulo = re.search(r"<title>(.*?)</title>", svg).group(1)
        self.n_grafico += 1
        self.partes.append(
            f'<figure class="grafico" id="grafico-{escape(nombre)}">{svg}'
            f"<figcaption>Gráfico {self.n_grafico}. {titulo}</figcaption></figure>"
        )

    def renderizar(self, texto, donde=""):
        lineas = texto.splitlines()
        pila = []  # ifdef/ifndef: whether each open block is shown
        comentario = False
        parrafo, lista, tipo_lista, definiciones = [], [], None, []
        pendiente_id, pendiente_clase = None, None

        def mostrar():
            return all(pila)

        def cerrar():
            nonlocal parrafo, lista, tipo_lista, definiciones, pendiente_clase
            if parrafo:
                contenido = " ".join(parrafo)
                m = re.match(r"^(NOTE|IMPORTANT|TIP|WARNING|CAUTION):\s+(.*)$", contenido)
                if m:
                    self.partes.append(
                        f'<aside class="admonicion {m.group(1).lower()}"><strong>{ADMONICION[m.group(1)]}.</strong> '
                        f"{en_linea(m.group(2), self.atributos)}</aside>"
                    )
                else:
                    clase = f' class="{pendiente_clase}"' if pendiente_clase else ""
                    self.partes.append(f"<p{clase}>{en_linea(contenido, self.atributos)}</p>")
                pendiente_clase = None
            if lista:
                etiqueta_lista = "ol" if tipo_lista == "." else "ul"
                items = "".join(f"<li>{en_linea(' '.join(i), self.atributos)}</li>" for i in lista)
                self.partes.append(f"<{etiqueta_lista}>{items}</{etiqueta_lista}>")
            if definiciones:
                items = "".join(
                    f"<dt>{en_linea(t, self.atributos)}</dt><dd>{en_linea(' '.join(d), self.atributos)}</dd>"
                    for t, d in definiciones
                )
                self.partes.append(f'<dl class="glosario">{items}</dl>')
            parrafo, lista, tipo_lista, definiciones = [], [], None, []

        for n, linea in enumerate(lineas, 1):
            crudo = linea.rstrip()
            if crudo.startswith("////"):
                comentario = not comentario
                continue
            if comentario or (crudo.startswith("//") and not crudo.startswith("////")):
                continue
            m = re.match(r"^(ifdef|ifndef)::([\w-]+)\[\]$", crudo)
            if m:
                definido = m.group(2) in self.atributos
                pila.append(definido if m.group(1) == "ifdef" else not definido)
                continue
            if re.match(r"^endif::([\w-]*)\[\]$", crudo):
                if not pila:
                    raise ErrorTexto(f"{donde}:{n}: endif::[] sin ifdef")
                cerrar()
                pila.pop()
                continue
            if not mostrar():
                continue
            if not crudo.strip():
                cerrar()
                continue
            m = re.match(r"^\[\[([\w-]+)\]\]$", crudo)
            if m:
                cerrar()
                pendiente_id = m.group(1)
                continue
            m = re.match(r"^\[\.([\w-]+)\]$", crudo)
            if m:
                cerrar()
                pendiente_clase = m.group(1)
                continue
            m = re.match(r"^(={2,4})\s+(.*)$", crudo)
            if m:
                cerrar()
                nivel = len(m.group(1))
                titulo = m.group(2).strip()
                ancla = self._id(titulo, pendiente_id)
                pendiente_id = None
                if nivel == 2:
                    self.secciones.append((ancla, en_linea(titulo, self.atributos)))
                self.partes.append(f'<h{nivel} id="{escape(ancla)}">{en_linea(titulo, self.atributos)}</h{nivel}>')
                continue
            m = re.match(r"^(tabla|grafico)::([\w-]+)\[\]$", crudo)
            if m:
                cerrar()
                (self.bloque_tabla if m.group(1) == "tabla" else self.bloque_grafico)(m.group(2))
                continue
            m = re.match(r"^(\*{1,2}|\.)\s+(.*)$", crudo)
            if m:
                if parrafo or definiciones:
                    cerrar()
                tipo = "." if m.group(1) == "." else "*"
                if lista and tipo != tipo_lista:
                    cerrar()
                tipo_lista = tipo
                lista.append([m.group(2)])
                continue
            m = re.match(r"^(.+?)::\s*(.*)$", crudo)
            if m and not crudo.startswith(" "):
                if parrafo or lista:
                    cerrar()
                definiciones.append((m.group(1), [m.group(2)] if m.group(2) else []))
                continue
            if lista:
                lista[-1].append(crudo.strip())
            elif definiciones:
                definiciones[-1][1].append(crudo.strip())
            else:
                parrafo.append(crudo.strip())
        if pila:
            raise ErrorTexto(f"{donde}: falta un endif::[]")
        cerrar()


# --- tables ---------------------------------------------------------------------------------------------------


def _m(valor, signo=False):
    return RESERVADO if valor is None else escape(ic.soles(valor, signo))


def _celda(contenido, numerica=True):
    return f'<td class="num">{contenido}</td>' if numerica else f"<td>{contenido}</td>"


def _tabla(cabecera, filas, pie=None):
    clase = ' class="num"'
    th = "".join(f'<th scope="col"{clase if i else ""}>{escape(c)}</th>' for i, c in enumerate(cabecera))
    cuerpo = "".join("<tr>" + "".join(f) + "</tr>" for f in filas)
    nota = f'<p class="nota-tabla">{pie}</p>' if pie else ""
    return f'<div class="desborde"><table><thead><tr>{th}</tr></thead><tbody>{cuerpo}</tbody></table></div>{nota}'


def _nota_reservado(ind, *grupos):
    if any(c.get("reservado") for g in grupos for c in g):
        return (f"«reservado»: el grupo suma menos de {ind['umbral']} predios, o su cifra permitiría deducir la de "
                "uno así (Ley 29733); solo se muestra cuántos son.")
    return None


def _pct(parte, total):
    if parte is None or not total:
        return "—"
    return escape(ic.porcentaje(Decimal(str(parte)) / Decimal(str(total)) * 100))


def tablas(ind, edicion, comparacion=None):
    t = {}
    corte, corrida = ind["corte"], ind["corrida"] or {}
    filas = [
        ("Organización", edicion["municipalidad"]),
        ("Fecha del corte", ic.fecha_larga(corte["fecha_corte"])),
        ("Año de la corrida", str(corte["anio"])),
        ("Tipo de corte", corte["tipo"] or "—"),
        ("Motor de valuación", corte["version_motor"] or "—"),
        ("Región del motor", corte["region"] or "—"),
        ("Versión de catastro", corte["version_catastro"] or "—"),
        ("Estado de la corrida", corrida.get("estado") or "sin corrida terminada"),
        ("Origen de la corrida", corrida.get("origen") or "—"),
        ("Fin de la corrida", ic.fecha_larga(corrida["terminado"]) if corrida.get("terminado") else "—"),
        ("Avisos del manifiesto", str(len(corte["avisos"]))),
    ]
    t["corte"] = ("El corte analítico", _tabla(
        ["Dato", "Valor"], [[_celda(escape(a), False), _celda(escape(b), False)] for a, b in filas]))
    if corte["avisos"]:
        t["avisos"] = ("Avisos del manifiesto del corte", _tabla(
            ["Aviso"], [[_celda(escape(a), False)] for a in corte["avisos"]]))
    else:
        t["avisos"] = ("Avisos del manifiesto del corte", _tabla(["Aviso"], [[_celda("Ninguno.", False)]]))

    tot = ind["totales"]
    t["totales"] = ("Totales de la corrida", _tabla(
        ["Indicador", "Valor"],
        [[_celda("Predios", False), _celda(escape(ic.numero(tot["predios"])))]]
        + [[_celda(nombre, False), _celda(_m(tot[campo], campo.startswith("delta")))] for campo, nombre in (
            ("autoavaluo_declarado", "Autoavalúo declarado"), ("autoavaluo_catastro", "Autoavalúo de catastro"),
            ("delta_autoavaluo", "Δ autoavalúo"), ("impuesto_declarado", "Impuesto declarado"),
            ("impuesto_catastro", "Impuesto con catastro"), ("delta_impuesto", "Δ impuesto"))],
        _nota_reservado(ind, [tot])))

    t["confianza"] = ("Predios y brecha según la confianza", _tabla(
        ["Confianza", "Predios", "% de predios", "Δ autoavalúo", "Impuesto declarado", "Impuesto con catastro",
         "Δ impuesto"],
        [[_celda(escape(etiqueta(ETIQUETA_CONFIANZA, c["clave"])), False), _celda(escape(ic.numero(c["predios"]))),
          _celda(_pct(c["predios"], tot["predios"])), _celda(_m(c["delta_autoavaluo"], True)),
          _celda(_m(c["impuesto_declarado"])), _celda(_m(c["impuesto_catastro"])),
          _celda(_m(c["delta_impuesto"], True))] for c in ind["confianza"]],
        _nota_reservado(ind, ind["confianza"])))

    def matriz(lista, nombre_fila, etiquetas, titulo):
        confianzas = [k for k in ORDEN_CONFIANZA if any(c["confianza"] == k for c in lista)]
        confianzas += sorted({c["confianza"] for c in lista} - set(confianzas))
        claves = list(dict.fromkeys(c["clave"] for c in sorted(
            lista, key=lambda c: (0 if c["confianza"] == "CONFIABLE" else 1))))
        cabecera = [nombre_fila]
        for k in confianzas:
            nombre = etiqueta(ETIQUETA_CONFIANZA, k)
            cabecera += [f"{nombre}: predios", f"{nombre}: Δ impuesto"]
        filas = []
        for clave in claves:
            fila = [_celda(escape(etiqueta(etiquetas, clave)), False)]
            for k in confianzas:
                c = next((x for x in lista if x["clave"] == clave and x["confianza"] == k), None)
                if c is None:
                    fila += [_celda("0"), _celda("—")]
                else:
                    fila += [_celda(escape(ic.numero(c["predios"]))), _celda(_m(c["delta_impuesto"], True))]
            filas.append(fila)
        return (titulo, _tabla(cabecera, filas, _nota_reservado(ind, lista)))

    t["categorias"] = matriz(ind["categorias"], "Categoría", ETIQUETA_CATEGORIA, "Brecha por categoría y confianza")
    t["clases"] = matriz(ind["clases"], "Clase de construcción", ETIQUETA_CLASE,
                         "Brecha por clase de construcción y confianza")

    p = ind["predial"]
    base = None if p["declarado"] is None else Decimal(p["declarado"])
    filas = []
    for campo, nombre in (("declarado", "Declarado (padrón de origen)"), ("motor_rentas", "Motor de rentas (srtm)"),
                          ("motor_catastro", "Motor con catastro")):
        dif = None if p[campo] is None or base is None else Decimal(p[campo]) - base
        filas.append([_celda(nombre, False), _celda(_m(p[campo])),
                      _celda("—" if campo == "declarado" else _m(dif, True))])
    t["predial"] = (f"Impuesto predial del año en tres columnas ({ic.numero(p['contribuyentes'])} contribuyentes)",
                    _tabla(["Columna", "Impuesto", "Diferencia con el declarado"], filas,
                           _nota_reservado(ind, [p])))

    total_niveles = sum(n["niveles"] for n in ind["niveles"])
    t["niveles"] = ("Niveles comparados por estado", _tabla(
        ["Estado", "Niveles", "% de niveles", "Predios", "Área en rentas (m²)", "Área en catastro (m²)"],
        [[_celda(escape(etiqueta(ETIQUETA_NIVEL, n["clave"])), False), _celda(escape(ic.numero(n["niveles"]))),
          _celda(_pct(n["niveles"], total_niveles)), _celda(escape(ic.numero(n["predios"]))),
          _celda(RESERVADO if n["area_rentas"] is None and n["reservado"] else escape(ic.numero(n["area_rentas"]))),
          _celda(RESERVADO if n["area_catastro"] is None and n["reservado"] else escape(ic.numero(n["area_catastro"])))]
         for n in ind["niveles"]] or [[_celda("Sin niveles comparados en esta corrida.", False)] + [_celda("")] * 5],
        _nota_reservado(ind, ind["niveles"])))
    m = ind["niveles_catastro_mayor"]
    area = lambda v: RESERVADO if m["reservado"] else escape(ic.numero(v))  # noqa: E731
    t["niveles-catastro-mayor"] = ("Niveles en par con más área en catastro que en rentas", _tabla(
        ["Predios", "Niveles", "Área en rentas (m²)", "Área en catastro (m²)", "Diferencia (m²)"],
        [[_celda(escape(ic.numero(m["predios"]))), _celda(escape(ic.numero(m["niveles"]))),
          _celda(area(m["area_rentas"])), _celda(area(m["area_catastro"])), _celda(area(m["delta_area"]))]],
        _nota_reservado(ind, [m])))

    if comparacion:
        def fila_comp(f):
            formato = (lambda v, signo=False: ic.soles(v, signo)) if f["unidad"] == "soles" else \
                (lambda v, signo=False: ic.numero(v, 0, signo))
            valor = lambda v, signo=False: RESERVADO if v is None else escape(formato(v, signo))  # noqa: E731
            return [_celda(escape(f["indicador"]), False), _celda(valor(f["anterior"])), _celda(valor(f["actual"])),
                    _celda(valor(f["variacion"], True)),
                    _celda("—" if f["variacion_pct"] is None else escape(ic.porcentaje(f["variacion_pct"], True)))]

        anterior = ic.fecha_larga(comparacion["fecha_corte_anterior"])
        t["comparacion"] = (f"Comparación con el corte del {anterior}", _tabla(
            ["Indicador", "Corte anterior", "Corte actual", "Variación", "Variación %"],
            [fila_comp(f) for f in comparacion["filas"]]))
        filas = []
        for c in comparacion["categorias_confiables"]:
            nombre = etiqueta(ETIQUETA_CATEGORIA, c["categoria"])
            filas.append(fila_comp({**c["predios"], "indicador": f"{nombre}: predios"}))
            filas.append(fila_comp({**c["delta_impuesto"], "indicador": f"{nombre}: Δ impuesto"}))
        t["comparacion-categorias"] = ("Predios confiables por categoría: corte anterior y actual", _tabla(
            ["Indicador", "Corte anterior", "Corte actual", "Variación", "Variación %"], filas))
    return t


# --- page -----------------------------------------------------------------------------------------------------

CSS = """
:root {
  color-scheme: light;
  --fondo: #f9f9f7; --superficie: #fcfcfb; --tinta: #0b0b0b; --tinta-2: #52514e; --tenue: #898781;
  --rejilla: #e1e0d9; --eje: #c3c2b7; --borde: rgba(11,11,11,0.10); --acento: #2a78d6;
  --serie-1: #2a78d6; --serie-2: #eb6834; --serie-3: #1baf7a; --neutro: #898781; --anterior: #86b6ef;
  --positivo: #2a78d6; --negativo: #e34948; --nota: #eef4fc;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
    --fondo: #0d0d0d; --superficie: #1a1a19; --tinta: #f0efec; --tinta-2: #c3c2b7; --tenue: #898781;
    --rejilla: #2c2c2a; --eje: #383835; --borde: rgba(255,255,255,0.10); --acento: #3987e5;
    --serie-1: #3987e5; --serie-2: #d95926; --serie-3: #199e70; --neutro: #898781; --anterior: #184f95;
    --positivo: #3987e5; --negativo: #e66767; --nota: #16212f;
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --fondo: #0d0d0d; --superficie: #1a1a19; --tinta: #f0efec; --tinta-2: #c3c2b7; --tenue: #898781;
  --rejilla: #2c2c2a; --eje: #383835; --borde: rgba(255,255,255,0.10); --acento: #3987e5;
  --serie-1: #3987e5; --serie-2: #d95926; --serie-3: #199e70; --neutro: #898781; --anterior: #184f95;
  --positivo: #3987e5; --negativo: #e66767; --nota: #16212f;
}
* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body { margin: 0; background: var(--fondo); color: var(--tinta);
  font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Helvetica, Arial, sans-serif; }
.pagina { max-width: 980px; margin: 0 auto; padding: 32px 16px 64px; }
header.portada { border-bottom: 1px solid var(--borde); padding-bottom: 20px; margin-bottom: 24px; }
header.portada h1 { font-size: 1.9rem; line-height: 1.2; margin: 0 0 8px; }
header.portada .subtitulo { color: var(--tinta-2); margin: 0; }
header.portada .meta { color: var(--tenue); font-size: 0.9rem; margin: 8px 0 0; }
nav.indice { background: var(--superficie); border: 1px solid var(--borde); border-radius: 8px; padding: 12px 20px;
  margin-bottom: 32px; }
nav.indice h2 { font-size: 1rem; margin: 0 0 4px; }
nav.indice ol { margin: 0; padding-left: 20px; }
a { color: var(--acento); }
h2 { font-size: 1.45rem; margin: 40px 0 12px; padding-top: 8px; border-top: 1px solid var(--borde); }
h3 { font-size: 1.15rem; margin: 28px 0 8px; }
h4 { font-size: 1rem; margin: 20px 0 6px; }
p.lead { font-size: 1.1rem; }
code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.9em; }
.admonicion { background: var(--nota); border-left: 4px solid var(--acento); border-radius: 4px; padding: 10px 14px;
  margin: 16px 0; }
.admonicion.warning, .admonicion.important, .admonicion.caution { border-left-color: var(--negativo); }
figure { margin: 20px 0; }
figure.grafico { background: var(--superficie); border: 1px solid var(--borde); border-radius: 8px; padding: 12px; }
figure.grafico svg { display: block; width: 100%; height: auto; max-width: 680px; }
figcaption { color: var(--tinta-2); font-size: 0.9rem; margin: 6px 0; }
.desborde { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; background: var(--superficie); font-size: 0.92rem; }
th, td { border-bottom: 1px solid var(--rejilla); padding: 6px 10px; text-align: left; vertical-align: top; }
th { color: var(--tinta-2); font-weight: 600; }
.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.reservado { color: var(--tenue); font-style: italic; }
.nota-tabla { color: var(--tenue); font-size: 0.85rem; margin: 4px 0 0; }
dl.glosario dt { font-weight: 600; margin-top: 10px; }
dl.glosario dd { margin: 2px 0 0 20px; }
footer { margin-top: 48px; border-top: 1px solid var(--borde); padding-top: 12px; color: var(--tenue);
  font-size: 0.85rem; }
@media print {
  :root { color-scheme: light; }
  body { background: #fff; font-size: 11pt; }
  .pagina { max-width: none; padding: 0; }
  nav.indice { break-after: page; }
  figure, table, .admonicion { break-inside: avoid; }
  h2, h3 { break-after: avoid; }
  a { color: inherit; text-decoration: none; }
}
"""


def pagina(edicion, atributos, cuerpo, secciones, metodo):
    indice = "".join(f'<li><a href="#{escape(a)}">{t}</a></li>' for a, t in secciones)
    titulo = f"Informe de brechas: {edicion['municipalidad']}"
    version = escape(str(edicion.get("version") or "borrador"))
    elaborado = escape(str(edicion.get("elaborado") or ""))
    meta = f"Versión {version}" + (f" · {elaborado}" if elaborado else "")
    return f"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>{escape(titulo)}</title>
<style>{CSS}</style>
</head>
<body>
<div class="pagina">
<header class="portada">
<h1>{escape(titulo)}</h1>
<p class="subtitulo">Corte analítico del {escape(atributos['fecha-corte'])} · corrida {escape(atributos['anio'])}</p>
<p class="meta">{meta}</p>
</header>
<nav class="indice" aria-label="Contenido"><h2>Contenido</h2><ol>{indice}</ol></nav>
<main>
{cuerpo}
</main>
<footer>
<p>Solo agregados del corte analítico: ningún nombre, documento, código de predio ni monto de un predio
individual (Ley 29733). Un grupo de menos de {escape(atributos['umbral-celda'])} predios muestra cuántos son,
nunca sus montos.</p>
<p>Método: docs/informes/brecha (informe.py) · {escape(metodo)}</p>
</footer>
</div>
</body>
</html>
"""


def armar(edicion, atributos, textos, ind, graficos, comparacion, metodo):
    """The whole page; textos = [(nombre de archivo, contenido)] in order."""
    doc = Documento(atributos, tablas(ind, edicion, comparacion), graficos)
    for nombre, contenido in textos:
        doc.partes.append(f"<section data-texto=\"{escape(nombre)}\">")
        doc.renderizar(contenido, nombre)
        doc.partes.append("</section>")
    cuerpo = "\n".join(doc.partes)
    titulos = dict(re.findall(r'<h[234] id="([^"]+)">(.*?)</h[234]>', cuerpo))
    cuerpo = re.sub(r'<a href="#([^"]+)" data-xref="">[^<]*</a>',
                    lambda m: f'<a href="#{m.group(1)}">{titulos.get(m.group(1), m.group(1))}</a>', cuerpo)
    return pagina(edicion, atributos, cuerpo, doc.secciones, metodo)
