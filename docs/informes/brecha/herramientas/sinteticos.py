"""Synthetic srtm/catastro fixtures for the corte (docs/superpowers/plans/2026-10-25-corte-analitico.md, task 3).

Two generators, both returning a `Sinteticos`:
  - `minimos()`: the exact, hand-picked dataset of the plan's task 3 (fixed ids, fixed figures), built to exercise
    every edge case `srtm.extraer_srtm` must handle (a contributor cited by nothing, a declaración with no
    origin values, a brecha_predio with no predio, a parámetro ANULADO, ...). Used by `pruebas/test_srtm.py`.
  - `datos(semilla, contribuyentes=40)`: a larger, seeded-random dataset (same srtm/catastro shape) for a
    whole corte to run against (task 5); plan, Rulings de la ejecución, R2, binds its documents: DNIs of 8 digits and
    RUCs of 11 digits with a non-zero first digit, all distinct from every exported code.

Every record is `{"id", "attributes"}`, the shape `Cliente.registros()` yields and the fakes
(`SrtmFalso`/`CatastroFalso`) serve back. Numbers are plain `int`/`float` here (never `decimal.Decimal`):
the fakes serialize fixtures with the stdlib's `json.dumps`, which cannot encode a `Decimal`, and
`Cliente` reads the response back with `parse_float=Decimal` - a short, exact literal like `285.00`
round-trips losslessly through that path (task 2's `test_decimales_exactos` established the same pattern).
"""

import random
from dataclasses import dataclass


@dataclass
class Sinteticos:
    srtm: dict[str, list[dict]]
    catastro: dict[str, list[dict]]
    valuaciones: list[dict]
    titulares: list[dict]
    cambios: list[dict]
    documentos: list[str]
    nombres: list[str]


def _registro(id_, **attributes):
    return {"id": id_, "attributes": attributes}


def _cuadrado(lon, lat, lado=0.0002):
    # a closed GeoJSON Polygon ring, lado° on each side - good enough for a synthetic lote near (-75.73, -14.06)
    anillo = [[lon, lat], [lon + lado, lat], [lon + lado, lat + lado], [lon, lat + lado], [lon, lat]]
    return {"type": "Polygon", "coordinates": [anillo]}


def _unico(valores):
    # order-preserving de-duplication, for documentos/nombres (several records share the same person)
    vistos = []
    for valor in valores:
        if valor is not None and valor not in vistos:
            vistos.append(valor)
    return vistos


# minimos(): fixed ids, named after the plan's task 3 so pruebas/test_srtm.py can import them directly
P1, P2 = "P1", "P2"
C1, C2, C3 = "C1", "C2", "C3"
D1, D2, D3, D4 = "D1", "D2", "D3", "D4"
N1, N2 = "N1", "N2"
O1 = "O1"
R1, R2, R3 = "R1", "R2", "R3"
BP0, BP1, BP2, BP3 = "BP0", "BP1", "BP2", "BP3"
F1, F2, F3 = "F1", "F2", "F3"

DOCUMENTO_C1 = "04587963"
DOCUMENTO_C2 = "20123456789"
DOCUMENTO_C3 = "88888888"
# catastro's own copy of C1's document (no leading zero: both systems write it differently, same person)
DOCUMENTO_TITULAR_F1 = "4587963"


def minimos():
    """Task 3's exact fixture: two predios, three contribuyentes (one cited by nothing), four declaraciones
    across two años, one brecha corrida TERMINADA (plus a decoy of an older one) and a third brecha_predio with
    neither a predio nor a confianza - the two absence-tolerance cases srtm.py must handle."""
    predios = [
        _registro(P1, codigo="0101001", codigo_origen="0101001-1"),
        _registro(P2, codigo="0101002"),
    ]

    contribuyentes = [
        _registro(
            C1,
            tipo_persona="NATURAL",
            tipo_documento="DNI",
            numero_documento=DOCUMENTO_C1,
            nombre_completo="QUISPE MAMANI ROSA",
            domicilio_fiscal="JR SINTETICO 123",
        ),
        _registro(
            C2,
            tipo_persona="JURIDICA",
            tipo_documento="RUC",
            numero_documento=DOCUMENTO_C2,
            razon_social="EMPRESA SINTETICA SAC",
        ),
        # cited by no declaración and no cuota of 2026: must not appear in srtm_contribuyente for that año
        _registro(
            C3,
            tipo_persona="NATURAL",
            tipo_documento="DNI",
            numero_documento=DOCUMENTO_C3,
            nombre_completo="TORRES VEGA LUIS",
        ),
    ]

    declaraciones = [
        _registro(
            D1,
            predio=P1,
            contribuyente=C1,
            anio=2026,
            estado="",
            condicion_propiedad="PROPIETARIO UNICO",
            porcentaje_condominio=100,
            valor_terreno=50000,
            valor_construccion=40000,
            valor_otras_instalaciones=10000,
            valor_autoavaluo=95000,
            valor_afecto=95000,
            deduccion=0,
            # on the whitelist's reject list (plan § Rulings): must never reach srtm_declaracion
            condicion_numero_documento="RES-001-SINTETICA",
            otros_datos="texto libre que tampoco debe salir",
        ),
        _registro(
            D2,
            predio=P2,
            contribuyente=C2,
            anio=2026,
            estado="VIGENTE",
            condicion_propiedad="PROPIETARIO UNICO",
            porcentaje_condominio=100,
            # sin valores de origen (valor_terreno/valor_construccion/valor_otras_instalaciones ausentes)
            valor_autoavaluo=400000,
            valor_afecto=400000,
        ),
        _registro(D4, predio=P1, contribuyente=C1, anio=2026, estado="SUSTITUIDA", valor_afecto=1, sustituye=D1),
        _registro(D3, predio=P1, contribuyente=C1, anio=2025, estado="VIGENTE", valor_afecto=90000),
    ]

    niveles = [
        _registro(
            N1,
            declaracion=D1,
            numero_piso=1,
            anio_construccion=2010,
            material="LADRILLO",
            estado_conservacion="BUENO",
            clasificacion="CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA",
            area_construida=60,
            area_comun=0,
            valor_unitario=350.50,
            porcentaje_depreciacion=10,
            valor_total=18900.00,
            estado="VIGENTE",
        ),
        # tied to D3 (2025): must not show up when extraer_srtm(..., anio=2026, ...) asks for niveles
        _registro(
            N2,
            declaracion=D3,
            numero_piso=1,
            anio_construccion=2009,
            material="LADRILLO",
            estado_conservacion="REGULAR",
            clasificacion="CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA",
            area_construida=55,
            estado="VIGENTE",
        ),
    ]

    obras = [
        _registro(
            O1,
            declaracion=D1,
            tipo_obra="MUROS PERIMETRICOS O CERCOS",
            material="CONCRETO",
            estado_conservacion="BUENO",
            anio_construccion=2015,
            categoria="A",
            cantidad=1,
            total_metrado=20,
            unidad_medida="M2",
            valor=500.00,
            estado="VIGENTE",
        ),
    ]

    def _cuota(id_, contribuyente, numero, revision, monto, fecha_calculo):
        return _registro(
            id_,
            contribuyente=contribuyente,
            anio=2026,
            numero=numero,
            revision=revision,
            monto=monto,
            fecha_calculo=fecha_calculo,
        )

    cuotas = [_cuota(f"CP1-{n}-1", C1, n, 1, 70.00, "2026-01-15") for n in (1, 2, 3, 4)] + [
        # a later recálculo of only cuota 4: its revisión 2 replaces revisión 1 for that numero
        _cuota("CP1-4-2", C1, 4, 2, 75.00, "2026-03-01"),
    ] + [_cuota(f"CP2-{n}-1", C2, n, 1, 587.50, "2026-01-15") for n in (1, 2, 3, 4)]

    corridas = [
        _registro(
            R1,
            anio=2026,
            estado="TERMINADA",
            version_catastro="valuacion-v3",
            iniciado="2026-10-01T09:00:00Z",
            terminado="2026-10-01T10:00:00Z",
            total_predios=1,
            origen="MANUAL",
        ),
        _registro(
            R2,
            anio=2026,
            estado="TERMINADA",
            version_catastro="valuacion-v3",
            iniciado="2026-10-20T09:00:00Z",
            terminado="2026-10-20T10:00:00Z",
            total_predios=3,
            declarado=2635.00,
            catastro=2600.00,
            delta_impuesto=-35.00,
            delta_arbitrios=0.00,
            # plan, Rulings de la ejecución, R6: 7 more columns, populated here so srtm.py's pass-through is exercised
            origen="MANUAL",
            predios_confiable=1,
            predios_dudoso=1,
            predios_sin_levantar=0,
            delta_impuesto_confiable=15.00,
            delta_impuesto_dudoso=-50.00,
            delta_impuesto_sin_levantar=0.00,
        ),
        _registro(
            R3,
            anio=2026,
            estado="EN_CURSO",
            iniciado="2026-10-24T10:00:00Z",
            origen="MANUAL",
        ),
    ]

    brechas = [
        # a decoy under R1 (not the chosen corrida): proves the corte filters brecha_predio by corrida, not año
        _registro(BP0, corrida=R1, predio=P1, codigo_catastro="0101001", categoria="SUBVALUADO", orden=1),
        _registro(
            BP1,
            corrida=R2,
            predio=P1,
            codigo_catastro="0101001",
            categoria="SUBVALUADO",
            impuesto_declarado=285.00,
            impuesto_catastro=300.00,
            delta_impuesto=15.00,
            delta_autoavaluo=5000,
            confianza="CONFIABLE",
            orden=1,
        ),
        _registro(
            BP2,
            corrida=R2,
            predio=P2,
            codigo_catastro="0101002",
            categoria="SOBREVALUADO",
            impuesto_declarado=2350.00,
            impuesto_catastro=2300.00,
            delta_impuesto=-50.00,
            confianza="DUDOSO",
            orden=2,
        ),
        _registro(
            BP3,
            corrida=R2,
            # sin predio (p.ej. un código de catastro que srtm nunca armó)
            codigo_catastro="0101003",
            categoria="OMISO",
            delta_impuesto=120.00,
            # sin "confianza" ni "confianza_motivo": srtm viejo, sin #204 (se exportan null + aviso)
            orden=3,
        ),
    ]

    parametros = [
        _registro(
            "PARAM-UIT-2026",
            tipo="UIT",
            clave="UIT-2026",
            vigencia_desde="2026-01-01",
            vigencia_hasta="2026-12-31",
            valor_numerico=5500,
            estado="VIGENTE",
        ),
        *(
            _registro(
                f"PARAM-TRAMO-{n}",
                tipo="TRAMO_PREDIAL",
                clave=str(n),
                vigencia_desde="2004-11-15",
                valor_numerico=valor,
                estado="VIGENTE",
            )
            for n, valor in ((1, 0.2), (2, 0.6), (3, 1.0))
        ),
        *(
            _registro(
                f"PARAM-LIMITE-{n}",
                tipo="TRAMO_PREDIAL_LIMITE",
                clave=str(n),
                vigencia_desde="2004-11-15",
                valor_numerico=valor,
                estado="VIGENTE",
            )
            for n, valor in ((1, 15), (2, 60))
        ),
        _registro(
            "PARAM-MINIMO",
            tipo="PREDIAL_MINIMO",
            clave="PREDIAL_MINIMO",
            vigencia_desde="2004-11-15",
            valor_numerico=0.6,
            estado="VIGENTE",
        ),
        _registro(
            "PARAM-UIT-2025",
            tipo="UIT",
            clave="UIT-2025",
            vigencia_desde="2025-01-01",
            vigencia_hasta="2025-12-31",
            valor_numerico=5350,
            estado="VIGENTE",
        ),
        _registro(
            "PARAM-UIT-2026-ANULADO",
            tipo="UIT",
            clave="UIT-2026-ANULADO",
            vigencia_desde="2026-01-01",
            vigencia_hasta="2026-12-31",
            valor_numerico=9999,
            estado="ANULADO",
        ),
    ]

    arancel = [
        _registro(
            "ARANCEL-01-2026",
            clave="01|2026",
            id_aranc="01",
            anio=2026,
            valor_m2=120.50,
            cod_via="001",
            cuadra="01",
            lado="PAR",
            estado="VIGENTE",
        ),
        _registro("ARANCEL-02-2026", clave="02|2026", id_aranc="02", anio=2026, valor_m2=95.00, estado="VIGENTE"),
        _registro("ARANCEL-03-2026", clave="03|2026", id_aranc="03", anio=2026, valor_m2=80.00, estado="POR_VERIFICAR"),
        _registro("ARANCEL-01-2025", clave="01|2025", id_aranc="01", anio=2025, valor_m2=110.00, estado="VIGENTE"),
    ]

    srtm = {
        "predio": predios,
        "contribuyente": contribuyentes,
        "declaracion_predial": declaraciones,
        "nivel_construccion": niveles,
        "obra_complementaria": obras,
        "cuota_predial": cuotas,
        "brecha_corrida": corridas,
        "brecha_predio": brechas,
        "parametro_tributario": parametros,
        "arancel": arancel,
    }

    fichas = [
        _registro(
            F1,
            codigo_municipal="0101001",
            estado_levantamiento="COMPLETO",
            vinculo_metodo="CODIGO_RENTAS",
            vinculo_revisado=True,
        ),
        _registro(
            F2,
            codigo_municipal="0101002",
            estado_levantamiento="PARCIAL",
            vinculo_metodo="GEOMETRICO",
            vinculo_revisado=False,
        ),
        _registro(F3, codigo_municipal="0101003", estado_levantamiento="SIN_LEVANTAR"),
    ]

    _CASA = "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA"
    _TIENDAS = "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES"

    def _construccion(id_, predio, codigo_municipal, piso, seccion, area_construida, clasificacion=None):
        return _registro(
            id_,
            predio=predio,
            codigo_municipal=codigo_municipal,
            piso=piso,
            seccion=seccion,
            area_construida=area_construida,
            clasificacion=clasificacion,
        )

    construcciones = [
        _construccion("CC1", F1, "0101001", piso=1, seccion=1, area_construida=60, clasificacion=_CASA),
        _construccion("CC2", F1, "0101001", piso=1, seccion=2, area_construida=40, clasificacion=_TIENDAS),
        _construccion("CC3", F1, "0101001", piso=2, seccion=1, area_construida=30, clasificacion=_TIENDAS),
        _construccion("CC4", F2, "0101002", piso=1, seccion=1, area_construida=50, clasificacion=_CASA),
        _construccion("CC5", F2, "0101002", piso=1, seccion=2, area_construida=50, clasificacion="EDIFICIO-OFICINAS"),
        # predio ajeno: ni F1, ni F2 ni F3 - prueba que el corte no asume que toda construcción tiene su ficha
        _construccion("CC6", "NO-ES-UNA-FICHA", "9999999", piso=1, seccion=1, area_construida=10),
    ]

    obras_catastro = [
        _registro(
            "CO1",
            predio=F1,
            codigo_municipal="0101001",
            codigo_vu="VU-01",
            cantidad=1,
            anio_construccion=2015,
            material="CONCRETO",
            estado_conservacion="BUENO",
        ),
    ]

    catastro = {
        "predio_catastral": fichas,
        "construccion": construcciones,
        "obra_complementaria": obras_catastro,
    }

    titulares = [
        {
            "codigoMunicipal": "0101001",
            "tipoDocumento": "DNI",
            "numeroDocumento": DOCUMENTO_TITULAR_F1,
            "nombre": "QUISPE MAMANI ROSA",
            "porcentaje": 100,
            "condicion": "PROPIETARIO",
        },
        {
            "codigoMunicipal": "0101002",
            "tipoDocumento": "RUC",
            "numeroDocumento": DOCUMENTO_C2,
            "nombre": "EMPRESA SINTETICA SAC",
            "porcentaje": 100,
            "condicion": "PROPIETARIO",
        },
    ]

    valuaciones = [
        {
            "codigoMunicipal": "0101001",
            "codigoCpu": "CPU-0101001",
            "sector": "01",
            "manzana": "01",
            "lote": "001",
            "uso": "CASA HABITACIÓN",
            "areaTerreno": 120.0,
            "areaConstruida": 130.0,
            "terreno": 72000.0,
            "construccion": 58000.0,
            "obras": 2000.0,
            "autoavaluo": 132000.0,
            "motivo": None,
            "llave": "0101001",
            "loteGeom": _cuadrado(-75.7300, -14.0600),
            "estadoLevantamiento": "COMPLETO",
            "vinculoMetodo": "CODIGO_RENTAS",
            "vinculoRevisado": True,
        },
        {
            "codigoMunicipal": "0101002",
            "codigoCpu": "CPU-0101002",
            "sector": "01",
            "manzana": "02",
            "lote": "002",
            "uso": "CASA HABITACIÓN",
            "areaTerreno": 100.0,
            "areaConstruida": 100.0,
            "terreno": 60000.0,
            "construccion": 40000.0,
            "obras": 0.0,
            "autoavaluo": 100000.0,
            "motivo": None,
            "llave": "0101002",
            "loteGeom": _cuadrado(-75.7310, -14.0610),
            "estadoLevantamiento": "PARCIAL",
            "vinculoMetodo": "GEOMETRICO",
            "vinculoRevisado": False,
        },
        {
            "codigoMunicipal": "0101003",
            "codigoCpu": "CPU-0101003",
            "sector": "01",
            "manzana": "03",
            "lote": "003",
            "uso": None,
            "autoavaluo": None,
            "motivo": "SIN_LEVANTAMIENTO",
            "llave": "0101003",
            "loteGeom": None,
            "estadoLevantamiento": "SIN_LEVANTAR",
        },
    ]

    def _cambio(codigo_municipal, objeto, operacion, en, campos=()):
        return {
            "codigoMunicipal": codigo_municipal,
            "objeto": objeto,
            "operacion": operacion,
            "campos": list(campos),
            "en": en,
            "entrega": None,
        }

    cambios = [
        _cambio("0101001", "construccion", "CREATE", "2026-10-20T08:00:00Z"),
        _cambio("0101002", "predio_catastral", "UPDATE", "2026-10-21T09:00:00Z", campos=("uso", "area_terreno")),
        _cambio("0101001", "obra_complementaria", "DELETE", "2026-10-22T10:00:00Z"),
    ]

    documentos = _unico([DOCUMENTO_C1, DOCUMENTO_C2, DOCUMENTO_C3, DOCUMENTO_TITULAR_F1])
    nombres = _unico(["QUISPE MAMANI ROSA", "EMPRESA SINTETICA SAC", "TORRES VEGA LUIS"])

    return Sinteticos(
        srtm=srtm,
        catastro=catastro,
        valuaciones=valuaciones,
        titulares=titulares,
        cambios=cambios,
        documentos=documentos,
        nombres=nombres,
    )


# datos(): a larger, seeded-random dataset for a whole corte to run against (task 5)

_NOMBRES_PILA = ("MARIA", "JOSE", "LUIS", "ROSA", "CARMEN", "JUAN", "ANA", "PEDRO", "LUCIA", "MIGUEL")
_APELLIDOS = ("QUISPE", "MAMANI", "TORRES", "VEGA", "FLORES", "RAMOS", "HUAMAN", "CONDORI", "ROJAS", "PAREDES")
_CLASIFICACIONES = (
    "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA",
    "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES",
    "EDIFICIO-OFICINAS",
)
_CATEGORIAS_BRECHA = ("OMISO", "SIN_CATASTRO", "SIN_VALOR", "SUBVALUADO", "SOBREVALUADO", "COINCIDE")
_CONFIANZAS = ("CONFIABLE", "DUDOSO")
_ANIO = 2026


def _dni(rng, usados):
    while True:
        numero = "".join(rng.choice("0123456789") for _ in range(8))
        if numero not in usados:
            usados.add(numero)
            return numero


def _ruc(rng, usados):
    while True:
        numero = rng.choice("123456789") + "".join(rng.choice("0123456789") for _ in range(10))
        if numero not in usados:
            usados.add(numero)
            return numero


def datos(semilla, contribuyentes=40):
    """A seeded-random dataset of `contribuyentes` contributors (predio + declaración + nivel + 4 cuotas each,
    one brecha_predio under a single corrida TERMINADA) plus a matching catastro: a ficha, titular and
    valuación per predio, consistent by código. Documents follow plan, Rulings de la ejecución, R2: 8-digit DNIs and
    11-digit RUCs (first digit non-zero), never repeated and never equal to any predio/ficha código (those
    use a distinct, shorter "0101NNN" shape, so a collision is not just unlikely - it cannot happen)."""
    rng = random.Random(semilla)
    documentos_usados = set()

    predios, contribuyentes_filas, declaraciones, niveles, cuotas = [], [], [], [], []
    fichas, construcciones, titulares, valuaciones, brechas = [], [], [], [], []
    documentos, nombres = [], []

    for i in range(contribuyentes):
        codigo = f"0101{i + 1:03d}"
        predio_id, contribuyente_id, declaracion_id = f"predio-{i}", f"contribuyente-{i}", f"declaracion-{i}"
        es_juridica = rng.random() < 0.2

        if es_juridica:
            numero_documento = _ruc(rng, documentos_usados)
            nombre = f"EMPRESA SINTETICA {i + 1} SAC"
            atributos_persona = {"tipo_persona": "JURIDICA", "tipo_documento": "RUC", "razon_social": nombre}
        else:
            numero_documento = _dni(rng, documentos_usados)
            nombre = f"{rng.choice(_APELLIDOS)} {rng.choice(_APELLIDOS)} {rng.choice(_NOMBRES_PILA)}"
            atributos_persona = {"tipo_persona": "NATURAL", "tipo_documento": "DNI", "nombre_completo": nombre}
        documentos.append(numero_documento)
        nombres.append(nombre)

        predios.append(_registro(predio_id, codigo=codigo))
        contribuyentes_filas.append(
            _registro(contribuyente_id, numero_documento=numero_documento, **atributos_persona)
        )

        valor_terreno = 20000 + 500 * i
        valor_construccion = 15000 + 300 * i
        valor_autoavaluo = valor_terreno + valor_construccion
        declaraciones.append(
            _registro(
                declaracion_id,
                predio=predio_id,
                contribuyente=contribuyente_id,
                anio=_ANIO,
                estado="VIGENTE",
                porcentaje_condominio=100,
                valor_terreno=valor_terreno,
                valor_construccion=valor_construccion,
                valor_autoavaluo=valor_autoavaluo,
                valor_afecto=valor_autoavaluo,
                deduccion=0,
            )
        )

        area = 40 + (i % 12) * 10
        niveles.append(
            _registro(
                f"nivel-{i}",
                declaracion=declaracion_id,
                numero_piso=1,
                area_construida=area,
                clasificacion=rng.choice(_CLASIFICACIONES),
                estado="VIGENTE",
            )
        )

        monto_cuota = round(valor_autoavaluo * 0.003 / 4, 2)
        for numero in (1, 2, 3, 4):
            cuotas.append(
                _registro(
                    f"cuota-{i}-{numero}",
                    contribuyente=contribuyente_id,
                    anio=_ANIO,
                    numero=numero,
                    revision=1,
                    monto=monto_cuota,
                    fecha_calculo=f"{_ANIO}-01-15",
                )
            )

        fichas.append(
            _registro(
                f"ficha-{i}",
                codigo_municipal=codigo,
                estado_levantamiento="COMPLETO",
                vinculo_metodo="CODIGO_RENTAS",
                vinculo_revisado=True,
            )
        )
        construcciones.append(
            _registro(
                f"construccion-{i}", predio=f"ficha-{i}", codigo_municipal=codigo, piso=1, seccion=1, area_construida=area
            )
        )
        titulares.append(
            {
                "codigoMunicipal": codigo,
                "tipoDocumento": atributos_persona["tipo_documento"],
                "numeroDocumento": numero_documento,
                "nombre": nombre,
                "porcentaje": 100,
                "condicion": "PROPIETARIO",
            }
        )
        autoavaluo_catastro = valor_autoavaluo * (0.9 + 0.2 * rng.random())
        lon, lat = -75.73 - 0.001 * i, -14.06 + 0.001 * i
        valuaciones.append(
            {
                "codigoMunicipal": codigo,
                "codigoCpu": f"CPU-{codigo}",
                "areaTerreno": float(area),
                "areaConstruida": float(area),
                "terreno": float(valor_terreno),
                "construccion": float(valor_construccion),
                "obras": 0.0,
                "autoavaluo": round(autoavaluo_catastro, 2),
                "motivo": None,
                "llave": codigo,
                "loteGeom": _cuadrado(lon, lat),
                "estadoLevantamiento": "COMPLETO",
                "vinculoMetodo": "CODIGO_RENTAS",
                "vinculoRevisado": True,
            }
        )

        categoria = rng.choice(_CATEGORIAS_BRECHA)
        impuesto_declarado = round(monto_cuota * 4, 2)
        impuesto_catastro = round(impuesto_declarado * (0.85 + 0.3 * rng.random()), 2)
        brechas.append(
            _registro(
                f"brecha-predio-{i}",
                corrida="corrida-1",
                predio=predio_id,
                codigo_catastro=codigo,
                categoria=categoria,
                autoavaluo_declarado=valor_autoavaluo,
                autoavaluo_catastro=round(autoavaluo_catastro, 2),
                delta_autoavaluo=round(autoavaluo_catastro - valor_autoavaluo, 2),
                impuesto_declarado=impuesto_declarado,
                impuesto_catastro=impuesto_catastro,
                delta_impuesto=round(impuesto_catastro - impuesto_declarado, 2),
                confianza=rng.choice(_CONFIANZAS),
                orden=i + 1,
            )
        )

    corridas = [
        _registro(
            "corrida-1",
            anio=_ANIO,
            estado="TERMINADA",
            version_catastro="valuacion-v3",
            iniciado=f"{_ANIO}-10-20T09:00:00Z",
            terminado=f"{_ANIO}-10-20T10:00:00Z",
            total_predios=contribuyentes,
            origen="MANUAL",
        )
    ]

    srtm = {
        "predio": predios,
        "contribuyente": contribuyentes_filas,
        "declaracion_predial": declaraciones,
        "nivel_construccion": niveles,
        "obra_complementaria": [],
        "cuota_predial": cuotas,
        "brecha_corrida": corridas,
        "brecha_predio": brechas,
        "parametro_tributario": minimos().srtm["parametro_tributario"],
        "arancel": minimos().srtm["arancel"],
    }
    catastro = {"predio_catastral": fichas, "construccion": construcciones, "obra_complementaria": []}
    cambios = [
        {
            "codigoMunicipal": predios[0]["attributes"]["codigo"],
            "objeto": "construccion",
            "operacion": "CREATE",
            "campos": [],
            "en": f"{_ANIO}-10-20T08:00:00Z",
            "entrega": None,
        },
        # the same `en`: CatastroFalso's barajar may swap these two, so catastro_cambio's own sort is exercised
        {
            "codigoMunicipal": predios[1]["attributes"]["codigo"],
            "objeto": "predio_catastral",
            "operacion": "UPDATE",
            "campos": ["uso"],
            "en": f"{_ANIO}-10-20T08:00:00Z",
            "entrega": None,
        },
    ]

    return Sinteticos(
        srtm=srtm,
        catastro=catastro,
        valuaciones=valuaciones,
        titulares=titulares,
        cambios=cambios,
        documentos=_unico(documentos),
        nombres=_unico(nombres),
    )
