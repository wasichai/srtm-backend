"""Validaciones de los manuales que asciidoctor no hace. Se corre desde docs/manuales: python3 herramientas/validar.py

Fuentes (.adoc):
- toda imagen referenciada existe, y toda captura de herramientas/capturas/*.json se usa en algún manual;
- ninguna ruta absoluta de una máquina (/Users/…, /home/…, C:\\…);
- ningún secreto: tokens JWT, claves de AWS, contraseñas que no sean las de la demo;
- ninguna sección vacía (un título seguido de otro título del mismo nivel o superior);
- ningún TODO/FIXME/XXX ni texto de relleno;
- la versión de atributos.adoc es la de gradle.properties.

Salida (si existe salida/html y salida/pdf):
- cada enlace interno del HTML (#ancla y página.html#ancla) resuelve;
- cada PDF tiene todas sus fuentes incrustadas (pdffonts) y marcadores.

Sale con 1 si algo falla, y lista cada falla con su archivo y línea.
"""
import html.parser
import json
import pathlib
import re
import shutil
import subprocess
import sys

RAIZ = pathlib.Path(__file__).resolve().parent.parent
REPO = RAIZ.parent.parent
fallas = []


def falla(donde, mensaje):
    fallas.append(f"{donde}: {mensaje}")


def fuentes():
    return sorted(p for p in RAIZ.rglob("*.adoc") if "salida" not in p.parts and "node_modules" not in p.parts)


# --- imágenes ---------------------------------------------------------------------------------------------------

IMAGEN = re.compile(r"image::?([^\[\s]+)\[")
ATRIBUTO = re.compile(r"\{([a-z0-9-]+)\}")


def atributos():
    valores = {}
    for linea in (RAIZ / "atributos.adoc").read_text(encoding="utf-8").splitlines():
        m = re.match(r"^:([a-z0-9-]+):\s*(.*)$", linea)
        if m:
            valores[m.group(1)] = m.group(2)
    return valores


def resolver(texto, valores):
    for _ in range(5):
        texto = ATRIBUTO.sub(lambda m: valores.get(m.group(1), m.group(0)), texto)
    return texto


def revisar_imagenes(valores):
    usadas = set()
    # README.adoc explica la sintaxis con ejemplos: no es un manual
    for adoc in (f for f in fuentes() if f.name != "README.adoc"):
        for n, linea in enumerate(adoc.read_text(encoding="utf-8").splitlines(), 1):
            if linea.lstrip().startswith("//"):
                continue
            for m in IMAGEN.finditer(linea):
                destino = resolver(m.group(1), valores)
                if destino.startswith(("http://", "https://", "data:")):
                    continue
                for ext in (["svg", "png"] if "{diagext}" in m.group(1) else [None]):
                    ruta = destino.replace("{diagext}", ext) if ext else destino
                    archivo = RAIZ / "recursos" / ruta
                    if not archivo.exists():
                        falla(f"{adoc.relative_to(RAIZ)}:{n}", f"imagen inexistente: recursos/{ruta}")
                    usadas.add(str(archivo.resolve()))
    for manifiesto in sorted((RAIZ / "herramientas" / "capturas").glob("*.json")):
        for captura in json.loads(manifiesto.read_text(encoding="utf-8")).get("capturas", []):
            archivo = RAIZ / "recursos" / "capturas" / captura["archivo"]
            if not archivo.exists():
                falla(f"herramientas/capturas/{manifiesto.name}", f"captura sin archivo: {captura['archivo']} (make capturas)")
            elif str(archivo.resolve()) not in usadas:
                falla(f"herramientas/capturas/{manifiesto.name}", f"captura que ningún manual usa: {captura['archivo']}")


# --- texto ------------------------------------------------------------------------------------------------------

RUTA_ABSOLUTA = re.compile(r"(?<![\w.])(/Users/|/home/[a-z]|/private/tmp/|[A-Z]:\\\\)")
SECRETOS = [
    (re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"), "token JWT"),
    (re.compile(r"AKIA[0-9A-Z]{16}"), "clave de acceso de AWS"),
    (re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"), "token de GitHub"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "clave privada"),
]
# TODO en mayúsculas (la palabra «todo» es español corriente); lorem ipsum en cualquier caja
RELLENO = re.compile(r"\b(TODO|FIXME|XXX)\b|(?i:lorem ipsum)")
TITULO = re.compile(r"^(=+) \S")


def revisar_texto():
    for adoc in fuentes():
        lineas = adoc.read_text(encoding="utf-8").splitlines()
        dentro_de_bloque = False
        anterior = None
        for n, linea in enumerate(lineas, 1):
            donde = f"{adoc.relative_to(RAIZ)}:{n}"
            if RUTA_ABSOLUTA.search(linea):
                falla(donde, "ruta absoluta de una máquina")
            for patron, nombre in SECRETOS:
                if patron.search(linea):
                    falla(donde, f"parece un secreto ({nombre})")
            if RELLENO.search(linea) and not linea.lstrip().startswith("//"):
                falla(donde, "texto pendiente o de relleno")
            if linea.startswith(("----", "....", "++++")):
                dentro_de_bloque = not dentro_de_bloque
            if dentro_de_bloque or adoc.name in ("atributos.adoc",):
                continue
            m = TITULO.match(linea)
            if m:
                nivel = len(m.group(1))
                if anterior and anterior[0] >= nivel and anterior[0] > 1:
                    falla(f"{adoc.relative_to(RAIZ)}:{anterior[1]}", "sección vacía")
                anterior = (nivel, n)
            elif linea.strip() and not linea.startswith(("[", ":", "//", "include::", "ifdef::", "endif::", "ifndef::")):
                anterior = None


def revisar_version(valores):
    propiedades = (REPO / "gradle.properties").read_text(encoding="utf-8")
    m = re.search(r"^version=(.+)$", propiedades, re.MULTILINE)
    if m and m.group(1).strip() != valores.get("version-producto"):
        falla("atributos.adoc", f"version-producto {valores.get('version-producto')} ≠ gradle.properties {m.group(1)}")


# --- salida -----------------------------------------------------------------------------------------------------

class Enlaces(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.hrefs = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a:
            self.ids.add(a["id"])
        if tag == "a" and "name" in a:
            self.ids.add(a["name"])
        if tag == "a" and a.get("href"):
            self.hrefs.append(a["href"])
        if tag in ("img", "link", "script") and (a.get("src") or a.get("href")):
            self.hrefs.append(a.get("src") or a.get("href"))


def revisar_html():
    raiz = RAIZ / "salida" / "html"
    if not raiz.exists():
        return
    paginas = {}
    for pagina in raiz.rglob("*.html"):
        p = Enlaces()
        p.feed(pagina.read_text(encoding="utf-8"))
        paginas[pagina.resolve()] = p
    for pagina, p in paginas.items():
        for href in p.hrefs:
            if re.match(r"^[a-z]+:", href) or href.startswith("//"):
                continue
            archivo, _, ancla = href.partition("#")
            destino = (pagina.parent / archivo).resolve() if archivo else pagina
            donde = str(pagina.relative_to(raiz.resolve()))
            if not destino.exists():
                falla(f"salida/html/{donde}", f"enlace roto: {href}")
            elif ancla and destino in paginas and ancla not in paginas[destino].ids:
                falla(f"salida/html/{donde}", f"ancla inexistente: {href}")


def revisar_pdf():
    raiz = RAIZ / "salida" / "pdf"
    if not raiz.exists():
        return
    if not shutil.which("pdffonts"):
        print("aviso: sin pdffonts (poppler), no se revisan las fuentes del PDF")
        return
    for pdf in sorted(raiz.rglob("*.pdf")):
        donde = f"salida/pdf/{pdf.relative_to(raiz)}"
        salida = subprocess.run(["pdffonts", str(pdf)], capture_output=True, text=True, check=True).stdout
        for linea in salida.splitlines()[2:]:
            columnas = linea.split()
            # name type encoding emb sub uni object ID: emb es la antepenúltima de las seis banderas
            if len(columnas) >= 7 and columnas[-5] != "yes":
                falla(donde, f"fuente sin incrustar: {columnas[0]}")
        info = subprocess.run(["pdfinfo", str(pdf)], capture_output=True, text=True, check=True).stdout
        if "Pages:" not in info:
            falla(donde, "pdfinfo no lo lee")
        marcadores = subprocess.run(["pdfinfo", "-dests", str(pdf)], capture_output=True, text=True).stdout
        if marcadores.count("\n") < 5:
            falla(donde, "sin destinos ni marcadores")


def main():
    valores = atributos()
    revisar_imagenes(valores)
    revisar_texto()
    revisar_version(valores)
    revisar_html()
    revisar_pdf()
    if fallas:
        print("\n".join(fallas))
        print(f"validar: {len(fallas)} fallas")
        return 1
    print(f"validar: ok ({len(fuentes())} fuentes .adoc)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
