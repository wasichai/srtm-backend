"""#206 task 10: `herramientas.metabase` against `pruebas.metabase_falso.MetabaseFalso` - one Metabase instance per
organization (plan, Decisión 2026-10-25): its one database, group and collection, All Users kept out of them and
of the root collection, the tableros of metabase/tableros.json, idempotency by name, the refusal to provision a
second organization into an instance, the first-run setup and `provisionar.py`'s CLI (URL from <org>.env)."""

import contextlib
import copy
import io
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import provisionar
from herramientas import base, comun, metabase
from pruebas.metabase_falso import ADMINISTRADORES, TODOS, MetabaseFalso

ADMIN = ("admin@ejemplo.pe", "clave-admin-1")
ORG, NOMBRE = "ficticia-a", "Ficticia A"
PROPIA = {"view-data": "unrestricted", "create-queries": "query-builder"}


def _tableros():
    return json.loads((comun.INFORME / "metabase" / "tableros.json").read_text(encoding="utf-8"))


class ConMetabase:
    """A fresh MetabaseFalso with one administrator, and a Metabase client logged in as that administrator."""

    def setUp(self):
        self.falso = MetabaseFalso({ADMIN[0]: ADMIN[1]})
        self.addCleanup(self.falso.cerrar)
        self.mb = metabase.Metabase(self.falso.url)
        self.mb.sesion(*ADMIN)

    def _provisionar(self, tableros=None, org=ORG, nombre=NOMBRE):
        return metabase.provisionar(self.mb, org, nombre, tableros or _tableros())

    def _por_nombre(self, coleccion, nombre):
        return next(x for x in coleccion.values() if x["name"] == nombre)

    def _escrituras(self, desde=0):
        return [(m, ruta) for m, ruta, _ in self.falso.peticiones[desde:] if m in ("POST", "PUT")]


class ProvisionarTests(ConMetabase, unittest.TestCase):
    def test_una_sola_base(self):
        self._provisionar()
        self.assertEqual(len(self.falso.bases), 1)
        b = self._por_nombre(self.falso.bases, f"Brecha {NOMBRE}")
        self.assertEqual(b["engine"], "duckdb")
        self.assertEqual(b["details"]["database_file"], f"/datos/{ORG}.duckdb")
        self.assertIs(b["details"]["read_only"], True)
        self.assertIs(b["details"]["old_implicit_casting"], True)
        self.assertEqual(b["details"]["init_sql"], base.INIT_SQL)

    def test_el_grupo_solo_consulta_con_el_constructor(self):
        r = self._provisionar()
        grafo = self.falso.grafo_datos
        self.assertEqual(grafo[r["grupo"]][r["base"]], PROPIA)
        # Metabase gives All Users native SQL on a new database: provisioning takes it away
        self.assertEqual(grafo[TODOS][r["base"]]["create-queries"], "no")
        self.assertEqual(self.falso.grupos[r["grupo"]]["name"], f"Brecha {ORG}")

    def test_la_coleccion_es_del_grupo(self):
        r = self._provisionar()
        grafo = self.falso.grafo_colecciones
        self.assertEqual(self.falso.colecciones[r["coleccion"]]["name"], f"Brecha {NOMBRE}")
        self.assertEqual(grafo[r["grupo"]][r["coleccion"]], "read")
        # a new collection copied the root's "write" for All Users: provisioning takes it away, and the root's
        # too (a question saved there would reach every user of the instance, in or out of the group)
        self.assertEqual(grafo[TODOS][r["coleccion"]], "none")
        self.assertEqual(grafo[TODOS]["root"], "none")

    def test_tarjetas_y_tableros(self):
        r = self._provisionar()
        self.assertEqual(len(r["tableros"]), 5)
        self.assertEqual(len(r["tarjetas"]), len(set(r["tarjetas"])))
        nombres = {self.falso.tableros[t]["name"] for t in r["tableros"]}
        self.assertEqual(
            nombres,
            {"Brecha confiable", "Segmentos", "Predial en tres columnas", "Cambios del catastro", "Mapa de lotes"},
        )
        for tid in r["tarjetas"]:
            tarjeta = self.falso.tarjetas[tid]
            consulta = tarjeta["dataset_query"]
            self.assertEqual(consulta["type"], "native")
            self.assertRegex(consulta["native"]["query"], r"^SELECT \* FROM v_[a-z_]+$")
            self.assertEqual(consulta["database"], r["base"])
            self.assertEqual(tarjeta["collection_id"], r["coleccion"])
        for did in r["tableros"]:
            tablero = self.falso.tableros[did]
            self.assertEqual(tablero["collection_id"], r["coleccion"])
            arriba = [d for d in tablero["dashcards"] if d["row"] == 0]
            self.assertEqual(len(arriba), 1)
            corte = self.falso.tarjetas[arriba[0]["card_id"]]
            self.assertEqual(corte["dataset_query"]["native"]["query"], "SELECT * FROM v_corte")
            self.assertEqual(corte["display"], "scalar")
            for d in tablero["dashcards"]:
                self.assertLessEqual(d["col"] + d["size_x"], 24)
                self.assertIn(d["card_id"], r["tarjetas"])
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
        self.assertEqual(len(self.falso.bases), 1)
        self.assertEqual(len(self.falso.tableros), 5)

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
        # the changed card and the dashboard whose layout changed
        self.assertEqual(sum(1 for m, ruta in escrituras if re.fullmatch(r"/api/card/\d+", ruta)), 1)
        self.assertEqual(sum(1 for m, ruta in escrituras if re.fullmatch(r"/api/dashboard/\d+", ruta)), 1)
        tarjeta = self._por_nombre(self.falso.tarjetas, "Segmentos por confianza y categoría")
        self.assertEqual(tarjeta["display"], "bar")

    def test_corrige_una_base_cambiada_a_mano(self):
        r = self._provisionar()
        self.falso.bases[r["base"]]["details"]["read_only"] = False
        self.falso.grafo_datos[TODOS][r["base"]]["create-queries"] = "query-builder-and-native"
        self.falso.grafo_datos[r["grupo"]][r["base"]]["create-queries"] = "query-builder-and-native"
        self._provisionar()
        self.assertIs(self.falso.bases[r["base"]]["details"]["read_only"], True)
        self.assertEqual(self.falso.grafo_datos[TODOS][r["base"]]["create-queries"], "no")
        self.assertEqual(self.falso.grafo_datos[r["grupo"]][r["base"]], PROPIA)

    def test_se_niega_a_meter_otra_organizacion_en_la_instancia(self):
        # one instance, one organization (plan, Decisión 2026-10-25): an org env file copied with another
        # organization's METABASE_PUERTO would otherwise provision B into A's Metabase, where B's users would
        # see A's users. Refused before anything is written.
        self._provisionar()
        desde = len(self.falso.peticiones)
        with self.assertRaises(metabase.ErrorMetabase) as error:
            self._provisionar(org="ficticia-b", nombre="Ficticia B")
        mensaje = str(error.exception)
        self.assertIn("Brecha Ficticia A", mensaje)
        self.assertIn("ficticia-b", mensaje)
        self.assertEqual(len(mensaje.splitlines()), 1)
        self.assertEqual(self._escrituras(desde), [])
        self.assertEqual(len(self.falso.bases), 1)

    def test_se_niega_si_hay_un_grupo_de_otra_organizacion(self):
        self.mb.post("/api/permissions/group", {"name": "Brecha ficticia-b"})
        desde = len(self.falso.peticiones)
        with self.assertRaises(metabase.ErrorMetabase) as error:
            self._provisionar()
        self.assertIn("Brecha ficticia-b", str(error.exception))
        self.assertEqual(self._escrituras(desde), [])

    def test_se_niega_si_la_base_apunta_a_otro_archivo(self):
        # a database with our name but another organization's file is the same mistake, made by hand
        r = self._provisionar()
        self.falso.bases[r["base"]]["details"]["database_file"] = "/datos/ficticia-b.duckdb"
        with self.assertRaises(metabase.ErrorMetabase) as error:
            self._provisionar()
        self.assertIn("/datos/ficticia-b.duckdb", str(error.exception))
        self.assertEqual(self.falso.bases[r["base"]]["details"]["database_file"], "/datos/ficticia-b.duckdb")

    def test_una_base_nueva_queda_cerrada_aunque_la_provision_se_corte(self):
        # Metabase gives All Users native SQL on a new database: if the run stops right after POSTing it, the
        # database must be closed to All Users anyway
        falla = metabase.ErrorMetabase("POST", "/api/collection", 500)
        with mock.patch.object(metabase, "_asegurar_coleccion", side_effect=falla), self.assertRaises(
            metabase.ErrorMetabase
        ):
            self._provisionar()
        base_a = self._por_nombre(self.falso.bases, f"Brecha {NOMBRE}")["id"]
        self.assertEqual(self.falso.grafo_datos[TODOS][base_a]["create-queries"], "no")
        # seen by a user who is only in All Users: no database at all
        self.falso._crear_usuario("solo-todos@ejemplo.pe", "clave-solo-1", "Solo", "Todos")
        solo = metabase.Metabase(self.falso.url)
        solo.sesion("solo-todos@ejemplo.pe", "clave-solo-1")
        self.assertEqual(solo.get("/api/database")["data"], [])

    def test_aislamiento_visto_por_un_usuario(self):
        r = self._provisionar()
        metabase.agregar_usuario(self.mb, ORG, "ana@ejemplo.pe", "Ana", "A", "clave-ana-1")
        ana = metabase.Metabase(self.falso.url)
        ana.sesion("ana@ejemplo.pe", "clave-ana-1")
        bases = [b["name"] for b in ana.get("/api/database")["data"]]
        self.assertEqual(bases, [f"Brecha {NOMBRE}"])
        self.assertEqual(ana.post(f"/api/card/{r['tarjetas'][0]}/query")["status"], "completed")
        nativa = {"database": r["base"], "type": "native", "native": {"query": "SELECT 1"}}
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

    def test_error_400_nombra_los_campos_sin_sus_valores(self):
        # a 400 from /api/setup is diagnosable only with the keys of its `errors` object; never their values
        # (they can echo what was sent) nor the request body (the password)
        falso = MetabaseFalso({})
        self.addCleanup(falso.cerrar)
        mb = metabase.Metabase(falso.url)
        cuerpo = {"token": MetabaseFalso.SETUP_TOKEN, "user": {"email": ADMIN[0], "password": "clave-secreta-9"}}
        with self.assertRaises(metabase.ErrorMetabase) as error:
            mb.post("/api/setup", {**cuerpo, "prefs": {}})
        mensaje = str(error.exception)
        self.assertEqual(error.exception.estado, 400)
        self.assertIn("POST /api/setup -> 400", mensaje)
        self.assertIn("user", mensaje)
        self.assertNotIn("clave-secreta-9", mensaje)
        self.assertNotIn("falta", mensaje)
        self.assertEqual(len(mensaje.splitlines()), 1)

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
        grupo = r["grupo"]
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

    def test_agregar_usuario_desactivado(self):
        self._provisionar()
        uid = metabase.agregar_usuario(self.mb, "ficticia-a", "ana@ejemplo.pe", "Ana", "A", "clave-ana-1")
        self.falso.usuarios[uid]["is_active"] = False
        desde = len(self.falso.peticiones)
        with self.assertRaises(metabase.ErrorMetabase) as error:
            metabase.agregar_usuario(self.mb, "ficticia-a", "ana@ejemplo.pe", "Ana", "A", "clave-ana-1")
        mensaje = str(error.exception)
        self.assertIn("desactivad", mensaje)
        self.assertIn("ana@ejemplo.pe", mensaje)
        self.assertEqual(len(mensaje.splitlines()), 1)
        self.assertEqual(self._escrituras(desde), [])

    def test_agregar_usuario_sin_grupo(self):
        with self.assertRaises(metabase.ErrorMetabase) as error:
            metabase.agregar_usuario(self.mb, "ficticia-z", "z@ejemplo.pe", "Z", "Z", "clave-z-1")
        self.assertIn("Brecha ficticia-z", str(error.exception))
        self.assertEqual(len(self.falso.usuarios), 1)


class CliTests(unittest.TestCase):
    """provisionar.py takes the instance's URL from the organization's own <org>.env (METABASE_PUERTO, or
    METABASE_URL there), never from the shell's environment: a METABASE_URL left exported from another
    organization's session must not send this one's provisioning to that instance."""

    def setUp(self):
        self.falso = MetabaseFalso({})
        self.addCleanup(self.falso.cerrar)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.archivo = self._entorno_org(ORG, f"METABASE_PUERTO={self.falso.url.rsplit(':', 1)[1]}\n")
        self.entorno = {"METABASE_ADMIN_EMAIL": ADMIN[0], "METABASE_ADMIN_PASSWORD": ADMIN[1]}

    def _entorno_org(self, org, contenido, modo=0o600):
        ruta = self.tmp / f"{org}.env"
        ruta.write_text(contenido, encoding="utf-8")
        ruta.chmod(modo)
        return ruta

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

    def _org(self, valor=f"{ORG}:{NOMBRE}", archivo=None):
        return ["--org", valor, "--entorno", str(archivo or self.archivo)]

    def test_provisiona_la_organizacion_de_su_instancia(self):
        codigo, salida, error = self._cli(self._org())
        self.assertEqual(codigo, 0, error)
        self.assertIn("setup inicial", salida)
        self.assertIn(ORG, salida)
        self.assertEqual([b["name"] for b in self.falso.bases.values()], [f"Brecha {NOMBRE}"])

    def test_nombre_de_parametros(self):
        org, datos = next(iter(comun.parametros()["organizaciones"].items()))
        archivo = self._entorno_org(org, self.archivo.read_text(encoding="utf-8"))
        codigo, _, error = self._cli(self._org(org, archivo))
        self.assertEqual(codigo, 0, error)
        self.assertEqual([b["name"] for b in self.falso.bases.values()], [f"Brecha {datos['nombre']}"])
        self.assertEqual(next(iter(self.falso.bases.values()))["details"]["database_file"], f"/datos/{org}.duckdb")

    def test_url_del_archivo_de_la_organizacion(self):
        # METABASE_URL in the file wins over the default 127.0.0.1:<METABASE_PUERTO>
        archivo = self._entorno_org(ORG, f"METABASE_PUERTO=1\nMETABASE_URL={self.falso.url}\n")
        codigo, _, error = self._cli(self._org(archivo=archivo))
        self.assertEqual(codigo, 0, error)
        self.assertEqual(len(self.falso.bases), 1)

    def test_ignora_metabase_url_del_entorno(self):
        otro = MetabaseFalso({})
        self.addCleanup(otro.cerrar)
        codigo, _, error = self._cli(self._org(), entorno={**self.entorno, "METABASE_URL": otro.url})
        self.assertEqual(codigo, 0, error)
        self.assertEqual(otro.peticiones, [])
        self.assertEqual(len(self.falso.bases), 1)

    def test_credenciales_del_archivo_de_la_organizacion(self):
        contenido = self.archivo.read_text(encoding="utf-8")
        archivo = self._entorno_org(
            ORG, contenido + f"METABASE_ADMIN_EMAIL={ADMIN[0]}\nMETABASE_ADMIN_PASSWORD={ADMIN[1]}\n"
        )
        codigo, _, error = self._cli(self._org(archivo=archivo), entorno={})
        self.assertEqual(codigo, 0, error)

    def test_falta_el_puerto(self):
        archivo = self._entorno_org(ORG, "OTRA=1\n")
        codigo, _, error = self._cli(self._org(archivo=archivo))
        self.assertEqual(codigo, 2)
        self.assertIn("METABASE_PUERTO", error)
        self.assertEqual(self.falso.peticiones, [])

    def test_archivo_con_permisos_abiertos(self):
        archivo = self._entorno_org(ORG, self.archivo.read_text(encoding="utf-8"), modo=0o644)
        codigo, _, error = self._cli(self._org(archivo=archivo))
        self.assertEqual(codigo, 2)
        self.assertIn("0600", error)
        self.assertEqual(self.falso.peticiones, [])

    def test_archivo_que_no_existe(self):
        codigo, _, error = self._cli(self._org(archivo=self.tmp / "no-existe.env"))
        self.assertEqual(codigo, 2)
        self.assertIn("no-existe.env", error)

    def test_una_sola_org(self):
        codigo, _, _ = self._cli([*self._org(), "--org", "ficticia-b:Ficticia B"])
        self.assertEqual(codigo, 2)
        codigo, _, _ = self._cli([])
        self.assertEqual(codigo, 2)
        self.assertEqual(self.falso.peticiones, [])

    def test_org_desconocida_sin_nombre(self):
        codigo, _, error = self._cli(self._org(ORG))
        self.assertEqual(codigo, 2)
        self.assertIn(ORG, error)
        self.assertEqual(self.falso.peticiones, [])

    def test_org_invalida(self):
        codigo, _, error = self._cli(self._org("../x:X"))
        self.assertEqual(codigo, 2)
        self.assertEqual(self.falso.peticiones, [])

    def test_falta_configuracion(self):
        entorno = {k: v for k, v in self.entorno.items() if k != "METABASE_ADMIN_PASSWORD"}
        codigo, _, error = self._cli(self._org(), entorno=entorno)
        self.assertEqual(codigo, 2)
        self.assertIn("METABASE_ADMIN_PASSWORD", error)
        self.assertEqual(self.falso.peticiones, [])

    def test_error_de_api_en_una_linea_sin_secretos(self):
        self._cli(self._org())
        entorno = {**self.entorno, "METABASE_ADMIN_PASSWORD": "clave-que-no-es"}
        codigo, _, error = self._cli(self._org(), entorno=entorno)
        self.assertEqual(codigo, 1)
        self.assertEqual(len(error.strip().splitlines()), 1, error)
        self.assertIn("401", error)
        self.assertNotIn("clave-que-no-es", error)
        self.assertNotIn("sesion-", error)

    def test_otra_organizacion_en_la_misma_instancia_sale_con_1(self):
        self._cli(self._org())
        archivo_b = self._entorno_org("ficticia-b", self.archivo.read_text(encoding="utf-8"))
        codigo, _, error = self._cli(self._org("ficticia-b:Ficticia B", archivo_b))
        self.assertEqual(codigo, 1)
        self.assertEqual(len(error.strip().splitlines()), 1, error)
        self.assertIn("Brecha Ficticia A", error)

    def _usuario(self, email, nombre):
        return ["usuario", "--org", ORG, "--entorno", str(self.archivo), "--email", email, "--nombre", nombre,
                "--apellido", "X"]

    def test_usuario_con_clave_del_entorno(self):
        self._cli(self._org())
        entorno = {**self.entorno, "METABASE_CLAVE_USUARIO": "clave-ana-1"}
        codigo, salida, error = self._cli(self._usuario("ana@ejemplo.pe", "Ana"), entorno=entorno)
        self.assertEqual(codigo, 0, error)
        self.assertIn("ana@ejemplo.pe", salida)
        self.assertNotIn("clave-ana-1", salida + error)
        self.getpass.assert_not_called()
        ana = metabase.Metabase(self.falso.url)
        ana.sesion("ana@ejemplo.pe", "clave-ana-1")

    def test_usuario_con_clave_tecleada(self):
        self._cli(self._org())
        codigo, _, error = self._cli(self._usuario("bea@ejemplo.pe", "Bea"), clave_tecleada="clave-bea-1")
        self.assertEqual(codigo, 0, error)
        self.getpass.assert_called_once()
        metabase.Metabase(self.falso.url).sesion("bea@ejemplo.pe", "clave-bea-1")

    def test_usuario_sin_clave(self):
        codigo, _, error = self._cli(self._usuario("c@ejemplo.pe", "C"), clave_tecleada="")
        self.assertEqual(codigo, 2)
        self.assertEqual(self.falso.peticiones, [])


@unittest.skipIf(shutil.which("make") is None, "make no está instalado")
class MakefileProvisionarTests(unittest.TestCase):
    def _seco(self, *argumentos):
        entorno = {
            k: v for k, v in os.environ.items() if k not in ("ORG", "ORGS", "MAKEFLAGS", "MFLAGS", "MAKELEVEL")
        }
        resultado = subprocess.run(
            ["make", "-n", *argumentos], cwd=comun.INFORME, env=entorno, capture_output=True, text=True
        )
        self.assertEqual(resultado.returncode, 0, resultado.stderr)
        return resultado.stdout

    def test_provisionar_pasa_la_organizacion_y_su_archivo(self):
        texto = self._seco("provisionar", "ORG=perene")
        self.assertIn(
            f'provisionar.py --org "perene" --entorno "{Path.home()}/.config/srtm-analitica/perene.env"', texto
        )

if __name__ == "__main__":
    unittest.main()
