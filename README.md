# srtm-backend

Backend básico de **rentas municipales** (Perené) sobre [wasichai](https://github.com/wasichai/wasichai): un servidor
Spring Boot armado solo con starters de wasichai, más un modelo de metadata (contribuyentes, predios, declaraciones
prediales, catastro fiscal) que se carga por REST, e importadores del padrón de predios (Excel) y del catastro
(GeoJSON). Sigue la forma de `gis-sample`: con **GIS** (wasichai-gis), sobre PostGIS.

| | |
|---|---|
| Módulos | core, workflow, documents, views, forms, pages, gis |
| Servidor | `src/`, puerto 8090 |
| Base de datos | PostgreSQL 18 con PostGIS 3.6 (`compose.yml`, imagen `postgis/postgis:18-3.6`, puerto 5433, base `srtm`) |
| Modelo e importador | `model/` (Python 3.11+, solo stdlib) |
| Login de desarrollo | `admin@wasichai.local` / `admin` (seed de desarrollo, `WASICHAI_SEED_DEV=true` por defecto) |

## Requisitos

- JDK 25 y Docker (para PostGIS y para los tests de integración con Testcontainers).
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

La guía completa de desarrollo local (variables, base, IDE, tests, front) está en
[docs/develop/README.md](docs/develop/README.md).

```bash
cp develop/example.env develop/.env  # variables de ejemplo; develop/.env no se versiona
set -a; source develop/.env; set +a
docker compose up -d                 # postgis 18-3.6 en localhost:5433, base srtm (usuario/clave srtm)
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
| `WASICHAI_GEOSERVER_ENABLED` / `_URL` | `false` / `http://localhost:8081/geoserver` | solo para publicar capas WMS |

### Pasar una base existente a PostGIS

Hasta la fase 3 la base era PostgreSQL 18 plano. `compose.yml` usa ahora `postgis/postgis:18-3.6`, que es el mismo
PostgreSQL 18, así que el volumen `postgres-data` sirve tal cual. La primera migración de wasichai-gis crea la
extensión `postgis` sola (el usuario `srtm` es superusuario).

```bash
docker compose exec postgres pg_dump -U srtm -Fc srtm > srtm-antes-de-postgis.dump   # respaldo, por si acaso
docker compose up -d                 # recrea el contenedor con la imagen de postgis, mismo volumen
./gradlew bootRun                    # la migración de wasichai-gis instala postgis
cd model && python3 apply.py         # crea catastro_fiscal, obra_categoria y los campos de geometría
```

## Cargar el modelo

Con el servidor corriendo:

```bash
cd model
python3 apply.py --validate-only   # valida model.json contra las reglas de Core, sin llamar a nada
python3 apply.py                   # done: 25 created, 0 updated, 0 skipped  (15 objetos + 10 relaciones)
python3 apply.py                   # idempotente: done: 0 created, 0 updated, 25 skipped
python3 apply.py --drop            # lo borra, en orden inverso (¡borra también los datos!)
```

Sobre una base que ya tiene el modelo, `apply.py` también **sincroniza**:
- **Campos:** añade a los objetos existentes los que `model.json` tiene y Core no.
- **Opciones ENUM:** añade las opciones que falten.
- **Obligatoriedad:** deja opcional el campo que `model.json` ya no exige (`contribuyente.numero_documento`, vacío con
  SIN DOCUMENTO); nunca vuelve obligatorio uno existente.

Solo añade o relaja: no renombra, no cambia tipos y no borra, así los registros importados siguen siendo válidos. Por ejemplo,
sobre la base del padrón:
- añade los campos nuevos de `contribuyente`, `predio` y `declaracion_predial`;
- amplía `tipo_documento` (`PASAPORTE`) y `condicion_propiedad` (`SOCIEDAD CONYUGAL`, `POSEEDOR`);
- crea los objetos y relaciones de las fases 1 y 2.

Flags: `--core` (default `http://localhost:8090` o `$WASICHAI_CORE`), `--email`, `--password`, `--dry-run`,
`--drop`, `--validate-only`. Salida: `0` ok, `1` error de Core, `2` `model.json` inválido.

## Cargar los catálogos

Los formularios del portal ofrecen ubigeo, vías y unidades urbanas desde tres objetos catálogo:

```bash
cd model
python3 import_catalogos.py                                                           # ubigeo y categorías de valores
python3 import_catalogos.py --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx" --dry-run  # cuenta, no llama a Core
python3 import_catalogos.py --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx"            # ubigeo + vías + unidades urbanas
```

- **`ubigeo`:** los 1 893 distritos del INEI, de `model/data/ubigeo.csv`. El archivo es un recorte de
  [ubigeo-peru-aumentado](https://github.com/jmcastagnetto/ubigeo-peru-aumentado): código INEI, departamento,
  provincia y distrito.
- **`categoria_valor`:** las 57 descripciones de las letras A–I de las siete columnas del *Cuadro de valores
  unitarios oficiales de edificación*, en `model/data/categorias_valor.csv`.
  - Están transcritas del cuadro vigente al 01/01/2026 (R.D. N° 00015-2025-VIVIENDA/VMVU-DGPRVU), publicado por el
    [Colegio de Arquitectos del Perú](https://cap.org.pe/wp-content/uploads/2026/01/CVU_ENERO_SIERRA_2026.pdf). Las
    descripciones son las mismas en costa, sierra y selva.
  - Los valores en soles, que sí cambian por región y mes, no se cargan: el cálculo del impuesto no es de esta fase.
- **`via` y `unidad_urbana`:** salen de `direccion_predio` del padrón, las mismas partes `<vía>` y `<habilitación>` que
  lee `import_predios.py`.
  - El tipo sale del primer término (CALLE, JIRÓN, CARROZABLE… / ASOCIACIÓN DE VIVIENDA, CENTRO POBLADO, CERCADO…).
  - En las unidades urbanas se descartan los restos de lote delante del tipo ("03-B CERCADO III MESETA").
  - Se asignan al distrito de `--distrito` (por defecto `120302`, Perené).
  - Con el Excel de 2026 salen 904 vías y 306 unidades urbanas.
- **`obra_categoria`:** las partidas del instructivo de obras complementarias e instalaciones fijas y permanentes
  (anexo III de la R.M. N.° 277-2025-VIVIENDA), en `model/data/obras_complementarias.csv`.
  - El archivo **viene solo con la cabecera** (`tipo_obra,numero,descripcion,unidad_medida,material`): gob.pe no deja
    que un script descargue el anexo. Hay que completarlo a mano desde www.gob.pe/vivienda.
  - Mientras está vacío, la categoría de una obra se escribe a mano en el portal.
- **Idempotente**, como los otros scripts.
- Los catálogos se pueden editar después desde el admin.

## Importar el catastro fiscal

Los lotes del catastro fiscal (código CPU y polígono) se cargan desde un GeoJSON en EPSG:4326. También se pueden
dibujar o corregir a mano en el portal.

```bash
cd model
ogr2ogr -f GeoJSON -t_srs EPSG:4326 lotes.geojson lotes.shp                     # si viene en Shapefile
python3 import_catastro.py --geojson lotes.geojson --map codigo_cpu=CPU --dry-run   # revisa, no llama a Core
python3 import_catastro.py --geojson lotes.geojson --map codigo_cpu=CPU --map codigo_predio_municipal=COD_MUN
```

- **Mapeo:** cada propiedad del GeoJSON cuyo nombre coincide con un campo lo llena. `--map campo=propiedad` renombra
  (se puede repetir). Campos: `codigo_cpu` (obligatorio), `codigo_predio_municipal`, `partida_registral`,
  `tipo_predio`, `ubigeo`, `tipo_via`, `via`, `numero`, `tipo_zona`, `zona`, `manzana`, `lote`, `kilometro`,
  `direccion`.
- **Geometría:** un MultiPolygon de una sola parte se toma como Polygon. wasichai-gis lo guarda en UTM 18S (EPSG:32718)
  y lo devuelve en EPSG:4326.
- **Idempotente** por `codigo_cpu`.
- **Salida:** `0` ok, `1` Core rechazó algo, `2` el archivo tiene lotes que no encajan en el modelo (no se envía nada).

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

Diecisiete objetos (`model/model.json`):
- **Padrón:** `contribuyente`, `predio` y `declaracion_predial`, cargados desde el Excel. Sus nombres de campo siguen el
  *Formato Padrón Municipal Armonización 2026*.
- **Registro de contribuyente del SRTM (fase 1):** `domicilio`, `relacionado`, `medio_contacto` y `sustento`, cada uno
  con una relación obligatoria a `contribuyente`.
- **Declaración jurada predial del SRTM (fase 2):** `transferente`, `nivel_construccion`, `obra_complementaria` y
  `otro_frente`, cada uno con una relación obligatoria a `declaracion_predial`.
- **Catastro fiscal (fase 3):** `catastro_fiscal`, un lote por código CPU, con su polígono.
- **Catálogos:** `ubigeo`, `via`, `unidad_urbana`, `categoria_valor` y `obra_categoria`.

Geometrías (wasichai-gis, GeoJSON en EPSG:4326 por la API):
- `predio.lote_geom` y `catastro_fiscal.lote_geom`: POLYGON, guardados en UTM 18S (EPSG:32718).
- `domicilio.ubicacion`: POINT, en EPSG:4326 ("Buscar dirección").

En la fase 1, `contribuyente` ganó los campos de la pantalla "Nuevo contribuyente" del SRTM:
- código y número de declaración autogenerados, fecha del registro;
- motivo, medios de determinación y de presentación, modificación de oficio, fecha de presentación;
- tipo de contribuyente, código anterior, fuente de información;
- fechas de nacimiento y de fallecimiento, estado civil, sexo y observación.

En la fase 2 ganaron campos:
- **`declaracion_predial`:**
  - datos del predio: número de DJ autogenerado, motivo, medios, fecha de presentación;
  - adquisición: tipo, fecha, documentos de sustento, folios;
  - condición especial y predio inhabitable;
  - clase y sub clase de uso, área común.
- **`predio`:** la ubicación del SRTM (ubigeo, región, tipo de vía, letras, UCV, edificación, interior, zona, sub zona,
  partida registral, código CPU…).

Ninguno es obligatorio en Core, porque los registros importados del padrón no los tienen. El portal los exige al
guardar.

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

`srtm.rentas` expone la API que usa el portal de `srtm-ui`. No hay BFF:
- **Capas:** `RentasController` llama a tres servicios (`ContribuyenteService`, `RentasService`, `CatalogoService`).
  Estos usan en proceso los servicios de wasichai (`RecordService`, `MetadataService`), a través de `Registros`, y
  devuelven DTOs tipados.
- **Claves JSON:** son los nombres de campo del modelo, en snake_case. `Records` convierte atributos ⇄ DTO con Jackson.
- **Protección:** como la API vive bajo `/api`, el filtro JWT de core ya la protege. `RecordService` aplica los permisos
  del usuario por objeto y por campo, y valida cada escritura.
- **Errores:** salen como problem+json, con `errors[].field` igual al nombre del campo.

| Método | Ruta | |
|---|---|---|
| GET | `/api/srtm/resumen` | totales del padrón |
| GET | `/api/srtm/catalogos` | opciones ENUM por objeto y campo |
| GET | `/api/srtm/ubigeos` | la lista INEI completa; la cascada departamento → provincia → distrito se hace en el portal |
| GET | `/api/srtm/vias?q&tipo&ubigeo`, `/api/srtm/unidades-urbanas?q&tipo&ubigeo` | sugerencias del catálogo |
| GET, POST | `/api/srtm/contribuyentes?q&page&size` | búsqueda (texto en todos los campos) e inscripción |
| GET, PUT | `/api/srtm/contribuyentes/{id}?anio` | la ficha: datos, nº de predios y totales del año; y la edición |
| GET | `/api/srtm/contribuyentes/{id}/declaraciones?anio` | sus declaraciones, cada una con su predio |
| GET, POST | `/api/srtm/contribuyentes/{id}/{lista}` | las listas del contribuyente: `domicilios`, `relacionados`, `medios-contacto`, `sustentos` |
| PUT, DELETE | `/api/srtm/{lista}/{id}` | edición y baja de una fila de esas listas |
| GET, POST | `/api/srtm/predios?q&page&size` | búsqueda y alta |
| GET, PUT | `/api/srtm/predios/{id}?anio` | la ficha: datos, nº de titulares y totales del año; y la edición |
| GET | `/api/srtm/predios/{id}/declaraciones?anio` | sus declaraciones, cada una con su contribuyente |
| POST | `/api/srtm/declaraciones` | alta corta, desde la ficha del predio (`contribuyente` y `predio` son ids) |
| POST | `/api/srtm/contribuyentes/{id}/declaraciones-juradas` | presenta una DJ: `{declaracion, predio_id}` sobre un predio del padrón, o `{declaracion, predio}` registrando uno |
| GET, PUT, DELETE | `/api/srtm/declaraciones/{id}` | la DJ con su predio y su contribuyente; la edición; y la baja, con sus listas |
| POST | `/api/srtm/declaraciones/{id}/condominos` | "Datos de los condóminos": `{contribuyente, porcentaje_condominio}` agrega otro titular del mismo predio, año y secuencia |
| GET, POST | `/api/srtm/declaraciones/{id}/{lista}` | las listas de la DJ: `transferentes`, `niveles`, `obras`, `frentes` |
| PUT, DELETE | `/api/srtm/{lista}/{id}` | edición y baja de una fila de esas listas |
| GET | `/api/srtm/categorias-valor` | las letras de las siete columnas del cuadro de valores, con su descripción |
| GET | `/api/srtm/obras-categorias?tipo_obra` | las partidas del instructivo de obras complementarias |
| GET | `/api/srtm/predios/buscar?…` | "Buscar en Tributario" (pág. 13) |
| GET, POST | `/api/srtm/catastro?…` | "Buscar en Catastro Fiscal" (pág. 13), y el alta de un lote |
| GET, PUT | `/api/srtm/catastro/{id}` | un lote del catastro, y su edición (polígono incluido) |
| GET | `/api/gis/objects/{catastro_fiscal\|predio}/features?bbox&geometry=lote_geom` | de wasichai-gis: los lotes del área visible, para el mapa |

Reglas del registro de contribuyente (en `Reglas.kt`, con sus tests):
- **Inscripción:** el backend asigna `codigo` (6 dígitos, correlativo), `numero_declaracion` y `fecha_registro`.
  - Por defecto pone INSCRIPCIÓN, DECLARACIÓN JURADA, FÍSICO, fecha de presentación de hoy y fuente MANUAL.
  - Un documento ya inscrito es un 400 sobre `numero_documento`: Core dejaría que la base de datos lo rechazara con un
    500.
- **Derivados:** `tipo_persona` sale de `tipo_contribuyente`. `nombre_completo` son apellidos y nombres (persona natural)
  o la razón social.
- **Domicilios:** el backend arma `descripcion` en el orden del SRTM (el portal muestra la misma vista previa).
  - El último domicilio FISCAL activo se copia a `domicilio_fiscal` / `_distrito` / `_provincia` / `_departamento` del
    contribuyente, que es lo que usan las listas.
- **Declaración jurada** (en `DeclaracionService`):
  - Al presentarla, el backend le asigna `numero_declaracion` (correlativo, único).
  - Por defecto: año de la fecha de presentación, secuencia 1, INSCRIPCIÓN, DECLARACIÓN JURADA, FÍSICO.
  - Un predio nuevo sin código lo recibe de su sector y manzana (`SS-MM-NNNN`, el siguiente de esa manzana, como el
    padrón). Si la declaración se rechaza, ese predio se borra.
  - Con `tipo_via`, la dirección del predio se arma de su ubicación. Los importados conservan el texto del padrón hasta
    que alguien completa su ubicación.
  - El total metrado de una obra complementaria es cantidad × metrado.
- **Condominio** (`Condominio.kt`, con sus tests): las declaraciones de un mismo predio, año y secuencia de uso. Tras
  cada alta, edición o baja de una de ellas el backend recalcula todo el grupo:
  - Un solo titular: PROPIETARIO ÚNICO (o la SOCIEDAD CONYUGAL / POSEEDOR que haya declarado) al 100 %. Dos o más:
    CONDÓMINO, cada uno con el % que declara.
  - `valor_condominio` = `valor_autoavaluo` × % / 100 (al céntimo, redondeo hacia arriba desde 0,5) y `valor_afecto` =
    `valor_condominio` − `deduccion`, nunca menos de 0.
  - Quien se suma a un predio de un titular único toma su % del 100 % de ese titular (0 < % < 100). Con dos o más, los %
    no pueden sumar más de 100: si no, 400 sobre `porcentaje_condominio`. Un contribuyente declara un predio, año y
    secuencia una sola vez (400 sobre `contribuyente`).
  - Un condómino agregado desde una DJ copia lo que esta declara del predio (año, secuencia, características,
    autoavalúo, inhabitabilidad, niveles, obras y otros frentes), no lo del titular (adquisición, documentos, condición
    especial, deducción, transferentes).
  - Los transferentes son dueños anteriores: no cambian ningún %.
- **Búsqueda de predios** (`BusquedaPredios.kt`): los filtros de la pág. 13 son tipo de predio, código, código CPU,
  partida registral, tipo de vía, vía, tipo de zona, zona, número, manzana, lote y kilómetro.
  - Un texto se busca contenido, sin distinguir mayúsculas; un ENUM, exacto. Los valores van ligados, nunca en el SQL.
  - Página de 5 por defecto, como el SRTM.
- **Predio registrado en el portal:** recibe `numero_registro` (correlativo, único). Código y número de registro se
  conservan en cada edición.
- **Geometrías:** viajan como un campo más del DTO (`lote_geom`, `ubicacion`); `Registros` las separa hacia la
  sección `geometries` de Core. Un DTO siempre lleva su geometría, `null` si el formulario no tenía mapa: por eso
  `null` **conserva** la geometría guardada. El portal la reemplaza, nunca la borra.
- **Lo que no se edita:** en una edición, `codigo`, `numero_declaracion`, `fecha_registro` y el domicilio fiscal se
  conservan aunque el cuerpo diga otra cosa. Una fila de una lista nunca cambia de contribuyente.
- **Escrituras:** el `update` de Core reemplaza **todos** los campos editables. Por eso el portal fusiona lo que envía
  con el registro guardado: un campo añadido desde el admin, que el DTO no conoce, no se borra al guardar desde el
  portal. Un campo enviado como `null`, en cambio, sí se borra.
- **Sin anio:** la ficha usa el año en curso y las declaraciones traen todos los años.
- **Declaraciones embebidas:** se leen en una sola consulta por ids, sin N+1.

## Tests

```bash
./gradlew build             # ktlint + tests unitarios (mapeo y reglas del portal)
./gradlew integrationTest   # smoke test y API del portal contra Testcontainers postgis/postgis:18-3.6 (o WASICHAI_TEST_DB_*)
cd model && python3 -m unittest -v
```

Con un Docker remoto, Testcontainers no llega a los puertos publicados. En ese caso se usa una base de test externa
tunelizada: `WASICHAI_TEST_DB_HOST`, `_PORT`, `_NAME` (debe terminar en `_test`, porque la suite la limpia),
`_USERNAME` y `_PASSWORD`, y se corre `./gradlew integrationTest --rerun`. Esa base debe tener PostGIS. Detalles en
`wasichai/docs/development/getting-started.md#integration-tests`.

## Siguientes pasos (fuera de este alcance)

- **Datos:** completar `model/data/obras_complementarias.csv` con el anexo oficial, y cargar el GeoJSON del catastro
  fiscal cuando esté disponible.
- **Integraciones:** PIDE RENIEC. El fondo del mapa es OpenStreetMap; una capa WMS/WMTS municipal se puede publicar
  con GeoServer.
- **Cálculo y cobranza:** impuesto predial (tramos UIT), arbitrios, deuda y cuotas, pagos y recibos.
- **En el modelo:** workflows y plantillas de documentos.

El frontend web está en `srtm-ui`.
