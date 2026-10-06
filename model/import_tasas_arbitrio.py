#!/usr/bin/env python3
"""Derives the CSV that import_parametros.py --csv reads from the long table of an ordinance's tasas de arbitrios:
one row per servicio, zona (optional), uso_codigo (optional), uso, ubicación (optional), nivel_afluencia (optional),
base and tasa_mensual, with a '#' header that cites the norm (the shape of data/arbitrios/*.csv and of the real
arbitrios-miraflores-2026.csv, #86).

What a row becomes, per servicio (srtm.arbitrios.Llaves names the same tipos; import_parametros.TIPOS_ARBITRIO
checks their shape):
- BASE_ARBITRIO: from the servicio's own base texto («S/ por ml…» -> FRONTIS_ML, «m2»/«m²» -> AREA_CONSTRUIDA_M2,
  «predio» -> PREDIO); every row of a servicio must agree.
- DIMENSIONES_ARBITRIO: ZONA when any row of the servicio has a zona, USO when any has a uso_codigo, INFLUENCIA when
  any has an ubicación, AFLUENCIA when any has a nivel_afluencia, in that order.
- TASA_ARBITRIO, one per row: --servicios' short código, plus one parte per dimension of that servicio, in order.
  zona and nivel_afluencia are normalized (sin acentos, mayúsculas, separadores por _: «Zona 1» -> ZONA_1, «Muy
  alta» -> MUY_ALTA); uso_codigo and ubicación go verbatim, the latter already fixed by --influencias' etiqueta.
- ARBITRIO_USO, straight from --usos (prefijo,uso); a tasa's uso_codigo must be one of its uso column, or the file
  does not fit (sin mapeo).
- ARBITRIO_INFLUENCIA, straight from --influencias (enum,etiqueta); a tasa's ubicación must be one of its etiqueta
  column, or the file does not fit (sin mapeo). ARBITRIO_AFLUENCIA (the mapping of a predio's propio uso a su nivel
  de afluencia) is not derived here: out of this task's scope (#86).
- HABITANTES_PROMEDIO/HABITANTES_VARIACION, from --habitantes SERVICIO=P:V, one pair per servicio:zona:uso of casa
  habitación present in --tasas for that servicio (its uso column contains «casa habitación», sin acentos).
- REDONDEO_ARBITRIO, one row (clave vacía) from --redondeo, when given.

--servicios maps the long table's own servicio column (a descriptive name) to the short código of
servicio_arbitrio (<=20 characters) that import_arbitrios.py loads separately and that TASA_ARBITRIO's clave
actually uses: NOMBRE=codigo, repeated with commas.

Nothing is sent anywhere: generar() always returns what it could build, even malformed; a problem (a servicio
outside --servicios, a base desconocida, a tasa repetida, a uso or una ubicación sin mapeo, or anything
import_parametros.errores rejects — called here, before writing) only stops main() from writing --salida.

Run: python3 import_tasas_arbitrio.py --tasas tasas.csv --servicios NOMBRE=codigo,... --usos usos.csv
     [--influencias influencias.csv] --vigencia-desde AAAA-MM-DD --norma "..." --fuente "..." --transcribio "X, fecha"
     --verifico "Y, fecha" [--redondeo 2:ABAJO] [--habitantes SERVICIO=P:V ...] --salida parametros.csv
Exit: 0 ok, 2 the file (or a CLI mapping) is malformed (nothing written).
"""
import argparse
import csv
import os
import re
import sys
import unicodedata
from decimal import Decimal, InvalidOperation

import import_parametros

COLUMNAS = ("servicio", "zona", "uso_codigo", "uso", "ubicacion", "nivel_afluencia", "base", "tasa_mensual")
# the Dimension names Servicios.dimensiones reads (same order as import_parametros.DIMENSIONES_VALIDAS), and the
# column of --tasas that each one is read from
DIMENSION_ORDEN = ("ZONA", "USO", "INFLUENCIA", "AFLUENCIA")
DIMENSION_COLUMNA = {"ZONA": "zona", "USO": "uso_codigo", "INFLUENCIA": "ubicacion", "AFLUENCIA": "nivel_afluencia"}


def leer_tasas(path):
    """The long table's rows, every column always present (empty string when the cell is blank): unlike
    import_parametros.read_parametros, an absent column still matters here (it says a dimension is not used)."""
    with open(path, encoding="utf-8") as f:
        filas = csv.DictReader(line for line in f if not line.startswith("#"))
        return [{c: (fila.get(c) or "").strip() for c in COLUMNAS} for fila in filas]


def leer_mapeo(path, columnas):
    """A small CSV of exactly `columnas`, '#' header lines aside (--usos, --influencias)."""
    with open(path, encoding="utf-8") as f:
        filas = csv.DictReader(line for line in f if not line.startswith("#"))
        leidas = [{c: (fila.get(c) or "").strip() for c in columnas} for fila in filas]
        return [fila for fila in leidas if any(fila.values())]


def _normalizar(texto):
    """«Zona 1» -> ZONA_1, «Área 0» -> AREA_0, «Muy alta» -> MUY_ALTA: sin acentos, en mayúsculas, lo que no es
    letra o número se vuelve _ (la Ruling de la tarea, para zona y nivel_afluencia)."""
    sin_acentos = "".join(c for c in unicodedata.normalize("NFKD", texto) if not unicodedata.combining(c))
    return re.sub(r"[^A-Za-z0-9]+", "_", sin_acentos.strip()).strip("_").upper()


def _base_de(texto):
    """«S/ por ml de frontis» -> FRONTIS_ML, «S/ por m2/m² de área construida» -> AREA_CONSTRUIDA_M2, «S/ por
    predio» -> PREDIO; None when the texto matches none of them (base desconocida)."""
    t = (texto or "").lower()
    if "ml" in t:
        return "FRONTIS_ML"
    if "m2" in t or "m²" in t:
        return "AREA_CONSTRUIDA_M2"
    if "predio" in t:
        return "PREDIO"
    return None


def _casa_habitacion(uso):
    return "CASA_HABITACION" in _normalizar(uso or "")


def _segmento(dimension, fila):
    """A row's value for one of its servicio's dimensiones: zona and afluencia are normalized, uso and ubicación go
    verbatim (ubicación is already fixed by --influencias' etiqueta; uso_codigo is already a short code)."""
    bruto = fila.get(DIMENSION_COLUMNA[dimension], "")
    if not bruto:
        return ""
    return _normalizar(bruto) if dimension in ("ZONA", "AFLUENCIA") else bruto


def _dimensiones_de_servicio(filas_servicio):
    """ZONA when any row has a zona, USO when any has a uso_codigo, INFLUENCIA/AFLUENCIA likewise, in that order."""
    return [d for d in DIMENSION_ORDEN if any(f.get(DIMENSION_COLUMNA[d]) for f in filas_servicio)]


def _fila(tipo, clave, valor_numerico=None, texto=None, **comunes):
    return {k: v for k, v in {"tipo": tipo, "clave": clave, "valor_numerico": valor_numerico, "texto": texto,
                               **comunes}.items() if v is not None}


def generar(filas, servicios, usos, influencias, habitantes, vigencia_desde, norma, fuente, transcribio, verifico,
            redondeo=None):
    """(parametros, errores): parametros is always the full attempt, even next to errores (the caller decides
    whether to write it; una TASA_ARBITRIO is always built per fila, whatever its clave turns out to be)."""
    comun = dict(vigencia_desde=vigencia_desde, norma=norma, fuente=fuente, transcribio=transcribio, verifico=verifico)
    usos_validos = {u["uso"] for u in usos}
    influencias_validas = {i["etiqueta"] for i in influencias}
    errores = []

    codigos_de_fila = []
    por_servicio = {}
    for i, f in enumerate(filas, start=1):
        nombre = f.get("servicio", "")
        codigo = servicios.get(nombre)
        if codigo is None:
            errores.append(f"fila {i}: el servicio «{nombre}» no está en --servicios")
        codigos_de_fila.append(codigo)
        if codigo is not None:
            por_servicio.setdefault(codigo, []).append(f)
        if _base_de(f.get("base")) is None:
            errores.append(f"fila {i}: base desconocida: «{f.get('base')}»")
        if f.get("uso_codigo") and f["uso_codigo"] not in usos_validos:
            errores.append(f"fila {i}: el uso «{f['uso_codigo']}» de la tasa no tiene mapeo en --usos")
        if f.get("ubicacion") and f["ubicacion"] not in influencias_validas:
            errores.append(f"fila {i}: la ubicación «{f['ubicacion']}» no tiene mapeo en --influencias")

    parametros = []
    dimensiones_por_codigo = {}
    for codigo, filas_servicio in por_servicio.items():
        bases = {_base_de(f.get("base")) for f in filas_servicio} - {None}
        if len(bases) > 1:
            errores.append(f"el servicio {codigo} tiene bases distintas: {', '.join(sorted(bases))}")
        if bases:
            parametros.append(_fila("BASE_ARBITRIO", codigo, texto=sorted(bases)[0], **comun))
        dims = _dimensiones_de_servicio(filas_servicio)
        dimensiones_por_codigo[codigo] = dims
        if dims:
            parametros.append(_fila("DIMENSIONES_ARBITRIO", codigo, texto=",".join(dims), **comun))

    claves_vistas = {}
    for i, f in enumerate(filas, start=1):
        codigo = codigos_de_fila[i - 1]
        if codigo is None:
            continue  # ya se reportó arriba: sin servicio no hay clave que construir
        dims = dimensiones_por_codigo.get(codigo, [])
        segmentos = [s for s in (_segmento(d, f) for d in dims) if s]
        clave = ":".join([codigo] + segmentos)
        if clave in claves_vistas:
            errores.append(f"fila {i} y fila {claves_vistas[clave]}: tasa repetida {clave}")
        else:
            claves_vistas[clave] = i
        parametros.append(_fila("TASA_ARBITRIO", clave, valor_numerico=f.get("tasa_mensual"), **comun))

    for u in usos:
        parametros.append(_fila("ARBITRIO_USO", u["prefijo"], texto=u["uso"], **comun))
    for inf in influencias:
        parametros.append(_fila("ARBITRIO_INFLUENCIA", inf["enum"], texto=inf["etiqueta"], **comun))

    vistos = set()
    for nombre, (promedio, variacion) in habitantes.items():
        codigo = servicios.get(nombre)
        if codigo is None:
            errores.append(f"--habitantes: el servicio «{nombre}» no está en --servicios")
            continue
        for f in por_servicio.get(codigo, []):
            if not _casa_habitacion(f.get("uso")):
                continue
            zona, uso = _segmento("ZONA", f), _segmento("USO", f)
            if not zona or not uso:
                continue
            clave = f"{codigo}:{zona}:{uso}"
            if clave in vistos:
                continue
            vistos.add(clave)
            parametros.append(_fila("HABITANTES_PROMEDIO", clave, valor_numerico=promedio, **comun))
            parametros.append(_fila("HABITANTES_VARIACION", clave, valor_numerico=variacion, **comun))

    if redondeo:
        parametros.append(_fila("REDONDEO_ARBITRIO", "", texto=redondeo, **comun))

    return parametros, errores


def escribir(path, parametros, cabecera):
    with open(path, "w", encoding="utf-8", newline="") as f:
        for linea in cabecera:
            f.write(linea + "\n")
        w = csv.DictWriter(f, fieldnames=import_parametros.FIELDS, lineterminator="\n")
        w.writeheader()
        for p in parametros:
            w.writerow({k: p.get(k, "") for k in import_parametros.FIELDS})


def _parse_pares(valor):
    """«A=B,C=D» -> {"A": "B", "C": "D"}: used by --servicios (NOMBRE=código)."""
    pares = {}
    for item in valor.split(","):
        item = item.strip()
        if not item:
            continue
        if item.count("=") != 1 or not all(p.strip() for p in item.split("=")):
            raise ValueError(f"«{item}» no es NOMBRE=CODIGO")
        nombre, codigo = (p.strip() for p in item.split("="))
        pares[nombre] = codigo
    return pares


def _parse_habitantes(lista):
    """«SERVICIO=P:V» -> {"SERVICIO": ("P", "V")}, repetible (--habitantes)."""
    resultado = {}
    for item in lista:
        if item.count("=") != 1:
            raise ValueError(f"«{item}» no es SERVICIO=P:V")
        nombre, resto = (p.strip() for p in item.split("="))
        partes = resto.split(":")
        if len(partes) != 2:
            raise ValueError(f"«{item}» no es SERVICIO=P:V")
        try:
            promedio, variacion = (str(Decimal(p.strip())) for p in partes)
        except InvalidOperation:
            raise ValueError(f"«{item}» no es SERVICIO=P:V") from None
        resultado[nombre] = (promedio, variacion)
    return resultado


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Deriva el CSV que import_parametros.py --csv lee, de la tabla de tasas "
                                             "de arbitrios de una ordenanza.")
    p.add_argument("--tasas", required=True, help="servicio,zona,uso_codigo,uso,ubicacion,nivel_afluencia,base,"
                                                    "tasa_mensual")
    p.add_argument("--servicios", required=True, help="NOMBRE_EN_TASAS=codigo,... (el código corto de "
                                                        "servicio_arbitrio que usa la clave de la tasa)")
    p.add_argument("--usos", required=True, help="csv prefijo,uso")
    p.add_argument("--influencias", help="csv enum,etiqueta; necesario si --tasas trae ubicación")
    p.add_argument("--vigencia-desde", required=True)
    p.add_argument("--norma", required=True)
    p.add_argument("--fuente", required=True)
    p.add_argument("--transcribio", required=True)
    p.add_argument("--verifico", required=True)
    p.add_argument("--redondeo", help="decimales:modo, p. ej. 2:ABAJO")
    p.add_argument("--habitantes", action="append", default=[], help="SERVICIO=P:V, repetible")
    p.add_argument("--salida", required=True)
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    try:
        servicios = _parse_pares(args.servicios)
        habitantes = _parse_habitantes(args.habitantes)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    filas = leer_tasas(args.tasas)
    usos = leer_mapeo(args.usos, ("prefijo", "uso"))
    influencias = leer_mapeo(args.influencias, ("enum", "etiqueta")) if args.influencias else []
    parametros, malas = generar(filas, servicios, usos, influencias, habitantes, args.vigencia_desde, args.norma,
                                 args.fuente, args.transcribio, args.verifico, args.redondeo)
    if not malas:
        malas = import_parametros.errores(parametros)
    if malas:
        for e in malas:
            print(f"error: {e}", file=sys.stderr)
        return 2
    escribir(args.salida, parametros, [f"# Derivado de {os.path.basename(args.tasas)} con import_tasas_arbitrio.py"])
    print(f"parámetros: {len(parametros)} (tasas: {len(filas)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
