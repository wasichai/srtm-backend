"""Tests for migrar_codigos_compartidos.py: a SAT code of distinct predios already loaded at 100/n (#219), against a
fake Core.

Every row here is made up: the real padrón carries names and documents and never enters the repo.

Run: cd model && python3 -m unittest -v test_migrar_codigos_compartidos
"""
import csv
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from datetime import date
from decimal import Decimal

import migrar_codigos_compartidos as mcc
from fake_core import FakeCore
from test_import_padron_ica import construccion, padron, write_xlsx

HERE = os.path.dirname(os.path.abspath(__file__))
P = "000000009-0001-0001"  # two distinct predios under one SAT code
C = "000000002-0001-0001"  # a real condominio: the same predio twice
CAMPOS_BAJA = [{"name": "fecha_baja"}, {"name": "motivo_baja"}]

PADRON = [
    padron(codigo_predio=P, codigo_contribuyente="900001", direccion_del_predio_completa="JR LIMA 100",
           area_terreno="120", valor_predio="50000"),
    padron(codigo_predio=P, codigo_contribuyente="900002", nro_documento="40000002",
           direccion_del_predio_completa="JR LIMA 200", area_terreno="300", valor_predio="90000"),
    padron(codigo_predio=C, codigo_contribuyente="900001", condicion_predio="5"),
    padron(codigo_predio=C, codigo_contribuyente="900002", nro_documento="40000002", condicion_predio="5"),
]


def liquidacion_al_dos_por_mil(core):
    """A stand-in for srtm's GET /contribuyentes/{id}/liquidacion: 0.2 % of the valor afecto of its vigentes DJ."""
    def liquidar(contribuyente, anio):
        base = sum((Decimal(str(r["attributes"].get("valor_afecto") or 0)) for r in core.records.get("declaracion_predial", [])
                    if r["attributes"].get("contribuyente") == contribuyente and str(r["attributes"].get("anio")) == str(anio)
                    and r["attributes"].get("estado") in (None, "", "VIGENTE")), Decimal(0))
        return {"anio": int(anio), "base": str(base), "impuestoAnual": str((base * Decimal("0.002")).quantize(Decimal("0.01"))),
                "faltan": []}
    return liquidar


class MigrarTestCase(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore(existing_fields={"predio": list(CAMPOS_BAJA)})
        self.core.liquidacion = liquidacion_al_dos_por_mil(self.core)
        self.addCleanup(self.core.stop)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.excel = os.path.join(tmp.name, "padron.xlsx")
        self.report = os.path.join(tmp.name, "reports", "codigos_compartidos.csv")
        write_xlsx(self.excel, PADRON, [construccion(codigo_predio=P, codigo_contribuyente="900002")])
        self.ana = self.core.add_record("contribuyente", {"codigo": "900001", "nombre_completo": "QUISPE MAMANI ANA"})
        self.luis = self.core.add_record("contribuyente", {"codigo": "900002", "nombre_completo": "PEREZ ROJAS LUIS"})
        # what a load before #207 left: the bare code, its two DJ at 50/50, each year
        self.bare = self.core.add_record("predio", {"codigo": P, "codigo_origen": P, "direccion": "JR LIMA 100"},
                                         geometries={"lote_geom": {"type": "Polygon", "coordinates": []}})
        self.djs = {}
        for anio in (2025, 2026):
            for persona, autoavaluo, mitad in ((self.ana, "50000.00", "25000.00"), (self.luis, "90000.00", "45000.00")):
                self.djs[(persona["attributes"]["codigo"], anio)] = self.core.add_record("declaracion_predial", {
                    "predio": self.bare["id"], "contribuyente": persona["id"], "anio": anio, "secuencia_uso": "001",
                    "condicion_propiedad": "CONDOMINO", "porcentaje_condominio": "50.00", "valor_autoavaluo": autoavaluo,
                    "valor_condominio": mitad, "deduccion": "0.00", "valor_afecto": mitad, "area_terreno": "120.00"})
        self.nivel = self.core.add_record("nivel_construccion", {"declaracion": self.djs[("900002", 2026)]["id"],
                                                                 "tipo_nivel": "PISO", "numero_piso": 1})
        # the real condominio, loaded at 50/50: never touched
        self.condominio = self.core.add_record("predio", {"codigo": C, "codigo_origen": C})
        for persona in (self.ana, self.luis):
            self.core.add_record("declaracion_predial", {
                "predio": self.condominio["id"], "contribuyente": persona["id"], "anio": 2026, "secuencia_uso": "001",
                "condicion_propiedad": "CONDOMINO", "porcentaje_condominio": "50.00", "valor_autoavaluo": "108427.00",
                "valor_condominio": "54213.50", "deduccion": "0.00", "valor_afecto": "54213.50"})

    def run_cli(self, extra=()):
        args = ["--excel", self.excel, "--core", self.core.base_url, "--report", self.report, "--workers", "2", *extra]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = mcc.main(args)
        return code, out.getvalue(), err.getvalue()

    def writes(self, since=0):
        return [(m, p) for m, p, _, _ in self.core.requests[since:] if m in ("PUT", "PATCH", "DELETE")
                or (m == "POST" and p != "/api/auth/login")]

    def predio(self, codigo):
        return next(r for r in self.core.records["predio"] if r["attributes"]["codigo"] == codigo)

    def report_rows(self):
        with open(self.report, encoding="utf-8") as f:
            return list(csv.DictReader(f))


class DosPrediosTests(MigrarTestCase):
    def test_creates_the_derived_predios(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        for n in (1, 2):
            derivado = self.predio(f"{P}-{n}")["attributes"]
            self.assertEqual(derivado["codigo_origen"], P)
        self.assertEqual(self.predio(f"{P}-2")["attributes"]["direccion"], "JR LIMA 200")

    def test_each_dj_moves_to_its_predio_at_100_propietario_unico(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        for (contribuyente, anio), dj in self.djs.items():
            with self.subTest(contribuyente=contribuyente, anio=anio):
                a = next(r for r in self.core.records["declaracion_predial"] if r["id"] == dj["id"])["attributes"]
                esperado = f"{P}-1" if contribuyente == "900001" else f"{P}-2"
                self.assertEqual(a["predio"], self.predio(esperado)["id"])
                self.assertEqual((a["condicion_propiedad"], a["porcentaje_condominio"]), ("PROPIETARIO UNICO", "100.00"))
                self.assertEqual(a["valor_condominio"], a["valor_autoavaluo"])
                self.assertEqual(a["valor_afecto"], a["valor_autoavaluo"])
                # Core's update replaces every field: what was not migrated is still there
                self.assertEqual((a["anio"], a["secuencia_uso"], a["area_terreno"]), (anio, "001", "120.00"))
        self.assertEqual(len(self.core.records["declaracion_predial"]), 6)

    def test_every_moved_dj_is_recalculated(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        recalculadas = [p for m, p, _, _ in self.core.requests if m == "POST" and p.endswith("/recalcular")]
        self.assertEqual(sorted(recalculadas), sorted(f"/api/srtm/declaraciones/{dj['id']}/recalcular" for dj in self.djs.values()))

    def test_the_bare_predio_is_given_de_baja_and_kept(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        bare = self.predio(P)
        self.assertEqual(bare["attributes"]["fecha_baja"], date.today().isoformat())
        self.assertIn("#207", bare["attributes"]["motivo_baja"])
        self.assertIn(f"{P}-1", bare["attributes"]["motivo_baja"])
        self.assertIn(f"{P}-2", bare["attributes"]["motivo_baja"])
        # a merge: what it had stays, its lote too
        self.assertEqual(bare["attributes"]["direccion"], "JR LIMA 100")
        self.assertIn("geometries", bare)

    def test_the_niveles_still_hang_from_the_same_dj(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        [nivel] = self.core.records["nivel_construccion"]
        self.assertEqual(nivel["attributes"]["declaracion"], self.djs[("900002", 2026)]["id"])
        self.assertFalse([p for m, p, _, _ in self.core.requests if "nivel_construccion" in p and m != "GET"])

    def test_the_summary_has_the_delta_of_the_base_and_of_the_impuesto(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        # 25 000 + 45 000 at 50 % -> 50 000 + 90 000 at 100 %
        self.assertIn("Δ base imponible 2026: S/ 70000.00 -> S/ 140000.00 (Δ S/ 70000.00)", out)
        self.assertIn("Δ base imponible 2025: S/ 70000.00 -> S/ 140000.00 (Δ S/ 70000.00)", out)
        # the liquidación read before and after: 0.2 % of 70 000 more, the condominio's DJ in both
        self.assertIn("Δ impuesto 2026: S/ 140.00 (2 contribuyentes)", out)

    def test_the_report_lists_the_moves_and_stdout_names_no_one(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        rows = self.report_rows()
        self.assertEqual(list(rows[0]), mcc.REPORT_COLUMNS)
        movidas = [r for r in rows if r["categoria"] == "dj_movida"]
        self.assertEqual(len(movidas), 4)
        self.assertEqual({r["codigo_predio"] for r in movidas}, {f"{P}-1", f"{P}-2"})
        self.assertEqual({r["codigo_contribuyente"] for r in movidas}, {"900001", "900002"})
        for dato in ("900001", "900002", "QUISPE", "PEREZ", "40000002"):
            self.assertNotIn(dato, out)
        self.assertFalse(any("QUISPE" in v or "PEREZ" in v for r in rows for v in r.values()))


class CondominioRealTests(MigrarTestCase):
    def test_a_real_condominio_is_not_touched(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        condominio = self.predio(C)
        self.assertNotIn("fecha_baja", condominio["attributes"])
        djs = [r["attributes"] for r in self.core.records["declaracion_predial"] if r["attributes"]["predio"] == condominio["id"]]
        self.assertEqual([a["porcentaje_condominio"] for a in djs], ["50.00", "50.00"])
        self.assertFalse(any(condominio["id"] in p or C + "-" in json.dumps(b) for m, p, _, b in self.core.requests if m != "GET"))


class DryRunTests(MigrarTestCase):
    def test_the_dry_run_writes_nothing_and_counts_right(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.writes(), [])
        for line in ("códigos compartidos: 1 en el padrón, 1 en Core", "migrables: 1", "no migrables: 0",
                     "predios a crear: 2 (0 ya existen)", "DJ a mover: 4", "predios a dar de baja: 1",
                     "Δ base imponible 2026: S/ 70000.00 -> S/ 140000.00 (Δ S/ 70000.00)"):
            self.assertIn(line, out)
        self.assertNotIn("Δ impuesto", out)
        self.assertEqual(len([r for r in self.report_rows() if r["categoria"] == "dj_movida"]), 4)

    def test_a_core_without_the_baja_fields_exits_2_and_writes_nothing(self):
        self.core.existing_fields["predio"] = []
        code, out, err = self.run_cli()
        self.assertEqual(code, 2)
        self.assertIn("fecha_baja", err)
        self.assertEqual(self.writes(), [])
        # the dry-run still says what it would do
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 2)
        self.assertIn("DJ a mover: 4", out)
        self.assertEqual(self.writes(), [])


class IdempotenciaTests(MigrarTestCase):
    def test_a_second_run_changes_nothing(self):
        self.assertEqual(self.run_cli()[0], 0)
        antes = json.dumps(self.core.records, sort_keys=True)
        since = len(self.core.requests)
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertEqual(self.writes(since), [])
        self.assertEqual(json.dumps(self.core.records, sort_keys=True), antes)
        self.assertIn("ya migrados: 1", out)
        self.assertIn("DJ a mover: 0", out)

    def test_derived_predios_that_exist_are_reused(self):
        self.core.add_record("predio", {"codigo": f"{P}-1", "codigo_origen": P})
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertIn("predios a crear: 1 (1 ya existen)", out)
        self.assertEqual(len([r for r in self.core.records["predio"] if r["attributes"]["codigo"] == f"{P}-1"]), 1)


class NoMigrableTests(MigrarTestCase):
    def assert_untouched(self, out, motivo):
        self.assertIn("no migrables: 1", out)
        self.assertIn(f"{motivo}: 1", out)
        self.assertNotIn("fecha_baja", self.predio(P)["attributes"])
        self.assertFalse([r for r in self.core.records["predio"] if r["attributes"]["codigo"].startswith(P + "-")])
        self.assertTrue(all(r["attributes"]["predio"] == self.bare["id"] for r in self.core.records["declaracion_predial"]
                            if r["id"] in {dj["id"] for dj in self.djs.values()}))
        [row] = [r for r in self.report_rows() if r["categoria"] == "no_migrable"]
        self.assertEqual(row["codigo_predio"], P)
        self.assertIn(motivo, row["detalle"])

    def test_a_dj_whose_contribuyente_is_no_titular_leaves_the_code_alone(self):
        otro = self.core.add_record("contribuyente", {"codigo": "900777"})
        self.core.add_record("declaracion_predial", {"predio": self.bare["id"], "contribuyente": otro["id"], "anio": 2026,
                                                     "secuencia_uso": "001", "porcentaje_condominio": "33.33"})
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assert_untouched(out, "contribuyente_sin_titular")

    def test_a_derived_predio_of_another_origin_leaves_the_code_alone(self):
        self.core.add_record("predio", {"codigo": f"{P}-2", "codigo_origen": "OTRO"})
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertIn("no migrables: 1", out)
        self.assertIn("predio_derivado_de_otro_origen: 1", out)
        self.assertNotIn("fecha_baja", self.predio(P)["attributes"])
        self.assertFalse([r for r in self.core.records["predio"] if r["attributes"]["codigo"] == f"{P}-1"])

    def test_a_derived_code_the_padron_already_uses_leaves_the_code_alone(self):
        write_xlsx(self.excel, PADRON + [padron(codigo_predio=f"{P}-1", codigo_contribuyente="900003", nro_documento="40000003")], [])
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertIn("codigo_derivado_repetido: 1", out)
        self.assertNotIn("fecha_baja", self.predio(P)["attributes"])
        self.assertTrue(all(r["attributes"]["predio"] == self.bare["id"] for r in self.core.records["declaracion_predial"]
                            if r["id"] in {dj["id"] for dj in self.djs.values()}))


class CuotasTests(MigrarTestCase):
    def test_the_cuotas_of_the_year_are_listed_and_not_touched(self):
        cuota = self.core.add_record("cuota_predial", {"contribuyente": self.ana["id"], "anio": 2026, "numero": 1,
                                                       "revision": 0, "monto": "50.00", "valor_afecto": "25000.00"})
        self.core.add_record("cuota_predial", {"contribuyente": self.ana["id"], "anio": 2025, "numero": 1, "monto": "40.00"})
        self.core.add_record("emision_masiva", {"anio": 2026, "estado": "TERMINADA"})
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, msg=err)
        self.assertIn("cuotas 2026: 1 contribuyentes afectados con 1 cuotas (no se tocan)", out)
        self.assertIn("emisión masiva 2026: 1 (TERMINADA: 1)", out)
        self.assertFalse([p for m, p, _, _ in self.core.requests if m != "GET" and ("cuota_predial" in p or "emision_masiva" in p)])
        self.assertEqual(cuota["attributes"]["monto"], "50.00")
        [row] = [r for r in self.report_rows() if r["categoria"] == "cuota_predial"]
        self.assertEqual((row["codigo_contribuyente"], row["anio"]), ("900001", "2026"))


class PureTests(unittest.TestCase):
    def test_valor_afecto_keeps_the_deduccion(self):
        self.assertEqual(mcc.valores(Decimal("100.00"), "50000.00", "0.00"), ("50000.00", "50000.00"))
        self.assertEqual(mcc.valores(Decimal("50.00"), "50000.00", "30000.00"), ("25000.00", "0.00"))
        self.assertEqual(mcc.valores(Decimal("100.00"), None, None), (None, None))


class IsolatedTests(MigrarTestCase):
    def test_runs_isolated_from_another_directory(self):
        result = subprocess.run([sys.executable, "-I", os.path.join(HERE, "migrar_codigos_compartidos.py"), "--excel", self.excel,
                                 "--core", self.core.base_url, "--report", self.report, "--dry-run"],
                                cwd=os.path.dirname(self.excel), capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, msg=result.stderr)
        self.assertIn("DJ a mover: 4", result.stdout)


if __name__ == "__main__":
    unittest.main()
