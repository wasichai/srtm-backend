#!/usr/bin/env python3
"""The DJ of a year take the arancel of their predio's vía (#226), once Normativa's aranceles of that year are
VIGENTE (aranceles_desde_padron.py, importar, verificar).

srtm does it on every recálculo of a DJ without id_aranc (DeclaracionService.arancelDeLaVia): the VIGENTE arancel of
the year whose cod_via is the predio's codigo_via, the same value on every tramo of the vía; never for a rústico
predio, never another year's. This script walks the vigente DJ of --anio without id_aranc, works out the same on
what it reads (the aranceles of the year, the predios) and prints how many take a tramo, how many of those change
value against the arancel they have (the padrón's: expected ≈ 0) and why the rest take none. Without --dry-run it
POSTs /recalcular to each DJ that takes one: srtm resolves it, stores its id_aranc and recalculates its autoavalúo.
When a year's value changes (annulled and registered again), run it again: the DJ with id_aranc whose arancel is no
longer their tramo's VIGENTE value are recalculated too, and take the new one. The DJ of other years are not touched.

Stdlib only. Only counts are printed; --reporte (outside the repo) lists each DJ's id and fate.

Run: python3 -I asignar_arancel_por_via.py --org ica --anio 2026 --dry-run
Exit: 0 ok, 1 Core refused something or a DJ recalculated did not keep its tramo, 2 bad arguments or the login is
of another organization (nothing is written).
"""
import argparse
import csv
import os
import sys
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
# under python3 -I the script's own directory is put back, for core_client
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from core_client import Client, CoreError  # noqa: E402

REPO = os.path.dirname(HERE)
REPORTE = ["declaracion", "destino", "id_aranc", "arancel", "valor_via"]
# blank estado or VIGENTE: a vigente DJ (declaracion_predial.estado) and a ruling arancel (arancel.estado)
VIGENTE = (None, "", "VIGENTE")
RUSTICO = "PREDIO RUSTICO"
# a DJ's fate
TOMA = "toma"
RUSTICA = "rustica"
SIN_CODIGO = "sin_codigo_de_via"
SIN_ARANCEL = "sin_arancel_del_anio"
DISTINTOS = "tramos_distintos"
VALOR_NUEVO = "valor_nuevo"


# ---------------------------------------------------------------------------
# pure
# ---------------------------------------------------------------------------

def _decimal(x):
    return None if x is None or x == "" else Decimal(str(x))


def _texto(x):
    t = "" if x is None else str(x).strip()
    return t or None


def por_via(aranceles):
    """The ruling aranceles of a year by cod_via -> (id_aranc, valor_m2), as ArancelesService.deLaVia picks it: the
    lowest id_aranc (and id) when every tramo has one value; None when they differ."""
    tramos = {}
    for r in sorted(aranceles, key=lambda r: (str(r["attributes"].get("id_aranc") or ""), r["id"])):
        a = r["attributes"]
        cod_via = _texto(a.get("cod_via"))
        if a.get("estado") not in VIGENTE or cod_via is None or not a.get("id_aranc"):
            continue
        tramos.setdefault(cod_via, []).append((a["id_aranc"], _decimal(a.get("valor_m2"))))
    resultado = {}
    for cod_via, lista in tramos.items():
        valores = {v for _, v in lista}
        resultado[cod_via] = lista[0] if len(valores) == 1 else None
    return resultado


def destino(declaracion, predio, vias):
    """A DJ's fate: (TOMA, id_aranc, valor) or (another fate, None, None)."""
    if (predio or {}).get("tipo_predio") == RUSTICO:
        return RUSTICA, None, None
    cod_via = _texto((predio or {}).get("codigo_via"))
    if cod_via is None:
        return SIN_CODIGO, None, None
    if cod_via not in vias:
        return SIN_ARANCEL, None, None
    tramo = vias[cod_via]
    if tramo is None:
        return DISTINTOS, None, None
    return TOMA, tramo[0], tramo[1]


def candidatas(declaraciones):
    """The vigente DJ: (without id_aranc, the ones a recálculo may give a tramo; with it)."""
    sin, con = [], []
    for r in declaraciones:
        a = r["attributes"]
        if a.get("estado") not in VIGENTE:
            continue
        (con if _texto(a.get("id_aranc")) else sin).append(r)
    return sin, con


def por_tramo(aranceles):
    """The ruling aranceles of a year by id_aranc -> valor_m2: of two, the lowest id (ArancelesService.vigente)."""
    resultado = {}
    for r in sorted(aranceles, key=lambda r: r["id"]):
        a = r["attributes"]
        if a.get("estado") in VIGENTE and a.get("id_aranc"):
            resultado.setdefault(a["id_aranc"], _decimal(a.get("valor_m2")))
    return resultado


def _distinto(arancel, valor):
    return arancel is None or arancel.compare(valor) != 0


def plan(declaraciones, predios, aranceles):
    """Every vigente DJ of the year the script may recalculate, with its fate (rows of REPORTE): the ones without
    id_aranc, and those with one whose tramo has another VIGENTE value now (VALOR_NUEVO, the year's value changed).
    And how many had id_aranc."""
    vias = por_via(aranceles)
    tramos = por_tramo(aranceles)
    predio_de = {p["id"]: p["attributes"] for p in predios}
    sin, con = candidatas(declaraciones)
    filas = []
    for r in sin:
        a = r["attributes"]
        cual, id_aranc, valor = destino(a, predio_de.get(a.get("predio")), vias)
        filas.append({"declaracion": r["id"], "destino": cual, "id_aranc": id_aranc, "arancel": _decimal(a.get("arancel")),
                      "valor_via": valor})
    for r in con:
        a = r["attributes"]
        id_aranc = _texto(a.get("id_aranc"))
        valor = tramos.get(id_aranc)
        if valor is not None and _distinto(_decimal(a.get("arancel")), valor):
            filas.append({"declaracion": r["id"], "destino": VALOR_NUEVO, "id_aranc": id_aranc,
                          "arancel": _decimal(a.get("arancel")), "valor_via": valor})
    return filas, len(con)


def resumen(filas):
    """A Counter of the fates, plus cambian: of the ones that take a tramo, those whose stored arancel differs from it
    (none stored counts as a change)."""
    cuentas = Counter(f["destino"] for f in filas)
    cuentas["cambian"] = sum(1 for f in filas if f["destino"] == TOMA and _distinto(f["arancel"], f["valor_via"]))
    return cuentas


def dentro_del_repo(path):
    real = os.path.realpath(os.path.expanduser(path))
    return os.path.commonpath([real, os.path.realpath(REPO)]) == os.path.realpath(REPO)


def _es_de(organizacion, org):
    """The login's organization (GET /api/organizations/current) is org, by its slug or its id, in any case."""
    return org.strip().lower() in {str(organizacion.get("slug") or "").lower(), str(organizacion.get("id") or "").lower()}


# ---------------------------------------------------------------------------
# Core calls
# ---------------------------------------------------------------------------

def recalcular_todas(client, filas, workers):
    """POSTs /recalcular for every DJ that takes a tramo (or its new value), in parallel: how many srtm left with that
    id_aranc. The first refusal stops the rest."""
    def una(fila):
        _, d = client.post(f"/api/srtm/declaraciones/{fila['declaracion']}/recalcular", None)
        return (d or {}).get("id_aranc") == fila["id_aranc"]

    tomaron = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(una, f) for f in filas]
        try:
            for future in as_completed(futures):
                tomaron += 1 if future.result() else 0
        except CoreError:
            for future in futures:
                future.cancel()
            raise
    return tomaron


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_args(argv):
    p = argparse.ArgumentParser(description="The DJ of a year take the arancel of their predio's vía (#226).")
    p.add_argument("--org", required=True, help="the organization (its slug or id): the login must be of it")
    p.add_argument("--anio", type=int, default=2026, help="year of the declaraciones (default 2026)")
    p.add_argument("--dry-run", action="store_true", help="read and count; recalculate none")
    p.add_argument("--workers", type=int, default=4, help="parallel recálculos (default 4)")
    p.add_argument("--reporte", help="a CSV of each candidate DJ's id and fate: outside the repo")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)
    if args.workers < 1:
        print("error: --workers es 1 o más", file=sys.stderr)
        return 2
    if args.reporte and dentro_del_repo(args.reporte):
        print(f"error: {args.reporte} está dentro del repo: escríbalo fuera", file=sys.stderr)
        return 2

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        organizacion = client.get("/api/organizations/current") or {}
        if not _es_de(organizacion, args.org):
            print(f"error: el usuario es de la organización {organizacion.get('slug')!r}, no de {args.org!r}: no se escribe nada",
                  file=sys.stderr)
            return 2
        filas, con_id = plan(client.list_all("declaracion_predial", anio=args.anio), client.list_all("predio"),
                             client.list_all("arancel", anio=args.anio))
        cuentas = resumen(filas)
        toman = [f for f in filas if f["destino"] in (TOMA, VALOR_NUEVO)]
        tomaron = None if args.dry_run else recalcular_todas(client, toman, args.workers)
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1

    if args.reporte:
        path = os.path.expanduser(args.reporte)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=REPORTE)
            writer.writeheader()
            writer.writerows(filas)
    print(f"organización: {organizacion.get('slug')}, año: {args.anio}{' (dry-run)' if args.dry_run else ''}")
    print(f"declaraciones sin id_aranc: {len(filas) - cuentas[VALOR_NUEVO]} (con id_aranc ya: {con_id}, "
          f"de ellas con un valor nuevo del año: {cuentas[VALOR_NUEVO]})")
    print(f"toman el arancel de su vía: {cuentas[TOMA]}, cambian de valor: {cuentas['cambian']}")
    print(f"sin tramo: rústicas {cuentas[RUSTICA]}, sin código de vía {cuentas[SIN_CODIGO]}, "
          f"sin arancel del año {cuentas[SIN_ARANCEL]}, tramos con valores distintos {cuentas[DISTINTOS]}")
    if tomaron is not None:
        print(f"recalculadas: {len(toman)}, quedaron con su tramo: {tomaron}")
    if args.reporte:
        print(f"reporte: {len(filas)} filas -> {args.reporte}")
    return 0 if tomaron is None or tomaron == len(toman) else 1


if __name__ == "__main__":
    sys.exit(main())
