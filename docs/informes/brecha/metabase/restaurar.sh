#!/usr/bin/env bash
# Restores a backup written by metabase/respaldo.sh into one organization's metabase-postgres and starts its
# metabase again (srtm-backend#206, task 9; plan, Decisión 2026-10-25: one instance per organization, compose
# project metabase-<org>). pg_restore --clean --if-exists wipes whatever that metabase-postgres currently holds,
# so this refuses to run without an ARCHIVO it can read - the only guard against restoring by accident.
#
#   metabase/restaurar.sh ORG ARCHIVO [ENV_FILE]
#
# ARCHIVO is a .dump written by respaldo.sh (pg_dump -Fc) for the same ORG: a dump of another organization's
# instance is refused by name (metabase-<org>-*.dump), since it would bring that organization's users and
# tableros into this one's. ENV_FILE defaults to ~/.config/srtm-analitica/<org>.env. metabase is stopped first
# (its connection pool must not be writing while the base underneath is replaced) and started again once the
# restore is done. A failed pg_restore leaves metabase stopped on purpose (never started over a half-restored
# base) and prints the exact command to start it once metabase-postgres is fixed.
set -euo pipefail
aqui=$(cd "$(dirname "$0")" && pwd)
org=${1:?uso: restaurar.sh ORG ARCHIVO [ENV_FILE]}
archivo=${2:?uso: restaurar.sh ORG ARCHIVO [ENV_FILE]}
[[ "$org" =~ ^[a-z][a-z0-9-]{1,30}$ ]] || { echo "restaurar: organización inválida: $org" >&2; exit 2; }
env_file=${3:-$HOME/.config/srtm-analitica/$org.env}
[ -r "$env_file" ] || { echo "restaurar: no se puede leer $env_file" >&2; exit 2; }
[ -r "$archivo" ] || { echo "restaurar: no se puede leer $archivo" >&2; exit 2; }
case "$(basename "$archivo")" in
  "metabase-$org-"*.dump) ;;
  *) echo "restaurar: $archivo no es un respaldo de $org (metabase-$org-*.dump)" >&2; exit 2;;
esac

compose() { METABASE_ORG="$org" docker compose -p "metabase-$org" -f "$aqui/compose.yml" --env-file "$env_file" "$@"; }

echo "restaurar: se detiene metabase-$org/metabase mientras se restaura su metabase-postgres"
compose stop metabase
# --wait: until its healthcheck (pg_isready) passes - a pg_restore against a postgres still starting would fail
compose up -d --wait metabase-postgres

echo "restaurar: $archivo -> metabase-$org/metabase-postgres"
if ! compose exec -T metabase-postgres pg_restore --clean --if-exists --no-owner -U metabase -d metabase < "$archivo"; then
  echo "restaurar: pg_restore falló (revisa el error de arriba); metabase sigue detenido a propósito," >&2
  echo "restaurar: para no dejarlo mirando una base a medio restaurar. Cuando metabase-postgres esté bien," >&2
  echo "restaurar: arráncalo con:" >&2
  echo "  METABASE_ORG=$org docker compose -p metabase-$org -f $aqui/compose.yml \\" >&2
  echo "    --env-file $env_file up -d metabase" >&2
  exit 1
fi

echo "restaurar: se arranca metabase-$org/metabase"
compose up -d metabase
echo "restaurar: listo"
