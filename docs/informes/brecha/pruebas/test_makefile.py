"""The Makefile's recipes as `make -n` prints them: nothing runs, no aws call is made. Checks the folders
the recipes hand to the AWS CLI and armar_duckdb.py, and the order of the S3 publish."""

import os
import shutil
import subprocess
import unittest
from pathlib import Path

from herramientas import comun

# variables a caller's shell or an outer `make pruebas` could leak into the dry run
_FUERA = (
    "SALIDA", "FECHA", "ORG", "CORTE", "REEMPLAZAR", "METABASE_ENV", "ENTORNO_ORG", "USUARIO", "NOMBRE",
    "MAKEFLAGS", "MFLAGS", "MAKELEVEL", "MAKEOVERRIDES",
)
ENTORNO_X = f"{Path.home()}/.config/srtm-analitica/x.env"


@unittest.skipIf(shutil.which("make") is None, "make no está instalado")
class MakefileTests(unittest.TestCase):
    def _seco(self, *argumentos):
        entorno = {clave: valor for clave, valor in os.environ.items() if clave not in _FUERA}
        resultado = subprocess.run(
            ["make", "-n", *argumentos],
            cwd=comun.INFORME,
            env=entorno,
            capture_output=True,
            text=True,
        )
        self.assertEqual(resultado.returncode, 0, resultado.stderr)
        return resultado.stdout

    def test_publicar_expande_la_salida_por_omision(self):
        texto = self._seco("publicar", "ORG=x", "FECHA=2026-01-01")
        self.assertNotIn('"~', texto)
        self.assertIn(f'"{Path.home()}/cortes/analitica/x/2026-01-01"', texto)

    def test_publicar_expande_una_salida_con_tilde(self):
        texto = self._seco("publicar", "ORG=x", "FECHA=2026-01-01", "SALIDA=~/otra")
        self.assertNotIn('"~', texto)
        self.assertIn(f'"{Path.home()}/otra/x/2026-01-01"', texto)

    def test_publicar_sube_el_manifiesto_al_final(self):
        texto = self._seco("publicar", "ORG=x", "FECHA=2026-01-01")
        sync = texto.index("aws s3 sync")
        copia = texto.index("aws s3 cp")
        self.assertLess(sync, copia)
        linea_sync = next(linea for linea in texto.splitlines() if "aws s3 sync" in linea)
        self.assertIn("--exclude manifiesto.json", linea_sync)
        self.assertIn("--sse AES256", linea_sync)
        linea_cp = next(linea for linea in texto.splitlines() if "aws s3 cp" in linea)
        self.assertIn("/x/2026-01-01/manifiesto.json", linea_cp)
        self.assertIn("--sse AES256", linea_cp)

    def test_publicar_comprueba_el_manifiesto_con_head_object(self):
        texto = self._seco("publicar", "ORG=x", "FECHA=2026-01-01")
        self.assertIn('aws s3api head-object --bucket "sgtm-dev" --key "analitica/x/2026-01-01/manifiesto.json"', texto)
        self.assertNotIn("aws s3 ls", texto)

    # one Metabase per organization (plan, Decisión 2026-10-25): every Metabase target takes ORG, runs the
    # compose project metabase-<ORG> and reads that organization's own env file

    def test_metabase_up_por_organizacion(self):
        texto = self._seco("metabase-up", "ORG=x")
        linea = next(x for x in texto.splitlines() if "docker compose" in x)
        self.assertIn('METABASE_ORG="x" docker compose -p "metabase-x" -f metabase/compose.yml', linea)
        self.assertIn(f'--env-file "{ENTORNO_X}"', linea)
        self.assertTrue(linea.endswith("up -d --build"), linea)

    def test_metabase_reiniciar_y_detener(self):
        self.assertIn("restart metabase", self._seco("metabase-reiniciar", "ORG=x"))
        self.assertIn('-p "metabase-x"', self._seco("metabase-detener", "ORG=x"))
        self.assertIn("stop metabase", self._seco("metabase-detener", "ORG=x"))

    def test_entorno_org_con_tilde(self):
        texto = self._seco("metabase-up", "ORG=x", "ENTORNO_ORG=~/otro.env")
        self.assertIn(f'--env-file "{Path.home()}/otro.env"', texto)

    def test_respaldo_por_organizacion(self):
        texto = self._seco("respaldo", "ORG=x")
        self.assertIn(f'metabase/respaldo.sh "x" "{ENTORNO_X}"', texto)

    def test_respaldo_no_lee_env(self):
        # ENV is the shell's own startup-file variable (POSIX sh sources $ENV): never the Metabase env file
        texto = self._seco("respaldo", "ORG=x", "ENV=/otro/metabase.env")
        self.assertNotIn("/otro/metabase.env", texto)

    def test_humo_por_organizacion(self):
        texto = self._seco("humo", "ORG=x", "USUARIO=humo-x@ejemplo.pe")
        self.assertIn(f'humo.py --org "x" --usuario "humo-x@ejemplo.pe" --entorno "{ENTORNO_X}"', texto)

    def test_provisionar_con_nombre(self):
        texto = self._seco("provisionar", "ORG=x", "NOMBRE=Equis")
        self.assertIn(f'provisionar.py --org "x:Equis" --entorno "{ENTORNO_X}"', texto)

    def test_duckdb_por_organizacion(self):
        self.assertIn('armar_duckdb.py --org "x"', self._seco("duckdb", "ORG=x"))

    def test_local_sin_barra_final(self):
        texto = self._seco("local", "CORTE=/tmp/cortes/x/2026-01-01/")
        self.assertIn('--destino "/tmp/cortes/x/2026-01-01.duckdb"', texto)
        self.assertNotIn("2026-01-01/.duckdb", texto)


if __name__ == "__main__":
    unittest.main()
