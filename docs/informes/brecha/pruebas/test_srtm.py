"""srtm extraction for the corte, against `SrtmFalso(minimos().srtm)` (task-3-brief.md's fixed fixture) and a
couple of trimmed variants of it for the two scenarios minimos() alone cannot show at once (no corrida
TERMINADA; a corrida missing controller ruling R6's seven columns)."""

import unittest
from decimal import Decimal

from herramientas import sinteticos
from herramientas.cliente import Cliente
from herramientas.falsos import CatastroFalso, SrtmFalso
from herramientas.seudonimo import seudonimo
from herramientas.sinteticos import (
    BP1,
    BP3,
    C1,
    C2,
    D1,
    D2,
    D4,
    DOCUMENTO_C1,
    DOCUMENTO_C2,
    F1,
    F2,
    F3,
    N1,
    O1,
    P1,
    P2,
    R2,
    minimos,
)
from herramientas.srtm import COLUMNAS, extraer_srtm

CLAVE = bytes(range(32))
ANIO = 2026


def _cliente(servidor):
    return Cliente(servidor.url, servidor.CLIENT_ID, servidor.CLIENT_SECRET)


def _extraer(srtm_registros, anio=ANIO, clave=CLAVE):
    servidor = SrtmFalso(srtm_registros)
    try:
        return extraer_srtm(_cliente(servidor), anio, clave)
    finally:
        servidor.cerrar()


def _por_id(filas):
    return {fila["id"]: fila for fila in filas}


class ExtraccionMinimosTests(unittest.TestCase):
    """One extraction of minimos(), shared read-only by every test (extraer_srtm has no side effect)."""

    @classmethod
    def setUpClass(cls):
        cls.ext = _extraer(minimos().srtm)

    def test_contribuyentes_seudonimizados_y_sin_nombres(self):
        filas = self.ext.tablas["srtm_contribuyente"]
        self.assertEqual({f["id"] for f in filas}, {C1, C2})  # C3 no sale: ni DJ ni cuota de 2026
        self.assertEqual(set(filas[0]), set(COLUMNAS["srtm_contribuyente"]))
        por_id = _por_id(filas)
        self.assertEqual(por_id[C1]["documento_hmac"], seudonimo(CLAVE, DOCUMENTO_C1))
        self.assertEqual(por_id[C2]["documento_hmac"], seudonimo(CLAVE, DOCUMENTO_C2))

    def test_declaraciones_del_anio_con_lista_blanca(self):
        filas = self.ext.tablas["srtm_declaracion"]
        self.assertEqual({f["id"] for f in filas}, {D1, D2, D4})  # D3 es de 2025
        for fila in filas:
            self.assertEqual(set(fila), set(COLUMNAS["srtm_declaracion"]))
            self.assertNotIn("condicion_numero_documento", fila)
            self.assertNotIn("otros_datos", fila)

    def test_niveles_y_obras_solo_de_las_dj_del_anio(self):
        self.assertEqual({f["id"] for f in self.ext.tablas["srtm_nivel"]}, {N1})  # N2 es de D3 (2025)
        self.assertEqual({f["id"] for f in self.ext.tablas["srtm_obra"]}, {O1})

    def test_impuesto_toma_la_ultima_revision(self):
        por_contribuyente = {f["contribuyente"]: f for f in self.ext.tablas["srtm_impuesto_contribuyente"]}
        self.assertEqual(por_contribuyente[C1]["impuesto_anual"], Decimal("285.00"))
        self.assertEqual(por_contribuyente[C1]["cuotas"], 4)
        self.assertEqual(por_contribuyente[C1]["fecha_calculo"], "2026-03-01")
        self.assertEqual(por_contribuyente[C2]["impuesto_anual"], Decimal("2350.00"))

    def test_corrida_terminada_mas_reciente(self):
        self.assertEqual(self.ext.corrida["id"], R2)  # R2 (2026-10-20) es más reciente que R1 (2026-10-01)
        filas = self.ext.tablas["srtm_brecha_predio"]
        self.assertEqual(len(filas), 3)  # BP1, BP2, BP3 de R2; el BP0 de R1 queda fuera
        self.assertEqual({f["corrida"] for f in filas}, {R2})

    def test_sin_confianza_es_null_y_avisa(self):
        por_id = _por_id(self.ext.tablas["srtm_brecha_predio"])
        self.assertIsNone(por_id[BP3]["confianza"])  # BP3 no trae el atributo
        self.assertIsNone(por_id[BP3]["confianza_motivo"])
        self.assertEqual(por_id[BP1]["confianza"], "CONFIABLE")
        self.assertTrue(any("srtm-backend#204" in aviso for aviso in self.ext.avisos))

    def test_parametros_vigentes_al_primero_de_enero(self):
        filas = self.ext.tablas["srtm_parametro"]
        self.assertEqual({f["tipo"] for f in filas}, {"UIT", "TRAMO_PREDIAL", "TRAMO_PREDIAL_LIMITE", "PREDIAL_MINIMO"})
        uit = next(f for f in filas if f["tipo"] == "UIT")
        self.assertEqual(uit["valor_numerico"], 5500)
        self.assertNotIn("UIT-2026-ANULADO", {f["clave"] for f in filas})  # el ANULADO no sale

    def test_aranceles_vigentes_del_anio(self):
        filas = self.ext.tablas["srtm_arancel"]
        self.assertEqual(len(filas), 2)  # los dos VIGENTE de 2026; el POR_VERIFICAR y el de 2025 quedan fuera
        self.assertEqual({f["estado"] for f in filas}, {"VIGENTE"})

    def test_predios_no_se_minimizan(self):
        # a diferencia de contribuyente, predio no lleva datos personales: salen todos, citados o no
        self.assertEqual({f["id"] for f in self.ext.tablas["srtm_predio"]}, {P1, P2})

    def test_parametros_del_dataclass_es_la_tabla_srtm_parametro(self):
        self.assertIs(self.ext.parametros, self.ext.tablas["srtm_parametro"])

    def test_columnas_r6_pasan_tal_cual(self):
        # controller ruling R6: srtm_predio.codigo_origen y los 7 campos nuevos de srtm_brecha_corrida
        predios = _por_id(self.ext.tablas["srtm_predio"])
        self.assertEqual(predios[P1]["codigo_origen"], "0101001-1")
        self.assertIsNone(predios[P2]["codigo_origen"])  # P2 no trae el atributo

        corrida = self.ext.corrida
        self.assertEqual(corrida["origen"], "MANUAL")
        self.assertEqual(corrida["predios_confiable"], 1)
        self.assertEqual(corrida["predios_dudoso"], 1)
        self.assertEqual(corrida["predios_sin_levantar"], 0)
        self.assertEqual(corrida["delta_impuesto_confiable"], Decimal("15.00"))
        self.assertEqual(corrida["delta_impuesto_dudoso"], Decimal("-50.00"))
        self.assertEqual(corrida["delta_impuesto_sin_levantar"], Decimal("0.00"))


class SinCorridaTerminadaTests(unittest.TestCase):
    def test_sin_corrida_terminada(self):
        # minimos() sin R1 ni R2 (ambas TERMINADA): solo queda R3, EN_CURSO
        registros = dict(minimos().srtm)
        registros["brecha_corrida"] = [c for c in registros["brecha_corrida"] if c["attributes"]["estado"] != "TERMINADA"]
        ext = _extraer(registros)
        self.assertIsNone(ext.corrida)
        self.assertEqual(ext.tablas["srtm_brecha_corrida"], [])
        self.assertEqual(ext.tablas["srtm_brecha_predio"], [])
        self.assertIn(f"sin brecha TERMINADA de {ANIO}", ext.avisos)


class ColumnasR6AusentesTests(unittest.TestCase):
    def test_columnas_r6_son_null_si_la_corrida_no_las_trae(self):
        # un srtm sin la migración de R6 todavía: la corrida TERMINADA no trae esas 7 columnas
        registros = dict(minimos().srtm)
        registros["brecha_corrida"] = [
            {"id": "R9", "attributes": {"anio": ANIO, "estado": "TERMINADA", "terminado": "2026-01-01T00:00:00Z"}}
        ]
        registros["brecha_predio"] = []
        ext = _extraer(registros)
        self.assertEqual(ext.corrida["id"], "R9")
        for columna in (
            "origen",
            "predios_confiable",
            "predios_dudoso",
            "predios_sin_levantar",
            "delta_impuesto_confiable",
            "delta_impuesto_dudoso",
            "delta_impuesto_sin_levantar",
        ):
            with self.subTest(columna=columna):
                self.assertIsNone(ext.corrida[columna])


class SinteticosCatastroTests(unittest.TestCase):
    """minimos()'s catastro/valuaciones/titulares/cambios, read back through CatastroFalso exactly as task 4
    will consume them (contratos.md §1-§3, through Cliente)."""

    def test_catastro_es_compatible_con_catastro_falso(self):
        s = minimos()
        servidor = CatastroFalso(registros=s.catastro, valuaciones=s.valuaciones, titulares=s.titulares, cambios=s.cambios)
        try:
            cliente = _cliente(servidor)
            fichas = list(cliente.registros("predio_catastral"))
            valuaciones = list(cliente.paginas("/api/catastro/valuaciones", 500))
            titulares = list(cliente.paginas("/api/catastro/titulares", 500))
            cambios = list(cliente.feed("/api/catastro/cambios", 200))
        finally:
            servidor.cerrar()
        self.assertEqual({f["id"] for f in fichas}, {F1, F2, F3})
        self.assertEqual(len(valuaciones), 3)
        self.assertEqual(len(titulares), 2)
        self.assertEqual(len(cambios), 3)


class DatosTests(unittest.TestCase):
    """sinteticos.datos(7): controller ruling R2 (documents) plus a smoke run of the whole extraction."""

    def test_documentos_cumplen_la_regla_r2(self):
        documentos = [f["attributes"]["numero_documento"] for f in sinteticos.datos(7).srtm["contribuyente"]]
        self.assertEqual(len(documentos), len(set(documentos)))  # todos distintos
        for numero in documentos:
            self.assertTrue(numero.isdigit())
            if len(numero) == 11:
                self.assertNotEqual(numero[0], "0")  # RUC: primer dígito no cero
            else:
                self.assertEqual(len(numero), 8)  # DNI

    def test_documentos_distintos_de_cualquier_codigo_exportado(self):
        s = sinteticos.datos(7)
        documentos = {f["attributes"]["numero_documento"] for f in s.srtm["contribuyente"]}
        codigos = {f["attributes"]["codigo"] for f in s.srtm["predio"]}
        codigos |= {f["attributes"]["codigo_municipal"] for f in s.catastro["predio_catastral"]}
        self.assertTrue(documentos.isdisjoint(codigos))

    def test_es_reproducible_y_corre_una_extraccion_completa(self):
        self.assertEqual(sinteticos.datos(7).documentos, sinteticos.datos(7).documentos)
        ext = _extraer(sinteticos.datos(7).srtm)
        self.assertEqual(len(ext.tablas["srtm_contribuyente"]), 40)  # los 40 están citados por su propia DJ
        self.assertIsNotNone(ext.corrida)
        self.assertEqual(len(ext.tablas["srtm_brecha_predio"]), 40)


if __name__ == "__main__":
    unittest.main()
