#!/bin/sh
# arma los contextos de build de compose.yml: los jar de los dos backends y el dist de las dos ui, desde los checkouts
# que se le pasen (por ejemplo worktrees de origin/main). con un daemon remoto el contexto viaja por el túnel: por eso
# no se monta nada
# uso: develop/pruebas/preparar.sh <srtm-backend> <catastro-backend> <srtm-ui> <catastro-ui>
set -eu
here=$(cd "$(dirname "$0")" && pwd)
[ $# -eq 4 ] || { echo "uso: $0 <srtm-backend> <catastro-backend> <srtm-ui> <catastro-ui>" >&2; exit 2; }
for x in srtm catastro; do
  mkdir -p "$here/core-$x" "$here/ui-$x"
  cp "$here/core/Dockerfile" "$here/core-$x/"
  cp "$here/ui/Dockerfile" "$here/ui-$x/"
done
(cd "$1" && ./gradlew bootJar -q) && cp "$1/build/libs/app.jar" "$here/core-srtm/app.jar"
(cd "$2" && ./gradlew bootJar -q) && cp "$2/build/libs/app.jar" "$here/core-catastro/app.jar"
(cd "$3" && yarn install --frozen-lockfile && yarn build) && rm -rf "$here/ui-srtm/dist" && cp -R "$3/dist" "$here/ui-srtm/dist"
(cd "$4" && yarn install --frozen-lockfile && yarn build) && rm -rf "$here/ui-catastro/dist" && cp -R "$4/dist" "$here/ui-catastro/dist"
sed 's/__API__/srtm-core:8090/' "$here/ui/nginx.conf.tpl" > "$here/ui-srtm/nginx.conf"
sed 's/__API__/catastro-core:8091/' "$here/ui/nginx.conf.tpl" > "$here/ui-catastro/nginx.conf"
echo listo
