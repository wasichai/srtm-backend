"""Tests for migrar_usos_padron.py: the padrón's grupos de uso moved to the srtm's clase, sub clase and uso, the report,
an idempotent run against a fake Core, and the order the README gives with apply.py (wasichai/srtm-backend#31).

Run: cd model && python3 -m unittest -v test_migrar_usos_padron
"""
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import migrar_usos_padron as mup
from fake_core import FakeCore
from import_predios import USOS_DEL_PADRON
from test_apply import ApplyCliTestCase, core_fields, load_model

CATALOGO = {("RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"), ("BIENES COMUNES", "COMERCIAL", "CENTRO COMERCIAL"),
            ("INSTITUCIONAL", "ASOCIACIÓN O FUNDACIÓN", "COMERCIAL")}


def dj(record_id, **attributes):
    return {"id": record_id, "attributes": {"anio": 2026, "secuencia_uso": "001", **attributes}}


class PlanTests(unittest.TestCase):
    def test_the_residential_grupo_is_casa_habitacion_and_any_other_its_clase(self):
        updates, rows = mup.plan([
            dj("d1", uso="RESIDENCIAL - CASA HABITACION", area_terreno="120.00"),
            dj("d2", uso="TERRENO"),
            dj("d3", uso="COMERCIAL"),
        ], CATALOGO)
        self.assertEqual({u.record_id: u.attributes for u in updates}, {
            "d1": {"anio": 2026, "secuencia_uso": "001", "area_terreno": "120.00",
                   "clase_uso": "RESIDENCIAL", "sub_clase_uso": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"},
            "d2": {"anio": 2026, "secuencia_uso": "001", "clase_uso": "TERRENO", "sub_clase_uso": None, "uso": None},
            "d3": {"anio": 2026, "secuencia_uso": "001", "clase_uso": "COMERCIAL", "sub_clase_uso": None, "uso": None},
        })
        self.assertEqual([(r["id"], r["grupo"], r["clase_uso"], r["sub_clase_uso"], r["uso"], r["nota"]) for r in rows], [
            ("d1", "RESIDENCIAL - CASA HABITACION", "RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN", ""),
            ("d2", "TERRENO", "TERRENO", "", "", ""),
            ("d3", "COMERCIAL", "COMERCIAL", "", "", ""),
        ])

    def test_an_srtm_uso_is_left_alone(self):
        updates, rows = mup.plan([
            dj("d1", clase_uso="RESIDENCIAL", sub_clase_uso="UNIFAMILIAR", uso="CASA HABITACIÓN"),
            dj("d2", clase_uso="TERRENO"),
            dj("d3"),
            # COMERCIAL is also a uso of the catalog: under its clase and sub clase it is the srtm's
            dj("d4", clase_uso="INSTITUCIONAL", sub_clase_uso="ASOCIACIÓN O FUNDACIÓN", uso="COMERCIAL"),
        ], CATALOGO)
        self.assertEqual((updates, rows), ([], []))

    def test_a_clase_already_there_is_not_overwritten_and_is_reported(self):
        updates, rows = mup.plan([
            dj("d1", numero_declaracion=39147, clase_uso="RESIDENCIAL", uso="TERRENO"),
            dj("d2", sub_clase_uso="UNIFAMILIAR", uso="RESIDENCIAL - CASA HABITACION"),
            dj("d3", clase_uso="RESIDENCIAL", sub_clase_uso="UNIFAMILIAR", uso="COMERCIAL"),
        ], CATALOGO)
        self.assertEqual(updates, [])
        self.assertEqual([(r["id"], r["numero_declaracion"], r["grupo"], r["clase_uso"], r["sub_clase_uso"]) for r in rows], [
            ("d1", 39147, "TERRENO", "RESIDENCIAL", ""),
            ("d2", "", "RESIDENCIAL - CASA HABITACION", "", "UNIFAMILIAR"),
            ("d3", "", "COMERCIAL", "RESIDENCIAL", "UNIFAMILIAR"),
        ])
        self.assertTrue(all(r["nota"].startswith("ya tiene clase o sub clase de uso: no se pisa") for r in rows))

    def test_a_second_run_changes_nothing(self):
        declaraciones = [dj(f"d{i}", uso=grupo) for i, grupo in enumerate(USOS_DEL_PADRON)]
        updates, _ = mup.plan(declaraciones, CATALOGO)
        self.assertEqual(len(updates), 10)
        migradas = [{"id": u.record_id, "attributes": u.attributes} for u in updates]
        self.assertEqual(mup.plan(migradas, CATALOGO), ([], []))


class MigrarCliTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.casa = self.core.add_record(
            "declaracion_predial",
            {"numero_declaracion": 1, "anio": 2026, "secuencia_uso": "001", "predio": "p1", "contribuyente": "c1",
             "uso": "RESIDENCIAL - CASA HABITACION", "area_terreno": 120.5},
        )
        self.terrenos = [
            self.core.add_record("declaracion_predial", {"anio": 2026, "secuencia_uso": f"00{i}", "uso": "TERRENO"}) for i in (1, 2)
        ]
        self.portal = self.core.add_record(
            "declaracion_predial", {"anio": 2026, "secuencia_uso": "001", "clase_uso": "COMERCIAL", "uso": "COMERCIAL"}
        )
        self.srtm = self.core.add_record(
            "declaracion_predial",
            {"anio": 2026, "secuencia_uso": "001", "clase_uso": "RESIDENCIAL", "sub_clase_uso": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"},
        )
        self.tmp = tempfile.TemporaryDirectory()
        self.report = os.path.join(self.tmp.name, "reports", "migrar_usos_padron.csv")

    def tearDown(self):
        self.core.stop()
        self.tmp.cleanup()

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = mup.main(["--core", self.core.base_url, "--report", self.report, "--workers", "2", *extra])
        return code, out.getvalue(), err.getvalue()

    def puts(self):
        return [r for r in self.core.requests if r[0] == "PUT"]

    def report_rows(self):
        with open(self.report, encoding="utf-8") as f:
            return list(csv.DictReader(f))

    def test_dry_run_counts_each_grupo_and_changes_nothing(self):
        code, out, err = self.run_main("--dry-run")
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.puts(), [])
        self.assertIn("declaraciones: 5 leídas, 3 por migrar", out)
        self.assertIn("  TERRENO: 2 -> TERRENO", out)
        self.assertIn("  RESIDENCIAL - CASA HABITACION: 1 -> RESIDENCIAL / UNIFAMILIAR / CASA HABITACIÓN", out)
        # the one the portal saved with a clase: not counted, reported
        self.assertNotIn("  COMERCIAL:", out)
        self.assertIn(f"notas: 1 -> {self.report}", out)
        rows = self.report_rows()
        self.assertEqual(len(rows), 4)
        [nota] = [r for r in rows if r["nota"]]
        self.assertEqual((nota["id"], nota["grupo"], nota["clase_uso"]), (self.portal["id"], "COMERCIAL", "COMERCIAL"))
        self.assertEqual(self.casa["attributes"]["uso"], "RESIDENCIAL - CASA HABITACION")

    def test_migrates_keeping_every_other_field_then_is_idempotent(self):
        code, out, err = self.run_main()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(
            sorted(p[1] for p in self.puts()),
            sorted(f"/api/objects/declaracion_predial/records/{r['id']}" for r in [self.casa, *self.terrenos]),
        )
        # core's update replaces every field: what the migration does not touch goes back as it was, and what it
        # leaves empty is not sent (the terreno's uso is cleared)
        self.assertEqual(self.casa["attributes"], {
            "numero_declaracion": 1, "anio": 2026, "secuencia_uso": "001", "predio": "p1", "contribuyente": "c1", "area_terreno": 120.5,
            "clase_uso": "RESIDENCIAL", "sub_clase_uso": "UNIFAMILIAR", "uso": "CASA HABITACIÓN",
        })
        self.assertEqual(self.terrenos[0]["attributes"], {"anio": 2026, "secuencia_uso": "001", "clase_uso": "TERRENO"})
        self.assertEqual(self.portal["attributes"]["uso"], "COMERCIAL")
        self.assertIn("done: 3 updated", out)

        self.core.requests.clear()
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertEqual(self.puts(), [])
        self.assertIn("declaraciones: 5 leídas, 0 por migrar", out)
        self.assertIn("done: 0 updated", out)

    def test_a_refusal_stops_with_the_record(self):
        self.core.fail_on_update = "declaracion_predial"
        code, _, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("error PUT declaracion_predial", err)
        self.assertIn("boom-update", err)


class WithApplyTests(ApplyCliTestCase):
    """The order the README gives for a Core with the padrón: apply.py, the migration, apply.py again."""

    def setUp(self):
        model = load_model()
        # the uso enum as Core has it before: the ten grupos, then the catalog's usos
        antes = list(USOS_DEL_PADRON) + [u for u in model["enums"]["uso"] if u not in USOS_DEL_PADRON]
        fields = {o["name"]: core_fields(model, o["name"]) for o in model["objects"]}
        for name in ("declaracion_predial", "uso_predio"):
            fields[name] = core_fields(model, name, options={"uso": antes})
        self.core = FakeCore(
            existing_objects=[o["name"] for o in model["objects"]],
            existing_relationships=[r["name"] for r in model["relationships"]],
            existing_fields=fields,
        )
        self.addCleanup(self.core.stop)
        for grupo in ("RESIDENCIAL - CASA HABITACION", "TERRENO", "TERRENO", "COMERCIAL"):
            self.core.add_record("declaracion_predial", {"anio": 2026, "secuencia_uso": "001", "uso": grupo})
        self.core.add_record("uso_predio", {"codigo": "010101", "clase": "RESIDENCIAL", "sub_clase": "UNIFAMILIAR", "uso": "CASA HABITACIÓN"})
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.report = os.path.join(tmp.name, "migrar_usos_padron.csv")

    def uso_puts(self):
        puts = [(r[1], r[3]["enumOptions"]) for r in self.core.requests if r[0] == "PUT" and "enumOptions" in (r[3] or {})]
        self.core.requests.clear()
        return dict(puts)

    def test_the_grupos_leave_once_no_declaration_uses_them(self):
        grupos = [g for g in USOS_DEL_PADRON if g not in ("COMERCIAL", "INDUSTRIA")]
        declaracion = "/api/metadata/objects/declaracion_predial/fields/uso"
        catalogo = "/api/metadata/objects/uso_predio/fields/uso"

        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertIn("keep   option declaracion_predial.uso RESIDENCIAL - CASA HABITACION: 1 record uses it", out)
        self.assertIn("keep   option declaracion_predial.uso TERRENO: 2 records use it", out)
        puts = self.uso_puts()
        self.assertEqual([g for g in grupos if g in puts[declaracion]], ["RESIDENCIAL - CASA HABITACION", "TERRENO"])
        # the catalog never used them: they leave it now
        self.assertEqual([g for g in grupos if g in puts[catalogo]], [])

        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = mup.main(["--core", self.core.base_url, "--report", self.report])
        self.assertEqual(code, 0, msg=err.getvalue())
        self.assertIn("declaraciones: 4 leídas, 4 por migrar", out.getvalue())
        self.core.requests.clear()

        code, out, err = self.run_cli([])
        self.assertEqual(code, 0, msg=err)
        self.assertNotIn("keep   option", out)
        puts = self.uso_puts()
        self.assertEqual([g for g in grupos if g in puts[declaracion]], [])
        # COMERCIAL and INDUSTRIA are usos of the catalog too: they stay
        self.assertIn("COMERCIAL", puts[declaracion])
        self.assertIn("INDUSTRIA", puts[declaracion])


if __name__ == "__main__":
    unittest.main()
