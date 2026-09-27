"""Tests for model.json and apply.py's pure parts (validate, payload builders).

Run: cd model && python3 -m unittest -v
"""
import copy
import json
import os
import unittest

from apply import enum_option_valid, object_payload, relationship_payload, validate

MODEL_PATH = os.path.join(os.path.dirname(__file__), "model.json")


def load_model():
    with open(MODEL_PATH, encoding="utf-8") as f:
        return json.load(f)


class ShippedModelTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def test_validates_clean(self):
        self.assertEqual(validate(self.model), [])

    def test_objects_in_topological_order(self):
        names = [o["name"] for o in self.model["objects"]]
        self.assertEqual(names[:3], ["contribuyente", "predio", "declaracion_predial"])
        self.assertEqual(len(names), 15)
        self.assertEqual(len(self.model["relationships"]), 10)

    def test_new_contribuyente_fields_are_optional(self):
        # 11 840 contribuyentes came from the padron without them: a required field would be refused by Core
        first = {"tipo_persona", "tipo_documento", "numero_documento", "nombre_completo"}
        contribuyente = next(o for o in self.model["objects"] if o["name"] == "contribuyente")
        required = {f["name"] for f in contribuyente["fields"] if f.get("required")}
        self.assertEqual(required, first)

    def test_every_enum_option_passes_core_regex(self):
        for name, options in self.model["enums"].items():
            for opt in options:
                self.assertTrue(enum_option_valid(opt), f"{name}: {opt}")

    def test_every_relation_is_required(self):
        self.assertTrue(all(r["required"] for r in self.model["relationships"]))

    def test_business_keys_are_unique(self):
        fields = {o["name"]: {f["name"]: f for f in o["fields"]} for o in self.model["objects"]}
        self.assertTrue(fields["contribuyente"]["numero_documento"]["unique"])
        self.assertTrue(fields["predio"]["codigo"]["unique"])

    def test_no_geometry(self):
        types = {f["type"] for o in self.model["objects"] for f in o["fields"]}
        self.assertNotIn("GEOMETRY", types)


class PayloadTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def test_enum_field_carries_its_options(self):
        obj = next(o for o in self.model["objects"] if o["name"] == "contribuyente")
        payload = object_payload(self.model, obj)
        tipo = next(f for f in payload["fields"] if f["name"] == "tipo_documento")
        self.assertEqual(tipo["enumOptions"], ["SIN DOCUMENTO", "DNI", "CARNET DE EXTRANJERIA", "RUC", "SUCESION", "PASAPORTE"])
        self.assertTrue(tipo["required"])
        self.assertNotIn("enum", tipo)
        self.assertNotIn("source", tipo)

    def test_relationship_payload_is_many_to_one(self):
        rel = self.model["relationships"][0]
        self.assertEqual(relationship_payload(rel), {
            "name": "declaracion_predial_contribuyente",
            "label": "Contribuyente",
            "inverseLabel": "Declaraciones prediales",
            "type": "MANY_TO_ONE",
            "source": "declaracion_predial",
            "target": "contribuyente",
            "fieldName": "contribuyente",
        })


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.model = load_model()

    def mutate(self, fn):
        model = copy.deepcopy(self.model)
        fn(model)
        return validate(model)

    def test_option_with_comma_is_refused(self):
        errors = self.mutate(lambda m: m["enums"]["clasificacion"].append("TIENDAS,DEPOSITOS"))
        self.assertTrue(any("TIENDAS,DEPOSITOS" in e for e in errors))

    def test_option_over_64_chars_is_refused(self):
        errors = self.mutate(lambda m: m["enums"]["uso"].append("X" * 65))
        self.assertTrue(any("invalid characters or length" in e for e in errors))

    def test_geometry_is_refused(self):
        errors = self.mutate(lambda m: m["objects"][1]["fields"].append({"name": "geom", "label": "G", "type": "GEOMETRY"}))
        self.assertTrue(any("unknown type 'GEOMETRY'" in e for e in errors))

    def test_target_after_source_is_refused(self):
        errors = self.mutate(lambda m: m["objects"].reverse())
        self.assertTrue(any("must appear before source" in e for e in errors))

    def test_reserved_and_keyword_names_are_refused(self):
        errors = self.mutate(lambda m: m["objects"][0]["fields"].extend([
            {"name": "version", "label": "V", "type": "TEXT"},
            {"name": "order", "label": "O", "type": "TEXT"},
        ]))
        self.assertTrue(any("reserved by Core" in e for e in errors))
        self.assertTrue(any("reserved SQL keyword" in e for e in errors))

    def test_unknown_enum_is_refused(self):
        errors = self.mutate(lambda m: m["objects"][0]["fields"].append({"name": "x", "label": "X", "type": "ENUM", "enum": "nope"}))
        self.assertTrue(any("enum 'nope' is not defined" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
