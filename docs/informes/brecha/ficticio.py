"""A corte of a fictitious organization, read straight from two in-process fakes - no real network, no
secrets file to prepare by hand. Feeds the Metabase isolation smoke test's two organizations, `ficticia-a` and
`ficticia-b` (plan § Rulings: "dos organizaciones ficticias ... con la clave HMAC derivada de la semilla";
srtm-backend#206, task 8).

    python3 -I ficticio.py --org ORG --semilla N --fecha AAAA-MM-DD --salida DIR

`sinteticos.datos(semilla)` feeds `SrtmFalso`/`CatastroFalso`, and the HMAC key is
`sha256(b"ficticia" + str(semilla).encode("ascii")).digest()` - at least the 32 bytes
`herramientas.seudonimo.clave_desde_hex` requires. Both are a pure function of `--semilla`, so two runs with
the same `--semilla` and `--fecha` write byte-identical Parquet files (`test_ficticio_reproducible`), whatever
ports the fakes happen to bind to. `--salida` must sit outside the repository, like any other corte
(`herramientas.comun.carpeta_corte`). Exit code is `corte.main`'s own (0 ok, 2 bad usage, 1 an
API/extraction error); the fakes are always closed, and the temporary secrets file always removed.
"""

import argparse
import hashlib
import os
import sys
import tempfile
from pathlib import Path

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import corte  # noqa: E402
from herramientas import comun  # noqa: E402
from herramientas.falsos import CatastroFalso, SrtmFalso  # noqa: E402
from herramientas.sinteticos import datos  # noqa: E402


def _argumentos():
    parser = argparse.ArgumentParser(prog="ficticio.py", description="Corte de una organización ficticia.")
    parser.add_argument("--org", required=True, help="organización ficticia, p.ej. ficticia-a")
    parser.add_argument("--semilla", required=True, type=int, help="semilla de sinteticos.datos()")
    parser.add_argument("--fecha", required=True, help="AAAA-MM-DD")
    parser.add_argument("--salida", required=True, help="carpeta base del corte (fuera del repositorio)")
    return parser


def _clave(semilla: int) -> bytes:
    return hashlib.sha256(b"ficticia" + str(semilla).encode("ascii")).digest()


def _secretos(ruta: Path, srtm: SrtmFalso, catastro: CatastroFalso, clave: bytes) -> None:
    texto = (
        f"SRTM_URL={srtm.url}\n"
        f"SRTM_CLIENT_ID={srtm.CLIENT_ID}\n"
        f"SRTM_CLIENT_SECRET={srtm.CLIENT_SECRET}\n"
        f"CATASTRO_URL={catastro.url}\n"
        f"CATASTRO_CLIENT_ID={catastro.CLIENT_ID}\n"
        f"CATASTRO_CLIENT_SECRET={catastro.CLIENT_SECRET}\n"
        f"CLAVE_SEUDONIMO={clave.hex()}\n"
    )
    ruta.write_text(texto, encoding="utf-8")
    ruta.chmod(0o600)


def main(argv=None):
    try:
        args = _argumentos().parse_args(argv)
    except SystemExit as e:  # argparse already printed the usage error (2) or the help (0)
        return e.code if isinstance(e.code, int) else 2

    # corte.main validates --org too, but only after this script already built a path from it
    # (<tmp>/<org>.env); checked here first, like corte.py's own usage errors (exit 2)
    try:
        comun.validar_org(args.org)
    except comun.ErrorCorte as e:
        print(f"ficticio: {e}", file=sys.stderr)
        return 2

    s = datos(args.semilla)
    srtm = SrtmFalso(s.srtm)
    catastro = CatastroFalso(
        registros=s.catastro, valuaciones=s.valuaciones, titulares=s.titulares, cambios=s.cambios
    )
    try:
        with tempfile.TemporaryDirectory(prefix="ficticio-secretos-") as tmp:
            secretos = Path(tmp) / f"{args.org}.env"
            _secretos(secretos, srtm, catastro, _clave(args.semilla))
            return corte.main(
                ["--org", args.org, "--fecha", args.fecha, "--salida", args.salida, "--secretos", str(secretos)]
            )
    finally:
        srtm.cerrar()
        catastro.cerrar()


if __name__ == "__main__":
    sys.exit(main())
