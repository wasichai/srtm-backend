"""Smoke test of one organization's own, already provisioned Metabase instance (herramientas/metabase.py;
srtm-backend#206, task 11; plan, Decisión 2026-10-25): the instance holds only that organization's DuckDB (by
file and by the corte inside it), the database still syncs and shows its views, every tablero's card still runs,
and the organization's user sees its database without native SQL. Meant to run after `make provisionar` and
after every `make duckdb` + the restart it needs (docs/develop/metabase-cortes.md).

    python3 -I humo.py --org ORG --usuario EMAIL [--entorno ARCHIVO] [--espera SEGUNDOS]

--usuario is the organization's smoke-test user, already in its group (`provisionar.py usuario`); its password
is METABASE_CLAVE_USUARIO - a smoke test uses a disposable account, never a real one's. The instance comes from
the organization's own --entorno file (by omission ~/.config/srtm-analitica/<org>.env), exactly as in
provisionar.py: METABASE_URL there, else http://127.0.0.1:<METABASE_PUERTO>; never from the shell's
environment. METABASE_ADMIN_EMAIL and METABASE_ADMIN_PASSWORD come from the environment or that file. --espera
(default 120s) is how long to wait for the database's sync_schema to finish.

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


def _argumentos():
    parser = argparse.ArgumentParser(
        prog="humo.py", description="Prueba de humo del Metabase de una organización: su base, sus tableros."
    )
    parser.add_argument("--org", required=True, help="organización dueña de la instancia")
    parser.add_argument(
        "--usuario", required=True, help="email del usuario de humo de la organización (clave: METABASE_CLAVE_USUARIO)"
    )
    parser.add_argument(
        "--entorno", help="archivo <org>.env de la organización (por omisión ~/.config/srtm-analitica/<org>.env)"
    )
    parser.add_argument(
        "--espera", type=float, default=120, help="segundos de espera por la sincronización de la base"
    )
    return parser


def main(argv=None):
    argv = sys.argv[1:] if argv is None else list(argv)
    try:
        args = _argumentos().parse_args(argv)
    except SystemExit as e:  # argparse already printed the usage error (2) or the help (0)
        return e.code if isinstance(e.code, int) else 2

    try:
        clave_usuario = os.environ.get("METABASE_CLAVE_USUARIO")
        if not clave_usuario:
            raise comun.ErrorCorte("falta METABASE_CLAVE_USUARIO en el entorno")
        url, email_admin, clave_admin = metabase.conexion(comun.validar_org(args.org), args.entorno)
    except comun.ErrorCorte as e:
        print(f"humo: {e}", file=sys.stderr)
        return 2

    try:
        fallas = metabase.humo(url, (email_admin, clave_admin), args.org, (args.usuario, clave_usuario), args.espera)
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
