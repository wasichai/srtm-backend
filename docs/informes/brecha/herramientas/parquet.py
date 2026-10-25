"""One Parquet file per table, byte-identical for the same data (plan § Rulings: Parquet, decimals).

Every value is converted to its column's type first (tablas.ESQUEMAS), so a DECIMAL that arrives as an int, as
text or with more than 10 decimals is quantized ROUND_HALF_EVEN instead of reaching pyarrow as something it
rejects; a value that cannot be converted is an ErrorCorte that names table and column, never an ArrowInvalid.
Rows are then sorted by tablas.ORDEN (None first) and, past those keys, by every other column, so the API's
order never reaches the file.
"""

from datetime import date, datetime, timezone
from decimal import ROUND_HALF_EVEN, Context, Decimal

import pyarrow as pa
import pyarrow.parquet as pq

from . import comun
from .tablas import COLUMNAS, ESQUEMAS, ORDEN

# plan § Rulings, Parquet; pyarrow is pinned because a new version may change these bytes
OPCIONES = {
    "compression": "zstd",
    "compression_level": 3,
    "row_group_size": 100_000,
    "use_dictionary": True,
    "write_statistics": True,
    "version": "2.6",
    "data_page_version": "1.0",
}

_DIEZ_DECIMALES = Decimal("1E-10")
# 38 significant digits: decimal128(38, 10) holds up to 28 integer digits; more is an InvalidOperation
_CONTEXTO = Context(prec=38, rounding=ROUND_HALF_EVEN)


def _decimal(valor):
    if isinstance(valor, float):
        valor = repr(valor)  # the shortest text that round-trips, not the binary expansion
    elif not isinstance(valor, (Decimal, int, str)) or isinstance(valor, bool):
        raise TypeError
    numero = Decimal(valor)
    if not numero.is_finite():
        raise ValueError
    return numero.quantize(_DIEZ_DECIMALES, context=_CONTEXTO)


def _entero(valor):
    if isinstance(valor, bool):
        raise TypeError
    if isinstance(valor, int):
        return valor
    if isinstance(valor, (Decimal, float)) and valor == int(valor):
        return int(valor)
    if isinstance(valor, str):
        return int(valor.strip())  # "1" is 1; "S1" is a ValueError
    raise ValueError


def _logico(valor):
    if isinstance(valor, bool):
        return valor
    raise TypeError


def _fecha(valor):
    if isinstance(valor, date) and not isinstance(valor, datetime):
        return valor
    return date.fromisoformat(valor)


def _instante(valor):
    if not isinstance(valor, datetime):
        valor = datetime.fromisoformat(valor)
    # an instant without an offset is taken as UTC, like wasichai's own DATETIME
    if valor.tzinfo is None:
        valor = valor.replace(tzinfo=timezone.utc)
    return valor.astimezone(timezone.utc)


def _real(valor):
    if isinstance(valor, bool) or not isinstance(valor, (Decimal, int, float)):
        raise TypeError
    return float(valor)


def _texto(valor):
    if isinstance(valor, str):
        return valor
    if isinstance(valor, (Decimal, int)) and not isinstance(valor, bool):
        return str(valor)
    raise TypeError


def _convertidor(tipo):
    if pa.types.is_decimal(tipo):
        return _decimal
    if pa.types.is_integer(tipo):
        return _entero
    if pa.types.is_boolean(tipo):
        return _logico
    if pa.types.is_date(tipo):
        return _fecha
    if pa.types.is_timestamp(tipo):
        return _instante
    if pa.types.is_floating(tipo):
        return _real
    return _texto


def _convertir(tabla, filas):
    """Each row as a tuple in schema order, every value already of its column's Python type."""
    esquema = ESQUEMAS[tabla]
    convertidores = [(campo.name, campo.type, _convertidor(campo.type)) for campo in esquema]
    convertidas = []
    for fila in filas:
        valores = []
        for columna, tipo, convertir in convertidores:
            valor = fila.get(columna)
            if valor is not None:
                try:
                    valor = convertir(valor)
                except (ArithmeticError, TypeError, ValueError):
                    raise comun.ErrorCorte(f"{tabla}.{columna}: no se puede convertir {valor!r:.60} a {tipo}") from None
            valores.append(valor)
        convertidas.append(tuple(valores))
    return convertidas


def _llave(tabla):
    """Sort key: ORDEN[tabla], then every other column in schema order; None sorts first in each."""
    columnas = list(COLUMNAS[tabla])
    orden = list(ORDEN[tabla]) + [c for c in columnas if c not in ORDEN[tabla]]
    indices = [columnas.index(c) for c in orden]
    return lambda fila: tuple((0,) if fila[i] is None else (1, fila[i]) for i in indices)


def escribir(tabla, filas, destino):
    """Writes `destino/<tabla>.parquet` and returns its manifest entry."""
    esquema = ESQUEMAS[tabla]
    convertidas = sorted(_convertir(tabla, filas), key=_llave(tabla))
    arreglos = []
    for i, campo in enumerate(esquema):
        try:
            arreglos.append(pa.array([fila[i] for fila in convertidas], type=campo.type))
        except (pa.ArrowException, OverflowError) as e:
            raise comun.ErrorCorte(f"{tabla}.{campo.name}: {e}") from None
    archivo = f"{tabla}.parquet"
    ruta = destino / archivo
    pq.write_table(pa.Table.from_arrays(arreglos, schema=esquema), ruta, **OPCIONES)
    return {
        "tabla": tabla,
        "archivo": archivo,
        "filas": len(convertidas),
        "bytes": ruta.stat().st_size,
        "sha256": comun.sha256(ruta),
    }
