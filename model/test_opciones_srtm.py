"""The SRTM's options missing from the model (wasichai/srtm-ui#20): apply.py adds them to a Core that already has the
model, after the options it has, and changes nothing else.

Run: cd model && python3 -m unittest -v test_opciones_srtm
"""
import unittest

from fake_core import FakeCore
from import_predios import TIPOS_UNIDAD_URBANA, split_tipo
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


# the two tipos de unidad urbana the presentation cuts on page 5 ("ASOCIACION DE VIVIENDA D…", "…E I…"), named as the
# catastro fiscal's TIPO_UU domain names them (codes 53 and 48): the list the srtm imports its zonas urbanas from
UNIDADES_NUEVAS = ["ASOCIACION DE VIVIENDA DE INTERES SOCIAL", "ASOCIACION DE VIVIENDA E INTERES SOCIAL"]


class TipoUnidadUrbanaSrtmTests(ApplyCliTestCase):
    def setUp(self):
        model = load_model()
        self.antes = [o for o in model["enums"]["tipo_unidad_urbana"] if o not in UNIDADES_NUEVAS]
        # every ENUM field on the list: the domicilio's, the catalog's, the predio's and the catastro's zona
        self.campos = [(o["name"], f["name"]) for o in model["objects"] for f in o["fields"]
                       if f.get("enum") == "tipo_unidad_urbana"]
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        for objeto, campo in self.campos:
            fields[objeto] = core_fields(model, objeto, options={campo: self.antes})
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)

    def test_model_lists_them_after_asociacion_de_vivienda(self):
        # page 5 sorts the list: AGRUPACION … ASOCIACION DE VIVIENDA, then these two
        tipos = load_model()["enums"]["tipo_unidad_urbana"]
        i = tipos.index("ASOCIACION DE VIVIENDA")
        self.assertEqual(tipos[i + 1:i + 3], UNIDADES_NUEVAS)

    def test_adds_them_to_every_field_on_the_list(self):
        self.assertEqual(len(self.campos), 4)
        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        option_puts = [(r[1], r[3]) for r in self.core.requests if r[0] == "PUT" and "enumOptions" in (r[3] or {})]
        # in model.json's order, page 5's, as apply.py writes any option list it changes
        self.assertEqual(option_puts, [
            (f"/api/metadata/objects/{objeto}/fields/{campo}", {"enumOptions": load_model()["enums"]["tipo_unidad_urbana"]})
            for objeto, campo in self.campos
        ])
        for objeto, campo in self.campos:
            self.assertIn(f"update field {objeto}.{campo} (+{', '.join(UNIDADES_NUEVAS)})", out)

    def test_a_padron_address_keeps_the_whole_type(self):
        for tipo in UNIDADES_NUEVAS:
            with self.subTest(tipo=tipo):
                self.assertEqual(split_tipo(f"{tipo} LOS PINOS", TIPOS_UNIDAD_URBANA, anywhere=True), (tipo, "LOS PINOS"))
        self.assertEqual(split_tipo("ASOCIACION DE VIVIENDA LOS PINOS", TIPOS_UNIDAD_URBANA, anywhere=True),
                         ("ASOCIACION DE VIVIENDA", "LOS PINOS"))


if __name__ == "__main__":
    unittest.main()
