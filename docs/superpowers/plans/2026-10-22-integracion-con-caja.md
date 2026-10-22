# Integración con caja: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** srtm da de alta en caja las cuotas de predial y arbitrios como órdenes de cobro, las retira o sustituye cuando
cambian, recibe los pagos del buzón de caja, y contesta la conciliación del día y la vigencia antes del cobro.

**Architecture:**
- **Cola de órdenes:** un objeto `orden_caja` es la cola y el registro de lo que srtm mandó a caja. La crea `Registros`,
  con la marca `EscrituraDeSrtm`. Sus cambios de estado son `UPDATE` directos por `TablasCore`, como los lotes de la
  emisión. Un bucle por organización la vacía (`EnvioACaja`), y las acciones del usuario la vacían en línea con un plazo.
- **Una sola regla de sincronización:** `sincronizar(contribuyente, anio)` calcula las órdenes que deberían estar en
  caja (`deseadas`) y las compara con las que hay (`planificar`, función pura). De ahí salen altas, sustituciones y
  retiros. Los ganchos de #164 solo llaman esa función para los contribuyentes que ya tienen órdenes del año.
- **Caja ante srtm:** caja se autentica ante srtm con una cuenta de servicio de srtm (client credentials de wasichai).
  Así `POST /pagos` y los demás usan la cadena JWT de siempre, y la organización sale del token.

**Tech Stack:** Kotlin, Spring Boot 4 WebFlux con corrutinas, wasichai 1.3 (Registros, `TablasCore`, cuentas de
servicio ADR-043). El modelo está en `model/model.json`. Tests de integración con `SrtmApiTest`, y la caja falsa con el
`com.sun.net.httpserver.HttpServer` del JDK, como `BrechaApiTest`. Del lado de caja: caja-backend (Kotlin) con
`SistemaDeOrigenFalso`.

**Spec:** issues srtm-backend #163, #164, #165, #166 y #168 (con el comentario de #165), y el contrato de caja-backend
main `5251a20`: su README (secciones «Ordenes de cobro», «Sustitución», «Retiro», «El buzón», «La conciliación del
día», «Vigencia», «Siguientes pasos») y `docs/superpowers/plans/2026-10-22-ordenes-que-cambian-en-el-origen.md`.

## Validación contra caja-backend main (`5251a20`) y decisiones

| # | Lo que dicen los issues | Lo que hay en caja o en srtm | Decisión |
|---|---|---|---|
| D1 | `POST /pagos` «autenticado con el token que caja tiene en `caja.buzon.destinos.rentas`» | caja manda un `Bearer` fijo. La cadena de wasichai decodifica todo `Bearer` como JWT (401 aunque la ruta sea pública), y si srtm declara otra `SecurityWebFilterChain`, la de wasichai se apaga (`@ConditionalOnMissingBean`). | **Tarea 0 en caja-backend:** el destino puede autenticarse con `client-id` y `client-secret` contra `POST {token-url}` y renovar el token. srtm crea una cuenta de servicio `caja` con rol `CAJA` por organización. La organización sale del claim `org`. |
| D2 | #164: `redeterminarPredio` devuelve los ids de las cuotas anuladas y escritas | — | **No hace falta.** La sincronización compara lo deseado con lo enviado, sea cual sea el cambio (redeterminación, rectificatoria, anulación, condómino o parámetro). |
| D3 | «se marca sin enviar y se reintenta» | srtm no tiene `@Scheduled` ni outbox; tiene `TablasCore` y `UPDATE` con compare-and-set (`MaquinaLotes`) | Cola `orden_caja` con estados. Se toma con `tomado_hasta` (lease) por compare-and-set. El bucle `EnvioACaja` corre cada `srtm.caja.intervalo` (PT30S), y en los tests se apaga (`intervalo=0`). |
| D4 | `cuota_predial` versionada, escrita al emitir o al enviar | La liquidación es pura y no guarda nada | `cuota_predial` appendOnly. La escribe `sincronizar` cuando la liquidación da otro monto que la versión vigente (la de versión más alta de `(contribuyente, anio, numero)`). |
| D5 | (no dice) | caja exige `concepto`, `fecha_exigibilidad`, `actualizado_a` y `observacion` (5-500 caracteres), y rechaza toda otra clave | `concepto`: «Impuesto predial {anio} cuota {n}» o «{servicio} {anio}-{mm}». `detalle`: el código del predio en arbitrios, nada en predial. `fecha_exigibilidad` = `{anio}-01-01`, porque se puede pagar por adelantado. `actualizado_a` = hoy en Lima. `observacion` = el motivo del envío. |
| D6 | 409 «con recibo» al sustituir una orden PAGADA | caja responde 409 y no crea la nueva. El recibo solo va en el `detail` (no se interpreta). | Un 409 de una sustitución pone la orden nueva en `CONFLICTO` con el `detail`, y no se reintenta. Un 409 de un retiro solo puede ser PAGADA, y pone la orden vieja en `PAGADA_EN_CAJA`. El pago llega por el buzón. |
| D7 | «La redeterminación no toca meses pagados. Si cambian, la diferencia va al saldo» | srtm anula sin mirar pagos (`Redeterminacion.kt:12`) | Los meses pagados no se redeterminan y la anulación manual de una cuota pagada da 409. **La diferencia al saldo queda fuera** (YAGNI): la respuesta cuenta `pagadas` para que el usuario lo sepa. Lo mismo en predial: una cuota pagada no cambia de versión. |
| D8 | SIN DOCUMENTO: «propuesta: no se envía y se avisa» | `pagador_documento` es opcional en caja | Se sigue la propuesta: no se envía, y el resumen cuenta `sin_documento`. |
| D9 | «al emitir (masiva y por predio) y a pedido» | La emisión por predio son GET de PDF: no deben escribir | A pedido: `POST /contribuyentes/{id}/caja`. Masiva: `enviar_a_caja: true` en el pedido de `POST /emisiones`, que sincroniza cada contribuyente del lote. Los ganchos de #164 solo tocan a quien ya tiene órdenes del año. |
| D10 | Rutas `POST {url}/pagos`, `/pagos/conciliacion`, `/ordenes/vigencia` | caja agrega esos sufijos a `destinos.<s>.url` | En srtm: `/api/srtm/caja/pagos`, `/api/srtm/caja/pagos/conciliacion` y `/api/srtm/caja/ordenes/vigencia`. La url del destino es `https://<srtm>/api/srtm/caja`. |
| D11 | El pago se imputa por `referenciaExterna` | Un pago puede traer varias órdenes, unas vigentes y otras no | Se clasifica cada orden. Si todas se aplican: **200** `{"aplicado":true,"a_cuenta":false,"motivo":null}`. Si alguna no se aplica: **202** `{"aplicado":false,"a_cuenta":true,"motivo":"…"}`; las que sí se aplican quedan aplicadas y el resto va a saldo. `pagoId` repetido: **409** con el mismo cuerpo. |
| D12 | Estado de cuenta: «las cuotas pagadas dejan de ser deuda» | srtm no tiene estado de cuenta | `CuotaMes.pagada` en la matriz de arbitrios, y `GET /contribuyentes/{id}/caja?anio=` con las órdenes, los pagos y el saldo a favor. Los PDF (HR, HLA) no cambian en este plan. |

## Global Constraints

- Los importes viajan como cadena con `setScale(2, HALF_UP).toPlainString()`. Los importes en 0 no se envían.
- `referencia_externa`: predial `PREDIAL-{contribuyenteUuid}-{anio}-C{n}-v{k}`, arbitrios `ARB-{clave}`. Hasta 120
  caracteres, y nunca en una URL ni en un log con datos del contribuyente.
- `pagador_externo_id` nunca se envía. `pagador_documento` = `numero_documento`, `pagador_nombre` = `nombre_completo`.
- El motivo de un retiro es de una línea y de 1 a 200 caracteres. Se corta a 200.
- Sin caja configurada para la organización, srtm se comporta exactamente como hoy: ni filas ni llamadas, y la
  cabecera `Srtm-Caja` no se agrega. Los endpoints de caja responden 409 «caja no está configurada para esta municipalidad».
- Una falla de caja nunca hace fallar la escritura de srtm (DJ, cuota, emisión).
- Nunca se loguean `client-secret`, tokens ni datos personales; solo la referencia y el estado.
- Commits: conventional commits, cuerpo con líneas de ≤ 100 caracteres, y los trailers de la sesión.
- Tests de integración contra la base externa (`WASICHAI_TEST_DB_*`; hoy `localhost:5449` es un túnel al VPS).
  Nunca Testcontainers.
- Configuración: `srtm.caja.organizaciones.<org>.{url, client-id, client-secret, timeout, organizacion}` y
  `srtm.caja.{intervalo, lote, intentos, plazo-en-linea}`. Variables `SRTM_CAJA_ORGANIZACIONES_<ORG>_URL`, etc.
  `toString()` oculta el secreto.

## Review Focus

1. **Dos srtm a la vez (dos Cores o el bucle más una acción en línea):** una orden se envía una sola vez por toma. La
   toma es por compare-and-set de `tomado_hasta`, y el alta es idempotente en caja. Test en la Tarea 4:
   `dosTomasDeLaMismaOrdenLaMandanUnaVez`.
2. **Caja cae a mitad de una sustitución:** la orden nueva queda `POR_ENVIAR` con su `sustituye_a`, y el reintento manda
   lo mismo (200 `nueva:false` si ya había llegado). Test en la Tarea 4: `sustitucionReintentadaDespuesDeTimeout`.
3. **Un contribuyente con DJ en condominio cambia el autoavalúo:** se sincronizan todos los condóminos del grupo que ya
   tenían órdenes del año, no solo el que editó. Test en la Tarea 5: `pisoEnCondominioSustituyeElPredialDeAmbos`.
4. **Un `PAGO_REGISTRADO` repetido después de un `PAGO_ANULADO`:** el repetido es 409 y no revive el pago. Test en la
   Tarea 6: `registradoRepetidoTrasAnulacionNoRevive`.
5. **Una referencia de otra organización:** el pago de la organización A nunca imputa una orden de B, aunque la
   referencia coincida. Test en la Tarea 6: `referenciaDeOtraOrganizacionVaACuenta`.

---

### Task 0 (caja-backend): el destino se autentica con una cuenta de servicio

**Repo:** `wasichai/caja-backend`, en un worktree propio y nunca en `~/IdeaProjects/caja-backend`, que es de otra
sesión. Antes se abre el issue «el destino del buzón se autentica con una cuenta de servicio del origen».

**Files:**
- Modify: `src/main/kotlin/caja/buzon/ConfiguracionDelBuzon.kt` (destino: `token-url`, `client-id`, `client-secret`)
- Create: `src/main/kotlin/caja/buzon/TokenDelDestino.kt`
- Modify: `src/main/kotlin/caja/buzon/ClienteDelSistemaDeOrigen.kt:33-122`. Las tres llamadas (`/pagos`,
  `/pagos/conciliacion` y `/ordenes/vigencia`) usan `autorizacion(destino)`.
- Modify: `README.md` (configuración del destino) y `src/main/resources/application.yml`
- Test: `src/test/kotlin/caja/buzon/TokenDelDestinoTest.kt` y el `SistemaDeOrigenFalso`, que sirve `/api/auth/token`

**Interfaces:**
- Produces:
  - `class TokenDelDestino(destino: Destino, http: WebClient, ahora: () -> Instant)`, con
    `suspend fun bearer(): String?` y `fun olvidar()`.
  - El token es `{token, expiresAt}` de `POST {token-url}` con `{"clientId","clientSecret"}`, y se cachea hasta
    `expiresAt - 60s`.
  - Con `token` fijo y sin `client-id`, `bearer()` devuelve el fijo, como hoy. Con los dos configurados, el arranque falla.
  - Un 401 o 403 del origen llama `olvidar()` y deja la entrega en NoContesta. La próxima vuelta pide un token nuevo.

- [ ] **Step 1:** tests:
  - `pideElTokenUnaVezYLoReusa`: dos entregas, un solo `POST /api/auth/token`.
  - `renuevaAlVencer`: con `ahora` adelantado más allá de `expiresAt - 60s`, se pide otro.
  - `un401OlvidaElToken`: el origen responde 401, y la siguiente entrega pide un token nuevo.
  - `tokenFijoSigueIgual`: sin `client-id`, la cabecera es la fija.
  - `losDosModosNoArrancan`: `token` y `client-id` a la vez es un error de configuración.
  - `elSecretoNoSeLoguea`: el log de un fallo de token nombra el `client-id` y el estado, nunca el secreto.
- [ ] **Step 2:** correr `./gradlew test --tests '*TokenDelDestino*'` → FAIL.
- [ ] **Step 3:** implementar. El `toString()` del destino oculta el `client-secret`.
- [ ] **Step 4:** `./gradlew check` → PASS.
- [ ] **Step 5:** commit `feat(buzon): el destino se autentica con una cuenta de servicio del origen`, PR, CI verde y merge.

### Task 1: modelo de caja

**Files:**
- Modify: `model/model.json` (enums, 4 objetos, relaciones)
- Modify: `model/test_model.py:110-111,333,443` (cuentas de objetos y relaciones, appendOnly) y el docstring de
  `model/apply.py:8`
- Create: `src/main/kotlin/srtm/caja/Modelo.kt` (nombres de objetos y campos, DTO)
- Create: `src/main/kotlin/srtm/caja/ReglasDeCaja.kt`: beans `ReglaDeEscritura` con `soloDesdeElServicio=true` para los
  cuatro objetos. `orden_caja` no se actualiza por la API (sus cambios son `UPDATE` directos).
- Modify: `docs/manuales/demo/perfiles.json`: REGISTRADOR y ARBITRIOS_EMISION reciben CREATE y READ en `cuota_predial`
  y `orden_caja`, y READ en `pago_caja` y `pago_caja_detalle`. CONSULTA recibe READ en los cuatro.
- Test: `model/test_model.py`, `src/test/kotlin/srtm/caja/ModeloCajaApiTest.kt`

**Interfaces:**
- Enums:
  - `estado_orden_caja`: `POR_ENVIAR, ENVIADA, POR_RETIRAR, RETIRADA, CANCELADA, PAGADA_EN_CAJA, CONFLICTO, ERROR`.
  - `tipo_orden_caja`: `PREDIAL, ARBITRIO`.
  - `tipo_pago_caja`: `PAGO_REGISTRADO, PAGO_ANULADO`.
- `cuota_predial` (appendOnly):
  - campos: `anio` INTEGER, `numero` INTEGER (1-4), `version` INTEGER, `monto` DECIMAL, `vencimiento` DATE,
    `valor_afecto` DECIMAL, `fecha_calculo` DATE, todos obligatorios;
  - `clave` TEXT unique = `{contribuyente}|{anio}|{numero}|{version}`;
  - relación `contribuyente`, obligatoria;
  - índice `[anio, contribuyente]`.
- `orden_caja`:
  - `referencia` TEXT unique (la `referencia_externa`);
  - `tipo` ENUM, `ranura` TEXT (ver la Tarea 3), `anio` INTEGER, `importe` DECIMAL, `concepto` TEXT,
    `detalle` TEXT, `fecha_exigibilidad` DATE;
  - `pagador_documento` TEXT, `pagador_nombre` TEXT;
  - `sustituye_a` TEXT, `motivo` TEXT, `estado` ENUM;
  - `intentos` INTEGER, `tomado_hasta` DATETIME, `ultimo_error` TEXT, `orden_id` TEXT (el UUID de caja),
    `enviada_en` DATETIME, `retirada_en` DATETIME;
  - relaciones `contribuyente` (obligatoria), `cuota_arbitrio` y `cuota_predial` (una de las dos);
  - índices `[anio, contribuyente]` y `[estado]`.
- `pago_caja` (appendOnly):
  - `pago_id` TEXT unique, `tipo` ENUM;
  - `recibo_numero` TEXT, `recibo_serie` TEXT, `fecha_pago` DATE, `cajero` TEXT, `forma_pago` TEXT;
  - `pagador_documento` TEXT, `pagador_nombre` TEXT, `total` DECIMAL;
  - `pago_original` TEXT (el `pagoOriginalId` de un anulado), `motivo` TEXT;
  - `aplicado` BOOLEAN, `a_cuenta` BOOLEAN, `respuesta` LONG_TEXT (el cuerpo devuelto, para repetirlo en el 409);
  - relación `contribuyente`, opcional;
  - índice `[fecha_pago]`.
- `pago_caja_detalle` (appendOnly):
  - `referencia` TEXT, `importe` DECIMAL, `aplicado` BOOLEAN, `motivo` TEXT;
  - `clave` TEXT unique = `{pago_id}|{referencia}`;
  - relaciones `pago` (a `pago_caja`, obligatoria), `orden` (a `orden_caja`, opcional) y `contribuyente` (opcional).
- Produces: `object Caja { const val ORDEN_CAJA = "orden_caja"; … }` y `data class OrdenCaja(…)`, con los nombres de
  campo del modelo, en `srtm/caja/Modelo.kt`.

- [ ] **Step 1:** tests:
  - `test_model.py`: 46 objetos, sus relaciones, y appendOnly = {cuota_arbitrio, anulacion_cuota_arbitrio,
    cuota_predial, pago_caja, pago_caja_detalle};
  - `ModeloCajaApiTest.laApiGenericaNoEscribeCaja`: `POST /api/records/orden_caja` como admin da 403 o 409 (la regla
    `soloDesdeElServicio`);
  - `ModeloCajaApiTest.elModeloSeAplica`: `aplicarModelo()` crea los cuatro objetos.
- [ ] **Step 2:** `python3 -m pytest model/test_model.py` y `./gradlew integrationTest --tests '*ModeloCaja*'` → FAIL.
- [ ] **Step 3:** implementar el modelo, las reglas y los perfiles.
- [ ] **Step 4:** las mismas pruebas → PASS, y `python3 model/apply.py --help` sin errores.
- [ ] **Step 5:** commit `feat(caja): modelo de órdenes, cuotas de predial y pagos de caja`.

### Task 2: cliente de caja

**Files:**
- Create: `src/main/kotlin/srtm/TokenDeServicio.kt`: se extrae de `ClienteCatastro.token()`
  (`ClienteCatastro.kt:286-320`, con `MARGEN` de 60 s y Mutex). Lo usan catastro y caja.
- Modify: `src/main/kotlin/srtm/brecha/ClienteCatastro.kt`, para usar `TokenDeServicio` (refactor sin cambio de
  conducta; `ClienteCatastroTest` sigue verde).
- Create: `src/main/kotlin/srtm/caja/ClienteCaja.kt`, con `CajaProperties`, `CuentaCaja`, `Cajas` y `CajaConfig`, a
  imagen de `CatastroProperties`, `CuentaCatastro`, `Catastros` y `CatastroConfig`.
- Modify: `src/main/resources/application.yml` (`srtm.caja.*`, apagado por defecto)
- Test: `src/test/kotlin/srtm/caja/ClienteCajaTest.kt` (HttpServer del JDK)

**Interfaces:**
- Produces:
  - `interface Caja`, con `suspend fun alta(orden: AltaDeOrden): ResultadoCaja` y
    `suspend fun retiro(referencia: String, motivo: String): ResultadoCaja`.
  - `data class AltaDeOrden(referencia, concepto, detalle?, importe: BigDecimal, fechaExigibilidad: LocalDate,
    actualizadoA: LocalDate, pagadorDocumento?, pagadorNombre?, observacion, sustituyeA?)`, serializada en snake_case
    con las claves exactas de caja. Nunca lleva `pagador_externo_id` ni claves vacías.
  - `sealed interface ResultadoCaja`:
    - `Hecho(ordenId: String, estado: String)` para 200 y 201;
    - `Conflicto(detalle: String)` para 409;
    - `NoExiste` para 404;
    - `Rechazo(detalle: String)` para 400 y 403;
    - `NoContesta(causa: String)` para 5xx, timeout, IO y un 401 repetido.
  - `class Cajas`, con `fun de(id: UUID, slug: String?): Caja?`, `val configurado: Boolean` y
    `fun organizaciones(): Set<String>`.
- Un 401 borra el token y reintenta una vez, como `autorizado` de catastro.

- [ ] **Step 1:** tests:
  - `altaMandaLasClavesDeCaja`: el cuerpo recibido tiene exactamente `referencia_externa`, `concepto`, `importe`
    (`"150.50"`, cadena), `fecha_exigibilidad`, `actualizado_a`, `pagador_documento`, `pagador_nombre` y `observacion`,
    sin `pagador_externo_id`;
  - `alta201y200SonHecho`;
  - `alta409EsConflictoConElDetail`;
  - `retiro409EsConflicto`;
  - `retiro404EsNoExiste`;
  - `timeoutEsNoContesta`;
  - `un401RenuevaElTokenUnaVez`;
  - `sinConfiguracionNoHayCaja` (`Cajas(emptyMap()).de(...) == null`);
  - `toStringOcultaElSecreto`.
- [ ] **Step 2:** `./gradlew test --tests '*ClienteCaja*' --tests '*ClienteCatastro*'` → FAIL en los de caja.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** los mismos tests → PASS.
- [ ] **Step 5:** commit `feat(caja): cliente de caja con la cuenta de servicio rentas`.

### Task 3: la sincronización como función pura

**Files:**
- Create: `src/main/kotlin/srtm/caja/Sincronizacion.kt`
- Test: `src/test/kotlin/srtm/caja/SincronizacionTest.kt`

**Interfaces:**
- Produces:
  - `data class OrdenDeseada(referencia: String, ranura: String, tipo: String, importe: BigDecimal, concepto: String,
    detalle: String?, cuotaArbitrio: UUID?, cuotaPredial: UUID?, pagada: Boolean)`.
  - Ranura: arbitrios `ARB|{predio}|{servicio}|{anio}|{periodo}|{secuencia}` (la clave sin la versión); predial
    `PREDIAL|{contribuyente}|{anio}|{n}`.
  - `fun referenciaPredial(contribuyente: UUID, anio: Int, numero: Int, version: Int): String` y
    `fun referenciaArbitrio(clave: String): String`.
  - `data class Plan(altas: List<Alta>, retiros: List<Retiro>, cancelar: List<UUID>)`, con
    `Alta(deseada, sustituyeA: String?)` y `Retiro(ordenCaja: UUID, referencia: String)`.
  - `fun planificar(deseadas: List<OrdenDeseada>, actuales: List<OrdenCaja>): Plan`.
- Reglas de `planificar`. Las filas `CONFLICTO`, `ERROR`, `CANCELADA` y `RETIRADA` no cuentan como vigentes.
  1. Una deseada cuya referencia ya está en `actuales` (en cualquier estado) no hace nada.
  2. Una deseada nueva da un `Alta` con `sustituyeA` = la referencia `ENVIADA` de su ranura, o null si no hay ninguna.
     Es la que está en caja.
  3. Las filas `POR_ENVIAR` de esa ranura con otra referencia (nunca llegaron a caja) van a `cancelar`.
  4. Una ranura con una fila `PAGADA_EN_CAJA`, o con una deseada `pagada`, no se toca.
  5. Una ranura sin deseada: su `ENVIADA` da un `Retiro` y sus `POR_ENVIAR` van a `cancelar`.
  6. Una deseada con importe 0 no existe, y su ranura sigue la regla 5.

- [ ] **Step 1:** tests, uno por regla (`mismaReferenciaNoHaceNada`, `ranuraNuevaEsAlta`,
  `otraVersionSustituyeALaEnviada`, `otraVersionCancelaLaQueNoSalio`, `pagadaNoSeToca`, `sinDeseadaSeRetira`,
  `ceroSeRetira`), más:
  - `dosCambiosAntesDeEnviarSustituyenALaEnviada`: con v1 `ENVIADA` y v2 `POR_ENVIAR` (`sustituye_a` = v1), llega v3:
    v2 se cancela y v3 sustituye a v1;
  - `conflictoNoEsVigente`: con v1 `ENVIADA` y v2 `CONFLICTO`, llega v3: v3 sustituye a v1;
  - `mismoImporteOtraVersionTambienSustituye` (#164: la versión manda, no el importe);
  - `referenciaPredialMideMenosDe120`, con un UUID y el año 2026: `PREDIAL-<uuid>-2026-C4-v12`.
- [ ] **Step 2:** `./gradlew test --tests '*SincronizacionTest*'` → FAIL.
- [ ] **Step 3:** implementar `planificar`.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(caja): plan de altas, sustituciones y retiros por ranura`.

### Task 4: alta a pedido y la cola (#163)

**Files:**
- Create: `src/main/kotlin/srtm/caja/CajaService.kt`, con `deseadas`, `sincronizar` y `estado`.
- Create: `src/main/kotlin/srtm/caja/EnvioACaja.kt`: toma, envío y actualización por `TablasCore`, más el bucle.
- Create: `src/main/kotlin/srtm/caja/CajaController.kt`, con `POST /api/srtm/contribuyentes/{id}/caja?anio=` y
  `GET /api/srtm/contribuyentes/{id}/caja?anio=`.
- Modify: `src/main/kotlin/srtm/emision/EmisionMasivaService.kt` y `LotesEmision.kt`: `enviar_a_caja` en el pedido, y
  `sincronizar` por contribuyente del lote. Si la sincronización falla, la emisión sigue.
- Test: `src/test/kotlin/srtm/caja/CajaApiTest.kt`, con una caja falsa con HttpServer del JDK que guarda los cuerpos y
  se programa para responder 201, 200, 409, 404, 503 o un retraso.

**Interfaces:**
- Consumes:
  - `planificar` y las referencias (Tarea 3);
  - `Cajas.de` y `Caja` (Tarea 2);
  - `LiquidacionService.determinar(id, anio)` (`impuesto/LiquidacionService.kt:56`);
  - `Arbitrios.vigentes` (`arbitrios/Arbitrios.kt:482`).
- Produces:
  - `suspend fun sincronizar(contribuyente: UUID, anio: Int, motivo: String): ResumenCaja`;
  - `suspend fun sincronizarSiEnviado(contribuyentes: Set<UUID>, anio: Int, motivo: String): ResumenCaja?`, que no hace
    nada (null) si ninguno tiene `orden_caja` del año, o si no hay caja;
  - `data class ResumenCaja(encoladas, enviadas, sustituidas, retiradas, pagadasEnCaja, conflictos, pendientes,
    sinDocumento: Int, error: String?)`, con `plus`;
  - `EnvioACaja.enviar(ids: List<UUID>, plazo: Duration): ResumenCaja`;
  - `EnvioACaja.vuelta(): Int`, una pasada del bucle (pública para los tests).
- **`deseadas`:**
  - El predial sale de `determinar`. Si la liquidación trae `faltan`, no hay deseadas de predial.
  - Por cada `Cuota` con monto > 0, se toma la `cuota_predial` vigente de su número. Si no existe, o su monto difiere,
    y no está pagada, se escribe la versión k+1 con `Registros` y la marca.
  - Los arbitrios son las cuotas vigentes del contribuyente en el año con monto > 0.
  - El contribuyente SIN DOCUMENTO no tiene deseadas, y el resumen cuenta `sinDocumento = 1`.
- **Cómo se escribe el plan:**
  - Cada `Alta` es una `orden_caja` `POR_ENVIAR`, creada con `Registros`.
  - Cada `cancelar` pasa a `CANCELADA` y cada `Retiro` a `POR_RETIRAR`, con `motivo`, por `UPDATE` compare-and-set
    sobre el estado previo.
  - Se hace todo bajo `Candados.bloquear(Candado.CAJA_CONTRIBUYENTE, contribuyente)` (candado nuevo en `Candados.kt`),
    en una transacción. Después, `enviar` de las filas tocadas, con `srtm.caja.plazo-en-linea` (PT5S).
- **La toma:** `UPDATE … SET tomado_hasta = now() + lease, intentos = intentos + 1 WHERE id = :id AND estado IN
  ('POR_ENVIAR','POR_RETIRAR') AND (tomado_hasta IS NULL OR tomado_hasta < now())`. Si actualiza 0 filas, otro la tiene.
- **Resultados:**

  | Estado | Respuesta de caja | Nuevo estado |
  |---|---|---|
  | `POR_ENVIAR` | `Hecho` | `ENVIADA` (`orden_id`, `enviada_en`); si llevaba `sustituye_a`, la vieja pasa a `RETIRADA` |
  | `POR_ENVIAR` | `Conflicto` | `CONFLICTO` |
  | `POR_ENVIAR` | `Rechazo` | `ERROR` |
  | `POR_RETIRAR` | `Hecho` o `NoExiste` | `RETIRADA` |
  | `POR_RETIRAR` | `Conflicto` | `PAGADA_EN_CAJA` |
  | cualquiera | `NoContesta` | sin cambio de estado, `ultimo_error` guardado; con `intentos >= srtm.caja.intentos` (20), `ERROR` |

  Siempre se suelta `tomado_hasta`.
- **El bucle:** cada `srtm.caja.intervalo`, por organización con caja (`TablasCore.de("orden_caja", …)`), toma hasta
  `srtm.caja.lote` (50) filas `POR_ENVIAR` o `POR_RETIRAR` por `created_at`. Arranca con `ApplicationReadyEvent`, se
  detiene con `@PreDestroy`, y con `intervalo=0` no arranca.
- **Permisos:**
  - `POST` exige CREATE en `orden_caja`. Sin caja: 409 con el mensaje de las Global Constraints.
  - `GET` exige READ en `orden_caja` y devuelve `{ordenes:[…estado, referencia, importe, pagada…], saldo_a_favor}`.
    `saldo_a_favor` vale 0 hasta la Tarea 6.

- [ ] **Step 1:** tests de integración (`CajaApiTest`):
  - `unPredioDa4PredialesYLasCuotasDeArbitrios`: la caja falsa recibe 4 altas de predial con los importes de
    `/liquidacion`, y una por cada cuota vigente de arbitrios del año, con los importes de la matriz;
  - `reenviarNoDuplica`: un segundo `POST` no manda nada nuevo;
  - `cajaCaidaDejaPendienteYLaVueltaLaManda`: 503 → `POR_ENVIAR` con `ultimo_error`; después 201 y `vuelta()` →
    `ENVIADA`;
  - `sinCajaEs409YNoEscribe`;
  - `cuotaEnCeroNoSeEnvia`;
  - `sinDocumentoNoSeEnvia`;
  - `dosTomasDeLaMismaOrdenLaMandanUnaVez`: dos `enviar` concurrentes, una sola alta en la caja falsa;
  - `sustitucionReintentadaDespuesDeTimeout`: la caja falsa tarda más que el timeout en la sustitución, y la vuelta
    reenvía el mismo cuerpo con `sustituye_a`;
  - `emisionMasivaConEnviarACaja`: una emisión con `enviar_a_caja:true` deja `ENVIADA` las órdenes del lote.
- [ ] **Step 2:** `./gradlew integrationTest --tests '*caja.CajaApiTest*'` → FAIL.
- [ ] **Step 3:** implementar `CajaService`, `EnvioACaja`, el controller y el gancho de la emisión.
- [ ] **Step 4:** PASS, y `./gradlew check` → PASS.
- [ ] **Step 5:** commit `feat(caja): dar de alta las cuotas de predial y arbitrios como órdenes de cobro (#163)`.
  PR «Closes #163» con las Tareas 1 a 4 y este plan. CI verde y merge.

### Task 5: retiro y sustitución cuando la deuda cambia (#164)

**Files:**
- Modify: `src/main/kotlin/srtm/rentas/DeclaracionService.kt:666-677`. `redeterminar(antes, despues)` pasa a llamarse
  `despuesDeCambiar(antes, despues)`.
  1. Hace lo de hoy (arbitrios).
  2. Llama `caja.sincronizarSiEnviado` con los contribuyentes de las DJ vigentes del grupo `(predio, anio)` de `antes`
     y de `despues`, y con el motivo.
  3. Anota el `ResumenCaja` en `AvisoDeCaja`.
  - Los llamadores (`:82`, `:109`, `:118`, `:174`, `:220`, `:254` y `:267`) pasan a `despuesDeCambiar`.
  - Corre también cuando no cambia ninguna base de arbitrios, porque el predial depende del valor afecto.
- Modify: `src/main/kotlin/srtm/arbitrios/ArbitriosService.kt`. Después de cada escritura que se compromete, se llama
  `sincronizarSiEnviado` con los contribuyentes de las cuotas tocadas:
  - `anular` (`:189-221`);
  - `determinarPredio` y `determinarContribuyente` (`:136`, `:149`);
  - `redeterminar` (`:250`);
  - `determinarEnLote` (`:180`).
- Create: `src/main/kotlin/srtm/caja/AvisoDeCaja.kt`: WebFilter con cabecera `Srtm-Caja`, a imagen de
  `AvisoDeArbitrios` (`arbitrios/AvisoDeArbitrios.kt:33-72`). Sin anotación no hay cabecera.
- Test: `src/test/kotlin/srtm/caja/CambiosEnCajaApiTest.kt`

**Interfaces:**
- Consumes: `sincronizarSiEnviado` y `ResumenCaja` (Tarea 4).
- Motivos, de una línea y ≤ 200 caracteres:
  - «Redeterminación por la DJ n° {numero_declaracion}»;
  - «Rectificatoria n° {numero}»;
  - «Anulación de la DJ n° {numero}»;
  - «Anulación de la cuota de arbitrios»;
  - «Determinación de arbitrios».
- La cabecera es `Srtm-Caja: {"encoladas":n,"enviadas":n,"sustituidas":n,"retiradas":n,"pagadas_en_caja":n,
  "conflictos":n,"pendientes":n,"error":null}`, en JSON ASCII con escapes, como `Srtm-Arbitrios`.

- [ ] **Step 1:** tests:
  - `pisoNuevoSustituyeSoloLosMesesQueCambian`: con las órdenes enviadas, agregar un piso sustituye las órdenes de
    arbitrios de los meses redeterminados (cuerpo con `sustituye_a`) y las 4 de predial; los meses iguales no reciben
    nada;
  - `rectificatoriaSustituyeElPredialDelAnio`;
  - `anularLaDjRetiraYNoDaDeAlta`;
  - `anularUnaCuotaRetiraSuOrden`;
  - `retiroDeOrdenPagadaQuedaPagadaEnCaja`: la caja falsa responde 409 al retiro → `PAGADA_EN_CAJA`, y la cabecera
    dice `pagadas_en_caja: 1`;
  - `sustitucionDeOrdenPagadaQuedaEnConflicto`;
  - `pisoEnCondominioSustituyeElPredialDeAmbos`;
  - `sinOrdenesEnviadasNoLlamaACaja`: la caja falsa no recibe nada y no hay cabecera;
  - `cajaCaidaNoFallaLaDj`: con 503, la DJ se guarda (200) y la cabecera trae `pendientes > 0`.
- [ ] **Step 2:** `./gradlew integrationTest --tests '*CambiosEnCaja*'` → FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS. Además `RedeterminacionApiTest`, `DerivadosApiTest` y `RectificatoriaApiTest` siguen verdes
  (sin caja no cambia nada).
- [ ] **Step 5:** commit `feat(caja): retirar y sustituir las órdenes al redeterminar o rectificar (#164)`. PR «Closes
  #164», CI verde y merge.

### Task 6: recibir los pagos (#165)

**Files:**
- Create: `src/main/kotlin/srtm/caja/PagosController.kt`: `POST /api/srtm/caja/pagos`, que exige CREATE en
  `pago_caja` (rol `CAJA` de la cuenta de servicio).
- Create: `src/main/kotlin/srtm/caja/Imputacion.kt`: la clasificación pura de cada orden de un pago.
- Create: `src/main/kotlin/srtm/caja/PagosService.kt`
- Modify: `src/main/kotlin/srtm/arbitrios/ArbitriosService.kt`:
  - `redeterminar` (`:270-307`) excluye las cuotas pagadas de `anular`, y sus ranuras de `escribir`;
  - `anular` da 409 «la cuota ya se pagó en caja (recibo {serie}-{numero})» si está pagada.
- Modify: `src/main/kotlin/srtm/arbitrios/AvisoDeArbitrios.kt`: `ResultadoRedeterminacion.pagadas`.
- Modify: `src/main/kotlin/srtm/arbitrios/Consultas.kt:28`: `CuotaMes.pagada: Boolean`.
- Modify: `CajaService.estado`: `saldo_a_favor` = Σ `importe` de los detalles no aplicados del contribuyente cuyos
  pagos no están anulados.
- Modify: `docs/develop/alta-organizacion.md`: crear la cuenta de servicio `caja` con el rol `CAJA`, y configurar
  `caja.buzon.destinos.rentas` con `url`, `token-url`, `client-id` y `client-secret`.
- Test: `src/test/kotlin/srtm/caja/PagosApiTest.kt` (eventos con la forma exacta del contrato, ver abajo) e
  `ImputacionTest.kt`

**Interfaces:**
- Consumes: `orden_caja` y la vigencia de las cuotas (`Arbitrios.vigentes`, la `cuota_predial` de versión más alta).
- **El evento**, en camelCase, se lee con `@JsonIgnoreProperties(ignoreUnknown = true)`:
  ```
  {"pagoId":"<uuid>","tipo":"PAGO_REGISTRADO","sistemaOrigen":"rentas",
   "recibo":{"numero":"001-0000001","serie":"001","fechaDePago":"2026-10-22","cajero":"ana@muni.gob.pe","formaDePago":"EFECTIVO"},
   "pagador":{"documento":"12345678","nombre":"…","idExterno":null},
   "total":"150.50","actualizadoA":"2026-10-22",
   "ordenes":[{"ordenId":"<uuid>","referenciaExterna":"ARB-…","importe":"150.50","actualizadoA":"2026-10-22"}]}

  {"pagoId":"<uuid>","tipo":"PAGO_ANULADO","pagoOriginalId":"<uuid>",
   "recibo":{…},"motivo":"…","fecha":"2026-10-22","total":"150.50"}
  ```
- **`Imputacion.clasificar(orden: OrdenDelPago, enSrtm: OrdenCaja?, vigente: Boolean, yaPagada: Boolean): Detalle`.**
  Una orden se aplica si:
  - su `orden_caja` es de esta organización;
  - su cuota sigue vigente;
  - el importe es igual (`compareTo`);
  - no hay otro pago aplicado y no anulado de esa orden.

  Si no, va a cuenta con el motivo, según el caso: «no existe», «la deuda {ref} fue sustituida por {nueva}», «la deuda
  {ref} ya no está vigente», «el importe no coincide: {a} en caja, {b} en srtm» o «ya pagada con el recibo {serie}-{numero}».
- **Respuestas:**

  | Caso | Respuesta | Cuerpo |
  |---|---|---|
  | Todas las órdenes se aplican | 200 | `{"aplicado":true,"a_cuenta":false,"motivo":null}` |
  | Alguna va a cuenta | 202 | `{"aplicado":false,"a_cuenta":true,"motivo":"<motivos unidos por '; ', ≤ 400>"}` |
  | `pagoId` repetido, en cualquier tipo | 409 | el `respuesta` guardado |
  | `PAGO_ANULADO` | 200 | `{"aplicado":true,"a_cuenta":<el del original>,"motivo":null}` |
  | Cuerpo ilegible: JSON roto, sin `pagoId` o `tipo`, `tipo` desconocido, `importe` que no es decimal | 400 | — |
  | `PAGO_ANULADO` cuyo original no existe | 503 | `{"motivo":"no conozco el pago original"}` |

  El anulado sin original responde 503 para que caja lo reintente. Caja toma un 409 como entregado y un 4xx mata la
  entrega; además nunca manda el anulado antes que el registrado.
- **Lo que hace un `PAGO_ANULADO`:**
  - escribe su `pago_caja`;
  - para cada `orden_caja` del original cuya cuota ya no está vigente, la pasa a `POR_RETIRAR` (caja la devolvió a
    PENDIENTE; #164) y llama `enviar` en línea.
- Se escribe con `Registros` y la marca, bajo `Candados.bloquear(Candado.CAJA_PAGO, pagoId)` (candado nuevo en
  `Candados.kt`), y se relee por `pago_id` dentro del candado.

- [ ] **Step 1:** tests:
  - `ImputacionTest`: un test por cada motivo y por la aplicación.
  - `PagosApiTest`:
    - `pagoNormalAplicaYLaCuotaQuedaPagada`: 200, `CuotaMes.pagada`, y `GET /caja` muestra la orden pagada;
    - `pagoRepetidoEs409ConElMismoCuerpo`;
    - `pagoACuentaRepetidoRepiteACuenta` (el comentario de #165);
    - `importeDistintoVaACuenta`: 202, `a_cuenta:true`, y el saldo a favor sube;
    - `cuotaSustituidaVaACuenta`;
    - `anulacionRevierteElPago`: la cuota vuelve a deuda;
    - `anulacionDeUnACuentaRevierteElSaldo`;
    - `anulacionReintentaElRetiro`: tras una redeterminación con la orden vieja pagada, el `PAGO_ANULADO` hace que la
      caja falsa reciba el retiro;
    - `registradoRepetidoTrasAnulacionNoRevive`;
    - `referenciaDeOtraOrganizacionVaACuenta`;
    - `cuerpoIlegibleEs400`;
    - `sinTokenEs401`;
    - `usuarioSinPermisoEs403`;
    - `redeterminacionNoTocaMesesPagados`: la cuota pagada sigue vigente, y la respuesta cuenta `pagadas: 1`;
    - `anularCuotaPagadaEs409`.
- [ ] **Step 2:** `./gradlew integrationTest --tests '*PagosApiTest*' --tests '*ImputacionTest*'` → FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS, más `./gradlew check`.
- [ ] **Step 5:** commit `feat(caja): recibir los pagos del buzón de caja (#165)`. PR «Closes #165», CI verde y merge.

### Task 7: conciliación y vigencia (#166, #168)

**Files:**
- Modify: `src/main/kotlin/srtm/caja/PagosController.kt`:
  - `GET /api/srtm/caja/pagos/conciliacion?fecha=` (READ en `pago_caja`);
  - `POST /api/srtm/caja/ordenes/vigencia` (READ en `orden_caja`).
- Create: `src/main/kotlin/srtm/caja/Conciliacion.kt` y `Vigencia.kt` (puras), con su servicio en `PagosService`.
- Modify: el rol `CAJA` documentado en `alta-organizacion.md` (READ en `orden_caja`, `cuota_arbitrio`,
  `anulacion_cuota_arbitrio` y `cuota_predial`).
- Test: `src/test/kotlin/srtm/caja/ConciliacionApiTest.kt`, `VigenciaApiTest.kt`, `ConciliacionTest.kt`

**Interfaces:**
- **Conciliación:** `fecha` es obligatoria, en `AAAA-MM-DD`; sin ella o mal formada, 400. La respuesta es
  `{"fecha":"2026-10-22","recibidos":n,"aplicados":n,"rechazados":0,"recibidos_a_cuenta":n,"importe_aplicado":"0.00",
  "importe_a_cuenta":"0.00"}`. Los conteos son enteros JSON y los importes cadenas con 2 decimales.
  - Pagos del día: los `PAGO_REGISTRADO` con `fecha_pago = fecha`, menos los que tienen un `PAGO_ANULADO`.
  - `recibidos`: los pagos del día.
  - `aplicados`: los pagos del día sin detalle a cuenta.
  - `recibidos_a_cuenta`: los pagos del día con algún detalle a cuenta.
  - `importe_aplicado` = Σ de los detalles aplicados de los pagos del día.
  - `importe_a_cuenta` = Σ de los detalles a cuenta de los pagos del día.
  - Un repetido no cuenta dos veces, porque hay una fila por `pago_id`.
  - `rechazados` es siempre 0, porque srtm no rechaza un pago legible.
- **Vigencia:** la pregunta es `{"ordenes":[{"referencia_externa":"…","importe":"12.50"}]}` (hasta 50 referencias; más
  de 50, 400). La respuesta es 200 `{"ordenes":[{"referencia_externa","vigente","importe","motivo"}]}`, con las mismas
  referencias en el mismo orden.
  - Vigente si la `orden_caja` existe, está `ENVIADA` y su cuota sigue vigente (arbitrios: sin anulación y sin versión
    mayor; predial: la versión más alta).
  - `importe` = el de la `orden_caja`.
  - Desconocida: `vigente:false`, `importe:null`, `motivo:"no existe"`.
  - Superada: `motivo:"sustituida por {ref}"`.
  - Anulada: `motivo:"anulada: {motivo}"`.
  - Se lee todo en 3 consultas (por `referencia IN`, y las cuotas por id), sin reliquidar.

- [ ] **Step 1:** tests:
  - `diaConPagosNormalesACuentaAnuladosYRepetidos`: la caja falsa del test de pagos entrega 3 normales, 1 a cuenta, 1
    anulado y 1 repetido, y se comprueban los conteos y los importes exactos;
  - `laFechaVaEnLaRespuesta`;
  - `sinFechaEs400`;
  - `contratoDeCaja`: el cuerpo se lee con las reglas de `aplicadoDe` de caja (enteros, importes en cadena con escala
    ≤ 2, `fecha` igual); el test copia esas reglas y cita `Conciliacion.kt:170-220` de caja-backend;
  - vigencia: `vigente`, `anulada`, `versionSuperada`, `importeDistintoDevuelveElDeSrtm`, `referenciaDesconocida`,
    `sinTokenEs401`, `masDe50Es400`, y `cincuentaReferenciasEnMenosDeUnSegundo` (medido en el test, con 50 órdenes
    reales).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implementar.
- [ ] **Step 4:** PASS, más `./gradlew check`.
- [ ] **Step 5:** commit `feat(caja): conciliación del día y vigencia de las órdenes (#166, #168)`. PR «Closes #166,
  closes #168», CI verde y merge.

### Task 8: prueba de punta a punta y srtm-ui

- **De punta a punta, en el VPS:** el stack `srtm-ica` (reconstruido con main) más caja-backend y su base, en un
  compose aparte `caja-prueba`, con la org `ica-prueba` (ficticia). Se usa con la cuenta `rentas` en caja y la cuenta
  `caja` en srtm. El recorrido:
  1. enviar un contribuyente;
  2. cobrarlo en la ventanilla (API de caja);
  3. ver el pago en srtm;
  4. redeterminar y ver la sustitución;
  5. anular el recibo y ver el retiro;
  6. conciliar el día, que debe cuadrar.

  Script en el scratchpad, `e2e_caja.py`, con `SRTM_URL` y `CAJA_URL`.
- **srtm-ui (issue nuevo):**
  - botón «Enviar a caja» en la ficha del contribuyente;
  - pestaña «Caja», con las órdenes y su estado, los pagos y el saldo a favor;
  - el aviso de `Srtm-Caja` junto al de arbitrios;
  - el mes pagado en la matriz de arbitrios.

  Se planea aparte, con su propio plan.
