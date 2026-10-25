"""#206 task 8: one DuckDB file per organization, built atomically from that organization's latest published
corte - `herramientas.s3` (download), `herramientas.base.construir_atomico` and `herramientas.base.INIT_SQL`
(Metabase's own isolation), the five tablero-only views `vistas.sql` gained, and `ficticio.py` (the smoke
test's fictitious organizations). `armar_duckdb.py --org` wires s3 + construir_atomico together but is not
re-tested here beyond that wiring: its --corte/--destino path is task 6's own (pruebas/test_vistas.py)."""

import contextlib
import io
import json
import shutil
import stat
import subprocess
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

import duckdb

import armar_duckdb
import ficticio
from herramientas import base, comun, manifiesto, s3
from herramientas.sinteticos import minimos
from pruebas.test_corte import ConCorte


def _cli(funcion, argv):
    """Runs a script's `main` capturing stdout/stderr, like pruebas/test_vistas.py's `_armar`."""
    salida, error = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(salida), contextlib.redirect_stderr(error):
        codigo = funcion(argv)
    return codigo, salida.getvalue(), error.getvalue()

TIENDAS = "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES"


class UltimoCorteTests(unittest.TestCase):
    def test_ultimo_corte_toma_la_fecha_mayor(self):
        listado = "PRE 2026-09-01/\nPRE 2026-11-01/\nPRE 2026-10-01/\nPRE tmp/\n2026-12-01 x.json"
        self.assertEqual(s3.ultimo_corte(listado), "2026-11-01")
        self.assertIsNone(s3.ultimo_corte(""))
        self.assertIsNone(s3.ultimo_corte("PRE tmp/\n2026-12-01 x.json"))


class DescargarTests(ConCorte, unittest.TestCase):
    def _sincronizador(self, origen_local):
        """A fake `ejecutar`: "aws s3 ls" answers a canned listing, and "aws s3 sync" copies `origen_local`'s
        files into the destination the real sync command would have written them to - close enough to let
        manifiesto.verificar_corte run for real on the result."""
        llamadas = []

        def ejecutar(cmd, **kwargs):
            llamadas.append(cmd)
            if cmd[:3] == ["aws", "s3", "ls"]:
                return subprocess.CompletedProcess(cmd, 0, stdout="PRE 2026-10-01/\nPRE 2026-11-01/\n", stderr="")
            if cmd[:3] == ["aws", "s3", "sync"]:
                destino = Path(cmd[4])
                destino.mkdir(parents=True, exist_ok=True)
                for archivo in origen_local.iterdir():
                    (destino / archivo.name).write_bytes(archivo.read_bytes())
                return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")
            raise AssertionError(f"comando inesperado: {cmd}")

        return ejecutar, llamadas

    def test_descargar_sincroniza_el_ultimo(self):
        origen_local = self._corte(minimos())
        ejecutar, llamadas = self._sincronizador(origen_local)
        cortes = self.tmp / "cortes"

        carpeta = s3.descargar("s3://sgtm-dev/analitica", "perene", cortes, ejecutar=ejecutar)

        self.assertEqual(carpeta, cortes / "perene" / "2026-11-01")
        self.assertEqual(llamadas[0], ["aws", "s3", "ls", "s3://sgtm-dev/analitica/perene/"])
        self.assertEqual(
            llamadas[1][:3] + llamadas[1][-1:],
            ["aws", "s3", "sync", "--only-show-errors"],
        )
        self.assertEqual(llamadas[1][3], "s3://sgtm-dev/analitica/perene/2026-11-01/")
        self.assertEqual(llamadas[1][4], str(carpeta))
        manifiesto.verificar_corte(carpeta)  # no ErrorCorte: the "downloaded" corte matches its manifest

    def test_descargar_sin_corte_publicado(self):
        def ejecutar(cmd, **kwargs):
            return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

        with self.assertRaises(comun.ErrorCorte) as error:
            s3.descargar("s3://sgtm-dev/analitica", "perene", self.tmp / "cortes", ejecutar=ejecutar)
        self.assertIn("perene", str(error.exception))

    def test_descargar_rechaza_una_organizacion_invalida(self):
        # an unvalidated org becomes a filesystem path segment (cortes/org/fecha) and an S3 key segment: "../x"
        # must be rejected before aws is ever invoked and before anything is written under `cortes`
        llamadas = []

        def ejecutar(cmd, **kwargs):
            llamadas.append(cmd)
            raise AssertionError("ejecutar no debía llamarse con una organización inválida")

        cortes = self.tmp / "cortes"
        with self.assertRaises(comun.ErrorCorte):
            s3.descargar("s3://sgtm-dev/analitica", "../x", cortes, ejecutar=ejecutar)
        self.assertEqual(llamadas, [])
        self.assertFalse(cortes.exists())


class ArmarDuckdbCliTests(ConCorte, unittest.TestCase):
    def test_armar_org_rechaza_una_organizacion_invalida(self):
        # _armar_org validates before calling comun.parametros() or s3.descargar - no S3/AWS involved at all
        codigo, _, error = _cli(armar_duckdb.main, ["--org", "../x", "--destino-dir", str(self.tmp / "duckdb")])
        self.assertEqual(codigo, 2)
        self.assertIn("organización inválida", error)
        self.assertFalse((self.tmp / "duckdb").exists())

    def test_armar_duckdb_rechaza_cortes_y_destino_dir_con_corte(self):
        carpeta = self._corte(minimos())
        for opcion in ("--cortes", "--destino-dir"):
            with self.subTest(opcion=opcion):
                codigo, _, error = _cli(
                    armar_duckdb.main,
                    ["--corte", str(carpeta), "--destino", str(self.tmp / "x.duckdb"), opcion, str(self.tmp)],
                )
                self.assertEqual(codigo, 2)
                self.assertIn("--corte", error)
                self.assertFalse((self.tmp / "x.duckdb").exists())


class ConstruirAtomicoTests(ConCorte, unittest.TestCase):
    def test_reemplazo_atomico_y_permisos(self):
        carpeta = self._corte(minimos())
        destino = self.tmp / "org.duckdb"

        base.construir_atomico(carpeta, destino)

        self.assertTrue(destino.is_file())
        self.assertEqual(stat.S_IMODE(destino.stat().st_mode), 0o644)
        self.assertFalse(destino.with_name(destino.name + ".nuevo").exists())
        con = duckdb.connect(str(destino), read_only=True)
        self.addCleanup(con.close)
        self.assertEqual(con.execute("SELECT count(*) FROM v_segmentos").fetchone()[0], 3)

    def test_falla_deja_la_base_anterior(self):
        carpeta = self._corte(minimos())
        destino = self.tmp / "org.duckdb"
        base.construir_atomico(carpeta, destino)
        sha_antes = comun.sha256(destino)

        # a Parquet altered after publishing: the corte no longer matches its own manifiesto.json
        archivo = carpeta / "catastro_lote.parquet"
        contenido = bytearray(archivo.read_bytes())
        contenido[0] ^= 0xFF
        archivo.write_bytes(bytes(contenido))

        with self.assertRaises(comun.ErrorCorte):
            base.construir_atomico(carpeta, destino)

        self.assertEqual(comun.sha256(destino), sha_antes)
        self.assertFalse(destino.with_name(destino.name + ".nuevo").exists())


class InitSqlTests(ConCorte, unittest.TestCase):
    def test_init_sql_aisla_la_base(self):
        carpeta = self._corte(minimos())
        a, b = self.tmp / "a.duckdb", self.tmp / "b.duckdb"
        base.construir_atomico(carpeta, a)
        base.construir_atomico(carpeta, b)

        con = duckdb.connect(str(a), read_only=True)
        self.addCleanup(con.close)
        con.execute(base.INIT_SQL)  # Metabase's own init_sql, run right after opening the file read-only

        self.assertEqual(con.execute("SELECT count(*) FROM v_segmentos").fetchone()[0], 3)
        with self.assertRaises(duckdb.Error):
            con.execute(f"ATTACH '{b}'")
        with self.assertRaises(duckdb.Error):
            con.execute(f"SELECT * FROM read_parquet('{carpeta / 'srtm_predio.parquet'}')")
        with self.assertRaises(duckdb.Error):
            con.execute("SET enable_external_access = true")

    def test_init_sql_corre_dos_veces(self):
        # the driver runs init_sql on each pooled connection and again on a duplicate() of it (same DuckDB
        # instance) to sync: both must work, and the second one must leave the isolation as it was
        carpeta = self._corte(minimos())
        a, b = self.tmp / "a.duckdb", self.tmp / "b.duckdb"
        base.construir_atomico(carpeta, a)
        base.construir_atomico(carpeta, b)

        con = duckdb.connect(str(a), read_only=True)
        self.addCleanup(con.close)
        con.execute(base.INIT_SQL)
        con.execute(base.INIT_SQL)
        duplicada = con.cursor()  # another connection to the same instance, like DuckDBConnection.duplicate()
        self.addCleanup(duplicada.close)
        duplicada.execute(base.INIT_SQL)
        duplicada.execute("SET TimeZone = 'America/Lima'")  # the driver's per-connection report timezone

        self.assertEqual(duplicada.execute("SELECT count(*) FROM v_segmentos").fetchone()[0], 3)
        for sql in (f"ATTACH '{b}'", "SET enable_external_access = true"):
            with self.subTest(sql=sql), self.assertRaises(duckdb.Error):
                duplicada.execute(sql)


class VistasDeLosTablerosTests(ConCorte, unittest.TestCase):
    def test_vistas_de_los_tableros(self):
        carpeta = self._corte(minimos())
        con = base.construir_duckdb(carpeta, self.tmp / "tableros.duckdb")
        self.addCleanup(con.close)

        # v_lotes_mapa: 0101001/0101002 have a loteGeom (catastro_lote); 0101003 (SIN_LEVANTAMIENTO) does not
        filas = dict(con.execute("SELECT codigo_municipal, categoria FROM v_lotes_mapa").fetchall())
        self.assertEqual(set(filas), {"0101001", "0101002"})
        tipos = {fila[0]: fila[1] for fila in con.execute("DESCRIBE v_lotes_mapa").fetchall()}
        self.assertEqual(tipos["lat"], "DOUBLE")
        self.assertEqual(tipos["lon"], "DOUBLE")

        # minimos()'s 3 cambios, all in October 2026 (plan, Rulings de la ejecución, R24: datos() shares an
        # `en` between two cambios; minimos() keeps 3 distinct ones)
        self.assertEqual(con.execute("SELECT sum(cambios) FROM v_cambios_por_mes").fetchone()[0], 3)
        self.assertEqual(con.execute("SELECT count(*) FROM v_cambios_recientes").fetchone()[0], 3)

        # v_brecha_confiable_por_clase: v_brecha_por_clase restricted to CONFIABLE (BP1, clase TIENDAS)
        self.assertEqual(
            con.execute("SELECT clase, predios, delta_impuesto FROM v_brecha_confiable_por_clase").fetchall(),
            [(TIENDAS, 1, Decimal("15.00"))],
        )

        self.assertEqual(con.execute("SELECT count(*) FROM v_corte").fetchone()[0], 1)

    def test_corte_manifiesto_trae_corrida_huella_y_avisos(self):
        carpeta = self._corte(minimos())
        m = manifiesto.verificar_corte(carpeta)
        con = base.construir_duckdb(carpeta, self.tmp / "manifiesto.duckdb")
        self.addCleanup(con.close)

        fila = con.execute(
            "SELECT brecha_corrida_id, version_catastro, huella_clave, avisos FROM corte_manifiesto"
        ).fetchone()
        self.assertEqual(fila[0], m["brecha_corrida"]["id"])
        self.assertEqual(fila[1], m["brecha_corrida"]["version_catastro"])
        self.assertEqual(fila[2], m["seudonimizacion"]["huella_clave"])
        self.assertTrue(m["avisos"])  # this corte does carry avisos (no VERSION_SRTM/CATASTRO in the test env)
        self.assertEqual(fila[3], "\n".join(m["avisos"]))

    def test_corte_manifiesto_sin_brecha_terminada(self):
        s = minimos()
        s.srtm["brecha_corrida"] = [c for c in s.srtm["brecha_corrida"] if c["attributes"]["estado"] != "TERMINADA"]
        carpeta = self._corte(s)
        con = base.construir_duckdb(carpeta, self.tmp / "sin_corrida.duckdb")
        self.addCleanup(con.close)
        fila = con.execute("SELECT brecha_corrida_id, version_catastro FROM v_corte").fetchone()
        self.assertEqual(fila, (None, None))


class FicticioTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="ficticio-prueba-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def _manifiesto(self, salida, org, fecha):
        return json.loads((salida / org / fecha / "manifiesto.json").read_text(encoding="utf-8"))

    def test_ficticio_reproducible(self):
        org, semilla, fecha = "ficticia-a", "1", "2026-10-25"
        salidas = [self.tmp / "uno", self.tmp / "dos"]
        for salida in salidas:
            codigo = ficticio.main(["--org", org, "--semilla", semilla, "--fecha", fecha, "--salida", str(salida)])
            self.assertEqual(codigo, 0)

        m1, m2 = (self._manifiesto(salida, org, fecha) for salida in salidas)
        self.assertEqual(
            {a["archivo"]: a["sha256"] for a in m1["archivos"]},
            {a["archivo"]: a["sha256"] for a in m2["archivos"]},
        )
        # the two organizations of the smoke test must not share a seed (plan § Rulings, aislamiento comprobable)
        codigo = ficticio.main(
            ["--org", "ficticia-b", "--semilla", "2", "--fecha", fecha, "--salida", str(self.tmp / "otra")]
        )
        self.assertEqual(codigo, 0)
        m_b = self._manifiesto(self.tmp / "otra", "ficticia-b", fecha)
        self.assertNotEqual(
            {a["sha256"] for a in m1["archivos"]},
            {a["sha256"] for a in m_b["archivos"]},
        )

    def test_ficticio_rechaza_una_organizacion_invalida(self):
        # validated before building the temp secrets file path (<tmp>/<org>.env) or starting the fakes
        salida = self.tmp / "salida"
        codigo, _, error = _cli(
            ficticio.main,
            ["--org", "../x", "--semilla", "1", "--fecha", "2026-10-25", "--salida", str(salida)],
        )
        self.assertEqual(codigo, 2)
        self.assertIn("organización inválida", error)
        self.assertFalse(salida.exists())


if __name__ == "__main__":
    unittest.main()
