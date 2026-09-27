#!/usr/bin/env python3
"""Loads the lotes of the catastro fiscal from a GeoJSON FeatureCollection (EPSG:4326) into catastro_fiscal.

Each feature is a lote: its polygon goes to lote_geom, its properties to the fields. By default a property with a
field's own name fills it; --map field=property renames (repeatable). A one-part MultiPolygon is taken as its
Polygon. Idempotent by codigo_cpu: lotes Core already has are skipped.

A Shapefile converts first: ogr2ogr -f GeoJSON -t_srs EPSG:4326 lotes.geojson lotes.shp

Run: python3 import_catastro.py --geojson lotes.geojson [--map codigo_cpu=CPU] [--dry-run]
Exit: 0 ok, 1 Core refused something, 2 the file has lotes that do not fit the model (nothing is sent).
"""
import argparse
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

from core_client import Client, CoreError
from import_predios import LoadError

HERE = os.path.dirname(os.path.abspath(__file__))

FIELDS = [
    "codigo_cpu", "codigo_predio_municipal", "partida_registral", "tipo_predio", "ubigeo", "tipo_via", "via", "numero",
    "tipo_zona", "zona", "manzana", "lote", "kilometro", "direccion",
]
# field -> the model enum its value must be one of
ENUM_FIELDS = {"tipo_predio": "tipo_predio", "tipo_via": "tipo_via", "tipo_zona": "tipo_unidad_urbana"}
GEOMETRY = "lote_geom"


def parse_map(pairs):
    mapping = {field: field for field in FIELDS}
    for pair in pairs:
        field, _, prop = pair.partition("=")
        if field not in FIELDS or not prop:
            raise ValueError(f"--map {pair}: expected <field>=<property>, field one of {', '.join(FIELDS)}")
        mapping[field] = prop
    return mapping


def polygon_of(geometry):
    """The lote's Polygon, or None when the geometry is not one (a MultiPolygon of one part counts)."""
    if not geometry:
        return None
    if geometry.get("type") == "Polygon":
        return geometry
    if geometry.get("type") == "MultiPolygon" and len(geometry.get("coordinates") or []) == 1:
        return {"type": "Polygon", "coordinates": geometry["coordinates"][0]}
    return None


def lotes_from(collection, mapping, enums):
    """(lotes, problems). A lote is (feature number, attributes, polygon)."""
    lotes, problems, seen = [], [], set()
    for n, feature in enumerate(collection.get("features") or [], start=1):
        props = feature.get("properties") or {}
        attributes = {}
        for field in FIELDS:
            value = props.get(mapping[field])
            text = None if value is None else str(value).strip()
            attributes[field] = text or None
        codigo = attributes["codigo_cpu"]
        polygon = polygon_of(feature.get("geometry"))
        if not codigo:
            problems.append(f"feature {n}: no codigo_cpu (property {mapping['codigo_cpu']})")
            continue
        if codigo in seen:
            problems.append(f"feature {n}: codigo_cpu {codigo} repeated")
            continue
        seen.add(codigo)
        if polygon is None:
            problems.append(f"feature {n} ({codigo}): geometry is not a Polygon")
            continue
        for field, enum in ENUM_FIELDS.items():
            if attributes[field] is not None and attributes[field] not in enums[enum]:
                problems.append(f"feature {n} ({codigo}): {field} '{attributes[field]}' is not one of the model's {enum}")
        lotes.append((n, attributes, polygon))
    return lotes, problems


def load(client, lotes, workers):
    """Creates the lotes Core lacks. Returns (created, skipped)."""
    existing = {r["attributes"].get("codigo_cpu") for r in client.list_all("catastro_fiscal")}
    todo = [lote for lote in lotes if lote[1]["codigo_cpu"] not in existing]

    def one(lote):
        n, attributes, polygon = lote
        payload = {"attributes": {k: v for k, v in attributes.items() if v is not None}, "geometries": {GEOMETRY: polygon}}
        try:
            client.post("/api/objects/catastro_fiscal/records", payload)
        except CoreError as e:
            raise LoadError("catastro_fiscal", n, e)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, lote) for lote in todo]
        try:
            for future in as_completed(futures):
                future.result()
        except LoadError:
            for future in futures:
                future.cancel()
            raise
    return len(todo), len(lotes) - len(todo)


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Load the catastro fiscal's lotes (GeoJSON) into wasichai Core.")
    p.add_argument("--geojson", required=True, help="FeatureCollection in EPSG:4326")
    p.add_argument("--map", action="append", default=[], metavar="FIELD=PROPERTY", help="property that fills a field")
    p.add_argument("--model", default=os.path.join(HERE, "model.json"))
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read and check only; call nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel POSTs (default 4)")
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)
    try:
        mapping = parse_map(args.map)
    except ValueError as e:
        print(str(e), file=sys.stderr)
        return 2
    with open(args.geojson, encoding="utf-8") as f:
        collection = json.load(f)
    with open(args.model, encoding="utf-8") as f:
        enums = json.load(f)["enums"]
    lotes, problems = lotes_from(collection, mapping, enums)
    print(f"lotes: {len(lotes)}")
    if problems:
        for problem in problems[:50]:
            print(f"problem: {problem}", file=sys.stderr)
        print(f"{len(problems)} problems: nothing was sent to Core", file=sys.stderr)
        return 2
    if args.dry_run:
        return 0
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        created, skipped = load(client, lotes, args.workers)
    except LoadError as e:
        print(f"error POST /api/objects/catastro_fiscal/records (feature {e.fila}) -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    print(f"done: {created} created, {skipped} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
