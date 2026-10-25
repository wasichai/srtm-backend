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

    def test_a_tramo_without_valor_m2_is_no_arancel_and_cod_via_is_matched_as_stored(self):
        vias = aav.por_via([
            registro("1", id_aranc="A-01", cod_via="A", valor_m2=None, estado="VIGENTE"),
            registro("2", id_aranc="B-01", cod_via="B ", valor_m2=5, estado="VIGENTE"),
        ])
        self.assertEqual(vias, {"B ": ("B-01", Decimal("5"))})
        self.assertEqual(aav.destino({}, {"codigo_via": "A"}, vias)[0], "sin_arancel_del_anio")
        # srtm filters cod_via exactly: "B " is no arancel of the predio's "B"
        self.assertEqual(aav.destino({}, {"codigo_via": "B"}, vias)[0], "sin_arancel_del_anio")

    def test_a_marked_predio_never_takes_its_via(self):
        vias = {"A": ("A", Decimal("20"))}
        self.assertEqual(aav.destino({}, {"codigo_via": "A", "varios_codigos_via": True}, vias)[0],
                         "varios_codigos_valores_distintos")

    def test_the_regla_para_varios_codigos_on_the_predios_core_holds(self):
        predios = [registro("p1", codigo="P1", codigo_via="V-01"), registro("p2", codigo="P2", codigo_via="V-03"),
                   registro("p3", codigo="P3", codigo_via="V-05", varios_codigos_via=True),
                   registro("p4", codigo="P4", codigo_via="V-07", varios_codigos_via=True)]
        varios = [
            {"codigo_predio": "P1", "regla": "varios_codigos_mismo_valor", "codigo_elegido": "V-02"},
            {"codigo_predio": "P2", "regla": "varios_codigos_valores_distintos", "codigo_elegido": ""},
            {"codigo_predio": "P3", "regla": "varios_codigos_valores_distintos", "codigo_elegido": ""},
            # one marked before, with the same value now: it takes the code and loses the mark
            {"codigo_predio": "P4", "regla": "varios_codigos_mismo_valor", "codigo_elegido": "V-07"},
            {"codigo_predio": "P9", "regla": "varios_codigos_mismo_valor", "codigo_elegido": "V-09"},
        ]
        cambios, cuentas = aav.cambios_varios(varios, predios)
        self.assertEqual(cambios, [("p1", {"codigo_via": "V-02"}), ("p2", {"varios_codigos_via": True}),
                                   ("p4", {"varios_codigos_via": False})])
        self.assertEqual(dict(cuentas), {"codigo_via": 2, "marcados": 1, "iguales": 1, "no_encontrados": 1})
        despues = aav.aplicar_en_memoria(predios, cambios)
        self.assertEqual(despues[0]["attributes"]["codigo_via"], "V-02")
        self.assertIs(despues[1]["attributes"]["varios_codigos_via"], True)
        self.assertEqual(predios[0]["attributes"]["codigo_via"], "V-01")

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
        urbano = self.urbano = self.predio("V-01", "PREDIO URBANO")
        rustico = self.predio("V-01", "PREDIO RUSTICO")
        sin_codigo = self.predio(None, "PREDIO URBANO")
        sin_arancel = self.sin_arancel_predio = self.predio("V-09", "PREDIO URBANO")
        distintos = self.predio("V-02", "PREDIO URBANO")
        self.arancel("V-01", 2026, 356, "V-01")
        self.arancel("V-01", 2027, 400, "V-01")
        self.arancel("V-02-A", 2026, 20, "V-02")
        self.arancel("V-02-B", 2026, 21, "V-02")
        # V-09 has an arancel, of another year only
        self.arancel("V-09", 2025, 50, "V-09")
        self.igual = self.dj(urbano, 2026, 356)
        # another secuencia de uso of the same predio: another condominio
        self.distinta = self.dj(urbano, 2026, 300, secuencia="2")
        self.rustica = self.dj(rustico, 2026, 3)
        self.sin_codigo = self.dj(sin_codigo, 2026, 10)
        self.sin_arancel = self.dj(sin_arancel, 2026, 50)
        self.distintos = self.dj(distintos, 2026, 20)
        # neither one with id_aranc, nor an anulada, nor another year's
        self.dj(urbano, 2026, 356, id_aranc="V-01", secuencia="9")
        self.dj(urbano, 2026, 356, estado="ANULADA")
        self.de_2027 = self.dj(urbano, 2027, 356)

    def predio(self, codigo_via, tipo):
        return self.core.add_record("predio", {"codigo_via": codigo_via, "tipo_predio": tipo})["id"]

    def arancel(self, id_aranc, anio, valor, cod_via, estado="VIGENTE"):
        self.core.add_record("arancel", {"id_aranc": id_aranc, "anio": anio, "valor_m2": valor, "cod_via": cod_via, "estado": estado})

    def dj(self, predio, anio, arancel, id_aranc=None, estado=None, secuencia="1"):
        return self.core.add_record(DJ, {"predio": predio, "anio": anio, "arancel": arancel, "id_aranc": id_aranc,
                                         "estado": estado, "secuencia_uso": secuencia})["id"]

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
        self.assertIn("declaraciones sin id_aranc: 6 (con id_aranc ya: 1, de ellas con un valor nuevo del año: 0, con id_aranc sin arancel VIGENTE del año: 0)", out)
        self.assertIn("toman el arancel de su vía: 2, cambian de valor: 1", out)
        self.assertIn("sin tramo: rústicas 1, varios códigos con valores distintos 0, sin código de vía 1, sin arancel del año 1, tramos con valores distintos 1", out)
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
        self.assertIn("recalculadas 2 de 2, quedaron con su tramo: 2", out)

    def test_a_dj_with_id_aranc_whose_tramo_has_a_new_value_is_recalculated_too(self):
        # the year's value changed after it took its tramo: annulled and registered again
        cambiada = self.dj(None, 2026, 300, id_aranc="V-01")
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026")
        self.assertEqual(code, 0, err)
        self.assertIn("(con id_aranc ya: 2, de ellas con un valor nuevo del año: 1,", out)
        self.assertEqual(self.recalculadas(), sorted([self.igual, self.distinta, cambiada]))

    def test_a_dj_with_id_aranc_and_no_vigente_arancel_of_the_year_is_counted_only(self):
        sin = self.dj(None, 2026, 356, id_aranc="V-77")
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026")
        self.assertEqual(code, 0, err)
        self.assertIn("con id_aranc sin arancel VIGENTE del año: 1)", out)
        self.assertNotIn(sin, self.recalculadas())

    def varios_csv(self, filas):
        path = os.path.join(self.tmp.name, "aranceles.varios_codigos.csv")
        with open(path, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["codigo_predio", "codigos", "valores", "regla", "codigo_elegido"])
            w.writeheader()
            w.writerows(filas)
        return path

    def test_varios_codigos_marks_the_predios_of_different_values_and_never_recalculates_them(self):
        predio = next(r for r in self.core.records["predio"] if r["id"] == self.urbano)
        predio["attributes"]["codigo"] = "P-URB"
        path = self.varios_csv([{"codigo_predio": "P-URB", "codigos": "V-01x1 V-02x1", "valores": "",
                                 "regla": "varios_codigos_valores_distintos", "codigo_elegido": ""}])
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--varios-codigos", path, "--dry-run")
        self.assertEqual(code, 0, err)
        self.assertIn("varios códigos de vía: codigo_via al más frecuente 0, marcados 1, ya estaban 0, no encontrados 0", out)
        self.assertIn("varios códigos con valores distintos 2", out)
        # dry-run: nothing written
        self.assertFalse([r for r in self.core.requests if r[0] == "PATCH"])
        self.assertNotIn("varios_codigos_via", predio["attributes"])

        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--varios-codigos", path)
        self.assertEqual(code, 0, err)
        self.assertIs(predio["attributes"]["varios_codigos_via"], True)
        self.assertEqual(self.recalculadas(), [])

    def test_varios_codigos_gives_a_predio_of_one_value_its_most_frequent_code(self):
        predio = next(r for r in self.core.records["predio"] if r["id"] == self.sin_arancel_predio)
        predio["attributes"]["codigo"] = "P-SIN"
        path = self.varios_csv([{"codigo_predio": "P-SIN", "codigos": "V-01x2 V-09x1", "valores": "",
                                 "regla": "varios_codigos_mismo_valor", "codigo_elegido": "V-01"}])
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--varios-codigos", path)
        self.assertEqual(code, 0, err)
        self.assertEqual(predio["attributes"]["codigo_via"], "V-01")
        self.assertIn(self.sin_arancel, self.recalculadas())

    def test_a_failure_halfway_prints_the_progress_and_writes_the_report(self):
        # the reads answer, then the network fails: no CoreError, and still no traceback
        cliente = aav.Client

        class SeCae(cliente):
            def post(self, path, body):
                raise ConnectionError("se cayó")

        aav.Client = SeCae
        self.addCleanup(setattr, aav, "Client", cliente)
        reporte = os.path.join(self.tmp.name, "asignacion.csv")
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--workers", "1", "--reporte", reporte)
        self.assertEqual(code, 1)
        self.assertIn("recalculadas 0 de 2, quedaron con su tramo: 0", out)
        self.assertIn("ConnectionError", err)
        with open(reporte, newline="", encoding="utf-8") as f:
            self.assertEqual(len(list(csv.DictReader(f))), 6)

    def test_a_condominio_is_recalculated_once_and_counts_for_all_its_titulares(self):
        otro = self.dj(self.urbano, 2026, 356)  # a condómino of self.igual
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--workers", "1")
        self.assertEqual(code, 0, err)
        self.assertIn("a recalcular: 3 DJ en 2 recálculos (uno por condominio)", out)
        recalculadas = self.recalculadas()
        self.assertEqual(len(recalculadas), 2)
        self.assertEqual(len({self.igual, otro} & set(recalculadas)), 1)
        self.assertIn("recalculadas 3 de 3, quedaron con su tramo: 3", out)

    def test_a_condominio_with_a_tramo_gives_it_to_its_titulares_without_one(self):
        condomino = self.dj(self.urbano, 2026, 20, id_aranc="V-02-A", secuencia="2")  # a condómino of self.distinta
        self.core.recalcular = lambda id: {"id": id, "id_aranc": "V-02-A" if id in (self.distinta, condomino) else "V-01"}
        reporte = os.path.join(self.tmp.name, "asignacion.csv")
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--reporte", reporte)
        self.assertEqual(code, 0, err)
        self.assertIn("toman el tramo de su condominio: 1", out)
        with open(reporte, newline="", encoding="utf-8") as f:
            fila = next(r for r in csv.DictReader(f) if r["declaracion"] == self.distinta)
        self.assertEqual((fila["destino"], fila["id_aranc"]), ("toma_el_tramo_del_condominio", "V-02-A"))

    def test_a_dj_that_took_its_tramo_before_the_regla_is_left_for_review(self):
        predio = next(r for r in self.core.records["predio"] if r["id"] == self.urbano)
        predio["attributes"]["codigo"] = "P-URB"
        # self.igual took V-01 before the regla ran: the predio's frentes differ
        next(r for r in self.core.records[DJ] if r["id"] == self.igual)["attributes"]["id_aranc"] = "V-01"
        path = self.varios_csv([{"codigo_predio": "P-URB", "codigos": "", "valores": "",
                                 "regla": "varios_codigos_valores_distintos", "codigo_elegido": ""}])
        reporte = os.path.join(self.tmp.name, "asignacion.csv")
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--varios-codigos", path, "--reporte", reporte)
        self.assertEqual(code, 0, err)
        # self.igual and the one with V-01 since setUp
        self.assertIn("a revisar: 2 (", out)
        self.assertNotIn(self.igual, self.recalculadas())
        with open(reporte, newline="", encoding="utf-8") as f:
            self.assertEqual(next(r for r in csv.DictReader(f) if r["declaracion"] == self.igual)["destino"], "a_revisar")

    def test_a_dj_whose_predio_changes_code_is_left_for_review_unless_it_has_the_new_one(self):
        predio = next(r for r in self.core.records["predio"] if r["id"] == self.urbano)
        predio["attributes"]["codigo"] = "P-URB"
        path = self.varios_csv([{"codigo_predio": "P-URB", "codigos": "", "valores": "",
                                 "regla": "varios_codigos_mismo_valor", "codigo_elegido": "V-02-A"}])
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--varios-codigos", path, "--dry-run")
        self.assertEqual(code, 0, err)
        # the DJ with V-01 since setUp: V-02-A is the predio's code now
        self.assertIn("a revisar: 1 (", out)

    def test_a_failure_while_changing_the_predios_says_how_many(self):
        for codigo, id in (("P-URB", self.urbano), ("P-SIN", self.sin_arancel_predio)):
            next(r for r in self.core.records["predio"] if r["id"] == id)["attributes"]["codigo"] = codigo
        path = self.varios_csv([
            {"codigo_predio": "P-URB", "codigos": "", "valores": "", "regla": "varios_codigos_valores_distintos", "codigo_elegido": ""},
            {"codigo_predio": "P-SIN", "codigos": "", "valores": "", "regla": "varios_codigos_valores_distintos", "codigo_elegido": ""},
        ])
        cliente = aav.Client
        enviados = []

        class FallaLaSegunda(cliente):
            def patch(self, path, body):
                if enviados:
                    raise aav.CoreError(500, "boom")
                enviados.append(path)
                return super().patch(path, body)

        aav.Client = FallaLaSegunda
        self.addCleanup(setattr, aav, "Client", cliente)
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2026", "--varios-codigos", path)
        self.assertEqual(code, 1)
        self.assertIn("predios cambiados 1 de 2", out)
        self.assertEqual(self.recalculadas(), [])

    def test_the_year_asked_for_only(self):
        code, out, err = self.run_main("--org", "ica-prueba", "--anio", "2027")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.recalculadas(), [self.de_2027])

    def test_a_dj_srtm_left_without_its_tramo_is_an_error(self):
        self.core.recalcular = lambda id: {"id": id, "id_aranc": None}
        code, out, _ = self.run_main("--org", "ica-prueba", "--anio", "2026")
        self.assertEqual(code, 1)
        self.assertIn("recalculadas 2 de 2, quedaron con su tramo: 0", out)

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
