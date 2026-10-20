"""Recaudado mensual por rubro y totales anuales de la municipalidad, de la Consulta Amigable de Ingresos del MEF.

Uso: EDICION=<carpeta> python3 -I herramientas/consulta_amigable.py

Para los años históricos de parametros.json escribe datos/ca/recaudado-mensual-<año>.csv (anio, mes, rubro, rubro_nombre,
recaudado): los datos abiertos del MEF no traen esos años por mes. Para todos los años del informe escribe
datos/referencias.json con el total anual publicado, con el que indicadores.py concilia los archivos de datos abiertos.

La Consulta Amigable es una aplicación ASP.NET WebForms: se navega pulsando sus botones, reenviando __VIEWSTATE y
__EVENTVALIDATION (no se ejecuta nada de la página). La secuencia, para una municipalidad:

  Navegar.aspx?y=<año> → BtnTipoGobierno → «M: GOBIERNOS LOCALES» + BtnSubTipoGobierno → «M: MUNICIPALIDADES» +
  BtnDepartamento → <departamento> + BtnProvincia → <provincia> + BtnMunicipalidad → <fila> + BtnRubro, y cada rubro +
  BtnMes.

Cada fila trae en el valor de su radio las cifras con decimales («código/pia/pim/recaudado»); la celda visible está
redondeada, así que se usa el valor. La Consulta no da el mes por específica (BtnGenerica responde «No hay
resultados»): el detalle por concepto de esos años sale del comparativo de datos abiertos.
"""

import csv
import datetime
import html
import http.cookiejar
import os
import re
import sys
import urllib.parse
import urllib.request
from decimal import Decimal

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from comun import edicion, escribir_csv, escribir_json, parametros  # noqa: E402

BASE = "https://apps5.mineco.gob.pe/transparenciaingresos/Navegador/"


class Sesion:
    def __init__(self):
        self.abridor = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.abridor.addheaders = [("User-Agent", "Mozilla/5.0 (informe-recaudacion; solo lectura)")]

    def pedir(self, url, datos=None):
        cuerpo = urllib.parse.urlencode(datos).encode() if datos is not None else None
        for intento in range(4):
            try:
                with self.abridor.open(url, cuerpo, timeout=120) as respuesta:
                    return respuesta.geturl(), respuesta.read().decode("utf-8", "replace")
            except OSError as error:
                if intento == 3:
                    raise
                print(f"reintento: {error}", file=sys.stderr)

    def navegar(self, anio, pasos):
        url, pagina = self.pedir(f"{BASE}Navegar.aspx?y={anio}")
        for boton, fila in pasos:
            datos = ocultos(pagina)
            if fila:
                candidatas = [v for v, celdas in filas(pagina) if v and celdas[0].upper().startswith(fila.upper())]
                if not candidatas:
                    sys.exit(f"{anio}: no está la fila {fila!r} antes de {boton}")
                datos["grp1"] = candidatas[0]
            datos[f"ctl00$CPH1${boton}"] = boton
            url, pagina = self.pedir(url, datos)
        return pagina


def ocultos(pagina):
    return {m.group(1): html.unescape(m.group(2)) for m in
            re.finditer(r'<input type="hidden" name="([^"]+)" id="[^"]*" value="([^"]*)"', pagina)}


def limpiar(celda):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", "", celda))).strip()


def filas(pagina):
    salida = []
    for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", pagina, re.S):
        radio = re.search(r'type="radio"[^>]*value="([^"]+)"', tr)
        celdas = [c for c in (limpiar(c) for c in re.findall(r"<td[^>]*>(.*?)</td>", tr, re.S)) if c]
        if celdas:
            salida.append((radio.group(1) if radio else None, celdas))
    return salida


def cifras(pagina):
    """[(etiqueta, recaudado)] de las filas con cifras en el valor del radio («código/pia/pim/recaudado»)."""
    salida = []
    for valor, celdas in filas(pagina):
        if valor and valor.count("/") == 3:
            salida.append((celdas[0], Decimal(valor.split("/")[3] or "0")))
    return salida


def main():
    p = parametros()
    ca = p["consulta_amigable"]
    prefijo = [("BtnTipoGobierno", None), ("BtnSubTipoGobierno", "M: GOBIERNOS LOCALES"),
               ("BtnDepartamento", "M: MUNICIPALIDADES"), ("BtnProvincia", ca["departamento"]),
               ("BtnMunicipalidad", ca["provincia"])]
    e = edicion()
    sesion = Sesion()
    totales = []
    for anio in sorted(set(p["anios_historicos"] + [p["anio_comparable"], p["anio"]])):
        rubros = cifras(sesion.navegar(anio, prefijo + [("BtnRubro", ca["fila"])]))
        total = sum((r for _, r in rubros), Decimal(0))
        totales.append({"anio": anio, "fuente": "Consulta Amigable de Ingresos", "recaudado": str(total)})
        print(f"{anio}: {len(rubros)} rubros, recaudado {total}")
        if anio not in p["anios_historicos"]:
            continue
        filas_csv = []
        for etiqueta, anual in rubros:
            codigo, nombre = etiqueta.split(": ", 1)
            meses = cifras(sesion.navegar(anio, prefijo + [("BtnRubro", ca["fila"]), ("BtnMes", f"{codigo}:")]))
            suma = sum((r for _, r in meses), Decimal(0))
            if suma != anual:
                sys.exit(f"{anio}, rubro {codigo}: los meses suman {suma} y el anual es {anual}")
            filas_csv += [[anio, int(m.split(":")[0]), codigo, nombre, str(r)] for m, r in meses]
        escribir_csv(e / "datos" / "ca" / f"recaudado-mensual-{anio}.csv",
                     ["anio", "mes", "rubro", "rubro_nombre", "recaudado"], filas_csv)
    escribir_json(e / "datos" / "referencias.json", {
        "_comentario": "Totales anuales publicados en la Consulta Amigable de Ingresos del MEF, para conciliar los "
                       "archivos de datos abiertos. Generado por herramientas/consulta_amigable.py.",
        "consultado": datetime.datetime.now().isoformat(timespec="seconds"),
        "url": f"{BASE}default.aspx",
        "totales": totales,
    })


if __name__ == "__main__":
    main()
