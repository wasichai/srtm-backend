"""srtm-backend#206, task 9, and the one-Metabase-per-organization decision (plan, Decisión 2026-10-25):
metabase/compose.yml validates with `docker compose config` against its own metabase.env.example, once per
organization (METABASE_ORG, which the Makefile sets from ORG): Postgres app db, the organization's DuckDB folder
alone, read-only, access only on 127.0.0.1 at the organization's METABASE_PUERTO, one shared image; it refuses
to run without a secret such as MB_ENCRYPTION_SECRET_KEY, without METABASE_PUERTO or without METABASE_ORG;
respaldo.sh and restaurar.sh parse as bash and pass shellcheck when it is installed; the Dockerfile pins both
jar sha256 and checks them with sha256sum -c.

`docker compose ... config` needs only the local docker CLI, no daemon (plan § Rulings) - these tests never
build or run a container.
"""

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from herramientas import comun

METABASE = comun.INFORME / "metabase"
EJEMPLO = METABASE / "metabase.env.example"


def _config(env_file, extra_argv=(), org="ficticia-a"):
    entorno = {k: v for k, v in os.environ.items() if not k.startswith(("METABASE_", "MB_", "COMPOSE_"))}
    if org is not None:
        entorno["METABASE_ORG"] = org
    return subprocess.run(
        ["docker", "compose", "-f", str(METABASE / "compose.yml"), "--env-file", str(env_file), "config", *extra_argv],
        capture_output=True,
        text=True,
        env=entorno,
    )


def _sin(*claves):
    """A copy of the example env without the lines of `claves` (deleted with the file by the caller)."""
    lineas = [
        linea for linea in EJEMPLO.read_text(encoding="utf-8").splitlines() if not linea.startswith(tuple(claves))
    ]
    with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as tmp:
        tmp.write("\n".join(lineas) + "\n")
    return Path(tmp.name)


@unittest.skipUnless(shutil.which("docker"), "docker no está instalado")
class ComposeConfigTests(unittest.TestCase):
    def _datos(self, org="ficticia-a", env_file=EJEMPLO):
        resultado = _config(env_file, ["--format", "json"], org=org)
        self.assertEqual(resultado.returncode, 0, resultado.stderr)
        return json.loads(resultado.stdout)

    def test_compose_valido_con_el_ejemplo(self):
        datos = self._datos()
        self.assertEqual(datos["name"], "metabase-ficticia-a")
        self.assertEqual(set(datos["services"]), {"metabase", "metabase-postgres"})

        metabase = datos["services"]["metabase"]
        puerto = metabase["ports"][0]
        self.assertEqual(puerto["host_ip"], "127.0.0.1")
        self.assertEqual(str(puerto["published"]), "3310")
        self.assertEqual(metabase["environment"]["MB_SITE_URL"], "http://localhost:3310")
        volumen = next(v for v in metabase["volumes"] if v["target"] == "/datos")
        self.assertTrue(volumen["read_only"])
        # only this organization's folder, never the parent that holds every organization's
        self.assertEqual(volumen["source"], "/srv/srtm-metabase/ficticia-a/duckdb")
        self.assertEqual(metabase["environment"]["MB_DB_TYPE"], "postgres")
        self.assertEqual(metabase["build"]["args"]["METABASE_VERSION"], "0.63.19")

        postgres = datos["services"]["metabase-postgres"]
        self.assertEqual(postgres["image"], "postgres:18.1")
        self.assertNotIn("ports", postgres)

    def test_dos_organizaciones_no_comparten_nada_salvo_la_imagen(self):
        a, b = self._datos("ficticia-a"), self._datos("ficticia-b")
        self.assertNotEqual(a["name"], b["name"])
        datos_a = next(v["source"] for v in a["services"]["metabase"]["volumes"] if v["target"] == "/datos")
        datos_b = next(v["source"] for v in b["services"]["metabase"]["volumes"] if v["target"] == "/datos")
        self.assertEqual(datos_b, "/srv/srtm-metabase/ficticia-b/duckdb")
        self.assertNotEqual(datos_a, datos_b)
        self.assertNotEqual(a["volumes"]["metabase-pg-data"]["name"], b["volumes"]["metabase-pg-data"]["name"])
        # the image is built once and shared: same tag and same build for every organization
        self.assertEqual(a["services"]["metabase"]["image"], b["services"]["metabase"]["image"])
        self.assertEqual(a["services"]["metabase"]["build"], b["services"]["metabase"]["build"])

    def test_otro_puerto(self):
        with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as tmp:
            tmp.write(EJEMPLO.read_text(encoding="utf-8").replace("METABASE_PUERTO=3310", "METABASE_PUERTO=3311"))
        try:
            datos = self._datos("ica", Path(tmp.name))
        finally:
            Path(tmp.name).unlink()
        metabase = datos["services"]["metabase"]
        self.assertEqual(str(metabase["ports"][0]["published"]), "3311")
        self.assertEqual(metabase["environment"]["MB_SITE_URL"], "http://localhost:3311")
        volumen = next(v for v in metabase["volumes"] if v["target"] == "/datos")
        self.assertEqual(volumen["source"], "/srv/srtm-metabase/ica/duckdb")

    def _falla_sin(self, clave, org="ficticia-a", env_file=None):
        env_file = env_file or _sin(clave)
        try:
            resultado = _config(env_file, ["-q"], org=org)
        finally:
            if env_file != EJEMPLO:
                env_file.unlink()
        self.assertNotEqual(resultado.returncode, 0)
        self.assertIn(clave, resultado.stderr)

    def test_falta_un_secreto(self):
        # the example minus MB_ENCRYPTION_SECRET_KEY: every other required variable is still set, so only its
        # absence can make `config` fail
        self._falla_sin("MB_ENCRYPTION_SECRET_KEY")

    def test_falta_el_puerto(self):
        # no default port: two organizations would otherwise both take 3310, and the second one's up would fail
        # only at bind time, or worse, a tunnel would reach the wrong one
        self._falla_sin("METABASE_PUERTO")

    def test_falta_la_organizacion(self):
        self._falla_sin("METABASE_ORG", org=None, env_file=EJEMPLO)


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

    def test_scripts_operan_sobre_la_instancia_de_la_organizacion(self):
        # one Metabase per organization (plan, Decisión 2026-10-25): both scripts take ORG first, use the compose
        # project metabase-<org> with METABASE_ORG set, and a backup carries the organization in its name
        for script in ("respaldo.sh", "restaurar.sh"):
            with self.subTest(script=script):
                texto = (METABASE / script).read_text(encoding="utf-8")
                self.assertIn('org=${1:?uso:', texto)
                # comun.validar_org's own pattern, before the org reaches a project name or a path
                self.assertIn('[[ "$org" =~ ^[a-z][a-z0-9-]{1,30}$ ]]', texto)
                self.assertIn('METABASE_ORG="$org" docker compose -p "metabase-$org"', texto)
        texto = (METABASE / "respaldo.sh").read_text(encoding="utf-8")
        self.assertIn('destino="$respaldos/metabase-$org-$sello.dump"', texto)
        self.assertIn("-name \"metabase-$org-*.dump\"", texto)
        self.assertIn('respaldos=${METABASE_RESPALDOS_DIR:-/srv/srtm-metabase/$org/respaldos}', texto)

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
