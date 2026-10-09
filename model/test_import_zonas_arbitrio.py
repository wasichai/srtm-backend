"""Tests for import_zonas_arbitrio.py: zonas de arbitrio por ubicación (vía, tramo y manzana), checked before
anything is sent and loaded idempotently into a fake Core.

Every value here is FICTITIOUS, only to test the shape. Run: cd model && python3 -m unittest -v
"""
import copy
import csv
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_zonas_arbitrio as iz
from fake_core import FakeCore

FILA = {
    "servicio": "BARRIDO",
    "tipo_via": "AVENIDA",
    "via": "Perú",
    "manzana": "A",
    "numero_inicial": "100",
    "numero_final": "200",
    "paridad": "PAR",
    "zona": "1",
    "vigencia_desde": "2026-01-01",
    "vigencia_hasta": "",
    "norma": "Ordenanza ficticia 000-2026-MDP",
    "observacion": "Tramo de prueba",
}


def escribir_csv(path, filas, cabecera="# fuente: ficticia, solo de prueba"):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(cabecera + "\n")
        w = csv.DictWriter(f, fieldnames=iz.COLUMNS)
        w.writeheader()
        for fila in filas:
            w.writerow({c: fila.get(c, "") for c in iz.COLUMNS})


def leer(tmp_path, filas, cabecera="# fuente: ficticia, solo de prueba"):
    escribir_csv(tmp_path, filas, cabecera)
    return iz.read_csv(tmp_path)


class ClaveTests(unittest.TestCase):
    def test_matches_the_kotlin_formula(self):
        fila = dict(FILA, numero_inicial=100, numero_final=200)
        self.assertEqual(iz.clave(fila), "BARRIDO|AVENIDA|Perú|A|100|200|PAR|2026-01-01")

    def test_empty_optional_fields_are_empty_parts(self):
        fila = {"via": "Perú", "numero_inicial": 1, "numero_final": 9, "paridad": "AMBOS", "vigencia_desde": "2026-01-01"}
        self.assertEqual(iz.clave(fila), "||Perú||1|9|AMBOS|2026-01-01")


class ValidarTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.ruta = os.path.join(tmp.name, "zonas.csv")

    def errores(self, filas, cabecera="# fuente: ficticia, solo de prueba"):
        return iz.errores(leer(self.ruta, filas, cabecera))

    def test_a_well_formed_file_passes(self):
        self.assertEqual(self.errores([FILA]), [])

    def test_required_fields(self):
        sin_via = dict(FILA, via="")
        self.assertTrue(any("via" in e for e in self.errores([sin_via])))
        sin_paridad = dict(FILA, paridad="")
        self.assertTrue(any("paridad" in e for e in self.errores([sin_paridad])))
        sin_zona = dict(FILA, zona="")
        self.assertTrue(any("zona" in e for e in self.errores([sin_zona])))

    def test_servicio_tipo_via_manzana_norma_observacion_and_vigencia_hasta_are_optional(self):
        minima = {"via": "Perú", "numero_inicial": "1", "numero_final": "9", "paridad": "AMBOS", "zona": "1",
                  "vigencia_desde": "2026-01-01"}
        self.assertEqual(self.errores([minima]), [])

    def test_numero_inicial_and_final_are_integers(self):
        malo = dict(FILA, numero_inicial="cien")
        self.assertTrue(any("numero_inicial" in e for e in self.errores([malo])))

    def test_numero_inicial_is_not_greater_than_numero_final(self):
        al_reves = dict(FILA, numero_inicial="300", numero_final="200")
        self.assertTrue(any("numero_inicial" in e and "numero_final" in e for e in self.errores([al_reves])))

    def test_paridad_is_one_of_the_enum(self):
        mala = dict(FILA, paridad="IZQUIERDA")
        self.assertTrue(any("paridad" in e for e in self.errores([mala])))

    def test_dates_are_iso(self):
        self.assertTrue(any("vigencia_desde" in e for e in self.errores([dict(FILA, vigencia_desde="31/01/2026")])))
        self.assertTrue(any("vigencia_hasta" in e for e in self.errores([dict(FILA, vigencia_hasta="31/12/2026")])))

    def test_overlapping_tramos_with_different_zona_and_crossing_vigencias_is_an_error(self):
        a = dict(FILA, numero_inicial="100", numero_final="200", zona="1", vigencia_desde="2026-01-01")
        b = dict(FILA, numero_inicial="150", numero_final="250", zona="2", vigencia_desde="2026-01-01")
        malas = self.errores([a, b])
        self.assertTrue(any("solapa" in e for e in malas), malas)

    def test_overlapping_tramos_with_the_same_zona_is_not_an_error(self):
        a = dict(FILA, numero_inicial="100", numero_final="200", zona="1", vigencia_desde="2026-01-01")
        b = dict(FILA, numero_inicial="150", numero_final="250", zona="1", vigencia_desde="2026-01-01")
        self.assertEqual(self.errores([a, b]), [])

    def test_overlapping_tramos_with_non_crossing_vigencias_is_not_an_error(self):
        a = dict(FILA, numero_inicial="100", numero_final="200", zona="1", vigencia_desde="2025-01-01",
                 vigencia_hasta="2025-12-31")
        b = dict(FILA, numero_inicial="100", numero_final="200", zona="2", vigencia_desde="2026-01-01")
        self.assertEqual(self.errores([a, b]), [])

    def test_non_overlapping_tramos_is_not_an_error(self):
        a = dict(FILA, numero_inicial="100", numero_final="200", zona="1", vigencia_desde="2026-01-01")
        b = dict(FILA, numero_inicial="201", numero_final="300", zona="2", vigencia_desde="2026-01-01")
        self.assertEqual(self.errores([a, b]), [])

    def test_a_different_servicio_via_manzana_or_paridad_is_not_an_error_even_if_overlapping(self):
        a = dict(FILA, numero_inicial="100", numero_final="200", zona="1", vigencia_desde="2026-01-01")
        distinto_servicio = dict(a, servicio="SERENAZGO", zona="2")
        distinta_via = dict(a, via="Los Incas", zona="2")
        distinta_manzana = dict(a, manzana="B", zona="2")
        distinta_paridad = dict(a, paridad="IMPAR", zona="2")
        for b in (distinto_servicio, distinta_via, distinta_manzana, distinta_paridad):
            self.assertEqual(self.errores([a, b]), [], b)

    # the overlap is what srtm.arbitrios.Zonas.de would find ambiguous: same servicio and manzana, the vía and the
    # tipo_via compared as Zonas compares them (trimmed, case and accent insensitive; an empty tipo_via matches any)
    def tramo(self, **cambios):
        base = {"tipo_via": "AV", "via": "LIMA", "numero_inicial": "1", "numero_final": "99", "paridad": "AMBOS",
                "zona": "Z1", "vigencia_desde": "2026-01-01"}
        return {**base, **cambios}

    def test_a_different_tipo_via_is_not_an_overlap(self):
        av = self.tramo(tipo_via="AV", zona="Z1")
        jr = self.tramo(tipo_via="JR", zona="Z2")
        self.assertEqual(self.errores([av, jr]), [])

    def test_an_empty_tipo_via_overlaps_any(self):
        sin_tipo = self.tramo(tipo_via="", zona="Z1")
        jr = self.tramo(tipo_via="JR", zona="Z2")
        self.assertTrue(any("solapa" in e for e in self.errores([sin_tipo, jr])))

    def test_ambos_overlaps_par(self):
        par = self.tramo(numero_inicial="200", numero_final="298", paridad="PAR", zona="Z2")
        ambos = self.tramo(numero_inicial="200", numero_final="298", paridad="AMBOS", zona="Z3")
        self.assertTrue(any("solapa" in e for e in self.errores([par, ambos])))

    def test_the_via_is_compared_without_case_or_accents(self):
        mayusculas = self.tramo(tipo_via="AV", via="X", zona="Z1")
        minusculas = self.tramo(tipo_via="Av", via=" x ", zona="Z2")
        self.assertTrue(any("solapa" in e for e in self.errores([mayusculas, minusculas])))
        con_tilde = self.tramo(via="Perú", zona="Z1")
        sin_tilde = self.tramo(via="PERU", zona="Z2")
        self.assertTrue(any("solapa" in e for e in self.errores([con_tilde, sin_tilde])))

    def test_a_row_of_all_servicios_and_one_of_a_servicio_are_different_levels(self):
        todos = self.tramo(servicio="", zona="Z1")
        barrido = self.tramo(servicio="BARRIDO", zona="Z2")
        self.assertEqual(self.errores([todos, barrido]), [])


class CargaTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.ruta = os.path.join(tmp.name, "zonas.csv")

    def run_main(self, filas, *extra):
        escribir_csv(self.ruta, filas)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = iz.main(["--core", self.core.base_url, "--csv", self.ruta, *extra])
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p.split("?")[0]) for m, p, _, _ in self.core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def test_creates_what_is_missing(self):
        otra = dict(FILA, via="Los Incas", zona="2")
        code, out, err = self.run_main([FILA, otra])
        self.assertEqual(code, 0, err)
        registros = self.core.records["zona_arbitrio_ubicacion"]
        self.assertEqual(len(registros), 2)
        claves = {r["attributes"]["clave"] for r in registros}
        self.assertEqual(claves, {iz.clave(FILA), iz.clave(otra)})
        self.assertEqual([m for m, _ in self.writes()], ["POST", "POST"])
        self.assertIn("zona_arbitrio_ubicacion: 2 created, 0 updated, 0 skipped", out)

    def test_a_second_run_writes_nothing(self):
        self.run_main([FILA])
        self.core.requests.clear()
        code, out, err = self.run_main([FILA])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("zona_arbitrio_ubicacion: 0 created, 0 updated, 1 skipped", out)

    def test_a_zona_corrected_later_updates_in_place(self):
        self.run_main([FILA])
        self.core.requests.clear()
        corregida = dict(FILA, zona="2", observacion="corregido")
        code, out, err = self.run_main([corregida])
        self.assertEqual(code, 0, err)
        self.assertEqual([m for m, _ in self.writes()], ["PUT"])
        [registro] = self.core.records["zona_arbitrio_ubicacion"]
        self.assertEqual(registro["attributes"]["zona"], "2")
        self.assertEqual(registro["attributes"]["clave"], iz.clave(FILA))
        self.assertIn("zona_arbitrio_ubicacion: 0 created, 1 updated, 0 skipped", out)

    def test_a_different_tramo_never_deletes_the_previous_row(self):
        self.run_main([FILA])
        self.core.requests.clear()
        otro_tramo = dict(FILA, numero_inicial="201", numero_final="300")
        code, out, err = self.run_main([otro_tramo])
        self.assertEqual(code, 0, err)
        self.assertEqual(len(self.core.records["zona_arbitrio_ubicacion"]), 2)
        self.assertNotIn("DELETE", [m for m, _ in self.writes()])

    def test_dry_run_reads_core_and_writes_nothing(self):
        code, out, err = self.run_main([FILA], "--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertIn("zona_arbitrio_ubicacion: 1 to create, 0 to update, 0 skipped", out)

    def test_a_malformed_file_exits_2_without_calling_core(self):
        code, out, err = self.run_main([dict(FILA, via="")])
        self.assertEqual(code, 2)
        self.assertEqual(self.core.requests, [])

    def test_av_lima_and_jr_lima_with_different_zonas_load(self):
        av = dict(FILA, tipo_via="AV", via="LIMA", manzana="", numero_inicial="1", numero_final="99", paridad="AMBOS",
                  zona="Z1")
        jr = dict(av, tipo_via="JR", zona="Z2")
        code, out, err = self.run_main([av, jr], "--dry-run")
        self.assertEqual(code, 0, err)

    def test_par_and_ambos_of_the_same_tramo_exit_2(self):
        par = dict(FILA, numero_inicial="200", numero_final="298", paridad="PAR", zona="Z2")
        ambos = dict(par, paridad="AMBOS", zona="Z3")
        code, out, err = self.run_main([par, ambos])
        self.assertEqual(code, 2)
        self.assertEqual(self.core.requests, [])

    def test_av_x_and_av_x_in_lowercase_with_different_zonas_exit_2(self):
        mayusculas = dict(FILA, tipo_via="AV", via="X", zona="Z1")
        minusculas = dict(mayusculas, tipo_via="Av", via="x", zona="Z2")
        code, out, err = self.run_main([mayusculas, minusculas])
        self.assertEqual(code, 2)
        self.assertEqual(self.core.requests, [])

    def test_overlapping_rows_exit_2_without_calling_core(self):
        a = dict(FILA, numero_inicial="100", numero_final="200", zona="1")
        b = dict(FILA, numero_inicial="150", numero_final="250", zona="2")
        code, out, err = self.run_main([a, b])
        self.assertEqual(code, 2)
        self.assertEqual(self.core.requests, [])

    def test_a_refusal_to_create_exits_1(self):
        self.core.fail_on_record = "zona_arbitrio_ubicacion"
        code, out, err = self.run_main([FILA])
        self.assertEqual(code, 1)
        self.assertIn("boom-record", err)

    def test_a_refusal_to_update_exits_1(self):
        self.run_main([FILA])
        self.core.fail_on_update = "zona_arbitrio_ubicacion"
        code, out, err = self.run_main([dict(FILA, zona="2")])
        self.assertEqual(code, 1)
        self.assertIn("boom-update", err)


if __name__ == "__main__":
    unittest.main()
