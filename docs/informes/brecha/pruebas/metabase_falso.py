"""A fake Metabase 0.63 for provisionar.py's and humo.py's tests (#206, tasks 10 and 11).

Modeled on herramientas/falsos.py (a ThreadingHTTPServer, a lock, a `.peticiones` log, a fast shutdown), but
speaking Metabase's own REST API with the shapes of its v0.63.19 source (task 10's report names the file behind
each one): a session in the `X-Metabase-Session` header, the v2 data permissions graph (`view-data`,
`create-queries`) and the collection graph, both with a `revision` that a stale PUT gets a 409 for, cards whose
`dataset_query` comes back as MBQL 5 unless `?legacy-mbql=true`, dashboards updated with `dashcards` (negative
ids are new ones, the ones left out are deleted).

It keeps Metabase's defaults that matter for isolation (permissions/models/data_permissions.clj): a new database
gives each non-admin group the most restrictive `create-queries` it already has on another database, and
"query-builder-and-native" to a group that has none - so "All Users" gets native SQL on the first one
(set-default-database-permissions!); a new group copies "All Users"' current data permissions
(set-default-group-permissions!, called by permissions_group.clj's after-insert); a new collection copies the
root's permissions (where "All Users" can write) - so a provisioning that forgot to take something away shows in
a test. A non-admin session sees only what its groups'
graphs grant: the databases it can query, the cards and dashboards of the collections it can read, native SQL
only with `create-queries` "query-builder-and-native". Test helper only; never imported by the scripts.

A query that fails answers like Metabase 0.63.19's streaming response: an HTTP error status with the failed
result as its JSON body (`{"status": "failed", "error": ...}`), never a 202. query_processor/streaming.clj
`status-code` gives 403 to a permission error, 400 to a client error (an invalid query, which is what DuckDB's
refusals become) and 500 to the rest, and server/streaming_response.clj `write-error!` sets it while the response
is not committed yet; upstream's query_processor/api_test.clj `failure-test` expects `:post 400 "dataset"`.
POST /api/card/:id/query goes through the same streaming response (query_processor/card.clj).

Task 11's smoke test turns knobs to simulate a broken isolation or a broken card: `listar_todas_las_bases`
(every session lists every database), `permitir_nativo` (native SQL for anyone), `tarjetas_fallidas` (card ids
whose query fails with a 400), `permitir_lectura_ajena` (any session can read any card or dashboard, ignoring the
collection graph) and `permitir_acceso_externo` (a native ATTACH/read_parquet/etc. "succeeds" instead of failing).
`SELECT current_setting('enable_external_access')` answers from the database's own `details.init_sql`: false only
when it sets `enable_external_access = false`, as base.INIT_SQL does. The smoke test's two checks that an instance
holds only its organization's DuckDB (plan, Decisión 2026-10-25) answer from the database too: a query on
`duckdb_databases()` lists its `details.database_file` plus `adjuntos_extra` (paths of files simulated as also
open), and one on `corte_manifiesto` gives the file's stem as the corte's organizacion unless
`organizacion_del_corte` (database id -> org) says otherwise.
"""

import itertools
import json
import re
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from herramientas import comun

TODOS = 1  # "All Users", magic_group_type "all-internal-users"
ADMINISTRADORES = 2  # "Administrators", magic_group_type "admin"

# permission levels from least to most (a user's effective level is the greatest among its groups)
_CONSULTAS = ("no", "query-builder", "query-builder-and-native")
_COLECCION = ("none", "read", "write")
_VER_DATOS = ("unrestricted", "legacy-no-self-service", "blocked")
# the closed map of permissions_rest/schema.clj ::db-perms: any other key is a 400
_LLAVES_PERMISOS = {"view-data", "create-queries", "download", "data-model", "details", "transforms"}
# what INIT_SQL (enable_external_access = false) makes DuckDB refuse in a native query
_SIN_ACCESO_EXTERNO = re.compile(r"\b(ATTACH|read_parquet|read_csv\w*|read_json\w*|COPY|INSTALL|LOAD)\b", re.I)
# the smoke test's positive check of INIT_SQL, and what makes it answer false
_CONSULTA_ACCESO_EXTERNO = re.compile(r"current_setting\(\s*'enable_external_access'\s*\)", re.I)
_CONSULTA_ARCHIVOS = re.compile(r"\bduckdb_databases\(\)", re.I)
_CONSULTA_ORGANIZACION = re.compile(r"\bcorte_manifiesto\b", re.I)
_INIT_SQL_SIN_ACCESO = re.compile(r"\bSET\s+enable_external_access\s*=\s*false\b", re.I)

_NO_PERMITIDO = "You don't have permissions to do that."
# what PUT /api/card/:id may change (a subset of card.clj CardUpdateSchema)
_CAMPOS_TARJETA = ("name", "type", "display", "visualization_settings", "dataset_query", "collection_id", "archived")


def _vistas():
    texto = (comun.INFORME / "vistas.sql").read_text(encoding="utf-8")
    return re.findall(r"CREATE OR REPLACE VIEW (\w+)", texto)


def _fallida(error):
    """The body of a failed query (query_processor/api_test.clj failure-test): no rows, `status` "failed"."""
    return {"status": "failed", "error": error, "row_count": 0, "data": {"rows": [], "cols": []}}


class _Respuesta(Exception):
    """Internal signal: a route answers early with this status and payload (a 403, a 404, a 400...)."""

    def __init__(self, estado, payload):
        super().__init__(estado)
        self.estado = estado
        self.payload = payload


class _Manejador(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # keep test output quiet, like herramientas/falsos.py

    def _atender(self, metodo):
        falso = self.server.falso
        largo = int(self.headers.get("Content-Length", 0))
        crudo = self.rfile.read(largo) if largo else b""
        try:
            cuerpo = json.loads(crudo) if crudo else None
        except ValueError:
            cuerpo = None
        ruta, _, query = self.path.partition("?")
        sesion = self.headers.get("X-Metabase-Session")
        with falso._lock:
            falso.peticiones.append((metodo, self.path, cuerpo))
            try:
                estado, payload = falso._responder(metodo, ruta, query, cuerpo, sesion)
            except _Respuesta as r:
                estado, payload = r.estado, r.payload
        datos = b"" if payload is None else json.dumps(payload).encode("utf-8")
        self.send_response(estado)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(datos)))
        self.end_headers()
        self.wfile.write(datos)

    def do_GET(self):
        self._atender("GET")

    def do_POST(self):
        self._atender("POST")

    def do_PUT(self):
        self._atender("PUT")


class MetabaseFalso:
    """`usuarios` (email -> clave) are the instance's administrators; an empty dict is a Metabase that has not
    been set up yet (`has-user-setup` false, a `setup-token` to finish it with POST /api/setup). `tablas` are the
    tables a synced database shows in its metadata (by omission, the views of vistas.sql)."""

    SETUP_TOKEN = "token-de-setup-falso"

    def __init__(self, usuarios, tablas=None):
        self._lock = threading.Lock()
        self.peticiones = []
        self.tablas = list(tablas) if tablas is not None else _vistas()
        self.listar_todas_las_bases = False
        self.permitir_nativo = False
        self.tarjetas_fallidas = set()
        self.permitir_lectura_ajena = False
        self.permitir_acceso_externo = False
        self.adjuntos_extra = []
        self.organizacion_del_corte = {}
        # database_file values whose connection test fails: POST /api/database answers 400 and creates nothing
        # (warehouses_rest/api.clj POST /), as with a <org>.duckdb that `make duckdb` never built
        self.archivos_inexistentes = set()

        self.usuarios = {}
        self.sesiones = {}
        self.grupos = {
            TODOS: {"id": TODOS, "name": "All Users", "magic_group_type": "all-internal-users"},
            ADMINISTRADORES: {"id": ADMINISTRADORES, "name": "Administrators", "magic_group_type": "admin"},
        }
        self._siguiente_grupo = itertools.count(3)
        self.membresias = []
        self._siguiente_membresia = itertools.count(1)
        self.bases = {}
        self._siguiente_base = itertools.count(1)
        self.sincronizadas = set()  # database ids a POST /api/database/:id/sync_schema went through
        self.grafo_datos = {TODOS: {}}  # group -> database -> {"view-data", "create-queries"}
        self.revision_datos = 0
        self.colecciones = {}
        self._siguiente_coleccion = itertools.count(1)
        self.grafo_colecciones = {TODOS: {"root": "write"}}  # group -> collection id or "root" -> perm
        self.revision_colecciones = 0
        self.tarjetas = {}
        self._siguiente_tarjeta = itertools.count(1)
        self.tableros = {}
        self._siguiente_tablero = itertools.count(1)
        self._siguiente_dashcard = itertools.count(1)
        self.setup_hecho = bool(usuarios)
        for email, clave in usuarios.items():
            self._crear_usuario(email, clave, "Admin", "Falso", admin=True)

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _Manejador)
        self._server.falso = self
        # a short poll keeps cerrar() from waiting the default half second for serve_forever to notice
        self._thread = threading.Thread(target=self._server.serve_forever, args=(0.01,), daemon=True)
        self._thread.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def cerrar(self):
        self._server.shutdown()
        self._server.server_close()

    # --- state helpers ------------------------------------------------------------------------------------

    def _crear_usuario(self, email, clave, nombre, apellido, admin=False):
        uid = len(self.usuarios) + 1
        self.usuarios[uid] = {
            "id": uid,
            "email": email,
            "first_name": nombre,
            "last_name": apellido,
            "is_superuser": admin,
            "is_active": True,
            "_clave": clave,
        }
        self._agregar_membresia(uid, TODOS)
        if admin:
            self._agregar_membresia(uid, ADMINISTRADORES)
        return uid

    def _agregar_membresia(self, uid, gid):
        if not any(m["user_id"] == uid and m["group_id"] == gid for m in self.membresias):
            self.membresias.append(
                {"membership_id": next(self._siguiente_membresia), "group_id": gid, "user_id": uid,
                 "is_group_manager": False}
            )

    def _usuario_publico(self, usuario):
        return {clave: valor for clave, valor in usuario.items() if not clave.startswith("_")}

    def grupos_de(self, uid):
        return {m["group_id"] for m in self.membresias if m["user_id"] == uid}

    def _es_admin(self, uid):
        return self.usuarios[uid]["is_superuser"]

    def _consultas(self, uid, base_id):
        """The user's effective create-queries level on a database (admins: native SQL everywhere)."""
        if self._es_admin(uid) or self.permitir_nativo:
            return "query-builder-and-native"
        niveles = [
            self.grafo_datos.get(g, {}).get(base_id, {}).get("create-queries", "no") for g in self.grupos_de(uid)
        ]
        return max(niveles, key=_CONSULTAS.index, default="no")

    def _permiso_coleccion(self, uid, coleccion_id):
        if self._es_admin(uid):
            return "write"
        clave = "root" if coleccion_id is None else coleccion_id
        niveles = [self.grafo_colecciones.get(g, {}).get(clave, "none") for g in self.grupos_de(uid)]
        return max(niveles, key=_COLECCION.index, default="none")

    def _leer_coleccion(self, uid, coleccion_id):
        if self.permitir_lectura_ajena:
            return
        if self._permiso_coleccion(uid, coleccion_id) == "none":
            raise _Respuesta(403, _NO_PERMITIDO)

    def _ve_base(self, uid, base_id):
        return self.listar_todas_las_bases or self._consultas(uid, base_id) != "no"

    # --- routing --------------------------------------------------------------------------------------------

    def _responder(self, metodo, ruta, query, cuerpo, sesion):
        params = urllib.parse.parse_qs(query)
        cuerpo = cuerpo if cuerpo is not None else {}
        if metodo == "GET" and ruta == "/api/health":
            return 200, {"status": "ok"}
        if metodo == "GET" and ruta == "/api/session/properties":
            return 200, {
                "has-user-setup": self.setup_hecho,
                "setup-token": None if self.setup_hecho else self.SETUP_TOKEN,
                "version": {"tag": "v0.63.19"},
            }
        if metodo == "POST" and ruta == "/api/setup":
            return self._setup(cuerpo)
        if metodo == "POST" and ruta == "/api/session":
            return self._sesion(cuerpo)
        uid = self.sesiones.get(sesion)
        if uid is None:
            return 401, "Unauthenticated"

        partes = ruta.strip("/").split("/")[1:]  # without the leading "api"
        recurso = partes[0] if partes else ""
        manejador = getattr(self, f"_r_{recurso}", None)
        if manejador is None:
            return 404, "API endpoint does not exist."
        return manejador(uid, metodo, partes[1:], params, cuerpo)

    def _solo_admin(self, uid):
        if not self._es_admin(uid):
            raise _Respuesta(403, _NO_PERMITIDO)

    @staticmethod
    def _entero(texto):
        try:
            return int(texto)
        except (TypeError, ValueError):
            raise _Respuesta(404, "Not found.") from None

    # --- setup and session ----------------------------------------------------------------------------------

    def _setup(self, cuerpo):
        if self.setup_hecho:
            return 403, "The /api/setup route can only be used to create the first user."
        if cuerpo.get("token") != self.SETUP_TOKEN:
            return 403, "Token does not match the setup token."
        usuario = cuerpo.get("user") or {}
        if not usuario.get("email") or not usuario.get("password") or not (cuerpo.get("prefs") or {}).get("site_name"):
            return 400, {"errors": {"user": "falta email, password o prefs.site_name"}}
        uid = self._crear_usuario(
            usuario["email"], usuario["password"], usuario.get("first_name"), usuario.get("last_name"), admin=True
        )
        self.setup_hecho = True
        return 200, {"id": self._nueva_sesion(uid)}

    def _sesion(self, cuerpo):
        for usuario in self.usuarios.values():
            if (
                usuario["email"].lower() == str(cuerpo.get("username", "")).lower()
                and usuario["_clave"] == cuerpo.get("password")
                and usuario["is_active"]
            ):
                return 200, {"id": self._nueva_sesion(usuario["id"])}
        return 401, {"errors": {"password": "did not match stored password"}}

    def _nueva_sesion(self, uid):
        token = f"sesion-{uid}-{len(self.sesiones) + 1}"
        self.sesiones[token] = uid
        return token

    # --- /api/user ------------------------------------------------------------------------------------------

    def _r_user(self, uid, metodo, partes, params, cuerpo):
        self._solo_admin(uid)
        if metodo == "GET" and not partes:
            consulta = (params.get("query") or [""])[0].lower()
            # like users_rest/api.clj GET /: active users unless `status` is "all" or "deactivated" (an admin-only
            # parameter), `query` matched against first name, last name and email
            estado = (params.get("status") or ["active"])[0]
            activos = {"active": (True,), "deactivated": (False,), "all": (True, False)}.get(estado, (True,))
            datos = [
                self._usuario_publico(u)
                for u in self.usuarios.values()
                if u["is_active"] in activos
                and consulta in " ".join((u["email"], u["first_name"] or "", u["last_name"] or "")).lower()
            ]
            return 200, {"data": datos, "total": len(datos), "limit": None, "offset": None}
        if metodo == "POST" and not partes:
            email = cuerpo.get("email")
            if not email:
                return 400, {"errors": {"email": "value must be a valid email address."}}
            if any(u["email"].lower() == email.lower() for u in self.usuarios.values()):
                return 400, {"errors": {"email": "Email address already in use."}}
            nuevo = self._crear_usuario(
                email, cuerpo.get("password"), cuerpo.get("first_name"), cuerpo.get("last_name")
            )
            return 200, self._usuario_publico(self.usuarios[nuevo])
        return 404, "API endpoint does not exist."

    # --- /api/permissions -----------------------------------------------------------------------------------

    def _r_permissions(self, uid, metodo, partes, params, cuerpo):
        self._solo_admin(uid)
        if partes == ["group"] and metodo == "GET":
            return 200, [
                {**g, "member_count": sum(1 for m in self.membresias if m["group_id"] == g["id"])}
                for g in sorted(self.grupos.values(), key=lambda g: g["name"].lower())
            ]
        if partes == ["group"] and metodo == "POST":
            nombre = cuerpo.get("name")
            if not nombre:
                return 400, {"errors": {"name": "value must be a non-blank string."}}
            gid = next(self._siguiente_grupo)
            self.grupos[gid] = {"id": gid, "name": nombre, "magic_group_type": None}
            # a new group's data permissions are a copy of All Users' (wherever All Users can still write native
            # SQL, so can it); it has no collection until a graph says so
            self.grafo_datos[gid] = {
                b: {"view-data": "unrestricted", "create-queries": self._todos_consulta(b)} for b in self.bases
            }
            self.grafo_colecciones[gid] = {"root": "none", **{cid: "none" for cid in self.colecciones}}
            return 200, self.grupos[gid]
        if partes == ["membership"] and metodo == "GET":
            mapa = {}
            for m in self.membresias:
                mapa.setdefault(str(m["user_id"]), []).append(dict(m))
            return 200, mapa
        if partes == ["membership"] and metodo == "POST":
            gid, usuario = cuerpo.get("group_id"), cuerpo.get("user_id")
            if gid not in self.grupos or usuario not in self.usuarios:
                return 404, "Not found."
            self._agregar_membresia(usuario, gid)
            miembros = [m["user_id"] for m in self.membresias if m["group_id"] == gid]
            return 200, [self._usuario_publico(self.usuarios[u]) for u in miembros]
        if partes == ["graph"] and metodo == "GET":
            return 200, self._grafo_datos_publico()
        if partes == ["graph"] and metodo == "PUT":
            return self._poner_grafo_datos(cuerpo)
        return 404, "API endpoint does not exist."

    def _todos_consulta(self, base_id):
        return self.grafo_datos.get(TODOS, {}).get(base_id, {}).get("create-queries", "query-builder-and-native")

    def _consulta_por_omision(self, gid):
        """A new database's create-queries for group `gid`: the most restrictive it has on any database, or
        native SQL when it has none (data_permissions.clj set-default-database-permissions!)."""
        niveles = [p.get("create-queries", "no") for p in self.grafo_datos.get(gid, {}).values()]
        return min(niveles, key=_CONSULTAS.index, default="query-builder-and-native")

    def _grafo_datos_publico(self):
        grupos = {str(g): {str(b): dict(p) for b, p in bases.items()} for g, bases in self.grafo_datos.items()}
        grupos[str(ADMINISTRADORES)] = {
            str(b): {"view-data": "unrestricted", "create-queries": "query-builder-and-native"} for b in self.bases
        }
        return {"revision": self.revision_datos, "groups": grupos}

    def _poner_grafo_datos(self, cuerpo):
        grupos = cuerpo.get("groups")
        if not isinstance(grupos, dict):
            return 400, {"errors": {"groups": "must be a map"}}
        if not cuerpo.get("force") and cuerpo.get("revision") != self.revision_datos:
            return 409, "Looks like someone else edited the permissions and your data is out of date."
        cambios = {}
        for g, bases in grupos.items():
            gid = self._entero(g)
            if gid not in self.grupos or gid == ADMINISTRADORES:
                return 400, {"errors": {"groups": f"grupo inválido: {g}"}}
            for b, permisos in (bases or {}).items():
                base_id = self._entero(b)
                if base_id not in self.bases or not isinstance(permisos, dict) or set(permisos) - _LLAVES_PERMISOS:
                    return 400, {"errors": {"groups": f"permisos inválidos para {g}/{b}"}}
                if (
                    permisos.get("create-queries", "no") not in _CONSULTAS
                    or permisos.get("view-data", "unrestricted") not in _VER_DATOS
                ):
                    return 400, {"errors": {"groups": f"valor inválido para {g}/{b}"}}
                cambios.setdefault(gid, {})[base_id] = permisos
        # like data/diff + update-data-perms-graph!: what the body names changes, what it leaves out stays
        for gid, bases in cambios.items():
            for base_id, permisos in bases.items():
                self.grafo_datos.setdefault(gid, {}).setdefault(base_id, {}).update(permisos)
        self.revision_datos += 1
        return 200, self._grafo_datos_publico()

    # --- /api/collection ------------------------------------------------------------------------------------

    def _r_collection(self, uid, metodo, partes, params, cuerpo):
        if partes == ["graph"]:
            self._solo_admin(uid)
            if metodo == "GET":
                return 200, self._grafo_colecciones_publico()
            if metodo == "PUT":
                return self._poner_grafo_colecciones(cuerpo)
        if not partes and metodo == "GET":
            visibles = [
                dict(c) for c in self.colecciones.values() if self._permiso_coleccion(uid, c["id"]) != "none"
            ]
            raiz = [{"id": "root", "name": "Our analytics"}] if self._permiso_coleccion(uid, None) != "none" else []
            return 200, raiz + visibles
        if not partes and metodo == "POST":
            self._solo_admin(uid)
            if not cuerpo.get("name"):
                return 400, {"errors": {"name": "value must be a non-blank string."}}
            if cuerpo.get("parent_id") is not None:
                return 400, {"errors": {"parent_id": "el falso solo crea colecciones en la raíz"}}
            cid = next(self._siguiente_coleccion)
            self.colecciones[cid] = {
                "id": cid, "name": cuerpo["name"], "location": "/", "personal_owner_id": None, "archived": False,
            }
            # a collection made at the root copies the root's permissions (All Users can write there by default)
            for permisos in self.grafo_colecciones.values():
                permisos[cid] = permisos.get("root", "none")
            return 200, self.colecciones[cid]
        if len(partes) == 2 and partes[1] == "items" and metodo == "GET":
            cid = self._entero(partes[0])
            if cid not in self.colecciones:
                return 404, "Not found."
            self._leer_coleccion(uid, cid)
            modelos = set(params.get("models") or ["card", "dashboard"])
            datos = []
            if "card" in modelos:
                datos += [{"id": t["id"], "name": t["name"], "model": "card"}
                          for t in self.tarjetas.values() if t["collection_id"] == cid and not t["archived"]]
            if "dashboard" in modelos:
                datos += [{"id": t["id"], "name": t["name"], "model": "dashboard"}
                          for t in self.tableros.values() if t["collection_id"] == cid and not t["archived"]]
            return 200, {"data": datos, "total": len(datos), "models": sorted(modelos), "limit": None, "offset": None}
        return 404, "API endpoint does not exist."

    def _grafo_colecciones_publico(self):
        grupos = {str(g): {str(c): p for c, p in permisos.items()} for g, permisos in self.grafo_colecciones.items()}
        grupos[str(ADMINISTRADORES)] = {"root": "write", **{str(c): "write" for c in self.colecciones}}
        return {"revision": self.revision_colecciones, "groups": grupos}

    def _poner_grafo_colecciones(self, cuerpo):
        grupos = cuerpo.get("groups")
        if not isinstance(grupos, dict):
            return 400, {"errors": {"groups": "must be a map"}}
        if not cuerpo.get("force") and cuerpo.get("revision") != self.revision_colecciones:
            return 409, "Looks like someone else edited the permissions and your data is out of date."
        cambios = []
        for g, permisos in grupos.items():
            gid = self._entero(g)
            if gid not in self.grupos or gid == ADMINISTRADORES:
                return 400, {"errors": {"groups": f"grupo inválido: {g}"}}
            for c, permiso in permisos.items():
                clave = "root" if c == "root" else self._entero(c)
                if (clave != "root" and clave not in self.colecciones) or permiso not in _COLECCION:
                    return 400, {"errors": {"groups": f"permiso inválido para {g}/{c}"}}
                cambios.append((gid, clave, permiso))
        for gid, clave, permiso in cambios:
            self.grafo_colecciones.setdefault(gid, {})[clave] = permiso
        self.revision_colecciones += 1
        return 200, self._grafo_colecciones_publico()

    # --- /api/database --------------------------------------------------------------------------------------

    def _r_database(self, uid, metodo, partes, params, cuerpo):
        if not partes and metodo == "GET":
            datos = []
            for base in self.bases.values():
                if self._es_admin(uid):
                    datos.append(dict(base))
                elif self._ve_base(uid, base["id"]):
                    datos.append({k: v for k, v in base.items() if k != "details"})
            return 200, {"data": datos, "total": len(datos)}
        if not partes and metodo == "POST":
            self._solo_admin(uid)
            if not cuerpo.get("name") or not cuerpo.get("engine") or not isinstance(cuerpo.get("details"), dict):
                return 400, {"errors": {"name": "name, engine y details son obligatorios"}}
            if cuerpo["details"].get("database_file") in self.archivos_inexistentes:
                return 400, {"message": "No se pudo conectar", "errors": {"database_file": "no existe"}}
            base_id = next(self._siguiente_base)
            self.bases[base_id] = {
                "id": base_id, "name": cuerpo["name"], "engine": cuerpo["engine"], "details": dict(cuerpo["details"]),
                "is_full_sync": cuerpo.get("is_full_sync", True),
            }
            # Metabase's default for a new database, per non-admin group (All Users included): its most restrictive
            # level elsewhere, native SQL when it has none
            for gid in self.grupos:
                if gid != ADMINISTRADORES:
                    self.grafo_datos.setdefault(gid, {})[base_id] = {
                        "view-data": "unrestricted", "create-queries": self._consulta_por_omision(gid),
                    }
            return 200, self.bases[base_id]
        if not partes:
            return 404, "API endpoint does not exist."
        base_id = self._entero(partes[0])
        if base_id not in self.bases:
            return 404, "Not found."
        if len(partes) == 1 and metodo == "PUT":
            self._solo_admin(uid)
            base = self.bases[base_id]
            for clave in ("name", "engine", "details", "is_full_sync"):
                if clave in cuerpo:
                    base[clave] = dict(cuerpo[clave]) if clave == "details" else cuerpo[clave]
            return 200, base
        if partes[1:] == ["sync_schema"] and metodo == "POST":
            self._solo_admin(uid)
            self.sincronizadas.add(base_id)
            return 200, {"status": "ok"}
        if partes[1:] == ["metadata"] and metodo == "GET":
            if not self._es_admin(uid) and not self._ve_base(uid, base_id):
                return 403, _NO_PERMITIDO
            sincronizada = base_id in self.sincronizadas
            tablas = [{"name": t, "schema": "main", "db_id": base_id} for t in self.tablas] if sincronizada else []
            return 200, {"id": base_id, "name": self.bases[base_id]["name"], "tables": tablas}
        return 404, "API endpoint does not exist."

    # --- /api/card and /api/dataset -------------------------------------------------------------------------

    def _r_card(self, uid, metodo, partes, params, cuerpo):
        if not partes and metodo == "POST":
            self._solo_admin(uid)
            faltan = [k for k in ("name", "display", "visualization_settings", "dataset_query") if k not in cuerpo]
            if faltan:
                return 400, {"errors": {k: "required" for k in faltan}}
            self._validar_consulta(cuerpo["dataset_query"])
            tid = next(self._siguiente_tarjeta)
            self.tarjetas[tid] = {
                "id": tid, "name": cuerpo["name"], "type": cuerpo.get("type", "question"), "display": cuerpo["display"],
                "visualization_settings": cuerpo["visualization_settings"], "dataset_query": cuerpo["dataset_query"],
                "collection_id": cuerpo.get("collection_id"), "archived": False,
            }
            return 200, self._tarjeta_publica(self.tarjetas[tid], legacy=False)
        if not partes:
            return 404, "API endpoint does not exist."
        tid = self._entero(partes[0])
        tarjeta = self.tarjetas.get(tid)
        if tarjeta is None:
            return 404, "Not found."
        if len(partes) == 1 and metodo == "GET":
            self._leer_coleccion(uid, tarjeta["collection_id"])
            legacy = (params.get("legacy-mbql") or ["false"])[0] == "true"
            return 200, self._tarjeta_publica(tarjeta, legacy)
        if len(partes) == 1 and metodo == "PUT":
            self._solo_admin(uid)
            if "dataset_query" in cuerpo:
                self._validar_consulta(cuerpo["dataset_query"])
            for clave in _CAMPOS_TARJETA:
                if clave in cuerpo:
                    tarjeta[clave] = cuerpo[clave]
            return 200, self._tarjeta_publica(tarjeta, legacy=False)
        if partes[1:] == ["query"] and metodo == "POST":
            self._leer_coleccion(uid, tarjeta["collection_id"])
            if tid in self.tarjetas_fallidas:
                return 400, _fallida("falla simulada")
            return 202, {"status": "completed", "row_count": 0, "data": {"rows": [], "cols": []}}
        return 404, "API endpoint does not exist."

    def _validar_consulta(self, consulta):
        if not isinstance(consulta, dict) or consulta.get("database") not in self.bases:
            raise _Respuesta(400, {"errors": {"dataset_query": "base inexistente"}})

    @staticmethod
    def _tarjeta_publica(tarjeta, legacy):
        """GET /api/card/:id answers MBQL 5 since v57 (`?legacy-mbql=true` for the legacy shape it was saved in)."""
        publica = {k: v for k, v in tarjeta.items()}
        consulta = tarjeta["dataset_query"]
        if not legacy and consulta.get("type") == "native":
            publica["dataset_query"] = {
                "lib/type": "mbql/query",
                "database": consulta["database"],
                "stages": [{"lib/type": "mbql.stage/native", "native": consulta["native"]["query"]}],
            }
        return publica

    def _r_dataset(self, uid, metodo, partes, params, cuerpo):
        if partes or metodo != "POST":
            return 404, "API endpoint does not exist."
        base_id = cuerpo.get("database")
        if base_id not in self.bases:
            return 404, "Not found."
        nivel = self._consultas(uid, base_id)
        if cuerpo.get("type") == "native":
            if nivel != "query-builder-and-native":
                return 403, "You do not have permissions to run this query."
            sql = (cuerpo.get("native") or {}).get("query") or ""
            if _CONSULTA_ACCESO_EXTERNO.search(sql):
                init_sql = self.bases[base_id]["details"].get("init_sql") or ""
                valor = not _INIT_SQL_SIN_ACCESO.search(init_sql)
                datos = {"rows": [[valor]], "cols": [{"name": "v"}]}
                return 202, {"status": "completed", "row_count": 1, "data": datos}
            archivo = self.bases[base_id]["details"].get("database_file") or ""
            if _CONSULTA_ARCHIVOS.search(sql):
                filas = [[ruta] for ruta in sorted([archivo, *self.adjuntos_extra])]
                datos = {"rows": filas, "cols": [{"name": "path"}]}
                return 202, {"status": "completed", "row_count": len(filas), "data": datos}
            if _CONSULTA_ORGANIZACION.search(sql):
                org = self.organizacion_del_corte.get(base_id) or archivo.rsplit("/", 1)[-1].removesuffix(".duckdb")
                datos = {"rows": [[org]], "cols": [{"name": "organizacion"}]}
                return 202, {"status": "completed", "row_count": 1, "data": datos}
            if not self.permitir_acceso_externo and _SIN_ACCESO_EXTERNO.search(sql):
                return 400, _fallida("Permission Error: file system operations are disabled by configuration")
            return 202, {"status": "completed", "row_count": 1, "data": {"rows": [[1]], "cols": [{"name": "1"}]}}
        if nivel == "no":
            return 403, "You do not have permissions to run this query."
        return 202, {"status": "completed", "row_count": 0, "data": {"rows": [], "cols": []}}

    # --- /api/dashboard -------------------------------------------------------------------------------------

    def _r_dashboard(self, uid, metodo, partes, params, cuerpo):
        if not partes and metodo == "POST":
            self._solo_admin(uid)
            if not cuerpo.get("name"):
                return 400, {"errors": {"name": "value must be a non-blank string."}}
            did = next(self._siguiente_tablero)
            self.tableros[did] = {
                "id": did, "name": cuerpo["name"], "collection_id": cuerpo.get("collection_id"), "archived": False,
                "dashcards": [],
            }
            return 200, self.tableros[did]
        if not partes:
            return 404, "API endpoint does not exist."
        did = self._entero(partes[0])
        tablero = self.tableros.get(did)
        if tablero is None:
            return 404, "Not found."
        if len(partes) == 1 and metodo == "GET":
            self._leer_coleccion(uid, tablero["collection_id"])
            return 200, tablero
        if len(partes) == 1 and metodo == "PUT":
            self._solo_admin(uid)
            if "dashcards" in cuerpo:
                tablero["dashcards"] = self._dashcards(tablero, cuerpo["dashcards"])
            for clave in ("name", "collection_id", "archived"):
                if clave in cuerpo:
                    tablero[clave] = cuerpo[clave]
            return 200, tablero
        return 404, "API endpoint does not exist."

    def _dashcards(self, tablero, nuevas):
        """dashboards_rest/api.clj UpdatedDashboardCard: an id each (negative = new), size_x/size_y >= 1,
        row/col >= 0; an existing id must be one of this dashboard's; the ones left out are deleted."""
        existentes = {d["id"] for d in tablero["dashcards"]}
        ids = [d.get("id") for d in nuevas]
        if len(ids) != len(set(ids)):
            raise _Respuesta(400, {"errors": {"dashcards": "ids repetidos"}})
        resultado = []
        for d in nuevas:
            if not isinstance(d.get("id"), int) or (d["id"] > 0 and d["id"] not in existentes):
                raise _Respuesta(400, {"errors": {"dashcards": f"id inválido: {d.get('id')!r}"}})
            for clave, minimo in (("size_x", 1), ("size_y", 1), ("row", 0), ("col", 0)):
                if not isinstance(d.get(clave), int) or d[clave] < minimo:
                    raise _Respuesta(400, {"errors": {"dashcards": f"{clave} inválido"}})
            if d.get("card_id") is not None and d["card_id"] not in self.tarjetas:
                raise _Respuesta(400, {"errors": {"dashcards": f"tarjeta inexistente: {d['card_id']}"}})
            resultado.append({
                "id": d["id"] if d["id"] > 0 else next(self._siguiente_dashcard),
                "dashboard_id": tablero["id"],
                "card_id": d.get("card_id"),
                "row": d["row"], "col": d["col"], "size_x": d["size_x"], "size_y": d["size_y"],
                "visualization_settings": d.get("visualization_settings") or {},
                "parameter_mappings": d.get("parameter_mappings") or [],
            })
        return resultado
