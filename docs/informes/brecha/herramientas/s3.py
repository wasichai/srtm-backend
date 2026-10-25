"""Downloads an organization's latest published corte from S3, for `armar_duckdb.py --org` (plan § Rulings:
S3 key `s3://sgtm-dev/analitica/<org>/<fecha>/`; srtm-backend#206, task 8).

Both steps shell out to the AWS CLI through `ejecutar` (`subprocess.run` by default, replaced only in tests):
each call is an argv list, never a string a shell could reinterpret.
"""

import re
import subprocess
from pathlib import Path

from . import comun, manifiesto

# aws s3 ls <prefijo>/ lists each published fecha as a "PRE" common prefix, e.g. "                           PRE
# 2026-11-01/"; plain objects (like a stray manifiesto.json at the prefix root) and a non-date prefix (a "tmp/"
# left by hand) are not "PRE AAAA-MM-DD/" and so never match.
_PRE = re.compile(r"PRE\s+(\d{4}-\d{2}-\d{2})/\s*$")


def ultimo_corte(listado: str) -> str | None:
    """The greatest AAAA-MM-DD among `listado`'s "PRE <fecha>/" lines (ISO dates sort the same way as text and
    as numbers); None when there is none - an empty prefix, or one with no date-shaped subfolder."""
    fechas = [coincide.group(1) for coincide in map(_PRE.search, listado.splitlines()) if coincide]
    return max(fechas) if fechas else None


def descargar(s3_base: str, org: str, cortes: Path, ejecutar=subprocess.run) -> Path:
    """Lists `<s3_base>/<org>/`, syncs its latest fecha into `cortes/<org>/<fecha>/` and checks the result
    against its manifiesto.json (an ErrorCorte before `armar_duckdb.py` trusts it); returns that folder. An
    ErrorCorte also names the organization when nothing is published there yet.

    `org` is validated first (armar_duckdb.py already does, but this is a public helper on its own): an
    unvalidated org becomes both a filesystem path segment (`cortes/org/fecha`) and an S3 key segment, so
    something like "../x" must never reach `ejecutar` or the filesystem."""
    comun.validar_org(org)
    listado = ejecutar(["aws", "s3", "ls", f"{s3_base}/{org}/"], capture_output=True, text=True, check=True)
    fecha = ultimo_corte(listado.stdout)
    if fecha is None:
        raise comun.ErrorCorte(f"no hay ningún corte publicado para {org} en {s3_base}")
    carpeta = cortes / org / fecha
    carpeta.mkdir(parents=True, exist_ok=True)
    ejecutar(
        ["aws", "s3", "sync", f"{s3_base}/{org}/{fecha}/", str(carpeta), "--only-show-errors"],
        check=True,
    )
    manifiesto.verificar_corte(carpeta)
    return carpeta
