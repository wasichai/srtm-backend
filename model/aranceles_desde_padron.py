#!/usr/bin/env python3
"""The aranceles of a year as the CSV of POST /api/srtm/normativa/aranceles/importar (#226), from one of two sources:

- --excel: Ica's padrón (PADRON_CONSTRUCCION.xlsx, sheet `padron`). Its `arancel` column is the valor arancelario of
  the predio's vía: one arancel per `codigo_de_via` (the SAT code, e.g. 0010000401-01, which is a frente de vía: RM
  514-2025-EF/15). id_aranc = cod_via = that code, read as import_padron_ica.py reads predio.codigo_via, so a predio
  finds it (DeclaracionService.arancelDeLaVia). Only urbano rows (tipo_predio 1): the rústico arancel is per hectárea
  (#209). A 0 or blank arancel is left out. A code with more than one value is a conflict: left out of the CSV and
  listed in --conflictos. A predio whose rows name several codes (one per frente) follows the regla para varios
  códigos de vía (varios_codigos) and is listed in --varios-codigos.
- --copiar-de AÑO: the VIGENTE aranceles of that year in the Core, copied to --anio, to prepare a new year: Normativa
  corrects or verifies them.

The CSV carries no signatures: the importación writes it POR_VERIFICAR, transcribed by whoever imports it, and a
second person verifies it in the Normativa screen (the double signature). It is written to --salida, which must be
outside the repo: the padrón is real data. Only counts are printed.

Stdlib only. See README.md («Cargar la Municipalidad de Ica» → «Aranceles por vía»).

Run: python3 -I aranceles_desde_padron.py --excel PADRON_CONSTRUCCION.xlsx --salida ~/Documents/srtm/ica/aranceles-2026.csv
     python3 -I aranceles_desde_padron.py --copiar-de 2026 --anio 2027 --org ica --salida ~/Documents/srtm/ica/aranceles-2027.csv
Exit: 0 ok, 1 Core refused something, 2 bad arguments or (--copiar-de) the login is of another organization than
--org (nothing is written).
"""
import argparse
import csv
import os
import sys
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP

HERE = os.path.dirname(os.path.abspath(__file__))
# under python3 -I (the xlsx is untrusted) the script's own directory is put back, for core_client and the importers
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from core_client import Client, CoreError  # noqa: E402
from import_padron_ica import MISMO_VALOR, VALORES_DISTINTOS, codigo, codigos_derivados, regla_varios_codigos  # noqa: E402
from import_predios import read_xlsx  # noqa: E402

REPO = os.path.dirname(HERE)
COLUMNAS = ["id_aranc", "anio", "valor_m2", "cod_via", "cuadra", "lado", "norma", "fuente"]
COLUMNAS_CONFLICTO = ["cod_via", "valores", "filas"]
COLUMNAS_VARIOS = ["codigo_predio", "codigos", "valores", "regla", "codigo_elegido"]
URBANO = "1"
RUSTICO = "2"
NORMA_2026 = "RM 514-2025-EF/15"
CENTIMO = Decimal("0.01")
# blank estado or VIGENTE both rule (model.json, arancel.estado)
VIGENTE = (None, "", "VIGENTE")


# ---------------------------------------------------------------------------
# pure
# ---------------------------------------------------------------------------

def valor(texto):
    """The padrón's arancel as Decimal with two decimals (the xlsx carries float noise); None when blank or not a
    number."""
    texto = (texto or "").strip() if isinstance(texto, str) else texto
    if texto in (None, ""):
        return None
    try:
        return Decimal(str(texto)).quantize(CENTIMO, rounding=ROUND_HALF_UP)
    except InvalidOperation:
        return None


def desde_padron(filas):
    """The padrón's rows -> (aranceles, conflictos, cuentas, varios). aranceles: cod_via -> its one valor_m2, of the
    urbano rows with an arancel > 0. conflictos: cod_via -> (its values, its rows' numbers), of the codes with more
    than one. cuentas: a Counter of every row's fate, and of the predios with several codes (MISMO_VALOR,
    VALORES_DISTINTOS). varios: those predios (varios_codigos)."""
    valores = defaultdict(set)
    filas_de = defaultdict(list)
    cuentas = Counter()
    for fila in filas:
        cuentas["filas"] += 1
        tipo = codigo(fila.get("tipo_predio"))
        if tipo == RUSTICO:
            cuentas["rusticas"] += 1
            continue
        if tipo != URBANO:
            cuentas["sin_tipo_urbano"] += 1
            continue
        cod_via = codigo(fila.get("codigo_de_via"))
        if cod_via is None:
            cuentas["sin_codigo_de_via"] += 1
            continue
        v = valor(fila.get("arancel"))
        if v is None or v <= 0:
            cuentas["arancel_cero_o_vacio"] += 1
            continue
        cuentas["urbanas_con_arancel"] += 1
        valores[cod_via].add(v)
        filas_de[cod_via].append(fila.get("_fila"))
    aranceles = {c: next(iter(v)) for c, v in valores.items() if len(v) == 1}
    conflictos = {c: (sorted(v), filas_de[c]) for c, v in valores.items() if len(v) > 1}
    varios = varios_codigos(filas)
    cuentas[MISMO_VALOR] = sum(1 for v in varios if v["regla"] == MISMO_VALOR)
    cuentas[VALORES_DISTINTOS] = sum(1 for v in varios if v["regla"] == VALORES_DISTINTOS)
    return aranceles, conflictos, cuentas, varios


def varios_codigos(filas):
    """The predios (by their srtm code, as import_padron_ica.py gives it: codigos_derivados, #207) whose urbano rows
    name several codigo_de_via, with what the regla para varios códigos de vía does to each
    (import_padron_ica.regla_varios_codigos): MISMO_VALOR takes codigo_elegido, the most frequent;
    VALORES_DISTINTOS is left as it is and marked. Rows of COLUMNAS_VARIOS, sorted by predio: what
    asignar_arancel_por_via.py --varios-codigos applies to the predios already imported."""
    srtm, _ = codigos_derivados(filas)
    grupos = defaultdict(list)
    for fila in filas:
        code = srtm.get((codigo(fila.get("codigo_predio")), codigo(fila.get("codigo_contribuyente"))))
        if code is not None:
            grupos[code].append(fila)
    resultado = []
    for predio, suyas in sorted(grupos.items()):
        cod_via, regla, cuantos, valores = regla_varios_codigos(suyas)
        if regla is None:
            continue
        resultado.append({
            "codigo_predio": predio,
            "codigos": " ".join(f"{c}x{n}" for c, n in sorted(cuantos.items())),
            "valores": " ".join(f"{c}={'/'.join(str(v) for v in sorted(valores[c]))}" for c in sorted(cuantos)),
            "regla": regla,
            "codigo_elegido": cod_via if regla == MISMO_VALOR else "",
        })
    return resultado


def filas_csv(aranceles, anio, norma, fuente):
    """One CSV row per cod_via, sorted by it: id_aranc = cod_via, no cuadra nor lado (the SAT code already is the
    frente)."""
    return [{"id_aranc": c, "anio": anio, "valor_m2": str(v), "cod_via": c, "cuadra": "", "lado": "", "norma": norma,
             "fuente": fuente} for c, v in sorted(aranceles.items())]


def vigentes_de(registros):
    """The VIGENTE aranceles of Core's records (one year's), one per id_aranc: of two (the importers' door may let a
    second in), the lowest id, as ArancelesService.vigente takes it."""
    por_id = {}
    for r in sorted(registros, key=lambda r: r["id"]):
        a = r["attributes"]
        if a.get("estado") not in VIGENTE or not a.get("id_aranc"):
            continue
        por_id.setdefault(a["id_aranc"], a)
    return [por_id[k] for k in sorted(por_id)]


def copia(vigentes, anio, norma, fuente):
    """The aranceles of another year as CSV rows of `anio`: the same tramo and value, the given norma and fuente. One
    without valor_m2 (Normativa requires it) is left out: (rows, how many were)."""
    def texto(x):
        return "" if x is None else str(x)

    filas, sin_valor = [], 0
    for a in vigentes:
        v = valor(texto(a.get("valor_m2")))
        if v is None:
            sin_valor += 1
            continue
        filas.append({"id_aranc": a["id_aranc"], "anio": anio, "valor_m2": str(v), "cod_via": texto(a.get("cod_via")),
                      "cuadra": texto(a.get("cuadra")), "lado": texto(a.get("lado")), "norma": norma, "fuente": fuente})
    return filas, sin_valor


def dentro_del_repo(path):
    """Whether path is in this repo (the CSV is real data: never there)."""
    real = os.path.realpath(os.path.expanduser(path))
    return os.path.commonpath([real, os.path.realpath(REPO)]) == os.path.realpath(REPO)


def escribir(path, columnas, filas):
    path = os.path.expanduser(path)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=columnas)
        writer.writeheader()
        writer.writerows(filas)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_args(argv):
    p = argparse.ArgumentParser(description="The aranceles of a year as Normativa's import CSV (#226).")
    fuente = p.add_mutually_exclusive_group(required=True)
    fuente.add_argument("--excel", help="Ica's padrón (PADRON_CONSTRUCCION.xlsx): its sheet padron")
    fuente.add_argument("--copiar-de", type=int, metavar="AÑO", help="copy the VIGENTE aranceles of that year from the Core")
    p.add_argument("--anio", type=int, default=2026, help="year of the aranceles written (default 2026)")
    p.add_argument("--salida", required=True, help="the CSV written: outside the repo")
    p.add_argument("--conflictos", help="the codes with more than one value (--excel); default: <salida>.conflictos.csv")
    p.add_argument("--varios-codigos", help="the predios with several codigo_de_via (--excel); default: <salida>.varios_codigos.csv")
    p.add_argument("--norma", help=f"the norma of every row (default {NORMA_2026!r} for 2026, blank otherwise)")
    p.add_argument("--org", help="--copiar-de: the organization (its slug or id): the login must be of it")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)
    base = os.path.splitext(os.path.expanduser(args.salida))[0]
    conflictos_path = args.conflictos or base + ".conflictos.csv"
    varios_path = args.varios_codigos or base + ".varios_codigos.csv"
    for path in (args.salida, conflictos_path, varios_path):
        if dentro_del_repo(path):
            print(f"error: {path} está dentro del repo: los aranceles salen de datos reales, escríbalos fuera", file=sys.stderr)
            return 2
    if args.copiar_de is not None and args.copiar_de == args.anio:
        print("error: --copiar-de y --anio son el mismo año", file=sys.stderr)
        return 2
    norma = args.norma if args.norma is not None else (NORMA_2026 if args.anio == 2026 else "")

    if args.excel:
        aranceles, conflictos, cuentas, varios = desde_padron(read_xlsx(args.excel, "padron"))
        filas = filas_csv(aranceles, args.anio, norma, os.path.basename(args.excel))
        escribir(args.salida, COLUMNAS, filas)
        escribir(conflictos_path, COLUMNAS_CONFLICTO,
                 [{"cod_via": c, "valores": " ".join(str(v) for v in vs), "filas": " ".join(str(f) for f in fs)}
                  for c, (vs, fs) in sorted(conflictos.items())])
        escribir(varios_path, COLUMNAS_VARIOS, varios)
        print(f"filas: {cuentas['filas']}, urbanas con arancel: {cuentas['urbanas_con_arancel']}, "
              f"rústicas: {cuentas['rusticas']}, sin tipo urbano: {cuentas['sin_tipo_urbano']}, "
              f"sin código de vía: {cuentas['sin_codigo_de_via']}, arancel 0 o vacío: {cuentas['arancel_cero_o_vacio']}")
        print(f"aranceles: {len(filas)} códigos de vía de {args.anio}, conflictos: {len(conflictos)}")
        print(f"csv: {len(filas)} filas -> {args.salida}")
        if conflictos:
            print(f"conflictos: {len(conflictos)} códigos con más de un valor, fuera del csv -> {conflictos_path}")
        print(f"predios con varios códigos de vía: {len(varios)} ({MISMO_VALOR}: {cuentas[MISMO_VALOR]}, "
              f"{VALORES_DISTINTOS}: {cuentas[VALORES_DISTINTOS]}, a revisar) -> {varios_path}")
        return 0

    if not args.org:
        print("error: --copiar-de pide --org: los aranceles se leen de esa organización", file=sys.stderr)
        return 2
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        organizacion = client.get("/api/organizations/current") or {}
        if args.org.strip().lower() not in {str(organizacion.get("slug") or "").lower(), str(organizacion.get("id") or "").lower()}:
            print(f"error: el usuario es de la organización {organizacion.get('slug')!r}, no de {args.org!r}: no se escribe nada",
                  file=sys.stderr)
            return 2
        vigentes = vigentes_de(client.list_all("arancel", anio=args.copiar_de))
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    filas, sin_valor = copia(vigentes, args.anio, norma, f"copia de los aranceles VIGENTE de {args.copiar_de}")
    escribir(args.salida, COLUMNAS, filas)
    print(f"organización: {organizacion.get('slug')}")
    print(f"aranceles: {len(filas)} VIGENTE de {args.copiar_de}, copiados a {args.anio}, sin valor_m2 (fuera): {sin_valor}")
    print(f"csv: {len(filas)} filas -> {args.salida}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
