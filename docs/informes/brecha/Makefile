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
#   make duckdb     ORG=<org>                                    downloads <org>'s latest corte and rebuilds its
#                                                                 Metabase DuckDB file atomically (#206, task 8)
#   make corte-ficticio ORG=<org> SEMILLA=<n> [SALIDA=<carpeta>]  a corte of a fictitious org, no real network
#
# FECHA defaults to today in America/Lima (herramientas.comun.hoy_lima()). SALIDA defaults to
# $(HOME)/cortes/analitica (corte.py's own default) and can be moved with SALIDA=<carpeta base>; verificar and
# publicar read the same <SALIDA>/<ORG>/<FECHA> folder that extraer just wrote. corte-ficticio's own SALIDA
# default is $(HOME)/cortes/ficticio instead (ficticio.py's cortes never belong under cortes/analitica).
#
# publicar uploads the Parquet files first and manifiesto.json last, so a manifest in S3 means a complete corte.
#
# Requirements: besides venv (duckdb, pyarrow), the AWS CLI to publicar and to duckdb (it downloads the
# corte). VERSION_SRTM and VERSION_CATASTRO come from the environment or the make command line; without them
# the manifest keeps them null, with a notice.

SHELL := /bin/bash
PYTHON ?= python3
# PY (bare, stdlib-only) reads parametros.json and hoy_lima() at parse time, before .venv may even exist
# (recaudacion/Makefile:19); VPY (the venv's python) is only for recipes that need duckdb or pyarrow.
PY := python3 -I
VPY := .venv/bin/python -I
SALIDA ?= $(HOME)/cortes/analitica
# make never expands a ~ and the recipes quote every path, so the shell would not either: a SALIDA=~/... or
# CORTE=~/... given on the command line is rewritten to $(HOME) here (the AWS CLI would take "~" as a folder
# name). CORTE also loses a trailing /, or `make local` would write <corte>/.duckdb inside the corte.
sin_tilde = $(patsubst ~/%,$(HOME)/%,$(patsubst ~,$(HOME),$(1)))
CARPETA_BASE := $(call sin_tilde,$(SALIDA))
# corte-ficticio's own default ($(HOME)/cortes/ficticio) only applies when SALIDA was never set by the caller
# (its origin is still this file's own ?= above, line 27); an explicit SALIDA=... (command line or
# environment) is honored for corte-ficticio too, like every other target
CARPETA_BASE_FICTICIO := $(if $(filter file,$(origin SALIDA)),$(call sin_tilde,$(HOME)/cortes/ficticio),$(CARPETA_BASE))
CARPETA_CORTE := $(patsubst %/,%,$(call sin_tilde,$(CORTE)))
# computed once (:=, not a recursively-expanded ?=), so a single `make corte` never asks twice and risks two
# different dates; skipped entirely when FECHA already comes from the command line or the environment
ifeq ($(origin FECHA),undefined)
FECHA := $(shell $(PY) -c "import sys; sys.path.insert(0, '.'); from herramientas import comun; print(comun.hoy_lima())")
endif
S3 := $(patsubst %/,%,$(shell $(PY) -c "import sys, json; sys.path.insert(0, '.'); print(json.load(open('parametros.json'))['publicacion']['s3'])"))
# s3://<bucket>/<prefijo> split for `aws s3api head-object`, which takes the bucket and the key apart
S3_BUCKET := $(firstword $(subst /, ,$(patsubst s3://%,%,$(S3))))
S3_PREFIJO := $(patsubst s3://$(S3_BUCKET)%,%,$(S3))
CARPETA := $(CARPETA_BASE)/$(ORG)/$(FECHA)
DESTINO := $(S3)/$(ORG)/$(FECHA)/
MANIFIESTO_S3 := $(patsubst /%,%,$(S3_PREFIJO)/$(ORG)/$(FECHA)/manifiesto.json)

.PHONY: venv pruebas comprobar-org comprobar-org-fecha extraer verificar publicar corte local duckdb corte-ficticio

# a stamp, not .venv/bin/python (a symlink: -nt would compare against the system interpreter and always
# reinstall); an existing .venv is reused as is, never rebuilt with another $(PYTHON)
venv: .venv/.instalado

.venv/.instalado: requirements.txt
	@test -x .venv/bin/python || $(PYTHON) -m venv .venv
	.venv/bin/pip install -q --disable-pip-version-check -r requirements.txt
	@touch $@

pruebas:
	$(VPY) -m unittest discover -s pruebas -t . -v

comprobar-org:
	@test -n "$(ORG)" || { echo "falta ORG=<organización>"; exit 2; }

comprobar-org-fecha: comprobar-org
	@test -n "$(FECHA)" || { echo "falta FECHA=AAAA-MM-DD"; exit 2; }

extraer: comprobar-org venv
	VERSION_SRTM="$(VERSION_SRTM)" VERSION_CATASTRO="$(VERSION_CATASTRO)" $(VPY) corte.py --org "$(ORG)" \
	  --fecha "$(FECHA)" --salida "$(CARPETA_BASE)" $(if $(REEMPLAZAR),--reemplazar)

verificar: comprobar-org-fecha venv
	$(VPY) corte.py --verificar "$(CARPETA)"

# a published manifiesto.json is immutable (plan § Rulings): without REEMPLAZAR=1 an existing one is never
# overwritten. head-object exits 0 when it exists; a 404 means it does not; any other error (credentials,
# network, bucket) stops the publish instead of passing for "not published". The Parquet files go up first
# and the manifest last: a cut publish leaves no manifest, so a retry is not refused. With REEMPLAZAR=1 the
# old manifest is removed first, so it never describes Parquet files that are being replaced.
publicar: verificar
	@case "$(S3)" in \
		s3://?*/*) ;; \
		*) echo "S3 inválido: '$(S3)' (revisa parametros.json y PY=$(PY)); no se publica nada"; exit 2;; \
	esac
	@if error=$$(aws s3api head-object --bucket "$(S3_BUCKET)" --key "$(MANIFIESTO_S3)" 2>&1 >/dev/null); then \
		if [ -z "$(REEMPLAZAR)" ]; then \
			echo "ya existe $(DESTINO)manifiesto.json (usa REEMPLAZAR=1 para sobrescribirlo)"; exit 2; \
		fi; \
		aws s3 rm "$(DESTINO)manifiesto.json" --only-show-errors || exit 1; \
	elif ! grep -qE '\(404\)|NotFound|Not Found' <<<"$$error"; then \
		echo "no se pudo comprobar $(DESTINO)manifiesto.json; no se publica nada:"; echo "$$error"; exit 1; \
	fi
	aws s3 sync "$(CARPETA)" "$(DESTINO)" --exclude manifiesto.json --sse AES256 --only-show-errors
	aws s3 cp "$(CARPETA)/manifiesto.json" "$(DESTINO)manifiesto.json" --sse AES256 --only-show-errors
	@echo "publicado en $(DESTINO)"

corte: extraer publicar

local: venv
	@test -n "$(CARPETA_CORTE)" || { echo "falta CORTE=<carpeta del corte>"; exit 2; }
	$(VPY) armar_duckdb.py --corte "$(CARPETA_CORTE)" --destino "$(CARPETA_CORTE).duckdb"

# #206 task 8: downloads ORG's latest published corte and rebuilds its Metabase file atomically
# (herramientas.base.construir_atomico); --cortes/--destino-dir keep armar_duckdb.py's own defaults
# (/srv/srtm-metabase/{cortes,duckdb}), the VPS paths docs/develop/metabase-cortes.md (#206, task 11) sets up.
duckdb: comprobar-org venv
	$(VPY) armar_duckdb.py --org "$(ORG)"

# A corte of a fictitious organization, read from two in-process fakes (ficticio.py) - no secrets file, no
# real network; feeds the Metabase isolation smoke test (plan § Rulings). SEMILLA also derives the HMAC key,
# so the same SEMILLA and FECHA always write the same Parquet bytes.
corte-ficticio: comprobar-org venv
	@test -n "$(SEMILLA)" || { echo "falta SEMILLA=<entero>"; exit 2; }
	$(VPY) ficticio.py --org "$(ORG)" --semilla "$(SEMILLA)" --fecha "$(FECHA)" --salida "$(CARPETA_BASE_FICTICIO)"
