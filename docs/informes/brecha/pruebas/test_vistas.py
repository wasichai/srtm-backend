"""The DuckDB of a corte: `base.construir_duckdb` over a corte of `minimos()` taken by `corte.main` against the
fakes (test_corte.ConCorte), and the views of vistas.sql against the brecha report v3 figures the fixture was
built to give. Plus `armar_duckdb.main`'s exit codes."""

import contextlib
import io
import unittest
from decimal import Decimal

import armar_duckdb
from herramientas import base
from herramientas.sinteticos import C1, C3, D2, F3, P2, minimos
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

    @staticmethod
    def _p2_compartido(*valores_afecto):
        """minimos() with P2 declared by three vigentes DJ: D2 (C2), D5 (C3) and D6 (C1), none with origin
        values, carrying `valores_afecto` in that order; BP2 puts 2300.00 of impuesto_catastro on P2."""
        s = minimos()
        declaraciones = s.srtm["declaracion_predial"]
        d2 = next(d for d in declaraciones if d["id"] == D2)
        d2["attributes"]["valor_afecto"] = valores_afecto[0]
        nuevas = (("D5", C3, "VIGENTE", valores_afecto[1]), ("D6", C1, "", valores_afecto[2]))
        for id_, contribuyente, estado, valor in nuevas:
            atributos = {"predio": P2, "contribuyente": contribuyente, "anio": 2026, "estado": estado}
            declaraciones.append({"id": id_, "attributes": {**atributos, "valor_afecto": valor}})
        return s

    def _motor_catastro(self, con):
        return dict(con.execute("SELECT contribuyente, motor_catastro FROM v_predial_contribuyente").fetchall())

    def test_motor_catastro_reparte_por_valor_afecto(self):
        con = self._base(self._p2_compartido(400000, 100000, 100000))
        # 2300 in 4:1:1 is 1533.33 + 383.33 + 383.33 = 2299.99: D6, the greatest id, takes the céntimo left over
        self.assertEqual(
            self._motor_catastro(con),
            {"C1": Decimal("300.00") + Decimal("383.34"), "C2": Decimal("1533.33"), "C3": Decimal("383.33")},
        )
        total = con.execute("SELECT motor_catastro FROM v_predial_tres_columnas").fetchone()[0]
        self.assertEqual(total, Decimal("2600.00"))

    def test_motor_catastro_en_partes_iguales_sin_valor_afecto(self):
        con = self._base(self._p2_compartido(None, 0, None))
        # no weight at all: 2300 / 3 is 766.67 twice, and D6 takes 766.66 so the parts add up to 2300.00
        self.assertEqual(
            self._motor_catastro(con),
            {"C1": Decimal("300.00") + Decimal("766.66"), "C2": Decimal("766.67"), "C3": Decimal("766.67")},
        )
        total = con.execute("SELECT motor_catastro FROM v_predial_tres_columnas").fetchone()[0]
        self.assertEqual(total, Decimal("2600.00"))

    def test_clase_sin_clasificacion(self):
        s = minimos()
        # F3 gains constructions, none of them classified (one NULL, one blank)
        for id_, clasificacion in (("CC7", None), ("CC8", "  ")):
            atributos = {"predio": F3, "codigo_municipal": "0101003", "piso": 1, "seccion": id_, "area_construida": 20}
            s.catastro["construccion"].append({"id": id_, "attributes": {**atributos, "clasificacion": clasificacion}})
        con = self._base(s)
        clase = con.execute("SELECT clase FROM v_clase_predominante WHERE codigo_municipal = '0101003'").fetchone()
        self.assertEqual(clase, ("SIN CLASIFICACION",))

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
