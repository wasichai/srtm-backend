"""Takes one organization's corte: srtm and catastro read through their APIs, written as one Parquet file per
table plus manifiesto.json, in <salida>/<org>/<fecha>/ (contexto.md; srtm-backend#205).

    python3 -I corte.py --org perene [--fecha AAAA-MM-DD] [--salida DIR] [--secretos ARCHIVO] [--reemplazar]
    python3 -I corte.py --verificar CARPETA

The corte is written to <carpeta>.parcial and renamed once complete; any error removes it, so a corte either
exists whole or not at all. Exit codes: 0 ok, 2 bad usage (organization, fecha, an existing folder without
--reemplazar, the secrets file), 1 an API, extraction or verification error.
"""

import argparse
import math
import os
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from herramientas import comun, manifiesto, parquet  # noqa: E402
from herramientas.catastro import extraer_catastro  # noqa: E402
from herramientas.cliente import TIMEOUT, Cliente, ErrorApi  # noqa: E402
from herramientas.seudonimo import clave_desde_hex  # noqa: E402
from herramientas.srtm import extraer_srtm  # noqa: E402
from herramientas.tablas import COLUMNAS  # noqa: E402

SALIDA = "~/cortes/analitica"
SECRETOS = "~/.config/srtm-analitica/{org}.env"


def _ahora():
    return datetime.now(timezone.utc)


def _argumentos():
    parser = argparse.ArgumentParser(prog="corte.py", description="Corte analítico de srtm y catastro.")
    parser.add_argument("--org", help="organización, como en parametros.json")
    parser.add_argument("--fecha", help="AAAA-MM-DD; por omisión, hoy en America/Lima")
    parser.add_argument("--salida", default=SALIDA, help=f"carpeta base de los cortes (por omisión {SALIDA})")
    parser.add_argument("--secretos", help=f"archivo de URL, credenciales y clave (por omisión {SECRETOS})")
    parser.add_argument("--reemplazar", action="store_true", help="sobrescribe un corte existente")
    parser.add_argument("--verificar", metavar="CARPETA", help="comprueba un corte contra su manifiesto")
    return parser


def _leer_secretos(ruta):
    try:
        secretos = comun.leer_secretos(ruta)
    except OSError as e:
        raise comun.ErrorCorte(f"no se puede leer el archivo de secretos {ruta}: {e.strerror}") from None
    for prefijo in ("SRTM", "CATASTRO"):
        _timeout(secretos, prefijo)  # a bad value is a usage error (exit 2), before anything is read
    return secretos


def _timeout(secretos, prefijo):
    """The optional <PREFIJO>_TIMEOUT of the secrets file, in seconds (srtm lets each organization override
    catastro's timeout too); cliente.TIMEOUT when absent or blank."""
    clave = f"{prefijo}_TIMEOUT"
    texto = secretos.get(clave)
    if not texto:
        return TIMEOUT
    try:
        segundos = float(texto)
    except ValueError:
        segundos = math.nan
    if not math.isfinite(segundos) or segundos <= 0:
        raise comun.ErrorCorte(
            f"{clave} inválido en el archivo de secretos: debe ser un número de segundos mayor que 0"
        )
    return segundos


def _cliente(secretos, prefijo):
    return Cliente(
        secretos[f"{prefijo}_URL"],
        secretos[f"{prefijo}_CLIENT_ID"],
        secretos[f"{prefijo}_CLIENT_SECRET"],
        timeout=_timeout(secretos, prefijo),
    )


def _backends(secretos):
    # contexto.md: no backend publishes its version through the API; make passes them in the environment
    return {
        "srtm": {"url": secretos["SRTM_URL"], "version": os.environ.get("VERSION_SRTM") or None},
        "catastro": {"url": secretos["CATASTRO_URL"], "version": os.environ.get("VERSION_CATASTRO") or None},
    }


def _publicar(parcial, carpeta):
    """Renames the finished corte into place; a corte being replaced is moved aside first and removed only
    once the new one is in its folder."""
    if not carpeta.exists():
        parcial.rename(carpeta)
        return
    anterior = carpeta.with_name(carpeta.name + ".anterior")
    shutil.rmtree(anterior, ignore_errors=True)
    carpeta.rename(anterior)
    parcial.rename(carpeta)
    shutil.rmtree(anterior)


def cortar(carpeta, org, fecha, secretos, clave, reloj):
    """Reads both APIs, writes the 17 tables and the manifest in <carpeta>.parcial, checks them against the
    manifest and renames the folder into place; returns the manifest. Nothing is left on disk on error."""
    parcial = carpeta.with_name(carpeta.name + ".parcial")
    shutil.rmtree(parcial, ignore_errors=True)  # left behind by a run that was killed
    parcial.mkdir(parents=True)
    try:
        leido_en = reloj()
        extraccion_srtm = extraer_srtm(_cliente(secretos, "SRTM"), fecha.year, clave)
        extraccion_catastro = extraer_catastro(_cliente(secretos, "CATASTRO"), fecha.year, clave)
        tablas = {**extraccion_srtm.tablas, **extraccion_catastro.tablas}
        archivos = [parquet.escribir(tabla, tablas[tabla], parcial) for tabla in COLUMNAS]
        m = manifiesto.construir(
            org, fecha, leido_en, extraccion_srtm, extraccion_catastro, archivos, _backends(secretos), clave
        )
        manifiesto.escribir(m, parcial)
        manifiesto.verificar_corte(parcial)
        _publicar(parcial, carpeta)
    finally:
        shutil.rmtree(parcial, ignore_errors=True)
    return m


def _verificar(carpeta):
    try:
        m = manifiesto.verificar_corte(carpeta)
    except comun.ErrorCorte as e:
        print(f"corte: {e}", file=sys.stderr)
        return 1
    except OSError as e:
        print(f"corte: no se pudo leer {carpeta}: {e.strerror}", file=sys.stderr)
        return 1
    filas = sum(entrada["filas"] for entrada in m["archivos"])
    print(f"corte verificado: {carpeta}: {len(m['archivos'])} tablas, {filas} filas")
    return 0


def main(argv=None, reloj=_ahora, hoy=comun.hoy_lima):
    parser = _argumentos()
    try:
        args = parser.parse_args(argv)
    except SystemExit as e:  # argparse already printed the usage error (2) or the help (0)
        return e.code if isinstance(e.code, int) else 2
    if args.verificar:
        return _verificar(Path(args.verificar).expanduser())
    if not args.org:
        print("corte: falta --org (o --verificar CARPETA)", file=sys.stderr)
        return 2

    try:
        org = comun.validar_org(args.org)
        fecha = comun.validar_fecha(args.fecha, hoy()) if args.fecha else hoy()
        carpeta = comun.carpeta_corte(Path(args.salida).expanduser(), org, fecha, reemplazar=args.reemplazar)
        secretos = _leer_secretos(Path(args.secretos or SECRETOS.format(org=org)).expanduser())
        clave = clave_desde_hex(secretos["CLAVE_SEUDONIMO"])
    except comun.ErrorCorte as e:
        print(f"corte: {e}", file=sys.stderr)
        return 2

    try:
        m = cortar(carpeta, org, fecha, secretos, clave, reloj)
    except (comun.ErrorCorte, ErrorApi, OSError) as e:
        # ErrorApi carries method, route and status only; ErrorCorte never a secret (comun.py)
        print(f"corte {org} {fecha}: {e}", file=sys.stderr)
        return 1
    filas = sum(entrada["filas"] for entrada in m["archivos"])
    print(f"corte {org} {fecha}: {len(m['archivos'])} tablas, {filas} filas -> {carpeta}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
