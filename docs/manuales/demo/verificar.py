"""Verifica una base de la demo de los manuales, o escribe el manifiesto de sus respaldos.

    python3 demo/verificar.py --base srtm_demo --escribir-manifiesto   # lo llama exportar.sh
    python3 demo/verificar.py --base srtm_demo_restaurada              # lo llama restaurar.sh

Con --escribir-manifiesto lee la base y escribe demo/respaldos/manifiesto.json: versiones (srtm-backend, PostgreSQL,
PostGIS, historial de Flyway de cada módulo), el conteo de filas de cada tabla de wasichai y app_data, las sumas sha256
de los respaldos y el orden de restauración.

Sin él, compara la base con el manifiesto y comprueba su integridad:
- las mismas tablas con los mismos conteos;
- el mismo historial de Flyway, sin migraciones fallidas;
- las mismas restricciones sin validar que el original (las migraciones de wasichai agregan algunas NOT VALID a
  propósito) y ninguna clave foránea rota (lo vuelve a comprobar fila a fila);
- las entidades que muestran los manuales: contribuyentes con DJ vigentes, el condominio, la DJ anulada, cuotas y su
  anulación, la emisión TERMINADA, actas, anuncios en sus cuatro estados, los seis usuarios y sus roles.

Usa psql (las variables PGHOST, PGPORT, PGUSER y PGPASSWORD, o las WASICHAI_DB_* de demo.env). Sale con 1 si algo falla.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys

AQUI = pathlib.Path(__file__).resolve().parent
REPO = AQUI.parent.parent.parent
RESPALDOS = AQUI / "respaldos"
MANIFIESTO = RESPALDOS / "manifiesto.json"
ARCHIVOS = ["srtm_demo.dump", "srtm_demo.sql", "datos.sql", "emisiones.tar.gz"]


def entorno():
    env = dict(os.environ)
    env.setdefault("PGHOST", env.get("WASICHAI_DB_HOST", "127.0.0.1"))
    env.setdefault("PGPORT", env.get("WASICHAI_DB_PORT", "5447"))
    env.setdefault("PGUSER", env.get("WASICHAI_DB_USERNAME", "srtm"))
    env.setdefault("PGPASSWORD", env.get("WASICHAI_DB_PASSWORD", "srtm"))
    return env


def consulta(base, sql):
    """Filas de una consulta, como listas de textos (psql -At, separador |)."""
    salida = subprocess.run(
        ["psql", "-d", base, "-At", "-F", "|", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, env=entorno(),
    )
    if salida.returncode != 0:
        raise SystemExit(f"verificar: psql falló en {base}: {salida.stderr.strip()}")
    return [linea.split("|") for linea in salida.stdout.splitlines() if linea]


def uno(base, sql):
    filas = consulta(base, sql)
    return filas[0][0] if filas else None


def conteos(base):
    tablas = consulta(base, """
        select table_schema || '.' || table_name from information_schema.tables
        where table_schema in ('wasichai', 'app_data') and table_type = 'BASE TABLE' order by 1""")
    # una sola consulta con un count por tabla: cada conexión nueva cuesta
    partes = [f"select '{t}', count(*) from {t.split('.')[0]}.\"{t.split('.')[1]}\"" for (t,) in tablas]
    filas = consulta(base, " union all ".join(partes)) if partes else []
    return {tabla: int(n) for tabla, n in filas}


def flyway(base):
    tablas = [t for (t,) in consulta(base, """
        select table_name from information_schema.tables
        where table_schema = 'wasichai' and table_name like 'flyway_history_%' order by 1""")]
    historial = {}
    for tabla in tablas:
        filas = consulta(base, f'select version, success from wasichai."{tabla}" where version is not null order by installed_rank')
        historial[tabla.removeprefix("flyway_history_")] = {
            "ultima": filas[-1][0] if filas else None,
            "migraciones": len(filas),
            "fallidas": sum(1 for _, ok in filas if ok != "t"),
        }
    return historial


def version_srtm():
    texto = (REPO / "gradle.properties").read_text(encoding="utf-8")
    m = re.search(r"^version=(.+)$", texto, re.MULTILINE)
    return m.group(1).strip() if m else None


def sha256(archivo):
    h = hashlib.sha256()
    with open(archivo, "rb") as f:
        for bloque in iter(lambda: f.read(1 << 20), b""):
            h.update(bloque)
    return h.hexdigest()


def tabla_de(base, objeto):
    """La tabla física de un objeto de Core en app_data (su nombre lleva el sufijo de la organización)."""
    return uno(base, f"select physical_table from wasichai.custom_objects where name = '{objeto}'")


def entidades(base):
    """Lo que los manuales muestran, contado en la base."""
    t = {o: tabla_de(base, o) for o in (
        "contribuyente", "predio", "declaracion_predial", "catastro_fiscal", "cuota_arbitrio",
        "anulacion_cuota_arbitrio", "emision_masiva", "papeleta", "anulacion_papeleta", "resolucion_gerencia",
        "notificacion_administrativa", "anuncio", "movimiento_anuncio", "parametro_tributario")}
    def contar(objeto, donde="true"):
        if not t[objeto]:
            return 0
        return int(uno(base, f'select count(*) from app_data."{t[objeto]}" where {donde}'))
    return {
        "usuarios": int(uno(base, "select count(*) from wasichai.users")),
        "roles": int(uno(base, "select count(*) from wasichai.roles")),
        "contribuyentes": contar("contribuyente"),
        "predios": contar("predio"),
        "declaraciones": contar("declaracion_predial"),
        "declaraciones_anuladas": contar("declaracion_predial", "estado = 'ANULADA'"),
        "condominos": contar("declaracion_predial", "condicion_propiedad = 'CONDOMINO'"),
        "lotes_catastro": contar("catastro_fiscal"),
        "cuotas_arbitrio": contar("cuota_arbitrio"),
        "cuotas_anuladas": contar("anulacion_cuota_arbitrio"),
        "emisiones_terminadas": contar("emision_masiva", "estado = 'TERMINADA'"),
        "actas": contar("papeleta"),
        "actas_anuladas": contar("anulacion_papeleta"),
        "resoluciones": contar("resolucion_gerencia"),
        "notificaciones_previas": contar("notificacion_administrativa"),
        "anuncios": contar("anuncio"),
        "movimientos_anuncio": contar("movimiento_anuncio"),
        "parametros": contar("parametro_tributario"),
    }


def sin_validar(base):
    return [c for (c,) in consulta(base, """select conname from pg_constraint c join pg_namespace n on n.oid = c.connamespace
        where not c.convalidated and n.nspname in ('wasichai', 'app_data') order by 1""")]


def claves_foraneas_rotas(base):
    """Cada clave foránea de app_data y wasichai, comprobada fila a fila (una restauración con triggers apagados
    podría haber dejado una sin padre)."""
    fks = consulta(base, """
        select c.conname, n.nspname, t.relname, a.attname, fn.nspname, ft.relname, fa.attname
        from pg_constraint c
        join pg_class t on t.oid = c.conrelid join pg_namespace n on n.oid = t.relnamespace
        join pg_class ft on ft.oid = c.confrelid join pg_namespace fn on fn.oid = ft.relnamespace
        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
        join pg_attribute fa on fa.attrelid = c.confrelid and fa.attnum = c.confkey[1]
        where c.contype = 'f' and array_length(c.conkey, 1) = 1 and n.nspname in ('wasichai', 'app_data')""")
    if not fks:
        return [], 0
    partes = [
        f"select '{nombre}', count(*) from {ns}.\"{tabla}\" h where h.\"{col}\" is not null and not exists "
        f"(select 1 from {fns}.\"{ftabla}\" p where p.\"{fcol}\" = h.\"{col}\")"
        for nombre, ns, tabla, col, fns, ftabla, fcol in fks
    ]
    rotas = [(n, int(c)) for n, c in consulta(base, " union all ".join(partes)) if int(c) > 0]
    return rotas, len(fks)


def escribir_manifiesto(base):
    servidor = uno(base, "show server_version")
    postgis = uno(base, "select extversion from pg_extension where extname = 'postgis'")
    manifiesto = {
        "generado": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "base": base,
        "versiones": {
            "srtm_backend": version_srtm(),
            "postgresql": servidor,
            "postgis": postgis,
            "imagen_docker": "postgis/postgis:18-3.6",
            "flyway": flyway(base),
        },
        "restauracion": [
            "1. docker compose -p srtm-docs -f docs/manuales/demo/compose.demo.yml up -d  (o una base PostgreSQL 18 con PostGIS 3.6)",
            "2. createdb srtm_demo_restaurada  (vacía: el respaldo trae extensiones, esquemas e historial de Flyway)",
            "3. pg_restore --no-owner --no-privileges -d srtm_demo_restaurada srtm_demo.dump  (o psql -f srtm_demo.sql)",
            "4. tar -xzf emisiones.tar.gz -C <SRTM_EMISION_DIR del Core>",
            "5. python3 docs/manuales/demo/verificar.py --base srtm_demo_restaurada",
            "6. Core de la misma versión (srtm_backend) con WASICHAI_DB_NAME=srtm_demo_restaurada: las migraciones no tienen nada que hacer",
        ],
        "archivos": {
            nombre: {"bytes": (RESPALDOS / nombre).stat().st_size, "sha256": sha256(RESPALDOS / nombre)}
            for nombre in ARCHIVOS if (RESPALDOS / nombre).exists()
        },
        "restricciones_sin_validar": sin_validar(base),
        "entidades": entidades(base),
        "tablas": conteos(base),
    }
    MANIFIESTO.write_text(json.dumps(manifiesto, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"verificar: manifiesto escrito ({len(manifiesto['tablas'])} tablas, {sum(manifiesto['tablas'].values())} filas)")
    return 0


def verificar(base):
    if not MANIFIESTO.exists():
        raise SystemExit("verificar: no hay demo/respaldos/manifiesto.json (demo.sh exportar)")
    esperado = json.loads(MANIFIESTO.read_text(encoding="utf-8"))
    fallas = []

    actuales = conteos(base)
    for tabla, n in esperado["tablas"].items():
        if tabla not in actuales:
            fallas.append(f"falta la tabla {tabla}")
        elif actuales[tabla] != n:
            fallas.append(f"{tabla}: {actuales[tabla]} filas, el manifiesto dice {n}")
    for tabla in sorted(set(actuales) - set(esperado["tablas"])):
        fallas.append(f"tabla que el manifiesto no tiene: {tabla}")

    historial = flyway(base)
    if historial != esperado["versiones"]["flyway"]:
        fallas.append(f"historial de Flyway distinto: {historial}")
    for modulo, h in historial.items():
        if h["fallidas"]:
            fallas.append(f"Flyway {modulo}: {h['fallidas']} migraciones fallidas")

    pendientes = sin_validar(base)
    if pendientes != esperado.get("restricciones_sin_validar", []):
        fallas.append(f"restricciones sin validar {pendientes}, el manifiesto dice {esperado.get('restricciones_sin_validar')}")
    rotas, total_fk = claves_foraneas_rotas(base)
    fallas += [f"clave foránea rota: {n} ({c} filas)" for n, c in rotas]

    actuales_ent = entidades(base)
    if actuales_ent != esperado["entidades"]:
        for clave, valor in esperado["entidades"].items():
            if actuales_ent.get(clave) != valor:
                fallas.append(f"entidad {clave}: {actuales_ent.get(clave)}, el manifiesto dice {valor}")
    minimos = {"usuarios": 6, "roles": 6, "contribuyentes": 30, "predios": 40, "declaraciones": 40, "condominos": 2,
               "declaraciones_anuladas": 1, "cuotas_arbitrio": 1, "emisiones_terminadas": 1, "actas": 3, "anuncios": 4}
    for clave, minimo in minimos.items():
        if (actuales_ent.get(clave) or 0) < minimo:
            fallas.append(f"la demo debería tener al menos {minimo} {clave}, tiene {actuales_ent.get(clave)}")

    if fallas:
        print("\n".join(f"FALLA {f}" for f in fallas))
        print(f"verificar: {base}: {len(fallas)} fallas")
        return 1
    filas = sum(actuales.values())
    modulos = ", ".join(f"{m} {h['ultima']}" for m, h in historial.items())
    print(f"verificar: {base}: ok ({len(actuales)} tablas y {filas} filas como el manifiesto; {total_fk} claves foráneas "
          f"comprobadas; Flyway {modulos})")
    print("verificar: entidades " + ", ".join(f"{k}={v}" for k, v in actuales_ent.items()))
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base", required=True)
    p.add_argument("--escribir-manifiesto", action="store_true")
    a = p.parse_args()
    if not a.base.startswith("srtm_demo"):
        raise SystemExit(f"verificar: {a.base} no es una base de la demo (srtm_demo*)")
    return escribir_manifiesto(a.base) if a.escribir_manifiesto else verificar(a.base)


if __name__ == "__main__":
    sys.exit(main())
