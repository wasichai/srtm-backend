#!/usr/bin/env python3
"""Padrón y catastro FICTICIOS para el entorno de develop/pruebas (organización ica-prueba). Ningún dato es real: los nombres son
CONTRIBUYENTE PRUEBA nnnn y los DNI 99xxxxxx. Usa el constructor de xlsx de test_import_padron_ica.py.

Escribe en <out>/: padron_ficticio.xlsx (hojas padron y construcciones), fichas.csv, pisos.csv, frentes.csv,
valores.csv y perturbaciones.csv (qué predios se alteraron a propósito y cómo).

Uso: python3 develop/pruebas/generar_ficticio.py model develop/pruebas/ficticio [n_predios]
"""
import csv
import os
import random
import sys
from decimal import Decimal, ROUND_HALF_UP

MODEL_DIR, OUT = sys.argv[1], sys.argv[2]
N = int(sys.argv[3]) if len(sys.argv) > 3 else 600
sys.path.insert(0, MODEL_DIR)
import import_padron_ica as ipi  # noqa: E402
import test_import_padron_ica as t  # noqa: E402

random.seed(20261020)
C2 = Decimal("0.01")
ANIO = 2026
# cuadro de valores unitarios de la costa 2026 (catastro-backend tablas/valores-unitarios-costa-2026.csv), solo las celdas con valor
VU = {
    "M": dict(A="894.27", B="576.57", C="387.43", D="374.68", E="263.77", F="198.64", G="117.05"),
    "T": dict(A="543.16", B="354.37", C="285.80", D="181.40", E="67.63", F="37.19", G="25.57", H="0.00"),
    "P": dict(A="485.33", B="255.81", C="161.41", D="141.39", E="120.98", F="90.81", G="49.05", H="24.53", I="0.00"),
}
VU = {k: {l: Decimal(v) for l, v in d.items()} for k, d in VU.items()}
RNT = ipi.read_depreciacion(ipi.default_depreciacion_path())
MATERIAL = {"1": "CONCRETO", "2": "LADRILLO", "3": "ADOBE"}
ESTADO = {"1": "MUY BUENO", "2": "BUENO", "3": "REGULAR", "4": "MALO"}
USO_SRTM = {"4": "010101", "1": "020300", "20": "070101"}
# 12 vías ficticias, cada una con su tramo de arancel (id_aranc)
VIAS = [(f"9{n:03d}", random.choice(["1", "2", "3", "4"]), f"VIA PRUEBA {n:02d}", Decimal(random.randrange(120, 640)))
        for n in range(1, 13)]
UNIDADES = [(f"8{n:02d}", "URBANIZACION", f"URB. PRUEBA {n:02d}") for n in range(1, 7)]


def r2(x):
    return x.quantize(C2, rounding=ROUND_HALF_UP)


def nivel_valor(m, te, p, clasif, mat, est, anio_c, area):
    unit = VU["M"][m] + VU["T"][te] + VU["P"][p]
    dep = ipi.depreciacion_rnt(RNT, clasif, MATERIAL[mat], ESTADO[est], ANIO - anio_c)
    return unit, dep, ipi.valor_total_nivel(unit, Decimal(0), dep, area)


def main():
    os.makedirs(OUT, exist_ok=True)
    padron, constr, fichas, pisos, pert = [], [], [], [], []
    fila_id = cons_id = 0
    contrib = 0
    for i in range(1, N + 1):
        codigo = f"{900000000 + i:09d}-0001-0001"
        via = VIAS[i % len(VIAS)]
        uu = UNIDADES[i % len(UNIDADES)]
        uso = "20" if i % 17 == 0 else ("1" if i % 9 == 0 else "4")
        area_t = Decimal(random.randrange(80, 320))
        terreno = r2(area_t * via[3])
        niveles = []
        if uso != "20":
            for piso in range(1, random.choice([1, 1, 2, 2, 3]) + 1):
                m, te, p = random.choice("BCCDDE"), random.choice("CCDDE"), random.choice("CDEFF")
                mat, est = random.choice("1222"), random.choice("1223")
                anio_c = random.randrange(1978, 2025)
                area = Decimal(random.randrange(40, 160))
                clasif = "1" if uso == "4" else "2"
                unit, dep, total = nivel_valor(m, te, p, clasif, mat, est, anio_c, area)
                niveles.append(dict(piso=piso, m=m, te=te, p=p, mat=mat, est=est, anio=anio_c, area=area, clasif=clasif,
                                    unit=unit, dep=dep, total=total))
        construido = sum((n["total"] for n in niveles), Decimal(0))
        area_c = sum((n["area"] for n in niveles), Decimal(0))
        valor = terreno + construido
        # 1 de cada 20 es un condominio de dos titulares
        titulares = 2 if i % 20 == 0 else 1
        for k in range(titulares):
            contrib += 1
            fila_id += 1
            cc = f"{990000 + contrib}"
            padron.append(t.padron(
                id=str(fila_id), codigo_contribuyente=cc, codigo_de_contribuyente=cc, codigo_de_contribuyente_2=cc,
                codigo_predio=codigo,
                direccion_del_predio_completa=f"{via[2]} {100 + i} {uu[2]}", codigo_de_via=via[0], tipo_de_via=via[1],
                nombre_de_la_via=via[2], numero=str(100 + i), manzana=chr(65 + i % 20), lote=str(i % 40 + 1),
                codigo_de_unidad_urbana=uu[0], tipo_de_unidad_urbana=uu[1], nombre_de_unidad_urbana=uu[2],
                condicion_predio="5" if titulares > 1 else "1", codigo_uso=uso,
                descripcion_del_uso={"4": "CASA HABITACION", "1": "COMERCIO", "20": "TERR.SIN CONSTRUIR"}[uso],
                area_terreno=str(area_t), arancel=str(via[3]), valor_terreno=str(terreno), area_construida=str(area_c),
                valor_area_construida=str(construido), valor_otras_instalaciones="0", valor_predio=str(valor),
                apellido_paterno="CONTRIBUYENTE", apellido_materno="PRUEBA", nombres=f"{contrib:04d}",
                nombre_completo=f"CONTRIBUYENTE PRUEBA {contrib:04d}", tipo_documento="1", nro_documento=f"{99000000 + contrib}",
                COD_HU_ARMONIZACION=uu[0], COD_VIA_ARMONIZACION=via[0]))
            for n in niveles:
                cons_id += 1
                constr.append(t.construccion(
                    id=str(cons_id), codigo_contribuyente=cc, codigo_predio=codigo, nivel=str(n["piso"]),
                    ano_construccion=str(n["anio"]), clasificacion_construccion=n["clasif"], material_predominante=n["mat"],
                    estado_de_conservacion=n["est"], muros_y_columnas=n["m"], techos=n["te"], pisos="C",
                    puertas_y_ventanas=n["p"], revestimientos="D", banos="D", instalaciones_electricas_y_sanitarias="D",
                    area_construida_propia=str(n["area"]), valor_unitario=str(n["unit"]), depreciacion=str(n["dep"]),
                    valor_unitario_depreciado=str(r2(n["unit"] * (1 - n["dep"] / 100))), valor_total_nivel=str(n["total"])))

        # el catastro: el mismo predio, salvo las perturbaciones
        motivo = None
        f_area_t, f_niveles, f_codigo = area_t, [dict(n) for n in niveles], codigo
        if i % 37 == 1:
            motivo, f_area_t = "SIN_CATASTRO: el levantamiento no lo tiene", None
        elif i % 41 == 2:
            motivo, f_area_t = "SUBVALUADO: terreno x 1.6", r2(area_t * Decimal("1.6"))
        elif i % 43 == 3 and niveles:
            motivo = "SUBVALUADO: un piso más sin declarar"
            f_niveles.append({**niveles[-1], "piso": niveles[-1]["piso"] + 1, "anio": 2023})
        elif i % 47 == 4 and niveles:
            motivo = "SOBREVALUADO: área construida x 0.5 y terreno x 0.6"
            f_area_t = r2(area_t * Decimal("0.6"))
            for n in f_niveles:
                n["area"] = r2(n["area"] * Decimal("0.5"))
        elif i % 53 == 5 and niveles:
            motivo = "SIN_VALOR: muros H (celda ..... del cuadro)"
            f_niveles[0]["m"] = "H"
        if motivo:
            pert.append({"codigo_predio": codigo, "perturbacion": motivo})
        if f_area_t is None:
            continue
        fichas.append({"cod_pre": f_codigo, "cod_sect": "01", "cod_mzn": chr(65 + i % 20), "cod_lote": str(i % 40 + 1),
                       "id_aranc": f"A{via[0]}", "cod_via": via[0], "cuadra": "1", "lado": "D", "uso": USO_SRTM[uso],
                       "area_terreno": str(f_area_t), "longitud_frente": str(random.randrange(6, 20)),
                       "anio_levantamiento": "2026", "es_edificio": "NO"})
        for n in f_niveles:
            pisos.append({"cod_pre": f_codigo, "piso": str(n["piso"]), "area_construida": str(n["area"]),
                          "anio_construccion": str(n["anio"]), "material": MATERIAL[n["mat"]],
                          "estado_conservacion": ESTADO[n["est"]].replace(" ", "_"),
                          "categorias": f"{n['m']}{n['te']}C{n['p']}DDD", "clasificacion": n["clasif"]})
    # 8 omisos: predios del levantamiento que el padrón no tiene
    for j in range(1, 9):
        via = VIAS[j]
        codigo = f"{950000000 + j:09d}-0001-0001"
        pert.append({"codigo_predio": codigo, "perturbacion": "OMISO: no está en el padrón"})
        fichas.append({"cod_pre": codigo, "cod_sect": "02", "cod_mzn": "Z", "cod_lote": str(j), "id_aranc": f"A{via[0]}",
                       "cod_via": via[0], "cuadra": "2", "lado": "I", "uso": "010101", "area_terreno": "150",
                       "longitud_frente": "10", "anio_levantamiento": "2026", "es_edificio": "NO"})
        pisos.append({"cod_pre": codigo, "piso": "1", "area_construida": "90", "anio_construccion": "2015",
                      "material": "LADRILLO", "estado_conservacion": "BUENO", "categorias": "CCCFDDD", "clasificacion": "1"})

    t.write_xlsx(os.path.join(OUT, "padron_ficticio.xlsx"), padron, constr)
    for name, rows in (("fichas", fichas), ("pisos", pisos), ("perturbaciones", pert),
                       ("frentes", [{"id_aranc": f"A{v[0]}", "cod_via": v[0], "cuadra": "1", "lado": "D"} for v in VIAS]),
                       ("valores", [{"id_aranc": f"A{v[0]}", "ano_ejec": "2026", "val_act": str(v[3])} for v in VIAS])):
        with open(os.path.join(OUT, f"{name}.csv"), "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
    print(f"padrón: {len(padron)} filas, {len(constr)} construcciones, {contrib} contribuyentes, {N} predios")
    print(f"catastro: {len(fichas)} fichas, {len(pisos)} pisos, {len(VIAS)} aranceles; {len(pert)} perturbaciones")


main()
