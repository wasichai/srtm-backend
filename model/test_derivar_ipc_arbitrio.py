"""Tests for derivar_ipc_arbitrio.py: deriving next year's arbitrios parameters CSV by reajuste del IPC (#88).

Every figure here is FICTITIOUS (data/arbitrios/ficticio-parametros.csv, año 2041), except the AC's own 2.5428 ->
2.5962 example (IPC 2.10 %, 4:MEDIO). Run: cd model && python3 -m unittest -v

An optional end-to-end test runs against a real file when $SRTM_MIRAFLORES_CSV is set (skipped otherwise): it
loads that long table with import_tasas_arbitrio.py and then derives its next year with IPC 2.10 % and 4:MEDIO.
"""
import io
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import derivar_ipc_arbitrio as di
import import_parametros

HERE = os.path.dirname(os.path.abspath(__file__))
FICTICIO_PARAMETROS = os.path.join(HERE, "data", "arbitrios", "ficticio-parametros.csv")


def comunes(**cambios):
    decimales, modo = di.parse_redondeo("4:MEDIO")
    base = {"anio": 2041, "ipc": "2.10", "decimales": decimales, "modo": modo, "norma": "OM 012-2042-MDP",
            "transcribio": "CARLA, 2042-01-05", "verifico": "DIEGO, 2042-01-09"}
    base.update(cambios)
    return base


def fila(tipo, clave, vigencia_desde="2041-01-01", valor_numerico=None, texto=None):
    f = {"tipo": tipo, "clave": clave, "vigencia_desde": vigencia_desde}
    if valor_numerico is not None:
        f["valor_numerico"] = valor_numerico
    if texto is not None:
        f["texto"] = texto
    return f


class ParseRedondeoTests(unittest.TestCase):
    def test_reconoce_los_tres_modos(self):
        self.assertEqual(di.parse_redondeo("4:MEDIO"), (4, di.MODOS["MEDIO"]))
        self.assertEqual(di.parse_redondeo("2:ABAJO"), (2, di.MODOS["ABAJO"]))
        self.assertEqual(di.parse_redondeo("0:ARRIBA"), (0, di.MODOS["ARRIBA"]))

    def test_malformado_es_un_error(self):
        for malo in ("4", "4:MEDIANO", "MEDIO:4", "", None):
            with self.subTest(malo=malo):
                with self.assertRaises(ValueError):
                    di.parse_redondeo(malo)


class MasUnAnioTests(unittest.TestCase):
    def test_una_fecha_cualquiera_solo_cambia_el_anio(self):
        self.assertEqual(di.mas_un_anio("2026-02-28"), "2027-02-28")
        self.assertEqual(di.mas_un_anio("2026-05-30"), "2027-05-30")

    def test_29_de_febrero_cae_a_28_si_el_siguiente_no_es_bisiesto(self):
        self.assertEqual(di.mas_un_anio("2024-02-29"), "2025-02-28")


class ReajusteDelAcTests(unittest.TestCase):
    """El ejemplo citado del AC de #88: 2.5428 x 1.021 = 2.5961988 -> 2.5962 con 4:MEDIO."""

    def test_2_5428_con_ipc_2_10_y_4_medio_da_2_5962(self):
        filas = [fila("TASA_ARBITRIO", "RRS:ZONA_1:2", valor_numerico="2.5428")]
        parametros = di.generar(filas, **comunes())
        self.assertEqual(len(parametros), 1)
        self.assertEqual(parametros[0]["valor_numerico"], "2.5962")
        self.assertEqual(parametros[0]["tipo"], "TASA_ARBITRIO")
        self.assertEqual(parametros[0]["clave"], "RRS:ZONA_1:2")


class GenerarTests(unittest.TestCase):
    def test_una_tasa_cita_la_clave_y_el_anio_base_en_texto_y_fuente(self):
        filas = [fila("TASA_ARBITRIO", "BAR:ZONA_1", vigencia_desde="2026-01-01", valor_numerico="3.1000")]
        parametros = di.generar(filas, **comunes(anio=2026))
        self.assertEqual(parametros[0]["texto"], "DERIVADA IPC 2.10% de BAR:ZONA_1 2026")
        self.assertEqual(parametros[0]["fuente"], "DERIVADA IPC 2.10% de BAR:ZONA_1 2026")

    def test_toda_fila_toma_norma_transcribio_y_verifico_del_reajuste(self):
        filas = [fila("TASA_ARBITRIO", "BAR:ZONA_1", valor_numerico="3.1000"),
                 fila("BASE_ARBITRIO", "BAR", texto="FRONTIS_ML")]
        parametros = di.generar(filas, **comunes())
        for p in parametros:
            self.assertEqual(p["norma"], "OM 012-2042-MDP")
            self.assertEqual(p["transcribio"], "CARLA, 2042-01-05")
            self.assertEqual(p["verifico"], "DIEGO, 2042-01-09")

    def test_vigencia_desde_es_el_1_de_enero_del_anio_siguiente(self):
        filas = [fila("TASA_ARBITRIO", "BAR:ZONA_1", vigencia_desde="2041-06-01", valor_numerico="3.1000")]
        parametros = di.generar(filas, **comunes(anio=2041))
        self.assertEqual(parametros[0]["vigencia_desde"], "2042-01-01")

    def test_una_fila_que_no_es_tasa_se_copia_con_su_propio_texto_y_valor(self):
        filas = [fila("BASE_ARBITRIO", "BAR", texto="FRONTIS_ML"),
                 fila("DIMENSIONES_ARBITRIO", "BAR", texto="ZONA"),
                 fila("ARBITRIO_USO", "0100", texto="1"),
                 fila("ARBITRIO_INFLUENCIA", "FRENTE A PARQUE", texto="Frente a parque"),
                 fila("HABITANTES_PROMEDIO", "RRS:ZONA_1:2", valor_numerico="4"),
                 fila("HABITANTES_VARIACION", "RRS:ZONA_1:2", valor_numerico="0.3")]
        parametros = di.generar(filas, **comunes())
        por_clave = {(p["tipo"], p["clave"]): p for p in parametros}
        self.assertEqual(por_clave[("BASE_ARBITRIO", "BAR")]["texto"], "FRONTIS_ML")
        self.assertEqual(por_clave[("DIMENSIONES_ARBITRIO", "BAR")]["texto"], "ZONA")
        self.assertEqual(por_clave[("ARBITRIO_USO", "0100")]["texto"], "1")
        self.assertEqual(por_clave[("ARBITRIO_INFLUENCIA", "FRENTE A PARQUE")]["texto"], "Frente a parque")
        self.assertEqual(por_clave[("HABITANTES_PROMEDIO", "RRS:ZONA_1:2")]["valor_numerico"], "4")
        self.assertEqual(por_clave[("HABITANTES_VARIACION", "RRS:ZONA_1:2")]["valor_numerico"], "0.3")

    def test_redondeo_arbitrio_se_copia_tal_cual_sin_usar_el_redondeo_del_reajuste(self):
        filas = [fila("REDONDEO_ARBITRIO", "", texto="2:ABAJO")]
        parametros = di.generar(filas, **comunes())  # --redondeo del reajuste es 4:MEDIO
        self.assertEqual(parametros[0]["texto"], "2:ABAJO")

    def test_vencimiento_se_corre_un_anio(self):
        filas = [fila("ARBITRIO_VENCIMIENTO", "1", vigencia_desde="2026-01-01", texto="2026-02-28")]
        parametros = di.generar(filas, **comunes(anio=2026))
        self.assertEqual(parametros[0]["texto"], "2027-02-28")

    def test_tope_arbitrio_con_fecha_se_corre_un_anio(self):
        filas = [fila("TOPE_ARBITRIO", "0101", vigencia_desde="2025-01-01", valor_numerico="8", texto="2025-12-31")]
        parametros = di.generar(filas, **comunes(anio=2025))
        self.assertEqual(parametros[0]["texto"], "2026-12-31")
        self.assertEqual(parametros[0]["valor_numerico"], "8")

    def test_tope_arbitrio_sin_fecha_queda_vacio(self):
        filas = [fila("TOPE_ARBITRIO", "*", valor_numerico="18")]
        parametros = di.generar(filas, **comunes())
        self.assertNotIn("texto", parametros[0])
        self.assertEqual(parametros[0]["valor_numerico"], "18")

    def test_una_fila_fuera_de_tipos_arbitrio_se_ignora(self):
        filas = [fila("TASA_ARBITRIO", "BAR:ZONA_1", valor_numerico="3.1000"),
                  fila("UIT", "2041", valor_numerico="5150")]
        parametros = di.generar(filas, **comunes())
        self.assertEqual(len(parametros), 1)
        self.assertEqual(parametros[0]["tipo"], "TASA_ARBITRIO")


class VigentesAl31DicTests(unittest.TestCase):
    def test_elige_la_de_vigencia_desde_mas_reciente_sin_pasarse_del_corte(self):
        filas = [fila("TASA_ARBITRIO", "BAR:ZONA_1", vigencia_desde="2041-01-01", valor_numerico="10"),
                 fila("TASA_ARBITRIO", "BAR:ZONA_1", vigencia_desde="2041-07-01", valor_numerico="12"),
                 fila("TASA_ARBITRIO", "BAR:ZONA_1", vigencia_desde="2042-01-05", valor_numerico="99")]
        vigentes = di.vigentes_al_31_12(filas, 2041)
        self.assertEqual(len(vigentes), 1)
        self.assertEqual(vigentes[0]["valor_numerico"], "12")

    def test_una_fila_que_empieza_despues_del_corte_se_ignora_del_todo(self):
        filas = [fila("TASA_ARBITRIO", "SOLO_FUTURA", vigencia_desde="2042-01-01", valor_numerico="5")]
        self.assertEqual(di.vigentes_al_31_12(filas, 2041), [])


class MainTests(unittest.TestCase):
    def run_main(self, csv_path=FICTICIO_PARAMETROS, extra=(), redondeo="4:MEDIO", ipc="2.10"):
        with tempfile.TemporaryDirectory() as d:
            salida = os.path.join(d, "parametros-2042.csv")
            args = ["--csv", csv_path, "--desde", "2041", "--ipc", ipc, "--norma", "OM 012-2042-MDP",
                    "--transcribio", "CARLA, 2042-01-05", "--verifico", "DIEGO, 2042-01-09", "--salida", salida]
            if redondeo is not None:
                args += ["--redondeo", redondeo]
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = di.main(args + list(extra))
            contenido = None
            if os.path.exists(salida):
                with open(salida, encoding="utf-8") as f:
                    contenido = f.read()
            return code, out.getvalue(), err.getvalue(), contenido

    def test_sin_redondeo_sale_con_2_y_no_escribe(self):
        code, _, err, contenido = self.run_main(redondeo=None)
        self.assertEqual(code, 2)
        self.assertIn("--redondeo", err)
        self.assertIsNone(contenido)

    def test_redondeo_malformado_sale_con_2_y_no_escribe(self):
        code, _, err, contenido = self.run_main(redondeo="4:MALO")
        self.assertEqual(code, 2)
        self.assertIn("decimales:modo", err)
        self.assertIsNone(contenido)

    def test_ipc_no_numerico_sale_con_2_y_no_escribe(self):
        code, _, err, contenido = self.run_main(ipc="abc")
        self.assertEqual(code, 2)
        self.assertIn("--ipc", err)
        self.assertIsNone(contenido)

    def test_corrida_valida_escribe_un_csv_que_pasa_import_parametros_errores(self):
        code, out, err, contenido = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertIn("parámetros:", out)
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write(contenido)
        self.addCleanup(os.unlink, f.name)
        leidos = import_parametros.read_parametros(f.name)
        self.assertEqual(import_parametros.errores(leidos), [])
        self.assertEqual(len([p for p in leidos if p["tipo"] == "TASA_ARBITRIO"]), 15)
        self.assertTrue(all(p["vigencia_desde"] == "2042-01-01" for p in leidos))

    def test_la_tasa_derivada_del_fixture_coincide_con_el_reajuste_esperado(self):
        # BAR:ZONA_1 = 3.1000 en el fixture; 3.1000 x 1.021 = 3.1651, exacta a 4 decimales
        _, _, _, contenido = self.run_main()
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write(contenido)
        self.addCleanup(os.unlink, f.name)
        leidos = import_parametros.read_parametros(f.name)
        tasa = next(p for p in leidos if p["tipo"] == "TASA_ARBITRIO" and p["clave"] == "BAR:ZONA_1")
        self.assertEqual(tasa["valor_numerico"], "3.1651")

    def test_el_vencimiento_se_corre_un_anio_en_la_corrida_completa(self):
        _, _, _, contenido = self.run_main()
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write(contenido)
        self.addCleanup(os.unlink, f.name)
        leidos = import_parametros.read_parametros(f.name)
        vencimiento_1 = next(p for p in leidos if p["tipo"] == "ARBITRIO_VENCIMIENTO" and p["clave"] == "1")
        self.assertEqual(vencimiento_1["texto"], "2042-02-28")  # 2041-02-28 + 1 año

    def test_redondeo_arbitrio_mantiene_su_propio_modo_en_la_corrida_completa(self):
        _, _, _, contenido = self.run_main()  # --redondeo 4:MEDIO del reajuste; el fixture trae 2:ABAJO
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write(contenido)
        self.addCleanup(os.unlink, f.name)
        leidos = import_parametros.read_parametros(f.name)
        redondeo = next(p for p in leidos if p["tipo"] == "REDONDEO_ARBITRIO")
        self.assertEqual(redondeo["texto"], "2:ABAJO")


@unittest.skipUnless(os.environ.get("SRTM_MIRAFLORES_CSV"), "SRTM_MIRAFLORES_CSV no está definida")
class MirafloresTests(unittest.TestCase):
    """Corrida opcional, de punta a punta, con la tabla real de Miraflores 2026 (no ficticia): la carga con
    import_tasas_arbitrio.py y deriva su año siguiente con IPC 2.10 % y 4:MEDIO."""

    def test_miraflores_2026_con_ipc_2_10_y_4_medio(self):
        import csv

        import import_tasas_arbitrio as it

        tasas = os.environ["SRTM_MIRAFLORES_CSV"]
        with open(tasas, encoding="utf-8") as f:
            filas = list(csv.DictReader(l for l in f if not l.startswith("#")))
        with tempfile.TemporaryDirectory() as d:
            parametros_2026 = os.path.join(d, "parametros-2026.csv")
            # --usos y --influencias cubren TODO lo que trae la tabla real (una fila por valor distinto): no son
            # los códigos reales de uso_predio ni de ubicacion_area_verde, solo lo que import_tasas_arbitrio.py
            # necesita para no rechazar ninguna fila por «sin mapeo»
            usos = os.path.join(d, "usos.csv")
            with open(usos, "w", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                w.writerow(["prefijo", "uso"])
                for i, u in enumerate(sorted({r["uso_codigo"] for r in filas if r.get("uso_codigo")}), start=1):
                    w.writerow([f"{i:02d}", u])
            influencias = os.path.join(d, "influencias.csv")
            etiquetas_a_enum = {
                "Frente a Parque y/o Área Verde": "FRENTE A PARQUE",
                "Cerca de Parque y/o Área Verde": "MEDIANAMENTE CERCANO A AREAS VERDES",
                "Frente a avenida con berma arbolada y/o berma con Área verde": "FRENTE A BERMA",
                "Ubicado en otras zonas": "OTRAS UBICACIONES",
            }
            with open(influencias, "w", encoding="utf-8", newline="") as f:
                w = csv.writer(f)
                w.writerow(["enum", "etiqueta"])
                for etiqueta in sorted({r["ubicacion"] for r in filas if r.get("ubicacion")}):
                    w.writerow([etiquetas_a_enum[etiqueta], etiqueta])
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = it.main(["--tasas", tasas, "--servicios", "BARRIDO_CALLES=BAR,RECOLECCION_RESIDUOS=RRS,"
                                 "PARQUES_JARDINES=PYJ,SERENAZGO=SER", "--usos", usos, "--influencias", influencias,
                                 "--vigencia-desde", "2026-01-01", "--norma", "Ordenanza N.° 665/MM",
                                 "--fuente", "Miraflores 2026", "--transcribio", "ANA, 2026-01-05",
                                 "--verifico", "BETO, 2026-01-09", "--redondeo", "2:ABAJO",
                                 "--salida", parametros_2026])
            self.assertEqual(code, 0, err.getvalue())

            salida_2027 = os.path.join(d, "parametros-2027.csv")
            out2, err2 = io.StringIO(), io.StringIO()
            with redirect_stdout(out2), redirect_stderr(err2):
                code2 = di.main(["--csv", parametros_2026, "--desde", "2026", "--ipc", "2.10",
                                  "--norma", "OM 012-2027-MDP", "--redondeo", "4:MEDIO",
                                  "--transcribio", "CARLA, 2027-01-05", "--verifico", "DIEGO, 2027-01-09",
                                  "--salida", salida_2027])
            self.assertEqual(code2, 0, err2.getvalue())
            leidos = import_parametros.read_parametros(salida_2027)
            self.assertEqual(import_parametros.errores(leidos), [])
            fila_2_5428 = [p for p in leidos if p["tipo"] == "TASA_ARBITRIO" and p["valor_numerico"] == "2.5962"]
            self.assertTrue(fila_2_5428, "no se encontró ninguna tasa derivada en 2.5962 (2.5428 x 1.021)")
