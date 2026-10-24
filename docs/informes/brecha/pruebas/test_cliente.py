"""The HTTP client against the fake srtm/catastro servers (contexto.md: cliente no reintenta salvo un 401)."""

import unittest
from decimal import Decimal

from herramientas.cliente import Cliente, ErrorApi
from herramientas.falsos import CatastroFalso, SrtmFalso


def _predio(i):
    return {"id": f"predio-{i}", "codigo": f"P{i:04d}"}


def _cliente(servidor, client_secret=None):
    return Cliente(servidor.url, servidor.CLIENT_ID, client_secret or servidor.CLIENT_SECRET)


class RegistrosTests(unittest.TestCase):
    def test_registros_sigue_el_cursor(self):
        predios = [_predio(i) for i in range(450)]
        servidor = SrtmFalso({"predio": predios})
        try:
            vistos = list(_cliente(servidor).registros("predio"))
            de_registros = [p for p in servidor.peticiones if p[0] == "GET"]
        finally:
            servidor.cerrar()
        self.assertEqual(len({fila["id"] for fila in vistos}), 450)
        self.assertEqual(len(de_registros), 3)
        self.assertNotIn("after=", de_registros[0][1])
        self.assertTrue(all("after=" in ruta for _, ruta, _ in de_registros[1:]))
        self.assertFalse(any("page=" in ruta for _, ruta, _ in de_registros))

    def test_registros_pasa_los_filtros(self):
        servidor = SrtmFalso({"declaracion_predial": [{"id": "d1", "anio": 2026}]})
        try:
            list(_cliente(servidor).registros("declaracion_predial", anio=2026))
            ruta = next(ruta for metodo, ruta, _ in servidor.peticiones if metodo == "GET")
        finally:
            servidor.cerrar()
        self.assertIn("anio=2026", ruta)

    def test_reautentica_ante_un_401(self):
        predios = [_predio(i) for i in range(450)]
        servidor = SrtmFalso({"predio": predios})
        try:
            cliente = _cliente(servidor)
            iterador = cliente.registros("predio")
            vistos = [next(iterador) for _ in range(200)]  # exactly srtm's first page (size=200)
            servidor.expirar_token()
            vistos.extend(iterador)
            autenticaciones = [p for p in servidor.peticiones if p[0] == "POST" and p[1] == "/api/auth/token"]
        finally:
            servidor.cerrar()
        self.assertEqual(len({fila["id"] for fila in vistos}), 450)
        self.assertEqual(len(autenticaciones), 2)

    def test_un_401_repetido_es_error(self):
        servidor = SrtmFalso({"predio": [_predio(0)]})
        try:
            cliente = _cliente(servidor, client_secret="mal-secreto")
            with self.assertRaises(ErrorApi) as contexto:
                list(cliente.registros("predio"))
        finally:
            servidor.cerrar()
        self.assertEqual(contexto.exception.estado, 401)

    def test_error_no_muestra_secretos(self):
        servidor = SrtmFalso({"predio": [_predio(0)]})
        try:
            cliente = _cliente(servidor)
            with self.assertRaises(ErrorApi) as contexto:
                list(cliente.registros("objeto_desconocido"))
        finally:
            servidor.cerrar()
        mensaje = str(contexto.exception)
        self.assertNotIn(servidor.CLIENT_SECRET, mensaje)
        self.assertNotIn("Bearer", mensaje)

    def test_decimales_exactos(self):
        servidor = SrtmFalso({"cuota_predial": [{"id": "c1", "impuesto_anual": 0.1}]})
        try:
            fila = next(_cliente(servidor).registros("cuota_predial"))
        finally:
            servidor.cerrar()
        self.assertEqual(fila["impuesto_anual"], Decimal("0.1"))
        self.assertIsInstance(fila["impuesto_anual"], Decimal)


class PaginasTests(unittest.TestCase):
    def test_paginas_hasta_la_ultima(self):
        valuaciones = [{"codigoMunicipal": f"V{i:04d}"} for i in range(1001)]
        servidor = CatastroFalso({}, valuaciones, [], [])
        try:
            cliente = _cliente(servidor)
            items = list(cliente.paginas("/api/catastro/valuaciones", 500, anio=2026))
            peticiones = [p for p in servidor.peticiones if p[0] == "GET"]
        finally:
            servidor.cerrar()
        self.assertEqual(len(items), 1001)
        self.assertEqual(len(peticiones), 3)
        self.assertEqual(cliente.ultima_pagina.get("version"), "valuacion-v3")
        self.assertEqual(cliente.ultima_pagina.get("region"), "COSTA")

    def test_paginas_con_size_fuera_de_rango_es_422(self):
        # contratos.md §2: titulares/valuaciones paginan con size 1..500, con los mismos errores (422)
        servidor = CatastroFalso({}, [], [], [])
        try:
            with self.assertRaises(ErrorApi) as contexto:
                list(_cliente(servidor).paginas("/api/catastro/titulares", 501))
        finally:
            servidor.cerrar()
        self.assertEqual(contexto.exception.estado, 422)


class FeedTests(unittest.TestCase):
    def test_feed_sigue_hayMas(self):
        cambios = [{"codigoMunicipal": f"C{i:04d}"} for i in range(450)]
        servidor = CatastroFalso({}, [], [], cambios)
        try:
            items = list(_cliente(servidor).feed("/api/catastro/cambios", 200))
            peticiones = [p for p in servidor.peticiones if p[0] == "GET"]
        finally:
            servidor.cerrar()
        self.assertEqual(items, cambios)
        self.assertEqual(len(peticiones), 3)
        self.assertNotIn("desde=", peticiones[0][1])
        self.assertTrue(all("desde=" in ruta for _, ruta, _ in peticiones[1:]))

    def test_feed_con_desde_vacio_es_422(self):
        # contratos.md §3: un desde vacío no es "desde el principio", es un cursor inválido
        servidor = CatastroFalso({}, [], [], [])
        try:
            with self.assertRaises(ErrorApi) as contexto:
                _cliente(servidor).get("/api/catastro/cambios", desde="", size=200)
        finally:
            servidor.cerrar()
        self.assertEqual(contexto.exception.estado, 422)

    def test_feed_con_size_fuera_de_rango_es_422(self):
        # contratos.md §3: cambios pagina con size 1..500
        servidor = CatastroFalso({}, [], [], [])
        try:
            with self.assertRaises(ErrorApi) as contexto:
                list(_cliente(servidor).feed("/api/catastro/cambios", 0))
        finally:
            servidor.cerrar()
        self.assertEqual(contexto.exception.estado, 422)


class GetTests(unittest.TestCase):
    def test_get_404_es_none(self):
        servidor = SrtmFalso({"predio": [_predio(0)]})
        try:
            resultado = _cliente(servidor).get("/no/existe")
        finally:
            servidor.cerrar()
        self.assertIsNone(resultado)


if __name__ == "__main__":
    unittest.main()
