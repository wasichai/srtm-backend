"""Provisions one organization's own Metabase instance (plan, Decisión 2026-10-25: one instance per
organization): its DuckDB database, group and collection, the permission graphs that keep "All Users" out of
them, and the tableros of metabase/tableros.json (herramientas/metabase.py;
docs/superpowers/plans/2026-10-25-corte-analitico.md; srtm-backend#206).

    python3 -I provisionar.py --org clave[:nombre] [--entorno ARCHIVO]
    python3 -I provisionar.py usuario --org ORG [--entorno ARCHIVO] --email EMAIL --nombre NOMBRE --apellido APELLIDO

A clave without :nombre takes its nombre from parametros.json. The first form also finishes Metabase's first-run
setup with the administrator below when nobody did it yet; run it again whenever tableros.json changes (it only
creates what is missing and updates what differs). An instance that already holds another organization's
database or group is refused (exit 1). `usuario` creates a user (if nobody has that email) in the
organization's group; a deactivated user's email is refused (exit 1). Its password comes from
METABASE_CLAVE_USUARIO or, without it, is asked for without echo.

The instance comes from the organization's own file, --entorno (by omission ~/.config/srtm-analitica/<org>.env,
mode 0600): METABASE_URL if it has one, else http://127.0.0.1:<METABASE_PUERTO> (the SSH tunnel forwards the
same port). Never from the shell's environment: a METABASE_URL left over from another organization's session
must not point this one at the wrong instance. METABASE_ADMIN_EMAIL and METABASE_ADMIN_PASSWORD come from the
environment or, without them, from that file. Errors are one line, never with a password or the session.
Exit codes: 0 ok, 2 bad usage or configuration (nothing was sent), 1 a Metabase API error.
"""

import argparse
import getpass
import json
import os
import sys

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from herramientas import comun, metabase  # noqa: E402

TABLEROS = comun.INFORME / "metabase" / "tableros.json"


class _ErrorUso(Exception):
    """A usage or configuration error (exit 2), found before any request."""


def _argumentos_provision():
    parser = argparse.ArgumentParser(
        prog="provisionar.py",
        description="Crea o actualiza, en el Metabase de una organización, su base, grupo, colección y tableros.",
    )
    # append, not store: a second --org is an error (one instance, one organization), never a silent override
    parser.add_argument(
        "--org", action="append", required=True, help="clave[:nombre] de la organización dueña de la instancia"
    )
    _argumento_entorno(parser)
    return parser


def _argumento_entorno(parser):
    parser.add_argument(
        "--entorno", help="archivo <org>.env de la organización (por omisión ~/.config/srtm-analitica/<org>.env)"
    )


def _argumentos_usuario():
    parser = argparse.ArgumentParser(
        prog="provisionar.py usuario", description="Crea un usuario de Metabase en el grupo de su organización."
    )
    parser.add_argument("--org", required=True, help="clave de la organización (su grupo es «Brecha <org>»)")
    _argumento_entorno(parser)
    parser.add_argument("--email", required=True)
    parser.add_argument("--nombre", required=True)
    parser.add_argument("--apellido", required=True)
    return parser


def _organizacion(valor):
    """(clave, nombre) from --org clave[:nombre]; without :nombre, parametros.json's."""
    clave, _, nombre = valor.partition(":")
    clave, nombre = clave.strip(), nombre.strip()
    try:
        comun.validar_org(clave)
    except comun.ErrorCorte as e:
        raise _ErrorUso(str(e)) from None
    nombre = nombre or comun.parametros()["organizaciones"].get(clave, {}).get("nombre")
    if not nombre:
        raise _ErrorUso(f"{clave} no está en parametros.json: usa --org {clave}:<nombre>")
    return clave, nombre


def _tableros():
    try:
        tableros = json.loads(TABLEROS.read_text(encoding="utf-8"))
        metabase.validar_tableros(tableros)
    except (OSError, ValueError, metabase.ErrorTableros) as e:
        raise _ErrorUso(f"{TABLEROS.name}: {e}") from None
    return tableros


def _provisionar(argv):
    args = _argumentos_provision().parse_args(argv)
    if len(args.org) != 1:
        raise _ErrorUso("una sola --org: cada organización tiene su propia instancia de Metabase")
    org, nombre = _organizacion(args.org[0])
    tableros = _tableros()
    url, email, clave = metabase.conexion(org, args.entorno)
    mb = metabase.Metabase(url)
    if metabase.asegurar_setup(mb, email, clave):
        print(f"setup inicial hecho: administrador {email}")
    mb.sesion(email, clave)
    ids = metabase.provisionar(mb, org, nombre, tableros)
    print(
        f"{org} ({url}): base {ids['base']}, grupo {ids['grupo']}, colección {ids['coleccion']}, "
        f"{len(ids['tarjetas'])} tarjetas, {len(ids['tableros'])} tableros"
    )
    return 0


def _usuario(argv):
    args = _argumentos_usuario().parse_args(argv)
    try:
        comun.validar_org(args.org)
    except comun.ErrorCorte as e:
        raise _ErrorUso(str(e)) from None
    url, email, clave = metabase.conexion(args.org, args.entorno)
    clave_usuario = os.environ.get("METABASE_CLAVE_USUARIO") or getpass.getpass(f"clave para {args.email}: ")
    if not clave_usuario:
        raise _ErrorUso("falta la clave del usuario (METABASE_CLAVE_USUARIO o tecleada)")
    mb = metabase.Metabase(url)
    mb.sesion(email, clave)
    uid = metabase.agregar_usuario(mb, args.org, args.email, args.nombre, args.apellido, clave_usuario)
    print(f"usuario {args.email} (id {uid}) en el grupo Brecha {args.org}")
    return 0


def main(argv=None):
    argv = sys.argv[1:] if argv is None else list(argv)
    try:
        if argv[:1] == ["usuario"]:
            return _usuario(argv[1:])
        return _provisionar(argv)
    except SystemExit as e:  # argparse already printed the usage error (2) or the help (0)
        return e.code if isinstance(e.code, int) else 2
    except _ErrorUso as e:
        print(f"provisionar: {e}", file=sys.stderr)
        return 2
    except comun.ErrorCorte as e:  # the <org>.env file (metabase.conexion), or an invalid org
        print(f"provisionar: {e}", file=sys.stderr)
        return 2
    except metabase.ErrorMetabase as e:
        print(f"provisionar: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
