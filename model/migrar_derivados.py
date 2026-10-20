#!/usr/bin/env python3
"""Gets the declaraciones already in Core ready for the derived values the portal now computes (#156):

- declaracion_predial: frontis_principal, what is written of the frente principal, is longitud_frente where it is
  empty. longitud_frente is now frontis_principal plus the otros frentes.
- nivel_construccion, obra_complementaria and otro_frente: a fila (uuid) for each row without one, the same for the
  same row in the other DJ of its condominio (same predio, año and secuencia de uso, vigentes). Rows pair by their
  natural key (nivel: tipo and piso; obra: tipo, piso and categoría; frente: vía and número), the n-th of a key in one
  DJ with the n-th in the others; a row with no pair gets a uuid of its own. A row that has its fila keeps it, and
  its pairs take it.

Recomputes nothing: area_construida, longitud_frente and valor_autoavaluo stay as imported until the DJ's first
change in the portal.

Reads everything first, then updates each record with a PUT of all its fields (Core's update replaces them all).
Idempotent: a second run changes nothing.

Run: python3 migrar_derivados.py [--dry-run]
Exit: 0 ok, 1 Core refused something.
"""
import argparse
import os
import sys
import uuid
from collections import defaultdict

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core_client import Client, CoreError  # noqa: E402
from import_predios import LoadError, clean_text, secuencia_uso  # noqa: E402
from normalizar_padron import Update, put_all  # noqa: E402

# a list -> the fields of its natural key
LLAVES = {
    "nivel_construccion": ("tipo_nivel", "numero_piso"),
    "obra_complementaria": ("tipo_obra", "numero_piso", "categoria"),
    "otro_frente": ("via", "numero"),
}
VIGENTE = "VIGENTE"


def _vacio(value):
    return value is None or (isinstance(value, str) and not value.strip())


def _grupo(attributes):
    """The condominio of a vigente declaración (an imported one has no estado), else None."""
    if not _vacio(attributes.get("estado")) and attributes.get("estado") != VIGENTE:
        return None
    predio, anio = attributes.get("predio"), attributes.get("anio")
    if _vacio(predio) or _vacio(anio):
        return None
    return predio, str(anio), secuencia_uso(attributes.get("secuencia_uso"))


def _llave(attributes, campos):
    return tuple((clean_text(str(attributes.get(c))) or "").upper() if not _vacio(attributes.get(c)) else "" for c in campos)


def _frontis(declaraciones):
    updates = []
    for record in declaraciones:
        stored = record["attributes"]
        if _vacio(stored.get("frontis_principal")) and not _vacio(stored.get("longitud_frente")):
            clave = str(stored.get("numero_declaracion") or record["id"])
            updates.append(Update("declaracion_predial", record["id"], clave, {**stored, "frontis_principal": stored["longitud_frente"]}))
    return updates


def _filas(objeto, rows, grupo_de):
    """The rows of one list -> their updates and how many were paired or left alone."""
    # (grupo, llave) -> declaración -> its rows of that key, in their order
    juntas = defaultdict(lambda: defaultdict(list))
    sueltas = []
    for row in sorted(rows, key=lambda r: (str(r.get("createdAt") or ""), r["id"])):
        grupo = grupo_de.get(row["attributes"].get("declaracion"))
        if grupo is None:
            sueltas.append([row])
            continue
        juntas[(grupo, _llave(row["attributes"], LLAVES[objeto]))][row["attributes"]["declaracion"]].append(row)
    conjuntos = list(sueltas)
    for por_declaracion in juntas.values():
        for n in range(max(len(v) for v in por_declaracion.values())):
            conjuntos.append([v[n] for v in por_declaracion.values() if n < len(v)])
    updates = []
    resumen = {"filas": 0, "emparejadas": 0, "sueltas": 0}
    for conjunto in conjuntos:
        sin = [r for r in conjunto if _vacio(r["attributes"].get("fila"))]
        if not sin:
            continue
        fila = next((r["attributes"]["fila"] for r in conjunto if not _vacio(r["attributes"].get("fila"))), None) or str(uuid.uuid4())
        resumen["filas"] += len(sin)
        resumen["emparejadas" if len(conjunto) > 1 else "sueltas"] += len(sin)
        updates += [Update(objeto, r["id"], r["id"], {**r["attributes"], "fila": fila}) for r in sin]
    return updates, resumen


def plan(declaraciones, listas):
    """Core's declaraciones and their lists (object -> rows) -> the updates to make and, per list, its counts."""
    updates = _frontis(declaraciones)
    grupo_de = {r["id"]: _grupo(r["attributes"]) for r in declaraciones}
    resumen = {}
    for objeto in LLAVES:
        suyas, resumen[objeto] = _filas(objeto, listas.get(objeto, []), grupo_de)
        updates += suyas
    return updates, resumen


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Copy frontis_principal and pair the condóminos' rows (fila) in srtm's wasichai Core.")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and print what it would do; change nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel PUTs (default 4)")
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        declaraciones = client.list_all("declaracion_predial")
        listas = {objeto: client.list_all(objeto) for objeto in LLAVES}
        updates, resumen = plan(declaraciones, listas)
        frontis = sum(1 for u in updates if u.object_name == "declaracion_predial")
        print(f"declaraciones: {len(declaraciones)} leídas, {frontis} con frontis_principal por copiar")
        for objeto, r in resumen.items():
            print(f"{objeto}: {r['filas']} filas sin fila, {r['emparejadas']} emparejadas, {r['sueltas']} sueltas")
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
