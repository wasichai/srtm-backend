"""Metabase's REST API for the corte's dashboards (srtm-backend#206): one Metabase instance per organization
(plan, Decisión 2026-10-25), and in it that organization's DuckDB database, group and collection, the permission
graphs that keep "All Users" out of them, and the starting tableros of metabase/tableros.json.

Stdlib only (urllib), like cliente.py: the client never retries, and an ErrorMetabase carries only the method,
the route and the status - never a password or a session token (the session travels in the
`X-Metabase-Session` header, never in a URL). The shapes are Metabase 0.63.19's (task 10's report names the
source file behind each one); pruebas/metabase_falso.py copies them.

Isolation between organizations is the instance itself: Metabase OSS shows every user of an instance the names
and emails of all the others (GET /api/user/recipients, `user-visibility` is a paid setting), so the
organizations no longer share one. Each instance's compose mounts only its organization's DuckDB, and
`provisionar` refuses an instance that already holds another organization's database or `Brecha …` group (an
<org>.env copied with someone else's METABASE_PUERTO). Inside the instance, the organization's group gets
`create-queries: query-builder` on its database and `read` on its collection; "All Users" gets `no` and `none`
on them and loses the root collection (Metabase's default is `write`), so an account outside the group - one
created by hand, say - sees nothing. The DuckDB connection is `read_only` with `init_sql` = base.INIT_SQL.

The tableros are native cards `SELECT * FROM <vista>` (plan § Rulings: "los tableros son tarjetas nativas ...
idempotentes por nombre dentro de la colección"): a second run finds every database, group, collection, card
and dashboard by name and only PUTs what differs from the definition.

`humo` (task 11, `humo.py`) checks a provisioned instance stays that way: it holds exactly its organization's
database, whose DuckDB connection has only `/datos/<org>.duckdb` open and whose corte is that organization's;
the database syncs and shows its views, every tablero's card still runs, and the organization's user sees it
without native SQL.
"""

import http.client
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from . import base, comun

TIMEOUT = 60
GRILLA = 24  # Metabase dashboards are 24 columns wide
PREFIJO = "Brecha "
CREDENCIALES = ("METABASE_ADMIN_EMAIL", "METABASE_ADMIN_PASSWORD")
TODOS = "all-internal-users"  # magic_group_type of "All Users" (permissions_group.clj)

PROPIA = {"view-data": "unrestricted", "create-queries": "query-builder"}
AJENA = {"create-queries": "no"}

_VISTA = re.compile(r"^v_[a-z0-9_]+$")
_ACCESO_EXTERNO = "SELECT current_setting('enable_external_access') AS v"
# the files the DuckDB connection has open (the instance must see only its organization's), and whose corte it is
_ARCHIVOS_ABIERTOS = "SELECT path FROM duckdb_databases() WHERE NOT internal ORDER BY path"
_ORGANIZACION_DEL_CORTE = "SELECT DISTINCT organizacion FROM corte_manifiesto"
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


def conexion(org, ruta=None):
    """(url, admin email, admin password) of the organization's own instance (provisionar.py and humo.py). The
    URL comes only from its <org>.env (`ruta`, by omission comun.ruta_entorno(org)): METABASE_URL there, else
    http://127.0.0.1:<METABASE_PUERTO> (the SSH tunnel forwards the same port) - never from the shell's
    environment, where a METABASE_URL left over from another organization's session would point this one at
    the wrong instance. The administrator's email and password come from the environment or, without them,
    from that file. Raises comun.ErrorCorte (a configuration error) naming keys and the file, never a value."""
    ruta = Path(ruta).expanduser() if ruta else comun.ruta_entorno(org)
    try:
        valores = comun.leer_entorno(ruta, ("METABASE_PUERTO",))
    except OSError as e:
        raise comun.ErrorCorte(f"no se puede leer {ruta}: {e.strerror}") from None
    url = valores.get("METABASE_URL") or f"http://127.0.0.1:{valores['METABASE_PUERTO']}"
    credenciales = [os.environ.get(c) or valores.get(c) for c in CREDENCIALES]
    faltan = [c for c, v in zip(CREDENCIALES, credenciales) if not v]
    if faltan:
        raise comun.ErrorCorte(f"falta {', '.join(faltan)} en el entorno o en {ruta}")
    return url, *credenciales


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


def provisionar(mb, org, nombre, tableros, ruta_datos="/datos"):
    """Creates or updates, in this organization's own instance, the database `Brecha <nombre>` over
    `<ruta_datos>/<org>.duckdb`, the group `Brecha <org>`, the collection `Brecha <nombre>`, the two permission
    graphs and the tableros' cards and dashboards. Returns {"base", "grupo", "coleccion", "tarjetas", "tableros"}
    with Metabase's ids. The session must be an administrator's.

    Refuses (an ErrorMetabase, before writing anything) an instance that already has another `Brecha …`
    database or group, or a `Brecha <nombre>` database over another file: one instance, one organization."""
    validar_tableros(tableros)
    comun.validar_org(org)
    archivo = f"{ruta_datos.rstrip('/')}/{org}.duckdb"
    _comprobar_instancia(mb, org, f"{PREFIJO}{nombre}", archivo)
    todos = _grupo_todos(mb)
    detalles = {"database_file": archivo, "read_only": True, "old_implicit_casting": True, "init_sql": base.INIT_SQL}
    grupos = mb.get("/api/permissions/group")
    r = {
        "base": _asegurar_base(mb, f"{PREFIJO}{nombre}", detalles, todos),
        "grupo": _asegurar_por_nombre(mb, "/api/permissions/group", grupos, f"{PREFIJO}{org}"),
        "coleccion": _asegurar_coleccion(mb, f"{PREFIJO}{nombre}"),
    }
    _grafo_de_datos(mb, r, todos)
    _grafo_de_colecciones(mb, r, todos)
    r["tarjetas"], r["tableros"] = _asegurar_tableros(mb, tableros, r["base"], r["coleccion"])
    return r


def _comprobar_instancia(mb, org, nombre_base, archivo):
    """The instance is this organization's, or still empty: no `Brecha …` database other than `nombre_base`, that
    one over `archivo`, and no `Brecha …` group other than `Brecha <org>`."""
    pista = "(¿METABASE_PUERTO de otra organización?)"
    for b in (mb.get("/api/database") or {}).get("data") or []:
        nombre = str(b.get("name", ""))
        if not nombre.startswith(PREFIJO):
            continue
        if nombre != nombre_base:
            ajena = f"la instancia ya tiene la base {nombre}: no es de {org} {pista}"
            raise ErrorMetabase("GET", "/api/database", ajena)
        otro = (b.get("details") or {}).get("database_file")
        if otro != archivo:
            raise ErrorMetabase("GET", "/api/database", f"{nombre} abre {otro}, no {archivo}: no es de {org}")
    for g in mb.get("/api/permissions/group") or []:
        nombre = str(g.get("name", ""))
        if nombre.startswith(PREFIJO) and nombre != f"{PREFIJO}{org}":
            ajeno = f"la instancia ya tiene el grupo {nombre}: no es de {org} {pista}"
            raise ErrorMetabase("GET", "/api/permissions/group", ajeno)


def agregar_usuario(mb, org, email, nombre, apellido, clave):
    """Creates the user if nobody has `email` (POST /api/user, with its password) and puts it in the group
    `Brecha <org>` if it is not there yet (POST /api/permissions/membership). An existing user keeps its password.
    Returns the user's id.

    A deactivated user's email is refused (POST /api/user would answer a bare 400; reactivating someone is an
    administrator's decision, made in Metabase's own admin): an ErrorMetabase whose one line names the email and
    the reason, before anything is written. The instance is this organization's alone, so there is no other
    organization's group to check the user against."""
    comun.validar_org(org)
    grupos = mb.get("/api/permissions/group") or []
    grupo = _buscar(grupos, f"{PREFIJO}{org}")
    if grupo is None:
        falta = f"no existe el grupo {PREFIJO}{org} (corre provisionar.py)"
        raise ErrorMetabase("GET", "/api/permissions/group", falta)
    # status=all (admin only, users_rest/api.clj GET /) also lists deactivated users, which a plain query omits
    consulta = urllib.parse.urlencode({"query": email, "status": "all"})
    usuarios = (mb.get(f"/api/user?{consulta}") or {}).get("data") or []
    usuario = next((u for u in usuarios if str(u.get("email", "")).lower() == email.lower()), None)
    if usuario is not None and usuario.get("is_active") is False:
        raise ErrorMetabase("GET", "/api/user", f"{email} es de un usuario desactivado (reactívalo en Metabase)")
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


def _asegurar_base(mb, nombre, detalles, todos):
    """GET /api/database is {"data": [...], "total"} (warehouses_rest/api.clj); an admin sees `details`. A database
    whose engine or any of our four details differ (someone edited it by hand) is PUT back. A new one is closed
    at once (_cerrar_base), before anything else in the run can fail."""
    bases = (mb.get("/api/database") or {}).get("data") or []
    existente = _buscar(bases, nombre)
    if existente is None:
        base_id = mb.post("/api/database", {"name": nombre, "engine": "duckdb", "details": detalles})["id"]
        _cerrar_base(mb, base_id, todos)
        return base_id
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


def _cerrar_base(mb, base_id, todos):
    """Right after POST /api/database: All Users gets AJENA on the new database. Metabase gives a non-admin group
    native SQL on a database when it has no other level to copy (permissions/models/data_permissions.clj
    set-default-database-permissions!), so a new database is open to All Users with native SQL until a graph
    says otherwise; without this, a later failure in the run (a 500 on the collection, a 409 on the graph) would
    leave it that way. Its own group gets PROPIA later, in _grafo_de_datos."""
    grafo = mb.get("/api/permissions/graph")
    mb.put("/api/permissions/graph", {"revision": grafo.get("revision"), "groups": {str(todos): {str(base_id): AJENA}}})


def _grafo_de_datos(mb, r, todos):
    """The data permissions graph (GET/PUT /api/permissions/graph, permissions_rest/api.clj; group id -> database
    id -> {"view-data", "create-queries"}; keys are strings in JSON): the organization's group gets PROPIA on its
    database and All Users AJENA. Only the entries that differ are PUT, with the graph's `revision` (a stale one
    is a 409)."""
    deseado = {(r["grupo"], r["base"]): PROPIA, (todos, r["base"]): AJENA}
    grafo = mb.get("/api/permissions/graph")
    actual = grafo.get("groups") or {}
    parche = {}
    for (grupo, b), permisos in deseado.items():
        tiene = (actual.get(str(grupo)) or {}).get(str(b)) or {}
        if any(tiene.get(k) != v for k, v in permisos.items()):
            parche.setdefault(str(grupo), {})[str(b)] = permisos
    if parche:
        mb.put("/api/permissions/graph", {"revision": grafo.get("revision"), "groups": parche})


def _grafo_de_colecciones(mb, r, todos):
    """The collection graph (GET/PUT /api/collection/graph, collections_rest/api.clj; group id -> collection id or
    "root" -> "read"/"write"/"none"): `read` for the organization's group on its collection, `none` for All Users
    on it and on the root. PUT only what differs."""
    deseado = {(r["grupo"], r["coleccion"]): "read", (todos, r["coleccion"]): "none", (todos, "root"): "none"}
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


def _nativa(mb, base_id, sql):
    """The rows of a native query the administrator runs on `base_id`, or a short reason it did not complete
    (as a str): a query that fails is a falla to report, never an exception out of humo."""
    try:
        resultado = mb.post("/api/dataset", {"database": base_id, "type": "native", "native": {"query": sql}}) or {}
    except ErrorMetabase as e:
        return f"falló ({e.estado})"
    if resultado.get("status") != "completed":
        return f"no terminó ({resultado.get('status')!r})"
    return (resultado.get("data") or {}).get("rows") or []


def humo(url, admin, org, usuario, espera=120):
    """Smoke test of an organization's own, already provisioned Metabase instance (plan, Decisión 2026-10-25).
    As the administrator: the instance holds exactly one database, over `/datos/<org>.duckdb` - any other
    database, `Brecha …` or not, is a falla; that database still syncs and shows `v_segmentos`; every tablero's
    card still runs (a card that fails is one falla, and the run goes on); and, inside its DuckDB connection,
    `enable_external_access` is false, `duckdb_databases()` shows only `<org>.duckdb` open, and
    `corte_manifiesto` says the corte is `org`'s - so the instance sees only its own DuckDB, by name and by
    content. Logged in as the organization's user (`usuario`: (email, clave)): GET /api/database lists its
    database and no other, and native SQL on it is 403 (provisioning never grants
    `create-queries: "query-builder-and-native"`, plan § Rulings: PROPIA).

    Returns a Fallas (empty = every comprobación passed); an ErrorMetabase propagates only from a request that is
    not itself what a comprobación checks (the administrator's login, a listing), never from one that merely
    found something wrong."""
    comun.validar_org(org)
    archivo = f"/datos/{org}.duckdb"
    mb = Metabase(url)
    mb.sesion(*admin)
    fallas = Fallas()

    fallas.comprobaciones += 1
    salud = mb.get("/api/health")
    if not isinstance(salud, dict) or salud.get("status") != "ok":
        fallas.append(f"/api/health no respondió ok: {salud!r}")

    fallas.comprobaciones += 1
    bases = (mb.get("/api/database") or {}).get("data") or []
    propia = next((b for b in bases if (b.get("details") or {}).get("database_file") == archivo), None)
    if propia is None and len(bases) == 1:
        propia = bases[0]
    if not bases:
        fallas.append(f"{org}: la instancia no tiene ninguna base (¿corrió provisionar.py?)")
    otras = [b for b in bases if b is not propia]
    if otras:
        nombres = ", ".join(f"{b.get('name')} ({(b.get('details') or {}).get('database_file')})" for b in otras)
        fallas.append(f"{org}: la instancia tiene otra base además de la suya: {nombres}")
    if propia is None:
        return fallas
    nombre = propia.get("name")
    base_id = propia["id"]
    otro_archivo = (propia.get("details") or {}).get("database_file")
    if otro_archivo != archivo:
        fallas.append(f"{nombre} abre {otro_archivo}, no {archivo}")

    fallas.comprobaciones += 1
    try:
        mb.post(f"/api/database/{base_id}/sync_schema")
    except ErrorMetabase as e:
        fallas.append(f"{nombre}: sync_schema -> {e}")
    else:
        if not _esperar_vista(mb, base_id, "v_segmentos", espera):
            fallas.append(f"{nombre}: v_segmentos no aparece en sus metadatos tras sincronizar")

    colecciones = [
        c for c in mb.get("/api/collection") or []
        if isinstance(c.get("id"), int) and str(c.get("name", "")).startswith(PREFIJO)
    ]
    for c in colecciones:
        items = (mb.get(f"/api/collection/{c['id']}/items?models=card") or {}).get("data") or []
        for item in items:
            fallas.comprobaciones += 1
            # a query that ran and failed answers an HTTP error in Metabase 0.63.19, not 202 "failed"
            # (query_processor/streaming.clj `status-code`: 400 for a client error, 500 for the rest; 403 is a
            # permission error): one falla naming the card, and the run goes on with the next one
            try:
                resultado = mb.post(f"/api/card/{item['id']}/query") or {}
            except ErrorMetabase as e:
                fallas.append(f"{c['name']}: la tarjeta «{item['name']}» no corrió ({e.estado})")
                continue
            if resultado.get("status") != "completed":
                fallas.append(f"{c['name']}: la tarjeta «{item['name']}» no corrió ({resultado.get('status')!r})")

    fallas.comprobaciones += 1
    filas = _nativa(mb, base_id, _ACCESO_EXTERNO)
    if isinstance(filas, str) or filas[:1] not in ([[False]], [["false"]]):
        fallas.append(f"{nombre}: enable_external_access no es false ({filas!r}): ¿falta su init_sql?")

    fallas.comprobaciones += 1
    filas = _nativa(mb, base_id, _ARCHIVOS_ABIERTOS)
    rutas = filas if isinstance(filas, str) else [str(f[0]) for f in filas]
    if isinstance(rutas, str) or len(rutas) != 1 or rutas[0].rsplit("/", 1)[-1] != f"{org}.duckdb":
        fallas.append(f"{nombre}: duckdb_databases() muestra {rutas!r}, no solo {org}.duckdb")

    fallas.comprobaciones += 1
    filas = _nativa(mb, base_id, _ORGANIZACION_DEL_CORTE)
    if filas != [[org]]:
        fallas.append(f"{nombre}: corte_manifiesto es de {filas!r}, no de {org}")

    mu = Metabase(url)
    fallas.comprobaciones += 1
    try:
        mu.sesion(*usuario)
    except ErrorMetabase as e:
        fallas.append(f"{org}: no pudo iniciar sesión ({e})")
        return fallas

    fallas.comprobaciones += 1
    vistas = {b["name"]: b["id"] for b in (mu.get("/api/database") or {}).get("data") or []}
    if base_id not in vistas.values():
        fallas.append(f"{org} no ve su propia base ({nombre})")
    ajenas = sorted(n for n, i in vistas.items() if i != base_id)
    if ajenas:
        fallas.append(f"{org} ve, además de la suya, otra base: {', '.join(ajenas)}")

    fallas.comprobaciones += 1
    try:
        mu.post("/api/dataset", {"database": base_id, "type": "native", "native": {"query": "SELECT 1"}})
    except ErrorMetabase as e:
        if e.estado != 403:
            fallas.append(f"{org}: SQL nativo sobre {nombre} dio {e.estado}, no 403 (no debería tener SQL nativo)")
    else:
        fallas.append(f"{org} pudo correr SQL nativo sobre su propia base ({nombre}): nadie debería tenerlo")

    return fallas
