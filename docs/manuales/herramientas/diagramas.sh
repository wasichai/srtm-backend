#!/usr/bin/env bash
# Dibuja recursos/diagramas/*.mmd en recursos/diagramas/generados/*.png, a 3x: el mismo archivo sirve al HTML y al
# PDF (prawn-svg no dibuja los foreignObject de Mermaid, y un SVG en <img> no ve las fuentes de la página). Se corre
# desde herramientas/ (make diagramas). Usa el Chromium de Playwright: puppeteer no descarga el suyo.
set -euo pipefail
cd "$(dirname "$0")"
origen=../recursos/diagramas
destino=$origen/generados
mkdir -p "$destino"
chrome=$(node --input-type=module -e "import {chromium} from 'playwright'; console.log(chromium.executablePath())")
temporal=$(mktemp -d)
trap 'rm -rf "$temporal"' EXIT
printf '{"executablePath": "%s", "args": ["--no-sandbox"], "protocolTimeout": 180000}\n' "$chrome" > "$temporal/puppeteer.json"
# las mismas fuentes que el manual, para que el texto mida lo mismo en el dibujo que en la página
# (en data: URI: la página de mmdc no carga file://)
fuentes=../estilos/fuentes/woff2
{
  printf '@font-face { font-family: "Source Sans 3"; font-weight: 400; src: url(data:font/woff2;base64,%s); }\n' "$(base64 < $fuentes/SourceSans3-Regular.woff2 | tr -d '\n')"
  printf '@font-face { font-family: "Source Sans 3"; font-weight: 600; src: url(data:font/woff2;base64,%s); }\n' "$(base64 < $fuentes/SourceSans3-Semibold.woff2 | tr -d '\n')"
} > "$temporal/fuentes.css"

for mmd in "$origen"/*.mmd; do
  nombre=$(basename "$mmd" .mmd)
  npx --no-install mmdc -q -i "$mmd" -o "$destino/$nombre.png" -c mermaid.json -C "$temporal/fuentes.css" -p "$temporal/puppeteer.json" -b white -s 3
  echo "diagrama: $nombre"
done
