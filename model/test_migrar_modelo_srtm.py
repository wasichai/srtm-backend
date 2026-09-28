"""Tests for migrar_modelo_srtm.py: the model as the srtm's manuals have it. A predio's tipo_predio from the old
condicion, a SUCESION document moved to the sucesión indivisa it is, the report, an idempotent run, and the old
condicion field deleted only when asked and only once every predio has its tipo_predio.

Run: cd model && python3 -m unittest -v test_migrar_modelo_srtm
"""
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import migrar_modelo_srtm as mms
from fake_core import FakeCore


def record(record_id, **attributes):
    return {"id": record_id, "attributes": attributes}


class PlanTests(unittest.TestCase):
    def test_the_old_condicion_becomes_tipo_predio(self):
        records = {
            "predio": [
                record("p1", codigo="01-01-0001", condicion="URBANO"),
                record("p2", codigo="90-01-0001", condicion="RUSTICO"),
                record("p3", codigo="01-01-0002", condicion="URBANO", tipo_predio="PREDIO URBANO"),  # done already
                record("p4", codigo="P-000001", tipo_predio="PREDIO URBANO"),  # the portal's, never had condicion
                record("p5", codigo="01-01-0003"),  # neither: nothing to take it from
            ],
        }
        updates, rows = mms.plan(records)
        self.assertEqual([(u.record_id, u.attributes["tipo_predio"]) for u in updates], [("p1", "PREDIO URBANO"), ("p2", "PREDIO RUSTICO")])
        # every field goes back: Core's update replaces them all
        self.assertEqual(updates[0].attributes["codigo"], "01-01-0001")
        self.assertEqual([r["clave"] for r in rows if r["nota"]], ["01-01-0003"])

    def test_a_sucesion_document_moves_to_the_tipo_de_contribuyente(self):
        records = {
            "contribuyente": [
                record("c1", numero_documento="151", tipo_documento="SUCESION", tipo_persona="SUCESION"),
                record("c2", numero_documento="152", tipo_documento="SUCESION", tipo_contribuyente="SUCESION INDIVISA"),
                record("c3", numero_documento="20529936", tipo_documento="DNI"),
            ],
            "relacionado": [record("r1", numero_documento="9", tipo_documento="SUCESION")],
            "transferente": [record("t1", numero_documento="8", tipo_documento="DNI")],
        }
        updates, rows = mms.plan(records)
        by_id = {u.record_id: u.attributes for u in updates}
        self.assertEqual(sorted(by_id), ["c1", "c2", "r1"])
        self.assertEqual((by_id["c1"]["tipo_documento"], by_id["c1"]["tipo_contribuyente"]), ("SIN DOCUMENTO", "SUCESION INDIVISA"))
        # the number stays: the key import_predios.py knows it by
        self.assertEqual(by_id["c1"]["numero_documento"], "151")
        self.assertEqual(by_id["c2"]["tipo_contribuyente"], "SUCESION INDIVISA")
        # a relacionado or transferente has no tipo de contribuyente
        self.assertEqual(by_id["r1"]["tipo_documento"], "SIN DOCUMENTO")
        self.assertNotIn("tipo_contribuyente", by_id["r1"])
        self.assertEqual(len([r for r in rows if not r["nota"]]), 3)


class CliTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.core.add_record("predio", {"codigo": "01-01-0001", "condicion": "URBANO"})
        self.core.add_record("contribuyente", {"numero_documento": "151", "tipo_documento": "SUCESION"})
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.report = os.path.join(tmp.name, "reports", "migrar_modelo_srtm.csv")

    def run_cli(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = mms.main(["--core", self.core.base_url, "--report", self.report, "--workers", "2", *extra])
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(r[0], r[1]) for r in self.core.requests if r[0] in ("PUT", "DELETE")]

    def test_dry_run_reports_and_changes_nothing(self):
        code, out, err = self.run_cli("--dry-run", "--borrar-condicion")
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.writes(), [])
        self.assertIn("predio: 1 tipo_predio", out)
        self.assertIn("contribuyente: 1 sucesion", out)
        self.assertIn("predio.condicion: se borraría", out)
        with open(self.report, encoding="utf-8") as f:
            self.assertEqual(len(list(csv.DictReader(f))), 2)

    def test_migrates_then_is_idempotent_and_keeps_condicion_unless_asked(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.core.records["predio"][0]["attributes"]["tipo_predio"], "PREDIO URBANO")
        contrib = self.core.records["contribuyente"][0]["attributes"]
        self.assertEqual((contrib["tipo_documento"], contrib["tipo_contribuyente"]), ("SIN DOCUMENTO", "SUCESION INDIVISA"))
        self.assertNotIn("DELETE", [m for m, _ in self.writes()])
        self.assertIn("done: 2 updated", out)

        self.core.requests.clear()
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.writes(), [])

    def test_deletes_condicion_only_once_every_predio_has_tipo_predio(self):
        code, out, err = self.run_cli("--borrar-condicion")
        self.assertEqual(code, 0, msg=err)
        # the updates first, then the field: none is left without its tipo_predio
        self.assertEqual(self.writes()[-1], ("DELETE", "/api/metadata/objects/predio/fields/condicion"))
        self.assertIn("predio.condicion: borrado", out)

    def test_a_predio_left_without_tipo_predio_keeps_condicion(self):
        self.core.add_record("predio", {"codigo": "01-01-0002", "condicion": "OTRA"})
        code, out, err = self.run_cli("--borrar-condicion")
        self.assertEqual(code, 1)
        self.assertIn("01-01-0002", err)
        self.assertNotIn("DELETE", [m for m, _ in self.writes()])


if __name__ == "__main__":
    unittest.main()
