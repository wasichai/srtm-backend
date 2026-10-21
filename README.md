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
- Las librerías de wasichai (`wasichai:wasichai-bom:1.3.0` y los starters). Se resuelven desde:
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
| `SRTM_CATASTRO_ENABLED` | `false` | valuaciones de catastro-backend para la brecha (ver [Brecha de recaudación](#brecha-de-recaudación)) y el [autoavalúo sugerido](#autoavalúo-sugerido) |
| `SRTM_CATASTRO_ORGANIZACIONES_<CLAVE>_URL` / `_CLIENT_ID` / `_CLIENT_SECRET` / `_TIMEOUT` | sin entradas / `60s` | el catastro-backend de una organización y su cuenta de servicio `srtm`; `<CLAVE>` es su slug, o cualquiera con `_ORGANIZACION` = su id. El secreto nunca en el repositorio |
| `SRTM_CATASTRO_URL` / `_CLIENT_ID` / `_CLIENT_SECRET` / `_TIMEOUT` | `http://localhost:8091` / vacías / `60s` | la forma de una sola organización: una entrada más del mapa |
| `SRTM_CATASTRO_ORGANIZACION` | vacía | slug o id de la organización de esa entrada única; vacía, esa cuenta no se usa |

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
python3 apply.py --diff            # lee la base y dice qué escribiría ("would PUT …"), sin escribir nada
python3 apply.py                   # done: 82 created, 2 updated, 0 skipped  (40 objetos + 42 relaciones; 2 appendOnly)
python3 apply.py                   # idempotente: done: 0 created, 0 updated, 82 skipped
python3 apply.py --drop            # apaga appendOnly y lo borra, en orden inverso (¡borra también los datos!)
```

Sobre una base que ya tiene el modelo, `apply.py` también **sincroniza**:
- **Campos:** añade a los objetos existentes los que `model.json` tiene y Core no.
- **Opciones ENUM:** añade las opciones que falten, y quita las que `model.json` ya no lista si ningún registro las usa
  (la que alguno usa se queda, con un aviso `keep option …`). La lista que cambia queda en el orden de `model.json`,
  con las que se conservan por estar en uso al final.
- **Obligatoriedad:** deja opcional el campo que `model.json` ya no exige (`contribuyente.numero_documento`, vacío con
  SIN DOCUMENTO); nunca vuelve obligatorio uno existente.
- **Etiquetas:** pone la etiqueta de `model.json` al campo que Core etiqueta distinto (`predio.tipo_predio`:
  "Tipo de predio"). Es solo lo que muestra el admin.
- **Reglas del objeto:** después de las relaciones (un índice puede nombrar un campo de relación), deja `indexes`
  (ADR-036) y `appendOnly` (ADR-040) como los declara `model.json`, con un `PUT /api/objects/{objeto}` que
  lleva la etiqueta y solo lo que cambia (`update object cuota_arbitrio (appendOnly)`). `model.json` manda: un índice
  que solo tiene Core se quita, y un objeto sin `appendOnly` queda sin él. El PUT devuelve la etiqueta, el plural, la
  descripción y `enabled` tal como los tiene Core (si faltan, Core los pisa), así que solo cambia las reglas.
  `--dry-run` no consulta a Core: muestra lo que declara `model.json`, no la diferencia con la base.

**`--diff`, antes de aplicar a una base compartida (dev).** Entra a Core y solo lo lee: corre la misma sincronización
que `apply.py`, pero cada escritura (POST, PUT o DELETE) se imprime como `would <MÉTODO> <ruta> <resumen>` en lugar de
enviarse, y al final `diff: nothing pending` o `diff: N writes pending, none sent`. Lo que la base ya tiene no cuenta
como pendiente: una relación que existe, y la marca de obligatoria de un campo que ya lo es. Sobre la base de dev de
antes de 0.3.2 muestra exactamente las dos reglas:

```text
would PUT /api/objects/cuota_arbitrio {"indexes": [["anio", "predio"], ["anio", "contribuyente"]], "appendOnly": true}
would PUT /api/objects/anulacion_cuota_arbitrio {"appendOnly": true}
diff: 2 writes pending, none sent
```

En dev el procedimiento es: `python3 apply.py --diff`, mostrar lo pendiente y pedir OK; recién entonces
`python3 apply.py`. Una segunda corrida de `--diff` debe decir `diff: nothing pending`. Con `--drop`, `--diff` lista
lo que borraría.

Solo añade, relaja, reetiqueta o quita opciones sin uso: no renombra, no cambia tipos y no borra campos, así los
registros importados siguen siendo válidos. Por ejemplo, sobre la base del padrón:
- añade los campos nuevos de `contribuyente`, `predio` y `declaracion_predial`;
- amplía `tipo_documento` (`PASAPORTE`, y `PTP-CPP`, `CI` y `OTROS` de los manuales del SRTM) y `condicion_propiedad`
  (`SOCIEDAD CONYUGAL`, `POSEEDOR`);
- cambia `tipo_unidad_urbana` a los 43 tipos del dominio oficial `TIPO_UU`
  ([Tipos de unidad urbana](#tipos-de-unidad-urbana)): agrega los 28 que faltaban y quita `COMUNIDAD CAMPESINA` y
  `COMUNIDAD NATIVA`. `ANEXO`, `HABILITACION URBANA` y `OTROS` salen cuando ya no los usa nadie
  ([Migrar los tipos de unidad urbana](#migrar-los-tipos-de-unidad-urbana));
- cambia `tipo_obra` a los grupos del anexo III de obras complementarias (quita `CISTERNAS`, `PISCINAS`,
  `LOSAS DEPORTIVAS`, `PISOS DE CONCRETO` y `OTROS`) y añade `PZA` a `unidad_medida`;
- quita de `clase_uso` y `sub_clase_uso` las opciones de antes del catálogo de usos del SRTM (`INDUSTRIAL`,
  `SERVICIOS`, `AGRICOLA`, `OTROS`; `BODEGA`, `TIENDA`, `TALLER`, `ALMACEN`, `OTROS`) que ninguna DJ usa;
- quita de `uso` los grupos de uso del padrón (`RESIDENCIAL - CASA HABITACION`, `TERRENO`…) y de `clase_uso`
  `ESTACIONAMIENTO` una vez migradas las DJ que los usan ([Migrar los usos del padrón](#migrar-los-usos-del-padrón));
- crea los objetos y relaciones de las fases 1 y 2.

Una relación queda obligatoria cuando `model.json` dice `"required": true`; las opcionales (ver
[Modelo](#modelo)) se crean sin ese paso.

Flags: `--core` (default `http://localhost:8090` o `$WASICHAI_CORE`), `--email`, `--password`, `--dry-run`,
`--diff` (no va con `--dry-run`), `--drop`, `--validate-only`. Salida: `0` ok, `1` error de Core, `2` `model.json`
inválido.

## Cargar los catálogos

Los formularios del portal ofrecen ubigeo, usos del predio, vías, unidades urbanas, categorías de valores y partidas de
obras complementarias desde objetos catálogo:

```bash
cd model
python3 import_catalogos.py --distrito 120302 --municipalidad-json data/perene/municipalidad.json --obras-csv data/obras_complementarias.csv   # ubigeo, categorías, partidas de obras, usos y municipalidad
python3 import_catalogos.py --distrito 120302 --municipalidad-json data/perene/municipalidad.json --obras-csv data/obras_complementarias.csv \
  --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx" --dry-run                                         # lee Core y dice qué haría
python3 import_catalogos.py --distrito 120302 --municipalidad-json data/perene/municipalidad.json --obras-csv data/obras_complementarias.csv \
  --excel "/ruta/CODIGO DE PREDIOS AL 2026.xlsx"                                                   # ubigeo + vías + unidades urbanas
```

`--distrito` (el ubigeo del distrito de las vías y unidades urbanas del padrón), `--municipalidad-json` (la cabecera
provisional de la organización) y `--obras-csv` (las partidas de obras complementarias de su región) son
**obligatorios**: ningún script elige una organización por defecto. Los de Perené son `120302`,
`model/data/perene/municipalidad.json` y `model/data/obras_complementarias.csv` (el anexo de selva).

- **`municipalidad`:** la cabecera del PU y la HR ([Cabecera de los documentos](#cabecera-de-los-documentos)). Se
  crea desde el archivo de `--municipalidad-json` (`model/data/perene/municipalidad.json` para Perené) solo si la organización no tiene ninguna; nunca pisa la editada en el
  admin. Los datos que trae son **provisionales**. El escudo no viene en el archivo: se sube aparte.
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
    Los de unidad urbana son los de [`TIPO_UU`](#tipos-de-unidad-urbana), con las palabras propias del padrón.
  - En las unidades urbanas se descartan los restos de lote delante del tipo ("03-B CERCADO III MESETA").
  - Una habilitación sin tipo reconocido no entra al catálogo: el tipo de la unidad urbana es obligatorio.
  - Se asignan al distrito de `--distrito` (por defecto `120302`, Perené).
  - Se leen también las abreviaturas con que el portal escribe las direcciones (JR., AV., CA., PSJE., PROL., CARR.;
    las de `ABREV_UU` y AA.VV.) y las erratas de CARROZABLE del padrón.
  - Con el Excel de 2026 salen 903 vías y 292 unidades urbanas.
- **`obra_categoria`:** las 96 partidas de obras complementarias e instalaciones fijas y permanentes del anexo III de
  la R.M. N.° 277-2025-VIVIENDA, en `model/data/obras_complementarias.csv`
  (`tipo_obra,numero,descripcion,unidad_medida,material,valor_unitario`).
  - Transcrito a mano del anexo III.4 (selva, la región de Perené): gob.pe no deja que un script lo descargue. Las
    partidas son las mismas en las cuatro regiones; solo cambia el valor.
  - `valor_unitario` es el V.U. 2026 a costo directo: se le aplica el factor de oficialización 0,68 y la depreciación.
  - `tipo_obra` es el grupo del anexo en mayúsculas sin tildes, con las comas cambiadas por ` - ` (las opciones ENUM de
    Core no las admiten). El único de más de 64 caracteres queda `LOSAS DEPORTIVAS - ESTACIONAMIENTOS - PATIOS - VEREDAS`.
  - Cada año, con la R.M. nueva, se actualizan los valores (y las partidas, si cambian).
- **`uso_predio`:** el *tipo de uso de predio* del SRTM (clase → sub clase → uso), en `model/data/usos_predio.csv`.
  Las características de la DJ lo ofrecen en cascada (`GET /api/srtm/usos-predio`).
  - El archivo tiene la forma de la tabla de parámetros del SRTM (*Parámetros / Uso Predio*, exportable a Excel):
    `codigo,descripcion,fuente`. El código de seis dígitos da el nivel: `XX0000` clase, `XXYY00` sub clase y `XXYYZZ`
    uso de esa sub clase. Se cargan los usos (276), cada uno con los nombres de su clase y su sub clase; la clave es el
    código.
  - **Es la tabla del SRTM hasta donde la muestran los documentos públicos:** el *codificador de usos* del SNCP, del
    que sale la del SRTM, más los cuatro usos residenciales que el SRTM le agrega (010206-010209). 324 filas: 10
    clases, 38 sub clases y 276 usos.
    - El SRTM tiene 323 (*M21-1-003*, pág. 262: "1 a 10 de 323 registros"). Sus ids 1-48 son las 10 clases y las 38
      sub clases del SNCP, en su orden; los usos van del 49 (010101) al 323 (100403).
    - Sobra uno de los usos 02xxxx-06xxxx: en el SRTM son los ids 49-298 (250 usos) y aquí 251. No se sabe cuál.
  - `fuente` dice de dónde sale cada fila:
    - `SRTM` (72): código y nombre completos en un documento del SRTM.
      - *Presentación2*, págs. 17 y 20.
      - Manual *M21-1-003 Parámetros*: §5.2.20 (págs. 259-263: la lista, ids 314-323, y el Excel exportado, ids
        298-323), el uso predio pensionista (pág. 52: los usos residenciales, ids 49-58, que van en el orden de los
        códigos) y §5.5.2 (págs. 411-417).
      - Manuales del SRTM de escritorio (SIAF-GL, módulo de rentas, MEF: `mef.gob.pe/contenidos/siafgl/manuales/`):
        v2.2.0 pág. 9, v3.0.0 (`Manual_Rentas_V300_Arbitrios.pdf`) págs. 13-14 y 21, v3.1.0
        (`Manual_Rentas_Proceso_Masivo_Version310.pdf`) pág. 8.
    - `SNCP` (171): el *Codificador de usos y actividades económicas*, Anexo 04 de las *Instrucciones para el llenado
      de las fichas catastrales* del SNCP.
      - SUNARP lo publica en `sunarp.gob.pe/transparencia.asp?ID=1230`, que no abre desde fuera del Perú. Se leyó la
        copia de `idoc.pub/documents/instrucciones-para-el-llenado-de-fichas-catastrales-con-anexos-en5ko617vpno`.
      - Todos los códigos que muestran los documentos del SRTM tienen ahí el mismo nombre.
      - El SNCP imprime los códigos 040301, 040303, 040302…: cada nombre va con el código de su fila (040303 BINGO O
        CASINO DE JUEGO, 040302 PINBALL O SIMILAR).
      - Imprime cinco renglones para los cuatro códigos 060201-060204 (los manuales del SRTM confirman
        060207-060209): se leyó 060202 COMISARÍA ESTACIÓN DE LA POLICÍA NACIONAL.
    - `ARMONIZACION` (81): el nombre del *Formato Padrón Municipal Armonización 2026* (el ejemplo lleno de Catacaos,
      columnas *Código uso* y *Descripción del uso*), del mismo código y sentido que en el SNCP.
      - Se dejaron fuera sus códigos propios: 010103-010105, 020108, 040211, 050117, 0504xx, 070102-070103 (el Excel
        del SRTM pasa de 070101, id 299, a 070201, id 300), 0909xx, 153045 y 999999.
      - También los nombres que el SNCP contradice: 020100 COMERCIAL (ALMACÉN), 020515 DE ESPARCIMIENTO NO
        ESPECIFICADO, 020517 TALLER y 040310 RESTAURANTE.
    - `INFERIDO` (0): un nombre o un código deducido. Ya no queda ninguno.
  - La clase 09 es GARAGE, como la llaman el SNCP y la armonización. El padrón de Perené la llama ESTACIONAMIENTO:
    `import_predios.py` guarda ese grupo como GARAGE y `migrar_usos_padron.py` pasa a GARAGE las DJ con esa clase.
  - Los nombres van en mayúsculas, con tildes y sin comas, barras, paréntesis ni punto final (las opciones ENUM de Core
    no los admiten). Se quitan los ejemplos entre paréntesis (DE ALIMENTACIÓN Y BEBIDAS). Si el último elemento de una
    lista no lleva "y", va con "O" (BINGO O CASINO DE JUEGO). Los de más de 64 caracteres se acortan.
  - **Los usos siguen al CSV por código**, y los otros catálogos solo se crean. `import_catalogos.py`:
    - crea los códigos que Core no tiene;
    - actualiza los que cambiaron de clase, sub clase o uso;
    - borra los que el CSV ya no tiene. Ningún registro apunta a un uso: la DJ guarda los nombres en sus ENUM.
    - Con `--dry-run` lee Core y lista lo que haría, sin escribir.
    - Imprime `uso_predio: N created, M updated, K deleted, S skipped`.
  - Para cambiar el catálogo (la lista oficial del SRTM, por ejemplo), en este orden:
    1. Dejar en el CSV código y descripción (`fuente` = `SRTM` si viene del *Exportar a Excel* de *Parámetros / Uso
       Predio*).
    2. Poner en los enums `sub_clase_uso` y `uso` de `model.json` los nombres del catálogo (`python3 -m unittest` dice
       cuáles faltan o sobran).
    3. `python3 apply.py` agrega las opciones nuevas.
    4. `python3 import_catalogos.py --distrito 120302 --municipalidad-json data/perene/municipalidad.json --obras-csv data/obras_complementarias.csv --dry-run`: revisar lo que va a cambiar. Después, sin `--dry-run`.
    5. Si cambió el nombre de una clase que las DJ usan, agregarla a `CLASES_RENOMBRADAS` de `migrar_usos_padron.py` y
       migrar (ver [Migrar los usos del padrón](#migrar-los-usos-del-padrón)).
    6. `python3 apply.py` otra vez quita las opciones que ya nadie usa.
  - Un Core con el catálogo anterior (154 filas) pasa a este con 169 usos creados, 17 actualizados y 5 borrados
    (020108, 040211, 050117, 070102 y 070103). Después, `migrar_usos_padron.py` pasa a GARAGE las DJ con la clase
    ESTACIONAMIENTO, y `apply.py` la quita de `clase_uso`.
  - Los campos `clase_uso`, `sub_clase_uso` y `uso` de `declaracion_predial` siguen siendo ENUM (Core no cambia el tipo
    de un campo) con los nombres del catálogo, y nada más: `uso` lista solo los usos del catálogo. Los diez grupos de
    uso del padrón son las diez clases: `RESIDENCIAL - CASA HABITACION` se guarda como RESIDENCIAL / UNIFAMILIAR / CASA
    HABITACIÓN, `ESTACIONAMIENTO` como GARAGE y cualquier otro grupo como la clase de su nombre, sin sub clase ni uso,
    que el portal pide al editar la DJ (ver [Migrar los usos del padrón](#migrar-los-usos-del-padrón)).
- **Idempotente**, como los otros scripts.
- Los catálogos se pueden editar después desde el admin. Un uso del predio editado así vuelve a lo que dice el CSV en
  la siguiente importación.

## Impuesto predial

`GET /api/srtm/contribuyentes/{id}/liquidacion?anio=` liquida el impuesto predial de un contribuyente en un año (sin
`anio`, el año en curso). Lo calcula `srtm.impuesto.ImpuestoPredial`, una función pura, con los parámetros de
`parametro_tributario`.

**Parámetros.** Son los valores normativos **verificados** (doble firma) del repo `normativa`:
`docs/10-negocio/valores-normativos/{uit,predial-tramos-y-alicuotas,predial-minimo,predial-deducciones}.md`.

- `model/data/parametros-predial.csv` es una copia de las filas `UIT`, `TRAMO_PREDIAL`, `TRAMO_PREDIAL_LIMITE`,
  `PREDIAL_MINIMO`, `DEDUCCION_PENSIONISTA` y `DEDUCCION_ADULTO_MAYOR` de su derivado publicable,
  `publicacion/parametros-2026.csv`.
  - Ninguna cifra se tecleó: las filas son las del original, sin su última columna (`valor_maquina`, vacía en todas).
  - La cabecera `#` cita la fuente.
  - Las columnas llevan los nombres de `parametro_tributario`: `tipo`, `clave`, `vigencia_desde`, `vigencia_hasta`,
    `valor_numerico`, `texto`, `norma`, `fuente`, `transcribio` y `verifico`.
- `valor_numerico` va como lo imprime la norma: las alícuotas y el mínimo en %, los límites de los tramos y las
  deducciones en UIT, y la UIT en soles.
- **Cada año**, con la UIT nueva de `normativa`, se copia su fila al CSV y se vuelve a cargar. Con
  `NORMATIVA=/ruta/a/normativa`, o con `normativa` junto a este repo, `python3 -m unittest` compara cada fila con el
  original.

```bash
cd model
python3 import_parametros.py --dry-run   # lee Core y dice qué crearía o actualizaría, sin escribir
python3 import_parametros.py             # parametro_tributario: 13 created, 0 updated, 0 skipped
```

`import_parametros.py` es idempotente y usa la clave natural (`tipo`, `clave`, `vigencia_desde`):
- crea las filas que faltan;
- actualiza en su lugar las que cambiaron;
- no borra las que el CSV no tiene;
- escribe una línea por fila que cambia y un resumen al final;
- sale con `0` si todo va bien y con `1` si Core rechaza algo.

**Cálculo** (art. 13 y 15 del TUO de la Ley de Tributación Municipal):
- **Base:** la suma del `valor_afecto` de las DJ **vigentes** del contribuyente en el año. Es la misma suma que los
  totales de la ficha (`totalesDeContribuyente`): cada condómino cuenta su parte, así que un predio compartido no se
  cuenta dos veces. Una DJ anulada no suma.
- **Tramos progresivos en UIT:**
  - hasta 15 UIT, al 0.2 %;
  - lo que excede de 15 UIT hasta 60 UIT, al 0.6 %;
  - lo que excede de 60 UIT, al 1.0 %.

  Cada tramo trae `desde`, `hasta`, `alicuota`, `monto` (la parte de la base que cae en él) e `impuesto`. El
  impuesto de cada tramo se redondea al céntimo, y `impuestoCalculado` es la suma de los tramos.
- **Mínimo:** 0.6 % de la UIT. Si la base es mayor que 0, `impuestoAnual` es el mayor entre `impuestoCalculado` y el
  mínimo, y `minimoAplicado` dice si se usó el mínimo. Con base 0, el impuesto es 0 y no hay mínimo.
- **Cuotas:** 4, de un cuarto cada una, redondeadas al céntimo (HALF_UP). La 4.ª lleva el residuo, así que las cuatro
  suman exactamente el anual.
- **Vencimientos:** el último día hábil de febrero, mayo, agosto y noviembre. Hábil significa de lunes a viernes y
  que no sea feriado nacional de fecha fija. Los feriados están en una sola lista, `Vencimientos.FERIADOS_NACIONALES`:
  1-ene, 1-may, 7-jun, 29-jun, 23-jul, 28 y 29-jul, 6-ago, 30-ago, 8-oct, 1-nov, 8-dic, 9-dic y 25-dic. En 2026 los
  vencimientos son el 27-feb, el 29-may, el 31-ago y el 30-nov.
- **Parámetros del año:** se usan la UIT, los tramos, los límites y el mínimo vigentes al 1 de enero. Si falta alguno,
  no se calcula:
  - `faltan` los nombra (`["UIT 2027"]`);
  - `uit`, los importes y `minimoAplicado` van en `null`, y `tramos` y `cuotas` van vacíos;
  - `base` sí se informa.

```json
{"anio": 2026, "uit": 5500.00, "base": 90000.00,
 "tramos": [{"tramo": 1, "desde": 0.00, "hasta": 82500.00, "alicuota": 0.2, "monto": 82500.00, "impuesto": 165.00}, …],
 "impuestoCalculado": 210.00, "minimo": 33.00, "minimoAplicado": false, "impuestoAnual": 210.00,
 "cuotas": [{"numero": 1, "monto": 52.50, "vencimiento": "2026-02-27"}, …], "faltan": []}
```

Fuera de alcance: el reajuste de las cuotas 2 a 4 por el IPM, la prórroga de los vencimientos por ordenanza, el derecho
de emisión y las deducciones (los parámetros ya se cargan, pero la DJ guarda su `deduccion`).

## Arbitrios

Los arbitrios se portan del negocio de `rentas` (`DeterminarArbitrios`). Ya están el modelo, la carga de una
ordenanza, el cálculo (`srtm.arbitrios.Arbitrios`, una función pura) y la determinación por predio y por contribuyente
con sus consultas, la determinación masiva, la Hoja de Liquidación de Arbitrios (HLA), sola y en la emisión masiva, y
la anulación de una cuota.

**Modelo:**

| Objeto | Qué es |
|---|---|
| `ordenanza_arbitrio` | La ordenanza de un año (`anio` único) y su ratificación por la provincial (`acuerdo_ratificacion`, `fecha_ratificacion`, `municipalidad_ratificante`). Sin ordenanza ratificada no se determina ni se emite (D-02b). |
| `servicio_arbitrio` | Un servicio que la ordenanza cobra (barrido, residuos, serenazgo…), con `codigo` único de hasta 20 caracteres y su vigencia. Ningún servicio está escrito en el código. |
| `inafectacion_arbitrio` | Un predio que no paga un servicio en una vigencia (ambos extremos cuentan), con su motivo, documento y observación. |
| `cuota_arbitrio` | Lo determinado de un predio, un servicio, un uso (`secuencia_uso`) y un mes: `monto`, `parametro_aplicado` (las llaves leídas, separadas por `;`), `zona` y `uso_arbitrio` usados, su desglose (`base`, `cantidad_base`, `tasa_unitaria`, `habitantes`, `habitantes_presuntos`, `promedio_habitantes`, `variacion_habitante`, `influencia`, `afluencia`, `redondeo`), `monto_sin_tope` y `tope_aplicado` (vacíos salvo que el tope de incremento la haya reducido), `fecha_calculo` y `observacion`, más relaciones obligatorias a `predio`, `contribuyente` (el titular principal ese mes), `servicio` y `parametro` (la fila de `parametro_tributario`). |
| `anulacion_cuota_arbitrio` | La corrección de una cuota: `motivo`, `observacion`, `fecha`, y su `cuota` y `predio`. `clave` única (el id de la cuota): una sola anulación por cuota. |
| `determinacion_arbitrio_masiva`, `determinacion_arbitrio_lote` | El job de la determinación de un año en segundo plano y sus lotes de predios, gemelos de `emision_masiva` y `emision_lote`. No generan archivo. |
| `zona_arbitrio_ubicacion` | Una zona por vía, tramo (`numero_inicial`/`numero_final` y `paridad`) y manzana, opcionalmente de un solo `servicio`; más precisa que el sector catastral de `ARBITRIO_ZONA`, y se intenta primero (`Zonas.de`, ver **Zona por servicio y por ubicación**). `clave` única por vía, tramo, manzana, paridad, servicio y vigencia. |

Las garantías de `determinacion_arbitrio` de rentas, trasladadas:
- **Una cuota por predio, servicio, año, mes y secuencia de uso:** `cuota_arbitrio.clave` es única y vale
  `predio|servicio|anio|periodo|secuencia|version` (las cuotas anteriores al PU por uso, `predio|servicio|anio|periodo|version`).
  Core no tiene unicidad compuesta; como cada organización tiene su tabla física, la unicidad es por organización.
- **Inmutable:** una cuota no se edita ni se borra, ni siquiera un ADMIN; una corrección es una anulación que se
  agrega y una versión nueva de la `clave` (ver **Anulación**). `cuota_arbitrio` y `anulacion_cuota_arbitrio` son
  `appendOnly` en el modelo (ADR-040): Core responde **409** a un PUT o DELETE de una, por cualquier vía y para
  cualquiera, antes de la guardia de escritura. También responde **409** al borrar un predio, contribuyente, servicio o
  parámetro al que apunta una cuota. Las cuotas no exigen la marca de escritura: la API genérica todavía puede
  agregarlas, si cumplen sus invariantes.
- **`periodo` de 1 a 12, `monto` no negativo y cuadrado con su desglose (el `monto_sin_tope`, cuando un tope la redujo; el
  `monto` nunca lo supera, y `tope_aplicado` va con él), `parametro_aplicado` de hasta 500 caracteres,
  relaciones obligatorias y `clave` coherente:** los revisa la regla `ReglaDeArbitrios` de la guardia de escritura
  (`srtm.GuardiaDeEscritura`, ver [Modelo](#modelo)) al insertar, venga la cuota de donde venga; si no, **400**.
- **Un valor único repetido** (dos cuotas con la misma `clave`, o cualquier otro campo único del modelo) responde
  **409** y no 500 (`srtm.Duplicados`; Core 0.3 también responde 409, con `errors[].field`, pero en inglés; srtm lo
  sobrescribe para dar el mensaje en castellano, y pierde el campo).
- **Observación de 5 a 500 caracteres:** va en la propia cuota, porque la auditoría de Core no guarda observación.

**Parámetros de la ordenanza.** Las tasas y los mapeos son filas de `parametro_tributario`, con vigencia y doble
firma, y se cargan con `import_parametros.py --csv <archivo>`:

| `tipo` | `clave` | Valor |
|---|---|---|
| `TASA_ARBITRIO` | `servicio` y una parte por cada Dimension del servicio (`zona:uso` por defecto); su parte de zona puede ir vacía cuando la primera Dimension del servicio es `ZONA` (`servicio::uso`): significa "esta tasa rige en toda zona del servicio" (una tarifa que no varía por zona, dentro de un servicio cuyas demás tasas sí varían — ver `import_tasas_arbitrio.py`, debajo); `Arbitrios.determinar` prueba primero la llave exacta de la zona del predio y, si no existe, esta; la exacta gana siempre que exista | `valor_numerico`: la tasa mensual en soles, no negativa |
| `ARBITRIO_ZONA` | el sector catastral | `texto`: su zona según la ordenanza |
| `ARBITRIO_USO` | un prefijo del código de `uso_predio`, de 2, 4 o 6 dígitos (gana el más largo) | `texto`: su uso de arbitrio |
| `ARBITRIO_VENCIMIENTO` | el mes, de 1 a 12 | `texto`: su vencimiento, `AAAA-MM-DD` |
| `BASE_ARBITRIO` | el servicio | `texto`: su Base (`PREDIO`, `FRONTIS_ML` o `AREA_CONSTRUIDA_M2`); ej. Miraflores 2026, RRSS: `AREA_CONSTRUIDA_M2` |
| `DIMENSIONES_ARBITRIO` | el servicio | `texto`: sus Dimension separadas por comas, sin repetir (`ZONA`, `USO`, `INFLUENCIA`, `AFLUENCIA`); ej. Miraflores 2026, PARQUES: `ZONA,INFLUENCIA,AFLUENCIA` |
| `HABITANTES_PROMEDIO` / `HABITANTES_VARIACION` | `servicio:zona:uso` | `valor_numerico`: sus habitantes promedio y su variación, mayor que 0 (una sin la otra en el archivo no cuenta); ej. Miraflores 2026: P=2, V=0.5 |
| `REDONDEO_ARBITRIO` | vacía (global a la ordenanza) | `texto`: `decimales:modo` (`ABAJO`, `MEDIO` o `ARRIBA`); ej. Miraflores 2026: `2:ABAJO` |
| `ARBITRIO_INFLUENCIA` | la `ubicacion_area_verde` del predio (enum del modelo: `FRENTE A PARQUE`, `MEDIANAMENTE CERCANO A AREAS VERDES`, `FRENTE A BERMA`, `OTRAS UBICACIONES`) | `texto`: su nivel de influencia; ej. Miraflores 2026: `FRENTE A PARQUE` → `Frente a Parque y/o Área Verde` |
| `ARBITRIO_AFLUENCIA` | un prefijo del código de `uso_predio`, de 2, 4 o 6 dígitos, solo o como `servicio:prefijo` (gana el de su servicio; dentro de cada familia, el más largo) | `texto`: su nivel de afluencia |
| `TOPE_ARBITRIO` | el grupo de uso: un prefijo del código de `uso_predio`, de 2, 4 o 6 dígitos (gana el más largo), o `*` para todos los demás | `valor_numerico`: el % máximo de aumento sobre lo pagado el año anterior, mayor que 0; `texto`: vacío, o la fecha de corte del registro del predio (`AAAA-MM-DD`); ej. Miraflores 2026: 8 % en casa habitación y 18 % en otros usos, para predios registrados al 31/12/2025 (ver **Tope de incremento**) |
| `ARBITRIO_PU_POR_USO` | vacía (global) | `valor_numerico`: 1; `vigencia_desde`: el 1 de enero del primer año en que un predio paga un PU por cada uso. Un año es «por uso» si una fila rige su 1 de enero; sin fila, un PU por predio (ver **Un PU por uso** abajo) |

`import_parametros.py` revisa la forma de estas filas antes de enviar nada: la llave de la tasa (según las
`DIMENSIONES_ARBITRIO` vigentes de su servicio), el valor, el mes, la fecha y que `transcribio` y `verifico` sean dos
personas distintas. Si una no encaja, la nombra y sale con `2`. `BASE_ARBITRIO`, `DIMENSIONES_ARBITRIO`,
`HABITANTES_PROMEDIO`, `HABITANTES_VARIACION`, `REDONDEO_ARBITRIO`, `ARBITRIO_INFLUENCIA`, `ARBITRIO_AFLUENCIA`,
`TOPE_ARBITRIO` y `ARBITRIO_PU_POR_USO` son opcionales: sin estas filas el cálculo es el
de siempre (base `PREDIO`, dimensiones `ZONA,USO`, sin redondeo adicional, sin tope y un PU por predio).
`ARBITRIO_PU_POR_USO` lleva la clave vacía, `valor_numerico` 1 y una `vigencia_desde` que es un 1 de enero.

**Tasas de una ordenanza, de la tabla del informe técnico.** `import_tasas_arbitrio.py` deriva el CSV de arriba
(el que lee `import_parametros.py --csv`) de la tabla larga de tasas de una ordenanza, una fila por tasa: `servicio,
zona, uso_codigo, uso, ubicacion, nivel_afluencia, base, tasa_mensual`, con una cabecera `#` que cita la fuente (la
forma de `arbitrios-miraflores-2026.csv`, #86). No carga nada a Core: solo escribe el archivo, y para eso llama
`import_parametros.errores` antes de escribir.

```bash
cd model
python3 import_tasas_arbitrio.py --tasas tasas.csv --servicios BARRIDO_CALLES=BAR,RECOLECCION_RESIDUOS=RRS \
    --usos usos.csv --influencias influencias.csv --vigencia-desde 2026-01-01 --norma "Ordenanza N.° 665/MM" \
    --fuente "El Peruano, 20/12/2025" --transcribio "ANA, 2026-01-05" --verifico "BETO, 2026-01-09" \
    --redondeo 2:ABAJO --habitantes RECOLECCION_RESIDUOS=2:0.5 \
    --vencimientos 2026-02-28,2026-05-30,2026-08-29,2026-11-30 --dry-run   # cuenta por tipo, no escribe
python3 import_tasas_arbitrio.py ... --salida parametros-2026.csv          # sin --dry-run, escribe el archivo
```

Por cada servicio escribe `BASE_ARBITRIO` (de su columna `base`: «ml» → `FRONTIS_ML`, «m2»/«m²» → `AREA_CONSTRUIDA_M2`,
«predio» → `PREDIO`) y `DIMENSIONES_ARBITRIO` (`ZONA` si alguna fila del servicio trae zona, `USO` si alguna trae
`uso_codigo`, `INFLUENCIA`/`AFLUENCIA` igual, en ese orden); una `TASA_ARBITRIO` por fila, siempre (zona y
`nivel_afluencia` normalizados sin acentos, en mayúsculas y con `_`; `uso_codigo` y `ubicacion` verbatim). Una fila
sin zona en un servicio cuyas demás filas sí la traen no se descarta ni se rechaza: su parte de zona queda vacía
(«esta tasa rige en toda zona del servicio», la tarifa de «otros usos» de Miraflores que no varía por zona, a
diferencia de la de casa habitación del mismo servicio). `--usos` (`prefijo,uso`) y `--influencias` (`enum,etiqueta`)
pasan verbatim a `ARBITRIO_USO`/`ARBITRIO_INFLUENCIA`; toda tasa cuyo `uso_codigo`/`ubicacion` no esté en esas
columnas no encaja. `--habitantes SERVICIO=P:V` (repetible) escribe `HABITANTES_PROMEDIO`/`HABITANTES_VARIACION` por
cada `servicio:zona:uso` de casa habitación presente para ese servicio (su columna `uso` contiene «casa
habitación», sin acentos); una fila de casa habitación sin zona no tiene cómo repartirse "en toda zona del
servicio", así que no encaja. `--redondeo` escribe la fila global; `--vencimientos` escribe las 12
`ARBITRIO_VENCIMIENTO` a partir de 12 fechas (una por mes) o 4 (trimestral: cada una cubre sus 3 meses).
`--dry-run` imprime la cantidad por tipo y no escribe nada: `--salida` es obligatorio sin `--dry-run`, y no hace
falta con él. Los códigos de salida no cambian con `--dry-run`.

`ARBITRIO_AFLUENCIA` **no se deriva aquí a propósito**: mapea el uso propio de un predio a su nivel de afluencia —
dato del predio, no de la ordenanza — y esta tabla no lo trae.

Sale con `2` (nada escrito) si un servicio de una fila no está en `--servicios`, una base no se reconoce, una tasa
se repite, un uso o una ubicación no tienen mapeo, una fila de casa habitación no tiene zona bajo `--habitantes`,
`--vencimientos` no trae 12 ni 4 fechas, o el CSV construido no pasa `import_parametros.errores`.

**Cuidado al cargar `ARBITRIO_ZONA`, `zona_arbitrio_ubicacion` y `ARBITRIO_AFLUENCIA` aparte.** Estos tres no los
escribe `import_tasas_arbitrio.py` (zona y ubicación son del predio, no de la tabla de tasas); quien los carga
—a mano o con `import_zonas_arbitrio.py`— **debe escribir el mismo texto normalizado que las `TASA_ARBITRIO`
usan en su clave**: sin acentos, en mayúsculas, lo que no es letra ni número vuelto `_` («Zona 1» → `ZONA_1`,
«Área 0» → `AREA_0`, «Muy alta» → `MUY_ALTA`). Una zona o una afluencia escrita como la ordenanza la imprime
(«Zona 1», «Muy alta») no calza con la clave de ninguna `TASA_ARBITRIO`, y el predio entero falla con un
`TASA_ARBITRIO` en `faltan` — sin ningún otro aviso.

`model/data/arbitrios/ficticio-parametros.csv` es exactamente lo que `import_tasas_arbitrio.py` produce de
`ficticio-tasas.csv`, `ficticio-usos.csv` y `ficticio-influencias.csv` (un contrato cruzado: `ArbitriosTest.kt`
lee este mismo archivo, y `test_import_tasas_arbitrio.py` lo regenera y compara byte a byte). Para regenerarlo:

```bash
cd model
python3 import_tasas_arbitrio.py --tasas data/arbitrios/ficticio-tasas.csv \
    --servicios BARRIDO_CALLES=BAR,RECOLECCION_RESIDUOS=RRS,PARQUES_JARDINES=PYJ,SERENAZGO=SER \
    --usos data/arbitrios/ficticio-usos.csv --influencias data/arbitrios/ficticio-influencias.csv \
    --vigencia-desde 2041-01-01 --norma "Ordenanza ficticia 000-2041-MDP" --fuente test \
    --transcribio "ANA, 2041-01-05" --verifico "BETO, 2041-01-09" --redondeo 2:ABAJO \
    --habitantes RECOLECCION_RESIDUOS=4:0.3 --vencimientos 2041-02-28,2041-05-30,2041-08-29,2041-11-30 \
    --salida data/arbitrios/ficticio-parametros.csv
```

**Tasas reajustadas por IPC, sin ordenanza con cuadro** (art. 69-B del TUO de la Ley de Tributación Municipal;
art. 8 de la Ord. 2386-MML, #88). Muchas ordenanzas que reajustan sus tasas del año anterior por el IPC no
publican ningún cuadro, solo el porcentaje (a veces ni eso: Arequipa 2026 no lo dice). `derivar_ipc_arbitrio.py`
deriva el CSV del año siguiente desde el del año base, sin teclear ninguna cifra:

```bash
cd model
python3 derivar_ipc_arbitrio.py --csv parametros-2026.csv --desde 2026 --ipc 2.10 \
    --norma "OM 012-2027-MDP" --redondeo 4:MEDIO \
    --transcribio "CARLA, 2027-01-05" --verifico "DIEGO, 2027-01-09" --salida parametros-2027.csv
```

Toma, de `--csv`, la fila de cada `(tipo, clave)` de `TIPOS_ARBITRIO` con la `vigencia_desde` más reciente que no
pase el 31/12 de `--desde`, entre las que siguen vigentes ese día (una fila que empieza después de esa fecha, o
cuya `vigencia_hasta` es anterior, se ignora), y escribe cada una para el año siguiente, con `vigencia_desde` el 01/01 de ese año:
- `TASA_ARBITRIO`: su `valor_numerico` × (1 + `--ipc` / 100), redondeado con `--redondeo` (`decimales:modo`, el
  mismo `ABAJO`/`MEDIO`/`ARRIBA` de `REDONDEO_ARBITRIO`); su `texto` queda `DERIVADA IPC <ipc>% de <clave> <año
  base>`. Ejemplo de Miraflores 2026 con IPC 2,10 % y `4:MEDIO`: `2.5428` → `2.5962` (`2.5428 × 1.021 =
  2.5961988`).
- Toda otra fila (`ARBITRIO_ZONA`, `ARBITRIO_USO`, `ARBITRIO_INFLUENCIA`, `ARBITRIO_AFLUENCIA`, `BASE_ARBITRIO`,
  `DIMENSIONES_ARBITRIO`, `HABITANTES_PROMEDIO`/`HABITANTES_VARIACION`, `REDONDEO_ARBITRIO`, `TOPE_ARBITRIO`) se
  copia tal cual (mismo `valor_numerico` y `texto`), salvo el `texto` de `ARBITRIO_VENCIMIENTO` y el de
  `TOPE_ARBITRIO` cuando es una fecha: ambos se corren un año (29 de febrero → 28 de febrero si el año siguiente
  no es bisiesto). `REDONDEO_ARBITRIO` se copia con su propio modo; `--redondeo` solo redondea las tasas de este
  reajuste, no la reemplaza.
- Toda fila derivada cita la fila base (su `clave` y el año base, en `fuente`) y lleva la `norma`, `transcribio`
  y `verifico` del propio reajuste, no los de la fila base: dos firmas distintas por fila, exigidas por
  `import_parametros.errores`.

`--redondeo` es **obligatorio**: ninguna de las ordenanzas revisadas publica cómo redondea un reajuste por IPC.
Sale con `2` (nada escrito) si falta `--redondeo` o no es `decimales:modo`, si `--ipc` no es un número, o si el
CSV construido no pasa `import_parametros.errores`.

**La ordenanza, los servicios y las inafectaciones** se cargan con `import_arbitrios.py`, desde un JSON:

```json
{"ordenanzas": [{"anio": 2026, "numero": "…", "fecha_publicacion": "…", "acuerdo_ratificacion": "…",
                 "fecha_ratificacion": "…", "municipalidad_ratificante": "…"}],
 "servicios": [{"codigo": "…", "nombre": "…", "orden": 1, "vigencia_desde": "2026-01-01", "ordenanza": 2026}],
 "inafectaciones": [{"predio": "<codigo del predio>", "servicio": "<codigo del servicio>", "vigencia_desde": "…",
                     "motivo": "…", "observacion": "…"}]}
```

```bash
cd model
python3 import_arbitrios.py --archivo arbitrios-2026.json --dry-run   # lee Core y dice qué haría, sin escribir
python3 import_arbitrios.py --archivo arbitrios-2026.json
```

`import_arbitrios.py`:
- un servicio nombra su ordenanza por el año, y una inafectación nombra su predio y su servicio por el código;
- antes de la primera escritura revisa el archivo (campos obligatorios, fechas, vigencias, códigos repetidos,
  observación de 5 a 500 caracteres) y que existan los predios, los servicios y las ordenanzas que nombra;
- es idempotente con claves naturales: la ordenanza por `anio`, el servicio por `codigo`, la inafectación por predio,
  servicio y `vigencia_desde`; actualiza en su lugar lo que cambió y no borra nada;
- sale con `0` si todo va bien, `1` si Core rechaza algo y `2` si el archivo no encaja (sin escribir nada).

**Zona por servicio y por ubicación.** La zona de un servicio, cada día, la decide `srtm.arbitrios.Zonas.de` en seis
niveles, de más a menos preciso; gana el primero con alguna fila vigente que coincida (varias filas de ese nivel que
coincidan en la zona la ganan igual; si no coinciden, la zona es ambigua y el predio entero falla, nombrada en
`faltan`):
1. `zona_arbitrio_ubicacion` del servicio, con la manzana del predio.
2. `zona_arbitrio_ubicacion` del servicio, sin manzana (vacía).
3. `zona_arbitrio_ubicacion` de todos los servicios (`servicio` vacío), con la manzana del predio.
4. `zona_arbitrio_ubicacion` de todos los servicios, sin manzana.
5. `ARBITRIO_ZONA servicio:sector_catastral`.
6. `ARBITRIO_ZONA sector_catastral`.

Una fila de `zona_arbitrio_ubicacion` coincide cuando está vigente ese día, su `via` es la del predio (sin distinguir
mayúsculas ni tildes), su `tipo_via` está vacío o es el del predio (comparado igual que la vía), el número del predio cae en su tramo
(`numero_inicial`/`numero_final`, extremos vacíos abiertos) y su `paridad` es `AMBOS`, vacía o la del número. Sin
ninguna fila que coincida en los cuatro niveles de ubicación, cae al sector catastral (niveles 5 y 6); sin sector
catastral tampoco, la zona de ese servicio no se resuelve, falta «Sector catastral del predio …» y **el predio entero
no se determina** (todo o nada), aunque otros servicios del predio sí resuelvan por ubicación. Un predio sin sector
se determina solo cuando cada uno de sus servicios resuelve su zona por ubicación.

Se carga con `import_zonas_arbitrio.py`, desde un CSV con las columnas
`servicio,tipo_via,via,manzana,numero_inicial,numero_final,paridad,zona,vigencia_desde,vigencia_hasta,norma,observacion`
y una cabecera `#` con la fuente; `servicio` vacío aplica a todos, `manzana` vacía no filtra por manzana, `tipo_via`
vacío no filtra por tipo de vía y `vigencia_hasta` vacía sigue vigente. **La columna `zona` debe traer el mismo
texto normalizado que las `TASA_ARBITRIO` de ese servicio** (ver la advertencia de arriba, en **Tasas de una
ordenanza**): `import_zonas_arbitrio.py` no normaliza nada, solo lo versiona tal cual se le da.

```bash
cd model
python3 import_zonas_arbitrio.py --csv zonas-2026.csv --dry-run   # lee Core y dice qué haría, sin escribir
python3 import_zonas_arbitrio.py --csv zonas-2026.csv
```

`import_zonas_arbitrio.py`:
- la clave natural es `servicio|tipo_via|via|manzana|numero_inicial|numero_final|paridad|vigencia_desde` (la misma
  fórmula de `claveDeZonaUbicacion`, trasladada a Python); cambiar cualquiera de esos campos es un tramo nuevo, no
  una fila reemplazada;
- antes de la primera escritura revisa el archivo: campos obligatorios, `numero_inicial` y `numero_final` enteros
  con `numero_inicial <= numero_final`, `paridad` del enum, fechas ISO, y que dos filas que un mismo predio podría
  encontrar en el mismo nivel no tengan distinta zona con vigencias que se cruzan (la ambigüedad que `Zonas.de`
  encontraría al calcular): mismo servicio (o ambas de todos), misma manzana (o ambas sin manzana), la misma vía y
  un `tipo_via` igual o vacío en alguna (las dos comparadas como `Zonas`: sin espacios en los extremos, sin
  distinguir mayúsculas ni tildes), paridades iguales o alguna `AMBOS`, y tramos que se solapan. `AV LIMA` y
  `JR LIMA` no chocan; `PAR 200-298` y `AMBOS 200-298`, o `AV X` y `Av x`, sí;
- es idempotente: crea lo que falta, actualiza en su lugar `zona`, `vigencia_hasta`, `norma` y `observacion` (lo que
  no es parte de la clave) y no borra nada;
- sale con `0` si todo va bien, `1` si Core rechaza algo y `2` si el archivo no encaja (sin escribir nada).

**Compatibilidad.** Sin ninguna fila de `zona_arbitrio_ubicacion`, los cuatro primeros niveles no tienen candidatas y
`Zonas.de` cae directo al sector catastral: un predio de un solo uso determina las mismas cuotas que antes de este
cambio.

**Cálculo** (`Arbitrios.determinar`, de un predio y un año):
- **Del año:** hace falta la ordenanza del año ratificada (acuerdo y fecha) y al menos un servicio vigente. Si no,
  `faltan` lo dice y no se determina ningún predio.
- **Cada mes a su fecha.** El mes *m* se decide el día 1 de *m* (`Periodo.atribucion`), no el día en que se corre:
  - **Titular:** de las DJ del año que cubren ese día, la del titular principal. Gana el mayor `porcentaje_condominio`;
    en empate, la `fecha_adquisicion` más antigua, luego el `codigo` de contribuyente menor y luego la secuencia de uso
    menor (leída como la numera el padrón: `1` es `001`). Se le cobra íntegro, sin prorrateo: es el contribuyente de la cuota de cada secuencia ese mes.
  - **Qué días cubre una DJ:** desde el 1 de enero, o desde el día siguiente a su `fecha_adquisicion` si cae en el año;
    hasta el 31 de diciembre, o hasta su `fecha_anulacion` si se anuló (descargo). Una importada sin fechas cubre el
    año. Una venta el 20 de mayo da: vendedor de enero a mayo, comprador de junio a diciembre.
  - **Zona:** la de cada servicio ese día, por `Zonas.de` (ver **Zona por servicio y por ubicación** arriba):
    `zona_arbitrio_ubicacion` por vía y tramo primero, `ARBITRIO_ZONA` del `sector_catastral` del predio como
    respaldo. Es la del predio: la misma para todas sus secuencias.
  - **Un PU por uso** (decisión del 2026-10-05), desde el año que fija `ARBITRIO_PU_POR_USO` (#80): cada secuencia
    de uso de las DJ que cubren el día se cobra aparte (`porSecuencia`: la de su propio titular principal, para un
    condómino de esa secuencia), con su propio uso de arbitrio, área, frontis y habitantes. `ARBITRIO_USO` del código
    de uso de cada DJ (tan preciso como la DJ: `XXYYZZ` con clase, sub clase y uso; `XXYY` sin uso; `XX` con solo la
    clase; gana el prefijo más largo). Un predio de un solo uso tiene, como antes, una sola secuencia.
  - **Un PU por predio** en un año anterior a ese, o sin la fila: como antes del PU por uso, cada mes se cobra solo la
    secuencia de la DJ del titular principal, con su uso, área, frontis y habitantes; las demás secuencias no se
    miran. La cuota guarda igual su secuencia en la clave de 6 partes. Un mes que ya tiene una cuota del servicio,
    de cualquier secuencia, ya está cobrado: si después cambia la DJ del titular principal (se rectifica una
    participación o una adquisición), no se cobra un segundo PU. Un predio de un solo uso tiene las mismas cuotas en
    los dos modos.
  - **Servicios:** cada servicio vigente ese día, salvo que el predio tenga una inafectación suya vigente ese día o
    que la cuota de esa secuencia ya exista. El monto es `tasa_unitaria × cantidad_base × (1 + (H − P) × V)`
    (`Calculo.calcular`): `tasa_unitaria` es `TASA_ARBITRIO servicio` más una parte por cada `Dimension` de
    `Servicios.dimensiones` (`zona:uso` por defecto); `cantidad_base` (`BASE_ARBITRIO` del servicio) es 1 por
    predio, o el frontis en ml o el área construida en m² de la DJ de la secuencia; el factor de habitantes
    (`HABITANTES_PROMEDIO`/`HABITANTES_VARIACION` de `servicio:zona:uso`) es 1 sin esas filas, o cuando la DJ no
    declara `numero_habitantes` (se presume el promedio, H = P). El monto se redondea al final según
    `REDONDEO_ARBITRIO` (`decimales:modo`, global a la ordenanza); sin `BASE_ARBITRIO`, habitantes ni redondeo es
    la tasa mensual tal cual, igual que antes de este cálculo. `parametro_aplicado` guarda las llaves exactas que se
    leyeron, separadas por `;`; sin `DIMENSIONES_ARBITRIO` es solo la de la tasa, la de siempre. `Calculo.formula`
    imprime la misma cuenta en texto, como la ordenanza la lee: `"5.0856 S/ por ml × 10.50 ml = 53.39"`,
    `"0.0514 S/ por m² × 100.00 m² × (1 + (4 − 2) × 0.50) = 10.28"` o, por predio, `"85.9875 S/ por predio"` (con
    `= monto` solo si hubo redondeo); una cuota de antes del desglose (`base` null) es solo su `monto`.
  - **Influencia y afluencia** (`Dimensiones.influencia`/`afluencia`, cuando el servicio las lista): la influencia es
    `ARBITRIO_INFLUENCIA` de la `ubicacion_area_verde` del predio; sin esa ubicación, o sin su fila, falta y el predio
    entero no se determina (todo o nada, igual que una zona o un uso que falta). La afluencia es `ARBITRIO_AFLUENCIA`
    del código de uso, con la misma regla del prefijo más largo que `ARBITRIO_USO`, pero primero por su servicio
    (`servicio:prefijo`, si existe) antes de caer a uno general (`prefijo`).
- **Meses sin cobro:** un mes sin titular, o una secuencia sin zona (por sector o por ubicación) o sin uso ese mes, no
  se cobra. Un predio que aparece en julio no debe de enero a junio.
- **No se puede determinar** un predio sin titular en ningún mes, o sin zona y uso a la vez en ninguna secuencia de
  ningún mes con titular. En un año de un PU por uso, una secuencia que nunca tuvo uso del catálogo, en ningún mes
  (aunque otra secuencia del mismo predio sí lo tenga), también hace fallar el predio entero, nombrada con su número
  cuando hay varias (`uso 002`); en uno de un PU por predio, esa secuencia no se mira.
- **Todo o nada:** si falta un parámetro (una zona, un uso, una tasa, una influencia o una afluencia) de cualquier
  secuencia, no se determina nada de ese predio. `faltan`
  nombra cada uno una vez (`TASA_ARBITRIO LIMPIEZA:Z1:CASA 2026`).
- **Reejecutar no duplica:** las cuotas que ya existen, de cada secuencia, no se recalculan.
- **La clave** de cada cuota es `predio|servicio|anio|periodo|secuencia|version` (ver **Modelo** arriba): un predio
  de un solo uso tiene, como antes de este cambio, una cuota por servicio y mes, con el mismo `monto` y el mismo
  `parametro_aplicado` de siempre; uno de varios usos tiene una por secuencia, y cada una se calcula con su propio
  uso de arbitrio, su base y su tasa, así que tiene su propio `monto` y su propio `parametro_aplicado`.

**Tope de incremento** (`TOPE_ARBITRIO`, #87). Muchas ordenanzas limitan lo que un predio paga en un año a lo que
pagó el anterior más un porcentaje (Miraflores 2026: 8 % en casa habitación, 18 % en otros usos; la diferencia la
asume la municipalidad). Al final de `Arbitrios.determinar`, con todas las cuotas del predio ya calculadas:
- **El grupo** es el código de uso de la secuencia menor del predio (en un año de un PU por predio, la secuencia de
  la DJ del titular principal, la que se cobra; el primer mes que lo tiene): su `TOPE_ARBITRIO`
  es la fila del prefijo más largo, o la de `*` si ninguno coincide. Sin fila, no hay tope.
- **Sin tope** también si el predio no tiene cuotas vigentes del año anterior (no anuladas, la última versión de
  cada una, de todos los servicios), o si esas cuotas suman 0 (no pagó nada: un límite de 0 dejaría todo el año en
  0), o si se registró después de la fecha de corte (`texto`). **Registrado** es cuando el predio entró al padrón: la
  fecha más antigua de sus declaraciones **de cualquier año**, no la de la DJ de este año; cada DJ cuenta con su
  `fecha_presentacion` o, si no la tiene (una DJ migrada), con el 1 de enero de su `anio`. Un predio cuya primera DJ
  es de 2024, que pagó en 2025 y presentó su DJ de 2026 en febrero de 2026, con corte 2025-12-31, tiene tope; uno
  con DJ migradas de 2020 a 2025 sin fecha y la de 2026 presentada el 1 de febrero, también (registrado el
  2020-01-01); uno cuya primera DJ es de enero de 2026, no.
- **Si no**, `límite = suma del año anterior × (1 + tope / 100)`. Si las cuotas vigentes de este año ya escritas más
  las nuevas lo superan, **solo las nuevas** bajan en proporción, hasta `límite − ya escritas` (nunca por debajo de
  0): cada una se redondea con su propio redondeo (`2:ABAJO` si no tiene), la diferencia del redondeo va a la última
  (en el orden en que se determinaron: mes, secuencia, servicio) sin que ninguna pase de lo que era, y la suma
  queda exacta. Cada nueva guarda `monto_sin_tope` (lo de antes del tope, que es lo que cuadra con su desglose),
  `tope_aplicado` (el %) y `TOPE_ARBITRIO:<clave>` en `parametro_aplicado`; `Calculo.formula` termina en
  `"= 33.33, con tope de 8 %: 29.99"`.
- **Ejemplo** (cifras ficticias, `TopeTest`): un predio de casa habitación que pagó 1 000.00 en 2025 y debería pagar
  1 200.00 en 2026 (100.00 al mes en tres servicios: 33.33 + 33.33 + 33.34), con `TOPE_ARBITRIO 0101` de 8 %, paga
  1 080.00: cada cuota × 1 080 / 1 200, redondeada hacia abajo (29.99, 29.99 y 30.00 al mes, 1 079.76 en el año), y
  los 0.24 que faltan van a la última de diciembre (30.24). Si los primeros 6 meses ya estaban escritos (600.00),
  las 18 nuevas suman 480.00.
- **La última** es la última en el orden en que se determinan las cuotas: por mes, luego por secuencia y luego por
  servicio (su `orden`); no el orden alfabético de la `clave`, que pondría el mes 9 después del 12.
- `ArbitriosService` lee las cuotas del año anterior (con sus anulaciones) y la fecha de registro (dos filas,
  `Registros.lowest`: la `fecha_presentacion` más baja de las declaraciones del predio y el `anio` más bajo de las que
  no la tienen; `Arbitrios.registrado` toma la menor) solo cuando el año tiene alguna
  fila `TOPE_ARBITRIO` vigente: sin ella no hay lecturas nuevas y las cuotas son las de siempre (`monto_sin_tope` y
  `tope_aplicado` vacíos).

**Escritura.** Las cuotas de un predio se escriben en una transacción: todas o ninguna. Si otra determinación del mismo
predio escribe alguna entre la lectura y la escritura (la `clave` es única), no queda nada de esta: se lee y se calcula
otra vez, una sola vez, y lo que la otra escribió ya no se recalcula.

**Índices.** Core indexa solo `organization_id`, el estado y los campos únicos, y un año trae de 540 000 a 900 000
cuotas. El modelo declara dos índices en `cuota_arbitrio` (`indexes` de `model/model.json`): `(anio, predio)` y
`(anio, contribuyente)`. Core los crea en la tabla física de cada organización, también en una que se crea después
(wasichai 0.3). Medido en el CI sobre 505 000 filas: listar un predio pasa de 62 ms a 3,3 ms.

**API** (`/api/srtm`, JSON en snake_case; sin `anio`, el año en curso):

| Método | Ruta | Responde |
|---|---|---|
| GET | `/arbitrios/servicios?anio` | Los servicios vigentes algún día del año, en su orden. |
| GET | `/arbitrios/parametros?anio` | La ordenanza, los servicios y las filas de la ordenanza vigentes en el año, con `faltan`. Siempre 200. |
| GET | `/arbitrios?anio&predio&contribuyente&servicio&page&size` | Una página de cuotas. Un parámetro desconocido o mal formado es **422** y lo nombra en `errors` (nunca un filtro ignorado). |
| GET | `/predios/{id}/arbitrios?anio` | La matriz servicio × mes, el titular de cada mes según la regla, los totales por servicio, por mes y del año, la `fecha_calculo` de la última cuota, cuántas cuotas agregaría determinar ahora (`pendientes`) y qué falta para hacerlo (`faltan`, y lo mismo con su predio, su declaración cuando la tiene y dónde se arregla: `faltan_detalle: [{mensaje, predio, declaracion, enlace}]`; `enlace` es `"DECLARACION"` cuando lo arregla la DJ —su uso, frontis o área construida—, `"PREDIO"` cuando lo arregla la ubicación del predio —su sector catastral, una zona por ubicación ausente o ambigua, su ubicación respecto del área verde— y `null` cuando no lo arregla ninguna de las dos: una fila de la ordenanza aunque nombre el sector o el uso del predio —`TASA_ARBITRIO`, `ARBITRIO_ZONA`, `ARBITRIO_USO`, `ARBITRIO_INFLUENCIA`, `ARBITRIO_AFLUENCIA`, `TOPE_ARBITRIO`—, un titular que ninguna DJ cubre, un factor de habitantes negativo o una invariante). Cada celda lleva su `desglose`, una entrada por secuencia de uso (`secuencia_uso`) con `id`, `monto`, `base`, `cantidad_base`, `tasa_unitaria`, `habitantes`, `promedio_habitantes`, `variacion_habitante`, `habitantes_presuntos`, `zona`, `uso_arbitrio`, `influencia`, `afluencia` y `formula`, la cuenta legible (`Calculo.formula`): `"0.0514 S/ por m² × 100.00 m² × (1 + (4 − 2) × 0.50) = 10.28"`, `"5.0856 S/ por ml × 10.50 ml = 53.39"` o, por predio, `"85.9875 S/ por predio"`; una cuota de antes del desglose (`base` null) solo trae `soles(monto)`. |
| GET | `/contribuyentes/{id}/arbitrios?anio` | Lo mismo por cada predio de sus DJ del año (o del que tiene cuotas), solo con las cuotas a su nombre, y su total. |
| POST | `/predios/{id}/arbitrios` `{anio, observacion}` | **201** con las cuotas escritas; **200** con `[]` si no había nada pendiente; **422** con `faltan` y `faltan_detalle`; **400** si la observación no tiene de 5 a 500 caracteres; **403** sin permiso de creación sobre `cuota_arbitrio`. |
| POST | `/contribuyentes/{id}/arbitrios` `{anio, observacion}` | Determina cada predio de sus DJ del año (también una anulada: puede cubrir los meses previos a una venta). Calcula todos antes de escribir: si uno no se puede determinar, **422** que lo nombra (`Predio <código>: …`, también en `faltan_detalle`) y no escribe ninguno. |

**Determinación masiva** (`/api/srtm/arbitrios/determinaciones`). Determina todos los predios de un año en segundo
plano, sobre la misma maquinaria de lotes de la [emisión masiva](#emisión-masiva):
- **POST `{anio, observacion}` → 202** con el job PENDIENTE. Antes de crear nada responde:
  - **403** si quien la lanza no tiene creación y edición sobre `determinacion_arbitrio_masiva` y creación sobre
    `determinacion_arbitrio_lote` y `cuota_arbitrio`;
  - **400** si la observación no tiene de 5 a 500 caracteres;
  - **422** con `faltan` si el año no tiene su ordenanza ratificada, sus servicios o filas de tasas, zonas y usos;
  - **409** si ya hay una del año en curso en la organización.
- **Preparación.** En segundo plano y como quien la lanzó, toma los predios con alguna DJ del año (también anulada: puede
  cubrir los meses previos a una venta), los ordena por código y los corta en lotes de `srtm.emision.lote`
  (`determinacion_arbitrio_lote`). El job pasa a EN_PROCESO.
- **Trabajadores.** Los mismos de la emisión toman sus lotes (`TrabajoDeterminacion`), turnándose con los de la emisión:
  ninguna espera a que la otra termine.
  - Un lote se procesa como el usuario que lo creó, con la ordenanza, los servicios, los parámetros y los usos del año
    leídos una vez por lote.
  - Cada predio se determina y se escribe como en el POST de su ficha, en su propia transacción.
  - Un predio que no se puede determinar va a `errores` (`[{predio, mensaje}]`, por su código) y el lote sigue.
  - Una cuota que ya existía no se vuelve a escribir, así que un lote retomado tras vencer su lease vuelve a empezar
    sin duplicar nada.
  - Toda escritura del lote exige su toma (`tomado_por` + `intentos`).
- **Cierre.** Cuando todos sus lotes terminaron, el job pasa a **TERMINADA** con `procesados` (predios), `generadas`
  (cuotas) y `errores`. No genera archivo, así que no pasa por ENSAMBLANDO. Mientras corre, `generadas` suma las de
  sus lotes terminados.
- **Fallos.** Igual que en la emisión: un lote que agota `srtm.emision.intentos` queda FALLIDO con todos sus predios en
  `errores`; si su creador ya no existe o está deshabilitado, también; un job sin quien lo prepare pasa a FALLIDA.
- **GET** lista (`?anio`) y **GET** `/{id}` devuelven el job. **DELETE** `/{id}` cancela sus lotes y lo borra (204);
  las cuotas que escribió quedan, porque una cuota no se borra.

**Cómo activar el PU por uso** (#84, #80). Sin la fila `ARBITRIO_PU_POR_USO`, todos los años pagan un PU por
predio, como antes del PU por uso: un año ya determinado no tiene nada `pendiente`, la HLA de sus contribuyentes se
emite sin volver a determinar y una nueva determinación no agrega cuotas de las secuencias distintas de la que se
cobra. Las cuotas
de antes (sin `secuencia_uso`) cuentan como de la secuencia que se cobra ese mes, así que no se duplican. Para pasar
a un PU por uso, se carga con `import_parametros.py --csv` una fila con el 1 de enero del primer año que lo aplica:

```csv
tipo,clave,vigencia_desde,vigencia_hasta,valor_numerico,texto,norma,fuente,transcribio,verifico
ARBITRIO_PU_POR_USO,,2027-01-01,,1,,<ordenanza o acuerdo que lo dispone>,<archivo>,<nombre>,<otro nombre>
```

Desde ese año, cada secuencia de uso tiene su PU; los años anteriores siguen con uno por predio. Si la fila rige un
año ya determinado, las secuencias distintas de la que se cobra de sus predios de varios usos quedan `pendientes` (y
su HLA da 422)
hasta volver a determinarlo: lo seguro es elegir un año que aún no se haya determinado. El tope de incremento del
primer año por uso se mide contra lo pagado el año anterior, con un PU por predio.

**HLA** (hoja de liquidación de arbitrios, `GET /api/srtm/contribuyentes/{id}/hla?anio`, PDF inline como la HR).
- **Qué imprime:** las cuotas a nombre del contribuyente tal como se determinaron, con las mismas cifras que
  `GET /contribuyentes/{id}/arbitrios`:
  - por predio, la zona y el uso, los meses con cuota por servicio y sus totales;
  - por predio, el cálculo (`PredioHla.calculos`): una línea por secuencia de uso, servicio y `Calculo.formula`
    distintos del año, con el rango de meses en que rigió (abreviado a 3 letras, `ene`, `feb`… `dic`; un solo mes
    va solo, `mar`, y un rango junta los dos extremos con un guion en, `ene–dic`), por ejemplo
    `"Recolección (uso 001), ene–dic: 0.0514 S/ por m² × 100.00 m² × (1 + (4 − 2) × 0.50) = 10.28"`. La misma
    fórmula que vuelve a aparecer sin ser contigua (una tasa que cambió y volvió) imprime una línea por tramo. Una
    cuota de antes del desglose (`base` null) no aporta ninguna línea: la HLA se ve como antes de este cálculo;
  - las cuotas mensuales con su vencimiento (`ARBITRIO_VENCIMIENTO` de cada mes, vigente al día 1);
  - el total del año, la fecha de cálculo y la ordenanza con su ratificación.
- **Usa** la cabecera de los documentos (`templates/emision/hla.html`).
- **422 con `faltan`** si:
  - el año no tiene su ordenanza ratificada;
  - un predio del contribuyente tiene cuotas por determinar, o no se puede determinar;
  - un mes con cuota no tiene vencimiento.
- **404** si no hay ninguna cuota a su nombre en el año (un condómino que no es el titular principal).
- **En la emisión masiva:** el `POST /api/srtm/emisiones` acepta `documentos` (de `HR`, `PU`, `HLA`; por defecto
  `["HR","PU"]`, como antes). Con la HLA:
  - **422 con `faltan`** antes de crear nada si al año le falta lo que toda HLA necesita (lo de
    `/arbitrios/parametros` y los vencimientos de los 12 meses);
  - la HLA va después de la HR y los PU de cada contribuyente (`<codigo>-<nombre>/HLA-<anio>.pdf` en el ZIP), con la
    ordenanza y los parámetros leídos una vez por lote;
  - un contribuyente cuya HLA no se puede hacer **sigue con su HR y sus PU** y queda en `errores` como
    `HLA: <por qué>`;
  - uno sin cuotas a su nombre no lleva HLA, y no es un error.

**Anulación** (`POST /api/srtm/arbitrios/cuotas/{id}/anulacion` `{motivo, observacion}` → 201). Así se corrige
una cuota, en lugar de editarla:
- se agrega una `anulacion_cuota_arbitrio`, que tampoco se edita ni se borra (409 por cualquier vía);
- la cuota anulada deja de contar en la matriz, en los totales y en la HLA, y solo su secuencia vuelve a quedar
  pendiente: las demás secuencias del mismo servicio y mes no se tocan;
- la siguiente determinación del predio la escribe de nuevo con la versión siguiente de su `clave`
  (`…|001|2`, `…|001|3`: la misma secuencia, otra versión), con la tasa, la zona, el uso y el titular vigentes
  entonces;
- una cuota se anula una sola vez (**409**);
- `motivo` es obligatorio, y la observación va de 5 a 500 caracteres (**400**);
- pide creación sobre `anulacion_cuota_arbitrio` (**403**).

La consulta paginada (`GET /arbitrios`) lista las cuotas tal como se escribieron, también las anuladas: la matriz, los
totales y la HLA son los que las excluyen.

**Permisos.** Consultar pide lectura de `cuota_arbitrio`, `servicio_arbitrio`, `ordenanza_arbitrio`,
`inafectacion_arbitrio` y `parametro_tributario`. Determinar pide, además, creación sobre `cuota_arbitrio`. Nadie
actualiza ni borra cuotas ni anulaciones; anular pide creación sobre `anulacion_cuota_arbitrio`.

**Ninguna cifra inventada.** Todavía no hay una transcripción verificada de la ordenanza de arbitrios de Perené y su
ratificación (E-6 del plan de desbloqueo D-02 de `normativa`). Hasta que la haya, el repo no lleva ni el JSON ni el CSV
de tasas, y los tests usan valores ficticios escritos dentro del propio test.

## Brecha de recaudación

Mide cuánto deja de recaudar el padrón: compara el autoavalúo **declarado** (las DJ de srtm) con el **valorizado** por
catastro-backend (aranceles, valores unitarios y depreciación del levantamiento catastral) y recalcula con él el
impuesto predial y los arbitrios. catastro-backend solo valoriza; el impuesto, la brecha y el tablero son de srtm
(`srtm.brecha`). Reusa las reglas de srtm, sin copiarlas: `totalesDePredio`, `condominio`, `ImpuestoPredial.liquidar`
y `Arbitrios.determinar`.

**Configuración.** srtm lee `GET {url}/api/catastro/valuaciones?anio&page&size=500` (todas las páginas) como la
cuenta de servicio `srtm` de catastro-backend (rol `SRTM_LECTOR`, la crea su `model/crear_cuenta_srtm.py`). El token
sale de `POST {url}/api/auth/token {clientId, clientSecret}` y se guarda hasta un minuto antes de `expiresAt` (o
hasta un 401). Apagado (`SRTM_CATASTRO_ENABLED`), o sin ninguna organización con url y cuenta, una corrida no
empieza: **409** «Catastro no configurado». Si la cuenta no puede leer alguno de los
cuatro objetos del catastro, catastro responde 403 a todo y la corrida queda FALLIDA con ese estado. Si catastro no
devuelve ningún ítem del año y el padrón tiene predios, la corrida queda FALLIDA («El catastro no devolvió valuaciones
de 2026…») en vez de dar todo el padrón por SIN_CATASTRO. Los importes se leen como decimales, nunca como `double`.

**Un catastro por organización.** Cada organización de srtm lee su propio catastro-backend: el mapa
`srtm.catastro.organizaciones.<slug-o-id>` con `url`, `client-id`, `client-secret` y, opcional, `timeout` (si no, el
de `srtm.catastro.timeout`). srtm guarda un cliente y un token por organización, y una corrida usa el de la
organización que la lanza. La clave casa como antes: si es un id (UUID), con el id de la organización; si no, con su
slug, sin distinguir mayúsculas. Por variables de entorno (relaxed binding de Spring) cada propiedad es
`SRTM_CATASTRO_ORGANIZACIONES_<CLAVE>_<PROPIEDAD>`; la clave llega en minúsculas y no puede llevar guiones ni guiones
bajos (`_SAN_RAMON_URL` sería la clave `san`), así que un id o un slug con guion o guion bajo va en `organizacion`, bajo
una clave cualquiera. Dos organizaciones, Perené por su slug e Ica por su id:

```bash
SRTM_CATASTRO_ENABLED=true
SRTM_CATASTRO_ORGANIZACIONES_PERENE_URL=http://catastro-perene:8091
SRTM_CATASTRO_ORGANIZACIONES_PERENE_CLIENT_ID=<clientId de la cuenta srtm del catastro de Perené>
SRTM_CATASTRO_ORGANIZACIONES_PERENE_CLIENT_SECRET=<clientSecret>
SRTM_CATASTRO_ORGANIZACIONES_ICA_ORGANIZACION=<id de la organización de srtm de Ica>
SRTM_CATASTRO_ORGANIZACIONES_ICA_URL=http://catastro-ica:8091
SRTM_CATASTRO_ORGANIZACIONES_ICA_CLIENT_ID=<clientId de la cuenta srtm del catastro de Ica>
SRTM_CATASTRO_ORGANIZACIONES_ICA_CLIENT_SECRET=<clientSecret>
SRTM_CATASTRO_ORGANIZACIONES_ICA_TIMEOUT=90s
```

La forma de antes, de una sola organización (`SRTM_CATASTRO_URL`, `_CLIENT_ID`, `_CLIENT_SECRET`, `_TIMEOUT` y
`SRTM_CATASTRO_ORGANIZACION` con su slug o su id), sigue valiendo: es una entrada más del mapa, y una entrada del mapa
que nombra igual a la misma organización (las dos por su slug o las dos por su id) la reemplaza. Sin
`SRTM_CATASTRO_ORGANIZACION` esa cuenta no es de nadie y no se usa. Una entrada sin url, client-id o client-secret se
salta (con un aviso en el log al arrancar). Una organización sin catastro responde **409** `SIN_CATASTRO_CONFIGURADO`
(«Catastro no configurado para esta organización: fije srtm.catastro.organizaciones.<slug o id>…»), y una nombrada dos
veces (por su slug y por su id, o por dos entradas del mapa) **409** «Catastro configurado dos veces para esta
organización», con las claves que la nombran: nunca se cruza el padrón de una organización con el catastro de otra.

**Cruce** (D7). Cada ítem del catastro busca su predio: por código municipal (`predio.codigo`), si no por código CPU
(`codigo_cpu`), si no por sector, manzana y lote (`sector_catastral`, `manzana_catastral`, `lote`), sin distinguir
mayúsculas, espacios en los extremos ni ceros a la izquierda de cada tramo de números: `01-01-0006` y `1-1-6` son la
misma llave, como un lote `08` y `8` (el padrón de Perené escribe los dos). El cruce va **por pasadas sobre todos los ítems**, así el orden de los ítems no
cambia nada: primero todos por código municipal, luego los que quedan por CPU, luego los que quedan por sector, manzana
y lote. Una llave de respaldo nunca le quita a otro ítem el predio que este nombra por una llave más fuerte. Un predio
cruza una sola vez. En las dos primeras pasadas, entre varios predios de la misma llave gana el que tiene DJ del año y
luego el de código menor. Sector, manzana y lote no identifican: cruzan solo cuando a la llave le quedan **un** ítem y
**un** predio; si la comparten varios ítems o varios predios, ninguno cruza (los ítems quedan OMISO y los predios
SIN_CATASTRO) antes que emparejarlos al azar.

**Categorías** (una fila de `brecha_predio` por ítem del catastro y por predio del padrón sin catastro). El padrón son
los predios con alguna DJ **vigente** del año; su autoavalúo declarado es el de `totalesDePredio` (el mayor de cada
secuencia de uso, una vez).

| Categoría | Qué es |
|---|---|
| `OMISO` | Ítem del catastro sin predio en el padrón (o cuyo predio no tiene DJ vigente del año: la fila guarda el predio). |
| `SIN_CATASTRO` | Predio del padrón que ningún ítem cruzó. |
| `SIN_VALOR` | Cruzó, pero catastro no lo valorizó: lleva su `motivo` (D5). |
| `SUBVALUADO` | Autoavalúo catastral > declarado × 1.05. |
| `SOBREVALUADO` | Autoavalúo catastral < declarado × 0.95. |
| `COINCIDE` | Dentro de ±5 %, ambos extremos incluidos (D10). |

**Impuesto.** Por cada contribuyente con DJ vigente del año:
- el valor catastral de cada predio cruzado y valorizado se reparte entre sus secuencias de uso según el autoavalúo
  declarado de cada una; si todas declaran 0, según su área construida; si tampoco, en partes iguales (D12), al
  céntimo y la última con el residuo;
- las DJ de ese predio se recalculan **dos veces con la misma regla**, `condominio` (el % de cada condómino y su
  `deduccion`): con el autoavalúo declarado y con la parte del catastro. Lo que el
  catastro mueve es la diferencia entre los dos valores afectos recalculados, nunca entre el afecto guardado y uno
  recalculado: en el padrón importado el afecto es el del Excel y el % está redondeado, y compararlos daría una brecha
  aunque los autoavalúos sean iguales. Un predio que el catastro no valorizó es el mismo en los dos lados;
- **% de condominio vacío** (el importador lo deja vacío cuando el Excel no trae condominio o el autoavalúo es 0): un
  titular solo de la secuencia tiene el 100 %; entre varios, los que no tienen % se reparten en partes iguales lo que
  dejan los demás (nunca menos de 0). La corrida cuenta en `mensaje` cuántas DJ se completaron así;
- **deducción del 5 %** (Amazonía, Ley 27037; D8): se reconoce en la DJ, no por un parámetro. Si la `deduccion` de
  una DJ es mayor que 0 y es el 5 % de su valor de condominio declarado (recalculado, al céntimo, ±S/ 0,01), en el
  lado catastral se recalcula: el 5 % del valor de condominio catastral (al céntimo, redondeo comercial), y el afecto
  es ese valor menos esa deducción. Las demás deducciones (pensionista, etc.) quedan fijas en los dos lados. La
  corrida cuenta en `mensaje` cuántas DJ se recalcularon así;
- `Δ impuesto = liquidar(afecto guardado + lo que mueve el catastro) − liquidar(afecto guardado)`: el lado declarado
  es exactamente la liquidación de `/liquidacion` (el `impuestoAnual`, con sus tramos y el mínimo);
- ese Δ se reparte entre sus predios en proporción a lo que movió cada uno: con su signo cuando todos se mueven en el
  mismo sentido, en valor absoluto cuando unos suben y otros bajan (con signo los pesos podrían sumar casi 0). Su
  impuesto declarado se reparte según el valor afecto guardado de cada predio. Así la suma por predio es exactamente
  la suma por contribuyente.

«Valores iguales no mueven nada» vale para el autoavalúo **de cada DJ**, no solo el del predio. El autoavalúo
declarado de una secuencia es el mayor de sus titulares (`totalesDePredio`), y el lado catastral da a cada titular la
parte de su secuencia. Un condómino que declaró **menos** que el mayor de su secuencia se recalcula con ese mayor:
aunque el catastro coincida con el declarado del predio (COINCIDE, Δ autoavalúo 0), su afecto sube y el predio puede
llevar un Δ impuesto distinto de 0. Es a propósito: el condómino declaró por debajo del valor de su propio predio.

Un **omiso** no tiene contribuyente: su impuesto se estima liquidando su autoavalúo catastral solo
(`estimado = true`). El Δ impuesto del panel es neto: lo que suman los subvaluados menos lo que restan los
sobrevaluados, más lo estimado de los omisos (`deltaImpuestoOmisosEstimado`, aparte). Sin los parámetros del impuesto del año (UIT, tramos, mínimo) la corrida queda **FALLIDA** con
`Faltan parámetros del impuesto predial: UIT 2099`.

**Arbitrios.** Para cada predio cruzado y valorizado, `Δ arbitrios = Arbitrios.determinar` del año entero con las DJ
del predio llevando el uso del catastro (su `uso_predio`), su área construida (repartida como el autoavalúo) y su
frente, **menos** lo mismo con las DJ declaradas. `Calculo` cobra la base `FRONTIS_ML` con el frente de cada DJ, una
vez por secuencia cobrada: en un año de un PU por predio se cobra una sola secuencia al mes y cada una lleva el frente
entero del catastro; en uno de un PU por uso se cobran todas, así que el frente del catastro se reparte entre ellas
(según el frente declarado de cada una, si no como el autoavalúo) y el predio lo paga una vez. Ninguna cuota cuenta
como ya escrita y no hay tope (no se leen las del año anterior): es el cargo anual hipotético de ambos lados, y nada
se escribe. Si el año tiene `TOPE_ARBITRIO`, la corrida lo advierte en `mensaje` («Δ arbitrios sin tope: el año
tiene TOPE_ARBITRIO»). Si al año le falta la ordenanza o
sus servicios, o el contexto no se pudo leer, no hay Δ arbitrios y la corrida lo dice en `mensaje`; un predio que no se
puede determinar queda sin Δ y se cuenta en `mensaje`. Un omiso no tiene Δ arbitrios (no tiene zona ni DJ).

**Corrida.** `POST /api/srtm/brecha/corridas {anio}` responde **202** con la corrida PENDIENTE; en segundo plano, como
quien la lanzó, un solo trabajador lee el catastro y el padrón, calcula y escribe las filas en lotes de 200,
actualizando `procesados`, hasta TERMINADA (o FALLIDA, con `mensaje`). Unos 4 000 predios son uno o dos minutos: la
maquinaria de lotes de la determinación masiva no aporta aquí. Una sola activa por organización y año (candado
`BRECHA`): otra responde **409**; una cuyo `latido` tiene más de 10 minutos (su instancia murió) se da por abandonada
y pasa a FALLIDA. El `latido` late con el reloj de la base (`now()`, como la determinación masiva) tras cada página
del catastro y entre los pasos largos; antes de escribir cada lote de filas y antes de cerrar, la corrida relee su
estado y se detiene si ya no está EN_CURSO, así una abandonada nunca pisa a una más nueva. La misma regla vale al
**leer**: `GET /corridas` y `GET /corridas/{id}` devuelven FALLIDA («Abandonada: sin latido desde …», con el reloj de
la base) la activa cuyo latido venció, y la guardan así si quien lee puede editar corridas; el portal deja de esperarla
y puede lanzar otra.
Pide creación y edición sobre `brecha_corrida` y creación sobre `brecha_predio` (**403**); consultar pide lectura
sobre `brecha_corrida`.

**Quién la ve.** La brecha la consulta cualquier usuario de la municipalidad (decisión del 2026-10-19), y eso se da por
roles, no por código: **todo rol** de srtm lleva lectura sobre `brecha_corrida` y `brecha_predio`. Core revisa la
lectura de cada fila y wasichai no tiene un permiso para «cualquier usuario autenticado», así que un rol nuevo sin esa
lectura ve el panel con 403. Lanzar una corrida queda para quien tenga creación y edición (arriba).

| Método | Ruta | Responde |
|---|---|---|
| POST | `/api/srtm/brecha/corridas` `{anio}` | 202 con la corrida; 400 año inválido; 403; 409 catastro no configurado (para la organización) o corrida en curso. |
| GET | `/api/srtm/brecha/corridas?anio` | Las corridas, la más reciente primero. |
| GET | `/api/srtm/brecha/corridas/{id}` | Una corrida, con su avance (`totalPredios`, `procesados`). |
| GET | `/api/srtm/brecha/{anio}/panel?corrida` | KPIs, por categoría y por sector de la última corrida TERMINADA del año (o de `corrida`); 404 si no hay. |
| GET | `/api/srtm/brecha/{anio}/predios?categoria&sector&page&size&corrida` | Página de filas por su `orden` (código del predio, luego código catastral); 422 si `categoria` no es una de las seis. |
| GET | `/api/srtm/brecha/{anio}/lotes?corrida` | GeoJSON `FeatureCollection` (EPSG:4326) con `{categoria, codigo, deltaImpuesto}`. |

**KPIs de autoavalúo.** `autoavaluoDeclarado`, `autoavaluoCatastro` y `deltaAutoavaluo` del panel (y `declarado` y
`catastro` de la corrida) suman las **mismas filas**: las comparadas (SUBVALUADO, SOBREVALUADO, COINCIDE) y los omisos,
así declarado + Δ = catastro. SIN_CATASTRO y SIN_VALOR tienen un solo lado y quedan fuera; las sumas de todas las filas
van aparte, en `autoavaluoDeclaradoTotal` y `autoavaluoCatastroTotal`.

El JSON va en camelCase, como `/liquidacion`. `lote_geom` de `brecha_predio` es un POLYGON 32718 como el de `predio`:
guarda el lote del predio del padrón o, para un omiso, el del catastro; así `/lotes` lo lee de las filas y la capa gis
(`/api/gis/objects/brecha_predio/features`) también puede servirlo.

**Decisiones** (confirmadas por la municipalidad el 2026-10-19; D8 decidida el 2026-10-20):
- D5: una celda «.....» del cuadro de valores no es cero: el predio queda SIN_VALOR con su motivo.
- D7: llave de cruce código municipal → CPU → sector, manzana y lote.
- D8: la deducción del 5 % (Amazonía, Ley 27037) se reconoce en la DJ (su `deduccion` es el 5 % de su valor de
  condominio declarado, ±S/ 0,01) y se recalcula sobre el valor de condominio catastral; las demás deducciones quedan
  fijas.
- D10: COINCIDE con |Δ autoavalúo| ≤ 5 %.
- D11: solo 2026 (el año lo decide catastro; srtm acepta cualquiera con sus parámetros).
- D12: reparto entre secuencias por autoavalúo declarado, si no por área construida, si no en partes iguales.
- Los demás (D1–D4, D6, D9: tablas, materiales, estados, partidas, actualización y obras) son de catastro-backend.

## Infracciones administrativas

Las multas administrativas (familia `ADMINISTRATIVA`) se portan del contexto `sanciones` de `rentas`: el modelo, la
guardia de escritura y la carga del CUIS (ver [Modelo](#modelo)), las reglas puras de `srtm.sanciones` y su api bajo
`/api/srtm/infracciones/...`: el CUIS, las notificaciones previas con sus padrones, los plazos cargados y el panel
(`InfraccionesController`),
las actas (`ActasController`) y los descargos, las resoluciones con su PDF y sus notificaciones
(`ResolucionesController`). srtm no cobra: la multa queda determinada en el acta, sin deuda, pagos ni coactiva
([Siguientes pasos](#siguientes-pasos-fuera-de-este-alcance)).

### API de infracciones

Bajo `/api/srtm/infracciones`, con las convenciones de arbitrios: registros en snake_case con los nombres del modelo,
relaciones como id, fechas ISO, dinero como número (los textos para leer, como los `detail` de los 409/422 y el
`detalle` de los actos, citan las fechas en `dd/MM/yyyy`, con `srtm.legible()`). Un parámetro de consulta desconocido o mal formado es **422** y lo
nombra en `errors` (`Filtros.soloConoce`). Cada acto pide permiso de creación sobre su objeto **antes** de leer nada
(**403** que nombra el objeto), exige `observacion` de 5 a 500 caracteres (**400**) y escribe con la marca de srtm
(`Registros.create`/`replace`): la API genérica no escribe estos objetos (POST **403**, PUT/DELETE **409**, también un
ADMIN). `FaltanSanciones` sale como **422** con `faltan: [...]` (`ErroresDeSanciones`, para todos los controladores de
`srtm.sanciones`).

| Método | Ruta | |
|---|---|---|
| GET | `/cuis?vigentes_a&materia&q` | `{vigentes_a, uit: {valor, anio, parametro_id} \| null, faltan, codigos}`: las versiones vigentes ese día (por omisión hoy), por código; `materia` y `q` (código o descripción) buscan una parte. Cada código lleva `multa`, `multa_segunda` y `multa_tercera` a la UIT de `vigentes_a` (`Multas.calcular`); null si el grado no tiene % o si no hay UIT. Sin UIT: `uit: null` y `faltan: ["UIT 2026"]`. Siempre 200. Es también el reporte de códigos del brief: el catálogo vigente a una fecha, con sus multas. |
| POST | `/cuis` `{familia?, codigo, descripcion, materia?, porcentaje_uit, porcentaje_uit_segunda?, porcentaje_uit_tercera?, medida_complementaria?, base_legal, vigencia_desde, observacion}` | **201** con el registro plano de la versión nueva y `cerrada` (la que cerró, o null). **409** si esa versión (`familia\|codigo\|vigencia_desde`) ya existe; **422** si no empieza después de la vigente (o pisa una cerrada); **400** por campo. Es lo que llama `import_cuis.py`. |
| POST | `/cuis/derogacion` `{familia?, codigo, vigencia_hasta}` | **200** con el registro plano de la versión derogada: rige hasta `vigencia_hasta` (inclusive) y ninguna la sigue. **404** si el CUIS no tiene el código; **409** si no tiene versión vigente; **422** si `vigencia_hasta` es anterior a su `vigencia_desde`; **400** por campo. Es lo que llama `import_cuis.py --completo`. |
| GET | `/notificaciones?numero&q&contribuyente&desde&hasta&vencidas_a&page&size` | Página (`size` 20 por omisión) de notificaciones, la más reciente primero, **como estaban a `vencidas_a`** (por omisión hoy): el registro plano + `vencimiento` (null sin plazo), `vencida`, `vencidas_a`, `subsanada: {fecha} \| null`, `acta: {id, numero} \| null` y `contribuyente_nombre`. Una notificación fechada después de `vencidas_a` no sale (no existía), y la subsanación y el acta solo cuentan si su fecha es ≤ `vencidas_a`. `numero` es el número entero (exacto); `q`, una parte del número sin distinguir mayúsculas (`%` y `_` se toman literales); `contribuyente`, su id. |
| POST | `/notificaciones` `{numero, fecha, contribuyente?, predio?, direccion, motivo, plazo_dias?, observacion}` | **201** con la fila de arriba. **404** si el contribuyente o el predio no existen; **409** si el número ya está; **422** si la fecha es posterior a hoy; **400** por campo (`plazo_dias` de 1 a 32767). |
| POST | `/notificaciones/{id}/subsanacion` `{fecha?, observacion}` | **201** con la subsanación. `fecha` por omisión hoy. **409** si ya está subsanada; **422** si venció a esa fecha, si ya originó un acta, o si la fecha es posterior a hoy o anterior a la notificación. |
| GET | `/notificaciones/vencidas?corte&page&size` | El padrón de vencidas: las no subsanadas, sin acta y vencidas a `corte` (por omisión hoy; con la misma regla de fechas: un acta posterior al corte no la saca), la de vencimiento más antiguo primero; cada fila es la de arriba más `corte` (y `vencimiento` nunca es null). |
| GET | `/notificaciones/por-contribuyente?contribuyente&page&size` | El padrón por contribuyente: las del contribuyente (obligatorio, su id; **404** si no existe), la más reciente primero, con las filas de arriba a hoy. |
| GET | `/panel?anio` | `{anio, al_dia, actas, resoluciones, notificadas, vencen_esta_semana, semana: {desde, hasta}, coactiva: null, nota}` del año (por omisión el actual) a hoy ([Panel](#panel)); `notificadas` no cuenta las RIS de actas anuladas o dejadas sin efecto. `coactiva` siempre es null y `nota` dice por qué: «En coactiva no existe en srtm: no hay cobranza». **422** si `anio` no es un año o con otro parámetro; **403** sin lectura sobre lo que cuenta. |
| GET | `/plazos?anio` | `{anio, al_dia, plazos: [{clave, dias, unidad, texto, vigencia_desde, vigencia_hasta, parametro_id}], feriados: {fechas, parametro_id} \| null, faltan}`: los `PLAZO DESCARGO_PAPELETA` y `PLAZO RG_RECURSO` vigentes al 1 de enero del año (por omisión el actual; en el año en curso, a hoy: `al_dia` es el día leído) y el `FERIADOS <anio>` (sus fechas movibles, ordenadas), leídos como los leen los actos (`Plazos.cargados`). Lo que falta o está mal formado va en `faltan` con el nombre que daría el 422 de un acto (`PLAZO RG_RECURSO 2027`, `FERIADOS 2027`). Siempre 200; **422** si `anio` no es un año o con otro parámetro. |
| POST | `/actas` `{numero, fecha_infraccion, hora_infraccion?, lugar, codigo, reincidencia, obligado, contribuyente?, predio?, notificacion_previa?, expediente?, inspector?, descripcion_hecho?, observacion}` | **201** con el registro plano de `papeleta` + `referencia` (`PAPELETA-<id>`) + `desglose {base_imponible, porcentaje_infraccion, importe_infraccion, porcentaje_a_cobrar, importe_a_pagar, importe_con_beneficio, fecha_calculo}`. `codigo` es el texto del CUIS; `hora_infraccion` `HH:mm`. **422** si el código no rige ese día (nombra código y fecha), con `faltan` (`UIT 1991`, `CUIS A-042 porcentaje_uit_segunda`, todos a la vez), si la previa está subsanada o si la fecha es posterior a hoy o anterior a la previa; **404** si el obligado, el contribuyente, el predio o la previa no existen; **409** si el número ya está; **400** por campo (contribuyente o predio, al menos uno). |
| GET | `/actas?numero&administrado&codigo&fase&desde&hasta&page&size` | Página de filas `{id, numero, fecha_infraccion, administrado, documento, codigo, descripcion_infraccion, porcentaje_infraccion, importe_a_pagar, fecha_calculo, medida_complementaria, fase, fase_al_dia, estado_de_la_deuda}`, la infracción más reciente primero. `administrado` busca una parte del documento o del nombre del obligado; `codigo`, el texto del CUIS (cualquier versión); `fase` ∈ PREVENTIVA, CONSTATADA, SANCIONADA (otra → **422** que las nombra). `fase` es null en un acta anulada o dejada sin efecto. |
| GET | `/actas/{id}` | `{acta, referencia, codigo_infraccion (la versión usada), notificacion_previa, partes, actos: [{orden, acto, fecha, documento, id, detalle}], descargos, resoluciones, anulacion, fase, fase_al_dia, estado_de_la_deuda, acciones: {descargo, resolucion, anulacion: {permitida, motivo}}}`. `partes` = `{obligado: {id, nombre, documento, domicilio_fiscal \| null}, contribuyente: {id, nombre, documento} \| null, predio: {id, codigo, direccion \| null} \| null}`. Cada resolución lleva sus `notificaciones` y `acciones: {notificacion: {permitida, motivo}}` (impedida si el acta está anulada, o si está dejada sin efecto y la resolución no es la que la dejó sin efecto, con el texto del 422). `detalle` es legible: fechas `dd/MM/yyyy` y palabras («Fundado, se deja sin efecto», «No ubicado»), no los códigos. |
| POST | `/actas/{id}/anulacion` `{motivo, fecha?, observacion}` | **201** con el registro plano de `anulacion_papeleta` (`clave` = id del acta). `fecha` por omisión hoy. **409** si ya está anulada; **422** si una resolución la dejó sin efecto, o si la fecha es anterior a la infracción o posterior a hoy. |
| POST | `/actas/{id}/descargos` `{numero_expediente, tipo_recurso, fecha, sustento, observacion}` | **201** con el registro plano de `descargo_papeleta`: `presentado_hasta`, `en_plazo`, `plazo_texto` (`5 DIAS_HABILES`) y la relación `plazo` (la fila leída). El tardío se registra con `en_plazo: false`. **422** con `faltan` (`PLAZO DESCARGO_PAPELETA 1991`, `FERIADOS 1991`; no escribe nada), si el acta está anulada o sin efecto, o si la fecha es anterior a la infracción o posterior a hoy; **409** si el expediente ya está; **404** si el acta no existe; **400** por campo. |
| POST | `/actas/{id}/resoluciones` `{tipo, descargo, sentido, efecto, fecha?, sustento, sancion_accesoria, observacion}` | **201** con el registro plano de `resolucion_gerencia`: `numero` (`RIS-AAAA-NNNNNN` o `RGR-AAAA-NNNNNN`), `anio`, `correlativo`, `plazo_texto` y `plazo`. Lo que no aplica va null; `fecha` por omisión hoy. **422** con `faltan` (`PLAZO RG_RECURSO 1991`), por `SE_REDUCE`, por una RECURSO sin descargo, por un fallo sin descargo (o un descargo sin fallo), por un descargo de otra acta, si el acta está anulada o sin efecto, o si la fecha es anterior a la infracción o al descargo, o posterior a hoy; **409** por una segunda RIS del acta o una segunda resolución del descargo; **404** si el acta o el descargo no existen. |
| GET | `/resoluciones/{id}/pdf` | La resolución en PDF, `inline; filename="RIS-1991-000001.pdf"`, dibujada otra vez desde las filas congeladas. **404** si no existe; **403** sin lectura sobre `resolucion_gerencia`. |
| POST | `/resoluciones/{id}/notificacion` `{fecha_diligencia?, modalidad, resultado, notificador, direccion, receptor, documento_receptor, vinculo, acuse, observacion}` | **201** con el registro plano de `notificacion_resolucion`: `intento`, la `direccion` usada (sin `direccion`, el domicilio fiscal del obligado) y, si surte efecto (NOTIFICADO o RECHAZADO), `exigible_desde`, `plazo_texto` y `plazo`; con NO_UBICADO van null. `fecha_diligencia` por omisión hoy. **422** si el acta está anulada («no queda nada que notificar») o, dejada sin efecto, si no es la resolución que la dejó sin efecto («esta ya no se notifica»; la que la dejó sin efecto se notifica como cualquiera), sin dirección (ni dada ni domicilio fiscal), con `faltan` (`PLAZO RG_RECURSO`, `FERIADOS`) si surte efecto, o si la diligencia es anterior a la resolución o posterior a hoy; **404** si la resolución no existe. |

Y bajo `/api/srtm`, en las fichas: `GET /contribuyentes/{id}/infracciones` (las actas donde es el obligado o el
contribuyente) y `GET /predios/{id}/infracciones` (las que nombran el predio) responden `{al_dia, actas: [fila]}`, con
las filas de `GET /actas` a hoy; **404** si el contribuyente o el predio no existen.

### CUIS

El cuadro único de infracciones y sanciones se versiona por vigencia: un cambio nunca edita una fila. `CuisService`
agrega la versión nueva y cierra la vigente (`vigencia_hasta` = el día antes, `clave_vigente` = NULL, con
`registros.replace`) en **una transacción**, bajo el candado consultivo `Candado.CODIGO_INFRACCION` de
`familia|codigo` (`srtm.Candados`, `pg_advisory_xact_lock`). Dentro del candado vuelve a leer las versiones del código
y aplica `Cuis.versionNueva`: dos versiones nuevas a la vez no se pisan (una espera a la otra; la misma versión dos
veces da 409). La versión cerrada conserva sus cifras, y un acta que la usó sigue apuntándola. El código se recorta y
se pasa a mayúsculas; `familia` por omisión es `ADMINISTRATIVA` (otra es 400). Los porcentajes llegan como número o
como texto (`import_cuis.py` manda las celdas del CSV como texto).

Una norma que **deroga** un código sin darle versión nueva (la OM 006-2026-MDP deroga el CUIEMA de la OM 01-2021, cuyos
códigos `01.01.001`… no siguen en el nuevo, que numera `001`…) lo cierra con `Cuis.derogar`: la vigente rige hasta el
día que dice la norma, bajo el mismo candado, y solo cambian `vigencia_hasta` y `clave_vigente`. Un código derogado
puede volver con una versión nueva posterior.

**Largos y alícuotas del CUIS.** Son más anchos que en `rentas`, porque el CUIEMA de Perené no cabía: descripción ≤ 1000
(llega a 537), materia ≤ 120 (66), medida complementaria ≤ 500 (169) y base legal ≤ 2000 (938). `porcentaje_uit` y los de reincidencia, y
los porcentajes del acta, van de más de 0 a 10 000 (100 UIT; Perené llega a 1000 %).

### Notificaciones previas

`NotificacionesService` registra la notificación previa (número del formulario, único y en mayúsculas) y su
subsanación, que es un objeto que se agrega (una por notificación: `clave` = id de la notificación). Lo derivado nunca
se guarda: `vencimiento` y `vencida` salen de `Notificaciones` (el último día del plazo todavía vale, #411; sin plazo
no vence), la subsanación de su registro y el acta de la `papeleta` que la nombra (`notificacion_previa`). La
subsanación toma el candado `Candado.NOTIFICACION` del id de la notificación y aplica
`Notificaciones.exigirSubsanable`; el alta del acta toma el mismo candado para que no se crucen ([Actas](#actas)). El padrón de
vencidas lee las candidatas (con plazo y fecha anterior al corte), aplica `Notificaciones.vencida` y corta la página
después: la definición de vencida es una sola.

**El padrón a una fecha pasada** muestra cada notificación como estaba ese día (`NotificacionesService.derivar(n, al,
hechos)`, pura): la subsanación y el acta solo cuentan si su fecha (la de la subsanación, la de la infracción) es ≤
`vencidas_a`, y el padrón no trae las notificaciones fechadas después de `vencidas_a` (`hasta`, si se da, no pasa de
ahí). El padrón de vencidas usa la misma regla al `corte`. `q` busca una parte del número (`Filtros.contiene`, `ILIKE`
con los comodines escapados); `numero` sigue siendo exacto.

### Actas

`ActasService` registra el acta (papeleta, familia `ADMINISTRATIVA`) con su multa **calculada una vez y congelada**:
toma la versión del código que rige el día de la infracción (`Cuis.vigenteA` sobre las versiones de ese código, no la
última), la UIT vigente ese día (`Multas.uit`) y aplica `Multas.calcular` con el grado que declara el inspector. Copia
en la fila el desglose, la `medida_complementaria` de la versión y las relaciones `codigo_infraccion` (la versión
usada) y `uit` (la fila leída): corregir la fila de la UIT o agregar una versión nueva del CUIS no cambia un acta ya
escrita. `fecha_calculo` es el día del registro. El número se recorta y se pasa a mayúsculas, y `clave` =
`familia|numero` lo hace único. El `obligado` es explícito (no se deduce) y el acta nombra un contribuyente o un predio,
al menos uno.

Con `notificacion_previa`, el alta toma el candado `Candado.NOTIFICACION` del id de la notificación (el mismo que la
subsanación), la vuelve a leer y aplica `Notificaciones.exigirQueOrigineActa` y `OrdenDeLosActos` (la infracción no es
anterior a la previa), todo en la misma transacción que el insert: un acta y una subsanación de la misma previa a la
vez no se escriben las dos. La anulación es un objeto que se agrega (`clave` = id del acta), bajo el candado
`Candado.ACTA` del acta, con `Procedimiento.exigirAnulable` (409 si ya está anulada, 422 si una resolución la dejó sin
efecto).

La **fase** y el **estado de la deuda** nunca se guardan: `ActasService.hechos(actas)` lee, una consulta por tipo, la
previa y su subsanación, la anulación y las resoluciones de cada acta, y `Procedimiento.fase` / `estadoDeLaDeuda` los
derivan a hoy (`fase_al_dia`). Por eso el filtro `fase` de la grilla es el único que no va a la base: con `fase`, la
consulta lee todas las actas que dejan los demás filtros (número, código, administrado, fechas, en la base), deriva la
fase de cada una con la misma función que pinta la columna y corta la página después; sin `fase`, pagina en la base.
Así el filtro encuentra exactamente lo que la columna muestra (#397).

La ficha (`GET /actas/{id}`) lista los actos en **orden legal** (`Expedientes.actos`): la notificación previa, el
acta, los descargos, las resoluciones, las notificaciones de las resoluciones y la anulación; dentro de cada tipo, por
fecha. `detalle` dice qué es cada uno hoy, para leerlo: fechas `dd/MM/yyyy` y palabras en vez de códigos (la previa
«Vence el 15/08/2026» o «Vencida el …», el descargo «En plazo (hasta el …)» o no, la resolución «Fundado, se deja sin
efecto. Notificada el …», la notificación «No ubicado» o «Notificado, exigible desde el …»). `acciones` sale de las
reglas: `Procedimiento.impedimento("impugnar")` para el descargo, `Resoluciones.impedimento(hechos, descargos)` para
la resolución (además de anulada o sin efecto: con la RIS dictada y ningún descargo sin resolver no queda resolución
que dictar) e `impedimentoDeAnular`, con el mismo texto que daría el acto; cada resolución lleva
`acciones.notificacion` (`Procedimiento.impedimentoDeNotificar(hechos, resolucion)`: impedida si el acta está
anulada; si está dejada sin efecto, impedida salvo en la resolución cuyo efecto es `SE_DEJA_SIN_EFECTO`). `partes` (`Partes.de`, pura) nombra al obligado con su documento y su domicilio fiscal vigente (el que usa la
notificación de una resolución por omisión), al contribuyente y al predio que nombra el acta; lo vacío va null.

### Descargos y resoluciones

`ResolucionesService` registra los actos que contestan un acta, cada uno un objeto que solo se agrega y que **copia**
lo que leyó:

- **Descargo.** `Descargos.registrar` con el `PLAZO DESCARGO_PAPELETA` y los `FERIADOS` vigentes **el día de la
  infracción** (como `rentas`): `presentado_hasta`, `en_plazo` y `plazo_texto` quedan en la fila, con la relación
  `plazo` a la fila leída. El tardío se registra (`en_plazo` false): lo que corresponde es declararlo improcedente.
  No procede contra un acta anulada o sin efecto (`Procedimiento.exigirQueQuedeAlgoQue("impugnar")`) y su fecha va
  entre la infracción y hoy (`OrdenDeLosActos`). El número de expediente se recorta, va en mayúsculas y es único.
- **Resolución.** `Resoluciones.validar` (SE_REDUCE 422, RECURSO con descargo, fallo ⇔ descargo, el descargo es de
  esta acta, nada contra un acta anulada o sin efecto, una RIS por acta y una por descargo), `OrdenDeLosActos` (después
  de la infracción y de la presentación del descargo) y el `PLAZO RG_RECURSO` vigente a su fecha, que copia
  (`plazo_texto`, lo imprime el papel). El número es `RIS-AAAA-NNNNNN` (ADMINISTRATIVA) o `RGR-AAAA-NNNNNN` (RECURSO),
  por tipo y año de la fecha: el correlativo es el mayor + 1, leído bajo el candado `Candado.RESOLUCION` de
  `tipo|anio` en la misma transacción que el insert (`numeroDeResolucion`). La transacción toma antes `Candado.ACTA`
  (el de la anulación): una resolución que deja sin efecto y una anulación no se cruzan, y dentro del candado se
  vuelve a validar. `clave_ris` (id del acta en la RIS) y `clave_descargo` (id del descargo) son la red de la base.
  - **El papel se dibuja antes de abrir la transacción**, con los datos que se van a guardar (y el correlativo
    esperado en ese momento): si no se puede dibujar no se escribe nada, y el dibujo no retiene el candado. No se
    guardan los bytes: `GET /resoluciones/{id}/pdf` lo vuelve a dibujar de las filas congeladas (decisión 8).
- **Notificación de la resolución.** Un intento por fila: `intento` = los que hay + 1, bajo el candado
  `Candado.NOTIFICACION_RESOLUCION` del id de la resolución (`clave` = `resolucion|intento`), tomado después de
  `Candado.ACTA`: no se notifica nada de un acta anulada (422 «no queda nada que notificar») y, de una dejada sin
  efecto, solo la resolución que la dejó sin efecto (a las demás, 422 «esta ya no se notifica»); la que la dejó sin
  efecto se notifica como cualquiera y su `exigible_desde` se cuenta con `RG_RECURSO`
  (`Procedimiento.exigirNotificable`, comprobado otra vez dentro del candado), y la notificación no se cruza con la
  anulación. Sin `direccion`, el
  domicilio fiscal del obligado (`contribuyente.domicilio_fiscal`, que sigue al domicilio fiscal activo); sin ninguno,
  422. `NotificacionesDeResolucion.exigibilidad` con el `PLAZO RG_RECURSO` y los `FERIADOS` vigentes a la diligencia:
  NOTIFICADO y RECHAZADO surten efecto (`exigible_desde`, `plazo_texto`, `plazo`); NO_UBICADO no hace exigible nada
  y se registra otro intento. La diligencia va entre la resolución y hoy.

**El papel** (`templates/emision/resolucion.html`, con `HojaResolucion.kt` que deja cada valor ya formateado): la
cabecera de los documentos con la fecha de la resolución, el número y la fecha; el acta (número, fecha y hora,
lugar, código, descripción y base legal de la versión del CUIS que usó, reincidencia, medida complementaria y el
desglose congelado con su `fecha_calculo`); el obligado (nombre, documento y domicilio fiscal); el recurso si lo hay
(expediente, tipo, presentado el, «Presentado dentro del plazo: SÍ, el plazo vencía el …» o «NO, el plazo venció el
…»); el sentido, el efecto, la sanción accesoria y el sustento; «Plazo para impugnar: `plazo_texto`», con la cita del
art. 218.2 del TUO de la Ley 27444 (sin cifras de la norma en el código); y las líneas de firma de Gerente y
Secretario, «sin firma digital (D-05)». Los considerandos no se inventan: un párrafo marcado dice «Considerandos: los
provee la municipalidad (decisión 8)». Corregir la UIT, el plazo o agregar una versión del CUIS no cambia el papel de
una resolución ya dictada. srtm no guarda la historia de los domicilios: el domicilio que imprime es el fiscal
vigente al dibujarlo.

### Panel

`GET /infracciones/panel` es el `inf-panel` de `rentas` (Ejercicio; Actas levantadas, Resoluciones dictadas,
Notificadas, Vencen esta semana). `PanelService` lee, una consulta por tipo, lo que cuenta `Panel.contar` (pura, con
el día como argumento) y la respuesta lleva `al_dia` (hoy):

- **`actas`:** las actas cuya infracción cae en el año, **todas**: también las anuladas o dejadas sin efecto, porque se
  levantaron (es la cifra de actas levantadas, no la de deuda viva).
- **`resoluciones`:** las RIS (tipo `ADMINISTRATIVA`) fechadas en el año. Una RGR resuelve un recurso: no sanciona y no
  cuenta.
- **`notificadas`:** de esas RIS, las que tienen al menos una notificación que surte efecto (NOTIFICADO o RECHAZADO);
  una NO_UBICADO no cuenta, ni la RIS de un acta anulada o dejada sin efecto (`Procedimiento.estadoDeLaDeuda` de su
  acta, sea cual sea el año de la anulación o de la resolución que la dejó sin efecto): ya no queda nada notificado.
  `resoluciones` sí las cuenta, porque se dictaron.
- **`vencen_esta_semana`:** las notificaciones previas no subsanadas y sin acta cuyo vencimiento
  (`Notificaciones.vencimiento`, la misma definición del padrón de vencidas) cae de lunes a domingo, ambos incluidos,
  de la semana de `al_dia` (`semana: {desde, hasta}`). Es de la semana de hoy, no del año pedido.
- **`coactiva`:** null siempre. srtm no cobra (no hay deuda, pagos ni coactiva), así que no hay cifra que dar y `nota`
  lo dice; nunca un 0.

Como el resto de las consultas de `srtm.sanciones`, no pide un permiso propio: `RecordService` exige lectura sobre
cada objeto que lee (`papeleta`, `resolucion_gerencia`, `notificacion_resolucion`, `notificacion_administrativa`,
`subsanacion_notificacion`, `contribuyente`), y sin ella responde **403**.

Los padrones de las notificaciones previas (vencidas y por contribuyente) y el reporte de códigos (el CUIS vigente con
sus multas) son las consultas de la tabla de arriba.

### Reglas

Funciones puras (`srtm.sanciones`): sin base, sin reloj (la fecha entra como argumento) y con `BigDecimal`. Los
servicios leen los registros y los parámetros y se los pasan; las pruebas portan los casos de `rentas` con cifras
ficticias.

| Regla | Qué decide | Fuente en `rentas` |
|---|---|---|
| `Multas.calcular(codigo, uit, reincidencia)` | El desglose del acta: `base_imponible` = la UIT, `porcentaje_infraccion` = `porcentaje_uit`, `porcentaje_a_cobrar` = el % del grado declarado (`porcentaje_uit`, `_segunda` o `_tercera`), los dos importes = base × % / 100, `importe_con_beneficio` vacío. Un grado sin su % falta (`CUIS A-042 porcentaje_uit_segunda`): nunca se cobra el de otro grado. `Multas.uit(parametros, dia)`: la UIT vigente el día de la infracción, o `UIT 2026`. | `Papeleta`, `PapeletaTest` |
| `Multas.redondear` | El único redondeo: al céntimo, `HALF_UP`, una sola vez sobre el producto exacto (el mismo que `ImpuestoPredial`). | decisión 2 |
| `Notificaciones.vencimiento` / `vencida` | `vencimiento` = fecha + `plazo_dias`; vencida a un corte si el corte es **posterior** (el último día todavía vale); sin plazo no vence nunca. Una sola definición para el padrón de vencidas, la fase y la subsanación. `exigirSubsanable`: una vez (409), no con acta ni vencida (422); `exigirQueOrigineActa`: una subsanada no origina acta (422). | `NotificacionAdministrativaTest`, `SubsanarNotificacionTest`, #411 |
| `Plazos` | Días hábiles (art. 144 de la Ley 27444): ni sábado ni domingo, ni `Vencimientos.FERIADOS_NACIONALES`, ni los `FERIADOS <anio>` cargados (`Calendario.de(parametros)`). `siguienteHabil` (estricto), `sumarHabiles` (sin contar el primero), `hasta` (infracción del 2026-03-04 con 5 → 2026-03-12), `exigibleDesde` (diligencia + hábil siguiente + plazo + 1 día). `faltan` nombra cada `FERIADOS <anio>` que el cómputo tocó y no está. `plazo(parametros, clave, fecha)`: la fila `PLAZO` vigente, entera y en `DIAS_HABILES`, o `PLAZO DESCARGO_PAPELETA 2026`. | `CalendarioHabil`, `Exigibilidad`, `SancionesJdbcTest`, `ElPlazoQueConcedeCadaResolucionJdbcTest` |
| `Cuis` | `vigenteA(versiones, fecha)` (los dos extremos cuentan; la de ese día, no la última), `cerrar(vigente, nuevaDesde)` (solo `vigencia_hasta` = el día antes y `clave_vigente` = NULL; una cerrada no se cierra, 409; la nueva empieza después de la vigente, 422), `versionNueva(versiones, desde)` (lo que se cierra; nunca se pisa una versión). | `CodigoInfraccionTest`, `MantenerCatalogoDeInfraccionesTest` |
| `Procedimiento.estadoDeLaDeuda(hechos)` / `fase(hechos, corte)` | Estado: `ANULADA`, `DEJADA_SIN_EFECTO` (si **alguna** resolución, de cualquier tipo, deja la multa sin efecto) o `PENDIENTE`. Fase: ninguna si no está pendiente (nunca «la más parecida»), `SANCIONADA` con su RIS (una RGR no sanciona), `PREVENTIVA` con la previa sin subsanar ni vencida al corte, si no `CONSTATADA`. `faseDelFiltro`: otra fase → 422 que nombra las tres. | `FaseDelProcedimiento`, `ProcedimientoSancionadorRepositoryJdbcTest` (#397, frontera #411) |
| `Descargos.registrar(infraccion, fecha, parametros)` | `presentado_hasta` = `Plazos.hasta` con el `PLAZO DESCARGO_PAPELETA` vigente el día de la infracción, `en_plazo` = fecha ≤ `presentado_hasta`, `plazo_texto`; el tardío se registra. | `RegistrarDescargo`, `DescargosResolucionesYDepositoTest` |
| `Resoluciones.validar(...)` / `plazo(tipo, fecha, parametros)` | RECURSO exige descargo; sentido y efecto van con el descargo y solo con él; el descargo es de esta acta; nada contra un acta `ANULADA` o `DEJADA_SIN_EFECTO` (422); una RIS por acta y una por descargo (409, además de `clave_ris` y `clave_descargo`). La RIS y la RGR conceden `RG_RECURSO` y, sin él, no toman otro. | `ResolverConResolucionDeGerencia`, `ElPlazoQueConcedeCadaTipoTest` (#410, #412) |
| `NotificacionesDeResolucion.exigibilidad(...)` | Surte efecto ⇔ `NOTIFICADO` o `RECHAZADO`; entonces `exigible_desde` y el plazo, contados desde la diligencia (2026-08-03 con 15 → 2026-08-27, porque el 6 de agosto es feriado nacional; sin él sería el 26). | `NotificarResolucionDeGerencia`, `Exigibilidad` |
| `Procedimiento.impedimentoDeNotificar(hechos, resolucion)` | Por qué no se notifica una resolución (la `acciones.notificacion` de la ficha y el 422 del acto): el acta anulada, o dejada sin efecto y la resolución no es la que la dejó sin efecto (esa se notifica: el administrado tiene que conocerla). | — |
| `Resoluciones.impedimento(hechos, descargos)` | Por qué no se puede dictar una resolución ahora (la `acciones.resolucion` de la ficha): el acta anulada o sin efecto, o la RIS ya dictada y ningún descargo sin su resolución. | — |
| `hojaResolucion(cabecera, resolucion, acta, codigo, obligado, descargo)` | Lo que imprime el papel de una resolución, todo desde filas congeladas: nada se vuelve a calcular. | `ModeloDeLaResolucionDeGerencia` |
| `Panel.contar(anio, alDia, hechos)` / `semana(dia)` | Las cifras del panel: actas del año (todas), RIS del año, las notificadas (alguna notificación que surte efecto, y el acta ni anulada ni dejada sin efecto) y las previas sin subsanar ni acta que vencen de lunes a domingo de la semana de `alDia`; `coactiva` null y la `nota`. | `inf-panel` |
| `Plazos.cargados(anio, hoy, parametros)` | Los plazos cargados de un año (`GET /plazos`): las dos filas `PLAZO` vigentes al 1 de enero (a `hoy` en el año en curso) y el `FERIADOS <anio>` que usa `Calendario.de`, o lo que falta con el nombre del 422 de un acto. | — |
| `NotificacionesService.derivar(n, al, hechos)` / `Partes.de(acta, personas, predio)` | La fila de una notificación a un día (la subsanación y el acta, solo si su fecha es ≤ `al`) y las partes del expediente. | — |
| `OrdenDeLosActos.exigir(acto, fecha, hoy, previos)` | Ningún acto antes del que lo origina ni después de hoy; el mismo día vale. 422 que nombra las dos fechas (`ActoFueraDeOrden`). | `OrdenDeLosActos` (#402) |

**Errores.** `FaltanSanciones(acto, faltan)` es el 422 con `faltan` (gemelo de `FaltanArbitrios`; el controlador lo
traduce con `srtm.sanciones.problemaFaltan`); `Resultado<T>(valor, faltan)` lleva la cifra o lo que falta, nunca los
dos, y `exigir(acto)` lanza el 422. `NoProcede` es el 422 de una petición que las reglas no admiten (con
`errors[].field` cuando hay un campo), y `ActoFueraDeOrden` su caso de fechas. Los mensajes citan las fechas en
`dd/MM/yyyy` («no puede fecharse el 03/03/2026: es anterior a la infracción del acta AC-0001, del 04/03/2026»). Un estado que no admite el acto (ya
subsanada, ya anulada, segunda RIS) es `ConflictException` (409).

**Lo que cambia respecto de `rentas`:**
- **SE_REDUCE → 422** «No hay regla de reducción: la fija la ordenanza (D-02b)». En `rentas` se guardaba sin efecto.
- **Una RIS por acta** (`clave_ris`); `rentas` no tenía índice para la ADMINISTRATIVA y admitía dos.
- **Sin `FERIADOS <anio>` → 422 `faltan`**; `rentas` contaba sin feriados cuando el conjunto no los traía.
- **Una notificación subsanada no origina acta** (422), y una con acta no se subsana (422); una vencida tampoco (422,
  donde `rentas` respondía 409).
- **La multa se calcula y se congela** con el CUIS y la UIT; `rentas` tomaba los seis importes tecleados del acta.
- **El alta del acta se publica** (`rentas` solo exponía la consulta) y el filtro `administrado` busca una parte del
  documento o del nombre (en `rentas`, el documento).
- Sin `PAGADA` ni `COACTIVA` (no hay cobranza), y una versión del CUIS para un código sin vigente es su primera versión.

## Anuncios y propaganda

La tasa por anuncios y propaganda se porta del negocio de `rentas` (`RegistrarAnuncio`, `RenovarAnuncio`,
`CesarAnuncio`, `ConsultaDeAnuncios`) al paquete `srtm.anuncios`, que no importa a ningún otro módulo de negocio. El
modelo (`anuncio`, `movimiento_anuncio`) y su regla de escritura están en [Modelo](#modelo).

**El anuncio no se edita.** Su estado no es una columna: se deriva de sus movimientos y del día que se pregunta
(`srtm.anuncios.Anuncios`, funciones puras, sin base ni reloj):
- `estado(movimientos, fecha)`: RETIRADO si hay retiro, CESADO si hay cese, VENCIDO si la vigencia derivada es anterior
  a la fecha, si no VIGENTE. Un movimiento posterior a la fecha no cuenta: el padrón de un día pasado dice lo que decía
  ese día. Sin plazo no vence.
- `vigencia(movimientos, fecha)`: la `vigencia_hasta` del último acto que devenga hasta esa fecha (a igual fecha, el
  último escrito). Una renovación prorroga sin tocar el anuncio, cuya `vigencia_hasta` es la del acto fundacional.
- `ejercicioQueRenueva(vigenciaActual, fechaActo, nuevaVigencia)` (rentas#417): el año de la nueva vigencia, o el del
  acto si no trae plazo. Una prórroga que abarca varios ejercicios no se cobra como uno: 422.
- `devengo(...)`, `devengado(movimientos, alDia)`, `tasa(...)`, `exigirOrden(...)` y `acciones(...)`.

**Los actos** (cada uno pide `observacion` de 5 a 500 caracteres, y permiso de creación sobre su objeto antes de leer
nada: `anuncio` el alta, `movimiento_anuncio` los demás; **403** que lo nombra):
- **Alta** (`POST /api/srtm/anuncios`): escribe el anuncio y su AUTORIZACION en una transacción. La tasa es la fila
  `TASA_ANUNCIO <clase>` vigente a la `fecha_autorizacion` (hoy si no viene), copiada en el movimiento con la fila como
  `parametro`; sin ella, **422** con `faltan: ["TASA_ANUNCIO PANEL 2026"]` y no se escribe nada. Una tasa en el cuerpo
  se ignora. El número `AN-AAAA-NNNNNN` es el correlativo del año de la autorización, bajo el candado consultivo de la
  serie y el año (`srtm.Candados`, `pg_advisory_xact_lock`): el máximo más uno, dentro de la transacción. El único de
  `numero` es la red: si salta, **409** «vuelva a intentarlo».
- **Idempotencia:** la cabecera `Idempotency-Key` (hasta 64 caracteres) va a `anuncio.clave_idempotencia`, que es única.
  El reenvío con la misma clave responde **200** con el anuncio y la autorización de la primera vez y `ya_existia:
  true`, sin devengar otra vez; dos primeras peticiones a la vez se ordenan bajo el candado, y si aun así chocan en el
  único se relee y se responde 200.
- **Renovación** (`POST /api/srtm/anuncios/{id}/renovacion`): devenga el ejercicio que renueva, con la tasa de ese
  ejercicio leída al 1 de enero. **409** si ese ejercicio ya está devengado (lo garantizan los únicos `clave` y
  `referencia_cargo`, así que de dos renovaciones a la vez se escribe una); **422** si el anuncio está cesado o
  retirado, si la prórroga abarca varios ejercicios o si la vigencia termina antes de la fecha; **422** con `faltan`.
- **Cese y retiro** (`/cese`, `/retiro`, con `motivo`): no devengan ni mueven la vigencia, y no deshacen lo devengado.
  **409** si el acto ya existe; **422** un retiro sin cese.
- **Orden de los actos:** ninguno se fecha después de hoy ni antes de la autorización o del último movimiento del
  anuncio (**422** que nombra las dos fechas).

**Las consultas** responden con su fecha: el padrón (`GET /api/srtm/anuncios`) da el estado a `vigentes_a` (hoy por
omisión) y solo los anuncios autorizados hasta ese día; filtra por `contribuyente` (id), `clase`, `estado` (VIGENTE,
VENCIDO, CESADO, RETIRADO) y `q` (número, denominación o dirección), y un filtro desconocido o mal formado es **422**
que lo nombra. La ficha da el estado, la vigencia, lo devengado (`{importe, al_dia}`: la suma de las tasas de los
actos que devengan hasta ese día, no una deuda) y `acciones` (`{renovacion, cese, retiro}`, cada una
`{permitida, motivo}`). `GET /api/srtm/anuncios/tasas?anio` da la tasa de cada clase y las que faltan, nunca un 0.

**Ninguna cifra inventada.** La tasa se carga como `TASA_ANUNCIO` con `import_parametros.py`, una por clase y ejercicio
completo, sin multiplicar por el área (la fórmula por área está pendiente de la ordenanza, D-02b). Los tests usan tasas
ficticias, en un año pasado propio.

**La de Perené** (`model/data/parametros-anuncios-perene.csv`). Perené no tiene ordenanza de tasa por anuncios: su TUPA 2019
cobra un derecho de trámite, y se toma como tasa del ejercicio por decisión del usuario. Desde 2026-01-01 (el PDF no dice
desde cuándo rige el TUPA):
- S/ 90,20 para LETRERO, PANEL, TOLDO, PANTALLA_DIGITAL y GLOBO_AEROSTATICO (proc. 71 C, anuncio de publicidad
  exterior en predio o vía pública);
- S/ 31,00 para BANDEROLA (proc. 73, banderola o pasacalle, cada una, máximo 15 días).

```bash
cd model
python3 import_parametros.py --csv data/parametros-anuncios-perene.csv --dry-run
python3 import_parametros.py --csv data/parametros-anuncios-perene.csv
```

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
- **`tipo_zona`** acepta el nombre, el código `TIPO_UU` o la abreviatura `ABREV_UU` del tipo de unidad urbana
  ([`data/tipos_unidad_urbana.csv`](#tipos-de-unidad-urbana)) y guarda el nombre. La GDB trae el código (`01`, `26`),
  así que basta con `--map tipo_zona=TIPO_UU`. `tipo_via` todavía espera el nombre: los códigos `TIP_VIA` de la GDB
  no se traducen.
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
`habilitacion_urbana` II MESETA, y `kilometro` sale de "Km.:". El tipo de la zona es uno de
[`TIPO_UU`](#tipos-de-unidad-urbana): "ANEXO - CENTRO POBLADO MIRICHARO" se guarda como CENTRO POBLADO y MIRICHARO.
`direccion` guarda el texto del padrón. La secuencia de uso tiene tres dígitos ("001"), también en el portal.

**El Excel y los reportes no se versionan**: traen DNI, nombres y domicilios. `*.xlsx` y `model/reports/` están en
`.gitignore`.

`model/reports/nombres_dudosos.csv` lista los nombres cuya separación en apellidos y nombres conviene revisar a mano
(con coma, con ` Y `, `SN`, menos de 3 palabras o sin nombres). `nombre_completo` siempre guarda el texto original.

## Cargar la Municipalidad de Ica

Ica es una organización aparte en el mismo Core (épica #126). Su padrón llega en `PADRON_CONSTRUCCION.xlsx`, con dos
hojas: `padron` (una fila por predio y contribuyente) y `construcciones` (una fila por sección de nivel). Lo carga
`import_padron_ica.py`, que necesita los campos de padrón externo de #124 en el modelo. Cada paso va primero con
`--dry-run`, y la carga real espera el OK.

El alta completa de la organización, en srtm y en catastro, hasta la brecha (con lo que se hizo en dev el
2026-10-20), está en [docs/develop/alta-organizacion.md](docs/develop/alta-organizacion.md). Para ensayarla sin tocar
dev está el entorno de [develop/pruebas](develop/pruebas/README.md).

1. **Organización:** crear Ica en Core (`POST /api/organizations`, como superadministrador) con su usuario
   administrador. Los pasos siguientes usan su cuenta: `--email` y `--password`, o `WASICHAI_EMAIL` y
   `WASICHAI_PASSWORD`.

   ```bash
   curl -X POST "$WASICHAI_CORE/api/organizations" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"name": "Municipalidad Provincial de Ica", "slug": "ica", "adminEmail": "<correo>", "adminPassword": "<clave>"}'
   ```

   El login es por correo, así que el administrador de Ica necesita un correo distinto al de las otras organizaciones.
   Para la brecha se crea la misma organización en catastro-backend (región COSTA) y se configura su entrada
   `SRTM_CATASTRO_ORGANIZACIONES_ICA_*` (#123).
2. **Modelo y catálogos:**

   ```bash
   cd model
   python3 apply.py --diff && python3 apply.py
   python3 import_catalogos.py --distrito 110101 --municipalidad-json data/ica/municipalidad.json --obras-csv data/ica/obras_complementarias_costa.csv --dry-run
   python3 import_catalogos.py --distrito 110101 --municipalidad-json data/ica/municipalidad.json --obras-csv data/ica/obras_complementarias_costa.csv
   python3 import_parametros.py --dry-run && python3 import_parametros.py
   ```

   - `import_catalogos.py` va sin `--excel`: las vías y las unidades urbanas de Ica, con su código, las carga
     `import_padron_ica.py`.
   - `data/ica/municipalidad.json` trae solo el nombre. El RUC está por confirmar y no se inventa: vacío no se carga, y
     se completa en el admin.
   - Las partidas de obras complementarias de Ica son las de la **costa** (Anexo III.2, verificado contra el PDF):
     `data/ica/obras_complementarias_costa.csv`, las mismas filas que `obras-complementarias-costa-2026.csv` de
     catastro-backend y las mismas llaves que las de selva. Las descripciones de las letras (`categoria_valor`) son las
     mismas en las tres regiones.
3. **Padrón:**

   ```bash
   python3 -I import_padron_ica.py --excel /ruta/PADRON_CONSTRUCCION.xlsx --dry-run    # lee y transforma, no llama a Core
   python3 -I import_padron_ica.py --excel /ruta/PADRON_CONSTRUCCION.xlsx --limit 50  # los primeros 50 predios
   python3 -I import_padron_ica.py --excel /ruta/PADRON_CONSTRUCCION.xlsx             # carga completa
   ```

   - **Opciones:** `--anio` (default 2026), `--limit N` (los primeros N predios, cada uno con todos sus titulares y sus
     niveles), `--workers`, `--report` (default `model/reports/padron_ica.csv`), `--codigos`, `--model`, `--core`,
     `--email` y `--password`.
   - **`-I`:** el xlsx es un archivo de afuera, así que Python no carga nada del directorio actual. El script agrega
     solo su propio directorio.
   - **Salida:** `0` ok; `1` Core rechazó algo (se muestra la fila); `2` el modelo no tiene un campo que el importador
     llena (falta #124), y no se envía nada.
   - **Si Core rechaza algo,** la carga se detiene en ese registro. Lo ya creado queda: se corrige o se quita la fila y
     se vuelve a correr, y la nueva corrida omite lo que ya está.

**Qué crea, en orden.** Lo que Core ya tiene se omite, así que una segunda corrida no crea nada.

1. **Catálogos `via` y `unidad_urbana`:** llevan `codigo`, `tipo`, `nombre` y ubigeo 110101. La llave incluye el
   código, porque Ica da el mismo nombre a varios códigos de vía.
2. **`contribuyente`:** la llave es `codigo` (`codigo_contribuyente`).
   - Un RUC que empieza con 20 es JURIDICA, con `razon_social`.
   - Es SUCESION (con `tipo_contribuyente` SUCESION INDIVISA) el contribuyente cuyo nombre empieza con SUC., SUC o
     SUCESION, o que tiene alguna DJ con condición 2 o 6.
   - El resto es NATURAL, con los apellidos y nombres del xlsx.
   - No se crea domicilio.
   - Un número de documento que ya tiene otro código se carga sin número, porque `numero_documento` es único. Lo
     conserva el código menor.
3. **`predio`:** la llave es `codigo`.
   - Valores fijos: ubigeo 110101, región COSTA y departamento, provincia y distrito ICA.
   - La dirección es la del xlsx. Si viene vacía, se arma con sus partes.
   - Se cargan también `codigo_via`, `codigo_unidad_urbana` y las dos llaves de armonización GIS.
4. **`declaracion_predial`:** la llave es predio + contribuyente + año + secuencia `001`.
   - `valor_autoavaluo` es `valor_predio`.
   - **Condominio:** como en `Condominio.kt`. Con varios titulares, cada uno es CONDOMINO con 100/n %: los céntimos que
     faltan van de a uno a los primeros, así que la suma da 100. Un titular solo conserva su condición si es PROPIETARIO
     UNICO, SOCIEDAD CONYUGAL o POSEEDOR; si no, queda PROPIETARIO UNICO con 100 %.
   - `valor_condominio` es el % del autoavalúo, `deduccion` es 0 y `valor_afecto` es igual a `valor_condominio`.
   - La fecha de adquisición 1900-01-01 es un relleno y se carga vacía.
   - Se cargan `arancel`, `valor_terreno`, `valor_construccion` y `valor_otras_instalaciones`.
5. **`nivel_construccion`:** se cuelga de la DJ de su par (predio, contribuyente).
   - El nivel 0 es SOTANO.
   - Las siete letras se cargan como vienen.
   - Se cargan la clasificación, el valor unitario, el incremento, la depreciación (la del padrón, tal cual) y el valor
     total.
     `valor_unitario_depreciado` no se carga, porque no cuadra con nada.
   - No tiene llave natural: se comparan DJ, nivel, año y área, contando las secciones repetidas.

**Tablas de códigos.** Están en `model/data/ica/codigos.json`: vía, documento, condición, uso, tipo de predio,
material, estado, clasificación y nivel. Ninguna está confirmada (`"confirmado": false`). Cada una dice de dónde sale
su lectura (`evidencia`) y cuáles códigos son una decisión propia (`dudosos`).

- Material, estado de conservación y clasificación se dedujeron con la depreciación del RNT (tablas 01–04,
  `model/data/depreciacion-rnt-2016.csv`, copia de la de catastro-backend) y la antigüedad 2026 − año. Así explican 3 742
  de 3 755 niveles. La clasificación 1–4 es la tabla 01–04, en el orden del enum `clasificacion` de la DJ.
- Un código que la tabla no tiene va al reporte (`codigo_sin_mapeo`). Si el campo es obligatorio en el modelo, la fila
  se rechaza; un tipo de vía desconocido se carga como OTROS.
- Un número o una fecha que no se pueden leer no detienen la carga. El número rechaza su fila al reporte. La fecha se
  carga vacía.

**Reporte** (`--report`, un CSV con `categoria, hoja, fila, codigo_predio, codigo_contribuyente, detalle`). Lleva solo
códigos, sin nombres ni documentos, pero no se versiona.

| Categoría | Qué es |
| --- | --- |
| `rechazada` | La fila no se carga: no encaja en el modelo (por ejemplo, la letra J), es un nivel sin DJ, o cuelga de algo rechazado |
| `condominio_100_n` | Varios titulares con el mismo valor y sin %: se carga 100/n |
| `condominio_valores_distintos` | Varios titulares con valores distintos: también 100/n, pero hay que revisarlo |
| `titulares_en_conflicto` | Un PROPIETARIO ÚNICO comparte el predio con otros titulares (posible transferencia no cerrada) |
| `condomino_solo` | Un condómino (5 o 9) sin otros titulares en el padrón: se carga PROPIETARIO UNICO con 100 % |
| `codigo_sin_mapeo` | Un código que `codigos.json` no tiene, una fila por columna y valor |
| `documento_rellenado`, `documento_sin_numero`, `documento_repetido` | DNI con ceros agregados, tipo sin número, número que ya tiene otro código |
| `documento_en_core` | En la carga: Core ya tiene ese número con otro código, y el contribuyente se envía sin número |
| `fecha_relleno` | `fecha_adquisicion` 1900-01-01, cargada vacía |
| `fecha_invalida` | `fecha_adquisicion` que no se lee como fecha, cargada vacía |
| `direccion_compuesta` | Predio sin dirección, armada con sus partes |
| `valor_predio_no_cuadra` | `valor_predio` distinto de terreno + construcción + otras instalaciones |
| `niveles_vs_padron` | La suma de los niveles de un par no da su `valor_area_construida` o su `area_construida` |
| `depreciacion_vs_rnt` | La columna `depreciacion` difiere del % del RNT para su antigüedad (casi siempre un tramo atrás). Se carga la del padrón |
| `formula_nivel` | `valor_total_nivel` distinto de redondear a 2 decimales (valor unitario + incremento) × (1 − % del RNT) y multiplicar por el área, por más de S/ 0,05. Sin celda del RNT, se usa la columna del padrón |

Con el extracto de 2026-10 (7 829 filas del padrón y 3 762 de construcciones), el `--dry-run` da 588 vías, 161
unidades urbanas, 1 458 contribuyentes, 7 521 predios, 7 829 declaraciones y 3 758 niveles, con 4 filas rechazadas. El
extracto es parte de un padrón de unas 32 000 filas: hasta tener el completo, la brecha de Ica infla los OMISOS.

## Normalizar el padrón importado

Un padrón importado antes de que `import_predios.py` separara los tipos se normaliza en Core, sin volver a importar:

```bash
cd model
python3 normalizar_padron.py --dry-run   # lee Core y escribe el reporte; no cambia nada
python3 normalizar_padron.py             # actualiza lo que el reporte lista
```

- **Predios del padrón** (sin `tipo_via`): separa tipo y nombre de `via` y de `habilitacion_urbana` (una vía sin tipo
  reconocido queda OTROS; una zona, sin tipo) y lee `kilometro` de `direccion`. `direccion` no cambia: el portal la
  rearma, con las abreviaturas del SRTM, al guardar la ubicación.
- **Declaraciones:** `secuencia_uso` con tres dígitos ("1" → "001").
- **Reporte** (`model/reports/normalizar_padron.csv`): cada campo que cambia (antes y después) y lo que conviene mirar a
  mano: tipos no reconocidos, restos de lote que salen de la zona, direcciones guardadas desde el portal que repiten el
  tipo ("JIRON JR. LIMA") y secuencias que coinciden con otra declaración.
- Actualiza cada registro con todos sus campos (el update de Core los reemplaza todos). **Idempotente:** una segunda
  corrida no cambia nada.
- Flags: `--report`, `--workers` (PUTs en paralelo, default 4), `--core`, `--email`, `--password`. Salida: `0` ok, `1`
  Core rechazó algo (se muestra el código del predio o el número de la declaración).

## Migrar los usos del padrón

Las DJ importadas antes de que `import_predios.py` guardara el grupo de uso del padrón como clase tienen el grupo en
`uso` (`RESIDENCIAL - CASA HABITACION`, `TERRENO`…) y ninguna clase. Las migradas antes de que la clase 09 se llamara
GARAGE tienen la clase `ESTACIONAMIENTO`. En una base así, en este orden:

```bash
cd model
python3 apply.py                          # 1. agrega opciones y campos, y quita las opciones sin uso
python3 migrar_usos_padron.py --dry-run   # 2. lee Core y escribe el reporte, sin cambiar nada: revisarlo
python3 migrar_usos_padron.py             #    y migrar
python3 apply.py                          # 3. quita los grupos y ESTACIONAMIENTO, que ya no usa nadie
```

- **El paso 1** conserva los grupos y la clase ESTACIONAMIENTO mientras alguna DJ los usa, con un aviso
  `keep option declaracion_predial.uso …` o `… clase_uso ESTACIONAMIENTO …`.
- **Migración:** `RESIDENCIAL - CASA HABITACION` pasa a `clase_uso` RESIDENCIAL, `sub_clase_uso` UNIFAMILIAR y `uso`
  CASA HABITACIÓN, y `ESTACIONAMIENTO` a `clase_uso` GARAGE. Los otros ocho grupos pasan a `clase_uso` del mismo
  nombre. En todos, `sub_clase_uso` y `uso` quedan vacíos: el padrón no dice más, y el portal los pide al editar la
  DJ.
- Una DJ con `clase_uso` ESTACIONAMIENTO pasa a GARAGE, con su sub clase y su uso como están (`CLASES_RENOMBRADAS`).
- Una DJ que ya tiene clase o sub clase **no se pisa**: queda en el reporte, salvo que su `uso` sea el del catálogo
  bajo esa clase y sub clase (COMERCIAL e INDUSTRIA también son usos del catálogo). Un grupo que siga en uso lo
  conserva el paso 3, con su aviso.
- La salida dice cuántas DJ hay por migrar de cada grupo (`TERRENO: 6562 -> TERRENO`, `ESTACIONAMIENTO: 1 -> GARAGE`).
  El **reporte** (`model/reports/migrar_usos_padron.csv`) tiene una fila por DJ con un grupo o una clase renombrada:
  el grupo (o la clase de antes), la clase, sub clase y uso con que queda y, si no se migra, por qué.
- Actualiza cada DJ con todos sus campos (el update de Core los reemplaza todos). **Idempotente:** una segunda corrida
  no cambia nada.
- Flags: `--dry-run`, `--report`, `--workers` (PUTs en paralelo, default 4), `--core`, `--email`, `--password`.
  Salida: `0` ok, `1` Core rechazó algo (se muestra el número de la declaración, o su id si no tiene).

## Migrar a los valores derivados

Antes de #156, `longitud_frente` era lo que se escribía del frente principal y las filas de las listas no tenían `fila`.
En una base con DJ, después de `apply.py`:

```bash
cd model
python3 migrar_derivados.py --dry-run   # lee Core y cuenta lo que haría, sin cambiar nada
python3 migrar_derivados.py
```

- **Frontis:** donde `frontis_principal` está vacío, copia `longitud_frente`.
- **`fila`:** a cada nivel, obra y otro frente sin `fila` le asigna una. En un grupo de condóminos (mismo predio, año y
  secuencia de uso, DJ vigentes) empareja las filas por su llave natural: el nivel por tipo y piso, la obra por tipo,
  piso y categoría, y el frente por vía y número. La n-ésima fila de una llave en una DJ va con la n-ésima de las demás,
  y todas llevan el mismo uuid. Una fila sin pareja recibe uno propio. Si una fila del conjunto ya tiene `fila`, las
  demás toman esa.
- **No recalcula** áreas ni autoavalúos: las DJ importadas conservan sus valores hasta su primer cambio en el portal.
- La salida dice cuántas DJ tienen frontis por copiar y, por lista, cuántas filas sin `fila` hay, cuántas se emparejan
  y cuántas quedan sueltas. Actualiza cada registro con todos sus campos. **Idempotente:** una segunda corrida no
  cambia nada.
- Flags: `--dry-run`, `--workers` (PUTs en paralelo, default 4), `--core`, `--email`, `--password`. Salida: `0` ok,
  `1` Core rechazó algo.

## Tipos de unidad urbana

La lista de *tipo de unidad urbana* del SRTM (pág. 5) es el dominio `TIPO_UU` del catastro fiscal del MEF: 43 tipos,
cada uno con su abreviatura del dominio `ABREV_UU` (mismo código).
- **Dato:** `model/data/tipos_unidad_urbana.csv` (`codigo,nombre,abreviatura`), en orden alfabético, el de la pág. 5.
  El enum `tipo_unidad_urbana` de `model.json` es la misma lista, y `test_tipos_unidad_urbana.py` lo verifica.
- **Fuente:** la geodatabase del catastro fiscal de Perené (`120302_MD_Perene_ECF.gdb`), leída con
  `ogrinfo -ro <gdb> -fielddomain TIPO_UU` y `-fielddomain ABREV_UU`. Los nombres van como en el dominio, sin tildes,
  salvo dos erratas corregidas: 56 `RESINDENCIAL` es `RESIDENCIAL` y 38 `PROGRAM MUNICIP'AL DE VIVIENDA` es
  `PROGRAMA MUNICIPAL DE VIVIENDA` (el apóstrofo tampoco cabe en una opción ENUM de Core).
- **Abreviaturas:** la dirección que arma el backend (`describir` y `describirUbicacion` en `Reglas.kt`) y la vista
  previa del portal (`forms/direccion.ts` en srtm-ui) escriben cada tipo con la suya: `A.P.V. LOS PINOS`,
  `CER II MESETA`, `C.P. MIRICHARO`. Las dos tablas son iguales y tienen los mismos casos de prueba. 48 y 53 comparten
  `ASOC.VIS.`; al leer una dirección, `ASOC.VIS.` es la 53, ASOCIACION DE VIVIENDA DE INTERES SOCIAL.
- **Padrón** (`import_predios.py`, `import_catalogos.py` y `normalizar_padron.py`, con la misma tabla):
  - `ANEXO` es CENTRO POBLADO, `HABILITACION URBANA` es URBANIZACION y `CENTRO URBANO INFORMAL` es POSESION INFORMAL,
    salvo que el nombre empiece con su propio tipo, escrito entero: "ANEXO - CENTRO POBLADO MIRICHARO" es CENTRO
    POBLADO MIRICHARO y "HABILITACION URBANA SECTOR 10 DE OCTUBRE" es SECTOR 10 DE OCTUBRE.
  - Un tipo escrito entero gana a una abreviatura: en "LOT 2A CENTRO POBLADO SAN FERNANDO DE KIVINAKI", LOT es el lote.
  - `OTROS` no es un tipo: una habilitación sin tipo reconocido se guarda sin tipo.

## Migrar al modelo de los manuales del SRTM

Tres datos del padrón no son como los tiene el SRTM. Se resuelven con los manuales *M01-1-012 Registro Tributario -
Contribuyente*, *M01-1-014 Registro Tributario - Predial* y *M21-1-003 Parámetros*:

- **Sucesión indivisa.** En el SRTM es un *tipo de contribuyente*, no un tipo de documento. Se registra con el
  documento del causante: DNI, pasaporte, CE, PTP / CPP, CI o S/D, y el causante tiene que figurar como fallecido.
  - `tipo_documento` ya no tiene SUCESION.
  - Las sucesiones del padrón (código 08) pasan a SIN DOCUMENTO con `tipo_contribuyente` SUCESION INDIVISA. Sus números
    del 08 son códigos propios del padrón, no documentos; se conservan como clave del importador.
  - El backend rechaza una sucesión indivisa con RUC (`errorTipoDocumento` en `Reglas.kt`).
- **Tipo de predio y condición del predio.** Son dos datos distintos.
  - El *tipo de predio* es Predio Urbano o Predio Rústico, y decide los campos de la ubicación. Ahora es
    `predio.tipo_predio`, con el mismo enum que `domicilio` y `catastro_fiscal`. Antes era `predio.condicion`
    (URBANO / RUSTICO).
  - La *condición del predio* (inafecto, exonerado, monumentos, concesiones forestales, con su documento de sustento)
    es `condicion_especial` de la declaración.
- **Clasificación y estado de construcción.** No son datos de la DJ del SRTM.
  - El SRTM deriva el *grupo de depreciación* del uso del predio, con el parámetro anual *Uso Predio - Depreciación*.
  - Sus niveles de construcción llevan *estado de conservación*, no estado de construcción.
  - Los dos campos se quedan en el modelo como datos heredados del padrón: el portal ya no los muestra ni los edita, y
    el PU los puede seguir leyendo.

En una base con registros de antes de este cambio, en este orden:

```bash
cd model
python3 apply.py                                                 # 1. crea predio.tipo_predio; SUCESION se conserva mientras esté en uso
python3 migrar_modelo_srtm.py --dry-run --borrar-condicion       # 2. lee Core y escribe el reporte, sin cambiar nada: revisarlo
python3 migrar_modelo_srtm.py                                    #    y migrar
python3 apply.py                                                 # 3. quita SUCESION de tipo_documento, que ya no usa nadie
python3 migrar_modelo_srtm.py --dry-run --borrar-condicion       # 4. confirma 0 por migrar
python3 migrar_modelo_srtm.py --borrar-condicion                 #    y borra predio.condicion (su columna con él)
```

- **El reporte** (`reports/migrar_modelo_srtm.csv`) tiene una fila por registro que cambia o que no se puede cambiar,
  con el motivo.
- **`--borrar-condicion`** no borra nada si queda algún predio con `condicion` que no pasó a `tipo_predio`: los
  lista y sale con `1`.
- **Es idempotente:** una segunda corrida no cambia nada.

## Migrar los tipos de unidad urbana

En una base con registros de antes de `TIPO_UU` (con `ANEXO`, `HABILITACION URBANA` u `OTROS`), en este orden:

```bash
cd model
python3 apply.py                                  # 1. agrega los 28 tipos que faltan
python3 migrar_tipos_unidad_urbana.py --dry-run   # 2. lee Core y escribe el reporte, sin cambiar nada: revisarlo
python3 migrar_tipos_unidad_urbana.py             #    y migrar (borra las unidades urbanas repetidas)
python3 apply.py                                  # 3. quita ANEXO, HABILITACION URBANA y OTROS, que ya no usa nadie
```

- **El paso 1** conserva `ANEXO`, `HABILITACION URBANA` y `OTROS` mientras algún registro los use, con un aviso
  `keep option …`, y quita `COMUNIDAD CAMPESINA` y `COMUNIDAD NATIVA`, que nadie usa. Deja la lista en el orden de la
  pág. 5, con las antiguas al final.
- **Migración**, como lee hoy el padrón, en `predio` (`tipo_zona`, `habilitacion_urbana`), `unidad_urbana`
  (`tipo_unidad_urbana`, `nombre`), `domicilio` (`tipo_unidad_urbana`, `unidad_urbana`) y `catastro_fiscal`
  (`tipo_zona`, `zona`):
  - `ANEXO` / CENTRO POBLADO MIRICHARO pasa a CENTRO POBLADO / MIRICHARO, y `ANEXO` / VILLA ANASHIRONI a CENTRO
    POBLADO / VILLA ANASHIRONI;
  - `HABILITACION URBANA` / SECTOR 10 DE OCTUBRE pasa a SECTOR / 10 DE OCTUBRE, / RESIDENCIAL IPANEMA a RESIDENCIAL /
    IPANEMA, y LA LUZ DEL VALLE DE PICHANAKI, 10 DE OCTUBRE y LOS COCOS a URBANIZACION con el mismo nombre;
  - `OTROS` / CENTRO URBANO INFORMAL VISTA ALEGRE pasa a POSESION INFORMAL / VISTA ALEGRE.
- **Unidades urbanas repetidas:** la que quedaría igual a otra (tipo, nombre y ubigeo) se **borra**, y la otra queda.
  Con el padrón de 2026 son 14 `ANEXO` cuyo centro poblado ya estaba como CENTRO POBLADO (MIRICHARO, LA
  ESPERANZA…): el padrón escribe el mismo lugar con y sin ANEXO. Ningún registro enlaza una unidad urbana (predios y
  domicilios guardan el nombre), así que borrarla no deja nada colgado.
- **No se cambia** lo que no tiene un tipo del SRTM (`OTROS` con otro nombre, una `COMUNIDAD NATIVA`): queda en el
  reporte con su tipo antiguo, y el paso 3 conserva esa opción, con su aviso.
- La salida dice, por objeto, cuántos registros cambian de cada tipo a cuál (`ANEXO -> CENTRO POBLADO: 4531`) y
  cuántas unidades urbanas se borran por repetidas (`ANEXO -> CENTRO POBLADO, ya existía (se borra): 14`). El
  **reporte** (`model/reports/migrar_tipos_unidad_urbana.csv`) tiene una fila por registro con un tipo antiguo: la
  acción (`migrado`, `borrado duplicado` o `sin cambio`), el tipo y el nombre antes y después, el id de la unidad
  urbana que queda (`queda_id`) cuando se borra una repetida y, si no cambia, por qué.
- La `direccion` de un predio no cambia: el portal la rearma al guardar la ubicación. La `descripcion` de un domicilio
  se rearma sola al migrarlo; el `domicilio_fiscal` del contribuyente, al volver a grabar el domicilio.
- Actualiza cada registro con todos sus campos (el update de Core los reemplaza todos) y, recién cuando todos pasaron,
  borra las repetidas. `--dry-run` solo escribe el reporte. **Idempotente:** una segunda corrida no cambia nada.
- Flags: `--dry-run`, `--report`, `--workers` (PUTs en paralelo, default 4), `--model`, `--core`, `--email`,
  `--password`. Salida: `0` ok, `1` Core rechazó algo (se muestra la clave del registro que actualizaba o el id del que
  borraba).

## Modelo

Cuarenta y dos objetos (`model/model.json`):
- **Padrón:** `contribuyente`, `predio` y `declaracion_predial`, cargados desde el Excel. Sus nombres de campo siguen el
  *Formato Padrón Municipal Armonización 2026*.
- **Registro de contribuyente del SRTM (fase 1):** `domicilio`, `relacionado`, `medio_contacto` y `sustento`, cada uno
  con una relación obligatoria a `contribuyente`.
- **Declaración jurada predial del SRTM (fase 2):** `transferente`, `nivel_construccion`, `obra_complementaria` y
  `otro_frente`, cada uno con una relación obligatoria a `declaracion_predial`.
- **Catastro fiscal (fase 3):** `catastro_fiscal`, un lote por código CPU, con su polígono.
- **Catálogos:** `ubigeo`, `via`, `unidad_urbana`, `categoria_valor`, `obra_categoria` y `uso_predio`.
- **Parámetros tributarios:** `parametro_tributario`, los valores normativos verificados del repo `normativa` (ver
  [Impuesto predial](#impuesto-predial)).
- **Emisión masiva:** `emision_masiva`, el job de la emisión de un año en segundo plano, y `emision_lote`, sus lotes (ver
  [Emisión masiva](#emisión-masiva)).
- **Arbitrios:** `ordenanza_arbitrio`, `servicio_arbitrio`, `inafectacion_arbitrio`, `cuota_arbitrio` y
  `anulacion_cuota_arbitrio`, y el job de la determinación masiva con sus lotes, `determinacion_arbitrio_masiva` y `determinacion_arbitrio_lote` (ver
  [Arbitrios](#arbitrios)).
- **Infracciones administrativas** (`srtm.sanciones`): `codigo_infraccion` (el CUIS, versionado por vigencia),
  `notificacion_administrativa` (la notificación previa) y su `subsanacion_notificacion`, `papeleta` (el acta, con su
  multa calculada y congelada), `anulacion_papeleta`, `descargo_papeleta`, `resolucion_gerencia` (RIS o RGR) y
  `notificacion_resolucion`.
- **Anuncios y propaganda** (`srtm.anuncios`): `anuncio` y `movimiento_anuncio` (autorización, renovación, cese y
  retiro; la autorización y la renovación devengan la tasa).
- **Brecha de recaudación** (`srtm.brecha`): `brecha_corrida` (una medición de un año) y `brecha_predio` (sus filas,
  con el lote en `lote_geom`). Ver [Brecha de recaudación](#brecha-de-recaudación).

Las garantías de `rentas` sobre las infracciones y los anuncios, y dónde quedan:
- **Unicidad:** Core no tiene unicidad compuesta, así que un campo único guarda las partes unidas: `codigo_infraccion.clave`
  (`familia|codigo|vigencia_desde`), `papeleta.clave` (`familia|numero`), una subsanación, una anulación y una RIS por
  cosa (su `clave` o `clave_ris` es el id de lo que toca), una resolución por descargo (`clave_descargo`),
  `notificacion_resolucion.clave` (`resolucion|intento`), y del movimiento la `clave` por acto (`<anuncio>|AUTORIZACION`,
  `|CESE`, `|RETIRO`, `|RENOVACION|<anio>`) y la `referencia_cargo` (`ANUNCIO-<anuncio>-<anio>`), que choca entre una
  autorización y una renovación del mismo año. Los números de formulario (`notificacion_administrativa.numero`,
  `descargo_papeleta.numero_expediente`), de resolución y de anuncio, y `anuncio.clave_idempotencia`, también son únicos.
- **Una sola versión vigente del CUIS por código:** `codigo_infraccion.clave_vigente` es única y vale `familia|codigo`
  mientras la versión rige; al cerrarse queda vacía (NULL, nunca `""`), y PostgreSQL admite muchos NULL en un único.
- **Lo que se lee se copia:** el acta guarda la UIT (`base_imponible`), los porcentajes y los importes; el descargo y la
  resolución, el plazo (`plazo_texto`); el movimiento, la tasa. La relación (`uit`, `plazo`, `parametro`,
  `codigo_infraccion`) queda como procedencia: `import_parametros.py` actualiza una fila en su lugar.
- **Relaciones opcionales:** las únicas que un registro puede no tener (`test_model.py`, `OPCIONALES`):
  `notif_adm_contribuyente`, `notif_adm_predio`, `papeleta_contribuyente`, `papeleta_predio` (un acta nombra al menos
  uno de los dos: lo revisa la regla), `papeleta_notificacion_previa`, `resolucion_descargo`, `notif_resolucion_plazo`
  (solo la notificación que surte efecto), `anuncio_predio` y `movimiento_parametro` (solo el movimiento que devenga).
  Todas las demás son obligatorias.
- **Largos y rangos:** TEXT y LONG_TEXT no tienen largo en Core; los revisa la regla de escritura (código ≤ 20 en
  mayúsculas, descripción del CUIS ≤ 1000, `porcentaje_uit` mayor que 0 y hasta 10 000, área mayor que 0, lados y cantidad desde 1…).

**Guardia de escritura** (`srtm.GuardiaDeEscritura`). Es un `RecordWriteGuard` de wasichai (ADR-040): Core lo llama
antes de cada escritura de un registro, por cualquier camino (la API genérica `/api/objects/{objeto}/records`, `/admin`,
un servicio de srtm, un enlace entre registros, una transición, una automatización o la plataforma), en la corrutina de
quien escribe y después de sus permisos, de `appendOnly` y de revisar que existan los registros a los que apunta (una
relación a un id que no existe responde **400** antes de llegar a la guardia). La guardia consulta la
`ReglaDeEscritura` del objeto. Cada módulo tiene la suya, como bean de su paquete (`ReglaDeArbitrios` en
`srtm.arbitrios`, `ReglaDeSanciones` en `srtm.sanciones`, `ReglaDeAnuncios` en `srtm.anuncios`), y ninguno importa a
otro:
- **Alta:** los invariantes del registro (los `CHECK` de rentas, los largos y las claves), venga de donde venga; si no,
  **400** que nombra cada campo en `errors[].field`.
- **Cambio, borrado o transición:** **409**. La única excepción: cerrar una versión del CUIS, una vez (su
  `vigencia_hasta` pasa de vacía a una fecha no anterior a `vigencia_desde`, su `clave_vigente` a NULL, y nada más
  cambia). La regla compara la fila entera, normalizada, porque el update de Core reemplaza todos los campos.
  wasichai le presenta a la guardia un enlace o desenlace entre registros como un cambio (UPDATED): enlazar o
  desenlazar un registro de un objeto con regla (sanciones y anuncios, el CUIS incluido) también es **409**.
- **Marca de escritura:** los objetos de sanciones y de anuncios son `soloDesdeElServicio`: solo los escribe la api de
  srtm, que corre sus reglas. `Registros` pone la marca `EscrituraDeSrtm` (un elemento del contexto de la corrutina, el
  patrón `EscrituraDeCaja` de caja-backend) alrededor de cada `create` y `replace`; un cliente HTTP no puede ponerla.
  Sin ella, un alta (o el cierre del CUIS) responde **403**, también a un ADMIN, sin tocar la base, y deja una línea
  WARN `ESCRITURA FUERA DE SRTM RECHAZADA`. La plataforma y las automatizaciones escriben sin usuario y en su propia
  corrutina: tampoco llevan la marca. Los arbitrios no la exigen.

Lo que la guardia no ve: una escritura directa a la base (así escriben sus estados los lotes de la emisión y la
determinación masivas, `srtm.emision.TablasCore`, que no son de estos objetos).

**Parámetros de las infracciones y los anuncios.** Son filas de `parametro_tributario`, con vigencia y doble firma, que
se cargan con `import_parametros.py --csv <archivo>`; su forma se revisa antes de enviar nada (`TIPOS_SANCIONES`,
`TIPOS_ANUNCIO`, y `srtm.sanciones.Llaves` / `srtm.anuncios.Llaves` nombran los mismos tipos):

| `tipo` | `clave` | Valor |
|---|---|---|
| `PLAZO` | `DESCARGO_PAPELETA` o `RG_RECURSO` | `valor_numerico`: los días, entero mayor que 0; `texto`: `DIAS_HABILES` (la única unidad, por ahora) |
| `FERIADOS` | el año (`2026`) | `texto`: los feriados movibles del año, fechas ISO de ese año separadas por coma (vacío si no hay); rige del 1 de enero al 31 de diciembre |
| `TASA_ANUNCIO` | una `clase_anuncio` (`PANEL`…) | `valor_numerico`: la tasa del ejercicio completo, mayor que 0 |

La UIT es la del predial (`UIT`, en `data/parametros-predial.csv`). Ninguno de estos valores está transcrito y
verificado en `normativa` todavía; la tasa de anuncios de Perené viene de su TUPA (ver [Anuncios y
propaganda](#anuncios-y-propaganda)).

**El CUIS** se carga con `import_cuis.py`, desde un CSV con `familia,codigo,descripcion,materia,porcentaje_uit,
porcentaje_uit_segunda,porcentaje_uit_tercera,medida_complementaria,base_legal,vigencia_desde,observacion` y una
cabecera `#` que cita la fuente. Los de Perené están en `data/cuiema/` (ver abajo); los tests usan uno ficticio:

```bash
cd model
python3 import_cuis.py --csv cuis.csv --dry-run   # lee Core y dice qué versiones agregaría y cuáles cerraría
python3 import_cuis.py --csv cuis.csv             # codigo_infraccion: N created, M closed, K skipped, 0 derogated
python3 import_cuis.py --csv cuis.csv --completo  # además deroga los códigos vigentes que el archivo no trae
```

- `familia` vacía es `ADMINISTRATIVA`; el código se pasa a mayúsculas.
- Antes de la primera escritura revisa el archivo (obligatorios, largos, alícuotas de más de 0 a 10 000, fechas,
  versiones repetidas) y lo que hay en Core.
- Es idempotente por `clave`: salta la versión que Core ya tiene. Si Core la tiene con otros valores, sale con `2`: un
  cambio es una versión nueva, con otra `vigencia_desde`.
- Una versión nueva de un código vigente cierra la anterior (`vigencia_hasta` = el día antes, `clave_vigente` = NULL) y se
  agrega; una no posterior a la vigente sale con `2`. Como el CUIS es `soloDesdeElServicio`, cada versión va a
  `POST /api/srtm/infracciones/cuis`, que cierra y agrega en una transacción.
- Con `--completo` el archivo es el CUIS entero desde su única `vigencia_desde` (dos fechas distintas salen con `2`): cada
  código vigente en Core que el archivo no trae se deroga el día antes, por `POST /api/srtm/infracciones/cuis/derogacion`.
  Sin la opción, un código que el archivo no trae no se toca.
- Sale con `0` si todo va bien, `1` si el servidor rechaza algo y `2` si el archivo no encaja (sin escribir nada).

**El CUIEMA de Perené** (`model/data/cuiema/`). Dos normas. Los PDF son escaneos sin texto y se transcribieron en
doble lectura, con las diferencias resueltas sobre la imagen y verificadas contra el PDF:

| Norma | Vigencia | Transcripción | Carga | Fuera |
|---|---|---|---|---|
| OM N.° 01-2021/MDP (anexo, págs. 29-157) | desde 2021-01-26 | `perene-2021-transcripcion.csv` | `perene-2021-cuis.csv` | `perene-2021-excluidas.csv` |
| OM N.° 006-2026-MDP (anexo, págs. 16-33), que deroga la anterior | desde 2026-05-07 | `perene-2026-transcripcion.csv` | `perene-2026-cuis.csv` | `perene-2026-excluidas.csv` |

- La **transcripción** es la norma literal, con sus erratas: código, infracción, calificación, medida cautelar,
  pecuniaria, no pecuniaria y base legal, más la página, la materia (el encabezado de sección) y una nota por cada
  decisión de lectura. La calificación y la medida cautelar no tienen campo en `codigo_infraccion`: quedan aquí.
- La **carga** la deriva `derivar_cuiema.py` (nada se teclea en ella): el % de la columna pecuniaria va a
  `porcentaje_uit`, y su condición («100% si es modalidad A») va entre paréntesis en la descripción. La no pecuniaria
  va a la medida complementaria. La tabla de 2026 no tiene base legal: va la ordenanza. Un código impreso con un
  solo dígito al inicio (`5.02.114`) recibe su cero (`05.02.114`).
- **Fuera** quedan, con su motivo, las filas que no son una versión del CUIS: sin multa pecuniaria, con 0 %, una
  multa que no es un % de la UIT («1% del valor de la obra»), una por unidad («por cada árbol», «por metro³»: el acta
  calcula una sola multa), una cifra ilegible y un código impreso dos veces con multas distintas (`06.01.032`).

```bash
cd model
python3 import_cuis.py --csv data/cuiema/perene-2021-cuis.csv --dry-run
python3 import_cuis.py --csv data/cuiema/perene-2021-cuis.csv
python3 import_cuis.py --csv data/cuiema/perene-2026-cuis.csv --completo --dry-run   # deroga los de 2021 al 2026-05-06
python3 import_cuis.py --csv data/cuiema/perene-2026-cuis.csv --completo
```

El orden importa: 2021 primero, y 2026 con `--completo`. Así los códigos de 2021 rigen hasta el 2026-05-06 y un
acta de esas fechas toma la versión de su día.

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

Para los padrones externos (Ica, #124) ganaron campos opcionales, al final de cada objeto:
- **`predio`:** `codigo_via`, `codigo_unidad_urbana`, `codigo_hu_armonizacion` y `codigo_via_armonizacion` (TEXT),
  los códigos del catastro de origen y del Formato Padrón Municipal Armonización.
- **`declaracion_predial`:** `arancel`, `valor_terreno`, `valor_construccion` y `valor_otras_instalaciones` (DECIMAL),
  la valuación como la trae el padrón. El impuesto se sigue liquidando sobre `valor_autoavaluo`.
- **`nivel_construccion`:** `clasificacion` (el mismo enum de la declaración), `valor_unitario`, `incremento`,
  `porcentaje_depreciacion` y `valor_total` (DECIMAL).
- **`via` y `unidad_urbana`:** `codigo` (TEXT, no único: los del padrón de Perené no lo tienen).

Además, `nivel_construccion.mes_construccion` dejó de ser obligatorio, porque Ica no lo trae: ni Core ni la API del
portal lo exigen (el formulario del nivel en srtm-ui lo sigue pidiendo), y la PU imprime solo el año cuando falta. Los
DTO del portal no conocen estos campos: los escribe el importador por la API de registros de Core, y un guardado del
portal no los borra porque se fusiona con lo guardado (`Registros.replace`). Un condómino agregado desde una
declaración copia también su valuación y la de sus niveles (`PADRON_EXTERNO_DECLARACION` y `PADRON_EXTERNO_NIVEL`).

### Valuación del padrón de origen

Como los DTO no los traen, el portal los lee aparte, en solo lectura (#132):

```
GET /api/srtm/declaraciones/{id}/valuacion-origen
{
  "predio":      { "codigo_via": "V0123", "codigo_unidad_urbana": "U045", "codigo_hu_armonizacion": "HU-01", "codigo_via_armonizacion": "VA-02" },
  "declaracion": { "arancel": 35.50, "valor_terreno": 7100, "valor_construccion": 48250.75, "valor_otras_instalaciones": 1200 },
  "niveles":     { "<id del nivel>": { "clasificacion": "…", "valor_unitario": 402.08, "incremento": 0, "porcentaje_depreciacion": 12, "valor_total": 48250.75 } },
  "modificada":  false
}
```

- Lee los registros guardados de la DJ, su predio y sus niveles con las listas `PADRON_EXTERNO_DECLARACION`,
  `PADRON_EXTERNO_NIVEL` y `PADRON_EXTERNO_PREDIO` (`Condominio.kt`).
- Devuelve solo los campos con valor (`conValor`: ni null ni texto en blanco). Un bloque sin valores es `{}` y un nivel
  sin valores no aparece en `niveles`: una DJ de Perené responde `{"predio": {}, "declaracion": {}, "niveles": {}}`.
- Los controles son los de `RecordService` sobre cada registro que lee, la DJ, su predio y sus niveles, como en
  `GET /api/srtm/declaraciones/{id}` (que lee la DJ, su predio y su contribuyente): 404 si la DJ no existe y 403 si el
  rol no puede leerla. Core no devuelve los campos que el rol no puede leer, así que tampoco aparecen aquí.
- No cambia el impuesto, que sigue saliendo de `valor_autoavaluo`.
- `modificada` (#148) dice si la DJ cambió después de importar su valuación: el portal pone
  `declaracion_predial.origen_modificado` en `true` cuando guarda la DJ o agrega, cambia o quita uno de sus niveles,
  obras o frentes, si la DJ o alguno de sus niveles tiene valuación de origen. No toca el motivo. El campo nace vacío
  (el importador no lo escribe), que cuenta como `false`, y los DTO no lo traen. La copia de un condómino lo hereda de
  su origen, junto con la valuación.
- La ficha del predio (`GET /api/srtm/predios/{id}`) trae el mismo bloque en `valuacion_origen.predio`, para el predio
  sin DJ.

### Autoavalúo sugerido

`GET /api/srtm/declaraciones/{id}/autoavaluo-sugerido` (#149) arma los insumos del motor de valuación con la DJ, su
predio, sus niveles y sus obras activos (`AutoavaluoSugerido.kt`), y los manda a `POST /api/catastro/valuacion` del
catastro-backend de la organización, con la misma cuenta de servicio que la brecha (`ClienteCatastro.valuar`). Devuelve
la respuesta del catastro en snake_case y no guarda nada. Es el mismo motor con el que cada escritura de la DJ calcula
su `valor_autoavaluo` ([Valores derivados de la DJ](#valores-derivados-de-la-dj)): aquí se ve cómo se llegó al valor.

```
{ "anio": 2026, "version": "valuacion-v2", "region": "SELVA", "codigo_municipal": "01-01-0006", "arancel_m2": 45.5,
  "origen_arancel": "CUERPO", "terreno": 9100.0, "construccion": 78781.6, "obras": 8870.61, "autoavaluo": 96752.21,
  "motivo": null, "llave": null,
  "detalle": { "pisos": [ { "piso": "1", "seccion": null, "area_construida": 100, "valor_unitario": 829.28, "incremento": 0.0,
                            "tabla_depreciacion": "01", "antiguedad": 10, "porcentaje_depreciacion": 5,
                            "valor_unitario_depreciado": 787.816, "valor": 78781.6 } ],
               "obras": [ { "codigo_vu": "MUROS PERIMETRICOS O CERCOS|1", "cantidad": 30, "valor_unitario": 457.72,
                            "factor_oficializacion": 0.68, "tabla_depreciacion": "01", "antiguedad": 10,
                            "porcentaje_depreciacion": 5, "valor": 8870.61 } ] } }
```

- **Arancel:** el `arancel` del padrón externo guardado en la DJ (`origen_arancel` = `CUERPO`). Sin él, el catastro
  toma el de su ficha por `codigo_municipal`, que es el `codigo` del predio, la misma llave de la brecha (`FICHA`).
- **Uso:** la DJ guarda los nombres de clase, sub clase y uso; se manda el `codigo` de `uso_predio` que les corresponde.
  Si no hay uno solo, va `null` y el catastro responde su motivo.
- **Niveles:** `piso` es el número (`1`), `S1` un sótano, `M1` un mezanine y `AZ` la azotea. `estado_conservacion`,
  `muros_columnas`, `techos` y `puertas_ventanas` van como `estado`, `muros`, `techos` y `puertas`. La `clasificacion`
  es la del nivel o, si no tiene, la de la DJ (las dos vienen del padrón externo). El área común no se valúa.
- **Obras:** `codigoVu` = `tipo_obra|n`, donde `n` es el número con que empieza la `categoria` (`3. MURO DE …`).
  Sin número, va `null` y el catastro nombra la obra que no valúa. `cantidad` es el total metrado (cantidad × metrado),
  en la unidad de la partida.
- **Edificio** (el 5 % desde el 5.° piso): si el predio tiene `edificacion` o hay un nivel PISO por encima del primero.
- Sin valor, `terreno`, `construccion`, `obras`, `autoavaluo` y `detalle` son `null`, y `motivo` y `llave` dicen por
  qué (`ARANCEL_FALTANTE`, `PARAMETRO_FALTANTE:PORCENTAJE_ACTUALIZACION:2027`…).
- 404 si la DJ no existe o es de otra organización, 409 `SIN_CATASTRO_CONFIGURADO` si la organización no tiene catastro
  y 502 si el catastro no responde o rechaza el pedido.

### Valores derivados de la DJ

Desde #156 el backend calcula lo que la DJ deriva de sus listas. Lo hace después de cada escritura de la DJ (alta, edición,
rectificatoria, DJ desde la anterior, condómino nuevo) y de cada alta, cambio o baja de sus niveles, obras y otros
frentes (`DeclaracionService.recalcular`, `Derivados.kt`):

- **Áreas:** `area_construida` es la suma del área construida de los niveles activos y `area_comun_construida` la de su
  área común, al céntimo. Sin niveles, `0`.
- **Frente:** `frontis_principal` es lo que se escribe del frente principal; `longitud_frente` es ese frontis más el de
  los otros frentes activos (la base `FRONTIS_ML` de los arbitrios). Sin ningún frontis, `null`.
- **Autoavalúo:** lo valúa el catastro de la organización, con el motor del [autoavalúo sugerido](#autoavalúo-sugerido).
  - Si da valor, `valor_autoavaluo` es ese valor y se limpian `autoavaluo_por_revisar` y `autoavaluo_motivo`.
  - Si no da valor, se conserva el autoavalúo anterior y se marca `autoavaluo_por_revisar = true`. `autoavaluo_motivo`
    dice por qué: el `motivo` del catastro (o su `llave`), o `El catastro no respondió: …` si respondió con error. La
    escritura no se rechaza.
  - **Sin catastro configurado** (el 409 del sugerido), el autoavalúo es manual: lo escribe el usuario, como antes, y
    no se marca nada.
- **Condominio:** luego se recalculan condición, %, `valor_condominio` y `valor_afecto` de la DJ y de los otros titulares
  del grupo, como en la edición. Solo se escriben los campos que cambiaron.
- **Réplica a los condóminos (#157):** lo que se declara del predio es de todo el condominio (las otras DJ vigentes del
  mismo predio, año y secuencia; no las anuladas ni las sustituidas).
  - **Filas:** el alta, el cambio y la baja de un nivel, una obra o un otro frente se repiten en la fila con la misma
    `fila` de cada condómino. Si no la tiene, el cambio la crea y la baja no hace nada.
  - **Filas sin `fila`** (no las alcanzó `migrar_derivados.py`): se empareja con la única fila sin `fila` de la misma
    llave natural (la de la migración), que toma la `fila`. Si hay dos o más, esa DJ no se toca y queda un `WARN` en el
    log.
  - **Edición de la DJ:** los campos del predio que copia `delPredio` se escriben en los demás, solo si difieren. Son
    uso, clase, subclase, clasificación, estado, áreas de terreno, frontis principal, habitantes e inhabitabilidad, y
    el autoavalúo solo si es manual. No se copian los derivados ni los del titular (adquisición, documentos, deducción,
    condición especial, %). Una DJ que cambia de predio, año o secuencia no lleva nada a ningún grupo.
  - Cada DJ que recibe una réplica se marca `origen_modificado` si trae la valuación de un padrón externo. Luego se
    recalcula con su propio %.
  - Las réplicas no se vuelven a replicar. La rectificatoria y la DJ desde la anterior copian sus filas sin replicarlas.
- **Una sola vez:** la rectificatoria, la DJ desde la anterior y el condómino nuevo copian todas sus filas y recalculan
  al final (una sola valuación).
- **Lo que manda el portal:** en el alta y en la edición se ignoran `area_construida`, `area_comun_construida`,
  `longitud_frente`, `autoavaluo_por_revisar` y `autoavaluo_motivo`, y también `valor_autoavaluo` mientras haya
  catastro. Un cliente anterior a #156 que manda `longitud_frente` sin `frontis_principal` en el alta declara con él el
  frontis principal. En la edición, un `frontis_principal` que no llega conserva el guardado (se borra mandando `0`) y,
  si la DJ no tiene ninguno (no la alcanzó `migrar_derivados.py`), se toma el `longitud_frente` que llega.
- **¿Editable?:** la ficha (`GET /api/srtm/declaraciones/{id}`) trae `autoavaluo_manual`: `true` si la organización no
  tiene catastro y el usuario escribe el autoavalúo.
- **Recalcular:** `POST /api/srtm/declaraciones/{id}/recalcular` vuelve a valuar una DJ vigente sin cambiarla (el
  catastro ya responde, o ya tiene el parámetro que faltaba) y devuelve la DJ.
- **`fila`:** cada nivel, obra y otro frente tiene una `fila` (uuid), la misma para la misma fila en las DJ de los
  condóminos. La pone el backend: se ignora la que manda el cliente; las copias conservan la de su origen.
- Las DJ importadas conservan sus valores hasta su primer cambio en el portal. Antes, en una base con DJ, se corre
  [`migrar_derivados.py`](#migrar-a-los-valores-derivados).

**`contribuyente`**: una persona natural, persona jurídica o sucesión. `numero_documento` es único.

| Campo | Origen en el Excel |
|---|---|
| `tipo_persona` (NATURAL, JURIDICA, SUCESION) | derivado: RUC ⇒ JURIDICA; código 08 o `SUCESION…`/`SUC.` ⇒ SUCESION; nombre institucional (ASOCIACION, IGLESIA, INSTITUCION, CENTRO…) ⇒ JURIDICA; si no, NATURAL |
| `tipo_documento` | `tipo_doc`: 00 SIN DOCUMENTO, 01 DNI, 04 CARNET DE EXTRANJERIA, 06 RUC, 08 SIN DOCUMENTO (ver abajo) |
| `tipo_contribuyente` | SUCESION INDIVISA para las sucesiones; en los demás importados queda vacío |
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
| `tipo_predio` (PREDIO URBANO, PREDIO RUSTICO) | `tipo_pupr_desc` (PU, PR) |
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
| `clase_uso`, `sub_clase_uso`, `uso` | `grupo_uso_desc`: `RESIDENCIAL - CASA HABITACION` es RESIDENCIAL / UNIFAMILIAR / CASA HABITACIÓN; `ESTACIONAMIENTO`, la clase GARAGE; otro grupo, la clase de su nombre, sin sub clase ni uso |
| `clasificacion` | `clasificacion_predio_desc`, acortado a 64 caracteres sin comas (límite de las opciones ENUM de Core). Heredado del padrón (ver abajo) |
| `estado_construccion` | `estado_construccion_desc`. Heredado del padrón (ver abajo) |
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
- **Capas:** `RentasController` llama a cinco servicios, que usan en proceso los servicios de wasichai
  (`RecordService`, `MetadataService`) a través de `Registros` (las listas, a través de `Listas`) y devuelven DTOs
  tipados:
  - `ContribuyenteService`: el registro de contribuyente y sus cuatro listas (domicilios, relacionados, medios de
    contacto, documentos sustento).
  - `DeclaracionService`: la declaración jurada y sus cuatro listas (transferentes, niveles, obras, otros frentes), el
    predio que registra el portal, el condominio, los condóminos, la anulación y las bajas.
  - `RentasService`: el resumen, las fichas con sus totales y la búsqueda de predios por texto.
  - `PredioService`: "Buscar predios" en el padrón y en el catastro fiscal, los lotes del catastro y las partidas de
    obras complementarias.
  - `CatalogoService`: las opciones ENUM y los catálogos (ubigeo, categorías de valores, usos, vías, unidades urbanas).
  - Aparte, `srtm.pide.DocumentosController` (`DocumentoService`) consulta un DNI a RENIEC ([PIDE RENIEC](#pide-reniec)).
  - Y `srtm.impuesto.LiquidacionController` (`LiquidacionService`) liquida el impuesto predial
    ([Impuesto predial](#impuesto-predial)).
- **Claves JSON:** son los nombres de campo del modelo, en snake_case. `Records` convierte atributos ⇄ DTO con Jackson.
  La liquidación sigue el contrato de la épica de emisión (wasichai/srtm-backend#37), en camelCase
  (`impuestoCalculado`, `minimoAplicado`…).
- **Protección:** como la API vive bajo `/api`, el filtro JWT de core ya la protege. `RecordService` aplica los permisos
  del usuario por objeto y por campo, y valida cada escritura. `Registros` envía solo los campos que el usuario puede
  escribir (un campo bloqueado conserva su valor) y `/catalogos` omite los objetos que su rol no puede leer.
- **Errores:** salen como problem+json, con `errors[].field` igual al nombre del campo.

| Método | Ruta | |
|---|---|---|
| GET | `/api/srtm/resumen` | totales del padrón |
| GET | `/api/srtm/municipalidad` | la cabecera y el mapa del portal: `{nombre, oficina, escudo, extension, centro, lugar}` de la organización del usuario; 403 sin lectura sobre `municipalidad` ([El portal y su municipalidad](#el-portal-y-su-municipalidad)) |
| GET | `/api/srtm/catalogos` | opciones ENUM por objeto y campo, de los objetos que el rol puede leer |
| GET | `/api/srtm/ubigeos` | la lista INEI completa; la cascada departamento → provincia → distrito se hace en el portal |
| GET | `/api/srtm/vias?q&tipo&ubigeo`, `/api/srtm/unidades-urbanas?q&tipo&ubigeo` | sugerencias del catálogo |
| GET, POST | `/api/srtm/contribuyentes?q&page&size` | búsqueda (texto en todos los campos) e inscripción |
| GET, PUT, DELETE | `/api/srtm/contribuyentes/{id}?anio` | la ficha: datos, nº de predios y totales del año; la edición; y la baja (409 si tiene declaraciones o cuotas de arbitrio) |
| GET | `/api/srtm/contribuyentes/{id}/declaraciones?anio` | sus declaraciones, cada una con su predio |
| GET | `/api/srtm/contribuyentes/{id}/liquidacion?anio` | el impuesto predial del año: base, tramos, mínimo, anual y cuotas con su vencimiento; `faltan` si falta un parámetro ([Impuesto predial](#impuesto-predial)) |
| GET, POST | `/api/srtm/contribuyentes/{id}/{lista}` | las listas del contribuyente: `domicilios`, `relacionados`, `medios-contacto`, `sustentos` |
| PUT, DELETE | `/api/srtm/{lista}/{id}` | edición y baja de una fila de esas listas |
| GET, POST | `/api/srtm/predios?q&page&size` | búsqueda y alta |
| GET, PUT, DELETE | `/api/srtm/predios/{id}?anio` | la ficha: datos, nº de titulares y totales del año, y `valuacion_origen.predio` (los códigos del padrón externo con valor, [abajo](#valuación-del-padrón-de-origen)); la edición; y la baja (409 si tiene declaraciones o cuotas de arbitrio) |
| GET | `/api/srtm/predios/{id}/declaraciones?anio` | sus declaraciones, cada una con su contribuyente |
| POST | `/api/srtm/declaraciones` | alta corta, desde la ficha del predio (`contribuyente` y `predio` son ids) |
| POST | `/api/srtm/contribuyentes/{id}/declaraciones-juradas` | presenta una DJ: `{declaracion, predio_id}` sobre un predio del padrón, o `{declaracion, predio}` registrando uno; con `desde` (una DJ de otro año del mismo predio y contribuyente) la copia, con sus listas, al año del cuerpo |
| GET, PUT, DELETE | `/api/srtm/declaraciones/{id}` | la DJ con su predio, su contribuyente y `autoavaluo_manual`; la edición (recalcula sus valores derivados, [abajo](#valores-derivados-de-la-dj)); y la baja (409 si alguna de sus listas tiene filas) |
| POST | `/api/srtm/declaraciones/{id}/recalcular` | vuelve a valuar la DJ y a calcular sus valores derivados sin cambiarla; devuelve la DJ |
| GET | `/api/srtm/declaraciones/{id}/valuacion-origen` | solo lectura: lo que trajo un padrón externo (Ica) a la DJ, su predio y sus niveles, solo los campos con valor ([abajo](#valuación-del-padrón-de-origen)) |
| GET | `/api/srtm/declaraciones/{id}/autoavaluo-sugerido` | solo lectura: la DJ valuada por el motor del catastro-backend de la organización, con su detalle; no guarda nada ([abajo](#autoavalúo-sugerido)) |
| POST | `/api/srtm/declaraciones/{id}/anular` | el descargo: `{motivo_anulacion}` la deja ANULADA |
| POST | `/api/srtm/declaraciones/{id}/rectificar` | la rectificatoria: `{medio_presentacion?, fecha_presentacion?}` crea una DJ nueva, numerada y RECTIFICACION, con lo que declara esta y sus listas (sin la valuación del padrón externo), y deja esta SUSTITUIDA; devuelve la ficha de la nueva |
| POST | `/api/srtm/declaraciones/{id}/condominos` | "Datos de los condóminos": `{contribuyente, porcentaje_condominio}` agrega otro titular del mismo predio, año y secuencia |
| GET, POST | `/api/srtm/declaraciones/{id}/{lista}` | las listas de la DJ: `transferentes`, `niveles`, `obras`, `frentes` |
| PUT, DELETE | `/api/srtm/{lista}/{id}` | edición y baja de una fila de esas listas |
| GET | `/api/srtm/categorias-valor` | las letras de las siete columnas del cuadro de valores, con su descripción |
| GET | `/api/srtm/usos-predio` | los usos del predio del SRTM, cada uno con su clase y su sub clase, en el orden de sus códigos |
| GET | `/api/srtm/obras-categorias?tipo_obra` | las partidas de obras complementarias, con su unidad y valor unitario |
| GET | `/api/srtm/predios/buscar?…` | "Buscar en Tributario" (pág. 13) |
| GET, POST | `/api/srtm/catastro?…` | "Buscar en Catastro Fiscal" (pág. 13), y el alta de un lote |
| GET, PUT | `/api/srtm/catastro/{id}` | un lote del catastro, y su edición (polígono incluido) |
| GET | `/api/gis/objects/{catastro_fiscal\|predio}/features?bbox&geometry=lote_geom` | de wasichai-gis: los lotes del área visible, para el mapa |
| GET | `/api/srtm/documentos/{tipo}/{numero}` | los apellidos y nombres que RENIEC da de un DNI; 404 si no hay datos o no hay convenio ([PIDE RENIEC](#pide-reniec)) |
| GET | `/api/srtm/predios/{id}/pu?anio&contribuyente` | la PU del predio en PDF, inline; 404 sin DJ vigente en el año, 409 con `titulares` si hay varios y falta `contribuyente` ([Emisión de documentos](#emisión-de-documentos)) |
| GET | `/api/srtm/contribuyentes/{id}/hr?anio` | la HR del contribuyente en PDF, inline, con el impuesto y las cuotas de `/liquidacion`; 422 con `faltan` sin parámetros del año, 404 sin DJ vigente en el año ([Emisión de documentos](#emisión-de-documentos)) |
| POST | `/api/srtm/emisiones` `{anio, formato: PDF\|ZIP}` | lanza la emisión masiva del año en segundo plano, por lotes: 202 con el job; 403 sin permiso de creación y de edición sobre `emision_masiva` y de creación sobre `emision_lote`; 409 si ya hay una activa de la misma organización y año ([Emisión masiva](#emisión-masiva)) |
| GET | `/api/srtm/emisiones?anio` | los jobs, el más reciente primero |
| GET | `/api/srtm/emisiones/{id}` | un job: `{id, anio, formato, estado, total, procesados, errores:[{contribuyente, mensaje}], archivo, tamano, mensaje, iniciado, terminado}` |
| GET | `/api/srtm/emisiones/{id}/archivo` | el PDF o ZIP, `attachment; filename="emision-<anio>-<id>.pdf\|zip"`, en streaming; 409 si aún no está TERMINADA; 410 si la retención depuró el archivo |
| DELETE | `/api/srtm/emisiones/{id}` | borra el job, sus lotes y sus archivos (si aún corre, la cancela): 204; 403 sin permiso de borrado sobre `emision_masiva` |
| GET | `/api/srtm/anuncios?contribuyente&clase&estado&vigentes_a&q&page&size` | el padrón de anuncios: página de `{…anuncio, contribuyente_nombre, estado, vigencia_hasta_vigente, vigentes_a}` ([Anuncios y propaganda](#anuncios-y-propaganda)) |
| POST | `/api/srtm/anuncios` (cabecera `Idempotency-Key`) | el alta con su autorización: 201 `{anuncio, movimiento, ya_existia:false}`; 200 en el reenvío con la misma clave; 422 con `faltan` |
| GET | `/api/srtm/anuncios/{id}` | `{anuncio, movimientos, estado, vigencia_hasta_vigente, al_dia, devengado: {importe, al_dia}, acciones}` |
| POST | `/api/srtm/anuncios/{id}/renovacion`, `/cese`, `/retiro` | los actos: 201 con el movimiento; 409 acto o ejercicio repetido; 422 fuera de orden, cesado, sin cese o `faltan` |
| GET | `/api/srtm/anuncios/tasas?anio` | `{anio, tasas: [{clase, tasa, parametro_id, vigencia_desde}], faltan}` |
| GET | `/api/srtm/contribuyentes/{id}/anuncios`, `/api/srtm/predios/{id}/anuncios` | `{al_dia, anuncios: [{…anuncio, estado, vigencia_hasta_vigente}]}` |

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
- **Solo por el portal** (desde el admin no se aplican):
  - Las reglas que leen otros registros: numeración y códigos (`codigo`, `numero_declaracion`, `fecha_registro` del
    contribuyente; `numero_declaracion` de la DJ; `codigo` y `numero_registro` del predio; `codigo` de un domicilio,
    relacionado, medio de contacto, documento sustento o transferente), el domicilio fiscal copiado al contribuyente y
    la regla del único domicilio fiscal activo, el condominio (condición, % y valores de todo el grupo) y la baja
    protegida (409).
  - Las validaciones: documento, nombre o razón social de un relacionado o un transferente, y la fuente PIDE RENIEC
    respaldada por una consulta.
  - El ciclo de la declaración: los valores por defecto de una inscripción, una DJ o una fila nueva, el motivo
    ACTUALIZACIÓN al editar, el estado VIGENTE y la anulación (una DJ anulada es de solo lectura en el portal, no en el
    admin).
  - La `direccion` del predio: `normalizar_padron.py` separa por esta API el tipo de vía del padrón y conserva su texto,
    y el portal la rearma al guardar la ubicación.

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

## Emisión de documentos

wasichai no genera PDF en el servidor: el paquete `srtm.emision` trae su propia infraestructura. De la épica
wasichai/srtm-backend#37, aquí están la PU (Predio Urbano) y la HR (Hoja de Resumen); la emisión masiva
(wasichai/srtm-backend#41) se construye encima.

- **`PdfRenderer.render(template, model)`:** una plantilla de `templates/emision/` a PDF.
  - Thymeleaf standalone (`TemplateEngine` + `ClassLoaderTemplateResolver`, sin MVC: la app es WebFlux) arma el
    HTML, y openhtmltopdf (el fork mantenido `io.github.openhtmltopdf`, sobre PDFBox 3) lo pasa a PDF.
  - Hoja A4, con `templates/emision/base.css` en línea en cada plantilla (variable `css`): márgenes, recuadros con
    título sombreado, grillas de etiqueta y valor, tablas y el pie "Página X de Y".
  - Fuente Liberation Sans embebida (`resources/fonts`, con su licencia SIL OFL 1.1), con las métricas de Arial,
    como los recibos de la municipalidad; Arial no se puede distribuir. DejaVu Sans queda detrás para cualquier glifo
    que le falte. Tildes y ñ salen iguales en cualquier visor.
  - El log de openhtmltopdf va por slf4j, en WARN (`logging.level.com.openhtmltopdf`).
- **`PdfMerger.merge(partes, destino)`:** une PDF en orden con PDFBox, de bytes a un stream o de archivos a un
  archivo. Usa archivos temporales (`MemoryUsageSetting.setupTempFileOnly()`), para que la masiva no llene la memoria.
- **`DocumentosPrediales`:** la única fachada para los endpoints y la masiva.
  - `pu(predioId, contribuyenteId?, anio)` devuelve un `Documento(nombre, bytes)`.
  - `hr(contribuyenteId, anio)` devuelve la HR del contribuyente, también como `Documento`.
  - Lee Core como el usuario, a través de `Registros`, y dibuja el PDF fuera del hilo de la petición.
- **La PU** (`templates/emision/pu.html`, con `HojaPu.kt` que deja cada valor ya formateado):
  - Una por predio y titular, con sus DJ **vigentes** del año. Cada `secuencia_uso` es una sección "Uso N.°".
  - Cabecera institucional ([Cabecera de los documentos](#cabecera-de-los-documentos)), el título y, debajo, el año
    y los N.° de declaración.
  - Contribuyente, ubicación del predio, datos del predio, niveles (con las 7 categorías), obras complementarias y
    los valores declarados (autoavalúo, valor condominio, deducción, valor afecto).
  - Muestra los valores declarados, sin revalorizar. Los niveles y obras INACTIVO no salen.
  - Pie: fecha de emisión y "Página X de Y".
- **Endpoint** `GET /api/srtm/predios/{id}/pu?anio=&contribuyente=` (sin `anio`, el año en curso):
  - Responde `application/pdf` con `Content-Disposition: inline; filename="PU-<codigo_predio>-<anio>.pdf"`.
  - 404 si el predio no tiene DJ vigente ese año (una ANULADA no se emite) o si `contribuyente` no lo declara.
  - 409 si hay más de un titular y falta `contribuyente`. El problem+json agrega
    `titulares: [{id, nombre, documento}]` para elegir.
- **La HR** (`templates/emision/hr.html`, con `HojaHr.kt`), una por contribuyente y año, con la misma cabecera y el
  mismo CSS que la PU:
  - Contribuyente: código, nombre o razón social, documento, domicilio fiscal completo (descripción y distrito /
    provincia / departamento) y la condición especial (pensionista…) que declaren sus DJ, si la hay.
  - Relación de predios: una fila por DJ **vigente** del año (un predio con dos usos tiene dos), con código,
    dirección, uso, autoavalúo, % de propiedad y valor afecto, y una fila de totales.
  - Determinación del impuesto: UIT, base imponible, cada tramo (desde, hasta, alícuota, monto gravado, impuesto), el
    impuesto calculado, el mínimo y el impuesto anual, con la marca "Se aplica el mínimo" cuando corresponde.
  - Cuotas 1 a 4 con monto y vencimiento, la línea "Al contado: <anual> hasta el <vencimiento 1>" y la nota "Las
    cuotas 2 a 4 se reajustan por IPM (TUO LTM art. 15)".
  - Las cifras salen de `LiquidacionService.determinar`, la misma liquidación que responde `/liquidacion`: la HR y el
    endpoint no pueden diferir.
- **Endpoint** `GET /api/srtm/contribuyentes/{id}/hr?anio=` (sin `anio`, el año en curso):
  - Responde `application/pdf` con `Content-Disposition: inline; filename="HR-<codigo>-<anio>.pdf"`.
  - 422 si falta un parámetro tributario del año. El problem+json agrega `faltan` (como en `/liquidacion`).
  - 404 si el contribuyente no existe o no tiene DJ vigente ese año.
- **Tiempo:** unos 50 ms por PU de dos usos solo en dibujar el PDF (`HojaPuTest`). `PuApiTest` mide 100 PU seguidas
  por la API, con las lecturas de Core, y lo imprime en la salida de `integrationTest`.

### Cabecera de los documentos

El PU y la HR abren con la misma cabecera (`templates/emision/cabecera.html`), ordenada como los recibos de la
municipalidad: el escudo a la izquierda, con el nombre en negrita y la oficina centrados; el RUC y la fecha y hora de
emisión (hora de Lima); la gerencia; la dirección; una línea punteada, y el título del documento.

- **Los datos** salen del registro `municipalidad` de la organización (nombre, oficina, RUC, gerencia y dirección),
  que se edita en el admin: `/admin`, objeto *Municipalidad*.
  - Hay uno por organización. Si hay más, vale el más antiguo y el log lo avisa.
  - Sin registro, la cabecera solo lleva un nombre de respaldo: `srtm.municipalidad.nombre`
    (`SRTM_MUNICIPALIDAD_NOMBRE`) si se configura; si no, el nombre de la organización en core. Por defecto está vacío,
    así una instalación con varias municipalidades no imprime el nombre de otra.
  - Una línea vacía no se imprime.
  - Se lee como el usuario que emite, así que su rol necesita permiso de lectura sobre `municipalidad`, como sobre
    `parametro_tributario`.
  - Se guarda `srtm.municipalidad.cache` (1 min) por organización: un cambio en el admin aparece en el PDF dentro de
    ese tiempo.
  - Los datos de `model/data/perene/municipalidad.json` son **provisionales**: el RUC sale del padrón, y la oficina y la
    gerencia son genéricas. Se corrigen en el admin.
- **El escudo** es de cada organización: el campo `escudo` (tipo `IMAGE`, de
  [wasichai-files](#archivos-wasichai-files)) del mismo registro `municipalidad`. Así cada municipalidad de una
  instalación imprime el suyo.
  - PNG o JPG de hasta 1 MiB (`contentTypes` y `maxBytes` del campo en `model.json`): otro tipo es un 400 al subirlo.
    Va dentro de cada PU y HR, también de la emisión masiva, cuyos trabajadores lo leen como el usuario que la creó.
  - Se sube con `POST /api/objects/municipalidad/records/{id}/files/escudo` (multipart, parte `file`), con permiso de
    actualizar `municipalidad`. El admin de wasichai-ui (0.5.0) todavía no sube archivos (wasichai-files no tiene
    paquete de frontend); mientras tanto, por la API:

    ```bash
    curl -X POST -H "Authorization: Bearer $TOKEN" -F "file=@escudo.png" \
      "$WASICHAI_CORE/api/objects/municipalidad/records/<id>/files/escudo"
    ```

  - Se lee como el usuario que emite (necesita leer `municipalidad`) y entra en la misma caché por organización: un
    escudo nuevo aparece dentro de `srtm.municipalidad.cache`.
  - Un escudo del registro que no se puede leer (el almacén ya no lo tiene) o que no es un PNG o JPG legible no rompe
    el documento: se imprime sin escudo y el log lo avisa (WARN).
  - **Respaldo del despliegue:** si el registro no tiene escudo (o no hay registro), vale `srtm.municipalidad.escudo`
    (`SRTM_MUNICIPALIDAD_ESCUDO`), `classpath:…` o `file:…`, solo PNG o JPG. Se lee una vez al arrancar: si no es PNG ni
    JPG, o si no existe, la app no arranca y el error nombra el archivo. Vacío (el valor por defecto), sin escudo. En
    una instalación con varias organizaciones conviene dejarlo vacío: si no, la que no subió el suyo imprime este.

### El portal y su municipalidad

`GET /api/srtm/municipalidad` da al portal (srtm-ui) lo que muestra de la organización del usuario, para no fijar
ninguna municipalidad en el frontend (wasichai/srtm-ui#112):

```json
{"nombre": "MUNICIPALIDAD DISTRITAL DE PERENÉ", "oficina": "…", "escudo": "data:image/png;base64,…",
 "extension": [-75.31, -10.99, -75.18, -10.90], "centro": [-75.245, -10.945],
 "lugar": {"ubigeo": "120302", "departamento": "JUNIN", "provincia": "CHANCHAMAYO", "distrito": "PERENE", "region": "SELVA"}}
```

- **Cabecera:** `nombre`, `oficina` y `escudo` son los de la [cabecera de los documentos](#cabecera-de-los-documentos)
  (`CabeceraDocumento`): el registro `municipalidad`, el mismo nombre de respaldo sin registro y el escudo como data
  URI (o `null`). Pide lectura sobre `municipalidad`, como emitir; sin ella, 403. Comparte la caché por organización.
- **Mapa:** `extension` (`[oeste, sur, este, norte]`, EPSG:4326) cubre los polígonos `lote_geom` de `predio` y de
  `catastro_fiscal` de la organización, y `centro` es su punto medio. Sin lotes, los dos son `null` y el portal abre
  el mapa en un centro neutro (Perú). `municipalidad` no tiene ubigeo ni coordenadas, y `model.json` no cambia: se
  calcula con un `ST_Extent` por tabla (una lectura de las cajas de los polígonos, sin traer los lotes).
- **Lugar por omisión:** `lugar` es el ubigeo que más predios tienen, con el departamento, la provincia, el distrito y
  la región que más se repiten en esos predios. El portal lo propone al registrar un predio, un domicilio, un
  transferente o un lote. Sin predios con ubigeo es `null` y no propone ninguno.
- **Permisos:** `extension` y `lugar` se calculan en SQL sobre las tablas del objeto (como `TablasCore`), fuera de
  `RecordService`; por eso solo cuentan los objetos que el rol puede leer (`predio`, `catastro_fiscal`). Si un cálculo
  falla, el campo sale `null` y el log lo avisa (WARN): la cabecera nunca se pierde por el mapa.

### Archivos (wasichai-files)

srtm instala `wasichai-spring-boot-starter-files` (del BOM de wasichai, 1.3.0) para los campos `FILE` e `IMAGE`; hoy,
solo `municipalidad.escudo`. Detalle del módulo: `docs/modules/files.md` de wasichai.

- **Al arrancar** crea su tabla `stored_files` (y las columnas `custom_fields.file_max_bytes` y `file_content_types`,
  y la función `stored_file_descriptor`), con su propio historial de Flyway (`flyway_history_files`), después de gis.
  La metadata la sigue cargando `apply.py`: el campo se crea como cualquier otro.
- **Los bytes** van a un almacén; la base solo guarda la fila y el registro, el id del archivo.
  - `srtm.archivos.almacen=local` (`SRTM_ARCHIVOS_ALMACEN`, por defecto): en el disco, bajo
    `wasichai.files.local.path` (`SRTM_ARCHIVOS_DIR`, por defecto `./data/archivos`, fuera del repo). En producción, un
    volumen persistente que vean todas las instancias.
  - `srtm.archivos.almacen=s3`: en un bucket (`ArchivosS3`), sin volumen. `srtm.archivos.s3.bucket`
    (`SRTM_ARCHIVOS_S3_BUCKET`, obligatoria), `region`, `endpoint` y `prefijo` (`SRTM_ARCHIVOS_S3_*`) se leen como los
    de [Almacén S3 (K8s)](#emisión-masiva) de la emisión, con las mismas credenciales (la cadena del SDK); al arrancar se
    comprueba el bucket. Las claves son `<prefijo>/<organización>/<archivo>`. El rol necesita `s3:PutObject`,
    `s3:GetObject`, `s3:DeleteObject` y `s3:ListBucket`. Puede ser el mismo bucket de las emisiones con otro prefijo.
  - Nunca `wasichai.files.store=s3`: el almacén S3 del módulo usa el cliente asíncrono del SDK, cuyo netty srtm deja
    fuera del build; sin él la app no arrancaría.
- **Limpieza:** el módulo borra cada hora (`wasichai.files.cleanup.*`) los archivos que ningún registro nombra hace más
  de 24 h: un escudo reemplazado, el de un registro borrado.

## Emisión masiva

Todas las HR y PU de un año (wasichai/srtm-backend#41), en segundo plano, como **un solo PDF** (por contribuyente, su
HR seguida de sus PU) o como **un ZIP** (`<codigo>-<nombre>/HR-<anio>.pdf` y `<codigo>-<nombre>/PU-<codigo_predio>-<anio>.pdf`).
Con `documentos`, cada contribuyente lleva los que se pidan de HR, PU y HLA (ver [Arbitrios](#arbitrios)).
El padrón se reparte en **lotes** que generan en paralelo los **trabajadores** de todas las instancias, coordinados
solo por Postgres, y **una sola instancia ensambla** el resultado (wasichai/srtm-backend#53, #54).

- **Job:** el objeto Core `emision_masiva` (anio, formato, estado, total, procesados, errores como JSON, archivo, tamano,
  mensaje, iniciado, terminado, latido, documentos como JSON). Estados: **PENDIENTE** (se lee el padrón y se crean los lotes) →
  **EN_PROCESO** (los trabajadores generan los lotes) → **ENSAMBLANDO** (una instancia une las partes) →
  **TERMINADA** o **FALLIDA** (con `mensaje`). `procesados` es la suma de los lotes y avanza durante la corrida.
- **`POST /api/srtm/emisiones`** (202): exige permiso de **creación y de edición** sobre `emision_masiva` y de
  **creación** sobre `emision_lote` antes de crear nada (403 problem+json si falta alguno, wasichai/srtm-backend#47).
  Los lotes se crean como quien llamó, y aunque las transiciones del job las escribe el sistema, las de su preparación
  (PENDIENTE → EN_PROCESO, o FALLIDA si falla) quedan en la auditoría como edición (UPDATE) de quien la lanzó: por eso
  la edición. Da **409** si ya hay una emisión PENDIENTE, EN_PROCESO o
  ENSAMBLANDO **de la misma organización y año** ("Ya hay una emisión masiva de <anio> en curso: espere a que
  termine"); dos organizaciones emiten a la vez. Si llegan dos POST a la vez, sigue el más antiguo y el otro da 409.
  En segundo plano, como quien llamó, lee los contribuyentes con DJ **vigentes** del año (por código, sus predios por
  código), los corta en lotes y pasa la emisión a EN_PROCESO con su `total`. Un fallo la deja FALLIDA.
- **Lotes** (objeto Core `emision_lote`): `emision`, `numero` (el orden), `contribuyentes` (JSON), `estado`
  (PENDIENTE, EN_PROCESO, TERMINADO o FALLIDO), `tomado_por`, `latido`, `intentos`, `procesados`, `documentos`,
  `errores` y `parte` (su clave en el almacén). Tienen `srtm.emision.lote` contribuyentes (`SRTM_EMISION_LOTE`, por
  defecto 100). Un trabajador toma el siguiente con `UPDATE ... FOR UPDATE SKIP LOCKED` sobre la tabla física: dos
  trabajadores, de esta u otra instancia, nunca toman el mismo. Las organizaciones se turnan: cada toma empieza por la
  siguiente a la del último lote que tomó la instancia, así las emisiones de dos organizaciones avanzan a la vez. Genera la parte (`GeneradorEmision`: por cada
  contribuyente su HR y sus PU; PDF unido con `PdfMerger` o ZIP en streaming) en sus temporales y la deja en el almacén
  como `emision-<id>/parte-00001.pdf|zip`. Un contribuyente que falla queda en `errores`
  (`{contribuyente: <codigo>, mensaje}`), sin sus documentos, y el resto sigue.
- **Trabajadores** (`TrabajadoresEmision`): al arrancar, cada instancia lanza N corrutinas (`SupervisorJob`,
  `Dispatchers.IO`) con un id de instancia `<hostname>-<uuid del arranque>`, que queda en `tomado_por`. Cada una, en
  su ciclo: falla las emisiones abandonadas, reclama un ensamblado pendiente, o toma un lote. Sin trabajo espera
  `srtm.emision.espera` (`SRTM_EMISION_ESPERA`, por defecto 5s); un POST a esa instancia la despierta antes.
  - **Cuántos:** `srtm.emision.trabajadores` (`SRTM_EMISION_TRABAJADORES`, por defecto `auto`). `auto` =
    `min(núcleos, (memoria máxima − 512 MiB) / 300 MiB)`, entre 1 y los núcleos. Un número se toma tal cual; `0` es una
    instancia que no corre trabajadores (sigue atendiendo POST). Nunca pasa de `spring.r2dbc.pool.max-size − 2` (para
    no dejar sin conexiones al portal; si un número lo pasa, se avisa en el log). El valor y el id de la instancia se
    registran al arrancar.
  - **Lease:** el trabajador renueva el `latido` de su lote cada `lease/4`. Un lote EN_PROCESO cuyo latido venció
    `srtm.emision.lease` (`SRTM_EMISION_LEASE`, por defecto 2m) lo toma otro: así se retoma lo de una instancia que
    murió. Cada toma suma un intento, y cada escritura del lote exige que siga siendo de esa toma: un trabajador que lo
    perdió (lease vencido y retomado, o la emisión cancelada o borrada) aborta, borra su temporal y no toca nada del
    nuevo dueño. Su parte se borra solo si el lote ya no existe o quedó FALLIDO.
  - **Intentos:** un lote tomado más de `srtm.emision.intentos` veces (`SRTM_EMISION_INTENTOS`, por defecto 3) queda
    FALLIDO y sus contribuyentes pasan a `errores` con "lote fallido tras N intentos". Un fallo inesperado al generar
    lo suelta (PENDIENTE) para el siguiente.
- **Identidad:** `RecordService` revisa los permisos, y `asPlatform` (wasichai 0.3) los saltaría; la emisión corre a
  propósito como el usuario que la lanzó. `CurrentUser` solo lee un principal `Jwt` (`sub` y los claims `org`,
  `email` y `roles`). Por eso cada lote se genera **como quien lo creó** (el `created_by` del lote, el mismo usuario que
  lanzó la emisión): `IdentidadEmision` arma en memoria el `Jwt` que armaría el filtro de Core, con la fila y los roles
  actuales de ese usuario. Ese `Jwt` nunca se firma ni sale del proceso. Si el usuario fue borrado o deshabilitado, el
  lote falla sin reintentos ("el usuario que lanzó la emisión ya no existe o está deshabilitado"). La preparación corre
  con la autenticación de la petición (`ReactiveSecurityContextHolder.withAuthentication(...)`); el JWT no se vuelve a
  validar después.
- **Ensamblado** (`EnsambladorEmision`): el trabajador que deja un lote TERMINADO o FALLIDO intenta pasar la emisión a
  ENSAMBLANDO con un `UPDATE` condicional (EN_PROCESO y sin lotes por generar, o ENSAMBLANDO con el latido vencido):
  solo el que obtiene la fila ensambla, y mantiene su latido cada `lease/4`. Trae las partes en orden de `numero`,
  las une (PDF con `PdfMerger`; ZIP copiando las entradas en orden), guarda el archivo final, borra las partes y pasa
  la emisión a TERMINADA con `archivo`, `tamano`, `terminado`, `procesados` y los `errores` de todos los lotes en
  orden. Luego corre la retención. Si el ensamblador muere, otro trabajador lo retoma cuando vence el lease; si el
  archivo final ya estaba guardado, lo usa tal cual. Un fallo deja la emisión FALLIDA con `mensaje` y sin partes. Una
  emisión sin contribuyentes no tiene lotes: el primer ciclo de un trabajador la ensambla vacía.
- **Al arrancar** (`ApplicationReadyEvent`): las emisiones abandonadas pasan a FALLIDA ("interrumpida: el proceso que
  la corría ya no está"): las PENDIENTE cuyo latido venció (su preparación murió) y las EN_PROCESO **sin latido**, que
  son jobs de la versión anterior a los lotes (no tienen lotes y nunca se ensamblan). Corre la retención. Los lotes de
  una emisión viva no se tocan: se retoman al vencer su lease. Cada grupo de trabajadores limpia sus temporales antes
  de empezar. Ya no hay advisory lock (`CerrojoEmision` se fue).
- **Auditoría:** las transiciones del job (PENDIENTE → EN_PROCESO, → ENSAMBLANDO, → TERMINADA o FALLIDA) y la
  retención van directo a la tabla del objeto, con los nombres de columna que da la metadata de Core, y dejan su
  entrada en el log de auditoría con `AuditService`: operación UPDATE, con el usuario que lanzó la emisión cuando es su
  preparación y sin usuario cuando es del sistema. El avance y el latido no se auditan, ni las escrituras de los lotes.
  No avisan a los listeners de `RecordChange`.
- **Varias instancias en Docker:** todas contra la misma base y con el **mismo volumen** en `srtm.emision.dir` (el
  almacén local es compartido), y cada una con sus `temporales` **locales** (nunca compartidos: cada instancia limpia
  los suyos al arrancar). Por ejemplo, con un servicio `backend` en `compose.yml`:

  ```yaml
  backend:
    image: srtm-backend
    environment:
      SRTM_EMISION_DIR: /data/emisiones # el volumen compartido
      SRTM_EMISION_TEMPORALES: /tmp/srtm-emision # dentro del contenedor, de cada réplica
    volumes:
      - emisiones:/data/emisiones
  ```

  y `docker compose up --scale backend=3`. Los lotes se reparten entre las réplicas; cualquiera atiende el POST, la
  consulta y la descarga. Sin volumen compartido (K8s), con `srtm.emision.almacen=s3`: ver **Almacén S3 (K8s)**.
- **Almacén** (`AlmacenEmision`, wasichai/srtm-backend#52): dónde viven los archivos ya generados. El servicio y el
  controlador solo hablan con esta interfaz (`guardar`, `abrir`, `traer`, `borrar`, `existe`, `tamano`, `listar`), así
  que los archivos pueden estar en el disco del servidor o en un almacén de objetos compartido por todas las
  instancias. Se elige con `srtm.emision.almacen` (`SRTM_EMISION_ALMACEN`): `local` (por defecto, `AlmacenLocal`) o
  `s3` (`AlmacenS3`, abajo).
  - **Claves:** relativas, separadas por `/`, de segmentos `[A-Za-z0-9._-]`; nunca vacías, ni `.` ni `..`, ni con `/`
    al inicio (cualquier otra es `IllegalArgumentException`: ninguna clave sale del almacén). El resultado de una
    emisión es `emision-<id>/emision-<anio>-<id>.pdf|zip`; las partes de sus lotes, `emision-<id>/parte-00001.pdf|zip`.
    Todo lo de una emisión cuelga de `emision-<id>/`, así que borrarla es borrar ese prefijo (`listar` + `borrar`).
    `guardar` consume el archivo local (al volver ya no existe), `abrir` devuelve un `Resource` en streaming cuyo
    `contentLength()` no lee el contenido, y `abrir`, `traer` y `tamano` de una clave que no está dan
    `ClaveInexistenteException`.
  - **`AlmacenLocal`:** `<srtm.emision.dir>/<clave>` (`SRTM_EMISION_DIR`, por defecto `./data/emisiones`, fuera de git).
    `guardar` mueve el archivo a un nombre temporal junto a su destino y lo renombra de forma atómica (sirve entre
    sistemas de archivos). Llevan datos personales de todo el padrón: en producción, un volumen persistente y privado.
  - **Archivos del formato anterior:** antes los archivos eran planos, `<dir>/emision-<anio>-<id>.pdf|zip`. Al crearse,
    `AlmacenLocal` mueve cada uno a `<dir>/emision-<id>/emision-<anio>-<id>.<ext>` y deja en el log cuántos movió. Es
    idempotente, y un archivo que no se puede mover se registra y queda donde estaba: nunca impide arrancar. Los
    `.part` y `.partes-` que haya dejado la versión anterior en `<dir>` ya no se limpian (la limpieza corre en
    `temporales`): se borran a mano.
  - **Temporales** `srtm.emision.temporales` (`SRTM_EMISION_TEMPORALES`, por defecto `${java.io.tmpdir}/srtm-emision`):
    el `.part` de un lote, el `.partes-` de su PDF y el `ensamblado-` de un ensamblado. Es local de cada instancia y
    descartable; no necesita persistir. Lo que deja un trabajador que murió se borra cuando los trabajadores vuelven a
    arrancar.
  - **Otra implementación:** implementar `AlmacenEmision` (con su IO bloqueante dentro de `Dispatchers.IO`), extender
    `AlmacenEmisionContractTest` dando su almacén vacío en `nuevo()`, y activarla con su propio valor de
    `srtm.emision.almacen` (`@ConditionalOnProperty(prefix = "srtm.emision", name = ["almacen"], havingValue = "...")`).
    Las claves y su validación (`exigirClave`) son las de arriba para todas.
- **Almacén S3 (K8s)** (`AlmacenS3`, wasichai/srtm-backend#56): con `srtm.emision.almacen=s3` los archivos van a un
  bucket de S3 (o de un servicio con su API, como MinIO) y **no hace falta volumen**: ni `srtm.emision.dir` ni un disco
  compartido. Cada pod solo necesita sus `temporales` locales (un `emptyDir` basta).
  - **Variables:** `srtm.emision.s3.bucket` (`SRTM_EMISION_S3_BUCKET`), obligatoria: sin ella la aplicación no arranca
    ("srtm.emision.s3.bucket es obligatorio con srtm.emision.almacen=s3"). `srtm.emision.s3.region`
    (`SRTM_EMISION_S3_REGION`): vacía, la de la cadena del SDK (`AWS_REGION`, que IRSA ya pone en el pod); contra otro
    endpoint, `us-east-1`. `srtm.emision.s3.endpoint` (`SRTM_EMISION_S3_ENDPOINT`): solo para otro servicio que AWS
    (MinIO), al que se llega por *path-style*; vacía en AWS. `srtm.emision.s3.prefijo` (`SRTM_EMISION_S3_PREFIJO`): las
    claves van debajo (`srtm/emisiones` da `srtm/emisiones/emision-<id>/...`; las `/` de los extremos sobran); vacío, en
    la raíz del bucket. Al arrancar se comprueba el bucket (`HeadBucket`): si no existe o las credenciales no llegan, la
    aplicación no arranca y lo nombra ("No se puede usar el bucket '<bucket>' de srtm.emision.s3.bucket: no existe").
    Así un bucket equivocado nunca parece un almacén con los archivos borrados.
  - **Credenciales:** las de la cadena por defecto del AWS SDK v2, nunca en la configuración de la app. En EKS, **IRSA**:
    el pod corre con una ServiceAccount anotada con `eks.amazonaws.com/role-arn` y el SDK toma el rol solo
    (`AWS_ROLE_ARN`, `AWS_WEB_IDENTITY_TOKEN_FILE`; asume el rol con STS, por eso el módulo `sts` del SDK va en el
    classpath). Si no, un **secreto** de Kubernetes con `AWS_ACCESS_KEY_ID` y
    `AWS_SECRET_ACCESS_KEY` como variables de entorno. El rol necesita `s3:PutObject`, `s3:GetObject`,
    `s3:DeleteObject`, `s3:AbortMultipartUpload` y `s3:ListBucket` sobre el bucket (o su prefijo). Por ejemplo:

    ```yaml
    serviceAccountName: srtm-backend # con IRSA; sin IRSA, el envFrom del secreto
    containers:
      - name: backend
        image: srtm-backend
        env:
          - { name: SRTM_EMISION_ALMACEN, value: s3 }
          - { name: SRTM_EMISION_S3_BUCKET, value: municipalidad-srtm-emisiones }
          - { name: SRTM_EMISION_S3_PREFIJO, value: srtm/emisiones }
          - { name: SRTM_EMISION_TEMPORALES, value: /tmp/srtm-emision } # un emptyDir del pod
        # envFrom: [{ secretRef: { name: srtm-s3 } }] # AWS_ACCESS_KEY_ID y AWS_SECRET_ACCESS_KEY
    ```

  - **Cómo guarda:** un archivo de menos de 16 MiB va en un `PutObject`; uno mayor, por *multipart upload* en partes de
    16 MiB leídas del archivo una a una (los PDF pueden pesar GB), que se aborta si algo falla. Una clave solo aparece
    completa (`PutObject` y `CompleteMultipartUpload` son atómicos): el ensamblado que encuentra el archivo final ya
    guardado puede usarlo tal cual. `traer` descarga a los temporales para el ensamblado, `listar` pagina por prefijo
    y `borrar` de una clave que no está no hace nada.
  - **Checksums:** el SDK manda checksums CRC32 (en S3 sobre HTTPS, al final del cuerpo). Las subidas en partes llevan
    siempre CRC32, fijado en el código (`CreateMultipartUpload` y cada parte); AWS S3 y MinIO lo aceptan. Para un
    almacén compatible que no acepte los checksums al final del cuerpo, `AWS_REQUEST_CHECKSUM_CALCULATION=when_required`
    los apaga en los `PutObject` (archivos de menos de 16 MiB), pero no en las subidas en partes.
  - **Descarga:** pasa por el backend, que mantiene el control de permisos, en streaming desde S3: el `Content-Length`
    sale de un `HeadObject` y el objeto se abre recién al leerlo, sin cargarlo en memoria, en trozos de 64 KiB leídos
    en `boundedElastic` (la lectura de S3 bloquea: nunca en el event loop de Netty). Si el cliente corta, se aborta la
    conexión con S3 en vez de leer el resto. No responde `Range` (siempre el archivo entero). (Una URL prefirmada queda
    como opción a futuro.)
  - **Probar en local:** `docker compose --profile s3 up -d` levanta MinIO (`127.0.0.1:9000`, consola en
    `127.0.0.1:9001`, usuario `srtm`, clave `srtm-minio`) y crea el bucket `srtm-emisiones`. Luego se arranca con
    `SRTM_EMISION_ALMACEN=s3`, `SRTM_EMISION_S3_BUCKET=srtm-emisiones`, `SRTM_EMISION_S3_ENDPOINT=http://localhost:9000`,
    `AWS_ACCESS_KEY_ID=srtm` y `AWS_SECRET_ACCESS_KEY=srtm-minio` (comentadas en `develop/example.env`). La imagen es el
    fork comunitario `pgsty/minio`: MinIO ya no publica `minio/minio` en Docker Hub.
- **Retención**, después de cada ensamblado y al arrancar (`RetencionEmision`): de cada organización y año se conservan
  los últimos `srtm.emision.conservar` archivos (`SRTM_EMISION_CONSERVAR`, por defecto 5), y ninguno más viejo que
  `srtm.emision.dias` días (`SRTM_EMISION_DIAS`, por defecto 0: sin límite). 0 apaga una regla. El job depurado se
  queda, sin `archivo` y con el mensaje "archivo depurado"; su descarga da 410.
- **`DELETE /api/srtm/emisiones/{id}`** (204): exige permiso de borrado antes de tocar nada. Si la emisión sigue activa,
  sus lotes por generar pasan a FALLIDO (sus trabajadores lo notan en su siguiente escritura y abortan); luego se borran
  sus lotes, el job y todo lo que el almacén guarde bajo `emision-<id>/`. Ya no da 409.
- **Descarga** `GET /api/srtm/emisiones/{id}/archivo`: el `Resource` del almacén, en streaming, sin cargar el archivo en
  memoria y con su `Content-Length`, sin leer el archivo. Un archivo en disco (`AlmacenLocal`) lo escribe el
  `ResourceHttpMessageWriter` de Spring, con un canal asíncrono y el largo de lo que responde: los `Range` con 206 (uno
  o varios, `multipart/byteranges`; una descarga cortada puede retomarse) y uno imposible o mal formado con 416. Otro
  recurso, como un objeto de S3, va en trozos de 64 KiB leídos en `boundedElastic`, con el `Content-Length` de `tamano`
  del almacén, y **no responde `Range`**: siempre el archivo entero, con 200. 409 si no está TERMINADA, 410 si se
  depuró, 404 si el almacén ya no tiene la clave. La clave y el nombre (`Content-Disposition`) se arman del job, nunca
  se leen del registro.

### Actualizar desde la versión anterior

La versión con lotes cambia el modelo, los estados y dónde viven los archivos. Para pasar a ella:

1. **El modelo primero**, con el backend nuevo todavía apagado: `cd model && python3 apply.py --dry-run` imprime, sin
   llamar a Core, el modelo entero como si se creara de cero (no lo que cambiaría en una base existente), y sirve para
   revisar `model.json`. Luego `python3 apply.py`, que sobre el modelo existente crea `emision_lote` y su relación y
   añade `emision_masiva.latido` y el estado `ENSAMBLANDO`. Sin `emision_lote`, el backend nuevo no ve la
   organización (le faltan las tablas de los lotes) y su POST falla.
2. **Sin versiones mezcladas:** detener **todas** las instancias viejas (mejor sin ninguna masiva corriendo) antes de
   arrancar las nuevas. Un POST de una instancia vieja pasa a FALLIDA los jobs activos de las nuevas: la versión vieja
   da por huérfano todo job activo que no corra su propio worker. Y una instancia nueva falla el job que una vieja
   esté corriendo (no tiene `latido`). Al arrancar, la versión nueva pasa a FALLIDA los jobs que dejó la anterior
   (PENDIENTE o EN_PROCESO sin `latido`): se vuelven a emitir. Con el almacén local, `AlmacenLocal` mueve al arrancar los archivos planos de la versión anterior
   (`<dir>/emision-<anio>-<id>.<ext>`) a su clave.
3. **De local a S3:** los archivos existentes **no se migran solos**: si no se copian al bucket con las mismas claves,
   sus descargas dan 404. Por ejemplo `aws s3 sync <srtm.emision.dir> s3://<bucket>/<prefijo>`. Viniendo directo de la
   versión anterior, arrancar antes una vez con `local` (para que `AlmacenLocal` los pase a sus claves) o copiarlos ya
   como `emision-<id>/emision-<anio>-<id>.<ext>`.
4. **Bucket:** una regla de ciclo de vida que aborte las subidas en partes incompletas (por ejemplo, a los 7 días). Un
   pod que muere a mitad de una subida no puede abortarla, y sus partes se cobran hasta que alguien las borre.

**La versión con wasichai 0.6** no cambia el modelo, pero sus migraciones de core no tienen vuelta atrás: primero
`pg_dump`, y después se detienen las instancias viejas y se arranca la nueva
([wasichai 0.6: qué cambia para srtm](#wasichai-06-qué-cambia-para-srtm)).

**La versión con wasichai 1.2** tampoco cambia el modelo. Core aplica V18, que agrega una columna nullable a
`custom_fields` y no tiene vuelta atrás: igual que arriba, primero `pg_dump`
([wasichai 1.2: qué cambia para srtm](#wasichai-12-qué-cambia-para-srtm)).

**La versión con wasichai 1.3** tampoco cambia el modelo. wasichai-files aplica su V2, que agrega una columna nullable
a `stored_files` y no tiene vuelta atrás: primero `pg_dump`
([wasichai 1.3: qué cambia para srtm](#wasichai-13-qué-cambia-para-srtm)).

**La versión con la HLA** (arbitrios) agrega `emision_masiva.documentos`, que los trabajadores leen al tomar cada
lote. Igual que arriba: `python3 apply.py` antes de arrancar el backend nuevo. Sin ese campo el backend no ve las
tablas de la emisión de la organización y su POST falla. Los jobs anteriores, sin `documentos`, son de HR y PU.

### Medición

Calibrar `srtm.emision.lote` y `srtm.emision.trabajadores` es medir (wasichai/srtm-backend#55). Cada lote lee una sola vez
los parámetros tributarios del año (`ParametrosTributarios.todos()`, como el usuario que creó el lote) y los pasa a
todas sus HR: antes se recargaban en cada contribuyente. La HR individual (`GET /contribuyentes/{id}/hr`) y
`GET /contribuyentes/{id}/liquidacion` los siguen leyendo en cada llamada, y el 422 con `faltan` no cambia. El log
(INFO, `srtm.emision.GrupoTrabajadores`) deja dos líneas:

```text
lote 3 de la emisión <id>: 100 contribuyentes, 187 documentos en 41250 ms (4.5 documentos/s)
emisión <id> de 2026: 11800 contribuyentes, 15900 documentos en 5230.4 s (3.0 documentos/s)
```

- **Por lote:** al terminar cada lote. Los documentos son las HR y las PU que escribió (un contribuyente que falló no
  suma). La duración es la del trabajador con ese lote: leer los parámetros, generar, guardar la parte y cerrar el lote.
  Los documentos/s son **de un trabajador**: con `trabajadores=N` corren N lotes a la vez.
- **Por emisión:** al pasar a TERMINADA. Los documentos son la suma de los lotes y la duración va **desde `iniciado`
  (el POST) hasta el final**: incluye la preparación, la espera entre lotes y el ensamblado. Es el rendimiento real de
  la emisión, el que ve quien la pidió.
- **Cómo leerlas:** si los documentos/s de la emisión se acercan a N veces los de un lote, los trabajadores escalan; si
  no, estorba algo compartido (el pool de conexiones, la base, el disco del almacén, el ensamblado). Un lote muy corto
  deja la preparación y el ensamblado pesando más: suba `srtm.emision.lote`. Uno muy largo reparte mal el final y
  repite más trabajo si un lote se reintenta: bájelo. `srtm.emision.trabajadores` no rinde más allá de los núcleos ni
  del pool de conexiones (ver arriba), y cada uno suma memoria al renderizar.
- **Cómo medir:** una emisión del padrón completo en dev con `SRTM_EMISION_TRABAJADORES=1` y otra con `auto`, anotando
  la máquina (núcleos y RAM) y las dos líneas de la emisión.
- **Medido (2026-10-02), padrón sintético:** 240 contribuyentes y 300 PU (540 documentos, PDF, `lote=20`), en una
  máquina de 4 núcleos (Xeon 2.8 GHz) y 15 GiB de RAM, con la JVM en 4 GiB de heap y Postgres (Testcontainers) en la
  misma máquina, después de una emisión de calentamiento:

  | `trabajadores` | Duración de la emisión | Documentos/s (emisión) | Documentos/s por lote |
  |---|---|---|---|
  | `1` | 23.8 s | 22.7 | 23–26 |
  | `auto` (= 4) | 10.4 s | 51.8 | 14–16 |

  Con 4 trabajadores la emisión rinde 2.3 veces más: cada lote va más lento (comparten núcleos con Postgres y el
  render), pero corren cuatro a la vez. Llevado al padrón completo (unos 11 800 contribuyentes y 15 000 PU, ~26 800
  documentos), serían unos 20 min con `1` y unos 9 min con `auto` en esa máquina. **Falta la cifra medida en dev con el
  padrón real** (wasichai/srtm-backend#55): ahí la base y el almacén están en otra máquina y las PU tienen más usos.

## Tests

```bash
./gradlew build             # ktlint + tests unitarios (mapeo y reglas del portal)
./gradlew integrationTest   # smoke test y API del portal contra Testcontainers postgis/postgis:18-3.6 (o WASICHAI_TEST_DB_*)
cd model && python3 apply.py --validate-only && python3 -m unittest -v
yarn format:check           # prettier: yaml y json, model.json incluido
```

- **Unitarios:** las reglas puras (`ReglasTest`, `CondominioTest`, `AnulacionTest`, `MotivoTest`,
  `ImpuestoPredialTest` y `VencimientosTest`, con los parámetros de `model/data/parametros-predial.csv`…; las de
  `srtm.sanciones`: `MultasTest`, `NotificacionesTest`, `PlazosTest`, `CuisTest`, `ProcedimientoTest`, `RecursosTest`,
  `OrdenDeLosActosTest`, `ExpedientesTest`, `HojaResolucionTest` y `PanelTest`, con cifras ficticias), la guardia
  de escritura (`GuardiaDeEscrituraTest`, `ReglaDeSancionesTest`, `ReglaDeAnunciosTest`, y `GuardiaDeEscrituraApiTest` en
  integración), los anuncios (`AnunciosTest`, los casos de `AnuncioYSusActosTest` de rentas, y `AnunciosApiTest` en
  integración: el alta, la idempotencia, diez altas a la vez, dos renovaciones a la vez, los actos, el padrón a un
  corte, la guardia y los permisos), `Records`,
  `Registros`, `srtm.pide` (`PideReniecTest`, contra un servidor local) y `srtm.emision` (`PdfRendererTest`,
  `PdfMergerTest`, `HojaPuTest`, `HojaHrTest`, `GeneradorEmisionTest`, que leen el PDF de vuelta con PDFBox; `AlmacenLocalTest`, que corre el contrato
  de `AlmacenEmision` sobre un directorio temporal).
- **Integración** (`@Tag("integration")`): `SrtmSmokeTest` y las clases `*ApiTest`, que llaman a la API del portal
  sobre la app entera y PostGIS. Heredan de `SrtmApiTest`: el modelo aplicado como lo hace `apply.py`, el token del
  admin de desarrollo y las llamadas. Cada endpoint de `RentasController` y `DocumentosController` tiene un caso feliz
  y uno de error donde aplica, repartidos por tema: `RentasApiTest` (el recorrido completo), `ListasApiTest` (las
  filas de las ocho listas), `DeclaracionJuradaApiTest` (una DJ rechazada no deja nada a medias), `FichasApiTest`,
  `CatalogosApiTest`, `CatastroApiTest`, `CondominioApiTest`, `AnulacionApiTest`, `MotivoApiTest`… Las de
  `srtm.sanciones` heredan de `ConSancionesApiTest` (UIT ficticias de 2041 y de 1991, ningún año 2042 ni 1990 con UIT;
  plazos y feriados ficticios; cada acto por su endpoint, fechado en el pasado porque ningún acto se fecha después de
  hoy; cada clase con sus años): `SancionesCuisApiTest` (catálogo con y sin UIT, versión que cierra la vigente, dos
  versiones a la vez, el cuerpo de `import_cuis.py`), `NotificacionesApiTest` (alta, padrón, frontera #411,
  subsanación, vencidas, por contribuyente), `ActasApiTest` (alta y desglose, la multa congelada al corregir la UIT y
  versionar el CUIS, código que no rige, `faltan`, previa subsanada, acta y subsanación a la vez, fases con su corte,
  filtro de fase igual a la columna, ficha en orden legal, anulación, fichas de contribuyente y predio, API genérica y
  permisos), `ResolucionesApiTest` (1993–1996: descargo en plazo y tardío, `faltan` de plazos y feriados, correlativos
  a la vez, una RIS por acta, SE_REDUCE, sin efecto, el papel congelado, notificación y exigibilidad) y `PanelApiTest`
  (1997–1999: las cifras del año con sus extremos, la semana de lunes a domingo, `coactiva` null y la `nota`, parámetros
  y permisos) y `AjustesApiTest` (1985–1989: no se notifica la resolución de un acta anulada, ni de una dejada sin efecto salvo la
  resolución que la dejó sin efecto, con
  `acciones.notificacion` y sin contarla en `notificadas`; el `detalle` legible; las `partes`; `q`; el padrón a una
  fecha pasada; `GET /plazos`).
- **Trabajadores de la emisión masiva en los tests:** `src/test/resources/application.properties` fija
  `srtm.emision.trabajadores=0` para toda la corrida (gana sobre `application.yml`; un `@TestPropertySource` gana sobre
  ambos), así que ningún contexto corre trabajadores salvo el de `EmisionMasivaApiTest` (2), que se cierra al terminar
  su clase (`@DirtiesContext`): un contexto queda en caché mientras corren las demás clases, y sus trabajadores
  tomarían los lotes que esas clases crean para mirarlos. `TrabajadoresEmisionApiTest` juega dos instancias con dos
  `GrupoTrabajadores` propios, cada uno con su `instancia` y sus temporales.
- **Almacén S3 en los tests:** `AlmacenS3Test` corre el contrato de `AlmacenEmision` (más las partes, el aborto y el
  streaming) contra un MinIO de Testcontainers (`pgsty/minio`, uno para toda la corrida), y `EmisionS3ApiTest` corre
  la app con `srtm.emision.almacen=s3` contra él: dos instancias sin disco compartido completan una emisión PDF y una
  ZIP, y una descarga de 101 MiB llega en streaming (más de mil trozos, ninguno de más de 64 KiB). `AlmacenS3ConfigTest`
  (unitario) revisa qué almacén se arma, que no arranca sin bucket o sin llegar a él y que el módulo `sts` (IRSA) está
  en el classpath; `DescargaEmisionTest`, que la descarga de un recurso que bloquea se lee en `boundedElastic`.
- Los de integración corren en el CI de cada PR. Con un Docker remoto no corren en local tal cual (Testcontainers no
  llega a sus puertos): se usa una base de test externa tunelizada, con PostGIS y un nombre que termine en `_test`.
  Cómo, en [docs/develop/README.md](docs/develop/README.md#6-tests).

## wasichai 0.3: lo que srtm no migra

Con `RecordService.asPlatform(organizationId)` (ADR-039) y el bean `ClusterLock` (candados de postgres), 0.3 permite
escribir como la plataforma. srtm no pasó nada a ellos (#76), y esto queda anotado con su motivo:

- **Las máquinas de lotes** (`MaquinaLotes`, `EstadoTrabajos`, `EstadoEmisiones`, `LotesEmision`): toman con
  `FOR UPDATE SKIP LOCKED`; las transiciones son compare-and-set, condicionadas al estado, con el «antes» leído bajo
  bloqueo en la misma sentencia; `procesados` suma lo de los lotes. `RecordService` no puede expresar nada de eso, y un
  `ClusterLock` serializaría lo que hoy reparte `SKIP LOCKED`.
- **`RetencionEmision`:** el `UPDATE ... WHERE archivo IS NOT NULL` es idempotente entre instancias. Con `asPlatform`
  haría falta leer, reemplazar la fila entera y tomar un `ClusterLock`, sin ganar nada. Queda como mejora posible.
- **`IdentidadEmision`:** la emisión y la determinación corren a propósito como el usuario que las lanzó, para que
  core revise sus permisos; `asPlatform` los saltaría. Es una elección, no una carencia.
- **La marca `EscrituraDeSrtm`, no `apiOnly`:** el `apiOnly` de wasichai solo cierra la API REST genérica; la marca
  rechaza además las escrituras de la plataforma y de las automatizaciones, que no pasan por la api de srtm ni corren
  sus reglas.
- **`Duplicados`:** desde 0.3.0 core responde 409 con `errors[].field` (D23), pero en inglés. srtm lo sigue
  sobrescribiendo para dar al portal el mensaje en castellano, y pierde el campo. Seguimiento: traducir el `detail` de
  core o pasar el campo.

## wasichai 0.6: qué cambia para srtm

srtm pasa de wasichai 0.3.2 a 0.6.0. El análisis y los riesgos están en
[el plan](docs/superpowers/plans/2026-10-09-wasichai-0-6-0.md). El código de srtm no cambia: no construye a mano los
servicios de core cuyos constructores cambiaron, no implementa `RecordStore` y nunca toca `audit_log` con SQL.

- **Migraciones al arrancar, sin vuelta atrás.** Core aplica V9 a V17 y `R__audit_purge_role`. Entre ellas:
  - `org_units`;
  - el origen y el índice por usuario de `audit_log`;
  - los triggers que hacen `audit_log` de solo agregar (ADR-054);
  - `MANAGE_TENANTS`;
  - `idempotency_keys`;
  - `users.tokens_valid_after`.

  Volver a 0.3.2 pide restaurar la base. Por eso, antes de arrancar 0.6.0, un `pg_dump`:
  `docker compose exec postgres pg_dump -U srtm -Fc srtm > srtm-antes-de-wasichai-0-6.dump`.
- **El rol que migra tiene que ser dueño de `audit_log`.** Lo pide V13 para crear sus triggers. V12 crea un índice
  sobre `audit_log` sin `CONCURRENTLY`, así que en una base con mucho log conviene arrancar sin tráfico.
- **Un WARN esperado al arrancar.** Con el superusuario de `compose.yml`, `AuditLogOwnershipCheck` avisa que el rol de
  la app puede actuar como dueño de `audit_log`. Separar el rol de migración del rol de la app es opcional
  (`docs/guides/build-your-app.md` de wasichai).
- **Lo que viene apagado y srtm no prende:**
  - el límite de intentos de login (`wasichai.security.login.enabled`);
  - la revocación de tokens (`wasichai.security.jwt.revocation`);
  - una política de claves más estricta que 8 caracteres;
  - `wasichai.organizations.separate-provisioning`;
  - `wasichai.audit.purge-role`.

  Prender el límite de login o la política pide ajustar `SrtmApiTest`: hace un login del admin por test, y las claves
  de sus funcionarios son solo minúsculas y guiones.
- **Lo que cambia solo:**
  - cada respuesta lleva `X-Correlation-Id`;
  - los registros responden `ETag`, aceptan `If-Match` y `PATCH`, y su `updated_at` avanza con `clock_timestamp()`;
  - `spring.reactor.context-propagation` pasa a `auto`;
  - hay rutas nuevas: `/api/org-units`, `/api/auth/me/org-units` y `POST /api/auth/logout`;
  - un campo con `defaultValue` se llena al crear (`model.json` no tiene ninguno);
  - una auto-relación se lista dos veces (`model.json` no tiene ninguna).

  Las máquinas de lotes escriben `updated_at = now()` por SQL. Dos escrituras suyas en una transacción comparten ETag,
  lo que no importa, porque nadie usa `If-Match` sobre lotes ni trabajos.
- **`apply.py`** reserva además `count` y `after`, los nombres del paginado por cursor de core.

## wasichai 1.3: qué cambia para srtm

srtm pasa de wasichai 1.2.0 a 1.3.0. El código de srtm no cambia: `FileStore`, que implementa `ArchivosS3`, sigue
igual, y `Cabecera` abre el escudo con `FileService.open` como el usuario, igual que antes.

- **Migración al arrancar, sin vuelta atrás.** wasichai-files aplica su V2: `stored_files.platform_run`, nullable, sin
  mover datos, y un CHECK que ninguna fila existente viola. Volver a 1.2.0 pide restaurar la base, así que antes un
  `pg_dump`.
- **Lo nuevo que srtm no usa:** `FileService.stage` y `open` dentro de `RecordService.asPlatform` (ADR-068): la
  plataforma sube y adjunta archivos sin usuario.
- **Corrección:** la foto de una tabla relacionada en un documento (wasichai-documents) acepta celdas vacías.

## wasichai 1.2: qué cambia para srtm

srtm pasa de wasichai 0.6.0 a 1.2.0 (cruza 1.0.0 y 1.1.0). El análisis está en
[el plan](docs/superpowers/plans/2026-10-13-wasichai-1-2-0.md). El código de srtm no cambia: los parámetros nuevos de
`CustomField`, `MetadataService` y `RecordService` van al final y tienen valor por defecto, y `RoleQueries.roleNamesOf`
sigue igual. El salto a 1.0 no trae cambios que rompan: es un `release-as`.

- **Migración al arrancar, sin vuelta atrás.** Core aplica V18: `custom_fields.time_zone`, nullable, sin mover datos.
  Volver a 0.6.0 pide restaurar la base, así que antes un `pg_dump`.
- **Caché de metadata** (`wasichai.metadata.cache.ttl`, 5 s por defecto). Cada nodo guarda las definiciones de los
  objetos, workflows, automatizaciones, reglas, relaciones y campos FILE, y el acceso del usuario se lee una vez por
  request. Un nodo olvida lo suyo al cambiar el modelo; los demás tardan hasta el ttl. Con varias instancias, un
  `apply.py` aplicado contra una se ve en las otras a lo sumo 5 s después. `0` apaga la caché. srtm no escribe la
  metadata de core por SQL, así que nada de srtm la deja desactualizada.
- **Máscara de lectura por campo** y política de lectura de los descriptores FILE: solo actúan si un rol tiene
  permisos por campo, y los roles de srtm no los usan.
- **Lo nuevo que srtm no usa:** la zona horaria de un campo DATETIME (`timeZone`, IANA o un desfase fijo).
  `apply.py` todavía no la acepta.

## Siguientes pasos (fuera de este alcance)

- **Datos:** cargar el GeoJSON del catastro fiscal cuando esté disponible. Si se decide, asignar código y número a los
  contribuyentes y declaraciones importados del padrón.
- **Integraciones:** probar PIDE RENIEC con las credenciales del convenio; PIDE SUNAT (RUC) y MIGRACIONES (carné de
  extranjería) con la misma interfaz `ConsultaDocumento`. El fondo del mapa es OpenStreetMap; una capa WMS/WMTS
  municipal se puede publicar con GeoServer.
- **Cálculo y cobranza:** el reajuste IPM de las cuotas, las prórrogas por ordenanza y el derecho de emisión del
  predial; las tasas reales de arbitrios (cuando `normativa` transcriba la ordenanza de Perené), deuda, pagos y
  recibos.
- **Fiscalización:** el flujo (rol y pantallas) que determina de oficio una declaración: medio de determinación
  FISCALIZACIÓN o DE OFICIO y su modificación de oficio.
- **En el modelo:** workflows y plantillas de documentos.
- **Infracciones administrativas** ([Infracciones administrativas](#infracciones-administrativas)):
  - **Cobro:** srtm determina la multa y no la cobra. La orden de cobro y el pago van en `caja-backend`, con la
    referencia estable `PAPELETA-<id>` del acta; con ellos llegan los estados `PAGADA` y `COACTIVA`.
  - **Recaudación y coactiva:** la resolución de multa (RM) y la ejecución coactiva, y el resumen de recaudación de las
    multas; hasta entonces el panel responde `coactiva: null` con su nota.
  - **Datos reales:** el CUIS, la UIT, los plazos y los feriados de Perené (D-02b: la ordenanza y su regla de
    reducción, `SE_REDUCE`, hoy 422), cargados con `import_cuis.py` e `import_parametros.py`; en el repo solo hay
    cifras ficticias.
  - **El papel de la resolución:** la firma digital (D-05; hoy dice «sin firma digital» y se firma a mano) y los
    considerandos, que provee la municipalidad (decisión 8; hoy un párrafo marcado los reserva).
  - **Historia de domicilios:** si se decide, para imprimir y notificar al domicilio fiscal vigente a la fecha de la
    resolución (hoy, el actual).
  - **Tránsito:** la familia `TRANSITO` de `rentas` no se trae (decisión 6); solo `ADMINISTRATIVA`.
- **Anuncios:** la orden de cobro de cada tasa devengada a caja-backend, con la referencia estable
  `ANUNCIO-<id>-<anio>` del movimiento (srtm determina, no cobra); la fórmula por área y lados, pendiente de la
  ordenanza (hoy la tasa es por clase y ejercicio completo); las tasas reales, cuando `normativa` las transcriba.

El frontend web está en `srtm-ui`.
