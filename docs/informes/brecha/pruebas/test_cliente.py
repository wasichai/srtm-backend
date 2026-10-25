"""The HTTP client against the fake srtm/catastro servers (contexto.md: cliente no reintenta salvo un 401)."""

import unittest
from decimal import Decimal

from herramientas.cliente import Cliente, ErrorApi
from herramientas.falsos import CatastroFalso, Crudo, SrtmFalso


def _predio(i):
    return {"id": f"predio-{i}", "codigo": f"P{i:04d}"}


def _cliente(servidor, client_secret=None):
    return Cliente(servidor.url, servidor.CLIENT_ID, client_secret or servidor.CLIENT_SECRET)


class _ValuacionesSinTotal(CatastroFalso):
    """A catastro whose valuaciones pages carry no `total`: only a short page can end the pass."""

    def manejar(self, metodo, ruta, query, cuerpo):
        estado, datos = super().manejar(metodo, ruta, query, cuerpo)
        if ruta == "/api/catastro/valuaciones" and estado == 200:
            datos.pop("total")
        return estado, datos


class _ValuacionesQueSeCortan(CatastroFalso):
    """A catastro that answers an empty page 1 while `total` still promises more items."""

    def manejar(self, metodo, ruta, query, cuerpo):
        estado, datos = super().manejar(metodo, ruta, query, cuerpo)
        if ruta == "/api/catastro/valuaciones" and estado == 200 and datos["page"] >= 1:
            datos["items"] = []
        return estado, datos


class _SrtmCrudo(SrtmFalso):
    """A srtm whose records route answers 200 with `crudo` (a body that is not JSON, or a cut one)."""

    def __init__(self, crudo):
        super().__init__({})
        self.crudo = crudo

    def manejar(self, metodo, ruta, query, cuerpo):
        return 200, self.crudo


class _TokenSinToken(SrtmFalso):
    """A srtm whose /api/auth/token answers 200 without a `token`."""

    def _autenticar(self, cuerpo):
        return 200, {"detalle": "sin token"}


class _FeedAtascado(CatastroFalso):
    """A catastro whose cambios feed says hayMas with a cursor that never moves."""

    def manejar(self, metodo, ruta, query, cuerpo):
        if ruta == "/api/catastro/cambios":
            return 200, {"items": [{"codigoMunicipal": "C0001"}], "cursor": "fijo", "hayMas": True}
        return super().manejar(metodo, ruta, query, cuerpo)


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

    def test_respuesta_que_no_es_json_es_error_api(self):
        servidor = _SrtmCrudo(Crudo(b"<html>mantenimiento</html>"))
        try:
            with self.assertRaises(ErrorApi) as contexto:
                list(_cliente(servidor).registros("predio"))
        finally:
            servidor.cerrar()
        self.assertIn("JSON", str(contexto.exception))

    def test_respuesta_que_no_es_un_objeto_es_error_api(self):
        servidor = _SrtmCrudo(Crudo(b"[1, 2]"))
        try:
            with self.assertRaises(ErrorApi) as contexto:
                list(_cliente(servidor).registros("predio"))
        finally:
            servidor.cerrar()
        self.assertIn("objeto JSON", str(contexto.exception))

    def test_respuesta_cortada_es_error_api(self):
        servidor = _SrtmCrudo(Crudo(b'{"content": [', largo=1000))
        try:
            with self.assertRaises(ErrorApi) as contexto:
                list(_cliente(servidor).registros("predio"))
        finally:
            servidor.cerrar()
        self.assertIn("/api/objects/predio/records", str(contexto.exception))

    def test_token_sin_token_es_error_api(self):
        servidor = _TokenSinToken({"predio": [_predio(0)]})
        try:
            with self.assertRaises(ErrorApi) as contexto:
                list(_cliente(servidor).registros("predio"))
        finally:
            servidor.cerrar()
        self.assertIn("/api/auth/token", str(contexto.exception))
        self.assertNotIn(servidor.CLIENT_SECRET, str(contexto.exception))

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

    def test_paginas_con_size_recortado_por_el_servidor(self):
        # srtm's ClienteCatastro.kt: "the size catastro served (it may cap the one asked)"
        valuaciones = [{"codigoMunicipal": f"V{i:04d}"} for i in range(1200)]
        servidor = CatastroFalso({}, valuaciones, [], [], tamano_servido=200)
        try:
            items = list(_cliente(servidor).paginas("/api/catastro/valuaciones", 500, anio=2026))
            peticiones = [p for p in servidor.peticiones if p[0] == "GET"]
        finally:
            servidor.cerrar()
        self.assertEqual(len(items), 1200)
        self.assertEqual(len({item["codigoMunicipal"] for item in items}), 1200)
        self.assertEqual(len(peticiones), 6)

    def test_paginas_vacia_antes_del_total_es_error(self):
        valuaciones = [{"codigoMunicipal": f"V{i:04d}"} for i in range(1200)]
        servidor = _ValuacionesQueSeCortan({}, valuaciones, [], [])
        try:
            with self.assertRaises(ErrorApi) as contexto:
                list(_cliente(servidor).paginas("/api/catastro/valuaciones", 500, anio=2026))
        finally:
            servidor.cerrar()
        self.assertIn("500 de 1200", str(contexto.exception))

    def test_paginas_sin_total_para_en_la_pagina_corta(self):
        valuaciones = [{"codigoMunicipal": f"V{i:04d}"} for i in range(1001)]
        servidor = _ValuacionesSinTotal({}, valuaciones, [], [])
        try:
            items = list(_cliente(servidor).paginas("/api/catastro/valuaciones", 500, anio=2026))
            peticiones = [p for p in servidor.peticiones if p[0] == "GET"]
        finally:
            servidor.cerrar()
        self.assertEqual(len(items), 1001)
        self.assertEqual(len(peticiones), 3)

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

    def test_feed_con_cursor_que_no_avanza_es_error(self):
        servidor = _FeedAtascado({}, [], [], [])
        try:
            with self.assertRaises(ErrorApi) as contexto:
                list(_cliente(servidor).feed("/api/catastro/cambios", 200))
            peticiones = [p for p in servidor.peticiones if p[0] == "GET"]
        finally:
            servidor.cerrar()
        self.assertIn("cursor", str(contexto.exception))
        self.assertEqual(len(peticiones), 2)

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


class BarajarTests(unittest.TestCase):
    """`barajar` reorders what the corte must sort itself (task 5's two-cortes test relies on it)."""

    def _servidor(self, barajar):
        valuaciones = [{"codigoMunicipal": f"V{i:04d}"} for i in range(50)]
        titulares = [{"codigoMunicipal": f"T{i:04d}"} for i in range(50)]
        # equal `en` in pairs: only rows within a pair may move (cambios are oldest first by contract §3)
        cambios = [{"codigoMunicipal": f"C{i:04d}", "en": f"2026-10-{10 + i // 2:02d}T00:00:00Z"} for i in range(20)]
        return CatastroFalso({}, valuaciones, titulares, cambios, barajar=barajar)

    def _leer(self, barajar):
        servidor = self._servidor(barajar)
        try:
            cliente = _cliente(servidor)
            return (
                list(cliente.paginas("/api/catastro/valuaciones", 500, anio=2026)),
                list(cliente.paginas("/api/catastro/titulares", 500)),
                list(cliente.feed("/api/catastro/cambios", 200)),
            )
        finally:
            servidor.cerrar()

    def test_barajar_reordena_valuaciones_titulares_y_cambios_de_igual_en(self):
        en_orden, barajado = self._leer(None), self._leer(3)
        for original, otro in zip(en_orden, barajado):
            self.assertNotEqual(original, otro)
            self.assertCountEqual(original, otro)
        cambios = barajado[2]
        self.assertEqual([c["en"] for c in cambios], sorted(c["en"] for c in cambios))


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
