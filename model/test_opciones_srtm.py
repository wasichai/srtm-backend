"""The SRTM's options missing from the model (wasichai/srtm-ui#20): apply.py adds them to a Core that already has the
model, after the options it has, and changes nothing else.

Run: cd model && python3 -m unittest -v test_opciones_srtm
"""
import unittest

from fake_core import FakeCore
from test_apply import ApplyCliTestCase, core_fields, load_model

# before: what Core has for tipo_documento. the srtm's manuals (M01-1-012 contribuyente, M01-1-014 predial) list
# "DNI, RUC, pasaporte, CE, PTP / CPP, CI, S/D y Otros": PTP / CPP, CI and Otros were missing
ANTES = ["SIN DOCUMENTO", "DNI", "CARNET DE EXTRANJERIA", "RUC", "SUCESION", "PASAPORTE"]
NUEVAS = ["PTP-CPP", "CI", "OTROS"]
PERSONAS = ["contribuyente", "relacionado", "transferente"]


class TipoDocumentoSrtmTests(ApplyCliTestCase):
    def setUp(self):
        model = load_model()
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        for name in PERSONAS:
            fields[name] = core_fields(model, name, options={"tipo_documento": ANTES})
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)

    def test_model_has_them_after_the_old_ones(self):
        # the stored values stay: the portal labels PTP-CPP as "PTP / CPP" (Core's options take no slash)
        self.assertEqual(load_model()["enums"]["tipo_documento"], ANTES + NUEVAS)

    def test_adds_them_to_the_three_personas_only(self):
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        option_puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "enumOptions" in (r[3] or {})]
        self.assertEqual(option_puts, [
            (f"/api/metadata/objects/{name}/fields/tipo_documento", {"enumOptions": ANTES + NUEVAS}) for name in PERSONAS
        ])
        for name in PERSONAS:
            self.assertIn(f"update field {name}.tipo_documento (+PTP-CPP, CI, OTROS)", out)


if __name__ == "__main__":
    unittest.main()
