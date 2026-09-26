# srtm-backend

Backend básico de **rentas municipales** (Perené) sobre [wasichai](https://github.com/wasichai/wasichai): un servidor
Spring Boot armado solo con starters de wasichai, más un modelo de metadata (contribuyentes, predios, declaraciones
prediales) que se carga por REST, y un importador del padrón de predios en Excel. Sigue la forma de
`wasichai/examples/gis-sample`, pero **sin GIS**: corre sobre PostgreSQL plano.

| | |
|---|---|
| Módulos | core, workflow, documents, views, forms, pages |
| Servidor | `src/`, puerto 8090 |
| Base de datos | PostgreSQL 18 (`compose.yml`, puerto 5433, base `srtm`) |
| Modelo e importador | `model/` (Python 3.11+, solo stdlib) |
| Login de desarrollo | `admin@wasichai.local` / `admin` (seed de desarrollo, `WASICHAI_SEED_DEV=true` por defecto) |

## Requisitos

- JDK 25 y Docker (para PostgreSQL y para los tests de integración con Testcontainers).
- Python 3.11+ para `model/`.
- Las librerías de wasichai (`wasichai:wasichai-bom:0.1.0` y los starters). Se resuelven desde:
  1. **GitHub Packages** (`https://maven.pkg.github.com/wasichai/wasichai`). Pide un token aunque sea para leer
     (`read:packages` basta). En `~/.gradle/gradle.properties`:
     ```properties
     gpr.user=<usuario de github>
     gpr.key=<PAT con read:packages>
     ```
     O, si no, las variables `GITHUB_ACTOR` / `GITHUB_TOKEN`.
  2. **mavenLocal**, como respaldo mientras no haya release publicada. En un checkout de wasichai:
     `./gradlew publishToMavenLocal`.

## Arrancar

```bash
docker compose up -d                 # postgres:18 en localhost:5433, base srtm (usuario/clave srtm)
./gradlew bootRun                    # servidor en http://localhost:8090
```

`compose.yml` publica el puerto solo en `127.0.0.1`. Con un Docker remoto (`DOCKER_HOST` apuntando a otro servidor),
ese puerto queda en el loopback del servidor: llega por un túnel, `ssh -N -L 5433:localhost:5433 <servidor>`.

| Variable | Default | |
|---|---|---|
| `WASICHAI_DB_HOST` / `WASICHAI_DB_PORT` / `WASICHAI_DB_NAME` | `localhost` / `5433` / `srtm` | |
| `WASICHAI_DB_USERNAME` / `WASICHAI_DB_PASSWORD` | `srtm` / `srtm` | |
| `WASICHAI_SEED_DEV` | `true` | crea el admin de desarrollo; apagarlo fuera de desarrollo |
| `WASICHAI_JWT_SECRET` | un valor solo para desarrollo | poner uno propio (>= 32 bytes) en cualquier entorno real |
| `SRTM_PG_PORT` | `5433` | puerto publicado por `compose.yml` |

## Cargar el modelo

Con el servidor corriendo:

```bash
cd model
python3 apply.py --validate-only   # valida model.json contra las reglas de Core, sin llamar a nada
python3 apply.py                   # done: 5 created, 0 skipped  (3 objetos + 2 relaciones)
python3 apply.py                   # idempotente: done: 0 created, 5 skipped
python3 apply.py --drop            # lo borra, en orden inverso (¡borra también los datos!)
```

Flags: `--core` (default `http://localhost:8090` o `$WASICHAI_CORE`), `--email`, `--password`, `--dry-run`,
`--drop`, `--validate-only`. Salida: `0` ok, `1` error de Core, `2` `model.json` inválido.

## Importar el padrón de predios

```bash
cd model
python3 import_predios.py --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx" --dry-run   # lee y transforma, no llama a Core
python3 import_predios.py --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx" --limit 200 # prueba parcial
python3 import_predios.py --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx"             # carga completa
```

Flags: `--anio` (año de las declaraciones, default 2026), `--limit N` (primeras N filas más los condóminos del último
predio), `--workers` (POSTs en paralelo, default 4), `--report`, `--sheet`, `--core`, `--email`, `--password`. Es
idempotente: vuelve a leer lo que ya existe en Core (contribuyentes por `numero_documento`, predios por `codigo`,
declaraciones por predio + contribuyente + año + secuencia) y crea solo lo que falta. Salida: `0` ok, `1` Core rechazó
algo (se muestra la fila del Excel), `2` el Excel tiene valores que no encajan en el modelo (no se envía nada).

Con el Excel de 2026 el resultado esperado es 11 840 contribuyentes, 14 947 predios y 15 644 declaraciones.

**El Excel y los reportes no se versionan**: traen DNI, nombres y domicilios. `*.xlsx` y `model/reports/` están en
`.gitignore`.

`model/reports/nombres_dudosos.csv` lista los nombres cuya separación en apellidos y nombres conviene revisar a mano
(con coma, con ` Y `, `SN`, menos de 3 palabras o sin nombres). `nombre_completo` siempre guarda el texto original.

## Modelo

Tres objetos (`model/model.json`). Los nombres de campo siguen el *Formato Padrón Municipal Armonización 2026*.

**`contribuyente`**: una persona natural, persona jurídica o sucesión. `numero_documento` es único.

| Campo | Origen en el Excel |
|---|---|
| `tipo_persona` (NATURAL, JURIDICA, SUCESION) | derivado: RUC ⇒ JURIDICA; código 08 o `SUCESION…`/`SUC.` ⇒ SUCESION; nombre institucional (ASOCIACION, IGLESIA, INSTITUCION, CENTRO…) ⇒ JURIDICA; si no, NATURAL |
| `tipo_documento` | `tipo_doc`: 00 SIN DOCUMENTO, 01 DNI, 04 CARNET DE EXTRANJERIA, 06 RUC, 08 SUCESION |
| `numero_documento` | `num_doc` |
| `nombre_completo` | `nombre_contribuyente`, con los espacios normalizados |
| `apellido_paterno`, `apellido_materno`, `nombres` | `nombre_contribuyente` separado (solo NATURAL) |
| `razon_social` | `nombre_contribuyente` (JURIDICA y SUCESION) |
| `domicilio_fiscal`, `domicilio_distrito`, `domicilio_provincia`, `domicilio_departamento` | `domicilio_fiscal`, separando el sufijo `, Dist. X Prov. Y Dpto. Z` |

**`predio`**: identidad y ubicación. `codigo` es único. Se toma de la primera fila de cada código.

| Campo | Origen en el Excel |
|---|---|
| `codigo` | `codigo_predio` (`01-01-0001`) |
| `sector_catastral`, `manzana_catastral` | `sector_manzana` (`01 - 01`) |
| `condicion` (URBANO, RUSTICO) | `tipo_pupr_desc` (PU, PR) |
| `direccion` | `direccion_predio` completa |
| `via`, `numero`, `manzana`, `lote`, `habilitacion_urbana` | `direccion_predio` parseada (`<vía> Nro.: Mz.: Lt.: … <habilitación>`) |
| `ubicacion_area_verde` | `ubicacion_parque` |

**`declaracion_predial`**: una por fila del Excel, con relaciones obligatorias a `contribuyente` y a `predio`.

| Campo | Origen en el Excel |
|---|---|
| `anio` | `--anio` del importador |
| `secuencia_uso` | `secuencia_uso` |
| `condicion_propiedad` (PROPIETARIO UNICO, CONDOMINO) | derivado: CONDOMINO si el predio tiene más de un titular |
| `porcentaje_condominio` | derivado: `valor_condominio / valor_autoavaluo × 100` |
| `uso` | `grupo_uso_desc` |
| `clasificacion` | `clasificacion_predio_desc`, acortado a 64 caracteres sin comas (límite de las opciones ENUM de Core) |
| `estado_construccion` | `estado_construccion_desc` |
| `area_terreno`, `area_construida`, `longitud_frente` | columnas con el mismo nombre |
| `numero_habitantes` | `cantidad_habitantes` |
| `valor_autoavaluo`, `valor_condominio`, `deduccion`, `valor_afecto` | `autoavaluo_total`, `condominio`, `deduccion`, `autoavaluo_afecto` |

Se descarta `orden2`, que es solo el número de fila.

### Cómo se lee el Excel

- Cada fila es una declaración: un contribuyente sobre un predio (`codigo_predio` + `secuencia_uso`).
- Una fila **sin** `codigo_predio` es un condómino del predio de la fila de arriba: hereda código y secuencia.
- Las áreas, el uso y el autoavalúo van en la declaración y no en el predio, porque algunos condóminos declaran
  valores propios.
- Los decimales se redondean a 2 (el Excel trae ruido de float, `613.82000000000005`). Un valor vacío se envía como
  nulo.

## API del portal

`srtm.rentas` expone la API que usa el portal de `srtm-ui`. No hay BFF: `RentasController` llama en proceso a los
servicios de wasichai (`RecordService`, `MetadataService`) y devuelve DTOs tipados. Las claves JSON son los nombres de
campo del modelo, en snake_case. Como vive bajo `/api`, el filtro JWT de core ya la protege. `RecordService` aplica los
permisos del usuario por objeto y por campo, y valida cada escritura. Los errores salen como problem+json, con
`errors[].field` igual al nombre del campo.

| Método | Ruta | |
|---|---|---|
| GET | `/api/srtm/resumen` | totales del padrón |
| GET | `/api/srtm/catalogos` | opciones ENUM por objeto y campo |
| GET, POST | `/api/srtm/contribuyentes?q&page&size` | búsqueda (texto en todos los campos) y alta |
| GET, PUT | `/api/srtm/contribuyentes/{id}?anio` | la ficha: datos, nº de predios y totales del año; y la edición |
| GET | `/api/srtm/contribuyentes/{id}/declaraciones?anio` | sus declaraciones, cada una con su predio |
| GET, POST | `/api/srtm/predios?q&page&size` | búsqueda y alta |
| GET, PUT | `/api/srtm/predios/{id}?anio` | la ficha: datos, nº de titulares y totales del año; y la edición |
| GET | `/api/srtm/predios/{id}/declaraciones?anio` | sus declaraciones, cada una con su contribuyente |
| POST | `/api/srtm/declaraciones` | alta (`contribuyente` y `predio` son ids) |
| PUT | `/api/srtm/declaraciones/{id}` | edición |

- Sin `anio`, la ficha usa el año en curso y las declaraciones traen todos los años.
- Un PUT reemplaza el registro: un campo enviado como `null` se borra.
- Las declaraciones embebidas se leen en una sola consulta por ids, sin N+1.

## Tests

```bash
./gradlew build             # ktlint + tests unitarios (mapeo del portal)
./gradlew integrationTest   # smoke test y API del portal contra Testcontainers postgres:18 (o WASICHAI_TEST_DB_*)
cd model && python3 -m unittest -v
```

Con un Docker remoto, Testcontainers no llega a los puertos publicados. En ese caso se usa una base de test externa
tunelizada: `WASICHAI_TEST_DB_HOST`, `_PORT`, `_NAME` (debe terminar en `_test`, porque la suite la limpia),
`_USERNAME` y `_PASSWORD`, y se corre `./gradlew integrationTest --rerun`. Detalles en
`wasichai/docs/development/getting-started.md#integration-tests`.

## Siguientes pasos (fuera de este alcance)

Cálculo del impuesto predial (tramos UIT), arbitrios, deuda y cuotas, pagos y recibos, workflows y plantillas de
documentos en el modelo. El frontend web está en `srtm-ui`.
