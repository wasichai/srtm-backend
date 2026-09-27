#!/usr/bin/env python3
"""Loads the catalogs the portal's forms offer: ubigeo (INEI districts), the categories of the official unit-value
table, the partidas of obras complementarias, the srtm's usos del predio, vías and unidades urbanas.

ubigeo comes from data/ubigeo.csv, the categories from data/categorias_valor.csv, the obras from
data/obras_complementarias.csv, the usos from data/usos_predio.csv (all shipped). vías and unidades urbanas come from the padrón Excel: the
same `direccion_predio` import_predios.py parses, its `<vía>` and `<habilitación>` parts, split into a type
(the model's tipo_via / tipo_unidad_urbana) and a name. Idempotent: what Core already has is skipped.

Run: python3 import_catalogos.py [--excel "CODIGO DE PREDIOS AL 2026.xlsx"] [--dry-run]
Exit: 0 ok, 1 Core refused something.
"""
import argparse
import csv
import os
import re
import sys

from core_client import Client, CoreError
from import_predios import (TIPOS_UNIDAD_URBANA, TIPOS_VIA, LoadError, clean_decimal, clean_text, parse_address, post_all, read_xlsx,
                            split_tipo)

HERE = os.path.dirname(os.path.abspath(__file__))

# Perené (JUNIN / CHANCHAMAYO): the district the padrón's vías and unidades urbanas belong to
DEFAULT_UBIGEO = "120302"


def read_ubigeo(path):
    with open(path, encoding="utf-8") as f:
        return [
            {"codigo": r["codigo"], "departamento": r["departamento"], "provincia": r["provincia"], "distrito": r["distrito"]}
            for r in csv.DictReader(f)
        ]


def read_categorias(path):
    """The descriptions of the letters of the official unit-value table, one row per column and letter."""
    with open(path, encoding="utf-8") as f:
        return [
            {"columna": int(r["columna"]), "categoria": r["categoria"], "letra": r["letra"], "descripcion": r["descripcion"]}
            for r in csv.DictReader(f)
        ]


def read_obras(path):
    """The partidas of obras complementarias e instalaciones fijas y permanentes: annex III of the yearly R.M. of the
    MVCS (277-2025-VIVIENDA for 2026), transcribed by hand because gob.pe does not let scripts download it. The unit
    value is the selva's (III.4, Perené's region), at direct cost: the 0.68 oficialización factor and the depreciation
    are applied on top."""
    with open(path, encoding="utf-8") as f:
        return [
            {
                "tipo_obra": r["tipo_obra"],
                "numero": int(r["numero"]),
                "descripcion": r["descripcion"],
                "unidad_medida": r["unidad_medida"],
                **({"material": r["material"]} if r.get("material") else {}),
                **({"valor_unitario": clean_decimal(r["valor_unitario"])} if r.get("valor_unitario") else {}),
            }
            for r in csv.DictReader(f)
        ]


def read_usos(path):
    """The srtm's "tipo de uso de predio" (clase -> sub clase -> uso), one row per uso with its clase and sub clase.

    The file is the srtm's parameter table: a six-digit code per row, whose level it gives (XX0000 a clase, XXYY00 a
    sub clase, XXYYZZ a uso of that sub clase), its description and where it was taken from."""
    with open(path, encoding="utf-8") as f:
        rows = [(r["codigo"].strip(), r["descripcion"].strip()) for r in csv.DictReader(f)]
    names = {}
    for codigo, descripcion in rows:
        if not re.fullmatch(r"\d{6}", codigo):
            raise ValueError(f"usos del predio: código '{codigo}' no tiene seis dígitos")
        names[codigo] = descripcion
    usos = []
    for codigo, descripcion in rows:
        if codigo.endswith("00"):
            continue
        clase, sub_clase = names.get(codigo[:2] + "0000"), names.get(codigo[:4] + "00")
        if clase is None or sub_clase is None:
            raise ValueError(f"usos del predio: al uso {codigo} le falta su clase o su sub clase")
        usos.append({"codigo": codigo, "clase": clase, "sub_clase": sub_clase, "uso": descripcion})
    return usos


def catalogs_from_rows(rows, ubigeo):
    """Distinct (tipo, nombre) of the vías and unidades urbanas in the padrón's addresses, in first-seen order."""
    vias = {}
    unidades = {}
    for row in rows:
        address = parse_address(clean_text(row.get("direccion_predio")))
        if address["via"]:
            tipo, nombre = split_tipo(address["via"], TIPOS_VIA)
            vias.setdefault((tipo, nombre, ubigeo), {"tipo_via": tipo, "nombre": nombre, "ubigeo": ubigeo})
        if address["habilitacion_urbana"]:
            tipo, nombre = split_tipo(address["habilitacion_urbana"], TIPOS_UNIDAD_URBANA, anywhere=True)
            unidades.setdefault((tipo, nombre, ubigeo), {"tipo_unidad_urbana": tipo, "nombre": nombre, "ubigeo": ubigeo})
    return list(vias.values()), list(unidades.values())


def _missing(client, object_name, items, key):
    existing = {key(r["attributes"]) for r in client.list_all(object_name)}
    return [(key(a), i + 1, a) for i, a in enumerate(items) if key(a) not in existing]


def load(client, ubigeos, vias, unidades, workers, categorias=(), obras=(), usos=()):
    """Creates what Core lacks. Returns (created, skipped)."""
    created = skipped = 0
    plan = [
        ("ubigeo", ubigeos, lambda a: a["codigo"]),
        ("categoria_valor", list(categorias), lambda a: (int(a["columna"]), a["letra"])),
        ("obra_categoria", list(obras), lambda a: (a["tipo_obra"], int(a["numero"]))),
        ("uso_predio", list(usos), lambda a: a["codigo"]),
        ("via", vias, lambda a: (a["tipo_via"], a["nombre"], a.get("ubigeo"))),
        ("unidad_urbana", unidades, lambda a: (a["tipo_unidad_urbana"], a["nombre"], a.get("ubigeo"))),
    ]
    for object_name, items, key in plan:
        todo = _missing(client, object_name, items, key)
        new = post_all(client, object_name, todo, workers)
        created += len(new)
        skipped += len(items) - len(todo)
        print(f"{object_name}: {len(new)} created, {len(items) - len(todo)} skipped", flush=True)
    return created, skipped


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Load srtm's catalogs (ubigeo, categorías, obras, usos del predio, vías, unidades urbanas) into wasichai Core.")
    p.add_argument("--ubigeo-csv", default=os.path.join(HERE, "data", "ubigeo.csv"))
    p.add_argument("--categorias-csv", default=os.path.join(HERE, "data", "categorias_valor.csv"))
    p.add_argument("--obras-csv", default=os.path.join(HERE, "data", "obras_complementarias.csv"))
    p.add_argument("--usos-csv", default=os.path.join(HERE, "data", "usos_predio.csv"))
    p.add_argument("--excel", default=None, help="padrón Excel; without it only ubigeo is loaded")
    p.add_argument("--sheet", default=None, help="sheet name (default: the first)")
    p.add_argument("--distrito", default=DEFAULT_UBIGEO, help=f"ubigeo of the padrón's vías (default {DEFAULT_UBIGEO}, Perené)")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read and transform only; call nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel POSTs (default 4)")
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    ubigeos = read_ubigeo(args.ubigeo_csv)
    categorias = read_categorias(args.categorias_csv)
    obras = read_obras(args.obras_csv)
    usos = read_usos(args.usos_csv)
    vias, unidades = catalogs_from_rows(read_xlsx(args.excel, args.sheet), args.distrito) if args.excel else ([], [])
    print(f"ubigeo: {len(ubigeos)}")
    print(f"categorías de valores unitarios: {len(categorias)}")
    print(f"categorías de obras complementarias: {len(obras)}")
    print(f"usos del predio: {len(usos)}")
    print(f"vías: {len(vias)}")
    print(f"unidades urbanas: {len(unidades)}")
    if args.dry_run:
        return 0

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        created, skipped = load(client, ubigeos, vias, unidades, args.workers, categorias, obras, usos)
    except LoadError as e:
        print(f"error POST /api/objects/{e.object_name}/records (#{e.fila}) -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    print(f"done: {created} created, {skipped} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
