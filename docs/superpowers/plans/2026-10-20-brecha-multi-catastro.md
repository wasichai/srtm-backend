# Brecha contra varios catastros: un catastro por organización (plan)

> **Para agentes:** se ejecuta tarea por tarea con TDD (superpowers:executing-plans). Los pasos usan casillas (`- [ ]`).

**Objetivo:** una misma instalación de srtm lee un catastro-backend distinto por organización (Perené e Ica), en vez
del único `srtm.catastro.*` de hoy.

**Arquitectura:** `CatastroProperties` gana el mapa `organizaciones` (clave = slug o id de la organización de srtm);
las propiedades únicas de hoy (`url`, `client-id`, `client-secret`, `organizacion`) se leen como una entrada más del
mapa. `CatastroConfig` arma un registro `Catastros` con un `ClienteCatastro` (y su token en caché) por entrada.
`BrechaService.lanzar` elige el cliente de la organización que lanza la corrida y se lo pasa a la corrida en segundo
plano; sin entrada para ella, 409 `SIN_CATASTRO_CONFIGURADO`.

**Stack:** Kotlin, Spring Boot 4 (`@ConfigurationProperties` por constructor, relaxed binding), WebClient, JUnit 5.

**Spec:** wasichai/srtm-backend#123 (épica #126).

## Restricciones globales

- No tocar `model/model.json` (otra rama lo cambia), ni catastro-backend, ni la base de dev.
- Comentarios del código en inglés en minúsculas, como el resto del paquete; README, mensajes y commits en español.
- Los mensajes de error son constantes de `ClienteCatastro.kt` (como `NO_CONFIGURADO`): el `detail` del 409 es su texto.
- El secreto nunca en un `toString`, un log ni un mensaje.

## Diseño

- `CuentaCatastro(organizacion = "", url = "", clientId = "", clientSecret = "", timeout: Duration? = null)`: una
  entrada del mapa. `organizacion`, si está, reemplaza a la clave: una variable de entorno no puede llevar guiones, así
  que un id (UUID) o un slug con guion se ponen ahí, con una clave cualquiera
  (`SRTM_CATASTRO_ORGANIZACIONES_ICA_ORGANIZACION=<uuid>`).
- `CatastroProperties.cuentas(): Map<String, CuentaCatastro>`: la entrada antigua (si `organizacion` no está vacía) y
  luego las del mapa; una del mapa con la misma organización (sin mayúsculas) reemplaza a la antigua.
- `Catastros(porOrganizacion: Map<String, Catastro>)`: `configurado`, `necesitaSlug` (alguna clave no es un id) y
  `de(id: UUID, slug: String?): Catastro?`. Una clave casa con la organización igual que hoy (`otraOrganizacion`): por
  id si la clave es un UUID, si no por slug sin mayúsculas. Dos claves que casan (su slug y su id) → 409
  `DOS_CATASTROS`.
- `CatastroConfig.catastros(properties)`: apagado → `Catastros(emptyMap())`; una cuenta sin url, client-id o
  client-secret se salta con un aviso en el log (sin el secreto).
- `BrechaService.lanzar`: `!catastros.configurado` → 409 `NO_CONFIGURADO`; `de(...) == null` → 409
  `SIN_CATASTRO_CONFIGURADO`. `DE_OTRA_ORGANIZACION`, `SIN_ORGANIZACION` y `otraOrganizacion` desaparecen.

## Foco de la revisión

- Variables de entorno con la clave en mayúsculas (`SRTM_CATASTRO_ORGANIZACIONES_PERENE_URL`): la clave llega en
  minúsculas y casa con el slug sin mayúsculas. Test de binding con `SystemEnvironmentPropertySource`.
- Un id (UUID) como clave: casa por id, nunca por slug. Test en `CatastrosTest`.
- La misma organización por la entrada antigua y por el mapa: gana el mapa, no hay dos clientes. Test de `cuentas()`.
- Una cuenta incompleta no se convierte en cliente: su organización recibe `SIN_CATASTRO_CONFIGURADO`, no un fallo de
  la corrida a medio camino. Test de `CatastroConfig`.
- Cada cliente cachea su propio token: un token de un catastro nunca va al otro. Test de integración con dos stubs que
  exigen su propio client-id y token.

---

### Tarea 1: configuración y registro por organización

**Archivos:**
- Modificar: `src/main/kotlin/srtm/brecha/ClienteCatastro.kt`
- Test: `src/test/kotlin/srtm/brecha/CatastrosTest.kt` (nuevo), `src/test/kotlin/srtm/brecha/ClienteCatastroTest.kt`

**Interfaces:**
- Produce: `CuentaCatastro`, `CatastroProperties.organizaciones: Map<String, CuentaCatastro>`,
  `CatastroProperties.cuentas()`, `Catastros`, `ClienteCatastro(cuenta: CuentaCatastro, timeout: Duration, ahora)`,
  constantes `SIN_CATASTRO_CONFIGURADO`, `DOS_CATASTROS(claves)`.

- [ ] Tests que fallan (`CatastrosTest`):
  - `the old single properties are a map of one entry`: `CatastroProperties(url=a, clientId, clientSecret,
    organizacion="perene").cuentas()` == `{"perene": CuentaCatastro(url=a, ...)}`; con `organizacion` vacía, vacío.
  - `a map entry replaces the old one of the same organization`: antigua `perene` + mapa `PERENE` → una sola, la del
    mapa.
  - `an entry's organizacion replaces its key`: mapa `ica` con `organizacion=<uuid>` → clave `<uuid>`.
  - `binds from environment variables`: `Binder` sobre `SystemEnvironmentPropertySource("systemEnvironment", ...)` con
    `SRTM_CATASTRO_ENABLED`, `SRTM_CATASTRO_ORGANIZACIONES_PERENE_URL/_CLIENT_ID/_CLIENT_SECRET/_TIMEOUT` y
    `SRTM_CATASTRO_ORGANIZACIONES_ICA_ORGANIZACION/_URL/...` → las dos cuentas, con su timeout.
  - `each organization its catastro, by id or by slug`: `Catastros(mapOf("perene" to a, id.toString() to b))`:
    `de(otroId, "PERENE") === a`, `de(id, null) === b`, `de(otroId, "ica") == null`; slug y id de la misma → 409
    `DOS_CATASTROS`.
  - `an incomplete account is skipped`: `CatastroConfig().catastros(...)` con una cuenta sin secreto → `de` da null.
  - `toString` de `CatastroProperties` y `CuentaCatastro` sin el secreto.
- [ ] Correr `./gradlew test --tests 'srtm.brecha.*'`: no compila.
- [ ] Implementar en `ClienteCatastro.kt`. `ClienteCatastroTest` construye el cliente con `CuentaCatastro`; su test
  de `otraOrganizacion` pasa a `CatastrosTest`.
- [ ] Correr los tests: pasan.

### Tarea 2: la corrida usa el catastro de su organización

**Archivos:**
- Modificar: `src/main/kotlin/srtm/brecha/BrechaService.kt`
- Test: `src/test/kotlin/srtm/brecha/BrechaApiTest.kt`

**Interfaces:**
- Consume: `Catastros.de`, `Catastros.necesitaSlug`, `Catastros.configurado`, `SIN_CATASTRO_CONFIGURADO`.

- [ ] Tests que fallan (`BrechaApiTest`, contexto con la propiedad antigua para `SLUG` → stub A y
  `srtm.catastro.organizaciones.<SLUG_ICA>` → stub B, cada stub con su client-id y su token):
  - `two organizations read each their own catastro`: corrida de cada una; las filas de cada una son solo los ítems de
    su stub; cada stub recibió solo su client-id y su token.
  - `an organization without a catastro is refused`: 409 con `detail` == `SIN_CATASTRO_CONFIGURADO`, sin corrida.
  - los tests existentes (propiedad antigua) siguen pasando: la configuración antigua sigue funcionando.
- [ ] Correr `./gradlew integrationTest --tests 'srtm.brecha.BrechaApiTest'`: falla.
- [ ] `BrechaService`: inyecta `Catastros` en lugar de `Catastro` y `CatastroProperties`; `lanzar` resuelve el
  catastro antes de la transacción y `correr(id, anio, catastro)` lo usa.
- [ ] Correr los tests: pasan.

### Tarea 3: documentación y entrega

- [ ] `application.yml`: comentario con el mapa y su forma por variables de entorno.
- [ ] `develop/example.env`: ejemplo con dos organizaciones (Perené por slug, Ica por id).
- [ ] README: tabla de variables y sección «Brecha de recaudación» (un catastro por organización, 409
  `SIN_CATASTRO_CONFIGURADO`); `docs/develop/README.md` si nombra las variables.
- [ ] `./gradlew ktlintCheck test integrationTest` verde; commit, push, PR «Closes #123», CI verde.
