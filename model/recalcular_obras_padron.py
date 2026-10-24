#!/usr/bin/env python3
"""Dry-run of the recálculo for the DJ whose autoavalúo still counts the padrón's otras instalaciones as a fixed
amount (#208). While a DJ has no obras of its own, Derivados.conObrasDelPadron adds that figure to catastro's
valuación; once the DJ is recalculated (POST /recalcular) and catastro values its own obras, valor_autoavaluo (and
valor_obras) may change. This script never writes by itself: it reads the sugerido of every vigente DJ with
valor_otras_instalaciones > 0, prints how many would change and by how much, and writes a report. Only with
--dry-run off does it POST /recalcular, one per DJ that changes.

Stdlib only. See README.md («Cargar la Municipalidad de Ica» → «Recalcular con las obras del padrón»).

Run: python3 -I recalcular_obras_padron.py --anio 2026 --dry-run
Exit: 0 ok, 1 Core refused something.
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

REPORT_COLUMNS = ["declaracion", "valor_autoavaluo", "sugerido", "delta", "motivo"]
UN_SOL = Decimal("1.00")
UN_CENTIMO = Decimal("0.01")
# blank estado or VIGENTE both count as vigente, as the importers load it (model.json, declaracion_predial.estado)
VIGENTE = (None, "", "VIGENTE")


def default_report_path():
    return os.path.join(HERE, "reports", "obras_padron.csv")


# ---------------------------------------------------------------------------
# pure
# ---------------------------------------------------------------------------

def _decimal(value):
    """A Core number (JSON float, string or Decimal) as Decimal; None stays None."""
    return None if value is None else Decimal(str(value))


def clasificar(guardado, sugerido):
    """The DJ's stored autoavalúo against its sugerido: "cambia" (|Δ| >= 0.01), "igual" or "sin_valor" (catastro did
    not value it)."""
    valor = _decimal(sugerido)
    if valor is None:
        return "sin_valor"
    return "cambia" if abs(valor - _decimal(guardado)) >= UN_CENTIMO else "igual"


def resumen(filas):
    """con_obras (every DJ read), valuadas (catastro answered), cambian, suma_delta (of the ones that cambian) and
    a_un_sol (of the valuadas, how many the motor reproduces within ±S/ 1 of the guardado: s10.md's own check of
    the motor against the SAT), and sin_valor, a Counter of catastro's motivo for the ones it did not value."""
    valuadas = cambian = a_un_sol = 0
    suma_delta = Decimal("0.00")
    sin_valor = Counter()
    for fila in filas:
        clasificacion = clasificar(fila["valor_autoavaluo"], fila["sugerido"])
        if clasificacion == "sin_valor":
            sin_valor[fila.get("motivo")] += 1
            continue
        valuadas += 1
        delta = _decimal(fila["sugerido"]) - _decimal(fila["valor_autoavaluo"])
        if abs(delta) <= UN_SOL:
            a_un_sol += 1
        if clasificacion == "cambia":
            cambian += 1
            suma_delta += delta
    return {"con_obras": len(filas), "valuadas": valuadas, "cambian": cambian, "suma_delta": suma_delta,
            "a_un_sol": a_un_sol, "sin_valor": sin_valor}


def con_obras(declaraciones):
    """The vigente DJ (blank estado or VIGENTE) with otras instalaciones del padrón: candidates for the recálculo."""
    result = []
    for r in declaraciones:
        attributes = r["attributes"]
        valor = _decimal(attributes.get("valor_otras_instalaciones"))
        if attributes.get("estado") in VIGENTE and valor is not None and valor > 0:
            result.append(r)
    return result


def fila_de(registro, sugerido):
    """One row: the stored and suggested autoavalúo, the delta between them and catastro's motivo when it did not
    value it. Only the declaración's id and amounts: never a name nor a document."""
    guardado = _decimal(registro["attributes"].get("valor_autoavaluo"))
    autoavaluo = _decimal((sugerido or {}).get("autoavaluo"))
    return {
        "declaracion": registro["id"],
        "valor_autoavaluo": guardado,
        "sugerido": autoavaluo,
        "delta": autoavaluo - guardado if autoavaluo is not None and guardado is not None else None,
        "motivo": (sugerido or {}).get("motivo"),
    }


def write_report(path, filas):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=REPORT_COLUMNS)
        writer.writeheader()
        writer.writerows(filas)


# ---------------------------------------------------------------------------
# Core calls
# ---------------------------------------------------------------------------

def consultar_todos(client, ids, workers):
    """GET the sugerido of every id, in parallel; id -> its ValuacionDeInsumos dict, or None (404). The first
    refusal (not a 404) stops the rest."""
    resultados = {}
    if not ids:
        return resultados

    def one(id):
        return id, client.get(f"/api/srtm/declaraciones/{id}/autoavaluo-sugerido")

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, id) for id in ids]
        try:
            for future in as_completed(futures):
                id, respuesta = future.result()
                resultados[id] = respuesta
        except CoreError:
            for future in futures:
                future.cancel()
            raise
    return resultados


def recalcular_todos(client, ids, workers):
    """POSTs /recalcular for every id, in parallel. The first refusal stops the rest."""
    if not ids:
        return

    def one(id):
        client.post(f"/api/srtm/declaraciones/{id}/recalcular", None)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, id) for id in ids]
        try:
            for future in as_completed(futures):
                future.result()
        except CoreError:
            for future in futures:
                future.cancel()
            raise


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_args(argv):
    p = argparse.ArgumentParser(description="Dry-run of the recálculo for the DJ con obras del padrón de origen (#208).")
    p.add_argument("--anio", type=int, default=2026, help="year of the declaraciones (default 2026)")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read the sugerido of every DJ; call /recalcular on none")
    p.add_argument("--workers", type=int, default=4, help="parallel GETs/POSTs (default 4)")
    p.add_argument("--report", default=default_report_path())
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        declaraciones = con_obras(client.list_all("declaracion_predial", anio=args.anio))
        sugeridos = consultar_todos(client, [r["id"] for r in declaraciones], args.workers)
        filas = [fila_de(r, sugeridos[r["id"]]) for r in declaraciones]
        resultado = resumen(filas)
        if not args.dry_run:
            cambian = [f["declaracion"] for f in filas if clasificar(f["valor_autoavaluo"], f["sugerido"]) == "cambia"]
            recalcular_todos(client, cambian, args.workers)
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1

    write_report(args.report, filas)
    print(f"con_obras: {resultado['con_obras']}, valuadas: {resultado['valuadas']}, cambian: {resultado['cambian']}, "
          f"suma_delta: S/ {resultado['suma_delta']}, a_un_sol: {resultado['a_un_sol']} (a ±S/ 1 del guardado)")
    sin_valor = resultado["con_obras"] - resultado["valuadas"]
    if sin_valor:
        print(f"sin_valor: {sin_valor}")
        for motivo, n in sorted(resultado["sin_valor"].items(), key=lambda kv: kv[0] or ""):
            print(f"  {motivo}: {n}")
    print(f"reporte: {len(filas)} filas -> {args.report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
