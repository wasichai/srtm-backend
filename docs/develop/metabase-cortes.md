# Metabase sobre los cortes

Cómo desplegar Metabase sobre el corte analítico de `docs/informes/brecha` (`B/` en lo que sigue), con **una
instancia de Metabase por organización**, cada una con su archivo DuckDB de solo lectura, y cómo mantenerlas
después de cada corte nuevo (srtm-backend#206). El corte en sí, sus Parquet y su manifiesto están en
`B/README.adoc`; esta guía solo cubre Metabase: el compose, la provisión (base, grupo, colección y tableros) y la
prueba de humo.

**Nada de esto corrió en esta rama contra un Metabase ni un VPS reales**: lo que certifica la provisión y la
prueba de humo aquí es `B/pruebas/test_provisionar.py` y `B/pruebas/test_humo.py`, contra un Metabase falso
(`B/pruebas/metabase_falso.py`), y `B/pruebas/test_compose.py`, que valida el compose de dos organizaciones con
`docker compose config`. El primer despliegue real queda para quien lo haga en el VPS, siguiendo esta misma
guía.

## 0. Qué es y por qué

Cada organización tiene su propia instancia de Metabase: un proyecto de compose `metabase-<org>` con su
Metabase, su base de aplicación en Postgres (volumen propio, respaldos propios) y, montada de solo lectura en
`/datos`, solo la carpeta `/srv/srtm-metabase/<org>/duckdb/`, donde está su `<org>.duckdb`, armado a partir de
su último corte publicado en S3. La imagen (`srtm-metabase:0.63.19-duckdb-1.5.5.0`) se construye una vez y la
comparten todas.

**Decisión 2026-10-25: una instancia por organización.** En Metabase 0.63.19, `GET /api/user/recipients`
devuelve el nombre y el email de **todos** los usuarios activos de la instancia cuando el ajuste
`user-visibility` vale `:all`, que es su valor por omisión (`src/metabase/users_rest/api.clj`); restringirlo
(`:group` o `:none`) es una función de pago (`:feature :email-restrict-recipients` en
`src/metabase/users/settings.clj`), así que en OSS no se puede cambiar. Con una instancia compartida, un usuario
de una municipalidad vería, por ejemplo en el selector de destinatarios de una suscripción, los nombres y
emails de los usuarios de las otras. Con una instancia por organización, todos los usuarios que ve son de su
propia municipalidad. El precio es la memoria: **cada instancia ocupa unos 600 MB de RAM en reposo** (sobre
todo la JVM de Metabase), así que el VPS necesita ese margen por cada organización que se agregue; `JAVA_TOOL_OPTIONS=-Xmx2g` y `mem_limit: 3g` en `B/metabase/compose.yml` son el techo por instancia,
no lo que usa.

Dentro de su instancia, la organización tiene una base `Brecha <nombre>`, un grupo `Brecha <org>` y una
colección `Brecha <nombre>`. «All Users» no ve ni la base ni la colección ni la raíz de colecciones, así que
una cuenta que no esté en el grupo (una creada a mano, por ejemplo) no ve nada. La conexión DuckDB abre el
archivo de solo lectura y sin acceso externo (`B/herramientas/base.py`, `INIT_SQL`), y nadie tiene SQL nativo:
las únicas preguntas son las tarjetas fijas de `B/metabase/tableros.json`.

## 1. Archivo de la organización y carpetas del VPS

Cada instancia se configura con el mismo archivo que ya usa el corte, `~/.config/srtm-analitica/<org>.env`
(modo `0600`, nunca en el repositorio), en el usuario del VPS que opera Metabase. Se le agregan las claves de
`B/metabase/metabase.env.example`:

```bash
cd srtm-backend/docs/informes/brecha
cat metabase/metabase.env.example >> ~/.config/srtm-analitica/<org>.env
chmod 600 ~/.config/srtm-analitica/<org>.env
openssl rand -hex 32                       # su MB_ENCRYPTION_SECRET_KEY: una por organización, no se cambia después
vi ~/.config/srtm-analitica/<org>.env      # METABASE_PUERTO, METABASE_PG_PASSWORD, MB_ENCRYPTION_SECRET_KEY
```

- `METABASE_PUERTO` es **distinto por organización** (3310, 3311, …) y no tiene valor por omisión: el compose
  se niega a arrancar sin él, y dos organizaciones con el mismo puerto no pueden estar arriba a la vez. Llevar
  la cuenta en un archivo del VPS (`/srv/srtm-metabase/PUERTOS`, por ejemplo) evita repetir uno.
- `METABASE_PG_PASSWORD` y `MB_ENCRYPTION_SECRET_KEY` también son por organización: copiar el archivo de otra
  organización como punto de partida obliga a cambiar las tres.
- `METABASE_URL` (opcional) es la URL con la que `provisionar.py` y `humo.py` llegan a la instancia; por
  omisión, `http://127.0.0.1:<METABASE_PUERTO>`. Estos scripts la leen **solo** de ese archivo, nunca del
  entorno de la terminal: una `METABASE_URL` que quedó exportada de la sesión de otra organización no puede
  mandar la provisión de esta a la instancia equivocada.

Las carpetas, una por organización:

```
/srv/srtm-metabase/
├── cortes/                # cortes descargados de S3 para armarlos (make duckdb los baja a cortes/<org>/<fecha>)
├── perene/
│   ├── duckdb/            # perene.duckdb: lo único que monta la instancia de perene (/datos, solo lectura)
│   └── respaldos/         # metabase-perene-<sello>.dump (metabase/respaldo.sh)
└── ica/
    ├── duckdb/
    └── respaldos/
```

`<org>/duckdb/` es el único volumen que la instancia de `<org>` monta; `cortes/` y `<org>/respaldos/` solo los
usan los scripts de `B/` desde el propio VPS, nunca un contenedor.

Los cortes bajados se acumulan en `cortes/<org>/<fecha>/`: `make duckdb` no borra los viejos (el `.duckdb` solo
depende del último). Para liberar espacio, a mano, dejando los tres más recientes de una organización:

```bash
ls -d /srv/srtm-metabase/cortes/<org>/*/ | sort | head -n -3 | xargs -r rm -r
```

## 2. Primera puesta de una organización

Con su archivo listo (sección 1):

```bash
cd srtm-backend/docs/informes/brecha
mkdir -p /srv/srtm-metabase/<org>/{duckdb,respaldos}
make duckdb ORG=<org>               # baja su último corte publicado y arma /srv/srtm-metabase/<org>/duckdb/<org>.duckdb
make metabase-up ORG=<org>          # construye la imagen compartida (solo la primera vez) y levanta metabase-<org>
METABASE_ORG=<org> docker compose -p metabase-<org> -f metabase/compose.yml \
  --env-file ~/.config/srtm-analitica/<org>.env ps                 # las dos healthy
```

`make metabase-up ORG=<org>` corre `METABASE_ORG=<org> docker compose -p metabase-<org> -f metabase/compose.yml
--env-file ~/.config/srtm-analitica/<org>.env up -d --build` (otro archivo con `ENTORNO_ORG=…`). `METABASE_ORG`
sale siempre de `ORG`, nunca del archivo: el nombre del proyecto, la carpeta montada y el archivo leído nombran
siempre a la misma organización.

Metabase solo escucha en el loopback del VPS (plan § Rulings: acceso por túnel SSH, sin proxy en este carril).
Desde la máquina de trabajo, un túnel por organización, con el mismo puerto a los dos lados:

```bash
ssh -fN -L <puerto>:localhost:<puerto> <vps>
```

Con el túnel abierto, `http://localhost:<puerto>` es el Metabase de esa organización. `make provisionar`
crea la base con `POST /api/database`, que prueba la conexión al archivo, así que el orden es siempre: primero
`make duckdb`, después `make provisionar`.

```bash
export METABASE_ADMIN_EMAIL=<administrador de la instancia de <org>>
export METABASE_ADMIN_PASSWORD=<su clave>
make provisionar ORG=<org>          # hace el setup inicial de la instancia si nadie lo hizo todavía

export METABASE_CLAVE_USUARIO=<clave temporal>
.venv/bin/python -I provisionar.py usuario --org <org> --email ana@ejemplo.pe --nombre Ana --apellido A
```

`METABASE_ADMIN_EMAIL` y `METABASE_ADMIN_PASSWORD` también pueden ir en el archivo de la organización; las del
entorno tienen prioridad. Una organización que no está en `parametros.json` (una ficticia, por ejemplo) pasa
su nombre con `NOMBRE=…`.

`make provisionar` es idempotente por nombre: correrlo otra vez (por ejemplo, después de cambiar
`tableros.json`) solo crea o actualiza lo que falta o cambió. La base nueva queda cerrada a «All Users» en
cuanto se crea, antes de seguir: si la corrida se corta después, no queda abierta; basta corregir y correrlo de
nuevo. Si la instancia ya tiene la base o el grupo `Brecha …` de **otra** organización (un archivo copiado con
el `METABASE_PUERTO` de otra), `make provisionar` se niega antes de escribir nada y sale con 1: una instancia,
una organización.

`provisionar.py usuario` crea el usuario en el grupo de su organización. El email de un usuario desactivado se
rechaza con una línea y sale con 1: reactivar a alguien se decide en `Admin → People` de Metabase.

Para la prueba de humo hace falta, además, un usuario dedicado solo a ella (por ejemplo `humo-<org>@…`), creado
igual, con una clave que no sea la de ninguna persona:

```bash
export METABASE_CLAVE_USUARIO=<clave del usuario de humo, solo para eso>
.venv/bin/python -I provisionar.py usuario --org <org> --email humo-<org>@<dominio> --nombre Humo --apellido <Org>
make humo ORG=<org> USUARIO=humo-<org>@<dominio>
```

## 3. Después de cada corte

Cada vez que `make corte ORG=<org>` publica un corte nuevo, el `.duckdb` de esa organización tiene que
rehacerse desde ese corte y su instancia tiene que reabrir el archivo:

```bash
make duckdb ORG=<org>
make metabase-reiniciar ORG=<org>
export METABASE_CLAVE_USUARIO=<clave del usuario de humo de <org>>
make humo ORG=<org> USUARIO=humo-<org>@<dominio>
```

`make humo` entra con el usuario de humo de la organización (sección 2), nunca con la cuenta ni la clave de
una persona real.

`make duckdb` baja el corte publicado más reciente que esté completo (con su `manifiesto.json`; si la fecha
más nueva todavía no lo tiene, avisa y usa la anterior), en una carpeta vaciada antes, y reemplaza el archivo
de forma atómica (`herramientas.base.construir_atomico`): si algo falla a medio camino, el `.duckdb` anterior
queda intacto y Metabase sigue sirviendo con él. Sale con 2 cuando la organización no tiene ningún corte
publicado, y con 1 cuando la CLI de AWS falla (credenciales, red). El reinicio es necesario porque el pool de
conexiones de Metabase mantiene abierto el archivo viejo (plan § Rulings): sin reiniciar, los tableros
seguirían mostrando el corte anterior. Unos dos minutos sin tableros mientras reinicia es lo esperado; las
demás organizaciones no se enteran.

## 4. Respaldos: cron y restaurar

Solo el `metabase-postgres` de cada instancia guarda estado que no se puede reconstruir (tableros, usuarios,
colecciones de esa organización); los `.duckdb` se rehacen con `make duckdb`. Un cron diario en el VPS, **una
línea por organización**:

```cron
30 3 * * * cd /ruta/a/srtm-backend/docs/informes/brecha && metabase/respaldo.sh perene
35 3 * * * cd /ruta/a/srtm-backend/docs/informes/brecha && metabase/respaldo.sh ica
```

(o `make respaldo ORG=<org>`, equivalente). `respaldo.sh <org>` lee `~/.config/srtm-analitica/<org>.env`
(otro, con un segundo argumento) y escribe `metabase-<org>-<sello>.dump` en
`/srv/srtm-metabase/<org>/respaldos/` (o en `METABASE_RESPALDOS_DIR`). Se conservan los 14 dumps más recientes
**de esa organización** (la rotación filtra por el nombre, así que dos organizaciones que compartan carpeta no
se borran los respaldos entre sí), y una copia en S3 si `METABASE_RESPALDOS_S3` está puesta en su archivo,
siempre con `--sse AES256`.

Para restaurar uno:

```bash
metabase/restaurar.sh <org> /srv/srtm-metabase/<org>/respaldos/metabase-<org>-<sello>.dump
```

Detiene el `metabase` de esa organización, hace `pg_restore --clean --if-exists` sobre su `metabase-postgres` y
lo arranca de nuevo. Se niega a restaurar un dump cuyo nombre no es `metabase-<org>-*.dump`: el respaldo de
otra organización traería a sus usuarios y tableros. Si `pg_restore` falla, `metabase` queda detenido a
propósito (nunca arranca sobre una base a medio restaurar); el script imprime el comando exacto para
arrancarlo una vez que `metabase-postgres` esté bien.

## 5. Subir versiones

Metabase y el driver DuckDB comunitario se suben **juntos**, nunca uno sin el otro: el driver solo declara
compatible una combinación concreta (hoy, «Metabase 63 + DuckDB 1.5.5», `metabase/Dockerfile`). Antes de subir
cualquiera de los dos:

1. Confirmar en las notas del driver qué versión de Metabase declara compatible la versión nueva.
2. Actualizar en `metabase/Dockerfile` los dos `ARG` de versión y los dos `ARG` de sha256 (calculados una sola
   vez con `shasum -a 256` sobre el jar bajado, como dice el comentario de cada uno), y en
   `metabase/compose.yml` el `image:` compartido y el `METABASE_VERSION`.
3. Poner en `requirements.txt` el mismo `duckdb==` que la versión de DuckDB que ese driver usa - `base.py`
   carga cada Parquet como tabla y corre `vistas.sql` con el mismo formato de almacenamiento que Metabase va a
   abrir; una diferencia ahí puede dejar a Metabase sin poder leer el archivo que `make duckdb` acaba de armar.
4. `METABASE_ORG=ficticia-a docker compose -f metabase/compose.yml --env-file metabase/metabase.env.example
   config -q` y `make pruebas` antes de construir la imagen nueva.
5. Levantar la organización ficticia (sección 6) con la imagen nueva y correr `make humo` antes de tocar las
   reales; después, `make metabase-up ORG=<org>` organización por organización.

## 6. Prueba de humo con una organización ficticia

`make humo` (`herramientas.metabase.humo`) comprueba, en la instancia de una organización:

- que la instancia tiene **una sola base**, sobre `/datos/<org>.duckdb` (cualquier otra base, `Brecha …` o no,
  es una falla);
- que dentro de esa conexión DuckDB `duckdb_databases()` muestra abierto solo `<org>.duckdb`,
  `corte_manifiesto` dice que el corte es de `<org>` y `enable_external_access` es `false` - la instancia solo
  ve su propio DuckDB, por nombre y por contenido;
- que la base sigue sincronizando y que cada tarjeta de cada tablero sigue corriendo;
- con la sesión del usuario de humo: que ve su base y ninguna otra, y que no tiene SQL nativo.

Antes de correrla contra una organización real (o después de cambiar algo en `metabase/`, `tableros.json` o
las versiones de la sección 5), se corre contra una organización ficticia, con su propia instancia, que no
toca ningún dato real:

```bash
make corte-ficticio ORG=ficticia-a SEMILLA=1   # escribe en ~/cortes/ficticio/ficticia-a/<fecha> (SALIDA propia)

mkdir -p /srv/srtm-metabase/ficticia-a/{duckdb,respaldos}
.venv/bin/python -I armar_duckdb.py \
  --corte ~/cortes/ficticio/ficticia-a/<fecha> \
  --destino /srv/srtm-metabase/ficticia-a/duckdb/ficticia-a.duckdb

# ~/.config/srtm-analitica/ficticia-a.env: solo las claves de metabase.env.example, con un puerto libre
make metabase-up ORG=ficticia-a
ssh -fN -L <puerto>:localhost:<puerto> <vps>   # desde la máquina de trabajo, si se provisiona desde ahí

make provisionar ORG=ficticia-a NOMBRE="Ficticia A"
export METABASE_CLAVE_USUARIO=<clave temporal, solo para esta prueba>
.venv/bin/python -I provisionar.py usuario --org ficticia-a --email a@ficticio.pe --nombre A --apellido F

make humo ORG=ficticia-a USUARIO=a@ficticio.pe
```

`ficticio.py` lee de dos servidores falsos en el propio proceso (`sinteticos.datos(SEMILLA)`), nunca por red:
`make corte-ficticio` nunca lee ni escribe un dato real. La misma `SEMILLA` y la misma `FECHA` siempre
escriben los mismos bytes. `make humo` sale con 0 y `humo: OK (<n> comprobaciones)` cuando todo pasa, o imprime
cada falla y sale con 1.

Al terminar, como la organización ficticia tiene su propia instancia, retirarla es bajar su proyecto entero,
volumen incluido (`-v`), sin tocar ninguna instancia real:

```bash
METABASE_ORG=ficticia-a docker compose -p metabase-ficticia-a -f metabase/compose.yml \
  --env-file ~/.config/srtm-analitica/ficticia-a.env down -v
rm -r /srv/srtm-metabase/ficticia-a ~/.config/srtm-analitica/ficticia-a.env
```

Volver a correr la prueba más adelante es repetir esta sección desde el principio.

## 7. Si el humo marca otra base u otro archivo

Una falla de `make humo` que dice que la instancia tiene otra base (`"<org>: la instancia tiene otra base además
de la suya: …"`), que `duckdb_databases()` muestra otro archivo, o que `corte_manifiesto` es de otra
organización, es aislamiento roto - nunca algo que se ignora ni se reintenta. Antes de nada:

1. Apagar la instancia mientras se investiga: `make metabase-detener ORG=<org>` (no toca su
   `metabase-postgres` ni su `.duckdb`).
2. Revisar el archivo de la organización: `METABASE_PUERTO` (¿es el de otra?) y `METABASE_URL` si la tiene.
3. Revisar `/srv/srtm-metabase/<org>/duckdb/`: tiene que tener solo `<org>.duckdb` (y, mientras `make duckdb`
   arma uno nuevo, `<org>.duckdb.nuevo`). Si el archivo es de otra organización, rehacerlo con
   `make duckdb ORG=<org>`.
4. Una base de más se quita como administrador, en `Admin → Databases` → `Remove this database`; después,
   `make provisionar ORG=<org>` y `make humo` de nuevo.

Si la falla es que el usuario de humo ve otra base o tiene SQL nativo, revisar a mano, como administrador,
`Admin → Permissions → Data` y `… → Collections`: el grupo `Brecha <org>` tiene que dar, en las columnas de
Metabase 0.63, `View data: Can view` y `Create queries: Query builder only` en su base, y «All Users»
`Create queries: No` y `No access` en la base, la colección y la raíz. Si la API de permisos de Metabase cambió
de forma entre versiones (sección 5), `provisionar.py` puede estar mandando una forma que esa versión ya no
entiende sin que falle de inmediato; corregirlo, repetir `make provisionar` y volver a correr `make humo`.

## Límite conocido: consultas que necesitan disco

El `temp_directory` de cada conexión DuckDB queda dentro de `/datos`, el volumen que Metabase monta de solo
lectura (`/srv/srtm-metabase/<org>/duckdb`); una consulta que necesite volcar a disco (una agregación o un
`ORDER BY` que no entra en memoria) falla en vez de usar disco temporal. Los cortes de esta brecha son chicos
(del orden de los 20 000 predios de Ica), así que no se ha visto hace falta; si una organización mucho más
grande lo necesita algún día, hace falta otro volumen de escritura solo para eso.
