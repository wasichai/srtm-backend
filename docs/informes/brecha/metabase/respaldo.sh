#!/usr/bin/env bash
# Daily backup of one organization's metabase-postgres (plan, Decisión 2026-10-25: one Metabase instance per
# organization, compose project metabase-<org>). Tableros, usuarios y colecciones viven solo ahí; el .duckdb de
# la organización se reconstruye con `make duckdb` y no necesita respaldo propio. Plan § Rulings: pg_dump -Fc,
# se conservan los 14 más recientes de la organización y, si METABASE_RESPALDOS_S3 está puesta, una copia con
# --sse AES256. Pensado para un cron diario en el VPS, una línea por organización (srtm-backend#206).
#
#   metabase/respaldo.sh ORG [ENV_FILE]
#
# ENV_FILE es el archivo de la organización (por omisión ~/.config/srtm-analitica/<org>.env), con las claves de
# metabase.env.example. Escribe metabase-<org>-<AAAAMMDDTHHMMSSZ, UTC>.dump en METABASE_RESPALDOS_DIR (por
# omisión /srv/srtm-metabase/<org>/respaldos): pg_dump vuelca a un
# .parcial y solo se renombra al terminar (como el .parcial de corte.py), así un pg_dump a medias nunca ocupa
# uno de los 14 cupos ni se confunde con un respaldo bueno. El nombre lleva la organización: aunque dos
# compartan carpeta, la rotación de una nunca borra los respaldos de la otra.
set -euo pipefail
aqui=$(cd "$(dirname "$0")" && pwd)
org=${1:?uso: respaldo.sh ORG [ENV_FILE]}
[[ "$org" =~ ^[a-z][a-z0-9-]{1,30}$ ]] || { echo "respaldo: organización inválida: $org" >&2; exit 2; }
env_file=${2:-$HOME/.config/srtm-analitica/$org.env}
[ -r "$env_file" ] || { echo "respaldo: no se puede leer $env_file" >&2; exit 2; }
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a
respaldos=${METABASE_RESPALDOS_DIR:-/srv/srtm-metabase/$org/respaldos}

compose() { METABASE_ORG="$org" docker compose -p "metabase-$org" -f "$aqui/compose.yml" --env-file "$env_file" "$@"; }

umask 077
mkdir -p "$respaldos"
sello=$(date -u +%Y%m%dT%H%M%SZ)
destino="$respaldos/metabase-$org-$sello.dump"
parcial="$destino.parcial"
# a pg_dump killed partway (disco lleno, el contenedor se cae) no debe dejar un .parcial huérfano ni, peor,
# un $destino incompleto contado entre los 14 que se conservan
trap 'rm -f "$parcial"' EXIT

echo "respaldo: metabase-$org/metabase-postgres -> $destino"
compose exec -T metabase-postgres pg_dump -U metabase -Fc metabase > "$parcial"
mv "$parcial" "$destino"

# conserva los 14 más recientes de esta organización: el sello (AAAAMMDDTHHMMSSZ) ordena por nombre igual que
# por fecha
mapfile -t anteriores < <(find "$respaldos" -maxdepth 1 -name "metabase-$org-*.dump" | sort)
sobran=$(( ${#anteriores[@]} - 14 ))
if [ "$sobran" -gt 0 ]; then
  for viejo in "${anteriores[@]:0:$sobran}"; do
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
