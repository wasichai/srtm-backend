# Metabase sobre los cortes

Cómo desplegar Metabase sobre el corte analítico de `docs/informes/brecha` (`B/` en lo que sigue), con un
archivo DuckDB de solo lectura por organización, y cómo mantenerlo después de cada corte nuevo
(srtm-backend#206). El corte en sí, sus Parquet y su manifiesto están en `B/README.adoc`; esta guía solo cubre
Metabase: el compose, la provisión (bases, grupos, colecciones y tableros) y la prueba de humo.

**Nada de esto corrió en esta rama contra un Metabase ni un VPS reales**: lo que certifica el aislamiento aquí
es `B/pruebas/test_provisionar.py` y `B/pruebas/test_humo.py`, contra un Metabase falso
(`B/pruebas/metabase_falso.py`). El primer despliegue real queda para quien lo haga en el VPS, siguiendo esta
misma guía.

## 0. Qué es y por qué

Metabase lee cada organización por un archivo DuckDB propio (`<org>.duckdb`), armado a partir de su último
corte publicado en S3. El aislamiento entre organizaciones es el que trae Metabase OSS (sin «Blocked», sin
sandboxing): un grupo y una colección por organización, y la conexión DuckDB abre el archivo de solo lectura y
sin acceso externo (`B/herramientas/base.py`, `INIT_SQL`), así que ni una consulta nativa puede abrir el
archivo de otra organización - nadie tiene SQL nativo de todas formas, porque las únicas preguntas son las
tarjetas fijas de `B/metabase/tableros.json`.

**Límite conocido, pendiente de decisión: los usuarios se ven entre organizaciones.** El aislamiento cubre
datos, tarjetas y tableros, no la lista de usuarios. En Metabase 0.63.19, `GET /api/user/recipients` devuelve
el nombre y el email de **todos** los usuarios activos de la instancia cuando el ajuste `user-visibility` vale
`:all`, que es su valor por omisión (`src/metabase/users_rest/api.clj`); restringirlo (`:group` o `:none`) es
una función de pago (`:feature :email-restrict-recipients` en `src/metabase/users/settings.clj`), así que en
OSS no se puede cambiar. Un usuario de una municipalidad ve, por ejemplo en el selector de destinatarios de una
suscripción, los nombres y emails de los usuarios de Metabase de las otras. Queda como límite aceptado o no por
decidir: aceptarlo (los usuarios son personal municipal, no contribuyentes) o pasar a un Metabase por
organización. Mientras tanto no se toca ningún ajuste de Metabase por esto.

## 1. Carpetas del VPS

```
/srv/srtm-metabase/
├── metabase.env       # de metabase.env.example; nunca se versiona, chmod 600
├── duckdb/            # <org>.duckdb, uno por organización (make duckdb ORG=… los arma ahí)
├── cortes/            # cortes descargados de S3 para armarlos (make duckdb ORG=… los baja ahí)
└── respaldos/         # dumps de metabase-postgres (metabase/respaldo.sh)
```

`duckdb/` es el único volumen que Metabase monta (de solo lectura, `/datos` dentro del contenedor); `cortes/` y
`respaldos/` solo los usan los scripts de `B/` desde el propio VPS, nunca el contenedor de Metabase.

Los cortes bajados se acumulan en `cortes/<org>/<fecha>/`: `make duckdb` no borra los viejos (el `.duckdb` solo
depende del último). Para liberar espacio, a mano, dejando los tres más recientes de una organización:

```bash
ls -d /srv/srtm-metabase/cortes/<org>/*/ | sort | head -n -3 | xargs -r rm -r
```

## 2. Primera puesta

```bash
cd srtm-backend/docs/informes/brecha
cp metabase/metabase.env.example /srv/srtm-metabase/metabase.env
chmod 600 /srv/srtm-metabase/metabase.env
openssl rand -hex 32                 # la MB_ENCRYPTION_SECRET_KEY: se genera una vez y no se cambia después
vi /srv/srtm-metabase/metabase.env   # METABASE_PG_PASSWORD, MB_ENCRYPTION_SECRET_KEY (la de arriba), rutas si cambian

mkdir -p /srv/srtm-metabase/{duckdb,cortes,respaldos}

docker compose -p srtm-metabase -f metabase/compose.yml --env-file /srv/srtm-metabase/metabase.env up -d --build
docker compose -p srtm-metabase -f metabase/compose.yml --env-file /srv/srtm-metabase/metabase.env ps   # las dos healthy
```

Metabase solo escucha en el loopback del VPS (plan § Rulings: acceso por túnel SSH, sin proxy en este carril).
Desde la máquina de trabajo:

```bash
ssh -fN -L 3310:localhost:3310 <vps>
```

Con el túnel abierto, `http://localhost:3310` es el Metabase del VPS. Antes de provisionar nada, cada
organización necesita su archivo `<org>.duckdb` en `/srv/srtm-metabase/duckdb/` - `make provisionar` crea la
base con `POST /api/database`, que prueba la conexión al archivo, así que el orden es siempre: primero
`make duckdb`, después `make provisionar`, y solo con las organizaciones cuyo `.duckdb` ya existe (`ORGS=…`;
sin `ORGS`, `make provisionar` toma todas las de `parametros.json` y se detiene en la primera sin archivo).

```bash
make duckdb ORG=perene                              # baja el último corte publicado de perene y arma su .duckdb

export METABASE_URL=http://localhost:3310
export METABASE_ADMIN_EMAIL=<administrador de Metabase>
export METABASE_ADMIN_PASSWORD=<su clave>
make provisionar ORGS=perene                        # solo las que ya tienen su .duckdb; hace el setup inicial
                                                      # de Metabase si nadie lo hizo todavía

export METABASE_CLAVE_USUARIO=<clave temporal>
.venv/bin/python -I provisionar.py usuario --org perene --email ana@ejemplo.pe --nombre Ana --apellido A
```

`make provisionar` es idempotente por nombre: correrlo otra vez (por ejemplo, después de cambiar
`tableros.json`) solo crea o actualiza lo que falta o cambió. Cada base nueva queda cerrada a «All Users» y a
los demás grupos `Brecha …` en cuanto se crea, antes de seguir: si la corrida se corta después (la base de otra
organización falla, un 409 en el grafo de permisos), ninguna base queda abierta; basta corregir y correrlo de
nuevo.

`provisionar.py usuario` crea el usuario en el grupo de su organización. Un email que ya está en el grupo de
otra organización, o que es de un usuario desactivado, se rechaza con una línea y sale con 1: un usuario es de
una sola organización, y reactivar a alguien se decide en `Admin → People` de Metabase.

Para la prueba de humo de cada organización hace falta, además, un usuario dedicado solo a ella (por ejemplo
`humo-perene@…`), creado igual, con una clave que no sea la de ninguna persona:

```bash
export METABASE_CLAVE_USUARIO=<clave del usuario de humo, solo para eso>
.venv/bin/python -I provisionar.py usuario --org perene --email humo-perene@<dominio> --nombre Humo --apellido Perene
```

## 3. Después de cada corte

Cada vez que `make corte ORG=<org>` publica un corte nuevo, el `.duckdb` de Metabase tiene que rehacerse desde
ese corte y el contenedor tiene que reabrir el archivo:

```bash
make duckdb ORG=<org>
docker compose -p srtm-metabase -f metabase/compose.yml --env-file /srv/srtm-metabase/metabase.env restart metabase
export METABASE_CLAVE_USUARIO=<clave del usuario de humo de <org>>
make humo USUARIOS="<org>:humo-<org>@<dominio>"
```

`make humo` entra con el usuario de humo de la organización (sección 2), nunca con la cuenta ni la clave de
una persona real.

`make duckdb` baja el corte publicado más reciente que esté completo (con su `manifiesto.json`; si la fecha
más nueva todavía no lo tiene, avisa y usa la anterior), en una carpeta vaciada antes, y reemplaza el archivo
de forma atómica (`herramientas.base.construir_atomico`): si algo falla a medio camino, el `.duckdb` anterior
queda intacto y Metabase sigue sirviendo con él. Sale con 2 cuando la organización no tiene ningún corte
publicado, y con 1 cuando la CLI de AWS falla (credenciales, red). El `restart` es necesario porque el pool de
conexiones de Metabase mantiene abierto el archivo viejo (plan § Rulings): sin reiniciar, los tableros
seguirían mostrando el corte anterior. Unos dos minutos sin tableros mientras reinicia es lo esperado.

## 4. Respaldos: cron y restaurar

Solo `metabase-postgres` guarda estado que no se puede reconstruir (tableros, usuarios, colecciones); los
`.duckdb` se rehacen con `make duckdb`. Un cron diario en el VPS:

```cron
30 3 * * * cd /ruta/a/srtm-backend/docs/informes/brecha && metabase/respaldo.sh /srv/srtm-metabase/metabase.env
```

(o `make respaldo METABASE_ENV=/srv/srtm-metabase/metabase.env`, equivalente). Se conservan los 14 dumps más recientes
en `/srv/srtm-metabase/respaldos/`, y una copia en S3 si `METABASE_RESPALDOS_S3` está puesta en
`metabase.env`, siempre con `--sse AES256`.

Para restaurar uno:

```bash
metabase/restaurar.sh /srv/srtm-metabase/metabase.env /srv/srtm-metabase/respaldos/metabase-<sello>.dump
```

Detiene `metabase`, hace `pg_restore --clean --if-exists` sobre `metabase-postgres` y arranca `metabase` de
nuevo. Si `pg_restore` falla, `metabase` queda detenido a propósito (nunca arranca sobre una base a medio
restaurar); el script imprime el comando exacto para arrancarlo una vez que `metabase-postgres` esté bien.

## 5. Subir versiones

Metabase y el driver DuckDB comunitario se suben **juntos**, nunca uno sin el otro: el driver solo declara
compatible una combinación concreta (hoy, «Metabase 63 + DuckDB 1.5.5», `metabase/Dockerfile`). Antes de subir
cualquiera de los dos:

1. Confirmar en las notas del driver qué versión de Metabase declara compatible la versión nueva.
2. Actualizar en `metabase/Dockerfile` los dos `ARG` de versión y los dos `ARG` de sha256 (calculados una sola
   vez con `shasum -a 256` sobre el jar bajado, como dice el comentario de cada uno).
3. Poner en `requirements.txt` el mismo `duckdb==` que la versión de DuckDB que ese driver usa - `base.py`
   carga cada Parquet como tabla y corre `vistas.sql` con el mismo formato de almacenamiento que Metabase va a
   abrir; una diferencia ahí puede dejar a Metabase sin poder leer el archivo que `make duckdb` acaba de armar.
4. `docker compose -f metabase/compose.yml --env-file metabase/metabase.env.example config -q` y
   `make pruebas` antes de construir la imagen nueva.
5. Reconstruir (`up -d --build`) y correr `make humo` contra las organizaciones ficticias (sección 6) antes de
   tocar las reales.

## 6. Prueba de humo con dos organizaciones ficticias

`make humo` (`herramientas.metabase.humo`) comprueba que cada base `Brecha …` sigue sincronizando, que cada
tarjeta de cada tablero sigue corriendo y, con la sesión de cada usuario, que el aislamiento sigue en pie: no
ve la base de otra organización, no puede leer una tarjeta ajena y no tiene SQL nativo en ninguna base, ni
ajena ni la propia. Antes de correrla contra una organización real (o después de cambiar algo en
`metabase/`, `tableros.json` o las versiones de la sección 5), se corre contra dos organizaciones ficticias que
no tocan ningún dato real:

```bash
make corte-ficticio ORG=ficticia-a SEMILLA=1   # escribe en ~/cortes/ficticio/ficticia-a/<fecha> (SALIDA propia)
make corte-ficticio ORG=ficticia-b SEMILLA=2   # ídem, ~/cortes/ficticio/ficticia-b/<fecha>

.venv/bin/python -I armar_duckdb.py \
  --corte ~/cortes/ficticio/ficticia-a/<fecha> \
  --destino /srv/srtm-metabase/duckdb/ficticia-a.duckdb
.venv/bin/python -I armar_duckdb.py \
  --corte ~/cortes/ficticio/ficticia-b/<fecha> \
  --destino /srv/srtm-metabase/duckdb/ficticia-b.duckdb

.venv/bin/python -I provisionar.py --org ficticia-a:"Ficticia A" --org ficticia-b:"Ficticia B"

export METABASE_CLAVE_USUARIO=<clave temporal, solo para esta prueba>
.venv/bin/python -I provisionar.py usuario --org ficticia-a --email a@ficticio.pe --nombre A --apellido F
.venv/bin/python -I provisionar.py usuario --org ficticia-b --email b@ficticio.pe --nombre B --apellido F

make humo USUARIOS="ficticia-a:a@ficticio.pe ficticia-b:b@ficticio.pe"
```

`ficticio.py` lee de dos servidores falsos en el propio proceso (`sinteticos.datos(SEMILLA)`), nunca por red:
`make corte-ficticio` nunca lee ni escribe un dato real. La misma `SEMILLA` y la misma `FECHA` siempre
escriben los mismos bytes, así que correrlo de nuevo no cambia nada que ya se haya visto. `make humo` sale con
0 y `humo: OK (<n> comprobaciones)` cuando todo pasa, o imprime cada falla y sale con 1.

Al terminar, se retiran las organizaciones ficticias para que ningún usuario real las vea en Metabase
(`provisionar.py` no tiene subcomando para borrar; se hace en la interfaz, como administrador):

1. `Admin → People`: desactivar `a@ficticio.pe` y `b@ficticio.pe`.
2. `Admin → People → Groups`: borrar los grupos `Brecha ficticia-a` y `Brecha ficticia-b`.
3. En la colección `Brecha Ficticia A` (y en la B): `…` → `Move to trash`, y vaciarla desde la papelera
   (sus tarjetas y tableros se van con ella).
4. `Admin → Databases`: `Brecha Ficticia A` y `Brecha Ficticia B` → `Remove this database`.
5. En el VPS: `rm /srv/srtm-metabase/duckdb/ficticia-{a,b}.duckdb`.

Volver a correr la prueba más adelante es repetir esta sección desde el principio: `provisionar.py` vuelve a
crear todo por nombre.

## 7. Si el humo marca una base visible

Una falla de `make humo` que dice que una organización ve, además de la suya, la base de otra (`"<org> ve,
además de la suya, otra base: Brecha <nombre>"`) es aislamiento roto - nunca algo que se ignora ni se
reintenta. Antes de nada:

1. Confirmar que `make provisionar` corrió con el `tableros.json` de esta rama y sin errores (su salida lista,
   por organización, la base, el grupo, la colección y cuántas tarjetas y tableros); si no, correrlo de nuevo.
2. Revisar a mano, como administrador de Metabase, `Admin → Permissions → Data` y `… → Collections`: el grupo
   de la organización marcada tiene que dar, en las columnas de Metabase 0.63, `View data: Can view` y
   `Create queries: No` en toda base que no sea la suya (en la suya, `Create queries: Query builder only`), y
   `No access` en toda colección que no sea la suya; «All Users» tiene que dar `Create queries: No` y
   `No access` en todo lo de esta rama.
3. Si la API de permisos de Metabase cambió de forma entre versiones (sección 5), `provisionar.py` puede estar
   mandando una forma que esa versión ya no entiende sin que falle de inmediato (el PUT puede aceptar un campo
   que la versión nueva ignora). Repetir `make provisionar` tras corregirlo y volver a correr `make humo`.
4. Nunca dejar Metabase así mientras se investiga:
   `docker compose -p srtm-metabase -f metabase/compose.yml --env-file /srv/srtm-metabase/metabase.env stop metabase`
   lo apaga sin tocar `metabase-postgres` ni los `.duckdb`.

Que un usuario vea los **nombres y emails** de usuarios de otras organizaciones (por ejemplo al elegir
destinatarios de una suscripción) no es esta falla ni la marca `make humo`: es el límite conocido de Metabase
OSS descrito en la sección 0, pendiente de decisión.

## Límite conocido: consultas que necesitan disco

El `temp_directory` de cada conexión DuckDB queda dentro de `/datos`, el volumen que Metabase monta de solo
lectura (`METABASE_DUCKDB_DIR`); una consulta que necesite volcar a disco (una agregación o un `ORDER BY` que
no entra en memoria) falla en vez de usar disco temporal. Los cortes de esta brecha son chicos (del orden de
los 20 000 predios de Ica), así que no se ha visto hace falta; si una organización mucho más grande lo necesita
algún día, hace falta otro volumen de escritura solo para eso.
