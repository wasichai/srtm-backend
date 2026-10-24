"""Lo que comparten los scripts del corte: parámetros, fechas, la carpeta del corte y el archivo de secretos.

Un corte es una carpeta FUERA del repositorio (contexto.md: URLs, credenciales y la clave HMAC van en
~/.config/srtm-analitica/<org>.env, no en parametros.json). Las rutas del repositorio solo sirven para rechazar una
carpeta de corte que caería dentro de él.
"""

import hashlib
import json
import pathlib
import re
from datetime import date, datetime
from zoneinfo import ZoneInfo

INFORME = pathlib.Path(__file__).resolve().parent.parent
REPO = INFORME.parent.parent.parent

_ORG = re.compile(r"^[a-z][a-z0-9-]{1,30}$")

# nombres de las claves del archivo <org>.env; sus valores nunca van en un mensaje de error
REQUERIDOS = (
    "SRTM_URL",
    "SRTM_CLIENT_ID",
    "SRTM_CLIENT_SECRET",
    "CATASTRO_URL",
    "CATASTRO_CLIENT_ID",
    "CATASTRO_CLIENT_SECRET",
    "CLAVE_SEUDONIMO",
)


class ErrorCorte(Exception):
    """Un error del corte con un mensaje seguro de imprimir (nunca un secreto)."""


def parametros():
    return json.loads((INFORME / "parametros.json").read_text(encoding="utf-8"))


def validar_org(org):
    if not _ORG.fullmatch(org):
        raise ErrorCorte(f"organización inválida: {org!r}")
    return org


def hoy_lima():
    return datetime.now(ZoneInfo("America/Lima")).date()


def validar_fecha(texto, hoy):
    try:
        fecha = date.fromisoformat(texto)
    except ValueError as e:
        raise ErrorCorte(f"fecha inválida: {texto!r}") from e
    if fecha > hoy:
        raise ErrorCorte(f"fecha futura: {texto}")
    return fecha


def carpeta_corte(base, org, fecha, reemplazar=False):
    # una carpeta dentro del repositorio publicaría el corte (y sus documentos seudonimizados) en el commit
    base_resuelta = base.resolve()
    if base_resuelta == REPO or REPO in base_resuelta.parents:
        raise ErrorCorte(f"la carpeta del corte no puede estar dentro del repositorio: {base}")
    carpeta = base / org / fecha.isoformat()
    if carpeta.exists() and not reemplazar:
        raise ErrorCorte(f"la carpeta ya existe: {carpeta} (usa REEMPLAZAR=1 para sobrescribirla)")
    return carpeta


def leer_secretos(ruta):
    # el archivo trae credenciales en claro: un modo con permisos de grupo u otros se rechaza sin leerlo
    modo = ruta.stat().st_mode
    if modo & 0o077:
        raise ErrorCorte(f"permisos demasiado abiertos en {ruta}: debe ser 0600 (chmod 600 {ruta})")
    secretos = {}
    for linea in ruta.read_text(encoding="utf-8").splitlines():
        linea = linea.strip()
        if not linea or linea.startswith("#"):
            continue
        clave, _, valor = linea.partition("=")
        secretos[clave.strip()] = valor.strip()
    faltantes = [clave for clave in REQUERIDOS if clave not in secretos]
    if faltantes:
        raise ErrorCorte(f"faltan claves en {ruta}: {', '.join(faltantes)}")
    return secretos


def sha256(ruta):
    suma = hashlib.sha256()
    with open(ruta, "rb") as entrada:
        for bloque in iter(lambda: entrada.read(1 << 20), b""):
            suma.update(bloque)
    return suma.hexdigest()
