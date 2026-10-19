"""Tests for import_padron_ica.py: Ica's code tables, the pure mappings, the dataset and the load against a fake Core.

Every row here is made up: the real padrón carries names and documents and never enters the repo.

Run: cd model && python3 -m unittest -v
"""
import csv
import io
import json
import os
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from decimal import Decimal
from xml.sax.saxutils import escape

import import_padron_ica as ipi
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
CODIGOS = ipi.load_codigos(ipi.default_codigos_path())
MODEL = ipi.load_model(ipi.default_model_path())
USOS = ipi.read_usos_srtm(ipi.default_usos_path())


class CodigosTests(unittest.TestCase):
    """data/ica/codigos.json against the model it maps into."""

    ENUM_OF = {
        "tipo_via": "tipo_via", "tipo_documento": "tipo_documento", "condicion_propiedad": "condicion_propiedad",
        "tipo_predio": "tipo_predio", "material": "material", "estado_conservacion": "estado_conservacion",
        "clasificacion": "clasificacion",
    }

    def test_no_table_is_confirmed_yet(self):
        tablas = {k: v for k, v in CODIGOS.items() if not k.startswith("_")}
        self.assertEqual(set(tablas), {*self.ENUM_OF, "uso", "nivel"})
        self.assertTrue(all(t["confirmado"] is False for t in tablas.values()))

    def test_every_option_is_one_of_its_enum(self):
        for tabla, enum in self.ENUM_OF.items():
            with self.subTest(tabla=tabla):
                valores = [v for v in CODIGOS[tabla]["valores"].values() if v is not None]
                self.assertTrue(set(valores) <= set(MODEL["enums"][enum]))
        self.assertIn(CODIGOS["tipo_via"]["por_defecto"], MODEL["enums"]["tipo_via"])
        self.assertIn(CODIGOS["nivel"]["valores"]["0"]["tipo_nivel"], MODEL["enums"]["tipo_nivel"])

    def test_every_uso_is_an_srtm_code_whose_names_are_options(self):
        for codigo, entrada in CODIGOS["uso"]["valores"].items():
            with self.subTest(codigo=codigo):
                if entrada["srtm"] is None:
                    continue
                usos = ipi.uso_srtm(entrada["srtm"], USOS)
                for field_name, enum in (("clase_uso", "clase_uso"), ("sub_clase_uso", "sub_clase_uso"), ("uso", "uso")):
                    if usos[field_name] is not None:
                        self.assertIn(usos[field_name], MODEL["enums"][enum])

    def test_municipalidad_json_has_no_invented_ruc(self):
        with open(os.path.join(HERE, "data", "ica", "municipalidad.json"), encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(data["nombre"], "MUNICIPALIDAD PROVINCIAL DE ICA")
        self.assertIsNone(data["ruc"])
        import import_catalogos
        self.assertEqual(import_catalogos.read_municipalidad(os.path.join(HERE, "data", "ica", "municipalidad.json")),
                         [{"nombre": "MUNICIPALIDAD PROVINCIAL DE ICA"}])


class CodigoTests(unittest.TestCase):
    def test_numbers_read_as_their_text(self):
        self.assertEqual(ipi.codigo("20"), "20")
        self.assertEqual(ipi.codigo("20.0"), "20")
        self.assertEqual(ipi.codigo(" 4 "), "4")
        self.assertIsNone(ipi.codigo(None))
        self.assertIsNone(ipi.codigo(""))
        self.assertEqual(ipi.codigo("47-23"), "47-23")


class DocumentoTests(unittest.TestCase):
    def test_dni_is_padded_to_eight_digits(self):
        self.assertEqual(ipi.documento("1", "1234567", CODIGOS), ("DNI", "01234567", "documento_rellenado"))
        self.assertEqual(ipi.documento("1", "12345678", CODIGOS), ("DNI", "12345678", None))

    def test_ruc_pasaporte_and_sin_documento(self):
        self.assertEqual(ipi.documento("3", "20123456789", CODIGOS), ("RUC", "20123456789", None))
        self.assertEqual(ipi.documento("7", "AB1234567", CODIGOS), ("PASAPORTE", "AB1234567", None))
        self.assertEqual(ipi.documento("0", "1234567", CODIGOS), ("SIN DOCUMENTO", "1234567", None))

    def test_unidentified_types_are_otros(self):
        self.assertEqual(ipi.documento("2", "12345678", CODIGOS), ("OTROS", "12345678", None))
        self.assertEqual(ipi.documento("5", "1234567", CODIGOS), ("OTROS", "1234567", None))

    def test_a_type_without_number_is_sin_documento(self):
        self.assertEqual(ipi.documento("1", None, CODIGOS), ("SIN DOCUMENTO", None, "documento_sin_numero"))
        self.assertEqual(ipi.documento(None, None, CODIGOS), ("SIN DOCUMENTO", None, None))

    def test_an_unknown_type_is_otros_and_unmapped(self):
        self.assertEqual(ipi.documento("9", "123", CODIGOS), ("OTROS", "123", "codigo_sin_mapeo"))


class PersonaTests(unittest.TestCase):
    def test_ruc_20_is_juridica(self):
        self.assertEqual(ipi.tipo_persona("RUC", "20123456789", "EMPRESA FICTICIA SAC", set()), "JURIDICA")

    def test_ruc_10_and_dni_are_natural(self):
        self.assertEqual(ipi.tipo_persona("RUC", "10123456789", "PEREZ PEREZ JUAN", set()), "NATURAL")
        self.assertEqual(ipi.tipo_persona("DNI", "12345678", "PEREZ PEREZ JUAN", set()), "NATURAL")

    def test_sucesion_by_name_or_by_condition(self):
        self.assertEqual(ipi.tipo_persona("DNI", "12345678", "SUC. PEREZ PEREZ JUAN", set()), "SUCESION")
        self.assertEqual(ipi.tipo_persona("DNI", "12345678", "PEREZ PEREZ JUAN", {"1", "6"}), "SUCESION")
        self.assertEqual(ipi.tipo_persona("DNI", "12345678", "PEREZ PEREZ JUAN", {"2"}), "SUCESION")

    def test_a_ruc_20_is_never_a_sucesion(self):
        self.assertEqual(ipi.tipo_persona("RUC", "20123456789", "EMPRESA FICTICIA SAC", {"6"}), "JURIDICA")


class CondominioTests(unittest.TestCase):
    def test_a_sole_titular_holds_100(self):
        self.assertEqual(ipi.porcentajes(1), [Decimal("100.00")])

    def test_shares_add_up_to_exactly_100(self):
        self.assertEqual(ipi.porcentajes(3), [Decimal("33.34"), Decimal("33.33"), Decimal("33.33")])
        # the cents 100/n leaves go one each to the first titulares
        self.assertEqual(ipi.porcentajes(6), [Decimal("16.67")] * 4 + [Decimal("16.66")] * 2)
        for n in (2, 3, 6, 7, 115):
            with self.subTest(n=n):
                shares = ipi.porcentajes(n)
                self.assertEqual(len(shares), n)
                self.assertEqual(sum(shares), Decimal("100"))
                self.assertTrue(all(s > 0 for s in shares))

    def test_values_as_condominio_kt(self):
        # valor_condominio = autoavalúo x % / 100, to the cent, half up; deducción 0, so valor_afecto is the same
        self.assertEqual(ipi.valores_condominio("10611.00", Decimal("33.34")), ("3537.71", "3537.71"))
        self.assertEqual(ipi.valores_condominio("66.23", Decimal("100.00")), ("66.23", "66.23"))
        self.assertEqual(ipi.valores_condominio(None, Decimal("100.00")), (None, None))

    def test_condicion_follows_condominio_kt(self):
        self.assertEqual(ipi.condicion("1", 1, CODIGOS), "PROPIETARIO UNICO")
        self.assertEqual(ipi.condicion("4", 1, CODIGOS), "SOCIEDAD CONYUGAL")
        self.assertEqual(ipi.condicion("3", 1, CODIGOS), "POSEEDOR")
        # a condómino alone in the extract holds it whole
        self.assertEqual(ipi.condicion("5", 1, CODIGOS), "PROPIETARIO UNICO")
        self.assertEqual(ipi.condicion("6", 1, CODIGOS), "PROPIETARIO UNICO")
        self.assertEqual(ipi.condicion("8", 1, CODIGOS), "PROPIETARIO UNICO")
        for code in ("1", "4", "5", "6", "9"):
            self.assertEqual(ipi.condicion(code, 2, CODIGOS), "CONDOMINO")


class FechaTests(unittest.TestCase):
    def test_the_1900_filler_is_no_date(self):
        self.assertIsNone(ipi.fecha("1900-01-01 00:00:00"))
        self.assertIsNone(ipi.fecha(None))

    def test_text_and_excel_serials(self):
        self.assertEqual(ipi.fecha("2004-01-01 00:00:00"), "2004-01-01")
        self.assertEqual(ipi.fecha("2018-06-30"), "2018-06-30")
        self.assertEqual(ipi.fecha("45292"), "2024-01-01")
        self.assertIsNone(ipi.fecha("1"))  # 1900-01-01 as a serial

    def test_garbage_is_an_error(self):
        with self.assertRaises(ValueError):
            ipi.fecha("ayer")


class UsoTests(unittest.TestCase):
    def test_a_uso_code_gives_clase_sub_clase_and_uso(self):
        self.assertEqual(ipi.uso_ica("20", CODIGOS, USOS),
                         {"clase_uso": "TERRENO", "sub_clase_uso": "DESOCUPADO", "uso": "SIN CONSTRUIR"})
        self.assertEqual(ipi.uso_ica("4", CODIGOS, USOS),
                         {"clase_uso": "RESIDENCIAL", "sub_clase_uso": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"})

    def test_a_sub_clase_or_clase_code_leaves_the_rest_empty(self):
        self.assertEqual(ipi.uso_ica("1", CODIGOS, USOS), {"clase_uso": "COMERCIAL", "sub_clase_uso": "TIENDAS", "uso": None})
        self.assertEqual(ipi.uso_ica("23", CODIGOS, USOS), {"clase_uso": "GARAGE", "sub_clase_uso": None, "uso": None})

    def test_unmapped_is_none(self):
        self.assertIsNone(ipi.uso_ica("22", CODIGOS, USOS))
        self.assertIsNone(ipi.uso_ica("99", CODIGOS, USOS))


class NivelTests(unittest.TestCase):
    def test_zero_is_the_sotano(self):
        self.assertEqual(ipi.nivel("0", CODIGOS), ("SOTANO", 1))

    def test_others_are_pisos(self):
        self.assertEqual(ipi.nivel("1", CODIGOS), ("PISO", 1))
        self.assertEqual(ipi.nivel("6.0", CODIGOS), ("PISO", 6))

    def test_no_nivel_is_none(self):
        self.assertEqual(ipi.nivel(None, CODIGOS), (None, None))


class CheckTests(unittest.TestCase):
    def test_a_valid_record_has_no_problems(self):
        self.assertEqual(ipi.check(MODEL, "via", {"tipo_via": "CALLE", "nombre": "LAS FLORES", "ubigeo": "110101"}), [])

    def test_missing_required_and_bad_option(self):
        problems = ipi.check(MODEL, "via", {"tipo_via": "AUTOPISTA"})
        self.assertIn("nombre es obligatorio", problems)
        self.assertIn("tipo_via 'AUTOPISTA' no es una opción de tipo_via", problems)

    def test_a_required_relationship_counts(self):
        self.assertIn("predio es obligatorio", ipi.check(MODEL, "declaracion_predial", {"anio": 2026, "secuencia_uso": "001"}, relations=True))

    def test_unknown_fields_are_schema_errors(self):
        self.assertEqual(ipi.unknown_fields(MODEL, "via", {"tipo_via": "CALLE", "nombre": "X", "sabor": "1"}), ["sabor"])
