#!/usr/bin/env python3
"""Loads the catalogs the portal's forms offer: ubigeo (INEI districts), the categories of the official unit-value
table, the partidas of obras complementarias, the srtm's usos del predio, vías and unidades urbanas, and the municipalidad (the PU and HR's header).

ubigeo comes from data/ubigeo.csv, the categories from data/categorias_valor.csv, the obras from
data/obras_complementarias.csv, the usos from data/usos_predio.csv (all shipped). vías and unidades urbanas come from the padrón Excel: the
same `direccion_predio` import_predios.py parses, its `<vía>` and `<habilitación>` parts, split into a type
(the model's tipo_via / tipo_unidad_urbana) and a name. Idempotent: what Core already has is skipped.

The usos del predio follow the CSV by código: a código Core lacks is created, one whose clase, sub clase or uso
changed is updated and one the CSV no longer has is deleted. Nothing references their records (a declaración keeps
the names in its own ENUMs). The other catalogs are only created. The municipalidad (data/municipalidad.json, the
provisional header) is created only when the organization has none: the real one is edited in the admin.

Run: python3 import_catalogos.py [--excel "CODIGO DE PREDIOS AL 2026.xlsx"] [--dry-run]
Exit: 0 ok, 1 Core refused something.
"""
import argparse
import csv
import json
import os
import re
import sys

from core_client import Client, CoreError
from import_predios import TIPOS_VIA, LoadError, clean_decimal, clean_text, parse_address, post_all, read_xlsx, split_tipo, split_zona

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


def read_municipalidad(path):
    """The municipalidad to create, as a one-item list: the file's fields without its notes (a key starting with _)
    and without the empty ones."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return [{k: v for k, v in data.items() if not k.startswith("_") and v not in (None, "")}]


def catalogs_from_rows(rows, ubigeo):
    """Distinct (tipo, nombre) of the vías and unidades urbanas in the padrón's addresses, in first-seen order. A
    habilitación of no type is no unidad urbana of the catalog: its tipo is required."""
    vias = {}
    unidades = {}
    for row in rows:
        address = parse_address(clean_text(row.get("direccion_predio")))
        if address["via"]:
            tipo, nombre = split_tipo(address["via"], TIPOS_VIA)
            vias.setdefault((tipo, nombre, ubigeo), {"tipo_via": tipo, "nombre": nombre, "ubigeo": ubigeo})
        tipo, nombre = split_zona(address["habilitacion_urbana"]) if address["habilitacion_urbana"] else (None, None)
        if tipo:
            unidades.setdefault((tipo, nombre, ubigeo), {"tipo_unidad_urbana": tipo, "nombre": nombre, "ubigeo": ubigeo})
    return list(vias.values()), list(unidades.values())


def _missing(client, object_name, items, key):
    existing = {key(r["attributes"]) for r in client.list_all(object_name)}
    return [(key(a), i + 1, a) for i, a in enumerate(items) if key(a) not in existing]


USO_PREDIO = "uso_predio"
USO_NAMES = ("clase", "sub_clase", "uso")


class SyncError(LoadError):
    """A PUT or DELETE of a uso Core refused."""

    def __init__(self, method, codigo, error):
        super().__init__(USO_PREDIO, codigo, error)
        self.method = method


def plan_usos(records, usos):
    """What makes Core's usos the CSV's, by código: (create, update, delete, skipped). create: the usos Core lacks;
    update: (record, uso) of a código whose clase, sub clase or uso changed; delete: the records of a código the CSV
    no longer has; skipped: how many are already as the CSV says."""
    by_codigo = {r["attributes"].get("codigo"): r for r in records}
    wanted = {u["codigo"]: u for u in usos}
    create = [u for codigo, u in wanted.items() if codigo not in by_codigo]
    update = [
        (by_codigo[codigo], u) for codigo, u in wanted.items()
        if codigo in by_codigo and any(by_codigo[codigo]["attributes"].get(k) != u[k] for k in USO_NAMES)
    ]
    delete = [r for codigo, r in by_codigo.items() if codigo not in wanted]
    return create, update, delete, len(wanted) - len(create) - len(update)


def _names(attributes, keys=USO_NAMES):
    return " / ".join(str(attributes.get(k)) for k in keys)


def sync_usos(client, usos, workers, dry_run=False):
    """Makes Core's usos del predio the CSV's (see plan_usos). Returns (created, skipped)."""
    create, update, delete, skipped = plan_usos(client.list_all(USO_PREDIO), usos)
    if dry_run:
        print(f"{USO_PREDIO}: {len(create)} to create, {len(update)} to update, {len(delete)} to delete, {skipped} skipped")
        for u in create:
            print(f"  create {u['codigo']}: {_names(u)}")
    for record, u in update:
        changed = [k for k in USO_NAMES if record["attributes"].get(k) != u[k]]
        print(f"  update {u['codigo']}: {_names(record['attributes'], changed)} -> {_names(u, changed)}")
    for record in delete:
        print(f"  delete {record['attributes'].get('codigo')}: {_names(record['attributes'])}")
    if dry_run:
        return len(create), skipped
    new = post_all(client, USO_PREDIO, [(u["codigo"], u["codigo"], u) for u in create], workers)
    for record, u in update:
        try:
            client.put(f"/api/objects/{USO_PREDIO}/records/{record['id']}", {"attributes": {"codigo": u["codigo"], **{k: u[k] for k in USO_NAMES}}})
        except CoreError as e:
            raise SyncError("PUT", u["codigo"], e)
    for record in delete:
        try:
            client.delete(f"/api/objects/{USO_PREDIO}/records/{record['id']}")
        except CoreError as e:
            raise SyncError("DELETE", record["attributes"].get("codigo"), e)
    print(f"{USO_PREDIO}: {len(new)} created, {len(update)} updated, {len(delete)} deleted, {skipped} skipped", flush=True)
    return len(new), skipped


def load(client, ubigeos, vias, unidades, workers, categorias=(), obras=(), usos=(), dry_run=False, municipalidades=()):
    """Creates what Core lacks and syncs the usos del predio (only when there are usos: none leaves them as they
    are). With dry_run, reads Core and says what it would do. Returns (created, skipped)."""
    created = skipped = 0
    plan = [
        ("ubigeo", ubigeos, lambda a: a["codigo"]),
        ("categoria_valor", list(categorias), lambda a: (int(a["columna"]), a["letra"])),
        ("obra_categoria", list(obras), lambda a: (a["tipo_obra"], int(a["numero"]))),
        (USO_PREDIO, list(usos), None),
        ("via", vias, lambda a: (a["tipo_via"], a["nombre"], a.get("ubigeo"))),
        ("unidad_urbana", unidades, lambda a: (a["tipo_unidad_urbana"], a["nombre"], a.get("ubigeo"))),
        # one per organization: any record there is the one, so the provisional is created only into an empty object
        ("municipalidad", list(municipalidades), lambda a: "municipalidad"),
    ]
    for object_name, items, key in plan:
        if object_name == USO_PREDIO:
            if items:
                new, same = sync_usos(client, items, workers, dry_run)
                created += new
                skipped += same
            continue
        todo = _missing(client, object_name, items, key)
        if dry_run:
            print(f"{object_name}: {len(todo)} to create, {len(items) - len(todo)} skipped", flush=True)
            new = todo
        else:
            new = post_all(client, object_name, todo, workers)
            print(f"{object_name}: {len(new)} created, {len(items) - len(todo)} skipped", flush=True)
        created += len(new)
        skipped += len(items) - len(todo)
    return created, skipped


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Load srtm's catalogs (ubigeo, categorías, obras, usos del predio, vías, unidades urbanas) into wasichai Core.")
    p.add_argument("--ubigeo-csv", default=os.path.join(HERE, "data", "ubigeo.csv"))
    p.add_argument("--categorias-csv", default=os.path.join(HERE, "data", "categorias_valor.csv"))
    p.add_argument("--obras-csv", default=os.path.join(HERE, "data", "obras_complementarias.csv"))
    p.add_argument("--usos-csv", default=os.path.join(HERE, "data", "usos_predio.csv"))
    p.add_argument("--municipalidad-json", default=os.path.join(HERE, "data", "municipalidad.json"))
    p.add_argument("--excel", default=None, help="padrón Excel; without it only ubigeo is loaded")
    p.add_argument("--sheet", default=None, help="sheet name (default: the first)")
    p.add_argument("--distrito", default=DEFAULT_UBIGEO, help=f"ubigeo of the padrón's vías (default {DEFAULT_UBIGEO}, Perené)")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and say what it would do; write nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel POSTs (default 4)")
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    ubigeos = read_ubigeo(args.ubigeo_csv)
    categorias = read_categorias(args.categorias_csv)
    obras = read_obras(args.obras_csv)
    usos = read_usos(args.usos_csv)
    municipalidades = read_municipalidad(args.municipalidad_json)
    vias, unidades = catalogs_from_rows(read_xlsx(args.excel, args.sheet), args.distrito) if args.excel else ([], [])
    print(f"ubigeo: {len(ubigeos)}")
    print(f"categorías de valores unitarios: {len(categorias)}")
    print(f"categorías de obras complementarias: {len(obras)}")
    print(f"usos del predio: {len(usos)}")
    print(f"vías: {len(vias)}")
    print(f"unidades urbanas: {len(unidades)}")

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        created, skipped = load(client, ubigeos, vias, unidades, args.workers, categorias, obras, usos, args.dry_run, municipalidades)
    except LoadError as e:
        method = getattr(e, "method", "POST")
        print(f"error {method} /api/objects/{e.object_name}/records (#{e.fila}) -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    if args.dry_run:
        print(f"dry run: {created} to create, {skipped} skipped; nothing written")
    else:
        print(f"done: {created} created, {skipped} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
