#!/usr/bin/env python3
"""Loads an ordinance of arbitrios into wasichai Core: the ordinance of a year with its provincial ratification
(ordenanza_arbitrio), the servicios it charges (servicio_arbitrio) and the predios that do not pay a servicio
(inafectacion_arbitrio). Its tasas and the mappings of zona and uso are parametro_tributario rows: import_parametros.py
loads them, from the CSV of the same transcription.

The file is a JSON {ordenanzas: [...], servicios: [...], inafectaciones: [...]}; a key starting with _ is a note. A
servicio names its ordinance by its year ("ordenanza": 2026); an inafectación names its predio by its code and its
servicio by its code. Natural keys: an ordinance by anio, a servicio by codigo, an inafectación by predio, servicio and
vigencia_desde. What Core lacks is created and what differs is updated in place (the file's fields over the stored
ones); nothing is deleted. Everything is checked and resolved before the first write. Idempotent.

No ordinance of Perené is transcribed and verified yet (D-02b): the repo ships no such file.

Run: python3 import_arbitrios.py --archivo arbitrios-2026.json [--dry-run]
Exit: 0 ok, 1 Core refused something, 2 the file is malformed or names what neither it nor Core has (nothing sent).
"""
import argparse
import json
import os
import sys
from datetime import date

from core_client import Client, CoreError

ORDENANZA = "ordenanza_arbitrio"
SERVICIO = "servicio_arbitrio"
INAFECTACION = "inafectacion_arbitrio"
CODIGO_MAX = 20
OBSERVACION = (5, 500)

REQUERIDOS = {
    "ordenanzas": ("anio", "numero"),
    "servicios": ("codigo", "nombre", "vigencia_desde", "ordenanza"),
    "inafectaciones": ("predio", "servicio", "vigencia_desde", "motivo", "observacion"),
}
FECHAS = ("fecha_publicacion", "fecha_ratificacion", "vigencia_desde", "vigencia_hasta")


class NoEncaja(Exception):
    """The file names what neither it nor Core has: exit 2, nothing written."""


def read_archivo(path):
    with open(path, encoding="utf-8") as f:
        datos = json.load(f)
    return {k: [{c: v for c, v in fila.items() if not c.startswith("_")} for fila in datos.get(k) or []] for k in REQUERIDOS}


def _vacio(valor):
    return valor is None or (isinstance(valor, str) and not valor.strip())


def _fecha(valor):
    try:
        return date.fromisoformat(str(valor))
    except ValueError:
        return None


def _errores_de(seccion, i, fila):
    nombre = f"{seccion}[{i}]"
    malas = [f"{nombre}: falta {c}" for c in REQUERIDOS[seccion] if _vacio(fila.get(c))]
    for c in FECHAS:
        if not _vacio(fila.get(c)) and _fecha(fila[c]) is None:
            malas.append(f"{nombre}: {c} no es una fecha AAAA-MM-DD")
    desde, hasta = _fecha(fila.get("vigencia_desde")), _fecha(fila.get("vigencia_hasta"))
    if desde and hasta and hasta < desde:
        malas.append(f"{nombre}: la vigencia termina antes de empezar")
    if seccion == "servicios" and len(str(fila.get("codigo") or "").strip()) > CODIGO_MAX:
        malas.append(f"{nombre}: el código tiene más de {CODIGO_MAX} caracteres")
    if seccion == "inafectaciones" and not _vacio(fila.get("observacion")):
        largo = len(fila["observacion"].strip())
        if not OBSERVACION[0] <= largo <= OBSERVACION[1]:
            malas.append(f"{nombre}: la observacion tiene {largo} caracteres; van de {OBSERVACION[0]} a {OBSERVACION[1]}")
    return malas


def errores(datos):
    """What does not fit in the file alone, as text. What depends on Core (an unknown predio) is checked by plan."""
    malas = []
    for seccion in REQUERIDOS:
        for i, fila in enumerate(datos.get(seccion) or []):
            malas += _errores_de(seccion, i, fila)
    for seccion, clave in (("ordenanzas", "anio"), ("servicios", "codigo")):
        vistos = [str(f.get(clave)).strip() for f in datos.get(seccion) or [] if not _vacio(f.get(clave))]
        malas += [f"{seccion}: {clave} {v} repetido" for v in sorted({v for v in vistos if vistos.count(v) > 1})]
    return malas


def _limpio(fila):
    return {k: (v.strip() if isinstance(v, str) else v) for k, v in fila.items() if not _vacio(v)}


def _cambios(guardado, deseado):
    return [k for k, v in deseado.items() if str(guardado.get(k)) != str(v)]


def _plan_de(registros, filas, clave):
    """(create, update, skipped) of one object: update is (record, merged attributes)."""
    por_clave = {clave(r["attributes"]): r for r in registros}
    crear, actualizar = [], []
    for fila in filas:
        r = por_clave.get(clave(fila))
        if r is None:
            crear.append(fila)
        elif _cambios(r["attributes"], fila):
            actualizar.append((r, {**r["attributes"], **fila}))
    return crear, actualizar, len(filas) - len(crear) - len(actualizar)


def _predios(client, codigos):
    encontrados = {}
    for codigo in sorted(codigos):
        filas = client.list_all("predio", codigo=codigo)
        if filas:
            encontrados[codigo] = filas[0]["id"]
    return encontrados


def cargar(client, datos, dry_run=False):
    """Creates and updates in dependency order. Returns (created, updated, skipped). NoEncaja before any write."""
    ordenanzas = [_limpio(f) for f in datos["ordenanzas"]]
    servicios_archivo = [_limpio(f) for f in datos["servicios"]]
    inafectaciones_archivo = [_limpio(f) for f in datos["inafectaciones"]]

    guardadas = client.list_all(ORDENANZA)
    servicios_core = client.list_all(SERVICIO)
    anios = {str(r["attributes"].get("anio")) for r in guardadas} | {str(f["anio"]) for f in ordenanzas}
    codigos = {r["attributes"].get("codigo") for r in servicios_core} | {f["codigo"] for f in servicios_archivo}
    predios = _predios(client, {f["predio"] for f in inafectaciones_archivo})
    faltan = [f"servicio {f['codigo']}: no hay ordenanza de {f['ordenanza']}" for f in servicios_archivo if str(f["ordenanza"]) not in anios]
    faltan += [f"inafectación del predio {f['predio']}: no existe el predio" for f in inafectaciones_archivo if f["predio"] not in predios]
    faltan += [f"inafectación del predio {f['predio']}: no existe el servicio {f['servicio']}" for f in inafectaciones_archivo
               if f["servicio"] not in codigos]
    if faltan:
        raise NoEncaja(faltan)

    total = [0, 0, 0]

    def escribir(objeto, registros, filas, clave):
        crear, actualizar, iguales = _plan_de(registros, filas, clave)
        for fila in crear:
            print(f"  create {objeto} {clave(fila)}")
        for r, fila in actualizar:
            print(f"  update {objeto} {clave(fila)}: {', '.join(_cambios(r['attributes'], fila))}")
        if not dry_run:
            for fila in crear:
                client.post(f"/api/objects/{objeto}/records", {"attributes": fila})
            for r, fila in actualizar:
                client.put(f"/api/objects/{objeto}/records/{r['id']}", {"attributes": fila})
        total[0] += len(crear)
        total[1] += len(actualizar)
        total[2] += iguales

    escribir(ORDENANZA, guardadas, ordenanzas, lambda a: str(a.get("anio")))
    # the ids the servicios point at: Core's once written; in a dry run, a stand-in for the ones it would create
    por_anio = {str(f["anio"]): f"<ordenanza {f['anio']}>" for f in ordenanzas}
    por_anio.update({str(r["attributes"].get("anio")): r["id"] for r in (guardadas if dry_run else client.list_all(ORDENANZA))})
    servicios = [{**f, "ordenanza": por_anio[str(f["ordenanza"])]} for f in servicios_archivo]
    escribir(SERVICIO, servicios_core, servicios, lambda a: a.get("codigo"))

    por_codigo = {f["codigo"]: f"<servicio {f['codigo']}>" for f in servicios_archivo}
    por_codigo.update({r["attributes"].get("codigo"): r["id"] for r in (servicios_core if dry_run else client.list_all(SERVICIO))})
    inafectaciones = [{**f, "predio": predios[f["predio"]], "servicio": por_codigo[f["servicio"]]} for f in inafectaciones_archivo]
    escribir(INAFECTACION, client.list_all(INAFECTACION), inafectaciones,
             lambda a: (a.get("predio"), a.get("servicio"), str(a.get("vigencia_desde"))))
    return tuple(total)


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Load an ordinance of arbitrios (ordinance, servicios, inafectaciones) into wasichai Core.")
    p.add_argument("--archivo", required=True, help="JSON {ordenanzas, servicios, inafectaciones}")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and say what it would do; write nothing")
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    datos = read_archivo(args.archivo)
    malas = errores(datos)
    if malas:
        for e in malas:
            print(f"error: {e}", file=sys.stderr)
        return 2
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        creados, actualizados, iguales = cargar(client, datos, args.dry_run)
    except NoEncaja as e:
        for falta in e.args[0]:
            print(f"error: {falta}", file=sys.stderr)
        return 2
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    if args.dry_run:
        print(f"dry run: {creados} to create, {actualizados} to update, {iguales} skipped; nothing written")
    else:
        print(f"done: {creados} created, {actualizados} updated, {iguales} skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
