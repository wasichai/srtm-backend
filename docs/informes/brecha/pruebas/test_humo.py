"""#206 task 11: herramientas.metabase.humo against pruebas.metabase_falso.MetabaseFalso, and humo.py's CLI - one
Metabase instance per organization (plan, Decisión 2026-10-25): the smoke test checks the instance holds only its
organization's DuckDB, and that its user sees it without native SQL."""

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import humo
from herramientas import comun, metabase
from pruebas.metabase_falso import MetabaseFalso

ADMIN = ("admin@ejemplo.pe", "clave-admin-1")
ORG, NOMBRE = "ficticia-a", "Ficticia A"
EMAIL = "humo-ficticia-a@ejemplo.pe"
CLAVE_USUARIO = "clave-usuario-1"


def _tableros():
    return json.loads((comun.INFORME / "metabase" / "tableros.json").read_text(encoding="utf-8"))


class ConHumo(unittest.TestCase):
    """A fresh MetabaseFalso, provisioned for ficticia-a alone, with its smoke-test user - the same shape
    humo.py's --org/--usuario expect."""

    def setUp(self):
        self.falso = MetabaseFalso({ADMIN[0]: ADMIN[1]})
        self.addCleanup(self.falso.cerrar)
        self.mb = metabase.Metabase(self.falso.url)
        self.mb.sesion(*ADMIN)
        self.resultado = metabase.provisionar(self.mb, ORG, NOMBRE, _tableros())
        metabase.agregar_usuario(self.mb, ORG, EMAIL, "Humo", ORG, CLAVE_USUARIO)
        self.usuario = (EMAIL, CLAVE_USUARIO)

    def _humo(self):
        return metabase.humo(self.falso.url, ADMIN, ORG, self.usuario, espera=1)


class HumoTests(ConHumo):
    def test_humo_pasa(self):
        fallas = self._humo()
        self.assertEqual(fallas, [])
        self.assertGreater(fallas.comprobaciones, 0)

    def test_humo_detecta_otra_base_en_la_instancia(self):
        detalles = dict(self.falso.bases[self.resultado["base"]]["details"], database_file="/datos/ficticia-b.duckdb")
        self.mb.post("/api/database", {"name": "Brecha Ficticia B", "engine": "duckdb", "details": detalles})
        fallas = self._humo()
        self.assertTrue(any("Brecha Ficticia B" in f for f in fallas), fallas)

    def test_humo_detecta_una_base_sin_prefijo(self):
        # any other database counts, not only a `Brecha …` one: the instance is the organization's alone
        self.mb.post("/api/database", {"name": "Otra", "engine": "postgres", "details": {"host": "x"}})
        fallas = self._humo()
        self.assertTrue(any("Otra" in f for f in fallas), fallas)

    def test_humo_detecta_que_falta_su_base(self):
        self.falso.bases.clear()
        fallas = self._humo()
        self.assertTrue(any("ninguna base" in f for f in fallas), fallas)

    def test_humo_detecta_una_base_sobre_otro_archivo(self):
        self.falso.bases[self.resultado["base"]]["details"]["database_file"] = "/datos/ficticia-b.duckdb"
        fallas = self._humo()
        self.assertTrue(any("/datos/ficticia-b.duckdb" in f for f in fallas), fallas)

    def test_humo_detecta_otro_archivo_abierto_en_duckdb(self):
        self.falso.adjuntos_extra = ["/datos/ficticia-b.duckdb"]
        fallas = self._humo()
        self.assertTrue(any("duckdb_databases" in f and "ficticia-b" in f for f in fallas), fallas)

    def test_humo_detecta_el_corte_de_otra_organizacion(self):
        # the file has the right name but holds another organization's corte (copied by hand, say)
        self.falso.organizacion_del_corte = {self.resultado["base"]: "ficticia-b"}
        fallas = self._humo()
        self.assertTrue(any("corte_manifiesto" in f and "ficticia-b" in f for f in fallas), fallas)

    def test_humo_comprueba_el_archivo_y_el_corte_con_sql(self):
        self._humo()
        consultas = [
            str((cuerpo or {}).get("native")) for m, ruta, cuerpo in self.falso.peticiones if ruta == "/api/dataset"
        ]
        self.assertTrue(any("duckdb_databases()" in c for c in consultas), consultas)
        self.assertTrue(any("corte_manifiesto" in c for c in consultas), consultas)

    def test_humo_detecta_sql_nativo(self):
        self.falso.permitir_nativo = True
        fallas = self._humo()
        self.assertTrue(any("sql nativo" in f.lower() for f in fallas), fallas)

    def test_humo_detecta_tarjeta_rota(self):
        alguna = self.resultado["tarjetas"][0]
        nombre = self.falso.tarjetas[alguna]["name"]
        self.falso.tarjetas_fallidas = {alguna}
        fallas = self._humo()
        self.assertTrue(any(nombre in f for f in fallas), fallas)

    def test_humo_detecta_que_el_usuario_no_ve_su_propia_base(self):
        # a membership mistake (the user was never put in its group): the admin still sees the base, the
        # user's own session does not
        uid = next(u["id"] for u in self.falso.usuarios.values() if u["email"] == EMAIL)
        self.falso.membresias = [
            m for m in self.falso.membresias if not (m["user_id"] == uid and m["group_id"] == self.resultado["grupo"])
        ]
        fallas = self._humo()
        self.assertTrue(any(f"{ORG} no ve su propia base" in f for f in fallas), fallas)

    def test_humo_tarjeta_que_falla_con_400_es_una_falla_y_sigue(self):
        normal = self._humo()
        alguna = self.resultado["tarjetas"][0]
        nombre = self.falso.tarjetas[alguna]["name"]
        self.falso.tarjetas_fallidas = {alguna}
        fallas = self._humo()
        self.assertEqual(len(fallas), 1, fallas)
        self.assertIn(nombre, fallas[0])
        self.assertIn("400", fallas[0])
        # every other comprobación still ran: a broken card does not stop the run
        self.assertEqual(fallas.comprobaciones, normal.comprobaciones)

    def test_humo_detecta_acceso_externo_activo(self):
        # the positive check of INIT_SQL: current_setting('enable_external_access') must be false
        self.falso.bases[self.resultado["base"]]["details"]["init_sql"] = ""
        fallas = self._humo()
        self.assertTrue(any("enable_external_access" in f for f in fallas), fallas)

    def test_humo_detecta_una_base_que_no_sincroniza(self):
        with mock.patch.object(metabase, "_esperar_vista", return_value=False):
            fallas = self._humo()
        self.assertTrue(any(f"Brecha {NOMBRE}" in f and "v_segmentos" in f for f in fallas), fallas)


class CliTests(ConHumo):
    def setUp(self):
        super().setUp()
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.archivo = Path(tmp.name) / f"{ORG}.env"
        self.archivo.write_text(f"METABASE_PUERTO={self.falso.url.rsplit(':', 1)[1]}\n", encoding="utf-8")
        self.archivo.chmod(0o600)
        self.entorno = {
            "METABASE_ADMIN_EMAIL": ADMIN[0],
            "METABASE_ADMIN_PASSWORD": ADMIN[1],
            "METABASE_CLAVE_USUARIO": CLAVE_USUARIO,
        }
        self.argv = ["--org", ORG, "--usuario", EMAIL, "--entorno", str(self.archivo)]

    def _cli(self, argv, entorno=None):
        salida, error = io.StringIO(), io.StringIO()
        with (
            mock.patch.dict(os.environ, self.entorno if entorno is None else entorno, clear=True),
            contextlib.redirect_stdout(salida),
            contextlib.redirect_stderr(error),
        ):
            codigo = humo.main(argv)
        return codigo, salida.getvalue(), error.getvalue()

    def test_humo_cli(self):
        codigo, salida, error = self._cli(self.argv)
        self.assertEqual(codigo, 0, error)
        self.assertIn("humo: OK", salida)

    def test_humo_cli_falla_imprime_cada_una_y_sale_con_1(self):
        self.falso.tarjetas_fallidas = {self.resultado["tarjetas"][0]}
        codigo, salida, error = self._cli(self.argv)
        self.assertEqual(codigo, 1)
        self.assertEqual(salida, "")
        self.assertTrue(error.strip())

    def test_humo_cli_ignora_metabase_url_del_entorno(self):
        otro = MetabaseFalso({})
        self.addCleanup(otro.cerrar)
        codigo, _, error = self._cli(self.argv, entorno={**self.entorno, "METABASE_URL": otro.url})
        self.assertEqual(codigo, 0, error)
        self.assertEqual(otro.peticiones, [])

    def test_humo_cli_falta_la_clave_de_usuario(self):
        entorno = {k: v for k, v in self.entorno.items() if k != "METABASE_CLAVE_USUARIO"}
        codigo, _, error = self._cli(self.argv, entorno=entorno)
        self.assertEqual(codigo, 2)
        self.assertIn("METABASE_CLAVE_USUARIO", error)

    def test_humo_cli_org_invalida(self):
        codigo, _, error = self._cli(["--org", "../x", "--usuario", EMAIL, "--entorno", str(self.archivo)])
        self.assertEqual(codigo, 2)
        self.assertIn("../x", error)

    def test_humo_cli_sin_configuracion(self):
        entorno = {k: v for k, v in self.entorno.items() if k != "METABASE_ADMIN_PASSWORD"}
        codigo, _, error = self._cli(self.argv, entorno=entorno)
        self.assertEqual(codigo, 2)
        self.assertIn("METABASE_ADMIN_PASSWORD", error)

    def test_humo_cli_falta_el_puerto(self):
        self.archivo.write_text("OTRA=1\n", encoding="utf-8")
        desde = len(self.falso.peticiones)
        codigo, _, error = self._cli(self.argv)
        self.assertEqual(codigo, 2)
        self.assertIn("METABASE_PUERTO", error)
        self.assertEqual(self.falso.peticiones[desde:], [])

if __name__ == "__main__":
    unittest.main()
