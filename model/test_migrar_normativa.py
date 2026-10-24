"""Tests for migrar_normativa.py: the parámetros without estado become VIGENTE, one without its two signatures is
reported, catastro's aranceles come into srtm signed MIGRACION/CATASTRO and marked synchronized, an equal one is skipped
and one that differs reported, against two fake Cores (srtm's and catastro's). FICTITIOUS figures (wasichai/srtm-backend#189).

Run: cd model && python3 -m unittest -v test_migrar_normativa
"""
import io
import unittest
from contextlib import redirect_stderr, redirect_stdout

import migrar_normativa as mn
from fake_core import FakeCore


def parametro(core, tipo="UIT", transcribio="ANA, 2026-01-05", verifico="BETO, 2026-01-06", **attributes):
    return core.add_record(mn.PARAMETRO, {"tipo": tipo, "clave": None, "vigencia_desde": "2061-01-01",
                                          "valor_numerico": "9000.00", "transcribio": transcribio, "verifico": verifico,
                                          **attributes})


def arancel(core, id_aranc="A-01", anio=2061, valor="12.50", **attributes):
    return core.add_record(mn.ARANCEL, {"clave": f"{id_aranc}|{anio}", "id_aranc": id_aranc, "anio": anio, "valor_m2": valor,
                                        "cod_via": "V-1", "cuadra": "1", "lado": "I", **attributes})


class MigrarNormativaTests(unittest.TestCase):
    def setUp(self):
        self.srtm = FakeCore()
        self.catastro = FakeCore(organization={"id": "c", "slug": "catastro-perene"})

    def tearDown(self):
        self.srtm.stop()
        self.catastro.stop()

    def run_main(self, *extra, catastro=True):
        argv = ["--core", self.srtm.base_url, "--email", "a", "--password", "b", "--org", "perene", *extra]
        if catastro:
            argv += ["--catastro-core", self.catastro.base_url, "--catastro-email", "c", "--catastro-password", "d",
                     "--catastro-org", "catastro-perene"]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = mn.main(argv)
        return code, out.getvalue(), err.getvalue()

    def writes(self, core):
        return [(m, p) for m, p, _, _ in core.requests if m in ("POST", "PUT", "DELETE") and p != "/api/auth/login"]

    def attrs(self, record_id, objeto=mn.PARAMETRO):
        return next(r for r in self.srtm.records[objeto] if r["id"] == record_id)["attributes"]

    def test_a_dry_run_reads_both_cores_and_writes_nothing(self):
        parametro(self.srtm)
        arancel(self.catastro)
        code, out, _ = self.run_main("--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("parametros: vigentes 1, sin_doble_firma 0", out)
        self.assertIn("aranceles: crear 1, iguales 0, distintos 0, por_verificar 0", out)
        self.assertEqual(self.writes(self.srtm), [])
        self.assertEqual(self.writes(self.catastro), [])

    def test_the_parametros_without_estado_become_vigente_and_nothing_else_changes(self):
        sin = parametro(self.srtm, norma="Ordenanza ficticia")
        ya = parametro(self.srtm, tipo="TRAMO_PREDIAL", estado="POR_VERIFICAR", verifico=None)
        code, out, _ = self.run_main(catastro=False)
        self.assertEqual(code, 0, out)
        self.assertEqual(self.attrs(sin["id"]), {**sin["attributes"], "estado": "VIGENTE"})
        # one with its estado is not this script's
        self.assertEqual(self.attrs(ya["id"])["estado"], "POR_VERIFICAR")
        self.assertEqual(self.writes(self.srtm), [("PUT", f"/api/objects/{mn.PARAMETRO}/records/{sin['id']}")])
        self.assertIn("aranceles: sin --catastro-core", out)
        # idempotent
        self.srtm.requests.clear()
        self.assertEqual(self.run_main(catastro=False)[0], 0)
        self.assertEqual(self.writes(self.srtm), [])

    def test_one_without_its_two_signatures_is_reported_and_left_alone(self):
        sin_verifico = parametro(self.srtm, verifico=None)
        misma = parametro(self.srtm, tipo="MINIMO", transcribio="ANA, 2026-01-05", verifico="ana, 2026-02-01")
        code, out, _ = self.run_main(catastro=False)
        self.assertEqual(code, 0)
        self.assertIn("parametros: vigentes 0, sin_doble_firma 2", out)
        self.assertIn(f"sin doble firma: revisar en Normativa: {sin_verifico['id']} UIT", out)
        self.assertIn(misma["id"], out)
        self.assertEqual(self.writes(self.srtm), [])
        self.assertNotIn("estado", self.attrs(sin_verifico["id"]))

    def test_catastros_aranceles_are_created_vigente_with_the_migrations_signatures_and_synchronized(self):
        arancel(self.catastro, "A-01")
        arancel(self.catastro, "A-02", valor=0, cod_via=None)
        code, out, err = self.run_main("--workers", "2")
        self.assertEqual(code, 0, err)
        self.assertIn("aranceles: crear 2, iguales 0, distintos 0, por_verificar 0", out)
        creados = {r["attributes"]["clave"]: r["attributes"] for r in self.srtm.records[mn.ARANCEL]}
        self.assertEqual(set(creados), {"A-01|2061", "A-02|2061"})
        a = creados["A-01|2061"]
        self.assertEqual((a["estado"], a["transcribio"], a["verifico"], a["fuente"]), ("VIGENTE", "MIGRACION", "CATASTRO", "catastro (migración inicial)"))
        self.assertEqual((a["id_aranc"], a["anio"], a["valor_m2"], a["cod_via"], a["cuadra"], a["lado"]), ("A-01", 2061, "12.50", "V-1", "1", "I"))
        self.assertTrue(a["sincronizado_en"])
        self.assertEqual(creados["A-02|2061"]["valor_m2"], "0")
        self.assertNotIn("cod_via", creados["A-02|2061"])
        # the insert goes without sincronizado_en (srtm's rule), the mark is a second write
        posts = [b for m, p, _, b in self.srtm.requests if m == "POST" and p == f"/api/objects/{mn.ARANCEL}/records"]
        self.assertEqual(len(posts), 2)
        self.assertTrue(all("sincronizado_en" not in b["attributes"] for b in posts))
        self.assertEqual(len([1 for m, p in self.writes(self.srtm) if m == "PUT"]), 2)
        # catastro is only read
        self.assertEqual(self.writes(self.catastro), [])
        # idempotent: both equal now
        code, out, _ = self.run_main()
        self.assertIn("aranceles: crear 0, iguales 2, distintos 0, por_verificar 0", out)

    def test_an_equal_arancel_is_skipped_and_one_that_differs_is_reported(self):
        arancel(self.catastro, "A-01", valor="12.5")
        arancel(self.catastro, "A-02", valor="20.00")
        arancel(self.catastro, "A-03")
        arancel(self.catastro, "A-04")
        arancel(self.srtm, "A-01", valor="12.50", estado="VIGENTE")
        # the same data, but still to verify in srtm: not iguales
        arancel(self.srtm, "A-04", estado="POR_VERIFICAR")
        distinto = arancel(self.srtm, "A-02", valor="21.00", estado="POR_VERIFICAR")
        # an annulled one frees its clave: A-03 is created
        arancel(self.srtm, "A-03", valor="99.00", estado="ANULADO")
        code, out, _ = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("aranceles: crear 1, iguales 1, distintos 1, por_verificar 1", out)
        self.assertIn("POR_VERIFICAR en srtm: verificar en Normativa: A-04|2061", out)
        self.assertIn("distinto en srtm: revisar: A-02|2061", out)
        self.assertEqual(self.attrs(distinto["id"], mn.ARANCEL)["valor_m2"], "21.00")
        nuevos = [r for r in self.srtm.records[mn.ARANCEL] if r["attributes"].get("transcribio") == "MIGRACION"]
        self.assertEqual([r["attributes"]["clave"] for r in nuevos], ["A-03|2061"])

    def test_an_invalid_arancel_of_catastro_or_another_organization_writes_nothing(self):
        parametro(self.srtm)
        arancel(self.catastro, "A-01", valor="")
        code, _, err = self.run_main()
        self.assertEqual(code, 2)
        self.assertIn("le falta id_aranc, anio o valor_m2", err)
        self.assertEqual(self.writes(self.srtm), [])

        code, _, err = self.run_main("--org", "ica", catastro=False)
        self.assertEqual(code, 2)
        self.assertIn("no de 'ica'", err)
        code, _, err = self.run_main("--catastro-core", "http://127.0.0.1:1", catastro=False)
        self.assertEqual(code, 2)
        # catastro hosts several municipalities: its login must be of --catastro-org
        login = ["--catastro-core", self.catastro.base_url, "--catastro-email", "c", "--catastro-password", "d"]
        code, _, err = self.run_main(*login, "--catastro-org", "catastro-ica", catastro=False)
        self.assertEqual(code, 2)
        self.assertIn("no de 'catastro-ica'", err)
        self.assertNotIn("inválido", err)
        self.assertEqual(self.writes(self.srtm), [])
        code, _, err = self.run_main(*login, catastro=False)
        self.assertEqual(code, 2)
        self.assertIn("--catastro-org", err)

    def test_a_refusal_of_core_is_exit_1(self):
        self.srtm.stop()
        self.srtm = FakeCore(fail_on_record=mn.ARANCEL)
        arancel(self.catastro)
        code, _, err = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("arancel A-01|2061", err)


if __name__ == "__main__":
    unittest.main()
