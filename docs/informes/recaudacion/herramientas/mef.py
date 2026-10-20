"""Descarga la ejecución de ingresos del Portal de Datos Abiertos del MEF y guarda solo las filas de la municipalidad.

Uso: EDICION=<carpeta> python3 -I herramientas/mef.py [--sin-descargar]

Por cada fuente de parametros.json baja el zip a datos/mef/crudos/ (con --sin-descargar usa el que ya está), lee su
CSV en streaming y escribe datos/mef/<nombre>.csv con las filas cuya ejecutora es la de la municipalidad. El
manifiesto datos/mef/manifiesto.json guarda la URL, la fecha de la fuente (Last-Modified), el tamaño, la suma
SHA-256 del zip y las filas leídas y guardadas: con él se comprueba de dónde salió cada cifra.
"""

import csv
import datetime
import io
import os
import sys
import urllib.request
import zipfile

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from comun import edicion, escribir_json, parametros, sha256  # noqa: E402

# el portal rechaza los clientes sin user-agent de navegador
CABECERAS = {"User-Agent": "Mozilla/5.0 (informe-recaudacion; solo lectura)"}


def descargar(url, destino):
    destino.parent.mkdir(parents=True, exist_ok=True)
    pedido = urllib.request.Request(url, headers=CABECERAS)
    with urllib.request.urlopen(pedido, timeout=300) as respuesta, open(destino, "wb") as salida:
        modificado = respuesta.headers.get("Last-Modified")
        while bloque := respuesta.read(1 << 20):
            salida.write(bloque)
    return modificado


def filtrar(zip_ruta, columna, ejecutora, destino):
    with zipfile.ZipFile(zip_ruta) as archivo:
        nombres = [n for n in archivo.namelist() if n.lower().endswith(".csv")]
        if len(nombres) != 1:
            sys.exit(f"{zip_ruta.name}: se esperaba un CSV y hay {nombres}")
        info = archivo.getinfo(nombres[0])
        leidas = guardadas = 0
        with archivo.open(info) as entrada, destino.open("w", newline="", encoding="utf-8") as salida:
            lector = csv.reader(io.TextIOWrapper(entrada, encoding="utf-8", errors="strict", newline=""))
            cabecera = next(lector)
            indice = cabecera.index(columna)
            escritor = csv.writer(salida, quoting=csv.QUOTE_ALL)
            escritor.writerow(cabecera)
            for fila in lector:
                leidas += 1
                if fila[indice].strip() == ejecutora:
                    escritor.writerow(fila)
                    guardadas += 1
    fecha_csv = datetime.datetime(*info.date_time).isoformat()
    return {"csv": info.filename, "fecha_csv_en_zip": fecha_csv, "filas_leidas": leidas, "filas_guardadas": guardadas}


def main():
    sin_descargar = "--sin-descargar" in sys.argv[1:]
    p = parametros()
    carpeta = edicion() / "datos" / "mef"
    ejecutora = p["municipalidad"]["sec_ejec"]
    manifiesto = {"municipalidad": p["municipalidad"], "generado": datetime.datetime.now().isoformat(timespec="seconds"),
                  "fuentes": []}
    for fuente in p["fuentes_mef"]:
        zip_ruta = carpeta / "crudos" / fuente["url"].rsplit("/", 1)[1]
        modificado = None
        if not (sin_descargar and zip_ruta.exists()):
            print(f"descargando {fuente['url']}")
            modificado = descargar(fuente["url"], zip_ruta)
        destino = carpeta / f"{fuente['nombre']}.csv"
        filtro = filtrar(zip_ruta, fuente["columna_ejecutora"], ejecutora, destino)
        if filtro["filas_guardadas"] == 0:
            sys.exit(f"{fuente['nombre']}: ninguna fila de la ejecutora {ejecutora}")
        manifiesto["fuentes"].append({
            **fuente,
            "last_modified": modificado,
            "zip": zip_ruta.name,
            "bytes": zip_ruta.stat().st_size,
            "sha256": sha256(zip_ruta),
            "filtrado": destino.name,
            **filtro,
        })
        print(f"{fuente['nombre']}: {filtro['filas_guardadas']} de {filtro['filas_leidas']} filas")
    escribir_json(carpeta / "manifiesto.json", manifiesto)


if __name__ == "__main__":
    main()
