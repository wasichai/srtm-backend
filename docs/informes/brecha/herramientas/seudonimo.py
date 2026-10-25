"""Normalization and pseudonymization of document numbers (contexto.md: HMAC-SHA256, without the document type).

The HMAC is computed over the normalized number, never over the raw document or together with its type: srtm and
catastro write the type differently, and #204 already compares documents without leading zeros.
"""

import hashlib
import hmac
import re

from .comun import ErrorCorte

_NO_ALFANUMERICO = re.compile(r"[^0-9A-Z]")

# the rule normalizar_documento applies, in words, for the corte's manifest (contexto.md HMAC ruling)
NORMALIZACION = "sin espacios, en mayúsculas, solo [0-9A-Z], sin ceros a la izquierda; vacío -> null"


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
    # a short fingerprint of the key for the manifest: shows whether two cortes share a key, without exposing it
    return hashlib.sha256(b"srtm-analitica-clave:" + clave).hexdigest()[:12]
