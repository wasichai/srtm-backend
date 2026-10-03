#!/usr/bin/env python3
"""Loads the verified tax parameters of the impuesto predial into parametro_tributario: the UIT of each year, the
tramos and their límites, the mínimo and the deducciones. With --csv, the rows of an ordinance too, whose shape is
checked before anything is sent: the arbitrios' tasas and mappings of zona and uso (TIPOS_ARBITRIO), the plazos and
feriados of the sanciones (TIPOS_SANCIONES) and the tasas of the anuncios (TIPOS_ANUNCIO).

They come from data/parametros-predial.csv, a verbatim copy of the predial rows of normativa's
docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv (double-signed: transcribio, verifico). No figure
is typed here.

The natural key is (tipo, clave, vigencia_desde). A row Core lacks is created and one whose values changed is
updated in place; a row the CSV does not have is left alone (a parameter of another year or source is not this
script's to delete). Idempotent: a second run writes nothing.

Run: python3 import_parametros.py [--csv <file>] [--dry-run]
Exit: 0 ok, 1 Core refused something, 2 a row of an ordinance is malformed (nothing is sent).
"""
import argparse
import csv
import json
import os
import re
import sys
from datetime import date
from decimal import Decimal, InvalidOperation

from core_client import Client, CoreError

HERE = os.path.dirname(os.path.abspath(__file__))
OBJECT = "parametro_tributario"
FIELDS = ("tipo", "clave", "vigencia_desde", "vigencia_hasta", "valor_numerico", "texto", "norma", "fuente", "transcribio", "verifico")


def read_parametros(path):
    """The CSV's rows as attributes; the '#' lines are its header comment and an empty cell is left out."""
    with open(path, encoding="utf-8") as f:
        rows = csv.DictReader(line for line in f if not line.startswith("#"))
        return [{k: r[k].strip() for k in FIELDS if r.get(k) and r[k].strip()} for r in rows]


# the ordinance's rows the arbitrios read (srtm.arbitrios.Llaves names the same tipos; its test compares both lists):
# TASA_ARBITRIO servicio:zona:uso the monthly tasa in soles; ARBITRIO_ZONA <sector catastral> its zona in texto;
# ARBITRIO_USO <prefix of a uso_predio code, 2, 4 or 6 digits> its uso de arbitrio in texto; ARBITRIO_VENCIMIENTO
# <month> its due date in texto (YYYY-MM-DD)
TASA_ARBITRIO = "TASA_ARBITRIO"
ARBITRIO_ZONA = "ARBITRIO_ZONA"
ARBITRIO_USO = "ARBITRIO_USO"
ARBITRIO_VENCIMIENTO = "ARBITRIO_VENCIMIENTO"
TIPOS_ARBITRIO = (TASA_ARBITRIO, ARBITRIO_ZONA, ARBITRIO_USO, ARBITRIO_VENCIMIENTO)


# the rows the sanciones read (srtm.sanciones.Llaves names the same tipos): PLAZO DESCARGO_PAPELETA or RG_RECURSO its
# days in valor_numerico, a whole number above 0, and its unit in texto (DIAS_HABILES, the only one for now); FERIADOS
# <year> the year's movable holidays in texto, ISO dates of that year separated by commas (empty when it has none), in
# force from its 1 January to its 31 December
PLAZO = "PLAZO"
FERIADOS = "FERIADOS"
TIPOS_SANCIONES = (PLAZO, FERIADOS)
PLAZO_DESCARGO_PAPELETA = "DESCARGO_PAPELETA"
PLAZO_RG_RECURSO = "RG_RECURSO"
CLAVES_PLAZO = (PLAZO_DESCARGO_PAPELETA, PLAZO_RG_RECURSO)
DIAS_HABILES = "DIAS_HABILES"

# the rows the anuncios read (srtm.anuncios.Llaves names the same tipo): TASA_ANUNCIO <clase_anuncio> the tasa of a
# whole ejercicio in valor_numerico, above 0 (a clase without one is not authorized at zero)
TASA_ANUNCIO = "TASA_ANUNCIO"
TIPOS_ANUNCIO = (TASA_ANUNCIO,)

VALIDADOS = TIPOS_ARBITRIO + TIPOS_SANCIONES + TIPOS_ANUNCIO


def _clases_de_anuncio():
    """model.json's clase_anuncio: a TASA_ANUNCIO's clave is one of them."""
    with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
        return tuple(json.load(f)["enums"]["clase_anuncio"])


def _firmante(firma):
    """'ANA, 2026-01-05' -> 'ANA': normativa's signature is a name and a date."""
    return (firma or "").split(",")[0].strip().upper()


def _decimal(p):
    try:
        return Decimal(str(p.get("valor_numerico")))
    except InvalidOperation:
        return None


def _fecha(texto):
    try:
        return date.fromisoformat(texto)
    except (TypeError, ValueError):
        return None


def _error_arbitrio(tipo, clave, texto, p):
    if tipo == TASA_ARBITRIO:
        partes = clave.split(":")
        if len(partes) != 3 or not all(x.strip() for x in partes):
            return "la clave de una tasa es servicio:zona:uso"
        valor = _decimal(p)
        if valor is None:
            return "una tasa necesita su valor_numerico"
        if valor < 0:
            return "una tasa no es negativa"
    elif tipo == ARBITRIO_ZONA:
        if not clave or not texto:
            return "una zona es el sector catastral (clave) y su zona (texto)"
    elif tipo == ARBITRIO_USO:
        if not re.fullmatch(r"\d{2}|\d{4}|\d{6}", clave) or not texto:
            return "un uso es un prefijo de código de uso_predio de 2, 4 o 6 dígitos (clave) y su uso de arbitrio (texto)"
    elif tipo == ARBITRIO_VENCIMIENTO:
        if not clave.isdigit() or not 1 <= int(clave) <= 12:
            return "un vencimiento es de un mes, de 1 a 12"
        if _fecha(texto) is None:
            return "un vencimiento es una fecha AAAA-MM-DD en texto"
    return None


def _error_sanciones(tipo, clave, texto, p):
    if tipo == PLAZO:
        if clave not in CLAVES_PLAZO:
            return f"la clave de un plazo es {' o '.join(CLAVES_PLAZO)}"
        valor = _decimal(p)
        if valor is None or valor != valor.to_integral_value() or valor <= 0:
            return "un plazo es un número entero de días mayor que 0 (valor_numerico)"
        if texto != DIAS_HABILES:
            return f"la unidad de un plazo (texto) es {DIAS_HABILES}"
    elif tipo == FERIADOS:
        if not re.fullmatch(r"\d{4}", clave):
            return "la clave de los feriados es el año, AAAA"
        if p.get("vigencia_desde") != f"{clave}-01-01" or p.get("vigencia_hasta") != f"{clave}-12-31":
            return f"los feriados de {clave} rigen del {clave}-01-01 al {clave}-12-31"
        fechas = [x.strip() for x in texto.split(",")] if texto else []
        if any(_fecha(x) is None or _fecha(x).year != int(clave) for x in fechas):
            return f"los feriados son fechas AAAA-MM-DD de {clave}, separadas por comas (texto)"
    return None


def _error_anuncio(tipo, clave, texto, p):
    if tipo == TASA_ANUNCIO:
        clases = _clases_de_anuncio()
        if clave not in clases:
            return f"la clave de una tasa de anuncio es una clase: {', '.join(clases)}"
        valor = _decimal(p)
        if valor is None or valor <= 0:
            return "una tasa de anuncio es mayor que 0 (valor_numerico)"
    return None


def _error(p):
    """Why a row of an ordinance does not fit, or None. A figure is never invented: a tasa is never negative (nor zero
    for an anuncio), a mapping names its value, and every row is signed by two different people (normativa's double
    signature)."""
    tipo, clave, texto = p["tipo"], (p.get("clave") or "").strip(), (p.get("texto") or "").strip()
    if not _firmante(p.get("transcribio")) or not _firmante(p.get("verifico")):
        return "falta una firma (transcribio, verifico)"
    if _firmante(p.get("transcribio")) == _firmante(p.get("verifico")):
        return "transcribio y verifico son la misma persona"
    if tipo in TIPOS_ARBITRIO:
        return _error_arbitrio(tipo, clave, texto, p)
    if tipo in TIPOS_SANCIONES:
        return _error_sanciones(tipo, clave, texto, p)
    return _error_anuncio(tipo, clave, texto, p)


def errores(parametros):
    """The rows of an ordinance that do not fit, as '<tipo> <clave>: why'. The predial's are not checked here."""
    malas = []
    for p in parametros:
        if p.get("tipo") in VALIDADOS:
            motivo = _error(p)
            if motivo:
                malas.append(f"{p['tipo']} {p.get('clave') or ''}: {motivo}")
    return malas


def key(attributes):
    return attributes["tipo"], attributes.get("clave") or "", attributes["vigencia_desde"]


def _same(field, stored, wanted):
    """Core reads a DECIMAL back as a number (5500.0 for 5500.00): the same value is no change."""
    if stored in (None, "") and wanted in (None, ""):
        return True
    if field == "valor_numerico" and stored is not None and wanted is not None:
        try:
            return Decimal(str(stored)) == Decimal(str(wanted))
        except InvalidOperation:
            return False
    return stored == wanted


def changes(stored, wanted):
    return [f for f in FIELDS if not _same(f, stored.get(f), wanted.get(f))]


def plan(records, parametros):
    """(create, update, skipped): the rows Core lacks, (record, row) of the ones that changed, and how many are as
    the CSV says."""
    by_key = {key(r["attributes"]): r for r in records}
    create = [p for p in parametros if key(p) not in by_key]
    update = [(by_key[key(p)], p) for p in parametros if key(p) in by_key and changes(by_key[key(p)]["attributes"], p)]
    return create, update, len(parametros) - len(create) - len(update)


def _label(attributes):
    return " ".join(x for x in key(attributes) if x)


def load(client, parametros, dry_run=False):
    """Creates and updates what differs, printing a line per row it writes. Returns (created, updated, skipped)."""
    create, update, skipped = plan(client.list_all(OBJECT), parametros)
    for p in create:
        print(f"  create {_label(p)}: {p.get('valor_numerico', '')}")
    for record, p in update:
        stored = record["attributes"]
        for field in changes(stored, p):
            print(f"  update {_label(p)}: {field} {stored.get(field)} -> {p.get(field)}")
    if dry_run:
        print(f"{OBJECT}: {len(create)} to create, {len(update)} to update, {skipped} skipped")
        return len(create), len(update), skipped
    for p in create:
        client.post(f"/api/objects/{OBJECT}/records", {"attributes": p})
    for record, p in update:
        client.put(f"/api/objects/{OBJECT}/records/{record['id']}", {"attributes": p})
    print(f"{OBJECT}: {len(create)} created, {len(update)} updated, {skipped} skipped", flush=True)
    return len(create), len(update), skipped


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Load the verified parameters of the impuesto predial into wasichai Core.")
    p.add_argument("--csv", default=os.path.join(HERE, "data", "parametros-predial.csv"))
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="read Core and say what it would do; write nothing")
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    parametros = read_parametros(args.csv)
    print(f"parámetros: {len(parametros)}")
    malas = errores(parametros)
    if malas:
        for e in malas:
            print(f"error: {e}", file=sys.stderr)
        return 2
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
        load(client, parametros, args.dry_run)
    except CoreError as e:
        print(f"error -> {e.status}\n{e.body}", file=sys.stderr)
        return 1
    if args.dry_run:
        print("dry run: nothing written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
