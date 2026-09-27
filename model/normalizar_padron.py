#!/usr/bin/env python3
"""Normalizes the padrón already in Core the way import_predios.py now imports it.

- predio: the type of its vía and of its habilitación urbana split off the name ('JIRON LIMA' is tipo_via JIRON,
  via LIMA; 'CERCADO II MESETA' is tipo_zona CERCADO, habilitacion_urbana II MESETA) and the kilómetro of its
  direccion ('Km.: 23.5'). Only a predio as the padrón left it: one with a tipo_via is the portal's (its form asks
  for it) and is left alone. direccion keeps the padrón's text: the portal rewrites it when the ubicación is saved.
- declaracion_predial: secuencia_uso with the padrón's three digits ('1' is '001').

Reads everything first, writes a report (every field it changes, and what needs a look by hand), then updates each
record with a PUT of all its fields (Core's update replaces them all). Idempotent: a second run changes nothing.

Run: python3 normalizar_padron.py [--dry-run] [--report reports/normalizar_padron.csv]
Exit: 0 ok, 1 Core refused something.
"""
import argparse
import csv
import os
import re
import sys
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass

from core_client import Client, CoreError
from import_predios import (
    PROGRESS_EVERY, TIPOS_UNIDAD_URBANA, TIPOS_VIA, LoadError, clean_text, parse_address, secuencia_uso, split_tipo,
    split_ubicacion,
)

HERE = os.path.dirname(os.path.abspath(__file__))
REPORT_COLUMNS = ["objeto", "id", "clave", "campo", "antes", "despues", "nota"]
# a predio's parts that carry a type: (field, its type's field, the types, the name the report gives it)
PARTES = [
    ("via", "tipo_via", TIPOS_VIA, "vía"),
    ("habilitacion_urbana", "tipo_zona", TIPOS_UNIDAD_URBANA, "zona"),
]


@dataclass
class Update:
    object_name: str
    record_id: str
    clave: str  # what a person finds the record by: the predio's codigo, the declaration's number
    attributes: dict  # every field, the changed ones included


def normalizar_predio(attributes):
    """The fields a predio of the padrón changes; empty for one already normalized or the portal's."""
    if clean_text(attributes.get("tipo_via")):
        return {}
    wanted = {}
    for field, tipo_field, _, _ in PARTES:
        text = clean_text(attributes.get(field))
        if text and not clean_text(attributes.get(tipo_field)):
            split = split_ubicacion({field: text})
            wanted.update({tipo_field: split[tipo_field], field: split[field]})
    if not clean_text(attributes.get("kilometro")):
        kilometro = parse_address(attributes.get("direccion"))["kilometro"]
        if kilometro:
            wanted["kilometro"] = kilometro
    return {k: v for k, v in wanted.items() if v != attributes.get(k)}


def notas_predio(attributes):
    """What a person should look at. In a padrón predio: a type not recognized, the lot's leftovers dropped. In one
    saved through the portal before the abbreviations: a direccion that repeats a type ("JIRON JR. LIMA, ..."); its
    vía is not changed here, since a street named CALLE 11 reads the same."""
    portal = bool(clean_text(attributes.get("tipo_via")))
    direccion = clean_text(attributes.get("direccion")) or ""
    notas = []
    for field, tipo_field, tipos, nombre in PARTES:
        text = clean_text(attributes.get(field))
        tipo_actual = clean_text(attributes.get(tipo_field))
        if not text:
            continue
        if portal:
            repetido = f"{tipo_actual} {text}"
            if split_tipo(text, tipos)[0] == tipo_actual and re.search(rf"(^|, ){re.escape(repetido)}(,|$)", direccion):
                notas.append(f"la {nombre} repite su tipo ({repetido}): corregir la {nombre} en el portal; al guardar se rearma la dirección")
        elif not tipo_actual:
            # only a zona's type may come after other words (split_ubicacion)
            anywhere = field == "habilitacion_urbana"
            tipo, name = split_tipo(text, tipos, anywhere=anywhere)
            restos = _restos(text, tipo, name, tipos) if anywhere else None
            if tipo == "OTROS":
                notas.append(f"{nombre} sin tipo reconocido: queda OTROS")
            elif restos:
                notas.append(f"se descartan restos de lote de la {nombre}: {restos}")
    return notas


def _restos(text, tipo, name, tipos):
    """What split_tipo dropped before the type: the lot's leftovers ('03-B' of '03-B CERCADO III MESETA')."""
    before = text[:text.rfind(name)].rstrip(" -")
    for prefix, t in tipos:
        if t == tipo and before.endswith(prefix):
            return before[:-len(prefix)].strip(" -") or None
    return None


def normalizar_declaracion(attributes):
    wanted = secuencia_uso(attributes.get("secuencia_uso"))
    return {} if wanted == attributes.get("secuencia_uso") else {"secuencia_uso": wanted}


def _row(objeto, record_id, clave, campo="", antes=None, despues=None, nota=""):
    return {
        "objeto": objeto, "id": record_id, "clave": clave, "campo": campo,
        "antes": "" if antes is None else antes, "despues": "" if despues is None else despues, "nota": nota,
    }


def plan(predios, declaraciones):
    """Core's records -> the updates to make and the report's rows."""
    updates = []
    rows = []

    def add(object_name, record, clave, changes, notas):
        stored = record["attributes"]
        for campo, despues in changes.items():
            rows.append(_row(object_name, record["id"], clave, campo, stored.get(campo), despues))
        for nota in notas:
            rows.append(_row(object_name, record["id"], clave, nota=nota))
        if changes:
            updates.append(Update(object_name, record["id"], clave, {**stored, **changes}))

    for record in predios:
        attributes = record["attributes"]
        add("predio", record, str(attributes.get("codigo")), normalizar_predio(attributes), notas_predio(attributes))

    def key(attributes, secuencia):
        return attributes.get("predio"), attributes.get("contribuyente"), attributes.get("anio"), secuencia

    changes_of = {r["id"]: normalizar_declaracion(r["attributes"]) for r in declaraciones}
    after = Counter(key(r["attributes"], changes_of[r["id"]].get("secuencia_uso", r["attributes"].get("secuencia_uso"))) for r in declaraciones)
    for record in declaraciones:
        attributes = record["attributes"]
        changes = changes_of[record["id"]]
        notas = []
        if changes and after[key(attributes, changes["secuencia_uso"])] > 1:
            notas.append(f"otra declaración del mismo predio, contribuyente y año ya tiene la secuencia {changes['secuencia_uso']}")
        add("declaracion_predial", record, str(attributes.get("numero_declaracion")), changes, notas)
    return updates, rows


def write_report(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=REPORT_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


def put_all(client, updates, workers):
    """PUTs every update; the first refusal stops it all. Returns how many were updated."""
    if not updates:
        return 0

    def one(update):
        payload = {"attributes": {k: v for k, v in update.attributes.items() if v is not None}}
        try:
            client.put(f"/api/objects/{update.object_name}/records/{update.record_id}", payload)
        except CoreError as e:
            raise LoadError(update.object_name, update.clave, e)

    done = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, u) for u in updates]
        try:
            for future in as_completed(futures):
                future.result()
                done += 1
                if done % PROGRESS_EVERY == 0:
                    print(f"  {done}/{len(updates)}", flush=True)
        except LoadError:
            for future in futures:
                future.cancel()
            raise
    return done


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Normalize the padrón already imported into srtm's wasichai Core.")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and write the report; change nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel PUTs (default 4)")
    p.add_argument("--report", default=os.path.join(HERE, "reports", "normalizar_padron.csv"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        predios = client.list_all("predio")
        declaraciones = client.list_all("declaracion_predial")
        updates, rows = plan(predios, declaraciones)
        write_report(args.report, rows)
        por_objeto = Counter(u.object_name for u in updates)
        print(f"predios: {len(predios)} leídos, {por_objeto['predio']} por normalizar")
        print(f"declaraciones: {len(declaraciones)} leídas, {por_objeto['declaracion_predial']} por normalizar")
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
