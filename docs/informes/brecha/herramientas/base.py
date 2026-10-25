"""A corte loaded into a DuckDB file: one materialized table per Parquet, the manifest's figures in
`corte_manifiesto`, and the views of vistas.sql (plan § Rulings: tables, not views over the files, so Metabase opens
the file read-only with external access off; `duckdb` is pinned to the driver's 1.5.5 for the same storage
format).

`INIT_SQL` is the `init_sql` Metabase's DuckDB connections run right after opening a file read-only (plan §
Rulings, aislamiento): it turns off `enable_external_access` (no ATTACH, no read_parquet/read_csv of another
path) and locks that setting so a later `SET` cannot turn it back on - one organization's native query can
never reach another organization's file.

`construir_atomico` is how `armar_duckdb.py --org` (and Metabase's `make duckdb`) rebuild a file in place:
plan, Rulings de la ejecución, R21, requires a FRESH file on every rebuild, never one opened on top of the
previous build, because `CREATE OR REPLACE` only touches the tables and views vistas.sql still names - a
table or view dropped from ESQUEMAS or vistas.sql would otherwise survive forever in a file that keeps getting
rebuilt onto itself.
"""

import os
from pathlib import Path

import duckdb

from . import comun, manifiesto
from .tablas import ESQUEMAS

VISTAS = comun.INFORME / "vistas.sql"

INIT_SQL = "SET enable_external_access = false; SET lock_configuration = true;"


def construir_duckdb(carpeta: Path, destino: Path | str) -> duckdb.DuckDBPyConnection:
    """Checks the corte in `carpeta` against its manifest (an ErrorCorte before `destino` is touched), then loads
    it into `destino` and runs vistas.sql; returns the open connection. Tables and views are created or
    replaced, so building onto an existing file leaves it holding this corte.

    corte_manifiesto also carries brecha_corrida_id and version_catastro (both NULL when the manifest's
    brecha_corrida is, i.e. no corrida TERMINADA), huella_clave, and avisos (the manifest's own list, joined
    with "\\n" into one VARCHAR - simpler for a Metabase scalar card than a LIST column; NULL when there is
    none) - plan, Rulings de la ejecución, R21."""
    m = manifiesto.verificar_corte(carpeta)
    con = duckdb.connect(str(destino))
    try:
        for tabla in ESQUEMAS:
            con.execute(
                f"CREATE OR REPLACE TABLE {tabla} AS SELECT * FROM read_parquet(?)",
                [str(carpeta / f"{tabla}.parquet")],
            )
        corrida = m.get("brecha_corrida") or {}
        con.execute(
            "CREATE OR REPLACE TABLE corte_manifiesto AS SELECT"
            " CAST(? AS VARCHAR) AS organizacion, CAST(? AS DATE) AS fecha_corte, CAST(? AS VARCHAR) AS tipo,"
            " CAST(? AS INTEGER) AS anio, CAST(? AS TIMESTAMPTZ) AS leido_en, CAST(? AS VARCHAR) AS version_motor,"
            " CAST(? AS VARCHAR) AS region, CAST(? AS VARCHAR) AS brecha_corrida_id,"
            " CAST(? AS VARCHAR) AS version_catastro, CAST(? AS VARCHAR) AS huella_clave,"
            " CAST(? AS VARCHAR) AS avisos",
            [
                m["organizacion"],
                m["fecha_corte"],
                m["tipo"],
                m["anio"],
                m["leido_en"],
                m["motor"]["version"],
                m["motor"]["region"],
                corrida.get("id"),
                corrida.get("version_catastro"),
                m["seudonimizacion"]["huella_clave"],
                "\n".join(m["avisos"]) if m["avisos"] else None,
            ],
        )
        con.execute(VISTAS.read_text(encoding="utf-8"))
    except BaseException:
        con.close()
        raise
    return con


def construir_atomico(carpeta: Path, destino: Path) -> None:
    """Builds `destino` from the corte in `carpeta` without ever exposing a half-built or stale file: it loads
    into `destino.nuevo` (removed first if a killed run left one behind, so it is always a fresh DuckDB file -
    plan, Rulings de la ejecución, R21), runs CHECKPOINT so every page is on disk before the swap, closes it,
    sets it 0o644 (Metabase's own user only needs read access) and moves it onto `destino` with `os.replace`
    (atomic on the same filesystem). On any error - a corte that fails verification, a DuckDB error, a full
    disk - the temporary file is removed and `destino` is left exactly as it was."""
    nuevo = destino.with_name(destino.name + ".nuevo")
    nuevo.unlink(missing_ok=True)
    try:
        con = construir_duckdb(carpeta, nuevo)
        try:
            con.execute("CHECKPOINT")
        finally:
            con.close()
        nuevo.chmod(0o644)
        os.replace(nuevo, destino)
    except BaseException:
        nuevo.unlink(missing_ok=True)
        raise
