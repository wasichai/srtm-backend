"""Downloads an organization's newest complete corte from S3, for `armar_duckdb.py --org` (plan § Rulings:
S3 key `s3://sgtm-dev/analitica/<org>/<fecha>/`; srtm-backend#206, task 8).

Both steps shell out to the AWS CLI through `ejecutar` (`subprocess.run` by default, replaced only in tests):
each call is an argv list, never a string a shell could reinterpret.
"""

import re
import shutil
import subprocess
import sys
from pathlib import Path

from . import comun, manifiesto

# aws s3 ls <prefijo>/ lists each published fecha as a "PRE" common prefix, e.g. "                           PRE
# 2026-11-01/"; plain objects (like a stray manifiesto.json at the prefix root) and a non-date prefix (a "tmp/"
# left by hand) are not "PRE AAAA-MM-DD/" and so never match.
_PRE = re.compile(r"PRE\s+(\d{4}-\d{2}-\d{2})/\s*$")


def fechas_publicadas(listado: str) -> list[str]:
    """The AAAA-MM-DD of `listado`'s "PRE <fecha>/" lines, newest first (ISO dates sort the same way as text
    and as numbers); [] when there is none - an empty prefix, or one with no date-shaped subfolder."""
    return sorted({coincide.group(1) for coincide in map(_PRE.search, listado.splitlines()) if coincide}, reverse=True)


def _listar(ruta: str, ejecutar) -> str:
    """`aws s3 ls <ruta>`'s output. The CLI exits 1 with no output at all when nothing matches (an empty or
    missing prefix, a missing object): that is "", not an error. Exit 1 with something on stderr (credentials,
    network, a bucket that does not exist), or any other non-zero exit, is a CalledProcessError."""
    resultado = ejecutar(["aws", "s3", "ls", ruta], capture_output=True, text=True, check=False)
    if resultado.returncode == 1 and not (resultado.stdout or "").strip() and not (resultado.stderr or "").strip():
        return ""
    if resultado.returncode != 0:
        raise subprocess.CalledProcessError(
            resultado.returncode, resultado.args, output=resultado.stdout, stderr=resultado.stderr
        )
    return resultado.stdout


def _tiene_manifiesto(s3_base: str, org: str, fecha: str, ejecutar) -> bool:
    """Whether `<s3_base>/<org>/<fecha>/manifiesto.json` exists. `make publicar` uploads it last, so a fecha
    without one is a publish in progress or cut short, never a corte to read. `aws s3 ls` of a key lists every
    object starting with it: only a line ending in exactly " manifiesto.json" counts."""
    listado = _listar(f"{s3_base}/{org}/{fecha}/manifiesto.json", ejecutar)
    return any(linea.rstrip().endswith(" manifiesto.json") for linea in listado.splitlines())


def descargar(s3_base: str, org: str, cortes: Path, ejecutar=subprocess.run) -> Path:
    """Lists `<s3_base>/<org>/`, syncs its newest complete corte (the newest fecha with a manifiesto.json; a
    newer one without it is skipped with a notice on stderr) into a fresh `cortes/<org>/<fecha>/` and checks
    the result against its manifiesto.json (an ErrorCorte before `armar_duckdb.py` trusts it); returns that
    folder. An ErrorCorte also names the organization when nothing is published there yet, and the newest fecha
    when no fecha has its manifest.

    The folder is emptied first: a cut or older download of the same fecha must never leave files that the new
    one does not have (`aws s3 sync` only adds and overwrites by size and date). Older fechas stay under
    `cortes/<org>/`; nothing prunes them (docs/develop/metabase-cortes.md, §3).

    `org` is validated first (armar_duckdb.py already does, but this is a public helper on its own): an
    unvalidated org becomes both a filesystem path segment (`cortes/org/fecha`) and an S3 key segment, so
    something like "../x" must never reach `ejecutar` or the filesystem."""
    comun.validar_org(org)
    listado = _listar(f"{s3_base}/{org}/", ejecutar)
    fechas = fechas_publicadas(listado)
    if not fechas:
        raise comun.ErrorCorte(f"no hay ningún corte publicado para {org} en {s3_base}")
    fecha = next((f for f in fechas if _tiene_manifiesto(s3_base, org, f, ejecutar)), None)
    if fecha is None:
        raise comun.ErrorCorte(
            f"ningún corte de {org} en {s3_base} tiene manifiesto.json (el más reciente: {fechas[0]}); "
            "¿una publicación a medias?"
        )
    if fecha != fechas[0]:
        saltadas = ", ".join(f for f in fechas if f > fecha)
        aviso = f"aviso: {org} {saltadas} sin manifiesto.json (¿publicación a medias?); se usa {fecha}"
        print(aviso, file=sys.stderr)
    carpeta = cortes / org / fecha
    if carpeta.exists():
        shutil.rmtree(carpeta)
    carpeta.mkdir(parents=True)
    ejecutar(
        ["aws", "s3", "sync", f"{s3_base}/{org}/{fecha}/", str(carpeta), "--only-show-errors"],
        check=True,
    )
    manifiesto.verificar_corte(carpeta)
    return carpeta
