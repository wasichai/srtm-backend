"""Validaciones de una edición antes de entregarla.

Uso: EDICION=<carpeta> python3 -I herramientas/validar.py

Comprueba:
- números: la conciliación con los totales publicados da cero; el recálculo del impuesto coincide con el padrón;
  las categorías suman el total; el padrón es de la municipalidad y del año;
- textos: ninguna cifra tecleada (montos, porcentajes, números de más de tres cifras que no son años) fuera de los
  atributos de generado/cifras.adoc (o de textos/unidades.adoc); ningún atributo sin definir;
- privacidad: ninguna salida lleva un DNI o RUC (8 u 11 dígitos seguidos), un correo o el identificador del padrón;
- salida: el HTML y el PDF existen, el PDF incrusta todas sus fuentes, y cada gráfico citado existe.
"""

import json
import os
import re
import shutil
import subprocess
import sys
from decimal import Decimal

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from comun import edicion  # noqa: E402

fallas = []


def falla(donde, mensaje):
    fallas.append(f"{donde}: {mensaje}")


# una cifra tecleada: S/ seguido de un número, un porcentaje, o un número de cuatro o más dígitos (con o sin
# separador de miles) que no sea un año 2000-2099
CIFRA = re.compile(r"S/\s*\d|\d+(?:,\d+)?\s*%|\b\d{1,3}(?:[  .]\d{3})+\b|\b(?!20\d\d\b)\d{4,}\b")
DOCUMENTO = re.compile(r"(?<![\d.,])\d{8}(?:\d{3})?(?![\d.,])")
CORREO = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")


def textos(e):
    definidos = set()
    for ruta in (e / "generado" / "cifras.adoc", e / "textos" / "unidades.adoc"):
        for linea in ruta.read_text(encoding="utf-8").splitlines():
            if m := re.match(r":([\w-]+!?):", linea):
                definidos.add(m.group(1).rstrip("!"))
    propios = {"municipalidad", "author", "revnumber", "revdate", "version", "elaboracion", "periodo", "corte"}
    for ruta in sorted((e / "textos").glob("*.adoc")) + [e / "informe.adoc"]:
        donde = ruta.relative_to(e)
        en_comentario = False
        for n, linea in enumerate(ruta.read_text(encoding="utf-8").splitlines(), 1):
            if linea.startswith("////"):
                en_comentario = not en_comentario
                continue
            if en_comentario or linea.startswith("//"):
                continue
            for m in re.finditer(r"\{([\w-]+)\}", linea):
                nombre = m.group(1)
                if nombre not in definidos and nombre not in propios and not re.match(
                        r"^(nbsp|sp|empty|zwsp|startsb|endsb|vbar|caret|amp|lt|gt|plus|blank|counter.*)$", nombre):
                    falla(f"{donde}:{n}", f"atributo sin definir: {{{nombre}}}")
            limpio = re.sub(r"\{[\w-]+\}", "", linea)
            limpio = re.sub(r"<<[^>]+>>|\[\[[^\]]+\]\]|xref:\S+\[|image::?\S+\[[^\]]*\]|include::\S+\[|https?://\S+", "",
                            limpio)
            if not linea.startswith(":") and (m := CIFRA.search(limpio)):
                falla(f"{donde}:{n}", f"cifra tecleada «{m.group(0)}»: debe ser un atributo de generado/cifras.adoc")


def numeros(e):
    ind = json.loads((e / "generado" / "indicadores.json").read_text(encoding="utf-8"))
    for r in ind["conciliacion"]:
        if Decimal(r["diferencia"]) != 0:
            falla("conciliación", f"{r['anio']}: el calculado difiere del publicado en {r['diferencia']}")
    if not ind["conciliacion"]:
        falla("conciliación", "no hay datos/referencias.json: el total no se concilió con una fuente publicada")
    padron = json.loads((e / "datos" / "padron" / "agregados.json").read_text(encoding="utf-8"))
    if padron["control_recalculo"]["difieren"]:
        falla("padrón", f"{padron['control_recalculo']['difieren']} contribuyentes con un impuesto distinto del recalculado")
    for campo in ("filas_de_otra_ejecutora", "filas_de_otro_anio"):
        if padron[campo]:
            falla("padrón", f"{campo}: {padron[campo]}")
    total = Decimal(ind["cifras"]["rec-corte"]["valor"])
    suma = sum((Decimal(ind["cifras"][f"rec-{g}-corte"]["valor"]) for g in ("propios", "transf", "fin")), Decimal(0))
    otros = Decimal(ind["grupos"]["Otros"]["corte"])
    if total != suma + otros:
        falla("categorías", f"los grupos suman {suma + otros} y el total es {total}")


# columnas del padrón que nunca deben aparecer en un archivo de la edición
CAMPOS_PERSONALES = ("propietario_id", "num_doc", "apellido_paterno", "apellido_materno", "razon_social")


def privacidad(e):
    # los JSON y CSV llevan montos sin formato (un PIM de ocho cifras parece un DNI): en ellos se buscan los campos
    # personales del padrón; en los textos, tablas y salidas con formato, además, documentos y correos
    for ruta in list((e / "generado").rglob("*.json")) + list((e / "generado").rglob("*.csv")) + \
            list((e / "datos" / "padron").glob("*.json")):
        texto = ruta.read_text(encoding="utf-8")
        for campo in CAMPOS_PERSONALES:
            if campo in texto:
                falla(str(ruta.relative_to(e)), f"contiene el campo personal {campo}")
    revisar = list((e / "generado").rglob("*.adoc")) + list((e / "generado").rglob("*.svg")) + \
        list((e / "textos").glob("*.adoc")) + list((e / "salida").rglob("*.html"))
    for ruta in revisar:
        texto = ruta.read_text(encoding="utf-8")
        # las sumas sha256 y las URL de las fuentes no son documentos
        texto = re.sub(r"[0-9a-f]{16,64}|https?://\S+", "", texto)
        for patron, que in ((DOCUMENTO, "un número de 8 u 11 dígitos (DNI o RUC)"), (CORREO, "un correo")):
            if m := patron.search(texto):
                falla(str(ruta.relative_to(e)), f"{que}: «{m.group(0)}»")
        for campo in CAMPOS_PERSONALES:
            if campo in texto:
                falla(str(ruta.relative_to(e)), f"menciona el campo personal {campo}")


def salida(e):
    html = e / "salida" / "html" / "index.html"
    pdf = e / "salida" / "pdf" / "informe-recaudacion.pdf"
    for ruta in (html, pdf):
        if not ruta.exists():
            falla(str(ruta.relative_to(e)), "no existe")
    for ruta in (e / "textos").glob("*.adoc"):
        for m in re.finditer(r"image::([^\[]+)\[", ruta.read_text(encoding="utf-8")):
            if not (e / "generado" / "graficos" / m.group(1)).exists():
                falla(str(ruta.relative_to(e)), f"gráfico inexistente: {m.group(1)}")
    if pdf.exists() and shutil.which("pdffonts"):
        lineas = subprocess.run(["pdffonts", str(pdf)], capture_output=True, text=True, check=True).stdout.splitlines()
        for linea in lineas[2:]:
            columnas = linea.split()
            if len(columnas) >= 5 and columnas[-5] != "yes":
                falla(str(pdf.relative_to(e)), f"fuente sin incrustar: {columnas[0]}")


def main():
    e = edicion()
    for paso in (numeros, textos, privacidad, salida):
        paso(e)
    if fallas:
        print("\n".join(fallas))
        sys.exit(f"validar: {len(fallas)} fallas")
    print("validar: ok")


if __name__ == "__main__":
    main()
