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
    "SALIDA", "FECHA", "ORG", "CORTE", "REEMPLAZAR", "METABASE_ENV", "MAKEFLAGS", "MFLAGS", "MAKELEVEL",
    "MAKEOVERRIDES",
)


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

    def test_respaldo_toma_metabase_env(self):
        # METABASE_ENV, not ENV: ENV is the shell's own startup-file variable (POSIX sh sources $ENV)
        texto = self._seco("respaldo", "METABASE_ENV=/srv/srtm-metabase/metabase.env")
        self.assertIn('metabase/respaldo.sh "/srv/srtm-metabase/metabase.env"', texto)

    def test_respaldo_no_lee_env(self):
        texto = self._seco("respaldo", "ENV=/srv/srtm-metabase/metabase.env")
        self.assertNotIn("/srv/srtm-metabase/metabase.env", texto)
        self.assertIn("METABASE_ENV", texto)

    def test_local_sin_barra_final(self):
        texto = self._seco("local", "CORTE=/tmp/cortes/x/2026-01-01/")
        self.assertIn('--destino "/tmp/cortes/x/2026-01-01.duckdb"', texto)
        self.assertNotIn("2026-01-01/.duckdb", texto)


if __name__ == "__main__":
    unittest.main()
