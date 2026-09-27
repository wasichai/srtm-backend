#!/usr/bin/env python3
"""Loads the verified tax parameters of the impuesto predial into parametro_tributario: the UIT of each year, the
tramos and their límites, the mínimo and the deducciones.

They come from data/parametros-predial.csv, a verbatim copy of the predial rows of normativa's
docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv (double-signed: transcribio, verifico). No figure
is typed here.

The natural key is (tipo, clave, vigencia_desde). A row Core lacks is created and one whose values changed is
updated in place; a row the CSV does not have is left alone (a parameter of another year or source is not this
script's to delete). Idempotent: a second run writes nothing.

Run: python3 import_parametros.py [--dry-run]
Exit: 0 ok, 1 Core refused something.
"""
import argparse
import csv
import os
import sys
from decimal import Decimal, InvalidOperation

from core_client import Client, CoreError

HERE = os.path.dirname(os.path.abspath(__file__))
OBJECT = "parametro_tributario"
FIELDS = ("tipo", "clave", "vigencia_desde", "vigencia_hasta", "valor_numerico", "texto", "norma", "fuente", "transcribio", "verifico")


def read_parametros(path):
    """The CSV's rows as attributes; the '#' lines are its header comment and an empty cell is left out."""
    with open(path, encoding="utf-8") as f:
        rows = csv.DictReader(line for line in f if not line.startswith("#"))
        return [{k: r[k].strip() for k in FIELDS if r.get(k) and r[k].strip()} for r in rows]


def key(attributes):
    return attributes["tipo"], attributes.get("clave") or "", attributes["vigencia_desde"]


def _same(field, stored, wanted):
    """Core reads a DECIMAL back as a number (5500.0 for 5500.00): the same value is no change."""
    if stored in (None, "") and wanted in (None, ""):
        return True
    if field == "valor_numerico" and stored is not None and wanted is not None:
        try:
            return Decimal(str(stored)) == Decimal(str(wanted))
        except InvalidOperation:
            return False
    return stored == wanted


def changes(stored, wanted):
    return [f for f in FIELDS if not _same(f, stored.get(f), wanted.get(f))]


def plan(records, parametros):
    """(create, update, skipped): the rows Core lacks, (record, row) of the ones that changed, and how many are as
    the CSV says."""
    by_key = {key(r["attributes"]): r for r in records}
    create = [p for p in parametros if key(p) not in by_key]
    update = [(by_key[key(p)], p) for p in parametros if key(p) in by_key and changes(by_key[key(p)]["attributes"], p)]
    return create, update, len(parametros) - len(create) - len(update)


def _label(attributes):
    return " ".join(x for x in key(attributes) if x)


def load(client, parametros, dry_run=False):
    """Creates and updates what differs, printing a line per row it writes. Returns (created, updated, skipped)."""
    create, update, skipped = plan(client.list_all(OBJECT), parametros)
    for p in create:
        print(f"  create {_label(p)}: {p.get('valor_numerico', '')}")
    for record, p in update:
        stored = record["attributes"]
        for field in changes(stored, p):
            print(f"  update {_label(p)}: {field} {stored.get(field)} -> {p.get(field)}")
    if dry_run:
        print(f"{OBJECT}: {len(create)} to create, {len(update)} to update, {skipped} skipped")
        return len(create), len(update), skipped
    for p in create:
        client.post(f"/api/objects/{OBJECT}/records", {"attributes": p})
    for record, p in update:
        client.put(f"/api/objects/{OBJECT}/records/{record['id']}", {"attributes": p})
    print(f"{OBJECT}: {len(create)} created, {len(update)} updated, {skipped} skipped", flush=True)
    return len(create), len(update), skipped


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Load the verified parameters of the impuesto predial into wasichai Core.")
    p.add_argument("--csv", default=os.path.join(HERE, "data", "parametros-predial.csv"))
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and say what it would do; write nothing")
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    parametros = read_parametros(args.csv)
    print(f"parámetros: {len(parametros)}")
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        load(client, parametros, args.dry_run)
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    if args.dry_run:
        print("dry run: nothing written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
