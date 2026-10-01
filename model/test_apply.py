"""End-to-end tests for apply.py's CLI against a fake Core.

Run: cd model && python3 -m unittest -v
"""
import io
import json
import os
import socket
import unittest
from contextlib import redirect_stdout, redirect_stderr

import apply
from fake_core import FakeCore

MODEL_PATH = os.path.join(os.path.dirname(__file__), "model.json")

OBJECTS = 21
RELATIONSHIPS = 10
OBJECT_ORDER = ["contribuyente", "predio", "declaracion_predial", "domicilio", "relacionado", "medio_contacto", "sustento",
                "ubigeo", "via", "unidad_urbana", "transferente", "nivel_construccion", "obra_complementaria", "otro_frente",
                "categoria_valor", "catastro_fiscal", "obra_categoria", "uso_predio", "parametro_tributario",
                "municipalidad", "emision_masiva"]
RELATIONSHIP_ORDER = ["declaracion_predial_contribuyente", "declaracion_predial_predio", "domicilio_contribuyente",
                      "relacionado_contribuyente", "medio_contacto_contribuyente", "sustento_contribuyente",
                      "transferente_declaracion", "nivel_construccion_declaracion", "obra_complementaria_declaracion",
                      "otro_frente_declaracion"]


def load_model():
    with open(MODEL_PATH, encoding="utf-8") as f:
        return json.load(f)


def core_fields(model, obj_name, drop=(), options=None):
    """What Core answers for an object that has model.json's fields, minus `drop`; `options` overrides enum options."""
    obj = next(o for o in model["objects"] if o["name"] == obj_name)
    fields = []
    for f in obj["fields"]:
        if f["name"] in drop:
            continue
        field = {"name": f["name"], "label": f["label"], "type": f["type"]}
        if f["type"] == "ENUM":
            field["enumOptions"] = (options or {}).get(f["name"], model["enums"][f["enum"]])
        fields.append(field)
    return fields


class ApplyCliTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)

    def run_cli(self, extra_args, core_url=None):
        args = [
            "--model", MODEL_PATH,
            "--core", core_url or self.core.base_url,
            "--email", "admin@wasichai.local",
            "--password", "admin",
        ] + extra_args
        out = io.StringIO()
        err = io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = apply.main(args)
        return code, out.getvalue(), err.getvalue()


class DryRunTests(ApplyCliTestCase):
    def test_dry_run_makes_no_requests_and_prints_headers(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0)
        self.assertEqual(self.core.requests, [])
        self.assertEqual(out.count("# POST /api/objects"), OBJECTS)
        self.assertEqual(out.count("# POST /api/relationships"), RELATIONSHIPS)


class HappyPathTests(ApplyCliTestCase):
    def test_creates_everything_in_order(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)

        object_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(object_posts, OBJECT_ORDER)

        rel_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/relationships" and r[0] == "POST"]
        self.assertEqual(rel_posts, RELATIONSHIP_ORDER)

        # every relation is required: one PUT each, right after its POST
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT"]
        self.assertEqual(puts, [
            ("/api/metadata/objects/declaracion_predial/fields/contribuyente", {"required": True}),
            ("/api/metadata/objects/declaracion_predial/fields/predio", {"required": True}),
            ("/api/metadata/objects/domicilio/fields/contribuyente", {"required": True}),
            ("/api/metadata/objects/relacionado/fields/contribuyente", {"required": True}),
            ("/api/metadata/objects/medio_contacto/fields/contribuyente", {"required": True}),
            ("/api/metadata/objects/sustento/fields/contribuyente", {"required": True}),
            ("/api/metadata/objects/transferente/fields/declaracion", {"required": True}),
            ("/api/metadata/objects/nivel_construccion/fields/declaracion", {"required": True}),
            ("/api/metadata/objects/obra_complementaria/fields/declaracion", {"required": True}),
            ("/api/metadata/objects/otro_frente/fields/declaracion", {"required": True}),
        ])

        for method, path, auth, body in self.core.requests:
            if path == "/api/auth/login":
                self.assertIsNone(auth)
            else:
                self.assertEqual(auth, "Bearer t")

        self.assertIn("done: 31 created, 0 updated, 0 skipped", out)


class IdempotencyTests(ApplyCliTestCase):
    def setUp(self):
        model = load_model()
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields={o["name"]: core_fields(model, o["name"]) for o in model["objects"]},
        )
        self.addCleanup(self.core.stop)

    def test_everything_skips(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        object_posts = [r for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(object_posts, [])
        self.assertEqual([r for r in self.core.requests if r[0] == "POST" and "/fields" in r[1]], [])
        self.assertIn("done: 0 created, 0 updated, 31 skipped", out)


class SyncTests(ApplyCliTestCase):
    """contribuyente as the first model left it: its new fields and one new enum option are added, nothing else."""

    NEW_FIELDS = ["codigo", "numero_declaracion", "fecha_registro", "motivo", "medio_determinacion", "medio_presentacion",
                  "modificacion_oficio", "fecha_presentacion", "tipo_contribuyente", "codigo_anterior", "fuente_informacion",
                  "fecha_nacimiento", "fecha_fallecimiento", "estado_civil", "sexo", "observacion"]

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        fields["contribuyente"] = core_fields(
            model, "contribuyente", drop=self.NEW_FIELDS,
            options={"tipo_documento": ["SIN DOCUMENTO", "DNI", "CARNET DE EXTRANJERIA", "RUC", "SUCESION"]},
        )
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)

    def test_adds_missing_fields_and_enum_options_only(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        added = [r[3]["name"] for r in self.core.requests
                 if r[0] == "POST" and r[1] == "/api/metadata/objects/contribuyente/fields"]
        self.assertEqual(added, self.NEW_FIELDS)
        motivo = next(r[3] for r in self.core.requests if r[0] == "POST" and r[3] and r[3].get("name") == "motivo")
        self.assertEqual(motivo["enumOptions"], ["INSCRIPCION", "ACTUALIZACION", "DESCARGO"])
        self.assertFalse(motivo["required"])
        option_puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "enumOptions" in (r[3] or {})]
        self.assertEqual(option_puts, [(
            "/api/metadata/objects/contribuyente/fields/tipo_documento",
            {"enumOptions": ["SIN DOCUMENTO", "DNI", "CARNET DE EXTRANJERIA", "RUC", "PASAPORTE", "PTP-CPP", "CI", "OTROS"]},
        )])
        self.assertIn("done: 16 created, 1 updated, 30 skipped", out)


class DropOptionsTests(ApplyCliTestCase):
    """tipo_obra as it was before the annex III groups: the options model.json dropped go, unless a record uses one."""

    OLD_TIPO_OBRA = ["MUROS PERIMETRICOS O CERCOS", "PORTONES Y PUERTAS", "TANQUES ELEVADOS", "CISTERNAS", "PISCINAS",
                     "LOSAS DEPORTIVAS", "PISOS DE CONCRETO", "OTROS"]

    def setUp(self):
        model = load_model()
        self.tipo_obra = model["enums"]["tipo_obra"]
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        for name in ("obra_complementaria", "obra_categoria"):
            fields[name] = core_fields(model, name, options={"tipo_obra": self.OLD_TIPO_OBRA})
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)
        self.core.add_record("obra_complementaria", {"tipo_obra": "OTROS", "cantidad": 1})

    def test_drops_the_unused_options_and_keeps_the_used_ones(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        option_puts = dict((r[1].rsplit("/", 3)[1], r[3]["enumOptions"]) for r in self.core.requests
                           if r[0] == "PUT" and "enumOptions" in (r[3] or {}))
        kept = ["MUROS PERIMETRICOS O CERCOS", "PORTONES Y PUERTAS", "TANQUES ELEVADOS"]
        new = [o for o in self.tipo_obra if o not in kept]
        self.assertEqual(option_puts, {
            "obra_categoria": kept + new,
            # a declaración already has an OTROS obra: it stays storable, after model.json's options
            "obra_complementaria": kept + new + ["OTROS"],
        })
        self.assertIn("; -CISTERNAS, PISCINAS, LOSAS DEPORTIVAS, PISOS DE CONCRETO, OTROS)", out)
        self.assertIn("; -CISTERNAS, PISCINAS, LOSAS DEPORTIVAS, PISOS DE CONCRETO)", out)
        self.assertIn("keep   option obra_complementaria.tipo_obra OTROS: 1 record uses it", out)
        self.assertIn("done: 0 created, 2 updated, 29 skipped", out)

    def count_answers(self, status, payload):
        """The records GET, the count asked before dropping an option, answers this."""
        records = self.core._records
        self.core._records = lambda method, *args: (status, payload) if method == "GET" else records(method, *args)

    def assert_drops_nothing(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("error GET /api/objects/obra_complementaria/records", err)
        self.assertEqual([r for r in self.core.requests if r[0] == "PUT" and "enumOptions" in (r[3] or {})], [])

    def test_a_count_core_refuses_drops_nothing(self):
        self.count_answers(500, {"message": "boom"})
        self.assert_drops_nothing()

    def test_a_404_is_not_a_zero(self):
        self.count_answers(404, {"detail": "not found"})
        self.assert_drops_nothing()

    def test_an_answer_without_its_count_is_not_a_zero(self):
        self.count_answers(200, {"content": []})
        self.assert_drops_nothing()


class RelaxRequiredTests(ApplyCliTestCase):
    """A field model.json no longer requires is made optional (SIN DOCUMENTO has no number); nothing is made required."""

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        for f in fields["contribuyente"]:
            # Core as the first model left it: numero_documento required. tipo_persona is required in both
            f["required"] = f["name"] in ("numero_documento", "tipo_persona")
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)

    def test_relaxes_required_only(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "/api/metadata/objects/contribuyente/" in r[1]]
        self.assertEqual(puts, [("/api/metadata/objects/contribuyente/fields/numero_documento", {"required": False})])
        self.assertIn("update field contribuyente.numero_documento (optional)", out)
        self.assertIn("done: 0 created, 1 updated, 30 skipped", out)


class RelacionadoTransferenteSyncTests(ApplyCliTestCase):
    """relacionado and transferente before razón social and código: both fields are added, and nombres (required
    then, optional now that a company has none) is relaxed. a field model.json requires is never tightened."""

    NEW_FIELDS = ["codigo", "razon_social"]

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        for name in ("relacionado", "transferente"):
            fields[name] = core_fields(model, name, drop=self.NEW_FIELDS)
            for field in fields[name]:
                field["required"] = field["name"] == "nombres"
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)

    def test_adds_razon_social_and_codigo_and_relaxes_nombres(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        for name in ("relacionado", "transferente"):
            added = [r[3]["name"] for r in self.core.requests
                     if r[0] == "POST" and r[1] == f"/api/metadata/objects/{name}/fields"]
            self.assertEqual(sorted(added), self.NEW_FIELDS)
        # the relations' own required PUT aside (HappyPathTests)
        relations = {f"/api/metadata/objects/{r['source']}/fields/{r['fieldName']}" for r in load_model()["relationships"]}
        field_puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "/fields/" in r[1] and r[1] not in relations]
        self.assertEqual(field_puts, [
            ("/api/metadata/objects/relacionado/fields/nombres", {"required": False}),
            ("/api/metadata/objects/transferente/fields/nombres", {"required": False}),
        ])
        self.assertIn("update field relacionado.nombres (optional)", out)
        self.assertIn("done: 4 created, 2 updated, 29 skipped", out)


class RelabelTests(ApplyCliTestCase):
    """A field model.json labels differently gets model.json's label: predio.tipo_predio as "Condición del predio"."""

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        next(f for f in fields["predio"] if f["name"] == "tipo_predio")["label"] = "Condición del predio"
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)

    def test_relabels_only_what_differs(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "label" in (r[3] or {})]
        self.assertEqual(puts, [("/api/metadata/objects/predio/fields/tipo_predio", {"label": "Tipo de predio"})])
        self.assertIn("update field predio.tipo_predio (label)", out)
        self.assertIn("done: 0 created, 1 updated, 30 skipped", out)


class FailureStopsTests(ApplyCliTestCase):
    def setUp(self):
        self.core = FakeCore(fail_on_post_object="predio")
        self.addCleanup(self.core.stop)

    def test_500_on_second_object_aborts(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("boom", err)
        object_posts = [r for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(len(object_posts), 2)
        self.assertEqual([r for r in self.core.requests if r[1] == "/api/relationships"], [])


class RequiredPutFailureTests(ApplyCliTestCase):
    """The required PUT tolerates nothing, not even 409."""

    def setUp(self):
        self.core = FakeCore(fail_put=True, fail_put_status=409)
        self.addCleanup(self.core.stop)

    def test_409_on_required_put_aborts(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("/api/metadata/objects/declaracion_predial/fields/contribuyente", err)
        rel_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/relationships" and r[0] == "POST"]
        self.assertEqual(rel_posts, ["declaracion_predial_contribuyente"])


class ConnectionFailureTests(unittest.TestCase):
    def test_closed_port_is_fatal_with_no_traceback(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()

        out = io.StringIO()
        err = io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = apply.main(["--model", MODEL_PATH, "--core", f"http://127.0.0.1:{port}"])
        self.assertEqual(code, 1)
        self.assertIn("connection failed", err.getvalue())
        self.assertNotIn("Traceback", err.getvalue())


class LoginMissingTokenTests(ApplyCliTestCase):
    def setUp(self):
        self.core = FakeCore(login_response={})
        self.addCleanup(self.core.stop)

    def test_login_without_token_is_fatal(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("error POST /api/auth/login -> no token in response", err)
        self.assertEqual(len(self.core.requests), 1)


class DropTests(ApplyCliTestCase):
    def test_drop_deletes_relationships_then_objects_in_reverse(self):
        code, out, err = self.run_cli(["--drop"])
        self.assertEqual(code, 0, msg=err)
        deletes = [r[1] for r in self.core.requests if r[0] == "DELETE"]
        self.assertEqual(deletes, [f"/api/relationships/{n}" for n in reversed(RELATIONSHIP_ORDER)]
                         + [f"/api/objects/{n}" for n in reversed(OBJECT_ORDER)])
        self.assertIn("done: 31 deleted, 0 skipped", out)

    def test_drop_dry_run_makes_no_requests(self):
        code, out, err = self.run_cli(["--drop", "--dry-run"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])
        self.assertEqual(out.count("# DELETE "), OBJECTS + RELATIONSHIPS)


class ValidateOnlyTests(ApplyCliTestCase):
    def test_validate_only_makes_no_requests(self):
        code, out, err = self.run_cli(["--validate-only"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])


if __name__ == "__main__":
    unittest.main()
