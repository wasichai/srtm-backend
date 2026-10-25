"""Normalization and pseudonymization of document numbers (HMAC-SHA256; plan:
docs/superpowers/plans/2026-10-25-corte-analitico.md, § Rulings)."""

import unittest

from herramientas.comun import ErrorCorte
from herramientas.seudonimo import clave_desde_hex, huella_clave, normalizar_documento, seudonimo


class NormalizarDocumentoTests(unittest.TestCase):
    def test_normaliza_documentos(self):
        casos = [
            ("  04587963 ", "4587963"),
            ("00000000", None),
            ("ab-12 3", "AB123"),
            ("", None),
            (None, None),
            ("0045", "45"),
        ]
        for crudo, esperado in casos:
            with self.subTest(crudo=crudo):
                self.assertEqual(normalizar_documento(crudo), esperado)


class SeudonimoTests(unittest.TestCase):
    def test_mismo_documento_mismo_seudonimo(self):
        k = bytes(range(32))
        self.assertEqual(seudonimo(k, "04587963"), seudonimo(k, "4587963"))
        self.assertRegex(seudonimo(k, "4587963"), r"^[0-9a-f]{64}$")
        self.assertNotEqual(seudonimo(k, "4587963"), seudonimo(bytes(32), "4587963"))
        self.assertIsNone(seudonimo(k, "000"))


class ClaveDesdeHexTests(unittest.TestCase):
    def test_clave_de_al_menos_32_bytes(self):
        with self.assertRaises(ErrorCorte):
            clave_desde_hex("ab" * 31)
        with self.assertRaises(ErrorCorte):
            clave_desde_hex("zz" * 32)
        self.assertEqual(len(clave_desde_hex("ab" * 32)), 32)


class HuellaClaveTests(unittest.TestCase):
    def test_huella_no_revela_la_clave(self):
        k = bytes(range(32))
        self.assertEqual(len(huella_clave(k)), 12)
        self.assertNotIn(huella_clave(k), k.hex())


if __name__ == "__main__":
    unittest.main()
