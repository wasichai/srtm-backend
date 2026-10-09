#!/usr/bin/env python3
"""Derives next year's CSV that import_parametros.py --csv reads, by reajuste del IPC (art. 69-B of the TUO de la
Ley de Tributación Municipal; art. 8 of Ord. 2386-MML) of a base year's arbitrios parameters: no figure is typed
by hand (#88).

Of the base --csv, it takes every TIPOS_ARBITRIO row vigente al 31/12 of --desde (the row of each (tipo, clave)
with the latest vigencia_desde <= that 31/12; a row that starts later, or one whose vigencia_hasta is before that
31/12, is ignored). Each one becomes a row for the
year after --desde, with vigencia_desde = 01/01 of that year:
- TASA_ARBITRIO: valor_numerico x (1 + --ipc/100), rounded per --redondeo (mirrors srtm.arbitrios.Servicios'
  ABAJO/MEDIO/ARRIBA: DOWN/HALF_UP/UP); texto = "DERIVADA IPC <ipc>% de <clave> <anio>" (anio is --desde).
- Every other type (ARBITRIO_ZONA, ARBITRIO_USO, ARBITRIO_INFLUENCIA, ARBITRIO_AFLUENCIA, BASE_ARBITRIO,
  DIMENSIONES_ARBITRIO, HABITANTES_PROMEDIO/VARIACION, REDONDEO_ARBITRIO, ARBITRIO_VENCIMIENTO, TOPE_ARBITRIO) is
  copied as is (same clave, valor_numerico and texto), except ARBITRIO_VENCIMIENTO's texto and TOPE_ARBITRIO's
  texto when it is a date: both are shifted one year forward (29 February -> 28 February when the next year is
  not leap). --redondeo only rounds the reajuste's TASA_ARBITRIO values; REDONDEO_ARBITRIO's own row is copied
  untouched.
Every derived row cites the base row (its clave and --desde, in fuente) and carries the reajuste's own norma,
transcribio and verifico (--norma/--transcribio/--verifico), not the base row's: import_parametros.errores needs
two different signatures on every row, and the row now rules under the reajuste's own norm.

Nothing is sent anywhere: this only writes --salida, and only after import_parametros.errores passes on it.

Run: python3 derivar_ipc_arbitrio.py --csv parametros-2026.csv --desde 2026 --ipc 2.10 \
     --norma "OM 012-2027-MDP" --redondeo 4:MEDIO --transcribio "ANA, 2027-01-05" --verifico "BETO, 2027-01-09" \
     --salida parametros-2027.csv
Exit: 0 ok, 2 --redondeo or --ipc is missing or malformed, or the CSV built does not fit import_parametros.errores
      (nothing written).
"""
import argparse
import os
import re
import sys
from datetime import date
from decimal import ROUND_DOWN, ROUND_HALF_UP, ROUND_UP, Decimal, InvalidOperation

import import_parametros

# mirrors srtm.arbitrios.Servicios.MODOS (Kotlin): ABAJO -> DOWN, MEDIO -> HALF_UP, ARRIBA -> UP
MODOS = {"ABAJO": ROUND_DOWN, "MEDIO": ROUND_HALF_UP, "ARRIBA": ROUND_UP}
REDONDEO_RE = re.compile(r"(\d):(ABAJO|MEDIO|ARRIBA)")
FECHA_TIPOS = (import_parametros.ARBITRIO_VENCIMIENTO, import_parametros.TOPE_ARBITRIO)


def _fecha(texto):
    try:
        return date.fromisoformat(texto)
    except (TypeError, ValueError):
        return None


def mas_un_anio(fecha_iso):
    """AAAA-MM-DD shifted one year forward; 29 February -> 28 February when the next year is not leap."""
    d = date.fromisoformat(fecha_iso)
    try:
        return d.replace(year=d.year + 1).isoformat()
    except ValueError:
        return d.replace(year=d.year + 1, day=28).isoformat()


def parse_redondeo(valor):
    """"d:MODO" -> (decimales, modo de Decimal.quantize), o ValueError."""
    m = REDONDEO_RE.fullmatch((valor or "").strip())
    if not m:
        raise ValueError(f"«{valor}» no es decimales:modo (ABAJO, MEDIO o ARRIBA)")
    return int(m.group(1)), MODOS[m.group(2)]


def vigentes_al_31_12(filas, anio):
    """One row per (tipo, clave) of TIPOS_ARBITRIO: the one with the latest vigencia_desde <= AAAA-12-31 of `anio`
    among those still in force that day, in the order its group first appears in `filas`. A row that starts later,
    one closed before that day (vigencia_hasta < AAAA-12-31), or one whose vigencia_desde does not parse, is
    ignored: a closed row neither is derived nor hides an earlier open one of its (tipo, clave)."""
    corte = date(anio, 12, 31)
    mejor, orden = {}, []
    for f in filas:
        if f.get("tipo") not in import_parametros.TIPOS_ARBITRIO:
            continue
        desde = _fecha(f.get("vigencia_desde"))
        if desde is None or desde > corte:
            continue
        hasta = _fecha(f.get("vigencia_hasta"))
        if hasta is not None and hasta < corte:
            continue
        llave = (f["tipo"], f.get("clave") or "")
        if llave not in mejor:
            orden.append(llave)
            mejor[llave] = f
        elif desde > _fecha(mejor[llave].get("vigencia_desde")):
            mejor[llave] = f
    return [mejor[llave] for llave in orden]


def generar(filas, anio, ipc, decimales, modo, norma, transcribio, verifico):
    """The rows of the year after `anio`, derived from `filas`' TIPOS_ARBITRIO rows vigentes al 31/12 de `anio`
    (see vigentes_al_31_12). `ipc` is kept as given (a string) so the citation prints exactly what was passed."""
    vigencia_desde = date(anio + 1, 1, 1).isoformat()
    factor = 1 + Decimal(str(ipc)) / 100
    escala = Decimal(1).scaleb(-decimales)
    parametros = []
    for f in vigentes_al_31_12(filas, anio):
        clave = f.get("clave") or ""
        cita = f"DERIVADA IPC {ipc}% de {clave} {anio}"
        fila = {"tipo": f["tipo"], "clave": clave, "vigencia_desde": vigencia_desde, "norma": norma,
                "fuente": cita, "transcribio": transcribio, "verifico": verifico}
        if f["tipo"] == import_parametros.TASA_ARBITRIO:
            try:
                valor = Decimal(str(f.get("valor_numerico")))
            except InvalidOperation:
                valor = None
            if valor is not None:
                fila["valor_numerico"] = str((valor * factor).quantize(escala, rounding=modo))
            fila["texto"] = cita
        else:
            if f.get("valor_numerico"):
                fila["valor_numerico"] = f["valor_numerico"]
            texto = f.get("texto") or ""
            if texto and f["tipo"] in FECHA_TIPOS and _fecha(texto):
                texto = mas_un_anio(texto)
            if texto:
                fila["texto"] = texto
        parametros.append(fila)
    return parametros


def escribir(path, parametros, cabecera):
    import csv
    with open(path, "w", encoding="utf-8", newline="") as f:
        for linea in cabecera:
            f.write(linea + "\n")
        w = csv.DictWriter(f, fieldnames=import_parametros.FIELDS, lineterminator="\n")
        w.writeheader()
        for p in parametros:
            w.writerow({k: p.get(k, "") for k in import_parametros.FIELDS})


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Derive next year's arbitrios parameters CSV by reajuste del IPC "
                                             "of a base year's, art. 69-B del TUO de la Ley de Tributación "
                                             "Municipal.")
    p.add_argument("--csv", required=True, help="parametros del año base (lo que import_parametros.py --csv lee)")
    p.add_argument("--desde", required=True, type=int, help="el año base (AAAA)")
    p.add_argument("--ipc", required=True, help="el %% de reajuste, p. ej. 2.10")
    p.add_argument("--norma", required=True, help="la ordenanza del reajuste")
    p.add_argument("--redondeo", help="decimales:modo (ABAJO, MEDIO o ARRIBA), obligatorio: ninguna ordenanza lo "
                                       "publica")
    p.add_argument("--transcribio", required=True)
    p.add_argument("--verifico", required=True)
    p.add_argument("--salida", required=True)
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    if not args.redondeo:
        print("error: --redondeo es obligatorio", file=sys.stderr)
        return 2
    try:
        decimales, modo = parse_redondeo(args.redondeo)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    try:
        Decimal(str(args.ipc))
    except InvalidOperation:
        print(f"error: --ipc «{args.ipc}» no es un número", file=sys.stderr)
        return 2
    filas = import_parametros.read_parametros(args.csv)
    parametros = generar(filas, args.desde, args.ipc, decimales, modo, args.norma, args.transcribio, args.verifico)
    malas = import_parametros.errores(parametros)
    if malas:
        for e in malas:
            print(f"error: {e}", file=sys.stderr)
        return 2
    escribir(args.salida, parametros,
             [f"# Derivado de {os.path.basename(args.csv)} con derivar_ipc_arbitrio.py (IPC {args.ipc}% desde "
              f"{args.desde})"])
    print(f"parámetros: {len(parametros)} (de {len(filas)} del año base)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
