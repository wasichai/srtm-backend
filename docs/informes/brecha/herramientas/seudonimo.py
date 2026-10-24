"""Normalización y seudonimización de números de documento (contexto.md: HMAC-SHA256, sin el tipo de documento).

El HMAC se calcula sobre el número normalizado, nunca sobre el documento crudo ni junto con su tipo: srtm y catastro
escriben el tipo distinto, y #204 ya compara documentos sin ceros a la izquierda.
"""

import hashlib
import hmac
import re

from .comun import ErrorCorte

_NO_ALFANUMERICO = re.compile(r"[^0-9A-Z]")


def normalizar_documento(numero):
    if numero is None:
        return None
    limpio = _NO_ALFANUMERICO.sub("", numero.upper()).lstrip("0")
    return limpio or None


def clave_desde_hex(texto):
    try:
        clave = bytes.fromhex(texto)
    except ValueError as e:
        raise ErrorCorte(f"la clave no es hexadecimal válida: {e}") from e
    if len(clave) < 32:
        raise ErrorCorte(f"la clave debe tener al menos 32 bytes (tiene {len(clave)})")
    return clave


def seudonimo(clave, numero):
    normalizado = normalizar_documento(numero)
    if normalizado is None:
        return None
    return hmac.new(clave, normalizado.encode("ascii"), hashlib.sha256).hexdigest()


def huella_clave(clave):
    # una huella corta de la clave para el manifiesto: ve si dos cortes comparten clave, sin exponerla
    return hashlib.sha256(b"srtm-analitica-clave:" + clave).hexdigest()[:12]
