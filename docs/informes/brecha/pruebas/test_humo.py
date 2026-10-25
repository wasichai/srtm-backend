"""#206 task 11: herramientas.metabase.humo against pruebas.metabase_falso.MetabaseFalso, and humo.py's CLI."""

import contextlib
import io
import json
import os
import unittest
from unittest import mock

import humo
from herramientas import comun, metabase
from pruebas.metabase_falso import MetabaseFalso

ADMIN = ("admin@ejemplo.pe", "clave-admin-1")
ORGS = {"ficticia-a": "Ficticia A", "ficticia-b": "Ficticia B"}
CLAVE_USUARIO = "clave-usuario-1"


def _tableros():
    return json.loads((comun.INFORME / "metabase" / "tableros.json").read_text(encoding="utf-8"))


class ConHumo(unittest.TestCase):
    """A fresh MetabaseFalso, provisioned for ficticia-a/b, with one user per organization sharing
    CLAVE_USUARIO - the same shape humo.py's --usuario expects."""

    def setUp(self):
        self.falso = MetabaseFalso({ADMIN[0]: ADMIN[1]})
        self.addCleanup(self.falso.cerrar)
        self.mb = metabase.Metabase(self.falso.url)
        self.mb.sesion(*ADMIN)
        self.resultado = metabase.provisionar(self.mb, ORGS, _tableros())
        self.usuarios = {}
        for org in ORGS:
            email = f"{org}@ejemplo.pe"
            metabase.agregar_usuario(self.mb, org, email, "Prueba", org, CLAVE_USUARIO)
            self.usuarios[org] = (email, CLAVE_USUARIO)


class HumoTests(ConHumo):
    def test_humo_pasa_con_aislamiento(self):
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertEqual(fallas, [])
        self.assertGreater(fallas.comprobaciones, 0)

    def test_humo_detecta_una_base_visible(self):
        self.falso.listar_todas_las_bases = True
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertTrue(
            any("ficticia-a" in f and "Brecha Ficticia B" in f for f in fallas), fallas
        )

    def test_humo_detecta_sql_nativo(self):
        self.falso.permitir_nativo = True
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertTrue(any("sql nativo" in f.lower() for f in fallas), fallas)

    def test_humo_detecta_tarjeta_rota(self):
        alguna = next(iter(self.resultado["ficticia-a"]["tarjetas"]))
        nombre = self.falso.tarjetas[alguna]["name"]
        self.falso.tarjetas_fallidas = {alguna}
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertTrue(any(nombre in f for f in fallas), fallas)

    def test_humo_detecta_que_el_usuario_no_ve_su_propia_base(self):
        # a membership mistake (the user was never put in its own group), not a graph mistake: _base_propia
        # (admin side, by group) still finds the base; the user's own session does not
        uid = next(u["id"] for u in self.falso.usuarios.values() if u["email"] == self.usuarios["ficticia-a"][0])
        grupo_a = self.resultado["ficticia-a"]["grupo"]
        self.falso.membresias = [
            m for m in self.falso.membresias if not (m["user_id"] == uid and m["group_id"] == grupo_a)
        ]
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertTrue(any("ficticia-a no ve su propia base" in f for f in fallas), fallas)

    def test_humo_detecta_tarjeta_ajena_legible(self):
        self.falso.permitir_lectura_ajena = True
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertTrue(any("pudo leer una tarjeta" in f for f in fallas), fallas)

    def test_humo_detecta_attach_que_no_falla(self):
        self.falso.permitir_acceso_externo = True
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertTrue(any("ATTACH" in f for f in fallas), fallas)

    def test_humo_attach_rechazado_con_400_no_es_falla(self):
        # Metabase 0.63.19 answers a refused query with an HTTP error status (400 for DuckDB's "file system
        # operations are disabled"), not 202 + status "failed": that is the expected outcome of the ATTACH check
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertEqual(fallas, [])
        attach = [
            (m, ruta) for m, ruta, cuerpo in self.falso.peticiones
            if ruta == "/api/dataset" and "ATTACH" in str((cuerpo or {}).get("native"))
        ]
        self.assertEqual(len(attach), 2)

    def test_humo_tarjeta_que_falla_con_400_es_una_falla_y_sigue(self):
        normal = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        alguna = self.resultado["ficticia-a"]["tarjetas"][0]
        nombre = self.falso.tarjetas[alguna]["name"]
        self.falso.tarjetas_fallidas = {alguna}
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertEqual(len(fallas), 1, fallas)
        self.assertIn(nombre, fallas[0])
        self.assertIn("400", fallas[0])
        # every other comprobación still ran (isolation, ATTACH...): a broken card does not stop the run
        self.assertEqual(fallas.comprobaciones, normal.comprobaciones)

    def test_humo_detecta_acceso_externo_activo(self):
        # the positive check: current_setting('enable_external_access') must be false on every base, so the
        # ATTACH check can never pass just because the query failed for some other reason
        base_a = self.resultado["ficticia-a"]["base"]
        self.falso.bases[base_a]["details"]["init_sql"] = ""
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertTrue(
            any("Brecha Ficticia A" in f and "enable_external_access" in f for f in fallas), fallas
        )
        self.assertFalse(any("Brecha Ficticia B" in f for f in fallas), fallas)

    def test_humo_comprueba_enable_external_access_en_cada_base(self):
        metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        consultadas = {
            cuerpo["database"] for m, ruta, cuerpo in self.falso.peticiones
            if ruta == "/api/dataset" and "current_setting" in str((cuerpo or {}).get("native"))
        }
        self.assertEqual(consultadas, {r["base"] for r in self.resultado.values()})

    def test_humo_no_truena_si_una_base_se_renombro_fuera_del_prefijo(self):
        # the admin's permissions graph (where humo finds an organization's "own" base, by group) and
        # GET /api/database (where it resolves a base id back to a name) can disagree - a renamed base must
        # not raise an uncaught StopIteration out of humo()
        base_a = self.resultado["ficticia-a"]["base"]
        self.falso.bases[base_a]["name"] = "renombrada a mano, sin el prefijo Brecha"
        fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertIsInstance(fallas, list)

    def test_humo_detecta_una_base_que_no_sincroniza(self):
        base_id = self.resultado["ficticia-a"]["base"]
        with mock.patch.object(metabase, "_esperar_vista", side_effect=lambda mb, bid, vista, espera: bid != base_id):
            fallas = metabase.humo(self.falso.url, ADMIN, self.usuarios, espera=1)
        self.assertTrue(any("Brecha Ficticia A" in f and "v_segmentos" in f for f in fallas), fallas)


class CliTests(ConHumo):
    def setUp(self):
        super().setUp()
        self.entorno = {
            "METABASE_URL": self.falso.url,
            "METABASE_ADMIN_EMAIL": ADMIN[0],
            "METABASE_ADMIN_PASSWORD": ADMIN[1],
            "METABASE_CLAVE_USUARIO": CLAVE_USUARIO,
        }
        self.argv = [arg for org in self.usuarios for arg in ("--usuario", f"{org}:{self.usuarios[org][0]}")]

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
        self.falso.tarjetas_fallidas = {next(iter(self.resultado["ficticia-a"]["tarjetas"]))}
        codigo, salida, error = self._cli(self.argv)
        self.assertEqual(codigo, 1)
        self.assertEqual(salida, "")
        self.assertTrue(error.strip())

    def test_humo_cli_falta_la_clave_de_usuario(self):
        entorno = {k: v for k, v in self.entorno.items() if k != "METABASE_CLAVE_USUARIO"}
        codigo, _, error = self._cli(self.argv, entorno=entorno)
        self.assertEqual(codigo, 2)
        self.assertIn("METABASE_CLAVE_USUARIO", error)

    def test_humo_cli_usuario_mal_escrito(self):
        codigo, _, error = self._cli(["--usuario", "sin-dos-puntos"])
        self.assertEqual(codigo, 2)
        self.assertIn("sin-dos-puntos", error)

    def test_humo_cli_sin_configuracion(self):
        entorno = {k: v for k, v in self.entorno.items() if k != "METABASE_ADMIN_PASSWORD"}
        codigo, _, error = self._cli(self.argv, entorno=entorno)
        self.assertEqual(codigo, 2)
        self.assertIn("METABASE_ADMIN_PASSWORD", error)


if __name__ == "__main__":
    unittest.main()
