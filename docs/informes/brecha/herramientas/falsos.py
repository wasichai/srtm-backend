"""Fake wasichai/catastro-backend servers for cliente.py's tests.

Modeled on model/fake_core.py's style (a ThreadingHTTPServer scripted through a `.falso` attribute, a lock, a
`.peticiones` log) without importing it: that fake speaks Core's generic object API; this one also speaks
catastro-backend's dedicated endpoints (contratos.md §1-§3). Test helper only.
"""

import base64
import json
import random
import re
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_RUTA_REGISTROS = re.compile(r"^/api/objects/([a-z0-9_]+)/records$")

# contratos.md §1: these seven keys are always present on a valuaciones item (null when there's no data)
_LLAVES_NUEVAS_VALUACION = (
    "estadoLevantamiento",
    "fechaLevantamiento",
    "entrega",
    "vinculoMetodo",
    "vinculoRevisado",
    "cuc",
    "codigoReferenciaCatastral",
)


def _parsear_query(query):
    # keep_blank_values=True: an explicit desde="" must stay distinguishable from an absent desde (§3's 422)
    return {clave: valores[0] for clave, valores in urllib.parse.parse_qs(query, keep_blank_values=True).items()}


def _indice_de(cursor):
    """The row index a base64 cursor (nextCursor/desde) encodes; an empty or missing one is the start."""
    if not cursor:
        return 0
    return int(base64.b64decode(cursor.encode("ascii")).decode("ascii"))


def _cursor(indice):
    return base64.b64encode(str(indice).encode("ascii")).decode("ascii")


class _ErrorParametro(Exception):
    """Internal signal: a page/size parameter failed contratos.md's bound -> the route answers 422 with this
    message (never raised past the module: every caller below catches it)."""


def _entero(params, clave, por_omision):
    valor = params.get(clave, por_omision)
    try:
        return int(valor)
    except (TypeError, ValueError):
        raise _ErrorParametro(f"{clave} inválido: {valor!r}") from None


def _tamano(params, por_omision, maximo=500):
    """contratos.md §2/§3: size 1..maximo (titulares/valuaciones 500, cambios 200 by default)."""
    size = _entero(params, "size", por_omision)
    if not 1 <= size <= maximo:
        raise _ErrorParametro(f"size debe estar entre 1 y {maximo} (llegó {size})")
    return size


def _pagina_y_tamano(params, tamano_por_omision, tamano_maximo=500):
    """The page/size preamble shared by valuaciones and titulares (contratos.md §2: page >= 0, size 1..500)."""
    page = _entero(params, "page", 0)
    if page < 0:
        raise _ErrorParametro(f"page inválido: {page}")
    return page, _tamano(params, tamano_por_omision, tamano_maximo)


class _Manejador(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # keep test output quiet, like fake_core.py

    def _atender(self, metodo):
        falso = self.server.falso
        largo = int(self.headers.get("Content-Length", 0))
        crudo = self.rfile.read(largo) if largo else b""
        cuerpo = json.loads(crudo) if crudo else None
        ruta, _, query = self.path.partition("?")
        autorizacion = self.headers.get("Authorization")
        with falso._lock:
            falso.peticiones.append((metodo, self.path, autorizacion))
            estado, payload = falso._responder(metodo, ruta, query, cuerpo, autorizacion)
        self.send_response(estado)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if payload is not None:
            self.wfile.write(json.dumps(payload).encode("utf-8"))

    def do_GET(self):
        self._atender("GET")

    def do_POST(self):
        self._atender("POST")


class ServidorFalso:
    """Base of the test doubles: a ThreadingHTTPServer on 127.0.0.1 with its own POST /api/auth/token, scripted
    by a subclass's `manejar`. CLIENT_ID/CLIENT_SECRET are the only credentials it accepts. `puerto` 0 picks a
    free port; a fixed one lets a test bring a new fake up at the URL of one it just closed (task 5: two cortes
    whose manifests must match, backends' URLs included)."""

    CLIENT_ID = "cid"
    CLIENT_SECRET = "csec"

    def __init__(self, puerto=0):
        self._lock = threading.Lock()
        self._tokens_validos = set()
        self._contador_tokens = 0
        self.peticiones = []
        self._server = ThreadingHTTPServer(("127.0.0.1", puerto), _Manejador)
        self._server.falso = self
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    @property
    def url(self):
        puerto = self._server.server_address[1]
        return f"http://127.0.0.1:{puerto}"

    def expirar_token(self):
        """Simulates the service token's expiry mid-corte (wasichai ADR-043): the next authorized request gets a
        401, forcing the client to ask for a new one."""
        with self._lock:
            self._tokens_validos.clear()

    def cerrar(self):
        self._server.shutdown()
        self._server.server_close()

    def manejar(self, metodo, ruta, query, cuerpo):
        """Subclasses answer their own routes here; anything else is a 404."""
        return 404, {"detail": "ruta no encontrada"}

    def _responder(self, metodo, ruta, query, cuerpo, autorizacion):
        if metodo == "POST" and ruta == "/api/auth/token":
            return self._autenticar(cuerpo)
        if not self._token_valido(autorizacion):
            return 401, {"detail": "token inválido o vencido"}
        return self.manejar(metodo, ruta, query, cuerpo)

    def _autenticar(self, cuerpo):
        cuerpo = cuerpo or {}
        if cuerpo.get("clientId") != self.CLIENT_ID or cuerpo.get("clientSecret") != self.CLIENT_SECRET:
            return 401, {"detail": "credenciales inválidas"}
        self._contador_tokens += 1
        token = f"t-{self._contador_tokens}"
        self._tokens_validos.add(token)
        return 200, {"token": token}

    def _token_valido(self, autorizacion):
        if not autorizacion or not autorizacion.startswith("Bearer "):
            return False
        return autorizacion.removeprefix("Bearer ") in self._tokens_validos


def _valor(fila, clave):
    """A filter's value for `clave`: wasichai's records are `{"id", "attributes": {...}}` (task 3's sinteticos.py
    builds them that way), so an equality filter on `clave` has to look inside `attributes` first; falling back
    to the row's own top level keeps working for a flat fixture that has no "attributes" at all (task 2's
    tests)."""
    atributos = fila.get("attributes")
    if atributos is not None and clave in atributos:
        return atributos[clave]
    return fila.get(clave)


class _Registros:
    """Serves GET /api/objects/{obj}/records for both fakes below: equality filters, size<=200, no OFFSET
    paging (`page` > 0 is rejected: ADR-036 is a keyset pass, never one), `nextCursor` as the base64 of the next
    row index. `barajar`, when given, shuffles each object's rows once with a fixed seed, so the client cannot
    rely on the server's order (it ignores `sort`), only on the cursor it follows."""

    def __init__(self, tablas, barajar=None):
        self._tablas = {}
        for nombre, filas in tablas.items():
            filas = list(filas)
            if barajar is not None:
                random.Random(barajar).shuffle(filas)
            self._tablas[nombre] = filas

    def responder(self, metodo, ruta, query):
        coincide = _RUTA_REGISTROS.match(ruta)
        if not coincide or metodo != "GET":
            return None
        objeto = coincide.group(1)
        if objeto not in self._tablas:
            return 404, {"detail": f"objeto desconocido: {objeto}"}
        params = _parsear_query(query)
        size = int(params.pop("size", 200))
        if size > 200:
            return 400, {"detail": "size no puede superar 200"}
        page = int(params.pop("page", 0))
        if page > 0:
            return 400, {"detail": "este objeto solo pagina con after, no con page"}
        after = params.pop("after", None)
        params.pop("sort", None)
        params.pop("dir", None)
        params.pop("count", None)
        filas = [f for f in self._tablas[objeto] if all(str(_valor(f, clave)) == valor for clave, valor in params.items())]
        inicio = _indice_de(after)
        contenido = filas[inicio : inicio + size]
        fin = inicio + len(contenido)
        siguiente = _cursor(fin) if fin < len(filas) else None
        return 200, {"content": contenido, "nextCursor": siguiente}


class SrtmFalso(ServidorFalso):
    """wasichai's generic record API only: the objects the corte reads straight from srtm (predio, declaracion,
    cuota_predial, brecha_corrida, parametro_tributario...)."""

    def __init__(self, registros, barajar=None, puerto=0):
        super().__init__(puerto)
        self._registros = _Registros(registros, barajar=barajar)

    def manejar(self, metodo, ruta, query, cuerpo):
        respuesta = self._registros.responder(metodo, ruta, query)
        return respuesta if respuesta is not None else (404, {"detail": "ruta no encontrada"})


class CatastroFalso(ServidorFalso):
    """catastro-backend's reads for the corte: the generic record API (like SrtmFalso, for predio_catastral,
    construccion, obra_complementaria) plus the three dedicated endpoints of contratos.md §1-§3. `sin_titulares`
    and `sin_cambios` simulate an older catastro that lacks those routes (404, contexto.md's tolerance ruling);
    `sin_llaves_nuevas` drops §1's seven new keys from every valuaciones item (an older engine)."""

    def __init__(
        self,
        registros,
        valuaciones,
        titulares,
        cambios,
        version="valuacion-v3",
        region="COSTA",
        sin_titulares=False,
        sin_cambios=False,
        sin_llaves_nuevas=False,
        barajar=None,
        puerto=0,
    ):
        super().__init__(puerto)
        self._registros = _Registros(registros, barajar=barajar)
        self._valuaciones = list(valuaciones)
        self._titulares = list(titulares)
        self._cambios = list(cambios)
        self.version = version
        self.region = region
        self.sin_titulares = sin_titulares
        self.sin_cambios = sin_cambios
        self.sin_llaves_nuevas = sin_llaves_nuevas

    def manejar(self, metodo, ruta, query, cuerpo):
        if metodo == "GET" and ruta == "/api/catastro/valuaciones":
            return self._valuaciones_pagina(query)
        if metodo == "GET" and ruta == "/api/catastro/titulares":
            return self._titulares_pagina(query)
        if metodo == "GET" and ruta == "/api/catastro/cambios":
            return self._cambios_pagina(query)
        respuesta = self._registros.responder(metodo, ruta, query)
        return respuesta if respuesta is not None else (404, {"detail": "ruta no encontrada"})

    def _valuaciones_pagina(self, query):
        params = _parsear_query(query)
        try:
            page, size = _pagina_y_tamano(params, tamano_por_omision=500)
        except _ErrorParametro as e:
            return 422, {"detail": str(e)}
        items = self._valuaciones[page * size : (page + 1) * size]
        if self.sin_llaves_nuevas:
            items = [{k: v for k, v in item.items() if k not in _LLAVES_NUEVAS_VALUACION} for item in items]
        return 200, {
            "page": page,
            "size": size,
            "total": len(self._valuaciones),
            "version": self.version,
            "region": self.region,
            "items": items,
        }

    def _titulares_pagina(self, query):
        if self.sin_titulares:
            return 404, {"detail": "titulares no disponible"}
        params = _parsear_query(query)
        try:
            page, size = _pagina_y_tamano(params, tamano_por_omision=500)
        except _ErrorParametro as e:
            return 422, {"detail": str(e)}
        items = self._titulares[page * size : (page + 1) * size]
        return 200, {"page": page, "size": size, "total": len(self._titulares), "items": items}

    def _cambios_pagina(self, query):
        """contratos.md §3: sin `desde`, empieza desde el principio; un `desde` vacío o que no decodifica a un
        índice es 422 (never silently treated as the start, so a client bug resending "" is caught here)."""
        if self.sin_cambios:
            return 404, {"detail": "cambios no disponible"}
        params = _parsear_query(query)
        try:
            size = _tamano(params, por_omision=200)
        except _ErrorParametro as e:
            return 422, {"detail": str(e)}
        desde = params.get("desde")
        if desde == "":
            return 422, {"detail": "desde no puede estar vacío: omítalo para empezar desde el principio"}
        if desde is None:
            inicio = 0
        else:
            try:
                inicio = _indice_de(desde)
            except ValueError:
                return 422, {"detail": f"desde inválido: {desde!r}"}
        items = self._cambios[inicio : inicio + size]
        if not items:
            return 200, {"items": [], "cursor": desde if desde is not None else _cursor(0), "hayMas": False}
        fin = inicio + len(items)
        return 200, {"items": items, "cursor": _cursor(fin), "hayMas": fin < len(self._cambios)}
