#!/usr/bin/env python3
"""Comprueba los perfiles de la demo (perfiles.json) contra el Core de la demo: entra con cada cuenta, llama lo que
usan sus pantallas (debe responder 200) y lo que no le toca (debe responder 403), e imprime la tabla.

Lo que un perfil escribe ya lo probó sembrar_demo.py, que siembra cada parte como su perfil. Aquí las escrituras son
las que deben fallar: van con un cuerpo válido, así que un 403 es del permiso y no de una validación (si alguna
pasara, crearía un registro: la tabla lo marca).

Uso: python3 docs/manuales/demo/verificar_perfiles.py [--core http://localhost:8098]
Salida: 0 todo como se espera, 1 alguna llamada no respondió lo esperado, 2 --core no es el de la demo.
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "model"))
sys.path.insert(0, HERE)

from core_client import CoreError  # noqa: E402
from guardia import exigir_core_demo  # noqa: E402
from sembrar_demo import ANIO, Core, leer_perfiles  # noqa: E402

TODOS = (200, 201, 202, 204, 400, 403, 404, 409, 410, 422, 500)


def ids_de(adm):
    """Los registros de la demo que las llamadas nombran."""
    def uno(objeto, **f):
        r = adm.buscar(objeto, **f)
        if not r:
            raise SystemExit(f"error: falta {objeto} {f}: ¿se sembró la demo?")
        return r["id"]

    c01 = uno("contribuyente", codigo_anterior="DEMO-001")
    p1 = uno("predio", codigo="01-01-0001")
    dj = adm.get(f"/api/srtm/predios/{p1}/declaraciones?anio={ANIO}")[0]["declaracion"]["id"]
    emisiones = [e for e in adm.get(f"/api/srtm/emisiones?anio={ANIO}") if e.get("estado") == "TERMINADA"]
    return {
        "c01": c01, "c34": uno("contribuyente", codigo_anterior="DEMO-034"), "p1": p1, "dj": dj,
        "c13": uno("contribuyente", codigo_anterior="DEMO-013"), "c35": uno("contribuyente", codigo_anterior="DEMO-035"),
        "p_acta": uno("predio", codigo="02-01-0003"),
        "lote": uno("catastro_fiscal", codigo_cpu="1203020101001"),
        "acta": uno("papeleta", numero="AC-2026-0003"), "ris": adm.buscar("resolucion_gerencia", numero="RIS-2026-000001")["id"],
        "anuncio": uno("anuncio", clave_idempotencia="demo-anuncio-01"),
        "emision": emisiones[0]["id"] if emisiones else "sin-emision",
        "cuota_anulada": adm.get(f"/api/objects/anulacion_cuota_arbitrio/records?size=1")["content"][0]["attributes"]["clave"],
        "np2": uno("notificacion_administrativa", numero="NP-2026-0002"), "acta_anulada": uno("papeleta", numero="AC-2026-0002"),
        "anuncio_cesado": uno("anuncio", clave_idempotencia="demo-anuncio-04"),
        "cuota": adm.get(f"/api/srtm/arbitrios?anio={ANIO}&predio={p1}&size=1")["content"][0]["id"],
    }  # fmt: skip


def cuerpos(i):
    contribuyente = {"tipo_contribuyente": "PERSONA NATURAL", "tipo_documento": "DNI", "numero_documento": "98999999",
                     "apellido_paterno": "NO", "apellido_materno": "CREAR", "nombres": "VERIFICACION"}  # fmt: skip
    acta = {"numero": "AC-VERIF-0001", "fecha_infraccion": "2026-09-01", "hora_infraccion": "10:00", "lugar": "Verificación",
            "codigo": "070", "reincidencia": "PRIMERA", "obligado": i["c01"], "contribuyente": i["c01"], "predio": None,
            "notificacion_previa": None, "expediente": None, "inspector": None, "descripcion_hecho": None,
            "observacion": "Verificación de permisos"}  # fmt: skip
    anuncio = {"contribuyente": i["c34"], "clase": "PANEL", "tipo": "AVISO_SIMPLE", "direccion": "Verificación", "area": 1, "lados": 1,
               "cantidad": 1, "fecha_autorizacion": "2026-09-01", "vigencia_hasta": "2026-12-31", "observacion": "Verificación de permisos"}  # fmt: skip
    notificacion = {"numero": "NP-VERIF-0001", "fecha": "2026-09-01", "contribuyente": i["c01"], "predio": None, "direccion": "Verificación",
                    "motivo": "Verificación", "plazo_dias": 5, "observacion": "Verificación de permisos"}  # fmt: skip
    return {
        "contribuyente": ("POST", "/api/srtm/contribuyentes", contribuyente),
        "predio": ("POST", "/api/srtm/predios", {"codigo": "VERIF-0001", "direccion": "Verificación", "tipo_predio": "PREDIO URBANO"}),
        "nivel": ("POST", f"/api/srtm/declaraciones/{i['dj']}/niveles", {
            "tipo_nivel": "PISO", "numero_piso": 9, "anio_construccion": 2020, "mes_construccion": 1, "material": "LADRILLO",
            "estado_conservacion": "BUENO", "area_construida": 10}),
        "determinacion": ("POST", "/api/srtm/arbitrios/determinaciones", {"anio": ANIO, "observacion": "Verificación de permisos"}),
        "determinar_predio": ("POST", f"/api/srtm/predios/{i['p1']}/arbitrios", {"anio": ANIO, "observacion": "Verificación de permisos"}),
        "anular_cuota": ("POST", f"/api/srtm/arbitrios/cuotas/{i['cuota']}/anulacion", {"motivo": "Verificación", "observacion": "Verificación de permisos"}),
        "emision": ("POST", "/api/srtm/emisiones", {"anio": ANIO, "formato": "PDF"}),
        "acta": ("POST", "/api/srtm/infracciones/actas", acta),
        "notificacion": ("POST", "/api/srtm/infracciones/notificaciones", notificacion),
        "anuncio": ("POST", "/api/srtm/anuncios", anuncio),
        "cese": ("POST", f"/api/srtm/anuncios/{i['anuncio']}/cese", {"fecha": "2026-10-15", "motivo": "Verificación", "observacion": "Verificación de permisos"}),
        "brecha": ("POST", "/api/srtm/brecha/corridas", {"anio": ANIO}),
        "lote": ("POST", "/api/srtm/catastro", {"codigo_cpu": "VERIF-0001"}),
    }  # fmt: skip


def lecturas(i):
    padron = [
        "/api/srtm/resumen", "/api/srtm/catalogos", "/api/srtm/contribuyentes?q=DEMO&page=0&size=10",
        f"/api/srtm/contribuyentes/{i['c01']}?anio={ANIO}", f"/api/srtm/contribuyentes/{i['c01']}/declaraciones?anio={ANIO}",
        f"/api/srtm/contribuyentes/{i['c01']}/domicilios", f"/api/srtm/contribuyentes/{i['c01']}/relacionados",
        f"/api/srtm/contribuyentes/{i['c01']}/medios-contacto", f"/api/srtm/contribuyentes/{i['c01']}/sustentos",
        f"/api/srtm/predios/{i['p1']}?anio={ANIO}", f"/api/srtm/predios/{i['p1']}/declaraciones?anio={ANIO}",
        f"/api/srtm/declaraciones/{i['dj']}", f"/api/srtm/declaraciones/{i['dj']}/niveles", f"/api/srtm/declaraciones/{i['dj']}/obras",
        f"/api/srtm/declaraciones/{i['dj']}/frentes", f"/api/srtm/declaraciones/{i['dj']}/transferentes",
        "/api/srtm/predios?q=FRESNOS&page=0&size=10", "/api/srtm/predios/buscar?codigo=01-01",
    ]  # fmt: skip
    catalogos = ["/api/srtm/ubigeos", "/api/srtm/vias?q=LOS", "/api/srtm/unidades-urbanas?q=VALLE", "/api/srtm/categorias-valor",
                 "/api/srtm/usos-predio", "/api/srtm/obras-categorias"]  # fmt: skip
    catastro = ["/api/srtm/catastro?codigo_cpu=1203020101", f"/api/srtm/catastro/{i['lote']}",
                "/api/gis/objects/catastro_fiscal/features?bbox=-75.226,-10.951,-75.218,-10.945&geometry=lote_geom"]  # fmt: skip
    documentos = [f"/api/srtm/contribuyentes/{i['c01']}/liquidacion?anio={ANIO}", f"/api/srtm/contribuyentes/{i['c01']}/hr?anio={ANIO}",
                  f"/api/srtm/predios/{i['p1']}/pu?anio={ANIO}"]  # fmt: skip
    arbitrios = [f"/api/srtm/arbitrios/servicios?anio={ANIO}", f"/api/srtm/arbitrios/parametros?anio={ANIO}", f"/api/srtm/arbitrios?anio={ANIO}&size=5",
                 f"/api/srtm/predios/{i['p1']}/arbitrios?anio={ANIO}", f"/api/srtm/contribuyentes/{i['c01']}/arbitrios?anio={ANIO}",
                 f"/api/srtm/contribuyentes/{i['c01']}/hla?anio={ANIO}"]  # fmt: skip
    masivas = [f"/api/srtm/arbitrios/determinaciones?anio={ANIO}", f"/api/srtm/emisiones?anio={ANIO}", f"/api/srtm/emisiones/{i['emision']}",
               f"/api/srtm/emisiones/{i['emision']}/archivo"]  # fmt: skip
    infracciones = ["/api/srtm/infracciones/cuis", "/api/srtm/infracciones/notificaciones", "/api/srtm/infracciones/notificaciones/vencidas",
                    f"/api/srtm/infracciones/notificaciones/por-contribuyente?contribuyente={i['c01']}", "/api/srtm/infracciones/actas",
                    f"/api/srtm/infracciones/actas/{i['acta']}", f"/api/srtm/infracciones/resoluciones/{i['ris']}/pdf",
                    f"/api/srtm/infracciones/panel?anio={ANIO}", f"/api/srtm/infracciones/plazos?anio={ANIO}"]  # fmt: skip
    anuncios = ["/api/srtm/anuncios", f"/api/srtm/anuncios/{i['anuncio']}", f"/api/srtm/anuncios/tasas?anio={ANIO}"]
    # la pestaña de infracciones de una ficha con actas (una anulada; otra con descargo, RIS y su notificación)
    ficha = [f"/api/srtm/contribuyentes/{i['c13']}/infracciones", f"/api/srtm/contribuyentes/{i['c35']}/infracciones",
             f"/api/srtm/predios/{i['p_acta']}/infracciones",
             f"/api/srtm/contribuyentes/{i['c34']}/anuncios", f"/api/srtm/predios/{i['p1']}/anuncios"]  # fmt: skip
    brecha = [f"/api/srtm/brecha/corridas?anio={ANIO}"]
    return {
        "REGISTRADOR": padron + catalogos + catastro + documentos + arbitrios + ficha,
        "ARBITRIOS_EMISION": padron + [r for r in catalogos if "/vias" not in r and "/unidades-urbanas" not in r] + documentos + arbitrios + masivas,
        "FISCALIZADOR": padron[:12] + infracciones + ["/api/srtm/ubigeos"],
        "ANUNCIOS": padron[:12] + anuncios + ["/api/srtm/ubigeos"],
        "CONSULTA": padron + catalogos + catastro + documentos + arbitrios + masivas + infracciones + anuncios + ficha + brecha,
    }


# escrituras que el perfil sí puede: con un cuerpo que pasa el permiso y que las reglas rechazan después (un año sin
# ordenanza, un acto repetido), así se prueba el permiso sin escribir nada
PERMITIDAS = {
    "ARBITRIOS_EMISION": [
        ("POST", "/api/srtm/arbitrios/determinaciones", {"anio": 2027, "observacion": "Verificación de permisos"}, (422,)),
        ("POST", "/api/srtm/emisiones", {"anio": 2027, "formato": "PDF", "documentos": ["HR", "PU", "HLA"]}, (422,)),
        ("POST", "/api/srtm/arbitrios/cuotas/{cuota_anulada}/anulacion", {"motivo": "Verificación", "observacion": "Verificación de permisos"}, (409,)),
    ],
    "FISCALIZADOR": [
        ("POST", "/api/srtm/infracciones/notificaciones/{np2}/subsanacion", {"fecha": "2026-09-30", "observacion": "Verificación de permisos"}, (409,)),
        ("POST", "/api/srtm/infracciones/actas/{acta_anulada}/anulacion", {"motivo": "Verificación", "fecha": "2026-08-20", "observacion": "Verificación de permisos"}, (409,)),
    ],
    "ANUNCIOS": [
        ("POST", "/api/srtm/anuncios/{anuncio_cesado}/cese", {"fecha": "2026-09-30", "motivo": "Verificación", "observacion": "Verificación de permisos"}, (409,)),
    ],
}  # fmt: skip

PROHIBIDOS = {
    "REGISTRADOR": ["determinacion", "determinar_predio", "anular_cuota", "emision", "acta", "notificacion", "anuncio", "cese", "brecha"],
    "ARBITRIOS_EMISION": ["contribuyente", "predio", "nivel", "lote", "acta", "notificacion", "anuncio", "brecha"],
    "FISCALIZADOR": ["contribuyente", "predio", "determinacion", "emision", "anuncio", "cese", "brecha"],
    "ANUNCIOS": ["contribuyente", "predio", "determinacion", "emision", "acta", "notificacion", "brecha"],
    "CONSULTA": ["contribuyente", "predio", "nivel", "lote", "determinacion", "determinar_predio", "anular_cuota", "emision", "acta",
                 "notificacion", "anuncio", "cese", "brecha"],
}  # fmt: skip


def main(argv=None):
    p = argparse.ArgumentParser(description="Comprueba los permisos de los perfiles de la demo.")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8098"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--permitir-otro-core", action="store_true")
    args = p.parse_args(argv)
    core = exigir_core_demo(args.core, args.permitir_otro_core)
    clave = os.environ.get("SRTM_DEMO_PASSWORD", "Demo-2026")
    adm = Core(core, args.email, args.password, "admin").entrar()
    i = ids_de(adm)
    escrituras = cuerpos(i)
    fallos = 0
    print("| perfil | llamada | esperado | obtenido | |")
    print("|---|---|---|---|---|")
    for perfil in leer_perfiles():
        rol = perfil["rol"]
        cli = Core(core, perfil["email"], clave, rol).entrar()
        filas = [("GET", ruta, None, (200,)) for ruta in lecturas(i)[rol]]
        filas += [(m, r.format(**i), b, e) for m, r, b, e in PERMITIDAS.get(rol, [])]
        filas += [(*escrituras[k][:2], escrituras[k][2], (403,)) for k in PROHIBIDOS[rol]]
        for metodo, ruta, cuerpo, esperado in filas:
            try:
                status, resp = cli.llamar(metodo, ruta, cuerpo, esperados=TODOS, binario=ruta.endswith(("/hr?anio=2026", "/pu?anio=2026", "/hla?anio=2026", "/pdf", "/archivo")))
            except CoreError as e:
                status, resp = e.status, e.body
            ok = status in esperado
            fallos += 0 if ok else 1
            extra = ""
            if not ok:
                extra = str(resp)[:160].replace("|", "/")
            elif isinstance(resp, dict) and "bytes" in resp:
                extra = f"{resp['tipo']}, {resp['bytes']} bytes"
            print(f"| {rol} | {metodo} {ruta.split('?')[0]} | {'/'.join(map(str, esperado))} | {status} | {'ok' if ok else 'FALLA'} {extra} |")
    print(f"\n{fallos} fallas")
    return 1 if fallos else 0


if __name__ == "__main__":
    sys.exit(main())
