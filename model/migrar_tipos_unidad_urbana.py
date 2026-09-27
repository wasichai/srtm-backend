#!/usr/bin/env python3
"""Moves the records whose tipo de unidad urbana is not one of the srtm's (the catastro fiscal's TIPO_UU domain,
data/tipos_unidad_urbana.csv) to the type the importers now read from the padrón (import_predios.split_tipo):

- ANEXO is a CENTRO POBLADO, and its name loses the CENTRO POBLADO it repeats: ANEXO / CENTRO POBLADO MIRICHARO is
  CENTRO POBLADO / MIRICHARO.
- HABILITACION URBANA is an URBANIZACION, unless its name starts with its own type: SECTOR 10 DE OCTUBRE is SECTOR /
  10 DE OCTUBRE, RESIDENCIAL IPANEMA is RESIDENCIAL / IPANEMA.
- OTROS takes the type its name starts with, if any: CENTRO URBANO INFORMAL VISTA ALEGRE is POSESION INFORMAL / VISTA
  ALEGRE.

In the predios (tipo_zona, habilitacion_urbana), the unidades urbanas (tipo_unidad_urbana, nombre), the domicilios
(tipo_unidad_urbana, unidad_urbana) and the lotes of the catastro fiscal (tipo_zona, zona). A unidad urbana that would
repeat another one (same tipo, nombre and ubigeo: the padrón wrote the same centro poblado with and without ANEXO) is
deleted and the other one stays: the catalog keeps one of each, and no record links to one (predios and domicilios
keep its name). What takes no type of the srtm (OTROS VILLA SOL, a COMUNIDAD NATIVA) is not changed: it goes to the
report, and apply.py keeps its option while it is used. Once none is left, apply.py drops the old options.

Reads everything first and writes a report (one row per record with an old option: migrado, borrado duplicado with
the id of the one that stays, or sin cambio and why). Then updates each record with a PUT of all its fields (Core's
update replaces them all) and, once every update went through, deletes the repeated ones. Idempotent: a second run
changes nothing.

Run: python3 migrar_tipos_unidad_urbana.py [--dry-run] [--report reports/migrar_tipos_unidad_urbana.csv]
Exit: 0 ok, 1 Core refused something.
"""
import argparse
import csv
import os
import sys
from collections import Counter
from dataclasses import dataclass

from core_client import Client, CoreError
from import_predios import TIPOS_UNIDAD_URBANA, LoadError, clean_text, load_enums, split_tipo
from normalizar_padron import Update, put_all

HERE = os.path.dirname(os.path.abspath(__file__))
# object -> (its tipo de unidad urbana, its name, what a person finds it by)
CAMPOS = {
    "predio": ("tipo_zona", "habilitacion_urbana", "codigo"),
    "unidad_urbana": ("tipo_unidad_urbana", "nombre", "ubigeo"),
    "domicilio": ("tipo_unidad_urbana", "unidad_urbana", "codigo"),
    "catastro_fiscal": ("tipo_zona", "zona", "codigo_cpu"),
}
REPORT_COLUMNS = ["objeto", "id", "clave", "accion", "tipo_antes", "nombre_antes", "tipo_despues", "nombre_despues", "queda_id",
                  "nota"]
MIGRADO, BORRADO, SIN_CAMBIO = "migrado", "borrado duplicado", "sin cambio"
NOTA_SIN_TIPO = "sin tipo del srtm: no se cambia; apply.py conserva su opción mientras la use"


@dataclass
class Borrado:
    object_name: str
    record_id: str
    queda_id: str  # the record it repeats, which stays


class BorradoError(LoadError):
    pass


def tipo_srtm(tipo, nombre):
    """An old option and its name -> the srtm's (tipo, nombre), read as the padrón's text would be; None when it has
    no type of the srtm. The name is its own text for OTROS, which was never a word of the address."""
    nombre = clean_text(nombre)
    texto = nombre if tipo == "OTROS" else " ".join(filter(None, [tipo, nombre]))
    if not texto:
        return None
    nuevo, resto = split_tipo(texto, TIPOS_UNIDAD_URBANA, default=None)
    if nuevo is not None:
        return nuevo, resto
    # a word alone, with no name
    tipos = dict(TIPOS_UNIDAD_URBANA)
    return (tipos[texto], None) if nombre is None and texto in tipos else None


def _blank(value):
    return "" if value is None else value


def _row(objeto, record, clave, accion, tipo, nombre, nuevo=(None, None), queda_id=None, nota=""):
    return {
        "objeto": objeto, "id": record["id"], "clave": clave, "accion": accion, "tipo_antes": tipo, "nombre_antes": _blank(nombre),
        "tipo_despues": _blank(nuevo[0]), "nombre_despues": _blank(nuevo[1]), "queda_id": _blank(queda_id), "nota": nota,
    }


def plan(records, opciones):
    """Core's records per object (CAMPOS) and the model's options -> the updates, the deletions and the report's rows."""
    updates = []
    borrados = []
    rows = []
    for objeto, (tipo_field, nombre_field, clave_field) in CAMPOS.items():
        # the unidades urbanas already there, (tipo, nombre, ubigeo) -> id: the catalog keeps one of each
        unidades = None
        if objeto == "unidad_urbana":
            unidades = {(r["attributes"].get(tipo_field), r["attributes"].get(nombre_field), r["attributes"].get("ubigeo")): r["id"]
                        for r in records[objeto] if r["attributes"].get(tipo_field) in opciones}
        for record in records[objeto]:
            stored = record["attributes"]
            tipo = stored.get(tipo_field)
            if not tipo or tipo in opciones:
                continue
            nombre = stored.get(nombre_field)
            clave = str(stored.get(clave_field) or record["id"])
            nuevo = tipo_srtm(tipo, nombre)
            if nuevo is None:
                rows.append(_row(objeto, record, clave, SIN_CAMBIO, tipo, nombre, nota=NOTA_SIN_TIPO))
                continue
            if unidades is not None:
                key = (*nuevo, stored.get("ubigeo"))
                if key in unidades:
                    rows.append(_row(objeto, record, clave, BORRADO, tipo, nombre, nuevo, queda_id=unidades[key]))
                    borrados.append(Borrado(objeto, record["id"], unidades[key]))
                    continue
                unidades[key] = record["id"]
            rows.append(_row(objeto, record, clave, MIGRADO, tipo, nombre, nuevo))
            updates.append(Update(objeto, record["id"], clave, {**stored, tipo_field: nuevo[0], nombre_field: nuevo[1]}))
    return updates, borrados, rows


def delete_all(client, borrados):
    """DELETEs every repeated one, in order; the first refusal stops it all. Returns how many were deleted."""
    for borrado in borrados:
        try:
            client.delete(f"/api/objects/{borrado.object_name}/records/{borrado.record_id}")
        except CoreError as e:
            raise BorradoError(borrado.object_name, borrado.record_id, e)
    return len(borrados)


def write_report(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=REPORT_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


def _print_plan(records, rows):
    for objeto in CAMPOS:
        filas = [r for r in rows if r["objeto"] == objeto]
        por_accion = Counter(r["accion"] for r in filas)
        borrar = f", {por_accion[BORRADO]} por borrar" if por_accion[BORRADO] else ""
        print(f"{objeto}: {len(records[objeto])} registros, {por_accion[MIGRADO]} por migrar{borrar}")
        for accion, sufijo in ((MIGRADO, ""), (BORRADO, ", ya existía (se borra)")):
            for (antes, despues), count in Counter((r["tipo_antes"], r["tipo_despues"]) for r in filas if r["accion"] == accion).most_common():
                print(f"  {antes} -> {despues}{sufijo}: {count}")
        if por_accion[SIN_CAMBIO]:
            print(f"  no se cambian (ver el reporte): {por_accion[SIN_CAMBIO]}")


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Move the old tipos de unidad urbana to the srtm's (TIPO_UU) in srtm's wasichai Core.")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--model", default=os.path.join(HERE, "model.json"))
    p.add_argument("--dry-run", action="store_true", help="read Core and write the report; change nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel PUTs (default 4)")
    p.add_argument("--report", default=os.path.join(HERE, "reports", "migrar_tipos_unidad_urbana.csv"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)
    opciones = load_enums(args.model)["tipo_unidad_urbana"]

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        records = {objeto: client.list_all(objeto) for objeto in CAMPOS}
        updates, borrados, rows = plan(records, opciones)
        write_report(args.report, rows)
        _print_plan(records, rows)
        print(f"notas: {sum(1 for r in rows if r['nota'])} -> {args.report}")
        if args.dry_run:
            return 0
        # the one a repeated unidad urbana leaves for may be migrating too: it goes first
        done = put_all(client, updates, args.workers)
        deleted = delete_all(client, borrados)
    except LoadError as e:
        metodo = "DELETE" if isinstance(e, BorradoError) else "PUT"
        print(f"error {metodo} {e.object_name} {e.fila} -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    print(f"done: {done} updated, {deleted} deleted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
