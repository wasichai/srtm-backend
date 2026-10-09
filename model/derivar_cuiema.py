#!/usr/bin/env python3
"""Derives the CSV that import_cuis.py loads from the literal transcription of a CUIEMA (the cuadro único de
infracciones y escala de multas administrativas of an ordinance): one row of the norm per row, with the columns
pagina, materia, codigo, infraccion, calificacion, medida_cautelar, pecuniaria, no_pecuniaria, base_legal and nota,
and a '#' header that cites the norm. The transcription is the norm as printed; this script is every decision taken
to load it, so none is taken by hand in the loaded file.

What a row becomes:
- codigo: as printed, except a first group of one digit, which gets its zero (5.02.114 -> 05.02.114, as 05.02.049
  next to it).
- porcentaje_uit: the % of the column pecuniaria. Its text after the % is a condition of the norm ("100% si es
  modalidad A") and goes, the whole cell, in parentheses after the infracción in descripcion.
- medida_complementaria: the column no pecuniaria; base_legal: the column, or --base-legal when the table has none.
- materia: the section the row is under.
The calificación and the medida cautelar have no field in codigo_infraccion: they stay in the transcription.

A row that cannot be a version of the CUIS is left out and listed with its reason: one without a pecuniary multa
(only a no pecuniaria), one whose multa is not a % of the UIT (1% of the value of the obra), one per unit (per tree,
per m3: Multas.calcular computes one multa per acta), one with a figure read as [ilegible], a 0 %, and a code printed
twice with different figures. Exit 2 when a row does not fit import_cuis.py's checks.

Run: python3 derivar_cuiema.py --transcripcion t.csv --vigencia-desde AAAA-MM-DD --observacion "..." --salida cuis.csv
     [--base-legal "..."] [--excluidas excluidas.csv]
"""
import argparse
import csv
import os
import re
import sys
from decimal import Decimal

import import_cuis

COLUMNAS = ("pagina", "materia", "codigo", "infraccion", "calificacion", "medida_cautelar", "pecuniaria", "no_pecuniaria",
            "base_legal", "nota")
PORCENTAJE = re.compile(r"^\s*(\d+(?:[.,]\d+)?)\s*%\s*(.*)$")
POR_UNIDAD = re.compile(r"(?i)\bpor cada\b|\bpor metro|\bmetro3\b|\bm3\b|\bpor m2\b")
UN_DIGITO = re.compile(r"^(\d)(\.\d{2}\.\d{3})$")


def leer(path):
    """(header lines, rows) of a transcription: the '#' lines are its header, kept to cite the norm."""
    with open(path, encoding="utf-8") as f:
        lineas = f.readlines()
    cabecera = [l.rstrip("\n") for l in lineas if l.startswith("#")]
    filas = list(csv.DictReader(l for l in lineas if not l.startswith("#")))
    return cabecera, [{c: (f.get(c) or "").strip() for c in COLUMNAS} for f in filas]


def codigo(impreso):
    return UN_DIGITO.sub(r"0\1\2", impreso.strip())


def porcentaje(pecuniaria):
    """(% as text, condition) or (None, reason it is not loaded)."""
    if not pecuniaria:
        return None, "sin multa pecuniaria"
    if "[ilegible]" in pecuniaria:
        return None, f"cifra ilegible: «{pecuniaria}»"
    m = PORCENTAJE.match(pecuniaria)
    if m is None:
        return None, f"la multa no es un % de la UIT: «{pecuniaria}»"
    valor, condicion = m.group(1).replace(",", "."), m.group(2).strip()
    if POR_UNIDAD.search(condicion):
        return None, f"multa por unidad, el acta calcula una sola: «{pecuniaria}»"
    if Decimal(valor) == 0:
        return None, f"multa de 0 %: «{pecuniaria}»"
    return valor, condicion


def derivar(filas, vigencia_desde, observacion, base_legal=""):
    """(rows for import_cuis.py, left out as (row, reason)), in the order of the norm."""
    por_codigo = {}
    for f in filas:
        por_codigo.setdefault(codigo(f["codigo"]), []).append(f)
    cuis, excluidas, vistos = [], [], set()
    for f in filas:
        cod = codigo(f["codigo"])
        copias = por_codigo[cod]
        distintas = {tuple(c[k] for k in COLUMNAS if k not in ("pagina", "nota")) for c in copias}
        if len(distintas) > 1:
            excluidas.append((f, f"el código {cod} está {len(copias)} veces, con otras cifras o textos"))
            continue
        if cod in vistos:
            continue  # the same row printed twice
        vistos.add(cod)
        valor, condicion = porcentaje(f["pecuniaria"])
        if valor is None:
            excluidas.append((f, condicion))
            continue
        fila = {
            "familia": import_cuis.FAMILIA,
            "codigo": cod,
            "descripcion": f"{f['infraccion']} ({f['pecuniaria']})" if condicion else f["infraccion"],
            "materia": f["materia"],
            "porcentaje_uit": valor,
            "medida_complementaria": f["no_pecuniaria"],
            "base_legal": f["base_legal"] or base_legal,
            "vigencia_desde": vigencia_desde,
            "observacion": observacion,
        }
        cuis.append({k: v for k, v in fila.items() if v})
    return cuis, excluidas


def escribir(path, cabecera, filas):
    with open(path, "w", encoding="utf-8", newline="") as f:
        for linea in cabecera:
            f.write(linea + "\n")
        w = csv.DictWriter(f, fieldnames=import_cuis.COLUMNS, lineterminator="\n")
        w.writeheader()
        for fila in filas:
            w.writerow(fila)


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Derive import_cuis.py's CSV from the literal transcription of a CUIEMA.")
    p.add_argument("--transcripcion", required=True)
    p.add_argument("--vigencia-desde", required=True, help="AAAA-MM-DD: the day the norm rules from")
    p.add_argument("--observacion", required=True, help="why these versions are loaded (5 to 500 characters)")
    p.add_argument("--base-legal", default="", help="for a table without a column of base legal")
    p.add_argument("--salida", required=True)
    p.add_argument("--excluidas", help="also write the rows left out, with their reason")
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    cabecera, filas = leer(args.transcripcion)
    cuis, excluidas = derivar(filas, args.vigencia_desde, args.observacion, args.base_legal)
    malas = import_cuis.errores(cuis)
    if malas:
        for e in malas:
            print(f"error: {e}", file=sys.stderr)
        return 2
    escribir(args.salida, cabecera + [f"# Derivado de {os.path.basename(args.transcripcion)} con derivar_cuiema.py"], cuis)
    if args.excluidas:
        with open(args.excluidas, "w", encoding="utf-8", newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(("pagina", "codigo", "pecuniaria", "motivo"))
            for fila, motivo in excluidas:
                w.writerow((fila["pagina"], fila["codigo"], fila["pecuniaria"], motivo))
    print(f"filas: {len(filas)}; versiones: {len(cuis)}; excluidas: {len(excluidas)}")
    for fila, motivo in excluidas:
        print(f"  p{fila['pagina']} {fila['codigo']}: {motivo}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
