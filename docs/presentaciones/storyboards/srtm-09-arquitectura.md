# 09 · Arquitectura técnica

<!-- generado por scripts/storyboards.ts a partir de src/videos: no se edita a mano -->

- **Composición:** `S09-Arquitectura` · **vídeo:** `salida/videos/srtm-09-arquitectura.mp4` · **miniatura:** `salida/miniaturas/srtm-09-arquitectura.jpg`
- **Audiencia:** Ingenieros de software, arquitectos y DevOps
- **Objetivo:** Entender cómo está construido srtm-backend sobre wasichai, cómo recorre una petición, cómo se guarda el modelo y qué garantías aplica el código.
- **Duración:** 7:31 (13518 fotogramas a 30 fps, 1920 × 1080) · **escenas:** 15
- **Manuales:** Manual técnico del sistema

| # | Escena | Tipo | Inicio | Duración | Fuente |
|---|---|---|---|---|---|
| 1 | Arquitectura técnica | Portada | 0:00 | 11,0 s | Manual técnico del sistema › A quién está dirigido |
| 2 | Un backend, un portal y una base | Diagrama | 0:10 | 36,0 s | Manual técnico del sistema › Contexto |
| 3 | La pila y sus versiones | Tabla | 0:46 | 25,0 s | Manual técnico del sistema › Versiones |
| 4 | Los paquetes de srtm y la plataforma | Diagrama | 1:10 | 34,0 s | Manual técnico del sistema › Componentes |
| 5 | Lo que da la plataforma y lo que agrega SRTM | Comparación | 1:44 | 26,0 s | Manual técnico del sistema › La plataforma wasichai |
| 6 | De la petición a la fila y su auditoría | Secuencia | 2:09 | 68,5 s | Manual técnico del sistema › Recorrido de una petición |
| 7 | El modelo se carga por REST, no con SQL | Proceso | 3:17 | 27,0 s | Manual técnico del sistema › Modelo de metadata cargado por REST |
| 8 | El padrón: contribuyente, predio y DJ | Diagrama | 3:43 | 24,5 s | Manual técnico del sistema › Diagramas entidad-relación |
| 9 | Garantías que no dependen del portal | Tabla | 4:07 | 35,0 s | Manual técnico del sistema › Decisiones verificables en el código |
| 10 | Lotes repartidos entre instancias | Diagrama | 4:42 | 39,0 s | Manual técnico del sistema › Emisión masiva |
| 11 | Cuántos trabajadores corre una instancia | Cálculo | 5:20 | 27,0 s | Manual técnico del sistema › Trabajadores |
| 12 | Disco local o bucket S3 | Comparación | 5:46 | 25,0 s | Manual técnico del sistema › Almacén de archivos |
| 13 | Autenticación, permisos y auditoría | Tarjetas | 6:11 | 34,0 s | Manual técnico del sistema › Autenticación |
| 14 | Cuatro suites, todas en el CI | Tabla | 6:44 | 26,0 s | Manual técnico del sistema › Pruebas |
| 15 | La arquitectura, en pocas palabras | Cierre | 7:10 | 21,0 s | Manual técnico del sistema › Arquitectura |

## 1. Arquitectura técnica

- **Id:** `portada` · **tipo:** Portada · **inicio:** 0:00 · **duración:** 11,0 s
- **Objetivo de aprendizaje:** Presentar el vídeo y su audiencia.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** El bloque azul baja; la marca, el título y el subtítulo entran escalonados; luego la audiencia y las fuentes.
- **Fuentes:** Manual técnico del sistema › A quién está dirigido (`docs/manuales/sistema/manual-sistema.adoc#intro-audiencia`)
- **Sigue:** 2. Un backend, un portal y una base
- **Contenido:**

  > Manual técnico del sistema: Arquitectura técnica
  > Cómo está construido el backend de SRTM y qué garantiza su código.
  > Para quién: Ingenieros de software, arquitectos y DevOps.

## 2. Un backend, un portal y una base

- **Id:** `contexto` · **tipo:** Diagrama · **inicio:** 0:10 · **duración:** 36,0 s
- **Objetivo de aprendizaje:** Situar srtm-backend entre sus usuarios, el portal, la base, el almacén y los servicios externos.
- **Imagen:** diagrama de 13 nodos y 10 flechas, dibujado en la escena
- **Animación:** Los nodos aparecen por pasos y las flechas se dibujan; lo nuevo de cada paso se resalta un momento y su explicación aparece al pie.
- **Fuentes:** Manual técnico del sistema › Contexto (`docs/manuales/sistema/manual-sistema.adoc#arq-contexto`)
- **Sigue:** 3. La pila y sus versiones
- **Contenido:**

  > Nodos: Funcionarios de rentas · Administrador · Portal tributario · Consola de administración · API /api/srtm/** · wasichai Core · Teselas de OpenStreetMap · catastro-backend · PIDE RENIEC · Almacén de emisiones · PostgreSQL 18
  > Paso 1: Los funcionarios usan el portal tributario; el administrador, la consola /admin. Los dos llaman a la misma API.
  > Paso 2: srtm-backend es un solo proceso en el puerto 8090: la API de SRTM (/api/srtm/**) y la de Core (/api/**).
  > Paso 3: La base y el almacén de los PDF y ZIP de la emisión son el único estado compartido entre instancias.
  > Paso 4: Opcionales: catastro-backend para la brecha y PIDE RENIEC, apagado. Las teselas de los mapas las pide el navegador.

## 3. La pila y sus versiones

- **Id:** `versiones` · **tipo:** Tabla · **inicio:** 0:46 · **duración:** 25,0 s
- **Objetivo de aprendizaje:** Conocer las versiones de la pila y dónde se fija cada una.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Versiones (`docs/manuales/sistema/manual-sistema.adoc#req-versiones`); Manual técnico del sistema › Versión que se describe (`docs/manuales/sistema/manual-sistema.adoc#intro-version`)
- **Sigue:** 4. Los paquetes de srtm y la plataforma
- **Contenido:**

  > Componente | Versión | Dónde se fija
  > JDK | 25 | jvmToolchain(25) en build.gradle.kts
  > Kotlin | 2.4.21 | build.gradle.kts
  > Spring Boot | 4.1.1 | plugin org.springframework.boot
  > Gradle | 9.8.1 | gradle-wrapper.properties: use ./gradlew
  > wasichai | 1.6.0 | val wasichaiVersion en build.gradle.kts
  > PostgreSQL + PostGIS | 18 + 3.6 | imagen postgis/postgis:18-3.6
  > Python | 3.11 o superior | model/: solo biblioteca estándar
  > Node | 26 o superior | srtm-ui y herramientas del repositorio
  > Nota: La etiqueta v0.9.0 se construyó con wasichai 0.6.0: no trae la brecha ni aplica la migración V18 de Core.

## 4. Los paquetes de srtm y la plataforma

- **Id:** `componentes` · **tipo:** Diagrama · **inicio:** 1:10 · **duración:** 34,0 s
- **Objetivo de aprendizaje:** Ver los paquetes de srtm-backend, en qué dirección se usan y sobre qué starters de wasichai corren.
- **Imagen:** diagrama de 18 nodos y 12 flechas, dibujado en la escena
- **Animación:** Los nodos aparecen por pasos y las flechas se dibujan; lo nuevo de cada paso se resalta un momento y su explicación aparece al pie.
- **Fuentes:** Manual técnico del sistema › Componentes (`docs/manuales/sistema/manual-sistema.adoc#arq-componentes`); Manual técnico del sistema › Módulos de SRTM y responsabilidades (`docs/manuales/sistema/manual-sistema.adoc#arq-modulos`)
- **Sigue:** 5. Lo que da la plataforma y lo que agrega SRTM
- **Contenido:**

  > Nodos: pide · rentas · impuesto · transversal · arbitrios · emision · brecha · sanciones · anuncios · gis · workflow · documents · views · forms · pages · core
  > Paso 1: La base: rentas (el registro tributario), impuesto, pide y las piezas transversales que usan todos.
  > Paso 2: Emisión usa rentas, impuesto y arbitrios; la brecha reusa sus reglas sin copiarlas.
  > Paso 3: Infracciones (sanciones) y anuncios: el CUIS, las actas y resoluciones; el padrón de anuncios y sus movimientos.
  > Paso 4: Todo corre sobre siete starters de wasichai. La clase srtm.SrtmApplication lleva @WasichaiApplication.

## 5. Lo que da la plataforma y lo que agrega SRTM

- **Id:** `wasichai` · **tipo:** Comparación · **inicio:** 1:44 · **duración:** 26,0 s
- **Objetivo de aprendizaje:** Distinguir lo que da la plataforma wasichai de lo que SRTM agrega por su cuenta.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las dos columnas entran y sus puntos aparecen uno a uno.
- **Fuentes:** Manual técnico del sistema › La plataforma wasichai (`docs/manuales/sistema/manual-sistema.adoc#arq-wasichai`)
- **Sigue:** 6. De la petición a la fila y su auditoría
- **Contenido:**

  > wasichai (WebFlux, corrutinas, R2DBC):
  >   - core: identidad, JWT, metadata, RecordService, permisos, auditoría, Flyway.
  >   - workflow y documents: SRTM no los usa para sus flujos ni sus PDF.
  >   - views, forms y pages: la consola de administración.
  >   - gis: el tipo GEOMETRY sobre PostGIS y /api/gis/**.
  > Lo que agrega SRTM:
  >   - PDF con Thymeleaf y openhtmltopdf sobre PDFBox.
  >   - Trabajos por lotes coordinados por PostgreSQL.
  >   - Almacén de archivos local o S3.
  >   - Candados consultivos y clientes de catastro-backend y PIDE.

## 6. De la petición a la fila y su auditoría

- **Id:** `peticion` · **tipo:** Secuencia · **inicio:** 2:09 · **duración:** 68,5 s
- **Objetivo de aprendizaje:** Seguir una petición desde el filtro JWT hasta la auditoría y la respuesta de error.
- **Imagen:** diagrama de secuencia: srtm-ui, filtro JWT, controlador, Permisos, Registros, RecordService, PostgreSQL
- **Animación:** Los participantes aparecen; cada mensaje se dibuja en orden, resaltado mientras se explica al pie.
- **Fuentes:** Manual técnico del sistema › Recorrido de una petición (`docs/manuales/sistema/manual-sistema.adoc#arq-peticion`)
- **Sigue:** 7. El modelo se carga por REST, no con SQL
- **Contenido:**

  > 1. ui → jwt: Bearer <token> (Valida el token en toda ruta /api, salvo login, token y salud: si no, 401.)
  > 2. jwt → ctl: función suspend (El controlador es una función suspend: el contexto de seguridad viaja en la corrutina.)
  > 3. ctl → perm: permiso de creación (Si el acto lo pide, exige permiso de creación; si falta, 403 sin leer nada.)
  > 4. ctl → reg: leer y escribir (Registros llama en proceso a RecordService como el usuario, con la marca EscrituraDeSrtm.)
  > 5. reg → rs: guardias (RecordService aplica permisos, appendOnly y las RecordWriteGuard, entre ellas GuardiaDeEscritura.)
  > 6. rs → db: candado (Los actos que no deben cruzarse toman un candado consultivo en la misma transacción.)
  > 7. rs → db: fila + audit_log (Core escribe la fila en la tabla de la organización y su entrada en audit_log.)
  > 8. ctl → ui: problem+json (Todo error es problem+json: Faltan responde 422 con faltan; un duplicado, 409.)

## 7. El modelo se carga por REST, no con SQL

- **Id:** `metadata` · **tipo:** Proceso · **inicio:** 3:17 · **duración:** 27,0 s
- **Objetivo de aprendizaje:** Entender que el modelo es metadata cargada por REST y qué consecuencias tiene.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los pasos entran en orden, unidos por flechas o por una línea que crece.
- **Fuentes:** Manual técnico del sistema › Modelo de metadata cargado por REST (`docs/manuales/sistema/manual-sistema.adoc#arq-metadata`); Manual técnico del sistema › Dónde se guarda (`docs/manuales/referencia/modelo-datos.adoc#ref-modelo-almacenamiento`)
- **Sigue:** 8. El padrón: contribuyente, predio y DJ
- **Contenido:**

  > 1. model/model.json: 42 objetos, 44 relaciones y 61 listas de opciones.
  > 2. model/apply.py: Lo valida y lo aplica con el servidor corriendo.
  > 3. /api/objects: Core guarda la definición en el esquema wasichai.
  > 4. app_data.<objeto>__<8 hex>: Una tabla física por objeto y organización.
  > Nota: El modelo se cambia en model.json y con apply.py, nunca con ALTER TABLE. Una consulta SQL busca el nombre físico en wasichai.custom_objects.

## 8. El padrón: contribuyente, predio y DJ

- **Id:** `padron` · **tipo:** Diagrama · **inicio:** 3:43 · **duración:** 24,5 s
- **Objetivo de aprendizaje:** Reconocer los objetos del padrón y sus relaciones principales.
- **Imagen:** diagrama de 12 nodos y 11 flechas, dibujado en la escena
- **Animación:** Los nodos aparecen por pasos y las flechas se dibujan; lo nuevo de cada paso se resalta un momento y su explicación aparece al pie.
- **Fuentes:** Manual técnico del sistema › Diagramas entidad-relación (`docs/manuales/referencia/modelo-datos.adoc#ref-modelo-diagramas`)
- **Sigue:** 9. Garantías que no dependen del portal
- **Contenido:**

  > Nodos: contribuyente · declaracion_predial · predio · domicilio · relacionado · medio_contacto · sustento · transferente · nivel_construccion · obra_complementaria · otro_frente · catastro_fiscal
  > Paso 1: Del contribuyente cuelgan sus domicilios, relacionados, medios de contacto y documentos de sustento.
  > Paso 2: De cada declaración, sus transferentes, niveles de construcción, obras complementarias y otros frentes.
  > Paso 3: La línea punteada no es una relación de Core: el predio guarda el código CPU del lote como texto.

## 9. Garantías que no dependen del portal

- **Id:** `decisiones` · **tipo:** Tabla · **inicio:** 4:07 · **duración:** 35,0 s
- **Objetivo de aprendizaje:** Conocer las garantías que el código aplica siempre, y dónde están.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Decisiones verificables en el código (`docs/manuales/sistema/manual-sistema.adoc#arq-decisiones`)
- **Sigue:** 10. Lotes repartidos entre instancias
- **Contenido:**

  > Decisión | Qué garantiza | Dónde
  > Guardia de escritura | Se consulta antes de cada escritura, por cualquier camino; rechaza cambios y borrados (409). | GuardiaDeEscritura.kt
  > Solo se agregan | cuota_arbitrio y su anulación son appendOnly: PUT o DELETE da 409. | model/model.json
  > Candados consultivos | pg_advisory_xact_lock en la transacción; siete clases con el prefijo 0x5352. | Candados.kt
  > Faltan → 422 | Lo que impide un acto se nombra en faltan: «UIT 2027». | Faltan.kt
  > Duplicados → 409 | Un valor único repetido es 409 en castellano, nunca 500. | Duplicados.kt
  > Observación | Cada acto de arbitrios, sanciones y anuncios: de 5 a 500 caracteres. | Observacion.kt
  > Trabajos como su usuario | Emisión, determinación y brecha corren con los permisos de quien las lanzó. | IdentidadEmision.kt

## 10. Lotes repartidos entre instancias

- **Id:** `emision` · **tipo:** Diagrama · **inicio:** 4:42 · **duración:** 39,0 s
- **Objetivo de aprendizaje:** Entender cómo se reparte la emisión masiva en lotes entre instancias y cómo termina.
- **Imagen:** diagrama de 6 nodos y 7 flechas, dibujado en la escena
- **Animación:** Los nodos aparecen por pasos y las flechas se dibujan; lo nuevo de cada paso se resalta un momento y su explicación aparece al pie.
- **Fuentes:** Manual técnico del sistema › Emisión masiva (`docs/manuales/sistema/manual-sistema.adoc#comp-emision-masiva`); Manual técnico del sistema › Estados y fallos (`docs/manuales/sistema/manual-sistema.adoc#async-estados`)
- **Sigue:** 11. Cuántos trabajadores corre una instancia
- **Contenido:**

  > Nodos: POST /api/srtm/emisiones · PENDIENTE · EN_PROCESO · ENSAMBLANDO · TERMINADA · FALLIDA
  > Paso 1: La preparación, como quien la lanzó, ordena a los contribuyentes por código y los corta en lotes de 100.
  > Paso 2: Los trabajadores de todas las instancias toman lotes con FOR UPDATE SKIP LOCKED; un lote sin latido se retoma a los 2 minutos.
  > Paso 3: Quien cierra el último lote pasa a ENSAMBLANDO con un UPDATE condicional y une las partes en el almacén.
  > Paso 4: FALLIDA: no se pudo ensamblar o nadie la corría. Un lote con 3 intentos fallidos no la hace fallar: sus contribuyentes van a errores.

## 11. Cuántos trabajadores corre una instancia

- **Id:** `trabajadores` · **tipo:** Cálculo · **inicio:** 5:20 · **duración:** 27,0 s
- **Objetivo de aprendizaje:** Saber cuántos trabajadores corre cada instancia y qué los limita.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La fórmula aparece primero y luego cada fila de la cuenta, hasta el total.
- **Fuentes:** Manual técnico del sistema › Trabajadores (`docs/manuales/sistema/manual-sistema.adoc#comp-trabajadores`); Manual técnico del sistema › Desplegar en un servidor (`docs/manuales/sistema/manual-sistema.adoc#desp-servidor`)
- **Sigue:** 12. Disco local o bucket S3
- **Contenido:**

  > auto = min(núcleos, (memoria máxima − 512 MiB) / 300 MiB)
  > Por la memoria (con -Xmx4g: (4096 − 512) / 300): 11
  > Por los núcleos (nunca más que los núcleos de la máquina): ≤ núcleos
  > Por el pool (spring.r2dbc.pool.max-size − 2, con el pool de 10): 8
  > Con -Xmx4g (el menor de los tres límites): hasta 8
  > Nota: Un número fijo se toma tal cual; 0 es una instancia que atiende peticiones sin trabajadores.

## 12. Disco local o bucket S3

- **Id:** `almacen` · **tipo:** Comparación · **inicio:** 5:46 · **duración:** 25,0 s
- **Objetivo de aprendizaje:** Elegir entre el almacén local y S3 según cuántas instancias haya.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las dos columnas entran y sus puntos aparecen uno a uno.
- **Fuentes:** Manual técnico del sistema › Almacén de archivos (`docs/manuales/sistema/manual-sistema.adoc#comp-almacen`)
- **Sigue:** 13. Autenticación, permisos y auditoría
- **Contenido:**

  > local (por defecto):
  >   - En srtm.emision.dir; por defecto ./data/emisiones.
  >   - Escribe en un temporal y renombra de forma atómica.
  >   - Varias instancias: el mismo volumen en todas.
  >   - Descarga en streaming con Range (206).
  > s3:
  >   - En <bucket>/<prefijo>/<clave>.
  >   - PutObject hasta 16 MiB; más, subida en partes.
  >   - Sin volumen: cada pod solo sus temporales.
  >   - Al arrancar, HeadBucket: sin bucket no arranca.

## 13. Autenticación, permisos y auditoría

- **Id:** `seguridad` · **tipo:** Tarjetas · **inicio:** 6:11 · **duración:** 34,0 s
- **Objetivo de aprendizaje:** Conocer las piezas de seguridad y lo que hay que cambiar fuera de desarrollo.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Las tarjetas entran una a una, en orden de lectura; al final, la nota.
- **Fuentes:** Manual técnico del sistema › Autenticación (`docs/manuales/sistema/manual-sistema.adoc#seg-autenticacion`); Manual técnico del sistema › Semilla de desarrollo (`docs/manuales/sistema/manual-sistema.adoc#seg-seed`); Manual técnico del sistema › Autorización (`docs/manuales/sistema/manual-sistema.adoc#seg-autorizacion`); Manual técnico del sistema › Guardia de escritura (`docs/manuales/sistema/manual-sistema.adoc#seg-guardia`); Manual técnico del sistema › Auditoría (`docs/manuales/sistema/manual-sistema.adoc#seg-auditoria`); Manual técnico del sistema › Endurecimiento disponible y apagado (`docs/manuales/sistema/manual-sistema.adoc#seg-pendiente`)
- **Sigue:** 14. Cuatro suites, todas en el CI
- **Contenido:**

  > JWT HS256: Toda ruta /api pide token, salvo login, token y salud. Un token de usuario vale 8 horas.
  > Un secreto propio: WASICHAI_JWT_SECRET de 32 bytes o más: el de application.yml no protege nada.
  > Semilla de desarrollo: Fije WASICHAI_SEED_DEV=false antes del primer arranque de un entorno real.
  > RBAC: Permisos por objeto y acción; SRTM no usa permisos por campo.
  > Guardia de escritura: Infracciones y anuncios solo se escriben por la API de SRTM: si no, 403.
  > Auditoría de solo agregar: Desde V13 de Core, disparadores rechazan UPDATE, DELETE y TRUNCATE.
  > Nota: Apagados por defecto: límite de intentos de ingreso, revocación de tokens y una política de claves más estricta.

## 14. Cuatro suites, todas en el CI

- **Id:** `pruebas` · **tipo:** Tabla · **inicio:** 6:44 · **duración:** 26,0 s
- **Objetivo de aprendizaje:** Saber qué pruebas hay, qué cubren y cómo se corren.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** La tabla aparece y sus filas entran en orden; las filas destacadas llevan el filete azul.
- **Fuentes:** Manual técnico del sistema › Pruebas (`docs/manuales/sistema/manual-sistema.adoc#pruebas`); Manual técnico del sistema › Integración continua (`docs/manuales/sistema/manual-sistema.adoc#pruebas-ci`)
- **Sigue:** 15. La arquitectura, en pocas palabras
- **Contenido:**

  > Suite | Qué prueba | Cómo se corre
  > Unitarias | Reglas puras, la guardia, el mapeo, los PDF y el almacén local: 68 clases. | ./gradlew build
  > Integración | La aplicación entera contra PostGIS, por HTTP: 47 clases. | ./gradlew integrationTest
  > Modelo e importadores | model.json y cada importador contra un Core falso: 18 archivos. | python3 -m unittest -v
  > Formato | YAML y JSON, model.json incluido. | yarn format:check
  > Nota: ci.yml las corre en cada PR y en cada push a main; commits.yml exige Conventional Commits.

## 15. La arquitectura, en pocas palabras

- **Id:** `cierre` · **tipo:** Cierre · **inicio:** 7:10 · **duración:** 21,0 s
- **Objetivo de aprendizaje:** Recordar lo esencial de la arquitectura y saber dónde seguir.
- **Imagen:** composición tipográfica, sin captura
- **Animación:** Los puntos del resumen entran uno a uno; luego los manuales y el vídeo siguiente.
- **Fuentes:** Manual técnico del sistema › Arquitectura (`docs/manuales/sistema/manual-sistema.adoc#arquitectura`)
- **Sigue:** fin del vídeo
- **Contenido:**

  > ✓ Un proceso Spring Boot sobre wasichai: la API de SRTM y la de Core.
  > ✓ El modelo es metadata cargada por REST, nunca SQL propio.
  > ✓ Toda escritura pasa por permisos, guardia y auditoría.
  > ✓ Los trabajos masivos se reparten en lotes coordinados por PostgreSQL.
  > Para saber más: Manual técnico del sistema · Referencias: modelo de datos, API y configuración
  > Siguiente: 10 · Instalación, operación y recuperación
