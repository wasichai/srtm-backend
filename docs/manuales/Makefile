# Manuales de Rentas municipales (SRTM): AsciiDoc → HTML y PDF.
# Se corre desde docs/manuales. Requisitos: asciidoctor 2.0 y asciidoctor-pdf 2.3 (Ruby); Node ≥ 22 solo para
# capturas y diagramas (herramientas/); Python 3.11 para validar y la demo; poppler (pdffonts) para validar el PDF.
#
#   make build         diagramas ya generados + html + pdf + validar
#   make capturas      vuelve a tomar las capturas con Playwright (necesita la demo arriba: ver demo/README.adoc)
#   make diagramas     vuelve a dibujar recursos/diagramas/generados desde los .mmd
#   make sitio         build + salida/index.html, que lleva a html/: salida/ es el sitio de GitHub Pages
#   make limpiar       borra salida/ (solo eso)
#
# El CI usa las gemas de Gemfile.lock: make build ASCIIDOCTOR="bundle exec asciidoctor" ...

SHELL := /bin/bash
ASCIIDOCTOR ?= asciidoctor
ASCIIDOCTOR_PDF ?= asciidoctor-pdf
LIBROS := usuario/registrador usuario/arbitrios-emision usuario/fiscalizador usuario/anuncios usuario/consulta \
          administrador/manual-administrador sistema/manual-sistema
FUENTES := $(addsuffix .adoc,$(LIBROS))
SALIDA := salida
ADOC_COMUN := --failure-level=WARN -R . -a sourcedir=$(CURDIR)
PDF_OPCIONES := -a pdf-themesdir=$(CURDIR)/estilos/pdf -a pdf-theme=tema-srtm.yml \
                -a pdf-fontsdir="$(CURDIR)/estilos/fuentes/ttf;GEM_FONTS_DIR" -a media=print \
                -a source-highlighter=rouge

.PHONY: build html pdf sitio capturas diagramas validar limpiar \
        demo-arriba demo-sembrar demo-exportar demo-restaurar demo-verificar demo-abajo

build: html pdf validar

# la portada del conjunto enlaza los PDF como ../pdf/: el sitio es salida/ entero, con html/ y pdf/ al lado
sitio: build
	@printf '%s\n' '<!doctype html>' '<html lang="es"><head><meta charset="utf-8">' \
	  '<meta http-equiv="refresh" content="0; url=html/index.html">' \
	  '<title>Manuales de Rentas municipales</title></head>' \
	  '<body><p><a href="html/index.html">Manuales de Rentas municipales</a></p></body></html>' \
	  > $(SALIDA)/index.html
	@echo "Sitio: $(SALIDA)/index.html"

html:
	@mkdir -p $(SALIDA)/html/estilos/fuentes
	$(ASCIIDOCTOR) $(ADOC_COMUN) -D $(SALIDA)/html index.adoc $(FUENTES)
	@python3 herramientas/posproceso_html.py $(SALIDA)/html
	@cp estilos/html/manuales.css $(SALIDA)/html/estilos/
	@rsync -a --delete estilos/fuentes/woff2/ $(SALIDA)/html/estilos/fuentes/woff2/
	@rsync -a --delete --exclude '*.mmd' recursos/ $(SALIDA)/html/recursos/
	@echo "HTML: $(SALIDA)/html/index.html"

pdf:
	@mkdir -p $(SALIDA)/pdf
	$(ASCIIDOCTOR_PDF) $(ADOC_COMUN) $(PDF_OPCIONES) -D $(SALIDA)/pdf $(FUENTES)
	@echo "PDF: $(SALIDA)/pdf/"

capturas:
	cd herramientas && npm ci --no-audit --no-fund && node capturas.mjs $(SOLO)

diagramas:
	cd herramientas && npm ci --no-audit --no-fund && ./diagramas.sh

validar:
	python3 herramientas/validar.py

limpiar:
	rm -rf $(SALIDA)

# --- base de demostración (demo/README.adoc) ---
demo-arriba:
	demo/demo.sh arriba
demo-sembrar:
	demo/demo.sh sembrar
demo-exportar:
	demo/demo.sh exportar
demo-restaurar:
	demo/demo.sh restaurar
demo-verificar:
	demo/demo.sh verificar
demo-abajo:
	demo/demo.sh abajo
