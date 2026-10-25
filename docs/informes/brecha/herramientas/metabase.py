"""Metabase's REST API for the corte's dashboards (srtm-backend#206): one DuckDB database, one group and one
collection per organization, the permission graphs that keep them apart, and the starting tableros of
metabase/tableros.json.

Stdlib only (urllib), like cliente.py: the client never retries, and an ErrorMetabase carries only the method,
the route and the status - never a password or a session token (the session travels in the
`X-Metabase-Session` header, never in a URL). The shapes are Metabase 0.63.19's (task 10's report names the
source file behind each one); pruebas/metabase_falso.py copies them.

Isolation is what Metabase OSS has (plan § Rulings, aislamiento: no "Blocked", no sandboxing): each
organization's group gets `create-queries: query-builder` on its own database and `no` on the others, "All
Users" `no` everywhere; each organization's collection is `read` for its group and `none` for the rest. The
DuckDB connection is `read_only` with `init_sql` = base.INIT_SQL, so not even a native query could open another
organization's file. Beyond the plan's letter, "All Users" also loses the root collection (Metabase's default
is `write`): a question saved there would be visible to every organization's users, and with `view-data`
unrestricted it would run for them.

The tableros are native cards `SELECT * FROM <vista>` (plan § Rulings: "los tableros son tarjetas nativas ...
idempotentes por nombre dentro de la colección"): a second run finds every database, group, collection, card
and dashboard by name and only PUTs what differs from the definition.

`humo` (task 11, `humo.py`) checks a provisioned Metabase stays that way: every `Brecha …` database syncs and
shows its views, every tablero's card still runs, and, logged in as each organization's user, isolation holds
(plan § Rulings: "si Metabase OSS muestra a un usuario el nombre de otra base, la prueba de humo lo marca").
"""

import http.client
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

from . import base, comun

TIMEOUT = 60
GRILLA = 24  # Metabase dashboards are 24 columns wide
PREFIJO = "Brecha "
TODOS = "all-internal-users"  # magic_group_type of "All Users" (permissions_group.clj)

PROPIA = {"view-data": "unrestricted", "create-queries": "query-builder"}
AJENA = {"create-queries": "no"}

_VISTA = re.compile(r"^v_[a-z0-9_]+$")
# The statuses of a query that ran and failed in Metabase 0.63.19 (query_processor/streaming.clj `status-code`,
# written by server/streaming_response.clj `write-error!`): 400 for a client error such as an invalid query, 500
# for the rest; the body is the failed result. 403 is a permission error, not a failure of the query itself.
_CONSULTA_FALLIDA = (400, 500)
_ACCESO_EXTERNO = "SELECT current_setting('enable_external_access') AS v"
_CAMPOS_TARJETA = ("nombre", "vista", "display", "visualizacion", "fila", "col", "ancho", "alto")


class ErrorMetabase(Exception):
    """A Metabase call that failed. Like cliente.ErrorApi, the message is `<method> <route> -> <status>`, safe to
    print: it never carries a request body (passwords) nor the session. `campos` are the keys of the response's
    `errors` object (a 400 from /api/setup or /api/user names the fields it refused), appended as
    `(errors: a, b)` - only the keys, never their values, which can echo what was sent."""

    def __init__(self, metodo, ruta, estado, campos=()):
        mensaje = f"{metodo} {ruta} -> {estado}"
        if campos:
            mensaje += f" (errors: {', '.join(campos)})"
        super().__init__(mensaje)
        self.metodo = metodo
        self.ruta = ruta
        self.estado = estado
        self.campos = tuple(campos)


class ErrorTableros(Exception):
    """tableros.json does not describe valid tableros (a configuration error, found before any API call)."""


class Metabase:
    """One Metabase instance, with an optional session (`sesion`); get/post/put return the parsed JSON (None
    for an empty body) and raise ErrorMetabase for any status >= 400 or a network failure."""

    def __init__(self, url, timeout=TIMEOUT):
        self.url = url.rstrip("/")
        self.timeout = timeout
        self._sesion = None

    def sesion(self, usuario, clave):
        """POST /api/session (session/api.clj): {"username", "password"} -> {"id"}; the id goes in the
        X-Metabase-Session header from then on. An account with a second factor answers `mfa_required`
        instead: that is an error here (the provisioning account must not have one)."""
        datos = self.post("/api/session", {"username": usuario, "password": clave})
        if not isinstance(datos, dict) or datos.get("mfa_required") or not datos.get("id"):
            raise ErrorMetabase("POST", "/api/session", "sin sesión (¿segundo factor?)")
        self._sesion = datos["id"]

    def get(self, ruta, cuerpo=None):
        return self._pedir("GET", ruta, cuerpo)

    def post(self, ruta, cuerpo=None):
        return self._pedir("POST", ruta, cuerpo)

    def put(self, ruta, cuerpo=None):
        return self._pedir("PUT", ruta, cuerpo)

    def _pedir(self, metodo, ruta, cuerpo):
        cabeceras = {"Accept": "application/json"}
        datos = None
        if cuerpo is not None:
            datos = json.dumps(cuerpo).encode("utf-8")
            cabeceras["Content-Type"] = "application/json"
        if self._sesion is not None:
            cabeceras["X-Metabase-Session"] = self._sesion
        peticion = urllib.request.Request(self.url + ruta, data=datos, method=metodo, headers=cabeceras)
        try:
            with urllib.request.urlopen(peticion, timeout=self.timeout) as respuesta:
                crudo = respuesta.read()
        except urllib.error.HTTPError as e:
            crudo = e.read()
            e.close()  # avoid a ResourceWarning: HTTPError is also an unread response
            raise ErrorMetabase(metodo, ruta, e.code, _campos_con_error(crudo)) from None
        except urllib.error.URLError as e:
            raise ErrorMetabase(metodo, ruta, str(e.reason)) from None
        except (http.client.HTTPException, OSError) as e:
            raise ErrorMetabase(metodo, ruta, f"respuesta interrumpida ({type(e).__name__})") from None
        if not crudo:
            return None
        try:
            return json.loads(crudo.decode("utf-8"))
        except ValueError:  # a proxy's HTML page, say
            raise ErrorMetabase(metodo, ruta, "la respuesta no es JSON") from None


def _campos_con_error(crudo):
    """The keys of an error body's `errors` object, sorted (Metabase's validation errors: field -> message), or
    () when the body is not JSON or has none. Keys only: printing a field's name is safe, its message is not."""
    try:
        cuerpo = json.loads(crudo.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return ()
    errores = cuerpo.get("errors") if isinstance(cuerpo, dict) else None
    return tuple(sorted(str(k) for k in errores)) if isinstance(errores, dict) else ()


def asegurar_setup(mb, email, clave):
    """A Metabase that was never set up has no user: GET /api/session/properties answers `has-user-setup`
    false and a `setup-token`, and POST /api/setup (setup_rest/api.clj) creates the first administrator with
    it. Returns whether it did the setup; with a user already there it touches nothing."""
    propiedades = mb.get("/api/session/properties") or {}
    if propiedades.get("has-user-setup"):
        return False
    token = propiedades.get("setup-token")
    if not token:
        raise ErrorMetabase("GET", "/api/session/properties", "sin setup-token")
    mb.post(
        "/api/setup",
        {
            "token": token,
            "user": {"email": email, "password": clave, "first_name": "Administración", "last_name": "SRTM"},
            "prefs": {"site_name": "SRTM analítica", "site_locale": "es"},
        },
    )
    return True


def validar_tableros(tableros):
    """Checks tableros.json before any API call: every card has its eight fields, a `vista` that is a plain view
    name (it goes into SQL as is), a place inside the 24-column grid, and one card name means one card - the same
    name in two tableros (the fecha del corte) must have the same vista, display and visualizacion."""
    if not isinstance(tableros, dict) or not isinstance(tableros.get("tableros"), list) or not tableros["tableros"]:
        raise ErrorTableros("tableros.json: falta la lista «tableros»")
    definiciones = {}
    nombres = set()
    for tablero in tableros["tableros"]:
        nombre = tablero.get("nombre") if isinstance(tablero, dict) else None
        if not isinstance(nombre, str) or not nombre.strip() or nombre in nombres:
            raise ErrorTableros(f"tableros.json: tablero sin nombre o repetido: {nombre!r}")
        nombres.add(nombre)
        if not isinstance(tablero.get("tarjetas"), list) or not tablero["tarjetas"]:
            raise ErrorTableros(f"tableros.json: «{nombre}» no tiene tarjetas")
        for tarjeta in tablero["tarjetas"]:
            faltan = [campo for campo in _CAMPOS_TARJETA if campo not in tarjeta]
            if faltan:
                raise ErrorTableros(f"tableros.json: a una tarjeta de «{nombre}» le falta {', '.join(faltan)}")
            donde = f"«{tarjeta['nombre']}» de «{nombre}»"
            if not isinstance(tarjeta["vista"], str) or not _VISTA.fullmatch(tarjeta["vista"]):
                raise ErrorTableros(f"tableros.json: vista inválida en {donde}: {tarjeta['vista']!r}")
            if not isinstance(tarjeta["visualizacion"], dict) or not isinstance(tarjeta["display"], str):
                raise ErrorTableros(f"tableros.json: display o visualizacion inválidos en {donde}")
            fila, col, ancho, alto = (tarjeta[c] for c in ("fila", "col", "ancho", "alto"))
            if not all(isinstance(v, int) and not isinstance(v, bool) for v in (fila, col, ancho, alto)):
                raise ErrorTableros(f"tableros.json: fila, col, ancho y alto deben ser enteros en {donde}")
            if fila < 0 or col < 0 or ancho < 1 or alto < 1 or col + ancho > GRILLA:
                raise ErrorTableros(f"tableros.json: {donde} sale de la cuadrícula de {GRILLA} columnas")
            definicion = (tarjeta["vista"], tarjeta["display"], json.dumps(tarjeta["visualizacion"], sort_keys=True))
            if definiciones.setdefault(tarjeta["nombre"], definicion) != definicion:
                raise ErrorTableros(f"tableros.json: «{tarjeta['nombre']}» tiene dos definiciones distintas")


def provisionar(mb, orgs, tableros, ruta_datos="/datos"):
    """Creates or updates, per organization (`orgs`: clave -> nombre), the database `Brecha <nombre>` over
    `<ruta_datos>/<org>.duckdb`, the group `Brecha <org>`, the collection `Brecha <nombre>`, the two permission
    graphs and the tableros' cards and dashboards. Returns {org: {"base", "grupo", "coleccion", "tarjetas",
    "tableros"}} with Metabase's ids. The session must be an administrator's."""
    validar_tableros(tableros)
    for org in orgs:
        comun.validar_org(org)
    todos = _grupo_todos(mb)
    resultado = {}
    for org, nombre in orgs.items():
        detalles = {
            "database_file": f"{ruta_datos.rstrip('/')}/{org}.duckdb",
            "read_only": True,
            "old_implicit_casting": True,
            "init_sql": base.INIT_SQL,
        }
        grupos = mb.get("/api/permissions/group")
        resultado[org] = {
            "base": _asegurar_base(mb, f"{PREFIJO}{nombre}", detalles),
            "grupo": _asegurar_por_nombre(mb, "/api/permissions/group", grupos, f"{PREFIJO}{org}"),
            "coleccion": _asegurar_coleccion(mb, f"{PREFIJO}{nombre}"),
        }
    _grafo_de_datos(mb, resultado, todos)
    _grafo_de_colecciones(mb, resultado, todos)
    for org in orgs:
        r = resultado[org]
        r["tarjetas"], r["tableros"] = _asegurar_tableros(mb, tableros, r["base"], r["coleccion"])
    return resultado


def agregar_usuario(mb, org, email, nombre, apellido, clave):
    """Creates the user if no active one has `email` (POST /api/user, with its password) and puts it in the group
    `Brecha <org>` if it is not there yet (POST /api/permissions/membership). An existing user keeps its password.
    Returns the user's id."""
    comun.validar_org(org)
    grupo = _buscar(mb.get("/api/permissions/group"), f"{PREFIJO}{org}")
    if grupo is None:
        falta = f"no existe el grupo {PREFIJO}{org} (corre provisionar.py)"
        raise ErrorMetabase("GET", "/api/permissions/group", falta)
    consulta = urllib.parse.urlencode({"query": email})
    usuarios = (mb.get(f"/api/user?{consulta}") or {}).get("data") or []
    usuario = next((u for u in usuarios if str(u.get("email", "")).lower() == email.lower()), None)
    if usuario is None:
        usuario = mb.post(
            "/api/user", {"email": email, "first_name": nombre, "last_name": apellido, "password": clave}
        )
    uid = usuario["id"]
    membresias = (mb.get("/api/permissions/membership") or {}).get(str(uid)) or []
    if not any(m.get("group_id") == grupo["id"] for m in membresias):
        mb.post("/api/permissions/membership", {"group_id": grupo["id"], "user_id": uid})
    return uid


# --- databases, groups and collections ------------------------------------------------------------------------


def _buscar(elementos, nombre):
    """The element named `nombre` (the lowest id if a human made two), or None."""
    candidatos = [e for e in elementos or [] if e.get("name") == nombre]
    return min(candidatos, key=lambda e: e["id"]) if candidatos else None


def _asegurar_por_nombre(mb, ruta, existentes, nombre, cuerpo=None):
    encontrado = _buscar(existentes, nombre)
    if encontrado is not None:
        return encontrado["id"]
    return mb.post(ruta, cuerpo or {"name": nombre})["id"]


def _grupo_todos(mb):
    grupos = mb.get("/api/permissions/group") or []
    todos = next((g for g in grupos if g.get("magic_group_type") == TODOS), None) or _buscar(grupos, "All Users")
    if todos is None:
        raise ErrorMetabase("GET", "/api/permissions/group", "no aparece el grupo All Users")
    return todos["id"]


def _asegurar_base(mb, nombre, detalles):
    """GET /api/database is {"data": [...], "total"} (warehouses_rest/api.clj); an admin sees `details`. A database
    whose engine or any of our four details differ (someone edited it by hand) is PUT back."""
    bases = (mb.get("/api/database") or {}).get("data") or []
    existente = _buscar(bases, nombre)
    if existente is None:
        return mb.post("/api/database", {"name": nombre, "engine": "duckdb", "details": detalles})["id"]
    actuales = existente.get("details") or {}
    if existente.get("engine") != "duckdb" or any(actuales.get(k) != v for k, v in detalles.items()):
        mb.put(f"/api/database/{existente['id']}", {"engine": "duckdb", "details": {**actuales, **detalles}})
    return existente["id"]


def _asegurar_coleccion(mb, nombre):
    """Only a top-level, shared (not personal), non-archived collection counts (GET /api/collection lists the
    root first, as id "root", and leaves archived ones out by default)."""
    colecciones = [
        c
        for c in mb.get("/api/collection") or []
        if isinstance(c.get("id"), int) and c.get("personal_owner_id") is None and c.get("location") == "/"
        and not c.get("archived")
    ]
    return _asegurar_por_nombre(mb, "/api/collection", colecciones, nombre)


# --- permission graphs ----------------------------------------------------------------------------------------


def _brecha(elementos):
    return {e["id"] for e in elementos if isinstance(e.get("id"), int) and str(e.get("name", "")).startswith(PREFIJO)}


def _grafo_de_datos(mb, resultado, todos):
    """The data permissions graph (GET/PUT /api/permissions/graph, permissions_rest/api.clj; group id -> database
    id -> {"view-data", "create-queries"}; keys are strings in JSON). This run's groups get PROPIA on their own
    database and AJENA on every other `Brecha …` database; every other `Brecha …` group gets AJENA on this run's
    databases (so a new organization never opens up the earlier ones, nor they it); All Users gets AJENA on this
    run's databases. Only the entries that differ are PUT, with the graph's `revision` (a stale one is a 409)."""
    grupos = _brecha(mb.get("/api/permissions/group") or [])
    bases = _brecha((mb.get("/api/database") or {}).get("data") or [])
    propias = {(r["grupo"], r["base"]) for r in resultado.values()}
    nuevos_grupos = {r["grupo"] for r in resultado.values()}
    nuevas_bases = {r["base"] for r in resultado.values()}
    deseado = {}
    for grupo in grupos:
        for b in bases:
            if (grupo, b) in propias:
                deseado[(grupo, b)] = PROPIA
            elif grupo in nuevos_grupos or b in nuevas_bases:
                deseado[(grupo, b)] = AJENA
    for b in nuevas_bases:
        deseado[(todos, b)] = AJENA
    grafo = mb.get("/api/permissions/graph")
    actual = grafo.get("groups") or {}
    parche = {}
    for (grupo, b), permisos in deseado.items():
        tiene = (actual.get(str(grupo)) or {}).get(str(b)) or {}
        if any(tiene.get(k) != v for k, v in permisos.items()):
            parche.setdefault(str(grupo), {})[str(b)] = permisos
    if parche:
        mb.put("/api/permissions/graph", {"revision": grafo.get("revision"), "groups": parche})


def _grafo_de_colecciones(mb, resultado, todos):
    """The collection graph (GET/PUT /api/collection/graph, collections_rest/api.clj; group id -> collection id or
    "root" -> "read"/"write"/"none"), on the same rule as the data graph: `read` on its own collection, `none`
    on the others, All Users `none` on this run's collections and on the root. PUT only what differs."""
    grupos = _brecha(mb.get("/api/permissions/group") or [])
    colecciones = _brecha(
        c for c in mb.get("/api/collection") or [] if c.get("personal_owner_id") is None and c.get("location") == "/"
    )
    propias = {(r["grupo"], r["coleccion"]) for r in resultado.values()}
    nuevos_grupos = {r["grupo"] for r in resultado.values()}
    nuevas = {r["coleccion"] for r in resultado.values()}
    deseado = {(todos, "root"): "none"}
    for grupo in grupos:
        for c in colecciones:
            if (grupo, c) in propias:
                deseado[(grupo, c)] = "read"
            elif grupo in nuevos_grupos or c in nuevas:
                deseado[(grupo, c)] = "none"
    for c in nuevas:
        deseado[(todos, c)] = "none"
    grafo = mb.get("/api/collection/graph")
    actual = grafo.get("groups") or {}
    parche = {}
    for (grupo, c), permiso in deseado.items():
        if (actual.get(str(grupo)) or {}).get(str(c)) != permiso:
            parche.setdefault(str(grupo), {})[str(c)] = permiso
    if parche:
        mb.put("/api/collection/graph", {"revision": grafo.get("revision"), "groups": parche})


# --- cards and dashboards -------------------------------------------------------------------------------------


def _asegurar_tableros(mb, tableros, base_id, coleccion_id):
    """Every card and dashboard of `tableros` inside the collection, found by name among its items
    (GET /api/collection/:id/items). Returns (card ids in order of first use, dashboard ids)."""
    items = (mb.get(f"/api/collection/{coleccion_id}/items?models=card&models=dashboard") or {}).get("data") or []
    tarjetas_existentes = [i for i in items if i.get("model") == "card"]
    tableros_existentes = [i for i in items if i.get("model") == "dashboard"]
    tarjetas = {}
    ids_tableros = []
    for tablero in tableros["tableros"]:
        disposicion = []
        for t in tablero["tarjetas"]:
            if t["nombre"] not in tarjetas:
                tarjetas[t["nombre"]] = _asegurar_tarjeta(mb, t, base_id, coleccion_id, tarjetas_existentes)
            disposicion.append((tarjetas[t["nombre"]], t["fila"], t["col"], t["ancho"], t["alto"]))
        ids_tableros.append(_asegurar_tablero(mb, tablero["nombre"], coleccion_id, disposicion, tableros_existentes))
    return list(tarjetas.values()), ids_tableros


def _asegurar_tarjeta(mb, t, base_id, coleccion_id, existentes):
    """POST /api/card (queries_rest/api/card.clj CardCreateSchema) with a legacy native `dataset_query`, which
    0.63 still accepts. GET /api/card/:id answers MBQL 5 since v57, so an existing card is read back with
    `?legacy-mbql=true` to compare it against the same shape it was saved in; a difference is a PUT."""
    definicion = {
        "name": t["nombre"],
        "type": "question",
        "display": t["display"],
        "visualization_settings": t["visualizacion"],
        "collection_id": coleccion_id,
        "dataset_query": {"type": "native", "native": {"query": f"SELECT * FROM {t['vista']}"}, "database": base_id},
    }
    existente = _buscar(existentes, t["nombre"])
    if existente is None:
        return mb.post("/api/card", definicion)["id"]
    actual = mb.get(f"/api/card/{existente['id']}?legacy-mbql=true") or {}
    consulta = actual.get("dataset_query") or {}
    igual = (
        actual.get("display") == t["display"]
        and (actual.get("visualization_settings") or {}) == t["visualizacion"]
        and consulta.get("type") == "native"
        and consulta.get("database") == base_id
        and (consulta.get("native") or {}).get("query") == definicion["dataset_query"]["native"]["query"]
    )
    if not igual:
        mb.put(f"/api/card/{existente['id']}", definicion)
    return existente["id"]


def _asegurar_tablero(mb, nombre, coleccion_id, disposicion, existentes):
    """POST /api/dashboard {"name", "collection_id"}, then PUT /api/dashboard/:id with the whole `dashcards` list
    (dashboards_rest/api.clj UpdatedDashboardCard): negative ids are new dashcards and the ones left out are
    deleted, so a changed layout is replaced whole. An existing dashboard whose dashcards already match
    (card, row, col, size) is left alone."""
    existente = _buscar(existentes, nombre)
    if existente is None:
        did = mb.post("/api/dashboard", {"name": nombre, "collection_id": coleccion_id})["id"]
        actual = []
    else:
        did = existente["id"]
        dashcards = (mb.get(f"/api/dashboard/{did}") or {}).get("dashcards") or []
        actual = [(d.get("card_id"), d.get("row"), d.get("col"), d.get("size_x"), d.get("size_y")) for d in dashcards]
    if sorted(actual, key=repr) != sorted(disposicion, key=repr):
        dashcards = [
            {"id": -(i + 1), "card_id": card, "row": fila, "col": col, "size_x": ancho, "size_y": alto,
             "parameter_mappings": [], "visualization_settings": {}}
            for i, (card, fila, col, ancho, alto) in enumerate(disposicion)
        ]
        mb.put(f"/api/dashboard/{did}", {"dashcards": dashcards})
    return did


# --- smoke test (task 11, humo.py) ------------------------------------------------------------------------------


class Fallas(list):
    """What `humo` returns: a list[str] of fallas (empty = OK) that also remembers how many comprobaciones it
    ran, for humo.py's `humo: OK (<n> comprobaciones)` - a plain list has nowhere to carry that count."""

    def __init__(self):
        super().__init__()
        self.comprobaciones = 0


def _base_propia(mb, org):
    """The id of the one database the group `Brecha <org>` can query (`create-queries` != "no" on the admin's
    data permissions graph) - safer than asking the organization's own user, whose GET /api/database is
    exactly what a broken isolation would lie about. None when the group is missing, or when it can query zero
    or more than one database (provisionar.py never ran, or ran against a different tableros.json)."""
    grupo = _buscar(mb.get("/api/permissions/group") or [], f"{PREFIJO}{org}")
    if grupo is None:
        return None
    permisos = (mb.get("/api/permissions/graph") or {}).get("groups") or {}
    propias = [
        int(b) for b, p in (permisos.get(str(grupo["id"])) or {}).items() if p.get("create-queries") not in (None, "no")
    ]
    return propias[0] if len(propias) == 1 else None


def _nombre_de_base(bases, base_id):
    """The name of `base_id` in `bases` (name -> id), or a fallback that still shows the id. The admin's
    permissions graph (where humo finds an organization's "own" base) and GET /api/database (where it lists
    `bases`) are two separate calls: a base renamed or deleted between them must become a falla's text, never
    an uncaught StopIteration that stops humo.py with a traceback instead of a clean exit 1."""
    for nombre, i in bases.items():
        if i == base_id:
            return nombre
    return f"base #{base_id} (no aparece en /api/database)"


def _esperar_vista(mb, base_id, vista, espera):
    """Polls GET /api/database/:id/metadata until `vista` is one of its tables, or `espera` seconds pass (a
    real sync_schema runs in the background; MetabaseFalso's is immediate, so the tests never wait)."""
    limite = time.monotonic() + espera
    retraso = 0.5
    while True:
        tablas = {t.get("name") for t in (mb.get(f"/api/database/{base_id}/metadata") or {}).get("tables") or []}
        if vista in tablas:
            return True
        restante = limite - time.monotonic()
        if restante <= 0:
            return False
        time.sleep(min(retraso, restante))
        retraso = min(retraso * 2, 5)


def humo(url, admin, usuarios, espera=120):
    """Smoke test of an already provisioned Metabase (plan § Rulings, aislamiento: "si Metabase OSS muestra a
    un usuario el nombre de otra base, la prueba de humo lo marca"). As the administrator: every `Brecha …`
    database still syncs and shows `v_segmentos`, every tablero's card still runs (a card that fails is one
    falla, and the run goes on), `current_setting('enable_external_access')` is false on every one of them, and
    an ATTACH from one organization's database to another's file fails (a 400/500, see _CONSULTA_FALLIDA).
    Logged in as each organization's user (`usuarios`: org ->
    (email, clave)): GET /api/database lists its own database, and no other, a foreign tablero's card is 403, and
    native SQL is 403 everywhere - on a foreign database and on its own (provisioning never grants
    `create-queries: "query-builder-and-native"` to anyone, plan § Rulings: PROPIA).

    Returns a Fallas (empty = every comprobación passed); an ErrorMetabase propagates only from a request that is
    not itself what a comprobación checks (the administrator's login, a listing), never from one that merely
    found something wrong."""
    mb = Metabase(url)
    mb.sesion(*admin)
    fallas = Fallas()

    fallas.comprobaciones += 1
    salud = mb.get("/api/health")
    if not isinstance(salud, dict) or salud.get("status") != "ok":
        fallas.append(f"/api/health no respondió ok: {salud!r}")

    bases = {
        b["name"]: b["id"]
        for b in (mb.get("/api/database") or {}).get("data") or []
        if str(b.get("name", "")).startswith(PREFIJO)
    }
    for nombre, base_id in bases.items():
        fallas.comprobaciones += 1
        try:
            mb.post(f"/api/database/{base_id}/sync_schema")
        except ErrorMetabase as e:
            fallas.append(f"{nombre}: sync_schema -> {e}")
            continue
        if not _esperar_vista(mb, base_id, "v_segmentos", espera):
            fallas.append(f"{nombre}: v_segmentos no aparece en sus metadatos tras sincronizar")

    colecciones = [
        c for c in mb.get("/api/collection") or []
        if isinstance(c.get("id"), int) and str(c.get("name", "")).startswith(PREFIJO)
    ]
    tarjetas_por_base = {}
    for c in colecciones:
        items = (mb.get(f"/api/collection/{c['id']}/items?models=card") or {}).get("data") or []
        tarjetas_por_base[c["name"]] = [i["id"] for i in items]
        for item in items:
            fallas.comprobaciones += 1
            # a card whose query fails answers an HTTP error (400/500, see _CONSULTA_FALLIDA), not 202 "failed":
            # one falla naming the card, and the run goes on with the next one
            try:
                resultado = mb.post(f"/api/card/{item['id']}/query") or {}
            except ErrorMetabase as e:
                fallas.append(f"{c['name']}: la tarjeta «{item['name']}» no corrió ({e.estado})")
                continue
            if resultado.get("status") != "completed":
                fallas.append(f"{c['name']}: la tarjeta «{item['name']}» no corrió ({resultado.get('status')!r})")

    # the positive half of INIT_SQL's check, which cannot pass vacuously: on every `Brecha …` base the setting
    # really is off (the ATTACH below only shows that one statement failed, for whatever reason)
    for nombre, base_id in bases.items():
        fallas.comprobaciones += 1
        consulta = {"database": base_id, "type": "native", "native": {"query": _ACCESO_EXTERNO}}
        try:
            resultado = mb.post("/api/dataset", consulta) or {}
        except ErrorMetabase as e:
            fallas.append(f"{nombre}: no se pudo leer enable_external_access ({e.estado})")
            continue
        filas = (resultado.get("data") or {}).get("rows") or []
        if resultado.get("status") != "completed" or filas[:1] not in ([[False]], [["false"]]):
            fallas.append(f"{nombre}: enable_external_access no es false ({filas[:1]!r}): ¿falta su init_sql?")

    bases_por_org = {}
    for org in usuarios:
        fallas.comprobaciones += 1
        base_id = _base_propia(mb, org)
        if base_id is None:
            fallas.append(f"{org}: no se encuentra su base (¿corrió provisionar.py con este tableros.json?)")
        bases_por_org[org] = base_id

    for org, (email, clave) in usuarios.items():
        propia = bases_por_org.get(org)
        if propia is None:
            continue
        nombre_propia = _nombre_de_base(bases, propia)
        otro_org = next((o for o, b in bases_por_org.items() if o != org and b is not None), None)

        mu = Metabase(url)
        fallas.comprobaciones += 1
        try:
            mu.sesion(email, clave)
        except ErrorMetabase as e:
            fallas.append(f"{org}: no pudo iniciar sesión ({e})")
            continue

        fallas.comprobaciones += 1
        vistas = {b["name"]: b["id"] for b in (mu.get("/api/database") or {}).get("data") or []}
        if propia not in vistas.values():
            fallas.append(f"{org} no ve su propia base ({nombre_propia})")
        ajenas = sorted(n for n, i in vistas.items() if i != propia)
        if ajenas:
            fallas.append(f"{org} ve, además de la suya, otra base: {', '.join(ajenas)}")

        if otro_org is not None:
            base_ajena = bases_por_org[otro_org]
            nombre_ajena = _nombre_de_base(bases, base_ajena)
            tarjeta_ajena = next(iter(tarjetas_por_base.get(nombre_ajena, [])), None)
            if tarjeta_ajena is not None:
                fallas.comprobaciones += 1
                try:
                    mu.get(f"/api/card/{tarjeta_ajena}")
                except ErrorMetabase as e:
                    if e.estado != 403:
                        fallas.append(f"{org}: leer una tarjeta de {nombre_ajena} dio {e.estado}, no 403")
                else:
                    fallas.append(f"{org} pudo leer una tarjeta de {nombre_ajena}")

            fallas.comprobaciones += 1
            nativa_ajena = {"database": base_ajena, "type": "native", "native": {"query": "SELECT 1"}}
            try:
                mu.post("/api/dataset", nativa_ajena)
            except ErrorMetabase as e:
                if e.estado != 403:
                    fallas.append(f"{org}: SQL nativo sobre {nombre_ajena} dio {e.estado}, no 403")
            else:
                fallas.append(f"{org} pudo correr SQL nativo sobre {nombre_ajena}")

        fallas.comprobaciones += 1
        nativa_propia = {"database": propia, "type": "native", "native": {"query": "SELECT 1"}}
        try:
            mu.post("/api/dataset", nativa_propia)
        except ErrorMetabase as e:
            if e.estado != 403:
                fallas.append(
                    f"{org}: SQL nativo sobre {nombre_propia} dio {e.estado}, no 403 (no debería tener SQL nativo)"
                )
        else:
            fallas.append(f"{org} pudo correr SQL nativo sobre su propia base ({nombre_propia}): nadie debería tenerlo")

    for org, propia in bases_por_org.items():
        if propia is None:
            continue
        otro_org = next((o for o, b in bases_por_org.items() if o != org and b is not None), None)
        if otro_org is None:
            continue
        fallas.comprobaciones += 1
        consulta = {"database": propia, "type": "native", "native": {"query": f"ATTACH '/datos/{otro_org}.duckdb'"}}
        try:
            resultado = mb.post("/api/dataset", consulta) or {}
        except ErrorMetabase as e:
            # DuckDB's refusal is a failed query: 400 (a client error) or 500, never anything else
            if e.estado not in _CONSULTA_FALLIDA:
                fallas.append(f"{org}: ATTACH '/datos/{otro_org}.duckdb' dio {e.estado}, no 400/500")
            continue
        if resultado.get("status") == "completed":
            fallas.append(f"{org}: ATTACH '/datos/{otro_org}.duckdb' no dio error (aislamiento roto)")

    return fallas
