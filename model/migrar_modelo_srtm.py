#!/usr/bin/env python3
"""Moves the records to the model as the srtm's manuals have it (M01-1-012 contribuyente, M01-1-014 predial):

- tipo de predio: the predio's old condicion (URBANO / RUSTICO) goes to tipo_predio (PREDIO URBANO / PREDIO RUSTICO),
  the enum domicilio and catastro_fiscal already use. The srtm's condición del predio is another datum, the
  declaration's condicion_especial. With --borrar-condicion, once every predio that had a condicion has its tipo_predio,
  the old field is deleted (its column with it).
- sucesión: SUCESION is no document in the srtm. A sucesión indivisa is a tipo de contribuyente, known by its causante's
  document; the padrón's 08 numbers are its own codes, so the contribuyente goes to SIN DOCUMENTO (keeping the number:
  the key import_predios.py knows it by) and to tipo_contribuyente SUCESION INDIVISA. A relacionado or transferente with
  SUCESION goes to SIN DOCUMENTO.

Reads everything first and writes a report (one row per record it changes, or cannot and why). Then updates each record
with a PUT of all its fields (Core's update replaces them all). Idempotent: a second run changes nothing. Afterwards
apply.py drops the SUCESION option, once no record uses it.

Run: python3 migrar_modelo_srtm.py [--dry-run] [--borrar-condicion] [--report reports/migrar_modelo_srtm.csv]
Exit: 0 ok, 1 Core refused something, or --borrar-condicion with a predio that would lose its condicion.
"""
import argparse
import csv
import os
import sys

from core_client import Client, CoreError
from import_predios import LoadError, clean_text
from normalizar_padron import Update, put_all

HERE = os.path.dirname(os.path.abspath(__file__))

TIPO_PREDIO = {"URBANO": "PREDIO URBANO", "RUSTICO": "PREDIO RUSTICO"}
SIN_DOCUMENTO = "SIN DOCUMENTO"
PERSONAS = ["contribuyente", "relacionado", "transferente"]
OBJETOS = ["predio", *PERSONAS]
CAMPO_VIEJO = "/api/metadata/objects/predio/fields/condicion"


def _row(objeto, record_id, clave, campo="", antes=None, despues=None, nota=""):
    return {"objeto": objeto, "id": record_id, "clave": clave, "campo": campo, "antes": antes or "", "despues": despues or "", "nota": nota}


def _predio(record, updates, rows):
    a = record["attributes"]
    condicion, tipo = clean_text(a.get("condicion")), clean_text(a.get("tipo_predio"))
    if tipo:
        return
    clave = a.get("codigo") or record["id"]
    if condicion is None:
        rows.append(_row("predio", record["id"], clave, "tipo_predio", nota="sin condicion ni tipo_predio: no hay de dónde tomarlo"))
        return
    if condicion not in TIPO_PREDIO:
        rows.append(_row("predio", record["id"], clave, "tipo_predio", condicion, nota=f"condicion '{condicion}' no es URBANO ni RUSTICO"))
        return
    updates.append(Update("predio", record["id"], clave, {**a, "tipo_predio": TIPO_PREDIO[condicion]}))
    rows.append(_row("predio", record["id"], clave, "tipo_predio", condicion, TIPO_PREDIO[condicion]))


def _persona(objeto, record, updates, rows):
    a = record["attributes"]
    if a.get("tipo_documento") != "SUCESION":
        return
    clave = a.get("numero_documento") or record["id"]
    nuevo = {**a, "tipo_documento": SIN_DOCUMENTO}
    if objeto == "contribuyente" and not clean_text(a.get("tipo_contribuyente")):
        nuevo["tipo_contribuyente"] = "SUCESION INDIVISA"
    updates.append(Update(objeto, record["id"], clave, nuevo))
    rows.append(_row(objeto, record["id"], clave, "tipo_documento", "SUCESION", SIN_DOCUMENTO))


def plan(records):
    """(updates, report rows) for {object: its records}."""
    updates, rows = [], []
    for record in records.get("predio", []):
        _predio(record, updates, rows)
    for objeto in PERSONAS:
        for record in records.get(objeto, []):
            _persona(objeto, record, updates, rows)
    return updates, rows


def pierden_condicion(rows):
    """The predios that would lose their condicion with the field: one that has it and takes no tipo_predio."""
    return [r["clave"] for r in rows if r["objeto"] == "predio" and r["nota"] and r["antes"]]


def write_report(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["objeto", "id", "clave", "campo", "antes", "despues", "nota"])
        writer.writeheader()
        writer.writerows(rows)


def _print_plan(updates, rows):
    predios = sum(1 for u in updates if u.object_name == "predio")
    print(f"predio: {predios} tipo_predio")
    for objeto in PERSONAS:
        print(f"{objeto}: {sum(1 for u in updates if u.object_name == objeto)} sucesion -> {SIN_DOCUMENTO}")
    print(f"notas: {sum(1 for r in rows if r['nota'])}")


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Move srtm's wasichai Core to the model of the srtm's manuals.")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and write the report; change nothing")
    p.add_argument("--borrar-condicion", action="store_true", help="then delete predio.condicion (its data with it)")
    p.add_argument("--workers", type=int, default=4, help="parallel PUTs (default 4)")
    p.add_argument("--report", default=os.path.join(HERE, "reports", "migrar_modelo_srtm.csv"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        updates, rows = plan({objeto: client.list_all(objeto) for objeto in OBJETOS})
        write_report(args.report, rows)
        _print_plan(updates, rows)
        print(f"reporte: {args.report}")
        perdidos = pierden_condicion(rows)
        if args.borrar_condicion and perdidos:
            print(f"predio.condicion: no se borra, {len(perdidos)} predios lo perderían: {', '.join(perdidos[:10])}", file=sys.stderr)
            return 1
        if args.dry_run:
            if args.borrar_condicion:
                print("predio.condicion: se borraría")
            return 0
        done = put_all(client, updates, args.workers)
        if args.borrar_condicion:
            client.delete(CAMPO_VIEJO)
            print("predio.condicion: borrado")
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
