"""Lo que comparten los scripts del informe: parámetros, carpetas de la edición y escritura de JSON y CSV.

Una edición es una carpeta fuera del repositorio (el informe lleva agregados del padrón): datos/, generado/, textos/
y salida/. Los scripts solo leen las fuentes; nunca escriben en una base de datos ni en el padrón.
"""

import csv
import hashlib
import json
import os
import pathlib
import sys

INFORME = pathlib.Path(__file__).resolve().parent.parent
REPO = INFORME.parent.parent.parent


def parametros():
    return json.loads((INFORME / "parametros.json").read_text(encoding="utf-8"))


def edicion():
    ruta = os.environ.get("EDICION")
    if not ruta:
        sys.exit("falta EDICION: la carpeta de la edición (por ejemplo, make EDICION=~/informes/2026-10-18 ...)")
    carpeta = pathlib.Path(ruta).expanduser().resolve()
    # una edición dentro del repositorio publicaría los agregados del padrón
    if carpeta == REPO or REPO in carpeta.parents:
        sys.exit(f"la edición no puede estar dentro del repositorio: {carpeta}")
    return carpeta


def escribir_json(ruta, datos):
    ruta.parent.mkdir(parents=True, exist_ok=True)
    ruta.write_text(json.dumps(datos, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def escribir_csv(ruta, columnas, filas):
    ruta.parent.mkdir(parents=True, exist_ok=True)
    with ruta.open("w", newline="", encoding="utf-8") as salida:
        escritor = csv.writer(salida)
        escritor.writerow(columnas)
        escritor.writerows(filas)


def sha256(ruta):
    suma = hashlib.sha256()
    with open(ruta, "rb") as entrada:
        for bloque in iter(lambda: entrada.read(1 << 20), b""):
            suma.update(bloque)
    return suma.hexdigest()
