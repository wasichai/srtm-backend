"""tablas.ESQUEMAS against srtm's model: the types are written by hand in tablas.py (a byte change in the cortes
must be a code change), so this test is what catches a drift from model/model.json."""

import json
import unittest

import pyarrow as pa

from herramientas import comun
from herramientas.tablas import COLUMNAS, DECIMAL, ENTERO, ESQUEMAS, FECHA, INSTANTE, LOGICO, ORDEN, TEXTO

# the srtm object each srtm_* table is read from (srtm.py)
OBJETO_SRTM = {
    "srtm_predio": "predio",
    "srtm_contribuyente": "contribuyente",
    "srtm_declaracion": "declaracion_predial",
    "srtm_nivel": "nivel_construccion",
    "srtm_obra": "obra_complementaria",
    "srtm_impuesto_contribuyente": "cuota_predial",
    "srtm_brecha_corrida": "brecha_corrida",
    "srtm_brecha_predio": "brecha_predio",
    "srtm_parametro": "parametro_tributario",
    "srtm_arancel": "arancel",
}

# contexto.md § Tablas: model type -> Arrow; every other model type (TEXT, ENUM, UUID, LONG_TEXT...) is a string
POR_TIPO_DEL_MODELO = {"DECIMAL": DECIMAL, "INTEGER": ENTERO, "BOOLEAN": LOGICO, "DATE": FECHA, "DATETIME": INSTANTE}


class EsquemasTests(unittest.TestCase):
    def test_tipos_de_srtm_coinciden_con_model_json(self):
        modelo = json.loads((comun.REPO / "model" / "model.json").read_text(encoding="utf-8"))
        objetos = {objeto["name"]: objeto for objeto in modelo["objects"]}
        revisadas = 0
        for tabla, nombre in OBJETO_SRTM.items():
            campos = {campo["name"]: campo["type"] for campo in objetos[nombre]["fields"]}
            for campo in ESQUEMAS[tabla]:
                # columns the model does not carry (relations, R6, #204, aggregates, the pseudonym) are skipped
                if campo.name not in campos:
                    continue
                revisadas += 1
                with self.subTest(tabla=tabla, columna=campo.name):
                    self.assertEqual(campo.type, POR_TIPO_DEL_MODELO.get(campos[campo.name], TEXTO))
        self.assertGreater(revisadas, 100)

    def test_diecisiete_tablas_con_sus_columnas_y_orden(self):
        self.assertEqual(len(ESQUEMAS), 17)
        for tabla, esquema in ESQUEMAS.items():
            with self.subTest(tabla=tabla):
                self.assertEqual(tuple(esquema.names), COLUMNAS[tabla])
                self.assertTrue(set(ORDEN[tabla]) <= set(COLUMNAS[tabla]))

    def test_piso_y_seccion_de_catastro_son_texto(self):
        esquema = ESQUEMAS["catastro_construccion"]
        self.assertEqual(esquema.field("piso").type, pa.string())
        self.assertEqual(esquema.field("seccion").type, pa.string())


if __name__ == "__main__":
    unittest.main()
