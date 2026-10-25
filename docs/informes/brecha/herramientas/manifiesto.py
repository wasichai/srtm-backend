"""The corte's manifiesto.json: what was read, from where, with which method, and the sha256 of every file.

contexto.md: the manifest carries no clock but `leido_en`; everything else is a function of the data, the
checkout and the environment, so two cortes of the same data compare with `diff` save that one line.
`verificar_corte` recomputes every sha256 and row count against it (task 6 loads a corte only after that).
"""

import json
import subprocess
from datetime import timezone
from decimal import Decimal
from zoneinfo import ZoneInfo

import pyarrow as pa
import pyarrow.parquet as pq

from . import comun
from .seudonimo import NORMALIZACION, huella_clave

FORMATO = 1
ARCHIVO = "manifiesto.json"

_PARAMETRO = ("tipo", "clave", "vigencia_desde", "vigencia_hasta", "valor_numerico", "texto", "norma")
_AVISO_SIN_VERSION = "sin {variable} en el entorno: backends.{backend}.version es null"
_AVISO_FECHA = (
    "fecha_corte {fecha} no es el día de la lectura ({dia} en America/Lima): las tablas traen los datos del {dia}"
)
# what verificar_corte and base.py read off a manifest: a file without them is refused, never a KeyError
_CLAVES = ("organizacion", "fecha_corte", "tipo", "anio", "leido_en", "motor", "archivos")
_CLAVES_ARCHIVO = ("archivo", "bytes", "sha256", "filas")


def _git(*argumentos):
    try:
        resultado = subprocess.run(["git", *argumentos], cwd=comun.REPO, capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as e:
        raise comun.ErrorCorte(f"no se pudo leer el estado de git para el manifiesto: {e}") from None
    return resultado.stdout


def _metodo():
    # the code that made the corte: its commit, and whether this tool's folder had uncommitted changes
    carpeta = str(comun.INFORME.relative_to(comun.REPO))
    return {
        "commit": _git("rev-parse", "HEAD").strip(),
        "sucio": bool(_git("status", "--porcelain", "--", carpeta).strip()),
    }


def _utc(momento):
    # an instant without an offset is taken as UTC
    if momento.tzinfo is None:
        momento = momento.replace(tzinfo=timezone.utc)
    return momento.astimezone(timezone.utc)


def _instante(momento):
    # written to the second, with a Z
    return _utc(momento).isoformat(timespec="seconds").replace("+00:00", "Z")


def _aviso_fecha(fecha, leido_en):
    """The APIs have no read "as of a date": a FECHA other than the day of the read (in Lima) labels that day's
    data with another date, so the manifest says so. A function of the two dates only, never of the hour."""
    dia = _utc(leido_en).astimezone(ZoneInfo("America/Lima")).date()
    if dia == fecha:
        return None
    return _AVISO_FECHA.format(fecha=fecha.isoformat(), dia=dia.isoformat())


def _parametros(filas):
    """normativa.parametros: the 7 manifest keys of each vigente parámetro, valor_numerico as text, sorted on
    every key (None first) so the API's order never shows."""
    parametros = []
    for fila in filas:
        parametro = {clave: fila.get(clave) for clave in _PARAMETRO}
        if parametro["valor_numerico"] is not None:
            parametro["valor_numerico"] = str(parametro["valor_numerico"])
        parametros.append(parametro)
    return sorted(parametros, key=lambda p: tuple((0, "") if p[c] is None else (1, str(p[c])) for c in _PARAMETRO))


def construir(org, fecha, leido_en, srtm, catastro, archivos, backends, clave):
    corrida = srtm.corrida
    avisos = list(srtm.avisos) + list(catastro.avisos)
    for backend in sorted(backends):
        if backends[backend]["version"] is None:
            avisos.append(_AVISO_SIN_VERSION.format(variable=f"VERSION_{backend.upper()}", backend=backend))
    if aviso := _aviso_fecha(fecha, leido_en):
        avisos.append(aviso)
    return {
        "formato": FORMATO,
        "organizacion": org,
        "fecha_corte": fecha.isoformat(),
        "tipo": "anual" if (fecha.month, fecha.day) == (1, 1) else "mensual",
        "anio": fecha.year,
        "leido_en": _instante(leido_en),
        "motor": {"version": catastro.motor.get("version"), "region": catastro.motor.get("region")},
        "brecha_corrida": None
        if corrida is None
        else {"id": corrida["id"], "terminado": corrida["terminado"], "version_catastro": corrida["version_catastro"]},
        "normativa": {
            "parametros": _parametros(srtm.parametros),
            "aranceles": {
                "anio": fecha.year,
                "total": len(srtm.tablas["srtm_arancel"]),
                "archivo": "srtm_arancel.parquet",
            },
        },
        "backends": {nombre: {"url": b["url"], "version": b["version"]} for nombre, b in backends.items()},
        "metodo": _metodo(),
        "seudonimizacion": {
            "algoritmo": "HMAC-SHA256",
            "normalizacion": NORMALIZACION,
            "huella_clave": huella_clave(clave),
        },
        "avisos": sorted(avisos),
        "archivos": sorted(archivos, key=lambda a: a["archivo"]),
    }


def _como_texto(valor):
    if isinstance(valor, Decimal):
        return str(valor)
    raise TypeError(f"no se puede escribir {type(valor).__name__} en el manifiesto")


def escribir(m, carpeta):
    texto = json.dumps(m, ensure_ascii=False, indent=2, sort_keys=True, default=_como_texto) + "\n"
    (carpeta / ARCHIVO).write_text(texto, encoding="utf-8")


def verificar_corte(carpeta):
    """The manifest of `carpeta` once every listed file is there, with its sha256, size and row count, and no
    other file is; otherwise an ErrorCorte naming each missing, extra or different file."""
    m = _leer(carpeta)
    esperados = {entrada["archivo"]: entrada for entrada in m["archivos"]}
    presentes = {p.name for p in carpeta.iterdir()} - {ARCHIVO}

    distintos = []
    for nombre in sorted(set(esperados) & presentes):
        entrada, archivo = esperados[nombre], carpeta / nombre
        if archivo.stat().st_size != entrada["bytes"] or comun.sha256(archivo) != entrada["sha256"]:
            distintos.append(f"{nombre} (sha256)")
        elif _filas(archivo) != entrada["filas"]:
            distintos.append(f"{nombre} (filas)")

    problemas = []
    if faltantes := sorted(set(esperados) - presentes):
        problemas.append(f"faltan {', '.join(faltantes)}")
    if sobrantes := sorted(presentes - set(esperados)):
        problemas.append(f"sobran {', '.join(sobrantes)}")
    if distintos:
        problemas.append(f"no coinciden {', '.join(distintos)}")
    if problemas:
        raise comun.ErrorCorte(f"el corte {carpeta} no coincide con su manifiesto: {'; '.join(problemas)}")
    return m


def _leer(carpeta):
    """The manifest of `carpeta`, or an ErrorCorte when it is missing, is not UTF-8 JSON or lacks the keys
    verificar_corte and base.py read (one line, never a traceback)."""
    ruta = carpeta / ARCHIVO
    if not ruta.is_file():
        raise comun.ErrorCorte(f"falta {ARCHIVO} en {carpeta}")
    try:
        m = json.loads(ruta.read_text(encoding="utf-8"))
    except ValueError:  # JSONDecodeError and UnicodeDecodeError
        raise comun.ErrorCorte(f"{ARCHIVO} ilegible en {carpeta}: no es JSON en UTF-8") from None
    completo = (
        isinstance(m, dict)
        and all(clave in m for clave in _CLAVES)
        and isinstance(m["motor"], dict)
        and isinstance(m["archivos"], list)
        and all(isinstance(e, dict) and all(clave in e for clave in _CLAVES_ARCHIVO) for e in m["archivos"])
    )
    if not completo:
        raise comun.ErrorCorte(f"{ARCHIVO} incompleto en {carpeta}: le faltan llaves del formato {FORMATO}")
    return m


def _filas(archivo):
    # a file whose sha256 matched but that pyarrow cannot read counts as different (None never equals filas)
    try:
        return pq.read_metadata(archivo).num_rows
    except (OSError, pa.ArrowException):
        return None
