"""Tests for import_catalogos.py: type splitting, catalogs from padrón rows, and an idempotent load.

Run: cd model && python3 -m unittest -v
"""
import io
import json
import os
import unittest
from contextlib import redirect_stdout

import import_catalogos as ic
from core_client import Client
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))


class SplitTipoTests(unittest.TestCase):
    def test_via_types(self):
        self.assertEqual(ic.split_tipo("AVENIDA LOS OLIVOS", ic.TIPOS_VIA), ("AVENIDA", "LOS OLIVOS"))
        self.assertEqual(ic.split_tipo("JR. LIMA", ic.TIPOS_VIA), ("JIRON", "LIMA"))
        self.assertEqual(ic.split_tipo("CARROZABLE BAJO MARANKIARI", ic.TIPOS_VIA), ("CARROZABLE", "BAJO MARANKIARI"))
        # a word that only starts like a type is not one
        self.assertEqual(ic.split_tipo("CALLEJON OSCURO", ic.TIPOS_VIA), ("OTROS", "CALLEJON OSCURO"))
        self.assertEqual(ic.split_tipo("SECTOR IPANEMA", ic.TIPOS_VIA), ("OTROS", "SECTOR IPANEMA"))

    def test_unidad_urbana_types_longest_first(self):
        tipos = ic.TIPOS_UNIDAD_URBANA
        self.assertEqual(ic.split_tipo("ASOCIACION DE VIVIENDA HERMANAS PAUCAR", tipos), ("ASOCIACION DE VIVIENDA", "HERMANAS PAUCAR"))
        self.assertEqual(ic.split_tipo("ASOCIACION AGRARIA", tipos), ("ASOCIACION", "AGRARIA"))
        self.assertEqual(ic.split_tipo("ANEXO - CENTRO POBLADO MIRICHARO", tipos), ("ANEXO", "CENTRO POBLADO MIRICHARO"))
        self.assertEqual(ic.split_tipo("CERCADO", tipos), ("OTROS", "CERCADO"))

    def test_unidad_urbana_after_lot_leftovers(self):
        tipos = ic.TIPOS_UNIDAD_URBANA
        self.assertEqual(ic.split_tipo("03-B CERCADO III MESETA", tipos, anywhere=True), ("CERCADO", "III MESETA"))
        self.assertEqual(ic.split_tipo("- MZ.B ASOCIACION DE VIVIENDA LAS VEGAS", tipos, anywhere=True),
                         ("ASOCIACION DE VIVIENDA", "LAS VEGAS"))
        self.assertEqual(ic.split_tipo("ANEXO - CENTRO POBLADO MIRICHARO", tipos, anywhere=True), ("ANEXO", "CENTRO POBLADO MIRICHARO"))
        self.assertEqual(ic.split_tipo("VILLA SOL", tipos, anywhere=True), ("OTROS", "VILLA SOL"))

    def test_every_type_is_a_model_option(self):
        with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
            enums = json.load(f)["enums"]
        self.assertTrue({t for _, t in ic.TIPOS_VIA} | {"OTROS"} <= set(enums["tipo_via"]))
        self.assertTrue({t for _, t in ic.TIPOS_UNIDAD_URBANA} | {"OTROS"} <= set(enums["tipo_unidad_urbana"]))


class CatalogsFromRowsTests(unittest.TestCase):
    def test_distinct_vias_and_unidades(self):
        rows = [
            {"direccion_predio": "AVENIDA MARGINAL Nro.: 12 CENTRO POBLADO UNION PERENE"},
            {"direccion_predio": "AVENIDA MARGINAL Nro.: 14 CENTRO POBLADO UNION PERENE"},
            {"direccion_predio": "JIRON LIMA Mz.: A Lt.: 3 CERCADO II MESETA"},
            {"direccion_predio": None},
        ]
        vias, unidades = ic.catalogs_from_rows(rows, "120302")
        self.assertEqual(vias, [
            {"tipo_via": "AVENIDA", "nombre": "MARGINAL", "ubigeo": "120302"},
            {"tipo_via": "JIRON", "nombre": "LIMA", "ubigeo": "120302"},
        ])
        self.assertEqual(unidades, [
            {"tipo_unidad_urbana": "CENTRO POBLADO", "nombre": "UNION PERENE", "ubigeo": "120302"},
            {"tipo_unidad_urbana": "CERCADO", "nombre": "II MESETA", "ubigeo": "120302"},
        ])


class ShippedUbigeoTests(unittest.TestCase):
    def test_every_inei_district(self):
        ubigeos = ic.read_ubigeo(os.path.join(HERE, "data", "ubigeo.csv"))
        self.assertEqual(len(ubigeos), 1893)
        self.assertEqual(len({u["codigo"] for u in ubigeos}), 1893)
        self.assertIn({"codigo": "120302", "departamento": "JUNIN", "provincia": "CHANCHAMAYO", "distrito": "PERENE"}, ubigeos)


class ShippedCategoriasTests(unittest.TestCase):
    def test_seven_columns_of_lettered_descriptions(self):
        categorias = ic.read_categorias(os.path.join(HERE, "data", "categorias_valor.csv"))
        self.assertEqual({c["columna"] for c in categorias}, set(range(1, 8)))
        self.assertEqual(len({(c["columna"], c["letra"]) for c in categorias}), len(categorias))
        with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
            letras = set(json.load(f)["enums"]["letra_categoria"])
        self.assertTrue({c["letra"] for c in categorias} <= letras)
        muros_c = next(c for c in categorias if c["columna"] == 1 and c["letra"] == "C")
        self.assertIn("ALBAÑILERÍA ARMADA", muros_c["descripcion"])


class ObrasTests(unittest.TestCase):
    def test_reads_partidas_and_every_value_fits_the_model(self):
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write("tipo_obra,numero,descripcion,unidad_medida,material\n")
            f.write("MUROS PERIMETRICOS O CERCOS,3,MURO DE LADRILLO,M2,LADRILLO\n")
            f.write("TANQUES ELEVADOS,1,TANQUE DE CONCRETO,M3,\n")
        self.addCleanup(os.unlink, f.name)
        obras = ic.read_obras(f.name)
        self.assertEqual(obras[0], {"tipo_obra": "MUROS PERIMETRICOS O CERCOS", "numero": 3, "descripcion": "MURO DE LADRILLO",
                                    "unidad_medida": "M2", "material": "LADRILLO"})
        self.assertNotIn("material", obras[1])
        # the shipped file is the template: header only
        self.assertEqual(ic.read_obras(os.path.join(HERE, "data", "obras_complementarias.csv")), [])


class LoadTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.client = Client(self.core.base_url)
        self.client.login("admin@wasichai.local", "admin")

    def test_creates_what_is_missing_and_nothing_twice(self):
        ubigeos = [{"codigo": "120302", "departamento": "JUNIN", "provincia": "CHANCHAMAYO", "distrito": "PERENE"}]
        vias = [{"tipo_via": "AVENIDA", "nombre": "MARGINAL", "ubigeo": "120302"}]
        unidades = [{"tipo_unidad_urbana": "CERCADO", "nombre": "II MESETA", "ubigeo": "120302"}]
        self.core.add_record("via", vias[0])
        with redirect_stdout(io.StringIO()):
            first = ic.load(self.client, ubigeos, vias, unidades, workers=2)
            second = ic.load(self.client, ubigeos, vias, unidades, workers=2)
        self.assertEqual(first, (2, 1))
        self.assertEqual(second, (0, 3))
        self.assertEqual(len(self.core.records["ubigeo"]), 1)
        self.assertEqual(len(self.core.records["via"]), 1)


if __name__ == "__main__":
    unittest.main()
