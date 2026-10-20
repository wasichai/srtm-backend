"""Lector mínimo de una hoja de un .xlsx con la biblioteca estándar (zipfile + ElementTree).

Devuelve las filas como diccionarios por nombre de columna (la primera fila es la cabecera). Solo valores: no
evalúa fórmulas (usa el valor que guardó Excel) ni lee estilos.
"""

import re
import zipfile
import xml.etree.ElementTree as ET

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
      "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
      "rel": "http://schemas.openxmlformats.org/package/2006/relationships"}
DC = {"cp": "http://schemas.openxmlformats.org/package/2006/metadata/core-properties",
      "dcterms": "http://purl.org/dc/terms/"}


def _columna(referencia):
    letras = re.match(r"[A-Z]+", referencia).group(0)
    numero = 0
    for letra in letras:
        numero = numero * 26 + ord(letra) - 64
    return numero - 1


def _compartidas(archivo):
    if "xl/sharedStrings.xml" not in archivo.namelist():
        return []
    raiz = ET.fromstring(archivo.read("xl/sharedStrings.xml"))
    return ["".join(t.text or "" for t in si.iter(f"{{{NS['m']}}}t")) for si in raiz.findall("m:si", NS)]


def _ruta_hoja(archivo, hoja):
    libro = ET.fromstring(archivo.read("xl/workbook.xml"))
    relaciones = ET.fromstring(archivo.read("xl/_rels/workbook.xml.rels"))
    destinos = {r.get("Id"): r.get("Target") for r in relaciones.findall("rel:Relationship", NS)}
    for h in libro.find("m:sheets", NS):
        if h.get("name") == hoja:
            destino = destinos[h.get(f"{{{NS['r']}}}id")].lstrip("/")
            return destino if destino.startswith("xl/") else f"xl/{destino}"
    nombres = [h.get("name") for h in libro.find("m:sheets", NS)]
    raise KeyError(f"no hay hoja {hoja!r}; hay {nombres}")


def propiedades(ruta):
    """Fechas de creación y última modificación que guardó Excel (docProps/core.xml)."""
    with zipfile.ZipFile(ruta) as archivo:
        if "docProps/core.xml" not in archivo.namelist():
            return {}
        raiz = ET.fromstring(archivo.read("docProps/core.xml"))
        return {campo: (raiz.find(f"dcterms:{campo}", DC).text if raiz.find(f"dcterms:{campo}", DC) is not None
                        else None) for campo in ("created", "modified")}


def filas(ruta, hoja):
    with zipfile.ZipFile(ruta) as archivo:
        compartidas = _compartidas(archivo)
        cabecera = None
        with archivo.open(_ruta_hoja(archivo, hoja)) as entrada:
            for _, elemento in ET.iterparse(entrada):
                if elemento.tag != f"{{{NS['m']}}}row":
                    continue
                valores = {}
                for celda in elemento.findall("m:c", NS):
                    tipo = celda.get("t")
                    if tipo == "inlineStr":
                        texto = "".join(t.text or "" for t in celda.iter(f"{{{NS['m']}}}t"))
                    else:
                        v = celda.find("m:v", NS)
                        texto = None if v is None else v.text
                        if texto is not None and tipo == "s":
                            texto = compartidas[int(texto)]
                    valores[_columna(celda.get("r"))] = texto
                elemento.clear()
                if cabecera is None:
                    cabecera = {i: (n or "").strip() for i, n in valores.items()}
                    continue
                yield {cabecera[i]: valores.get(i) for i in cabecera}
