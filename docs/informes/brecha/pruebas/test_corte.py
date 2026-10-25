"""The whole corte, end to end: `corte.main` against `SrtmFalso`/`CatastroFalso` serving `minimos()` or
`datos(7)`, a 0600 secrets file and an output folder outside the repository (comun.carpeta_corte rejects one
inside it). Plus `parquet.escribir`'s decimal quantization and `manifiesto.verificar_corte` on their own."""

import contextlib
import io
import json
import os
import shutil
import tempfile
import unittest
from datetime import date, datetime, timezone
from decimal import Decimal
from pathlib import Path
from unittest import mock

import pyarrow.parquet as pq

import corte
from herramientas import comun, manifiesto, parquet
from herramientas.comun import ErrorCorte
from herramientas.falsos import CatastroFalso, SrtmFalso
from herramientas.seudonimo import huella_clave
from herramientas.sinteticos import R2, datos, minimos
from herramientas.tablas import COLUMNAS, ESQUEMAS

CLAVE = bytes(range(32))
ORG = "ficticia"
FECHA = "2026-10-25"
HOY = date(2026, 10, 25)
RELOJ = datetime(2026, 10, 25, 11, 0, tzinfo=timezone.utc)
OTRO_RELOJ = datetime(2026, 10, 25, 12, 30, 15, tzinfo=timezone.utc)


class _CatastroRoto(CatastroFalso):
    """A catastro-backend whose valuaciones answer 500: the corte must abort without leaving anything."""

    def manejar(self, metodo, ruta, query, cuerpo):
        if ruta == "/api/catastro/valuaciones":
            return 500, {"detail": "falla simulada"}
        return super().manejar(metodo, ruta, query, cuerpo)


class ConCorte:
    """setUp and helpers to take a corte against the fakes; mixed into a TestCase here and in
    test_vistas.py."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="corte-prueba-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        # the backend versions come from the environment: start every test without them
        entorno = mock.patch.dict(os.environ)
        entorno.start()
        self.addCleanup(entorno.stop)
        os.environ.pop("VERSION_SRTM", None)
        os.environ.pop("VERSION_CATASTRO", None)

    # --- helpers -------------------------------------------------------------------------------------------

    def _falsos(self, s, barajar=None, puertos=(0, 0), clase_catastro=CatastroFalso, **opciones_catastro):
        servidor_srtm = SrtmFalso(s.srtm, barajar=barajar, puerto=puertos[0])
        self.addCleanup(servidor_srtm.cerrar)
        servidor_catastro = clase_catastro(
            registros=s.catastro,
            valuaciones=s.valuaciones,
            titulares=s.titulares,
            cambios=s.cambios,
            barajar=barajar,
            puerto=puertos[1],
            **opciones_catastro,
        )
        self.addCleanup(servidor_catastro.cerrar)
        return servidor_srtm, servidor_catastro

    def _secretos(self, servidor_srtm, servidor_catastro, modo=0o600, extra=""):
        ruta = self.tmp / "secretos.env"
        ruta.write_text(
            f"SRTM_URL={servidor_srtm.url}\n"
            f"SRTM_CLIENT_ID={servidor_srtm.CLIENT_ID}\n"
            f"SRTM_CLIENT_SECRET={servidor_srtm.CLIENT_SECRET}\n"
            f"CATASTRO_URL={servidor_catastro.url}\n"
            f"CATASTRO_CLIENT_ID={servidor_catastro.CLIENT_ID}\n"
            f"CATASTRO_CLIENT_SECRET={servidor_catastro.CLIENT_SECRET}\n"
            f"CLAVE_SEUDONIMO={CLAVE.hex()}\n" + extra,
            encoding="utf-8",
        )
        ruta.chmod(modo)
        return ruta

    def _correr(self, secretos, salida, *extra, org=ORG, fecha=FECHA, reloj=RELOJ):
        argv = ["--org", org, "--salida", str(salida), "--secretos", str(secretos), *extra]
        if fecha is not None:
            argv += ["--fecha", fecha]
        salida_std, error_std = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(salida_std), contextlib.redirect_stderr(error_std):
            codigo = corte.main(argv, reloj=lambda: reloj, hoy=lambda: HOY)
        return codigo, salida_std.getvalue(), error_std.getvalue()

    def _corte(self, s, salida=None, fecha=FECHA, **opciones_catastro):
        """One successful corte of `s`; returns its folder."""
        salida = salida or self.tmp / "cortes"
        secretos = self._secretos(*self._falsos(s, **opciones_catastro))
        codigo, _, error = self._correr(secretos, salida, fecha=fecha)
        self.assertEqual(codigo, 0, error)
        return salida / ORG / fecha

    @staticmethod
    def _sumas(carpeta):
        return {p.name: comun.sha256(p) for p in sorted(carpeta.glob("*.parquet"))}

    @staticmethod
    def _manifiesto(carpeta):
        return json.loads((carpeta / "manifiesto.json").read_text(encoding="utf-8"))


class CorteTests(ConCorte, unittest.TestCase):
    # --- the brief's scenarios -----------------------------------------------------------------------------

    def test_dos_cortes_dan_los_mismos_sha256(self):
        s = datos(7)
        srtm_1, catastro_1 = self._falsos(s)
        puertos = tuple(int(servidor.url.rsplit(":", 1)[1]) for servidor in (srtm_1, catastro_1))
        secretos = self._secretos(srtm_1, catastro_1)
        codigo, salida_std, error = self._correr(secretos, self.tmp / "uno")
        self.assertEqual(codigo, 0, error)
        srtm_1.cerrar()
        catastro_1.cerrar()

        # same data, same URLs, but every record object served in another order, and another clock
        srtm_2, catastro_2 = self._falsos(s, barajar=3, puertos=puertos)
        self._secretos(srtm_2, catastro_2)
        codigo, _, error = self._correr(secretos, self.tmp / "dos", reloj=OTRO_RELOJ)
        self.assertEqual(codigo, 0, error)

        uno, dos = self.tmp / "uno" / ORG / FECHA, self.tmp / "dos" / ORG / FECHA
        self.assertEqual(len(self._sumas(uno)), 17)
        self.assertEqual(self._sumas(uno), self._sumas(dos))
        m_uno, m_dos = self._manifiesto(uno), self._manifiesto(dos)
        self.assertNotEqual(m_uno.pop("leido_en"), m_dos.pop("leido_en"))
        self.assertEqual(m_uno, m_dos)
        filas = sum(a["filas"] for a in m_uno["archivos"])
        self.assertEqual(salida_std.strip(), f"corte {ORG} {FECHA}: 17 tablas, {filas} filas -> {uno}")

    def test_ningun_parquet_trae_documentos_ni_nombres(self):
        s = datos(7)
        carpeta = self._corte(s)
        prohibidos = set(s.nombres)
        for documento in s.documentos:
            prohibidos |= {documento, documento.lstrip("0")}
        archivos = sorted(carpeta.glob("*.parquet"))
        self.assertEqual(len(archivos), 17)
        for archivo in archivos:
            for fila in pq.read_table(archivo).to_pylist():
                for columna, valor in fila.items():
                    if not isinstance(valor, str):
                        continue
                    for prohibido in prohibidos:
                        with self.subTest(archivo=archivo.name, columna=columna):
                            self.assertNotIn(prohibido, valor)

    def test_manifiesto_completo(self):
        carpeta = self._corte(minimos())
        m = self._manifiesto(carpeta)
        self.assertEqual(m["formato"], 1)
        self.assertEqual(m["organizacion"], ORG)
        self.assertEqual(m["fecha_corte"], FECHA)
        self.assertEqual(m["tipo"], "mensual")
        self.assertEqual(m["anio"], 2026)
        self.assertEqual(m["leido_en"], "2026-10-25T11:00:00Z")
        self.assertEqual(m["motor"], {"version": "valuacion-v3", "region": "COSTA"})
        self.assertEqual(
            m["brecha_corrida"],
            {"id": R2, "terminado": "2026-10-20T10:00:00Z", "version_catastro": "valuacion-v3"},
        )
        self.assertEqual(m["normativa"]["aranceles"], {"anio": 2026, "total": 2, "archivo": "srtm_arancel.parquet"})
        parametros = m["normativa"]["parametros"]
        claves = [(p["tipo"], p["clave"]) for p in parametros]
        self.assertEqual(claves, sorted(claves))
        self.assertIn(
            {
                "tipo": "UIT",
                "clave": "UIT-2026",
                "vigencia_desde": "2026-01-01",
                "vigencia_hasta": "2026-12-31",
                "valor_numerico": "5500",
                "texto": None,
                "norma": None,
            },
            parametros,
        )
        self.assertNotIn("UIT-2025", {p["clave"] for p in parametros})
        self.assertEqual(set(m["backends"]), {"srtm", "catastro"})
        self.assertTrue(m["backends"]["srtm"]["url"].startswith("http://127.0.0.1:"))
        self.assertEqual(set(m["metodo"]), {"commit", "sucio"})
        self.assertRegex(m["metodo"]["commit"], r"^[0-9a-f]{40}$")
        self.assertIsInstance(m["metodo"]["sucio"], bool)
        self.assertEqual(m["seudonimizacion"]["algoritmo"], "HMAC-SHA256")
        self.assertEqual(m["seudonimizacion"]["huella_clave"], huella_clave(CLAVE))
        self.assertTrue(m["seudonimizacion"]["normalizacion"])
        self.assertEqual(m["avisos"], sorted(m["avisos"]))
        self.assertEqual(
            set(m),
            {
                "formato",
                "organizacion",
                "fecha_corte",
                "tipo",
                "anio",
                "leido_en",
                "motor",
                "brecha_corrida",
                "normativa",
                "backends",
                "metodo",
                "seudonimizacion",
                "avisos",
                "archivos",
            },
        )

        archivos = m["archivos"]
        self.assertEqual(len(archivos), 17)
        self.assertEqual([a["archivo"] for a in archivos], sorted(a["archivo"] for a in archivos))
        self.assertEqual({a["tabla"] for a in archivos}, set(COLUMNAS))
        for entrada in archivos:
            with self.subTest(archivo=entrada["archivo"]):
                ruta = carpeta / entrada["archivo"]
                self.assertEqual(entrada["archivo"], f"{entrada['tabla']}.parquet")
                self.assertEqual(entrada["sha256"], comun.sha256(ruta))
                self.assertEqual(entrada["bytes"], ruta.stat().st_size)
                self.assertEqual(entrada["filas"], pq.read_metadata(ruta).num_rows)
                self.assertTrue(pq.read_schema(ruta).equals(ESQUEMAS[entrada["tabla"]]))
        en_disco = sorted(p.name for p in carpeta.iterdir())
        self.assertEqual(en_disco, sorted([*(a["archivo"] for a in archivos), "manifiesto.json"]))
        self.assertEqual(manifiesto.verificar_corte(carpeta), m)

        anual = self._manifiesto(self._corte(minimos(), fecha="2026-01-01"))
        self.assertEqual(anual["tipo"], "anual")
        self.assertEqual(anual["anio"], 2026)

    def test_tablas_vacias_conservan_el_esquema(self):
        s = minimos()
        s.srtm["brecha_corrida"] = [c for c in s.srtm["brecha_corrida"] if c["attributes"]["estado"] != "TERMINADA"]
        carpeta = self._corte(s, sin_titulares=True, sin_cambios=True)
        for tabla in ("srtm_brecha_corrida", "srtm_brecha_predio", "catastro_titular", "catastro_cambio"):
            with self.subTest(tabla=tabla):
                leida = pq.read_table(carpeta / f"{tabla}.parquet")
                self.assertEqual(leida.num_rows, 0)
                self.assertTrue(leida.schema.equals(ESQUEMAS[tabla]))
        m = self._manifiesto(carpeta)
        self.assertIsNone(m["brecha_corrida"])
        self.assertIn("sin brecha TERMINADA de 2026", m["avisos"])
        self.assertTrue(any("titulares" in aviso for aviso in m["avisos"]))
        self.assertTrue(any("cambios" in aviso for aviso in m["avisos"]))

    def test_decimales_se_cuantizan(self):
        filas = [
            {"id": "a", "valor_afecto": 400000},
            {"id": "b", "valor_afecto": "12.5"},
            {"id": "c", "valor_afecto": Decimal("0.123456789012")},
        ]
        entrada = parquet.escribir("srtm_declaracion", filas, self.tmp)
        leida = pq.read_table(self.tmp / "srtm_declaracion.parquet")
        self.assertEqual(
            leida.column("valor_afecto").to_pylist(),
            [Decimal("400000.0000000000"), Decimal("12.5000000000"), Decimal("0.1234567890")],
        )
        self.assertEqual(entrada["filas"], 3)
        self.assertEqual(entrada["archivo"], "srtm_declaracion.parquet")
        self.assertEqual(entrada["sha256"], comun.sha256(self.tmp / "srtm_declaracion.parquet"))

    def test_valor_no_convertible_nombra_tabla_y_columna(self):
        with self.assertRaises(ErrorCorte) as error:
            parquet.escribir("srtm_declaracion", [{"id": "a", "valor_afecto": "doce"}], self.tmp)
        self.assertIn("srtm_declaracion", str(error.exception))
        self.assertIn("valor_afecto", str(error.exception))
        with self.assertRaises(ErrorCorte) as error:
            parquet.escribir("catastro_construccion", [{"id": "a", "anio_construccion": "S1"}], self.tmp)
        self.assertIn("catastro_construccion.anio_construccion", str(error.exception))

    def test_piso_de_catastro_acepta_texto_y_numeros(self):
        filas = [{"id": "a", "piso": "S1"}, {"id": "b", "piso": 2}, {"id": "c", "piso": "1"}]
        parquet.escribir("catastro_construccion", filas, self.tmp)
        leida = pq.read_table(self.tmp / "catastro_construccion.parquet")
        self.assertEqual(leida.column("piso").to_pylist(), ["1", "2", "S1"])

    def test_orden_con_none_primero(self):
        comun_a_todas = {"objeto": "construccion", "operacion": "CREATE", "campos": ""}
        filas = [
            {"codigo_municipal": "0101002", "en": "2026-10-21T00:00:00Z", **comun_a_todas},
            {"codigo_municipal": "0101001", "en": None, **comun_a_todas},
            {"codigo_municipal": "0101001", "en": "2026-10-20T00:00:00Z", **comun_a_todas},
        ]
        parquet.escribir("catastro_cambio", filas, self.tmp)
        leida = pq.read_table(self.tmp / "catastro_cambio.parquet").to_pylist()
        self.assertEqual(
            [f["en"] for f in leida],
            [None, datetime(2026, 10, 20, tzinfo=timezone.utc), datetime(2026, 10, 21, tzinfo=timezone.utc)],
        )

    def test_un_error_no_deja_carpeta(self):
        secretos = self._secretos(*self._falsos(minimos(), clase_catastro=_CatastroRoto))
        salida = self.tmp / "cortes"
        codigo, _, error = self._correr(secretos, salida)
        self.assertNotEqual(codigo, 0)
        self.assertIn("500", error)
        self.assertNotIn(CLAVE.hex(), error)
        self.assertNotIn(f"={CatastroFalso.CLIENT_SECRET}", error)
        self.assertFalse((salida / ORG / FECHA).exists())
        self.assertFalse((salida / ORG / f"{FECHA}.parcial").exists())

    def test_carpeta_existente_sin_reemplazar(self):
        s = minimos()
        salida = self.tmp / "cortes"
        secretos = self._secretos(*self._falsos(s))
        codigo, _, error = self._correr(secretos, salida)
        self.assertEqual(codigo, 0, error)
        carpeta = salida / ORG / FECHA
        antes = self._sumas(carpeta)
        manifiesto_antes = (carpeta / "manifiesto.json").read_bytes()

        codigo, _, error = self._correr(secretos, salida, reloj=OTRO_RELOJ)
        self.assertEqual(codigo, 2)
        self.assertIn("ya existe", error)
        self.assertEqual(self._sumas(carpeta), antes)
        self.assertEqual((carpeta / "manifiesto.json").read_bytes(), manifiesto_antes)
        self.assertFalse((salida / ORG / f"{FECHA}.parcial").exists())

    def test_reemplazar_sustituye_el_corte(self):
        salida = self.tmp / "cortes"
        secretos = self._secretos(*self._falsos(minimos()))
        self.assertEqual(self._correr(secretos, salida)[0], 0)
        codigo, _, error = self._correr(secretos, salida, "--reemplazar", reloj=OTRO_RELOJ)
        self.assertEqual(codigo, 0, error)
        carpeta = salida / ORG / FECHA
        self.assertEqual(self._manifiesto(carpeta)["leido_en"], "2026-10-25T12:30:15Z")
        self.assertEqual(sorted(p.name for p in (salida / ORG).iterdir()), [FECHA])

    def test_errores_de_uso_salen_con_2(self):
        servidores = self._falsos(minimos())
        secretos = self._secretos(*servidores)
        salida = self.tmp / "cortes"
        self.assertEqual(self._correr(secretos, salida, org="Mala Org")[0], 2)
        self.assertEqual(self._correr(secretos, salida, fecha="2026-10-26")[0], 2)
        self.assertEqual(self._correr(secretos, salida, fecha="ayer")[0], 2)
        abiertos = self._secretos(*servidores, modo=0o644)
        self.assertEqual(self._correr(abiertos, salida)[0], 2)
        self.assertEqual(self._correr(self.tmp / "no-existe.env", salida)[0], 2)
        self.assertFalse((salida / ORG / FECHA).exists())

    def test_verificar_detecta_un_archivo_alterado(self):
        carpeta = self._corte(minimos())
        self.assertEqual(manifiesto.verificar_corte(carpeta)["organizacion"], ORG)
        ruta = carpeta / "srtm_predio.parquet"
        contenido = bytearray(ruta.read_bytes())
        contenido[len(contenido) // 2] ^= 0xFF
        ruta.write_bytes(bytes(contenido))
        with self.assertRaises(ErrorCorte) as error:
            manifiesto.verificar_corte(carpeta)
        self.assertIn("srtm_predio.parquet", str(error.exception))

        salida_std, error_std = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(salida_std), contextlib.redirect_stderr(error_std):
            self.assertNotEqual(corte.main(["--verificar", str(carpeta)]), 0)
        self.assertIn("srtm_predio.parquet", error_std.getvalue())

    def test_verificar_detecta_faltantes_y_sobrantes(self):
        carpeta = self._corte(minimos())
        (carpeta / "catastro_lote.parquet").unlink()
        with self.assertRaises(ErrorCorte) as error:
            manifiesto.verificar_corte(carpeta)
        self.assertIn("catastro_lote.parquet", str(error.exception))

        carpeta = self._corte(minimos(), fecha="2026-10-24")
        (carpeta / "extra.parquet").write_bytes(b"")
        with self.assertRaises(ErrorCorte) as error:
            manifiesto.verificar_corte(carpeta)
        self.assertIn("extra.parquet", str(error.exception))

    def test_timeout_del_archivo_de_secretos(self):
        servidores = self._falsos(minimos())
        secretos = comun.leer_secretos(self._secretos(*servidores, extra="SRTM_TIMEOUT=5\nCATASTRO_TIMEOUT=120.5\n"))
        self.assertEqual(corte._cliente(secretos, "SRTM").timeout, 5.0)
        self.assertEqual(corte._cliente(secretos, "CATASTRO").timeout, 120.5)
        sin_timeout = comun.leer_secretos(self._secretos(*servidores))
        self.assertEqual(corte._cliente(sin_timeout, "SRTM").timeout, 60)

    def test_timeout_invalido_sale_con_2(self):
        servidores = self._falsos(minimos())
        salida = self.tmp / "cortes"
        for valor in ("abc", "0", "-3"):
            with self.subTest(valor=valor):
                secretos = self._secretos(*servidores, extra=f"CATASTRO_TIMEOUT={valor}\n")
                codigo, _, error = self._correr(secretos, salida)
                self.assertEqual(codigo, 2)
                self.assertIn("CATASTRO_TIMEOUT", error)
                self.assertFalse((salida / ORG / FECHA).exists())

    def test_version_de_backends_del_entorno(self):
        os.environ["VERSION_SRTM"] = "2.0.0"
        m = self._manifiesto(self._corte(minimos()))
        self.assertEqual(m["backends"]["srtm"]["version"], "2.0.0")
        self.assertIsNone(m["backends"]["catastro"]["version"])
        self.assertTrue(any("VERSION_CATASTRO" in aviso for aviso in m["avisos"]))
        self.assertFalse(any("VERSION_SRTM" in aviso for aviso in m["avisos"]))


if __name__ == "__main__":
    unittest.main()
