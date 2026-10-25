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

    def test_respaldo_limpia_el_parcial_si_pg_dump_falla(self):
        # a killed pg_dump must not leave a half-written file counted among the 14 kept backups: it is written
        # to a .parcial and only renamed once pg_dump finishes (review finding, fix round 1)
        texto = (METABASE / "respaldo.sh").read_text(encoding="utf-8")
        self.assertIn('parcial="$destino.parcial"', texto)
        self.assertIn("trap 'rm -f \"$parcial\"' EXIT", texto)
        self.assertIn('pg_dump -U metabase -Fc metabase > "$parcial"', texto)
        self.assertIn('mv "$parcial" "$destino"', texto)

    def test_restaurar_no_arranca_metabase_si_pg_restore_falla(self):
        # metabase must stay stopped (never started over a half-restored base) and the operator gets the
        # exact command to start it again once metabase-postgres is fixed (review finding, fix round 1)
        texto = (METABASE / "restaurar.sh").read_text(encoding="utf-8")
        coincide = re.search(r"if ! compose exec -T metabase-postgres pg_restore.*?\n(.*?)fi\n", texto, re.DOTALL)
        self.assertIsNotNone(coincide, "falta el chequeo de pg_restore en restaurar.sh")
        cuerpo = coincide.group(1)
        self.assertIn("exit 1", cuerpo)
        self.assertIn("up -d metabase", cuerpo)
        # "compose up -d metabase" (sin el "if !") solo debe aparecer después del bloque de error, nunca dentro
        self.assertNotIn("compose up -d metabase", cuerpo)


    def test_restaurar_espera_a_metabase_postgres(self):
        # pg_restore right after `up -d` can find postgres still starting: --wait blocks until its healthcheck
        # (pg_isready) passes
        texto = (METABASE / "restaurar.sh").read_text(encoding="utf-8")
        self.assertIn("compose up -d --wait metabase-postgres", texto)
        self.assertLess(texto.index("up -d --wait metabase-postgres"), texto.index("compose exec -T metabase-postgres pg_restore"))
        self.assertNotIn("compose up -d metabase-postgres\n", texto)


class EjemploEnvTests(unittest.TestCase):
    def test_clave_de_cifrado_de_ejemplo_no_sirve(self):
        # a placeholder copied as is must be obviously wrong, yet long enough (>= 32) not to hide that the real
        # one must be too; the guide says to generate it with `openssl rand -hex 32`
        valores = dict(
            linea.split("=", 1) for linea in EJEMPLO.read_text(encoding="utf-8").splitlines()
            if linea and not linea.startswith("#")
        )
        clave = valores["MB_ENCRYPTION_SECRET_KEY"]
        self.assertGreaterEqual(len(clave), 32)
        self.assertIn("NO-USAR", clave)
        self.assertIn("openssl rand -hex 32", EJEMPLO.read_text(encoding="utf-8"))


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
