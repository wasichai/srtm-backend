"""Loads a corte into a DuckDB file: its 17 tables, corte_manifiesto and the views of vistas.sql
(herramientas/base.py; docs/superpowers/plans/2026-10-25-corte-analitico.md; srtm-backend#205, #206).

    python3 -I armar_duckdb.py --corte CARPETA --destino ARCHIVO
    python3 -I armar_duckdb.py --org ORG [--cortes DIR] [--destino-dir DIR]

--corte/--destino builds once, directly into --destino (make local: a one-off file to inspect). --org is the
one Metabase's `make duckdb` runs: it downloads the organization's latest published corte with
herramientas.s3.descargar into --cortes/<org>/<fecha> (by omission /srv/srtm-metabase/cortes) and rebuilds
--destino-dir/<org>.duckdb atomically with herramientas.base.construir_atomico (by omission
/srv/srtm-metabase/duckdb) - the file Metabase has open is only ever replaced whole, never half-built.

Either way the corte is checked against its manifest before anything is touched. Exit codes: 0 ok, 2 bad
usage, a corte that does not match its manifest, or (--org) nothing published yet; 1 a DuckDB, download or
file error.
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import duckdb  # noqa: E402

from herramientas import base, comun, s3  # noqa: E402

CORTES_DIR = "/srv/srtm-metabase/cortes"
DESTINO_DIR = "/srv/srtm-metabase/duckdb"


def _argumentos():
    parser = argparse.ArgumentParser(prog="armar_duckdb.py", description="Carga un corte en un archivo DuckDB.")
    grupo = parser.add_mutually_exclusive_group(required=True)
    grupo.add_argument("--corte", help="carpeta de un corte ya descargado (con --destino)")
    grupo.add_argument("--org", help="organización: baja su último corte de S3 y arma <destino-dir>/<org>.duckdb")
    parser.add_argument("--destino", help="archivo DuckDB que se crea o se reemplaza (con --corte)")
    # no default here (None, not CORTES_DIR/DESTINO_DIR): main() tells "not given" from "given, same as the
    # default" apart, so --cortes/--destino-dir can be rejected alongside --corte without a value comparison
    parser.add_argument("--cortes", help=f"carpeta de cortes bajados (con --org; por omisión {CORTES_DIR})")
    parser.add_argument(
        "--destino-dir",
        dest="destino_dir",
        help=f"carpeta de los .duckdb (con --org; por omisión {DESTINO_DIR})",
    )
    return parser


def _armar_local(carpeta, destino):
    # a file inside the corte would be an extra file its manifest does not list: the corte would stop verifying
    if carpeta.resolve() in (destino.resolve(), *destino.resolve().parents):
        print(f"armar_duckdb: el destino {destino} queda dentro del corte {carpeta}; usa otra carpeta", file=sys.stderr)
        return 2
    try:
        con = base.construir_duckdb(carpeta, destino)
    except comun.ErrorCorte as e:
        print(f"armar_duckdb: {e}", file=sys.stderr)
        return 2
    except (duckdb.Error, OSError) as e:
        print(f"armar_duckdb: no se pudo armar {destino}: {e}", file=sys.stderr)
        return 1
    try:
        tablas = con.execute("SELECT count(*) FROM duckdb_tables()").fetchone()[0]
        vistas = con.execute("SELECT count(*) FROM duckdb_views() WHERE NOT internal").fetchone()[0]
    finally:
        con.close()
    print(f"duckdb armado: {carpeta} -> {destino}: {tablas} tablas, {vistas} vistas")
    return 0


def _armar_org(org, cortes, destino_dir):
    # validated again inside s3.descargar (a public helper on its own), but checked here first too: an
    # unvalidated org would otherwise reach a filesystem path (cortes/org/fecha, destino_dir/<org>.duckdb)
    # and an S3 prefix before either function has a chance to reject it
    try:
        comun.validar_org(org)
    except comun.ErrorCorte as e:
        print(f"armar_duckdb: {e}", file=sys.stderr)
        return 2
    s3_base = comun.parametros()["publicacion"]["s3"]
    try:
        carpeta = s3.descargar(s3_base, org, cortes)
    except comun.ErrorCorte as e:
        print(f"armar_duckdb: {e}", file=sys.stderr)
        return 2
    except (subprocess.CalledProcessError, OSError) as e:
        print(f"armar_duckdb: no se pudo bajar el corte de {org}: {e}", file=sys.stderr)
        return 1
    destino = destino_dir / f"{org}.duckdb"
    try:
        destino_dir.mkdir(parents=True, exist_ok=True)
        base.construir_atomico(carpeta, destino)
    except comun.ErrorCorte as e:
        print(f"armar_duckdb: {e}", file=sys.stderr)
        return 2
    except (duckdb.Error, OSError) as e:
        print(f"armar_duckdb: no se pudo armar {destino}: {e}", file=sys.stderr)
        return 1
    print(f"duckdb armado: {org} ({carpeta}) -> {destino}")
    return 0


def main(argv=None):
    try:
        args = _argumentos().parse_args(argv)
    except SystemExit as e:  # argparse already printed the usage error (2) or the help (0)
        return e.code if isinstance(e.code, int) else 2

    if args.org:
        if args.destino:
            print("armar_duckdb: --destino no se usa con --org (usa --destino-dir)", file=sys.stderr)
            return 2
        cortes = Path(args.cortes or CORTES_DIR).expanduser()
        destino_dir = Path(args.destino_dir or DESTINO_DIR).expanduser()
        return _armar_org(args.org, cortes, destino_dir)

    if not args.destino:
        print("armar_duckdb: falta --destino (o usa --org)", file=sys.stderr)
        return 2
    if args.cortes or args.destino_dir:
        print("armar_duckdb: --cortes/--destino-dir no se usan con --corte (son de --org)", file=sys.stderr)
        return 2
    return _armar_local(Path(args.corte).expanduser(), Path(args.destino).expanduser())


if __name__ == "__main__":
    sys.exit(main())
