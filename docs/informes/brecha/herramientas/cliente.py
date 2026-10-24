"""HTTP client for wasichai (srtm) and catastro-backend's service-account APIs.

Stdlib only (urllib), like model/core_client.py. A service token is fetched with POST {base}/api/auth/token
{"clientId", "clientSecret"} and cached; the client never retries on its own except once, re-authenticating on a
401 (contexto.md ruling: "el cliente HTTP no reintenta, salvo volver a pedir el token una vez ante un 401;
cualquier otro error aborta el corte sin escribir ni publicar nada"). JSON bodies are read with
parse_float=Decimal (contexto.md: DECIMAL columns must stay exact, never a float).
"""

import json
import urllib.error
import urllib.parse
import urllib.request
from decimal import Decimal


class ErrorApi(Exception):
    """An API call that failed. Its message carries only the method, route and status - never a token or a
    secret, so it is always safe to print or log (contexto.md: "nunca el secreto")."""

    def __init__(self, metodo, ruta, estado):
        super().__init__(f"{metodo} {ruta} -> {estado}")
        self.metodo = metodo
        self.ruta = ruta
        self.estado = estado


class Cliente:
    """One account against one API (srtm or catastro-backend): auth, the generic records cursor, and the two
    page/feed shapes catastro's valuaciones/titulares and cambios endpoints use."""

    def __init__(self, base_url, client_id, client_secret, timeout=60):
        self.base_url = base_url.rstrip("/")
        self.client_id = client_id
        self.client_secret = client_secret
        self.timeout = timeout
        self._token = None
        # paginas() keeps the first page's top-level fields here (e.g. version/region): corte.py (task 4) needs
        # them without paginas() stopping being a plain Iterator[dict]
        self.ultima_pagina = {}

    def get(self, ruta, **params):
        """A single GET; a 404 is None, never an error (used for one-off lookups, unlike the cursor methods)."""
        return self._get(self._con_params(ruta, params), en_404="none")

    def registros(self, objeto, **filtros):
        """Every record of `objeto`, following srtm's keyset pass (Registros.kt, ADR-036): size=200, count=false,
        sort=id, dir=asc on every request, plus `after=<nextCursor>` from the second request on. Stops when
        nextCursor is null/absent. A 404 (the object does not exist) is an ErrorApi."""
        ruta_base = f"/api/objects/{objeto}/records"
        base = {"size": 200, "count": "false", "sort": "id", "dir": "asc", **filtros}
        cursor = None
        while True:
            consulta = dict(base)
            if cursor is not None:
                consulta["after"] = cursor
            pagina = self._get(self._con_params(ruta_base, consulta), en_404="error")
            yield from pagina.get("content", [])
            cursor = pagina.get("nextCursor")
            if cursor is None:
                return

    def paginas(self, ruta, tamano, **params):
        """Every item of a `page`/`size` endpoint (catastro's valuaciones/titulares): stops when a page comes
        back shorter than `tamano`, or once `total` items have been seen. A 404 is an ErrorApi."""
        pagina = 0
        vistos = 0
        while True:
            consulta = {"page": pagina, "size": tamano, **params}
            datos = self._get(self._con_params(ruta, consulta), en_404="error")
            if pagina == 0:
                self.ultima_pagina = {clave: valor for clave, valor in datos.items() if clave != "items"}
            items = datos.get("items", [])
            yield from items
            vistos += len(items)
            total = datos.get("total", vistos)
            if len(items) < tamano or vistos >= total:
                return
            pagina += 1

    def feed(self, ruta, tamano):
        """Every item of a `desde`/`size` feed (catastro's cambios, contratos.md §3): the first request omits
        `desde` ("sin desde, empieza desde el principio" - an empty desde is rejected, 422); from the second
        request on it sends `desde=<cursor>`, following it while `hayMas` is true. A 404 is an ErrorApi."""
        cursor = None
        while True:
            params = {"size": tamano}
            if cursor is not None:
                params["desde"] = cursor
            datos = self._get(self._con_params(ruta, params), en_404="error")
            yield from datos.get("items", [])
            cursor = datos.get("cursor")
            if not datos.get("hayMas"):
                return

    def _get(self, ruta, en_404):
        """GET `ruta` (its query already encoded) with the cached token; asks for a new one once on a 401 and
        retries, never more than that. `en_404`: "none" returns None (get()), "error" raises ErrorApi (the
        cursor methods, where a missing object or route is a corte-ending problem, not an empty result)."""
        if self._token is None:
            self._token = self._token_nuevo()
        reautenticado = False
        while True:
            peticion = urllib.request.Request(
                self.base_url + ruta,
                headers={"Authorization": f"Bearer {self._token}", "Accept": "application/json"},
            )
            try:
                with urllib.request.urlopen(peticion, timeout=self.timeout) as respuesta:
                    crudo = respuesta.read()
                break
            except urllib.error.HTTPError as e:
                e.read()
                e.close()  # avoid a ResourceWarning: HTTPError is also an unread response
                if e.code == 401 and not reautenticado:
                    self._token = self._token_nuevo()
                    reautenticado = True
                    continue
                if e.code == 404 and en_404 == "none":
                    return None
                raise ErrorApi("GET", ruta, e.code) from None
            except urllib.error.URLError as e:
                raise ErrorApi("GET", ruta, str(e.reason)) from None
        if not crudo:
            return None
        return json.loads(crudo.decode("utf-8"), parse_float=Decimal)

    def _token_nuevo(self):
        cuerpo = json.dumps({"clientId": self.client_id, "clientSecret": self.client_secret}).encode("utf-8")
        peticion = urllib.request.Request(
            f"{self.base_url}/api/auth/token",
            data=cuerpo,
            method="POST",
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )
        try:
            with urllib.request.urlopen(peticion, timeout=self.timeout) as respuesta:
                datos = json.loads(respuesta.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            e.read()
            e.close()
            raise ErrorApi("POST", "/api/auth/token", e.code) from None
        except urllib.error.URLError as e:
            raise ErrorApi("POST", "/api/auth/token", str(e.reason)) from None
        return datos["token"]

    @staticmethod
    def _con_params(ruta, params):
        if not params:
            return ruta
        separador = "&" if "?" in ruta else "?"
        return f"{ruta}{separador}{urllib.parse.urlencode(params)}"
