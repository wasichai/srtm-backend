#!/usr/bin/env python3
"""Loads zonas de arbitrio por ubicación into wasichai Core (zona_arbitrio_ubicacion): a zona by vía, tramo (a range
of número and paridad) and manzana, optionally restricted to one servicio. srtm.arbitrios.Zonas.de tries it before
ARBITRIO_ZONA's sector catastral (README § Arbitrios, «Zona por servicio y por ubicación»).

The file is a CSV with the columns servicio, tipo_via, via, manzana, numero_inicial, numero_final, paridad, zona,
vigencia_desde, vigencia_hasta, norma, observacion, and a '#' header that cites its source; servicio, tipo_via,
manzana, vigencia_hasta, norma and observacion may be empty. The natural key is clave(), srtm.arbitrios.Modelo's
claveDeZonaUbicacion mirrored in Python: servicio|tipo_via|via|manzana|numero_inicial|numero_final|paridad|
vigencia_desde, each part trimmed and empty when absent.

What Core lacks is created, a row whose non-key fields changed (zona, vigencia_hasta, norma, observacion) is updated
in place, and one that is the same is skipped; nothing is deleted, so a tramo whose key fields changed is a new row
and the old one stays. Everything is checked before the first write: the required fields, numero_inicial and
numero_final as integers with numero_inicial <= numero_final, paridad in model.json's paridad_numeracion, every
date in ISO, and two rows of the same servicio, vía, manzana and paridad whose tramos overlap, with different zona
and vigencias that cross (an ambiguity that Zonas.de would otherwise hit at calculation time).

Run: python3 import_zonas_arbitrio.py --csv zonas.csv [--dry-run]
Exit: 0 ok, 1 Core refused something, 2 the file is malformed (nothing is sent).
"""
import argparse
import csv
import json
import os
import sys
from datetime import date

from core_client import Client, CoreError

HERE = os.path.dirname(os.path.abspath(__file__))
OBJECT = "zona_arbitrio_ubicacion"
COLUMNS = ("servicio", "tipo_via", "via", "manzana", "numero_inicial", "numero_final", "paridad", "zona",
           "vigencia_desde", "vigencia_hasta", "norma", "observacion")
# the fields claveDeZonaUbicacion joins, in order
CAMPOS_CLAVE = ("servicio", "tipo_via", "via", "manzana", "numero_inicial", "numero_final", "paridad", "vigencia_desde")
REQUERIDOS = ("via", "numero_inicial", "numero_final", "paridad", "zona", "vigencia_desde")
ENTEROS = ("numero_inicial", "numero_final")
FECHAS = ("vigencia_desde", "vigencia_hasta")


def _paridades():
    with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
        return tuple(json.load(f)["enums"]["paridad_numeracion"])


def read_csv(path):
    """The CSV's rows as attributes; the '#' lines are its header comment and an empty cell is left out."""
    with open(path, encoding="utf-8") as f:
        rows = csv.DictReader(line for line in f if not line.startswith("#"))
        return [{k: r[k].strip() for k in COLUMNS if r.get(k) and r[k].strip()} for r in rows]


def _entero(valor):
    if isinstance(valor, int):
        return valor
    try:
        return int(str(valor).strip())
    except (TypeError, ValueError):
        return None


def _fecha(valor):
    try:
        return date.fromisoformat(str(valor))
    except ValueError:
        return None


def _parte(fila, campo):
    valor = fila.get(campo)
    return str(valor).strip() if valor not in (None, "") else ""


def clave(fila):
    """Mirrors srtm.arbitrios.Modelo.claveDeZonaUbicacion: each field trimmed, empty when absent, joined by '|'."""
    return "|".join(_parte(fila, c) for c in CAMPOS_CLAVE)


def _errores_de(i, fila, paridades):
    nombre = f"fila {i + 1} ({fila.get('via') or '?'})"
    malas = [f"{nombre}: falta {c}" for c in REQUERIDOS if c not in fila]
    for c in ENTEROS:
        if c in fila and _entero(fila[c]) is None:
            malas.append(f"{nombre}: {c} no es un número entero")
    if all(c in fila and _entero(fila[c]) is not None for c in ENTEROS):
        if _entero(fila["numero_inicial"]) > _entero(fila["numero_final"]):
            malas.append(f"{nombre}: numero_inicial es mayor que numero_final")
    if "paridad" in fila and fila["paridad"] not in paridades:
        malas.append(f"{nombre}: la paridad es una de {', '.join(paridades)}")
    for c in FECHAS:
        if c in fila and _fecha(fila[c]) is None:
            malas.append(f"{nombre}: {c} no es una fecha AAAA-MM-DD")
    return malas


def _se_solapan_tramos(a, b):
    inicial_a, final_a = _entero(a["numero_inicial"]), _entero(a["numero_final"])
    inicial_b, final_b = _entero(b["numero_inicial"]), _entero(b["numero_final"])
    return inicial_a <= final_b and inicial_b <= final_a


def _se_cruzan_vigencias(a, b):
    hasta_a = _fecha(a["vigencia_hasta"]) if a.get("vigencia_hasta") else None
    hasta_b = _fecha(b["vigencia_hasta"]) if b.get("vigencia_hasta") else None
    desde_a, desde_b = _fecha(a["vigencia_desde"]), _fecha(b["vigencia_desde"])
    if hasta_a is not None and hasta_a < desde_b:
        return False
    if hasta_b is not None and hasta_b < desde_a:
        return False
    return True


def _mismo_tramo(a, b):
    return (a.get("servicio", ""), a.get("via"), a.get("manzana", ""), a.get("paridad")) == \
           (b.get("servicio", ""), b.get("via"), b.get("manzana", ""), b.get("paridad"))


def errores(filas):
    """What does not fit in the file alone, as text. Overlaps are only checked once every row is well-formed."""
    paridades = _paridades()
    malas = []
    for i, fila in enumerate(filas):
        malas += _errores_de(i, fila, paridades)
    if malas:
        return malas
    for i, a in enumerate(filas):
        for j in range(i + 1, len(filas)):
            b = filas[j]
            if a.get("zona") == b.get("zona") or not _mismo_tramo(a, b):
                continue
            if _se_solapan_tramos(a, b) and _se_cruzan_vigencias(a, b):
                malas.append(f"fila {i + 1} y fila {j + 1}: tramos de {a.get('via')} se solapan con distinta zona "
                             "y vigencias que se cruzan")
    return malas


def _atributos(fila):
    """Fila ready to send: numero_inicial/numero_final as int and the computed clave."""
    atributos = {**fila, "numero_inicial": _entero(fila["numero_inicial"]), "numero_final": _entero(fila["numero_final"])}
    atributos["clave"] = clave(atributos)
    return atributos


def _cambios(guardado, deseado):
    return [k for k, v in deseado.items() if str(guardado.get(k)) != str(v)]


def plan(registros, filas):
    """(create, update, skipped): the rows to create, (record, merged attributes) to update in place, how many are
    already as the file says. The natural key is clave(): a row whose key fields changed is a new one, never a
    replacement of the old (nothing is deleted)."""
    por_clave = {r["attributes"].get("clave"): r for r in registros}
    crear, actualizar = [], []
    for fila in filas:
        r = por_clave.get(fila["clave"])
        if r is None:
            crear.append(fila)
        elif _cambios(r["attributes"], fila):
            actualizar.append((r, {**r["attributes"], **fila}))
    return crear, actualizar, len(filas) - len(crear) - len(actualizar)


def cargar(client, filas, dry_run=False):
    """Creates and updates in place, printing a line per row it writes. Returns (created, updated, skipped)."""
    atributos = [_atributos(f) for f in filas]
    crear, actualizar, iguales = plan(client.list_all(OBJECT), atributos)
    for fila in crear:
        print(f"  create {OBJECT} {fila['clave']}")
    for r, fila in actualizar:
        print(f"  update {OBJECT} {fila['clave']}: {', '.join(_cambios(r['attributes'], fila))}")
    if not dry_run:
        for fila in crear:
            client.post(f"/api/objects/{OBJECT}/records", {"attributes": fila})
        for r, fila in actualizar:
            client.put(f"/api/objects/{OBJECT}/records/{r['id']}", {"attributes": fila})
    return len(crear), len(actualizar), iguales


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Load zonas de arbitrio por ubicación into wasichai Core.")
    p.add_argument("--csv", required=True, help="servicio,tipo_via,via,manzana,numero_inicial,numero_final,paridad,"
                                                 "zona,vigencia_desde,vigencia_hasta,norma,observacion")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and say what it would do; write nothing")
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    filas = read_csv(args.csv)
    malas = errores(filas)
    if malas:
        for e in malas:
            print(f"error: {e}", file=sys.stderr)
        return 2
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        creadas, actualizadas, iguales = cargar(client, filas, args.dry_run)
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    if args.dry_run:
        print(f"{OBJECT}: {creadas} to create, {actualizadas} to update, {iguales} skipped; nothing written")
    else:
        print(f"{OBJECT}: {creadas} created, {actualizadas} updated, {iguales} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
