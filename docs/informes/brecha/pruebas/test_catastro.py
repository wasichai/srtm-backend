"""catastro extraction for the corte, against `CatastroFalso(minimos().catastro, ...)` (sinteticos.py's fixed
fixture) and a couple of variants of it for the two shapes an older catastro-backend can serve (contratos.md
§1-§3: missing /titulares or /cambios, or missing §1's seven new valuaciones keys)."""

import unittest

from herramientas import sinteticos
from herramientas.catastro import COLUMNAS, centroide, extraer_catastro
from herramientas.cliente import Cliente
from herramientas.falsos import CatastroFalso
from herramientas.sinteticos import DOCUMENTO_C1, DOCUMENTO_C2, minimos
from herramientas.seudonimo import seudonimo

CLAVE = bytes(range(32))
ANIO = 2026


def _cliente(servidor):
    return Cliente(servidor.url, servidor.CLIENT_ID, servidor.CLIENT_SECRET)


def _extraer(s, anio=ANIO, clave=CLAVE, **kwargs):
    servidor = CatastroFalso(
        registros=s.catastro, valuaciones=s.valuaciones, titulares=s.titulares, cambios=s.cambios, **kwargs
    )
    try:
        return extraer_catastro(_cliente(servidor), anio, clave)
    finally:
        servidor.cerrar()


def _por_id(filas):
    return {fila["id"]: fila for fila in filas}


def _por_codigo(filas):
    return {fila["codigo_municipal"]: fila for fila in filas}


class ExtraccionMinimosTests(unittest.TestCase):
    """One extraction of minimos(), shared read-only by every test (extraer_catastro has no side effect)."""

    @classmethod
    def setUpClass(cls):
        cls.ext = _extraer(minimos())

    def test_construcciones_llevan_el_codigo_de_su_ficha(self):
        por_id = _por_id(self.ext.tablas["catastro_construccion"])
        for id_ in ("CC1", "CC2", "CC3"):
            with self.subTest(id_=id_):
                self.assertEqual(por_id[id_]["codigo_municipal"], "0101001")
        self.assertIsNone(por_id["CC6"]["codigo_municipal"])  # predio huérfano: ninguna ficha lo referencia

    def test_obras_llevan_el_codigo_de_su_ficha(self):
        por_id = _por_id(self.ext.tablas["catastro_obra"])
        self.assertEqual(por_id["CO1"]["codigo_municipal"], "0101001")

    def test_titulares_seudonimizados_sin_nombre(self):
        filas = self.ext.tablas["catastro_titular"]
        self.assertEqual(len(filas), 2)
        for fila in filas:
            self.assertEqual(set(fila), set(COLUMNAS["catastro_titular"]))
            self.assertNotIn("nombre", fila)
        por_codigo = _por_codigo(filas)
        self.assertEqual(por_codigo["0101001"]["documento_hmac"], seudonimo(CLAVE, "04587963"))
        self.assertEqual(por_codigo["0101001"]["documento_hmac"], seudonimo(CLAVE, DOCUMENTO_C1))
        self.assertEqual(por_codigo["0101002"]["documento_hmac"], seudonimo(CLAVE, DOCUMENTO_C2))

    def test_valuaciones_con_las_llaves_del_contrato(self):
        filas = self.ext.tablas["catastro_valuacion"]
        por_codigo = _por_codigo(filas)
        for fila in filas:
            self.assertEqual(set(fila), set(COLUMNAS["catastro_valuacion"]))
        f3 = por_codigo["0101003"]
        self.assertIsNone(f3["autoavaluo"])
        self.assertEqual(f3["motivo"], "SIN_LEVANTAMIENTO")
        self.assertEqual(f3["estado_levantamiento"], "SIN_LEVANTAR")
        self.assertEqual(self.ext.motor, {"version": "valuacion-v3", "region": "COSTA"})

    def test_cambios_del_feed(self):
        filas = self.ext.tablas["catastro_cambio"]
        self.assertEqual(len(filas), 3)
        for fila in filas:
            self.assertEqual(set(fila), set(COLUMNAS["catastro_cambio"]))
        de_f2 = next(f for f in filas if f["codigo_municipal"] == "0101002")
        self.assertEqual(de_f2["campos"], "area_terreno,uso")  # ordenado alfabéticamente, no como llegó

    def test_lote_con_centroide(self):
        filas = self.ext.tablas["catastro_lote"]
        self.assertEqual({f["codigo_municipal"] for f in filas}, {"0101001", "0101002"})  # F3 sin geometría
        f1 = _por_codigo(filas)["0101001"]
        self.assertAlmostEqual(f1["lon"], -75.7299, places=6)
        self.assertAlmostEqual(f1["lat"], -14.0599, places=6)
        self.assertIsInstance(f1["lon"], float)
        self.assertIsInstance(f1["lat"], float)
        self.assertIn('"type":"Polygon"', f1["geojson"])

    def test_sin_aviso_cuando_todo_esta_disponible(self):
        self.assertEqual(self.ext.avisos, [])


class CatastroViejoTests(unittest.TestCase):
    def test_catastro_viejo_sin_llaves_nuevas(self):
        ext = _extraer(minimos(), sin_llaves_nuevas=True)
        filas = ext.tablas["catastro_valuacion"]
        self.assertTrue(filas)
        for fila in filas:
            self.assertIsNone(fila["vinculo_metodo"])
            self.assertIsNone(fila["cuc"])
            self.assertIsNone(fila["entrega"])
        self.assertEqual(ext.avisos, [])  # faltar esas llaves no es un 404: no hay aviso


class MotorSinVersionTests(unittest.TestCase):
    def test_aviso_sin_version_ni_region(self):
        ext = _extraer(minimos(), version=None, region=None)
        self.assertEqual(ext.motor, {"version": None, "region": None})
        self.assertIn("catastro sin version en /api/catastro/valuaciones: motor.version es null", ext.avisos)
        self.assertIn("catastro sin region en /api/catastro/valuaciones: motor.region es null", ext.avisos)

    def test_aviso_solo_sin_region(self):
        ext = _extraer(minimos(), region=None)
        self.assertEqual(ext.avisos, ["catastro sin region en /api/catastro/valuaciones: motor.region es null"])


class SinTitularesNiCambiosTests(unittest.TestCase):
    def test_sin_titulares_ni_cambios(self):
        ext = _extraer(minimos(), sin_titulares=True, sin_cambios=True)
        self.assertEqual(ext.tablas["catastro_titular"], [])
        self.assertEqual(ext.tablas["catastro_cambio"], [])
        self.assertTrue(any("catastro-backend#25" in aviso for aviso in ext.avisos))
        self.assertTrue(any("catastro-backend#26" in aviso for aviso in ext.avisos))


class CentroideTests(unittest.TestCase):
    def test_centroide_de_polygon(self):
        anillo = [
            [-75.73, -14.06],
            [-75.7298, -14.06],
            [-75.7298, -14.0598],
            [-75.73, -14.0598],
            [-75.73, -14.06],
        ]
        geometria = {"type": "Polygon", "coordinates": [anillo]}
        lon, lat = centroide(geometria)
        self.assertAlmostEqual(lon, -75.7299, places=6)
        self.assertAlmostEqual(lat, -14.0599, places=6)

    def test_centroide_de_multipolygon(self):
        chico = [[-75.70, -14.00], [-75.699, -14.00], [-75.699, -13.999], [-75.70, -13.999], [-75.70, -14.00]]
        grande = [[-75.80, -14.10], [-75.70, -14.10], [-75.70, -14.00], [-75.80, -14.00], [-75.80, -14.10]]
        geometria = {"type": "MultiPolygon", "coordinates": [[chico], [grande]]}
        lon, lat = centroide(geometria)
        self.assertAlmostEqual(lon, -75.75, places=6)  # el centro del polígono grande, no del chico
        self.assertAlmostEqual(lat, -14.05, places=6)

    def test_centroide_de_none_es_none(self):
        self.assertIsNone(centroide(None))


if __name__ == "__main__":
    unittest.main()
