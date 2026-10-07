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
from fake_core import OBJECT, FakeCore

MODEL_PATH = os.path.join(os.path.dirname(__file__), "model.json")

OBJECTS = 40
RELATIONSHIPS = 42
OBJECT_ORDER = ["contribuyente", "predio", "declaracion_predial", "domicilio", "relacionado", "medio_contacto", "sustento",
                "ubigeo", "via", "unidad_urbana", "transferente", "nivel_construccion", "obra_complementaria", "otro_frente",
                "categoria_valor", "catastro_fiscal", "obra_categoria", "uso_predio", "parametro_tributario",
                "municipalidad", "emision_masiva", "emision_lote", "ordenanza_arbitrio", "servicio_arbitrio",
                "inafectacion_arbitrio", "cuota_arbitrio", "anulacion_cuota_arbitrio", "determinacion_arbitrio_masiva",
                "determinacion_arbitrio_lote", "codigo_infraccion", "notificacion_administrativa", "subsanacion_notificacion",
                "papeleta", "anulacion_papeleta", "descargo_papeleta", "resolucion_gerencia", "notificacion_resolucion",
                "anuncio", "movimiento_anuncio", "zona_arbitrio_ubicacion"]
RELATIONSHIP_ORDER = ["declaracion_predial_contribuyente", "declaracion_predial_predio", "domicilio_contribuyente",
                      "relacionado_contribuyente", "medio_contacto_contribuyente", "sustento_contribuyente",
                      "transferente_declaracion", "nivel_construccion_declaracion", "obra_complementaria_declaracion",
                      "otro_frente_declaracion", "emision_lote_emision", "servicio_arbitrio_ordenanza",
                      "inafectacion_arbitrio_predio", "inafectacion_arbitrio_servicio", "cuota_arbitrio_predio",
                      "cuota_arbitrio_contribuyente", "cuota_arbitrio_servicio", "cuota_arbitrio_parametro",
                      "determinacion_arbitrio_lote_masiva", "anulacion_cuota_arbitrio_cuota", "anulacion_cuota_arbitrio_predio",
                      "notif_adm_contribuyente", "notif_adm_predio", "subsanacion_notificacion_rel", "papeleta_codigo",
                      "papeleta_uit", "papeleta_obligado", "papeleta_contribuyente", "papeleta_predio",
                      "papeleta_notificacion_previa", "anulacion_papeleta_rel", "descargo_papeleta_rel", "descargo_plazo",
                      "resolucion_papeleta", "resolucion_descargo", "resolucion_plazo", "notif_resolucion_rel",
                      "notif_resolucion_plazo", "anuncio_contribuyente", "anuncio_predio", "movimiento_anuncio_rel",
                      "movimiento_parametro"]


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


def core_rules(model):
    """What Core answers for objects that already have model.json's object-level rules (indexes, appendOnly)."""
    return {o["name"]: {k: o[k] for k in apply.RULE_KEYS if k in o} for o in model["objects"]}


def object_puts(core):
    """The object-level PUTs (rules) Core got, in order: path and body."""
    return [(r[1], r[3]) for r in core.requests if r[0] == "PUT" and OBJECT.match(r[1])]


def core_meta(model):
    """An object's own metadata as Core has it once model.json created it (POST /api/objects)."""
    return {o["name"]: {"label": o["label"], "pluralLabel": o["pluralLabel"], "description": o.get("description", ""),
                        "enabled": True} for o in model["objects"]}


def rule_put(name, **rules):
    """A rule PUT of an object model.json created: its label, plural, description and enabled back as Core has them."""
    return f"/api/objects/{name}", {**core_meta(load_model())[name], **rules}


# the objects model.json makes append-only, and the PUT apply sends Core for each
APPEND_ONLY = ["cuota_arbitrio", "anulacion_cuota_arbitrio"]
CUOTA_INDEXES = [["anio", "predio"], ["anio", "contribuyente"]]
APPEND_ONLY_PUTS = [rule_put(n, appendOnly=True, **({"indexes": CUOTA_INDEXES} if n == "cuota_arbitrio" else {}))
                    for n in APPEND_ONLY]


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
        self.assertEqual(out.count("# PUT /api/objects/"), len(APPEND_ONLY))


class HappyPathTests(ApplyCliTestCase):
    def test_creates_everything_in_order(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)

        object_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(object_posts, OBJECT_ORDER)

        rel_posts = [r[3]["name"] for r in self.core.requests if r[1] == "/api/relationships" and r[0] == "POST"]
        self.assertEqual(rel_posts, RELATIONSHIP_ORDER)

        # a required relation gets one PUT, right after its POST; an optional one (test_model.OPCIONALES) none
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
            ("/api/metadata/objects/emision_lote/fields/emision", {"required": True}),
            ("/api/metadata/objects/servicio_arbitrio/fields/ordenanza", {"required": True}),
            ("/api/metadata/objects/inafectacion_arbitrio/fields/predio", {"required": True}),
            ("/api/metadata/objects/inafectacion_arbitrio/fields/servicio", {"required": True}),
            ("/api/metadata/objects/cuota_arbitrio/fields/predio", {"required": True}),
            ("/api/metadata/objects/cuota_arbitrio/fields/contribuyente", {"required": True}),
            ("/api/metadata/objects/cuota_arbitrio/fields/servicio", {"required": True}),
            ("/api/metadata/objects/cuota_arbitrio/fields/parametro", {"required": True}),
            ("/api/metadata/objects/determinacion_arbitrio_lote/fields/determinacion", {"required": True}),
            ("/api/metadata/objects/anulacion_cuota_arbitrio/fields/cuota", {"required": True}),
            ("/api/metadata/objects/anulacion_cuota_arbitrio/fields/predio", {"required": True}),
            ("/api/metadata/objects/subsanacion_notificacion/fields/notificacion", {"required": True}),
            ("/api/metadata/objects/papeleta/fields/codigo_infraccion", {"required": True}),
            ("/api/metadata/objects/papeleta/fields/uit", {"required": True}),
            ("/api/metadata/objects/papeleta/fields/obligado", {"required": True}),
            ("/api/metadata/objects/anulacion_papeleta/fields/papeleta", {"required": True}),
            ("/api/metadata/objects/descargo_papeleta/fields/papeleta", {"required": True}),
            ("/api/metadata/objects/descargo_papeleta/fields/plazo", {"required": True}),
            ("/api/metadata/objects/resolucion_gerencia/fields/papeleta", {"required": True}),
            ("/api/metadata/objects/resolucion_gerencia/fields/plazo", {"required": True}),
            ("/api/metadata/objects/notificacion_resolucion/fields/resolucion", {"required": True}),
            ("/api/metadata/objects/anuncio/fields/contribuyente", {"required": True}),
            ("/api/metadata/objects/movimiento_anuncio/fields/anuncio", {"required": True}),
        ] + APPEND_ONLY_PUTS)
        # the rules go after the last relationship: an index can name a relation field
        last_relationship = max(i for i, r in enumerate(self.core.requests) if r[1] == "/api/relationships")
        first_rule = min(i for i, r in enumerate(self.core.requests) if (r[1], r[3]) in APPEND_ONLY_PUTS)
        self.assertLess(last_relationship, first_rule)

        for method, path, auth, body in self.core.requests:
            if path == "/api/auth/login":
                self.assertIsNone(auth)
            else:
                self.assertEqual(auth, "Bearer t")

        self.assertIn("update object cuota_arbitrio (appendOnly, indexes)", out)
        self.assertIn("done: 82 created, 2 updated, 0 skipped", out)


class IdempotencyTests(ApplyCliTestCase):
    def setUp(self):
        model = load_model()
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            object_rules=core_rules(model),
            object_meta=core_meta(model),
            existing_fields={o["name"]: core_fields(model, o["name"]) for o in model["objects"]},
        )
        self.addCleanup(self.core.stop)

    def test_everything_skips(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        object_posts = [r for r in self.core.requests if r[1] == "/api/objects" and r[0] == "POST"]
        self.assertEqual(object_posts, [])
        self.assertEqual([r for r in self.core.requests if r[0] == "POST" and "/fields" in r[1]], [])
        self.assertEqual(object_puts(self.core), [])
        self.assertIn("done: 0 created, 0 updated, 82 skipped", out)


class ObjectRulesTests(ApplyCliTestCase):
    """Core with the model but not its object-level rules (the dev db, created before 0.3.2): apply adds them with a
    PUT of the object, and only what differs. a second run sends none."""

    def setUp(self):
        model = load_model()
        self.rules = {}
        self.core_with(model)

    def core_with(self, model, **kwargs):
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields={o["name"]: core_fields(model, o["name"]) for o in model["objects"]},
            object_rules=self.rules,
            object_meta=core_meta(model),
            **kwargs,
        )
        self.addCleanup(self.core.stop)

    def test_adds_append_only_and_a_second_run_sends_nothing(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(object_puts(self.core), APPEND_ONLY_PUTS)
        self.assertIn("update object cuota_arbitrio (appendOnly, indexes)", out)
        self.assertIn("update object anulacion_cuota_arbitrio (appendOnly)", out)
        self.assertIn("done: 0 created, 2 updated, 81 skipped", out)
        self.assertEqual({n: r for n, r in self.core.object_rules.items() if r.get("appendOnly")},
                         {n: {"appendOnly": True, **({"indexes": CUOTA_INDEXES} if n == "cuota_arbitrio" else {})}
                          for n in APPEND_ONLY})

        self.core.requests.clear()
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(object_puts(self.core), [])
        self.assertIn("done: 0 created, 0 updated, 81 skipped", out)

    def test_indexes_core_has_and_the_model_lacks_are_dropped(self):
        # model.json is the source of the indexes: one only Core has goes
        self.core.object_rules["predio"]["indexes"] = [["codigo"]]
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertIn(rule_put("predio", indexes=[]), object_puts(self.core))
        self.assertIn("update object predio (indexes)", out)

    def test_append_only_core_has_and_the_model_lacks_is_switched_off(self):
        self.core.object_rules["predio"]["appendOnly"] = True
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertIn(rule_put("predio", appendOnly=False), object_puts(self.core))

    def test_a_rule_put_keeps_what_an_admin_set_on_the_object(self):
        # Core's PUT resets a description, plural or enabled left out: apply sends them back as Core has them
        admin = {"label": "Cuota (admin)", "pluralLabel": "Cuotas (admin)", "description": "Del admin", "enabled": False}
        self.core.object_meta["cuota_arbitrio"] = dict(admin)
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertIn(("/api/objects/cuota_arbitrio", {**admin, "appendOnly": True, "indexes": CUOTA_INDEXES}), object_puts(self.core))
        self.assertEqual(self.core.object_meta["cuota_arbitrio"], admin)
        self.assertTrue(self.core.object_rules["cuota_arbitrio"]["appendOnly"])
        # and the other objects keep model.json's own
        self.assertEqual(self.core.object_meta["anulacion_cuota_arbitrio"], core_meta(load_model())["anulacion_cuota_arbitrio"])

    def test_a_refused_put_is_fatal(self):
        self.core_with(load_model(), fail_object_put=409)
        code, out, err = self.run_cli([])
        self.assertEqual(code, 1)
        self.assertIn("error PUT /api/objects/cuota_arbitrio -> 409", err)
        self.assertEqual(len(object_puts(self.core)), 1)
        self.assertNotIn("done:", out)

    def test_dry_run_writes_nothing(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])
        # what model.json declares, not a diff against Core: the dry run asks Core nothing
        self.assertIn("# PUT /api/objects/cuota_arbitrio (plus label, pluralLabel, description and enabled as Core has them)"
                      '\n{\n  "indexes": [\n    [\n      "anio",\n      "predio"\n    ],\n    [\n      "anio",\n      "contribuyente"\n    ]\n  ],\n  "appendOnly": true\n}', out)


def core_fields_with_relations(model, obj_name):
    """What Core answers for an object model.json created whole: its fields with required, and the field of each
    relationship it is the source of (required as model.json marks it)."""
    obj = next(o for o in model["objects"] if o["name"] == obj_name)
    required = {f["name"]: f.get("required", False) for f in obj["fields"]}
    fields = [{**f, "required": required[f["name"]]} for f in core_fields(model, obj_name)]
    return fields + [
        {"name": r["fieldName"], "label": r["label"], "type": "RELATION", "required": r.get("required", False)}
        for r in model["relationships"] if r["source"] == obj_name]


def writes(core):
    """What Core got that is not a read: the login aside."""
    return [r for r in core.requests if r[0] != "GET" and r[1] != "/api/auth/login"]


def would_lines(out):
    return [line for line in out.splitlines() if line.startswith("would ")]


class DiffTests(ApplyCliTestCase):
    """--diff reads the real Core and writes nothing: what the real run would send is printed, "would <METHOD> <path>
    <summary>". the dev procedure is --diff, an OK, and then the real run."""

    def core_with(self, rules):
        model = load_model()
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields={o["name"]: core_fields_with_relations(model, o["name"]) for o in model["objects"]},
            object_rules=rules,
            object_meta=core_meta(model),
        )
        self.addCleanup(self.core.stop)

    def test_a_core_without_the_rules_would_get_exactly_their_two_puts(self):
        self.core_with({})
        code, out, err = self.run_cli(["--diff"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(writes(self.core), [])
        self.assertEqual(would_lines(out), [
            'would PUT /api/objects/cuota_arbitrio {"indexes": [["anio", "predio"], ["anio", "contribuyente"]], '
            '"appendOnly": true}',
            'would PUT /api/objects/anulacion_cuota_arbitrio {"appendOnly": true}',
        ])
        self.assertIn("diff: 2 writes pending, none sent", out)
        # and Core still lacks them
        self.assertEqual({n: r for n, r in self.core.object_rules.items() if r}, {})

    def test_an_up_to_date_core_has_nothing_pending(self):
        self.core_with(core_rules(load_model()))
        code, out, err = self.run_cli(["--diff"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(writes(self.core), [])
        self.assertEqual(would_lines(out), [])
        self.assertIn("diff: nothing pending", out)

    def test_an_empty_core_would_get_the_whole_model(self):
        code, out, err = self.run_cli(["--diff"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(writes(self.core), [])
        lines = would_lines(out)
        self.assertEqual([line for line in lines if line.startswith("would POST /api/objects ")],
                         [f"would POST /api/objects {n}" for n in OBJECT_ORDER])
        self.assertEqual([line for line in lines if line.startswith("would POST /api/relationships ")],
                         [f"would POST /api/relationships {n}" for n in RELATIONSHIP_ORDER])
        self.assertEqual(len([line for line in lines if line.startswith("would PUT /api/objects/")]), len(APPEND_ONLY))

    def test_a_drop_diff_lists_what_it_would_delete(self):
        self.core_with(core_rules(load_model()))
        code, out, err = self.run_cli(["--drop", "--diff"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(writes(self.core), [])
        lines = would_lines(out)
        self.assertEqual(lines[:2], ['would PUT /api/objects/anulacion_cuota_arbitrio {"appendOnly": false}',
                                     'would PUT /api/objects/cuota_arbitrio {"indexes": [], "appendOnly": false}'])
        self.assertEqual(len([line for line in lines if line.startswith("would DELETE ")]), OBJECTS + RELATIONSHIPS)
        self.assertIn(f"diff: {OBJECTS + RELATIONSHIPS + 2} writes pending, none sent", out)

    def test_diff_and_dry_run_do_not_go_together(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.run_cli(["--diff", "--dry-run"])


class RuleChangesTests(unittest.TestCase):
    """rule_changes compares as Core stores: names trimmed and lower-cased in their order, appendOnly left out is false."""

    def test_the_same_rules_written_otherwise_are_no_change(self):
        self.assertEqual(apply.rule_changes({"indexes": [[" Anio", "PREDIO "]]}, {"indexes": [["anio", "predio"]]}), {})
        self.assertEqual(apply.rule_changes({}, {"appendOnly": False}), {})
        self.assertEqual(apply.rule_changes({"appendOnly": False}, {}), {})
        self.assertEqual(apply.rule_changes({"indexes": []}, {}), {})

    def test_only_what_differs_is_sent(self):
        self.assertEqual(apply.rule_changes({"appendOnly": True}, {}), {"appendOnly": True})
        self.assertEqual(apply.rule_changes({"indexes": [["predio", "anio"]], "appendOnly": True},
                                            {"indexes": [["anio", "predio"]], "appendOnly": True}),
                         {"indexes": [["predio", "anio"]]})
        self.assertEqual(apply.rule_changes({}, {"indexes": [["anio"]]}), {"indexes": []})


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
            object_rules=core_rules(model),
            object_meta=core_meta(model),
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
        self.assertIn("done: 16 created, 1 updated, 81 skipped", out)


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
            object_rules=core_rules(model),
            object_meta=core_meta(model),
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
        self.assertIn("done: 0 created, 2 updated, 80 skipped", out)

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
            object_rules=core_rules(model),
            object_meta=core_meta(model),
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)

    def test_relaxes_required_only(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "/api/metadata/objects/contribuyente/" in r[1]]
        self.assertEqual(puts, [("/api/metadata/objects/contribuyente/fields/numero_documento", {"required": False})])
        self.assertIn("update field contribuyente.numero_documento (optional)", out)
        self.assertIn("done: 0 created, 1 updated, 81 skipped", out)


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
            object_rules=core_rules(model),
            object_meta=core_meta(model),
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
        self.assertIn("done: 4 created, 2 updated, 80 skipped", out)


class RelabelTests(ApplyCliTestCase):
    """A field model.json labels differently gets model.json's label: predio.tipo_predio as "Condición del predio"."""

    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        next(f for f in fields["predio"] if f["name"] == "tipo_predio")["label"] = "Condición del predio"
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            object_rules=core_rules(model),
            object_meta=core_meta(model),
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)

    def test_relabels_only_what_differs(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "label" in (r[3] or {})]
        self.assertEqual(puts, [("/api/metadata/objects/predio/fields/tipo_predio", {"label": "Tipo de predio"})])
        self.assertIn("update field predio.tipo_predio (label)", out)
        self.assertIn("done: 0 created, 1 updated, 81 skipped", out)


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
        self.assertIn("done: 82 deleted, 0 skipped", out)

    def test_drop_switches_append_only_off_before_deleting(self):
        # Core refuses to drop an append-only object, or a relationship that holds one's values, until the rule is off
        model = load_model()
        self.core = FakeCore(existing_objects=[o["name"] for o in model["objects"]], object_rules=core_rules(model),
                             object_meta=core_meta(model))
        self.addCleanup(self.core.stop)
        code, out, err = self.run_cli(["--drop"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(object_puts(self.core), [rule_put(n, appendOnly=False, **({"indexes": []} if n == "cuota_arbitrio" else {}))
                          for n in reversed(APPEND_ONLY)])
        first_delete = min(i for i, r in enumerate(self.core.requests) if r[0] == "DELETE")
        last_put = max(i for i, r in enumerate(self.core.requests) if r[0] == "PUT")
        self.assertLess(last_put, first_delete)
        self.assertIn("update object cuota_arbitrio (appendOnly, indexes)", out)
        self.assertIn("done: 81 deleted, 0 skipped", out)

    def test_drop_dry_run_makes_no_requests(self):
        code, out, err = self.run_cli(["--drop", "--dry-run"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])
        self.assertEqual(out.count("# DELETE "), OBJECTS + RELATIONSHIPS)
        self.assertEqual(out.count("# PUT /api/objects/"), len(APPEND_ONLY))


class ValidateOnlyTests(ApplyCliTestCase):
    def test_validate_only_makes_no_requests(self):
        code, out, err = self.run_cli(["--validate-only"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])


if __name__ == "__main__":
    unittest.main()
