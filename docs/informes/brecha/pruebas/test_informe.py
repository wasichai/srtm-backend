"""The informe de brechas (informe.py, srtm-backend#301) over fictitious cortes: `datos(7)`/`datos(8)` and
`minimos()` taken by `corte.main` against the fakes (test_corte.ConCorte) and loaded with
`base.construir_duckdb`, as `make local` does. Checks the new views, the indicators and their reservation
(Ley 29733), the edición's life cycle (nueva, generar, validar), the comparison with a corte anterior, the
self-contained HTML and every failure the validation must catch."""

import contextlib
import io
import json
import re
import shutil
import subprocess
import unittest
from decimal import Decimal
from pathlib import Path

import duckdb

import informe
from herramientas import base, comun
from herramientas import informe_comun as ic
from herramientas import informe_html, informe_indicadores, informe_validar
from herramientas.sinteticos import datos, minimos
from pruebas.test_corte import ORG, ConCorte

NOMBRE = "Municipalidad Ficticia"


def _cli(argv):
    salida, error = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(salida), contextlib.redirect_stderr(error):
        codigo = informe.main(argv)
    return codigo, salida.getvalue(), error.getvalue()


class ConInforme(ConCorte):
    def _duckdb(self, s, fecha="2026-10-25"):
        carpeta = self._corte(s, fecha=fecha)
        con = base.construir_duckdb(carpeta, Path(f"{carpeta}.duckdb"))
        con.close()
        return carpeta

    def _edicion(self, corte, nombre="edicion"):
        carpeta = self.tmp / nombre
        codigo, _, error = _cli(["nueva", "--edicion", str(carpeta), "--org", ORG, "--corte", str(corte),
                                 "--nombre", NOMBRE])
        self.assertEqual(codigo, 0, error)
        return carpeta

    def _generar(self, edicion, *extra, esperado=0):
        codigo, salida, error = _cli(["generar", "--edicion", str(edicion), *extra])
        self.assertEqual(codigo, esperado, salida + error)
        return salida + error

    def _validar(self, edicion):
        return informe_validar.validar(edicion)


class VistasInformeTests(ConInforme, unittest.TestCase):
    def test_totales_y_confianza_cuadran_con_los_segmentos(self):
        carpeta = self._duckdb(datos(7))
        con = duckdb.connect(f"{carpeta}.duckdb", read_only=True)
        self.addCleanup(con.close)
        total = con.execute("SELECT predios, delta_impuesto, impuesto_declarado FROM v_brecha_totales").fetchone()
        segmentos = con.execute("SELECT sum(predios), sum(delta_impuesto), sum(impuesto_declarado)"
                                " FROM v_segmentos").fetchone()
        self.assertEqual(total, segmentos)
        self.assertEqual(total[0], 40)
        confianza = con.execute("SELECT sum(predios), sum(delta_impuesto) FROM v_brecha_por_confianza").fetchone()
        self.assertEqual(confianza, total[:2])
        corrida = con.execute("SELECT estado, total_predios FROM v_brecha_corrida").fetchall()
        self.assertEqual(corrida, [("TERMINADA", 40)])

    def test_niveles_por_estado(self):
        carpeta = self._duckdb(minimos())
        con = duckdb.connect(f"{carpeta}.duckdb", read_only=True)
        self.addCleanup(con.close)
        estados = dict(con.execute("SELECT estado, niveles FROM v_niveles_por_estado").fetchall())
        self.assertIn("PAR", estados)
        self.assertEqual(estados["SOLO_CATASTRO"], 1)
        self.assertEqual(estados["AMBIGUO"], 1)
        confianzas = {c for (c,) in con.execute("SELECT confianza FROM v_brecha_por_confianza").fetchall()}
        self.assertIn("SIN_DATO", confianzas)


class IndicadoresTests(ConInforme, unittest.TestCase):
    def test_reserva_los_grupos_pequenos(self):
        carpeta = self._duckdb(datos(7))
        ind = informe_indicadores.desde_archivo(ic.ruta_duckdb(carpeta), 5)
        self.assertEqual(ind["corte"]["organizacion"], ORG)
        self.assertEqual(ind["totales"]["predios"], 40)
        self.assertFalse(ind["totales"]["reservado"])
        celdas = ind["confianza"] + ind["categorias"] + ind["clases"] + ind["niveles"]
        for celda in celdas:
            if celda["predios"] < 5:
                self.assertTrue(celda["reservado"], celda)
            if celda["reservado"]:
                self.assertIsNone(celda.get("delta_impuesto"), celda)
        self.assertEqual(sum(c["predios"] for c in ind["confianza"]), 40)
        # with no threshold nothing is reserved, and the amounts add up to the total to the céntimo
        libre = informe_indicadores.desde_archivo(ic.ruta_duckdb(carpeta), 0)
        suma = sum(Decimal(c["delta_impuesto"]) for c in libre["categorias"])
        self.assertEqual(suma, Decimal(libre["totales"]["delta_impuesto"]))

    def test_reserva_complementaria(self):
        familia = [{"clave": "A", "predios": 2}, {"clave": "B", "predios": 9}, {"clave": "C", "predios": 30}]
        for c in familia:
            c["delta_impuesto"] = "1.00"
            informe_indicadores._reservar(c, ("delta_impuesto",), "predios", 5)
        informe_indicadores._complementar(familia, ("delta_impuesto",), "predios")
        # A alone would be the total minus B and C: the next smallest, B, is reserved too
        self.assertEqual([c["reservado"] for c in familia], [True, True, False])
        self.assertIsNone(familia[1]["delta_impuesto"])

    def test_sin_vistas_del_informe(self):
        ruta = self.tmp / "vacia.duckdb"
        duckdb.connect(str(ruta)).close()
        with self.assertRaises(informe_indicadores.ErrorIndicadores) as error:
            informe_indicadores.abrir(ruta)
        self.assertIn("make local", str(error.exception))
        with self.assertRaises(informe_indicadores.ErrorIndicadores):
            informe_indicadores.abrir(self.tmp / "no-existe.duckdb")


class EdicionTests(ConInforme, unittest.TestCase):
    def test_nueva_no_pisa_lo_que_existe(self):
        corte = self._duckdb(datos(7))
        edicion = self._edicion(corte)
        textos = sorted(p.name for p in (edicion / "textos").glob("*.adoc"))
        self.assertEqual(textos, sorted(p.name for p in (ic.PLANTILLA / "textos").glob("*.adoc")))
        datos_edicion = json.loads((edicion / "edicion.json").read_text(encoding="utf-8"))
        self.assertEqual((datos_edicion["organizacion"], datos_edicion["municipalidad"]), (ORG, NOMBRE))
        (edicion / "textos" / "01-resumen.adoc").write_text("== Mío\n\nTexto propio.\n", encoding="utf-8")
        self._edicion(corte)
        self.assertEqual((edicion / "textos" / "01-resumen.adoc").read_text(encoding="utf-8"),
                         "== Mío\n\nTexto propio.\n")

    def test_nueva_rechaza_otra_org_y_el_repositorio(self):
        corte = self._duckdb(datos(7))
        edicion = self._edicion(corte)
        codigo, _, error = _cli(["nueva", "--edicion", str(edicion), "--org", "otra-org", "--corte", str(corte),
                                 "--nombre", NOMBRE])
        self.assertEqual(codigo, 2)
        self.assertIn("ya es de", error)
        codigo, _, error = _cli(["nueva", "--edicion", str(comun.INFORME / "edicion"), "--org", ORG,
                                 "--corte", str(corte), "--nombre", NOMBRE])
        self.assertEqual(codigo, 2)
        self.assertIn("dentro del repositorio", error)
        self.assertFalse((comun.INFORME / "edicion").exists())
        # an organization outside parametros.json needs its name
        codigo, _, error = _cli(["nueva", "--edicion", str(self.tmp / "otra"), "--org", ORG, "--corte", str(corte)])
        self.assertEqual(codigo, 2)
        self.assertIn("NOMBRE", error)


class InformeTests(ConInforme, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.corte = self._duckdb(datos(7))
        self.edicion = self._edicion(self.corte)

    def test_genera_un_html_autocontenido_que_valida(self):
        salida = self._generar(self.edicion)
        self.assertIn("validar: ok", salida)
        html = (self.edicion / "salida" / ic.HTML).read_text(encoding="utf-8")
        for seccion in ("Resumen ejecutivo", "Punto de partida y método", "Hallazgos en los datos",
                        "Estado actual de los entornos", "Comparación con el corte anterior",
                        "Lecciones y consideraciones", "Pendientes y preguntas", "Glosario"):
            self.assertIn(f">{seccion}</h2>", html)
        self.assertEqual(html.count("<svg"), 4)
        self.assertIn("Esta edición no se comparó", html)
        self.assertNotRegex(html, r"<script|<link|<img|url\(|@import")
        self.assertEqual(re.findall(r"https?://[^\"]+", html), ["http://www.w3.org/2000/svg"] * 4)
        self.assertIn("prefers-color-scheme: dark", html)
        self.assertNotIn("{", re.sub(r"<style>.*?</style>", "", html, flags=re.S))
        # no código of the corte reaches the report
        for codigo in ("0101001", "0101007", "0101040", "predio-0"):
            self.assertNotIn(codigo, html)
        for nombre in ("confianza", "categorias", "predial", "niveles"):
            self.assertTrue((self.edicion / "generado" / "graficos" / f"{nombre}.svg").is_file())
        cifras = json.loads((self.edicion / "generado" / "cifras.json").read_text(encoding="utf-8"))
        self.assertEqual(cifras["predios"], "40")
        self.assertNotIn("comparacion", cifras)

    def test_comparacion_con_un_corte_anterior(self):
        anterior = self._duckdb(datos(8), fecha="2026-09-25")
        self._generar(self.edicion, "--corte-anterior", str(anterior))
        html = (self.edicion / "salida" / ic.HTML).read_text(encoding="utf-8")
        self.assertIn("Comparación con el corte del 25 de septiembre de 2026", html)
        self.assertNotIn("Esta edición no se comparó", html)
        self.assertEqual(html.count("<svg"), 5)
        ind = json.loads((self.edicion / "generado" / "indicadores.json").read_text(encoding="utf-8"))
        fila = next(f for f in ind["comparacion"]["filas"] if f["indicador"] == "Predios en la corrida")
        self.assertEqual((fila["anterior"], fila["actual"], fila["variacion"]), ("40", "40", "0"))
        cifras = json.loads((self.edicion / "generado" / "cifras.json").read_text(encoding="utf-8"))
        self.assertIn("var-delta-impuesto", cifras)
        # without the option again, the comparison and its chart are gone
        self._generar(self.edicion)
        self.assertFalse((self.edicion / "generado" / "graficos" / "comparacion.svg").exists())

    def test_corte_minimo_con_todo_reservado(self):
        corte = self._duckdb(minimos(), fecha="2026-10-24")
        edicion = self._edicion(corte, "minimos")
        self.assertIn("validar: ok", self._generar(edicion))
        html = (edicion / "salida" / ic.HTML).read_text(encoding="utf-8")
        self.assertIn("reservado", html)
        self.assertIn("Solo en catastro", html)

    def _falla(self, texto_extra, esperado, archivo="06-lecciones.adoc"):
        ruta = self.edicion / "textos" / archivo
        ruta.write_text(ruta.read_text(encoding="utf-8") + "\n" + texto_extra + "\n", encoding="utf-8")
        salida = self._generar(self.edicion, esperado=1)
        self.assertIn(esperado, salida)

    def test_falla_con_un_codigo_de_predio(self):
        self._falla("Revisar el predio de la ficha {anio}-0101007.", "identificador del corte «0101007»")

    def test_falla_con_un_documento(self):
        self._falla("Contacto: 45678912.", "DNI o RUC")

    def test_falla_con_un_correo(self):
        self._falla("Escribir a alguien@ejemplo.pe.", "un correo")

    def test_falla_con_una_cifra_tecleada(self):
        self._falla("La brecha es de S/ 12 y sube.", "cifra tecleada")

    def test_falla_con_un_atributo_sin_definir(self):
        self._falla("Faltan {predios-inventados}.", "atributo sin definir")

    def test_falla_con_el_monto_de_un_predio(self):
        con = duckdb.connect(f"{self.corte}.duckdb", read_only=True)
        monto = con.execute("SELECT max(autoavaluo_catastro) FROM srtm_brecha_predio").fetchone()[0]
        con.close()
        # written in the report's own style (a space for thousands, a comma for decimals): it is not typed
        # by hand here (the textos check would catch that first), it comes through a generated file
        self._generar(self.edicion)
        ruta = self.edicion / "generado" / "cifras.adoc"
        ruta.write_text(ruta.read_text(encoding="utf-8") + f":fuga: {ic.numero(monto, 2)}\n", encoding="utf-8")
        fallas = self._validar(self.edicion)
        self.assertTrue(any("monto de un predio individual" in f for f in fallas), fallas)

    def test_falla_con_un_recurso_externo(self):
        self._generar(self.edicion)
        html = self.edicion / "salida" / ic.HTML
        texto = html.read_text(encoding="utf-8")
        html.write_text(texto.replace("</body>", '<script src="https://cdn.example/x.js"></script></body>'),
                        encoding="utf-8")
        fallas = self._validar(self.edicion)
        self.assertTrue(any("carga algo de fuera" in f for f in fallas), fallas)

    def test_falla_con_un_grupo_pequeno_publicado(self):
        self._generar(self.edicion)
        ruta = self.edicion / "generado" / "indicadores.json"
        ind = json.loads(ruta.read_text(encoding="utf-8"))
        celda = next(c for c in ind["actual"]["categorias"] if c["reservado"])
        celda["reservado"] = False
        celda["delta_impuesto"] = "1.00"
        ruta.write_text(json.dumps(ind), encoding="utf-8")
        fallas = self._validar(self.edicion)
        self.assertTrue(any("no está reservado" in f for f in fallas), fallas)

    def test_falla_con_el_corte_de_otra_organizacion(self):
        ruta = self.edicion / "edicion.json"
        datos_edicion = json.loads(ruta.read_text(encoding="utf-8"))
        datos_edicion["organizacion"] = "otra-org"
        ruta.write_text(json.dumps(datos_edicion), encoding="utf-8")
        salida = self._generar(self.edicion, esperado=1)
        self.assertIn("el corte es de «ficticia»", salida)

    def test_tabla_desconocida(self):
        ruta = self.edicion / "textos" / "06-lecciones.adoc"
        ruta.write_text("== Lecciones\n\ntabla::no-existe[]\n", encoding="utf-8")
        salida = self._generar(self.edicion, esperado=2)
        self.assertIn("tabla desconocida", salida)


class MarcadoTests(unittest.TestCase):
    def _html(self, texto, atributos=None):
        doc = informe_html.Documento(atributos or {}, {"t": ("Título", "<table></table>")}, {})
        doc.renderizar(texto, "prueba.adoc")
        return "\n".join(doc.partes)

    def test_subconjunto_asciidoc(self):
        html = self._html(
            "// un comentario\n[[uno]]\n== Uno\n\n[.lead]\nHola *mundo* y _tú_, `x` {a}.\n\n* uno\n* dos\n"
            "sigue\n\n. paso\n\nTérmino:: definición\n\nNOTE: ojo\n\ntabla::t[]\n\nVer <<uno,arriba>>.\n"
            "////\noculto\n////\n",
            {"a": "S/ 5 <b>"},
        )
        self.assertIn('<h2 id="uno">Uno</h2>', html)
        self.assertIn('<p class="lead">Hola <strong>mundo</strong> y <em>tú</em>, <code>x</code> S/ 5 &lt;b&gt;.</p>',
                      html)
        self.assertIn("<ul><li>uno</li><li>dos sigue</li></ul>", html)
        self.assertIn("<ol><li>paso</li></ol>", html)
        self.assertIn("<dt>Término</dt><dd>definición</dd>", html)
        self.assertIn('class="admonicion note"', html)
        self.assertIn("Tabla 1. Título", html)
        self.assertIn('<a href="#uno">arriba</a>', html)
        self.assertNotIn("comentario", html)
        self.assertNotIn("oculto", html)

    def test_ifdef(self):
        texto = "ifdef::x[]\nsí\nendif::[]\nifndef::x[]\nno\nendif::[]\n"
        self.assertIn("sí", self._html(texto, {"x": "1"}))
        self.assertNotIn("no", self._html(texto, {"x": "1"}))
        self.assertIn("no", self._html(texto))
        with self.assertRaises(informe_html.ErrorTexto):
            self._html("ifdef::x[]\nabierto\n")


@unittest.skipIf(shutil.which("make") is None, "make no está instalado")
class MakefileInformeTests(unittest.TestCase):
    def _seco(self, *argumentos):
        resultado = subprocess.run(["make", "-n", *argumentos], cwd=comun.INFORME, capture_output=True, text=True,
                                   env={"PATH": "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin",
                                        "HOME": str(Path.home())})
        self.assertEqual(resultado.returncode, 0, resultado.stderr)
        return resultado.stdout

    def test_nueva_edicion_e_informe(self):
        casa = str(Path.home())
        texto = self._seco("nueva-edicion", "EDICION=~/informes/brecha/x/", "ORG=x", "CORTE=~/cortes/x/2026-11-01/")
        self.assertIn(f'informe.py nueva --edicion "{casa}/informes/brecha/x" --org "x" '
                      f'--corte "{casa}/cortes/x/2026-11-01"', texto)
        texto = self._seco("informe", "EDICION=~/e", "CORTE_ANTERIOR=~/cortes/x/2026-10-01")
        self.assertIn(f'informe.py generar --edicion "{casa}/e"', texto)
        self.assertIn(f'--corte-anterior "{casa}/cortes/x/2026-10-01"', texto)
        self.assertNotIn("--corte-anterior", self._seco("informe", "EDICION=/tmp/e"))
