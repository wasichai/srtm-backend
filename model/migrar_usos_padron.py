#!/usr/bin/env python3
"""Moves the declaraciones that still hold a grupo de uso of the padrón in `uso` ("RESIDENCIAL - CASA HABITACION",
"TERRENO"...) to the srtm's clase, sub clase and uso, as import_predios.py now imports them (USOS_DEL_PADRON):
RESIDENCIAL - CASA HABITACION is RESIDENCIAL / UNIFAMILIAR / CASA HABITACIÓN, any other grupo the clase of its name
with no sub clase nor uso (the padrón says no more: the portal asks for them when the declaración is edited).

One that already has a clase or a sub clase is not overwritten: it goes to the report, unless its uso is the catalog's
under them (COMERCIAL and INDUSTRIA are also usos of the catalog). Once no declaración holds a grupo, apply.py drops
them from the `uso` enum.

Reads everything first, writes a report (one row per declaración with a grupo), then updates each record with a PUT
of all its fields (Core's update replaces them all). Idempotent: a second run changes nothing.

Run: python3 migrar_usos_padron.py [--dry-run] [--report reports/migrar_usos_padron.csv]
Exit: 0 ok, 1 Core refused something.
"""
import argparse
import csv
import os
import sys
from collections import Counter

from core_client import Client, CoreError
from import_predios import USO_FIELDS, LoadError, clean_text, uso_del_padron
from normalizar_padron import Update, put_all

HERE = os.path.dirname(os.path.abspath(__file__))
REPORT_COLUMNS = ["id", "numero_declaracion", "anio", "secuencia_uso", "grupo", *USO_FIELDS, "nota"]
NOTA_CON_CLASE = "ya tiene clase o sub clase de uso: no se pisa; su uso del padrón queda y apply.py no lo quita mientras lo use"


def _blank(value):
    return "" if value is None else value


def _row(record, grupo, usos, nota=""):
    attributes = record["attributes"]
    return {
        "id": record["id"],
        **{k: _blank(attributes.get(k)) for k in ("numero_declaracion", "anio", "secuencia_uso")},
        "grupo": grupo,
        **{k: _blank(usos.get(k)) for k in USO_FIELDS},
        "nota": nota,
    }


def plan(declaraciones, catalogo):
    """Core's declaraciones and the catalog's (clase, sub clase, uso) -> the updates to make and the report's rows."""
    updates = []
    rows = []
    for record in declaraciones:
        stored = record["attributes"]
        grupo = stored.get("uso")
        usos = uso_del_padron(grupo)
        if usos is None:
            continue
        if clean_text(stored.get("clase_uso")) or clean_text(stored.get("sub_clase_uso")):
            if tuple(stored.get(k) for k in USO_FIELDS) not in catalogo:
                rows.append(_row(record, grupo, stored, NOTA_CON_CLASE))
            continue
        rows.append(_row(record, grupo, usos))
        clave = str(stored.get("numero_declaracion") or record["id"])
        updates.append(Update("declaracion_predial", record["id"], clave, {**stored, **usos}))
    return updates, rows


def write_report(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=REPORT_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Move the padrón's grupos de uso to the srtm's clase, sub clase and uso in srtm's wasichai Core.")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and write the report; change nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel PUTs (default 4)")
    p.add_argument("--report", default=os.path.join(HERE, "reports", "migrar_usos_padron.csv"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        catalogo = {(a.get("clase"), a.get("sub_clase"), a.get("uso")) for a in (r["attributes"] for r in client.list_all("uso_predio"))}
        declaraciones = client.list_all("declaracion_predial")
        updates, rows = plan(declaraciones, catalogo)
        write_report(args.report, rows)
        print(f"declaraciones: {len(declaraciones)} leídas, {len(updates)} por migrar")
        for grupo, count in Counter(r["grupo"] for r in rows if not r["nota"]).most_common():
            destino = " / ".join(v for v in uso_del_padron(grupo).values() if v)
            print(f"  {grupo}: {count} -> {destino}")
        print(f"notas: {sum(1 for r in rows if r['nota'])} -> {args.report}")
        if args.dry_run:
            return 0
        done = put_all(client, updates, args.workers)
    except LoadError as e:
        print(f"error PUT {e.object_name} {e.fila} -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    print(f"done: {done} updated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
