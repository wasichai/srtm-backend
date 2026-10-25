#!/usr/bin/env python3
"""Crea en el wasichai Core de srtm la cuenta de servicio con la que el corte analítico lee la organización del login.

La cuenta (`analitica` por defecto) lleva el rol ANALITICA de roles.json, que apply_roles.py crea antes: sin él la
cuenta no se crea. Core muestra el clientSecret una sola vez, al crearla (POST /api/service-accounts) o al rotarlo
(POST /api/service-accounts/{id}/secret, que anula el anterior): este comando lo imprime esa vez, con el clientId, con
los nombres del archivo de secretos del corte (~/.config/srtm-analitica/<org>.env, docs/informes/brecha/README.adoc):
SRTM_CLIENT_ID y SRTM_CLIENT_SECRET.

Con --salida <archivo> no los imprime: los escribe en ese archivo, en modo 0600. Si el archivo ya existe se conservan
sus demás líneas (las de catastro, la clave de seudonimización) y solo se reemplazan SRTM_CLIENT_ID y
SRTM_CLIENT_SECRET; SRTM_URL se agrega con --core si no está.

Es idempotente: si la cuenta ya existe no la toca más que para darle el rol si le falta, y dice su clientId; el secreto
ya no se puede leer. --rotar pide uno nuevo.

Correr: python3 -I crear_cuenta_analitica.py [--dry-run] [--rotar] [--salida ARCHIVO] [--nombre analitica]
        [--core URL] [--email E] [--password P]
Salida: 0 si va bien, 1 si core rechaza algo o no responde o el archivo no se puede escribir, 2 si falta el rol o el
nombre no es válido.
"""
import argparse
import json
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter, for core_client
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from core_client import Client, CoreError  # noqa: E402

ROL = "ANALITICA"
# ServiceAccountService.NAME (core la pasa a minúsculas antes)
NOMBRE = re.compile(r"^[a-z][a-z0-9_-]{1,48}$")
# las claves de srtm en el archivo de secretos del corte (docs/informes/brecha/herramientas/comun.py, REQUERIDOS)
URL, CLIENT_ID, CLIENT_SECRET = "SRTM_URL", "SRTM_CLIENT_ID", "SRTM_CLIENT_SECRET"
CLAVES = (URL, CLIENT_ID, CLIENT_SECRET)


def _fatal(method, path, err):
    print(f"error {method} {path} -> {err.status}\n{err.body}", file=sys.stderr)


def escribir_salida(ruta, core_url, client_id, secreto):
    """Deja en `ruta`, modo 0600, SRTM_CLIENT_ID y SRTM_CLIENT_SECRET (y SRTM_URL si falta), con las demás líneas que
    ya tenía. Se escribe a un temporal 0600 junto a él y se reemplaza: el secreto nunca queda en un archivo abierto."""
    lineas = []
    if os.path.exists(ruta):
        with open(ruta, encoding="utf-8") as f:
            lineas = f.read().splitlines()
    nuevas = {CLIENT_ID: client_id, CLIENT_SECRET: secreto}
    tiene_url = any(l.strip().partition("=")[0].strip() == URL for l in lineas)
    salida = []
    for linea in lineas:
        clave = linea.strip().partition("=")[0].strip()
        if clave in nuevas:
            salida.append(f"{clave}={nuevas.pop(clave)}")
        else:
            salida.append(linea)
    if not tiene_url:
        salida.append(f"{URL}={core_url}")
    salida.extend(f"{k}={v}" for k, v in nuevas.items())
    directorio = os.path.dirname(os.path.abspath(ruta))
    os.makedirs(directorio, mode=0o700, exist_ok=True)
    fd, temporal = tempfile.mkstemp(dir=directorio, prefix=".analitica-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write("\n".join(salida) + "\n")
        os.chmod(temporal, 0o600)
        os.replace(temporal, ruta)
    except BaseException:
        if os.path.exists(temporal):
            os.unlink(temporal)
        raise


def _entregar(client_id, secreto, core_url, salida, out):
    """El secreto nuevo, una sola vez: en pantalla o en el archivo de --salida."""
    if salida is None:
        print(f"{CLIENT_ID}={client_id}", file=out)
        print(f"{CLIENT_SECRET}={secreto}", file=out)
        print("GUÁRDALOS AHORA en ~/.config/srtm-analitica/<org>.env (modo 0600): core no vuelve a mostrar el secreto.",
              file=out)
        return 0
    try:
        escribir_salida(salida, core_url, client_id, secreto)
    except OSError as e:
        print(f"error: {salida}: {e.strerror or e}", file=sys.stderr)
        return 1
    print(f"{CLIENT_ID} y {CLIENT_SECRET} escritos en {salida} (modo 0600)", file=out)
    return 0


def crear(client, nombre, core_url, rotar=False, salida=None, out=None):
    """Crea la cuenta, o la deja con el rol y, con `rotar`, un secreto nuevo. 0, 1 (core o archivo) o 2 (falta el rol)."""
    out = out or sys.stdout
    try:
        roles = {r["name"] for r in client.get("/api/roles") or []}
    except CoreError as e:
        _fatal("GET", "/api/roles", e)
        return 1
    if ROL not in roles:
        print(f"error: core no tiene el rol {ROL}: corre antes python3 -I apply_roles.py", file=sys.stderr)
        return 2
    try:
        cuentas = client.get("/api/service-accounts") or []
    except CoreError as e:
        _fatal("GET", "/api/service-accounts", e)
        return 1
    cuenta = next((c for c in cuentas if c.get("name") == nombre), None)
    if cuenta is None:
        try:
            _, creada = client.post("/api/service-accounts", {"name": nombre, "roles": [ROL]})
        except CoreError as e:
            _fatal("POST", "/api/service-accounts", e)
            return 1
        print(f"create service account {nombre} ({ROL})", file=out)
        return _entregar(creada["clientId"], creada["clientSecret"], core_url, salida, out)
    roles_cuenta = list(cuenta.get("roles") or [])
    if ROL not in roles_cuenta:
        path = f"/api/service-accounts/{cuenta['id']}"
        try:
            client.put(path, {"roles": roles_cuenta + [ROL]})
        except CoreError as e:
            _fatal("PUT", path, e)
            return 1
        print(f"update service account {nombre} (+{ROL})", file=out)
    else:
        print(f"skip   service account {nombre} (ya existe con {ROL})", file=out)
    if cuenta.get("enabled") is False:
        print(f"aviso: la cuenta {nombre} está deshabilitada; se habilita en el admin de core", file=out)
    if rotar:
        path = f"/api/service-accounts/{cuenta['id']}/secret"
        try:
            _, rotada = client.post(path, None)
        except CoreError as e:
            _fatal("POST", path, e)
            return 1
        print(f"rotate secret of {nombre} (el anterior ya no sirve)", file=out)
        return _entregar(rotada["clientId"], rotada["clientSecret"], core_url, salida, out)
    print(f"{CLIENT_ID}={cuenta['clientId']} (el secreto ya no se puede leer; uno nuevo: --rotar)", file=out)
    return 0


def _parse_args(argv):
    p = argparse.ArgumentParser(description="Crea la cuenta de servicio del corte analítico (rol ANALITICA) en srtm.")
    p.add_argument("--nombre", default="analitica")
    p.add_argument("--rotar", action="store_true", help="si la cuenta existe, le pide a core un secreto nuevo")
    p.add_argument("--salida", help="escribe SRTM_CLIENT_ID y SRTM_CLIENT_SECRET en este archivo (0600), no en pantalla")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8090"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="imprime lo que mandaría a un core vacío; no llama a core")
    return p.parse_args(argv)


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    nombre = args.nombre.strip().lower()
    if not NOMBRE.fullmatch(nombre):
        print(f"error: el nombre '{nombre}' no cumple {NOMBRE.pattern}", file=sys.stderr)
        return 2
    if args.dry_run:
        print("# POST /api/service-accounts")
        print(json.dumps({"name": nombre, "roles": [ROL]}, indent=2, ensure_ascii=False))
        return 0
    client = Client(args.core)
    try:
        client.login(args.email, args.password)
    except CoreError as e:
        _fatal("POST", "/api/auth/login", e)
        return 1
    salida = os.path.expanduser(args.salida) if args.salida else None
    return crear(client, nombre, args.core, rotar=args.rotar, salida=salida)


if __name__ == "__main__":
    sys.exit(main())
