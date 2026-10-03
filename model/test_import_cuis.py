"""Tests for import_cuis.py: the shape of a CUIS file and an idempotent load, version by version, into a fake Core.

Run: cd model && python3 -m unittest -v
"""
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_cuis as ic
from fake_core import FakeCore

OBJECT = "codigo_infraccion"
CABECERA = ",".join(ic.COLUMNS)

# FICTITIOUS rows, written here only to test the load: the CUIS of Perené is not transcribed and verified yet
CUIS = """# CUIS ficticio de prueba: no es una ordenanza, sus cifras no son de nadie
{cabecera}
ADMINISTRATIVA,a-042,No exhibir la licencia,COMERCIO,10,15,20,Clausura temporal,Ordenanza ficticia 001,2026-01-01,Carga de prueba del CUIS
,B-001,Arrojar desmonte en la vía,LIMPIEZA,25.5,,,,Ordenanza ficticia 001,2026-01-01,Carga de prueba del CUIS
"""

# a second version of A-042, from July: it closes the first one
NUEVA = "ADMINISTRATIVA,A-042,No exhibir la licencia,COMERCIO,12,18,24,Clausura temporal,Ordenanza ficticia 002,2026-07-01,Nueva versión de prueba\n"


class CuisTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)

    def archivo(self, texto):
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write(texto.format(cabecera=CABECERA) if "{cabecera}" in texto else texto)
        self.addCleanup(os.unlink, f.name)
        return f.name

    def run_main(self, texto, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ic.main(["--core", self.core.base_url, "--csv", self.archivo(texto), *extra])
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p) for m, p, _, _ in self.core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def versiones(self):
        return {r["attributes"]["clave"]: r["attributes"] for r in self.core.records.get(OBJECT, [])}


class ReadTests(CuisTestCase):
    def test_a_row_as_the_services_body(self):
        filas = ic.read_cuis(self.archivo(CUIS))
        self.assertEqual(filas[0], {
            "familia": "ADMINISTRATIVA", "codigo": "A-042", "descripcion": "No exhibir la licencia", "materia": "COMERCIO",
            "porcentaje_uit": "10", "porcentaje_uit_segunda": "15", "porcentaje_uit_tercera": "20",
            "medida_complementaria": "Clausura temporal", "base_legal": "Ordenanza ficticia 001", "vigencia_desde": "2026-01-01",
            "observacion": "Carga de prueba del CUIS"})
        # familia defaults to ADMINISTRATIVA; an empty cell is left out
        self.assertEqual(filas[1]["familia"], "ADMINISTRATIVA")
        self.assertNotIn("porcentaje_uit_segunda", filas[1])
        self.assertEqual([ic.clave(f) for f in filas], ["ADMINISTRATIVA|A-042|2026-01-01", "ADMINISTRATIVA|B-001|2026-01-01"])

    def test_a_malformed_row_names_why(self):
        base = "ADMINISTRATIVA,A-1,Descripción,,10,,,,Base legal,2026-01-01,Carga de prueba"
        for fila, motivo in [
            (base.replace(",10,", ",0,"), "porcentaje_uit es una alícuota"),
            (base.replace(",10,", ",100.01,"), "porcentaje_uit es una alícuota"),
            (base.replace(",10,,", ",10,cero,"), "porcentaje_uit_segunda es una alícuota"),
            (base.replace("A-1", "A" * 21), "codigo tiene más de 20"),
            (base.replace("Base legal", "B" * 201), "base_legal tiene más de 200"),
            (base.replace("Base legal", ""), "falta base_legal"),
            (base.replace("2026-01-01", "01/01/2026"), "vigencia_desde no es una fecha"),
            (base.replace("Carga de prueba", "Ok"), "la observacion tiene 2 caracteres"),
            (base.replace("ADMINISTRATIVA", "TRANSITO"), "la familia es una de ADMINISTRATIVA"),
        ]:
            with self.subTest(motivo=motivo):
                malas = ic.errores(ic.read_cuis(self.archivo(f"{CABECERA}\n{fila}\n")))
                self.assertTrue(any(motivo in m for m in malas), malas)

    def test_a_repeated_version_is_refused(self):
        fila = "ADMINISTRATIVA,A-1,Descripción,,10,,,,Base legal,2026-01-01,Carga de prueba\n"
        self.assertEqual(ic.errores(ic.read_cuis(self.archivo(f"{CABECERA}\n{fila}{fila.replace('A-1', 'a-1')}"))),
                         ["la versión ADMINISTRATIVA|A-1|2026-01-01 está repetida"])


class LoadTests(CuisTestCase):
    def test_first_load_sends_each_version_to_the_service(self):
        code, out, err = self.run_main(CUIS)
        self.assertEqual(code, 0, err)
        # never the generic api: codigo_infraccion is written only by srtm's service (403 there)
        self.assertEqual(self.writes(), [("POST", ic.ENDPOINT), ("POST", ic.ENDPOINT)])
        versiones = self.versiones()
        self.assertEqual(set(versiones), {"ADMINISTRATIVA|A-042|2026-01-01", "ADMINISTRATIVA|B-001|2026-01-01"})
        self.assertEqual(versiones["ADMINISTRATIVA|A-042|2026-01-01"]["clave_vigente"], "ADMINISTRATIVA|A-042")
        self.assertIn("  create ADMINISTRATIVA|A-042|2026-01-01: 10% UIT", out)
        self.assertIn(f"{OBJECT}: 2 created, 0 closed, 0 skipped", out)

    def test_a_second_run_changes_nothing(self):
        self.run_main(CUIS)
        self.core.requests.clear()
        code, out, err = self.run_main(CUIS)
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn(f"{OBJECT}: 0 created, 0 closed, 2 skipped", out)

    def test_core_reads_the_percentages_back_as_numbers(self):
        # core answers a DECIMAL as a number: 25.5 for "25.50", the same value
        self.core.add_record(OBJECT, {"familia": "ADMINISTRATIVA", "codigo": "B-001", "descripcion": "Arrojar desmonte en la vía",
                                      "materia": "LIMPIEZA", "porcentaje_uit": 25.50, "base_legal": "Ordenanza ficticia 001",
                                      "vigencia_desde": "2026-01-01", "observacion": "Otra observación",
                                      "clave": "ADMINISTRATIVA|B-001|2026-01-01", "clave_vigente": "ADMINISTRATIVA|B-001"})
        code, out, err = self.run_main(CUIS)
        self.assertEqual(code, 0, err)
        self.assertIn(f"{OBJECT}: 1 created, 0 closed, 1 skipped", out)

    def test_a_new_version_closes_the_one_in_force_and_is_added(self):
        self.run_main(CUIS)
        self.core.requests.clear()
        code, out, err = self.run_main(CUIS + NUEVA)
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("POST", ic.ENDPOINT)])
        versiones = self.versiones()
        anterior, nueva = versiones["ADMINISTRATIVA|A-042|2026-01-01"], versiones["ADMINISTRATIVA|A-042|2026-07-01"]
        self.assertEqual((anterior["vigencia_hasta"], anterior["clave_vigente"]), ("2026-06-30", None))
        self.assertEqual((nueva.get("vigencia_hasta"), nueva["clave_vigente"]), (None, "ADMINISTRATIVA|A-042"))
        self.assertEqual(anterior["porcentaje_uit"], "10", "the closed version keeps its figures")
        self.assertIn("  close  ADMINISTRATIVA|A-042|2026-01-01: vigencia_hasta 2026-06-30", out)
        self.assertIn(f"{OBJECT}: 1 created, 1 closed, 2 skipped", out)

    def test_two_versions_in_one_file_go_in_order(self):
        code, out, err = self.run_main(CUIS.rstrip("\n").replace("\nADMINISTRATIVA,a-042", "\n" + NUEVA + "ADMINISTRATIVA,a-042") + "\n")
        self.assertEqual(code, 0, err)
        posts = [b["vigencia_desde"] for m, p, _, b in self.core.requests if p == ic.ENDPOINT and b["codigo"] == "A-042"]
        self.assertEqual(posts, ["2026-01-01", "2026-07-01"])
        self.assertEqual(self.versiones()["ADMINISTRATIVA|A-042|2026-01-01"]["vigencia_hasta"], "2026-06-30")
        self.assertIn(f"{OBJECT}: 3 created, 1 closed, 0 skipped", out)

    def test_dry_run_reads_core_and_writes_nothing(self):
        self.run_main(CUIS)
        self.core.requests.clear()
        code, out, err = self.run_main(CUIS + NUEVA, "--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIsNone(self.versiones()["ADMINISTRATIVA|A-042|2026-01-01"].get("vigencia_hasta"))
        self.assertIn("  close  ADMINISTRATIVA|A-042|2026-01-01: vigencia_hasta 2026-06-30", out)
        self.assertIn("dry run: 1 to create, 1 to close, 2 skipped; nothing written", out)

    def test_a_changed_version_is_not_overwritten(self):
        # a change is a new version, with another vigencia_desde: the stored one stays as it was read
        self.run_main(CUIS)
        self.core.requests.clear()
        code, out, err = self.run_main(CUIS.replace(",10,15,20,", ",11,15,20,"))
        self.assertEqual(code, 2)
        self.assertIn("la versión ADMINISTRATIVA|A-042|2026-01-01 ya existe con otro porcentaje_uit", err)
        self.assertEqual(self.writes(), [])

    def test_a_version_not_after_the_one_in_force_exits_2_before_writing(self):
        self.run_main(CUIS + NUEVA)
        self.core.requests.clear()
        code, out, err = self.run_main(CUIS + NUEVA + NUEVA.replace("2026-07-01", "2026-03-01").replace(",12,", ",11,"))
        self.assertEqual(code, 2)
        self.assertIn("la versión ADMINISTRATIVA|A-042|2026-03-01 no es posterior a la vigente, que rige desde 2026-07-01", err)
        self.assertEqual(self.writes(), [])

    def test_a_malformed_file_exits_2_before_calling_core(self):
        code, out, err = self.run_main(CUIS.replace(",25.5,", ",-1,"))
        self.assertEqual(code, 2)
        self.assertIn("porcentaje_uit es una alícuota mayor que 0 y hasta 100", err)
        self.assertEqual(self.core.requests, [])

    def test_a_refusal_exits_1(self):
        self.core.fail_on_record = OBJECT
        code, out, err = self.run_main(CUIS)
        self.assertEqual(code, 1)
        self.assertIn("boom-cuis", err)


if __name__ == "__main__":
    unittest.main()
