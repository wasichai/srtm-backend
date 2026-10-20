# Entorno de pruebas (Docker)

Una base PostGIS con dos bases de datos (`srtm` y `catastro`), los dos Core (srtm-backend y catastro-backend) y las dos
UI (srtm-ui y catastro-ui servidas por nginx, que pasa `/api` a su Core). Sirve para ensayar algo de punta a punta sin
tocar dev: el alta de una organización, una carga, una corrida de brecha. Es un proyecto de compose aparte, con su
propio volumen, y **nunca** usa `srtm-postgres` (dev, :5433) ni `catastro-postgres` (dev, :5434).

El del VPS se levantó el 2026-10-20 como `srtm-ica` para la épica #126 (Municipalidad de Ica).

## Puertos

Todo publica en `127.0.0.1` del host de Docker. Con el daemon en el VPS se entra por túnel.

| Servicio | Puerto (variable) | Dentro |
|---|---|---|
| PostgreSQL | 5448 (`PRUEBAS_PG_PORT`) | 5432 |
| srtm Core | 8190 (`PRUEBAS_SRTM_PORT`) | 8090 |
| catastro Core | 8191 (`PRUEBAS_CATASTRO_PORT`) | 8091 |
| srtm-ui | 5190 (`PRUEBAS_SRTM_UI_PORT`) | 80 |
| catastro-ui | 5191 (`PRUEBAS_CATASTRO_UI_PORT`) | 80 |

## Levantarlo

1. **Claves:** se copian las plantillas y se llenan. `pg.env`, `srtm.env` y `catastro.env` no se versionan.

   ```bash
   cd develop/pruebas
   cp pg.example.env pg.env && cp srtm.example.env srtm.env && cp catastro.example.env catastro.env
   ```

2. **Contextos de build:** jar y dist desde checkouts limpios, por ejemplo worktrees de `origin/main`.

   ```bash
   develop/pruebas/preparar.sh ../srtm-backend-main ../catastro-backend-main ../srtm-ui-main ../catastro-ui-main
   ```

   Las UI necesitan el token de `npm.pkg.github.com` (`.npmrc`) y los backends el de GitHub Packages
   ([docs/develop/README.md](../../docs/develop/README.md#1-requisitos)).

3. **Arranque:**

   ```bash
   DOCKER_HOST=unix:///tmp/docker.sock PRUEBAS_PROYECTO=srtm-ica docker compose -f develop/pruebas/compose.yml up -d --build
   ```

   - **Con el daemon del VPS:** `DOCKER_HOST` es el socket que reenvía el túnel de un usuario con acceso a Docker
     (`ssh -fN -o StreamLocalBindUnlink=yes -L /tmp/docker.sock:/var/run/docker.sock <usuario>@<vps>`).
   - **Sin buildkit:** los Dockerfile no usan `COPY --chmod`, porque el daemon remoto puede no tener buildkit.

4. **Entrar desde el Mac:** un túnel TCP, que también vale con el usuario `github`, que solo hace túneles.

   ```bash
   ssh -fN -o ExitOnForwardFailure=yes -o IdentitiesOnly=yes -i ~/.ssh/github_actions_deploy_stg \
     -L 8190:localhost:8190 -L 8191:localhost:8191 -L 5190:localhost:5190 -L 5191:localhost:5191 github@<vps>
   ```

   Quedan srtm-ui en http://localhost:5190, catastro-ui en http://localhost:5191 y las API en :8190 y :8191. La
   semilla de dev crea `admin@wasichai.local` / `admin` (superadministrador, organización Demo).

Después de cambiar `srtm.env` (por ejemplo, una cuenta de catastro nueva), se recrea solo ese Core:
`docker compose ... up -d srtm-core`. Al arrancar, su log dice `srtm.catastro: catastro de <slug o id>, ...` con cada
entrada que reconoció.

## Datos ficticios

`generar_ficticio.py` arma un padrón de Ica **ficticio**, con contribuyentes «CONTRIBUYENTE PRUEBA nnnn» y DNI 99xxxxxx,
en el formato de `PADRON_CONSTRUCCION.xlsx`. También arma su catastro (fichas, pisos, frentes y valores) con algunas
alteraciones a propósito, que anota en `perturbaciones.csv`:

```bash
python3 develop/pruebas/generar_ficticio.py model develop/pruebas/ficticio 600
```

Con ese par, la brecha tiene que dar exactamente las alteraciones sembradas, y los COINCIDE con Δ 0,00 (el motor Costa
de catastro y el padrón valoran igual al céntimo). `develop/pruebas/ficticio/` no se versiona.

El padrón **real** lleva datos personales: solo entra a un entorno de pruebas con la autorización expresa del
usuario, y no se copia fuera de los contenedores.

## Bajarlo

```bash
docker compose -p srtm-ica -f develop/pruebas/compose.yml down        # conserva los volúmenes
docker compose -p srtm-ica -f develop/pruebas/compose.yml down -v     # y borra la base y los archivos
```
