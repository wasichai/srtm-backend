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
