"""Loads a corte into a DuckDB file: its 17 tables, corte_manifiesto and the views of vistas.sql
(herramientas/base.py; contexto.md; srtm-backend#205).

    python3 -I armar_duckdb.py --corte CARPETA --destino ARCHIVO

The corte is checked against its manifest before the file is touched. Exit codes: 0 ok, 2 bad usage or a corte
that does not match its manifest, 1 a DuckDB or file error.
"""

import argparse
import os
import sys
from pathlib import Path

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import duckdb  # noqa: E402

from herramientas import base, comun  # noqa: E402


def _argumentos():
    parser = argparse.ArgumentParser(prog="armar_duckdb.py", description="Carga un corte en un archivo DuckDB.")
    parser.add_argument("--corte", required=True, help="carpeta del corte (la de su manifiesto.json)")
    parser.add_argument("--destino", required=True, help="archivo DuckDB que se crea o se reemplaza")
    return parser


def main(argv=None):
    try:
        args = _argumentos().parse_args(argv)
    except SystemExit as e:  # argparse already printed the usage error (2) or the help (0)
        return e.code if isinstance(e.code, int) else 2
    carpeta, destino = Path(args.corte).expanduser(), Path(args.destino).expanduser()
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


if __name__ == "__main__":
    sys.exit(main())
