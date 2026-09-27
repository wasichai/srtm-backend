"""The tipos de unidad urbana are the catastro fiscal's TIPO_UU domain (data/tipos_unidad_urbana.csv), and the importers
read the padrón's words for them the same way (wasichai/srtm-backend#34).

Run: cd model && python3 -m unittest -v test_tipos_unidad_urbana
"""
import csv
import os
import unittest

import import_catalogos as ic
import import_predios as ip
import normalizar_padron as npad
from apply import enum_option_valid
from test_apply import load_model

HERE = os.path.dirname(os.path.abspath(__file__))


def read_csv():
    with open(os.path.join(HERE, "data", "tipos_unidad_urbana.csv"), encoding="utf-8") as f:
        return list(csv.DictReader(f))


def zona(text):
    """A padrón's habilitación as import_predios.py stores it: (tipo_zona, habilitacion_urbana)."""
    split = ip.split_ubicacion({"habilitacion_urbana": text})
    return split.get("tipo_zona"), split["habilitacion_urbana"]


class TiposUuTests(unittest.TestCase):
    def test_the_file_is_the_domain_with_its_two_typos_fixed(self):
        rows = read_csv()
        self.assertEqual(len(rows), 43)
        self.assertEqual(len({r["codigo"] for r in rows}), 43)
        self.assertTrue(all(len(r["codigo"]) == 2 and r["codigo"].isdigit() for r in rows))
        nombres = {r["codigo"]: r["nombre"] for r in rows}
        self.assertEqual((nombres["56"], nombres["38"]), ("RESIDENCIAL", "PROGRAMA MUNICIPAL DE VIVIENDA"))
        self.assertEqual({r["codigo"]: r["abreviatura"] for r in rows if r["abreviatura"] == "ASOC.VIS."}, {"48": "ASOC.VIS.", "53": "ASOC.VIS."})
        for r in rows:
            with self.subTest(nombre=r["nombre"]):
                self.assertTrue(enum_option_valid(r["nombre"]))
                self.assertTrue(r["abreviatura"])

    def test_the_model_lists_them_in_the_srtms_order(self):
        # page 5 sorts the list: AGRUPACION, ASENTAMIENTO HUMANO, ASOCIACION, ASOCIACION DE VIVIENDA…
        nombres = [r["nombre"] for r in read_csv()]
        self.assertEqual(nombres, sorted(nombres))
        self.assertEqual(load_model()["enums"]["tipo_unidad_urbana"], nombres)


class PadronTests(unittest.TestCase):
    def test_every_type_is_read_whole_and_abbreviated(self):
        for r in read_csv():
            with self.subTest(nombre=r["nombre"]):
                self.assertEqual(zona(f"{r['nombre']} LOS PINOS"), (r["nombre"], "LOS PINOS"))
                # 48 and 53 share ASOC.VIS.: it reads back as the first, DE INTERES SOCIAL
                esperado = "ASOCIACION DE VIVIENDA DE INTERES SOCIAL" if r["abreviatura"] == "ASOC.VIS." else r["nombre"]
                self.assertEqual(zona(f"{r['abreviatura']} LOS PINOS"), (esperado, "LOS PINOS"))

    def test_an_anexo_is_a_centro_poblado(self):
        self.assertEqual(zona("ANEXO - CENTRO POBLADO MIRICHARO"), ("CENTRO POBLADO", "MIRICHARO"))
        self.assertEqual(zona("ANEXO VILLA ANASHIRONI"), ("CENTRO POBLADO", "VILLA ANASHIRONI"))
        # only its own type leaves the name, and only written whole: S. is SAN there
        self.assertEqual(zona("ANEXO - CENTRO POBLADO SECTOR PERENE RURAL"), ("CENTRO POBLADO", "SECTOR PERENE RURAL"))
        self.assertEqual(zona("ANEXO S. JUAN DE KIMARINI"), ("CENTRO POBLADO", "S. JUAN DE KIMARINI"))

    def test_a_habilitacion_urbana_is_an_urbanizacion_unless_its_name_says_its_type(self):
        self.assertEqual(zona("HABILITACION URBANA LA LUZ DEL VALLE DE PICHANAKI"), ("URBANIZACION", "LA LUZ DEL VALLE DE PICHANAKI"))
        self.assertEqual(zona("HABILITACION URBANA 10 DE OCTUBRE"), ("URBANIZACION", "10 DE OCTUBRE"))
        self.assertEqual(zona("- 1 HABILITACION URBANA 10 DE OCTUBRE"), ("URBANIZACION", "10 DE OCTUBRE"))
        self.assertEqual(zona("HABILITACION URBANA SECTOR 10 DE OCTUBRE"), ("SECTOR", "10 DE OCTUBRE"))
        self.assertEqual(zona("HABILITACION URBANA RESIDENCIAL IPANEMA"), ("RESIDENCIAL", "IPANEMA"))

    def test_a_centro_urbano_informal_is_a_posesion_informal_unless_its_name_says_its_type(self):
        self.assertEqual(zona("CENTRO URBANO INFORMAL VISTA ALEGRE"), ("POSESION INFORMAL", "VISTA ALEGRE"))
        self.assertEqual(zona("1A CENTRO URBANO INFORMAL SECTOR LA ALBORADA"), ("SECTOR", "LA ALBORADA"))

    def test_a_type_written_whole_comes_before_an_abbreviation(self):
        # the padrón's LOT is its lote, not a LOTIZACION: it writes its types whole
        self.assertEqual(zona("LOT 2A CENTRO POBLADO SAN FERNANDO DE KIVINAKI"), ("CENTRO POBLADO", "SAN FERNANDO DE KIVINAKI"))
        self.assertEqual(zona("03-B URB. LOS PINOS"), ("URBANIZACION", "LOS PINOS"))

    def test_no_type_is_no_type(self):
        # OTROS is no tipo de unidad urbana: the name stays whole, with no type
        self.assertEqual(zona("VILLA SOL"), (None, "VILLA SOL"))
        self.assertEqual(zona("COMUNIDAD NATIVA HUACAMAYO"), (None, "COMUNIDAD NATIVA HUACAMAYO"))

    def test_every_type_read_is_an_option(self):
        self.assertEqual({t for _, t in ip.TIPOS_UNIDAD_URBANA}, set(load_model()["enums"]["tipo_unidad_urbana"]))

    def test_the_catalog_takes_the_same_types_and_leaves_out_what_has_none(self):
        rows = [{"direccion_predio": f"CALLE A Mz.: B Lt.: 1 {habilitacion}"} for habilitacion in (
            "ANEXO - CENTRO POBLADO MIRICHARO", "HABILITACION URBANA SECTOR 10 DE OCTUBRE", "VILLA SOL",
        )]
        _, unidades = ic.catalogs_from_rows(rows, "120302")
        self.assertEqual(unidades, [
            {"tipo_unidad_urbana": "CENTRO POBLADO", "nombre": "MIRICHARO", "ubigeo": "120302"},
            {"tipo_unidad_urbana": "SECTOR", "nombre": "10 DE OCTUBRE", "ubigeo": "120302"},
        ])

    def test_normalizar_padron_takes_the_same_types(self):
        self.assertEqual(npad.normalizar_predio({"habilitacion_urbana": "ANEXO - CENTRO POBLADO MIRICHARO"}),
                         {"tipo_zona": "CENTRO POBLADO", "habilitacion_urbana": "MIRICHARO"})
        # the padrón's word is not a lot's leftover
        self.assertEqual(npad.notas_predio({"habilitacion_urbana": "ANEXO - CENTRO POBLADO MIRICHARO"}), [])
        self.assertEqual(npad.notas_predio({"habilitacion_urbana": "- 1 HABILITACION URBANA 10 DE OCTUBRE"}),
                         ["se descartan restos de lote de la zona: 1"])
        self.assertEqual(npad.normalizar_predio({"habilitacion_urbana": "VILLA SOL"}), {})
        self.assertEqual(npad.notas_predio({"habilitacion_urbana": "VILLA SOL"}), ["zona sin tipo reconocido: queda sin tipo"])


if __name__ == "__main__":
    unittest.main()
