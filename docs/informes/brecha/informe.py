"""The informe de brechas of an organization, from the DuckDB of one corte analítico (srtm-backend#301).

    python3 -I informe.py nueva   --edicion CARPETA --org ORG --corte CORTE [--nombre "Municipalidad …"]
    python3 -I informe.py generar --edicion CARPETA [--corte-anterior CORTE]
    python3 -I informe.py validar --edicion CARPETA

`nueva` creates the edición (a folder outside the repository): edicion.json and the textos of plantilla/textos,
never overwriting a file that is already there. `generar` computes the indicators from the views of
<CORTE>.duckdb (make local) and, with a corte anterior (the option, else edicion.json's corte_anterior), the
comparison; writes generado/ (indicadores.json, cifras.json, cifras.adoc, graficos/*.svg) and
salida/informe-brechas.html; and then validates. `validar` only validates. Exit codes: 0 ok, 2 bad usage or an
edición/corte that cannot be read, 1 a validation that failed.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

# under python3 -I neither the cwd nor the script's directory is on sys.path: put back only the latter
if os.path.dirname(os.path.abspath(__file__)) not in sys.path:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from herramientas import comun  # noqa: E402
from herramientas import informe_comun as ic  # noqa: E402


def _argumentos():
    parser = argparse.ArgumentParser(prog="informe.py", description="Informe de brechas desde un corte analítico.")
    sub = parser.add_subparsers(dest="accion", required=True)
    nueva = sub.add_parser("nueva", help="crea la edición (no pisa lo que ya exista)")
    nueva.add_argument("--edicion", required=True, help="carpeta de la edición (fuera del repositorio)")
    nueva.add_argument("--org", required=True, help="organización del corte, p.ej. ica")
    nueva.add_argument("--corte", required=True, help="carpeta del corte (o su .duckdb)")
    nueva.add_argument("--nombre", help="nombre de la municipalidad, si la organización no está en parametros.json")
    generar = sub.add_parser("generar", help="indicadores, gráficos, HTML y validación")
    generar.add_argument("--edicion", required=True)
    generar.add_argument("--corte-anterior", dest="corte_anterior", help="carpeta (o .duckdb) del corte anterior")
    validar = sub.add_parser("validar", help="solo la validación")
    validar.add_argument("--edicion", required=True)
    return parser


def nueva(carpeta, org, corte, nombre=None):
    comun.validar_org(org)
    if nombre is None:
        nombre = comun.parametros().get("organizaciones", {}).get(org, {}).get("nombre")
    if not nombre:
        raise comun.ErrorCorte(f"«{org}» no está en parametros.json: da el nombre de la municipalidad (NOMBRE=…)")
    textos = carpeta / "textos"
    textos.mkdir(parents=True, exist_ok=True)
    copiados = 0
    for origen in sorted((ic.PLANTILLA / "textos").glob("*.adoc")):
        destino = textos / origen.name
        if not destino.exists():
            shutil.copyfile(origen, destino)
            copiados += 1
    archivo = carpeta / ic.ARCHIVO_EDICION
    if archivo.exists():
        actual = ic.leer_edicion(carpeta)
        if actual["organizacion"] != org:
            raise comun.ErrorCorte(f"{archivo} ya es de «{actual['organizacion']}», no de «{org}»")
        print(f"edición: {carpeta} ya tenía {ic.ARCHIVO_EDICION}; no se tocó")
    else:
        ic.escribir_json(archivo, {
            "organizacion": org,
            "municipalidad": nombre,
            "corte": str(Path(corte).expanduser().resolve()),
            "corte_anterior": None,
            "version": "borrador",
            "elaborado": "",
        })
    print(f"edición: {carpeta} ({copiados} textos nuevos; los existentes no se tocaron)")
    if not ic.ruta_duckdb(corte).is_file():
        print(f"aviso: falta {ic.ruta_duckdb(corte)}: ármalo con make local CORTE={corte}", file=sys.stderr)


def _metodo():
    try:
        commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=comun.REPO, capture_output=True, text=True,
                                check=True).stdout.strip()
        sucio = subprocess.run(["git", "status", "--porcelain", "--", str(comun.INFORME)], cwd=comun.REPO,
                               capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "commit desconocido"
    return f"commit {commit}" + (" con cambios sin commit" if sucio else "")


def generar(carpeta, corte_anterior=None):
    # duckdb is imported here, not at the top: `nueva` runs without it
    from herramientas import informe_cifras, informe_graficos, informe_html, informe_indicadores

    edicion = ic.leer_edicion(carpeta)
    anterior_ruta = corte_anterior or edicion.get("corte_anterior")
    umbral = ic.umbral()
    try:
        ind = informe_indicadores.desde_archivo(ic.ruta_duckdb(edicion["corte"]), umbral)
        anterior = informe_indicadores.desde_archivo(ic.ruta_duckdb(anterior_ruta), umbral) if anterior_ruta else None
    except informe_indicadores.ErrorIndicadores as e:
        raise comun.ErrorCorte(str(e)) from None
    comparacion = informe_indicadores.comparar(ind, anterior) if anterior else None
    atributos = informe_cifras.cifras(ind, edicion, comparacion)
    graficos = informe_graficos.todos(ind, comparacion, anterior)

    generado = carpeta / "generado"
    ic.escribir_json(generado / "indicadores.json", {
        "corte": edicion["corte"],
        "corte_anterior": str(Path(anterior_ruta).expanduser().resolve()) if anterior_ruta else None,
        "actual": ind,
        "anterior": anterior,
        "comparacion": comparacion,
    })
    ic.escribir_json(generado / "cifras.json", atributos)
    (generado / "cifras.adoc").write_text(informe_cifras.adoc(atributos), encoding="utf-8")
    carpeta_graficos = generado / "graficos"
    if carpeta_graficos.exists():
        shutil.rmtree(carpeta_graficos)  # a chart of an earlier run (the comparison) must not linger
    carpeta_graficos.mkdir(parents=True)
    for nombre, svg in graficos.items():
        (carpeta_graficos / f"{nombre}.svg").write_text(svg, encoding="utf-8")

    textos = [(r.name, r.read_text(encoding="utf-8")) for r in sorted((carpeta / "textos").glob("*.adoc"))]
    if not textos:
        raise comun.ErrorCorte(f"no hay textos en {carpeta / 'textos'}: corre make nueva-edicion")
    try:
        html = informe_html.armar(edicion, atributos, textos, ind, graficos, comparacion, _metodo())
    except informe_html.ErrorTexto as e:
        raise comun.ErrorCorte(str(e)) from None
    salida = carpeta / "salida" / ic.HTML
    salida.parent.mkdir(parents=True, exist_ok=True)
    salida.write_text(html, encoding="utf-8")
    print(f"informe: {salida} ({len(graficos)} gráficos)")


def validar(carpeta):
    from herramientas import informe_validar

    fallas = informe_validar.validar(carpeta)
    if fallas:
        print("\n".join(fallas), file=sys.stderr)
        print(f"validar: {len(fallas)} fallas", file=sys.stderr)
        return 1
    print("validar: ok")
    return 0


def main(argv=None):
    try:
        args = _argumentos().parse_args(argv)
    except SystemExit as e:  # argparse already printed the usage error (2) or the help (0)
        return e.code if isinstance(e.code, int) else 2
    try:
        carpeta = ic.carpeta_edicion(args.edicion)
        if args.accion == "nueva":
            nueva(carpeta, args.org, args.corte, args.nombre)
            return 0
        if args.accion == "generar":
            generar(carpeta, args.corte_anterior)
        return validar(carpeta)
    except comun.ErrorCorte as e:
        print(f"informe: {e}", file=sys.stderr)
        return 2
    except (OSError, json.JSONDecodeError) as e:
        print(f"informe: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
