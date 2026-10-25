# Corte analítico: a dated, pseudonymized snapshot of srtm and catastro into Parquet, published to S3 and
# loaded into DuckDB (README.adoc). Runs from docs/informes/brecha.
#
#   make venv                                                    creates .venv and installs requirements.txt
#   make pruebas                                                 runs the unit tests
#   make extraer    ORG=<org> [FECHA=AAAA-MM-DD] [REEMPLAZAR=1]  writes the corte locally, nothing published
#   make verificar  ORG=<org>  FECHA=AAAA-MM-DD                  checks a corte against its manifiesto.json
#   make publicar   ORG=<org>  FECHA=AAAA-MM-DD [REEMPLAZAR=1]   verifies, then syncs it to S3
#   make corte      ORG=<org> [FECHA=AAAA-MM-DD] [REEMPLAZAR=1]  extraer + publicar
#   make local      CORTE=<carpeta>                              builds <carpeta>.duckdb to read it with DuckDB
#
# FECHA defaults to today in America/Lima (herramientas.comun.hoy_lima()). SALIDA defaults to ~/cortes/analitica
# (corte.py's own default) and can be moved with SALIDA=<carpeta base>; verificar and publicar read the same
# <SALIDA>/<ORG>/<FECHA> folder that extraer just wrote.
#
# Requirements: besides venv (duckdb, pyarrow), the AWS CLI to publish. VERSION_SRTM and VERSION_CATASTRO come
# from the environment or the make command line; without them the manifest keeps them null, with a notice.

SHELL := /bin/bash
PYTHON ?= python3
VPY := .venv/bin/python -I
SALIDA ?= ~/cortes/analitica
FECHA ?= $(shell $(VPY) -c "import sys; sys.path.insert(0, '.'); from herramientas import comun; print(comun.hoy_lima())")
S3 := $(shell $(VPY) -c "import sys, json; sys.path.insert(0, '.'); print(json.load(open('parametros.json'))['publicacion']['s3'])")

.PHONY: venv pruebas comprobar-org comprobar-org-fecha extraer verificar publicar corte local

venv:
	@if [ -x .venv/bin/python ] && [ ! requirements.txt -nt .venv/bin/python ]; then \
		echo "venv: ya está al día"; \
	else \
		$(PYTHON) -m venv .venv && .venv/bin/pip install -q --disable-pip-version-check -r requirements.txt; \
	fi

pruebas:
	$(VPY) -m unittest discover -s pruebas -t . -v

comprobar-org:
	@test -n "$(ORG)" || { echo "falta ORG=<organización>"; exit 2; }

comprobar-org-fecha: comprobar-org
	@test -n "$(FECHA)" || { echo "falta FECHA=AAAA-MM-DD"; exit 2; }

extraer: comprobar-org venv
	VERSION_SRTM="$(VERSION_SRTM)" VERSION_CATASTRO="$(VERSION_CATASTRO)" $(VPY) corte.py --org "$(ORG)" \
	  --fecha "$(FECHA)" --salida "$(SALIDA)" $(if $(REEMPLAZAR),--reemplazar)

verificar: comprobar-org-fecha venv
	$(VPY) corte.py --verificar "$(SALIDA)/$(ORG)/$(FECHA)"

# a manifiesto.json ya publicado es inmutable (contexto.md): sin REEMPLAZAR=1 no se pisa uno que ya exista
publicar: verificar
	@if [ -z "$(REEMPLAZAR)" ] && aws s3 ls "$(S3)/$(ORG)/$(FECHA)/manifiesto.json" >/dev/null 2>&1; then \
		echo "ya existe $(S3)/$(ORG)/$(FECHA)/manifiesto.json (usa REEMPLAZAR=1 para sobrescribirlo)"; exit 2; \
	fi
	aws s3 sync "$(SALIDA)/$(ORG)/$(FECHA)" "$(S3)/$(ORG)/$(FECHA)/" --sse AES256 --only-show-errors
	@echo "publicado en $(S3)/$(ORG)/$(FECHA)/"

corte: extraer publicar

local: venv
	@test -n "$(CORTE)" || { echo "falta CORTE=<carpeta del corte>"; exit 2; }
	$(VPY) armar_duckdb.py --corte "$(CORTE)" --destino "$(CORTE).duckdb"
