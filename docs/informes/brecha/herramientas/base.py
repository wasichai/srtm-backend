"""A corte loaded into a DuckDB file: one materialized table per Parquet, the manifest's figures in
`corte_manifiesto`, and the views of vistas.sql (contexto.md: tables, not views over the files, so Metabase opens
the file read-only with external access off; `duckdb` is pinned to the driver's 1.5.5 for the same storage
format).
"""

from pathlib import Path

import duckdb

from . import comun, manifiesto
from .tablas import ESQUEMAS

VISTAS = comun.INFORME / "vistas.sql"


def construir_duckdb(carpeta: Path, destino: Path | str) -> duckdb.DuckDBPyConnection:
    """Checks the corte in `carpeta` against its manifest (an ErrorCorte before `destino` is touched), then loads
    it into `destino` and runs vistas.sql; returns the open connection. Tables and views are created or
    replaced, so building onto an existing file leaves it holding this corte."""
    m = manifiesto.verificar_corte(carpeta)
    con = duckdb.connect(str(destino))
    try:
        for tabla in ESQUEMAS:
            con.execute(
                f"CREATE OR REPLACE TABLE {tabla} AS SELECT * FROM read_parquet(?)",
                [str(carpeta / f"{tabla}.parquet")],
            )
        con.execute(
            "CREATE OR REPLACE TABLE corte_manifiesto AS SELECT"
            " CAST(? AS VARCHAR) AS organizacion, CAST(? AS DATE) AS fecha_corte, CAST(? AS VARCHAR) AS tipo,"
            " CAST(? AS INTEGER) AS anio, CAST(? AS TIMESTAMPTZ) AS leido_en, CAST(? AS VARCHAR) AS version_motor,"
            " CAST(? AS VARCHAR) AS region",
            [
                m["organizacion"],
                m["fecha_corte"],
                m["tipo"],
                m["anio"],
                m["leido_en"],
                m["motor"]["version"],
                m["motor"]["region"],
            ],
        )
        con.execute(VISTAS.read_text(encoding="utf-8"))
    except BaseException:
        con.close()
        raise
    return con
