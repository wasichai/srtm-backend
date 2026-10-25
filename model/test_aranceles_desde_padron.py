"""Tests for aranceles_desde_padron.py: the padrón's aranceles by código de vía, the copy of a year from the Core and
where the CSV may be written.

Every row here is made up (test_import_padron_ica's padrón): the real padrón never enters the repo.

Run: cd model && python3 -m unittest -v test_aranceles_desde_padron
"""
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from decimal import Decimal

import aranceles_desde_padron as adp
from fake_core import FakeCore
from test_import_padron_ica import padron, write_xlsx

HERE = os.path.dirname(os.path.abspath(__file__))


def numerados(filas):
    return [{**f, "_fila": i} for i, f in enumerate(filas, start=2)]


class PadronTests(unittest.TestCase):
    def test_one_arancel_per_codigo_de_via_of_the_urbano_rows_with_an_arancel(self):
        aranceles, conflictos, cuentas = adp.desde_padron(numerados([
            padron(codigo_de_via="0010000401-01", arancel="356"),
            padron(codigo_de_via="0010000401-01", arancel="356.0000000001"),
            padron(codigo_de_via="0010000401-02", arancel="340"),
            # rústico (per hectárea, #209), 0 and blank: out
            padron(codigo_de_via="0010000401-01", tipo_predio="2", arancel="0"),
            padron(codigo_de_via="0020000101-01", tipo_predio="2", arancel="12"),
            padron(codigo_de_via="0030000101-01", arancel="0"),
            padron(codigo_de_via="0030000101-01", arancel=None),
            padron(codigo_de_via=None, arancel="100"),
            padron(codigo_de_via="0040000101-01", tipo_predio=None, arancel="100"),
        ]))
        self.assertEqual(aranceles, {"0010000401-01": Decimal("356.00"), "0010000401-02": Decimal("340.00")})
        self.assertEqual(conflictos, {})
        self.assertEqual(cuentas["filas"], 9)
        self.assertEqual(cuentas["urbanas_con_arancel"], 3)
        self.assertEqual(cuentas["rusticas"], 2)
        self.assertEqual(cuentas["arancel_cero_o_vacio"], 2)
        self.assertEqual(cuentas["sin_codigo_de_via"], 1)
        self.assertEqual(cuentas["sin_tipo_urbano"], 1)

    def test_a_code_with_two_values_is_a_conflict_out_of_the_aranceles(self):
        aranceles, conflictos, _ = adp.desde_padron(numerados([
            padron(codigo_de_via="0010000401-01", arancel="356"),
            padron(codigo_de_via="0010000401-01", arancel="300"),
            padron(codigo_de_via="0010000401-02", arancel="340"),
        ]))
        self.assertEqual(aranceles, {"0010000401-02": Decimal("340.00")})
        self.assertEqual(conflictos, {"0010000401-01": ([Decimal("300.00"), Decimal("356.00")], [2, 3])})

    def test_the_code_is_read_as_import_padron_ica_reads_predio_codigo_via(self):
        # 501.0 is how the xlsx may carry 501: the predio's codigo_via is 501, and so is the arancel's
        aranceles, _, _ = adp.desde_padron(numerados([padron(codigo_de_via="501.0", arancel="20")]))
        self.assertEqual(list(aranceles), ["501"])

    def test_the_csv_rows_are_normativas_columns_with_id_aranc_the_code(self):
        filas = adp.filas_csv({"B": Decimal("2.00"), "A": Decimal("1.50")}, 2026, adp.NORMA_2026, "padron.xlsx")
        self.assertEqual(filas[0], {"id_aranc": "A", "anio": 2026, "valor_m2": "1.50", "cod_via": "A", "cuadra": "",
                                    "lado": "", "norma": "RM 514-2025-EF/15", "fuente": "padron.xlsx"})
        self.assertEqual([f["id_aranc"] for f in filas], ["A", "B"])


class CliTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.salida = os.path.join(self.tmp.name, "aranceles.csv")

    def run_main(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = adp.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def leer(self, path=None):
        with open(path or self.salida, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))


class ExcelCliTests(CliTestCase):
    def setUp(self):
        super().setUp()
        self.excel = os.path.join(self.tmp.name, "padron.xlsx")
        write_xlsx(self.excel, [
            padron(codigo_de_via="0010000401-01", arancel="356"),
            padron(codigo_de_via="0010000401-02", arancel="340"),
            padron(codigo_de_via="0010000401-02", arancel="341"),
            padron(codigo_de_via="0020000101-01", tipo_predio="2", arancel="0"),
        ], [])

    def test_the_csv_of_the_year_its_conflicts_apart_and_only_counts_printed(self):
        code, out, err = self.run_main("--excel", self.excel, "--salida", self.salida)
        self.assertEqual(code, 0, err)
        filas = self.leer()
        self.assertEqual(list(filas[0]), adp.COLUMNAS)
        self.assertEqual([(f["id_aranc"], f["anio"], f["valor_m2"], f["cod_via"], f["norma"], f["fuente"]) for f in filas],
                         [("0010000401-01", "2026", "356.00", "0010000401-01", "RM 514-2025-EF/15", "padron.xlsx")])
        conflictos = self.leer(os.path.join(self.tmp.name, "aranceles.conflictos.csv"))
        self.assertEqual(conflictos, [{"cod_via": "0010000401-02", "valores": "340.00 341.00", "filas": "3 4"}])
        self.assertIn("aranceles: 1 códigos de vía de 2026, conflictos: 1", out)
        # counts only: neither a code nor a value of the padrón on the screen
        self.assertNotIn("0010000401", out)
        self.assertNotIn("356", out)

    def test_another_year_takes_no_norma_unless_given(self):
        code, _, err = self.run_main("--excel", self.excel, "--salida", self.salida, "--anio", "2027")
        self.assertEqual(code, 0, err)
        self.assertEqual([(f["anio"], f["norma"]) for f in self.leer()], [("2027", "")])
        code, _, err = self.run_main("--excel", self.excel, "--salida", self.salida, "--norma", "Ordenanza ficticia")
        self.assertEqual(code, 0, err)
        self.assertEqual([f["norma"] for f in self.leer()], ["Ordenanza ficticia"])

    def test_the_csv_never_goes_into_the_repo(self):
        dentro = os.path.join(HERE, "reports", "aranceles-prueba.csv")
        code, _, err = self.run_main("--excel", self.excel, "--salida", dentro)
        self.assertEqual(code, 2)
        self.assertIn("dentro del repo", err)
        self.assertFalse(os.path.exists(dentro))
        # nor its conflicts
        code, _, err = self.run_main("--excel", self.excel, "--salida", self.salida, "--conflictos", dentro)
        self.assertEqual(code, 2)
        self.assertFalse(os.path.exists(self.salida))

    def test_salida_is_required_and_one_source_too(self):
        with redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                adp.main(["--excel", self.excel])
            with self.assertRaises(SystemExit):
                adp.main(["--salida", self.salida])
            with self.assertRaises(SystemExit):
                adp.main(["--excel", self.excel, "--copiar-de", "2026", "--salida", self.salida])


class CopiaCliTests(CliTestCase):
    def setUp(self):
        super().setUp()
        self.core = FakeCore()
        self.addCleanup(self.core.stop)

    def arancel(self, id_aranc, anio, valor, estado="VIGENTE", **otros):
        return self.core.add_record("arancel", {"id_aranc": id_aranc, "anio": anio, "valor_m2": valor, "estado": estado,
                                                "cod_via": id_aranc, "cuadra": None, "lado": None, **otros})

    def test_the_vigente_aranceles_of_a_year_copied_to_another(self):
        self.arancel("V-01", 2026, 356.0)
        self.arancel("V-02", 2026, 340.5, estado=None)
        self.arancel("V-03", 2026, 99, estado="POR_VERIFICAR")
        self.arancel("V-04", 2026, 98, estado="ANULADO")
        self.arancel("V-05", 2025, 97)
        code, out, err = self.run_main("--copiar-de", "2026", "--anio", "2027", "--salida", self.salida,
                                       "--core", self.core.base_url, "--email", "a@b.c", "--password", "x")
        self.assertEqual(code, 0, err)
        filas = self.leer()
        self.assertEqual([(f["id_aranc"], f["anio"], f["valor_m2"], f["cod_via"], f["norma"]) for f in filas],
                         [("V-01", "2027", "356.00", "V-01", ""), ("V-02", "2027", "340.50", "V-02", "")])
        self.assertEqual(filas[0]["fuente"], "copia de los aranceles VIGENTE de 2026")
        self.assertIn("aranceles: 2 VIGENTE de 2026, copiados a 2027", out)
        # the Core is only read
        self.assertFalse([r for r in self.core.requests if r[0] in ("PUT", "DELETE")
                          or (r[0] == "POST" and r[1] != "/api/auth/login")])

    def test_two_vigente_of_one_clave_copy_the_lowest_id(self):
        a = self.arancel("V-01", 2026, 10)
        b = self.arancel("V-01", 2026, 11)
        vigentes = adp.vigentes_de([a, b])
        self.assertEqual(len(vigentes), 1)
        self.assertEqual(vigentes[0]["valor_m2"], min([a, b], key=lambda r: r["id"])["attributes"]["valor_m2"])

    def test_a_copy_to_the_same_year_is_refused(self):
        code, _, err = self.run_main("--copiar-de", "2026", "--anio", "2026", "--salida", self.salida,
                                     "--core", self.core.base_url)
        self.assertEqual(code, 2)
        self.assertIn("mismo año", err)
        self.assertEqual(self.core.requests, [])


if __name__ == "__main__":
    unittest.main()
