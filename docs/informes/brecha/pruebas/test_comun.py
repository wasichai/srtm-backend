"""Shared helpers: parameters, dates, the corte folder, and the secrets file (contexto.md rulings)."""

import tempfile
import unittest
from datetime import date
from pathlib import Path

from herramientas import comun
from herramientas.comun import REPO, REQUERIDOS, ErrorCorte, carpeta_corte, leer_secretos, validar_fecha, validar_org


class ParametrosTests(unittest.TestCase):
    def test_lee_las_organizaciones_y_la_publicacion(self):
        datos = comun.parametros()
        self.assertEqual(datos["publicacion"]["s3"], "s3://sgtm-dev/analitica")
        self.assertEqual(datos["organizaciones"]["perene"]["nombre"], "Municipalidad Distrital de Perené")
        self.assertEqual(datos["organizaciones"]["ica"]["nombre"], "Municipalidad Provincial de Ica")


class HoyLimaTests(unittest.TestCase):
    def test_devuelve_una_fecha(self):
        self.assertIsInstance(comun.hoy_lima(), date)


class ValidarFechaTests(unittest.TestCase):
    def test_fecha_futura_se_rechaza(self):
        with self.assertRaises(ErrorCorte):
            validar_fecha("2026-10-26", date(2026, 10, 25))

    def test_fecha_mal_formada_se_rechaza(self):
        for texto in ("2026-13-01", "25/10/2026"):
            with self.subTest(texto=texto):
                with self.assertRaises(ErrorCorte):
                    validar_fecha(texto, date(2026, 10, 25))

    def test_fecha_valida_se_acepta(self):
        self.assertEqual(validar_fecha("2026-10-25", date(2026, 10, 25)), date(2026, 10, 25))


class ValidarOrgTests(unittest.TestCase):
    def test_org_invalida_se_rechaza(self):
        for org in ("Perene", "a", "../x"):
            with self.subTest(org=org):
                with self.assertRaises(ErrorCorte):
                    validar_org(org)

    def test_org_valida_se_acepta(self):
        for org in ("perene", "ficticia-a"):
            with self.subTest(org=org):
                self.assertEqual(validar_org(org), org)


class CarpetaCorteTests(unittest.TestCase):
    def test_carpeta_dentro_del_repo_se_rechaza(self):
        base = REPO / "tmp"
        with self.assertRaises(ErrorCorte):
            carpeta_corte(base, "perene", date(2026, 10, 25))

    def test_carpeta_existente_pide_reemplazar(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            fecha = date(2026, 10, 25)
            carpeta = carpeta_corte(base, "perene", fecha)
            carpeta.mkdir(parents=True)
            (carpeta / "archivo").write_text("x", encoding="utf-8")
            with self.assertRaises(ErrorCorte):
                carpeta_corte(base, "perene", fecha)
            # reemplazar=True accepts the existing folder and leaves it untouched
            self.assertEqual(carpeta_corte(base, "perene", fecha, reemplazar=True), carpeta)

    def test_carpeta_devuelve_base_org_fecha_sin_crearla(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            fecha = date(2026, 10, 25)
            carpeta = carpeta_corte(base, "perene", fecha)
            self.assertEqual(carpeta, base / "perene" / "2026-10-25")
            self.assertFalse(carpeta.exists())


class LeerSecretosTests(unittest.TestCase):
    def _escribir(self, tmp, contenido, modo=0o600):
        ruta = Path(tmp) / "org.env"
        ruta.write_text(contenido, encoding="utf-8")
        ruta.chmod(modo)
        return ruta

    def test_secretos_con_permisos_abiertos_se_rechazan(self):
        with tempfile.TemporaryDirectory() as tmp:
            ruta = self._escribir(tmp, "SRTM_URL=x\n", modo=0o644)
            with self.assertRaises(ErrorCorte) as ctx:
                leer_secretos(ruta)
            self.assertIn(str(ruta), str(ctx.exception))

    def test_secretos_incompletos_nombran_la_clave_sin_valores(self):
        with tempfile.TemporaryDirectory() as tmp:
            contenido = "\n".join(
                f"{clave}=valor-de-{clave.lower()}" for clave in REQUERIDOS if clave != "CATASTRO_CLIENT_SECRET"
            )
            contenido = contenido.replace("valor-de-srtm_client_secret", "s3cr3t")
            ruta = self._escribir(tmp, contenido + "\n")
            with self.assertRaises(ErrorCorte) as ctx:
                leer_secretos(ruta)
            mensaje = str(ctx.exception)
            self.assertIn("CATASTRO_CLIENT_SECRET", mensaje)
            self.assertNotIn("s3cr3t", mensaje)

    def test_secretos_ignoran_comentarios_y_blancos(self):
        with tempfile.TemporaryDirectory() as tmp:
            lineas = [f"{clave}=valor-de-{clave}" for clave in REQUERIDOS]
            lineas += ["# un comentario", "", "A=b=c"]
            ruta = self._escribir(tmp, "\n".join(lineas) + "\n")
            secretos = leer_secretos(ruta)
            self.assertEqual(secretos["A"], "b=c")
            for clave in REQUERIDOS:
                self.assertEqual(secretos[clave], f"valor-de-{clave}")


class Sha256Tests(unittest.TestCase):
    def test_calcula_el_sha256_del_archivo(self):
        with tempfile.TemporaryDirectory() as tmp:
            ruta = Path(tmp) / "archivo"
            ruta.write_bytes(b"hola")
            self.assertEqual(
                comun.sha256(ruta),
                "b221d9dbb083a7f33428d7c2a3c3198ae925614d70210e28716ccaa7cd4ddb79",
            )


if __name__ == "__main__":
    unittest.main()
