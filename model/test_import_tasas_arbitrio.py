"""Tests for import_tasas_arbitrio.py: deriving import_parametros.py's CSV from the long table of an ordinance's
tasas de arbitrios.

Every tasa here is FICTITIOUS (data/arbitrios/ficticio-*.csv, año 2041), except the end-to-end test that cites
Miraflores 2026 (Ord. 665/MM), whitelisted with its source in global-constraints.md. Run: cd model && python3
-m unittest -v
"""
import csv
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

import import_arbitrios
import import_parametros
import import_tasas_arbitrio as it
from fake_core import FakeCore

HERE = os.path.dirname(os.path.abspath(__file__))
DATOS = os.path.join(HERE, "data", "arbitrios")
FICTICIO_TASAS = os.path.join(DATOS, "ficticio-tasas.csv")
FICTICIO_USOS = os.path.join(DATOS, "ficticio-usos.csv")
FICTICIO_INFLUENCIAS = os.path.join(DATOS, "ficticio-influencias.csv")
SERVICIOS = "BARRIDO_CALLES=BAR,RECOLECCION_RESIDUOS=RRS,PARQUES_JARDINES=PYJ,SERENAZGO=SER"


def comunes(**cambios):
    base = {"vigencia_desde": "2041-01-01", "norma": "Ordenanza ficticia 000-2041-MDP", "fuente": "test",
            "transcribio": "ANA, 2041-01-05", "verifico": "BETO, 2041-01-09", "redondeo": None}
    base.update(cambios)
    return base


class NormalizarYBaseTests(unittest.TestCase):
    def test_zona_y_nivel_se_normalizan_segun_la_ruling(self):
        self.assertEqual(it._normalizar("Zona 1"), "ZONA_1")
        self.assertEqual(it._normalizar("Área 0"), "AREA_0")
        self.assertEqual(it._normalizar("Muy alta"), "MUY_ALTA")

    def test_base_de_reconoce_los_tres_textos(self):
        self.assertEqual(it._base_de("S/ por ml de frontis"), "FRONTIS_ML")
        self.assertEqual(it._base_de("S/ por m2 de área construida"), "AREA_CONSTRUIDA_M2")
        self.assertEqual(it._base_de("S/ por predio"), "PREDIO")
        self.assertIsNone(it._base_de("S/ por otra cosa"))

    def test_casa_habitacion_es_insensible_a_acentos_y_mayusculas(self):
        self.assertTrue(it._casa_habitacion("Casa habitación y predios en construcción"))
        self.assertFalse(it._casa_habitacion("Otro uso ficticio"))


class LeerTests(unittest.TestCase):
    def test_leer_tasas_mantiene_las_columnas_vacias(self):
        filas = it.leer_tasas(FICTICIO_TASAS)
        self.assertEqual(len(filas), 14)
        self.assertEqual(filas[0], {"servicio": "BARRIDO_CALLES", "zona": "Zona 1", "uso_codigo": "", "uso": "",
                                     "ubicacion": "", "nivel_afluencia": "", "base": "S/ por ml de frontis",
                                     "tasa_mensual": "3.1000"})

    def test_leer_mapeo_usos_e_influencias(self):
        usos = it.leer_mapeo(FICTICIO_USOS, ("prefijo", "uso"))
        self.assertEqual(usos, [{"prefijo": "0100", "uso": "1"}, {"prefijo": "0101", "uso": "2"},
                                 {"prefijo": "0102", "uso": "9"}])
        influencias = it.leer_mapeo(FICTICIO_INFLUENCIAS, ("enum", "etiqueta"))
        self.assertEqual(influencias, [{"enum": "FRENTE A PARQUE", "etiqueta": "Frente a parque ficticio"}])


class GenerarTests(unittest.TestCase):
    def setUp(self):
        self.filas = it.leer_tasas(FICTICIO_TASAS)
        self.usos = it.leer_mapeo(FICTICIO_USOS, ("prefijo", "uso"))
        self.influencias = it.leer_mapeo(FICTICIO_INFLUENCIAS, ("enum", "etiqueta"))
        self.servicios = it._parse_pares(SERVICIOS)

    def generar(self, habitantes=(), **cambios):
        c = comunes(**cambios)
        redondeo = c.pop("redondeo")
        return it.generar(self.filas, self.servicios, self.usos, self.influencias, dict(habitantes), **c, redondeo=redondeo)

    def por_tipo(self, parametros, tipo):
        return [p for p in parametros if p["tipo"] == tipo]

    def test_cantidades_por_tipo(self):
        parametros, malas = self.generar(habitantes=[("RECOLECCION_RESIDUOS", ("4", "0.3"))], redondeo="2:MEDIO")
        self.assertEqual(malas, [])
        self.assertEqual(len(self.por_tipo(parametros, "TASA_ARBITRIO")), 14)
        self.assertEqual(len(self.por_tipo(parametros, "BASE_ARBITRIO")), 4)
        self.assertEqual(len(self.por_tipo(parametros, "DIMENSIONES_ARBITRIO")), 4)
        self.assertEqual(len(self.por_tipo(parametros, "ARBITRIO_USO")), 3)
        self.assertEqual(len(self.por_tipo(parametros, "ARBITRIO_INFLUENCIA")), 1)
        # solo RECOLECCION_RESIDUOS tiene --habitantes, y dos zonas de casa habitación
        self.assertEqual(len(self.por_tipo(parametros, "HABITANTES_PROMEDIO")), 2)
        self.assertEqual(len(self.por_tipo(parametros, "HABITANTES_VARIACION")), 2)
        self.assertEqual(len(self.por_tipo(parametros, "REDONDEO_ARBITRIO")), 1)
        self.assertEqual(import_parametros.errores(parametros), [])

    def test_las_claves_de_tasa_siguen_el_formato_de_cada_servicio(self):
        parametros, malas = self.generar()
        self.assertEqual(malas, [])
        claves = {p["clave"] for p in self.por_tipo(parametros, "TASA_ARBITRIO")}
        self.assertEqual(claves, {
            "BAR:ZONA_1", "BAR:ZONA_2",
            "RRS:ZONA_1:2", "RRS:ZONA_2:2", "RRS:ZONA_1:9", "RRS:ZONA_2:9",
            "PYJ:ZONA_A:Frente a parque ficticio:ALTA", "PYJ:ZONA_A:Frente a parque ficticio:MUY_ALTA",
            "PYJ:ZONA_B:Frente a parque ficticio:ALTA", "PYJ:ZONA_B:Frente a parque ficticio:MUY_ALTA",
            "SER:AREA_0:1", "SER:AREA_0:2", "SER:AREA_1:1", "SER:AREA_1:2",
        })

    def test_las_dimensiones_son_las_columnas_presentes_de_cada_servicio(self):
        parametros, _ = self.generar()
        dims = {p["clave"]: p["texto"] for p in self.por_tipo(parametros, "DIMENSIONES_ARBITRIO")}
        self.assertEqual(dims, {"BAR": "ZONA", "RRS": "ZONA,USO", "PYJ": "ZONA,INFLUENCIA,AFLUENCIA", "SER": "ZONA,USO"})

    def test_las_bases_son_las_del_servicio(self):
        parametros, _ = self.generar()
        bases = {p["clave"]: p["texto"] for p in self.por_tipo(parametros, "BASE_ARBITRIO")}
        self.assertEqual(bases, {"BAR": "FRONTIS_ML", "RRS": "AREA_CONSTRUIDA_M2", "PYJ": "PREDIO", "SER": "PREDIO"})

    def test_habitantes_solo_para_casa_habitacion_del_servicio_pedido(self):
        parametros, malas = self.generar(habitantes=[("RECOLECCION_RESIDUOS", ("4", "0.3"))])
        self.assertEqual(malas, [])
        promedio = {p["clave"]: p["valor_numerico"] for p in self.por_tipo(parametros, "HABITANTES_PROMEDIO")}
        self.assertEqual(promedio, {"RRS:ZONA_1:2": "4", "RRS:ZONA_2:2": "4"})
        variacion = {p["clave"]: p["valor_numerico"] for p in self.por_tipo(parametros, "HABITANTES_VARIACION")}
        self.assertEqual(variacion, {"RRS:ZONA_1:2": "0.3", "RRS:ZONA_2:2": "0.3"})
        # SERENAZGO también tiene una fila de casa habitación, pero no se pidió --habitantes para él
        self.assertFalse(any(c.startswith("SER:") for c in promedio))

    def test_sin_redondeo_no_hay_fila(self):
        parametros, malas = self.generar(redondeo=None)
        self.assertEqual(malas, [])
        self.assertEqual(self.por_tipo(parametros, "REDONDEO_ARBITRIO"), [])

    def test_servicio_fuera_de_mapeo_es_un_error(self):
        filas = self.filas + [dict(self.filas[0], servicio="OTRO_SERVICIO")]
        parametros, malas = it.generar(filas, self.servicios, self.usos, self.influencias, {}, **comunes())
        self.assertTrue(any("OTRO_SERVICIO" in e and "--servicios" in e for e in malas))

    def test_base_desconocida_es_un_error(self):
        filas = [dict(self.filas[0], base="S/ por litro")]
        _, malas = it.generar(filas, self.servicios, self.usos, self.influencias, {}, **comunes())
        self.assertTrue(any("base desconocida" in e for e in malas))

    def test_uso_sin_mapeo_en_usos_es_un_error(self):
        filas = [dict(f) for f in self.filas]
        filas[2] = dict(filas[2], uso_codigo="99")
        _, malas = it.generar(filas, self.servicios, self.usos, self.influencias, {}, **comunes())
        self.assertTrue(any("99" in e and "--usos" in e for e in malas))

    def test_ubicacion_sin_mapeo_en_influencias_es_un_error(self):
        filas = [dict(f) for f in self.filas]
        filas[6] = dict(filas[6], ubicacion="Otra ubicación cualquiera")
        _, malas = it.generar(filas, self.servicios, self.usos, self.influencias, {}, **comunes())
        self.assertTrue(any("Otra ubicación cualquiera" in e and "--influencias" in e for e in malas))

    def test_tasa_repetida_es_un_error_y_no_pierde_las_demas(self):
        filas = self.filas + [dict(self.filas[0])]  # BARRIDO_CALLES Zona 1 dos veces
        parametros, malas = it.generar(filas, self.servicios, self.usos, self.influencias, {}, **comunes())
        self.assertTrue(any("tasa repetida" in e and "BAR:ZONA_1" in e for e in malas))
        # el intento completo sigue disponible (15 filas -> 15 TASA_ARBITRIO), aunque no se escriba
        self.assertEqual(len([p for p in parametros if p["tipo"] == "TASA_ARBITRIO"]), 15)

    def test_el_csv_generado_pasa_import_parametros_errores(self):
        parametros, malas = self.generar(habitantes=[("RECOLECCION_RESIDUOS", ("4", "0.3"))], redondeo="2:ABAJO")
        self.assertEqual(malas, [])
        self.assertEqual(import_parametros.errores(parametros), [])


class MainTests(unittest.TestCase):
    def run_main(self, *extra):
        with tempfile.TemporaryDirectory() as d:
            salida = os.path.join(d, "parametros.csv")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = it.main(["--tasas", FICTICIO_TASAS, "--servicios", SERVICIOS, "--usos", FICTICIO_USOS,
                                 "--influencias", FICTICIO_INFLUENCIAS, "--vigencia-desde", "2041-01-01",
                                 "--norma", "Ordenanza ficticia 000-2041-MDP", "--fuente", "test",
                                 "--transcribio", "ANA, 2041-01-05", "--verifico", "BETO, 2041-01-09",
                                 "--habitantes", "RECOLECCION_RESIDUOS=4:0.3", "--redondeo", "2:ABAJO",
                                 "--salida", salida, *extra])
            contenido = None
            if os.path.exists(salida):
                with open(salida, encoding="utf-8") as f:
                    contenido = f.read()
            return code, out.getvalue(), err.getvalue(), contenido

    def test_main_escribe_el_csv_que_import_parametros_lee(self):
        code, out, err, contenido = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertIn("parámetros:", out)
        self.assertTrue(contenido.startswith("# Derivado de ficticio-tasas.csv"))
        with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8") as f:
            f.write(contenido)
        self.addCleanup(os.unlink, f.name)
        leidos = import_parametros.read_parametros(f.name)
        self.assertEqual(import_parametros.errores(leidos), [])
        self.assertEqual(len([p for p in leidos if p["tipo"] == "TASA_ARBITRIO"]), 14)

    def test_la_salida_es_idempotente(self):
        _, _, _, primera = self.run_main()
        _, _, _, segunda = self.run_main()
        self.assertEqual(primera, segunda)

    def test_un_servicio_sin_mapeo_sale_con_2_y_no_escribe(self):
        with tempfile.TemporaryDirectory() as d:
            tasas = os.path.join(d, "tasas.csv")
            with open(FICTICIO_TASAS, encoding="utf-8") as f:
                original = f.read()
            with open(tasas, "w", encoding="utf-8") as f:
                f.write(original.replace("BARRIDO_CALLES", "OTRO_SERVICIO"))
            salida = os.path.join(d, "parametros.csv")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = it.main(["--tasas", tasas, "--servicios", SERVICIOS, "--usos", FICTICIO_USOS,
                                 "--influencias", FICTICIO_INFLUENCIAS, "--vigencia-desde", "2041-01-01",
                                 "--norma", "x", "--fuente", "x", "--transcribio", "ANA, 2041-01-05",
                                 "--verifico", "BETO, 2041-01-09", "--salida", salida])
            self.assertEqual(code, 2)
            self.assertIn("OTRO_SERVICIO", err.getvalue())
            self.assertFalse(os.path.exists(salida))

    def test_servicios_malformado_sale_con_2(self):
        code, out, err, contenido = self.run_main()
        with tempfile.TemporaryDirectory() as d:
            salida = os.path.join(d, "parametros.csv")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = it.main(["--tasas", FICTICIO_TASAS, "--servicios", "BARRIDO_CALLES", "--usos", FICTICIO_USOS,
                                 "--vigencia-desde", "2041-01-01", "--norma", "x", "--fuente", "x",
                                 "--transcribio", "ANA, 2041-01-05", "--verifico", "BETO, 2041-01-09",
                                 "--salida", salida])
            self.assertEqual(code, 2)
            self.assertIn("NOMBRE=CODIGO", err.getvalue())


# el final de punta a punta (#86, Step 2): generar -> import_parametros.py --csv -> import_arbitrios.py, sobre una
# ordenanza y servicios ficticios, con las cifras de Miraflores 2026 (Ord. 665/MM) citadas en global-constraints.md
# (whitelisted: 0.0514, 5.0856, 85.9875, 61.0535, P=2, V=0.5, redondeo 2:ABAJO). Las mismas filas están en
# ArbitriosTest.kt («un predio por base»): no se duplica aquí el cálculo, solo que esto es lo que Kotlin lee.
MIRAFLORES_TASAS = """# Extracto de Miraflores 2026, Ord. 665/MM (cifras reales, citadas en global-constraints.md); zona y uso ficticios
servicio,zona,uso_codigo,uso,ubicacion,nivel_afluencia,base,tasa_mensual
BARRIDO,Zona 2,,,,,S/ por ml de frontis,5.0856
RECOLECCION,Zona 01A,2,Casa habitación,,,S/ por m2 de área construida,0.0514
PARQUES,Zona A,,,Frente a parque,Baja,S/ por predio,85.9875
SERENAZGO,Zona 1,1,Otro uso,,,S/ por predio,61.0535
"""


class PuntaAPuntaTests(unittest.TestCase):
    def setUp(self):
        self.core = FakeCore()
        self.addCleanup(self.core.stop)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = tmp.name
        self.tasas = os.path.join(self.dir, "miraflores-tasas.csv")
        with open(self.tasas, "w", encoding="utf-8") as f:
            f.write(MIRAFLORES_TASAS)
        self.usos = os.path.join(self.dir, "usos.csv")
        with open(self.usos, "w", encoding="utf-8") as f:
            f.write("prefijo,uso\n0101,2\n0102,1\n")
        self.influencias = os.path.join(self.dir, "influencias.csv")
        with open(self.influencias, "w", encoding="utf-8") as f:
            f.write("enum,etiqueta\nFRENTE A PARQUE,Frente a parque\n")
        self.parametros_csv = os.path.join(self.dir, "parametros.csv")

    def generar_parametros(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = it.main(["--tasas", self.tasas, "--servicios",
                             "BARRIDO=BARRIDO,RECOLECCION=RECOLECCION,PARQUES=PARQUES,SERENAZGO=SERENAZGO",
                             "--usos", self.usos, "--influencias", self.influencias,
                             "--vigencia-desde", "2026-01-01", "--norma", "Ordenanza N.° 665/MM",
                             "--fuente", "El Peruano, 20/12/2025", "--transcribio", "ANA, 2026-01-05",
                             "--verifico", "BETO, 2026-01-09", "--redondeo", "2:ABAJO",
                             "--habitantes", "RECOLECCION=2:0.5", "--salida", self.parametros_csv])
        return code, err.getvalue()

    def test_generar_y_cargar_con_import_parametros_y_import_arbitrios(self):
        code, err = self.generar_parametros()
        self.assertEqual(code, 0, err)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = import_parametros.main(["--core", self.core.base_url, "--csv", self.parametros_csv])
        self.assertEqual(code, 0, err.getvalue())
        tasas = {r["attributes"]["clave"]: r["attributes"]["valor_numerico"]
                 for r in self.core.records["parametro_tributario"] if r["attributes"]["tipo"] == "TASA_ARBITRIO"}
        self.assertEqual(tasas, {
            "BARRIDO:ZONA_2": "5.0856", "RECOLECCION:ZONA_01A:2": "0.0514",
            "PARQUES:ZONA_A:Frente a parque:BAJA": "85.9875", "SERENAZGO:ZONA_1:1": "61.0535",
        })

        archivo = {
            "ordenanzas": [{"anio": 2026, "numero": "665/MM"}],
            "servicios": [
                {"codigo": c, "nombre": c, "orden": i, "vigencia_desde": "2026-01-01", "ordenanza": 2026}
                for i, c in enumerate(("BARRIDO", "RECOLECCION", "PARQUES", "SERENAZGO"), start=1)
            ],
            "inafectaciones": [],
        }
        archivo_path = os.path.join(self.dir, "arbitrios.json")
        with open(archivo_path, "w", encoding="utf-8") as f:
            json.dump(archivo, f)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = import_arbitrios.main(["--core", self.core.base_url, "--archivo", archivo_path])
        self.assertEqual(code, 0, err.getvalue())
        self.assertEqual({r["attributes"]["codigo"] for r in self.core.records["servicio_arbitrio"]},
                          {"BARRIDO", "RECOLECCION", "PARQUES", "SERENAZGO"})


# el CSV real de Miraflores (#86): no se versiona (se carga más adelante); se salta si no está la variable
MIRAFLORES_CSV = os.environ.get("SRTM_MIRAFLORES_CSV")


@unittest.skipUnless(MIRAFLORES_CSV, "SRTM_MIRAFLORES_CSV no está configurado")
class MirafloresRealTests(unittest.TestCase):
    """Cuenta las TASA_ARBITRIO que generar() construye del CSV real de Miraflores 2026, una por fila (sin
    importar si el resultado pasa las demás validaciones: eso se deja para la carga real de #86)."""

    def test_246_tasas_3_68_64_111(self):
        filas = it.leer_tasas(MIRAFLORES_CSV)
        servicios = it._parse_pares("BARRIDO_CALLES=BAR,RECOLECCION_RESIDUOS=RRS,PARQUES_JARDINES=PYJ,SERENAZGO=SER")
        # mapeos sintéticos que cubren cada uso_codigo y cada ubicación distintos del archivo real, solo para que
        # generar() no se detenga antes de construir las TASA_ARBITRIO; no son del padrón real
        usos_distintos = sorted({f["uso_codigo"] for f in filas if f["uso_codigo"]})
        usos = [{"prefijo": f"{i:06d}", "uso": u} for i, u in enumerate(usos_distintos, start=1)]
        ubicaciones_distintas = sorted({f["ubicacion"] for f in filas if f["ubicacion"]})
        enums = ["FRENTE A PARQUE", "MEDIANAMENTE CERCANO A AREAS VERDES", "FRENTE A BERMA", "OTRAS UBICACIONES"]
        influencias = [{"enum": enums[i % len(enums)], "etiqueta": u} for i, u in enumerate(ubicaciones_distintas)]
        parametros, malas = it.generar(filas, servicios, usos, influencias, {}, vigencia_desde="2026-01-01",
                                        norma="x", fuente="x", transcribio="ANA, 2026-01-01",
                                        verifico="BETO, 2026-01-01")
        tasas = [p for p in parametros if p["tipo"] == "TASA_ARBITRIO"]
        self.assertEqual(len(tasas), 246)
        por_servicio = {}
        for p in tasas:
            codigo = p["clave"].split(":")[0]
            por_servicio[codigo] = por_servicio.get(codigo, 0) + 1
        self.assertEqual(por_servicio, {"BAR": 3, "RRS": 68, "PYJ": 64, "SER": 111})
        print(f"Miraflores real: 246 TASA_ARBITRIO generadas (3/68/64/111); malas en el resto del pipeline: "
              f"{len(malas)}")


if __name__ == "__main__":
    unittest.main()
