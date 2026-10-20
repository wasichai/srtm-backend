# Releases

Igual que wasichai: [release-please](https://github.com/googleapis/release-please) sobre Conventional Commits.

## Flujo

1. Se mergean commits convencionales (`feat:`, `fix:`, ...) a `main`.
2. `release-please.yml` corre en cada push a `main` y abre (o actualiza) un PR de release que sube `version=` en
   `gradle.properties`, `docs/manuales/atributos.adoc` y `docs/presentaciones/src/generado/edicion.json`
   (la portada de las presentaciones, que `npm run verificar` compara con los atributos) y escribe `CHANGELOG.md`.
3. Al mergear ese PR, release-please crea el tag `vX.Y.Z` y el GitHub Release.
4. El release dispara `release.yml`: compila `./gradlew bootJar` con la versión del tag y adjunta
   `srtm-backend-X.Y.Z.jar` y su `.sha256` al release.

## Primer release: 0.9.0

La línea parte de `0.8.0` (manifest y `gradle.properties`) y `release-please-config.json` trae
`"release-as": "0.9.0"`, así que el primer PR de release propone exactamente 0.9.0. **Después de mergearlo, quitar
`release-as` de la config**; si no, cada PR siguiente volvería a proponer 0.9.0. Mientras la versión sea 0.x, un
`feat:` sube el minor y un `fix:` el patch (`bump-minor-pre-major`).

## Release 1.0.0

`release-please-config.json` trae `"release-as": "1.0.0"`: el siguiente PR de release propone exactamente 1.0.0 (con
wasichai 1.3.0 y todo lo de `main` desde 0.9.0). **Después de mergearlo, quitar `release-as` de la config**, igual
que tras 0.9.0. Desde 1.x, `bump-minor-pre-major` ya no aplica: un `feat:` sube el minor y un `fix:` el patch, y un
cambio que rompe (`!` o `BREAKING CHANGE:`) sube el major.

## Secreto (una vez)

`RELEASE_PLEASE_TOKEN`: PAT fine-grained sobre este repo con Contents y Pull requests en lectura/escritura. Un
release creado con el `GITHUB_TOKEN` del workflow no dispara otros workflows, y sin PAT `release.yml` no correría.
Sin el secreto, `release-please.yml` usa `GITHUB_TOKEN` (requiere Settings → Actions → General → permitir que
Actions cree PRs) y el jar habría que adjuntarlo a mano.
