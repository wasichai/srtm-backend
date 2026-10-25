"""The DuckDB of a corte: `base.construir_duckdb` over a corte of `minimos()` taken by `corte.main` against the
fakes (test_corte.ConCorte), and the views of vistas.sql against the brecha report v3 figures the fixture was
built to give. Plus `armar_duckdb.main`'s exit codes."""

import contextlib
import io
import unittest
from decimal import Decimal

import armar_duckdb
from herramientas import base
from herramientas.sinteticos import minimos
from pruebas.test_corte import ORG, ConCorte

PARAMETROS = "5500, 0.2, 0.6, 1.0, 15, 60, 0.6"
CASA = "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA"
TIENDAS = "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES"


class VistasTests(ConCorte, unittest.TestCase):
    def _base(self, s=None, **opciones_catastro):
        carpeta = self._corte(s or minimos(), **opciones_catastro)
        con = base.construir_duckdb(carpeta, self.tmp / "corte.duckdb")
        self.addCleanup(con.close)
        return con

    def _impuesto(self, con, base_imponible, parametros=PARAMETROS):
        return con.execute(f"SELECT impuesto_predial({base_imponible}, {parametros})").fetchone()[0]

    def test_impuesto_predial_como_srtm(self):
        con = self._base()
        # the figures of ImpuestoPredial.liquidar with the 2026 UIT, the three alícuotas, the two límites and the
        # mínimo: 0 pays nothing, 10000 pays the mínimo, 100000 crosses into tramo 2, 400000 into tramo 3
        for base_imponible, esperado in (
            ("0", Decimal("0")),
            ("10000", Decimal("33.00")),
            ("100000", Decimal("270.00")),
            ("400000", Decimal("2350.00")),
            ("12345.675", Decimal("33.00")),
            # 20002.50 × 0.2 % = 40.005 exactly: half up gives 40.01 (half even, or a DOUBLE, 40.00)
            ("20002.50", Decimal("40.01")),
            ("NULL", Decimal("0")),
            ("-10", Decimal("0")),
        ):
            with self.subTest(base=base_imponible):
                self.assertEqual(self._impuesto(con, base_imponible), esperado)
        # like srtm, no figure when a parameter is missing
        self.assertIsNone(self._impuesto(con, "100000", "5500, 0.2, 0.6, 1.0, NULL, 60, 0.6"))
        tipo = con.execute(f"SELECT typeof(impuesto_predial(100000, {PARAMETROS}))").fetchone()[0]
        self.assertTrue(tipo.startswith("DECIMAL"), tipo)

    def test_parametros_predial(self):
        con = self._base()
        fila = con.execute("SELECT uit, a1, a2, a3, l1, l2, minimo FROM v_parametros_predial").fetchall()
        self.assertEqual(fila, [tuple(Decimal(v) for v in ("5500", "0.2", "0.6", "1.0", "15", "60", "0.6"))])

    def test_segmentos_por_confianza(self):
        con = self._base()
        filas = con.execute("SELECT confianza, categoria, predios, delta_impuesto FROM v_segmentos").fetchall()
        self.assertEqual(
            filas,
            [
                ("CONFIABLE", "SUBVALUADO", 1, Decimal("15.00")),
                ("DUDOSO", "SOBREVALUADO", 1, Decimal("-50.00")),
                ("SIN_DATO", "OMISO", 1, Decimal("120.00")),
            ],
        )
        columnas = [c[0] for c in con.execute("SELECT * FROM v_segmentos").description]
        self.assertEqual(
            columnas,
            [
                "confianza",
                "categoria",
                "predios",
                "delta_impuesto",
                "delta_autoavaluo",
                "impuesto_declarado",
                "impuesto_catastro",
            ],
        )

    def test_brecha_confiable_por_categoria(self):
        con = self._base()
        filas = con.execute("SELECT * FROM v_brecha_confiable_por_categoria").fetchall()
        self.assertEqual(filas, [("SUBVALUADO", 1, Decimal("15.00"), Decimal("5000"))])

    def test_clase_predominante(self):
        con = self._base()
        clases = dict(con.execute("SELECT codigo_municipal, clase FROM v_clase_predominante").fetchall())
        # 0101001: TIENDAS 70 m² over CASA 60; 0101002: a 50/50 tie goes to the first alphabetically
        self.assertEqual(clases, {"0101001": TIENDAS, "0101002": CASA, "0101003": "SIN CONSTRUCCION"})

    def test_brecha_predio_con_ficha_y_clase(self):
        con = self._base()
        filas = con.execute(
            "SELECT codigo_catastro, confianza, estado_levantamiento, clase FROM v_brecha_predio ORDER BY orden"
        ).fetchall()
        self.assertEqual(
            filas,
            [
                ("0101001", "CONFIABLE", "COMPLETO", TIENDAS),
                ("0101002", "DUDOSO", "PARCIAL", CASA),
                ("0101003", "SIN_DATO", "SIN_LEVANTAR", "SIN CONSTRUCCION"),
            ],
        )

    def test_brecha_por_clase(self):
        con = self._base()
        filas = con.execute("SELECT confianza, clase, predios, delta_impuesto FROM v_brecha_por_clase").fetchall()
        self.assertIn(("CONFIABLE", TIENDAS, 1, Decimal("15.00")), filas)
        self.assertEqual(sum(f[2] for f in filas), 3)

    def test_predial_por_contribuyente(self):
        con = self._base()
        filas = con.execute(
            "SELECT contribuyente, documento_hmac, declarado, motor_rentas, motor_catastro "
            "FROM v_predial_contribuyente ORDER BY contribuyente"
        ).fetchall()
        self.assertEqual(
            [(f[0], f[2], f[3], f[4]) for f in filas],
            [
                ("C1", Decimal("270.00"), Decimal("285.00"), Decimal("300.00")),
                ("C2", Decimal("2350.00"), Decimal("2350.00"), Decimal("2300.00")),
            ],
        )
        for fila in filas:
            self.assertRegex(fila[1], r"^[0-9a-f]{64}$")

    def test_predial_tres_columnas(self):
        con = self._base()
        filas = con.execute("SELECT * FROM v_predial_tres_columnas").fetchall()
        self.assertEqual(filas, [(2, Decimal("2620.00"), Decimal("2635.00"), Decimal("2600.00"))])

    def test_vistas_con_corte_vacio(self):
        s = minimos()
        s.srtm["brecha_corrida"] = [c for c in s.srtm["brecha_corrida"] if c["attributes"]["estado"] != "TERMINADA"]
        con = self._base(s, sin_titulares=True, sin_cambios=True)
        vistas = [
            fila[0]
            for fila in con.execute(
                "SELECT view_name FROM duckdb_views() WHERE NOT internal ORDER BY view_name"
            ).fetchall()
        ]
        self.assertEqual(len(vistas), 8)
        for vista in vistas:
            with self.subTest(vista=vista):
                con.execute(f"SELECT * FROM {vista}").fetchall()
        self.assertEqual(con.execute("SELECT count(*) FROM v_segmentos").fetchone()[0], 0)

    def test_corte_manifiesto(self):
        con = self._base()
        filas = con.execute(
            # leido_en compared as an instant: its text follows the session's time zone
            "SELECT organizacion, fecha_corte::VARCHAR, tipo, anio, leido_en = TIMESTAMPTZ '2026-10-25 11:00:00Z', "
            "version_motor, region "
            "FROM corte_manifiesto"
        ).fetchall()
        self.assertEqual(filas, [(ORG, "2026-10-25", "mensual", 2026, True, "valuacion-v3", "COSTA")])

    def test_vistas_se_pueden_volver_a_correr(self):
        con = self._base()
        con.execute(base.VISTAS.read_text(encoding="utf-8"))
        self.assertEqual(con.execute("SELECT count(*) FROM v_segmentos").fetchone()[0], 3)

    def test_armar_duckdb(self):
        carpeta = self._corte(minimos())
        destino = self.tmp / "ficticia.duckdb"
        codigo, salida, error = self._armar(carpeta, destino)
        self.assertEqual(codigo, 0, error)
        self.assertIn(str(destino), salida)
        self.assertTrue(destino.is_file())

        # a corte that no longer matches its manifest is refused with a clear message
        (carpeta / "catastro_lote.parquet").unlink()
        codigo, _, error = self._armar(carpeta, self.tmp / "otra.duckdb")
        self.assertEqual(codigo, 2)
        self.assertIn("catastro_lote.parquet", error)
        self.assertFalse((self.tmp / "otra.duckdb").exists())

    @staticmethod
    def _armar(carpeta, destino):
        salida, error = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(salida), contextlib.redirect_stderr(error):
            codigo = armar_duckdb.main(["--corte", str(carpeta), "--destino", str(destino)])
        return codigo, salida.getvalue(), error.getvalue()


if __name__ == "__main__":
    unittest.main()
