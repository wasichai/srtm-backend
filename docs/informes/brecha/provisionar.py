"""Provisions Metabase for the corte's organizations: one DuckDB database, group and collection each, the
permission graphs that isolate them, and the tableros of metabase/tableros.json (herramientas/metabase.py;
docs/superpowers/plans/2026-10-25-corte-analitico.md; srtm-backend#206).

    python3 -I provisionar.py [--org clave[:nombre]]...
    python3 -I provisionar.py usuario --org ORG --email EMAIL --nombre NOMBRE --apellido APELLIDO

Without --org, the organizations of parametros.json; a clave without :nombre takes its nombre from there. The
first form also finishes Metabase's first-run setup with the administrator below when nobody did it yet; run it
again whenever tableros.json changes (it only creates what is missing and updates what differs). `usuario`
creates a user (if no active one has that email) in the organization's group; its password comes from
METABASE_CLAVE_USUARIO or, without it, is asked for without echo.

The connection comes from the environment: METABASE_URL (e.g. http://localhost:3310 through the SSH tunnel),
METABASE_ADMIN_EMAIL and METABASE_ADMIN_PASSWORD. Errors are one line, never with a password or the session.
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
ENTORNO = ("METABASE_URL", "METABASE_ADMIN_EMAIL", "METABASE_ADMIN_PASSWORD")


class _ErrorUso(Exception):
    """A usage or configuration error (exit 2), found before any request."""


def _argumentos_provision():
    parser = argparse.ArgumentParser(
        prog="provisionar.py", description="Crea o actualiza en Metabase las bases, grupos, colecciones y tableros."
    )
    parser.add_argument(
        "--org",
        action="append",
        default=[],
        help="clave[:nombre] de una organización (repetible; sin --org, las de parametros.json)",
    )
    return parser


def _argumentos_usuario():
    parser = argparse.ArgumentParser(
        prog="provisionar.py usuario", description="Crea un usuario de Metabase en el grupo de su organización."
    )
    parser.add_argument("--org", required=True, help="clave de la organización (su grupo es «Brecha <org>»)")
    parser.add_argument("--email", required=True)
    parser.add_argument("--nombre", required=True)
    parser.add_argument("--apellido", required=True)
    return parser


def _organizaciones(valores):
    """{clave: nombre} from the --org values, or parametros.json's organizations when there are none."""
    conocidas = {clave: datos["nombre"] for clave, datos in comun.parametros()["organizaciones"].items()}
    if not valores:
        return conocidas
    orgs = {}
    for valor in valores:
        clave, _, nombre = valor.partition(":")
        clave, nombre = clave.strip(), nombre.strip()
        try:
            comun.validar_org(clave)
        except comun.ErrorCorte as e:
            raise _ErrorUso(str(e)) from None
        nombre = nombre or conocidas.get(clave)
        if not nombre:
            raise _ErrorUso(f"{clave} no está en parametros.json: usa --org {clave}:<nombre>")
        orgs[clave] = nombre
    return orgs


def _conexion():
    faltan = [nombre for nombre in ENTORNO if not os.environ.get(nombre)]
    if faltan:
        raise _ErrorUso(f"falta {', '.join(faltan)} en el entorno")
    return (os.environ[nombre] for nombre in ENTORNO)


def _tableros():
    try:
        tableros = json.loads(TABLEROS.read_text(encoding="utf-8"))
        metabase.validar_tableros(tableros)
    except (OSError, ValueError, metabase.ErrorTableros) as e:
        raise _ErrorUso(f"{TABLEROS.name}: {e}") from None
    return tableros


def _provisionar(argv):
    args = _argumentos_provision().parse_args(argv)
    orgs = _organizaciones(args.org)
    tableros = _tableros()
    url, email, clave = _conexion()
    mb = metabase.Metabase(url)
    if metabase.asegurar_setup(mb, email, clave):
        print(f"setup inicial hecho: administrador {email}")
    mb.sesion(email, clave)
    resultado = metabase.provisionar(mb, orgs, tableros)
    for org, ids in resultado.items():
        print(
            f"{org}: base {ids['base']}, grupo {ids['grupo']}, colección {ids['coleccion']}, "
            f"{len(ids['tarjetas'])} tarjetas, {len(ids['tableros'])} tableros"
        )
    return 0


def _usuario(argv):
    args = _argumentos_usuario().parse_args(argv)
    try:
        comun.validar_org(args.org)
    except comun.ErrorCorte as e:
        raise _ErrorUso(str(e)) from None
    url, email, clave = _conexion()
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
    except comun.ErrorCorte as e:  # an invalid org caught by metabase.provisionar itself
        print(f"provisionar: {e}", file=sys.stderr)
        return 2
    except metabase.ErrorMetabase as e:
        print(f"provisionar: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
