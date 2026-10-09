"""Tests for derivar_cuiema.py: from the literal transcription of a CUIEMA to the rows import_cuis.py loads.

Run: cd model && python3 -m unittest -v
"""
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import derivar_cuiema as dc
import import_cuis

# FICTITIOUS rows, in the shape of the transcriptions of Perené: their figures are no ordinance's
TRANSCRIPCION = """# CUIEMA ficticio de prueba: no es una ordenanza
pagina,materia,codigo,infraccion,calificacion,medida_cautelar,pecuniaria,no_pecuniaria,base_legal,nota
1,Limpieza pública,001,Arrojar residuos en la vía,Leve,,50%,Clausura temporal,,
1,Limpieza pública,002,Ejecutar obra sin licencia,Grave,Paralización,200% si es modalidad C,Demolición,,
1,Limpieza pública,003,Ejecutar obra sin licencia,Grave,,El uno (1%) de valor de la obra o proyecto.,,,
2,Ornato,004,Talar un árbol,Grave,,10% por cada árbol,,,
2,Ornato,005,Extraer material,Grave,,5% de la UIT por metro3 de material extraido,,,
2,Ornato,006,No exhibir el permiso,Leve,,,Retiro,,
2,Ornato,007,No exhibir el permiso,Leve,,2[ilegible]0%,,,
2,Ornato,008,Infracción de cero,Leve,,0%,,,
3,Salud,5.02.114,Despachar en bolsas,Muy Grave,,"1000%",Decomiso,Ley ficticia 1,
3,Salud,5.02.115,Repetida igual,Leve,,25%,,Ley ficticia 1,
4,Salud,5.02.115,Repetida igual,Leve,,25%,,Ley ficticia 1,la norma repite la fila al cambiar de página
4,Salud,06.01.032,Repetida distinta,Leve,,1%,,Ley ficticia 1,
4,Salud,06.01.032,Repetida distinta,Muy Grave,,200%,,Ley ficticia 1,
"""


class DerivarTests(unittest.TestCase):
    def setUp(self):
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write(TRANSCRIPCION)
        self.addCleanup(os.unlink, f.name)
        self.cabecera, self.filas = dc.leer(f.name)
        self.cuis, self.excluidas = dc.derivar(self.filas, "2026-05-07", "Carga de prueba del CUIEMA", "Ordenanza ficticia")
        self.por_codigo = {c["codigo"]: c for c in self.cuis}
        self.motivos = {f["codigo"]: m for f, m in self.excluidas}

    def test_a_plain_row_is_a_version(self):
        self.assertEqual(self.por_codigo["001"], {
            "familia": "ADMINISTRATIVA", "codigo": "001", "descripcion": "Arrojar residuos en la vía", "materia": "Limpieza pública",
            "porcentaje_uit": "50", "medida_complementaria": "Clausura temporal", "base_legal": "Ordenanza ficticia",
            "vigencia_desde": "2026-05-07", "observacion": "Carga de prueba del CUIEMA"})

    def test_a_condition_of_the_multa_goes_in_the_descripcion(self):
        self.assertEqual(self.por_codigo["002"]["porcentaje_uit"], "200")
        self.assertEqual(self.por_codigo["002"]["descripcion"], "Ejecutar obra sin licencia (200% si es modalidad C)")

    def test_what_is_not_a_multa_on_the_uit_is_left_out_with_its_reason(self):
        self.assertIn("no es un % de la UIT", self.motivos["003"])
        self.assertIn("por unidad", self.motivos["004"])
        self.assertIn("por unidad", self.motivos["005"])
        self.assertEqual(self.motivos["006"], "sin multa pecuniaria")
        self.assertIn("ilegible", self.motivos["007"])
        self.assertIn("0 %", self.motivos["008"])

    def test_a_one_digit_first_group_gets_its_zero_and_the_row_its_base_legal(self):
        self.assertEqual(self.por_codigo["05.02.114"]["porcentaje_uit"], "1000")
        self.assertEqual(self.por_codigo["05.02.114"]["base_legal"], "Ley ficticia 1")

    def test_a_row_printed_twice_is_one_version_and_a_code_with_two_rows_is_left_out(self):
        self.assertEqual([c["codigo"] for c in self.cuis].count("05.02.115"), 1)
        self.assertNotIn("06.01.032", self.por_codigo)
        self.assertEqual([f["codigo"] for f, _ in self.excluidas].count("06.01.032"), 2)

    def test_every_version_fits_import_cuis(self):
        self.assertEqual(import_cuis.errores(self.cuis), [])

    def test_main_writes_the_file_import_cuis_reads(self):
        with tempfile.TemporaryDirectory() as d:
            entrada = os.path.join(d, "t.csv")
            with open(entrada, "w", encoding="utf-8") as f:
                f.write(TRANSCRIPCION)
            salida, excluidas = os.path.join(d, "cuis.csv"), os.path.join(d, "excluidas.csv")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = dc.main(["--transcripcion", entrada, "--vigencia-desde", "2026-05-07", "--observacion", "Carga de prueba",
                                "--base-legal", "Ordenanza ficticia", "--salida", salida, "--excluidas", excluidas])
            self.assertEqual(code, 0, err.getvalue())
            self.assertIn("filas: 13; versiones: 4; excluidas: 8", out.getvalue())
            leidas = import_cuis.read_cuis(salida)
            self.assertEqual([f["codigo"] for f in leidas], ["001", "002", "05.02.114", "05.02.115"])
            with open(salida, encoding="utf-8") as f:
                self.assertTrue(f.readline().startswith("# CUIEMA ficticio de prueba"), "it keeps the header that cites the norm")
            with open(excluidas, encoding="utf-8") as f:
                self.assertEqual(len(list(csv.DictReader(f))), 8)


# the CUIEMA of Perené in data/cuiema: each loaded file is what derivar_cuiema.py gives from its transcription
PERENE = {
    "2021": ("2021-01-26", "Carga del CUIEMA de la OM N.° 01-2021/MDP (anexo del RAMSA, p. 29-157 del PDF), transcrito en doble lectura", ""),
    "2026": ("2026-05-07", "Carga del CUIEMA de la OM N.° 006-2026-MDP (anexo, p. 16-33 del PDF), transcrito en doble lectura",
             "Ordenanza Municipal N.° 006-2026-MDP, que aprueba el RAMSA y el CUIEMA de la Municipalidad Distrital de Perené"),
}


class PereneTests(unittest.TestCase):
    def test_each_loaded_file_is_derived_from_its_transcription(self):
        datos = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "cuiema")
        for anio, (desde, observacion, base_legal) in PERENE.items():
            with self.subTest(anio=anio):
                _, filas = dc.leer(os.path.join(datos, f"perene-{anio}-transcripcion.csv"))
                cuis, excluidas = dc.derivar(filas, desde, observacion, base_legal)
                self.assertEqual(import_cuis.errores(cuis), [])
                guardado = import_cuis.read_cuis(os.path.join(datos, f"perene-{anio}-cuis.csv"))
                self.assertEqual(guardado, [{k: v for k, v in f.items()} for f in cuis])
                with open(os.path.join(datos, f"perene-{anio}-excluidas.csv"), encoding="utf-8") as f:
                    self.assertEqual([r["codigo"] for r in csv.DictReader(f)], [f["codigo"] for f, _ in excluidas])
                self.assertTrue(all(c["vigencia_desde"] == desde for c in guardado))


if __name__ == "__main__":
    unittest.main()
