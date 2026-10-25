#!/usr/bin/env bash
# Daily backup of metabase-postgres (tableros, usuarios y colecciones viven solo ahí; los .duckdb de cada
# organización se reconstruyen con `make duckdb` y no necesitan respaldo propio). Plan § Rulings: pg_dump -Fc,
# se conservan los 14 más recientes y, si METABASE_RESPALDOS_S3 está puesta, una copia con --sse AES256.
# Pensado para un cron diario en el VPS (srtm-backend#206).
#
#   metabase/respaldo.sh ENV_FILE
#
# ENV_FILE es metabase.env (o cualquier archivo con las mismas claves: METABASE_RESPALDOS_DIR,
# METABASE_RESPALDOS_S3). Escribe metabase-<AAAAMMDDTHHMMSSZ, UTC>.dump en METABASE_RESPALDOS_DIR.
set -euo pipefail
aqui=$(cd "$(dirname "$0")" && pwd)
env_file=${1:?uso: respaldo.sh ENV_FILE}
[ -r "$env_file" ] || { echo "respaldo: no se puede leer $env_file" >&2; exit 2; }
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a
: "${METABASE_RESPALDOS_DIR:?falta METABASE_RESPALDOS_DIR en $env_file}"

compose() { docker compose -p srtm-metabase -f "$aqui/compose.yml" --env-file "$env_file" "$@"; }

mkdir -p "$METABASE_RESPALDOS_DIR"
umask 077
sello=$(date -u +%Y%m%dT%H%M%SZ)
destino="$METABASE_RESPALDOS_DIR/metabase-$sello.dump"

echo "respaldo: metabase-postgres -> $destino"
compose exec -T metabase-postgres pg_dump -U metabase -Fc metabase > "$destino"

# conserva los 14 más recientes: el sello (AAAAMMDDTHHMMSSZ) ordena por nombre igual que por fecha
mapfile -t respaldos < <(find "$METABASE_RESPALDOS_DIR" -maxdepth 1 -name 'metabase-*.dump' | sort)
sobran=$(( ${#respaldos[@]} - 14 ))
if [ "$sobran" -gt 0 ]; then
  for viejo in "${respaldos[@]:0:$sobran}"; do
    echo "respaldo: se borra $viejo"
    rm -f "$viejo"
  done
fi

if [ -n "${METABASE_RESPALDOS_S3:-}" ]; then
  s3_destino="${METABASE_RESPALDOS_S3%/}/$(basename "$destino")"
  echo "respaldo: copia a $s3_destino"
  aws s3 cp "$destino" "$s3_destino" --sse AES256 --only-show-errors
fi
echo "respaldo: listo"
