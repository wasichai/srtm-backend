#!/usr/bin/env python3
"""Moves the SAT codes of distinct predios that a load before #207 left in Core at 100/n to their <codigo>-<n> (#219).

import_padron_ica.py splits a SAT code that groups distinct predios (address, area or value differ) into one predio
per distinct predio, <codigo>-<n>, each with the SAT code in codigo_origen; but it skips a code whose bare predio Core
already has (codigo_compartido_en_core), so those keep their DJ at 100/n. This script reads the same padrón, groups it
the same way (build_dataset) and, for every such code:

- creates the predios <codigo>-<n> Core lacks, with the importer's attributes (one that exists is reused);
- moves every vigente DJ of the bare predio, any year, to its titular's <codigo>-<n>: the same record, so its niveles,
  obras and frentes follow it, with the condición and % of its group (propietario único at 100 % when it holds the
  predio alone) and valor_condominio and valor_afecto as Condominio.kt computes them; a PUT of every field, as Core's
  update replaces them all;
- recalculates each moved DJ (POST /api/srtm/declaraciones/{id}/recalcular);
- gives the bare predio de baja (fecha_baja today and motivo_baja, by a PATCH that keeps the rest): never deleted.

A code with a DJ whose contribuyente is no titular of the code in the padrón, with a derived code the padrón uses for
another predio (codigo_derivado_repetido), or a derived predio in Core of another codigo_origen is left as it is and
reported. A real condominio (one predio, several titulares) is no shared code and is never read. The cuotas
(cuota_predial) and the emisión masiva of --anio already made are listed, never changed: the administración decides
on a rectificación de oficio. So are the arbitrio cuotas (cuota_arbitrio) of --anio of the bare predios: they hang
from the predio and the year, so the move leaves them there and the derived predios have none until someone
redetermines them (POST /api/srtm/predios/{id}/arbitrios/redeterminacion).

stdout carries counts only: no names, documents nor contribuyente codes. The report (--report, a CSV) has the codes.
Idempotent: a second run finds every code de baja and changes nothing.

Stdlib only. See README.md («Cargar la Municipalidad de Ica» → «Migrar los códigos compartidos ya cargados»).

Run: python3 -I migrar_codigos_compartidos.py --excel PADRON_CONSTRUCCION.xlsx --core <url> --anio 2026 --dry-run
Exit: 0 ok, 1 Core refused something, 2 Core's predio (or model.json) lacks a field it fills (nothing is written).
"""
import argparse
import csv
import os
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
# under python3 -I (the xlsx is untrusted) only the script's own directory is put back, for its siblings
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from core_client import Client, CoreError  # noqa: E402
from import_padron_ica import (  # noqa: E402
    build_dataset, default_codigos_path, default_depreciacion_path, default_model_path, default_usos_path, load_codigos,
    load_model, read_depreciacion, read_usos_srtm, reparto, unknown_fields, valores_condominio,
)
from import_predios import LoadError, post_all, read_xlsx  # noqa: E402
from normalizar_padron import Update, put_all  # noqa: E402
from recalcular_obras_padron import recalcular_todos  # noqa: E402

REPORT_COLUMNS = ["categoria", "codigo_predio", "codigo_contribuyente", "anio", "detalle"]
CAMPOS_BAJA = ("fecha_baja", "motivo_baja")
# blank estado or VIGENTE both count as vigente, as the importers load it (model.json, declaracion_predial.estado)
VIGENTE = (None, "", "VIGENTE")
CERO = Decimal("0.00")
CENTIMO = Decimal("0.01")
# why a code is not migrated
SIN_TITULAR = "contribuyente_sin_titular"
REPETIDO = "codigo_derivado_repetido"
OTRO_ORIGEN = "predio_derivado_de_otro_origen"
RECHAZADO = "predio_derivado_rechazado"


def default_report_path():
    return os.path.join(HERE, "reports", "codigos_compartidos.csv")


# ---------------------------------------------------------------------------
# pure
# ---------------------------------------------------------------------------

def _decimal(value):
    """A Core number (JSON float, string or Decimal) as Decimal; empty is 0."""
    return Decimal(str(value)) if value not in (None, "") else CERO


def valores(porcentaje, autoavaluo, deduccion):
    """(valor_condominio, valor_afecto) as Condominio.kt: the % of the autoavalúo to the cent, half up, and what the
    deducción leaves of it, never below 0."""
    valor, _ = valores_condominio(None if autoavaluo is None else str(autoavaluo), porcentaje)
    if valor is None:
        return None, None
    return valor, str(max(Decimal(valor) - _decimal(deduccion), CERO).quantize(CENTIMO))


def motivo_baja(codigo, derivados):
    return (f"Código del SAT con predios distintos (#207): sus DJ pasan a {derivados[0]} … {derivados[-1]}"
            if len(derivados) > 1 else f"Código del SAT con predios distintos (#207): sus DJ pasan a {derivados[0]}")


@dataclass
class Movida:
    """A DJ of the bare predio and where it goes."""
    record: dict
    codigo_contribuyente: str
    derivado: str
    attributes: dict  # every field, as the PUT sends them, but the predio's id (known once it exists)


@dataclass
class Codigo:
    codigo: str
    bare: dict
    estado: str  # migrable | no_migrable | ya_migrado
    derivados: list
    motivos: Counter = field(default_factory=Counter)
    crear: list = field(default_factory=list)  # derived codes Core lacks
    reusar: dict = field(default_factory=dict)  # derived code -> its id in Core
    movidas: list = field(default_factory=list)
    recalcular_ya: list = field(default_factory=list)  # vigentes DJ already on a derived predio (an earlier run)


def planear(data, predios, contribuyentes, declaraciones, codigos):
    """What to do with each shared code of the padrón whose bare predio Core has. predios: codigo -> record;
    contribuyentes: id -> codigo; declaraciones: predio id -> its records. Pure: reads nothing, calls nothing."""
    plan = []
    for c in data.compartidos:
        code, derivados = c["codigo"], c["predios"]
        bare = predios.get(code)
        if bare is None:
            continue
        item = Codigo(code, bare, "migrable", derivados)
        plan.append(item)
        if bare["attributes"].get("fecha_baja"):
            item.estado = "ya_migrado"
            continue
        pares = {k: v for k, v in data.srtm.items() if k[0] == code}
        if any(v is None for v in pares.values()):
            item.motivos[REPETIDO] += 1
        for derivado in derivados:
            existente = predios.get(derivado)
            if existente is not None and existente["attributes"].get("codigo_origen") != code:
                item.motivos[OTRO_ORIGEN] += 1
            elif derivado not in data.predios and derivado in pares.values():
                item.motivos[RECHAZADO] += 1
            elif existente is not None:
                item.reusar[derivado] = existente["id"]
                item.recalcular_ya += [r["id"] for r in declaraciones.get(existente["id"], [])
                                       if r["attributes"].get("estado") in VIGENTE]
            else:
                item.crear.append(derivado)
        for record in declaraciones.get(bare["id"], []):
            a = record["attributes"]
            if a.get("estado") not in VIGENTE:
                continue
            contribuyente = contribuyentes.get(a.get("contribuyente"))
            if (code, contribuyente) not in pares:
                item.motivos[SIN_TITULAR] += 1
                continue
            derivado = pares[(code, contribuyente)]
            if derivado is None:
                continue
            titulares = data.titulares[derivado]
            orden = list(titulares)
            condicion, porcentaje = reparto(titulares[contribuyente].get("condicion_predio"), orden.index(contribuyente),
                                            len(orden), codigos)
            valor_condominio, valor_afecto = valores(porcentaje, a.get("valor_autoavaluo"), a.get("deduccion"))
            item.movidas.append(Movida(record, contribuyente, derivado, {
                **a, "condicion_propiedad": condicion, "porcentaje_condominio": str(porcentaje),
                "valor_condominio": valor_condominio, "valor_afecto": valor_afecto}))
        if item.motivos:
            item.estado = "no_migrable"  # nothing of it is written: migrar and the summary take only the migrables
    return plan


def delta_base(plan):
    """anio -> (antes, después): the sum of the valor_afecto of the DJ to move."""
    por_anio = defaultdict(lambda: [CERO, CERO])
    for item in plan:
        for m in item.movidas:
            anio = str(m.record["attributes"].get("anio"))
            por_anio[anio][0] += _decimal(m.record["attributes"].get("valor_afecto"))
            por_anio[anio][1] += _decimal(m.attributes.get("valor_afecto"))
    return {anio: tuple(v) for anio, v in por_anio.items()}


def filas_reporte(plan, contribuyentes, cuotas, emisiones, arbitrios=()):
    filas = []

    def fila(categoria, predio=None, contribuyente=None, anio=None, detalle=""):
        filas.append({"categoria": categoria, "codigo_predio": predio, "codigo_contribuyente": contribuyente,
                      "anio": anio, "detalle": detalle})

    for item in plan:
        if item.estado == "ya_migrado":
            fila("ya_migrado", item.codigo, detalle=f"dado de baja el {item.bare['attributes'].get('fecha_baja')}")
            continue
        if item.estado == "no_migrable":
            fila("no_migrable", item.codigo, detalle="; ".join(f"{m}: {n}" for m, n in sorted(item.motivos.items())))
            continue
        for derivado in item.crear:
            fila("predio_a_crear", derivado, detalle=f"codigo_origen {item.codigo}")
        for m in item.movidas:
            a, nuevo = m.record["attributes"], m.attributes
            fila("dj_movida", m.derivado, m.codigo_contribuyente, a.get("anio"),
                 f"de {item.codigo} ({a.get('condicion_propiedad')} {a.get('porcentaje_condominio')} %, afecto "
                 f"{a.get('valor_afecto')}) a {m.derivado} ({nuevo['condicion_propiedad']} {nuevo['porcentaje_condominio']} %, "
                 f"afecto {nuevo['valor_afecto']})")
        fila("predio_baja", item.codigo, detalle=motivo_baja(item.codigo, item.derivados))
    for c in cuotas:
        a = c["attributes"]
        fila("cuota_predial", None, contribuyentes.get(a.get("contribuyente")), a.get("anio"),
             f"cuota {a.get('numero')}, revisión {a.get('revision')}, monto {a.get('monto')}, afecto {a.get('valor_afecto')}: "
             "no se toca")
    codigo_de = {i.bare["id"]: i.codigo for i in plan}
    for c in arbitrios:
        a = c["attributes"]
        fila("cuota_arbitrio", codigo_de.get(a.get("predio")), contribuyentes.get(a.get("contribuyente")), a.get("anio"),
             f"periodo {a.get('periodo')}, servicio {a.get('servicio')}, monto {a.get('monto')}: queda en el predio dado de "
             "baja; los derivados no tienen arbitrios hasta redeterminarlos")
    for e in emisiones:
        a = e["attributes"]
        fila("emision_masiva", anio=a.get("anio"), detalle=f"{e['id']} {a.get('estado')}: no se toca")
    return filas


def write_report(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=REPORT_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


# ---------------------------------------------------------------------------
# Core calls
# ---------------------------------------------------------------------------

def faltan_campos(client):
    """The baja fields Core's predio lacks (model.json not applied yet)."""
    nombres = {f.get("name") for f in client.get("/api/metadata/objects/predio/fields") or []}
    return [c for c in CAMPOS_BAJA if c not in nombres]


def leer(client):
    predios = {}
    for r in client.list_all("predio"):
        predios.setdefault(r["attributes"].get("codigo"), r)
    contribuyentes = {r["id"]: r["attributes"].get("codigo") for r in client.list_all("contribuyente")}
    declaraciones = defaultdict(list)
    for r in client.list_all("declaracion_predial"):
        declaraciones[r["attributes"].get("predio")].append(r)
    return predios, contribuyentes, declaraciones


def liquidaciones(client, pares, workers):
    """(contribuyente id, anio) -> its impuestoAnual (Decimal), or None when srtm does not liquidate it (no endpoint,
    parámetros missing)."""
    def one(par):
        contribuyente, anio = par
        respuesta = client.get(f"/api/srtm/contribuyentes/{contribuyente}/liquidacion?anio={anio}")
        if not respuesta or respuesta.get("faltan") or respuesta.get("impuestoAnual") is None:
            return par, None
        return par, Decimal(str(respuesta["impuestoAnual"]))

    if not pares:
        return {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return dict(f.result() for f in as_completed([pool.submit(one, p) for p in pares]))


def dar_de_baja(client, items, workers):
    """PATCHes fecha_baja and motivo_baja on each bare predio: a merge, so its lote and the rest stay."""
    hoy = date.today().isoformat()

    def one(item):
        try:
            client.patch(f"/api/objects/predio/records/{item.bare['id']}",
                         {"attributes": {"fecha_baja": hoy, "motivo_baja": motivo_baja(item.codigo, item.derivados)}})
        except CoreError as e:
            raise LoadError("predio", item.codigo, e)

    if not items:
        return
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, i) for i in items]
        try:
            for future in as_completed(futures):
                future.result()
        except LoadError:
            for future in futures:
                future.cancel()
            raise


def migrar(client, data, plan, workers):
    """Creates the derived predios, moves and recalculates the DJ, gives the bare predios de baja. Returns the counts."""
    migrables = [i for i in plan if i.estado == "migrable"]
    crear = [(d, data.filas[("predio", d)], data.predios[d]) for i in migrables for d in i.crear]
    ids = post_all(client, "predio", crear, workers)
    for i in migrables:
        i.reusar.update({d: ids[d] for d in i.crear})
    updates = [Update("declaracion_predial", m.record["id"], m.record["id"], {**m.attributes, "predio": i.reusar[m.derivado]})
               for i in migrables for m in i.movidas]
    put_all(client, updates, workers)
    recalcular = [m.record["id"] for i in migrables for m in i.movidas] + [d for i in migrables for d in i.recalcular_ya]
    recalcular_todos(client, recalcular, workers)
    dar_de_baja(client, migrables, workers)
    return len(crear), len(updates), len(recalcular), len(migrables)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _parse_args(argv):
    p = argparse.ArgumentParser(description="Move the shared SAT codes Core holds at 100/n to their <codigo>-<n> (#219).")
    p.add_argument("--excel", required=True, help="path to PADRON_CONSTRUCCION.xlsx (sheet padron)")
    p.add_argument("--anio", type=int, default=2026, help="year of the cuotas and the emisión listed (default 2026)")
    p.add_argument("--model", default=default_model_path())
    p.add_argument("--codigos", default=default_codigos_path(), help="Ica's code tables (default data/ica/codigos.json)")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and say what it would do; write nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel calls (default 4)")
    p.add_argument("--report", default=default_report_path())
    return p.parse_args(argv)


def _resumen(plan, data, cuotas, emisiones, anio, arbitrios=()):
    estados = Counter(i.estado for i in plan)
    motivos = Counter(m for i in plan for m in i.motivos)
    migrables = [i for i in plan if i.estado == "migrable"]
    movidas = [m for i in migrables for m in i.movidas]
    print(f"códigos compartidos: {len(data.compartidos)} en el padrón, {len(plan)} en Core")
    print(f"  migrables: {estados['migrable']}")
    print(f"  ya migrados: {estados['ya_migrado']}")
    print(f"  no migrables: {estados['no_migrable']}")
    for motivo, n in sorted(motivos.items()):
        print(f"    {motivo}: {n}")
    print(f"predios a crear: {sum(len(i.crear) for i in migrables)} ({sum(len(i.reusar) for i in migrables)} ya existen)")
    por_anio = Counter(str(m.record["attributes"].get("anio")) for m in movidas)
    print(f"DJ a mover: {len(movidas)}" + (" (" + ", ".join(f"{a}: {n}" for a, n in sorted(por_anio.items())) + ")" if movidas else ""))
    print(f"DJ a recalcular: {len(movidas) + sum(len(i.recalcular_ya) for i in migrables)}")
    print(f"predios a dar de baja: {len(migrables)}")
    for a, (antes, despues) in sorted(delta_base(plan).items(), reverse=True):
        print(f"Δ base imponible {a}: S/ {antes} -> S/ {despues} (Δ S/ {despues - antes})")
    print(f"cuotas {anio}: {len({c['attributes'].get('contribuyente') for c in cuotas})} contribuyentes afectados con "
          f"{len(cuotas)} cuotas (no se tocan)")
    print(f"cuotas de arbitrio {anio}: {len(arbitrios)} en {len({c['attributes'].get('predio') for c in arbitrios})} predios "
          "dados de baja (no se tocan: redeterminar los derivados)")
    estados_emision = Counter(e["attributes"].get("estado") for e in emisiones)
    print(f"emisión masiva {anio}: {len(emisiones)}" + (" (" + ", ".join(f"{e}: {n}" for e, n in sorted(estados_emision.items())) + ")"
                                                         if emisiones else ""))


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)

    model, codigos = load_model(args.model), load_codigos(args.codigos)
    data = build_dataset(read_xlsx(args.excel, "padron"), [], args.anio, model, codigos,
                         read_usos_srtm(default_usos_path()), read_depreciacion(default_depreciacion_path()))
    errores = data.errores + [f"predio: el modelo no tiene el campo {c}" for c in unknown_fields(model, "predio", CAMPOS_BAJA)]

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        errores += [f"predio: Core no tiene el campo {c}" for c in faltan_campos(client)]
        predios, contribuyentes, declaraciones = leer(client)
        plan = planear(data, predios, contribuyentes, declaraciones, codigos)
        movidas = [m for i in plan if i.estado == "migrable" for m in i.movidas]
        afectados = {m.record["attributes"].get("contribuyente") for m in movidas}
        cuotas = [c for c in client.list_all("cuota_predial", anio=args.anio) if c["attributes"].get("contribuyente") in afectados]
        emisiones = client.list_all("emision_masiva", anio=args.anio)
        # the arbitrios hang from the predio and the year: the move leaves them on the bare predio
        pelados = {i.bare["id"] for i in plan if i.estado == "migrable"}
        arbitrios = [c for c in client.list_all("cuota_arbitrio", anio=args.anio) if c["attributes"].get("predio") in pelados]
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1

    filas = filas_reporte(plan, contribuyentes, cuotas, emisiones, arbitrios)
    write_report(args.report, filas)
    _resumen(plan, data, cuotas, emisiones, args.anio, arbitrios)
    print(f"reporte: {len(filas)} filas -> {args.report}")
    if errores:
        for error in errores:
            print(f"error: {error}", file=sys.stderr)
        print("the model lacks the fields the migration fills (apply model.json): nothing was written to Core", file=sys.stderr)
        return 2
    if args.dry_run:
        return 0

    pares = sorted({(m.record["attributes"].get("contribuyente"), str(m.record["attributes"].get("anio"))) for m in movidas})
    try:
        antes = liquidaciones(client, pares, args.workers)
        creados, movidas_n, recalculadas, bajas = migrar(client, data, plan, args.workers)
        despues = liquidaciones(client, pares, args.workers)
    except LoadError as e:
        print(f"error {e.object_name} {e.fila} -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    por_anio = defaultdict(lambda: [CERO, 0, 0])  # anio -> [Δ, liquidados, sin liquidación]
    for par in pares:
        if antes.get(par) is None or despues.get(par) is None:
            por_anio[par[1]][2] += 1
        else:
            por_anio[par[1]][0] += despues[par] - antes[par]
            por_anio[par[1]][1] += 1
    for a, (delta, n, sin) in sorted(por_anio.items(), reverse=True):
        print(f"Δ impuesto {a}: S/ {delta} ({n} contribuyentes)" + (f", {sin} sin liquidación" if sin else ""))
    print(f"done: {creados} predios creados, {movidas_n} DJ movidas, {recalculadas} recalculadas, {bajas} predios de baja")
    return 0


if __name__ == "__main__":
    sys.exit(main())
