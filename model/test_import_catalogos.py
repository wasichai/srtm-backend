"""Tests for import_catalogos.py: type splitting, catalogs from padrón rows, and an idempotent load.

Run: cd model && python3 -m unittest -v
"""
import io
import json
import os
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_catalogos as ic
from core_client import Client
from fake_core import FakeCore
from import_predios import TIPOS_UNIDAD_URBANA

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
        tipos = TIPOS_UNIDAD_URBANA
        self.assertEqual(ic.split_tipo("ASOCIACION DE VIVIENDA HERMANAS PAUCAR", tipos), ("ASOCIACION DE VIVIENDA", "HERMANAS PAUCAR"))
        self.assertEqual(ic.split_tipo("ASOCIACION AGRARIA", tipos), ("ASOCIACION", "AGRARIA"))
        # the padrón's ANEXO is a CENTRO POBLADO, and the name says it again
        self.assertEqual(ic.split_tipo("ANEXO - CENTRO POBLADO MIRICHARO", tipos), ("CENTRO POBLADO", "MIRICHARO"))
        self.assertEqual(ic.split_tipo("CERCADO", tipos, default=None), (None, "CERCADO"))

    def test_unidad_urbana_after_lot_leftovers(self):
        tipos = TIPOS_UNIDAD_URBANA
        self.assertEqual(ic.split_tipo("03-B CERCADO III MESETA", tipos, anywhere=True), ("CERCADO", "III MESETA"))
        self.assertEqual(ic.split_tipo("- MZ.B ASOCIACION DE VIVIENDA LAS VEGAS", tipos, anywhere=True),
                         ("ASOCIACION DE VIVIENDA", "LAS VEGAS"))
        self.assertEqual(ic.split_tipo("ANEXO - CENTRO POBLADO MIRICHARO", tipos, anywhere=True), ("CENTRO POBLADO", "MIRICHARO"))
        self.assertEqual(ic.split_tipo("VILLA SOL", tipos, default=None, anywhere=True), (None, "VILLA SOL"))

    def test_every_type_is_a_model_option(self):
        with open(os.path.join(HERE, "model.json"), encoding="utf-8") as f:
            enums = json.load(f)["enums"]
        self.assertTrue({t for _, t in ic.TIPOS_VIA} | {"OTROS"} <= set(enums["tipo_via"]))
        self.assertTrue({t for _, t in TIPOS_UNIDAD_URBANA} <= set(enums["tipo_unidad_urbana"]))


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

    def test_uso_lists_the_catalog_usos_only(self):
        # wasichai/srtm-backend#31: the padrón's grupos de uso left it for the clases. COMERCIAL and INDUSTRIA stay:
        # they are also usos of the catalog (060806, 100402; 100301)
        usos = ic.read_usos(self.path)
        self.assertEqual(sorted(set(self.enums["uso"]) - {u["uso"] for u in usos}), [])
        self.assertEqual(len(self.enums["uso"]), len(set(self.enums["uso"])))
        self.assertNotIn("RESIDENCIAL - CASA HABITACION", self.enums["uso"])

    def test_the_padron_grupos_are_the_clases(self):
        # the padrón's ESTACIONAMIENTO is the clase 09, GARAGE in the SNCP's codifier; apply.py drops ESTACIONAMIENTO
        # from Core once migrar_usos_padron.py has moved the declaraciones that hold it
        padron = ["RESIDENCIAL", "TERRENO", "COMERCIAL", "DESOCUPADO", "INSTITUCIONAL", "EQUIPAMIENTO URBANO", "INDUSTRIA",
                  "RECREACIONAL", "BIENES COMUNES", "GARAGE"]
        self.assertEqual(sorted(self.enums["clase_uso"]), sorted(padron))
        self.assertIn(("090000", "GARAGE", "SNCP"), [(r["codigo"], r["descripcion"], r["fuente"]) for r in self._rows()])
        self.assertIn(
            {"codigo": "010101", "clase": "RESIDENCIAL", "sub_clase": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"}, ic.read_usos(self.path)
        )

    def test_every_row_says_where_it_comes_from(self):
        rows = self._rows()
        self.assertEqual({r["fuente"] for r in rows} - {"SRTM", "SNCP", "ARMONIZACION", "INFERIDO"}, set())
        self.assertEqual([r["codigo"] for r in rows], sorted(r["codigo"] for r in rows))
        self.assertEqual(len({r["codigo"] for r in rows}), len(rows))

    def test_the_srtm_structure(self):
        # the srtm's tipo uso predio ids 1-48 are its clases and sub clases: the SNCP codifier's 10 and 38
        codigos = [r["codigo"] for r in self._rows()]
        self.assertEqual(len([c for c in codigos if c.endswith("0000")]), 10)
        self.assertEqual(len([c for c in codigos if c.endswith("00") and not c.endswith("0000")]), 38)
        # ids 49-58 are its residential usos and 299-303 the terreno's (M21-1-003, pages 263 and 417)
        self.assertEqual([c for c in codigos if c.startswith("01") and not c.endswith("00")],
                         ["010101"] + [f"0102{i:02d}" for i in range(1, 10)])
        self.assertEqual([c for c in codigos if c.startswith("07") and not c.endswith("00")],
                         ["070101", "070201", "070301", "070401", "070402"])

    def test_the_cascade_is_unambiguous(self):
        # the portal cascades by name: two sub clases of a clase with one name would be a single option
        usos = ic.read_usos(self.path)
        names = [(clase, sub_clase) for (clase, _), sub_clase in
                 {(u["clase"], u["codigo"][:4]): u["sub_clase"] for u in usos}.items()]
        self.assertEqual(sorted(n for n in set(names) if names.count(n) > 1), [])
        triples = [(u["clase"], u["sub_clase"], u["uso"]) for u in usos]
        self.assertEqual(sorted(t for t in set(triples) if triples.count(t) > 1), [])

    def test_every_name_is_a_valid_enum_option(self):
        import apply
        self.assertEqual([r["descripcion"] for r in self._rows() if not apply.enum_option_valid(r["descripcion"])], [])

    def test_sub_clase_lists_the_catalog_sub_clases_only(self):
        usos = ic.read_usos(self.path)
        self.assertEqual(sorted(set(self.enums["sub_clase_uso"]) - {u["sub_clase"] for u in usos}), [])
        self.assertEqual(len(self.enums["sub_clase_uso"]), len(set(self.enums["sub_clase_uso"])))

    def _rows(self):
        import csv
        with open(self.path, encoding="utf-8") as f:
            return list(csv.DictReader(f))


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

    def test_the_municipalidad_only_when_the_organization_has_none(self):
        # one record per organization: the provisional one never doubles nor overwrites what the admin edited
        provisional = ic.read_municipalidad(os.path.join(os.path.dirname(__file__), "data", "municipalidad.json"))
        self.assertEqual(provisional[0]["nombre"], "MUNICIPALIDAD DISTRITAL DE PERENÉ")
        self.assertEqual(provisional[0]["ruc"], "20195238961")
        self.assertNotIn("_comentario", provisional[0])
        self.assertNotIn("direccion", provisional[0])
        with redirect_stdout(io.StringIO()):
            first = ic.load(self.client, [], [], [], workers=2, municipalidades=provisional)
            second = ic.load(self.client, [], [], [], workers=2, municipalidades=provisional)
        self.assertEqual((first, second), ((1, 0), (0, 1)))
        self.assertEqual(len(self.core.records["municipalidad"]), 1)

    def test_an_edited_municipalidad_stays(self):
        self.core.add_record("municipalidad", {"nombre": "MUNICIPALIDAD DISTRITAL DE PERENÉ", "direccion": "JR. LIMA 123"})
        with redirect_stdout(io.StringIO()):
            ic.load(self.client, [], [], [], workers=2, municipalidades=[{"nombre": "OTRA"}])
        self.assertEqual([r["attributes"]["nombre"] for r in self.core.records["municipalidad"]], ["MUNICIPALIDAD DISTRITAL DE PERENÉ"])

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


CASA = {"codigo": "010101", "clase": "RESIDENCIAL", "sub_clase": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"}
TERCEROS = {"codigo": "070401", "clase": "TERRENO", "sub_clase": "OCUPADO", "uso": "CON CONSTRUCCIÓN DE TERCEROS"}
COMUN = {"codigo": "100106", "clase": "BIENES COMUNES", "sub_clase": "RESIDENCIAL", "uso": "CASA HABITACIÓN"}


class SyncUsosTests(unittest.TestCase):
    """uso_predio follows the CSV by código: nothing references its records (the declaración keeps the names)."""

    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.client = Client(self.core.base_url)
        self.client.login("admin@wasichai.local", "admin")
        self.core.add_record("uso_predio", CASA)
        self.core.add_record("uso_predio", {**TERCEROS, "uso": "CON CONSTRUCCIÓN"})
        self.core.add_record("uso_predio", {"codigo": "070102", "clase": "TERRENO", "sub_clase": "DESOCUPADO", "uso": "TERRENO ERIAZO"})
        self.core.add_record("via", {"tipo_via": "AVENIDA", "nombre": "MARGINAL", "ubigeo": "120302"})

    def _load(self, usos):
        out = io.StringIO()
        with redirect_stdout(out):
            result = ic.load(self.client, [], [], [], workers=2, usos=usos)
        return result, out.getvalue()

    def _usos(self):
        return sorted((r["attributes"]["codigo"], r["attributes"]["uso"]) for r in self.core.records["uso_predio"])

    def test_creates_updates_and_deletes_by_codigo(self):
        result, out = self._load([CASA, TERCEROS, COMUN])
        self.assertIn("uso_predio: 1 created, 1 updated, 1 deleted, 1 skipped", out)
        self.assertEqual(result, (1, 1))
        self.assertEqual(self._usos(), [("010101", "CASA HABITACIÓN"), ("070401", "CON CONSTRUCCIÓN DE TERCEROS"),
                                        ("100106", "CASA HABITACIÓN")])
        # Core's update replaces every field: all four go
        [terceros] = [r for r in self.core.records["uso_predio"] if r["attributes"]["codigo"] == "070401"]
        self.assertEqual(terceros["attributes"], TERCEROS)

    def test_a_second_run_changes_nothing(self):
        self._load([CASA, TERCEROS, COMUN])
        writes = len([r for r in self.core.requests if r[0] != "GET"])
        _, out = self._load([CASA, TERCEROS, COMUN])
        self.assertIn("uso_predio: 0 created, 0 updated, 0 deleted, 3 skipped", out)
        self.assertEqual(len([r for r in self.core.requests if r[0] != "GET"]), writes)

    def test_the_other_catalogs_stay_create_only(self):
        # a via Core has and the list lacks stays; without usos the catalog of usos is not touched
        result, out = self._load(())
        self.assertEqual(result, (0, 0))
        self.assertEqual(len(self.core.records["via"]), 1)
        self.assertEqual(len(self.core.records["uso_predio"]), 3)
        self.assertNotIn("uso_predio", out)
        self.assertEqual([r for r in self.core.requests if r[0] == "DELETE"], [])

    def test_dry_run_reads_core_and_writes_nothing(self):
        path = write_csv(self, "codigo,descripcion,fuente\n010000,RESIDENCIAL,SRTM\n010100,UNIFAMILIAR,SRTM\n"
                               "010101,CASA HABITACIÓN,SRTM\n070000,TERRENO,SNCP\n070400,OCUPADO,SRTM\n"
                               "070401,CON CONSTRUCCIÓN DE TERCEROS,SRTM\n100000,BIENES COMUNES,SNCP\n"
                               "100100,RESIDENCIAL,SRTM\n100106,CASA HABITACIÓN,SRTM\n")
        out = io.StringIO()
        with redirect_stdout(out):
            code = ic.main(["--usos-csv", path, "--core", self.core.base_url, "--dry-run"])
        self.assertEqual(code, 0)
        self.assertEqual({r[0] for r in self.core.requests if r[1] != "/api/auth/login"}, {"GET"})
        self.assertIn("uso_predio: 1 to create, 1 to update, 1 to delete, 1 skipped", out.getvalue())
        self.assertIn("  update 070401: CON CONSTRUCCIÓN -> CON CONSTRUCCIÓN DE TERCEROS", out.getvalue())
        self.assertIn("  delete 070102", out.getvalue())
        self.assertIn("  create 100106", out.getvalue())
        self.assertEqual(len(self.core.records["uso_predio"]), 3)

    def test_a_refused_update_names_the_codigo(self):
        self.core.fail_on_update = "uso_predio"
        path = write_csv(self, "codigo,descripcion,fuente\n070000,TERRENO,SNCP\n070400,OCUPADO,SRTM\n"
                               "070401,CON CONSTRUCCIÓN DE TERCEROS,SRTM\n")
        err = io.StringIO()
        with redirect_stdout(io.StringIO()), redirect_stderr(err):
            code = ic.main(["--usos-csv", path, "--core", self.core.base_url])
        self.assertEqual(code, 1)
        self.assertIn("error PUT /api/objects/uso_predio/records (#070401) -> 400", err.getvalue())


if __name__ == "__main__":
    unittest.main()
