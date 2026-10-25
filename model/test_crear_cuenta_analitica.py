"""Pruebas de crear_cuenta_analitica.py contra un FakeCore.

Correr: cd model && python3 -m unittest -v
"""
import io
import os
import stat
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import crear_cuenta_analitica
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
BRECHA = os.path.join(HERE, "..", "docs", "informes", "brecha")


class CrearCuentaAnaliticaTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        self.core.roles["ANALITICA"] = {"name": "ANALITICA", "label": "Corte analítico", "permissions": []}
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.salida = os.path.join(self.tmp.name, "perene.env")

    def run_cli(self, extra=()):
        args = ["--core", self.core.base_url, "--email", "a@b", "--password", "p", *extra]
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = crear_cuenta_analitica.main(args)
        return code, out.getvalue(), err.getvalue()

    def writes(self):
        return [(m, p, b) for m, p, _, b in self.core.requests if m in ("POST", "PUT") and p != "/api/auth/login"]

    def leer_salida(self):
        with open(self.salida, encoding="utf-8") as f:
            return dict(l.split("=", 1) for l in f.read().splitlines() if l and not l.startswith("#"))

    def test_crea_la_cuenta_con_el_rol_e_imprime_el_secreto_una_vez(self):
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("POST", "/api/service-accounts", {"name": "analitica", "roles": ["ANALITICA"]})])
        cuenta = next(iter(self.core.service_accounts.values()))
        self.assertEqual(out.count("secreto-analitica"), 1)
        self.assertIn(f"SRTM_CLIENT_ID={cuenta['clientId']}", out)
        self.assertIn("SRTM_CLIENT_SECRET=secreto-analitica", out)

    def test_sin_el_rol_no_crea_la_cuenta(self):
        del self.core.roles["ANALITICA"]
        code, _, err = self.run_cli()
        self.assertEqual(code, 2)
        self.assertIn("apply_roles.py", err)
        self.assertEqual(self.writes(), [])

    def test_la_segunda_corrida_no_escribe_ni_muestra_secreto(self):
        self.run_cli()
        self.core.requests.clear()
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [])
        self.assertNotIn("secreto-analitica", out)
        cuenta = next(iter(self.core.service_accounts.values()))
        self.assertIn(cuenta["clientId"], out)
        self.assertIn("--rotar", out)
        self.assertEqual(len(self.core.service_accounts), 1)

    def test_una_cuenta_sin_el_rol_lo_recibe(self):
        cuenta = self.core.add_service_account("analitica", roles=["OTRO"])
        code, out, err = self.run_cli()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("PUT", f"/api/service-accounts/{cuenta['id']}", {"roles": ["OTRO", "ANALITICA"]})])
        self.assertIn("+ANALITICA", out)

    def test_rotar_pide_un_secreto_nuevo_y_lo_imprime_una_vez(self):
        cuenta = self.core.add_service_account("analitica", roles=["ANALITICA"])
        code, out, err = self.run_cli(["--rotar"])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.writes(), [("POST", f"/api/service-accounts/{cuenta['id']}/secret", None)])
        self.assertEqual(out.count("rotado-1-analitica"), 1)
        self.assertIn(f"SRTM_CLIENT_ID={cuenta['clientId']}", out)

    def test_rotar_sin_cuenta_la_crea(self):
        code, out, err = self.run_cli(["--rotar"])
        self.assertEqual(code, 0, err)
        self.assertEqual([p for _, p, _ in self.writes()], ["/api/service-accounts"])
        self.assertEqual(self.core.rotations, 0)
        self.assertIn("secreto-analitica", out)

    def test_salida_escribe_las_claves_del_corte_en_un_archivo_0600_y_no_en_pantalla(self):
        code, out, err = self.run_cli(["--salida", self.salida])
        self.assertEqual(code, 0, err)
        self.assertNotIn("secreto-analitica", out + err)
        self.assertEqual(stat.S_IMODE(os.stat(self.salida).st_mode), 0o600)
        cuenta = next(iter(self.core.service_accounts.values()))
        valores = self.leer_salida()
        self.assertEqual(valores["SRTM_CLIENT_ID"], cuenta["clientId"])
        self.assertEqual(valores["SRTM_CLIENT_SECRET"], "secreto-analitica")
        self.assertEqual(valores["SRTM_URL"], self.core.base_url)
        self.assertIn(self.salida, out)

    def test_salida_usa_los_nombres_que_el_corte_exige(self):
        sys.path.insert(0, BRECHA)
        self.addCleanup(sys.path.remove, BRECHA)
        from herramientas import comun
        self.assertTrue(set(crear_cuenta_analitica.CLAVES) <= set(comun.REQUERIDOS), crear_cuenta_analitica.CLAVES)
        self.assertEqual(set(crear_cuenta_analitica.CLAVES), {"SRTM_URL", "SRTM_CLIENT_ID", "SRTM_CLIENT_SECRET"})

    def test_salida_conserva_las_demas_claves_del_archivo(self):
        with open(self.salida, "w", encoding="utf-8") as f:
            f.write("# perene\nSRTM_URL=https://srtm.perene\nSRTM_CLIENT_ID=viejo\nSRTM_CLIENT_SECRET=viejo\n"
                    "CATASTRO_URL=https://catastro\nCLAVE_SEUDONIMO=abc\n")
        os.chmod(self.salida, 0o600)
        cuenta = self.core.add_service_account("analitica", roles=["ANALITICA"])
        code, _, err = self.run_cli(["--rotar", "--salida", self.salida])
        self.assertEqual(code, 0, err)
        valores = self.leer_salida()
        self.assertEqual(valores, {"SRTM_URL": "https://srtm.perene", "SRTM_CLIENT_ID": cuenta["clientId"],
                                   "SRTM_CLIENT_SECRET": "rotado-1-analitica", "CATASTRO_URL": "https://catastro",
                                   "CLAVE_SEUDONIMO": "abc"})
        with open(self.salida, encoding="utf-8") as f:
            self.assertTrue(f.read().startswith("# perene\n"))
        self.assertEqual(stat.S_IMODE(os.stat(self.salida).st_mode), 0o600)

    def test_salida_sin_secreto_nuevo_no_toca_el_archivo(self):
        self.core.add_service_account("analitica", roles=["ANALITICA"])
        code, out, err = self.run_cli(["--salida", self.salida])
        self.assertEqual(code, 0, err)
        self.assertFalse(os.path.exists(self.salida))
        self.assertIn("--rotar", out)

    def test_dry_run_no_llama_a_core(self):
        code, out, err = self.run_cli(["--dry-run"])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.core.requests, [])
        self.assertIn("# POST /api/service-accounts", out)
        self.assertIn('"ANALITICA"', out)

    def test_un_nombre_que_core_no_acepta_sale_con_2(self):
        code, _, err = self.run_cli(["--nombre", "9x"])
        self.assertEqual(code, 2)
        self.assertEqual(self.core.requests, [])

    def test_core_que_rechaza_la_cuenta_sale_con_1(self):
        self.core.fail_on_service_account = 500
        code, _, err = self.run_cli(["--salida", self.salida])
        self.assertEqual(code, 1)
        self.assertIn("500", err)
        self.assertFalse(os.path.exists(self.salida))


if __name__ == "__main__":
    unittest.main()
