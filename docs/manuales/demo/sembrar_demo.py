#!/usr/bin/env python3
"""Siembra la base de la demostración de los manuales (Core de la demo, :8098): reproducible e idempotente.

En orden:
  a) los cargadores del repo (model/): el modelo, los parámetros del predial, los catálogos y la municipalidad, la tasa
     de anuncios de Perené, los plazos y feriados FICTICIOS de las infracciones (datos/), el CUIS de Perené 2026 y los
     arbitrios 2026 FICTICIOS ("Ordenanza ficticia DEMO 000-2026-MDP"): tasas derivadas con import_tasas_arbitrio.py de
     model/data/arbitrios/ficticio-*.csv (más las filas de datos/*-extra.csv), zonas, afluencias y una zona por
     ubicación;
  b) los roles y las cuentas de perfiles.json;
  c) datos sintéticos por la api de srtm (/api/srtm, así corren sus reglas), cada parte como el perfil que la haría:
     contribuyentes con sus listas, predios con su DJ 2026 y sus listas, un condominio 60/40, una DJ anulada, el
     catastro fiscal, la ordenanza (import_arbitrios.py), la determinación masiva de arbitrios 2026 y la anulación de
     una cuota, la emisión masiva 2026 (HR, PU y HLA), infracciones, anuncios en sus cuatro estados y un intento de
     brecha.

Idempotente: busca cada cosa por su clave natural (codigo_anterior DEMO-NNN del contribuyente, código del predio,
codigo_cpu, número de la notificación o del acta, Idempotency-Key del anuncio...) antes de crearla, y la segunda
corrida no crea nada. Fechas fijas de 2026 (un "hoy" de 2026-10-15), salvo las que sella el servidor.

Corre solo contra el Core de la demo (guardia.py): otro puerto pide --permitir-otro-core, y el 8090 nunca.

Uso: python3 docs/manuales/demo/sembrar_demo.py [--core http://localhost:8098] [--dry-run] [--solo-perfiles]
Salida: 0 ok, 1 Core rechazó algo, 2 --core no es el de la demo.
"""
import argparse
import csv
import json
import os
import random
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
MODEL = os.path.join(REPO, "model")
DATOS = os.path.join(HERE, "datos")
GENERADOS = os.path.join(REPO, "build", "demo", "datos")
sys.path.insert(0, MODEL)
sys.path.insert(0, HERE)

from core_client import Client, CoreError  # noqa: E402
from guardia import exigir_core_demo  # noqa: E402

ANIO = 2026
HOY = "2026-10-15"  # el "hoy" fijo de la demo; ningún acto se fecha después del hoy real del servidor
NORMA_DEMO = "Ordenanza ficticia DEMO 000-2026-MDP"
UBIGEO = {"ubigeo": "120302", "departamento": "JUNIN", "provincia": "CHANCHAMAYO", "distrito": "PERENE"}
SERVICIOS_TASAS = "BARRIDO_CALLES=BAR,RECOLECCION_RESIDUOS=RRS,PARQUES_JARDINES=PYJ,SERENAZGO=SER"


# --- cliente ---------------------------------------------------------------------------------------------------


class Core(Client):
    """El Client de model/core_client.py, con cabeceras propias, sin excepción para los estados esperados, y otro
    login si el token venció a media siembra."""

    def __init__(self, base_url, email, password, nombre):
        super().__init__(base_url)
        self.email, self.password, self.nombre = email, password, nombre

    def entrar(self):
        self.login(self.email, self.password)
        return self

    def llamar(self, method, path, body=None, headers=None, esperados=(200, 201, 202, 204), binario=False, reintento=True, caidas=0):
        """(status, json o bytes): un estado fuera de `esperados` es CoreError. Una lectura que encuentra a Core sin
        base (5xx o sin conexión) espera y se repite, hasta 6 veces; una escritura no: la siembra se vuelve a correr."""
        cabeceras = {"Content-Type": "application/json", "Authorization": f"Bearer {self.token}", **(headers or {})}
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method, headers=cabeceras)
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                status, raw, tipo = resp.status, resp.read(), resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as e:
            status, raw, tipo = e.code, e.read(), e.headers.get("Content-Type", "")
        except (urllib.error.URLError, ConnectionError, OSError) as e:
            status, raw, tipo = f"connection failed: {getattr(e, 'reason', e)}", b"", ""
        if method == "GET" and (not isinstance(status, int) or status >= 500) and caidas < 6:
            print(f"  (Core respondió {status} a GET {path.split('?')[0]}: se reintenta en 15 s)")
            time.sleep(15)
            return self.llamar(method, path, body, headers, esperados, binario, reintento, caidas + 1)
        if not isinstance(status, int):
            raise CoreError(status, "")
        if status == 401 and reintento:
            self.entrar()
            return self.llamar(method, path, body, headers, esperados, binario, reintento=False, caidas=caidas)
        if status not in esperados:
            raise CoreError(status, raw.decode("utf-8", errors="replace"))
        if binario:
            return status, {"tipo": tipo, "bytes": len(raw), "inicio": raw[:5]}
        texto = raw.decode("utf-8") if raw else ""
        return status, (json.loads(texto) if texto and "json" in tipo else texto or None)

    def get(self, path):
        return self.llamar("GET", path)[1]

    def post(self, path, body, headers=None):
        return self.llamar("POST", path, body, headers)[1]

    def put(self, path, body):
        return self.llamar("PUT", path, body)[1]

    def buscar(self, objeto, **filtros):
        """El primer registro de `objeto` con esos valores (filtros de igualdad de Core), o None."""
        q = urllib.parse.urlencode({"page": 0, "size": 5, "sort": "id", **filtros})
        datos = self.get(f"/api/objects/{objeto}/records?{q}")
        filas = (datos or {}).get("content", [])
        return filas[0] if filas else None


# --- conteo y escritura ----------------------------------------------------------------------------------------


class Siembra:
    def __init__(self, dry_run):
        self.dry_run = dry_run
        self.cuenta = {}
        self.notas = []

    def contar(self, tipo, creado):
        c = self.cuenta.setdefault(tipo, [0, 0])
        c[0 if creado else 1] += 1

    def crear(self, tipo, descripcion, accion):
        """Corre `accion` (la escritura) y la cuenta como creada; en --dry-run solo la anuncia y devuelve None."""
        self.contar(tipo, True)
        if self.dry_run:
            print(f"  crearía {tipo}: {descripcion}")
            return None
        resultado = accion()
        print(f"  {tipo}: {descripcion}")
        return resultado

    def omitir(self, tipo):
        self.contar(tipo, False)

    def nota(self, texto):
        print(f"  NOTA: {texto}")
        self.notas.append(texto)

    def resumen(self):
        print("\nresumen (creados, omitidos):")
        for tipo, (c, o) in self.cuenta.items():
            print(f"  {tipo}: {c} creados, {o} omitidos")
        total = sum(c for c, _ in self.cuenta.values())
        print(f"total: {total} {'por crear (dry-run, nada escrito)' if self.dry_run else 'creados'}")
        return total


# --- a) cargadores ---------------------------------------------------------------------------------------------


def correr(args, script, *extra, dry_flag="--dry-run"):
    cmd = [sys.executable, os.path.join(MODEL, script), "--core", args.core, "--email", args.email, "--password", args.password, *extra]
    if args.dry_run and dry_flag:
        cmd.append(dry_flag)
    print(f"\n$ python3 model/{script} {' '.join(extra)}{' ' + dry_flag if args.dry_run and dry_flag else ''}")
    env = {**os.environ, "WASICHAI_CORE": args.core, "WASICHAI_EMAIL": args.email, "WASICHAI_PASSWORD": args.password}
    r = subprocess.run(cmd, cwd=MODEL, env=env, capture_output=True, text=True)
    lineas = [x for x in (r.stdout + r.stderr).splitlines() if x.strip()]
    resumen = [x for x in lineas if any(k in x for k in ("created", "skipped", "diff:", "done:", "error", "Error", "would"))]
    for x in (resumen or lineas)[-12:]:
        print(f"  {x}")
    if r.returncode != 0:
        print(f"error: model/{script} salió con {r.returncode}", file=sys.stderr)
        for x in lineas[-20:]:
            print(f"  {x}", file=sys.stderr)
        sys.exit(1)


def _filas_csv(path):
    with open(path, encoding="utf-8") as f:
        return [x for x in f.read().splitlines() if x.strip() and not x.startswith("#")]


def unir_csv(base, extra, salida, nota):
    """El CSV `base` (con su cabecera #) más las filas de `extra` (misma cabecera de columnas), en `salida`."""
    with open(base, encoding="utf-8") as f:
        lineas = f.read().splitlines()
    comentarios = [x for x in lineas if x.startswith("#")]
    filas = _filas_csv(base)
    filas_extra = _filas_csv(extra)
    if filas_extra[0] != filas[0]:
        raise SystemExit(f"error: {extra} no tiene las columnas de {base}")
    os.makedirs(os.path.dirname(salida), exist_ok=True)
    with open(salida, "w", encoding="utf-8") as f:
        f.write("\n".join(comentarios + [f"# {nota}"] + filas + filas_extra[1:]) + "\n")
    return salida


def cargadores(args):
    print("\n== a) cargadores del repo ==")
    correr(args, "apply.py", dry_flag="--diff")
    correr(args, "import_parametros.py")
    # la demo es de Perené (selva): su distrito, su cabecera y las obras complementarias de la selva
    correr(
        args,
        "import_catalogos.py",
        "--distrito",
        "120302",
        "--municipalidad-json",
        os.path.join(MODEL, "data", "perene", "municipalidad.json"),
        "--obras-csv",
        os.path.join(MODEL, "data", "obras_complementarias.csv"),
        "--workers",
        "8",
    )
    correr(args, "import_parametros.py", "--csv", os.path.join(MODEL, "data", "parametros-anuncios-perene.csv"))
    correr(args, "import_parametros.py", "--csv", os.path.join(DATOS, "parametros-sanciones-2026.csv"))
    correr(args, "import_cuis.py", "--csv", os.path.join(MODEL, "data", "cuiema", "perene-2026-cuis.csv"))

    # arbitrios 2026 ficticios: la tabla larga de model/data/arbitrios con las filas DEMO, derivada a parámetros (un
    # archivo local de build/demo, también en --dry-run: no escribe nada en Core)
    ficticios = os.path.join(MODEL, "data", "arbitrios")
    nota = "DEMO: + docs/manuales/demo/datos (filas ficticias para la demostración de los manuales)"
    tasas = unir_csv(os.path.join(ficticios, "ficticio-tasas.csv"), os.path.join(DATOS, "tasas-extra.csv"), os.path.join(GENERADOS, "tasas-2026.csv"), nota)
    usos = unir_csv(os.path.join(ficticios, "ficticio-usos.csv"), os.path.join(DATOS, "usos-extra.csv"), os.path.join(GENERADOS, "usos-2026.csv"), nota)
    influencias = unir_csv(
        os.path.join(ficticios, "ficticio-influencias.csv"), os.path.join(DATOS, "influencias-extra.csv"), os.path.join(GENERADOS, "influencias-2026.csv"), nota
    )
    derivado = os.path.join(GENERADOS, "parametros-arbitrios-2026.csv")
    cmd = [
        sys.executable, os.path.join(MODEL, "import_tasas_arbitrio.py"), "--tasas", tasas, "--servicios", SERVICIOS_TASAS,
        "--usos", usos, "--influencias", influencias, "--vigencia-desde", f"{ANIO}-01-01", "--norma", NORMA_DEMO,
        "--fuente", "docs/manuales/demo (datos ficticios)", "--transcribio", "DEMO A, 2026-01-05", "--verifico", "DEMO B, 2026-01-09",
        "--redondeo", "2:ABAJO", "--habitantes", "RECOLECCION_RESIDUOS=4:0.3",
        "--vencimientos", "2026-02-27,2026-05-29,2026-08-31,2026-11-30", "--salida", derivado,
    ]  # fmt: skip
    print("\n$ python3 model/import_tasas_arbitrio.py --tasas build/demo/datos/tasas-2026.csv ... --salida build/demo/datos/parametros-arbitrios-2026.csv")
    r = subprocess.run(cmd, cwd=MODEL, capture_output=True, text=True)
    for x in (r.stdout + r.stderr).splitlines()[-6:]:
        print(f"  {x}")
    if r.returncode != 0:
        sys.exit(1)
    correr(args, "import_parametros.py", "--csv", derivado)
    correr(args, "import_parametros.py", "--csv", os.path.join(DATOS, "parametros-arbitrios-zonas-2026.csv"))
    correr(args, "import_zonas_arbitrio.py", "--csv", os.path.join(DATOS, "zonas-ubicacion-2026.csv"))


# --- b) perfiles -----------------------------------------------------------------------------------------------


def leer_perfiles():
    with open(os.path.join(HERE, "perfiles.json"), encoding="utf-8") as f:
        return json.load(f)["perfiles"]


def permisos_de(perfil):
    return sorted((objeto, accion) for objeto, acciones in perfil["permisos"].items() for accion in acciones)


def perfiles(adm, s, clave):
    print("\n== b) perfiles ==")
    roles = {r["name"]: r for r in adm.get("/api/roles")}
    usuarios = {u["email"].lower(): u for u in adm.get("/api/users")}
    for p in leer_perfiles():
        rol = p["rol"]
        if rol in roles:
            s.omitir("rol")
        else:
            s.crear("rol", rol, lambda: adm.post("/api/roles", {"name": rol, "label": p["etiqueta"]}))
        quiere = permisos_de(p)
        tiene = sorted((x["objectName"], x["action"]) for x in roles.get(rol, {}).get("permissions", []) if x.get("allowed", True))
        if quiere == tiene:
            s.omitir("permisos del rol")
        else:
            cuerpo = {"permissions": [{"objectName": o, "action": a} for o, a in quiere]}
            s.crear("permisos del rol", f"{rol}: {len(quiere)} permisos", lambda: adm.put(f"/api/roles/{rol}/permissions", cuerpo))
        # la cuenta del perfil y las otras del mismo rol (NORMATIVA: la segunda firma la pone otra persona)
        for cuenta in [p] + p.get("otras_cuentas", []):
            u = usuarios.get(cuenta["email"].lower())
            if u is None:
                cuerpo = {"email": cuenta["email"], "displayName": cuenta["displayName"], "password": clave, "roles": [rol]}
                s.crear("usuario", f"{cuenta['email']} ({rol})", lambda cuerpo=cuerpo: adm.post("/api/users", cuerpo))
            elif u.get("roles") != [rol]:
                s.crear("roles del usuario", f"{cuenta['email']} -> {rol}", lambda u=u: adm.put(f"/api/users/{u['id']}/roles", {"roles": [rol]}))
            else:
                s.omitir("usuario")


def clientes(args, adm, clave):
    """Un cliente por perfil (rol -> Core). En --dry-run, o si la cuenta aún no existe, el del admin: solo lee."""
    out = {}
    for p in leer_perfiles():
        c = Core(args.core, p["email"], clave, p["rol"])
        try:
            out[p["rol"]] = c.entrar()
        except CoreError:
            if not args.dry_run:
                raise
            out[p["rol"]] = adm
    return out


# --- c) datos sintéticos -----------------------------------------------------------------------------------------

APELLIDOS = [
    "QUISPE", "MAMANI", "HUAMAN", "ROJAS", "FLORES", "CHAVEZ", "VARGAS", "RAMOS", "TORRES", "CASTRO",
    "MENDOZA", "SALAZAR", "PAREDES", "CORDOVA", "LEON", "GUTIERREZ", "ESPINOZA", "SANCHEZ", "CAMPOS", "AQUINO",
]  # fmt: skip
NOMBRES_M = ["JUAN CARLOS", "LUIS ALBERTO", "PEDRO PABLO", "JOSE ANTONIO", "MIGUEL ANGEL", "CESAR AUGUSTO", "RAUL", "JORGE LUIS", "VICTOR HUGO", "ALEJANDRO"]
NOMBRES_F = ["ROSA ELENA", "MARIA ISABEL", "CARMEN ROSA", "ANA LUCIA", "JULIA", "GLADYS", "SONIA", "PATRICIA", "LUZ MARINA", "ELENA"]
EMPRESAS = [
    "COMERCIAL LOS FRESNOS S.A.C.", "BODEGA LA ORQUIDEA E.I.R.L.", "INVERSIONES VALLE VERDE S.A.C.",
    "FERRETERIA EL CEDRO S.R.L.", "SERVICIOS GENERALES LAS PALMERAS E.I.R.L.", "TRANSPORTES RIO DEMO S.A.C.",
    "ASOCIACION CULTURAL DEMO PERENE",
]  # fmt: skip

# manzanas: sector, manzana catastral, manzana, tipo de vía, vía, primer número, paso, lotes, unidad urbana, área verde
MANZANAS = [
    ("01", "01", "A", "AVENIDA", "LOS FRESNOS", 100, 12, 7, "VV", "FRENTE A PARQUE"),
    ("01", "02", "B", "JIRON", "LAS ORQUIDEAS", 101, 10, 7, "VV", "FRENTE A PARQUE"),
    ("01", "03", "C", "CALLE", "LOS CEDROS", 120, 10, 7, "VV", "OTRAS UBICACIONES"),
    ("02", "01", "D", "AVENIDA", "LOS FRESNOS", 201, 12, 7, "VV", "FRENTE A PARQUE"),
    ("02", "02", "E", "JIRON", "LOS ALISOS", 102, 10, 7, "VV", "FRENTE A PARQUE"),
    ("02", "03", "F", "PASAJE", "LAS BEGONIAS", 103, 10, 7, "VV", "OTRAS UBICACIONES"),
    ("03", "01", "G", "CALLE", "LOS PINOS", 110, 10, 7, "LP", "FRENTE A PARQUE"),
    ("03", "02", "H", "AVENIDA", "LAS MAGNOLIAS", 115, 10, 6, "LP", "OTRAS UBICACIONES"),
]  # fmt: skip
UNIDADES = {"VV": ("URBANIZACION", "VALLE VERDE"), "LP": ("ASOCIACION PRO VIVIENDA", "LAS PALMERAS")}
VIAS = sorted({(m[3], m[4]) for m in MANZANAS})

# la malla de los lotes, cerca de Perené (EPSG:4326): cada lote 0.00025° x 0.00035°, las manzanas en 2 columnas
LON0, LAT0, DX, DY = -75.2240, -10.9490, 0.00025, 0.00035

USO_CASA = ("RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN", "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA")
USO_EDIFICIO = ("RESIDENCIAL", "MULTIFAMILIAR", "EDIFICIO", "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA")
USO_TIENDA = ("COMERCIAL", "TIENDAS", "DE PRODUCTOS ALIMENTICIOS", "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES")
USO_OFICINA = ("COMERCIAL", "OFICINA", "DE ESTUDIOS Y PROYECTOS", "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES")
LETRAS = [("C", "C", "D", "E", "D", "C", "D"), ("D", "D", "E", "E", "E", "D", "E"), ("B", "C", "C", "D", "C", "C", "C")]


def ruc(n):
    """20999 + n en 5 dígitos + el dígito verificador de SUNAT (el de srtm.rentas.Reglas)."""
    base = f"20999{n:05d}"
    pesos = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2]
    return base + str((11 - sum(int(d) * p for d, p in zip(base, pesos)) % 11) % 10)


def poligono(lon, lat):
    return {"type": "Polygon", "coordinates": [[[lon, lat], [lon + DX, lat], [lon + DX, lat + DY], [lon, lat + DY], [lon, lat]]]}


def lote_xy(i_manzana, lote):
    col, fila = i_manzana % 2, i_manzana // 2
    lon = LON0 + col * (7 * DX + 0.0004) + (lote - 1) * DX
    lat = LAT0 + fila * (DY + 0.0003)
    return round(lon, 6), round(lat, 6)


def contribuyentes():
    """Los 40: 30 personas naturales, una sociedad conyugal, una sin documento, una sucesión y 7 personas jurídicas."""
    out = []
    for i in range(30):
        mujer = i % 2 == 0
        ap1, ap2 = APELLIDOS[i % 20], APELLIDOS[(i * 7 + 3) % 20]
        if ap2 == ap1:
            ap2 = APELLIDOS[(i + 11) % 20]
        out.append({
            "tipo_contribuyente": "PERSONA NATURAL", "tipo_documento": "DNI", "numero_documento": f"9{1000101 + i * 137:07d}",
            "apellido_paterno": ap1, "apellido_materno": ap2, "nombres": (NOMBRES_F if mujer else NOMBRES_M)[(i // 2) % 10],
            "sexo": "MUJER" if mujer else "HOMBRE", "estado_civil": ["SOLTERO", "CASADO", "VIUDO", "DIVORCIADO", "CONVIVIENTE"][i % 5],
            "fecha_nacimiento": f"{1950 + (i * 3) % 50}-{1 + i % 12:02d}-{1 + (i * 5) % 28:02d}",
        })  # fmt: skip
    out.append({
        "tipo_contribuyente": "SOCIEDAD CONYUGAL", "tipo_documento": "DNI", "numero_documento": "95000031",
        "apellido_paterno": "VILCA", "apellido_materno": "CONDORI", "nombres": "ERNESTO", "sexo": "HOMBRE",
        "estado_civil": "CASADO", "fecha_nacimiento": "1971-04-12",
    })  # fmt: skip
    out.append({
        "tipo_contribuyente": "PERSONA NATURAL", "tipo_documento": "SIN DOCUMENTO", "apellido_paterno": "CCAHUANA",
        "apellido_materno": "PUMA", "nombres": "TEODORO", "sexo": "HOMBRE", "estado_civil": "VIUDO",
    })  # fmt: skip
    out.append({
        "tipo_contribuyente": "SUCESION INDIVISA", "tipo_documento": "DNI", "numero_documento": "95000033",
        "razon_social": "SUCESION INDIVISA HUARCAYA LAZO ANSELMO", "apellido_paterno": "HUARCAYA",
        "apellido_materno": "LAZO", "nombres": "ANSELMO", "fecha_fallecimiento": "2024-08-30",
    })  # fmt: skip
    for j, nombre in enumerate(EMPRESAS):
        out.append({"tipo_contribuyente": "PERSONA JURIDICA", "tipo_documento": "RUC", "numero_documento": ruc(j + 1), "razon_social": nombre})
    for k, c in enumerate(out):
        c["codigo_anterior"] = f"DEMO-{k + 1:03d}"
        c["fecha_presentacion"] = f"{ANIO}-01-{5 + k % 20:02d}"
        c["fuente_informacion"] = "MANUAL"
        c["observacion"] = "Contribuyente FICTICIO de la demostración de los manuales"
    return out


def predios():
    """Los 55 predios en sus 8 manzanas, con su dueño (clave DEMO-NNN), usos y secuencias, en el orden de su código."""
    out = []
    for im, (sector, mzc, mz, tvia, via, num0, paso, lotes, uu, verde) in enumerate(MANZANAS):
        tzona, zona = UNIDADES[uu]
        for lote in range(1, lotes + 1):
            lon, lat = lote_xy(im, lote)
            codigo = f"{sector}-{mzc}-{lote:04d}"
            out.append({
                "codigo": codigo, "sector_catastral": sector, "manzana_catastral": mzc, "tipo_predio": "PREDIO URBANO",
                **UBIGEO, "region": "SELVA", "tipo_via": tvia, "via": via, "numero": str(num0 + (lote - 1) * paso),
                "manzana": mz, "lote": str(lote), "tipo_zona": tzona, "habilitacion_urbana": zona,
                "ubicacion_area_verde": verde, "lote_geom": poligono(lon, lat),
                "_centro": [round(lon + DX / 2, 6), round(lat + DY / 2, 6)],
            })  # fmt: skip
    # los que no tienen lote en el catastro fiscal: cada quinto y el 52 (12); el resto (43) cruza por su código
    for n, p in enumerate(out, start=1):
        if n % 5 != 0 and n != 52:
            p["codigo_cpu"] = "120302" + p["codigo"].replace("-", "")[:4] + f"{int(p['lote']):03d}"
    return out


def asignacion():
    """predio n (1..55) -> lista de (clave del titular, % o None). Los fijos y el resto en orden."""
    fijos = {
        1: ["DEMO-001"], 2: ["DEMO-001"], 3: ["DEMO-002"], 4: ["DEMO-003", "DEMO-004"], 8: ["DEMO-001"], 15: ["DEMO-001"],
        22: ["DEMO-034"], 23: ["DEMO-034"], 24: ["DEMO-035"], 25: ["DEMO-036"], 26: ["DEMO-037"], 27: ["DEMO-038"],
        28: ["DEMO-039"], 55: ["DEMO-040"], 29: ["DEMO-031"], 36: ["DEMO-032"], 43: ["DEMO-033"],
    }  # fmt: skip
    cola = [f"DEMO-{k:03d}" for k in range(5, 31)] + [f"DEMO-{k:03d}" for k in range(5, 17)]
    out = {}
    for n in range(1, 56):
        out[n] = fijos.get(n) or [cola.pop(0)]
    assert not cola
    return out


def declaraciones(lista_predios):
    """Las DJ 2026: una por titular y secuencia, con su uso, áreas, frente, habitantes, autoavalúo y sus listas."""
    rng = random.Random(ANIO)
    out = []
    asig = asignacion()
    for n, p in enumerate(lista_predios, start=1):
        titulares = asig[n]
        juridica = titulares[0] >= "DEMO-034"
        uso = USO_OFICINA if n == 55 else USO_TIENDA if juridica else USO_EDIFICIO if n % 6 == 0 else USO_CASA
        terreno = round(rng.uniform(120, 300), 2)
        pisos = 2 if (juridica or n % 3 == 0) else 1
        areas = [round(rng.uniform(70, 140), 2) for _ in range(pisos)]
        frente = round(rng.uniform(7.5, 15), 2)
        vu = 950 if juridica else 820
        letras = LETRAS[n % 3]
        dj = {
            "anio": ANIO, "secuencia_uso": "1", "tipo_adquisicion": "COMPRA" if n % 4 else "HERENCIA",
            "fecha_adquisicion": f"{2010 + n % 14}-{1 + n % 12:02d}-{1 + n % 27:02d}",
            "fecha_presentacion": f"{ANIO}-02-{2 + n % 25:02d}", "documentos_sustento": "Minuta de compraventa" if n % 4 else "Sucesión intestada",
            "folios": 2 + n % 5, "clase_uso": uso[0], "sub_clase_uso": uso[1], "uso": uso[2], "clasificacion": uso[3],
            "estado_construccion": "TERMINADO", "area_terreno": terreno, "area_construida": round(sum(areas), 2),
            "longitud_frente": frente, "valor_autoavaluo": round(sum(areas) * vu + terreno * 48, 2),
        }  # fmt: skip
        if uso[0] == "RESIDENCIAL":
            dj["numero_habitantes"] = 2 + n % 5
        niveles = [
            {
                "tipo_nivel": "PISO", "numero_piso": k + 1, "anio_construccion": 2005 + n % 18, "mes_construccion": 1 + n % 12,
                "material": "CONCRETO" if juridica else ["LADRILLO", "CONCRETO", "ADOBE"][n % 3],
                "estado_conservacion": ["BUENO", "REGULAR", "MUY BUENO"][n % 3], "area_construida": a,
                "muros_columnas": letras[0], "techos": letras[1], "pisos": letras[2], "puertas_ventanas": letras[3],
                "revestimientos": letras[4], "banos": letras[5], "instalaciones": letras[6],
            }  # fmt: skip
            for k, a in enumerate(areas)
        ]
        obras = []
        if n % 3 == 1:
            obras.append({
                "ingreso": "POR CATEGORIAS", "material": "LADRILLO", "tipo_obra": "MUROS PERIMETRICOS O CERCOS",
                "estado_conservacion": "BUENO", "anio_construccion": 2015 + n % 9, "mes_construccion": 1 + n % 12,
                "numero_piso": 1, "cantidad": 1, "metrado": round(frente * 2 + 20, 2), "unidad_medida": "ML",
            })  # fmt: skip
        if n % 7 == 2:
            obras.append({
                "ingreso": "POR CATEGORIAS", "material": "CONCRETO", "tipo_obra": "TANQUES ELEVADOS",
                "estado_conservacion": "REGULAR", "anio_construccion": 2018, "mes_construccion": 6,
                "numero_piso": 2, "cantidad": 1, "metrado": 2.5, "unidad_medida": "M3",
            })  # fmt: skip
        frentes = []
        if p["codigo"] == "01-01-0007":
            # el lote de la esquina: su otro frente da al JR. LAS ORQUIDEAS
            frentes.append({"tipo_via": "JIRON", "via": "LAS ORQUIDEAS", "numero": "171", "frontis": 9.5, "lado": "IMPAR"})
        transferentes = []
        if p["codigo"] == "01-01-0007":
            dj.update({"fecha_adquisicion": "2025-11-14", "documentos_sustento": "Escritura pública de compraventa"})
            transferentes.append({
                "porcentaje_transferido": 100, "tipo_documento": "DNI", "numero_documento": "95000077",
                "apellido_paterno": "OCHOA", "apellido_materno": "BALDEON", "nombres": "FERMIN", **UBIGEO,
                "descripcion_domicilio": "JR. LOS ALISOS 300, PERENE",
            })  # fmt: skip
        if n == 9:
            # una pensionista: la deducción de 50 UIT de 2026 la deja sin impuesto
            dj.update({
                "condicion_especial": "PENSIONISTA", "condicion_tipo_documento": "RESOLUCION",
                "condicion_numero_documento": "RES-DEMO-0009-2026", "condicion_fecha_documento": "2026-01-20",
                "condicion_fecha_inicio": "2026-01-01", "deduccion": 275000,
            })  # fmt: skip
        if n == 29:
            dj["condicion_propiedad"] = "SOCIEDAD CONYUGAL"
        base = {"predio": p["codigo"], "titular": titulares[0], "secuencia": "001", "declaracion": dj, "niveles": niveles, "obras": obras,
                "frentes": frentes, "transferentes": transferentes}  # fmt: skip
        out.append(base)
        if len(titulares) == 2:
            # el condominio: el segundo titular se suma con el 40 % (el primero queda con el 60 %)
            out.append({"predio": p["codigo"], "titular": titulares[1], "secuencia": "001", "condomino_de": titulares[0], "porcentaje": 40})
        if n == 1:
            # dos usos: la casa (001) y la tienda del primer piso (002)
            dj2 = {**dj, "secuencia_uso": "2", "clase_uso": USO_TIENDA[0], "sub_clase_uso": USO_TIENDA[1], "uso": USO_TIENDA[2],
                   "clasificacion": USO_TIENDA[3], "area_construida": 38.5, "area_terreno": 0.0, "valor_autoavaluo": round(38.5 * 950, 2)}  # fmt: skip
            dj2.pop("numero_habitantes", None)
            nivel2 = {**niveles[0], "area_construida": 38.5}
            out.append({"predio": p["codigo"], "titular": titulares[0], "secuencia": "002", "declaracion": dj2, "niveles": [nivel2],
                        "obras": [], "frentes": [], "transferentes": []})  # fmt: skip
        if n == 3:
            # una DJ que se anula: el uso comercial que se declaró por error
            dj3 = {**dj, "secuencia_uso": "2", "clase_uso": USO_TIENDA[0], "sub_clase_uso": USO_TIENDA[1], "uso": USO_TIENDA[2],
                   "clasificacion": USO_TIENDA[3], "area_construida": 25.0, "area_terreno": 0.0, "valor_autoavaluo": 23750.0}  # fmt: skip
            dj3.pop("numero_habitantes", None)
            out.append({"predio": p["codigo"], "titular": titulares[0], "secuencia": "002", "declaracion": dj3, "niveles": [], "obras": [],
                        "frentes": [], "transferentes": [],
                        "anular": "Uso comercial declarado por error: el local del primer piso no funciona desde 2025 (DEMO)"})  # fmt: skip
    return out


def listas_de(k, c, domicilio):
    """Las listas de un contribuyente: su domicilio fiscal, un medio de contacto y, a algunos, relacionados y sustentos."""
    medios = [{"tipo": "TELEFONO CELULAR", "valor": f"9000{k + 1:05d}", "principal": True}]
    relacionados, sustentos = [], []
    if c["tipo_documento"] == "RUC":
        medios.append({"tipo": "CORREO ELECTRONICO", "valor": f"contacto{k + 1:03d}@correo.demo"})
        relacionados.append({
            "tipo_relacionado": "REPRESENTANTE LEGAL", "tipo_documento": "DNI", "numero_documento": f"9600{k + 1:04d}",
            "apellido_paterno": APELLIDOS[(k * 3) % 20], "apellido_materno": APELLIDOS[(k * 5 + 1) % 20], "nombres": NOMBRES_M[k % 10],
            "fecha_inicio": "2024-03-01", "fuente_informacion": "MANUAL",
        })  # fmt: skip
        sustentos.append({"documento": "VIGENCIA DE PODER", "numero_documento": f"PE-DEMO-{k + 1:04d}", "tipo_presentacion": "COPIA SIMPLE", "folios": 3})
    elif c["tipo_contribuyente"] == "SOCIEDAD CONYUGAL":
        relacionados.append({
            "tipo_relacionado": "CONYUGE", "tipo_documento": "DNI", "numero_documento": "95000032", "apellido_paterno": "QUISPE",
            "apellido_materno": "HUAMAN", "nombres": "BEATRIZ", "fecha_inicio": "1998-06-20", "fuente_informacion": "MANUAL",
        })  # fmt: skip
        sustentos.append({"documento": "PARTIDA REGISTRAL", "numero_documento": "PR-DEMO-11002233", "tipo_presentacion": "COPIA LEGALIZADA", "folios": 2})
    elif c["tipo_contribuyente"] == "SUCESION INDIVISA":
        relacionados.append({
            "tipo_relacionado": "HEREDERO", "tipo_documento": "DNI", "numero_documento": "95000034", "apellido_paterno": "HUARCAYA",
            "apellido_materno": "SOTO", "nombres": "MARLENI", "fuente_informacion": "MANUAL",
        })  # fmt: skip
        sustentos.append({"documento": "SUCESION INTESTADA", "numero_documento": "SI-DEMO-2025-0007", "tipo_presentacion": "COPIA FEDATEADA", "folios": 6})
    elif k == 0:
        relacionados.append({
            "tipo_relacionado": "APODERADO", "tipo_documento": "DNI", "numero_documento": "95000035", "apellido_paterno": "ROJAS",
            "apellido_materno": "VARGAS", "nombres": "DIANA", "fecha_inicio": "2025-01-10", "fuente_informacion": "MANUAL",
        })  # fmt: skip
        sustentos.append({"documento": "PODER ESPECIAL", "numero_documento": "PODER-DEMO-0001", "tipo_presentacion": "ORIGINAL", "folios": 1})
    return {"domicilios": [domicilio], "medios-contacto": medios, "relacionados": relacionados, "sustentos": sustentos}


def domicilio_fiscal(p):
    """El domicilio fiscal en la dirección de un predio de la demo (o, sin predio, en el JR. LOS ALISOS)."""
    tzona, zona = UNIDADES["VV"] if p is None else (p["tipo_zona"], p["habilitacion_urbana"])
    d = {
        "tipo_domicilio": "FISCAL", "tipo_predio": "PREDIO URBANO", **UBIGEO, "tipo_unidad_urbana": tzona, "unidad_urbana": zona,
        "tipo_via": "JIRON" if p is None else p["tipo_via"], "via": "LOS ALISOS" if p is None else p["via"],
        "numero": "300" if p is None else p["numero"], "manzana": None if p is None else p["manzana"],
        "lote": None if p is None else p["lote"],
    }  # fmt: skip
    if p is not None:
        d["ubicacion"] = {"type": "Point", "coordinates": p["_centro"]}
    return {k: v for k, v in d.items() if v is not None}


def sembrar_catalogo_vias(adm, s):
    print("\n-- vías y unidades urbanas de la demo (catálogo) --")
    for tvia, via in VIAS:
        if adm.buscar("via", nombre=via, tipo_via=tvia):
            s.omitir("via")
        else:
            s.crear("via", f"{tvia} {via}", lambda: adm.post("/api/objects/via/records", {"attributes": {"tipo_via": tvia, "nombre": via, "ubigeo": UBIGEO["ubigeo"]}}))
    for tuu, uu in UNIDADES.values():
        if adm.buscar("unidad_urbana", nombre=uu, tipo_unidad_urbana=tuu):
            s.omitir("unidad_urbana")
        else:
            s.crear(
                "unidad_urbana", f"{tuu} {uu}",
                lambda: adm.post("/api/objects/unidad_urbana/records", {"attributes": {"tipo_unidad_urbana": tuu, "nombre": uu, "ubigeo": UBIGEO["ubigeo"]}}),
            )  # fmt: skip


def sembrar_lista(cli, s, tipo, padre, ruta_padre, segmento, filas, clave):
    """Las filas de una lista (domicilios, niveles...) que su padre aún no tiene, comparadas por `clave`."""
    if not filas:
        return
    existentes = [] if padre is None else cli.get(f"/api/srtm/{ruta_padre}/{padre}/{segmento}") or []
    tiene = {clave(x) for x in existentes}
    for f in filas:
        if clave(f) in tiene:
            s.omitir(tipo)
            continue
        s.crear(tipo, f"{segmento} {clave(f)}", lambda: cli.post(f"/api/srtm/{ruta_padre}/{padre}/{segmento}", f))


def sembrar_padron(adm, reg, s, ids):
    print("\n-- contribuyentes y sus listas (como REGISTRADOR) --")
    lista_predios = predios()
    por_codigo = {p["codigo"]: p for p in lista_predios}
    djs = declaraciones(lista_predios)
    primer_predio = {}
    for d in djs:
        primer_predio.setdefault(d["titular"], por_codigo[d["predio"]])
    for k, c in enumerate(contribuyentes()):
        clave = c["codigo_anterior"]
        r = adm.buscar("contribuyente", codigo_anterior=clave)
        if r:
            s.omitir("contribuyente")
            cid = r["id"]
        else:
            nuevo = s.crear("contribuyente", f"{clave} {c.get('razon_social') or c['apellido_paterno'] + ' ' + c['nombres']}", lambda: reg.post("/api/srtm/contribuyentes", c))
            cid = nuevo["id"] if nuevo else None
        ids["contribuyente"][clave] = cid
        listas = listas_de(k, c, domicilio_fiscal(primer_predio.get(clave)))
        sembrar_lista(reg, s, "domicilio", cid, "contribuyentes", "domicilios", listas["domicilios"], lambda x: x["tipo_domicilio"])
        sembrar_lista(reg, s, "medio_contacto", cid, "contribuyentes", "medios-contacto", listas["medios-contacto"], lambda x: x["valor"])
        sembrar_lista(reg, s, "relacionado", cid, "contribuyentes", "relacionados", listas["relacionados"], lambda x: x["numero_documento"])
        sembrar_lista(reg, s, "sustento", cid, "contribuyentes", "sustentos", listas["sustentos"], lambda x: x["numero_documento"])

    print("\n-- predios y declaraciones juradas 2026 (como REGISTRADOR) --")
    por_crear = set()  # en dry-run: los predios que se crearían con su primera DJ
    for d in djs:
        p = por_codigo[d["predio"]]
        cid = ids["contribuyente"].get(d["titular"])
        pr = ids["predio"].get(p["codigo"])
        if pr is None:
            pr = (adm.buscar("predio", codigo=p["codigo"]) or {}).get("id")
            if pr:
                s.omitir("predio")
                ids["predio"][p["codigo"]] = pr
        existentes = adm.get(f"/api/srtm/predios/{pr}/declaraciones?anio={ANIO}") if pr else []
        suya = next(
            (x["declaracion"] for x in existentes or [] if x["declaracion"].get("contribuyente") == cid and x["declaracion"].get("secuencia_uso") == d["secuencia"]),
            None,
        )
        if suya:
            s.omitir("declaracion_predial")
        elif "condomino_de" in d:
            de = next(x["declaracion"] for x in existentes or [] if x["declaracion"].get("contribuyente") == ids["contribuyente"].get(d["condomino_de"])) if pr else None
            suya = s.crear(
                "declaracion_predial", f"{p['codigo']} condómino {d['titular']} {d['porcentaje']} %",
                lambda: reg.post(f"/api/srtm/declaraciones/{de['id']}/condominos", {"contribuyente": cid, "porcentaje_condominio": d["porcentaje"]}),
            )  # fmt: skip
        else:
            if pr:
                cuerpo = {"declaracion": d["declaracion"], "predio_id": pr}
            else:
                cuerpo = {"declaracion": d["declaracion"], "predio": {k: v for k, v in p.items() if not k.startswith("_")}}
            nueva = s.crear(
                "declaracion_predial", f"{p['codigo']} uso {d['secuencia']} de {d['titular']}" + ("" if pr else " (y el predio)"),
                lambda: reg.post(f"/api/srtm/contribuyentes/{cid}/declaraciones-juradas", cuerpo),
            )  # fmt: skip
            if not pr and p["codigo"] not in por_crear:
                por_crear.add(p["codigo"])
                s.contar("predio", True)
            if nueva:
                suya = nueva["declaracion"]
                ids["predio"][p["codigo"]] = nueva["predio"]["id"]
        did = suya["id"] if suya else None
        ids["declaracion"][(p["codigo"], d["titular"], d["secuencia"])] = did
        if "condomino_de" in d:
            continue
        sembrar_lista(reg, s, "nivel_construccion", did, "declaraciones", "niveles", d["niveles"], lambda x: (x["numero_piso"], float(x["area_construida"])))
        sembrar_lista(reg, s, "obra_complementaria", did, "declaraciones", "obras", d["obras"], lambda x: x["tipo_obra"])
        sembrar_lista(reg, s, "otro_frente", did, "declaraciones", "frentes", d["frentes"], lambda x: x["via"])
        sembrar_lista(reg, s, "transferente", did, "declaraciones", "transferentes", d["transferentes"], lambda x: x["numero_documento"])
        if d.get("anular"):
            if suya and suya.get("estado") == "ANULADA":
                s.omitir("anulacion de DJ")
            else:
                s.crear("anulacion de DJ", f"{p['codigo']} uso {d['secuencia']}", lambda: reg.post(f"/api/srtm/declaraciones/{did}/anular", {"motivo_anulacion": d["anular"]}))
    return lista_predios


def sembrar_catastro(adm, reg, s, lista_predios):
    print("\n-- catastro fiscal (como REGISTRADOR) --")
    lotes = []
    for p in lista_predios:
        if "codigo_cpu" in p:
            lotes.append((p["codigo_cpu"], p, p["codigo"]))
    # dos lotes del catastro sin predio en el padrón (omisos): la manzana H, lotes 7 y 8
    for lote in (7, 8):
        lon, lat = lote_xy(7, lote)
        omiso = {"tipo_via": "AVENIDA", "via": "LAS MAGNOLIAS", "numero": str(115 + (lote - 1) * 10), "manzana": "H", "lote": str(lote),
                 "tipo_zona": "ASOCIACION PRO VIVIENDA", "habilitacion_urbana": "LAS PALMERAS", "lote_geom": poligono(lon, lat)}  # fmt: skip
        lotes.append((f"120302{'0302'}{lote:03d}", omiso, None))
    for cpu, p, codigo in lotes:
        if adm.buscar("catastro_fiscal", codigo_cpu=cpu):
            s.omitir("catastro_fiscal")
            continue
        cuerpo = {
            "codigo_cpu": cpu, "codigo_predio_municipal": codigo, "partida_registral": f"P1100{cpu[-7:]}", "tipo_predio": "PREDIO URBANO",
            "ubigeo": UBIGEO["ubigeo"], "tipo_via": p["tipo_via"], "via": p["via"], "numero": p["numero"], "tipo_zona": p["tipo_zona"],
            "zona": p["habilitacion_urbana"], "manzana": p["manzana"], "lote": p["lote"], "lote_geom": p["lote_geom"],
        }  # fmt: skip
        s.crear("catastro_fiscal", f"{cpu} -> {codigo or 'sin predio (omiso)'}", lambda: reg.post("/api/srtm/catastro", {k: v for k, v in cuerpo.items() if v is not None}))


def esperar(cli, ruta, finales=("TERMINADA", "FALLIDA"), limite=3600):
    inicio = time.time()
    while True:
        job = cli.get(ruta)
        if job.get("estado") in finales:
            return job
        if time.time() - inicio > limite:
            raise SystemExit(f"error: {ruta} sigue {job.get('estado')} tras {limite} s")
        time.sleep(3)


def sembrar_arbitrios(args, adm, arb, s, ids):
    print("\n-- arbitrios 2026 (ordenanza como admin; determinación como ARBITRIOS_EMISION) --")
    correr(args, "import_arbitrios.py", "--archivo", os.path.join(DATOS, "arbitrios-2026.json"))
    hechas = [d for d in adm.get(f"/api/srtm/arbitrios/determinaciones?anio={ANIO}") or [] if d.get("estado") == "TERMINADA"]
    if hechas:
        s.omitir("determinacion masiva")
        job = hechas[0]
    else:
        job = s.crear(
            "determinacion masiva", f"{ANIO}",
            lambda: arb.post("/api/srtm/arbitrios/determinaciones", {"anio": ANIO, "observacion": "Determinación masiva 2026 de la demostración"}),
        )  # fmt: skip
        if job:
            job = esperar(adm, f"/api/srtm/arbitrios/determinaciones/{job['id']}")
            print(f"  determinación {job['id']}: {job['estado']}, {job.get('procesados')} predios, {job.get('generadas')} cuotas, errores {job.get('errores')}")
            if job["estado"] != "TERMINADA":
                raise SystemExit(f"error: la determinación quedó {job['estado']}: {job.get('mensaje')}")
    ids["determinacion"] = job and job.get("id")

    # la anulación de una cuota (el barrido de marzo del 01-01-0002) y la redeterminación que escribe su versión 2
    pr = ids["predio"].get("01-01-0002")
    if not pr:
        s.contar("anulacion de cuota", True)
        return
    servicio = adm.buscar("servicio_arbitrio", codigo="BAR")
    cuotas = adm.get(f"/api/srtm/arbitrios?anio={ANIO}&predio={pr}&servicio={servicio['id']}&size=50")["content"] if servicio else []
    marzo = sorted((c for c in cuotas if c.get("periodo") == 3), key=lambda c: c.get("clave"))
    if not marzo:
        s.contar("anulacion de cuota", True)
        return
    primera = marzo[0]
    ids["cuota_anulada"] = primera["id"]
    if adm.buscar("anulacion_cuota_arbitrio", clave=primera["id"]):
        s.omitir("anulacion de cuota")
    else:
        s.crear(
            "anulacion de cuota", f"{primera['clave']}",
            lambda: arb.post(f"/api/srtm/arbitrios/cuotas/{primera['id']}/anulacion",
                             {"motivo": "Frontis mal medido", "observacion": "Se anula para volver a determinar con el frontis verificado en campo (DEMO)"}),
        )  # fmt: skip
    matriz = adm.get(f"/api/srtm/predios/{pr}/arbitrios?anio={ANIO}")
    if matriz and matriz.get("pendientes"):
        s.crear(
            "redeterminacion de predio", f"01-01-0002 ({matriz['pendientes']} pendiente)",
            lambda: arb.post(f"/api/srtm/predios/{pr}/arbitrios", {"anio": ANIO, "observacion": "Redeterminación tras la anulación de marzo (DEMO)"}),
        )  # fmt: skip
    else:
        s.omitir("redeterminacion de predio")


def sembrar_emision(adm, arb, s, ids):
    print("\n-- emisión masiva 2026: HR, PU y HLA en PDF (como ARBITRIOS_EMISION) --")
    hechas = [e for e in adm.get(f"/api/srtm/emisiones?anio={ANIO}") or [] if e.get("estado") == "TERMINADA" and "HLA" in json.dumps(e.get("documentos"))]
    if hechas:
        s.omitir("emision masiva")
        job = hechas[0]
    else:
        job = s.crear("emision masiva", f"{ANIO} PDF HR+PU+HLA", lambda: arb.post("/api/srtm/emisiones", {"anio": ANIO, "formato": "PDF", "documentos": ["HR", "PU", "HLA"]}))
        if job:
            job = esperar(adm, f"/api/srtm/emisiones/{job['id']}")
            print(f"  emisión {job['id']}: {job['estado']}, {job.get('procesados')}/{job.get('total')}, {job.get('tamano')} bytes, errores {job.get('errores')}")
            if job["estado"] != "TERMINADA":
                raise SystemExit(f"error: la emisión quedó {job['estado']}: {job.get('mensaje')}")
    ids["emision"] = job and job.get("id")


def sembrar_infracciones(adm, fis, s, ids):
    print("\n-- infracciones (como FISCALIZADOR) --")
    c = ids["contribuyente"]
    pred = ids["predio"]
    base = "/api/srtm/infracciones"
    notificaciones = [
        # número, contribuyente, predio, fecha, plazo, motivo; la 0002 se subsana y la 0001 vence (su acta es la 0001)
        ("NP-2026-0001", "DEMO-010", "01-02-0004", "2026-09-01", 10, "Anuncio sin autorización municipal en la fachada"),
        ("NP-2026-0002", "DEMO-011", "01-02-0005", "2026-09-21", 15, "Desmonte en la vía pública frente al predio"),
        ("NP-2026-0003", "DEMO-012", "01-02-0006", "2026-10-13", 7, "Cerco perimétrico sin licencia de edificación"),
    ]
    for numero, cont, cod, fecha, plazo, motivo in notificaciones:
        r = adm.buscar("notificacion_administrativa", numero=numero)
        if r:
            s.omitir("notificacion previa")
        else:
            cuerpo = {"numero": numero, "fecha": fecha, "contribuyente": c.get(cont), "predio": pred.get(cod),
                      "direccion": f"Predio {cod}, PERENE", "motivo": motivo, "plazo_dias": plazo,
                      "observacion": "Notificación previa de la demostración"}  # fmt: skip
            r = s.crear("notificacion previa", numero, lambda: fis.post(f"{base}/notificaciones", cuerpo))
        ids["notificacion"][numero] = r and r["id"]
    np2 = ids["notificacion"].get("NP-2026-0002")
    if np2 and adm.buscar("subsanacion_notificacion", clave=np2):
        s.omitir("subsanacion")
    else:
        s.crear("subsanacion", "NP-2026-0002", lambda: fis.post(f"{base}/notificaciones/{np2}/subsanacion", {"fecha": "2026-09-29", "observacion": "Retiró el desmonte (DEMO)"}))

    actas = [
        # número, obligado, predio, fecha, código CUIS, previa
        ("AC-2026-0001", "DEMO-010", "01-02-0004", "2026-09-16", "070", "NP-2026-0001"),
        ("AC-2026-0002", "DEMO-013", "01-02-0007", "2026-08-10", "202", None),
        ("AC-2026-0003", "DEMO-035", "02-01-0003", "2026-07-06", "202", None),
    ]
    for numero, obligado, cod, fecha, codigo, previa in actas:
        r = adm.buscar("papeleta", numero=numero)
        if r:
            s.omitir("acta")
        else:
            cuerpo = {
                "numero": numero, "fecha_infraccion": fecha, "hora_infraccion": "10:30", "lugar": f"Predio {cod}, PERENE",
                "codigo": codigo, "reincidencia": "PRIMERA", "obligado": c.get(obligado), "contribuyente": c.get(obligado),
                "predio": pred.get(cod), "notificacion_previa": ids["notificacion"].get(previa) if previa else None,
                "expediente": None, "inspector": "INSPECTOR DEMO", "descripcion_hecho": "Hecho constatado en la inspección (DEMO)",
                "observacion": "Acta de la demostración",
            }  # fmt: skip
            r = s.crear("acta", f"{numero} código {codigo}", lambda: fis.post(f"{base}/actas", cuerpo))
        ids["acta"][numero] = r and r["id"]

    a2 = ids["acta"].get("AC-2026-0002")
    if a2 and adm.buscar("anulacion_papeleta", clave=a2):
        s.omitir("anulacion de acta")
    else:
        s.crear(
            "anulacion de acta", "AC-2026-0002",
            lambda: fis.post(f"{base}/actas/{a2}/anulacion",
                             {"motivo": "Error en la identificación del obligado", "fecha": "2026-08-14", "observacion": "Anulada de oficio (DEMO)"}),
        )  # fmt: skip

    a3 = ids["acta"].get("AC-2026-0003")
    desc = adm.buscar("descargo_papeleta", numero_expediente="EXP-2026-0101")
    if desc:
        s.omitir("descargo")
    else:
        desc = s.crear(
            "descargo", "EXP-2026-0101 de AC-2026-0003",
            lambda: fis.post(f"{base}/actas/{a3}/descargos", {"numero_expediente": "EXP-2026-0101", "tipo_recurso": "DESCARGO", "fecha": "2026-07-09",
                                                              "sustento": "Tenía la licencia de funcionamiento en trámite", "observacion": "Descargo de la demostración"}),
        )  # fmt: skip
    ris = adm.buscar("resolucion_gerencia", clave_ris=a3) if a3 else None
    if ris:
        s.omitir("resolucion")
    else:
        ris = s.crear(
            "resolucion", "RIS de AC-2026-0003",
            lambda: fis.post(f"{base}/actas/{a3}/resoluciones", {
                "tipo": "ADMINISTRATIVA", "descargo": desc["id"], "sentido": "INFUNDADO", "efecto": "SE_MANTIENE", "fecha": "2026-08-03",
                "sustento": "El descargo no acredita la licencia vigente al día de la infracción", "sancion_accesoria": None,
                "observacion": "Resolución de la demostración"}),
        )  # fmt: skip
    ids["resolucion"] = ris and ris["id"]
    if ris and adm.buscar("notificacion_resolucion", clave=f"{ris['id']}|1"):
        s.omitir("notificacion de resolucion")
    else:
        s.crear(
            "notificacion de resolucion", "RIS de AC-2026-0003",
            lambda: fis.post(f"{base}/resoluciones/{ris['id']}/notificacion", {
                "fecha_diligencia": "2026-08-05", "modalidad": "PERSONAL", "resultado": "NOTIFICADO", "notificador": "NOTIFICADOR DEMO",
                "direccion": None, "receptor": "RECEPTOR DEMO", "documento_receptor": "95000099", "vinculo": "ADMINISTRADOR DEL LOCAL",
                "acuse": "Firmó el cargo", "observacion": "Notificación de la demostración"}),
        )  # fmt: skip


def sembrar_anuncios(adm, anu, s, ids):
    print("\n-- anuncios (como ANUNCIOS) --")
    c, pred = ids["contribuyente"], ids["predio"]
    anuncios = [
        # clave, titular, predio, clase, tipo, denominación, autorización, vigencia, actos
        ("demo-anuncio-01", "DEMO-034", "02-01-0001", "PANEL", "AVISO_LUMINOSO", "COMERCIAL LOS FRESNOS", "2026-03-02", "2026-12-31", []),
        ("demo-anuncio-02", "DEMO-035", "02-01-0003", "LETRERO", "AVISO_SIMPLE", "BODEGA LA ORQUIDEA", "2026-01-20", "2026-12-31",
         [("renovacion", {"fecha": "2026-10-05", "vigencia_hasta": "2027-12-31", "observacion": "Renovación anticipada para 2027 (DEMO)"})]),
        ("demo-anuncio-03", "DEMO-036", "02-01-0004", "TOLDO", "AVISO_SIMPLE", "INVERSIONES VALLE VERDE", "2026-02-10", "2026-07-31", []),
        ("demo-anuncio-04", "DEMO-037", "02-01-0005", "PANTALLA_DIGITAL", "AVISO_ELECTRONICO", "FERRETERIA EL CEDRO", "2026-03-15", "2026-12-31",
         [("cese", {"fecha": "2026-08-31", "motivo": "Cierre del local", "observacion": "Cese comunicado por el titular (DEMO)"})]),
        ("demo-anuncio-05", "DEMO-038", "02-01-0006", "BANDEROLA", "AVISO_SIMPLE", "SERVICIOS GENERALES LAS PALMERAS", "2026-04-01", "2026-04-15",
         [("cese", {"fecha": "2026-04-15", "motivo": "Fin de la campaña", "observacion": "Cese al vencer la banderola (DEMO)"}),
          ("retiro", {"fecha": "2026-04-20", "motivo": "Retirada por el titular", "observacion": "Retiro verificado en campo (DEMO)"})]),
    ]  # fmt: skip
    for clave, titular, cod, clase, tipo, denominacion, desde, hasta, actos in anuncios:
        r = adm.buscar("anuncio", clave_idempotencia=clave)
        if r:
            s.omitir("anuncio")
            aid = r["id"]
        else:
            cuerpo = {
                "contribuyente": c.get(titular), "predio": pred.get(cod), "clase": clase, "tipo": tipo, "emplazamiento": "FACHADA",
                "forma": "RECTANGULAR", "denominacion": denominacion, "direccion": f"Predio {cod}, PERENE", "area": 2.5 if clase != "BANDEROLA" else 4.0,
                "lados": 1, "cantidad": 1, "fecha_autorizacion": desde, "vigencia_hasta": hasta, "expediente": f"EXP-AN-{clave[-2:]}",
                "fecha_expediente": desde, "licencia_texto": f"LF-DEMO-{clave[-2:]}", "observacion": "Anuncio de la demostración",
            }  # fmt: skip
            r = s.crear("anuncio", f"{clase} de {titular} ({clave})", lambda: anu.post("/api/srtm/anuncios", cuerpo, headers={"Idempotency-Key": clave}))
            aid = r and r["anuncio"]["id"]
        ids["anuncio"][clave] = aid
        for acto, cuerpo in actos:
            sufijo = {"renovacion": f"RENOVACION|{cuerpo.get('vigencia_hasta', '')[:4]}", "cese": "CESE", "retiro": "RETIRO"}[acto]
            if aid and adm.buscar("movimiento_anuncio", clave=f"{aid}|{sufijo}"):
                s.omitir(f"{acto} de anuncio")
            else:
                s.crear(f"{acto} de anuncio", clave, lambda: anu.post(f"/api/srtm/anuncios/{aid}/{acto}", cuerpo))


def intentar_brecha(adm, s):
    print("\n-- brecha de recaudación 2026 (como admin; con el catastro apagado se espera un 409) --")
    if s.dry_run:
        print("  (dry-run: no se intenta)")
        return None
    status, cuerpo = adm.llamar("POST", "/api/srtm/brecha/corridas", {"anio": ANIO}, esperados=(202, 400, 403, 409, 422))
    texto = json.dumps(cuerpo, ensure_ascii=False) if isinstance(cuerpo, (dict, list)) else str(cuerpo)
    print(f"  POST /api/srtm/brecha/corridas -> {status}: {texto}")
    if status == 202:
        s.nota("la brecha empezó una corrida: el catastro está configurado")
    return status, cuerpo


def mostrar_estado(adm):
    print("\n== estado (lo que la ui muestra) ==")
    print(f"  /api/srtm/resumen: {json.dumps(adm.get('/api/srtm/resumen'))}")
    for objeto in ("contribuyente", "predio", "declaracion_predial", "domicilio", "medio_contacto", "relacionado", "sustento", "nivel_construccion",
                   "obra_complementaria", "otro_frente", "transferente", "catastro_fiscal", "cuota_arbitrio", "anulacion_cuota_arbitrio",
                   "notificacion_administrativa", "subsanacion_notificacion", "papeleta", "anulacion_papeleta", "descargo_papeleta",
                   "resolucion_gerencia", "notificacion_resolucion", "anuncio", "movimiento_anuncio", "emision_masiva",
                   "determinacion_arbitrio_masiva"):  # fmt: skip
        datos = adm.get(f"/api/objects/{objeto}/records?page=0&size=1")
        print(f"  {objeto}: {(datos or {}).get('totalElements')}")


def main(argv=None):
    p = argparse.ArgumentParser(description="Siembra la base de la demostración de los manuales (idempotente).")
    p.add_argument("--core", default=os.environ.get("WASICHAI_CORE", "http://localhost:8098"))
    p.add_argument("--email", default=os.environ.get("WASICHAI_EMAIL", "admin@wasichai.local"))
    p.add_argument("--password", default=os.environ.get("WASICHAI_PASSWORD", "admin"))
    p.add_argument("--dry-run", action="store_true", help="lee Core y dice qué crearía; no escribe nada")
    p.add_argument("--solo-perfiles", action="store_true", help="solo los roles y las cuentas de perfiles.json")
    p.add_argument("--sin-cargadores", action="store_true", help="no corre los cargadores de model/ (ya cargados)")
    p.add_argument("--permitir-otro-core", action="store_true", help="un Core que no es el de la demo (:8098); el 8090 nunca")
    args = p.parse_args(argv)
    args.core = exigir_core_demo(args.core, args.permitir_otro_core)
    clave = os.environ.get("SRTM_DEMO_PASSWORD", "Demo-2026")
    s = Siembra(args.dry_run)
    try:
        adm = Core(args.core, args.email, args.password, "admin").entrar()
        if not args.solo_perfiles and not args.sin_cargadores:
            cargadores(args)
        perfiles(adm, s, clave)
        if not args.solo_perfiles:
            cli = clientes(args, adm, clave)
            ids = {"contribuyente": {}, "predio": {}, "declaracion": {}, "notificacion": {}, "acta": {}, "anuncio": {}}
            print("\n== c) datos sintéticos ==")
            sembrar_catalogo_vias(adm, s)
            lista = sembrar_padron(adm, cli["REGISTRADOR"], s, ids)
            sembrar_catastro(adm, cli["REGISTRADOR"], s, lista)
            if not args.dry_run:
                sembrar_arbitrios(args, adm, cli["ARBITRIOS_EMISION"], s, ids)
                sembrar_emision(adm, cli["ARBITRIOS_EMISION"], s, ids)
            else:
                print("\n-- arbitrios y emisión: en dry-run solo se listan las que ya hay --")
                print(f"  determinaciones {ANIO}: {[d.get('estado') for d in adm.get(f'/api/srtm/arbitrios/determinaciones?anio={ANIO}') or []]}")
                print(f"  emisiones {ANIO}: {[e.get('estado') for e in adm.get(f'/api/srtm/emisiones?anio={ANIO}') or []]}")
            sembrar_infracciones(adm, cli["FISCALIZADOR"], s, ids)
            sembrar_anuncios(adm, cli["ANUNCIOS"], s, ids)
            intentar_brecha(adm, s)
            mostrar_estado(adm)
            print("\nids notables:")
            print(json.dumps({k: (v if not isinstance(v, dict) else {str(a): b for a, b in v.items()}) for k, v in ids.items() if k != "declaracion"}, indent=1, ensure_ascii=False))
    except CoreError as e:
        print(f"error: Core respondió {e.status}: {e.body}", file=sys.stderr)
        s.resumen()
        return 1
    s.resumen()
    return 0


if __name__ == "__main__":
    sys.exit(main())
