# Corte analítico y Metabase: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** una foto fechada y reproducible de srtm y catastro por organización (Parquet + `manifiesto.json` en S3,
documentos seudonimizados, sin nombres), las vistas DuckDB que dan las cifras del informe de brechas v3, y Metabase en
el VPS sobre un DuckDB por organización.

**Architecture:** `docs/informes/brecha/corte.py` lee las dos APIs con cuentas de servicio (registros de wasichai con
cursor `after`, y los endpoints de catastro de `contratos.md`), filtra cada tabla a una lista blanca de columnas,
seudonimiza los documentos con HMAC-SHA256, ordena cada tabla por una llave fija y escribe un Parquet por tabla con
pyarrow y un manifiesto con el sha256 de cada archivo. `armar_duckdb.py` carga un corte en un archivo DuckDB por
organización (tablas materializadas + `vistas.sql`). Metabase (imagen Debian propia con el driver DuckDB comunitario,
base de aplicación en Postgres) abre cada archivo de solo lectura y sin acceso externo; `provisionar.py` crea por la
API de Metabase una base, un grupo, una colección y los tableros por organización.

**Tech Stack:** Python 3.11+ (stdlib + `pyarrow==25.0.1` + `duckdb==1.5.5` en `.venv`), `unittest` con servidores
`http.server` falsos, AWS CLI, Docker Compose, Metabase 0.63.19 + `metabase_duckdb_driver` 1.5.5.0, Postgres 18.1.

**Spec:** `/private/tmp/claude-502/-Users-jorge-IdeaProjects-srtm-backend/882b0f7f-a674-480b-a7cb-90fd30468dca/scratchpad/e202/s8.md`
(#205) y `s8b.md` (#206) en el mismo directorio; contratos entre carriles en `contratos.md` (§1, §2, §3 y §5 son los que
lee este carril).

Base de todas las rutas de este plan, salvo que se diga otra cosa: `docs/informes/brecha/` (abreviado `B/`).

## Rulings

- Ruling: srtm se lee con un rol nuevo `ANALITICA` (solo `READ`) y una cuenta de servicio `analitica`; catastro, con la cuenta `srtm` existente (`SRTM_LECTOR`) — este carril no toca catastro y `SRTM_LECTOR` ya lee los cinco objetos y `titular_catastral` (#25) — si catastro quiere distinguir el corte de la brecha en su auditoría, hace falta otra cuenta allá.
- Ruling: `parametro_tributario` y `arancel` se leen por la API genérica de registros, no por `/api/srtm/normativa/**` — un solo permiso `READ` y el mismo cursor `after` que el resto — si Normativa agrega una regla de lectura propia, el corte tiene que replicarla.
- Ruling: parámetros = los que rigen el 1 de enero del año de `FECHA` (`vigencia_desde <= AAAA-01-01 <= vigencia_hasta|∞`, `estado` `VIGENTE` o vacío), de todos los tipos; aranceles = `anio` de `FECHA` con `estado` `VIGENTE` o vacío — el predial se determina al 1 de enero (LTM art. 10) — no captura parámetros que entran a mitad de año (anuncios, sanciones).
- Ruling: URLs, credenciales y clave HMAC van en un archivo por organización fuera del repo, `~/.config/srtm-analitica/<org>.env` (`CLAVE=valor`, modo 0600 obligatorio), no en `parametros.json` — cambian por entorno y son secretos — un archivo más que preparar por organización.
- Ruling: el HMAC se calcula sobre el número normalizado (sin espacios, mayúsculas, solo `[0-9A-Z]`, sin ceros a la izquierda; vacío → `null`) y no sobre el tipo de documento — los tipos se escriben distinto en srtm y catastro, y #204 compara documentos sin ceros a la izquierda — dos personas con el mismo número bajo tipos distintos colisionan (DNI 8 y RUC 11 dígitos lo hacen improbable).
- Ruling: el seudónimo es el HMAC completo en hex (64) y el manifiesto guarda una huella de la clave (`sha256("srtm-analitica-clave:" + clave)[:12]`) — se ve si dos cortes se cruzan con la misma clave sin exponerla — ninguno relevante.
- Ruling: cada tabla exporta una lista blanca de columnas (§ Tablas); se descartan nombres, domicilios, direcciones, `partida_registral`, `condicion_numero_documento`, textos libres y fechas personales; solo salen los contribuyentes que aparecen en una DJ o una cuota del año — minimización (Ley 29733) — un análisis que pida otra columna requiere cambiar el código.
- Ruling: JSON se lee con `parse_float=Decimal`; los `DECIMAL` van a Parquet como `decimal128(38, 10)`, cuantizados `ROUND_HALF_EVEN` — las cifras del v3 se comparan al céntimo — un valor con más de 28 dígitos enteros falla (no existe en estos datos).
- Ruling: Parquet con pyarrow: `compression="zstd"`, `compression_level=3`, `row_group_size=100_000`, `use_dictionary=True`, `write_statistics=True`, `version="2.6"`, `data_page_version="1.0"`; cada tabla ordenada por su llave (`None` primero) — el orden de la API no basta para que dos cortes den el mismo sha256 — subir pyarrow cambia los bytes de los cortes nuevos (por eso va fijo).
- Ruling: el manifiesto no lleva otro reloj que `leido_en`; todo lo demás es función de los datos — dos cortes de los mismos datos se comparan con `diff` salvo esa línea — ninguno.
- Ruling: el corte se escribe en `<carpeta>.parcial/` y se renombra al terminar; una carpeta existente se rechaza sin `REEMPLAZAR=1`, y `make publicar` rechaza un `manifiesto.json` remoto existente sin `REEMPLAZAR=1` — un corte publicado es inmutable — corregir un corte malo pide el flag a propósito.
- Ruling: `FECHA` por omisión es hoy en America/Lima, no puede ser futura, y una `FECHA` `AAAA-01-01` es `tipo: "anual"` (las demás `"mensual"`); un solo cron `0 6 1 * *` (TZ America/Lima) da el mensual y el del 1 de enero — las APIs no tienen lectura «a una fecha», así que el corte del 1 de enero se toma ese día — un 1 de enero perdido no se recupera exacto (`leido_en` muestra el desfase).
- Ruling: la brecha del corte es la corrida `TERMINADA` del año con `terminado` más reciente (empate: mayor `id`); sin ninguna, las dos tablas de brecha van vacías y el manifiesto lo avisa — un corte no lanza corridas — los tableros de brecha salen vacíos hasta que haya una.
- Ruling: la versión de cada backend sale de `VERSION_SRTM` y `VERSION_CATASTRO` del entorno (make las pasa); sin ellas, `null` y un aviso — ningún backend publica su versión por la API (`/api/health` solo da el nombre) — un valor viejo puesto a mano miente.
- Ruling: la versión y la región del motor son las de la respuesta de `/api/catastro/valuaciones`; el manifiesto también copia `version_catastro` de la corrida — son las dos que deciden las cifras — ninguno.
- Ruling: la geometría del lote sale de `loteGeom` de las valuaciones (EPSG:4326 documentado) a `catastro_lote` con el centroide (`lon`, `lat`, 7 decimales) y el GeoJSON canónico — Metabase OSS hace mapas de puntos con lat/lon, no de polígonos de una base DuckDB — no hay mapa de polígonos («si el driver lo permite»: no).
- Ruling: los cambios del catastro se leen enteros del feed `GET /api/catastro/cambios` (contrato §3) a `catastro_cambio`; no se lee `cambio_catastro` de srtm (#203) — su API no está en los contratos entre carriles — el tablero de cambios no trae el Δ ni `SUPERA_5_UIT` de #203 (se agrega después).
- Ruling: tolerancia: un 404 de `/titulares` o `/cambios` da la tabla vacía con su esquema y un aviso; una llave de §1 o un campo de §5 ausente es `null`; `confianza` `null` se muestra `SIN_DATO` en las vistas, sin derivarla en SQL — la regla de confianza es de srtm (#204) — con un srtm viejo todo cae en `SIN_DATO`.
- Ruling: predial en tres columnas, por contribuyente: `declarado` = la ley sobre el autoavalúo del padrón de origen (`valor_terreno + valor_construccion + valor_otras_instalaciones`) × `porcentaje_condominio`/100 − `deduccion`, y `valor_afecto` cuando la DJ no trae ninguno de esos tres; `motor_rentas` = lo liquidado por srtm (`cuota_predial`, última `revision` de cada cuota); `motor_catastro` = `brecha_predio.impuesto_catastro` repartido entre las DJ vigentes del predio en proporción a su `valor_afecto` — es la forma de `predial_3col.py` del informe v3 (xlsx / motor rentas / motor catastro) — si el v3 usó otra base declarada, la columna `declarado` difiere y se corrige solo en `vistas.sql`.
- Ruling: el impuesto de la columna `declarado` lo calcula una macro DuckDB `impuesto_predial` copiada de `ImpuestoPredial.liquidar` (base al céntimo, cada tramo al céntimo, mínimo % de la UIT si hay base) — srtm no guarda el impuesto del padrón de origen — duplica la regla; los valores fijos de la prueba detectan una deriva.
- Ruling: la clase de construcción de un predio es la `clasificacion` de catastro con más área construida sumada; empate: la primera en orden alfabético; sin construcciones: `SIN CONSTRUCCION`; sin clasificación: `SIN CLASIFICACION` — una ficha tiene varias construcciones y la brecha es por predio — un predio mixto se atribuye entero a una clase.
- Ruling: el archivo DuckDB materializa cada Parquet como tabla (no vistas sobre archivos) y corre `vistas.sql`; el `duckdb` de Python va fijo a la versión de DuckDB del driver (1.5.5) — Metabase lo abre sin acceso externo y con el mismo formato de almacenamiento — subir uno obliga a subir el otro.
- Ruling: dependencias en `B/requirements.txt` con `==` (sin hashes) en `B/.venv`, creada por `make venv` — `recaudacion` es solo stdlib y no hay otro patrón con dependencias en el repo — se confía en PyPI.
- Ruling: CI gana un job `brecha` (`make venv pruebas`) y en #206 el paso `docker compose config` — los criterios piden pruebas que corran — 1–2 minutos más de CI.
- Ruling: `B/herramientas/` es un paquete y las pruebas corren con `python -I -m unittest discover -s pruebas -t .`; `corte.py` y los demás scripts de `B/` vuelven a poner su carpeta en `sys.path` como `recaudacion` — `-I` saca la carpeta del script — ninguno.
- Ruling: el cliente HTTP no reintenta, salvo volver a pedir el token una vez ante un 401; cualquier otro error aborta el corte sin escribir ni publicar nada — un corte a medias no debe existir — una red inestable obliga a correrlo otra vez.
- Ruling: Metabase 0.63.19 con el driver 1.5.5.0 («Metabase 63 + DuckDB 1.5.5»; el 1.5.6.0 tiene menos de dos semanas), en una imagen propia `eclipse-temurin:21-jre-jammy` (el driver no funciona en la imagen Alpine oficial), con el sha256 de los dos jar verificado al construir; base de aplicación `postgres:18.1` — versiones fijas que el driver declara compatibles — subir Metabase obliga a revisar el driver.
- Ruling: acceso solo por túnel SSH: el puerto se publica en `127.0.0.1:3310` del VPS, sin proxy en este carril; Metabase sigue pidiendo usuario y clave — nunca expuesto sin autenticación — cada usuario necesita SSH hasta que haya un proxy con TLS.
- Ruling: aislamiento con lo que trae Metabase OSS (no hay «Blocked» ni sandboxing): un grupo por organización con `create-queries: query-builder` en su base y `no` en las demás, «All Users» con `no` en todas; una colección por organización, `read` para su grupo y `none` para los demás; la conexión DuckDB va `read_only` con `init_sql` = `SET enable_external_access = false; SET lock_configuration = true;`, así que ni una consulta nativa puede abrir el archivo de otra organización — si Metabase OSS muestra a un usuario el nombre de otra base, la prueba de humo lo marca y hay que decidir.
- Ruling: los tableros son tarjetas nativas `SELECT * FROM <vista>` definidas en `B/metabase/tableros.json` y creadas por la API de Metabase, idempotentes por nombre dentro de la colección — los tableros quedan versionados en el repo — un cambio de la API de Metabase entre versiones rompe `provisionar.py` (la prueba de humo lo detecta).
- Ruling: después de `make duckdb` el archivo se reemplaza con `os.replace` y se reinicia el contenedor de Metabase — su pool mantiene abierto el archivo viejo — unos dos minutos sin tableros por actualización.
- Ruling: respaldo diario de la base de aplicación con `pg_dump -Fc`, se guardan los 14 más recientes y, si `METABASE_RESPALDOS_S3` está puesta, una copia con `--sse AES256` — tableros y usuarios viven solo allí — espacio en disco.
- Ruling: la prueba de humo usa dos organizaciones ficticias (`ficticia-a`, `ficticia-b`) generadas con `sinteticos.py` y los servidores falsos (`make corte-ficticio`), con la clave HMAC derivada de la semilla — aislamiento comprobable sin datos reales — ninguno.
- Ruling: las vistas que solo usan los tableros (`v_brecha_confiable_por_clase`, `v_cambios_por_mes`, `v_cambios_recientes`, `v_lotes_mapa`, `v_corte`) se agregan a `vistas.sql` en #206 — #205 entrega solo las que pide su spec — ninguno.
- Ruling: el código Python de Metabase vive en `B/herramientas/metabase.py` con scripts finos `B/provisionar.py` y `B/humo.py`; `B/metabase/` solo tiene archivos de despliegue — un directorio `metabase` con `__init__.py` confundiría — ninguno.

## Global Constraints

- Una rama y un PR por issue; ramas apiladas: `feat/metabase-cortes` sale de `feat/corte-analitico`. El controlador hace push y PR; el implementador solo commitea. El PR cierra su issue (`Closes #205`, `Closes #206`).
- Conventional commits en español (`feat(brecha): …`, `docs(brecha): …`, `ci: …`); líneas del cuerpo ≤ 100 caracteres (envolver a ~95; comprobar con `git log -1 --format=%B | awk 'length>100'`).
- Comentarios del código en inglés, con la densidad del código vecino (`docs/informes/recaudacion/herramientas/*.py`).
- NUNCA leer ni procesar datos municipales reales (padrón, libro de comparación, datos personales). Solo fixtures sintéticos. No tocar la base de dev, los stacks del VPS ni otro worktree. Nada se despliega en este carril.
- Pruebas: `cd docs/informes/brecha && make venv pruebas` (= `.venv/bin/python -I -m unittest discover -s pruebas -t . -v`). Sin Testcontainers ni Docker local para las pruebas Python; `docker compose config` no necesita daemon.
- Cada issue actualiza los documentos que nombra: README del informe (#205), `docs/develop/` (#206).
- `.prettierignore` ignora `docs/`; `.github/workflows/ci.yml` sí pasa por prettier (`yarn format:check`: comillas simples, sin `;`).
- Fijos: `duckdb==1.5.5`, `pyarrow==25.0.1`, Metabase `0.63.19`, driver `1.5.5.0` (sha256 `47a0d41ca52ff82a6ce78b0c52b2bdb58b32094405e446734cefc44eb1ddbd07`), `postgres:18.1`, `eclipse-temurin:21-jre-jammy`.
- S3: `s3://sgtm-dev/analitica/<org>/<fecha>/` (`parametros.json` → `publicacion.s3` = `s3://sgtm-dev/analitica`), siempre `--sse AES256`.

## Review Focus

- El token de servicio vence a los 15 minutos (wasichai ADR-043) en medio de un corte grande: se espera que el cliente pida otro una vez y siga. Prueba: Task 2, `test_reautentica_ante_un_401`.
- Una organización sin corrida `TERMINADA`, o un catastro sin `/titulares` ni `/cambios`: se espera un Parquet de cero filas con su esquema completo y vistas que corren igual. Prueba: Task 5, `test_tablas_vacias_conservan_el_esquema`; Task 6, `test_vistas_con_corte_vacio`.
- Documentos de relleno o mal escritos (`00000000`, ` 0045 `, `ab-123`, vacío): se espera `null` o la misma forma normalizada en srtm y en catastro. Prueba: Task 1, `test_normaliza_documentos`.
- Un `DECIMAL` que llega como entero, como texto o con más de 10 decimales: se espera que se cuantice, nunca un `ArrowInvalid`. Prueba: Task 5, `test_decimales_se_cuantizan`.
- Un `make duckdb` que falla a medias o baja un archivo alterado: se espera que el `<org>.duckdb` anterior quede intacto. Prueba: Task 8, `test_falla_deja_la_base_anterior`.

## Tablas del corte

Una fila por tabla: archivo `B/<tabla>.parquet`, origen, columnas (en este orden) y llave de orden. Tipos: `DECIMAL` →
`decimal128(38,10)`, `INTEGER` → `int64`, `BOOLEAN` → `bool`, `DATE` → `date32`, `DATETIME`/instante →
`timestamp("us", tz="UTC")`, el resto `string`. Relaciones (`predio`, `declaracion`, `corrida`…) son el uuid.

| Tabla | Origen | Columnas | Orden |
|---|---|---|---|
| `srtm_predio` | registros `predio` | id, codigo, tipo_predio, sector_catastral, manzana_catastral, manzana, lote, sub_lote, ubigeo, codigo_cpu, codigo_via, habilitacion_urbana | id |
| `srtm_contribuyente` | registros `contribuyente` (solo los citados) | id, codigo, tipo_persona, tipo_documento, documento_hmac, tipo_contribuyente | id |
| `srtm_declaracion` | registros `declaracion_predial?anio=` | id, predio, contribuyente, anio, estado, condicion_propiedad, porcentaje_condominio, uso, clase_uso, clasificacion, area_terreno, area_construida, valor_terreno, valor_construccion, valor_otras_instalaciones, valor_autoavaluo, valor_condominio, deduccion, valor_afecto, arancel, id_aranc, autoavaluo_por_revisar, autoavaluo_motivo, sustituye | id |
| `srtm_nivel` | registros `nivel_construccion` de esas DJ | id, declaracion, numero_piso, anio_construccion, material, estado_conservacion, clasificacion, area_construida, area_comun, muros_columnas, techos, pisos, puertas_ventanas, revestimientos, banos, instalaciones, valor_unitario, porcentaje_depreciacion, valor_total, estado | id |
| `srtm_obra` | registros `obra_complementaria` de esas DJ | id, declaracion, tipo_obra, material, estado_conservacion, anio_construccion, categoria, cantidad, total_metrado, unidad_medida, valor, estado | id |
| `srtm_impuesto_contribuyente` | registros `cuota_predial?anio=` | contribuyente, anio, impuesto_anual, cuotas, fecha_calculo | contribuyente |
| `srtm_brecha_corrida` | registros `brecha_corrida?anio=` (la elegida) | id, anio, estado, version_catastro, iniciado, terminado, total_predios, declarado, catastro, delta_impuesto, delta_arbitrios | id |
| `srtm_brecha_predio` | registros `brecha_predio?corrida=` | id, corrida, predio, codigo_catastro, codigo_predio, categoria, sector, manzana, lote, uso, autoavaluo_declarado, autoavaluo_catastro, delta_autoavaluo, impuesto_declarado, impuesto_catastro, delta_impuesto, delta_arbitrios, estimado, motivo, confianza, confianza_motivo, orden | orden, id |
| `srtm_parametro` | registros `parametro_tributario` | id, tipo, clave, vigencia_desde, vigencia_hasta, valor_numerico, texto, norma, estado | tipo, clave, vigencia_desde, id |
| `srtm_arancel` | registros `arancel?anio=` | id, clave, id_aranc, anio, valor_m2, cod_via, cuadra, lado, norma, estado | id_aranc, id |
| `catastro_ficha` | registros `predio_catastral` | id, codigo_municipal, codigo_cpu, sector, manzana, lote, id_aranc, cod_via, cuadra, lado, uso, area_terreno, longitud_frente, es_edificio, tipo_predio, estado_levantamiento, fecha_levantamiento, vinculo_metodo, vinculo_revisado, cuc, codigo_referencia_catastral | codigo_municipal, id |
| `catastro_construccion` | registros `construccion` | id, predio, codigo_municipal, piso, seccion, area_construida, anio_construccion, material, estado_conservacion, muros_columnas, techos, pisos, puertas_ventanas, revestimientos, banos, instalaciones, techo_incluido, clasificacion | codigo_municipal, piso, seccion, id |
| `catastro_obra` | registros `obra_complementaria` | id, predio, codigo_municipal, codigo_vu, cantidad, anio_construccion, material, estado_conservacion | codigo_municipal, codigo_vu, id |
| `catastro_titular` | `GET /api/catastro/titulares` | codigo_municipal, tipo_documento, documento_hmac, porcentaje, condicion | todas, en ese orden |
| `catastro_valuacion` | `GET /api/catastro/valuaciones?anio=` | codigo_municipal, codigo_cpu, sector, manzana, lote, uso, area_terreno, area_construida, longitud_frente, terreno, construccion, obras, autoavaluo, motivo, llave, estado_levantamiento, fecha_levantamiento, entrega, vinculo_metodo, vinculo_revisado, cuc, codigo_referencia_catastral | codigo_municipal |
| `catastro_lote` | `loteGeom` de las valuaciones | codigo_municipal, lon (`float64`), lat (`float64`), geojson | codigo_municipal |
| `catastro_cambio` | `GET /api/catastro/cambios` | codigo_municipal, objeto, operacion, campos (lista ordenada unida con `,`), en, entrega | en, codigo_municipal, objeto, operacion, campos |

---

## Issue #205: corte analítico en Parquet con manifiesto

**Branch:** `feat/corte-analitico` (ya creada; parte de `main`).

### Task 1: Andamiaje, entorno y seudonimización

**Files:**
- Create: `B/requirements.txt`, `B/.gitignore`, `B/parametros.json`, `B/Makefile`, `B/herramientas/__init__.py`, `B/herramientas/comun.py`, `B/herramientas/seudonimo.py`, `B/pruebas/__init__.py`
- Modify: `.github/workflows/ci.yml` (job nuevo `brecha`)
- Test: `B/pruebas/test_comun.py`, `B/pruebas/test_seudonimo.py`

**Interfaces:**
- Produces:
  - `comun.INFORME: Path`, `comun.REPO: Path`, `class comun.ErrorCorte(Exception)`
  - `comun.parametros() -> dict`; `comun.validar_org(org: str) -> str` (`^[a-z][a-z0-9-]{1,30}$`)
  - `comun.hoy_lima() -> date`; `comun.validar_fecha(texto: str, hoy: date) -> date`
  - `comun.carpeta_corte(base: Path, org: str, fecha: date, reemplazar: bool = False) -> Path` (devuelve `base/org/fecha`; no la crea)
  - `comun.leer_secretos(ruta: Path) -> dict[str, str]`; `comun.REQUERIDOS: tuple[str, ...]`
  - `comun.sha256(ruta: Path) -> str`
  - `seudonimo.normalizar_documento(numero: str | None) -> str | None`
  - `seudonimo.clave_desde_hex(texto: str) -> bytes`; `seudonimo.seudonimo(clave: bytes, numero: str | None) -> str | None`; `seudonimo.huella_clave(clave: bytes) -> str`

- [ ] **Step 1: Write the failing tests**

```python
# test_seudonimo.py
def test_normaliza_documentos(self):
    for crudo, esperado in [("  04587963 ", "4587963"), ("00000000", None), ("ab-12 3", "AB123"),
                            ("", None), (None, None), ("0045", "45")]:
        self.assertEqual(normalizar_documento(crudo), esperado)
def test_mismo_documento_mismo_seudonimo(self):
    k = bytes(range(32))
    self.assertEqual(seudonimo(k, "04587963"), seudonimo(k, "4587963"))
    self.assertRegex(seudonimo(k, "4587963"), r"^[0-9a-f]{64}$")
    self.assertNotEqual(seudonimo(k, "4587963"), seudonimo(bytes(32), "4587963"))
    self.assertIsNone(seudonimo(k, "000"))
def test_clave_de_al_menos_32_bytes(self):
    with self.assertRaises(ErrorCorte): clave_desde_hex("ab" * 31)
    with self.assertRaises(ErrorCorte): clave_desde_hex("zz" * 32)
    self.assertEqual(len(clave_desde_hex("ab" * 32)), 32)
def test_huella_no_revela_la_clave(self):
    k = bytes(range(32))
    self.assertEqual(len(huella_clave(k)), 12)
    self.assertNotIn(huella_clave(k), k.hex())

# test_comun.py
def test_fecha_futura_se_rechaza(self):  # validar_fecha("2026-10-26", date(2026, 10, 25)) -> ErrorCorte
def test_fecha_mal_formada_se_rechaza(self):  # "2026-13-01", "25/10/2026" -> ErrorCorte
def test_org_invalida_se_rechaza(self):  # "Perene", "a", "../x" -> ErrorCorte; "perene", "ficticia-a" pasan
def test_carpeta_dentro_del_repo_se_rechaza(self):  # base = REPO / "tmp" -> ErrorCorte
def test_carpeta_existente_pide_reemplazar(self):  # carpeta con un archivo -> ErrorCorte; reemplazar=True la acepta
def test_secretos_con_permisos_abiertos_se_rechazan(self):  # chmod 0o644 -> ErrorCorte que nombra la ruta
def test_secretos_incompletos_nombran_la_clave_sin_valores(self):
    # archivo 0o600 sin CATASTRO_CLIENT_SECRET y con SRTM_CLIENT_SECRET=s3cr3t
    # -> ErrorCorte cuyo mensaje contiene "CATASTRO_CLIENT_SECRET" y no contiene "s3cr3t"
def test_secretos_ignoran_comentarios_y_blancos(self):  # "# x", "", "A=b=c" -> {"A": "b=c"}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd docs/informes/brecha && make venv pruebas`
Expected: FAIL (`ModuleNotFoundError: herramientas.seudonimo`).

- [ ] **Step 3: Implement**

- `requirements.txt`: `duckdb==1.5.5` y `pyarrow==25.0.1`. `.gitignore`: `__pycache__/`, `.venv/`.
- `parametros.json`: `{"_comentario": …, "publicacion": {"s3": "s3://sgtm-dev/analitica"}, "organizaciones": {"perene": {"nombre": "Municipalidad Distrital de Perené"}, "ica": {"nombre": "Municipalidad Provincial de Ica"}}}`.
- `REQUERIDOS = ("SRTM_URL", "SRTM_CLIENT_ID", "SRTM_CLIENT_SECRET", "CATASTRO_URL", "CATASTRO_CLIENT_ID", "CATASTRO_CLIENT_SECRET", "CLAVE_SEUDONIMO")`. Permisos: rechazar si `st_mode & 0o077`.
- `seudonimo`: `hmac.new(clave, normalizado.encode("ascii"), hashlib.sha256).hexdigest()`; normalizar con `re.sub(r"[^0-9A-Z]", "", s.upper()).lstrip("0") or None`.
- `Makefile` (cabecera como la de `recaudacion`): `PYTHON ?= python3`, `VPY := .venv/bin/python -I`; objetivos `venv` (`$(PYTHON) -m venv .venv && .venv/bin/pip install -q --disable-pip-version-check -r requirements.txt`, saltado si `.venv/bin/python` existe y `requirements.txt` no es más nuevo) y `pruebas` (`$(VPY) -m unittest discover -s pruebas -t . -v`).
- `ci.yml`: job `brecha`, nombre `Corte analítico (python)`, `runs-on: ubuntu-latest`, `timeout-minutes: 10`, `working-directory: docs/informes/brecha`, el mismo `actions/checkout@3d3c42e…` fijado, pasos `python3 --version`, `make venv`, `make pruebas`.

- [ ] **Step 4: Run them to verify they pass**

Run: `cd docs/informes/brecha && make pruebas && cd ../../.. && yarn format:check`
Expected: PASS; prettier sin cambios en `ci.yml`.

- [ ] **Step 5: Commit** — `feat(brecha): andamiaje del corte analítico y seudonimización de documentos`

### Task 2: Cliente HTTP y servidores falsos

**Files:**
- Create: `B/herramientas/cliente.py`, `B/herramientas/falsos.py`
- Test: `B/pruebas/test_cliente.py`

**Interfaces:**
- Produces:
  - `class cliente.ErrorApi(Exception)` con `.estado: int | str`, `.ruta: str`; su mensaje nunca lleva token ni secreto.
  - `class cliente.Cliente(base_url: str, client_id: str, client_secret: str, timeout: float = 60)`:
    - `get(ruta: str, **params) -> dict | list | None` (404 → `None`; 401 → pide token otra vez y reintenta una vez; JSON con `parse_float=Decimal`);
    - `registros(objeto: str, **filtros) -> Iterator[dict]` (`size=200`, `count=false`, `sort=id`, `dir=asc`, sigue `nextCursor` con `after`; 404 → `ErrorApi`);
    - `paginas(ruta: str, tamano: int, **params) -> Iterator[dict]` (`page`/`size`; para cuando una página trae menos de `tamano` o `(page+1)*tamano >= total`; 404 → `ErrorApi`);
    - `feed(ruta: str, tamano: int) -> Iterator[dict]` (`desde`/`size`; sigue `cursor` mientras `hayMas`; 404 → `ErrorApi`).
  - `class falsos.ServidorFalso` (`ThreadingHTTPServer` en `127.0.0.1:0`): `.url`, `.peticiones: list[tuple[str, str, str | None]]` (método, ruta con query, `Authorization`), `.expirar_token()`, `.cerrar()`; `POST /api/auth/token` con `clientId`/`clientSecret` correctos → `{"token": "t-<n>"}`, otros → 401.
  - `class falsos.SrtmFalso(ServidorFalso)(registros: dict[str, list[dict]], barajar: int | None = None)`: `GET /api/objects/{obj}/records` con filtros de igualdad, `size` ≤ 200 (si no, 400), `after` con `page` > 0 → 400, `nextCursor` = base64 del índice; `barajar` = semilla para devolver las filas en otro orden (ignora `sort`); objeto desconocido → 404.
  - `class falsos.CatastroFalso(ServidorFalso)(registros: dict, valuaciones: list[dict], titulares: list[dict], cambios: list[dict], version="valuacion-v3", region="COSTA", sin_titulares=False, sin_cambios=False, sin_llaves_nuevas=False, barajar=None)`: los registros como `SrtmFalso`, y `/api/catastro/valuaciones`, `/titulares`, `/cambios` con las formas de `contratos.md` §1–§3 (`sin_*` → 404; `sin_llaves_nuevas` quita las siete llaves de §1).

- [ ] **Step 1: Write the failing tests**

```python
def test_registros_sigue_el_cursor(self):
    # SrtmFalso con 450 predios -> list(c.registros("predio")) tiene 450 ids distintos
    # y 3 peticiones GET de registros, las dos últimas con "after=" y ninguna con "page="
def test_registros_pasa_los_filtros(self):  # registros("declaracion_predial", anio=2026) -> la query lleva "anio=2026"
def test_reautentica_ante_un_401(self):
    # servidor.expirar_token() entre la página 1 y la 2 -> 450 registros y 2 POST /api/auth/token
def test_un_401_repetido_es_error(self):  # credenciales malas -> ErrorApi con estado 401
def test_error_no_muestra_secretos(self):  # str(ErrorApi) no contiene client_secret ni "Bearer"
def test_decimales_exactos(self):  # un registro con 0.1 -> Decimal("0.1"), no float
def test_paginas_hasta_la_ultima(self):  # 1001 valuaciones, tamano=500 -> 1001 ítems, 3 peticiones
def test_feed_sigue_hayMas(self):  # 450 cambios, tamano=200 -> 450 ítems en orden, 3 peticiones con desde
def test_get_404_es_none(self):  # c.get("/no/existe") is None
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL (`ModuleNotFoundError: herramientas.cliente`).
- [ ] **Step 3: Implement** `cliente.py` (stdlib `urllib`; token por `POST {base}/api/auth/token` con `{"clientId", "clientSecret"}`) y `falsos.py` (como `model/fake_core.py`, sin importarlo).
- [ ] **Step 4: Run to verify they pass** — `make pruebas` → PASS.
- [ ] **Step 5: Commit** — `feat(brecha): cliente de las APIs con cursor y servidores falsos para las pruebas`

### Task 3: Datos sintéticos y extracción de srtm

**Files:**
- Create: `B/herramientas/sinteticos.py`, `B/herramientas/srtm.py`
- Test: `B/pruebas/test_srtm.py`

**Interfaces:**
- Consumes: `Cliente.registros`, `seudonimo.seudonimo`, `SrtmFalso`.
- Produces:
  - `sinteticos.minimos() -> Sinteticos` y `sinteticos.datos(semilla: int, contribuyentes: int = 40) -> Sinteticos`; `@dataclass Sinteticos(srtm: dict[str, list[dict]], catastro: dict[str, list[dict]], valuaciones: list[dict], titulares: list[dict], cambios: list[dict], documentos: list[str], nombres: list[str])` (registros con la forma `{"id", "attributes", "geometries"?}`; ids uuid fijos derivados de la semilla).
  - `srtm.extraer_srtm(cliente: Cliente, anio: int, clave: bytes) -> ExtraccionSrtm`; `@dataclass ExtraccionSrtm(tablas: dict[str, list[dict]], corrida: dict | None, parametros: list[dict], avisos: list[str])`, con las diez tablas `srtm_*` de § Tablas (filas = dict con exactamente esas columnas, sin ordenar todavía).

`minimos()` fija estos datos (todo inventado):
- predios `P1` `0101001`, `P2` `0101002`; contribuyentes `C1` (`DNI`, `04587963`, `nombre_completo` `QUISPE MAMANI ROSA`, `domicilio_fiscal` `JR SINTETICO 123`), `C2` (`RUC`, `20123456789`, `razon_social` `EMPRESA SINTETICA SAC`), `C3` (sin DJ ni cuotas).
- DJ 2026: `D1` (P1, C1, `estado` vacío, `porcentaje_condominio` 100, `valor_terreno` 50000, `valor_construccion` 40000, `valor_otras_instalaciones` 10000, `valor_autoavaluo` 95000, `valor_afecto` 95000, `deduccion` 0, `condicion_numero_documento` `RES-001-SINTETICA`); `D2` (P2, C2, `VIGENTE`, sin valores de origen, `valor_autoavaluo` y `valor_afecto` 400000); `D4` (P1, C1, `SUSTITUIDA`, `valor_afecto` 1). DJ 2025: `D3` (P1, C1).
- niveles `N1` (D1, 60 m², `CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA`), `N2` (D3); obra `O1` (D1).
- `cuota_predial` 2026: C1 cuotas 1–4 `revision` 1 de 70.00 y la 4 `revision` 2 de 75.00; C2 cuotas 1–4 de 587.50.
- `brecha_corrida` 2026: `R1` `TERMINADA` `terminado` 2026-10-01T10:00:00Z, `R2` `TERMINADA` 2026-10-20T10:00:00Z, `R3` `EN_CURSO` 2026-10-24T10:00:00Z.
- `brecha_predio` de R2: `BP1` (P1, `0101001`, `SUBVALUADO`, `impuesto_declarado` 285.00, `impuesto_catastro` 300.00, `delta_impuesto` 15.00, `delta_autoavaluo` 5000, `confianza` `CONFIABLE`, `orden` 1); `BP2` (P2, `0101002`, `SOBREVALUADO`, 2350.00, 2300.00, −50.00, `DUDOSO`, 2); `BP3` (sin predio, `0101003`, `OMISO`, `delta_impuesto` 120.00, sin `confianza`, 3); y una fila de R1.
- `parametro_tributario`: UIT 2026 5500; `TRAMO_PREDIAL` 1/2/3 = 0.2/0.6/1.0; `TRAMO_PREDIAL_LIMITE` 1/2 = 15/60; `PREDIAL_MINIMO` 0.6 (desde 2004-11-15, abiertos); UIT 2025 5350; una UIT 2026 `ANULADO` de 9999.
- `arancel`: dos 2026 `VIGENTE`, uno 2026 `POR_VERIFICAR`, uno 2025.
- catastro: fichas `F1` `0101001` (`COMPLETO`, `CODIGO_RENTAS`, revisado), `F2` `0101002` (`PARCIAL`, `GEOMETRICO`), `F3` `0101003` (`SIN_LEVANTAR`); construcciones F1: 60 `CASA HABITACION…`, 40 y 30 `TIENDAS DEPOSITOS…`; F2: 50 `CASA HABITACION…` y 50 `EDIFICIO-OFICINAS`; F3 ninguna; una construcción con `predio` que no es ficha; obra en F1; titulares F1 (`DNI`, `4587963`, `QUISPE MAMANI ROSA`, 100, `PROPIETARIO`), F2 (`RUC`, `20123456789`, `EMPRESA SINTETICA SAC`, 100, `PROPIETARIO`); valuaciones de F1 y F2 con `autoavaluo` y `loteGeom` (cuadrados de 0.0002° cerca de (−75.73, −14.06)) y F3 con `autoavaluo` `null` y `motivo` `SIN_LEVANTAMIENTO`; tres cambios (`CREATE construccion` F1, `UPDATE predio_catastral` F2 con `campos` `["uso","area_terreno"]`, `DELETE obra_complementaria` F1).
- `documentos` = todos los números de documento crudos usados; `nombres` = todos los nombres y razones sociales.

- [ ] **Step 1: Write the failing tests** (contra `SrtmFalso(minimos().srtm)`)

```python
def test_contribuyentes_seudonimizados_y_sin_nombres(self):
    filas = ext.tablas["srtm_contribuyente"]
    self.assertEqual({f["id"] for f in filas}, {C1, C2})          # C3 no sale
    self.assertEqual(set(filas[0]), set(COLUMNAS["srtm_contribuyente"]))
    self.assertEqual(fila(C1)["documento_hmac"], seudonimo(CLAVE, "4587963"))
def test_declaraciones_del_anio_con_lista_blanca(self):
    # ids {D1, D2, D4}; ninguna fila tiene "condicion_numero_documento" ni "otros_datos"
def test_niveles_y_obras_solo_de_las_dj_del_anio(self):  # niveles {N1}, obras {O1}
def test_impuesto_toma_la_ultima_revision(self):
    # C1: impuesto_anual == Decimal("285.00"), cuotas == 4; C2: Decimal("2350.00")
def test_corrida_terminada_mas_reciente(self):  # ext.corrida["id"] == R2; brecha_predio solo de R2 (3 filas)
def test_sin_corrida_terminada(self):  # sin R1/R2 -> tablas de brecha [], corrida None, aviso "sin brecha TERMINADA de 2026"
def test_sin_confianza_es_null_y_avisa(self):
    # brecha_predio sin el atributo confianza -> confianza None en todas y aviso que cita srtm-backend#204
def test_parametros_vigentes_al_primero_de_enero(self):
    # tipos {UIT, TRAMO_PREDIAL, TRAMO_PREDIAL_LIMITE, PREDIAL_MINIMO}; la UIT es 5500, la ANULADO no sale
def test_aranceles_vigentes_del_anio(self):  # 2 filas
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL.
- [ ] **Step 3: Implement** `sinteticos.py` y `srtm.py` (`impuesto_anual` = suma del `monto` de la mayor `revision` de cada `numero`; `revision` vacía cuenta 0; `fecha_calculo` = la mayor).
- [ ] **Step 4: Run to verify they pass** — `make pruebas` → PASS.
- [ ] **Step 5: Commit** — `feat(brecha): extracción de srtm para el corte, seudonimizada`

### Task 4: Extracción de catastro

**Files:**
- Create: `B/herramientas/catastro.py`
- Test: `B/pruebas/test_catastro.py`

**Interfaces:**
- Consumes: `Cliente.registros/paginas/feed`, `seudonimo`, `CatastroFalso`, `sinteticos.minimos()`.
- Produces: `catastro.extraer_catastro(cliente: Cliente, anio: int, clave: bytes) -> ExtraccionCatastro`; `@dataclass ExtraccionCatastro(tablas: dict[str, list[dict]], motor: dict, avisos: list[str])` con `motor = {"version": str, "region": str}` y las siete tablas `catastro_*`; `catastro.centroide(geometria: dict | None) -> tuple[float, float] | None` (Polygon o MultiPolygon: el centroide de área del anillo exterior del polígono más grande, redondeado a 7 decimales).

- [ ] **Step 1: Write the failing tests**

```python
def test_construcciones_llevan_el_codigo_de_su_ficha(self):
    # F1 -> 3 construcciones con codigo_municipal "0101001"; la de predio huérfano -> codigo_municipal None
def test_titulares_seudonimizados_sin_nombre(self):
    # 2 filas, columnas exactas de § Tablas; documento_hmac == seudonimo(CLAVE, "04587963")  (mismo que srtm)
def test_valuaciones_con_las_llaves_del_contrato(self):
    # F3: autoavaluo None, motivo "SIN_LEVANTAMIENTO", estado_levantamiento "SIN_LEVANTAR"; motor == {"version": "valuacion-v3", "region": "COSTA"}
def test_catastro_viejo_sin_llaves_nuevas(self):  # sin_llaves_nuevas=True -> vinculo_metodo, cuc, entrega None; sin error
def test_sin_titulares_ni_cambios(self):
    # sin_titulares=True, sin_cambios=True -> tablas [] y avisos que citan catastro-backend#25 y #26
def test_cambios_del_feed(self):  # 3 filas; la de F2 tiene campos == "area_terreno,uso"
def test_lote_con_centroide(self):  # catastro_lote de F1: lon/lat a 1e-7 del centro del cuadrado; F3 sin geometría no sale
def test_centroide_de_multipolygon(self):  # toma el polígono más grande
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL.
- [ ] **Step 3: Implement** `catastro.py` (valuaciones con `tamano=500`, titulares con `tamano=500`, cambios con `tamano=500`; un `ErrorApi` 404 de `/titulares` o `/cambios` se vuelve aviso; `geojson` = `json.dumps(geometria, sort_keys=True, separators=(",", ":"))` con las coordenadas como `float`).
- [ ] **Step 4: Run to verify they pass** — `make pruebas` → PASS.
- [ ] **Step 5: Commit** — `feat(brecha): extracción de catastro para el corte, con titulares seudonimizados`

### Task 5: Esquemas, Parquet, manifiesto y `corte.py`

**Files:**
- Create: `B/herramientas/tablas.py`, `B/herramientas/parquet.py`, `B/herramientas/manifiesto.py`, `B/corte.py`
- Test: `B/pruebas/test_corte.py`

**Interfaces:**
- Consumes: `extraer_srtm`, `extraer_catastro`, `comun.*`, `Cliente`.
- Produces:
  - `tablas.ESQUEMAS: dict[str, pa.Schema]` y `tablas.ORDEN: dict[str, tuple[str, ...]]` (§ Tablas); `tablas.COLUMNAS: dict[str, tuple[str, ...]]`.
  - `parquet.escribir(tabla: str, filas: list[dict], destino: Path) -> dict` → `{"tabla", "archivo", "filas", "bytes", "sha256"}`; ordena con `ORDEN[tabla]` (`None` primero), convierte tipos, escribe con las opciones del ruling.
  - `manifiesto.construir(org: str, fecha: date, leido_en: datetime, srtm: ExtraccionSrtm, catastro: ExtraccionCatastro, archivos: list[dict], backends: dict, clave: bytes) -> dict`; `manifiesto.escribir(m: dict, carpeta: Path) -> None` (`json.dumps(ensure_ascii=False, indent=2, sort_keys=True)` + `\n`, `Decimal` → texto); `manifiesto.verificar_corte(carpeta: Path) -> dict` (vuelve a calcular cada sha256 y conteo; `ErrorCorte` que nombra el archivo distinto, faltante o sobrante).
  - `corte.main(argv: list[str] | None = None, reloj: Callable[[], datetime] = ..., hoy: Callable[[], date] = comun.hoy_lima) -> int`: `--org`, `--fecha`, `--salida` (por omisión `~/cortes/analitica`), `--secretos` (por omisión `~/.config/srtm-analitica/<org>.env`), `--reemplazar`, `--verificar CARPETA`. Escribe en `<carpeta>.parcial` y renombra. Imprime `corte <org> <fecha>: 17 tablas, <n> filas -> <carpeta>`.

Manifiesto (llaves exactas): `formato` (1), `organizacion`, `fecha_corte`, `tipo` (`anual`|`mensual`), `anio`, `leido_en`, `motor` (`version`, `region`), `brecha_corrida` (`id`, `terminado`, `version_catastro` | `null`), `normativa` (`parametros`: lista ordenada de `{tipo, clave, vigencia_desde, vigencia_hasta, valor_numerico, texto, norma}`; `aranceles`: `{anio, total, archivo: "srtm_arancel.parquet"}`), `backends` (`srtm`: `{url, version}`, `catastro`: `{url, version}`), `metodo` (`commit`: `git rev-parse HEAD`, `sucio`: hay cambios en `docs/informes/brecha`), `seudonimizacion` (`algoritmo`: `HMAC-SHA256`, `normalizacion`: texto de la regla, `huella_clave`), `avisos` (ordenados), `archivos` (ordenados por `archivo`).

- [ ] **Step 1: Write the failing tests** (cores falsos con `minimos()` o `datos(7)`; secretos en un archivo temporal 0600; `reloj` fijo)

```python
def test_dos_cortes_dan_los_mismos_sha256(self):
    # dos corridas de corte.main sobre los mismos datos, la segunda con barajar=3 en los dos falsos
    # y otro reloj -> mismos {archivo: sha256}; manifiestos iguales salvo "leido_en"
def test_ningun_parquet_trae_documentos_ni_nombres(self):
    # datos(7): para cada .parquet, cada celda de texto (pq.read_table(...).to_pylist())
    # no contiene ningún s.documentos (ni sin ceros a la izquierda) ni ningún s.nombres
def test_manifiesto_completo(self):
    # tipo "mensual" (fecha 2026-10-25); "anual" con 2026-01-01; motor.version "valuacion-v3";
    # brecha_corrida.id == R2; cada archivo: sha256 == comun.sha256(ruta), filas == num_rows;
    # 17 archivos; normativa.aranceles.total == 2; huella_clave == huella_clave(CLAVE)
def test_tablas_vacias_conservan_el_esquema(self):
    # sin corrida, sin titulares, sin cambios -> srtm_brecha_predio.parquet con 0 filas y el esquema de ESQUEMAS
def test_decimales_se_cuantizan(self):
    # parquet.escribir con valor_afecto 400000 (int), "12.5" (texto) y Decimal("0.123456789012") -> sin error;
    # se leen Decimal("400000.0000000000"), Decimal("12.5000000000"), Decimal("0.1234567890")
def test_un_error_no_deja_carpeta(self):  # CatastroFalso que devuelve 500 en valuaciones -> exit != 0, ni carpeta ni .parcial
def test_carpeta_existente_sin_reemplazar(self):  # segunda corrida sin --reemplazar -> exit 2 y la primera intacta
def test_verificar_detecta_un_archivo_alterado(self):  # un byte cambiado en srtm_predio.parquet -> ErrorCorte que lo nombra
def test_version_de_backends_del_entorno(self):  # VERSION_SRTM=2.0.0 -> backends.srtm.version "2.0.0"; sin VERSION_CATASTRO -> null y aviso
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL.
- [ ] **Step 3: Implement** los cuatro módulos.
- [ ] **Step 4: Run to verify they pass** — `make pruebas` → PASS.
- [ ] **Step 5: Commit** — `feat(brecha): corte en Parquet con manifiesto reproducible`

### Task 6: DuckDB y `vistas.sql`

**Files:**
- Create: `B/herramientas/base.py`, `B/armar_duckdb.py`, `B/vistas.sql`
- Test: `B/pruebas/test_vistas.py`

**Interfaces:**
- Consumes: `manifiesto.verificar_corte`, `tablas.ESQUEMAS`, un corte escrito por `corte.main` sobre `minimos()`.
- Produces:
  - `base.construir_duckdb(carpeta: Path, destino: Path | str) -> duckdb.DuckDBPyConnection`: verifica el corte, crea cada tabla con `CREATE TABLE <t> AS SELECT * FROM read_parquet(?)`, crea `corte_manifiesto` (una fila: `organizacion`, `fecha_corte`, `tipo`, `anio`, `leido_en`, `version_motor`, `region`) y corre `vistas.sql`; devuelve la conexión abierta.
  - `armar_duckdb.py --corte CARPETA --destino ARCHIVO` (local; `--org` llega en Task 8).
  - En `vistas.sql`: macro `impuesto_predial(base, uit, a1, a2, a3, l1, l2, minimo)`; vistas `v_parametros_predial` (una fila: `uit, a1, a2, a3, l1, l2, minimo`), `v_brecha_predio` (`srtm_brecha_predio` con `catastro_ficha` por `codigo_catastro = codigo_municipal`, `coalesce(confianza, 'SIN_DATO')` y la `clase` de `v_clase_predominante`), `v_segmentos` (`confianza, categoria, predios, delta_impuesto, delta_autoavaluo, impuesto_declarado, impuesto_catastro`), `v_brecha_confiable_por_categoria` (`categoria, predios, delta_impuesto, delta_autoavaluo`), `v_clase_predominante` (`codigo_municipal, clase`), `v_brecha_por_clase` (`confianza, clase, predios, delta_impuesto, delta_autoavaluo`), `v_predial_contribuyente` (`contribuyente, documento_hmac, declarado, motor_rentas, motor_catastro`), `v_predial_tres_columnas` (`contribuyentes, declarado, motor_rentas, motor_catastro`). DJ vigente = `estado` vacío o `VIGENTE`.

Fórmula de la macro (igual que `ImpuestoPredial.liquidar`):

```sql
CASE WHEN base IS NULL OR round(base, 2) <= 0 THEN 0 ELSE greatest(
  round(uit * minimo / 100, 2),
  round(least(round(base, 2), round(l1 * uit, 2)) * a1 / 100, 2)
  + round(greatest(least(round(base, 2), round(l2 * uit, 2)) - round(l1 * uit, 2), 0) * a2 / 100, 2)
  + round(greatest(round(base, 2) - round(l2 * uit, 2), 0) * a3 / 100, 2)) END
```

- [ ] **Step 1: Write the failing tests**

```python
def test_impuesto_predial_como_srtm(self):
    # con 5500, 0.2, 0.6, 1.0, 15, 60, 0.6: base 0 -> 0; 10000 -> 33.00; 100000 -> 270.00;
    # 400000 -> 2350.00; 12345.675 -> 33.00
def test_segmentos_por_confianza(self):
    # v_segmentos: (CONFIABLE, SUBVALUADO, 1, 15.00), (DUDOSO, SOBREVALUADO, 1, -50.00), (SIN_DATO, OMISO, 1, 120.00)
def test_brecha_confiable_por_categoria(self):  # una fila: (SUBVALUADO, 1, 15.00, 5000)
def test_clase_predominante(self):
    # 0101001 -> "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES";
    # 0101002 -> "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA"; 0101003 -> "SIN CONSTRUCCION"
def test_brecha_por_clase(self):  # (CONFIABLE, TIENDAS…, 1, 15.00) está; suma de predios == 3
def test_predial_por_contribuyente(self):
    # C1: declarado 270.00, motor_rentas 285.00, motor_catastro 300.00
    # C2: declarado 2350.00, motor_rentas 2350.00, motor_catastro 2300.00
def test_predial_tres_columnas(self):  # (2, 2620.00, 2635.00, 2600.00)
def test_vistas_con_corte_vacio(self):  # corte sin brecha, titulares ni cambios -> cada vista corre y v_segmentos tiene 0 filas
def test_corte_manifiesto(self):  # una fila con organizacion "ficticia", version_motor "valuacion-v3"
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL.
- [ ] **Step 3: Implement** `base.py`, `armar_duckdb.py` y `vistas.sql` (`CREATE OR REPLACE`, para poder volver a correrlo).
- [ ] **Step 4: Run to verify they pass** — `make pruebas` → PASS.
- [ ] **Step 5: Commit** — `feat(brecha): vistas DuckDB de segmentos, clase de construcción y predial en tres columnas`

### Task 7: Publicación, README y cuenta de servicio

**Files:**
- Modify: `B/Makefile`
- Create: `B/README.adoc`
- Modify: `docs/develop/alta-organizacion.md` (sección nueva `## 9. Corte analítico (#205)`, antes de «Registro de Ica»)

**Interfaces:**
- Consumes: `corte.py`, `armar_duckdb.py`, `manifiesto.verificar_corte`.
- Produces (Makefile): `extraer ORG= [FECHA=] [REEMPLAZAR=1]` (`$(VPY) corte.py …`, con `VERSION_SRTM`/`VERSION_CATASTRO` pasadas), `verificar ORG= FECHA=`, `publicar ORG= FECHA= [REEMPLAZAR=1]` (verifica; `aws s3 ls …/manifiesto.json` existente → error sin `REEMPLAZAR=1`; `aws s3 sync <carpeta> $(S3)/$(ORG)/$(FECHA)/ --sse AES256 --only-show-errors`), `corte` (= `extraer` + `publicar`), `local CORTE=<carpeta>` (arma `<carpeta>.duckdb`). `FECHA` por omisión: la de `comun.hoy_lima()`. `S3` se lee de `parametros.json` como en `recaudacion`.

- [ ] **Step 1: Write** `README.adoc` (estilo de `recaudacion/README.adoc`): aviso «aquí no hay datos»; qué lee (tabla de § Tablas resumida) y con qué cuentas; seudonimización y lo que nunca sale; el archivo de secretos (formato, `chmod 600`, generar la clave con `python3 -c "import secrets; print(secrets.token_hex(32))"`, guardarla también en el gestor de claves, y que cambiarla corta la comparación entre cortes); `make venv`, `make corte ORG=… FECHA=…`; el cron `CRON_TZ=America/Lima` + `0 6 1 * * cd …/docs/informes/brecha && make corte ORG=perene`; el manifiesto llave por llave; cómo leerlo con DuckDB (`make local CORTE=…`, y `duckdb <archivo> -c "SELECT * FROM v_segmentos"`); qué vista da cada cifra del informe v3; reproducibilidad (mismos datos y mismas versiones fijas → mismos sha256).
- [ ] **Step 2: Write** la sección 9 de `alta-organizacion.md`: rol `ANALITICA` con `READ` sobre `predio`, `contribuyente`, `declaracion_predial`, `nivel_construccion`, `obra_complementaria`, `cuota_predial`, `brecha_corrida`, `brecha_predio`, `parametro_tributario`, `arancel` (los `curl` como los de `CAJA`); cuenta `analitica`; en catastro, la cuenta `srtm` existente; el archivo `~/.config/srtm-analitica/<org>.env`.
- [ ] **Step 3: Verify**

Run: `cd docs/informes/brecha && make -n corte ORG=perene FECHA=2026-10-25 && make pruebas && make local CORTE=$(mktemp -d)/x 2>&1 | head -3`
Expected: `make -n` muestra `corte.py`, la verificación y `aws s3 sync … --sse AES256`; pruebas PASS; `local` sobre una carpeta inexistente falla con un `ErrorCorte` claro.
- [ ] **Step 4: Commit** — `docs(brecha): cómo correr el corte analítico, publicarlo y leerlo con DuckDB`

---

## Issue #206: Metabase sobre los cortes

**Branch:** `feat/metabase-cortes` (sale de `feat/corte-analitico`; su PR apunta a esa rama).

### Task 8: Un DuckDB por organización desde su último corte

**Files:**
- Modify: `B/herramientas/base.py`, `B/armar_duckdb.py`, `B/vistas.sql`, `B/Makefile`
- Create: `B/herramientas/s3.py`, `B/ficticio.py`
- Test: `B/pruebas/test_duckdb_org.py`

**Interfaces:**
- Consumes: `base.construir_duckdb`, `corte.main`, `sinteticos.datos`, `SrtmFalso`, `CatastroFalso`.
- Produces:
  - `base.INIT_SQL = "SET enable_external_access = false; SET lock_configuration = true;"`
  - `base.construir_atomico(carpeta: Path, destino: Path) -> None`: arma `destino.nuevo`, `CHECKPOINT`, cierra, `chmod 0o644`, `os.replace`; ante cualquier error borra el temporal y deja `destino` como estaba.
  - `s3.ultimo_corte(listado: str) -> str | None` (salida de `aws s3 ls <prefijo>/`: la mayor línea `PRE AAAA-MM-DD/`); `s3.descargar(s3_base: str, org: str, cortes: Path, ejecutar=subprocess.run) -> Path`.
  - `armar_duckdb.py --org ORG [--cortes DIR] [--destino-dir DIR]` (por omisión `/srv/srtm-metabase/cortes` y `/srv/srtm-metabase/duckdb`; destino `<dir>/<org>.duckdb`) además de `--corte/--destino`.
  - `ficticio.py --org ORG --semilla N --fecha AAAA-MM-DD --salida DIR`: `datos(semilla)` en los dos falsos, secretos temporales 0600 con la clave `sha256(b"ficticia" + semilla)`, y `corte.main`.
  - Vistas nuevas: `v_brecha_confiable_por_clase` (`clase, predios, delta_impuesto, delta_autoavaluo`), `v_cambios_por_mes` (`mes, objeto, operacion, cambios`), `v_cambios_recientes` (los 500 últimos por `en` desc), `v_lotes_mapa` (`codigo_municipal, lat DOUBLE, lon DOUBLE, categoria, confianza, delta_impuesto`), `v_corte` (= `corte_manifiesto`).
  - Makefile: `duckdb ORG=` (`$(VPY) armar_duckdb.py --org $(ORG)`), `corte-ficticio ORG= SEMILLA= [SALIDA=]`.

- [ ] **Step 1: Write the failing tests**

```python
def test_ultimo_corte_toma_la_fecha_mayor(self):
    # "PRE 2026-09-01/\nPRE 2026-11-01/\nPRE 2026-10-01/\nPRE tmp/\n2026-12-01 x.json" -> "2026-11-01"; "" -> None
def test_descargar_sincroniza_el_ultimo(self):  # ejecutar falso: llama "aws s3 ls" y luego "aws s3 sync …/perene/2026-11-01/ …"
def test_reemplazo_atomico_y_permisos(self):  # destino nuevo con 0o644 y sin .nuevo al lado
def test_falla_deja_la_base_anterior(self):
    # destino existente; corte con un parquet alterado -> ErrorCorte; destino con el mismo sha256 que antes; sin .nuevo
def test_init_sql_aisla_la_base(self):
    # dos archivos a.duckdb y b.duckdb; duckdb.connect(a, read_only=True); execute(INIT_SQL):
    # SELECT count(*) FROM v_segmentos funciona; ATTACH b -> duckdb.Error; read_parquet(<algún parquet>) -> duckdb.Error;
    # SET enable_external_access = true -> duckdb.Error
def test_vistas_de_los_tableros(self):
    # minimos(): v_lotes_mapa tiene 0101001 y 0101002 con lat/lon DOUBLE; v_cambios_por_mes suma 3 cambios
def test_ficticio_reproducible(self):  # dos ficticio.py con la misma semilla y fecha -> mismos sha256 en los manifiestos
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL.
- [ ] **Step 3: Implement** lo de Interfaces.
- [ ] **Step 4: Run to verify they pass** — `make pruebas` → PASS.
- [ ] **Step 5: Commit** — `feat(brecha): un DuckDB por organización armado desde su último corte`

### Task 9: Compose de Metabase y respaldos

**Files:**
- Create: `B/metabase/Dockerfile`, `B/metabase/compose.yml`, `B/metabase/metabase.env.example`, `B/metabase/respaldo.sh`, `B/metabase/restaurar.sh`
- Modify: `B/.gitignore` (`metabase/metabase.env`), `.github/workflows/ci.yml` (paso de `docker compose config` en el job `brecha`)
- Test: `B/pruebas/test_compose.py`

**Interfaces:**
- Produces:
  - `Dockerfile`: `FROM eclipse-temurin:21-jre-jammy`; `ARG METABASE_VERSION=0.63.19`, `ARG METABASE_SHA256=<el de https://downloads.metabase.com/v0.63.19/metabase.jar, calculado una vez con shasum -a 256 y escrito aquí>`, `ARG DUCKDB_DRIVER_VERSION=1.5.5.0`, `ARG DUCKDB_DRIVER_SHA256=47a0d41ca52ff82a6ce78b0c52b2bdb58b32094405e446734cefc44eb1ddbd07`; instala `curl` y `ca-certificates`; baja los dos jar con `curl -fsSL` y los comprueba con `sha256sum -c`; usuario `metabase`; `MB_PLUGINS_DIR=/home/metabase/plugins`; `CMD ["java", "-jar", "/home/metabase/metabase.jar"]` (como el Dockerfile del driver 1.5.5.0).
  - `compose.yml` (`name: srtm-metabase`): servicio `metabase-postgres` (`postgres:18.1`, `POSTGRES_DB/USER=metabase`, `POSTGRES_PASSWORD=${METABASE_PG_PASSWORD:?…}`, volumen `metabase-pg-data:/var/lib/postgresql`, sin `ports`, healthcheck `pg_isready`); servicio `metabase` (`build: .`, `depends_on` sano, `ports: ['127.0.0.1:${METABASE_PUERTO:-3310}:3000']`, `volumes: ['${METABASE_DUCKDB_DIR:?…}:/datos:ro']`, `MB_DB_TYPE=postgres`, `MB_DB_HOST=metabase-postgres`, `MB_DB_PORT=5432`, `MB_DB_DBNAME=metabase`, `MB_DB_USER=metabase`, `MB_DB_PASS=${METABASE_PG_PASSWORD:?…}`, `MB_ENCRYPTION_SECRET_KEY=${MB_ENCRYPTION_SECRET_KEY:?…}`, `MB_SITE_URL=${METABASE_SITE_URL:-http://localhost:3310}`, `MB_ANON_TRACKING_ENABLED=false`, `MB_CHECK_FOR_UPDATES=false`, `MB_LOAD_SAMPLE_CONTENT=false`, `MB_REPORT_TIMEZONE=America/Lima`, `JAVA_TOOL_OPTIONS=-Xmx2g`, `mem_limit: 3g`, healthcheck `curl -fsS http://localhost:3000/api/health` con `start_period: 180s`, `restart: unless-stopped`). Cabecera con el uso (`docker compose -p srtm-metabase -f … --env-file /srv/srtm-metabase/metabase.env up -d --build`), como `docs/manuales/demo/compose.demo.yml`.
  - `metabase.env.example`: `METABASE_PG_PASSWORD=cambiar`, `MB_ENCRYPTION_SECRET_KEY=cambiar-por-32-caracteres-o-mas`, `METABASE_DUCKDB_DIR=/srv/srtm-metabase/duckdb`, `METABASE_RESPALDOS_DIR=/srv/srtm-metabase/respaldos`, `METABASE_RESPALDOS_S3=` (vacío), `METABASE_PUERTO=3310`.
  - `respaldo.sh ENV_FILE`: `set -euo pipefail`; `docker compose … exec -T metabase-postgres pg_dump -U metabase -Fc metabase` a `$METABASE_RESPALDOS_DIR/metabase-<UTC AAAAMMDDTHHMMSSZ>.dump` (umask 077), conserva los 14 más nuevos, y con `METABASE_RESPALDOS_S3` hace `aws s3 cp … --sse AES256`. `restaurar.sh ENV_FILE ARCHIVO`: para `metabase`, `pg_restore --clean --if-exists --no-owner -d metabase`, arranca `metabase`; se niega sin `ARCHIVO` legible.

- [ ] **Step 1: Write the failing tests** (`unittest.skipUnless(shutil.which("docker"))`)

```python
def test_compose_valido_con_el_ejemplo(self):
    # docker compose -f metabase/compose.yml --env-file metabase/metabase.env.example config --format json -> rc 0
    # services == {"metabase", "metabase-postgres"}; metabase.ports[0]: host_ip "127.0.0.1", published "3310";
    # volumen con target "/datos" y read_only True; environment.MB_DB_TYPE == "postgres";
    # metabase-postgres.image == "postgres:18.1" y sin "ports"; build.args incluye METABASE_VERSION "0.63.19"
def test_falta_un_secreto(self):  # env sin MB_ENCRYPTION_SECRET_KEY -> rc != 0 y stderr lo nombra
def test_scripts_de_respaldo(self):  # bash -n en los dos; shellcheck (si está) sin hallazgos
def test_dockerfile_fija_versiones(self):  # el Dockerfile tiene los dos ARG *_SHA256 de 64 hex y "sha256sum -c"
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL.
- [ ] **Step 3: Implement** los cinco archivos; en `ci.yml`, paso `docker compose -f metabase/compose.yml --env-file metabase/metabase.env.example config -q` en el job `brecha`.
- [ ] **Step 4: Verify**

Run: `cd docs/informes/brecha && docker compose -f metabase/compose.yml --env-file metabase/metabase.env.example config -q && make pruebas && cd ../../.. && yarn format:check`
Expected: sin salida del `config -q`; PASS; prettier limpio.
- [ ] **Step 5: Commit** — `feat(brecha): compose de Metabase con el driver DuckDB, base de aplicación en Postgres y respaldos`

### Task 10: Provisión de bases, grupos, colecciones y tableros

**Files:**
- Create: `B/herramientas/metabase.py`, `B/provisionar.py`, `B/metabase/tableros.json`, `B/pruebas/metabase_falso.py`
- Modify: `B/Makefile` (`provisionar`)
- Test: `B/pruebas/test_provisionar.py`

**Interfaces:**
- Consumes: `base.INIT_SQL`, `vistas.sql`.
- Produces:
  - `class metabase.Metabase(url: str)`: `sesion(usuario: str, clave: str) -> None` (`POST /api/session`, cabecera `X-Metabase-Session`), `get/post/put(ruta, cuerpo=None)`; `class metabase.ErrorMetabase(Exception)` con `.estado`.
  - `metabase.asegurar_setup(mb: Metabase, email: str, clave: str) -> bool` (`GET /api/session/properties`; si `has-user-setup` es falso, `POST /api/setup` con `setup-token`; devuelve si hizo el setup).
  - `metabase.provisionar(mb: Metabase, orgs: dict[str, str], tableros: dict, ruta_datos: str = "/datos") -> dict` (clave → nombre; devuelve `{org: {"base": id, "grupo": id, "coleccion": id, "tarjetas": [ids], "tableros": [ids]}}`). Por organización: base `Brecha <nombre>` (`engine: "duckdb"`, `details: {"database_file": "/datos/<org>.duckdb", "read_only": true, "old_implicit_casting": true, "init_sql": INIT_SQL}`), grupo `Brecha <org>`, colección `Brecha <nombre>`; grafo de datos (`PUT /api/permissions/graph` con `revision`): grupo propio `{"view-data": "unrestricted", "create-queries": "query-builder"}`, ajenas y «All Users» `{"create-queries": "no"}`; grafo de colecciones (`PUT /api/collection/graph`): propia `read`, ajenas `none`, «All Users» `none`; tarjetas `{"type": "native", "native": {"query": "SELECT * FROM <vista>"}, "database": <id>}` y tableros (`PUT /api/dashboard/:id` con `dashcards`), creados o actualizados por nombre.
  - `metabase.agregar_usuario(mb: Metabase, org: str, email: str, nombre: str, apellido: str, clave: str) -> int` (crea si falta y lo pone en el grupo de la organización).
  - `provisionar.py [--org clave[:nombre]]...` (sin `--org`: las de `parametros.json`) y `provisionar.py usuario --org … --email … --nombre … --apellido …` (clave por `METABASE_CLAVE_USUARIO` o `getpass`); conexión por `METABASE_URL`, `METABASE_ADMIN_EMAIL`, `METABASE_ADMIN_PASSWORD`.
  - `tableros.json`: `{"tableros": [{"nombre", "tarjetas": [{"nombre", "vista", "display", "visualizacion", "fila", "col", "ancho", "alto"}]}]}` con cinco tableros: «Brecha confiable» (`v_brecha_confiable_por_categoria` bar, `v_brecha_confiable_por_clase` bar), «Segmentos» (`v_segmentos` table), «Predial en tres columnas» (`v_predial_tres_columnas` bar, `v_predial_contribuyente` table), «Cambios del catastro» (`v_cambios_por_mes` line, `v_cambios_recientes` table), «Mapa de lotes» (`v_lotes_mapa` map con `{"map.type": "pin", "map.latitude_column": "lat", "map.longitude_column": "lon"}`); más `v_corte` (scalar) arriba de cada tablero. Cuadrícula de 24 columnas.
  - `metabase_falso.MetabaseFalso(usuarios: dict[str, str])`: guarda en memoria bases, grupos (con «All Users» id 1 y «Administrators» id 2), colecciones, grafos con `revision`, tarjetas, tableros, usuarios y membresías; responde las rutas de arriba y las de la Task 11; `.peticiones`.

- [ ] **Step 1: Write the failing tests**

```python
def test_una_base_por_organizacion(self):
    # orgs {"ficticia-a": "Ficticia A", "ficticia-b": "Ficticia B"} -> 2 bases engine "duckdb",
    # details.database_file "/datos/ficticia-a.duckdb", read_only True, init_sql == INIT_SQL
def test_cada_grupo_solo_consulta_su_base(self):
    # grafo: grupo A en base A "query-builder", en base B "no"; "All Users" "no" en las dos
def test_colecciones_por_organizacion(self):  # A->colA "read", A->colB "none", All Users -> "none" en las dos
def test_tarjetas_y_tableros(self):
    # por org: 5 tableros; cada tarjeta nativa "SELECT * FROM v_…" con database == su base y collection_id == su colección
def test_es_idempotente(self):  # segunda corrida: ningún POST de base, grupo, colección, tarjeta ni tablero
def test_setup_inicial(self):  # has-user-setup False -> un POST /api/setup con el setup-token
def test_agregar_usuario(self):  # crea el usuario y una membresía al grupo "Brecha ficticia-a"; repetirlo no duplica
def test_tableros_citan_vistas_que_existen(self):
    # cada "vista" de tableros.json aparece como "CREATE OR REPLACE VIEW <vista>" en vistas.sql
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL.
- [ ] **Step 3: Implement** lo de Interfaces (comprobar las formas de la API contra la documentación de Metabase 0.63: `/api/permissions/graph`, `/api/collection/graph`, `/api/dashboard/:id` con `dashcards`; el falso copia esas formas).
- [ ] **Step 4: Run to verify they pass** — `make pruebas` → PASS.
- [ ] **Step 5: Commit** — `feat(brecha): provisión de Metabase por organización con sus tableros de partida`

### Task 11: Prueba de humo y documentación

**Files:**
- Modify: `B/herramientas/metabase.py`, `B/Makefile` (`humo`, `respaldo`), `B/README.adoc` (sección «Metabase»), `docs/develop/README.md` (enlace en «8. Otra organización y el entorno de pruebas»)
- Create: `B/humo.py`, `docs/develop/metabase-cortes.md`
- Test: `B/pruebas/test_humo.py`

**Interfaces:**
- Consumes: `Metabase`, `provisionar`, `MetabaseFalso`.
- Produces:
  - `metabase.humo(url: str, admin: tuple[str, str], usuarios: dict[str, tuple[str, str]], espera: float = 120) -> list[str]` (fallas; vacía = OK). Comprueba: `GET /api/health` `{"status": "ok"}`; cada base `Brecha …` sincroniza (`POST /api/database/:id/sync_schema`, espera hasta ver `v_segmentos` en `GET /api/database/:id/metadata`); cada tarjeta de cada tablero da `status: "completed"` (`POST /api/card/:id/query`); con la sesión de cada usuario: `GET /api/database` no lista la base de otra organización, `GET /api/card/:id` de una tarjeta ajena es 403, `POST /api/dataset` nativo sobre la base ajena es 403, `POST /api/dataset` nativo sobre la propia (`SELECT 1`) es 403 (sin SQL nativo); con la sesión de admin, `ATTACH '/datos/<otra>.duckdb'` sobre una base da error.
  - `humo.py` (`METABASE_URL`, `METABASE_ADMIN_EMAIL`, `METABASE_ADMIN_PASSWORD`, `--usuario org:email` con la clave en `METABASE_CLAVE_USUARIO`); imprime `humo: OK (<n> comprobaciones)` o cada falla y sale con 1.

- [ ] **Step 1: Write the failing tests**

```python
def test_humo_pasa_con_aislamiento(self):  # MetabaseFalso provisionado para ficticia-a/b -> humo(...) == []
def test_humo_detecta_una_base_visible(self):
    # falso que lista la base B al usuario A -> una falla que nombra "ficticia-a" y "Brecha Ficticia B"
def test_humo_detecta_sql_nativo(self):  # falso que acepta POST /api/dataset nativo del usuario -> una falla
def test_humo_detecta_tarjeta_rota(self):  # una tarjeta con status "failed" -> una falla que la nombra
def test_humo_cli(self):  # humo.main([...]) con el falso -> 0 y "humo: OK"
```

- [ ] **Step 2: Run to verify they fail** — `make pruebas` → FAIL.
- [ ] **Step 3: Implement** `humo` y `humo.py`; Makefile `humo` y `respaldo ENV=` (`metabase/respaldo.sh $(ENV)`).
- [ ] **Step 4: Write** `docs/develop/metabase-cortes.md`: qué es y por qué (enlace a #206); carpetas del VPS (`/srv/srtm-metabase/{metabase.env,duckdb,cortes,respaldos}`); primera puesta (copiar el `.env.example`, `chmod 600`, `up -d --build`, túnel `ssh -fN -L 3310:localhost:3310 <vps>`, `make provisionar`, `provisionar.py usuario`); **después de cada corte** (`make duckdb ORG=…`, `docker compose -p srtm-metabase restart metabase`, `make humo`); cron del respaldo (`30 3 * * *`) y cómo restaurar; subir versiones (Metabase y driver juntos, y `duckdb` de `requirements.txt` igual a la DuckDB del driver); prueba de humo con dos organizaciones ficticias (`make corte-ficticio ORG=ficticia-a SEMILLA=1`, `…-b SEMILLA=2`, `armar_duckdb.py --corte … --destino /srv/srtm-metabase/duckdb/ficticia-a.duckdb`, `provisionar.py --org ficticia-a:"Ficticia A" --org ficticia-b:"Ficticia B"`, un usuario por cada una, `make humo`); qué hacer si el humo marca una base visible. Sección «Metabase» del README del informe que enlaza esto.
- [ ] **Step 5: Verify**

Run: `cd docs/informes/brecha && make pruebas && docker compose -f metabase/compose.yml --env-file metabase/metabase.env.example config -q && shellcheck metabase/*.sh`
Expected: PASS, sin salida, sin hallazgos.
- [ ] **Step 6: Commit** — `docs(brecha): prueba de humo de Metabase y cómo actualizar los DuckDB después de cada corte`
