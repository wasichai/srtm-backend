"""Smoke test of an already provisioned Metabase (herramientas/metabase.py; srtm-backend#206, task 11): every
organization's database still syncs and shows its views, every tablero's card still runs, and the isolation
between organizations still holds (plan § Rulings, aislamiento). Meant to run after `make provisionar` and
after every `make duckdb` + the restart it needs (docs/develop/metabase-cortes.md).

    python3 -I humo.py --usuario ORG:EMAIL [--usuario ORG:EMAIL]... [--espera SEGUNDOS]

Every --usuario is an organization already provisioned with a user in its group (`provisionar.py usuario`);
they all share one password, METABASE_CLAVE_USUARIO - a smoke test uses disposable accounts, never a real
one's. The connection comes from METABASE_URL, METABASE_ADMIN_EMAIL and METABASE_ADMIN_PASSWORD. --espera
(default 120s) is how long to wait for each database's sync_schema to finish; on the fictitious organizations
of docs/develop/metabase-cortes.md it settles almost at once.

Prints `humo: OK (<n> comprobaciones)` and exits 0 when every comprobación passed, or each falla (one per
line) to stderr and exits 1. Exit codes: 0 ok, 2 bad usage or configuration (nothing was asked of Metabase),
1 a falla, or Metabase itself could not be reached.
"""

import argparse
import os
import sys

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from herramientas import comun, metabase  # noqa: E402

ENTORNO = ("METABASE_URL", "METABASE_ADMIN_EMAIL", "METABASE_ADMIN_PASSWORD")


class _ErrorUso(Exception):
    """A usage or configuration error (exit 2), found before any request to Metabase."""


def _argumentos():
    parser = argparse.ArgumentParser(
        prog="humo.py", description="Prueba de humo de Metabase: aislamiento entre organizaciones y tableros."
    )
    parser.add_argument(
        "--usuario",
        action="append",
        default=[],
        required=True,
        help="org:email de un usuario ya creado (repetible; comparten clave, METABASE_CLAVE_USUARIO)",
    )
    parser.add_argument(
        "--espera", type=float, default=120, help="segundos de espera por la sincronización de cada base"
    )
    return parser


def _usuarios(valores, clave):
    usuarios = {}
    for valor in valores:
        org, _, email = valor.partition(":")
        org, email = org.strip(), email.strip()
        if not org or not email:
            raise _ErrorUso(f"--usuario inválido: {valor!r} (se espera org:email)")
        try:
            comun.validar_org(org)
        except comun.ErrorCorte as e:
            raise _ErrorUso(str(e)) from None
        usuarios[org] = (email, clave)
    return usuarios


def _conexion():
    faltan = [nombre for nombre in ENTORNO if not os.environ.get(nombre)]
    if faltan:
        raise _ErrorUso(f"falta {', '.join(faltan)} en el entorno")
    return (os.environ[nombre] for nombre in ENTORNO)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else list(argv)
    try:
        args = _argumentos().parse_args(argv)
    except SystemExit as e:  # argparse already printed the usage error (2) or the help (0)
        return e.code if isinstance(e.code, int) else 2

    try:
        clave_usuario = os.environ.get("METABASE_CLAVE_USUARIO")
        if not clave_usuario:
            raise _ErrorUso("falta METABASE_CLAVE_USUARIO en el entorno")
        usuarios = _usuarios(args.usuario, clave_usuario)
        url, email_admin, clave_admin = _conexion()
    except _ErrorUso as e:
        print(f"humo: {e}", file=sys.stderr)
        return 2

    try:
        fallas = metabase.humo(url, (email_admin, clave_admin), usuarios, espera=args.espera)
    except metabase.ErrorMetabase as e:
        print(f"humo: {e}", file=sys.stderr)
        return 1

    if not fallas:
        print(f"humo: OK ({fallas.comprobaciones} comprobaciones)")
        return 0
    for falla in fallas:
        print(falla, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
