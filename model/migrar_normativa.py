#!/usr/bin/env python3
"""Gets an organization's normativa ready for the double signature (épica #186), once, after apply.py:

1. Parámetros: each parametro_tributario without estado (loaded before the field) becomes VIGENTE, and nothing else
   changes. One without its two signatures (verifico empty, or the same person as transcribio) is left alone and
   reported "sin doble firma: revisar en Normativa".
2. Aranceles, only with --catastro-core: catastro-backend's aranceles (its object arancel, the copy it values with)
   come into srtm, which owns them from now on. Each one srtm lacks (by clave, among the aranceles not ANULADO) is
   created VIGENTE, signed transcribio MIGRACION and verifico CATASTRO, fuente "catastro (migración inicial)", and
   then marked sincronizado_en now: it is in catastro already. srtm's rule wants a new arancel without sincronizado_en,
   so the mark is a second write (a PUT of the same fields plus sincronizado_en), which the rule lets through in any
   estado. One equal and VIGENTE in srtm is skipped; one that differs is reported "distinto en srtm: revisar", and one
   equal but POR_VERIFICAR in srtm "POR_VERIFICAR en srtm: verificar en Normativa" (the DJ does not read it until it
   is verified); both are left alone.

Reads everything first, then writes, --workers at a time. Idempotent: a second run writes nothing. Prints counts and
the records to review by their id and key, never a row of the padrón.

Run: python3 migrar_normativa.py --org <slug> [--dry-run]
         [--catastro-core URL --catastro-email E --catastro-password P]
Order: apply.py, then migrar_normativa.py --dry-run, then without --dry-run.
Exit: 0 ok, 1 Core refused something, 2 an input is invalid (the organization is not --org, catastro's options are
incomplete, an arancel of catastro lacks its data): nothing is written.
"""
import argparse
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core_client import Client, CoreError  # noqa: E402
from import_predios import LoadError  # noqa: E402
from normalizar_padron import Update, put_all  # noqa: E402

PARAMETRO = "parametro_tributario"
ARANCEL = "arancel"
VIGENTE = "VIGENTE"
ANULADO = "ANULADO"
TRANSCRIBIO = "MIGRACION"
VERIFICO = "CATASTRO"
FUENTE = "catastro (migración inicial)"
# catastro's arancel (catastro-backend's model.json): what srtm copies to it and compares
DATOS = ("id_aranc", "anio", "valor_m2", "cod_via", "cuadra", "lado")


def _vacio(value):
    return value is None or (isinstance(value, str) and not value.strip())


def _texto(value):
    return None if _vacio(value) else str(value).strip()


def firmante(firma):
    """srtm's ReglaDeDobleFirma: the person is the part before the comma ("ANA, 2026-01-05"), in any case."""
    return (firma or "").split(",")[0].strip().upper()


def plan_parametros(records):
    """Core's parámetros -> the updates (estado VIGENTE, every other field as it is) and those without two signatures."""
    updates, sin_doble_firma = [], []
    for record in records:
        stored = record["attributes"]
        if not _vacio(stored.get("estado")):
            continue
        transcribio, verifico = stored.get("transcribio"), stored.get("verifico")
        if _vacio(transcribio) or _vacio(verifico) or firmante(transcribio) == firmante(verifico):
            sin_doble_firma.append(record)
            continue
        llave = "|".join(str(stored.get(c) or "") for c in ("tipo", "clave", "vigencia_desde"))
        updates.append(Update(PARAMETRO, record["id"], llave, {**stored, "estado": VIGENTE}))
    return updates, sin_doble_firma


def arancel(attributes):
    """An arancel's data as srtm compares it (a number by its value, an empty text None), or a str with what is wrong."""
    id_aranc = _texto(attributes.get("id_aranc"))
    try:
        anio = int(str(attributes.get("anio")).strip())
    except ValueError:
        anio = None
    try:
        valor = Decimal(str(attributes.get("valor_m2")).strip())
    except (InvalidOperation, ValueError):
        valor = None
    if id_aranc is None or anio is None or valor is None or not valor.is_finite():
        return "le falta id_aranc, anio o valor_m2"
    if valor < 0:
        return "valor_m2 es negativo"
    clave = f"{id_aranc}|{anio}"
    if not _vacio(attributes.get("clave")) and str(attributes["clave"]).strip() != clave:
        return f"su clave no es id_aranc|anio ({clave})"
    return {"clave": clave, "id_aranc": id_aranc, "anio": anio, "valor_m2": valor,
            "cod_via": _texto(attributes.get("cod_via")), "cuadra": _texto(attributes.get("cuadra")),
            "lado": _texto(attributes.get("lado"))}


def plan_aranceles(de_catastro, de_srtm):
    """catastro's and srtm's aranceles -> what to create in srtm, how many are equal (and VIGENTE), the claves that
    differ, the claves equal in srtm but POR_VERIFICAR there, and the invalid ones of catastro ((its id, motivo))."""
    vivos = {}
    for record in de_srtm:
        if (record["attributes"].get("estado") or "").strip() == ANULADO:
            continue
        datos = arancel(record["attributes"])
        if isinstance(datos, dict):
            vivos.setdefault(datos["clave"], {**datos, "estado": (record["attributes"].get("estado") or "").strip() or VIGENTE})
    crear, distintos, por_verificar, invalidos, vistas = [], [], [], [], set()
    iguales = 0
    for record in de_catastro:
        datos = arancel(record["attributes"])
        if isinstance(datos, str):
            invalidos.append((record["id"], datos))
            continue
        if datos["clave"] in vistas:
            invalidos.append((record["id"], f"repite la clave {datos['clave']}"))
            continue
        vistas.add(datos["clave"])
        suyo = vivos.get(datos["clave"])
        if suyo is None:
            crear.append(datos)
        elif not all(suyo[c] == datos[c] for c in DATOS):
            distintos.append(datos["clave"])
        elif suyo["estado"] != VIGENTE:
            # the same data, still to verify in srtm: the DJ does not read it until someone does
            por_verificar.append(datos["clave"])
        else:
            iguales += 1
    return crear, iguales, distintos, por_verificar, invalidos


def _atributos(datos):
    """A new arancel of srtm, VIGENTE with the migration's signatures: its data as Core takes it."""
    return {**{k: v for k, v in datos.items() if v is not None}, "valor_m2": str(datos["valor_m2"]),
            "estado": VIGENTE, "transcribio": TRANSCRIBIO, "verifico": VERIFICO, "fuente": FUENTE}


def crear_aranceles(client, aranceles, workers, ahora=None):
    """POSTs each arancel and then PUTs it with sincronizado_en; the first refusal stops it all. Returns how many."""
    sincronizado = (ahora or datetime.now(timezone.utc)).isoformat()

    def one(datos):
        atributos = _atributos(datos)
        try:
            _, creado = client.post(f"/api/objects/{ARANCEL}/records", {"attributes": atributos})
            client.put(f"/api/objects/{ARANCEL}/records/{creado['id']}", {"attributes": {**atributos, "sincronizado_en": sincronizado}})
        except CoreError as e:
            raise LoadError(ARANCEL, datos["clave"], e)

    if not aranceles:
        return 0
    done = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(one, a) for a in aranceles]
        try:
            for future in as_completed(futures):
                future.result()
                done += 1
        except LoadError:
            for future in futures:
                future.cancel()
            raise
    return done


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Pass srtm's parámetros to VIGENTE and bring catastro's aranceles into srtm's Normativa.")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--org", required=True, help="the organization of srtm (its slug or id): the login must be of it")
    p.add_argument("--dry-run", action="store_true", help="read both Cores and print what it would do; change nothing")
    p.add_argument("--workers", type=int, default=4, help="parallel writes (default 4)")
    p.add_argument("--catastro-core", default=os.environ.get("CATASTRO_CORE"), help="catastro-backend's url: its aranceles")
    p.add_argument("--catastro-email", default=os.environ.get("CATASTRO_EMAIL"))
    p.add_argument("--catastro-password", default=os.environ.get("CATASTRO_PASSWORD"))
    return p.parse_args(argv)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = _parse_args(argv)
    if args.workers < 1:
        print("error: --workers es 1 o más", file=sys.stderr)
        return 2
    con_catastro = any([args.catastro_core, args.catastro_email, args.catastro_password])
    if con_catastro and not all([args.catastro_core, args.catastro_email, args.catastro_password]):
        print("error: los aranceles piden --catastro-core, --catastro-email y --catastro-password", file=sys.stderr)
        return 2

    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        organizacion = client.get("/api/organizations/current") or {}
        if args.org.strip().lower() not in {str(organizacion.get("slug") or "").lower(), str(organizacion.get("id") or "").lower()}:
            print(f"error: el usuario es de la organización {organizacion.get('slug')!r}, no de {args.org!r}: no se escribe nada",
                  file=sys.stderr)
            return 2
        updates, sin_doble_firma = plan_parametros(client.list_all(PARAMETRO))
        crear, iguales, distintos, por_verificar, invalidos = [], 0, [], [], []
        if con_catastro:
            catastro = Client(args.catastro_core)
            catastro.login(args.catastro_email, args.catastro_password)
            crear, iguales, distintos, por_verificar, invalidos = plan_aranceles(catastro.list_all(ARANCEL), client.list_all(ARANCEL))

        print(f"organización: {organizacion.get('slug')}")
        print(f"parametros: vigentes {len(updates)}, sin_doble_firma {len(sin_doble_firma)}")
        for record in sin_doble_firma:
            a = record["attributes"]
            print(f"  sin doble firma: revisar en Normativa: {record['id']} {a.get('tipo')} {a.get('clave') or ''} "
                  f"{a.get('vigencia_desde')}".rstrip())
        if con_catastro:
            print(f"aranceles: crear {len(crear)}, iguales {iguales}, distintos {len(distintos)}, por_verificar {len(por_verificar)}")
            for clave in distintos:
                print(f"  distinto en srtm: revisar: {clave}")
            for clave in por_verificar:
                print(f"  POR_VERIFICAR en srtm: verificar en Normativa: {clave}")
        else:
            print("aranceles: sin --catastro-core, no se leen")
        if invalidos:
            for record_id, motivo in invalidos:
                print(f"  arancel inválido en catastro {record_id}: {motivo}", file=sys.stderr)
            print(f"error: {len(invalidos)} aranceles de catastro no sirven: no se escribe nada", file=sys.stderr)
            return 2
        if args.dry_run:
            return 0
        hechos = put_all(client, updates, args.workers)
        creados = crear_aranceles(client, crear, args.workers)
    except LoadError as e:
        print(f"error {e.object_name} {e.fila} -> {e.error.status}\n{e.error.body}", file=sys.stderr)
        return 1
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    print(f"done: {hechos} parámetros VIGENTE, {creados} aranceles creados")
    return 0


if __name__ == "__main__":
    sys.exit(main())
