"""Tests for asignar_arancel_por_via.py: which DJ take their vía's arancel, the counts, that --dry-run writes nothing
and that only the year asked for is touched.

Every record here is made up.

Run: cd model && python3 -m unittest -v test_asignar_arancel_por_via
"""
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from decimal import Decimal

import asignar_arancel_por_via as aav
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
DJ = "declaracion_predial"


def registro(id, **attributes):
    return {"id": id, "attributes": attributes}


class PureTests(unittest.TestCase):
    def test_the_tramos_of_a_via_count_only_with_one_value(self):
        vias = aav.por_via([
            registro("1", id_aranc="A-02", cod_via="A", valor_m2=20, estado="VIGENTE"),
            registro("2", id_aranc="A-01", cod_via="A", valor_m2="20.00", estado=None),
            registro("3", id_aranc="B-01", cod_via="B", valor_m2=20, estado="VIGENTE"),
            registro("4", id_aranc="B-02", cod_via="B", valor_m2=21, estado="VIGENTE"),
            # neither POR_VERIFICAR nor ANULADO rules
            registro("5", id_aranc="C-01", cod_via="C", valor_m2=5, estado="POR_VERIFICAR"),
            registro("6", id_aranc="C-02", cod_via="C", valor_m2=6, estado="ANULADO"),
        ])
        self.assertEqual(vias, {"A": ("A-01", Decimal("20")), "B": None})

    def test_a_djs_fate(self):
        vias = {"A": ("A", Decimal("20")), "B": None}
        self.assertEqual(aav.destino({}, {"codigo_via": "A", "tipo_predio": "PREDIO URBANO"}, vias), ("toma", "A", Decimal("20")))
        # a predio without tipo_predio is urbano, as srtm values it
        self.assertEqual(aav.destino({}, {"codigo_via": " A "}, vias)[0], "toma")
        self.assertEqual(aav.destino({}, {"codigo_via": "A", "tipo_predio": "PREDIO RUSTICO"}, vias)[0], "rustica")
        self.assertEqual(aav.destino({}, {"codigo_via": None}, vias)[0], "sin_codigo_de_via")
        self.assertEqual(aav.destino({}, None, vias)[0], "sin_codigo_de_via")
        self.assertEqual(aav.destino({}, {"codigo_via": "Z"}, vias)[0], "sin_arancel_del_anio")
        self.assertEqual(aav.destino({}, {"codigo_via": "B"}, vias)[0], "tramos_distintos")

    def test_cambian_counts_the_ones_whose_arancel_is_not_the_vias(self):
        filas = [
            {"destino": "toma", "arancel": Decimal("20.00"), "valor_via": Decimal("20")},
            {"destino": "toma", "arancel": Decimal("19"), "valor_via": Decimal("20")},
            {"destino": "toma", "arancel": None, "valor_via": Decimal("20")},
            {"destino": "rustica", "arancel": Decimal("3"), "valor_via": None},
        ]
        cuentas = aav.resumen(filas)
        self.assertEqual(cuentas["toma"], 3)
        self.assertEqual(cuentas["cambian"], 2)
        self.assertEqual(cuentas["rustica"], 1)


class CliTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore(organization={"id": "00000000-0000-0000-0000-000000000002", "name": "Ica", "slug": "ica-prueba"},
                             recalcular=self.recalcular)
        self.addCleanup(self.core.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        urbano = self.predio("V-01", "PREDIO URBANO")
        rustico = self.predio("V-01", "PREDIO RUSTICO")
        sin_codigo = self.predio(None, "PREDIO URBANO")
        sin_arancel = self.predio("V-09", "PREDIO URBANO")
        distintos = self.predio("V-02", "PREDIO URBANO")
        self.arancel("V-01", 2026, 356, "V-01")
        self.arancel("V-01", 2027, 400, "V-01")
        self.arancel("V-02-A", 2026, 20, "V-02")
        self.arancel("V-02-B", 2026, 21, "V-02")
        # V-09 has an arancel, of another year only
        self.arancel("V-09", 2025, 50, "V-09")
        self.igual = self.dj(urbano, 2026, 356)
        self.distinta = self.dj(urbano, 2026, 300)
        self.rustica = self.dj(rustico, 2026, 3)
        self.sin_codigo = self.dj(sin_codigo, 2026, 10)
        self.sin_arancel = self.dj(sin_arancel, 2026, 50)
        self.distintos = self.dj(distintos, 2026, 20)
        # neither one with id_aranc, nor an anulada, nor another year's
        self.dj(urbano, 2026, 356, id_aranc="V-01")
        self.dj(urbano, 2026, 356, estado="ANULADA")
        self.de_2027 = self.dj(urbano, 2027, 356)

    def predio(self, codigo_via, tipo):
        return self.core.add_record("predio", {"codigo_via": codigo_via, "tipo_predio": tipo})["id"]

    def arancel(self, id_aranc, anio, valor, cod_via, estado="VIGENTE"):
        self.core.add_record("arancel", {"id_aranc": id_aranc, "anio": anio, "valor_m2": valor, "cod_via": cod_via, "estado": estado})

    def dj(self, predio, anio, arancel, id_aranc=None, estado=None):
        return self.core.add_record(DJ, {"predio": predio, "anio": anio, "arancel": arancel, "id_aranc": id_aranc,
                                         "estado": estado})["id"]

    # srtm's recálculo, as the fake answers it: the DJ of the urbano predio takes V-01
    def recalcular(self, id):
        d = next(r for r in self.core.records[DJ] if r["id"] == id)
        d["attributes"]["id_aranc"] = "V-01"
        return {"id": id, **d["attributes"]}

    def run_main(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = aav.main(["--core", self.core.base_url, "--email", "a@b.c", "--password", "x", *argv])
        return code, out.getvalue(), err.getvalue()

    def recalculadas(self):
        return sorted(r[1].split("/")[4] for r in self.core.requests if r[0] == "POST" and r[1].endswith("/recalcular"))

    def test_dry_run_counts_and_recalculates_none(self):
        reporte = os.path.join(self.tmp.name, "asignacion.csv")
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--dry-run", "--reporte", reporte)
        self.assertEqual(code, 0, err)
        self.assertIn("declaraciones sin id_aranc: 6 (con id_aranc ya: 1, de ellas con un valor nuevo del año: 0)", out)
        self.assertIn("toman el arancel de su vía: 2, cambian de valor: 1", out)
        self.assertIn("sin tramo: rústicas 1, sin código de vía 1, sin arancel del año 1, tramos con valores distintos 1", out)
        self.assertEqual(self.recalculadas(), [])
        self.assertFalse([r for r in self.core.requests if r[0] in ("PUT", "DELETE")])
        with open(reporte, newline="", encoding="utf-8") as f:
            filas = {r["declaracion"]: r["destino"] for r in csv.DictReader(f)}
        self.assertEqual(filas[self.igual], "toma")
        self.assertEqual(filas[self.rustica], "rustica")
        self.assertNotIn(self.de_2027, filas)

    def test_without_dry_run_it_recalculates_the_ones_that_take_a_tramo_only(self):
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.recalculadas(), sorted([self.igual, self.distinta]))
        self.assertIn("recalculadas: 2, quedaron con su tramo: 2", out)

    def test_a_dj_with_id_aranc_whose_tramo_has_a_new_value_is_recalculated_too(self):
        # the year's value changed after it took its tramo: annulled and registered again
        cambiada = self.dj(None, 2026, 300, id_aranc="V-01")
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026")
        self.assertEqual(code, 0, err)
        self.assertIn("(con id_aranc ya: 2, de ellas con un valor nuevo del año: 1)", out)
        self.assertEqual(self.recalculadas(), sorted([self.igual, self.distinta, cambiada]))

    def test_the_year_asked_for_only(self):
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2027")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.recalculadas(), [self.de_2027])

    def test_a_dj_srtm_left_without_its_tramo_is_an_error(self):
        self.core.recalcular = lambda id: {"id": id, "id_aranc": None}
        code, out, _ = self.run_main("--org", "ica-prueba", "--anio", "2026")
        self.assertEqual(code, 1)
        self.assertIn("recalculadas: 2, quedaron con su tramo: 0", out)

    def test_another_organizations_login_writes_nothing(self):
        code, _, err = self.run_main("--org", "ica", "--anio", "2026")
        self.assertEqual(code, 2)
        self.assertIn("no de 'ica'", err)
        self.assertEqual(self.recalculadas(), [])

    def test_the_report_never_goes_into_the_repo(self):
        code, _, err = self.run_main("--org", "ica-prueba", "--dry-run", "--reporte", os.path.join(HERE, "reports", "x.csv"))
        self.assertEqual(code, 2)
        self.assertIn("dentro del repo", err)
        self.assertEqual(self.core.requests, [])


if __name__ == "__main__":
    unittest.main()
