# Corte analítico: una foto fechada y seudonimizada de srtm y catastro, lista para armar un DuckDB y Metabase.
# Se corre desde docs/informes/brecha.
#
#   make venv       crea .venv e instala requirements.txt (se salta si ya está al día)
#   make pruebas    corre las pruebas unitarias
#
# Requisitos: Python 3.11+. Las dependencias (duckdb, pyarrow) van fijas en requirements.txt, instaladas en .venv;
# nada se instala fuera de ella.

PYTHON ?= python3
VPY := .venv/bin/python -I

.PHONY: venv pruebas

venv:
	@if [ -x .venv/bin/python ] && [ ! requirements.txt -nt .venv/bin/python ]; then \
		echo "venv: ya está al día"; \
	else \
		$(PYTHON) -m venv .venv && .venv/bin/pip install -q --disable-pip-version-check -r requirements.txt; \
	fi

pruebas:
	$(VPY) -m unittest discover -s pruebas -t . -v
