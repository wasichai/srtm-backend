"""Agregados de la emisión del impuesto predial a partir del padrón (export del SRTM del MEF), sin datos personales.

Uso: EDICION=<carpeta> python3 -I herramientas/padron.py [<padron.xlsx>]

Lee la hoja del padrón (parametros.json: padron.archivo y padron.hoja) y escribe datos/padron/agregados.json con
totales, conteos y distribuciones. Ninguna fila del padrón sale del proceso: ni nombres, ni documentos, ni el
identificador del contribuyente. Los grupos con menos de MINIMO_GRUPO contribuyentes se informan sin montos.

Además recalcula el impuesto de cada contribuyente con la regla del SRTM (ImpuestoPredial.kt: tramos del art. 13 del
TUO de la Ley de Tributación Municipal y el mínimo, con los parámetros de model/data/parametros-predial.csv) y lo
compara con el insoluto del padrón: es el control de que la emisión es la que la ley y los parámetros producen.
"""

import collections
import csv
import datetime
import os
import pathlib
import sys
from decimal import ROUND_HALF_UP, Decimal

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import xlsx  # noqa: E402
from comun import REPO, edicion, escribir_json, parametros, sha256  # noqa: E402

MINIMO_GRUPO = 5
CENTIMO = Decimal("0.01")
# tramos de impuesto anual, en soles, para segmentar la cobranza (cortes fijos, elegidos antes de ver los datos:
# el mínimo legal, cien soles, quinientos, dos mil)
RANGOS_IMPUESTO = [("Mínimo legal", None), ("Hasta S/ 100", 100), ("Más de S/ 100 hasta S/ 500", 500),
                   ("Más de S/ 500 hasta S/ 2 000", 2000), ("Más de S/ 2 000", None)]
CUANTILES = [1, 5, 10, 20, 50]


def decimal(texto):
    if texto is None or str(texto).strip() == "":
        return Decimal(0)
    return Decimal(str(texto).strip())


def dos(valor):
    return valor.quantize(CENTIMO, rounding=ROUND_HALF_UP)


def parametros_predial(anio):
    """UIT, alícuotas, límites y mínimo vigentes al 1 de enero del año (las mismas filas que lee el SRTM)."""
    primero = datetime.date(anio, 1, 1)
    valores = {}
    with (REPO / parametros()["padron"]["parametros_predial"]).open(encoding="utf-8") as entrada:
        for fila in csv.DictReader(linea for linea in entrada if not linea.startswith("#")):
            tipo, clave, desde, hasta, valor = (fila[c] for c in ("tipo", "clave", "vigencia_desde", "vigencia_hasta",
                                                                  "valor_numerico"))
            if not valor or datetime.date.fromisoformat(desde) > primero:
                continue
            if hasta and datetime.date.fromisoformat(hasta) < primero:
                continue
            valores[(tipo, clave)] = Decimal(valor)
    uit = valores[("UIT", "")]
    return {
        "uit": uit,
        "alicuotas": [valores[("TRAMO_PREDIAL", str(i))] for i in (1, 2, 3)],
        "limites": [valores[("TRAMO_PREDIAL_LIMITE", str(i))] for i in (1, 2)],
        "minimo": dos(uit * valores[("PREDIAL_MINIMO", "")] / 100),
    }


def liquidar(base, p):
    """El impuesto anual y el tramo más alto que alcanza la base (0 si paga el mínimo), como ImpuestoPredial.kt."""
    base = dos(base)
    cortes = [Decimal(0)] + [dos(limite * p["uit"]) for limite in p["limites"]] + [None]
    calculado = Decimal(0)
    tramo = 0
    for i, alicuota in enumerate(p["alicuotas"]):
        desde, hasta = cortes[i], cortes[i + 1]
        monto = max((base if hasta is None else min(base, hasta)) - desde, Decimal(0))
        if monto > 0:
            tramo = i + 1
        calculado += dos(monto * alicuota / 100)
    if base > 0 and calculado < p["minimo"]:
        return p["minimo"], 0
    return calculado, tramo


def cuotas_vencidas(anual, vencidas):
    """Lo exigible si el contribuyente fracciona: las cuotas ya vencidas (un cuarto cada una; la cuarta, el resto)."""
    cuarto = (anual / 4).quantize(CENTIMO, rounding=ROUND_HALF_UP)
    montos = [cuarto] * 3 + [anual - cuarto * 3]
    return sum(montos[:vencidas], Decimal(0))


def ultimo_dia_habil(anio, mes, feriados=()):
    dia = (datetime.date(anio, mes % 12 + 1, 1) if mes < 12 else datetime.date(anio + 1, 1, 1)) - datetime.timedelta(1)
    while dia.weekday() >= 5 or (dia.month, dia.day) in feriados:
        dia -= datetime.timedelta(1)
    return dia


def rango(impuesto, minimo_aplicado):
    if minimo_aplicado:
        return RANGOS_IMPUESTO[0][0]
    for nombre, tope in RANGOS_IMPUESTO[1:-1]:
        if impuesto <= tope:
            return nombre
    return RANGOS_IMPUESTO[-1][0]


def grupo(conteo, montos):
    """Un grupo agregado; sin montos si tiene menos de MINIMO_GRUPO contribuyentes."""
    if conteo < MINIMO_GRUPO:
        return {"contribuyentes": conteo, "suprimido": True}
    return {"contribuyentes": conteo, **{k: str(dos(v)) for k, v in montos.items()}}


def main():
    p = parametros()
    anio = p["anio"]
    ruta = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else p["padron"]["archivo"]).expanduser()
    pp = parametros_predial(anio)
    corte = datetime.date.fromisoformat(p["fecha_corte"])
    # las fechas de la ordenanza de la municipalidad (parametros.json: vencimientos_predial) o, sin ella, las del art.
    # 15 del TUO LTM: último día hábil de febrero, mayo, agosto y noviembre (feriados fijos de Vencimientos.kt)
    if p.get("vencimientos_predial"):
        vencimientos = [datetime.date.fromisoformat(f) for f in p["vencimientos_predial"]["fechas"]]
        norma_vencimientos = p["vencimientos_predial"]["norma"]
    else:
        feriados = {(1, 1), (5, 1), (6, 7), (6, 29), (7, 23), (7, 28), (7, 29), (8, 6), (8, 30), (10, 8), (11, 1),
                    (12, 8), (12, 9), (12, 25)}
        vencimientos = [ultimo_dia_habil(anio, mes, feriados) for mes in (2, 5, 8, 11)]
        norma_vencimientos = "TUO de la Ley de Tributación Municipal, art. 15"
    vencidas = sum(1 for v in vencimientos if v <= corte)

    filas = 0
    contribuyentes = set()
    fuera = collections.Counter()
    tot = collections.Counter()
    impuestos = []
    por_condicion = collections.defaultdict(collections.Counter)
    por_rango = collections.defaultdict(collections.Counter)
    por_tramo = collections.defaultdict(collections.Counter)
    control = {"coinciden": 0, "difieren": 0, "diferencia_total": Decimal(0), "diferencia_maxima": Decimal(0)}

    for fila in xlsx.filas(ruta, p["padron"]["hoja"]):
        if not any(fila.values()):
            continue
        filas += 1
        if fila.get("sec_ejec") != p["municipalidad"]["sec_ejec"]:
            fuera["sec_ejec"] += 1
        if str(fila.get("ano_aplicacion")) != str(anio):
            fuera["anio"] += 1
        contribuyentes.add(fila.get("propietario_id"))
        base_total = decimal(fila.get("base_imp_total"))
        base = decimal(fila.get("base_imp_afecto"))
        insoluto = dos(decimal(fila.get("imp_insoluto_anual")))
        calculado, tramo = liquidar(base, pp)
        diferencia = abs(calculado - insoluto)
        if diferencia <= CENTIMO:
            control["coinciden"] += 1
        else:
            control["difieren"] += 1
            control["diferencia_total"] += calculado - insoluto
            control["diferencia_maxima"] = max(control["diferencia_maxima"], diferencia)
        exigible = cuotas_vencidas(insoluto, vencidas)
        montos = {"base_total": base_total, "base_afecta": base, "insoluto": insoluto, "exigible_fraccionado": exigible}
        tot.update(montos)
        tot["con_impuesto"] += insoluto > 0
        tot["sin_base_afecta"] += base == 0
        impuestos.append(insoluto)
        condicion = (fila.get("condicion_contribuyente_desc") or "SIN CONDICIÓN").strip()
        por_condicion[condicion].update({**montos, "n": 1})
        if insoluto > 0:
            por_rango[rango(insoluto, tramo == 0)].update({"insoluto": insoluto, "n": 1})
            por_tramo["Mínimo legal" if tramo == 0 else f"Tramo {tramo}"].update({"insoluto": insoluto,
                                                                                    "base_afecta": base, "n": 1})

    if filas == 0:
        sys.exit("el padrón no tiene filas")
    impuestos.sort(reverse=True)
    total = sum(impuestos, Decimal(0))
    con_impuesto = [i for i in impuestos if i > 0]
    concentracion = []
    for porcentaje in CUANTILES:
        n = max(1, round(len(con_impuesto) * porcentaje / 100))
        parte = sum(con_impuesto[:n], Decimal(0))
        concentracion.append({"porcentaje_contribuyentes": porcentaje, "contribuyentes": n,
                              "insoluto": str(dos(parte)), "participacion": str(dos(parte / total * 100))})
    acumulado, mitad = Decimal(0), 0
    for i in con_impuesto:
        acumulado += i
        mitad += 1
        if acumulado >= total / 2:
            break

    def montos(c, campos):
        return {k: c[k] for k in campos}

    agregados = {
        "fuente": {"archivo": ruta.name, "hoja": p["padron"]["hoja"], "sha256": sha256(ruta),
                   "propiedades": xlsx.propiedades(ruta)},
        "anio": anio,
        "filas": filas,
        "contribuyentes_distintos": len(contribuyentes),
        "filas_de_otra_ejecutora": fuera["sec_ejec"],
        "filas_de_otro_anio": fuera["anio"],
        "parametros": {"uit": str(pp["uit"]), "alicuotas": [str(a) for a in pp["alicuotas"]],
                       "limites_uit": [str(x) for x in pp["limites"]], "minimo": str(pp["minimo"])},
        "totales": {"base_total": str(dos(tot["base_total"])), "base_afecta": str(dos(tot["base_afecta"])),
                    "insoluto": str(dos(tot["insoluto"])), "con_impuesto": tot["con_impuesto"],
                    "sin_base_afecta": tot["sin_base_afecta"]},
        "vencimientos": [v.isoformat() for v in vencimientos],
        "norma_vencimientos": norma_vencimientos,
        "cuotas_vencidas_al_corte": vencidas,
        "exigible_al_corte_si_fraccionan": str(dos(tot["exigible_fraccionado"])),
        "control_recalculo": {k: (str(dos(v)) if isinstance(v, Decimal) else v) for k, v in control.items()},
        "concentracion": concentracion,
        "contribuyentes_para_mitad_del_insoluto": mitad,
        "por_condicion": {k: grupo(c["n"], montos(c, ("base_afecta", "insoluto"))) for k, c in
                          sorted(por_condicion.items(), key=lambda kv: -kv[1]["n"])},
        "por_rango_de_impuesto": {nombre: grupo(por_rango[nombre]["n"], montos(por_rango[nombre], ("insoluto",)))
                                  for nombre, _ in RANGOS_IMPUESTO},
        "por_tramo": {k: grupo(c["n"], montos(c, ("base_afecta", "insoluto"))) for k, c in sorted(por_tramo.items())},
        "grupo_minimo": MINIMO_GRUPO,
    }
    escribir_json(edicion() / "datos" / "padron" / "agregados.json", agregados)
    print(f"padrón: {filas} filas, {len(contribuyentes)} contribuyentes, insoluto {dos(total)}; recálculo: "
          f"{control['coinciden']} coinciden, {control['difieren']} difieren")


if __name__ == "__main__":
    main()
