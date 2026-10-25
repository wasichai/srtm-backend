"""srtm-backend#206, task 9: metabase/compose.yml validates with `docker compose config` against its own
metabase.env.example (plan § Rulings: Postgres app db, DuckDB read-only, access only on 127.0.0.1); it refuses
to run without a secret such as MB_ENCRYPTION_SECRET_KEY; respaldo.sh and restaurar.sh parse as bash and pass
shellcheck when it is installed; the Dockerfile pins both jar sha256 and checks them with sha256sum -c.

`docker compose ... config` needs only the local docker CLI, no daemon (plan § Rulings) - these tests never
build or run a container.
"""

import json
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from herramientas import comun

METABASE = comun.INFORME / "metabase"
EJEMPLO = METABASE / "metabase.env.example"


def _config(env_file, extra_argv=()):
    return subprocess.run(
        ["docker", "compose", "-f", str(METABASE / "compose.yml"), "--env-file", str(env_file), "config", *extra_argv],
        capture_output=True,
        text=True,
    )


@unittest.skipUnless(shutil.which("docker"), "docker no está instalado")
class ComposeConfigTests(unittest.TestCase):
    def test_compose_valido_con_el_ejemplo(self):
        resultado = _config(EJEMPLO, ["--format", "json"])
        self.assertEqual(resultado.returncode, 0, resultado.stderr)
        datos = json.loads(resultado.stdout)

        self.assertEqual(set(datos["services"]), {"metabase", "metabase-postgres"})

        metabase = datos["services"]["metabase"]
        puerto = metabase["ports"][0]
        self.assertEqual(puerto["host_ip"], "127.0.0.1")
        self.assertEqual(str(puerto["published"]), "3310")
        volumen = next(v for v in metabase["volumes"] if v["target"] == "/datos")
        self.assertTrue(volumen["read_only"])
        self.assertEqual(metabase["environment"]["MB_DB_TYPE"], "postgres")
        self.assertEqual(metabase["build"]["args"]["METABASE_VERSION"], "0.63.19")

        postgres = datos["services"]["metabase-postgres"]
        self.assertEqual(postgres["image"], "postgres:18.1")
        self.assertNotIn("ports", postgres)

    def test_falta_un_secreto(self):
        # the example minus MB_ENCRYPTION_SECRET_KEY: every other required variable is still set, so only its
        # absence can make `config` fail
        lineas = [
            linea for linea in EJEMPLO.read_text(encoding="utf-8").splitlines() if "MB_ENCRYPTION_SECRET_KEY" not in linea
        ]
        with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as tmp:
            tmp.write("\n".join(lineas) + "\n")
            env_sin_secreto = Path(tmp.name)
        try:
            resultado = _config(env_sin_secreto, ["-q"])
        finally:
            env_sin_secreto.unlink()
        self.assertNotEqual(resultado.returncode, 0)
        self.assertIn("MB_ENCRYPTION_SECRET_KEY", resultado.stderr)


class ScriptsDeRespaldoTests(unittest.TestCase):
    def test_scripts_de_respaldo(self):
        for script in ("respaldo.sh", "restaurar.sh"):
            with self.subTest(script=script):
                ruta = METABASE / script
                resultado = subprocess.run(["bash", "-n", str(ruta)], capture_output=True, text=True)
                self.assertEqual(resultado.returncode, 0, resultado.stderr)
                if shutil.which("shellcheck"):
                    resultado = subprocess.run(["shellcheck", str(ruta)], capture_output=True, text=True)
                    self.assertEqual(resultado.returncode, 0, resultado.stdout + resultado.stderr)


class DockerfileTests(unittest.TestCase):
    def test_dockerfile_fija_versiones(self):
        texto = (METABASE / "Dockerfile").read_text(encoding="utf-8")
        for arg in ("METABASE_SHA256", "DUCKDB_DRIVER_SHA256"):
            coincide = re.search(rf"ARG {arg}=([0-9a-f]+)", texto)
            self.assertIsNotNone(coincide, f"falta ARG {arg} en el Dockerfile")
            self.assertEqual(len(coincide.group(1)), 64, f"{arg} no es un sha256 (64 hex)")
        self.assertIn("sha256sum -c", texto)


if __name__ == "__main__":
    unittest.main()
