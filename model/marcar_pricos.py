#!/usr/bin/env python3
"""Marks the PRICOs (principales contribuyentes) of a padrón in a wasichai Core running srtm's model (#273).

The padrón vinculado of the SAT (Ica) says which contribuyentes are PRICOs in two columns of a sheet that also has
`cod_predio` and `cod_contrib`: `pricos` (1, or empty) and `pricos_codigo` (the contribuyente's código). A contribuyente
whose `codigo` is the `pricos_codigo` of a row with `pricos` = 1 gets `prico = true`; one marked before that no row
names any more gets `prico = false`, unless --sin-desmarcar. A row of another year is left out when the sheet has an
`anio` column. The sheet is the first one with both columns, or --hoja; the columns are found by their names
(--col-prico, --col-codigo), and a missing one stops the run before anything is sent (exit 2).

Each change is a PATCH of `prico` alone (Core merges it, ADR-051) with `X-Change-Reason: pricos:<archivo>`, so the
audit says where it came from. Idempotent: a second run changes nothing. Prints counts only, never a name or a code.

Stdlib only. Run: python3 -I marcar_pricos.py --excel <padrón.xlsx> --anio 2026 [--dry-run] [--sin-desmarcar]
Exit: 0 ok, 1 Core refused something, 2 the workbook or the model lacks what the script needs (nothing is sent).
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
# under python3 -I (the xlsx is untrusted) neither the cwd nor the script's directory is on sys.path: put back only the
# latter, for core_client and import_predios
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from core_client import Client, CoreError  # noqa: E402
from import_padron_ica import codigo  # noqa: E402
from import_predios import XLSX_MAIN, read_xlsx  # noqa: E402

CONTRIBUYENTE = "contribuyente"
CAMPO = "prico"
COL_PRICO = "pricos"
COL_CODIGO = "pricos_codigo"
COL_ANIO = ("anio", "año")
MARCADO = {"1", "1.0", "si", "sí", "s", "x", "true"}


class Falta(Exception):
    """What the workbook or the model lacks: the run stops before anything is sent."""


def hojas(path):
    """The workbook's sheet names, in its order."""
    with zipfile.ZipFile(path) as z:
        workbook = ET.fromstring(z.read("xl/workbook.xml"))
    return [s.get("name") for s in workbook.find(f"{XLSX_MAIN}sheets")]


def _encabezado(rows):
    return set().union(*(r.keys() for r in rows)) - {"_fila"} if rows else set()


def leer_filas(path, hoja=None, col_prico=COL_PRICO, col_codigo=COL_CODIGO):
    """(sheet name, its rows) of the sheet with both columns: `hoja`, or the first one that has them. Falta when none."""
    nombres = [hoja] if hoja else hojas(path)
    if hoja and hoja not in hojas(path):
        raise Falta(f"la hoja '{hoja}' no está en {os.path.basename(path)}: hay {', '.join(hojas(path))}")
    vistas = []
    for nombre in nombres:
        rows = read_xlsx(path, nombre)
        columnas = _encabezado(rows)
        if col_prico in columnas and col_codigo in columnas:
            return nombre, rows
        vistas.append(f"{nombre}: {', '.join(sorted(columnas)) or 'vacía'}")
    raise Falta(f"ninguna hoja tiene las columnas '{col_prico}' y '{col_codigo}' (use --col-prico y --col-codigo); " + "; ".join(vistas))


def marcados(rows, anio, col_prico=COL_PRICO, col_codigo=COL_CODIGO):
    """The códigos of the rows marked as PRICO (of `anio` when the sheet says the year)."""
    codigos = set()
    for row in rows:
        anio_fila = next((row.get(c) for c in COL_ANIO if row.get(c) not in (None, "")), None)
        if anio_fila is not None and codigo(anio_fila) != str(anio):
            continue
        if (codigo(row.get(col_prico)) or "").lower() not in MARCADO:
            continue
        code = codigo(row.get(col_codigo))
        if code is not None:
            codigos.add(code)
    return codigos


def plan(contribuyentes, codigos, desmarcar=True):
    """Core's contribuyentes and the códigos to mark -> ({id: prico} to write, counts)."""
    cambios = {}
    por_codigo = {}
    for record in contribuyentes:
        code = codigo(record["attributes"].get("codigo"))
        if code is not None:
            por_codigo[code] = record
    sin_cambios = 0
    for code, record in por_codigo.items():
        tiene = record["attributes"].get(CAMPO) is True
        quiere = code in codigos if desmarcar else (tiene or code in codigos)
        if tiene == quiere:
            sin_cambios += 1
        else:
            cambios[record["id"]] = quiere
    conteos = {
        "marcados": sum(1 for v in cambios.values() if v),
        "desmarcados": sum(1 for v in cambios.values() if not v),
        "sin_cambios": sin_cambios,
        "sin_contribuyente": len(codigos - por_codigo.keys()),
    }
    return cambios, conteos


def campo_en_el_modelo(client):
    """Falta when Core's contribuyente has no `prico` field (apply.py adds it from model.json)."""
    campos = client.get(f"/api/metadata/objects/{CONTRIBUYENTE}/fields") or []
    if not any(f.get("name") == CAMPO for f in campos):
        raise Falta(f"{CONTRIBUYENTE} no tiene el campo '{CAMPO}': aplique model.json (apply.py) antes")


def escribir(client, cambios, motivo):
    for record_id, valor in cambios.items():
        client.patch(f"/api/objects/{CONTRIBUYENTE}/records/{record_id}", {"attributes": {CAMPO: valor}}, headers={"X-Change-Reason": motivo})


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Mark the padrón's PRICOs (contribuyente.prico) in srtm's wasichai Core.")
    p.add_argument("--excel", required=True, help="the padrón vinculado (xlsx)")
    p.add_argument("--anio", type=int, required=True, help="the padrón's year: rows of another year are left out")
    p.add_argument("--hoja", help="the sheet; by default the first one with both columns")
    p.add_argument("--col-prico", default=COL_PRICO, help=f"the column that marks a PRICO (default {COL_PRICO})")
    p.add_argument("--col-codigo", default=COL_CODIGO, help=f"the column of the contribuyente's código (default {COL_CODIGO})")
    p.add_argument("--sin-desmarcar", action="store_true", help="do not unmark who the padrón no longer marks")
    p.add_argument("--dry-run", action="store_true", help="read the workbook and Core and print the counts; change nothing")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)
    try:
        hoja, rows = leer_filas(args.excel, args.hoja, args.col_prico, args.col_codigo)
        codigos = marcados(rows, args.anio, args.col_prico, args.col_codigo)
        client = Client(args.core)
        client.login(args.email, args.password)
        campo_en_el_modelo(client)
        cambios, conteos = plan(client.list_all(CONTRIBUYENTE), codigos, desmarcar=not args.sin_desmarcar)
    except Falta as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    print(f"hoja {hoja}: {len(rows)} filas, {len(codigos)} códigos PRICO de {args.anio}")
    print(f"marcados: {conteos['marcados']}")
    print(f"desmarcados: {conteos['desmarcados']}{' (--sin-desmarcar)' if args.sin_desmarcar else ''}")
    print(f"sin cambios: {conteos['sin_cambios']}")
    print(f"códigos sin contribuyente: {conteos['sin_contribuyente']}")
    if args.dry_run:
        print("dry-run: no se escribió nada")
        return 0
    try:
        escribir(client, cambios, f"pricos:{os.path.basename(args.excel)}")
    except CoreError as e:
        print(f"error PATCH -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    print(f"done: {len(cambios)} actualizados")
    return 0


if __name__ == "__main__":
    sys.exit(main())
