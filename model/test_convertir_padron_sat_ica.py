"""Tests for convertir_padron_sat_ica.py: the padrón SAT depurado and the construcciones of the reporte de subvaluados
into the two-sheet workbook of import_padron_ica.py.

Every row here is made up: the real files carry names and documents and never enter the repo.

Run: cd model && python3 -m unittest -v
"""
import io
import os
import stat
import tempfile
import unittest
import zipfile
from collections import Counter
from contextlib import redirect_stderr, redirect_stdout
from xml.sax.saxutils import escape

import convertir_padron_sat_ica as conv
import import_padron_ica as ipi
from import_predios import read_xlsx

CODIGOS = ipi.load_codigos(ipi.default_codigos_path())
LETRAS = conv.load_letras(conv.default_model_path())

SAT_HEADER = [
    "UNICOS", "UBIGEO", "COD_PREDIO", "CODHHUU_ARMO", "TIPO_UNIDAD URBANA_ARMO", "NOMBRE_UNIDAD URBANA_ARMO",
    "CODVIA_ARMO", "TIPO_VIA_ARMO", "NOMBRE_VIA_ARMO", "MANZANA_URBANA_ARMO", "LOTE_URBANO_ARMO", "MANZANA_URBANA",
    "LOTE_URBANO", "SUB_LOTE", "N_MUNICIPAL", "PISO", "TIPO_EDIFICACIÓN", "N_EDIFICACION", "TIPO_INTERIOR",
    "N_INTERIOR", "N_PUERTA", "KM", "N_PARTIDA", "AREA DE TERRENO", "TIP_DOC", "N_DOC", "COD_CONTRIB", "NOMBRES",
    "RazonSocial", "A_PATERNO", "A_MATERNO", "ESTADO", "Dirección_completa_del_predio", "Codigo_de_via_MUNICIPAL",
    "Codigo_Habilitación_Urbana", "Condición_predio", "Fecha_Adquisición", "Código_uso", "Descripcion_del_Uso",
    "Condición_propiedad", "__Condomin", "Área_terreno_Común", "Arancel", "Valor_Terreno", "Área_construida",
    "Area_construida_Común", "Valor_Área_Construida", "Valor_Otras_Instalaciones", "Valor_Predio",
    "Numero_de_Partida_Registral", "domicilio_fiscal",
]
CONSTRUCCION_HEADER = [
    "id_lote", "pricos", "cod_predio", "codigo_unidad_catastro", "tipo_unidad", "es_unidad_comun", "estado_vinculo",
    "nivel_rentas", "ano_rentas", "material_rentas", "estado_conservacion_rentas", "muros_rentas", "techos_rentas",
    "pisos_rentas", "puertas_rentas", "revestimientos_rentas", "banos_rentas", "instalaciones_rentas",
    "area_construida_nivel_rentas",
]


def sat(**overrides):
    """A made-up row of the padrón SAT depurado: a casa habitación of one propietario único."""
    row = {
        "UNICOS": "UNICO", "UBIGEO": "110101", "COD_PREDIO": "000000001-0001-0001", "CODHHUU_ARMO": "77",
        "TIPO_UNIDAD URBANA_ARMO": "URB.", "NOMBRE_UNIDAD URBANA_ARMO": "LOS FICTICIOS", "CODVIA_ARMO": "880",
        "TIPO_VIA_ARMO": "CA.", "NOMBRE_VIA_ARMO": "LAS PRUEBAS", "MANZANA_URBANA_ARMO": "Z", "LOTE_URBANO_ARMO": "9",
        "MANZANA_URBANA": "A", "LOTE_URBANO": "5", "SUB_LOTE": None, "N_MUNICIPAL": "123", "PISO": None,
        "TIPO_EDIFICACIÓN": None, "N_EDIFICACION": None, "TIPO_INTERIOR": None, "N_INTERIOR": None, "N_PUERTA": None,
        "KM": None, "N_PARTIDA": None, "AREA DE TERRENO": "120.5", "TIP_DOC": "1", "N_DOC": "40000001",
        "COD_CONTRIB": "900001", "NOMBRES": "ANA", "RazonSocial": None, "A_PATERNO": "PRUEBA", "A_MATERNO": "FICTICIA",
        "ESTADO": "VINCULADO", "Dirección_completa_del_predio": "CA. LAS PRUEBAS 123", "Codigo_de_via_MUNICIPAL":
        "0000000001-01", "Codigo_Habilitación_Urbana": "301", "Condición_predio": "1", "Fecha_Adquisición": "38000",
        "Código_uso": "4", "Descripcion_del_Uso": "CASA HABITACION", "Condición_propiedad": "PROPIETARIO ÚNICO",
        "__Condomin": "0", "Área_terreno_Común": "0", "Arancel": "60", "Valor_Terreno": "7230",
        "Área_construida": "66", "Area_construida_Común": "0", "Valor_Área_Construida": "20000",
        "Valor_Otras_Instalaciones": "0", "Valor_Predio": "27230", "Numero_de_Partida_Registral": None,
        "domicilio_fiscal": "CA. LAS PRUEBAS 123",
    }
    row.update(overrides)
    return row


def construccion(**overrides):
    """A made-up row of construccion_rentas: one nivel of the predio of sat()."""
    row = {
        "id_lote": "1", "pricos": "0", "cod_predio": "000000001-0001-0001", "codigo_unidad_catastro": "U-1",
        "tipo_unidad": "PRINCIPAL", "es_unidad_comun": "0", "estado_vinculo": "PAR", "nivel_rentas": "1",
        "ano_rentas": "1984", "material_rentas": "LADRILLO", "estado_conservacion_rentas": "BUENO",
        "muros_rentas": "C", "techos_rentas": "C", "pisos_rentas": "I", "puertas_rentas": "F",
        "revestimientos_rentas": "I", "banos_rentas": "I", "instalaciones_rentas": "I",
        "area_construida_nivel_rentas": "66",
    }
    row.update(overrides)
    return row


def _column(n):
    letters = ""
    while n >= 0:
        letters = chr(ord("A") + n % 26) + letters
        n = n // 26 - 1
    return letters


def write_source(path, sheet, header, rows):
    """A one-sheet xlsx of inline strings, as read_xlsx reads it."""
    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    rel_ns = 'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
    xml_rows = []
    for r, values in enumerate([dict(zip(header, header))] + rows, start=1):
        cells = "".join(f'<c r="{_column(c)}{r}" t="inlineStr"><is><t>{escape(values[name])}</t></is></c>'
                        for c, name in enumerate(header) if values.get(name) is not None)
        xml_rows.append(f'<row r="{r}">{cells}</row>')
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("xl/workbook.xml", f'<workbook {ns} {rel_ns}><sheets><sheet name="{escape(sheet)}" sheetId="1" '
                                      'r:id="rId1"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels",
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr("xl/worksheets/sheet1.xml", f'<worksheet {ns}><sheetData>{"".join(xml_rows)}</sheetData></worksheet>')


def padron(rows, mapa=None, alias=None):
    for i, row in enumerate(rows, start=2):
        row.setdefault("_fila", i)
    return conv.convertir_padron(rows, mapa or dict(conv.PADRON_COLUMNS), CODIGOS, alias or {})


def construcciones(rows, titulares, mapa=None):
    for i, row in enumerate(rows, start=2):
        row.setdefault("_fila", i)
    return conv.convertir_construcciones(rows, mapa or dict(conv.CONSTRUCCION_COLUMNS), titulares, CODIGOS, LETRAS)


class PadronTests(unittest.TestCase):
    def test_every_column_the_importer_reads_comes_from_its_source(self):
        filas, _ = padron([sat()])
        fila = filas[0]
        self.assertEqual(fila["codigo_predio"], "000000001-0001-0001")
        self.assertEqual(fila["codigo_contribuyente"], "900001")
        self.assertEqual(fila["direccion_del_predio_completa"], "CA. LAS PRUEBAS 123")
        self.assertEqual(fila["codigo_de_via"], "0000000001-01")
        self.assertEqual((fila["numero"], fila["manzana"], fila["lote"]), ("123", "A", "5"))
        self.assertEqual(fila["codigo_de_unidad_urbana"], "77")
        self.assertEqual(fila["nombre_de_unidad_urbana"], "LOS FICTICIOS")
        self.assertEqual((fila["condicion_predio"], fila["codigo_uso"]), ("1", "4"))
        self.assertEqual(fila["fecha_adquisicion"], "38000")
        self.assertEqual((fila["tipo_documento"], fila["nro_documento"]), ("1", "40000001"))
        self.assertEqual((fila["apellido_paterno"], fila["apellido_materno"], fila["nombres"]),
                         ("PRUEBA", "FICTICIA", "ANA"))
        self.assertEqual((fila["area_terreno"], fila["arancel"], fila["valor_predio"]), ("120.5", "60", "27230"))
        self.assertEqual((fila["area_construida"], fila["valor_area_construida"]), ("66", "20000"))
        self.assertEqual((fila["COD_HU_ARMONIZACION"], fila["COD_VIA_ARMONIZACION"]), ("77", "880"))

    def test_the_via_name_is_not_read_by_default(self):
        # the SAT's codigo de vía carries no name of its own, and pairing it with the armonización's name would give
        # the catalog one vía per (code, name): only the predio's address names the vía
        filas, _ = padron([sat()])
        self.assertIsNone(filas[0]["nombre_de_la_via"])

    def test_the_unidad_urbana_is_the_armonizacion_code_with_its_own_name(self):
        filas, _ = padron([sat()])
        self.assertEqual((filas[0]["codigo_de_unidad_urbana"], filas[0]["nombre_de_unidad_urbana"]),
                         ("77", "LOS FICTICIOS"))

    def test_the_tipo_de_via_becomes_the_code_codigos_json_maps(self):
        filas, _ = padron([sat(TIPO_VIA_ARMO=t) for t in ("CA.", "AV.", "JR.", "PJE.", "PRLG.")])
        tipos = [f["tipo_de_via"] for f in filas]
        self.assertEqual([CODIGOS["tipo_via"]["valores"][t] for t in tipos],
                         ["CALLE", "AVENIDA", "JIRON", "PASAJE", "PROLONGACION"])

    def test_a_tipo_de_via_without_a_code_goes_empty_and_is_counted(self):
        filas, n = padron([sat(TIPO_VIA_ARMO="AL."), sat(TIPO_VIA_ARMO="ST."), sat(TIPO_VIA_ARMO=None)])
        self.assertEqual([f["tipo_de_via"] for f in filas], [None, None, None])
        self.assertEqual(n["tipo_via_sin_codigo"], 2)

    def test_the_tipo_de_unidad_urbana_becomes_the_model_option(self):
        filas, _ = padron([sat(**{"TIPO_UNIDAD URBANA_ARMO": t}) for t in ("URB.", "AA.HH.", "P.J.", "URBANIZACION")])
        self.assertEqual([f["tipo_de_unidad_urbana"] for f in filas],
                         ["URBANIZACION", "ASENTAMIENTO HUMANO", "PUEBLO JOVEN", "URBANIZACION"])

    def test_a_tipo_de_unidad_urbana_the_srtm_lacks_goes_empty_and_is_counted(self):
        filas, n = padron([sat(**{"TIPO_UNIDAD URBANA_ARMO": "ST."})])
        self.assertIsNone(filas[0]["tipo_de_unidad_urbana"])
        self.assertEqual(n["tipo_unidad_urbana_sin_tipo"], 1)

    def test_an_alias_reads_a_tipo_de_unidad_urbana(self):
        filas, n = padron([sat(**{"TIPO_UNIDAD URBANA_ARMO": "ST."})], alias={"ST.": "SECTOR"})
        self.assertEqual(filas[0]["tipo_de_unidad_urbana"], "SECTOR")
        self.assertEqual(n["tipo_unidad_urbana_sin_tipo"], 0)

    def test_a_dash_and_blanks_become_empty(self):
        filas, n = padron([sat(MANZANA_URBANA="'-", N_MUNICIPAL="   ", A_MATERNO="")])
        self.assertEqual((filas[0]["manzana"], filas[0]["numero"], filas[0]["apellido_materno"]), (None, None, None))
        self.assertEqual(n["celdas_guion"], 1)

    def test_codes_are_passed_as_codes_and_those_codigos_json_lacks_are_counted(self):
        filas, n = padron([sat(**{"Condición_predio": "7", "TIP_DOC": "6.0", "Código_uso": "25"}), sat()])
        self.assertEqual((filas[0]["condicion_predio"], filas[0]["tipo_documento"], filas[0]["codigo_uso"]),
                         ("7", "6", "25"))
        self.assertEqual((n["condicion_predio_sin_mapeo"], n["tipo_documento_sin_mapeo"], n["codigo_uso_sin_mapeo"]),
                         (1, 1, 1))

    def test_tipo_predio_has_no_source_by_default_and_is_counted(self):
        filas, n = padron([sat()])
        self.assertIsNone(filas[0]["tipo_predio"])
        self.assertEqual(n["tipo_predio_vacio"], 1)

    def test_a_map_overrides_a_source_column(self):
        mapa = conv.parse_map(["manzana=MANZANA_URBANA_ARMO", "tipo_predio=KM"], conv.PADRON_COLUMNS)
        filas, _ = padron([sat(KM="1")], mapa=mapa)
        self.assertEqual((filas[0]["manzana"], filas[0]["tipo_predio"]), ("Z", "1"))

    def test_counts_rows_predios_and_contribuyentes(self):
        _, n = padron([sat(), sat(COD_CONTRIB="900002"), sat(COD_PREDIO="000000002-0001-0001")])
        self.assertEqual((n["filas_padron"], n["predios"], n["contribuyentes"]), (3, 2, 2))


class ConstruccionTests(unittest.TestCase):
    TITULARES = {"000000001-0001-0001": ["900001"]}

    def test_a_nivel_comes_with_the_codes_the_importer_reads(self):
        filas, _ = construcciones([construccion()], self.TITULARES)
        fila = filas[0]
        self.assertEqual((fila["codigo_predio"], fila["codigo_contribuyente"]), ("000000001-0001-0001", "900001"))
        self.assertEqual((fila["nivel"], fila["ano_construccion"], fila["area_construida_propia"]), ("1", "1984", "66"))
        self.assertEqual(CODIGOS["material"]["valores"][fila["material_predominante"]], "LADRILLO")
        self.assertEqual(CODIGOS["estado_conservacion"]["valores"][fila["estado_de_conservacion"]], "BUENO")
        self.assertEqual([fila[c] for c in ("muros_y_columnas", "techos", "pisos", "puertas_y_ventanas",
                                            "revestimientos", "banos", "instalaciones_electricas_y_sanitarias")],
                         ["C", "C", "I", "F", "I", "I", "I"])
        self.assertIsNone(fila["valor_total_nivel"])

    def test_a_material_or_estado_without_a_code_goes_empty_and_is_counted(self):
        filas, n = construcciones([construccion(material_rentas="QUINCHA", estado_conservacion_rentas="PESIMO")],
                                  self.TITULARES)
        self.assertEqual((filas[0]["material_predominante"], filas[0]["estado_de_conservacion"]), (None, None))
        self.assertEqual((n["materiales_sin_codigo"], n["estados_sin_codigo"]), (1, 1))

    def test_a_code_of_codigos_json_passes_as_it_is(self):
        filas, _ = construcciones([construccion(material_rentas="3", estado_conservacion_rentas="4.0")], self.TITULARES)
        self.assertEqual((filas[0]["material_predominante"], filas[0]["estado_de_conservacion"]), ("3", "4"))

    def test_a_letter_outside_the_model_goes_empty_and_is_counted(self):
        filas, n = construcciones([construccion(techos_rentas="0", puertas_rentas="j")], self.TITULARES)
        self.assertEqual((filas[0]["techos"], filas[0]["puertas_y_ventanas"]), (None, None))
        self.assertEqual(n["letras_vaciadas"], 2)

    def test_an_unreadable_nivel_or_year_goes_empty_and_is_counted(self):
        filas, n = construcciones([construccion(nivel_rentas="AZ", ano_rentas="S/F")], self.TITULARES)
        self.assertEqual((filas[0]["nivel"], filas[0]["ano_construccion"]), (None, None))
        self.assertEqual((n["niveles_sin_numero"], n["anios_ilegibles"]), (1, 1))

    def test_a_predio_of_several_titulares_gets_its_niveles_in_each_dj(self):
        titulares = {"000000001-0001-0001": ["900001", "900002"]}
        filas, n = construcciones([construccion(), construccion(nivel_rentas="2")], titulares)
        self.assertEqual([(f["codigo_contribuyente"], f["nivel"]) for f in filas],
                         [("900001", "1"), ("900002", "1"), ("900001", "2"), ("900002", "2")])
        self.assertEqual((n["filas_construcciones"], n["niveles"], n["niveles_copiados"]), (2, 4, 2))

    def test_the_niveles_of_a_code_the_importer_splits_are_counted(self):
        titulares = {"000000001-0001-0001": ["900001", "900002"]}
        rows = [construccion()]
        for i, row in enumerate(rows, start=2):
            row["_fila"] = i
        _, n = conv.convertir_construcciones(rows, dict(conv.CONSTRUCCION_COLUMNS), titulares, CODIGOS, LETRAS,
                                             {"000000001-0001-0001"})
        self.assertEqual(n["niveles_en_codigos_compartidos"], 2)

    def test_a_nivel_of_a_predio_the_padron_lacks_is_dropped_and_counted(self):
        filas, n = construcciones([construccion(cod_predio="000000099-0001-0001")], self.TITULARES)
        self.assertEqual(filas, [])
        self.assertEqual(n["construcciones_sin_predio"], 1)


def observaciones_padron(rows, alias=None):
    for i, row in enumerate(rows, start=2):
        row.setdefault("_fila", i)
    obs = []
    conv.convertir_padron(rows, dict(conv.PADRON_COLUMNS), CODIGOS, alias or {}, observaciones=obs)
    return obs


def observaciones_construcciones(rows, titulares, compartidos=()):
    for i, row in enumerate(rows, start=2):
        row.setdefault("_fila", i)
    obs = []
    conv.convertir_construcciones(rows, dict(conv.CONSTRUCCION_COLUMNS), titulares, CODIGOS, LETRAS, compartidos,
                                  observaciones=obs)
    return obs


def resumen(obs):
    return [(o["regla"], o["objeto"], o["codigo_predio"], o["codigo_contribuyente"], o["hoja"], o["fila"]) for o in obs]


class ObservacionesPadronTests(unittest.TestCase):
    """#297: each row the converter emptied, read by an assumption or flagged gets one observation."""

    def test_an_st_read_as_sector_is_observed_on_its_row(self):
        obs = observaciones_padron([sat(), sat(COD_CONTRIB="900002", **{"TIPO_UNIDAD URBANA_ARMO": "ST."})],
                                   alias={"ST.": "SECTOR"})
        self.assertEqual(resumen(obs), [("TIPO_UU_ST_COMO_SECTOR", "predio", "000000001-0001-0001", "900002",
                                         "Hoja 1", 3)])
        self.assertEqual(obs[0]["detalle"], 'tipo_de_unidad_urbana "ST." → SECTOR')

    def test_another_alias_is_otra(self):
        obs = observaciones_padron([sat(**{"TIPO_UNIDAD URBANA_ARMO": "SEC."})], alias={"SEC.": "SECTOR"})
        self.assertEqual([(o["regla"], o["detalle"]) for o in obs],
                         [("OTRA", 'tipo_de_unidad_urbana "SEC." → SECTOR (--tipo-unidad)')])

    def test_an_alias_the_table_already_reads_is_no_observation(self):
        self.assertEqual(observaciones_padron([sat(**{"TIPO_UNIDAD URBANA_ARMO": "C.P."})],
                                              alias={"C.P.": "CENTRO POBLADO"}), [])

    def test_a_tipo_without_option_or_code_is_observed(self):
        obs = observaciones_padron([sat(TIPO_VIA_ARMO="AL.", **{"TIPO_UNIDAD URBANA_ARMO": "ST."})])
        self.assertEqual([(o["regla"], o["objeto"], o["detalle"]) for o in obs], [
            ("TIPO_VIA_NO_LEIDO", "predio", 'tipo_de_via "AL." → vacío'),
            ("TIPO_UU_NO_LEIDO", "predio", 'tipo_de_unidad_urbana "ST." → vacío')])

    def test_unmapped_codes_are_observed_the_documento_once_per_contribuyente(self):
        obs = observaciones_padron([
            sat(**{"Condición_predio": "7", "TIP_DOC": "6", "Código_uso": "25"}),
            sat(COD_PREDIO="000000002-0001-0001", TIP_DOC="6")])
        self.assertEqual(resumen(obs), [
            ("CONDICION_SIN_MAPEO", "declaracion_predial", "000000001-0001-0001", "900001", "Hoja 1", 2),
            ("DOCUMENTO_SIN_MAPEO", "contribuyente", None, "900001", "Hoja 1", 2),
            ("USO_SIN_MAPEO", "declaracion_predial", "000000001-0001-0001", "900001", "Hoja 1", 2)])
        self.assertEqual([o["detalle"] for o in obs], [
            'condicion_predio "7" sin código en data/ica/codigos.json',
            'tipo_documento "6" sin código en data/ica/codigos.json',
            'codigo_uso "25" sin código de srtm en data/ica/codigos.json'])

    def test_a_clean_row_has_no_observation(self):
        self.assertEqual(observaciones_padron([sat()]), [])

    def test_every_regla_and_objeto_is_an_option_of_the_model(self):
        model = ipi.load_model(ipi.default_model_path())
        obs = observaciones_padron([sat(TIPO_VIA_ARMO="AL.", **{"TIPO_UNIDAD URBANA_ARMO": "ST.", "Condición_predio": "7",
                                                               "TIP_DOC": "6", "Código_uso": "25"}),
                                    sat(COD_CONTRIB="900002", **{"TIPO_UNIDAD URBANA_ARMO": "ST."}),
                                    sat(COD_CONTRIB="900003", **{"TIPO_UNIDAD URBANA_ARMO": "SEC."}),
                                    sat(COD_CONTRIB="900004", **{"TIPO_UNIDAD URBANA_ARMO": "XX."})],
                                   alias={"ST.": "SECTOR", "SEC.": "SECTOR"})
        obs += observaciones_construcciones(
            [construccion(techos_rentas="0", material_rentas="QUINCHA", estado_conservacion_rentas="PESIMO",
                          nivel_rentas="AZ", ano_rentas="S/F"), construccion(cod_predio="000000099-0001-0001")],
            {"000000001-0001-0001": ["900001", "900002"]}, {"000000001-0001-0001"})
        self.assertEqual(len({o["regla"] for o in obs}), 15)
        self.assertTrue({o["regla"] for o in obs} <= set(model["enums"]["regla_observacion_importacion"]))
        self.assertTrue({o["objeto"] for o in obs} <= set(model["enums"]["objeto_importado"]))


class ObservacionesConstruccionTests(unittest.TestCase):
    TITULARES = {"000000001-0001-0001": ["900001"]}

    def test_the_letters_of_a_row_out_of_range_are_one_observation(self):
        obs = observaciones_construcciones([construccion(techos_rentas="0", puertas_rentas="j")], self.TITULARES)
        self.assertEqual(resumen(obs), [("LETRA_FUERA_DE_RANGO", "nivel_construccion", "000000001-0001-0001",
                                         "900001", "construccion_rentas", 2)])
        self.assertEqual(obs[0]["detalle"], 'techos "0", puertas_y_ventanas "j" → vacías')

    def test_unreadable_material_estado_nivel_and_year(self):
        obs = observaciones_construcciones([construccion(material_rentas="QUINCHA", estado_conservacion_rentas="PESIMO",
                                                         nivel_rentas="AZ", ano_rentas="S/F")], self.TITULARES)
        self.assertEqual([(o["regla"], o["detalle"]) for o in obs], [
            ("NIVEL_NO_LEIDO", 'nivel "AZ" → vacío'), ("ANIO_NO_LEIDO", 'ano_construccion "S/F" → vacío'),
            ("MATERIAL_NO_LEIDO", 'material_predominante "QUINCHA" → vacío'),
            ("ESTADO_NO_LEIDO", 'estado_de_conservacion "PESIMO" → vacío')])

    def test_a_copied_nivel_is_observed_on_the_cotitulars_dj_with_the_rows_own_observations(self):
        titulares = {"000000001-0001-0001": ["900001", "900002"]}
        obs = observaciones_construcciones([construccion(techos_rentas="0")], titulares)
        self.assertEqual([(o["regla"], o["codigo_contribuyente"]) for o in obs], [
            ("LETRA_FUERA_DE_RANGO", "900001"), ("LETRA_FUERA_DE_RANGO", "900002"),
            ("NIVEL_COPIADO_A_COTITULAR", "900002")])
        self.assertEqual(obs[-1]["detalle"], "nivel 1 copiado a la DJ del co-titular 2 de 2 (contribuyente 900002)")

    def test_a_nivel_of_a_shared_code_is_observed_in_each_dj(self):
        titulares = {"000000001-0001-0001": ["900001", "900002"]}
        obs = observaciones_construcciones([construccion()], titulares, {"000000001-0001-0001"})
        self.assertEqual([(o["regla"], o["codigo_contribuyente"]) for o in obs], [
            ("CODIGO_COMPARTIDO_DIVIDIDO", "900001"), ("CODIGO_COMPARTIDO_DIVIDIDO", "900002"),
            ("NIVEL_COPIADO_A_COTITULAR", "900002")])

    def test_a_nivel_without_predio_is_observed_and_dropped(self):
        obs = observaciones_construcciones([construccion(cod_predio="000000099-0001-0001")], self.TITULARES)
        self.assertEqual(resumen(obs), [("NIVEL_SIN_PREDIO", "nivel_construccion", "000000099-0001-0001", None,
                                         "construccion_rentas", 2)])


class MainTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.padron = os.path.join(self.dir.name, "padron.xlsx")
        self.construcciones = os.path.join(self.dir.name, "construcciones.xlsx")
        self.salida = os.path.join(self.dir.name, "salida")
        write_source(self.padron, "Hoja 1", SAT_HEADER, [
            sat(),
            # a condominio of two
            sat(COD_PREDIO="000000002-0001-0001", **{"Condición_predio": "5"}),
            sat(COD_PREDIO="000000002-0001-0001", COD_CONTRIB="900002", N_DOC="40000002", **{"Condición_predio": "5"}),
        ])
        write_source(self.construcciones, "construccion_rentas", CONSTRUCCION_HEADER, [
            construccion(), construccion(cod_predio="000000002-0001-0001"),
        ])

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = conv.main(["--padron", self.padron, "--construcciones", self.construcciones,
                              "--salida", self.salida, *extra])
        return code, out.getvalue(), err.getvalue()

    def test_writes_the_workbook_import_padron_ica_reads(self):
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        libro = os.path.join(self.salida, conv.LIBRO)
        padron_rows, construccion_rows = read_xlsx(libro, "padron"), read_xlsx(libro, "construcciones")
        self.assertEqual(list(padron_rows[0])[:-1], conv.PADRON_SALIDA + conv.ORIGEN)
        self.assertEqual(list(construccion_rows[0])[:-1], conv.CONSTRUCCION_SALIDA + conv.ORIGEN)
        # #297: each row carries its sheet and row of the source
        self.assertEqual([(r["hoja_origen"], r["fila_origen"]) for r in padron_rows],
                         [("Hoja 1", "2"), ("Hoja 1", "3"), ("Hoja 1", "4")])
        self.assertEqual([(r["hoja_origen"], r["fila_origen"]) for r in construccion_rows],
                         [("construccion_rentas", "2"), ("construccion_rentas", "3"), ("construccion_rentas", "3")])
        data = ipi.build_dataset(padron_rows, construccion_rows, 2026, ipi.load_model(ipi.default_model_path()),
                                 CODIGOS, ipi.read_usos_srtm(ipi.default_usos_path()),
                                 ipi.read_depreciacion(ipi.default_depreciacion_path()))
        self.assertEqual((len(data.predios), len(data.declaraciones), len(data.niveles)), (2, 3, 3))
        self.assertEqual(Counter(r["categoria"] for r in data.reporte)["rechazada"], 0)
        self.assertIn("niveles: 3\n", out)

    def test_writes_the_observaciones_sheet(self):
        write_source(self.padron, "Hoja 1", SAT_HEADER, [
            sat(**{"TIPO_UNIDAD URBANA_ARMO": "ST."}), sat(COD_PREDIO="000000002-0001-0001", TIP_DOC="6")])
        code, out, _ = self.run_main("--tipo-unidad", "ST.=SECTOR")
        self.assertEqual(code, 0)
        rows = read_xlsx(os.path.join(self.salida, conv.LIBRO), "observaciones")
        self.assertEqual(list(rows[0])[:-1], conv.OBSERVACIONES_SALIDA)
        self.assertEqual(conv.OBSERVACIONES_SALIDA, ["codigo_predio", "codigo_contribuyente", "objeto", "regla",
                                                     "detalle", "hoja", "fila"])
        # the second row's TIP_DOC is not its contribuyente's first: the importer reads the document from the first
        self.assertEqual([(r["regla"], r["codigo_predio"], r["hoja"], r["fila"]) for r in rows],
                         [("TIPO_UU_ST_COMO_SECTOR", "000000001-0001-0001", "Hoja 1", "2")])
        self.assertIn("observaciones: 1\n", out)
        # codes and catalog values only: never a name nor a document (Ley 29733)
        texto = " ".join(str(v) for r in rows for v in r.values())
        for dato in ("PRUEBA", "FICTICIA", "ANA", "40000001"):
            self.assertNotIn(dato, texto)

    def test_the_importer_carries_the_observations_to_the_original_rows(self):
        write_source(self.padron, "Hoja 1", SAT_HEADER, [
            sat(**{"TIPO_UNIDAD URBANA_ARMO": "ST."}),
            sat(COD_PREDIO="000000002-0001-0001", **{"Condición_predio": "5"}),
            sat(COD_PREDIO="000000002-0001-0001", COD_CONTRIB="900002", N_DOC="40000002", **{"Condición_predio": "5"})])
        self.assertEqual(self.run_main("--tipo-unidad", "ST.=SECTOR")[0], 0)
        libro = os.path.join(self.salida, conv.LIBRO)
        padron_rows, construccion_rows = read_xlsx(libro, "padron"), read_xlsx(libro, "construcciones")
        data = ipi.build_dataset(padron_rows, construccion_rows, 2026, ipi.load_model(ipi.default_model_path()),
                                 CODIGOS, ipi.read_usos_srtm(ipi.default_usos_path()),
                                 ipi.read_depreciacion(ipi.default_depreciacion_path()))
        obs = ipi.observaciones_de(data, "C1", padron_rows, construccion_rows, read_xlsx(libro, "observaciones"))
        por_regla = {o["regla"]: o for o in obs}
        self.assertEqual((por_regla["TIPO_UU_ST_COMO_SECTOR"]["hoja"], por_regla["TIPO_UU_ST_COMO_SECTOR"]["fila"]),
                         ("Hoja 1", 2))
        self.assertEqual((por_regla["CONDOMINIO_SIN_PORCENTAJE"]["hoja"], por_regla["CONDOMINIO_SIN_PORCENTAJE"]["fila"],
                          por_regla["CONDOMINIO_SIN_PORCENTAJE"]["codigo"]), ("Hoja 1", 3, "000000002-0001-0001"))
        # the nivel of predio 2 goes to both DJ: the copy carries the row of construccion_rentas
        [nivel] = [o for o in obs if o["objeto"] == "nivel_construccion"]
        self.assertEqual((nivel["regla"], nivel["codigo"], nivel["hoja"], nivel["fila"]),
                         ("NIVEL_COPIADO_A_COTITULAR", "000000002-0001-0001/900002", "construccion_rentas", 3))
        # the importer's report of the converted rows points to the SAT's rows too
        self.assertEqual({o["hoja"] for o in obs}, {"Hoja 1", "construccion_rentas"})

    def test_excluded_predios_leave_every_sheet(self):
        excluir = os.path.join(self.dir.name, "excluir.csv")
        with open(excluir, "w", encoding="utf-8") as f:
            f.write("codigo_predio,motivo\n000000002-0001-0001,duplicado\n000000099-0001-0001,no está\n")
        write_source(self.padron, "Hoja 1", SAT_HEADER, [
            sat(), sat(COD_PREDIO="000000002-0001-0001", TIP_DOC="6"),
            sat(COD_PREDIO="000000002-0001-0001", COD_CONTRIB="900002")])
        code, out, err = self.run_main("--excluir-predios", excluir)
        self.assertEqual(code, 0, msg=err)
        libro = os.path.join(self.salida, conv.LIBRO)
        for hoja in ("padron", "construcciones", "observaciones"):
            with self.subTest(hoja=hoja):
                self.assertNotIn("000000002-0001-0001", {r.get("codigo_predio") for r in read_xlsx(libro, hoja)})
        self.assertIn("predios_excluidos: 1\n", out)
        self.assertIn("filas_padron: 1\n", out)
        self.assertIn("construcciones_sin_predio: 0\n", out)

    def test_an_exclusion_csv_without_codigo_predio_exits_2(self):
        excluir = os.path.join(self.dir.name, "excluir.csv")
        with open(excluir, "w", encoding="utf-8") as f:
            f.write("codigo\n000000002-0001-0001\n")
        code, _, err = self.run_main("--excluir-predios", excluir)
        self.assertEqual(code, 2)
        self.assertIn("codigo_predio", err)
        self.assertFalse(os.path.exists(self.salida))

    def test_the_workbook_is_readable_only_by_its_owner(self):
        self.run_main()
        mode = stat.S_IMODE(os.stat(os.path.join(self.salida, conv.LIBRO)).st_mode)
        self.assertEqual(mode, 0o600)

    def test_stdout_is_counts_only(self):
        _, out, _ = self.run_main()
        for line in out.splitlines():
            self.assertRegex(line, r"^[a-z_]+: \d+$")
        self.assertNotIn("PRUEBA", out)
        self.assertNotIn("40000001", out)

    def test_a_missing_column_exits_2_naming_it_and_writes_nothing(self):
        write_source(self.padron, "Hoja 1", [c for c in SAT_HEADER if c != "COD_CONTRIB"], [sat()])
        code, _, err = self.run_main()
        self.assertEqual(code, 2)
        self.assertIn("COD_CONTRIB", err)
        self.assertFalse(os.path.exists(self.salida))

    def test_a_missing_sheet_exits_2_naming_it_and_writes_nothing(self):
        code, _, err = self.run_main("--hoja-construcciones", "otra")
        self.assertEqual(code, 2)
        self.assertIn("otra", err)
        self.assertFalse(os.path.exists(self.salida))

    def test_a_bad_map_exits_2(self):
        code, _, err = self.run_main("--map-padron", "no_es_un_campo=X")
        self.assertEqual(code, 2)
        self.assertIn("no_es_un_campo", err)

    def test_a_bad_alias_exits_2(self):
        code, _, err = self.run_main("--tipo-unidad", "ST.=NO ES UN TIPO")
        self.assertEqual(code, 2)
        self.assertIn("NO ES UN TIPO", err)

    def test_an_output_inside_the_repository_exits_2(self):
        dentro = os.path.join(conv.REPOSITORIO, "model", "reports", "no-se-escribe")
        err = io.StringIO()
        with redirect_stderr(err):
            code = conv.main(["--padron", self.padron, "--construcciones", self.construcciones, "--salida", dentro])
        self.assertEqual(code, 2)
        self.assertIn("repositorio", err.getvalue())
        self.assertFalse(os.path.exists(dentro))


if __name__ == "__main__":
    unittest.main()
