"""Tests for import_parametros.py: the shipped copy of normativa's rows and an idempotent load into a fake Core.

Run: cd model && python3 -m unittest -v
"""
import csv
import io
import os
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_parametros as ip
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
SHIPPED = os.path.join(HERE, "data", "parametros-predial.csv")
# the verified source, in a checkout of normativa next to this repo or at $NORMATIVA (not in CI)
NORMATIVA = os.path.join(os.environ.get("NORMATIVA", os.path.join(HERE, "..", "..", "normativa")),
                         "docs", "10-negocio", "valores-normativos", "publicacion", "parametros-2026.csv")
OBJECT = "parametro_tributario"


class ShippedParametrosTests(unittest.TestCase):
    def setUp(self):
        self.parametros = ip.read_parametros(SHIPPED)

    def test_the_predial_rows_and_nothing_else(self):
        tipos = [p["tipo"] for p in self.parametros]
        self.assertEqual(len(self.parametros), 13)
        self.assertEqual(set(tipos), {"UIT", "TRAMO_PREDIAL", "TRAMO_PREDIAL_LIMITE", "PREDIAL_MINIMO", "DEDUCCION_PENSIONISTA",
                                      "DEDUCCION_ADULTO_MAYOR"})
        self.assertEqual(tipos.count("UIT"), 5)
        self.assertEqual(tipos.count("TRAMO_PREDIAL"), 3)
        self.assertEqual(tipos.count("TRAMO_PREDIAL_LIMITE"), 2)

    def test_a_row_as_attributes(self):
        uit = next(p for p in self.parametros if p["tipo"] == "UIT" and p["vigencia_desde"] == "2026-01-01")
        # empty cells are left out: core stores no empty strings
        self.assertEqual(uit, {"tipo": "UIT", "vigencia_desde": "2026-01-01", "vigencia_hasta": "2026-12-31", "valor_numerico": "5500.00",
                               "norma": "D.S. N.° 301-2025-EF", "fuente": "uit.md", "transcribio": "JNA", "verifico": "HNA"})

    def test_every_row_is_signed_twice(self):
        for p in self.parametros:
            self.assertTrue(p["transcribio"] and p["verifico"] and p["transcribio"] != p["verifico"], p)

    def test_natural_keys_are_unique(self):
        keys = [ip.key(p) for p in self.parametros]
        self.assertEqual(len(keys), len(set(keys)))

    @unittest.skipUnless(os.path.exists(NORMATIVA), "normativa is not checked out next to this repo")
    def test_every_row_is_a_copy_of_normativa(self):
        with open(NORMATIVA, encoding="utf-8") as f:
            source = {(r["tipo"], r["clave"], r["vigencia_desde"]): r for r in csv.DictReader(line for line in f if not line.startswith("#"))}
        columns = {"texto": "valor_texto", "norma": "documento_fuente", "fuente": "archivo_del_corpus"}
        for p in self.parametros:
            row = source[(p["tipo"], p.get("clave", ""), p["vigencia_desde"])]
            for field in ("vigencia_hasta", "valor_numerico", "texto", "norma", "fuente", "transcribio", "verifico"):
                self.assertEqual(p.get(field, ""), row[columns.get(field, field)], f"{ip.key(p)} {field}")


class LoadTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.parametros = ip.read_parametros(SHIPPED)

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ip.main(["--core", self.core.base_url, "--csv", SHIPPED, *extra])
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p) for m, p, _, _ in self.core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def test_first_load_creates_every_row(self):
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        stored = [r["attributes"] for r in self.core.records[OBJECT]]
        self.assertEqual(sorted(map(ip.key, stored)), sorted(map(ip.key, self.parametros)))
        self.assertIn({"tipo": "TRAMO_PREDIAL", "clave": "2", "vigencia_desde": "2004-11-15", "valor_numerico": "0.6",
                       "texto": "Más de 15 UIT y hasta 60 UIT; 0.6%",
                       "norma": "TUO de la Ley de Tributación Municipal (D.S. 156-2004-EF), artículo 13",
                       "fuente": "predial-tramos-y-alicuotas.md", "transcribio": "JNA", "verifico": "HNA"}, stored)
        self.assertIn(f"{OBJECT}: 13 created, 0 updated, 0 skipped", out)
        # the report: one line per row it writes
        self.assertIn("  create UIT 2026-01-01: 5500.00", out)

    def test_a_second_run_changes_nothing(self):
        self.run_main()
        self.core.requests.clear()
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn(f"{OBJECT}: 0 created, 0 updated, 13 skipped", out)

    def test_a_second_run_skips_what_core_reads_back_as_numbers(self):
        # core answers a DECIMAL as a number: 5500.00 comes back 5500.0, and it is the same value
        for p in self.parametros:
            self.core.add_record(OBJECT, {**p, "valor_numerico": float(p["valor_numerico"])})
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn(f"{OBJECT}: 0 created, 0 updated, 13 skipped", out)

    def test_a_changed_row_is_updated_in_place(self):
        self.run_main()
        record = next(r for r in self.core.records[OBJECT] if r["attributes"]["tipo"] == "UIT" and r["attributes"]["vigencia_desde"] == "2026-01-01")
        record["attributes"]["valor_numerico"] = "5000.00"
        self.core.requests.clear()
        code, out, err = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("PUT", f"/api/objects/{OBJECT}/records/{record['id']}")])
        self.assertEqual(record["attributes"]["valor_numerico"], "5500.00")
        self.assertIn("  update UIT 2026-01-01: valor_numerico 5000.00 -> 5500.00", out)
        self.assertIn(f"{OBJECT}: 0 created, 1 updated, 12 skipped", out)

    def test_dry_run_reads_core_and_writes_nothing(self):
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertNotIn(OBJECT, self.core.records)
        self.assertIn(f"{OBJECT}: 13 to create, 0 to update, 0 skipped", out)
        self.assertIn("dry run: nothing written", out)

    def test_a_refusal_exits_1(self):
        self.core.fail_on_record = OBJECT
        code, out, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("boom-record", err)


if __name__ == "__main__":
    unittest.main()
