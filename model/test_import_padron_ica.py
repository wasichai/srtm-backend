"""Tests for import_padron_ica.py: Ica's code tables, the pure mappings, the dataset and the load against a fake Core.

Every row here is made up: the real padrón carries names and documents and never enters the repo.

Run: cd model && python3 -m unittest -v
"""
import csv
from collections import Counter
import io
import json
import os
import subprocess
import sys
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
        self.assertEqual(ipi.tipo_persona("DNI", "12345678", "SUC PEREZ PEREZ JUAN", set()), "SUCESION")
        self.assertEqual(ipi.tipo_persona("DNI", "12345678", "SUCESION PEREZ PEREZ JUAN", set()), "SUCESION")
        # a surname that starts like it is no sucesión
        self.assertEqual(ipi.tipo_persona("DNI", "12345678", "SUCRE PEREZ JUAN", set()), "NATURAL")

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
        self.assertEqual(ipi.fecha("31/12/2004"), "2004-12-31")

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

    def test_a_negative_or_unreadable_nivel_is_an_error(self):
        for value in ("-1", "PB"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                ipi.nivel(value, CODIGOS)

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


# ---------------------------------------------------------------------------
# made-up rows, with the columns of the real sheets
# ---------------------------------------------------------------------------

PADRON = [
    "id", "codigo_contribuyente", "codigo_predio", "direccion_del_predio_completa", "codigo_de_via", "tipo_de_via",
    "nombre_de_la_via", "numero", "manzana", "lote", "codigo_de_unidad_urbana", "tipo_de_unidad_urbana",
    "nombre_de_unidad_urbana", "condicion_predio", "fecha_adquisicion", "codigo_uso", "descripcion_del_uso",
    "tipo_predio", "numero_de_interior", "condicion_propiedad", "condomin", "area_terreno", "area_terreno_comun",
    "arancel", "valor_terreno", "area_construida", "area_construida_comun", "valor_area_construida",
    "valor_otras_instalaciones", "valor_predio", "numero_de_partida_registral", "codigo_de_contribuyente",
    "codigo_de_contribuyente_2", "apellido_paterno", "apellido_materno", "nombres", "nombre_completo",
    "tipo_documento", "nro_documento", "COD_HU_ARMONIZACION", "COD_VIA_ARMONIZACION",
]
CONSTRUCCIONES = [
    "id", "codigo_contribuyente", "codigo_predio", "nivel", "ano_construccion", "clasificacion_construccion",
    "material_predominante", "estado_de_conservacion", "muros_y_columnas", "techos", "pisos", "puertas_y_ventanas",
    "revestimientos", "banos", "instalaciones_electricas_y_sanitarias", "area_construida_propia",
    "area_construida_comun", "valor_unitario", "incremento_5", "depreciacion", "valor_unitario_depreciado",
    "valor_total_nivel", "area_comun",
]


def padron(**overrides):
    base = {
        "id": "1", "codigo_contribuyente": "900001", "codigo_predio": "000000001-0001-0001",
        "direccion_del_predio_completa": "CLL. LAS FLORES 120 URB. EL JARDIN", "codigo_de_via": "501", "tipo_de_via": "3",
        "nombre_de_la_via": "LAS FLORES", "numero": "120", "manzana": "C", "lote": "17", "codigo_de_unidad_urbana": "77",
        "tipo_de_unidad_urbana": "URBANIZACION", "nombre_de_unidad_urbana": "EL JARDIN", "condicion_predio": "1",
        "fecha_adquisicion": "2004-01-01 00:00:00", "codigo_uso": "4", "descripcion_del_uso": "CASA HABITACION",
        "tipo_predio": "1", "condicion_propiedad": "PROPIETARIO ÚNICO", "condomin": "0", "area_terreno": "120",
        "area_terreno_comun": "0", "arancel": "356", "valor_terreno": "42720", "area_construida": "100",
        "area_construida_comun": "0", "valor_area_construida": "69527.64", "valor_otras_instalaciones": "0",
        "valor_predio": "112247.64", "codigo_de_contribuyente": "900001", "codigo_de_contribuyente_2": "900001",
        "apellido_paterno": "QUISPE", "apellido_materno": "MAMANI", "nombres": "ANA", "nombre_completo": "QUISPE MAMANI ANA",
        "tipo_documento": "1", "nro_documento": "40000001", "COD_HU_ARMONIZACION": "405", "COD_VIA_ARMONIZACION": "23250",
    }
    base.update(overrides)
    return base


def construccion(**overrides):
    # (764.04 + 0) x (1 - 9/100) x 100 = 69527.64
    base = {
        "id": "1", "codigo_contribuyente": "900001", "codigo_predio": "000000001-0001-0001", "nivel": "1",
        "ano_construccion": "2011", "clasificacion_construccion": "1", "material_predominante": "2",
        "estado_de_conservacion": "2", "muros_y_columnas": "C", "techos": "C", "pisos": "I", "puertas_y_ventanas": "F",
        "revestimientos": "I", "banos": "I", "instalaciones_electricas_y_sanitarias": "I", "area_construida_propia": "100",
        "area_construida_comun": "0", "valor_unitario": "764.04", "incremento_5": "0", "depreciacion": "9",
        "valor_unitario_depreciado": "123.45", "valor_total_nivel": "69527.64", "area_comun": "0",
    }
    base.update(overrides)
    return base


def numbered(rows):
    # read_xlsx numbers rows from 2 (1 is the header)
    return [{**r, "_fila": i} for i, r in enumerate(rows, start=2)]


def build(padron_rows, construcciones_rows=()):
    return ipi.build_dataset(numbered(padron_rows), numbered(construcciones_rows), 2026, MODEL, CODIGOS, USOS)


def categorias(data):
    return Counter(r["categoria"] for r in data.reporte)


class BuildSingleOwnerTests(unittest.TestCase):
    def setUp(self):
        self.data = build([padron()], [construccion()])

    def test_contribuyente(self):
        c = self.data.contribuyentes["900001"]
        self.assertEqual(c["codigo"], "900001")
        self.assertEqual((c["tipo_persona"], c["tipo_documento"], c["numero_documento"]), ("NATURAL", "DNI", "40000001"))
        self.assertEqual((c["apellido_paterno"], c["apellido_materno"], c["nombres"]), ("QUISPE", "MAMANI", "ANA"))
        self.assertEqual(c["nombre_completo"], "QUISPE MAMANI ANA")
        self.assertIsNone(c.get("razon_social"))
        self.assertFalse(any(k.startswith("domicilio") for k in c))

    def test_predio(self):
        p = self.data.predios["000000001-0001-0001"]
        self.assertEqual({k: p[k] for k in ("ubigeo", "region", "departamento", "provincia", "distrito")},
                         {"ubigeo": "110101", "region": "COSTA", "departamento": "ICA", "provincia": "ICA", "distrito": "ICA"})
        self.assertEqual((p["tipo_via"], p["via"], p["numero"], p["manzana"], p["lote"]), ("CALLE", "LAS FLORES", "120", "C", "17"))
        self.assertEqual((p["tipo_zona"], p["habilitacion_urbana"]), ("URBANIZACION", "EL JARDIN"))
        self.assertEqual(p["tipo_predio"], "PREDIO URBANO")
        self.assertEqual(p["direccion"], "CLL. LAS FLORES 120 URB. EL JARDIN")
        self.assertEqual((p["codigo_via"], p["codigo_unidad_urbana"], p["codigo_hu_armonizacion"], p["codigo_via_armonizacion"]),
                         ("501", "77", "405", "23250"))

    def test_declaracion(self):
        [d] = self.data.declaraciones
        a = d.attributes
        self.assertEqual((d.codigo_predio, d.codigo_contribuyente), ("000000001-0001-0001", "900001"))
        self.assertEqual((a["anio"], a["secuencia_uso"], a["condicion_propiedad"]), (2026, "001", "PROPIETARIO UNICO"))
        self.assertEqual((a["porcentaje_condominio"], a["valor_autoavaluo"], a["valor_condominio"], a["deduccion"], a["valor_afecto"]),
                         ("100.00", "112247.64", "112247.64", "0.00", "112247.64"))
        self.assertEqual((a["clase_uso"], a["sub_clase_uso"], a["uso"]), ("RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"))
        self.assertEqual((a["area_terreno"], a["area_comun_terreno"], a["area_construida"]), ("120.00", "0.00", "100.00"))
        self.assertEqual((a["arancel"], a["valor_terreno"], a["valor_construccion"], a["valor_otras_instalaciones"]),
                         ("356.00", "42720.00", "69527.64", "0.00"))
        self.assertEqual(a["fecha_adquisicion"], "2004-01-01")

    def test_nivel(self):
        [n] = self.data.niveles
        a = n.attributes
        self.assertEqual((n.codigo_predio, n.codigo_contribuyente), ("000000001-0001-0001", "900001"))
        self.assertEqual((a["tipo_nivel"], a["numero_piso"], a["anio_construccion"]), ("PISO", 1, 2011))
        self.assertNotIn("mes_construccion", a)
        self.assertEqual((a["material"], a["estado_conservacion"], a["clasificacion"]),
                         ("LADRILLO", "BUENO", "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA"))
        self.assertEqual([a[k] for k in ("muros_columnas", "techos", "pisos", "puertas_ventanas", "revestimientos", "banos", "instalaciones")],
                         ["C", "C", "I", "F", "I", "I", "I"])
        self.assertEqual((a["area_construida"], a["area_comun"]), ("100.00", "0.00"))
        self.assertEqual((a["valor_unitario"], a["incremento"], a["porcentaje_depreciacion"], a["valor_total"]),
                         ("764.04", "0.00", "9.00", "69527.64"))
        self.assertFalse(any("depreciado" in k for k in a))

    def test_catalogs_carry_their_codes(self):
        self.assertEqual(list(self.data.vias.values()),
                         [{"codigo": "501", "tipo_via": "CALLE", "nombre": "LAS FLORES", "ubigeo": "110101"}])
        self.assertEqual(list(self.data.unidades.values()),
                         [{"codigo": "77", "tipo_unidad_urbana": "URBANIZACION", "nombre": "EL JARDIN", "ubigeo": "110101"}])

    def test_nothing_to_report_and_the_model_has_every_field(self):
        self.assertEqual(self.data.reporte, [])
        self.assertEqual(self.data.errores, [])


class BuildTests(unittest.TestCase):
    def test_ruc_20_is_juridica_with_razon_social(self):
        data = build([padron(tipo_documento="3", nro_documento="20100000001", nombre_completo="EMPRESA FICTICIA SAC",
                             apellido_paterno="EMPRESA FICTICIA SAC", apellido_materno=None, nombres=None)])
        c = data.contribuyentes["900001"]
        self.assertEqual((c["tipo_persona"], c["tipo_documento"], c["razon_social"]), ("JURIDICA", "RUC", "EMPRESA FICTICIA SAC"))
        self.assertIsNone(c.get("apellido_paterno"))

    def test_a_sucesion_by_its_condicion(self):
        data = build([padron(condicion_predio="6", condicion_propiedad="SUCESION INTESTADA")])
        c = data.contribuyentes["900001"]
        self.assertEqual((c["tipo_persona"], c["tipo_contribuyente"]), ("SUCESION", "SUCESION INDIVISA"))
        self.assertEqual(data.declaraciones[0].attributes["condicion_propiedad"], "PROPIETARIO UNICO")

    def test_a_short_dni_is_padded_and_reported(self):
        data = build([padron(nro_documento="4000001")])
        self.assertEqual(data.contribuyentes["900001"]["numero_documento"], "04000001")
        self.assertEqual(categorias(data)["documento_rellenado"], 1)

    def test_a_document_two_codes_share_stays_with_the_lowest_code(self):
        data = build([padron(codigo_contribuyente="900002", codigo_predio="000000002-0001-0001"), padron()])
        self.assertEqual(data.contribuyentes["900001"]["numero_documento"], "40000001")
        self.assertIsNone(data.contribuyentes["900002"].get("numero_documento"))
        [repetido] = [r for r in data.reporte if r["categoria"] == "documento_repetido"]
        self.assertEqual(repetido["codigo_contribuyente"], "900002")

    def test_the_1900_filler_loads_no_date(self):
        data = build([padron(fecha_adquisicion="1900-01-01 00:00:00")])
        self.assertIsNone(data.declaraciones[0].attributes.get("fecha_adquisicion"))
        self.assertEqual(categorias(data)["fecha_relleno"], 1)

    def test_a_via_code_of_no_via_is_otros(self):
        data = build([padron(tipo_de_via="27", direccion_del_predio_completa="URB. EL JARDIN MZ C LT 17", nombre_de_la_via="EL JARDIN")])
        self.assertEqual(data.predios["000000001-0001-0001"]["tipo_via"], "OTROS")
        self.assertEqual(categorias(data)["codigo_sin_mapeo"], 0)

    def test_an_unmapped_uso_loads_empty_and_is_reported_once_per_code(self):
        data = build([padron(codigo_uso="22"), padron(codigo_predio="000000002-0001-0001", codigo_uso="22")])
        self.assertIsNone(data.declaraciones[0].attributes.get("clase_uso"))
        [row] = [r for r in data.reporte if r["categoria"] == "codigo_sin_mapeo"]
        self.assertIn("codigo_uso '22'", row["detalle"])
        self.assertIn("2 filas", row["detalle"])

    def test_no_address_composes_one(self):
        data = build([padron(direccion_del_predio_completa=None)])
        self.assertEqual(data.predios["000000001-0001-0001"]["direccion"], "CALLE LAS FLORES 120 MZ C LT 17 URBANIZACION EL JARDIN")
        self.assertEqual(categorias(data)["direccion_compuesta"], 1)

    def test_an_unknown_via_code_is_otros_and_reported(self):
        data = build([padron(tipo_de_via="99")])
        self.assertEqual(data.predios["000000001-0001-0001"]["tipo_via"], "OTROS")
        self.assertEqual(categorias(data)["codigo_sin_mapeo"], 1)

    def test_the_lowest_code_is_the_numeric_one(self):
        data = build([padron(codigo_contribuyente="100000"), padron(codigo_contribuyente="99999", codigo_predio="000000002-0001-0001")])
        self.assertEqual(data.contribuyentes["99999"]["numero_documento"], "40000001")
        self.assertIsNone(data.contribuyentes["100000"].get("numero_documento"))

    def test_an_unreadable_date_loads_empty_and_is_reported(self):
        data = build([padron(fecha_adquisicion="ayer")])
        self.assertEqual(len(data.declaraciones), 1)
        self.assertIsNone(data.declaraciones[0].attributes.get("fecha_adquisicion"))
        self.assertEqual(categorias(data)["fecha_invalida"], 1)

    def test_a_predio_without_code_is_rejected(self):
        data = build([padron(codigo_predio=None)])
        self.assertEqual((data.predios, data.declaraciones), ({}, []))
        self.assertNotIn("condominio_100_n", categorias(data))
        self.assertGreaterEqual(categorias(data)["rechazada"], 1)

    def test_a_repeated_row_does_not_make_a_condominio(self):
        data = build([padron(), padron()])
        [d] = data.declaraciones
        self.assertEqual((d.attributes["porcentaje_condominio"], d.attributes["condicion_propiedad"]), ("100.00", "PROPIETARIO UNICO"))
        self.assertEqual(categorias(data)["rechazada"], 1)
        self.assertNotIn("condominio_100_n", categorias(data))

    def test_a_non_numeric_cell_is_rejected_not_fatal(self):
        predio = "000000009-0001-0001"
        data = build([padron(codigo_predio=predio, valor_predio="S/N"),
                      padron(codigo_predio=predio, codigo_contribuyente="900002", nro_documento="40000002", area_construida="x")],
                     [construccion(codigo_predio=predio, area_construida_propia="?"),
                      construccion(codigo_predio=predio, valor_total_nivel="--")])
        self.assertEqual((data.declaraciones, data.niveles), ([], []))
        self.assertEqual(categorias(data)["rechazada"], 4)

    def test_valor_predio_that_is_not_its_parts_is_reported(self):
        self.assertEqual(categorias(build([padron(valor_predio="99999")]))["valor_predio_no_cuadra"], 1)


class CondominioBuildTests(unittest.TestCase):
    PREDIO = "000000009-0001-0001"

    def rows(self, valores, condiciones):
        return [padron(codigo_predio=self.PREDIO, codigo_contribuyente=f"90000{i}", nro_documento=f"4000000{i}",
                       valor_predio=v, condicion_predio=c) for i, (v, c) in enumerate(zip(valores, condiciones), start=1)]

    def test_three_titulares_share_100_and_are_condominos(self):
        data = build(self.rows(["90000", "90000", "90000"], ["5", "5", "9"]))
        attrs = [d.attributes for d in data.declaraciones]
        self.assertEqual([a["porcentaje_condominio"] for a in attrs], ["33.34", "33.33", "33.33"])
        self.assertEqual([a["condicion_propiedad"] for a in attrs], ["CONDOMINO"] * 3)
        self.assertEqual([a["valor_condominio"] for a in attrs], ["30006.00", "29997.00", "29997.00"])
        self.assertEqual([a["valor_afecto"] for a in attrs], ["30006.00", "29997.00", "29997.00"])
        self.assertEqual(categorias(data)["condominio_100_n"], 1)

    def test_different_values_are_to_review(self):
        data = build(self.rows(["90000", "45000"], ["5", "5"]))
        [r] = [r for r in data.reporte if r["categoria"] == "condominio_valores_distintos"]
        self.assertEqual(r["codigo_predio"], self.PREDIO)

    def test_a_propietario_unico_with_others_is_in_conflict(self):
        data = build(self.rows(["90000", "90000"], ["1", "2"]))
        self.assertEqual(categorias(data)["titulares_en_conflicto"], 1)
        self.assertEqual([d.attributes["condicion_propiedad"] for d in data.declaraciones], ["CONDOMINO", "CONDOMINO"])

    def test_a_condomino_alone_holds_it_whole(self):
        data = build(self.rows(["90000"], ["5"]))
        self.assertEqual(data.declaraciones[0].attributes["condicion_propiedad"], "PROPIETARIO UNICO")
        self.assertEqual(data.declaraciones[0].attributes["porcentaje_condominio"], "100.00")
        self.assertEqual(categorias(data)["condomino_solo"], 1)


class BuildNivelesTests(unittest.TestCase):
    def test_a_letter_out_of_the_table_rejects_the_row(self):
        data = build([padron()], [construccion(techos="J")])
        self.assertEqual(data.niveles, [])
        [r] = [r for r in data.reporte if r["categoria"] == "rechazada"]
        self.assertEqual((r["hoja"], r["fila"]), ("construcciones", 2))
        self.assertIn("techos 'J'", r["detalle"])

    def test_a_nivel_without_declaracion_is_rejected(self):
        data = build([padron()], [construccion(codigo_contribuyente="999999")])
        self.assertEqual(data.niveles, [])
        self.assertIn("sin DJ", data.reporte[0]["detalle"])

    def test_the_sotano_and_repeated_sections(self):
        data = build([padron(area_construida="150", valor_area_construida="104291.46")],
                     [construccion(nivel="0", valor_total_nivel="34763.82", area_construida_propia="50"),
                      construccion(), construccion(area_construida_propia="0.5", valor_total_nivel="347.64")])
        self.assertEqual([(n.attributes["tipo_nivel"], n.attributes["numero_piso"]) for n in data.niveles],
                         [("SOTANO", 1), ("PISO", 1), ("PISO", 1)])

    def test_clasificacion_0_loads_empty(self):
        data = build([padron()], [construccion(clasificacion_construccion="0")])
        self.assertIsNone(data.niveles[0].attributes.get("clasificacion"))
        self.assertEqual(categorias(data)["codigo_sin_mapeo"], 1)

    def test_an_unknown_material_rejects_the_row(self):
        data = build([padron()], [construccion(material_predominante="8")])
        self.assertEqual(data.niveles, [])
        self.assertEqual(categorias(data)["rechazada"], 1)

    def test_a_total_off_the_formula_is_reported(self):
        data = build([padron(valor_area_construida="60000")], [construccion(valor_total_nivel="60000")])
        self.assertEqual(len(data.niveles), 1)
        self.assertEqual(categorias(data)["formula_nivel"], 1)

    def test_niveles_that_do_not_add_up_to_the_padron(self):
        data = build([padron(area_construida="120")], [construccion()])
        [r] = [r for r in data.reporte if r["categoria"] == "niveles_vs_padron"]
        self.assertIn("área", r["detalle"])
        self.assertEqual(categorias(build([padron(valor_area_construida="1")], [construccion()]))["niveles_vs_padron"], 1)

    def test_a_rejected_contribuyente_takes_its_declaraciones_and_niveles(self):
        data = build([padron(nombre_completo=None, apellido_paterno=None, apellido_materno=None, nombres=None)], [construccion()])
        self.assertEqual((data.contribuyentes, data.declaraciones, data.niveles), ({}, [], []))
        self.assertEqual(categorias(data)["rechazada"], 3)


class SchemaTests(unittest.TestCase):
    def test_a_model_without_the_new_fields_is_an_error(self):
        model = json.loads(json.dumps(MODEL))
        predio = next(o for o in model["objects"] if o["name"] == "predio")
        predio["fields"] = [f for f in predio["fields"] if f["name"] != "codigo_via"]
        data = ipi.build_dataset(numbered([padron()]), [], 2026, model, CODIGOS, USOS)
        self.assertEqual(data.errores, ["predio: el modelo no tiene el campo codigo_via"])


class LimitTests(unittest.TestCase):
    def test_limit_keeps_whole_predios_and_their_niveles(self):
        rows = numbered([padron(), padron(codigo_predio="000000002-0001-0001"),
                         padron(codigo_contribuyente="900002", nro_documento="40000002")])
        niveles = numbered([construccion(), construccion(codigo_predio="000000002-0001-0001")])
        rows, niveles = ipi.limit_predios(rows, niveles, 1)
        self.assertEqual([r["_fila"] for r in rows], [2, 4])
        self.assertEqual([r["_fila"] for r in niveles], [2])


# ---------------------------------------------------------------------------
# the CLI against a fake Core
# ---------------------------------------------------------------------------

def column_letter(index):
    letters = ""
    index += 1
    while index:
        index, rem = divmod(index - 1, 26)
        letters = chr(65 + rem) + letters
    return letters


def _sheet_xml(header, rows):
    xml_rows = []
    for r_index, values in enumerate([dict(zip(header, header))] + rows, start=1):
        cells = "".join(
            f'<c r="{column_letter(c)}{r_index}" t="inlineStr"><is><t>{escape(values[name])}</t></is></c>'
            for c, name in enumerate(header) if values.get(name) is not None)
        xml_rows.append(f'<row r="{r_index}">{cells}</row>')
    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    return f'<worksheet {ns}><sheetData>{"".join(xml_rows)}</sheetData></worksheet>'


def write_xlsx(path, padron_rows, construcciones_rows):
    """A minimal two-sheet xlsx, every value an inline string, as read_xlsx reads it."""
    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    rel_ns = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("xl/workbook.xml", f'<workbook {ns} {rel_ns}><sheets><sheet name="padron" sheetId="1" r:id="rId1"/>'
                                      '<sheet name="construcciones" sheetId="2" r:id="rId2"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels",
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Target="worksheets/sheet1.xml"/>'
                   '<Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>')
        z.writestr("xl/worksheets/sheet1.xml", _sheet_xml(PADRON, padron_rows))
        z.writestr("xl/worksheets/sheet2.xml", _sheet_xml(CONSTRUCCIONES, construcciones_rows))


SAMPLE_PADRON = [
    padron(),
    # a condominio of two, the second titular a persona jurídica
    padron(codigo_predio="000000002-0001-0001", condicion_predio="5"),
    padron(codigo_predio="000000002-0001-0001", codigo_contribuyente="900002", condicion_predio="5",
           tipo_documento="3", nro_documento="20100000001", nombre_completo="EMPRESA FICTICIA SAC",
           apellido_paterno=None, apellido_materno=None, nombres=None),
    padron(codigo_predio="000000003-0001-0001", codigo_contribuyente="900003", nro_documento="4000003",
           codigo_de_via="502", tipo_de_via="0", nombre_de_la_via="SECTOR NORTE", codigo_uso="20",
           area_construida="0", valor_area_construida="0", valor_predio="42720"),
]
SAMPLE_CONSTRUCCIONES = [
    construccion(),
    # two sections of the same piso
    construccion(codigo_predio="000000002-0001-0001", area_construida_propia="50", valor_total_nivel="34763.82"),
    construccion(codigo_predio="000000002-0001-0001", area_construida_propia="50", valor_total_nivel="34763.82"),
    construccion(codigo_predio="000000002-0001-0001", techos="J"),
]


class CliTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.excel = os.path.join(tmp.name, "padron.xlsx")
        self.report = os.path.join(tmp.name, "reports", "padron_ica.csv")
        write_xlsx(self.excel, SAMPLE_PADRON, SAMPLE_CONSTRUCCIONES)

    def run_cli(self, extra=()):
        args = ["--excel", self.excel, "--core", self.core.base_url, "--report", self.report, "--workers", "2", *extra]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ipi.main(args)
        return code, out.getvalue(), err.getvalue()

    def count(self, name):
        return len(self.core.records.get(name, []))

    def test_dry_run_counts_reports_and_calls_nothing(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])
        for line in ("vías: 2", "unidades urbanas: 1", "contribuyentes: 3", "predios: 3", "declaraciones: 4", "niveles: 3",
                     "rechazadas: 1"):
            self.assertIn(line, out)
        with open(self.report, encoding="utf-8") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual(list(rows[0]), ipi.REPORT_COLUMNS)
        self.assertEqual(Counter(r["categoria"] for r in rows)["rechazada"], 1)
        self.assertIn("condominio_100_n", {r["categoria"] for r in rows})
        # the report names no one: codes only
        self.assertFalse(any("QUISPE" in v or "EMPRESA" in v for r in rows for v in r.values()))

    def test_loads_everything_with_its_relations(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual([self.count(n) for n in ("via", "unidad_urbana", "contribuyente", "predio", "declaracion_predial", "nivel_construccion")],
                         [2, 1, 3, 3, 4, 3])
        by_id = {name: {r["id"]: r["attributes"] for r in self.core.records[name]} for name in ("predio", "contribuyente", "declaracion_predial")}
        for nivel in self.core.records["nivel_construccion"]:
            dj = by_id["declaracion_predial"][nivel["attributes"]["declaracion"]]
            self.assertEqual(by_id["predio"][dj["predio"]]["codigo"][:9], "00000000" + ("1" if nivel["attributes"]["area_construida"] == "100.00" else "2"))
        condominio = [a for a in by_id["declaracion_predial"].values() if by_id["predio"][a["predio"]]["codigo"] == "000000002-0001-0001"]
        self.assertEqual(sorted(a["porcentaje_condominio"] for a in condominio), ["50.00", "50.00"])
        self.assertEqual({a["condicion_propiedad"] for a in condominio}, {"CONDOMINO"})
        juridica = next(a for a in by_id["contribuyente"].values() if a["codigo"] == "900002")
        self.assertEqual((juridica["tipo_persona"], juridica["razon_social"]), ("JURIDICA", "EMPRESA FICTICIA SAC"))
        self.assertIn("done:", out)

    def test_a_second_run_creates_nothing(self):
        self.assertEqual(self.run_cli()[0], 0)
        posts = len([r for r in self.core.requests if r[0] == "POST"])
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(len([r for r in self.core.requests if r[0] == "POST"]) - posts, 1)  # the login
        self.assertEqual(self.count("nivel_construccion"), 3)
        self.assertIn("done: 0 created", out)

    def test_a_second_run_creates_nothing_when_core_answers_numbers(self):
        self.assertEqual(self.run_cli()[0], 0)
        for record in self.core.records["nivel_construccion"]:
            a = record["attributes"]
            a.update(area_construida=float(a["area_construida"]), numero_piso=int(a["numero_piso"]),
                     anio_construccion=int(a["anio_construccion"]))
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.count("nivel_construccion"), 3)

    def test_a_missing_section_is_completed_on_the_next_run(self):
        self.assertEqual(self.run_cli()[0], 0)
        self.core.records["nivel_construccion"].pop()
        self.assertEqual(self.run_cli()[0], 0)
        self.assertEqual(self.count("nivel_construccion"), 3)

    def test_a_document_core_has_under_another_code_is_sent_without_it(self):
        self.core.add_record("contribuyente", {"codigo": "800000", "numero_documento": "40000001", "tipo_persona": "NATURAL",
                                               "tipo_documento": "DNI", "nombre_completo": "OTRA PERSONA"})
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        nuevo = next(r["attributes"] for r in self.core.records["contribuyente"] if r["attributes"]["codigo"] == "900001")
        self.assertNotIn("numero_documento", nuevo)
        self.assertIn("sin número", out)
        with open(self.report, encoding="utf-8") as f:
            [row] = [r for r in csv.DictReader(f) if r["categoria"] == "documento_en_core"]
        self.assertEqual(row["codigo_contribuyente"], "900001")
        self.assertNotIn("40000001", row["detalle"])

    def test_runs_isolated_from_another_directory(self):
        # python3 -I, as an untrusted xlsx is read: neither the cwd nor PYTHONPATH is on sys.path, only the script's own
        result = subprocess.run([sys.executable, "-I", os.path.join(HERE, "import_padron_ica.py"), "--excel", self.excel,
                                 "--report", self.report, "--dry-run"], cwd=os.path.dirname(self.excel),
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("niveles: 3", result.stdout)

    def test_limit_takes_the_first_predios_whole(self):
        code, out, err = self.run_cli(["--dry-run", "--limit", "2"])
        self.assertEqual(code, 0, msg=err)
        self.assertIn("predios: 2", out)
        self.assertIn("declaraciones: 3", out)

    def test_a_core_refusal_exits_1(self):
        self.core.fail_on_record = "predio"
        code, out, err = self.run_cli()
        self.assertEqual(code, 1)
        self.assertIn("predio", err)

    def test_an_older_model_exits_2_and_sends_nothing(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        model = json.loads(json.dumps(MODEL))
        nivel = next(o for o in model["objects"] if o["name"] == "nivel_construccion")
        nivel["fields"] = [f for f in nivel["fields"] if f["name"] != "valor_total"]
        path = os.path.join(tmp.name, "model.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(model, f)
        code, out, err = self.run_cli(["--model", path])
        self.assertEqual(code, 2)
        self.assertIn("nivel_construccion: el modelo no tiene el campo valor_total", err)
        self.assertEqual(self.core.requests, [])
