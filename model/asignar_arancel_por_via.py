#!/usr/bin/env python3
"""The DJ of a year take the arancel of their predio's vía (#226), once Normativa's aranceles of that year are
VIGENTE (aranceles_desde_padron.py, importar, verificar).

srtm does it on every recálculo of a DJ without id_aranc (DeclaracionService.arancelDeLaVia): the VIGENTE arancel of
the year whose cod_via is the predio's codigo_via, the same value on every tramo of the vía; never for a rústico
predio, never for one marked varios_codigos_via, never another year's. This script walks the vigente DJ of --anio,
works out the same on what it reads (the aranceles of the year, the predios) and prints how many take a tramo, how
many of those change value against the arancel they have (the padrón's: expected ≈ 0) and why the rest take none.
Without --dry-run it POSTs /recalcular to each DJ that takes one: srtm resolves it, stores its id_aranc and
recalculates its autoavalúo. When a year's value changes (annulled and registered again), run it again: the DJ with
id_aranc whose arancel is no longer their tramo's VIGENTE value are recalculated too (valor_nuevo). The DJ with
id_aranc and no VIGENTE arancel of their tramo in the year are only counted. The DJ of other years are not touched.

--varios-codigos takes the CSV aranceles_desde_padron.py writes (the regla para varios códigos de vía) and applies
it to the predios already imported, before anything is recalculated: a varios_codigos_mismo_valor predio gets
codigo_via = its codigo_elegido (the most frequent); a varios_codigos_valores_distintos one is marked
varios_codigos_via, and its DJ are never recalculated. The DJ that had already taken a tramo there (a marked predio,
or an id_aranc that is not the new codigo_via) go a_revisar: counted, listed, not recalculated. Run it right after a
deploy or a padrón import, before anyone edits. With --dry-run, nothing is written: only counted.

One DJ per condominio is recalculated: srtm's pass gives its tramo to the other titulares without one (and a titular
that names a tramo gives that one to the condominio: toma_el_tramo_del_condominio). The condominios run across
--workers.

Stdlib only. Only counts are printed; --reporte (outside the repo) lists each DJ's id and fate, also after a failure.

Run: python3 -I asignar_arancel_por_via.py --org ica --anio 2026 --varios-codigos ~/x.varios_codigos.csv --dry-run
Exit: 0 ok, 1 Core refused something (or did not answer) or a DJ recalculated did not keep its tramo, 2 bad
arguments or the login is of another organization (nothing is written).
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
VARIOS = "varios_codigos_via"
# the rows of aranceles_desde_padron.py's --varios-codigos (import_padron_ica.regla_varios_codigos)
MISMO_VALOR = "varios_codigos_mismo_valor"
VALORES_DISTINTOS = "varios_codigos_valores_distintos"
# a DJ's fate
TOMA = "toma"
RUSTICA = "rustica"
MARCADA = VALORES_DISTINTOS
SIN_CODIGO = "sin_codigo_de_via"
SIN_ARANCEL = "sin_arancel_del_anio"
DISTINTOS = "tramos_distintos"
VALOR_NUEVO = "valor_nuevo"
CON_ID_SIN_ARANCEL = "con_id_aranc_sin_arancel_del_anio"
TOMA_DEL_CONDOMINIO = "toma_el_tramo_del_condominio"
A_REVISAR = "a_revisar"
# the fates that are recalculated
RECALCULAN = (TOMA, TOMA_DEL_CONDOMINIO, VALOR_NUEVO)


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
    lowest id_aranc (and id) when every tramo has one value; None when they differ. cod_via as stored (srtm filters
    it exactly); a tramo without valor_m2 gives no arancel (SIN_ARANCEL), as srtm leaves the DJ."""
    tramos = {}
    for r in sorted(aranceles, key=lambda r: (str(r["attributes"].get("id_aranc") or ""), r["id"])):
        a = r["attributes"]
        cod_via = a.get("cod_via")
        if a.get("estado") not in VIGENTE or cod_via in (None, ""):
            continue
        tramos.setdefault(cod_via, []).append((a.get("id_aranc"), _decimal(a.get("valor_m2"))))
    resultado = {}
    for cod_via, lista in tramos.items():
        valores = {None if v is None else v.normalize() for _, v in lista}
        if len(valores) > 1:
            resultado[cod_via] = None
        elif lista[0][0] and lista[0][1] is not None:
            resultado[cod_via] = lista[0]
    return resultado


def destino(declaracion, predio, vias):
    """A DJ's fate: (TOMA, id_aranc, valor) or (another fate, None, None)."""
    predio = predio or {}
    if predio.get("tipo_predio") == RUSTICO:
        return RUSTICA, None, None
    if predio.get(VARIOS) is True:
        return MARCADA, None, None
    cod_via = _texto(predio.get("codigo_via"))
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


def _secuencia(valor):
    """secuencia_uso as srtm compares it (Reglas.secuenciaUso): blank is 1, digits to three."""
    texto = str(valor or "").strip() or "1"
    return texto.zfill(3) if texto.isdigit() else texto


def condominio_de(attributes):
    """A DJ's condominio, as DeclaracionService.titulares groups it: its predio, year and secuencia de uso."""
    return attributes.get("predio"), str(attributes.get("anio")), _secuencia(attributes.get("secuencia_uso"))


def plan(declaraciones, predios, aranceles, revisar=None):
    """Every vigente DJ of the year with its fate (rows of REPORTE):
    - without id_aranc: destino, but a DJ whose condominio has a titular that names a tramo takes that one
      (TOMA_DEL_CONDOMINIO: an explicit id_aranc wins, DeclaracionService.arancelDeLaVia);
    - with id_aranc on a predio of `revisar` (predio id -> the code it should have, or None when it is marked): A_REVISAR,
      never recalculated (the regla para varios códigos de vía came after it took its tramo);
    - with id_aranc: VALOR_NUEVO when their tramo has another VIGENTE value now, CON_ID_SIN_ARANCEL when it has none this
      year (only counted).
    And how many had id_aranc."""
    revisar = revisar or {}
    vias = por_via(aranceles)
    tramos = por_tramo(aranceles)
    predio_de = {p["id"]: p["attributes"] for p in predios}
    sin, con = candidatas(declaraciones)
    explicito = {}
    for r in sorted(con, key=lambda r: r["id"]):
        explicito.setdefault(condominio_de(r["attributes"]), _texto(r["attributes"].get("id_aranc")))
    filas = []
    for r in sin:
        a = r["attributes"]
        del_condominio = explicito.get(condominio_de(a))
        if del_condominio is not None:
            cual, id_aranc, valor = TOMA_DEL_CONDOMINIO, del_condominio, tramos.get(del_condominio)
        else:
            cual, id_aranc, valor = destino(a, predio_de.get(a.get("predio")), vias)
        filas.append({"declaracion": r["id"], "destino": cual, "id_aranc": id_aranc, "arancel": _decimal(a.get("arancel")),
                      "valor_via": valor})
    for r in con:
        a = r["attributes"]
        id_aranc = _texto(a.get("id_aranc"))
        valor = tramos.get(id_aranc)
        arancel = _decimal(a.get("arancel"))
        if a.get("predio") in revisar and revisar[a.get("predio")] != id_aranc:
            filas.append({"declaracion": r["id"], "destino": A_REVISAR, "id_aranc": id_aranc, "arancel": arancel,
                          "valor_via": revisar[a.get("predio")]})
        elif valor is None:
            filas.append({"declaracion": r["id"], "destino": CON_ID_SIN_ARANCEL, "id_aranc": id_aranc, "arancel": arancel,
                          "valor_via": None})
        elif _distinto(arancel, valor):
            filas.append({"declaracion": r["id"], "destino": VALOR_NUEVO, "id_aranc": id_aranc, "arancel": arancel,
                          "valor_via": valor})
    return filas, len(con)


def por_condominio(filas, declaraciones):
    """The DJ to recalculate in groups: the ones that take a tramo by condominio (one recálculo takes it for every
    titular without one: arancelDeLaVia), each VALOR_NUEVO on its own (its recálculo is its own). [(the DJ recalculated,
    the rows it covers)]."""
    grupo_de = {r["id"]: condominio_de(r["attributes"]) for r in declaraciones}
    grupos, solas = {}, []
    for f in filas:
        if f["destino"] in (TOMA, TOMA_DEL_CONDOMINIO):
            grupos.setdefault(grupo_de.get(f["declaracion"], f["declaracion"]), []).append(f)
        elif f["destino"] == VALOR_NUEVO:
            solas.append([f])
    return [(g[0], g) for g in list(grupos.values()) + solas]


def revisar_de(cambios, predios):
    """The predios whose DJ with id_aranc go A_REVISAR: the ones marked (None) and the ones whose codigo_via the regla
    changes (the new code), after `cambios`."""
    por_id = dict(cambios)
    resultado = {}
    for p in predios:
        a = {**p["attributes"], **por_id.get(p["id"], {})}
        if a.get(VARIOS) is True:
            resultado[p["id"]] = None
        elif "codigo_via" in por_id.get(p["id"], {}):
            resultado[p["id"]] = a["codigo_via"]
    return resultado


def resumen(filas):
    """A Counter of the fates, plus cambian: of the ones that take a tramo, those whose stored arancel differs from it
    (none stored counts as a change)."""
    cuentas = Counter(f["destino"] for f in filas)
    cuentas["cambian"] = sum(1 for f in filas if f["destino"] == TOMA and _distinto(f["arancel"], f["valor_via"]))
    return cuentas


def leer_varios(path):
    """aranceles_desde_padron.py's --varios-codigos: its rows (codigo_predio, regla, codigo_elegido...)."""
    with open(os.path.expanduser(path), newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def cambios_varios(varios, predios):
    """What the regla para varios códigos de vía changes in the predios Core holds, found by their codigo: [(predio id,
    the attributes to merge)], and a Counter (codigo_via, marcados, iguales, no_encontrados). A MISMO_VALOR predio
    takes codigo_elegido (and loses a mark it had); a VALORES_DISTINTOS one is marked varios_codigos_via. One already
    so counts as iguales."""
    por_codigo = {p["attributes"].get("codigo"): p for p in predios}
    cambios, cuentas = [], Counter()
    for fila in varios:
        predio = por_codigo.get(fila.get("codigo_predio"))
        if predio is None:
            cuentas["no_encontrados"] += 1
            continue
        a = predio["attributes"]
        cambio = {}
        if fila.get("regla") == MISMO_VALOR and fila.get("codigo_elegido"):
            cuenta = "codigo_via"
            if a.get("codigo_via") != fila["codigo_elegido"]:
                cambio["codigo_via"] = fila["codigo_elegido"]
            if a.get(VARIOS) is True:
                cambio[VARIOS] = False
        elif fila.get("regla") == VALORES_DISTINTOS:
            cuenta = "marcados"
            if a.get(VARIOS) is not True:
                cambio[VARIOS] = True
        else:
            continue
        if cambio:
            cambios.append((predio["id"], cambio))
            cuentas[cuenta] += 1
        else:
            cuentas["iguales"] += 1
    return cambios, cuentas


def aplicar_en_memoria(predios, cambios):
    """The predios as Core leaves them after `cambios`: a dry-run plans with them too."""
    por_id = dict(cambios)
    return [{**p, "attributes": {**p["attributes"], **por_id.get(p["id"], {})}} for p in predios]


def dentro_del_repo(path):
    real = os.path.realpath(os.path.expanduser(path))
    return os.path.commonpath([real, os.path.realpath(REPO)]) == os.path.realpath(REPO)


def _es_de(organizacion, org):
    """The login's organization (GET /api/organizations/current) is org, by its slug or its id, in any case."""
    return org.strip().lower() in {str(organizacion.get("slug") or "").lower(), str(organizacion.get("id") or "").lower()}


def escribir_reporte(path, filas):
    path = os.path.expanduser(path)
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=REPORTE)
        writer.writeheader()
        writer.writerows(filas)


# ---------------------------------------------------------------------------
# Core calls
# ---------------------------------------------------------------------------

class Progreso:
    """How many predios were changed, recálculos answered, and DJ left with their tramo: read after a failure too."""

    def __init__(self):
        self.predios = 0
        self.hechas = 0
        self.tomaron = 0


def cambiar_predios(client, cambios, progreso):
    """PATCHes each predio of the regla para varios códigos de vía, counting into `progreso` as they are sent."""
    for predio_id, cambio in cambios:
        client.patch(f"/api/objects/predio/records/{predio_id}", {"attributes": cambio})
        progreso.predios += 1


def recalcular_todas(client, grupos, workers, progreso):
    """POSTs /recalcular for one DJ of each group (por_condominio), the groups in parallel, counting into `progreso` as
    they answer: the group's rows are done, and they took their tramo when the DJ recalculated did. The first failure
    (a refusal, or no answer) stops the rest and is raised."""
    def una(grupo):
        fila, filas = grupo
        _, d = client.post(f"/api/srtm/declaraciones/{fila['declaracion']}/recalcular", None)
        return len(filas), (d or {}).get("id_aranc") == fila["id_aranc"]

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(una, g) for g in grupos]
        try:
            for future in as_completed(futures):
                n, tomo = future.result()
                progreso.hechas += n
                progreso.tomaron += n if tomo else 0
        except Exception:
            for future in futures:
                future.cancel()
            raise


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_args(argv):
    p = argparse.ArgumentParser(description="The DJ of a year take the arancel of their predio's vía (#226).")
    p.add_argument("--org", required=True, help="the organization (its slug or id): the login must be of it")
    p.add_argument("--anio", type=int, default=2026, help="year of the declaraciones (default 2026)")
    p.add_argument("--dry-run", action="store_true", help="read and count; write nothing, recalculate none")
    p.add_argument("--workers", type=int, default=4, help="parallel recálculos (default 4)")
    p.add_argument("--varios-codigos", help="aranceles_desde_padron.py's CSV of the predios with several códigos de vía")
    p.add_argument("--reporte", help="a CSV of each DJ's id and fate: outside the repo")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    return p.parse_args(argv)


def _imprimir(args, organizacion, filas, con_id, cuentas, varios):
    print(f"organización: {organizacion.get('slug')}, año: {args.anio}{' (dry-run)' if args.dry_run else ''}")
    if varios is not None:
        print(f"varios códigos de vía: codigo_via al más frecuente {varios['codigo_via']}, marcados "
              f"{varios['marcados']}, ya estaban {varios['iguales']}, no encontrados {varios['no_encontrados']}")
    sin_id = len(filas) - cuentas[VALOR_NUEVO] - cuentas[CON_ID_SIN_ARANCEL] - cuentas[A_REVISAR]
    print(f"declaraciones sin id_aranc: {sin_id} (con id_aranc ya: {con_id}, de ellas con un valor nuevo del año: "
          f"{cuentas[VALOR_NUEVO]}, con id_aranc sin arancel VIGENTE del año: {cuentas[CON_ID_SIN_ARANCEL]})")
    print(f"toman el arancel de su vía: {cuentas[TOMA]}, cambian de valor: {cuentas['cambian']}; "
          f"toman el tramo de su condominio: {cuentas[TOMA_DEL_CONDOMINIO]}")
    print(f"a revisar: {cuentas[A_REVISAR]} (con id_aranc en predios marcados o cuyo codigo_via cambió: no se recalculan)")
    print(f"sin tramo: rústicas {cuentas[RUSTICA]}, varios códigos con valores distintos {cuentas[MARCADA]}, "
          f"sin código de vía {cuentas[SIN_CODIGO]}, sin arancel del año {cuentas[SIN_ARANCEL]}, "
          f"tramos con valores distintos {cuentas[DISTINTOS]}")


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)
    if args.workers < 1:
        print("error: --workers es 1 o más", file=sys.stderr)
        return 2
    if args.reporte and dentro_del_repo(args.reporte):
        print(f"error: {args.reporte} está dentro del repo: escríbalo fuera", file=sys.stderr)
        return 2
    try:
        varios = leer_varios(args.varios_codigos) if args.varios_codigos else None
    except OSError as e:
        print(f"error: no se lee --varios-codigos: {e}", file=sys.stderr)
        return 2

    client = Client(args.core)
    filas, progreso, recalcular, cambios = [], Progreso(), [], []
    try:
        client.login(args.email, args.password)
        organizacion = client.get("/api/organizations/current") or {}
        if not _es_de(organizacion, args.org):
            print(f"error: el usuario es de la organización {organizacion.get('slug')!r}, no de {args.org!r}: no se escribe nada",
                  file=sys.stderr)
            return 2
        predios = client.list_all("predio")
        cuentas_varios = None
        if varios is not None:
            cambios, cuentas_varios = cambios_varios(varios, predios)
            if not args.dry_run:
                cambiar_predios(client, cambios, progreso)
        revisar = revisar_de(cambios, predios)
        predios = aplicar_en_memoria(predios, cambios)
        declaraciones = client.list_all("declaracion_predial", anio=args.anio)
        filas, con_id = plan(declaraciones, predios, client.list_all("arancel", anio=args.anio), revisar)
        cuentas = resumen(filas)
        _imprimir(args, organizacion, filas, con_id, cuentas, cuentas_varios)
        recalcular = [f for f in filas if f["destino"] in RECALCULAN]
        grupos = por_condominio(recalcular, declaraciones)
        print(f"a recalcular: {len(recalcular)} DJ en {len(grupos)} recálculos (uno por condominio)")
        if not args.dry_run:
            recalcular_todas(client, grupos, args.workers, progreso)
    except Exception as e:
        if cambios and progreso.predios < len(cambios) and not args.dry_run:
            print(f"predios cambiados {progreso.predios} de {len(cambios)}")
        if recalcular:
            print(f"recalculadas {progreso.hechas} de {len(recalcular)}, quedaron con su tramo: {progreso.tomaron}")
        detalle = f"{e.status}\n{e.body}" if isinstance(e, CoreError) else f"{type(e).__name__}: {e}"
        print(f"error -> {detalle}", file=sys.stderr)
        return 1
    finally:
        if args.reporte and filas:
            escribir_reporte(args.reporte, filas)
            print(f"reporte: {len(filas)} filas -> {args.reporte}")

    if not args.dry_run:
        print(f"recalculadas {progreso.hechas} de {len(recalcular)}, quedaron con su tramo: {progreso.tomaron}")
    return 0 if args.dry_run or progreso.tomaron == len(recalcular) else 1


if __name__ == "__main__":
    sys.exit(main())
