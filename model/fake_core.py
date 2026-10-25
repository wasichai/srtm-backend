"""A scripted fake wasichai Core for the tests: metadata routes plus in-memory records, and srtm's
POST /api/srtm/infracciones/cuis and /cuis/derogacion (what import_cuis.py calls) as the service does it, and Core's
roles and service accounts (what apply_roles.py and crear_cuenta_analitica.py call), taken from catastro-backend's.

Adapted from wasichai's examples/gis-sample/perene/test_apply.py. Test helper only.
"""
import json
import re
from datetime import date, timedelta
import threading
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RECORDS = re.compile(r"^/api/objects/([a-z0-9_]+)/records$")
RECORD = re.compile(r"^/api/objects/([a-z0-9_]+)/records/([0-9a-f-]+)$")
FIELDS = re.compile(r"^/api/metadata/objects/([a-z0-9_]+)/fields$")
OBJECT = re.compile(r"^/api/objects/([a-z0-9_]+)$")
CUIS = "/api/srtm/infracciones/cuis"
SUGERIDO = re.compile(r"^/api/srtm/declaraciones/([^/]+)/autoavaluo-sugerido$")
RECALCULAR = re.compile(r"^/api/srtm/declaraciones/([^/]+)/recalcular$")
ROLE = re.compile(r"^/api/roles/([A-Z0-9_]+)$")
ROLE_PERMISSIONS = re.compile(r"^/api/roles/([A-Z0-9_]+)/permissions$")
SERVICE_ACCOUNT = re.compile(r"^/api/service-accounts/([0-9a-f-]+)$")
SERVICE_ACCOUNT_SECRET = re.compile(r"^/api/service-accounts/([0-9a-f-]+)/secret$")


def _meta(label, plural_label=None, description=None, enabled=True):
    """An object's own metadata as Core stores it: a blank plural is the label."""
    return {"label": label, "pluralLabel": (plural_label or "").strip() or label, "description": description,
            "enabled": enabled}


class FakeCoreHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # keep test output quiet

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw) if raw else None

    def _handle(self, method):
        server = self.server
        body = self._body()
        with server.lock:
            server.requests.append((method, self.path, self.headers.get("Authorization"), body))
            status, payload = server.script(method, self.path, body)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if payload is not None:
            self.wfile.write(json.dumps(payload).encode("utf-8"))

    def do_GET(self):
        self._handle("GET")

    def do_POST(self):
        self._handle("POST")

    def do_PUT(self):
        self._handle("PUT")

    def do_DELETE(self):
        self._handle("DELETE")


class FakeCore:
    """existing_objects/existing_relationships mark names that 409 on create; records live in memory."""

    def __init__(self, existing_objects=(), existing_relationships=(), fail_on_post_object=None,
                 fail_put=False, fail_put_status=500, login_response=None, fail_on_record=None, existing_fields=None,
                 fail_on_update=None, fail_on_delete=None, object_rules=None, fail_object_put=None, object_meta=None,
                 conflict_on_update=None, organization=None, sugeridos=None,
                 fail_on_role=None, fail_on_service_account=None):
        self.existing_objects = set(existing_objects)
        # object name -> its object-level rules ({"indexes", "appendOnly"}), as Core stores them; none by default
        self.object_rules = {n: dict((object_rules or {}).get(n, {})) for n in self.existing_objects}
        # object name -> {"label", "pluralLabel", "description", "enabled"}; the name as label by default
        self.object_meta = {n: {**_meta(n, n), **(object_meta or {}).get(n, {})} for n in self.existing_objects}
        self.fail_object_put = fail_object_put  # PUT /api/objects/{name} -> this status
        self.existing_fields = existing_fields or {}  # object name -> list of {"name", "enumOptions"?}
        self.existing_relationships = set(existing_relationships)
        self.fail_on_post_object = fail_on_post_object  # object name -> triggers 500
        self.fail_put = fail_put  # PUT .../fields/... -> fail_put_status
        self.fail_put_status = fail_put_status
        self.login_response = login_response  # override the default {"token": "t"}
        self.fail_on_record = fail_on_record  # object name -> its record POSTs answer 400
        self.fail_on_update = fail_on_update  # object name -> its record PUTs answer 400
        self.conflict_on_update = conflict_on_update  # object name -> its record PUTs answer 409 (srtm's ReglaDeNormativa)
        self.fail_on_delete = fail_on_delete  # object name -> its record DELETEs answer 409
        self.records = {}  # object name -> list of {"id", "attributes"}
        self.sugeridos = dict(sugeridos or {})  # declaración id -> its autoavaluo-sugerido dict, or 404 if missing
        # GET /api/organizations/current: the login's organization
        self.organization = organization or {"id": "00000000-0000-0000-0000-000000000001", "name": "Perené", "slug": "perene"}
        self.fail_on_role = fail_on_role  # role name -> its POST /api/roles answers 500
        self.roles = {}  # role name -> {"name", "label", "permissions": [{"objectName", "action", "allowed"}]}
        self.fail_on_service_account = fail_on_service_account  # POST /api/service-accounts -> this status
        self.service_accounts = {}  # id -> ServiceAccountResponse without the secret
        self.rotations = 0  # how many POST /api/service-accounts/{id}/secret it answered
        self.requests = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeCoreHandler)
        self.server.requests = self.requests
        self.server.script = self._script
        self.server.lock = threading.Lock()
        self.port = self.server.server_address[1]
        self.base_url = f"http://127.0.0.1:{self.port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    def add_record(self, object_name, attributes, geometries=None):
        record = {"id": str(uuid.uuid4()), "attributes": dict(attributes)}
        if geometries is not None:
            record["geometries"] = dict(geometries)
        self.records.setdefault(object_name, []).append(record)
        return record

    def _records(self, method, path, query, body):
        name = RECORDS.match(path).group(1)
        if method == "POST":
            if self.fail_on_record == name:
                return 400, {"detail": "Invalid option", "errors": [{"field": "uso", "message": "boom-record"}]}
            return 201, self.add_record(name, body["attributes"], body.get("geometries"))
        params = {k: v[0] for k, v in urllib.parse.parse_qs(query).items()}
        page = int(params.pop("page", 0))
        size = int(params.pop("size", 25))
        params.pop("sort", None)
        params.pop("dir", None)
        rows = [r for r in self.records.get(name, []) if all(str(r["attributes"].get(k)) == v for k, v in params.items())]
        total_pages = (len(rows) + size - 1) // size
        return 200, {
            "content": rows[page * size:(page + 1) * size],
            "page": page,
            "size": size,
            "totalElements": len(rows),
            "totalPages": total_pages,
        }

    def _update(self, path, body):
        """A record's PUT: Core replaces every field with what is sent."""
        name, record_id = RECORD.match(path).groups()
        if self.fail_on_update == name:
            return 400, {"detail": "Invalid option", "errors": [{"field": "tipo_via", "message": "boom-update"}]}
        if self.conflict_on_update == name:
            return 409, {"detail": "Un parámetro VIGENTE no se edita: anúlelo y registre el valor correcto"}
        record = next((r for r in self.records.get(name, []) if r["id"] == record_id), None)
        if record is None:
            return 404, {"detail": "not found"}
        record["attributes"] = dict(body["attributes"])
        return 200, record

    def _delete(self, path):
        """A record's DELETE: it leaves the records."""
        name, record_id = RECORD.match(path).groups()
        if self.fail_on_delete == name:
            return 409, {"detail": "In use", "errors": [{"field": "id", "message": "boom-delete"}]}
        records = self.records.get(name, [])
        if not any(r["id"] == record_id for r in records):
            return 404, {"detail": "not found"}
        self.records[name] = [r for r in records if r["id"] != record_id]
        return 204, None

    def _object(self, name):
        """An object as GET /api/objects lists it: indexes only when some are declared, appendOnly always."""
        rules = self.object_rules.get(name, {})
        obj = {"name": name, **self.object_meta.get(name, _meta(name)), "appendOnly": bool(rules.get("appendOnly"))}
        if rules.get("indexes"):
            obj["indexes"] = rules["indexes"]
        return obj

    def _update_object(self, path, body):
        """An object's PUT, as MetadataService.updateObject: label required and overwritten; pluralLabel left out or
        blank becomes the label, description left out goes null, enabled left out is true; a rule left out (or null)
        stays as it is."""
        name = OBJECT.match(path).group(1)
        if self.fail_object_put:
            return self.fail_object_put, {"message": "boom-object-put"}
        if name not in self.existing_objects:
            return 404, {"detail": "not found"}
        if not (body or {}).get("label"):
            return 400, {"detail": "label is required"}
        enabled = body.get("enabled")
        self.object_meta[name] = _meta(body["label"], body.get("pluralLabel"), body.get("description"),
                                       True if enabled is None else enabled)
        for key in ("indexes", "appendOnly"):
            if body.get(key) is not None:
                self.object_rules[name][key] = body[key]
        return 200, self._object(name)

    def _cuis(self, body):
        """srtm's new version of a codigo_infraccion: it closes the one in force (vigencia_hasta the day before,
        clave_vigente emptied) and adds this one. A repeated clave is a 409, one not after the vigente a 422."""
        if self.fail_on_record == "codigo_infraccion":
            return 400, {"detail": "Invalid value", "errors": [{"field": "porcentaje_uit", "message": "boom-cuis"}]}
        familia = body.get("familia") or "ADMINISTRATIVA"
        codigo = f"{familia}|{body['codigo']}"
        clave = f"{codigo}|{body['vigencia_desde']}"
        versiones = self.records.setdefault("codigo_infraccion", [])
        if any(r["attributes"].get("clave") == clave for r in versiones):
            return 409, {"detail": "Ya existe un registro con ese valor"}
        vigente = next((r for r in versiones if r["attributes"].get("clave_vigente") == codigo), None)
        desde = date.fromisoformat(body["vigencia_desde"])
        if vigente is not None:
            if date.fromisoformat(vigente["attributes"]["vigencia_desde"]) >= desde:
                return 422, {"detail": "vigencia_desde no es posterior a la de la vigente"}
            vigente["attributes"].update(vigencia_hasta=(desde - timedelta(days=1)).isoformat(), clave_vigente=None)
        nueva = self.add_record("codigo_infraccion", {**body, "familia": familia, "clave": clave, "clave_vigente": codigo})
        # the flat record of the new version, and the one it closed (srtm.sanciones.VersionCuisCreada)
        cerrada = {"id": vigente["id"], **vigente["attributes"]} if vigente is not None else None
        return 201, {"id": nueva["id"], **nueva["attributes"], "cerrada": cerrada}

    def _derogacion(self, body):
        """srtm's derogation of a code: its version in force rules until vigencia_hasta and clave_vigente is emptied.
        A code without versions is a 404, without one in force a 409, a day before it started a 422."""
        familia = body.get("familia") or "ADMINISTRATIVA"
        codigo = f"{familia}|{body['codigo']}"
        versiones = [r for r in self.records.get("codigo_infraccion", []) if r["attributes"].get("clave", "").startswith(codigo + "|")]
        if not versiones:
            return 404, {"detail": f"El código {body['codigo']} no está en el CUIS"}
        vigente = next((r for r in versiones if r["attributes"].get("clave_vigente") == codigo), None)
        if vigente is None:
            return 409, {"detail": "ya está cerrado"}
        if date.fromisoformat(body["vigencia_hasta"]) < date.fromisoformat(vigente["attributes"]["vigencia_desde"]):
            return 422, {"detail": "vigencia_hasta es anterior a vigencia_desde"}
        vigente["attributes"].update(vigencia_hasta=body["vigencia_hasta"], clave_vigente=None)
        return 200, {"id": vigente["id"], **vigente["attributes"]}

    def _roles(self, method, path, body):
        """Core's role routes, addressed by name: list, create, relabel and replace the permissions."""
        if path == "/api/roles" and method == "GET":
            return 200, list(self.roles.values())
        if path == "/api/roles" and method == "POST":
            name = body["name"]
            if name == self.fail_on_role:
                return 500, {"message": "boom-role"}
            if name in self.roles:
                return 409, {"detail": f"Role '{name}' already exists"}
            self.roles[name] = {"name": name, "label": body["label"], "permissions": []}
            return 201, self.roles[name]
        match = ROLE_PERMISSIONS.match(path) or ROLE.match(path)
        role = self.roles.get(match.group(1)) if match and method == "PUT" else None
        if role is None:
            return 404, {"detail": "no such role"}
        if ROLE_PERMISSIONS.match(path):
            role["permissions"] = [{**p, "allowed": p.get("allowed", True)} for p in body["permissions"]]
        else:
            role["label"] = body.get("label", role["label"])
        return 200, role

    def add_service_account(self, name, roles=(), enabled=True):
        account = {"id": str(uuid.uuid4()), "clientId": str(uuid.uuid4()), "name": name, "enabled": enabled,
                   "roles": list(roles)}
        self.service_accounts[account["id"]] = account
        return account

    def _service_accounts(self, method, path, body):
        """ServiceAccountController: list, create and rotate (the secret only in those two answers), update roles. A
        name core already has is a 409, an unknown role a 400."""
        if path == "/api/service-accounts" and method == "GET":
            return 200, list(self.service_accounts.values())
        if path == "/api/service-accounts" and method == "POST":
            if self.fail_on_service_account:
                return self.fail_on_service_account, {"detail": "boom-service-account"}
            name = body["name"].strip().lower()
            if any(a["name"] == name for a in self.service_accounts.values()):
                return 409, {"detail": f"Service account '{name}' already exists"}
            unknown = [r for r in body.get("roles", []) if r not in self.roles]
            if unknown:
                return 400, {"detail": f"Unknown role {unknown[0]}"}
            return 201, {**self.add_service_account(name, body.get("roles", [])), "clientSecret": "secreto-" + name}
        rotate = SERVICE_ACCOUNT_SECRET.match(path)
        if rotate and method == "POST":
            account = self.service_accounts.get(rotate.group(1))
            if account is None:
                return 404, {"detail": "no such service account"}
            self.rotations += 1
            return 200, {**account, "clientSecret": f"rotado-{self.rotations}-{account['name']}"}
        match = SERVICE_ACCOUNT.match(path)
        account = self.service_accounts.get(match.group(1)) if match and method == "PUT" else None
        if account is None:
            return 404, {"detail": "no such service account"}
        if body.get("roles") is not None:
            account["roles"] = list(body["roles"])
        if body.get("enabled") is not None:
            account["enabled"] = body["enabled"]
        return 200, account

    def _script(self, method, full_path, body):
        path, _, query = full_path.partition("?")
        if path == "/api/roles" or path.startswith("/api/roles/"):
            return self._roles(method, path, body)
        if path == "/api/service-accounts" or path.startswith("/api/service-accounts/"):
            return self._service_accounts(method, path, body)
        if path == CUIS and method == "POST":
            return self._cuis(body)
        if path == CUIS + "/derogacion" and method == "POST":
            return self._derogacion(body)
        sugerido = SUGERIDO.match(path)
        if sugerido and method == "GET":
            respuesta = self.sugeridos.get(sugerido.group(1))
            return (404, {"detail": "not found"}) if respuesta is None else (200, respuesta)
        recalcular = RECALCULAR.match(path)
        if recalcular and method == "POST":
            return 200, {"id": recalcular.group(1)}
        if path == "/api/organizations/current" and method == "GET":
            return 200, self.organization
        if path == "/api/auth/login" and method == "POST":
            if self.login_response is not None:
                return 200, self.login_response
            return 200, {"token": "t"}
        if RECORDS.match(path) and method in ("GET", "POST"):
            return self._records(method, path, query, body)
        if RECORD.match(path) and method == "PUT":
            return self._update(path, body)
        if RECORD.match(path) and method == "DELETE":
            return self._delete(path)
        if path == "/api/objects" and method == "GET":
            return 200, [self._object(n) for n in self.existing_objects]
        if path == "/api/objects" and method == "POST":
            name = body["name"]
            if self.fail_on_post_object and name == self.fail_on_post_object:
                return 500, {"message": "boom"}
            if name in self.existing_objects:
                return 409, {"message": "exists"}
            self.existing_objects.add(name)
            self.object_rules[name] = {k: body[k] for k in ("indexes", "appendOnly") if k in body}
            self.object_meta[name] = _meta(body["label"], body.get("pluralLabel"), body.get("description"))
            return 201, {"name": name}
        if OBJECT.match(path) and method == "PUT":
            return self._update_object(path, body)
        if path == "/api/relationships" and method == "GET":
            return 200, [{"name": n} for n in sorted(self.existing_relationships)]
        if path == "/api/relationships" and method == "POST":
            name = body["name"]
            if name in self.existing_relationships:
                return 409, {"message": "exists"}
            return 201, {"name": name}
        fields = FIELDS.match(path)
        if fields and method == "GET":
            return 200, self.existing_fields.get(fields.group(1), [])
        if fields and method == "POST":
            return 201, {"name": body["name"]}
        if path.startswith("/api/metadata/objects/") and method == "PUT":
            if self.fail_put:
                return self.fail_put_status, {"message": "boom-put"}
            return 200, {"required": True}
        if path.startswith("/api/metadata/objects/") and "/fields/" in path and method == "DELETE":
            return 204, None
        if path.startswith("/api/relationships/") and method == "DELETE":
            return 204, None
        if path.startswith("/api/objects/") and method == "DELETE":
            # Core keeps an append-only object's table until its rule is switched off (ADR-040)
            if self.object_rules.get(path.rsplit("/", 1)[1], {}).get("appendOnly"):
                return 409, {"detail": "append-only: switch appendOnly off before deleting it"}
            return 204, None
        return 500, {"message": "boom"}
