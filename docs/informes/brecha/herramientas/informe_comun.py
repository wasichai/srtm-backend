"""What the informe de brechas shares (srtm-backend#301): the edición folder, its edicion.json, the corte's
DuckDB file and the Spanish number formatting of the report.

An edición is a folder OUTSIDE the repository, like docs/informes/recaudacion's: edicion.json (organization,
corte and, optionally, the corte anterior), textos/ (the narrative, one AsciiDoc-like file per section),
generado/ (indicadores.json, cifras.adoc and the SVG charts) and salida/ (the self-contained HTML). It only
ever holds aggregates of the corte, but those aggregates and the narrative are the municipality's, so they
never go into the repository either.
"""

import json
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from . import comun

PLANTILLA = comun.INFORME / "plantilla"
ARCHIVO_EDICION = "edicion.json"
HTML = "informe-brechas.html"

MESES = (
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)


def carpeta_edicion(ruta):
    """The edición's folder, resolved; an ErrorCorte when it would fall inside the repository."""
    carpeta = Path(ruta).expanduser().resolve()
    if carpeta == comun.REPO or comun.REPO in carpeta.parents:
        raise comun.ErrorCorte(f"la edición no puede estar dentro del repositorio: {carpeta}")
    return carpeta


def ruta_duckdb(corte):
    """The DuckDB file of a corte: the path itself when it is a .duckdb, else <carpeta>.duckdb (make local)."""
    ruta = Path(corte).expanduser()
    if ruta.suffix == ".duckdb":
        return ruta.resolve()
    return ruta.resolve().with_name(ruta.resolve().name + ".duckdb")


def leer_edicion(carpeta):
    ruta = carpeta / ARCHIVO_EDICION
    if not ruta.is_file():
        raise comun.ErrorCorte(f"no hay {ARCHIVO_EDICION} en {carpeta}: crea la edición con make nueva-edicion")
    try:
        datos = json.loads(ruta.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise comun.ErrorCorte(f"{ruta} no es JSON válido: {e}") from None
    for clave in ("organizacion", "municipalidad", "corte"):
        if not datos.get(clave):
            raise comun.ErrorCorte(f"{ruta}: falta «{clave}»")
    return datos


def escribir_json(ruta, datos):
    ruta.parent.mkdir(parents=True, exist_ok=True)
    ruta.write_text(json.dumps(datos, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")


def umbral():
    """The smallest number of predios (or contribuyentes) a published amount may add up (parametros.json)."""
    return int(comun.parametros().get("informe", {}).get("umbral_celda", 5))


# --- formatting --------------------------------------------------------------------------------------------
# Spanish style, as recaudacion/herramientas/graficos.py: a space between thousands, a comma for decimals, a
# real minus sign. Amounts are shown in whole soles: a céntimo never reaches the report.


def entero(valor):
    """Rounded half up to a whole number, an int (None stays None)."""
    if valor is None:
        return None
    return int(Decimal(str(valor)).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def numero(valor, decimales=0, signo=False):
    if valor is None:
        return "—"
    d = Decimal(str(valor)).quantize(Decimal(1).scaleb(-decimales), rounding=ROUND_HALF_UP)
    texto = f"{abs(d):,.{decimales}f}".replace(",", " ").replace(".", ",")
    if d < 0:
        return "−" + texto
    return ("+" + texto) if signo and d > 0 else texto


def soles(valor, signo=False):
    """S/ 1 234; with signo, +S/ 1 234 and −S/ 1 234 (a negative always carries its sign)."""
    if valor is None:
        return "—"
    texto = numero(valor, 0, signo)
    if texto[0] in "+−":
        return texto[0] + "S/ " + texto[1:]
    return "S/ " + texto


def porcentaje(valor, signo=False):
    return "—" if valor is None else numero(valor, 1, signo) + " %"


def fecha_larga(texto):
    """2026-11-01 → 1 de noviembre de 2026."""
    if not texto:
        return "—"
    anio, mes, dia = (int(p) for p in str(texto)[:10].split("-"))
    return f"{dia} de {MESES[mes - 1]} de {anio}"
