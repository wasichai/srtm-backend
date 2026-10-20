#!/usr/bin/env bash
# Restaura un respaldo de demo/respaldos/ en otra base del mismo servidor y la verifica contra el manifiesto. Lo llama
# demo.sh restaurar, que carga demo.env.
#
#   demo.sh restaurar [dump|sql|datos] [base]
#
#   dump   (por defecto) pg_restore de srtm_demo.dump en una base vacía
#   sql    psql -f srtm_demo.sql en una base vacía
#   datos  el esquema del .dump (pg_restore --schema-only) y luego datos.sql: prueba que datos.sql solo basta sobre
#          un esquema de la misma versión
#
# La base destino (por defecto srtm_demo_restaurada) se borra y se vuelve a crear. Solo acepta nombres
# srtm_demo_*: nunca restaura sobre srtm_demo, la base de la que salió el respaldo, ni sobre otra.
set -euo pipefail
aqui=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$aqui/../../.." && pwd)
modo=${1:-dump}
base=${2:-srtm_demo_restaurada}
respaldos="$aqui/respaldos"
case "$base" in srtm_demo_*) ;; *) echo "restaurar: $base no es una base srtm_demo_*: no la toco" >&2; exit 2 ;; esac
export PGHOST="${WASICHAI_DB_HOST}" PGPORT="${WASICHAI_DB_PORT}" PGUSER="${WASICHAI_DB_USERNAME}" PGPASSWORD="${WASICHAI_DB_PASSWORD}"

echo "restaurar: $modo → $base (se borra y se crea de nuevo)"
dropdb --if-exists --maintenance-db=postgres "$base"
createdb --maintenance-db=postgres "$base"
case "$modo" in
  dump)
    pg_restore -d "$base" --no-owner --no-privileges --exit-on-error "$respaldos/srtm_demo.dump"
    ;;
  sql)
    psql -d "$base" -v ON_ERROR_STOP=1 -q -f "$respaldos/srtm_demo.sql" > /dev/null
    ;;
  datos)
    pg_restore -d "$base" --no-owner --no-privileges --exit-on-error --schema-only "$respaldos/srtm_demo.dump"
    psql -d "$base" -v ON_ERROR_STOP=1 -q -f "$respaldos/datos.sql" > /dev/null
    ;;
  *)
    echo "restaurar: modo $modo desconocido (dump, sql o datos)" >&2
    exit 2
    ;;
esac

# los archivos de las emisiones, junto a los de la demo (el Core de la restaurada lee SRTM_EMISION_DIR)
if [ -f "$respaldos/emisiones.tar.gz" ]; then
  emisiones="$repo/build/demo/emisiones-$base"
  rm -rf "$emisiones" && mkdir -p "$emisiones"
  tar -xzf "$respaldos/emisiones.tar.gz" -C "$emisiones"
  echo "restaurar: archivos de emisión en $emisiones (SRTM_EMISION_DIR para un Core contra $base)"
fi

python3 "$aqui/verificar.py" --base "$base"
