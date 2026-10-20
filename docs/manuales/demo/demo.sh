#!/usr/bin/env bash
# La base de demostración de los manuales, de principio a fin (demo/README.adoc). Se corre desde cualquier lugar:
#
#   demo/demo.sh arriba        levanta el contenedor srtm-docs-postgres (compose.demo.yml)
#   demo/demo.sh core          arranca el Core de la demo (:8098) contra srtm_demo, en primer plano
#   demo/demo.sh sembrar [..]  carga el modelo y los datos sintéticos (sembrar_demo.py; --dry-run para ensayar)
#   demo/demo.sh exportar      respaldos/: srtm_demo.dump, srtm_demo.sql, datos.sql, emisiones.tar.gz y manifiesto.json
#   demo/demo.sh restaurar [modo] [base]   restaura en otra base (por defecto srtm_demo_restaurada) y la verifica
#   demo/demo.sh verificar [base]          compara una base con manifiesto.json
#   demo/demo.sh abajo [--borrar]          detiene el contenedor; --borrar elimina también su volumen
#
# Las variables salen de demo/demo.env (copia de demo.env.example). Ningún comando toca otra base que las srtm_demo*
# del proyecto srtm-docs.
set -euo pipefail
aqui=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$aqui/../../.." && pwd)
env_file="$aqui/demo.env"
[ -f "$env_file" ] || env_file="$aqui/demo.env.example"
set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

# la demo es la base srtm_demo* en el puerto de la demo: nunca la de desarrollo (5433) ni otra
guardia() {
  case "${WASICHAI_DB_NAME:-}" in srtm_demo*) ;; *) echo "demo: WASICHAI_DB_NAME=${WASICHAI_DB_NAME:-} no es una base de la demo" >&2; exit 2 ;; esac
  if [ "${WASICHAI_DB_PORT:-}" != "${SRTM_DEMO_PG_PORT:-5447}" ]; then
    echo "demo: WASICHAI_DB_PORT=${WASICHAI_DB_PORT:-} no es el puerto de la demo (${SRTM_DEMO_PG_PORT:-5447})" >&2
    exit 2
  fi
}

compose() { docker compose -p srtm-docs -f "$aqui/compose.demo.yml" "$@"; }

orden=${1:-}
shift || true
case "$orden" in
  arriba)
    guardia
    compose up -d --wait
    echo "demo: srtm-docs-postgres arriba en 127.0.0.1:${SRTM_DEMO_PG_PORT:-5447} del servidor de Docker"
    case "${DOCKER_HOST:-}" in
      unix:///var/run/docker.sock | "") ;;
      *) echo "demo: Docker remoto: abra el túnel, ssh -fN -L ${SRTM_DEMO_PG_PORT:-5447}:localhost:${SRTM_DEMO_PG_PORT:-5447} <servidor>" ;;
    esac
    ;;
  core)
    guardia
    cd "$repo"
    exec ./gradlew bootRun
    ;;
  sembrar)
    guardia
    exec python3 "$aqui/sembrar_demo.py" --core "${WASICHAI_CORE}" "$@"
    ;;
  exportar)
    guardia
    exec "$aqui/exportar.sh" "$@"
    ;;
  restaurar)
    guardia
    exec "$aqui/restaurar.sh" "$@"
    ;;
  verificar)
    guardia
    exec python3 "$aqui/verificar.py" --base "${1:-$WASICHAI_DB_NAME}"
    ;;
  abajo)
    if [ "${1:-}" = "--borrar" ]; then
      echo "demo: se borra el volumen de srtm-docs (la base de la demo y su restauración)"
      compose down -v
    else
      compose down
    fi
    ;;
  *)
    sed -n '2,15p' "$0"
    exit 1
    ;;
esac
