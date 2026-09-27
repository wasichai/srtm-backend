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
            f.write("tipo_obra,numero,descripcion,unidad_medida,material,valor_unitario\n")
            f.write("MUROS PERIMETRICOS O CERCOS,3,MURO DE LADRILLO,M2,LADRILLO,387.38\n")
            f.write("TANQUES ELEVADOS,1,TANQUE DE CONCRETO,M3,,1328.5\n")
            f.write("TANQUES ELEVADOS,2,TANQUE DE PLÁSTICO,M3,,\n")
        self.addCleanup(os.unlink, f.name)
        obras = ic.read_obras(f.name)
        self.assertEqual(obras[0], {"tipo_obra": "MUROS PERIMETRICOS O CERCOS", "numero": 3, "descripcion": "MURO DE LADRILLO",
                                    "unidad_medida": "M2", "material": "LADRILLO", "valor_unitario": "387.38"})
        self.assertNotIn("material", obras[1])
        self.assertEqual(obras[1]["valor_unitario"], "1328.50")
        self.assertNotIn("valor_unitario", obras[2])


class ShippedObrasTests(unittest.TestCase):
    """The partidas of annex III.4 (selva) of R.M. 277-2025-VIVIENDA, the one for Perené."""

    def setUp(self):
        self.obras = ic.read_obras(os.path.join(HERE, "data", "obras_complementarias.csv"))
        with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
            self.enums = json.load(f)["enums"]

    def test_the_96_items_of_the_annex(self):
        self.assertEqual([o["numero"] for o in self.obras], list(range(1, 97)))
        self.assertEqual(len({(o["tipo_obra"], o["numero"]) for o in self.obras}), 96)

    def test_every_value_is_an_option_of_its_enum(self):
        self.assertEqual(sorted({o["tipo_obra"] for o in self.obras} - set(self.enums["tipo_obra"])), [])
        self.assertEqual(sorted({o["unidad_medida"] for o in self.obras} - set(self.enums["unidad_medida"])), [])
        self.assertEqual(sorted({o["material"] for o in self.obras if "material" in o} - set(self.enums["material"])), [])

    def test_every_group_of_the_annex_is_used(self):
        # "Losas deportivas, estacionamientos, patios de maniobras, superficie de rodadura, veredas" does not fit Core's
        # 64 characters (nor its commas): it is LOSAS DEPORTIVAS - ESTACIONAMIENTOS - PATIOS - VEREDAS
        self.assertEqual(sorted(set(self.enums["tipo_obra"]) - {o["tipo_obra"] for o in self.obras}), [])
        self.assertEqual(len(self.enums["tipo_obra"]), 31)

    def test_every_item_has_its_unit_value(self):
        self.assertTrue(all(float(o["valor_unitario"]) > 0 for o in self.obras))
        by_numero = {o["numero"]: o for o in self.obras}
        muro = by_numero[3]
        self.assertEqual((muro["tipo_obra"], muro["unidad_medida"], muro["material"], muro["valor_unitario"]),
                         ("MUROS PERIMETRICOS O CERCOS", "M2", "LADRILLO", "387.38"))
        self.assertTrue(muro["descripcion"].startswith("MURO DE LADRILLO DE ARCILLA O SIMILAR, TARRAJEADO"))
        self.assertEqual(by_numero[17]["valor_unitario"], "1328.49")
        self.assertEqual(by_numero[74]["unidad_medida"], "PZA")


def write_csv(test, text):
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
        f.write(text)
    test.addCleanup(os.unlink, f.name)
    return f.name


class UsosPredioTests(unittest.TestCase):
    def test_reads_the_hierarchy_by_code(self):
        path = write_csv(self, "codigo,descripcion,fuente\n"
                               "010000,RESIDENCIAL,SRTM\n010100,UNIFAMILIAR,SRTM\n010101,CASA HABITACIÓN,SRTM\n"
                               "090000,ESTACIONAMIENTO,INFERIDO\n090100,RESIDENCIAL,SRTM\n090101,CASA HABITACIÓN,SRTM\n"
                               "090200,RESIDENCIAL,SRTM\n090201,EDIFICIO,SRTM\n")
        self.assertEqual(ic.read_usos(path), [
            {"codigo": "010101", "clase": "RESIDENCIAL", "sub_clase": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"},
            {"codigo": "090101", "clase": "ESTACIONAMIENTO", "sub_clase": "RESIDENCIAL", "uso": "CASA HABITACIÓN"},
            {"codigo": "090201", "clase": "ESTACIONAMIENTO", "sub_clase": "RESIDENCIAL", "uso": "EDIFICIO"},
        ])

    def test_a_uso_without_its_sub_clase_is_refused(self):
        path = write_csv(self, "codigo,descripcion,fuente\n010000,RESIDENCIAL,SRTM\n010201,EDIFICIO,SRTM\n")
        with self.assertRaisesRegex(ValueError, "010201"):
            ic.read_usos(path)

    def test_a_bad_code_is_refused(self):
        path = write_csv(self, "codigo,descripcion,fuente\n10101,CASA HABITACIÓN,SRTM\n")
        with self.assertRaisesRegex(ValueError, "10101"):
            ic.read_usos(path)


class ShippedUsosTests(unittest.TestCase):
    def setUp(self):
        self.path = os.path.join(HERE, "data", "usos_predio.csv")
        with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
            self.enums = json.load(f)["enums"]

    def test_the_srtm_example_is_there(self):
        usos = ic.read_usos(self.path)
        self.assertIn({"codigo": "010101", "clase": "RESIDENCIAL", "sub_clase": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"}, usos)
        self.assertEqual(len({u["codigo"] for u in usos}), len(usos))

    def test_every_name_is_an_option_of_its_enum(self):
        # the declaración's clase_uso, sub_clase_uso and uso are ENUMs: what the cascade offers must be storable
        usos = ic.read_usos(self.path)
        self.assertEqual(sorted({u["clase"] for u in usos} - set(self.enums["clase_uso"])), [])
        self.assertEqual(sorted({u["sub_clase"] for u in usos} - set(self.enums["sub_clase_uso"])), [])
        self.assertEqual(sorted({u["uso"] for u in usos} - set(self.enums["uso"])), [])

    def test_the_padron_usos_stay_storable(self):
        padron = ["RESIDENCIAL - CASA HABITACION", "TERRENO", "COMERCIAL", "DESOCUPADO", "INSTITUCIONAL",
                  "EQUIPAMIENTO URBANO", "INDUSTRIA", "RECREACIONAL", "BIENES COMUNES", "ESTACIONAMIENTO"]
        self.assertTrue(set(padron) <= set(self.enums["uso"]))

    def test_every_row_says_where_it_comes_from(self):
        import csv
        with open(self.path, encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual({r["fuente"] for r in rows} - {"SRTM", "ARMONIZACION", "INFERIDO"}, set())
        self.assertEqual([r["codigo"] for r in rows], sorted(r["codigo"] for r in rows))
        self.assertEqual(len({r["codigo"] for r in rows}), len(rows))


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

    def test_usos_by_codigo_once(self):
        usos = [
            {"codigo": "010101", "clase": "RESIDENCIAL", "sub_clase": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"},
            {"codigo": "100106", "clase": "BIENES COMUNES", "sub_clase": "RESIDENCIAL", "uso": "CASA HABITACIÓN"},
        ]
        self.core.add_record("uso_predio", usos[0])
        with redirect_stdout(io.StringIO()):
            first = ic.load(self.client, [], [], [], workers=2, usos=usos)
            second = ic.load(self.client, [], [], [], workers=2, usos=usos)
        self.assertEqual(first, (1, 1))
        self.assertEqual(second, (0, 2))
        self.assertEqual([r["attributes"]["codigo"] for r in self.core.records["uso_predio"]], ["010101", "100106"])

    def test_obras_by_tipo_and_numero_once(self):
        obras = [
            {"tipo_obra": "MUROS PERIMETRICOS O CERCOS", "numero": 3, "descripcion": "MURO DE LADRILLO", "unidad_medida": "M2",
             "material": "LADRILLO", "valor_unitario": "387.38"},
            {"tipo_obra": "TANQUES ELEVADOS", "numero": 17, "descripcion": "TANQUE DE CONCRETO", "unidad_medida": "M3",
             "valor_unitario": "1328.49"},
        ]
        self.core.add_record("obra_categoria", obras[0])
        with redirect_stdout(io.StringIO()):
            first = ic.load(self.client, [], [], [], workers=2, obras=obras)
            second = ic.load(self.client, [], [], [], workers=2, obras=obras)
        self.assertEqual(first, (1, 1))
        self.assertEqual(second, (0, 2))
        self.assertEqual([r["attributes"]["numero"] for r in self.core.records["obra_categoria"]], [3, 17])


if __name__ == "__main__":
    unittest.main()
