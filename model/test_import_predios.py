"""Tests for import_predios.py: the pure transformations, the xlsx reader and the load against a fake Core.

Run: cd model && python3 -m unittest -v
"""
import io
import os
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout, redirect_stderr
from xml.sax.saxutils import escape

import import_predios as ip
from fake_core import FakeCore

HEADER = [
    "orden2", "codigo_predio", "secuencia_uso", "sector_manzana", "direccion_predio", "area_construida",
    "longitud_frente", "area_terreno", "ubicacion_parque", "grupo_uso_desc", "cantidad_habitantes", "tipo_pupr_desc",
    "clasificacion_predio_desc", "estado_construccion_desc", "nombre_contribuyente", "tipo_doc", "num_doc",
    "domicilio_fiscal", "autoavaluo_total", "condominio", "deduccion", "autoavaluo_afecto",
]

DOMICILIO = "AVENIDA LA ESPERANZA Mz.: B Lt.: 3 LOTIZACION PARAISO SANTA INES, Dist. PERENE Prov. CHANCHAMAYO Dpto. JUNIN"


def row(**overrides):
    base = {
        "orden2": "1", "codigo_predio": "01-01-0001", "secuencia_uso": "001", "sector_manzana": "01 - 01",
        "direccion_predio": "PASAJE SAN PEDRO Mz.: G Lt.: 21 ASOCIACION DE VIVIENDA 06 DE AGOSTO",
        "area_construida": "45", "longitud_frente": "6", "area_terreno": "108", "ubicacion_parque": "OTRAS UBICACIONES",
        "grupo_uso_desc": "RESIDENCIAL - CASA HABITACION", "cantidad_habitantes": "0", "tipo_pupr_desc": "PU",
        "clasificacion_predio_desc": "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA", "estado_construccion_desc": "TERMINADO",
        "nombre_contribuyente": "MOSQUERA FLORES PEDRO", "tipo_doc": "01", "num_doc": "20529936", "domicilio_fiscal": DOMICILIO,
        "autoavaluo_total": "10611", "condominio": "10611", "deduccion": "530.54999999999995",
        "autoavaluo_afecto": "10080.450000000001",
    }
    base.update(overrides)
    return base


def condomino(**overrides):
    # a co-owner row: no codigo, no secuencia, no orden
    return row(orden2=None, codigo_predio=None, secuencia_uso=None, **overrides)


class SplitNameTests(unittest.TestCase):
    CASES = [
        ("MOSQUERA FLORES PEDRO", ("MOSQUERA", "FLORES", "PEDRO")),
        ("DE LA CRUZ ATIZ JUAN CARLOS", ("DE LA CRUZ", "ATIZ", "JUAN CARLOS")),
        ("MAVILA DE HINOJOSA JACINTA", ("MAVILA", "DE HINOJOSA", "JACINTA")),
        ("VALDEZ MIRANDA FLOR DE MARIA", ("VALDEZ", "MIRANDA", "FLOR DE MARIA")),
        ("IÑIGOS FLORES DE TOSCANO HAYDEE LAZARA", ("IÑIGOS", "FLORES DE TOSCANO", "HAYDEE LAZARA")),
        ("ARIAS VDA. DE TUMAYA ISABEL", ("ARIAS", "VDA. DE TUMAYA", "ISABEL")),
        ("MEZA VDA DE CHAVEZ MACARIA", ("MEZA", "VDA DE CHAVEZ", "MACARIA")),
        ("AUQUI ÑACAYAURI VDA DE PATIÑO TEODORA", ("AUQUI", "ÑACAYAURI VDA DE PATIÑO", "TEODORA")),
        ("LLANTUY ABREGU VDA. DE DE LA CRUZ ANGELICA GERARDA", ("LLANTUY", "ABREGU VDA. DE DE LA CRUZ", "ANGELICA GERARDA")),
        ("KOZLOWSKI . LIDIA NORMA", ("KOZLOWSKI", None, "LIDIA NORMA")),
        ("LA TORRE SIMON EFRAIN EDUARDO", ("LA TORRE", "SIMON", "EFRAIN EDUARDO")),
        ("CHISE ALEGRIA MARIA DEL CARMEN", ("CHISE", "ALEGRIA", "MARIA DEL CARMEN")),
        ("HUARANCCA  DE LA CRUZ EDITH ROSSMERI", ("HUARANCCA", "DE LA CRUZ", "EDITH ROSSMERI")),
        # SANTA is the surname here: joining it to ENRIQUE would leave no names
        ("AGUIRRE SANTA ENRIQUE", ("AGUIRRE", "SANTA", "ENRIQUE")),
        ("CORAL DE DE LA CRUZ NELLY", ("CORAL", "DE DE LA CRUZ", "NELLY")),
    ]

    def test_real_names(self):
        for full, expected in self.CASES:
            with self.subTest(full=full):
                paterno, materno, nombres, _ = ip.split_name(full)
                self.assertEqual((paterno, materno, nombres), expected)

    def test_clean_names_are_not_doubtful(self):
        for full, _ in self.CASES:
            with self.subTest(full=full):
                self.assertIsNone(ip.split_name(full)[3])

    def test_doubtful_names_carry_a_reason(self):
        for full in [
            "PELAEZ Y RAMIREZ SIMON RIGOBERTO",
            "ALIAGA ALARCON ANDERSON ABEL, WEINER ALINDER",
            "OSORIO EMLIO SN",
            "QUISPE MAMANI",
            # names first: the widow's surname eats everything, nothing is left for nombres
            "GUILLERMINA CRISPIN VDA DE MENESES",
        ]:
            with self.subTest(full=full):
                self.assertIsNotNone(ip.split_name(full)[3])


class ClassifyPersonTests(unittest.TestCase):
    def test_ruc_is_juridica(self):
        self.assertEqual(ip.classify_person("06", "OROCOM SAC"), "JURIDICA")
        self.assertEqual(ip.classify_person("06", "MUNICIPALIDAD DISTRITAL DE PERENE"), "JURIDICA")

    def test_sucesiones(self):
        self.assertEqual(ip.classify_person("08", "SUCESION INDIVISA CONDORI PAYTAN PABLO"), "SUCESION")
        self.assertEqual(ip.classify_person("08", "MALDONADO GARCIA CATALINA"), "SUCESION")
        self.assertEqual(ip.classify_person("01", "SUC. IND. VELASQUEZ CAMPO MODESTO"), "SUCESION")

    def test_institution_without_ruc_is_juridica(self):
        self.assertEqual(ip.classify_person("00", "ASOCIACION DE VIVIENDA TAHUANTINSUYO"), "JURIDICA")
        self.assertEqual(ip.classify_person("00", "IGLESIA EVANGELICA PENTECOSTAL DE JESUCRISTO"), "JURIDICA")

    def test_people_are_natural(self):
        self.assertEqual(ip.classify_person("01", "CAJA MUÑOZ NORIS TERESA"), "NATURAL")
        self.assertEqual(ip.classify_person("00", "MENDOZA TAYPE TEODOCIO"), "NATURAL")
        self.assertEqual(ip.classify_person("04", "KOZLOWSKI . LIDIA NORMA"), "NATURAL")


class ParseAddressTests(unittest.TestCase):
    def test_via_manzana_lote_habilitacion(self):
        self.assertEqual(
            ip.parse_address("PASAJE SAN PEDRO Mz.: G Lt.: 21 ASOCIACION DE VIVIENDA 06 DE AGOSTO"),
            {"via": "PASAJE SAN PEDRO", "numero": None, "manzana": "G", "lote": "21", "kilometro": None,
             "habilitacion_urbana": "ASOCIACION DE VIVIENDA 06 DE AGOSTO"},
        )

    def test_number_block_dpto_int(self):
        parsed = ip.parse_address(
            "AVENIDA LA ESPERANZA Nro.: 415 Mz.: A Lt.: 14 Block.: 2 Dpto.: 2 Int.: 2 LOTIZACION PARAISO SANTA INES")
        self.assertEqual(parsed["via"], "AVENIDA LA ESPERANZA")
        self.assertEqual(parsed["numero"], "415")
        self.assertEqual(parsed["lote"], "14")
        self.assertEqual(parsed["habilitacion_urbana"], "LOTIZACION PARAISO SANTA INES")

    def test_nro_alt_is_not_the_number(self):
        parsed = ip.parse_address("CALLE SAN PEDRO - ISAIAS ALDORADIN Nro.Alt.: 2 Mz.: B Lt.: 3 ASOCIACION DE VIVIENDA ISAIAS ALDORADIN")
        self.assertIsNone(parsed["numero"])
        self.assertEqual(parsed["via"], "CALLE SAN PEDRO - ISAIAS ALDORADIN")

    def test_rural_without_lote(self):
        self.assertEqual(
            ip.parse_address(" CARROZABLE MIRICHARO Mz.: 99 ANEXO - CENTRO POBLADO MIRICHARO"),
            {"via": "CARROZABLE MIRICHARO", "numero": None, "manzana": "99", "lote": None, "kilometro": None,
             "habilitacion_urbana": "ANEXO - CENTRO POBLADO MIRICHARO"},
        )

    def test_no_tags_is_all_via(self):
        self.assertEqual(ip.parse_address("SECTOR IPANEMA")["via"], "SECTOR IPANEMA")

    def test_kilometro(self):
        parsed = ip.parse_address("AVENIDA CIRCUNVALACION - II- III MESETA Mz.: D Lt.: 20 Km.: 23.5 CERCADO III MESETA")
        self.assertEqual(parsed["kilometro"], "23.5")
        self.assertEqual(parsed["habilitacion_urbana"], "CERCADO III MESETA")
        self.assertIsNone(ip.parse_address("SECTOR IPANEMA")["kilometro"])


class SplitUbicacionTests(unittest.TestCase):
    def test_types_split_off_the_names(self):
        self.assertEqual(
            ip.split_ubicacion({"via": "JIRON LIMA", "numero": "12", "habilitacion_urbana": "03-B CERCADO III MESETA"}),
            {"tipo_via": "JIRON", "via": "LIMA", "numero": "12", "tipo_zona": "CERCADO", "habilitacion_urbana": "III MESETA"},
        )
        self.assertEqual(ip.split_ubicacion({"via": "JR. LIMA"}), {"tipo_via": "JIRON", "via": "LIMA"})

    def test_no_type_is_otros_and_nothing_stays_nothing(self):
        self.assertEqual(ip.split_ubicacion({"via": "SECTOR IPANEMA"}), {"tipo_via": "OTROS", "via": "SECTOR IPANEMA"})
        self.assertEqual(ip.split_ubicacion({"via": None, "habilitacion_urbana": None}), {"via": None, "habilitacion_urbana": None})

    def test_the_padron_misspells_carrozable(self):
        for via in ["CORRAZABLE TUPAC AMARU", "CORROZABLE TUPAC AMARU", "CARROZBLE TUPAC AMARU", "CACARROZABLE TUPAC AMARU"]:
            with self.subTest(via=via):
                self.assertEqual(ip.split_tipo(via, ip.TIPOS_VIA), ("CARROZABLE", "TUPAC AMARU"))

    def test_every_abbreviation_of_the_address_is_read_back(self):
        # the tables Reglas.kt (srtm-backend) and forms/direccion.ts (srtm-ui) write addresses with
        vias = {"AVENIDA": "AV.", "CALLE": "CA.", "JIRON": "JR.", "PASAJE": "PSJE.", "PROLONGACION": "PROL.", "CARRETERA": "CARR."}
        unidades = {"ASENTAMIENTO HUMANO": "AA.HH.", "ASOCIACION DE VIVIENDA": "AA.VV.", "CENTRO POBLADO": "C.P.", "URBANIZACION": "URB."}
        for tipo, sigla in vias.items():
            with self.subTest(sigla=sigla):
                self.assertEqual(ip.split_tipo(f"{sigla} LOS PINOS", ip.TIPOS_VIA), (tipo, "LOS PINOS"))
        for tipo, sigla in unidades.items():
            with self.subTest(sigla=sigla):
                self.assertEqual(ip.split_tipo(f"{sigla} LOS PINOS", ip.TIPOS_UNIDAD_URBANA, anywhere=True), (tipo, "LOS PINOS"))


class SecuenciaUsoTests(unittest.TestCase):
    def test_three_digits_as_the_padron(self):
        self.assertEqual(ip.secuencia_uso("1"), "001")
        self.assertEqual(ip.secuencia_uso(" 12 "), "012")
        self.assertEqual(ip.secuencia_uso("002"), "002")
        self.assertEqual(ip.secuencia_uso("0001"), "0001")
        self.assertEqual(ip.secuencia_uso(None), "001")
        self.assertEqual(ip.secuencia_uso(""), "001")
        # not a number: kept as written
        self.assertEqual(ip.secuencia_uso("A"), "A")


class ParseDomicilioTests(unittest.TestCase):
    def test_splits_the_location_suffix(self):
        self.assertEqual(ip.parse_domicilio(DOMICILIO), {
            "domicilio_fiscal": "AVENIDA LA ESPERANZA Mz.: B Lt.: 3 LOTIZACION PARAISO SANTA INES",
            "domicilio_distrito": "PERENE",
            "domicilio_provincia": "CHANCHAMAYO",
            "domicilio_departamento": "JUNIN",
        })

    def test_spaced_comma_and_two_word_district(self):
        parsed = ip.parse_domicilio("JIRON LOS INCAS Nro.: 415 SIN HABILITACION , Dist. VILLA RICA Prov. OXAPAMPA Dpto. PASCO")
        self.assertEqual(parsed["domicilio_fiscal"], "JIRON LOS INCAS Nro.: 415 SIN HABILITACION")
        self.assertEqual(parsed["domicilio_distrito"], "VILLA RICA")

    def test_without_suffix_keeps_the_text(self):
        parsed = ip.parse_domicilio("CALLE LIMA 123")
        self.assertEqual(parsed["domicilio_fiscal"], "CALLE LIMA 123")
        self.assertIsNone(parsed["domicilio_distrito"])


class CleanTests(unittest.TestCase):
    def test_decimal_drops_float_noise(self):
        self.assertEqual(ip.clean_decimal("613.82000000000005"), "613.82")
        self.assertEqual(ip.clean_decimal("10611"), "10611.00")
        self.assertIsNone(ip.clean_decimal(""))
        self.assertIsNone(ip.clean_decimal("  "))

    def test_int(self):
        self.assertEqual(ip.clean_int("0"), 0)
        self.assertEqual(ip.clean_int("3.0"), 3)
        self.assertIsNone(ip.clean_int(None))

    def test_text_collapses_spaces(self):
        self.assertEqual(ip.clean_text("  INSTITUCION EDUCATIVA INICIAL  N° 762 "), "INSTITUCION EDUCATIVA INICIAL N° 762")
        self.assertIsNone(ip.clean_text("   "))


class BuildDatasetTests(unittest.TestCase):
    def build(self, rows):
        # read_xlsx numbers rows from 2 (1 is the header)
        rows = [{**r, "_fila": i} for i, r in enumerate(rows, start=2)]
        return ip.build_dataset(rows, anio=2026, enums=ip.load_enums(ip.default_model_path()))

    def test_single_owner(self):
        data = self.build([row()])
        self.assertEqual(data.problems, [])
        contrib = data.contribuyentes["20529936"]
        self.assertEqual(contrib["tipo_documento"], "DNI")
        self.assertEqual(contrib["tipo_persona"], "NATURAL")
        self.assertEqual((contrib["apellido_paterno"], contrib["apellido_materno"], contrib["nombres"]), ("MOSQUERA", "FLORES", "PEDRO"))
        self.assertIsNone(contrib["razon_social"])
        self.assertEqual(contrib["domicilio_distrito"], "PERENE")

        predio = data.predios["01-01-0001"]
        self.assertEqual((predio["sector_catastral"], predio["manzana_catastral"]), ("01", "01"))
        self.assertEqual(predio["condicion"], "URBANO")
        self.assertEqual(predio["manzana"], "G")
        # the ubicación as the srtm's form edits it; direccion keeps the padrón's text
        self.assertEqual((predio["tipo_via"], predio["via"]), ("PASAJE", "SAN PEDRO"))
        self.assertEqual((predio["tipo_zona"], predio["habilitacion_urbana"]), ("ASOCIACION DE VIVIENDA", "06 DE AGOSTO"))
        self.assertEqual(predio["direccion"], "PASAJE SAN PEDRO Mz.: G Lt.: 21 ASOCIACION DE VIVIENDA 06 DE AGOSTO")

        [decl] = data.declaraciones
        self.assertEqual(decl.attributes["condicion_propiedad"], "PROPIETARIO UNICO")
        self.assertEqual(decl.attributes["porcentaje_condominio"], "100.00")
        self.assertEqual(decl.attributes["valor_afecto"], "10080.45")
        self.assertEqual(decl.attributes["anio"], 2026)
        self.assertEqual((decl.codigo_predio, decl.numero_documento), ("01-01-0001", "20529936"))
        self.assertEqual(decl.attributes["secuencia_uso"], "001")

    def test_secuencia_has_three_digits(self):
        [decl] = self.build([row(secuencia_uso="2")]).declaraciones
        self.assertEqual(decl.attributes["secuencia_uso"], "002")

    def test_co_owners_inherit_the_predio(self):
        data = self.build([
            row(codigo_predio="01-16-0003", autoavaluo_total="60145.2", condominio="30072.6"),
            condomino(nombre_contribuyente="GOMEZ CONDO ZONIA", num_doc="28294013", autoavaluo_total="60145.2", condominio="30072.6"),
            row(codigo_predio="01-16-0004", num_doc="11111111"),
        ])
        self.assertEqual(data.problems, [])
        first, second, third = data.declaraciones
        self.assertEqual(second.codigo_predio, "01-16-0003")
        self.assertEqual(second.attributes["secuencia_uso"], "001")
        self.assertEqual([d.attributes["condicion_propiedad"] for d in data.declaraciones], ["CONDOMINO", "CONDOMINO", "PROPIETARIO UNICO"])
        self.assertEqual(second.attributes["porcentaje_condominio"], "50.00")
        self.assertEqual(len(data.predios), 2)
        self.assertEqual(len(data.contribuyentes), 3)

    def test_juridica_gets_razon_social(self):
        data = self.build([row(tipo_doc="06", num_doc="20603080590", nombre_contribuyente="OROCOM SAC")])
        contrib = data.contribuyentes["20603080590"]
        self.assertEqual(contrib["razon_social"], "OROCOM SAC")
        self.assertIsNone(contrib["apellido_paterno"])

    def test_clasificacion_is_shortened_and_empty_is_none(self):
        data = self.build([
            row(clasificacion_predio_desc="TIENDAS,DEPOSITOS,CENTROS DE RECREACION O ESPARCIMIENTO ,CLUB SOCIALES O INSTITUCIONES"),
            row(codigo_predio="01-01-0002", clasificacion_predio_desc=None, autoavaluo_total="0", condominio="0"),
        ])
        self.assertEqual(data.declaraciones[0].attributes["clasificacion"], "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES")
        self.assertIsNone(data.declaraciones[1].attributes["clasificacion"])
        self.assertIsNone(data.declaraciones[1].attributes["porcentaje_condominio"])

    def test_the_grupo_de_uso_is_the_srtm_clase(self):
        # wasichai/srtm-backend#31: the ten grupos are the catalog's ten clases; only the residential one says more
        [casa] = self.build([row()]).declaraciones
        self.assertEqual(
            {k: casa.attributes[k] for k in ("clase_uso", "sub_clase_uso", "uso")},
            {"clase_uso": "RESIDENCIAL", "sub_clase_uso": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"},
        )
        [terreno] = self.build([row(grupo_uso_desc="TERRENO")]).declaraciones
        self.assertEqual(
            {k: terreno.attributes[k] for k in ("clase_uso", "sub_clase_uso", "uso")},
            {"clase_uso": "TERRENO", "sub_clase_uso": None, "uso": None},
        )
        [sin_uso] = self.build([row(grupo_uso_desc=None)]).declaraciones
        self.assertEqual((sin_uso.attributes["clase_uso"], sin_uso.attributes["uso"]), (None, None))

    def test_every_grupo_lands_on_options_of_the_model(self):
        enums = ip.load_enums(ip.default_model_path())
        self.assertEqual(len(ip.USOS_DEL_PADRON), 10)
        for grupo in ip.USOS_DEL_PADRON:
            with self.subTest(grupo=grupo):
                usos = ip.uso_del_padron(grupo)
                self.assertIn(usos["clase_uso"], enums["clase_uso"])
                self.assertTrue(usos["sub_clase_uso"] is None or usos["sub_clase_uso"] in enums["sub_clase_uso"])
                self.assertTrue(usos["uso"] is None or usos["uso"] in enums["uso"])
        self.assertIsNone(ip.uso_del_padron("SPA"))
        self.assertIsNone(ip.uso_del_padron("CASA HABITACIÓN"))

    def test_unknown_values_are_problems(self):
        data = self.build([row(tipo_doc="99", grupo_uso_desc="SPA")])
        self.assertTrue(any("tipo_doc '99'" in p for p in data.problems))
        self.assertTrue(any("grupo_uso_desc 'SPA'" in p for p in data.problems))

    def test_co_owner_before_any_predio_is_a_problem(self):
        data = self.build([condomino()])
        self.assertTrue(any("no predio above" in p for p in data.problems))

    def test_repeated_declaration_is_a_problem(self):
        data = self.build([row(), row()])
        self.assertTrue(any("repeated" in p for p in data.problems))

    def test_doubtful_names_are_collected(self):
        data = self.build([row(nombre_contribuyente="OSORIO EMLIO SN")])
        self.assertEqual(len(data.doubtful_names), 1)

    def test_limit_keeps_co_owners_with_their_predio(self):
        rows = [row(codigo_predio="01-16-0003"), condomino(num_doc="2"), row(codigo_predio="01-16-0004", num_doc="3")]
        self.assertEqual(len(ip.limit_rows(rows, 1)), 2)
        self.assertEqual(len(ip.limit_rows(rows, 3)), 3)


def write_xlsx(path, rows):
    """A minimal xlsx: header row as shared strings, values as inline strings and numbers."""
    shared = HEADER
    cells = []
    for r_index, values in enumerate([None] + rows, start=1):
        xml_cells = []
        for c_index, name in enumerate(HEADER):
            ref = f"{column_letter(c_index)}{r_index}"
            if values is None:
                xml_cells.append(f'<c r="{ref}" t="s"><v>{c_index}</v></c>')
                continue
            value = values.get(name)
            if value is None:
                continue
            try:
                float(value)
                is_number = not value.startswith("0") or value == "0" or value.startswith("0.")
            except ValueError:
                is_number = False
            if is_number:
                xml_cells.append(f'<c r="{ref}"><v>{value}</v></c>')
            else:
                xml_cells.append(f'<c r="{ref}" t="inlineStr"><is><t>{escape(value)}</t></is></c>')
        cells.append(f'<row r="{r_index}">{"".join(xml_cells)}</row>')
    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    rel_ns = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("xl/workbook.xml", f'<workbook {ns} {rel_ns}><sheets><sheet name="Hoja1" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr(
            "xl/_rels/workbook.xml.rels",
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Target="worksheets/sheet1.xml" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/></Relationships>',
        )
        z.writestr("xl/sharedStrings.xml", f'<sst {ns}>{"".join(f"<si><t>{escape(s)}</t></si>" for s in shared)}</sst>')
        z.writestr("xl/worksheets/sheet1.xml", f'<worksheet {ns}><sheetData>{"".join(cells)}</sheetData></worksheet>')


def column_letter(index):
    letters = ""
    index += 1
    while index:
        index, rem = divmod(index - 1, 26)
        letters = chr(65 + rem) + letters
    return letters


SAMPLE = [
    row(codigo_predio="01-16-0003", autoavaluo_total="60145.2", condominio="30072.6", deduccion="1503.63", autoavaluo_afecto="28568.97"),
    condomino(nombre_contribuyente="GOMEZ CONDO ZONIA", num_doc="28294013", autoavaluo_total="60145.2", condominio="30072.6",
              deduccion="1503.63", autoavaluo_afecto="28568.97"),
    row(codigo_predio="06-11-0007", num_doc="20040062", nombre_contribuyente="MEZA FERNANDEZ MARTIR", tipo_doc="01"),
    row(codigo_predio="06-11-0007", secuencia_uso="002", num_doc="20040062", nombre_contribuyente="MEZA FERNANDEZ MARTIR", tipo_doc="01"),
]


class ReadXlsxTests(unittest.TestCase):
    def test_reads_rows_by_header_with_row_numbers(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "predios.xlsx")
            write_xlsx(path, SAMPLE)
            rows = ip.read_xlsx(path)
        self.assertEqual(len(rows), 4)
        self.assertEqual(rows[0]["codigo_predio"], "01-16-0003")
        self.assertEqual(rows[0]["tipo_doc"], "01")
        self.assertEqual(rows[0]["_fila"], 2)
        self.assertIsNone(rows[1]["codigo_predio"])
        self.assertEqual(rows[1]["nombre_contribuyente"], "GOMEZ CONDO ZONIA")


class ImportCliTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.excel = os.path.join(tmp.name, "predios.xlsx")
        self.report = os.path.join(tmp.name, "reports", "nombres_dudosos.csv")
        write_xlsx(self.excel, SAMPLE)

    def run_cli(self, extra=()):
        args = ["--excel", self.excel, "--core", self.core.base_url, "--report", self.report, "--workers", "2", *extra]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ip.main(args)
        return code, out.getvalue(), err.getvalue()

    def test_dry_run_counts_and_calls_nothing(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.requests, [])
        self.assertIn("contribuyentes: 3", out)
        self.assertIn("predios: 2", out)
        self.assertIn("declaraciones: 4", out)
        self.assertTrue(os.path.exists(self.report))

    def test_loads_with_relations_then_is_idempotent(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(len(self.core.records["contribuyente"]), 3)
        self.assertEqual(len(self.core.records["predio"]), 2)
        self.assertEqual(len(self.core.records["declaracion_predial"]), 4)

        ids = {r["attributes"]["numero_documento"]: r["id"] for r in self.core.records["contribuyente"]}
        predios = {r["attributes"]["codigo"]: r["id"] for r in self.core.records["predio"]}
        gomez = next(d for d in self.core.records["declaracion_predial"] if d["attributes"]["contribuyente"] == ids["28294013"])
        self.assertEqual(gomez["attributes"]["predio"], predios["01-16-0003"])
        self.assertEqual(gomez["attributes"]["porcentaje_condominio"], "50.00")
        self.assertIn("done: 9 created, 0 skipped", out)

        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertIn("done: 0 created, 9 skipped", out)
        self.assertEqual(len(self.core.records["declaracion_predial"]), 4)

    def test_a_refused_record_stops_with_its_row(self):
        self.core.fail_on_record = "predio"
        code, out, err = self.run_cli()
        self.assertEqual(code, 1)
        self.assertIn("fila", err)
        self.assertIn("boom-record", err)
        self.assertNotIn("declaracion_predial", self.core.records)

    def test_problems_abort_before_calling_core(self):
        write_xlsx(self.excel, [row(tipo_doc="99")])
        code, out, err = self.run_cli()
        self.assertEqual(code, 2)
        self.assertIn("tipo_doc '99'", err)
        self.assertEqual(self.core.requests, [])


if __name__ == "__main__":
    unittest.main()
