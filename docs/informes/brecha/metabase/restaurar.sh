#!/usr/bin/env bash
# Restores a backup written by metabase/respaldo.sh into metabase-postgres and starts metabase again
# (srtm-backend#206, task 9). pg_restore --clean --if-exists wipes whatever metabase-postgres currently
# holds, so this refuses to run without an ARCHIVO it can read - the only guard against restoring by accident.
#
#   metabase/restaurar.sh ENV_FILE ARCHIVO
#
# ARCHIVO is a .dump written by respaldo.sh (pg_dump -Fc). metabase is stopped first (its connection pool
# must not be writing while the base underneath is replaced) and started again once the restore is done.
set -euo pipefail
aqui=$(cd "$(dirname "$0")" && pwd)
env_file=${1:?uso: restaurar.sh ENV_FILE ARCHIVO}
archivo=${2:?uso: restaurar.sh ENV_FILE ARCHIVO}
[ -r "$env_file" ] || { echo "restaurar: no se puede leer $env_file" >&2; exit 2; }
[ -r "$archivo" ] || { echo "restaurar: no se puede leer $archivo" >&2; exit 2; }

compose() { docker compose -p srtm-metabase -f "$aqui/compose.yml" --env-file "$env_file" "$@"; }

echo "restaurar: se detiene metabase mientras se restaura metabase-postgres"
compose stop metabase
compose up -d metabase-postgres

echo "restaurar: $archivo -> metabase-postgres"
compose exec -T metabase-postgres pg_restore --clean --if-exists --no-owner -U metabase -d metabase < "$archivo"

echo "restaurar: se arranca metabase"
compose up -d metabase
echo "restaurar: listo"
