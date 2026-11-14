#!/usr/bin/env python3
"""Turns Ica's padrón SAT depurado and the construcciones of its reporte de subvaluados into the workbook that
import_padron_ica.py reads, in the format of PADRON_CONSTRUCCION.xlsx (`padron`, `construcciones`), plus a third sheet,
`observaciones` (#297).

Sources (wasichai/srtm-backend#289):
  padrón: BASE DE RENTAS DEPURADA_<fecha>.xlsx, sheet `Hoja 1`, one row per (predio, contribuyente).
  construcciones: REPORTE_SUBVALUADOS.xlsx, sheet `construccion_rentas`, one row per section of a nivel, joined to
          the padrón by cod_predio only: it has no contribuyente.
Rules:
  - `'-` and blanks go empty. A code ('20.0' too) goes as its text.
  - The codes import_padron_ica.py translates through data/ica/codigos.json arrive as those codes: TIPO_VIA_ARMO
    (CA., AV., JR., PJE., PRLG. or the option itself) becomes the code of its option in `tipo_via`; material and
    estado in text (LADRILLO, BUENO) become the code of `material` and `estado_conservacion`, and one of those codes
    passes as it is. condición, tipo de documento and uso already come as codes and pass as they are; one that
    codigos.json lacks is counted (<campo>_sin_mapeo) and the importer reports it again. Nothing is guessed: a value
    with no code (a tipo de vía AL. or ST., a material QUINCHA) goes empty and is counted.
  - TIPO_UNIDAD URBANA_ARMO becomes the model's option, whole or by its abreviatura of data/tipos_unidad_urbana.csv
    (URB., AA.HH.…), the importer's table; --tipo-unidad TEXTO=TIPO reads one more (ST.=SECTOR). Anything else goes
    empty and is counted, because the importer rejects a predio whose tipo_zona is not an option.
  - The catalogs pair a code with its own name. The armonización's unidad urbana (CODHHUU_ARMO, its tipo and its
    name) is one name per code, so codigo_de_unidad_urbana is CODHHUU_ARMO (the SAT's Codigo_Habilitación_Urbana
    carries several names of the armonización per code). The SAT's codigo de vía (Codigo_de_via_MUNICIPAL, the one
    the arancel por vía reads, #226) has no name of its own and the armonización's names do not pair with it, so
    nombre_de_la_via is not read: the importer creates no vía of the catalog and the predio's address names the vía
    (--map-padron nombre_de_la_via=NOMBRE_VIA_ARMO reads it, one vía of the catalog per code and name).
  - The padrón has no tipo de predio (urbano/rústico): it goes empty and is counted unless --map-padron
    tipo_predio=COLUMNA names one.
  - The construcciones carry no clasificación, valor unitario, incremento, depreciación nor valor total: they go empty
    (the importer checks them only when they come; --map-construccion names a column that has them).
  - A nivel goes to the DJ of every contribuyente of its predio, in the padrón's order, as PADRON_CONSTRUCCION.xlsx
    repeats them in each condómino's DJ (the importer hangs a nivel from its (predio, contribuyente)). A code the
    importer splits into distinct predios (#207) gives the niveles to each: niveles_en_codigos_compartidos counts
    them. A nivel of a predio the padrón lacks is dropped (construcciones_sin_predio). A letter outside the model's
    letra_categoria, a nivel without digits and a year that is not a number go empty and are counted.

Observaciones (#297): every row it empties, reads by an alias or flags (what it counts) also goes to the `observaciones`
sheet (codigo_predio, codigo_contribuyente, objeto, regla, detalle, hoja, fila), hoja and fila those of the SAT's file,
so that import_padron_ica.py --carga gives each record its observacion_importacion. A nivel's go once per DJ it goes
to. detalle carries codes and catalog values, never a name nor a document. Each row of padron and construcciones
carries its source too (hoja_origen, fila_origen), for the importer's own observations. --excluir-predios CSV (a
codigo_predio column) leaves those predios out of the three sheets (predios_excluidos).

--map-padron and --map-construccion campo=COLUMNA override a source (campo is a column of the output workbook);
campo= (empty) leaves it unread. Every mapped column is required: a missing sheet or column exits 2 naming it, before
anything is written. The workbook (PADRON_CONSTRUCCION.xlsx, mode 0600) goes in --salida, which must be outside the
repository: it holds datos personales (Ley 29733). stdout carries only `<clave>: <n>` lines, never a value.

Run: python3 -I convertir_padron_sat_ica.py --padron "BASE DE RENTAS DEPURADA.xlsx" \
         --construcciones REPORTE_SUBVALUADOS.xlsx --salida ~/ica/padron
Exit: 0 ok, 2 a missing sheet or column or a bad flag (nothing is written).
"""
import argparse
import csv
import json
import os
import re
import sys
import zipfile
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
# under python3 -I (the xlsx are untrusted) neither the cwd nor the script's directory is on sys.path: put back only
# the latter, for import_predios and import_padron_ica
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from import_padron_ica import codigo, codigos_derivados, default_codigos_path, load_codigos  # noqa: E402
from import_predios import TIPOS_UNIDAD_URBANA, read_xlsx  # noqa: E402

REPOSITORIO = os.path.dirname(HERE)
LIBRO = "PADRON_CONSTRUCCION.xlsx"
HOJA_PADRON, HOJA_CONSTRUCCIONES = "Hoja 1", "construccion_rentas"
GUION = "'-"

# the output's columns, PADRON_CONSTRUCCION.xlsx's, in its order
PADRON_SALIDA = [
    "id", "codigo_contribuyente", "codigo_predio", "direccion_del_predio_completa", "codigo_de_via", "tipo_de_via",
    "nombre_de_la_via", "numero", "manzana", "lote", "codigo_de_unidad_urbana", "tipo_de_unidad_urbana",
    "nombre_de_unidad_urbana", "condicion_predio", "fecha_adquisicion", "codigo_uso", "descripcion_del_uso",
    "tipo_predio", "numero_de_interior", "condicion_propiedad", "condomin", "area_terreno", "area_terreno_comun",
    "arancel", "valor_terreno", "area_construida", "area_construida_comun", "valor_area_construida",
    "valor_otras_instalaciones", "valor_predio", "numero_de_partida_registral", "codigo_de_contribuyente",
    "codigo_de_contribuyente_2", "apellido_paterno", "apellido_materno", "nombres", "nombre_completo", "tipo_documento",
    "nro_documento", "COD_HU_ARMONIZACION", "COD_VIA_ARMONIZACION",
]
CONSTRUCCION_SALIDA = [
    "id", "codigo_contribuyente", "codigo_predio", "nivel", "ano_construccion", "clasificacion_construccion",
    "material_predominante", "estado_de_conservacion", "muros_y_columnas", "techos", "pisos", "puertas_y_ventanas",
    "revestimientos", "banos", "instalaciones_electricas_y_sanitarias", "area_construida_propia",
    "area_construida_comun", "valor_unitario", "incremento_5", "depreciacion", "valor_unitario_depreciado",
    "valor_total_nivel", "area_comun",
]

# #297: each row of padron and construcciones says where it came from, for the importer's observaciones
ORIGEN = ["hoja_origen", "fila_origen"]
# the third sheet: one row per observation of a record (what the converter emptied, read by an assumption or flagged)
OBSERVACIONES_SALIDA = ["codigo_predio", "codigo_contribuyente", "objeto", "regla", "detalle", "hoja", "fila"]
CODIGOS_JSON = "data/ica/codigos.json"

# output column -> source column; None: no source by default (it goes empty)
PADRON_COLUMNS = {
    "codigo_contribuyente": "COD_CONTRIB", "codigo_predio": "COD_PREDIO",
    "direccion_del_predio_completa": "Dirección_completa_del_predio", "codigo_de_via": "Codigo_de_via_MUNICIPAL",
    "tipo_de_via": "TIPO_VIA_ARMO", "nombre_de_la_via": None, "numero": "N_MUNICIPAL",
    "manzana": "MANZANA_URBANA", "lote": "LOTE_URBANO", "codigo_de_unidad_urbana": "CODHHUU_ARMO",
    "tipo_de_unidad_urbana": "TIPO_UNIDAD URBANA_ARMO", "nombre_de_unidad_urbana": "NOMBRE_UNIDAD URBANA_ARMO",
    "condicion_predio": "Condición_predio", "fecha_adquisicion": "Fecha_Adquisición", "codigo_uso": "Código_uso",
    "descripcion_del_uso": "Descripcion_del_Uso", "tipo_predio": None, "numero_de_interior": "N_INTERIOR",
    "condicion_propiedad": "Condición_propiedad", "condomin": "__Condomin", "area_terreno": "AREA DE TERRENO",
    "area_terreno_comun": "Área_terreno_Común", "arancel": "Arancel", "valor_terreno": "Valor_Terreno",
    "area_construida": "Área_construida", "area_construida_comun": "Area_construida_Común",
    "valor_area_construida": "Valor_Área_Construida", "valor_otras_instalaciones": "Valor_Otras_Instalaciones",
    "valor_predio": "Valor_Predio", "numero_de_partida_registral": "Numero_de_Partida_Registral",
    "apellido_paterno": "A_PATERNO", "apellido_materno": "A_MATERNO", "nombres": "NOMBRES",
    "nombre_completo": "RazonSocial", "tipo_documento": "TIP_DOC", "nro_documento": "N_DOC",
    "COD_HU_ARMONIZACION": "CODHHUU_ARMO", "COD_VIA_ARMONIZACION": "CODVIA_ARMO",
}
CONSTRUCCION_COLUMNS = {
    "codigo_predio": "cod_predio", "nivel": "nivel_rentas", "ano_construccion": "ano_rentas",
    "clasificacion_construccion": None, "material_predominante": "material_rentas",
    "estado_de_conservacion": "estado_conservacion_rentas", "muros_y_columnas": "muros_rentas",
    "techos": "techos_rentas", "pisos": "pisos_rentas", "puertas_y_ventanas": "puertas_rentas",
    "revestimientos": "revestimientos_rentas", "banos": "banos_rentas",
    "instalaciones_electricas_y_sanitarias": "instalaciones_rentas",
    "area_construida_propia": "area_construida_nivel_rentas", "area_construida_comun": None, "valor_unitario": None,
    "incremento_5": None, "depreciacion": None, "valor_total_nivel": None,
}
LETRAS = ["muros_y_columnas", "techos", "pisos", "puertas_y_ventanas", "revestimientos", "banos",
          "instalaciones_electricas_y_sanitarias"]
# the padrón's abbreviations of a tipo de vía (the catastro's ABR_TVIA) as the model's option
ABREVIATURAS_VIA = {"AV.": "AVENIDA", "JR.": "JIRON", "CA.": "CALLE", "CLL.": "CALLE", "PJE.": "PASAJE",
                    "PRLG.": "PROLONGACION"}
# the code columns of the padrón that pass as they are, and their table in codigos.json
CODIGOS_PADRON = {"condicion_predio": "condicion_propiedad", "tipo_documento": "tipo_documento",
                  "codigo_uso": "uso"}
# the observation of each: its regla and the object it concerns
SIN_MAPEO = {"condicion_predio": ("CONDICION_SIN_MAPEO", "declaracion_predial"),
             "tipo_documento": ("DOCUMENTO_SIN_MAPEO", "contribuyente"),
             "codigo_uso": ("USO_SIN_MAPEO", "declaracion_predial")}

CONTEOS_PADRON = ["filas_padron", "predios", "contribuyentes", "celdas_guion", "tipo_via_sin_codigo",
                  "tipo_unidad_urbana_sin_tipo", "tipo_predio_vacio", "condicion_predio_sin_mapeo",
                  "tipo_documento_sin_mapeo", "codigo_uso_sin_mapeo"]
CONTEOS_CONSTRUCCIONES = ["filas_construcciones", "construcciones_sin_predio", "predios_con_niveles", "niveles",
                          "niveles_copiados", "niveles_en_codigos_compartidos", "materiales_sin_codigo",
                          "estados_sin_codigo", "niveles_sin_numero", "anios_ilegibles", "letras_vaciadas"]
# XML 1.0 refuses these characters: a cell that carries one loses it
NO_XML = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f￾￿]")


def default_model_path():
    return os.path.join(HERE, "model.json")


def load_letras(path):
    """The model's letra_categoria options (A-I)."""
    with open(path, encoding="utf-8") as f:
        return set(json.load(f)["enums"]["letra_categoria"])


# ---------------------------------------------------------------------------
# mappings (pure)
# ---------------------------------------------------------------------------

def limpio(value):
    """A cell as text with single spaces; blank and `'-` are None."""
    if value is None:
        return None
    text = " ".join(str(value).split())
    return None if text in ("", GUION) else text


def parse_map(pares, columnas):
    """campo=COLUMNA flags over the default columns; campo= leaves the field unread."""
    mapa = dict(columnas)
    for par in pares:
        campo, igual, columna = par.partition("=")
        campo = campo.strip()
        if not igual or campo not in columnas:
            raise ValueError(f"--map {par}: expected campo=COLUMNA, campo one of {', '.join(columnas)}")
        mapa[campo] = columna.strip() or None
    return mapa


def parse_alias(pares):
    """TEXTO=TIPO flags as {TEXTO: TIPO}, TIPO an option of tipo_unidad_urbana."""
    opciones = {nombre for _, nombre in TIPOS_UNIDAD_URBANA}
    alias = {}
    for par in pares:
        texto, igual, tipo = par.partition("=")
        texto, tipo = (limpio(texto) or "").upper(), (limpio(tipo) or "").upper()
        if not igual or not texto or tipo not in opciones:
            raise ValueError(f"--tipo-unidad {par}: expected TEXTO=TIPO, TIPO an option of tipo_unidad_urbana")
        alias[texto] = tipo
    return alias


def _codigo_de(tabla, codigos):
    """option -> its code in a table of codigos.json, for the options only one code maps to."""
    por_opcion = {}
    for code, opcion in codigos[tabla]["valores"].items():
        por_opcion.setdefault(opcion, []).append(code)
    return {opcion: codes[0] for opcion, codes in por_opcion.items() if len(codes) == 1}


def _codigo_o_texto(value, tabla, codigos, textos=None):
    """A code of the table as it is, or a text (an option, or one `textos` reads as one) as its code; None when
    neither (the caller counts it)."""
    code = codigo(value)
    if code is None or code in codigos[tabla]["valores"]:
        return code
    texto = code.upper()
    opcion = (textos or {}).get(texto, texto)
    return _codigo_de(tabla, codigos).get(opcion)


def _sin_mapeo(code, tabla, codigos):
    """True when codigos.json does not translate the code: absent, or a uso without its srtm code."""
    valores = codigos[tabla]["valores"]
    if code not in valores:
        return True
    return tabla == "uso" and not (valores[code] or {}).get("srtm")


def _tipo_unidad(value, alias):
    return _tipo_unidad_leido(value, alias)[0]


def _tipo_unidad_leido(value, alias):
    """(the option, True when only an alias read it so)."""
    texto = (limpio(value) or "").upper()
    if not texto:
        return None, False
    propio = next((nombre for t, nombre in TIPOS_UNIDAD_URBANA if t == texto), None)
    if texto in alias:
        return alias[texto], alias[texto] != propio
    return propio, False


def observar(observaciones, regla, objeto, predio, contribuyente, detalle, hoja, fila):
    """One row of the observaciones sheet; none when the caller keeps no list."""
    if observaciones is not None:
        observaciones.append({"codigo_predio": predio, "codigo_contribuyente": contribuyente, "objeto": objeto,
                              "regla": regla, "detalle": detalle, "hoja": hoja, "fila": fila})


def _celda(row, columna, n):
    if columna is None:
        return None
    value = row.get(columna)
    if value is not None and str(value).strip() == GUION:
        n["celdas_guion"] += 1
    return limpio(value)


def convertir_padron(rows, mapa, codigos, alias, hoja=HOJA_PADRON, observaciones=None):
    """(rows of the output's padron sheet, counts) from the padrón SAT depurado. What it empties, reads by an alias or
    flags goes to `observaciones` (#297), one per row: the tipo de vía and de unidad urbana are the predio's, the
    condición and the uso the DJ's, the tipo de documento its contribuyente's first row (the one the importer reads)."""
    n = Counter(dict.fromkeys(CONTEOS_PADRON, 0))
    filas, vistos = [], set()
    for i, row in enumerate(rows, start=1):
        fila = dict.fromkeys(PADRON_SALIDA)
        fila["id"] = str(i)
        for campo, columna in mapa.items():
            fila[campo] = _celda(row, columna, n)
        fila["codigo_predio"], fila["codigo_contribuyente"] = codigo(fila["codigo_predio"]), codigo(
            fila["codigo_contribuyente"])
        fila["_fila"] = row.get("_fila", i + 1)
        fila["hoja_origen"], fila["fila_origen"] = hoja, fila["_fila"]
        predio, contribuyente = fila["codigo_predio"], fila["codigo_contribuyente"]

        def obs(regla, objeto, detalle, codigo_predio=predio):
            observar(observaciones, regla, objeto, codigo_predio, contribuyente, detalle, hoja, fila["_fila"])

        tipo_via = fila["tipo_de_via"]
        fila["tipo_de_via"] = _codigo_o_texto(tipo_via, "tipo_via", codigos, ABREVIATURAS_VIA)
        if tipo_via is not None and fila["tipo_de_via"] is None:
            n["tipo_via_sin_codigo"] += 1
            obs("TIPO_VIA_NO_LEIDO", "predio", f'tipo_de_via "{tipo_via}" → vacío')
        tipo_unidad = fila["tipo_de_unidad_urbana"]
        fila["tipo_de_unidad_urbana"], por_alias = _tipo_unidad_leido(tipo_unidad, alias)
        if tipo_unidad is not None and fila["tipo_de_unidad_urbana"] is None:
            n["tipo_unidad_urbana_sin_tipo"] += 1
            obs("TIPO_UU_NO_LEIDO", "predio", f'tipo_de_unidad_urbana "{tipo_unidad}" → vacío')
        elif por_alias:
            st = tipo_unidad.upper() == "ST." and fila["tipo_de_unidad_urbana"] == "SECTOR"
            obs("TIPO_UU_ST_COMO_SECTOR" if st else "OTRA", "predio",
                f'tipo_de_unidad_urbana "{tipo_unidad}" → {fila["tipo_de_unidad_urbana"]}'
                + ("" if st else " (--tipo-unidad)"))
        n["tipo_predio_vacio"] += fila["tipo_predio"] is None
        primera = contribuyente not in vistos
        vistos.add(contribuyente)
        for campo, tabla in CODIGOS_PADRON.items():
            fila[campo] = codigo(fila[campo])
            if fila[campo] is not None and _sin_mapeo(fila[campo], tabla, codigos):
                n[f"{campo}_sin_mapeo"] += 1
                regla, objeto = SIN_MAPEO[campo]
                if objeto == "contribuyente" and not primera:
                    continue
                de_srtm = " de srtm" if tabla == "uso" else ""
                obs(regla, objeto, f'{campo} "{fila[campo]}" sin código{de_srtm} en {CODIGOS_JSON}',
                    None if objeto == "contribuyente" else predio)
        filas.append(fila)
    n["filas_padron"] = len(filas)
    n["predios"] = len({f["codigo_predio"] for f in filas if f["codigo_predio"]})
    n["contribuyentes"] = len({f["codigo_contribuyente"] for f in filas if f["codigo_contribuyente"]})
    return filas, n


def titulares_de(filas):
    """codigo_predio -> its contribuyentes, each once, in the padrón's order."""
    titulares = {}
    for fila in filas:
        if fila["codigo_predio"] and fila["codigo_contribuyente"]:
            titulares.setdefault(fila["codigo_predio"], {}).setdefault(fila["codigo_contribuyente"])
    return {code: list(t) for code, t in titulares.items()}


def convertir_construcciones(rows, mapa, titulares, codigos, letras, compartidos=(), hoja=HOJA_CONSTRUCCIONES,
                             observaciones=None):
    """(rows of the output's construcciones sheet, counts): each nivel once per contribuyente of its predio.
    `compartidos` are the SAT codes the importer splits into distinct predios (#207). What it empties or flags goes to
    `observaciones` (#297) once per DJ the nivel goes to, so each copy carries it."""
    n = Counter(dict.fromkeys(CONTEOS_CONSTRUCCIONES, 0))
    compartidos = set(compartidos)
    filas, con_niveles = [], set()
    for row in rows:
        n["filas_construcciones"] += 1
        fila_origen = row.get("_fila")
        base = dict.fromkeys(CONSTRUCCION_SALIDA)
        for campo, columna in mapa.items():
            base[campo] = _celda(row, columna, Counter())
        base["hoja_origen"], base["fila_origen"] = hoja, fila_origen
        code = codigo(base["codigo_predio"])
        if code not in titulares:
            n["construcciones_sin_predio"] += 1
            observar(observaciones, "NIVEL_SIN_PREDIO", "nivel_construccion", code, None,
                     f"cod_predio {code} no está en el padrón: el nivel se descarta", hoja, fila_origen)
            continue
        base["codigo_predio"] = code
        propias = []  # (regla, detalle) of this row, for each DJ it goes to
        nivel = codigo(base["nivel"])
        if nivel is not None and not nivel.isdigit():
            n["niveles_sin_numero"] += 1
            propias.append(("NIVEL_NO_LEIDO", f'nivel "{nivel}" → vacío'))
            nivel = None
        base["nivel"] = nivel
        anio = codigo(base["ano_construccion"])
        if anio is not None and not anio.isdigit():
            n["anios_ilegibles"] += 1
            propias.append(("ANIO_NO_LEIDO", f'ano_construccion "{anio}" → vacío'))
            anio = None
        base["ano_construccion"] = anio
        for campo, tabla, clave, regla in (
                ("material_predominante", "material", "materiales_sin_codigo", "MATERIAL_NO_LEIDO"),
                ("estado_de_conservacion", "estado_conservacion", "estados_sin_codigo", "ESTADO_NO_LEIDO")):
            valor = base[campo]
            base[campo] = _codigo_o_texto(valor, tabla, codigos)
            if valor is not None and base[campo] is None:
                n[clave] += 1
                propias.append((regla, f'{campo} "{valor}" → vacío'))
        vaciadas = []
        for campo in LETRAS:
            letra = (base[campo] or "").upper()
            if letra and letra not in letras:
                n["letras_vaciadas"] += 1
                vaciadas.append(f'{campo} "{base[campo]}"')
                letra = ""
            base[campo] = letra or None
        if vaciadas:
            propias.append(("LETRA_FUERA_DE_RANGO", ", ".join(vaciadas) + (" → vacías" if len(vaciadas) > 1
                                                                              else " → vacía")))
        con_niveles.add(code)
        todos = titulares[code]
        for posicion, contribuyente in enumerate(todos):
            filas.append({**base, "id": str(len(filas) + 1), "codigo_contribuyente": contribuyente})

            def obs(regla, detalle):
                observar(observaciones, regla, "nivel_construccion", code, contribuyente, detalle, hoja, fila_origen)

            for regla, detalle in propias:
                obs(regla, detalle)
            if code in compartidos:
                n["niveles_en_codigos_compartidos"] += 1
                obs("CODIGO_COMPARTIDO_DIVIDIDO", f"el código {code} se divide en predios {code}-<n> (#207): el nivel "
                                                  "va a la DJ de cada titular")
            if posicion > 0:
                n["niveles_copiados"] += 1
                obs("NIVEL_COPIADO_A_COTITULAR", f"nivel {nivel or 'sin número'} copiado a la DJ del co-titular "
                                                 f"{posicion + 1} de {len(todos)} (contribuyente {contribuyente})")
    n["niveles"] = len(filas)
    n["predios_con_niveles"] = len(con_niveles)
    return filas, n


# ---------------------------------------------------------------------------
# the workbook
# ---------------------------------------------------------------------------

def _columna(i):
    letras = ""
    while i >= 0:
        letras = chr(ord("A") + i % 26) + letras
        i = i // 26 - 1
    return letras


def _xml(text):
    text = NO_XML.sub("", text)
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _hoja(f, header, filas):
    letras = [_columna(i) for i in range(len(header))]
    f.write(b'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
            b'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>')
    for r, valores in enumerate([dict(zip(header, header))] + filas, start=1):
        celdas = "".join(f'<c r="{letras[i]}{r}" t="inlineStr"><is><t xml:space="preserve">{_xml(str(valores[c]))}'
                         "</t></is></c>" for i, c in enumerate(header) if valores.get(c) is not None)
        f.write(f'<row r="{r}">{celdas}</row>'.encode("utf-8"))
    f.write(b"</sheetData></worksheet>")


def write_xlsx(path, hojas):
    """An xlsx of inline strings, one sheet per (name, header, rows), with mode 0o600: it holds datos personales."""
    tipos = "".join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/'
                    'vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for i in range(1, len(hojas) + 1))
    sheets = "".join(f'<sheet name="{_xml(nombre)}" sheetId="{i}" r:id="rId{i}"/>'
                     for i, (nombre, _, _) in enumerate(hojas, start=1))
    rels = "".join(f'<Relationship Id="rId{i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
                   f'relationships/worksheet" Target="worksheets/sheet{i}.xml"/>' for i in range(1, len(hojas) + 1))
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as out, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
                   '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                   '<Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/xl/workbook.xml" ContentType="application/'
                   f'vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>{tipos}</Types>')
        z.writestr("_rels/.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
                   '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/'
                   'officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr("xl/workbook.xml",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
                   '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                   'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                   f'<sheets>{sheets}</sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels",
                   '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
                   f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{rels}'
                   '</Relationships>')
        for i, (_, header, filas) in enumerate(hojas, start=1):
            with z.open(f"xl/worksheets/sheet{i}.xml", "w", force_zip64=True) as f:
                _hoja(f, header, filas)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _dentro_del_repositorio(path):
    real, repo = os.path.realpath(path), os.path.realpath(REPOSITORIO)
    return os.path.commonpath([real, repo]) == repo


def _faltan(rows, mapa, hoja):
    """The mapped columns the sheet lacks (none can be told on a sheet without rows)."""
    if not rows:
        return []
    return [f"{hoja}: no column '{columna}' (field {campo})" for campo, columna in mapa.items()
            if columna is not None and columna not in rows[0]]


def _parse_args(argv):
    p = argparse.ArgumentParser(
        description="Turn Ica's padrón SAT depurado and its construcciones into the workbook of import_padron_ica.py.")
    p.add_argument("--padron", required=True, help="BASE DE RENTAS DEPURADA_<fecha>.xlsx")
    p.add_argument("--hoja-padron", default=HOJA_PADRON, help=f"its sheet (default '{HOJA_PADRON}')")
    p.add_argument("--construcciones", required=True, help="REPORTE_SUBVALUADOS.xlsx")
    p.add_argument("--hoja-construcciones", default=HOJA_CONSTRUCCIONES,
                   help=f"its sheet (default '{HOJA_CONSTRUCCIONES}')")
    p.add_argument("--salida", required=True, help=f"directory for {LIBRO}, outside the repository (datos personales)")
    for name in ("map-padron", "map-construccion"):
        p.add_argument(f"--{name}", action="append", default=[], metavar="CAMPO=COLUMNA",
                       help="the source column of an output column (repeatable; CAMPO= leaves it empty)")
    p.add_argument("--tipo-unidad", action="append", default=[], metavar="TEXTO=TIPO",
                   help="a tipo de unidad urbana text that means an option of tipo_unidad_urbana (repeatable)")
    p.add_argument("--excluir-predios", metavar="CSV",
                   help="a csv with a codigo_predio column: those predios leave the three sheets (predios_excluidos)")
    p.add_argument("--codigos", default=default_codigos_path(), help="Ica's code tables (default data/ica/codigos.json)")
    p.add_argument("--model", default=default_model_path())
    return p.parse_args(argv)


def leer_excluidos(path):
    """The codigo_predio column of a csv, as codes."""
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if "codigo_predio" not in (reader.fieldnames or []):
            raise ValueError(f"--excluir-predios {path}: no column 'codigo_predio'")
        return {code for code in (codigo(r["codigo_predio"]) for r in reader) if code}


def excluir(rows, columna, excluidos):
    """(the rows whose predio is not excluded, the excluded codes found)."""
    quedan, vistos = [], set()
    for row in rows:
        code = codigo(row.get(columna)) if columna else None
        if code in excluidos:
            vistos.add(code)
        else:
            quedan.append(row)
    return quedan, vistos


def _error(problems):
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    return 2


def main(argv=None):
    args = _parse_args(sys.argv[1:] if argv is None else argv)
    if _dentro_del_repositorio(args.salida):
        return _error([f"--salida {args.salida} está dentro del repositorio: la salida tiene datos personales "
                       "(Ley 29733), escríbala fuera"])
    try:
        mapa_padron = parse_map(args.map_padron, PADRON_COLUMNS)
        mapa_construccion = parse_map(args.map_construccion, CONSTRUCCION_COLUMNS)
        alias = parse_alias(args.tipo_unidad)
        codigos, letras = load_codigos(args.codigos), load_letras(args.model)
        excluidos = leer_excluidos(args.excluir_predios) if args.excluir_predios else set()
    except (OSError, ValueError, KeyError) as e:
        return _error([str(e)])
    try:
        padron_rows = read_xlsx(args.padron, args.hoja_padron)
        construccion_rows = read_xlsx(args.construcciones, args.hoja_construcciones)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as e:
        return _error([f"input: {e}"])
    problems = _faltan(padron_rows, mapa_padron, args.hoja_padron)
    problems += _faltan(construccion_rows, mapa_construccion, args.hoja_construcciones)
    if problems:
        return _error(problems)

    padron_rows, fuera = excluir(padron_rows, mapa_padron["codigo_predio"], excluidos)
    construccion_rows, fuera_niveles = excluir(construccion_rows, mapa_construccion["codigo_predio"], excluidos)

    observaciones = []
    padron, n_padron = convertir_padron(padron_rows, mapa_padron, codigos, alias, args.hoja_padron, observaciones)
    _, compartidos = codigos_derivados(padron)
    niveles, n_niveles = convertir_construcciones(construccion_rows, mapa_construccion, titulares_de(padron), codigos,
                                                  letras, {c["codigo"] for c in compartidos},
                                                  args.hoja_construcciones, observaciones)
    try:
        os.makedirs(args.salida, mode=0o700, exist_ok=True)
        write_xlsx(os.path.join(args.salida, LIBRO),
                   [("padron", PADRON_SALIDA + ORIGEN, padron),
                    ("construcciones", CONSTRUCCION_SALIDA + ORIGEN, niveles),
                    ("observaciones", OBSERVACIONES_SALIDA, observaciones)])
    except OSError as e:
        return _error([f"output: {e}"])

    for conteos, n in ((CONTEOS_PADRON, n_padron), (CONTEOS_CONSTRUCCIONES, n_niveles)):
        for clave in conteos:
            print(f"{clave}: {n[clave]}")
    print(f"codigos_compartidos: {len(compartidos)}")
    print(f"predios_excluidos: {len(fuera | fuera_niveles)}")
    print(f"observaciones: {len(observaciones)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
