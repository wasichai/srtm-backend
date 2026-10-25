"""#206 task 10: `herramientas.metabase` against `pruebas.metabase_falso.MetabaseFalso` - one database, group and
collection per organization, the two permission graphs that isolate them (plan § Rulings, aislamiento), the
tableros of metabase/tableros.json, idempotency by name, the first-run setup and `provisionar.py`'s CLI."""

import contextlib
import copy
import io
import json
import os
import re
import shutil
import subprocess
import unittest
from unittest import mock

import provisionar
from herramientas import base, comun, metabase
from pruebas.metabase_falso import ADMINISTRADORES, TODOS, MetabaseFalso

ADMIN = ("admin@ejemplo.pe", "clave-admin-1")
ORGS = {"ficticia-a": "Ficticia A", "ficticia-b": "Ficticia B"}


def _tableros():
    return json.loads((comun.INFORME / "metabase" / "tableros.json").read_text(encoding="utf-8"))


class ConMetabase:
    """A fresh MetabaseFalso with one administrator, and a Metabase client logged in as that administrator."""

    def setUp(self):
        self.falso = MetabaseFalso({ADMIN[0]: ADMIN[1]})
        self.addCleanup(self.falso.cerrar)
        self.mb = metabase.Metabase(self.falso.url)
        self.mb.sesion(*ADMIN)

    def _provisionar(self, tableros=None):
        return metabase.provisionar(self.mb, ORGS, tableros or _tableros())

    def _por_nombre(self, coleccion, nombre):
        return next(x for x in coleccion.values() if x["name"] == nombre)

    def _escrituras(self, desde=0):
        return [(m, ruta) for m, ruta, _ in self.falso.peticiones[desde:] if m in ("POST", "PUT")]


class ProvisionarTests(ConMetabase, unittest.TestCase):
    def test_una_base_por_organizacion(self):
        self._provisionar()
        self.assertEqual(len(self.falso.bases), 2)
        for org, nombre in ORGS.items():
            b = self._por_nombre(self.falso.bases, f"Brecha {nombre}")
            self.assertEqual(b["engine"], "duckdb")
            self.assertEqual(b["details"]["database_file"], f"/datos/{org}.duckdb")
            self.assertIs(b["details"]["read_only"], True)
            self.assertIs(b["details"]["old_implicit_casting"], True)
            self.assertEqual(b["details"]["init_sql"], base.INIT_SQL)

    def test_cada_grupo_solo_consulta_su_base(self):
        r = self._provisionar()
        a, b = r["ficticia-a"], r["ficticia-b"]
        grafo = self.falso.grafo_datos
        self.assertEqual(grafo[a["grupo"]][a["base"]], {"view-data": "unrestricted", "create-queries": "query-builder"})
        self.assertEqual(grafo[a["grupo"]][b["base"]]["create-queries"], "no")
        self.assertEqual(grafo[b["grupo"]][b["base"]]["create-queries"], "query-builder")
        self.assertEqual(grafo[b["grupo"]][a["base"]]["create-queries"], "no")
        # Metabase gives All Users native SQL on a new database: provisioning takes it away on both
        self.assertEqual(grafo[TODOS][a["base"]]["create-queries"], "no")
        self.assertEqual(grafo[TODOS][b["base"]]["create-queries"], "no")
        self.assertEqual(self.falso.grupos[a["grupo"]]["name"], "Brecha ficticia-a")

    def test_colecciones_por_organizacion(self):
        r = self._provisionar()
        a, b = r["ficticia-a"], r["ficticia-b"]
        grafo = self.falso.grafo_colecciones
        self.assertEqual(self.falso.colecciones[a["coleccion"]]["name"], "Brecha Ficticia A")
        self.assertEqual(grafo[a["grupo"]][a["coleccion"]], "read")
        self.assertEqual(grafo[a["grupo"]][b["coleccion"]], "none")
        self.assertEqual(grafo[b["grupo"]][b["coleccion"]], "read")
        self.assertEqual(grafo[b["grupo"]][a["coleccion"]], "none")
        # a new collection copied the root's "write" for All Users: provisioning takes it away, and the root's
        # too (a question saved there would reach every organization's users)
        self.assertEqual(grafo[TODOS][a["coleccion"]], "none")
        self.assertEqual(grafo[TODOS][b["coleccion"]], "none")
        self.assertEqual(grafo[TODOS]["root"], "none")

    def test_tarjetas_y_tableros(self):
        r = self._provisionar()
        for org in ORGS:
            o = r[org]
            self.assertEqual(len(o["tableros"]), 5)
            self.assertEqual(len(o["tarjetas"]), len(set(o["tarjetas"])))
            nombres = {self.falso.tableros[t]["name"] for t in o["tableros"]}
            self.assertEqual(
                nombres,
                {"Brecha confiable", "Segmentos", "Predial en tres columnas", "Cambios del catastro", "Mapa de lotes"},
            )
            for tid in o["tarjetas"]:
                tarjeta = self.falso.tarjetas[tid]
                consulta = tarjeta["dataset_query"]
                self.assertEqual(consulta["type"], "native")
                self.assertRegex(consulta["native"]["query"], r"^SELECT \* FROM v_[a-z_]+$")
                self.assertEqual(consulta["database"], o["base"])
                self.assertEqual(tarjeta["collection_id"], o["coleccion"])
            for did in o["tableros"]:
                tablero = self.falso.tableros[did]
                self.assertEqual(tablero["collection_id"], o["coleccion"])
                arriba = [d for d in tablero["dashcards"] if d["row"] == 0]
                self.assertEqual(len(arriba), 1)
                corte = self.falso.tarjetas[arriba[0]["card_id"]]
                self.assertEqual(corte["dataset_query"]["native"]["query"], "SELECT * FROM v_corte")
                self.assertEqual(corte["display"], "scalar")
                for d in tablero["dashcards"]:
                    self.assertLessEqual(d["col"] + d["size_x"], 24)
                    self.assertIn(d["card_id"], o["tarjetas"])
        mapa = next(t for t in self.falso.tarjetas.values() if t["display"] == "map")
        self.assertEqual(
            mapa["visualization_settings"],
            {"map.type": "pin", "map.latitude_column": "lat", "map.longitude_column": "lon"},
        )

    def test_es_idempotente(self):
        primero = self._provisionar()
        desde = len(self.falso.peticiones)
        segundo = self._provisionar()
        self.assertEqual(segundo, primero)
        # no POST of a database, group, collection, card or dashboard; and nothing changed, so no PUT either
        self.assertEqual(self._escrituras(desde), [])
        self.assertEqual(len(self.falso.bases), 2)
        self.assertEqual(len(self.falso.tableros), 10)

    def test_actualiza_en_su_lugar(self):
        primero = self._provisionar()
        tableros = _tableros()
        segmentos = next(t for t in tableros["tableros"] if t["nombre"] == "Segmentos")
        segmentos["tarjetas"][1]["display"] = "bar"
        segmentos["tarjetas"][1]["alto"] = 12
        desde = len(self.falso.peticiones)
        segundo = self._provisionar(tableros)
        self.assertEqual(segundo, primero)
        escrituras = self._escrituras(desde)
        self.assertFalse([e for e in escrituras if e[0] == "POST"], escrituras)
        # per organization: the changed card and the dashboard whose layout changed
        self.assertEqual(sum(1 for m, ruta in escrituras if re.fullmatch(r"/api/card/\d+", ruta)), 2)
        self.assertEqual(sum(1 for m, ruta in escrituras if re.fullmatch(r"/api/dashboard/\d+", ruta)), 2)
        tarjeta = self._por_nombre(self.falso.tarjetas, "Segmentos por confianza y categoría")
        self.assertEqual(tarjeta["display"], "bar")

    def test_corrige_una_base_cambiada_a_mano(self):
        r = self._provisionar()
        self.falso.bases[r["ficticia-a"]["base"]]["details"]["read_only"] = False
        self.falso.grafo_datos[TODOS][r["ficticia-a"]["base"]]["create-queries"] = "query-builder-and-native"
        self._provisionar()
        self.assertIs(self.falso.bases[r["ficticia-a"]["base"]]["details"]["read_only"], True)
        self.assertEqual(self.falso.grafo_datos[TODOS][r["ficticia-a"]["base"]]["create-queries"], "no")

    def test_una_organizacion_nueva_no_abre_las_anteriores(self):
        metabase.provisionar(self.mb, {"ficticia-a": "Ficticia A"}, _tableros())
        r = metabase.provisionar(self.mb, {"ficticia-b": "Ficticia B"}, _tableros())
        grupo_a = self._por_nombre(self.falso.grupos, "Brecha ficticia-a")["id"]
        base_a = self._por_nombre(self.falso.bases, "Brecha Ficticia A")["id"]
        coleccion_a = self._por_nombre(self.falso.colecciones, "Brecha Ficticia A")["id"]
        b = r["ficticia-b"]
        self.assertEqual(self.falso.grafo_datos[grupo_a][b["base"]]["create-queries"], "no")
        self.assertEqual(self.falso.grafo_datos[b["grupo"]][base_a]["create-queries"], "no")
        self.assertEqual(self.falso.grafo_colecciones[grupo_a][b["coleccion"]], "none")
        self.assertEqual(self.falso.grafo_colecciones[b["grupo"]][coleccion_a], "none")
        self.assertEqual(self.falso.grafo_datos[grupo_a][base_a]["create-queries"], "query-builder")

    def test_aislamiento_visto_por_un_usuario(self):
        r = self._provisionar()
        metabase.agregar_usuario(self.mb, "ficticia-a", "ana@ejemplo.pe", "Ana", "A", "clave-ana-1")
        ana = metabase.Metabase(self.falso.url)
        ana.sesion("ana@ejemplo.pe", "clave-ana-1")
        bases = [b["name"] for b in ana.get("/api/database")["data"]]
        self.assertEqual(bases, ["Brecha Ficticia A"])
        propia, ajena = r["ficticia-a"]["tarjetas"][0], r["ficticia-b"]["tarjetas"][0]
        self.assertEqual(ana.post(f"/api/card/{propia}/query")["status"], "completed")
        with self.assertRaises(metabase.ErrorMetabase) as error:
            ana.get(f"/api/card/{ajena}")
        self.assertEqual(error.exception.estado, 403)
        nativa = {"database": r["ficticia-a"]["base"], "type": "native", "native": {"query": "SELECT 1"}}
        with self.assertRaises(metabase.ErrorMetabase) as error:
            ana.post("/api/dataset", nativa)
        self.assertEqual(error.exception.estado, 403)

    def test_tableros_invalidos(self):
        for cambio, texto in (
            (lambda t: t["tableros"][0]["tarjetas"][1].update(vista="v_no; DROP"), "vista"),
            (lambda t: t["tableros"][0]["tarjetas"][1].update(col=20, ancho=12), "24"),
            (lambda t: t["tableros"][1]["tarjetas"][0].update(display="table"), "Fecha del corte"),
            (lambda t: t["tableros"][0]["tarjetas"][1].pop("alto"), "alto"),
        ):
            tableros = copy.deepcopy(_tableros())
            cambio(tableros)
            with self.subTest(texto=texto), self.assertRaises(metabase.ErrorTableros) as error:
                metabase.validar_tableros(tableros)
            self.assertIn(texto, str(error.exception))

    def test_tableros_citan_vistas_que_existen(self):
        vistas = (comun.INFORME / "vistas.sql").read_text(encoding="utf-8")
        tableros = _tableros()
        metabase.validar_tableros(tableros)
        self.assertEqual(len(tableros["tableros"]), 5)
        for tablero in tableros["tableros"]:
            self.assertEqual(tablero["tarjetas"][0]["vista"], "v_corte")
            self.assertEqual(tablero["tarjetas"][0]["display"], "scalar")
            for tarjeta in tablero["tarjetas"]:
                with self.subTest(tablero=tablero["nombre"], vista=tarjeta["vista"]):
                    self.assertRegex(vistas, rf"CREATE OR REPLACE VIEW {re.escape(tarjeta['vista'])} AS")


class SetupTests(unittest.TestCase):
    def test_setup_inicial(self):
        falso = MetabaseFalso({})
        self.addCleanup(falso.cerrar)
        mb = metabase.Metabase(falso.url)
        self.assertTrue(metabase.asegurar_setup(mb, *ADMIN))
        setups = [cuerpo for m, ruta, cuerpo in falso.peticiones if (m, ruta) == ("POST", "/api/setup")]
        self.assertEqual(len(setups), 1)
        self.assertEqual(setups[0]["token"], MetabaseFalso.SETUP_TOKEN)
        self.assertEqual(setups[0]["user"]["email"], ADMIN[0])
        mb.sesion(*ADMIN)
        usuario = next(u for u in falso.usuarios.values() if u["email"] == ADMIN[0])
        self.assertTrue(usuario["is_superuser"])
        # a second run finds the setup done and posts nothing
        self.assertFalse(metabase.asegurar_setup(mb, *ADMIN))
        self.assertEqual(sum(1 for m, ruta, _ in falso.peticiones if ruta == "/api/setup"), 1)

    def test_sesion_rechazada_sin_secretos(self):
        falso = MetabaseFalso({ADMIN[0]: ADMIN[1]})
        self.addCleanup(falso.cerrar)
        mb = metabase.Metabase(falso.url)
        with self.assertRaises(metabase.ErrorMetabase) as error:
            mb.sesion(ADMIN[0], "clave-equivocada")
        self.assertEqual(error.exception.estado, 401)
        self.assertNotIn("clave-equivocada", str(error.exception))

    def test_sin_servidor(self):
        falso = MetabaseFalso({})
        url = falso.url
        falso.cerrar()
        with self.assertRaises(metabase.ErrorMetabase) as error:
            metabase.Metabase(url).get("/api/health")
        self.assertIn("/api/health", str(error.exception))


class UsuarioTests(ConMetabase, unittest.TestCase):
    def test_agregar_usuario(self):
        r = self._provisionar()
        uid = metabase.agregar_usuario(self.mb, "ficticia-a", "ana@ejemplo.pe", "Ana", "A", "clave-ana-1")
        grupo = r["ficticia-a"]["grupo"]
        self.assertEqual(self.falso.usuarios[uid]["email"], "ana@ejemplo.pe")
        self.assertFalse(self.falso.usuarios[uid]["is_superuser"])
        self.assertEqual(self.falso.grupos_de(uid), {TODOS, grupo})
        # again: the same user, no second user and no second membership
        desde = len(self.falso.peticiones)
        self.assertEqual(metabase.agregar_usuario(self.mb, "ficticia-a", "Ana@Ejemplo.pe", "Ana", "A", "x"), uid)
        self.assertEqual(self._escrituras(desde), [])
        self.assertEqual(sum(1 for u in self.falso.usuarios.values() if u["email"] == "ana@ejemplo.pe"), 1)
        self.assertEqual(sum(1 for m in self.falso.membresias if m["user_id"] == uid and m["group_id"] == grupo), 1)
        self.assertNotIn(ADMINISTRADORES, self.falso.grupos_de(uid))

    def test_agregar_usuario_sin_grupo(self):
        with self.assertRaises(metabase.ErrorMetabase) as error:
            metabase.agregar_usuario(self.mb, "ficticia-z", "z@ejemplo.pe", "Z", "Z", "clave-z-1")
        self.assertIn("Brecha ficticia-z", str(error.exception))
        self.assertEqual(len(self.falso.usuarios), 1)


class CliTests(unittest.TestCase):
    def setUp(self):
        self.falso = MetabaseFalso({})
        self.addCleanup(self.falso.cerrar)
        self.entorno = {
            "METABASE_URL": self.falso.url,
            "METABASE_ADMIN_EMAIL": ADMIN[0],
            "METABASE_ADMIN_PASSWORD": ADMIN[1],
        }

    def _cli(self, argv, entorno=None, clave_tecleada=None):
        salida, error = io.StringIO(), io.StringIO()
        entorno = self.entorno if entorno is None else entorno
        with (
            mock.patch.dict(os.environ, entorno, clear=True),
            mock.patch("getpass.getpass", return_value=clave_tecleada) as getpass,
            contextlib.redirect_stdout(salida),
            contextlib.redirect_stderr(error),
        ):
            codigo = provisionar.main(argv)
        self.getpass = getpass
        return codigo, salida.getvalue(), error.getvalue()

    def test_provisiona_las_organizaciones_dadas(self):
        codigo, salida, error = self._cli(["--org", "ficticia-a:Ficticia A", "--org", "ficticia-b:Ficticia B"])
        self.assertEqual(codigo, 0, error)
        self.assertIn("setup inicial", salida)
        self.assertIn("ficticia-a", salida)
        self.assertEqual(
            sorted(b["name"] for b in self.falso.bases.values()), ["Brecha Ficticia A", "Brecha Ficticia B"]
        )

    def test_sin_org_toma_parametros(self):
        codigo, _, error = self._cli([])
        self.assertEqual(codigo, 0, error)
        nombres = {o["nombre"] for o in comun.parametros()["organizaciones"].values()}
        self.assertEqual({b["name"] for b in self.falso.bases.values()}, {f"Brecha {n}" for n in nombres})
        archivos = {b["details"]["database_file"] for b in self.falso.bases.values()}
        self.assertEqual(archivos, {f"/datos/{org}.duckdb" for org in comun.parametros()["organizaciones"]})

    def test_org_desconocida_sin_nombre(self):
        codigo, _, error = self._cli(["--org", "ficticia-a"])
        self.assertEqual(codigo, 2)
        self.assertIn("ficticia-a", error)
        self.assertEqual(self.falso.peticiones, [])

    def test_org_invalida(self):
        codigo, _, error = self._cli(["--org", "../x:X"])
        self.assertEqual(codigo, 2)
        self.assertEqual(self.falso.peticiones, [])

    def test_falta_configuracion(self):
        entorno = {k: v for k, v in self.entorno.items() if k != "METABASE_ADMIN_PASSWORD"}
        codigo, _, error = self._cli(["--org", "ficticia-a:Ficticia A"], entorno=entorno)
        self.assertEqual(codigo, 2)
        self.assertIn("METABASE_ADMIN_PASSWORD", error)
        self.assertEqual(self.falso.peticiones, [])

    def test_error_de_api_en_una_linea_sin_secretos(self):
        self._cli(["--org", "ficticia-a:Ficticia A"])
        entorno = {**self.entorno, "METABASE_ADMIN_PASSWORD": "clave-que-no-es"}
        codigo, _, error = self._cli(["--org", "ficticia-a:Ficticia A"], entorno=entorno)
        self.assertEqual(codigo, 1)
        self.assertEqual(len(error.strip().splitlines()), 1, error)
        self.assertIn("401", error)
        self.assertNotIn("clave-que-no-es", error)
        self.assertNotIn("sesion-", error)

    def test_usuario_con_clave_del_entorno(self):
        self._cli(["--org", "ficticia-a:Ficticia A"])
        entorno = {**self.entorno, "METABASE_CLAVE_USUARIO": "clave-ana-1"}
        argv = ["usuario", "--org", "ficticia-a", "--email", "ana@ejemplo.pe", "--nombre", "Ana", "--apellido", "A"]
        codigo, salida, error = self._cli(argv, entorno=entorno)
        self.assertEqual(codigo, 0, error)
        self.assertIn("ana@ejemplo.pe", salida)
        self.assertNotIn("clave-ana-1", salida + error)
        self.getpass.assert_not_called()
        ana = metabase.Metabase(self.falso.url)
        ana.sesion("ana@ejemplo.pe", "clave-ana-1")

    def test_usuario_con_clave_tecleada(self):
        self._cli(["--org", "ficticia-a:Ficticia A"])
        argv = ["usuario", "--org", "ficticia-a", "--email", "bea@ejemplo.pe", "--nombre", "Bea", "--apellido", "B"]
        codigo, _, error = self._cli(argv, clave_tecleada="clave-bea-1")
        self.assertEqual(codigo, 0, error)
        self.getpass.assert_called_once()
        metabase.Metabase(self.falso.url).sesion("bea@ejemplo.pe", "clave-bea-1")

    def test_usuario_sin_clave(self):
        argv = ["usuario", "--org", "ficticia-a", "--email", "c@ejemplo.pe", "--nombre", "C", "--apellido", "C"]
        codigo, _, error = self._cli(argv, clave_tecleada="")
        self.assertEqual(codigo, 2)
        self.assertEqual(self.falso.peticiones, [])


@unittest.skipIf(shutil.which("make") is None, "make no está instalado")
class MakefileProvisionarTests(unittest.TestCase):
    def _seco(self, *argumentos):
        entorno = {k: v for k, v in os.environ.items() if k not in ("ORGS", "MAKEFLAGS", "MFLAGS", "MAKELEVEL")}
        resultado = subprocess.run(
            ["make", "-n", *argumentos], cwd=comun.INFORME, env=entorno, capture_output=True, text=True
        )
        self.assertEqual(resultado.returncode, 0, resultado.stderr)
        return resultado.stdout

    def test_provisionar_pasa_las_organizaciones(self):
        self.assertIn('provisionar.py --org "perene" --org "ica"', self._seco("provisionar", "ORGS=perene ica"))

    def test_provisionar_sin_orgs(self):
        linea = next(x for x in self._seco("provisionar").splitlines() if "provisionar.py" in x)
        self.assertNotIn("--org", linea)


if __name__ == "__main__":
    unittest.main()
