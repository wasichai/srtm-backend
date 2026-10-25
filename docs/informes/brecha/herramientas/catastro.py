"""catastro-backend extraction for the corte (contexto.md § Tablas; contratos.md §1-§3; controller ruling R1).

`extraer_catastro` reads catastro's five sources for one año: the three generic objects it shares with srtm's
API shape (`predio_catastral`, `construccion`, `obra_complementaria`), and the three dedicated endpoints
(valuaciones, titulares, cambios). The generic objects are already snake_case (seudonimo.py's own worktree
DTOs, controller ruling R1); the dedicated endpoints answer camelCase (contratos.md §1-§3), so every item is
remapped by hand to the table's snake_case column. `construccion`/`obra_complementaria`'s own `codigo_municipal`
attribute is never trusted: it is looked up from the ficha their `predio` points to, so an orphan (a predio
that matches no ficha) exports `None` instead of whatever stale value the row happened to carry.

Titulares and cambios are tolerated missing (an older catastro-backend without those routes, catastro-backend
issues #25 and #26): a 404 becomes an empty table and an aviso, like srtm.py's own absence-tolerance. Missing
§1 keys (an engine before the seven new ones) are tolerated the same way `_fila` tolerates a missing attribute
in srtm.py: `.get()` everywhere, `None` when absent, never a KeyError.
"""

import json
from dataclasses import dataclass
from decimal import Decimal

from .cliente import ErrorApi
from .seudonimo import seudonimo

# contexto.md § Tablas, in the order given there
COLUMNAS = {
    "catastro_ficha": (
        "id",
        "codigo_municipal",
        "codigo_cpu",
        "sector",
        "manzana",
        "lote",
        "id_aranc",
        "cod_via",
        "cuadra",
        "lado",
        "uso",
        "area_terreno",
        "longitud_frente",
        "es_edificio",
        "tipo_predio",
        "estado_levantamiento",
        "fecha_levantamiento",
        "vinculo_metodo",
        "vinculo_revisado",
        "cuc",
        "codigo_referencia_catastral",
    ),
    "catastro_construccion": (
        "id",
        "predio",
        "codigo_municipal",
        "piso",
        "seccion",
        "area_construida",
        "anio_construccion",
        "material",
        "estado_conservacion",
        "muros_columnas",
        "techos",
        "pisos",
        "puertas_ventanas",
        "revestimientos",
        "banos",
        "instalaciones",
        "techo_incluido",
        "clasificacion",
    ),
    "catastro_obra": (
        "id",
        "predio",
        "codigo_municipal",
        "codigo_vu",
        "cantidad",
        "anio_construccion",
        "material",
        "estado_conservacion",
    ),
    "catastro_titular": ("codigo_municipal", "tipo_documento", "documento_hmac", "porcentaje", "condicion"),
    "catastro_valuacion": (
        "codigo_municipal",
        "codigo_cpu",
        "sector",
        "manzana",
        "lote",
        "uso",
        "area_terreno",
        "area_construida",
        "longitud_frente",
        "terreno",
        "construccion",
        "obras",
        "autoavaluo",
        "motivo",
        "llave",
        "estado_levantamiento",
        "fecha_levantamiento",
        "entrega",
        "vinculo_metodo",
        "vinculo_revisado",
        "cuc",
        "codigo_referencia_catastral",
    ),
    "catastro_lote": ("codigo_municipal", "lon", "lat", "geojson"),
    "catastro_cambio": ("codigo_municipal", "objeto", "operacion", "campos", "en", "entrega"),
}

_AVISO_SIN_TITULARES = "catastro sin /api/catastro/titulares (catastro-backend#25): se exporta la tabla vacía"
_AVISO_SIN_CAMBIOS = "catastro sin /api/catastro/cambios (catastro-backend#26): se exporta la tabla vacía"
# srtm's own valuaciones DTO has no region: a real catastro may well answer without one
_AVISO_SIN_MOTOR = "catastro sin {campo} en /api/catastro/valuaciones: motor.{campo} es null"

_TAMANO_PAGINA = 500


@dataclass
class ExtraccionCatastro:
    tablas: dict[str, list[dict]]
    motor: dict
    avisos: list[str]


def _fila(columnas, registro):
    """A row with exactly `columnas`: attributes are already snake_case here (R1) - the same absence-tolerant
    shape as srtm.py's `_fila` (a missing attribute is `None`, never a KeyError)."""
    atributos = registro.get("attributes", {})
    fila = {columna: atributos.get(columna) for columna in columnas}
    fila["id"] = registro["id"]
    return fila


def _fila_hija(columnas, registro, codigo_por_ficha):
    """construccion/obra_complementaria: same as `_fila`, but `codigo_municipal` always comes from the ficha
    its `predio` points to, never from the row's own attribute (an orphan predio -> None)."""
    fila = _fila(columnas, registro)
    atributos = registro.get("attributes", {})
    fila["codigo_municipal"] = codigo_por_ficha.get(atributos.get("predio"))
    return fila


def _fila_titular(item, clave):
    # contratos.md §2: camelCase in, snake_case out; numeroDocumento never leaves as itself (nombre tampoco sale)
    return {
        "codigo_municipal": item.get("codigoMunicipal"),
        "tipo_documento": item.get("tipoDocumento"),
        "documento_hmac": seudonimo(clave, item.get("numeroDocumento")),
        "porcentaje": item.get("porcentaje"),
        "condicion": item.get("condicion"),
    }


def _fila_valuacion(item):
    # contratos.md §1: codigoMunicipal/codigoCpu/areaTerreno/areaConstruida/longitudFrente plus §1's seven keys
    return {
        "codigo_municipal": item.get("codigoMunicipal"),
        "codigo_cpu": item.get("codigoCpu"),
        "sector": item.get("sector"),
        "manzana": item.get("manzana"),
        "lote": item.get("lote"),
        "uso": item.get("uso"),
        "area_terreno": item.get("areaTerreno"),
        "area_construida": item.get("areaConstruida"),
        "longitud_frente": item.get("longitudFrente"),
        "terreno": item.get("terreno"),
        "construccion": item.get("construccion"),
        "obras": item.get("obras"),
        "autoavaluo": item.get("autoavaluo"),
        "motivo": item.get("motivo"),
        "llave": item.get("llave"),
        "estado_levantamiento": item.get("estadoLevantamiento"),
        "fecha_levantamiento": item.get("fechaLevantamiento"),
        "entrega": item.get("entrega"),
        "vinculo_metodo": item.get("vinculoMetodo"),
        "vinculo_revisado": item.get("vinculoRevisado"),
        "cuc": item.get("cuc"),
        "codigo_referencia_catastral": item.get("codigoReferenciaCatastral"),
    }


def _fila_lote(item):
    """catastro_lote: only valuaciones that carry a `loteGeom` (contexto.md: the lote's geometry comes from
    there). The centroid and the GeoJSON both go through the same float coordinates - `Cliente` parses JSON
    with `parse_float=Decimal`, which `json.dumps` cannot serialize on its own."""
    geometria = item.get("loteGeom")
    if not geometria:
        return None
    centro = centroide(geometria)
    if centro is None:
        return None
    lon, lat = centro
    geometria_float = _a_float(geometria)
    return {
        "codigo_municipal": item.get("codigoMunicipal"),
        "lon": lon,
        "lat": lat,
        "geojson": json.dumps(geometria_float, sort_keys=True, separators=(",", ":")),
    }


def _fila_cambio(item):
    # contratos.md §3; "campos" is a list of field names - sorted so two cortes of the same change agree on order
    campos = sorted(item.get("campos") or [])
    return {
        "codigo_municipal": item.get("codigoMunicipal"),
        "objeto": item.get("objeto"),
        "operacion": item.get("operacion"),
        "campos": ",".join(campos),
        "en": item.get("en"),
        "entrega": item.get("entrega"),
    }


def _a_float(valor):
    # recursively turns every Decimal/int/float leaf into a plain float, keeping strings (type) and None as-is
    if isinstance(valor, list):
        return [_a_float(v) for v in valor]
    if isinstance(valor, dict):
        return {clave: _a_float(v) for clave, v in valor.items()}
    if isinstance(valor, (Decimal, int, float)) and not isinstance(valor, bool):
        return float(valor)
    return valor


def _anillo_cerrado(anillo):
    """Closes the ring (if it isn't already) and re-bases it on its own first vertex: lon/lat sit around
    ±75/±14 while a lote's ring spans a few 1e-4 degrees, so the raw shoelace sums below would subtract two
    ~1000-magnitude products to get an 1e-8 one and lose every digit that matters - translating first keeps
    every coordinate small, where the same formula is accurate."""
    x0, y0 = float(anillo[0][0]), float(anillo[0][1])
    puntos = [(float(x) - x0, float(y) - y0) for x, y in anillo]
    if puntos and puntos[0] != puntos[-1]:
        puntos.append(puntos[0])
    return puntos, x0, y0


def _area_signada(anillo):
    # the shoelace formula; its sign also tells a ring's winding, not used here beyond abs() for size
    return sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(anillo, anillo[1:])) / 2


def _centroide_anillo(anillo, area):
    cx = cy = 0.0
    for (x0, y0), (x1, y1) in zip(anillo, anillo[1:]):
        cruzado = x0 * y1 - x1 * y0
        cx += (x0 + x1) * cruzado
        cy += (y0 + y1) * cruzado
    factor = 1 / (6 * area)
    return cx * factor, cy * factor


def centroide(geometria):
    """The area centroid of the exterior ring of the largest polygon in a GeoJSON Polygon or MultiPolygon,
    rounded to 7 decimals as `(lon, lat)` - Metabase OSS maps points, not polygons (contexto.md), so this is
    all `catastro_lote` needs. `None` for anything else (no geometry, an empty ring, a degenerate polygon)."""
    if not geometria:
        return None
    tipo = geometria.get("type")
    coordenadas = geometria.get("coordinates")
    if tipo == "Polygon":
        poligonos = [coordenadas] if coordenadas else []
    elif tipo == "MultiPolygon":
        poligonos = coordenadas or []
    else:
        return None
    anillos = [_anillo_cerrado(p[0]) for p in poligonos if p and p[0]]
    anillos = [a for a in anillos if len(a[0]) >= 4]
    if not anillos:
        return None
    anillo_mas_grande, x0, y0 = max(anillos, key=lambda a: abs(_area_signada(a[0])))
    area = _area_signada(anillo_mas_grande)
    if area == 0:
        return None
    cx, cy = _centroide_anillo(anillo_mas_grande, area)
    return round(cx + x0, 7), round(cy + y0, 7)


def extraer_catastro(cliente, anio, clave):
    fichas = list(cliente.registros("predio_catastral"))
    construcciones = list(cliente.registros("construccion"))
    obras = list(cliente.registros("obra_complementaria"))
    codigo_por_ficha = {f["id"]: f["attributes"].get("codigo_municipal") for f in fichas}

    tablas = {
        "catastro_ficha": [_fila(COLUMNAS["catastro_ficha"], f) for f in fichas],
        "catastro_construccion": [
            _fila_hija(COLUMNAS["catastro_construccion"], c, codigo_por_ficha) for c in construcciones
        ],
        "catastro_obra": [_fila_hija(COLUMNAS["catastro_obra"], o, codigo_por_ficha) for o in obras],
    }

    valuaciones = list(cliente.paginas("/api/catastro/valuaciones", _TAMANO_PAGINA, anio=anio))
    # controller ruling R11: the motor's version/region are the top-level fields of that same call's first page
    motor = {"version": cliente.ultima_pagina.get("version"), "region": cliente.ultima_pagina.get("region")}
    avisos = [_AVISO_SIN_MOTOR.format(campo=campo) for campo in ("version", "region") if motor[campo] is None]
    tablas["catastro_valuacion"] = [_fila_valuacion(v) for v in valuaciones]
    filas_lote = (_fila_lote(v) for v in valuaciones)
    tablas["catastro_lote"] = [fila for fila in filas_lote if fila is not None]

    try:
        titulares = list(cliente.paginas("/api/catastro/titulares", _TAMANO_PAGINA))
    except ErrorApi as e:
        if e.estado != 404:
            raise
        avisos.append(_AVISO_SIN_TITULARES)
        titulares = []
    tablas["catastro_titular"] = [_fila_titular(t, clave) for t in titulares]

    try:
        cambios = list(cliente.feed("/api/catastro/cambios", _TAMANO_PAGINA))
    except ErrorApi as e:
        if e.estado != 404:
            raise
        avisos.append(_AVISO_SIN_CAMBIOS)
        cambios = []
    tablas["catastro_cambio"] = [_fila_cambio(c) for c in cambios]

    return ExtraccionCatastro(tablas=tablas, motor=motor, avisos=avisos)
