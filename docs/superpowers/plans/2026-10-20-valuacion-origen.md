# Valuación del padrón de origen en solo lectura: plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Objetivo:** el portal puede mostrar, sin poder escribirlos, los campos del padrón externo (Ica) que #128 guarda y los
DTO no conocen.

**Arquitectura:** `GET /api/srtm/declaraciones/{id}/valuacion-origen` lee los registros guardados de la DJ, su predio y
sus niveles como `Map` (igual que `copiarPadronExterno`) y devuelve, de las listas `PADRON_EXTERNO_*`, solo los campos
con valor. Los DTO de escritura no cambian. `PredioFicha` gana el mismo bloque `predio` (`valuacion_origen.predio`).

**Stack:** Kotlin/Spring sobre wasichai; pruebas unitarias (JUnit) y de integración (`SrtmApiTest`) contra un
PG18+PostGIS local.

**Spec:** wasichai/srtm-backend#132 (épica #126), sobre la rama de #128.

## Restricciones globales

- Rama `feat/valuacion-origen` sobre `feat/campos-padron-externo` (PR #128); el PR va contra esa rama.
- Respuesta: `{"predio": {...}, "declaracion": {...}, "niveles": {"<id>": {...}}}`; un bloque sin valores es `{}` y un
  nivel sin valores no aparece.
- `PADRON_EXTERNO_PREDIO = codigo_via, codigo_unidad_urbana, codigo_hu_armonizacion, codigo_via_armonizacion`.
- Mismos controles que `GET /declaraciones/{id}`: los de `RecordService` (permiso de objeto y de campo); 404 si no
  existe.
- Nada contra la base ni los Core de dev.

## Review Focus

- Un campo de texto en blanco (`""`) no es un valor: no se devuelve.
- Un rol que no puede leer la DJ recibe lo mismo que en `GET /declaraciones/{id}` (403), no una respuesta vacía.
- Un nivel sin valores de origen no aparece en `niveles`.
- Una DJ sin nada del padrón responde 200 con los tres bloques vacíos (la UI no dibuja nada).
- Los decimales salen como número JSON, comparables por valor.

---

### Task 1: `conValor` y `PADRON_EXTERNO_PREDIO`

**Files:** Modify `src/main/kotlin/srtm/rentas/Condominio.kt`, `DeclaracionService.kt` (copiarPadronExterno la usa);
Test `src/test/kotlin/srtm/rentas/ValuacionOrigenTest.kt`.

**Interfaces:** Produces `fun conValor(registro: Map<*, *>, campos: List<String>): Map<String, Any>` (orden de
`campos`; fuera null y texto en blanco) y `val PADRON_EXTERNO_PREDIO: List<String>`.

- [ ] Test: `only the fields with a value come, in the list's order` (null, `""`, `"  "` fuera; BigDecimal y texto quedan;
  campos fuera de la lista no).
- [ ] Verlo fallar (no compila), implementar, verlo pasar, commit.

### Task 2: el endpoint y `PredioFicha`

**Files:** Modify `Dtos.kt` (`ValuacionOrigen`, `PredioFicha.valuacionOrigen`), `DeclaracionService.kt`
(`suspend fun valuacionOrigen(id: UUID): ValuacionOrigen`), `RentasService.kt` (`fichaPredio`), `RentasController.kt`;
Test `PadronExternoApiTest.kt`; README (tabla de la API del portal y sección del modelo).

- [ ] Tests de integración: DJ importada con dos niveles (uno con valores) → los tres bloques con sus valores y solo el
  nivel importado; DJ sin padrón → bloques vacíos; id desconocido → 404; rol sin READ de la DJ → el mismo estado que
  `GET /declaraciones/{id}`; `GET /predios/{id}` trae `valuacion_origen.predio`.
- [ ] Verlos fallar, implementar, verlos pasar con la suite de `srtm.rentas`, commit.
