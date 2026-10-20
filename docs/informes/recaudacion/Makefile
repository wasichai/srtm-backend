# Informe ejecutivo del estado de la recaudación municipal: datos → indicadores → gráficos → HTML y PDF.
# Se corre desde docs/informes/recaudacion. La edición vive FUERA del repositorio (lleva agregados del padrón):
#
#   make nueva-edicion EDICION=~/informes/recaudacion/2026-10-18   copia la plantilla (no pisa lo que haya)
#   make datos         EDICION=...   descarga el MEF (datos abiertos y Consulta Amigable) y agrega el padrón
#   make build         EDICION=...   indicadores + gráficos + HTML + PDF + validar
#   make publicar      EDICION=...   sube la edición a S3 (parametros.json: publicacion.s3)
#
# Requisitos: Python 3.11 (solo biblioteca estándar), asciidoctor 2.0 y asciidoctor-pdf 2.3 (o las gemas de
# ../../manuales/Gemfile.lock con ASCIIDOCTOR="bundle exec asciidoctor" ...), poppler (pdffonts) y, para publicar,
# la CLI de AWS.

SHELL := /bin/bash
ASCIIDOCTOR ?= asciidoctor
ASCIIDOCTOR_PDF ?= asciidoctor-pdf
PY := python3 -I
AQUI := $(CURDIR)
MANUALES := $(abspath $(CURDIR)/../../manuales)
S3 := $(shell $(PY) -c "import json;print(json.load(open('parametros.json'))['publicacion']['s3'])")

.PHONY: nueva-edicion datos mef consulta padron indicadores graficos html pdf build validar publicar comprobar-edicion

comprobar-edicion:
	@test -n "$(EDICION)" || { echo "falta EDICION=<carpeta de la edición>"; exit 2; }

nueva-edicion: comprobar-edicion
	@mkdir -p "$(EDICION)/textos"
	@cp -n plantilla/informe.adoc "$(EDICION)/" || true
	@for f in plantilla/textos/*.adoc; do cp -n "$$f" "$(EDICION)/textos/" || true; done
	@echo "edición: $(EDICION) (los textos existentes no se tocaron)"

datos: mef consulta padron

mef: comprobar-edicion
	EDICION="$(EDICION)" $(PY) herramientas/mef.py

consulta: comprobar-edicion
	EDICION="$(EDICION)" $(PY) herramientas/consulta_amigable.py

padron: comprobar-edicion
	EDICION="$(EDICION)" $(PY) herramientas/padron.py

indicadores: comprobar-edicion
	EDICION="$(EDICION)" $(PY) herramientas/indicadores.py

graficos: indicadores
	EDICION="$(EDICION)" $(PY) herramientas/graficos.py

html: graficos
	@mkdir -p "$(EDICION)/salida/html/estilos"
	cd "$(EDICION)" && $(ASCIIDOCTOR) --failure-level=WARN -a linkcss -a copycss! -a stylesdir=estilos -a stylesheet=informe.css \
	  -o salida/html/index.html informe.adoc
	@$(PY) "$(MANUALES)/herramientas/posproceso_html.py" "$(EDICION)/salida/html"
	@cp estilos/informe.css "$(MANUALES)/estilos/html/manuales.css" "$(EDICION)/salida/html/estilos/"
	@rsync -a --delete "$(MANUALES)/estilos/fuentes/woff2/" "$(EDICION)/salida/html/estilos/fuentes/woff2/"
	@echo "HTML: $(EDICION)/salida/html/index.html"

pdf: graficos
	@mkdir -p "$(EDICION)/salida/pdf"
	cd "$(EDICION)" && $(ASCIIDOCTOR_PDF) --failure-level=WARN -a pdf-themesdir="$(AQUI)/estilos" \
	  -a pdf-theme=tema-informe.yml -a pdf-fontsdir="$(MANUALES)/estilos/fuentes/ttf;GEM_FONTS_DIR" \
	  -o salida/pdf/informe-recaudacion.pdf informe.adoc
	@echo "PDF: $(EDICION)/salida/pdf/informe-recaudacion.pdf"

validar: comprobar-edicion
	EDICION="$(EDICION)" $(PY) herramientas/validar.py

build: html pdf validar

# la edición entera menos los zips del MEF (se vuelven a bajar; el manifiesto guarda su URL y su SHA-256), más una
# copia del método (estos scripts y la plantilla) en el commit con que se generó
publicar: validar
	@rm -rf "$(EDICION)/metodo" && mkdir -p "$(EDICION)/metodo"
	@git archive --format=tar HEAD . | tar -x -C "$(EDICION)/metodo"
	@git rev-parse HEAD > "$(EDICION)/metodo/COMMIT"
	@git diff --quiet HEAD -- . || echo "aviso: hay cambios sin commit en el método; metodo/ es el de HEAD"
	aws s3 sync "$(EDICION)" "$(S3)/$$(basename "$(EDICION)")/" --exclude "datos/mef/crudos/*" --exclude ".DS_Store" \
	  --sse AES256 --only-show-errors
	@echo "publicado en $(S3)/$$(basename "$(EDICION)")/"
