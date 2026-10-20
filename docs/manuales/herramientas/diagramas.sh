#!/usr/bin/env bash
# Dibuja recursos/diagramas/*.mmd en recursos/diagramas/generados/*.png, a 3x: el mismo archivo sirve al HTML y al
# PDF (prawn-svg no dibuja los foreignObject de Mermaid, y un SVG en <img> no ve las fuentes de la página). Se corre
# desde herramientas/ (make diagramas). Usa el Chromium de Playwright: puppeteer no descarga el suyo.
set -euo pipefail
cd "$(dirname "$0")"
origen=../recursos/diagramas
destino=$origen/generados
mkdir -p "$destino"
# el chrome-headless-shell de la misma revisión que el Chromium de Playwright: el Chromium con ventana se cuelga al
# capturar cuando la pantalla del equipo duerme
chrome=$(node --input-type=module -e "import {chromium} from 'playwright'; console.log(chromium.executablePath())")
revision=$(echo "$chrome" | sed -E 's|.*/chromium-([0-9]+)/.*|\1|')
cache=$(echo "$chrome" | sed -E 's|/chromium-[0-9]+/.*||')
shell=$(find "$cache/chromium_headless_shell-$revision" -name chrome-headless-shell -type f 2>/dev/null | head -1)
if [ -n "$shell" ]; then chrome=$shell; else echo "diagramas: sin chrome-headless-shell (npx playwright install chromium-headless-shell); uso $chrome" >&2; fi
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
