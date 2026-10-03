#!/usr/bin/env python3
"""Loads the CUIS (the cuadro único de infracciones y sanciones) into wasichai Core: one version of a codigo_infraccion
per row of a CSV with the columns familia, codigo, descripcion, materia, porcentaje_uit, porcentaje_uit_segunda,
porcentaje_uit_tercera, medida_complementaria, base_legal, vigencia_desde and observacion, and a '#' header that cites
its source. familia is ADMINISTRATIVA when empty; codigo is trimmed and upper-cased.

A version is never overwritten: idempotent by clave (familia|codigo|vigencia_desde), a row whose clave Core has is
skipped, and a new version of a codigo in force closes it (its vigencia_hasta is the day before the new one starts, its
clave_vigente is emptied) and is added. codigo_infraccion is written only by srtm's service, which closes and adds in
one transaction under a lock per codigo, so every version goes to POST /api/srtm/infracciones/cuis (the generic API
answers 403). Everything is checked against the file and Core before the first write.

With --completo the file is the whole CUIS from its one vigencia_desde (a norm that replaces the previous CUIS, as
OM 006-2026-MDP derogates OM 01-2021): a code in force in Core that the file does not bring is derogated the day
before, through POST /api/srtm/infracciones/cuis/derogacion. Without it, a code the file does not bring is left alone.

Run: python3 import_cuis.py --csv cuis.csv [--completo] [--dry-run]
Exit: 0 ok, 1 Core refused something, 2 the file is malformed, or a row clashes with what Core has (nothing is sent).
"""
import argparse
import csv
import json
import os
import sys
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation

from core_client import Client, CoreError

HERE = os.path.dirname(os.path.abspath(__file__))
OBJECT = "codigo_infraccion"
ENDPOINT = "/api/srtm/infracciones/cuis"
DEROGACION = ENDPOINT + "/derogacion"
COLUMNS = ("familia", "codigo", "descripcion", "materia", "porcentaje_uit", "porcentaje_uit_segunda", "porcentaje_uit_tercera",
           "medida_complementaria", "base_legal", "vigencia_desde", "observacion")
REQUIRED = ("codigo", "descripcion", "porcentaje_uit", "base_legal", "vigencia_desde", "observacion")
FAMILIA = "ADMINISTRATIVA"
# the lengths kotlin's rule checks (srtm.sanciones): core's TEXT has none
LARGOS = {"codigo": 20, "descripcion": 1000, "materia": 120, "medida_complementaria": 500, "base_legal": 2000}
OBSERVACION = (5, 500)
# kotlin's Largos.PORCENTAJE_UIT: a multa of the CUIS goes over the UIT (Perené's reach 1000 %)
PORCENTAJE_MAXIMO = Decimal(10000)
PORCENTAJES = ("porcentaje_uit", "porcentaje_uit_segunda", "porcentaje_uit_tercera")
# what a version is: a row whose clave Core has must say the same (the observación only explains the load)
VALORES = ("descripcion", "materia", "porcentaje_uit", "porcentaje_uit_segunda", "porcentaje_uit_tercera", "medida_complementaria",
           "base_legal")


class NoEncaja(Exception):
    """A row clashes with what Core has: exit 2, nothing written."""


def _familias():
    with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
        return tuple(json.load(f)["enums"]["familia_infraccion"])


def read_cuis(path):
    """The CSV's rows as the service's body: '#' lines are its header comment, an empty cell is left out, familia
    defaults to ADMINISTRATIVA and codigo is upper-cased."""
    with open(path, encoding="utf-8") as f:
        rows = csv.DictReader(line for line in f if not line.startswith("#"))
        filas = [{k: r[k].strip() for k in COLUMNS if r.get(k) and r[k].strip()} for r in rows]
    for fila in filas:
        fila.setdefault("familia", FAMILIA)
        if "codigo" in fila:
            fila["codigo"] = fila["codigo"].upper()
    return filas


def clave(fila):
    return f"{fila['familia']}|{fila['codigo']}|{fila['vigencia_desde']}"


def _decimal(valor):
    try:
        return Decimal(str(valor))
    except InvalidOperation:
        return None


def _fecha(valor):
    try:
        return date.fromisoformat(str(valor))
    except ValueError:
        return None


def _errores_de(i, fila, familias):
    nombre = f"fila {i + 1} ({fila.get('codigo', '?')})"
    malas = [f"{nombre}: falta {c}" for c in REQUIRED if c not in fila]
    if fila["familia"] not in familias:
        malas.append(f"{nombre}: la familia es una de {', '.join(familias)}")
    for campo, largo in LARGOS.items():
        if len(fila.get(campo, "")) > largo:
            malas.append(f"{nombre}: {campo} tiene más de {largo} caracteres")
    for campo in PORCENTAJES:
        if campo in fila:
            valor = _decimal(fila[campo])
            if valor is None or not valor.is_finite() or not Decimal(0) < valor <= PORCENTAJE_MAXIMO:
                malas.append(f"{nombre}: {campo} es una alícuota mayor que 0 y hasta {PORCENTAJE_MAXIMO}")
    if "vigencia_desde" in fila and _fecha(fila["vigencia_desde"]) is None:
        malas.append(f"{nombre}: vigencia_desde no es una fecha AAAA-MM-DD")
    if "observacion" in fila and not OBSERVACION[0] <= len(fila["observacion"]) <= OBSERVACION[1]:
        malas.append(f"{nombre}: la observacion tiene {len(fila['observacion'])} caracteres; van de {OBSERVACION[0]} a {OBSERVACION[1]}")
    return malas


def errores(filas):
    """What does not fit in the file alone, as text. What depends on Core is checked by plan."""
    familias = _familias()
    malas = []
    for i, fila in enumerate(filas):
        malas += _errores_de(i, fila, familias)
    if not malas:
        vistas = [clave(f) for f in filas]
        malas += [f"la versión {c} está repetida" for c in sorted({c for c in vistas if vistas.count(c) > 1})]
    return malas


def _igual(guardado, deseado):
    if guardado in (None, "") or deseado in (None, ""):
        return guardado in (None, "") and deseado in (None, "")
    a, b = _decimal(guardado), _decimal(deseado)
    if a is not None and b is not None:
        return a == b
    return str(guardado) == str(deseado)


def plan(registros, filas, completo=False):
    """(create, close, skipped, derogate): the rows to send, in order, (stored clave, vigencia_hasta) of each version
    they close and, with completo, (familia, codigo, vigencia_hasta) of each code in force the file does not bring.
    NoEncaja when a row clashes with Core."""
    por_clave = {r["attributes"].get("clave"): r["attributes"] for r in registros}
    vigentes = {a["clave_vigente"]: a for a in por_clave.values() if a.get("clave_vigente")}
    crear, cerrar, iguales, choques = [], [], 0, []
    for fila in sorted(filas, key=lambda f: (f["familia"], f["codigo"], f["vigencia_desde"])):
        guardado = por_clave.get(clave(fila))
        if guardado is not None:
            distintos = [c for c in VALORES if not _igual(guardado.get(c), fila.get(c))]
            if distintos:
                choques.append(f"la versión {clave(fila)} ya existe con otro {', '.join(distintos)}: un cambio es una versión "
                               "nueva, con otra vigencia_desde")
            else:
                iguales += 1
            continue
        codigo = f"{fila['familia']}|{fila['codigo']}"
        vigente = vigentes.get(codigo)
        desde = _fecha(fila["vigencia_desde"])
        if vigente is not None:
            if _fecha(vigente["vigencia_desde"]) >= desde:
                choques.append(f"la versión {clave(fila)} no es posterior a la vigente, que rige desde {vigente['vigencia_desde']}")
                continue
            cerrar.append((vigente["clave"], (desde - timedelta(days=1)).isoformat()))
        crear.append(fila)
        vigentes[codigo] = {"clave": clave(fila), "vigencia_desde": fila["vigencia_desde"]}
    derogar = []
    if completo:
        desdes = sorted({f["vigencia_desde"] for f in filas})
        if len(desdes) != 1:
            raise NoEncaja([f"--completo pide una sola vigencia_desde en el archivo; trae {', '.join(desdes) or 'ninguna'}"])
        hasta = _fecha(desdes[0]) - timedelta(days=1)
        traidos = {f"{f['familia']}|{f['codigo']}" for f in filas}
        for codigo, a in sorted(vigentes.items()):
            if codigo in traidos:
                continue
            if _fecha(a["vigencia_desde"]) > hasta:
                choques.append(f"{codigo} rige desde {a['vigencia_desde']}: no se deroga el {hasta.isoformat()}, antes de empezar")
                continue
            familia, cod = codigo.split("|", 1)
            derogar.append((familia, cod, hasta.isoformat()))
    if choques:
        raise NoEncaja(choques)
    return crear, cerrar, iguales, derogar


def cargar(client, filas, dry_run=False, completo=False):
    """Sends each new version, in order, then each derogation. Returns (created, closed, skipped, derogated)."""
    crear, cerrar, iguales, derogar = plan(client.list_all(OBJECT), filas, completo)
    for anterior, hasta in cerrar:
        print(f"  close  {anterior}: vigencia_hasta {hasta}")
    for fila in crear:
        print(f"  create {clave(fila)}: {fila['porcentaje_uit']}% UIT")
    for familia, codigo, hasta in derogar:
        print(f"  derogate {familia}|{codigo}: vigencia_hasta {hasta}")
    if not dry_run:
        for fila in crear:
            client.post(ENDPOINT, fila)
        for familia, codigo, hasta in derogar:
            client.post(DEROGACION, {"familia": familia, "codigo": codigo, "vigencia_hasta": hasta})
    return len(crear), len(cerrar), iguales, len(derogar)


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Load the CUIS (codigo_infraccion) into wasichai Core through srtm's service.")
    p.add_argument("--csv", required=True, help="familia,codigo,descripcion,...,vigencia_desde,observacion")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--completo", action="store_true",
                   help="the file is the whole CUIS from its vigencia_desde: derogate the codes in force it does not bring")
    p.add_argument("--dry-run", action="store_true", help="read Core and say what it would do; write nothing")
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    filas = read_cuis(args.csv)
    print(f"versiones: {len(filas)}")
    malas = errores(filas)
    if malas:
        for e in malas:
            print(f"error: {e}", file=sys.stderr)
        return 2
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        creadas, cerradas, iguales, derogadas = cargar(client, filas, args.dry_run, args.completo)
    except NoEncaja as e:
        for choque in e.args[0]:
            print(f"error: {choque}", file=sys.stderr)
        return 2
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    if args.dry_run:
        print(f"dry run: {creadas} to create, {cerradas} to close, {iguales} skipped, {derogadas} to derogate; nothing written")
    else:
        print(f"{OBJECT}: {creadas} created, {cerradas} closed, {iguales} skipped, {derogadas} derogated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
