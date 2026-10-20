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
   - **En un Mac con Apple Silicon:** ver [En el Mac (arm64)](#en-el-mac-arm64).

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

## En el Mac (arm64)

Se probó el 2026-10-20 en un Mac con Apple Silicon (16 GB), como proyecto `srtm-local`, al lado del túnel a `srtm-ica`.

- **Runtime:** [Colima](https://github.com/abiosoft/colima) con Rosetta. Hace falta además el plugin `docker-buildx`:
  el builder clásico no construye para otra arquitectura.

  ```bash
  brew install colima docker-buildx
  colima start --vm-type vz --vz-rosetta --cpu 4 --memory 6 --disk 30
  ```

- **`colima start` deja `colima` como contexto activo.** Si `DOCKER_HOST` apunta al VPS, igual gana `DOCKER_HOST`.
  Por eso cada comando local lleva `docker --context colima`, y conviene volver con `docker context use default` para
  no cambiarle el daemon a otras terminales.
- **PostGIS:** `postgis/postgis` solo se publica para amd64. Corre emulado con Rosetta si `pg` lleva
  `platform: linux/amd64`, en un archivo de compose aparte que no se versiona:

  ```yaml
  # compose.local.yml
  services:
    pg:
      platform: linux/amd64
  ```

- **Puertos:** si el túnel al VPS ocupa los de siempre, se mueven todos.

  ```bash
  export PRUEBAS_PROYECTO=srtm-local PRUEBAS_PG_PORT=5449 PRUEBAS_SRTM_PORT=8290 PRUEBAS_CATASTRO_PORT=8291 \
    PRUEBAS_SRTM_UI_PORT=5290 PRUEBAS_CATASTRO_UI_PORT=5291
  docker --context colima compose -f develop/pruebas/compose.yml -f compose.local.yml up -d --build
  ```

Con 4 CPU y 6 GB los dos Core arrancan en menos de un minuto.

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

### De punta a punta

Los pasos de [alta-organizacion.md](../../docs/develop/alta-organizacion.md) en orden, para una organización
`ica-prueba`. Es una base desechable: el `--dry-run` de cada script se mira, pero no espera un OK.

1. **Organización:** `POST /api/organizations` en los dos Core, como `admin@wasichai.local`, con el mismo correo y la
   misma clave de administrador. Se anotan los dos ids.
2. **srtm, como ese administrador** (`WASICHAI_CORE=http://localhost:8190`):
   - `apply.py`;
   - `import_catalogos.py` con los datos de Ica;
   - `import_parametros.py`;
   - `import_padron_ica.py --excel <out>/padron_ficticio.xlsx`.
3. **catastro, como ese administrador** (`WASICHAI_CORE=http://localhost:8191`):
   - `apply.py` y `apply_roles.py`;
   - `configurar_region.py --region COSTA`;
   - `crear_cuenta_srtm.py --clave icaprueba > cuenta.txt`.
4. **Cuenta en srtm:** el slug lleva guion, así que en `srtm.env` va
   `SRTM_CATASTRO_ORGANIZACIONES_ICAPRUEBA_ORGANIZACION=<id en srtm>`, junto a `_URL=http://catastro-core:8091` y a
   `_CLIENT_ID` y `_CLIENT_SECRET`.
   - **El secreto se copia de la línea `clientSecret:` de `cuenta.txt`.** La línea de ejemplo que imprime debajo trae
     `<el clientSecret>`: si se copia esa, la brecha falla con «no dio el token de la cuenta de servicio … (401)».
   - **Entradas de la plantilla:** se quitan las que no tienen cuenta todavía, como `DEMO` e `ICA`.
   - Después se corre `up -d --force-recreate srtm-core`.
5. **Catastro:**
   - `import_aranceles.py --frentes <out>/frentes.csv --valores <out>/valores.csv --anio 2026`;
   - `import_fichas.py --fichas <out>/fichas.csv --pisos <out>/pisos.csv`.
6. **Brecha:** `POST /api/srtm/brecha/corridas {"anio":2026}` y después `GET /api/srtm/brecha/2026/panel`.

Con `generar_ficticio.py ... 600`, el panel da:

| Categoría | Predios |
|---|---|
| COINCIDE | 536 (Δ 0,00) |
| SUBVALUADO | 26 |
| SOBREVALUADO | 12 |
| OMISO | 8 |
| SIN_CATASTRO | 17 |
| SIN_VALOR | 9 |

La versión del catastro es `valuacion-v2`. El mensaje «Δ arbitrios no calculado» se espera, porque no hay ordenanza.

## Bajarlo

```bash
docker compose -p srtm-ica -f develop/pruebas/compose.yml down        # conserva los volúmenes
docker compose -p srtm-ica -f develop/pruebas/compose.yml down -v     # y borra la base y los archivos
```
