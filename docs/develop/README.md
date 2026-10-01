# Desarrollo local

Cómo preparar, iniciar y probar srtm-backend en una máquina de desarrollo. La descripción del proyecto, el modelo y la
API están en el [README principal](../../README.md).

## 1. Requisitos

| Herramienta | Versión | Para |
|---|---|---|
| JDK | 25 | el servidor (toolchain de Gradle) |
| Docker | cualquiera reciente | PostgreSQL (`compose.yml`) y los tests de integración |
| Python | 3.11+ | `model/` (solo stdlib, sin `pip install`) |
| Node | >= 26 | solo si también corres el front, `../srtm-ui` |

Las librerías de wasichai (`wasichai:wasichai-bom:0.2.0` y sus starters) se resuelven así, en este orden:

1. **GitHub Packages**. Pide un token aunque sea para leer. En `~/.gradle/gradle.properties`:
   ```properties
   gpr.user=<usuario de github>
   gpr.key=<PAT con read:packages>
   ```
   También sirven `GITHUB_ACTOR` / `GITHUB_TOKEN` en `develop/.env`.
2. **mavenLocal**, como respaldo mientras no haya una release publicada. En un checkout de
   [wasichai](https://github.com/wasichai/wasichai):
   ```bash
   cd ../wasichai && ./gradlew publishToMavenLocal -x test
   ```
   Hay que repetirlo cada vez que cambie wasichai.

## 2. Variables de entorno

```bash
cp develop/example.env develop/.env      # develop/.env está en .gitignore
```

Edita `develop/.env` si hace falta y cárgalo en el shell donde vayas a trabajar:

```bash
set -a; source develop/.env; set +a
```

| Variable | Ejemplo | Uso |
|---|---|---|
| `WASICHAI_DB_HOST` / `_PORT` / `_NAME` | `localhost` / `5433` / `srtm` | a qué PostgreSQL se conecta el servidor |
| `WASICHAI_DB_USERNAME` / `_PASSWORD` | `srtm` / `srtm` | credenciales de esa base |
| `SRTM_PG_PORT` | `5433` | puerto que publica `compose.yml` en `127.0.0.1` |
| `WASICHAI_JWT_SECRET` | valor de desarrollo | firma de los tokens. Mínimo 32 bytes; en cualquier entorno real, uno propio |
| `WASICHAI_SEED_DEV` | `true` | crea el usuario `admin@wasichai.local` / `admin`. Apagarlo fuera de desarrollo |
| `WASICHAI_CORE` | `http://localhost:8090` | URL que usan `model/apply.py` e `import_predios.py` |
| `WASICHAI_EMAIL` / `WASICHAI_PASSWORD` | el admin de desarrollo | login de esos scripts |
| `WASICHAI_TEST_DB_*` | comentadas | solo para tests de integración contra una base externa (ver 6) |
| `SRTM_PIDE_RENIEC_*` | comentadas | consulta de DNI a RENIEC por la PIDE; apagada hasta tener las credenciales del convenio (README, "PIDE RENIEC") |

Todas tienen el mismo valor por defecto en `src/main/resources/application.yml`, así que con la base de `compose.yml`
el servidor arranca aunque no cargues nada. `develop/.env` sirve para cambiarlas sin tocar el yaml.

## 3. Base de datos

```bash
docker compose up -d          # postgis/postgis:18-3.6, contenedor srtm-postgres, 127.0.0.1:5433, base srtm
docker compose ps             # debe salir healthy
```

- Es PostgreSQL 18 con PostGIS 3.6: srtm instala el módulo gis (lotes del catastro, del predio y el punto del
  domicilio). Para pasar una base existente, ver "Pasar una base existente a PostGIS" en el README.
- Al arrancar, el servidor crea el esquema con las migraciones Flyway de wasichai. No hay migraciones propias.
- Para empezar de cero: `docker compose down -v`, que borra el volumen con todos los datos.

**Docker remoto.** Si `DOCKER_HOST` apunta a otro servidor, el contenedor corre allá y el puerto queda en el loopback
de ese servidor. Hace falta un túnel y dejarlo abierto:

```bash
ssh -N -L 5433:localhost:5433 <usuario>@<servidor>
```

## 4. Iniciar el backend

```bash
set -a; source develop/.env; set +a
./gradlew bootRun
curl http://localhost:8090/actuator/health       # {"status":"UP",...}
```

- El servidor escucha en `http://localhost:8090`. Se detiene con `Ctrl+C`.
- Para arrancarlo desde el IDE: la clase principal es `srtm.SrtmApplication`. Las variables de `develop/.env` van en
  la configuración de ejecución; en IntelliJ, con un plugin de EnvFile o pegándolas en *Environment variables*.
- Otra opción es el jar: `./gradlew bootJar && java -jar build/libs/app.jar`.

## 5. Modelo y datos

Con el servidor corriendo y `develop/.env` cargado:

```bash
cd model
python3 apply.py                                   # 21 objetos + 10 relaciones; idempotente
python3 import_parametros.py                       # UIT, tramos y mínimo del predial; idempotente
python3 import_catalogos.py                        # catálogos y la municipalidad (cabecera del PU y la HR); idempotente
python3 import_predios.py --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx" --dry-run
python3 import_predios.py --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx" --limit 200
python3 import_predios.py --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx"
```

- **Cabecera del PU y la HR:** los datos de `model/data/municipalidad.json` son provisionales. Los reales se editan
  en `/admin` (objeto *Municipalidad*); el escudo se configura con `SRTM_MUNICIPALIDAD_ESCUDO`.
- **Datos personales:** el Excel y `model/reports/` traen DNI, nombres y domicilios. No se copian al repo: `*.xlsx`
  y `model/reports/` están en `.gitignore`.
- **Tiempo de carga:** la importación completa son unos 42 000 registros. Con la base local tarda unos minutos. Por
  un túnel SSH es mucho más lenta (unos 12 registros por segundo); ahí conviene subir `--workers`.
- **Idempotencia:** se puede volver a correr, porque solo crea lo que falta.

## 6. Tests

```bash
./gradlew build --no-daemon                  # ktlint + tests unitarios (excluye los de integración)
./gradlew ktlintFormat                       # formatea el Kotlin según .editorconfig
./gradlew compileTestKotlin                  # compila también los tests de integración, sin correrlos
./gradlew integrationTest                    # tests de integración: Testcontainers, necesita un Docker local
./gradlew integrationTest --tests 'srtm.rentas.ListasApiTest'   # una sola clase
cd model && python3 apply.py --validate-only && python3 -m unittest -v
yarn format:check                            # prettier: yaml y json, model.json incluido (yarn format lo arregla)
```

**Qué son los tests de integración.** Las clases `*ApiTest` de `src/test/kotlin/srtm/rentas` y `SrtmSmokeTest`, con
`@Tag("integration")`: `build` las excluye y `integrationTest` las corre. Cada una levanta la app entera
(`SrtmApplication`, en un puerto aleatorio) contra un PostgreSQL con PostGIS (`postgis/postgis:18-3.6`, la propiedad
`wasichai.test.db.image` de `build.gradle.kts`) y la llama por HTTP, como el portal.

- **`SrtmApiTest`** es la base de las de la API. Antes de cada test entra como el admin de desarrollo y aplica
  `model/model.json` como `apply.py`: crea los objetos, campos (geometrías incluidas) y relaciones que falten, añade las
  opciones ENUM que falten y relaja lo que el modelo ya no exige. Da además las llamadas (`send`, `get`, `post`, `put`,
  `delete`, `rejected` para un 400 sobre un campo) y registros de prueba (`inscribir()`, `predio()`,
  `nuevaDeclaracion()`).
- **La base se comparte** entre todas las clases de una corrida: cada test crea sus propios registros, con documentos y
  códigos únicos (`uniqueDocumento()`), y no supone que la base está vacía.
- **Una clase nueva** hereda de `SrtmApiTest`. Si necesita un doble de un bean (RENIEC, por ejemplo), lo declara en una
  `@TestConfiguration` anidada, como `ConsultaReniecApiTest`.

**En el CI.** El job *Integration tests* de `.github/workflows/ci.yml` los corre en cada PR y en `main`, en un runner
de GitHub que tiene Docker local. Si fallan, el job sube `build/reports/tests/` (artefacto
`integration-test-reports`).

**Por qué no corren en local con un Docker remoto.** Testcontainers crea el contenedor en el daemon al que apunta
`DOCKER_HOST`, pero lo busca en `localhost:<puerto publicado>`. Con un Docker remoto (otro servidor, o su socket
reenviado por SSH, como `unix:///tmp/docker.sock`), el contenedor y sus puertos quedan en ese servidor: ni Ryuk ni
PostgreSQL responden en `localhost` y la suite falla al arrancar, aunque `docker info` funcione. Entonces:

1. Lo habitual: compilarlos (`./gradlew compileTestKotlin`) y dejar que los corra el CI del PR.
2. O usar una base externa ya levantada en ese servidor y tunelizada. Descomenta `WASICHAI_TEST_DB_*` en
   `develop/.env`. Esa base debe tener PostGIS, y su nombre **debe terminar en `_test`**, porque la suite la limpia
   entera al empezar. Luego:

   ```bash
   set -a; source develop/.env; set +a
   ./gradlew integrationTest --rerun
   ```

   `--rerun` evita que la caché de Gradle devuelva un resultado verde viejo. Dos suites contra la misma base no se
   pisan: la segunda espera a que termine la primera. Detalles en
   `wasichai/docs/development/getting-started.md#integration-tests`.

## 7. Con el front (`../srtm-ui`)

El front no llama al backend directamente: su servidor de Vite hace proxy de `/api` a `http://localhost:8090` (se
cambia con `WASICHAI_API_URL`), así que no hace falta configurar CORS.

```bash
# terminal 1
set -a; source develop/.env; set +a
./gradlew bootRun

# terminal 2
cd ../srtm-ui && yarn dev          # http://localhost:5180
```

Entra con `admin@wasichai.local` / `admin`. El front necesita que el modelo esté cargado (`model/apply.py`).

## Problemas comunes

| Síntoma | Causa probable |
|---|---|
| `Could not find wasichai:wasichai-spring-boot-starter…` | sin token de GitHub Packages y sin `publishToMavenLocal` (ver 1) |
| `Connection refused` a `localhost:5433` | `docker compose up -d` no corrió, o falta el túnel con Docker remoto |
| El servidor no arranca: falta `wasichai.security.jwt.secret` | `WASICHAI_JWT_SECRET` vacío en `develop/.env` |
| `apply.py`: `connection failed` | el servidor no está en `WASICHAI_CORE` |
| Puerto 8090 ocupado | otro backend corriendo: `lsof -iTCP:8090 -sTCP:LISTEN` |
| `integrationTest` no arranca (Ryuk o PostgreSQL no responden) | un Docker remoto: Testcontainers no llega a sus puertos (ver 6) |
