# Corte analítico: a dated, pseudonymized snapshot of srtm and catastro, ready to build a DuckDB and Metabase.
# Runs from docs/informes/brecha.
#
#   make venv       creates .venv and installs requirements.txt (skipped if already up to date)
#   make pruebas    runs the unit tests
#
# Requirements: Python 3.11+. The dependencies (duckdb, pyarrow) are pinned in requirements.txt, installed in
# .venv; nothing is installed outside it.

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
