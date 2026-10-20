"""Guardia de los scripts de la demostración (docs/manuales/demo): solo hablan con el Core de la demo.

El Core de la demo escucha en el 8098 (demo.env). El 8090 es el de desarrollo, con la base compartida de dev: ningún
script de la demo lo llama, ni con --permitir-otro-core. Otro puerto solo con --permitir-otro-core, a propósito.

Solo stdlib.
"""
import sys
import urllib.parse

PUERTO_DEMO = 8098
# los servidores y las bases compartidas de dev: nunca, con ningún flag
PUERTOS_PROHIBIDOS = {8090, 5433, 5434, 5435}


class CoreNoPermitido(Exception):
    pass


def puerto_de(url):
    partes = urllib.parse.urlparse(url)
    if partes.scheme not in ("http", "https") or not partes.hostname:
        raise CoreNoPermitido(f"--core no es una url http(s): {url!r}")
    return partes.port or (443 if partes.scheme == "https" else 80)


def revisar_core(url, permitir_otro=False):
    """La url si es la del Core de la demo; si no, CoreNoPermitido con el motivo."""
    puerto = puerto_de(url)
    if puerto in PUERTOS_PROHIBIDOS:
        raise CoreNoPermitido(f"{url}: el puerto {puerto} es de desarrollo (compartido); la demo nunca lo llama")
    if puerto != PUERTO_DEMO and not permitir_otro:
        raise CoreNoPermitido(f"{url}: el Core de la demo escucha en el {PUERTO_DEMO}; para otro, --permitir-otro-core")
    return url.rstrip("/")


def exigir_core_demo(url, permitir_otro=False):
    """revisar_core, o sale con 2 y el motivo en stderr, sin haber llamado a nada."""
    try:
        return revisar_core(url, permitir_otro)
    except CoreNoPermitido as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(2)
