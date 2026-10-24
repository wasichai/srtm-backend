"""Tests for recalcular_obras_padron.py: who it asks, the summary it computes and that --dry-run writes nothing.

Run: cd model && python3 -m unittest -v test_recalcular_obras_padron
"""
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from decimal import Decimal

import recalcular_obras_padron as rop
from fake_core import FakeCore

OBJECT = "declaracion_predial"


class PureTests(unittest.TestCase):
    def test_clasificar(self):
        self.assertEqual(rop.clasificar("100.00", "101.50"), "cambia")
        self.assertEqual(rop.clasificar("50.00", "50.00"), "igual")
        self.assertEqual(rop.clasificar("70.00", None), "sin_valor")

    def test_the_summary_counts_changes_their_sum_and_those_within_a_sol(self):
        filas = [
            {"valor_autoavaluo": "100.00", "sugerido": "101.50", "motivo": None},
            {"valor_autoavaluo": "50.00", "sugerido": "50.00", "motivo": None},
            {"valor_autoavaluo": "70.00", "sugerido": None, "motivo": "ARANCEL_FALTANTE"},
        ]
        resultado = rop.resumen(filas)
        self.assertEqual(resultado["con_obras"], 3)
        self.assertEqual(resultado["valuadas"], 2)
        self.assertEqual(resultado["cambian"], 1)
        self.assertEqual(resultado["suma_delta"], Decimal("1.50"))
        self.assertEqual(resultado["a_un_sol"], 1)
        self.assertEqual(resultado["sin_valor"], {"ARANCEL_FALTANTE": 1})


class RecalcularTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        fd, self.report_path = tempfile.mkstemp(suffix=".csv")
        os.close(fd)
        self.addCleanup(os.unlink, self.report_path)

    def declaracion(self, valor_autoavaluo, valor_otras_instalaciones, estado=None):
        return self.core.add_record(OBJECT, {
            "anio": 2026, "valor_autoavaluo": valor_autoavaluo, "valor_otras_instalaciones": valor_otras_instalaciones,
            "estado": estado,
        })

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = rop.main(["--core", self.core.base_url, "--anio", "2026", "--report", self.report_path, *extra])
        return code, out.getvalue(), err.getvalue()

    def gets(self):
        return [p for m, p, _, _ in self.core.requests if m == "GET" and p.endswith("/autoavaluo-sugerido")]

    def posts(self):
        return [p for m, p, _, _ in self.core.requests if m == "POST" and p.endswith("/recalcular")]

    def filas_del_reporte(self):
        with open(self.report_path, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))


class MainTests(RecalcularTestCase):
    def test_a_dj_without_otras_instalaciones_is_not_asked(self):
        self.declaracion("100.00", "0.00")
        self.declaracion("100.00", None)
        self.declaracion("100.00", "50.00", estado="ANULADA")  # no vigente: tampoco se pregunta
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.gets(), [])
        self.assertIn("con_obras: 0", out)

    def test_dry_run_posts_nothing(self):
        d = self.declaracion("100.00", "50.00")
        self.core.sugeridos[d["id"]] = {"autoavaluo": "101.50", "motivo": None}
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(len(self.gets()), 1)
        self.assertEqual(self.posts(), [])
        self.assertIn("con_obras: 1, valuadas: 1, cambian: 1, suma_delta: S/ 1.50, a_un_sol: 1", out)

    def test_without_dry_run_only_the_changed_are_recalculated(self):
        cambia = self.declaracion("100.00", "50.00")
        igual = self.declaracion("50.00", "50.00")
        sin_valor = self.declaracion("70.00", "50.00")
        self.core.sugeridos[cambia["id"]] = {"autoavaluo": "101.50", "motivo": None}
        self.core.sugeridos[igual["id"]] = {"autoavaluo": "50.00", "motivo": None}
        # sin_valor is left out of self.core.sugeridos: the GET answers 404
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.posts(), [f"/api/srtm/declaraciones/{cambia['id']}/recalcular"])
        self.assertNotIn(f"/api/srtm/declaraciones/{igual['id']}/recalcular", self.posts())
        self.assertNotIn(f"/api/srtm/declaraciones/{sin_valor['id']}/recalcular", self.posts())

    def test_the_report_has_ids_and_amounts_only(self):
        d = self.declaracion("100.00", "50.00")
        self.core.sugeridos[d["id"]] = {"autoavaluo": "101.50", "motivo": None}
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 0, err)
        filas = self.filas_del_reporte()
        self.assertEqual(len(filas), 1)
        self.assertEqual(list(filas[0].keys()), rop.REPORT_COLUMNS)
        self.assertEqual(filas[0]["declaracion"], d["id"])
        self.assertEqual(filas[0]["valor_autoavaluo"], "100.00")
        self.assertEqual(filas[0]["sugerido"], "101.50")
        self.assertEqual(filas[0]["delta"], "1.50")
        self.assertEqual(filas[0]["motivo"], "")

    def test_a_refusal_exits_1(self):
        self.declaracion("100.00", "50.00")
        self.core.login_response = {}  # no token: login raises CoreError
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 1)
        self.assertIn("no token in response", err)


if __name__ == "__main__":
    unittest.main()
