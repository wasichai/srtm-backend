#!/usr/bin/env bash
# Exporta la base de la demo (srtm_demo) a demo/respaldos/ y escribe el manifiesto. Lo llama demo.sh exportar, que
# carga demo.env y comprueba que la base es la de la demo.
#
#   srtm_demo.dump    pg_dump -Fc: la base entera (esquemas wasichai y app_data, extensiones, historial de Flyway).
#                     Se restaura con pg_restore en una base vacía. Es el respaldo de referencia.
#   srtm_demo.sql     lo mismo en SQL plano, legible: psql -f en una base vacía.
#   datos.sql         solo los datos de wasichai y app_data, con INSERT por columna: para una base que ya tiene el
#                     esquema (pg_restore --schema-only del .dump, o un Core de la misma versión que la migró).
#   emisiones.tar.gz  los archivos de las emisiones masivas (SRTM_EMISION_DIR): la base los nombra, no los contiene.
#   manifiesto.json   versiones, conteos por tabla, sumas sha256 y el orden de restauración.
set -euo pipefail
aqui=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$aqui/../../.." && pwd)
destino="$aqui/respaldos"
mkdir -p "$destino"
export PGHOST="${WASICHAI_DB_HOST}" PGPORT="${WASICHAI_DB_PORT}" PGUSER="${WASICHAI_DB_USERNAME}" PGPASSWORD="${WASICHAI_DB_PASSWORD}"
base="${WASICHAI_DB_NAME}"

version_servidor=$(psql -d "$base" -Atc "show server_version_num")
version_cliente=$(pg_dump --version | sed -E 's/.* ([0-9]+)\..*/\1/')
if [ "${version_servidor:0:2}" != "$version_cliente" ]; then
  echo "exportar: pg_dump $version_cliente no es de la versión del servidor (${version_servidor:0:2})" >&2
  exit 2
fi

echo "exportar: $base → $destino"
pg_dump -d "$base" -Fc --no-owner --no-privileges -f "$destino/srtm_demo.dump"
pg_dump -d "$base" --no-owner --no-privileges -f "$destino/srtm_demo.sql"
# --disable-triggers: la auditoría (wasichai.audit_log) y las tablas con reglas tienen triggers que una carga de
# datos no debe disparar; restaurar este archivo pide un superusuario, como el srtm de compose.demo.yml
pg_dump -d "$base" --data-only --column-inserts --disable-triggers -n wasichai -n app_data --no-owner --no-privileges \
  -f "$destino/datos.sql"

emisiones="${SRTM_EMISION_DIR:-./data/emisiones}"
case "$emisiones" in /*) ;; *) emisiones="$repo/$emisiones" ;; esac
if [ -d "$emisiones" ]; then
  tar -czf "$destino/emisiones.tar.gz" -C "$emisiones" .
else
  echo "exportar: aviso: no existe $emisiones; emisiones.tar.gz no se genera" >&2
  rm -f "$destino/emisiones.tar.gz"
fi

python3 "$aqui/verificar.py" --base "$base" --escribir-manifiesto
echo "exportar: listo"
ls -l "$destino"
