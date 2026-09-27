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
| `SRTM_PIDE_RENIEC_ENABLED` | `false` | consulta de DNI a RENIEC por la PIDE (ver [PIDE RENIEC](#pide-reniec)) |
| `SRTM_PIDE_RENIEC_DNI_USUARIO` / `_RUC_USUARIO` / `_PASSWORD` | vacías | credenciales del convenio con la PIDE; nunca en el repositorio |
| `SRTM_PIDE_RENIEC_URL` / `_TIMEOUT` | `https://ws2.pide.gob.pe/Rest/RENIEC/Consultar` / `5s` | servicio REST "Consultar" y cuánto se espera |

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
- **Etiquetas:** pone la etiqueta de `model.json` al campo que Core etiqueta distinto (`predio.condicion`: "Tipo de
  predio"). Es solo lo que muestra el admin.

Solo añade, relaja o reetiqueta: no renombra, no cambia tipos y no borra, así los registros importados siguen siendo válidos. Por ejemplo,
sobre la base del padrón:
- añade los campos nuevos de `contribuyente`, `predio` y `declaracion_predial`;
- amplía `tipo_documento` (`PASAPORTE`, y `PTP-CPP`, `CI` y `OTROS` de los manuales del SRTM) y `condicion_propiedad`
  (`SOCIEDAD CONYUGAL`, `POSEEDOR`);
- crea los objetos y relaciones de las fases 1 y 2.

Flags: `--core` (default `http://localhost:8090` o `$WASICHAI_CORE`), `--email`, `--password`, `--dry-run`,
`--drop`, `--validate-only`. Salida: `0` ok, `1` error de Core, `2` `model.json` inválido.

## Cargar los catálogos

Los formularios del portal ofrecen ubigeo, usos del predio, vías y unidades urbanas desde objetos catálogo:

```bash
cd model
python3 import_catalogos.py                                                           # ubigeo, categorías de valores y usos
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
  - Se leen también las abreviaturas con que el portal escribe las direcciones (JR., AV., CA., PSJE., PROL., CARR.;
    AA.HH., AA.VV., C.P., URB.) y las erratas de CARROZABLE del padrón.
  - Con el Excel de 2026 salen 903 vías y 306 unidades urbanas.
- **`obra_categoria`:** las partidas del instructivo de obras complementarias e instalaciones fijas y permanentes
  (anexo III de la R.M. N.° 277-2025-VIVIENDA), en `model/data/obras_complementarias.csv`.
  - El archivo **viene solo con la cabecera** (`tipo_obra,numero,descripcion,unidad_medida,material`): gob.pe no deja
    que un script descargue el anexo. Hay que completarlo a mano desde www.gob.pe/vivienda.
  - Mientras está vacío, la categoría de una obra se escribe a mano en el portal.
- **`uso_predio`:** el *tipo de uso de predio* del SRTM (clase → sub clase → uso), en `model/data/usos_predio.csv`.
  Las características de la DJ lo ofrecen en cascada (`GET /api/srtm/usos-predio`).
  - El archivo tiene la forma de la tabla de parámetros del SRTM (*Parámetros / Uso Predio*, exportable a Excel):
    `codigo,descripcion,fuente`. El código de seis dígitos da el nivel: `XX0000` clase, `XXYY00` sub clase y `XXYYZZ`
    uso de esa sub clase. Se cargan los usos (112), cada uno con los nombres de su clase y su sub clase; la clave es el
    código.
  - **No es la lista oficial completa** (el SRTM tiene 324 filas), que no está en los documentos. `fuente` dice de
    dónde sale cada fila:
    - `SRTM`: código y nombre como en la *Presentación2* (págs. 17 y 20: RESIDENCIAL → UNIFAMILIAR → CASA
      HABITACIÓN) o en el manual *M21-1-003 Parámetros* (§5.2.20-5.2.21 y §5.5.2: las filas 0701xx-1004xx y los usos
      residenciales CASA HABITACIÓN, EDIFICIO, QUINTA, CALLEJÓN, CORRALÓN, SOLAR, EDIFICIO EN QUINTA, AIRES, TENDAL EN
      QUINTA y TENDAL EN EDIFICIO).
    - `ARMONIZACION`: código y nombre del *Formato Padrón Municipal Armonización 2026* (el ejemplo lleno de otra
      municipalidad, columnas *Código uso* y *Descripción del uso*). Se dejaron fuera sus códigos que contradicen al
      manual (010103-010105, 0504xx, 0909xx, 153045, 999999). Los nombres van sin comas, barras ni paréntesis (las
      opciones ENUM de Core no los admiten), con las abreviaturas desarrolladas y con tildes.
    - `INFERIDO`: el nombre o el código se dedujo. Las clases EQUIPAMIENTO URBANO (05), DESOCUPADO (08) y
      ESTACIONAMIENTO (09) salen de los diez grupos de uso del padrón de Perené, que calzan con las diez clases; la
      armonización llama GARAGE a la 09. Las sub clases MULTIFAMILIAR, OFICINAS, SERVICIOS (0205), INDUSTRIA
      MANUFACTURERA y CULTURAL salen de sus usos. Los nombres cortados en el manual (EN CONST…, CONSTRU…, CON CONS…,
      COMERCI…, PROFESIO…) y los códigos 010202-010203 y 010206-010209 (por el orden de sus ids en el manual) también.
  - Para cargar la lista oficial: exportar *Parámetros / Uso Predio* del SRTM, dejar en el CSV código y descripción
    (`fuente` = `SRTM`), agregar a los enums `clase_uso`, `sub_clase_uso` y `uso` de `model.json` los nombres nuevos
    (`python3 -m unittest` dice cuáles faltan), `python3 apply.py` y `python3 import_catalogos.py`. Lo cargado no se
    borra: un uso que desaparezca de la lista se borra a mano desde el admin.
  - Los campos `clase_uso`, `sub_clase_uso` y `uso` de `declaracion_predial` siguen siendo ENUM (Core no cambia el tipo
    de un campo) con los nombres del catálogo. `uso` conserva además los diez grupos del padrón
    (`RESIDENCIAL - CASA HABITACION`, `TERRENO`…), que las DJ importadas tienen sin clase ni sub clase.
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

Cada predio llega con su ubicación separada como la pide el formulario del SRTM: `via` "JIRON LIMA" se guarda como
`tipo_via` JIRON y `via` LIMA, `habilitacion_urbana` "CERCADO II MESETA" como `tipo_zona` CERCADO y
`habilitacion_urbana` II MESETA, y `kilometro` sale de "Km.:". `direccion` guarda el texto del padrón. La secuencia de
uso tiene tres dígitos ("001"), también en el portal.

**El Excel y los reportes no se versionan**: traen DNI, nombres y domicilios. `*.xlsx` y `model/reports/` están en
`.gitignore`.

`model/reports/nombres_dudosos.csv` lista los nombres cuya separación en apellidos y nombres conviene revisar a mano
(con coma, con ` Y `, `SN`, menos de 3 palabras o sin nombres). `nombre_completo` siempre guarda el texto original.

## Normalizar el padrón importado

Un padrón importado antes de que `import_predios.py` separara los tipos se normaliza en Core, sin volver a importar:

```bash
cd model
python3 normalizar_padron.py --dry-run   # lee Core y escribe el reporte; no cambia nada
python3 normalizar_padron.py             # actualiza lo que el reporte lista
```

- **Predios del padrón** (sin `tipo_via`): separa tipo y nombre de `via` y de `habilitacion_urbana` (sin tipo
  reconocido queda OTROS) y lee `kilometro` de `direccion`. `direccion` no cambia: el portal la rearma, con las
  abreviaturas del SRTM, al guardar la ubicación.
- **Declaraciones:** `secuencia_uso` con tres dígitos ("1" → "001").
- **Reporte** (`model/reports/normalizar_padron.csv`): cada campo que cambia (antes y después) y lo que conviene mirar a
  mano: tipos no reconocidos, restos de lote que salen de la zona, direcciones guardadas desde el portal que repiten el
  tipo ("JIRON JR. LIMA") y secuencias que coinciden con otra declaración.
- Actualiza cada registro con todos sus campos (el update de Core los reemplaza todos). **Idempotente:** una segunda
  corrida no cambia nada.
- Flags: `--report`, `--workers` (PUTs en paralelo, default 4), `--core`, `--email`, `--password`. Salida: `0` ok, `1`
  Core rechazó algo (se muestra el código del predio o el número de la declaración).

## Modelo

Dieciocho objetos (`model/model.json`):
- **Padrón:** `contribuyente`, `predio` y `declaracion_predial`, cargados desde el Excel. Sus nombres de campo siguen el
  *Formato Padrón Municipal Armonización 2026*.
- **Registro de contribuyente del SRTM (fase 1):** `domicilio`, `relacionado`, `medio_contacto` y `sustento`, cada uno
  con una relación obligatoria a `contribuyente`.
- **Declaración jurada predial del SRTM (fase 2):** `transferente`, `nivel_construccion`, `obra_complementaria` y
  `otro_frente`, cada uno con una relación obligatoria a `declaracion_predial`.
- **Catastro fiscal (fase 3):** `catastro_fiscal`, un lote por código CPU, con su polígono.
- **Catálogos:** `ubigeo`, `via`, `unidad_urbana`, `categoria_valor`, `obra_categoria` y `uso_predio`.

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
  del usuario por objeto y por campo, y valida cada escritura. `Registros` envía solo los campos que el usuario puede
  escribir (un campo bloqueado conserva su valor) y `/catalogos` omite los objetos que su rol no puede leer.
- **Errores:** salen como problem+json, con `errors[].field` igual al nombre del campo.

| Método | Ruta | |
|---|---|---|
| GET | `/api/srtm/resumen` | totales del padrón |
| GET | `/api/srtm/catalogos` | opciones ENUM por objeto y campo, de los objetos que el rol puede leer |
| GET | `/api/srtm/ubigeos` | la lista INEI completa; la cascada departamento → provincia → distrito se hace en el portal |
| GET | `/api/srtm/vias?q&tipo&ubigeo`, `/api/srtm/unidades-urbanas?q&tipo&ubigeo` | sugerencias del catálogo |
| GET, POST | `/api/srtm/contribuyentes?q&page&size` | búsqueda (texto en todos los campos) e inscripción |
| GET, PUT, DELETE | `/api/srtm/contribuyentes/{id}?anio` | la ficha: datos, nº de predios y totales del año; la edición; y la baja (409 si tiene declaraciones) |
| GET | `/api/srtm/contribuyentes/{id}/declaraciones?anio` | sus declaraciones, cada una con su predio |
| GET, POST | `/api/srtm/contribuyentes/{id}/{lista}` | las listas del contribuyente: `domicilios`, `relacionados`, `medios-contacto`, `sustentos` |
| PUT, DELETE | `/api/srtm/{lista}/{id}` | edición y baja de una fila de esas listas |
| GET, POST | `/api/srtm/predios?q&page&size` | búsqueda y alta |
| GET, PUT, DELETE | `/api/srtm/predios/{id}?anio` | la ficha: datos, nº de titulares y totales del año; la edición; y la baja (409 si tiene declaraciones) |
| GET | `/api/srtm/predios/{id}/declaraciones?anio` | sus declaraciones, cada una con su contribuyente |
| POST | `/api/srtm/declaraciones` | alta corta, desde la ficha del predio (`contribuyente` y `predio` son ids) |
| POST | `/api/srtm/contribuyentes/{id}/declaraciones-juradas` | presenta una DJ: `{declaracion, predio_id}` sobre un predio del padrón, o `{declaracion, predio}` registrando uno |
| GET, PUT, DELETE | `/api/srtm/declaraciones/{id}` | la DJ con su predio y su contribuyente; la edición; y la baja (409 si alguna de sus listas tiene filas) |
| POST | `/api/srtm/declaraciones/{id}/anular` | el descargo: `{motivo_anulacion}` la deja ANULADA |
| POST | `/api/srtm/declaraciones/{id}/condominos` | "Datos de los condóminos": `{contribuyente, porcentaje_condominio}` agrega otro titular del mismo predio, año y secuencia |
| GET, POST | `/api/srtm/declaraciones/{id}/{lista}` | las listas de la DJ: `transferentes`, `niveles`, `obras`, `frentes` |
| PUT, DELETE | `/api/srtm/{lista}/{id}` | edición y baja de una fila de esas listas |
| GET | `/api/srtm/categorias-valor` | las letras de las siete columnas del cuadro de valores, con su descripción |
| GET | `/api/srtm/obras-categorias?tipo_obra` | las partidas del instructivo de obras complementarias |
| GET | `/api/srtm/predios/buscar?…` | "Buscar en Tributario" (pág. 13) |
| GET, POST | `/api/srtm/catastro?…` | "Buscar en Catastro Fiscal" (pág. 13), y el alta de un lote |
| GET, PUT | `/api/srtm/catastro/{id}` | un lote del catastro, y su edición (polígono incluido) |
| GET | `/api/gis/objects/{catastro_fiscal\|predio}/features?bbox&geometry=lote_geom` | de wasichai-gis: los lotes del área visible, para el mapa |
| GET | `/api/srtm/documentos/{tipo}/{numero}` | los apellidos y nombres que RENIEC da de un DNI; 404 si no hay datos o no hay convenio ([PIDE RENIEC](#pide-reniec)) |

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
- **Código de fila:** domicilios, relacionados, medios de contacto y documentos sustento (y los transferentes de una DJ)
  reciben `codigo` 001, 002… bajo su padre, el siguiente al mayor (`siguienteCodigoLista`). Se conserva al editar y no
  se renumera al borrar.
- **Declaración jurada** (en `DeclaracionService`):
  - Al presentarla, el backend le asigna `numero_declaracion` (correlativo, único).
  - Por defecto: año de la fecha de presentación, secuencia 1, INSCRIPCIÓN, DECLARACIÓN JURADA, FÍSICO.
  - Un predio nuevo sin código recibe uno (`codigoPredio`); sector y manzana catastral no son obligatorios (el SRTM
    no los pide, pág. 14):
    1. con sector **y** manzana: `SS-MM-NNNN`, el siguiente de esa manzana, como el padrón;
    2. si no, el `codigo_predio_municipal` del lote de catastro cuyo `codigo_cpu` trae, si ningún predio lo tiene ya;
    3. si no, la serie propia del portal: `P-NNNNNN` (`P-000001`, `P-000002`…). Una letra y un solo guion: no choca
       con `SS-MM-NNNN` (dos guiones, sector de dos caracteres) ni con los códigos del padrón.
  - Un `codigo` enviado por el cliente se respeta si está libre; si ya es de otro predio, es un 400 sobre `codigo`
    (Core dejaría que la base lo rechazara con un 500).
  - El código y `numero_registro` se calculan dentro del reintento: si otro funcionario tomó el mismo en ese momento
    (la base rechaza el duplicado), se vuelven a calcular. Si la declaración se rechaza, ese predio se borra.
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
- **Anulación y baja** (`Anulacion.kt`, con sus tests):
  - Anular una DJ es su descargo: `estado` ANULADA, `motivo` DESCARGO, `motivo_anulacion` (obligatorio, si no 400) y
    `fecha_anulacion` de hoy. Sin `estado` (las importadas) una DJ es VIGENTE; la nueva nace VIGENTE y la edición no
    cambia su estado.
  - Una anulada sale de su condominio (el resto se recalcula: el titular que queda solo vuelve a PROPIETARIO ÚNICO al
    100 %) y de los totales y conteos de las fichas, pero sigue en las listas de declaraciones. Es de solo lectura: su
    edición, la de sus listas, agregar un condómino desde ella o anularla otra vez son un 400 sobre `estado`.
  - Baja protegida: un predio o un contribuyente con declaraciones (vigentes o anuladas) no se borra (409 con el
    motivo). Sin ellas, el contribuyente se borra con sus domicilios, relacionados, medios de contacto y sustentos. Una
    DJ con transferentes, niveles, obras u otros frentes tampoco (409: se anula). Core respondería un 500 por la clave
    foránea: la comprobación va antes.
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
- **Motivo** (`Motivo.kt`): INSCRIPCIÓN al inscribir un contribuyente o presentar una DJ. Cada edición desde el portal
  (`PUT` del contribuyente o de la declaración) lo pasa a ACTUALIZACIÓN, diga lo que diga el cuerpo, sin renumerar ni
  guardar historial. Una DJ anulada no se edita (ver *Anulación y baja*) y un DESCARGO nunca se pisa.
  - No son una edición las filas de sus listas (domicilios, niveles, transferentes…) ni los valores que el condominio
    recalcula en los otros condóminos.
  - Los importados del padrón no traen motivo, código ni número: editarlos los pasa a ACTUALIZACIÓN, pero no les
    asigna código ni número (el portal muestra "SIN CÓDIGO (padrón)").
- **Medio de determinación y modificación de oficio:** de solo lectura en el portal; se guardan como vienen
  (DECLARACIÓN JURADA al inscribir o presentar).
  - La modificación de oficio (FISCALIZACIÓN, CRUCE DE INFORMACIÓN, RESOLUCIÓN) dice por qué la administración cambió
    una declaración sin que el contribuyente la presentara, con medio de determinación FISCALIZACIÓN o DE OFICIO.
  - Solo la fijará un flujo de fiscalización, que el portal no tiene (fuera de alcance: siguientes pasos).
- **Escrituras:** el `update` de Core reemplaza **todos** los campos editables. Por eso el portal fusiona lo que envía
  con el registro guardado: un campo añadido desde el admin, que el DTO no conoce, no se borra al guardar desde el
  portal. Un campo enviado como `null`, en cambio, sí se borra.
- **Sin anio:** la ficha usa el año en curso y las declaraciones traen todos los años.
- **Declaraciones embebidas:** se leen en una sola consulta por ids, sin N+1.

Guardado fuera del portal (el admin, la API de registros de Core; `ReglasFueraDelPortal.kt`): Core no tiene un gancho
antes de escribir, sus `RecordChangeListener` corren justo después, dentro de la misma petición. El listener completa el
registro con una segunda escritura, como el mismo usuario y solo en los campos que puede escribir:
- **Derivados de un solo registro:** `tipo_persona` y `nombre_completo` del contribuyente, `descripcion` del domicilio y
  `total_metrado` de la obra complementaria, con las mismas funciones de `Reglas.kt` que usan los servicios.
- **Cuándo:** un registro nuevo recibe solo los derivados que dejó vacíos (el importador conserva el texto del padrón).
  Uno editado recibe los que cambian porque cambió aquello de lo que salen, salvo que esa misma escritura los fije a
  mano. Lo que el portal ya guardó derivado no se vuelve a escribir.
- **Solo por el portal:** las reglas que leen otros registros: numeración y códigos (`codigo`, `numero_declaracion`,
  `fecha_registro` del contribuyente; `numero_declaracion` de la DJ; `codigo` y `numero_registro` del predio; `codigo`
  de un domicilio, relacionado, medio de contacto, documento sustento o transferente), el domicilio fiscal copiado al
  contribuyente, el condominio (condición, % y valores de todo el grupo), las validaciones (documento, nombre o razón
  social de un relacionado o un transferente) y los valores por defecto de una inscripción, una DJ o una fila nueva.
  También la `direccion` del predio: `normalizar_padron.py` separa por esta API el tipo de vía del padrón y conserva su
  texto, y el portal la rearma al guardar la ubicación. Desde el admin no se aplican.

## PIDE RENIEC

Al salir del N° de DNI de un contribuyente, relacionado o transferente (págs. 3, 8 y 15), el portal pide
`GET /api/srtm/documentos/DNI/{numero}`. Con respuesta, rellena y deja en gris apellidos y nombres, con fuente
PIDE RENIEC; sin ella (404), se escriben a mano con fuente MANUAL.

- **Paquete `srtm.pide`:** la interfaz `ConsultaDocumento`, con dos implementaciones:
  - `SinConsulta`, por defecto: nunca hay datos.
  - `PideReniec`, con `SRTM_PIDE_RENIEC_ENABLED=true` y las tres credenciales del convenio. Si falta alguna, se
    registra un aviso y no se consulta.
- **Contrato:** `PideReniec` hace un POST a `…/Rest/RENIEC/Consultar?out=json` con
  `{"PIDE": {"nuDniConsulta", "nuDniUsuario", "nuRucUsuario", "password"}}`, así la clave nunca va en una URL. Lee
  `consultarResponse.return`:
  - `coResultado` es `0000` cuando encuentra a la persona.
  - `datosPersona` trae `apPrimer`, `apSegundo`, `prenombres`, `estadoCivil`, `direccion` y `ubigeo`. La foto no se lee.
  - Está tomado de los ejemplos publicados de la PIDE, sin credenciales para probarlo: **hay que revisarlo contra la
    documentación del convenio** antes de encenderlo. Algunas entidades usan `ws6.pide.gob.pe` en producción: la URL se
    configura.
- **Fallos:** cualquier error, código distinto de `0000` o demora mayor a `SRTM_PIDE_RENIEC_TIMEOUT` equivale a "sin
  datos". El log registra solo el motivo, nunca la clave ni los datos de la persona.
  - La clave vencida (`1002`) no se renueva sola: hay que cambiarla en el convenio.
- **Qué se consulta:** solo un DNI de 8 dígitos, porque cada consulta tiene costo.
- **Fuente PIDE RENIEC respaldada:** el backend recuerda en memoria, durante 30 minutos, cada consulta con respuesta.
  Una alta o edición con fuente PIDE RENIEC exige una consulta vigente de ese DNI con los mismos apellidos y nombres,
  sin distinguir mayúsculas ni espacios. Si no, es un 400 sobre `fuente_informacion`.
  - Un registro que ya tenía PIDE RENIEC la conserva mientras no cambien su documento ni sus nombres.
  - La memoria es de cada instancia: con varias instancias del backend habría que guardarla en la base.
  - Lo que se escribe fuera del portal (admin, API de Core) no pasa por esta regla.
- **Tests:** usan un doble de `ConsultaDocumento` (`ConsultaReniecApiTest`) o un servidor local
  (`PideReniecTest`). Nunca llaman a la PIDE real.

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
  fiscal cuando esté disponible. Si se decide, asignar código y número a los contribuyentes y declaraciones importados
  del padrón.
- **Integraciones:** probar PIDE RENIEC con las credenciales del convenio; PIDE SUNAT (RUC) y MIGRACIONES (carné de
  extranjería) con la misma interfaz `ConsultaDocumento`. El fondo del mapa es OpenStreetMap; una capa WMS/WMTS
  municipal se puede publicar con GeoServer.
- **Cálculo y cobranza:** impuesto predial (tramos UIT), arbitrios, deuda y cuotas, pagos y recibos.
- **Fiscalización:** el flujo (rol y pantallas) que determina de oficio una declaración: medio de determinación
  FISCALIZACIÓN o DE OFICIO y su modificación de oficio.
- **En el modelo:** workflows y plantillas de documentos.

El frontend web está en `srtm-ui`.
