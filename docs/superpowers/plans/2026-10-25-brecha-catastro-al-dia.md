# Brecha con el catastro al día (carril B de la épica #202) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** srtm values the DJ with the padrón's obras and the predio's tipo, splits the codes Ica shares among distinct
predios, weighs each brecha row by how sure its cruce is, and follows catastro's changes to run the brecha by itself
and warn of predios that changed by more than 5 UIT.

**Architecture:** Five stacked branches, one per issue, in the order #208 → #209 → #207 → #204 → #203. The pure rules
live in `Derivados`, `AutoavaluoSugerido`, `Brecha`, a new `Confiabilidad` and a new `Seguimiento`. The services only
read and write. Catastro is reached only through `Catastro`/`ClienteCatastro`, coded against contratos 1–4. The tests
use the jdk `HttpServer` stubs that are already in `ClienteCatastroTest` and `BrechaApiTest`, plus `CatastrosTest.Falso`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 (WebFlux, coroutines), wasichai 1.3.0 (`RecordService.asPlatform`,
`wasichai.core.platform.ClusterLock`, `TenantDirectory`), the Python 3 stdlib (model scripts, `fake_core`) and AsciiDoc.

**Spec:** `/private/tmp/claude-502/-Users-jorge-IdeaProjects-srtm-backend/882b0f7f-a674-480b-a7cb-90fd30468dca/scratchpad/e202/`
`contratos.md` (binding), then `s10.md` (#208), `s11.md` (#209), `s9.md` (#207), `s7.md` (#204) and `s6.md` (#203).
Executors read the spec of their issue and `contratos.md`.

## Global Constraints

- Worktree `/Users/jorge/IdeaProjects/srtm-backend-e202`. Never read or edit `/Users/jorge/IdeaProjects/catastro-backend-e202`
  nor any other worktree. Do not touch the dev DB nor the VPS app stacks.
- One branch per issue, stacked. Each group starts with `git checkout -b <branch>` from the previous group's head.
  Implementers only commit; the controller pushes and opens the PR (`Closes #N`), which targets the previous branch
  (#208's targets `main`).
- Commits: conventional, in Spanish, like the history (`feat(brecha): …`, `fix(rentas): …`), body lines ≤ 95 chars
  (commitlint caps them at 100). Every commit message ends with:
  `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01VY1WTWiqDGJRzjitHKYVPv`.
- Code comments: English, lower case, the density of the surrounding code. User-facing text and docs: Spanish.
- TDD. Unit tests: `./gradlew test --tests '<class>'`. Integration tests (`SrtmApiTest` subclasses, tag `integration`)
  only through `$REMOTO integrationTest --tests '<pattern>'`, where
  `REMOTO="/private/tmp/claude-502/-Users-jorge-IdeaProjects-srtm-backend/882b0f7f-a674-480b-a7cb-90fd30468dca/scratchpad/e202/pruebas-remotas.sh /Users/jorge/IdeaProjects/srtm-backend-e202 testdb-srtm"`.
  No Testcontainers, no local Docker. Python: `cd model && python3 -m unittest -v test_<x>`.
- The model changes only through `model/model.json`, checked with `cd model && python3 apply.py --validate-only`.
  Never SQL. New fields are optional.
- Tests use synthetic fixtures only. Never read real municipal data (padrón, libro de comparación, personal data).
- Catastro's JSON as contratos.md fixes it: the new keys of `/valuaciones` are always present (`null` when there is no
  data); `/titulares?page&size` returns `{page,size,total,items:[{codigoMunicipal,tipoDocumento,numeroDocumento,nombre,porcentaje,condicion}]}`;
  `/cambios?desde&size` returns `{items:[{codigoMunicipal,objeto,operacion,campos,en,entrega}],cursor,hayMas}` with
  `size` from 1 to 500; `POST /valuacion` takes `"tipoPredio": "URBANO"|"RUSTICO"`; and a rústico answers
  `motivo = "PREDIO_RUSTICO"`.
- At the end of every group: `./gradlew ktlintCheck test`, `cd model && python3 apply.py --validate-only && python3 -m unittest`,
  `cd docs/manuales && python3 herramientas/validar.py`, and that group's integration tests through `$REMOTO`.

## Rulings

- Ruling: #208 "obras activas con partida" means any active obra (`estado != INACTIVO`). Why: an obra whose categoría
  has no partida number already makes catastro answer `OBRA_DESCONOCIDA`, so the DJ is por revisar either way, and the
  padrón's total is never mixed with obras the DJ declares. Cost if wrong: a DJ with only obras that have no partida
  number loses the fixed amount, but it is por revisar in both readings.
- Ruling: #208 srtm adds the amount after catastro answers (`Derivados.conObrasDelPadron`, called in
  `AutoavaluoSugeridoService.valuar`). The body of `POST /valuacion` does not change. Why: contrato 4 is the only change
  to that body, and lane A is not building an "otras instalaciones" input. Cost if wrong: if catastro later values the
  amount itself, it is counted twice until this function is removed.
- Ruling: #208 the breakdown shows the amount as one more row of `detalle.obras`, with `codigo_vu = null` and the new
  field `descripcion = "Obras del padrón de origen"`. `ValuacionDeInsumos` also gains `obras_padron_origen`. Why: the
  detail must name the row, and `codigo_vu` is a key of catastro's table. Cost if wrong: srtm-ui shows an unlabeled
  row until it reads `descripcion` (a ui follow-up for the controller).
- Ruling: #208 "sus partidas valorizadas" for the brecha come from a new derived field,
  `declaracion_predial.valor_obras`: the engine's `obras` (fixed amount included), written by `recalcular`. When it is
  null (not recalculated since), the brecha uses `valor_otras_instalaciones`, because the importer never loads obras.
  The amount counts once per secuencia (the highest of its titulares, as `totalesDePredio` counts the autoavalúo). A
  "ficha sin obras" is a valued item whose `obras` is null or 0. The new `brecha_predio.obras_dj` records what was
  added. Why: valuing every DJ's partidas during a corrida would cost one catastro call per DJ. Cost if wrong: a DJ
  edited before this deploy and never recalculated adds nothing until its next write.
- Ruling: #208 the dev dry-run is `model/recalcular_obras_padron.py --dry-run`. It reads
  `GET /declaraciones/{id}/autoavaluo-sugerido` and writes nothing. Without `--dry-run` it posts `/recalcular` for each
  DJ that changes. Why: the acceptance asks for a count and an amount before anything is written. Cost if wrong: one
  more script to maintain.
- Ruling: #209 `tipoPredio` comes from `predio.tipo_predio` only (`PREDIO RUSTICO`→`RUSTICO`, `PREDIO URBANO`→`URBANO`,
  anything else → null). Why: `declaracion_predial` has no `tipo_predio` field in model.json. Cost if wrong: a DJ that
  could say rústico while its predio does not is valued as urban.
- Ruling: #209 srtm does not translate the rústico motivo. `Derivados.autoavaluo` already keeps the declared value and
  marks the DJ with catastro's `motivo` (else its `llave`), so `PREDIO_RUSTICO` reaches `autoavaluo_motivo` as it comes.
  Cost if wrong: if a friendlier text is wanted, it is a one-line map.
- Ruling: #209 in the brecha, a crossed srtm predio whose `tipo_predio` is `PREDIO RUSTICO` is valued as null with
  motivo `PREDIO_RUSTICO`, even when catastro valued it (a ficha without `tipo_predio`). This applies to an OMISO that
  crossed a rústico predio too (no estimate). Why: the engine has no rústico tables yet, so any value it gives is the
  urban one. Cost if wrong: a rústico with a real valuation shows up as SIN_VALOR.
- Ruling: #207 a new predio is coded `<codigo>-<n>`. n runs from 1 to k over every distinct predio of a shared code,
  and none of them keeps the bare code. The groups are ordered by their lowest contribuyente code, compared as
  `(len, code)` (the importer's numeric-aware rule). Every predio the importer creates stores the SAT code in the new
  `predio.codigo_origen`. Why: in model.json `predio.codigo` is TEXT, required and unique (per organization). The
  brecha compares codes through `Brecha.llave`, which keeps the `-n` part, so a derived code cannot collide with Ica's
  three-part codes. Arbitrios and emisión use `predio.codigo` only as a label, a sort key and the PU file name
  (`PU-<codigo>-<anio>.pdf`, ErrorPredio, HR/PU), never as a key. A suffix therefore breaks nothing and stays readable
  on documents, where a wasichai uuid would not. The cruce keeps working through `codigo_origen`. Cost if wrong:
  renaming about 2 900 unique codes through the API and reprinting documents; the brecha is unaffected.
- Ruling: #207 two rows of a code are the same predio when their `direccion` (`direccion_del_predio_completa`, else
  the vía, número, manzana and lote, upper case with single spaces), `area_terreno` and `valor_predio` (both to the
  cent) are all equal, compared exactly. Why: the grouping must be transitive, and a SAT condominio repeats its
  figures verbatim. The report `condominio_valores_distintos` disappears: those rows are now distinct predios. Cost if
  wrong: a real condominio with a one-cent difference becomes two predios at 100 %, listed in `codigo_compartido`.
- Ruling: #207 `codigo_origen` is read-only in the portal. It is in the `Predio` dto, but `registrarPredio` and
  `actualizarPredio` never write it (the #195 pattern), and it is added to `PADRON_EXTERNO_PREDIO`. Why: it is a code
  of the source padrón. Cost if wrong: none for the brecha; the field just cannot be edited.
- Ruling: #207 when a load meets a shared code whose bare predio Core already has (dev's Ica), it skips that code and
  everything under it, and reports `codigo_compartido_en_core`. Migrating the ~1 464 codes already in dev (moving DJs,
  niveles and cuotas) is a separate issue for the controller to open. Why: a reload would otherwise leave the old 100/n
  predio next to the new ones. Cost if wrong: dev keeps 100/n on those codes until the migration.
- Ruling: #207 the cruce gains a second pass by `codigo_origen`, between código municipal and CPU. Among several
  predios it uses the same preference (a DJ of the year, then the lowest código). "Por confirmar" of #126 goes to
  `docs/develop/alta-organizacion.md` (Pendiente). Editing the GitHub épica is the controller's job.
- Ruling: #204 `SIN_LEVANTAR` is also a seventh `categoria_brecha`, crossed or not, with no difference, so the row is
  neither OMISO nor COINCIDE. `Kpis` gains `sinLevantar`. `prediosPadron` counts a SIN_LEVANTAR row only when it has a
  declared autoavalúo. Why: OMISO is a categoría, and counting ~9 800 empty fichas as omisos is the error #204 fixes.
  Cost if wrong: lane C's corte (#205) and srtm-ui meet a new enum value. **The controller must tell lane C.**
- Ruling: #204 every row gets a confianza, checked in this order:
  1. `SIN_LEVANTAR` (ficha `estado_levantamiento`);
  2. CONFIABLE by vínculo (`CODIGO_RENTAS`, or `MANUAL` and `vinculoRevisado == true`), only when the row crossed a predio;
  3. CONFIABLE by a titular's documento;
  4. CONFIABLE by a titular's nombre;
  5. otherwise DUDOSO.

  SIN_CATASTRO rows are DUDOSO "Sin ficha del catastro", and an uncrossed OMISO is DUDOSO "Sin predio en el padrón".
  Why: the spec says "DUDOSO: todo lo demás", and the corte reads one value per row. Cost if wrong: the DUDOSO total
  includes rows that have no cruce to doubt.
- Ruling: #204 documento = alphanumerics only, upper case, without leading zeros; empty (e.g. `000000000`) is none.
  nombre = upper case, without accents (NFD minus marks, so Ñ→N), split on anything not A–Z, words sorted and joined by
  one space. Cost if wrong: a few matches more or fewer; both are pure functions with their own tests.
- Ruling: #204 `GET /api/catastro/titulares` answering 404 or 403 (an older catastro, or one without READ on
  `titular_catastral`) is no failure. The corrida compares no titulares and says so in `mensaje`. Any other error
  fails the corrida, as `/valuaciones` does. Why: acceptance asks that a catastro without the new keys measures as
  today.
- Ruling: #204 the corrida stores six flat fields: `predios_confiables`, `predios_dudosos`, `predios_sin_levantar`,
  `delta_impuesto_confiable`, `delta_impuesto_dudoso` and `delta_impuesto_sin_levantar`. The panel's `porConfianza`
  always has the three keys, in enum order, over the rows that have a confianza (an older corrida shows zeros). Why:
  flat columns are what the corte and Metabase read. Cost if wrong: re-deriving the fields from the rows.
- Ruling: #203 `sincronizacion_catastro` = `cursor`, `ultimo_cambio_en`, `ultima_corrida` (a relation to
  `brecha_corrida`), `pendientes` (INTEGER: cambios read since the last automatic corrida) and `error`. There is one row
  per organization and no unique key: the `ClusterLock` leaves a single writer. `cambio_catastro.objeto` is TEXT (an
  object catastro adds later never stalls the feed). `operacion` is the ENUM `operacion_catastro`. The object is
  `appendOnly`, indexed on `en` and on `codigo_municipal`.
- Ruling: #203 the schedule is `SRTM_CATASTRO_SEGUIMIENTO_MINUTOS` (default 0 = off) and `SRTM_CATASTRO_SEGUIMIENTO_HORA`
  (default 2, America/Lima). At most one automatic corrida a day, at or after that hour, for the current Lima year:
  - CAMBIOS when `pendientes > 0`, or when the last automatic corrida is FALLIDA;
  - SEMANAL when there was none, or the last one was 7 or more calendar days ago.

  `pendientes` goes back to 0 when the corrida is launched. Why: the hour must still be hit when the loop runs less
  often than once an hour. Cost if wrong: one corrida more or less a day.
- Ruling: #203 an automatic corrida runs as the platform: `BrechaService.lanzarAutomatica`, inside
  `RecordService.asPlatform`, and no user's permissions apply. `brecha_corrida.origen` becomes the ENUM
  `MANUAL|CAMBIOS|SEMANAL` (null is an older MANUAL). Each page of cambios is written in one transaction together with
  its cursor, so a crash never repeats or skips a page. When the read fails, `error` is written and that pass makes no
  decision.
- Ruling: #203 the aviso (`GET /{anio}/cambios`) works as follows:
  - permission: read on `brecha_corrida` ("los de la brecha"); the cambios are then read as the platform, so no role
    needs a new permission;
  - rows: one per código municipal changed since `desde` (default 1 January of `anio`), joined by `Brecha.llave` to
    `codigo_catastro` in the last TERMINADA corrida of the year (or `?corrida`);
  - Δ: that corrida's `delta_autoavaluo`;
  - marca: `SUPERA_5_UIT` when |Δ| is strictly greater than 5 × the UIT in force on 1 January of `anio`;
  - order: marked rows first, then |Δ| from highest (nulls last), then código;
  - errors: 422 `faltan` when that UIT is missing.

## Review Focus

- A load into a Core that already holds a shared bare code (dev's Ica): it must create neither a duplicate predio nor
  a DJ for that code. Pinned in Task 8 (`test_a_shared_code_core_already_has_is_skipped_and_reported`).
- Catastro down, or `/cambios` answering 404 or 500, while the loop runs: `error` is set, the cursor stays, no corrida
  starts, the loop keeps going, and the next good read clears `error`. Pinned in Task 18.
- A corrida against a catastro of today (no new keys, no `/titulares`): TERMINADA, the same categories and totals as
  before, every row DUDOSO, and `mensaje` says that titulares were not compared. Pinned in Tasks 12 and 13
  (`BrechaApiTest`: the existing stub has no `/titulares`).
- The padrón's obras on a condominio: every condómino's DJ carries the whole amount (it belongs to the predio), and the
  brecha adds it once per secuencia, not once per titular. Pinned in Task 3
  (`condominos add the DJ's obras once per secuencia`).
- Two instances: only the one that holds the `ClusterLock` key reads an organization's feed. Pinned in Task 18
  (`a lock held elsewhere skips the organization`).

---

## Group #208: obras del padrón externo en el motor y en la brecha

Branch: `fix/obras-padron-externo` (already checked out, from `main`)

### Task 1: valor_obras and obras_dj in the model

**Files:**
- Modify: `model/model.json` (`declaracion_predial`, `brecha_predio`)
- Test: `model/test_model.py`

- [ ] **Step 1: Write the failing test.** In `test_model.py` add `test_the_dj_keeps_its_obras_and_the_brecha_what_it_added`:

```python
dj = self.campos("declaracion_predial")
self.assertEqual(dj["valor_obras"]["type"], "DECIMAL")
self.assertFalse(dj["valor_obras"].get("required"))
self.assertEqual(self.campos("brecha_predio")["obras_dj"]["type"], "DECIMAL")
```
Also append `"obras_dj"` after `"orden"` in the field list of `test_the_brecha_is_a_corrida_and_its_predios`.

- [ ] **Step 2:** `cd model && python3 -m unittest -v test_model`. Expect FAIL (KeyError `valor_obras`).
- [ ] **Step 3: Add the fields.** `declaracion_predial.valor_obras` goes right after `valor_autoavaluo`: DECIMAL,
  "Valor de las obras complementarias", description "Derivado: las obras del autoavalúo que valuó el catastro o,
  sin obras declaradas, las del padrón de origen (#208).". `brecha_predio.obras_dj` goes last: DECIMAL, "Obras de la
  DJ (S/)", description "Lo que la corrida sumó al lado catastro porque la ficha no trae obras (#208).".
- [ ] **Step 4:** `python3 apply.py --validate-only && python3 -m unittest -v test_model`. Expect PASS.
- [ ] **Step 5: Commit.** `feat(modelo): valor de las obras en la DJ y obras de la DJ en la brecha (#208)`

### Task 2: the engine's autoavalúo adds the padrón's otras instalaciones

**Files:**
- Modify: `src/main/kotlin/srtm/rentas/Derivados.kt`, `src/main/kotlin/srtm/brecha/ValuacionDeInsumos.kt`,
  `src/main/kotlin/srtm/rentas/Dtos.kt` (`Declaracion`), `src/main/kotlin/srtm/rentas/AutoavaluoSugeridoService.kt`,
  `src/main/kotlin/srtm/rentas/DeclaracionService.kt` (`delCliente`, `actualizar`), `README.md` («Autoavalúo
  sugerido», «Valores derivados de la DJ»), `docs/manuales/usuario/registrador.adoc` (`[[autoavaluo-sugerido]]`)
- Test: `src/test/kotlin/srtm/rentas/DerivadosTest.kt`, `src/test/kotlin/srtm/rentas/AutoavaluoSugeridoApiTest.kt`

**Interfaces:**
- Produces:
  - `Autoavaluo.Valuado(valor: BigDecimal, obras: BigDecimal? = null)`.
  - `Declaracion.valorObras: BigDecimal? = null`, which `Derivados.campos` writes as `valor_obras`.
  - `ValuacionDeInsumos.obrasPadronOrigen: BigDecimal? = null` (json `obras_padron_origen`).
  - `DetalleObraValuacion.descripcion: String? = null`.
  - `const val OBRAS_DEL_PADRON = "Obras del padrón de origen"` (in `Derivados.kt`).
  - `Derivados.conObrasDelPadron(v: ValuacionDeInsumos, obras: List<ObraComplementaria>, valorOtrasInstalaciones: BigDecimal?): ValuacionDeInsumos`.

- [ ] **Step 1: Write the failing unit tests** in `DerivadosTest` (v = a valued answer with `autoavaluo = 90000.00`,
  `obras = 0.00`, `detalle.obras = []`):

```kotlin
@Test fun `the padron's otras instalaciones are added while the DJ declares no obra`() {
    val r = Derivados.conObrasDelPadron(v, emptyList(), BigDecimal("10000.00"))
    assertEquals(BigDecimal("100000.00"), r.autoavaluo); assertEquals(BigDecimal("10000.00"), r.obras)
    assertEquals(BigDecimal("10000.00"), r.obrasPadronOrigen)
    val fila = r.detalle!!.obras.last()
    assertEquals(OBRAS_DEL_PADRON, fila.descripcion); assertNull(fila.codigoVu); assertEquals(BigDecimal("10000.00"), fila.valor)
}
@Test fun `an active obra stops it, an inactive one does not`()            // obra(estado = null) -> r == v; obra(estado = "INACTIVO") -> 100000.00
@Test fun `not valued, no amount or zero leaves catastro's answer`()       // autoavaluo null / null / 0 -> r == v
@Test fun `a valued declaracion keeps its obras, one not valued its stored ones`() {
    // aplicar(d(valorObras = 5.00), t, Valuado(100000.00, 10000.00)).valorObras == 10000.00
    // aplicar(d(valorObras = 5.00), t, PorRevisar("X")).valorObras == 5.00; Manual keeps 5.00
    // "valor_obras" in Derivados.campos(...).keys
}
```

- [ ] **Step 2:** `./gradlew test --tests 'srtm.rentas.DerivadosTest'`. Expect FAIL (unresolved `conObrasDelPadron`).
- [ ] **Step 3: Implement.**
  - `conObrasDelPadron`: returns `v` unchanged when `v.autoavaluo == null`, when the amount is null or ≤ 0, or when
    any obra has `estado != "INACTIVO"`. Otherwise it adds the amount to `autoavaluo` and `obras`, sets
    `obrasPadronOrigen`, and appends one `DetalleObraValuacion(codigoVu = null, …, valor = amount, descripcion = OBRAS_DEL_PADRON)`.
  - `Derivados.autoavaluo(v)` returns `Valuado(v.autoavaluo, v.obras)`.
  - `aplicar` sets `valorObras` only for `Valuado`.
  - `AutoavaluoSugeridoService.valuar` returns `Derivados.conObrasDelPadron(catastro.valuar(insumos), obras, registro["valor_otras_instalaciones"]?.toString()?.trim()?.toBigDecimalOrNull())`.
  - `delCliente` sets `valorObras = null`, and `actualizar` keeps `stored.valorObras`.
  - `ClienteCatastro.detalleObra` leaves `descripcion` null.
- [ ] **Step 4:** `./gradlew test --tests 'srtm.rentas.DerivadosTest' --tests 'srtm.rentas.AutoavaluoSugeridoTest' --tests 'srtm.brecha.ClienteCatastroTest'`. Expect PASS.
- [ ] **Step 5: Integration test** in `AutoavaluoSugeridoApiTest`:
  `the padron's otras instalaciones add to catastro's valuation until the DJ declares an obra`.
  1. Put `valor_otras_instalaciones = 1200` through the generic record PUT (as the existing test puts `arancel`).
  2. `GET …/autoavaluo-sugerido` has `autoavaluo` = the stub's + 1200, `obras_padron_origen` = 1200, and the last
     `detalle.obras[].descripcion` = "Obras del padrón de origen".
  3. After `POST /api/srtm/declaraciones/{id}/obras` with one obra, `autoavaluo` is the stub's again and
     `obras_padron_origen` is null.

  Run: `$REMOTO integrationTest --tests '*AutoavaluoSugeridoApiTest*' --tests '*DerivadosApiTest*'`. Expect PASS.
- [ ] **Step 6: Docs.**
  - README «Autoavalúo sugerido»: add an «Obras del padrón de origen» bullet with the condition and the stop.
  - README «Valores derivados de la DJ»: `valor_obras`.
  - Both README sections and `registrador.adoc` state the limit verbatim: «el monto del padrón es fijo: no se vuelve a
    depreciar en los años siguientes». `registrador.adoc` also says that the dialog shows the row
    *Obras del padrón de origen*.
- [ ] **Step 7: Commit.** `fix(rentas): el autoavalúo suma las obras del padrón de origen mientras la DJ no declare obras (#208)`

### Task 3: the brecha's catastro side adds the DJ's obras when the ficha has none

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/Brecha.kt` (`Padron`, `medir`), `src/main/kotlin/srtm/brecha/Modelo.kt`
  (`FilaBrecha.obrasDj`, `PredioBrecha.obrasDj`), `src/main/kotlin/srtm/brecha/BrechaService.kt` (`padron`),
  `README.md` («Brecha de recaudación»: a paragraph «Obras de la DJ»)
- Test: `src/test/kotlin/srtm/brecha/BrechaTest.kt`

**Interfaces:**
- Consumes: `Declaracion.valorObras` (Task 2).
- Produces:
  - `Padron.obrasDelPadron: Map<String, BigDecimal> = emptyMap()` (declaración id → `valor_otras_instalaciones`);
  - `Brecha.obrasDeLaDj(declaraciones: List<Declaracion>, obrasDelPadron: Map<String, BigDecimal>): BigDecimal?`
    (Σ over secuencias of the highest of `valorObras ?: obrasDelPadron[id]`; null when there is nothing);
  - `Brecha.conObrasDeLaDj(item: ValuacionCatastral, obras: BigDecimal?): ValuacionCatastral`
    (adds to `autoavaluo` and `obras` only when `autoavaluo != null`, `obras` is null or 0, and `obras > 0`);
  - `FilaBrecha.obrasDj: BigDecimal? = null`.

- [ ] **Step 1: Write the failing tests** in `BrechaTest` (predio `p1`, a DJ of `100000.00` that crosses an item of
  `90000.00` with `obras = 0.00`):

```kotlin
@Test fun `a ficha without obras takes the DJ's obras del padron on the catastro side`() {
    // obrasDelPadron = mapOf("d1" to 10000.00), valorObras null
    // fila.autoavaluoCatastro == 100000.00, fila.obrasDj == 10000.00, categoria COINCIDE, deltaAutoavaluo == 0.00
}
@Test fun `with its obras valued, the DJ's valor_obras is what is added`()  // valorObras 8000.00 and padron 10000.00 -> obrasDj 8000.00, catastro 98000.00
@Test fun `a ficha with obras adds nothing`()                               // item.obras 5000.00 -> autoavaluoCatastro 90000.00, obrasDj null
@Test fun `condominos add the DJ's obras once per secuencia`()             // two DJs of one secuencia, each 10000.00 -> obrasDj 10000.00
@Test fun `the corrida counts the predios whose obras came from the DJ`()  // avisos contains "1 predios sin obras en la ficha: se sumaron S/ 10000.00 de obras de sus DJ"
```

- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.BrechaTest'`. Expect FAIL.
- [ ] **Step 3: Implement.** In `medir`, map every crossed pair whose predio has vigentes to
  `conObrasDeLaDj(item, obrasDeLaDj(suyas, padron.obrasDelPadron))` before `valuados` is built, so the impuesto sees
  the same value. The row gets `obrasDj`. `BrechaService.padron` reads `DECLARACION` once as `Map`, turns each record
  into a `Declaracion` with `Records.read`, and fills `obrasDelPadron` from `valor_otras_instalaciones > 0`.
- [ ] **Step 4:** `./gradlew test --tests 'srtm.brecha.BrechaTest'`, then `$REMOTO integrationTest --tests '*BrechaApiTest*'`. Expect PASS.
- [ ] **Step 5: Commit.** `fix(brecha): sin obras en la ficha, el lado catastro suma las obras de la DJ (#208)`

### Task 4: dry-run of the recálculo for dev

**Files:**
- Create: `model/recalcular_obras_padron.py`, `model/test_recalcular_obras_padron.py`
- Modify: `model/fake_core.py` (the `sugeridos` and `recalculados` routes), `README.md` («Cargar la Municipalidad de
  Ica»: a subsection «Recalcular con las obras del padrón»)

**Interfaces:**
- Produces:
  - `main(argv) -> int`, with the flags `--core`, `--email`, `--password`, `--anio` (2026), `--dry-run`, `--workers`
    (4) and `--report` (`reports/obras_padron.csv`);
  - the pure `clasificar(guardado, sugerido) -> str`, which returns `"cambia"` (|Δ| ≥ 0.01), `"igual"` or `"sin_valor"`;
  - the pure `resumen(filas) -> dict` with the keys `con_obras`, `valuadas`, `cambian`, `suma_delta`, `a_un_sol` and
    `sin_valor` (a Counter by motivo).
- `FakeCore(sugeridos={id: response})`:
  - `GET /api/srtm/declaraciones/{id}/autoavaluo-sugerido` answers the scripted dict, or 404;
  - `POST /api/srtm/declaraciones/{id}/recalcular` answers 200 and is recorded in `requests`.

- [ ] **Step 1: Write the failing tests:**
  - `test_a_dj_without_otras_instalaciones_is_not_asked`
  - `test_the_summary_counts_changes_their_sum_and_those_within_a_sol`. Stored and suggested pairs: 100/101.50 →
    cambia, 50/50 → igual, 70/null → sin_valor. Expect `cambian == 1`, `suma_delta == Decimal("1.50")`,
    `a_un_sol == 1`.
  - `test_dry_run_posts_nothing`
  - `test_without_dry_run_only_the_changed_are_recalculated`
  - `test_the_report_has_ids_and_amounts_only`. The columns are `declaracion, valor_autoavaluo, sugerido, delta,
    motivo`.
- [ ] **Step 2:** `cd model && python3 -m unittest -v test_recalcular_obras_padron`. Expect FAIL (module missing).
- [ ] **Step 3: Implement.**
  - Read `client.list_all("declaracion_predial", anio=…)`. Keep the DJ that are vigente (blank `estado` or VIGENTE)
    and have `valor_otras_instalaciones > 0`.
  - GET the sugerido of each, in parallel with `--workers` threads.
  - Print the summary and write the report. No names or documents, ever.
  - Exit codes: 0, or 1 when Core refused something.
- [ ] **Step 4:** `python3 -m unittest -v test_recalcular_obras_padron && python3 -m unittest`. Expect PASS.
- [ ] **Step 5: Commit.** `feat(modelo): recalcular_obras_padron.py dice cuántas DJ cambian con las obras del padrón (#208)`

**Controller, not an implementer step:** once the branch is in dev and the user gives the OK, run
`python3 -I model/recalcular_obras_padron.py --core <dev> --anio 2026 --dry-run` and paste the summary into the PR.

---

## Group #209: predios rústicos

Branch: `fix/rusticos-dj` (`git checkout -b fix/rusticos-dj` from `fix/obras-padron-externo`)

### Task 5: the insumos say the tipo de predio, and a rústico DJ stays por revisar

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/ValuacionDeInsumos.kt` (`InsumosValuacion.tipoPredio: String? = null`),
  `src/main/kotlin/srtm/rentas/AutoavaluoSugerido.kt`, `docs/manuales/usuario/registrador.adoc` (the «Autoavalúo por
  revisar» bullet and `[[autoavaluo-sugerido]]`), `README.md` («Autoavalúo sugerido»: «Tipo de predio»)
- Test: `src/test/kotlin/srtm/rentas/AutoavaluoSugeridoTest.kt`, `src/test/kotlin/srtm/rentas/DerivadosTest.kt`,
  `src/test/kotlin/srtm/brecha/ClienteCatastroTest.kt`

**Interfaces:**
- Produces: `AutoavaluoSugerido.tipoPredio(tipo: String?): String?` and `const val PREDIO_RUSTICO = "PREDIO_RUSTICO"`
  (in `srtm.brecha.Modelo.kt`).

- [ ] **Step 1: Write the failing tests.**

```kotlin
// AutoavaluoSugeridoTest
@Test fun `a rustico predio sends RUSTICO, an urbano URBANO, none or another null`() {
    assertEquals("RUSTICO", insumos(predio = predio.copy(tipoPredio = "PREDIO RUSTICO")).tipoPredio)
    assertEquals("URBANO", insumos(predio = predio.copy(tipoPredio = "PREDIO URBANO")).tipoPredio)
    assertNull(insumos(predio = predio.copy(tipoPredio = null)).tipoPredio); assertNull(insumos(predio = null).tipoPredio)
}
// DerivadosTest
@Test fun `catastro's PREDIO_RUSTICO keeps the declared autoavaluo, marked to review`() {
    // v(autoavaluo = null, motivo = "PREDIO_RUSTICO") -> aplicar(d(valorAutoavaluo = 12800.00), t, Derivados.autoavaluo(v))
    // valorAutoavaluo == 12800.00, autoavaluoPorRevisar == true, autoavaluoMotivo == "PREDIO_RUSTICO"
}
// ClienteCatastroTest: the posted body of `insumos are posted as catastro's body…` contains "\"tipoPredio\":\"RUSTICO\"" for a rústico
```

- [ ] **Step 2:** `./gradlew test --tests 'srtm.rentas.AutoavaluoSugeridoTest' --tests 'srtm.rentas.DerivadosTest' --tests 'srtm.brecha.ClienteCatastroTest'`. Expect FAIL.
- [ ] **Step 3: Implement.** `insumos(...)` sets `tipoPredio = tipoPredio(predio?.tipoPredio)`. `Derivados` needs no
  change; the test pins the behavior.
- [ ] **Step 4:** Run the same command again. Expect PASS.
- [ ] **Step 5: Docs.** `registrador.adoc` says: «Una DJ de un predio rústico queda *por revisar* con el motivo
  PREDIO_RUSTICO: el catastro todavía no tiene los aranceles rústicos (por hectárea) y no la valúa; se conserva el
  autoavalúo declarado». The README says the same in «Autoavalúo sugerido».
- [ ] **Step 6: Commit.** `fix(rentas): los insumos dicen si el predio es rústico y la DJ queda por revisar (#209)`

### Task 6: a rústico predio is SIN_VALOR in the brecha

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/Brecha.kt`, `README.md` (the «Categorías» table: the SIN_VALOR row)
- Test: `src/test/kotlin/srtm/brecha/BrechaTest.kt`

**Interfaces:**
- Produces: `Brecha.sinValorSiRustico(item: ValuacionCatastral, predio: Predio?): ValuacionCatastral`. For
  `predio?.tipoPredio == "PREDIO RUSTICO"` it returns the item with `autoavaluo`, `terreno`, `construccion` and `obras`
  null and `motivo = item.motivo ?: PREDIO_RUSTICO`; for anything else it returns the item unchanged.

- [ ] **Step 1: Write the failing tests:**
  - `a rustico predio catastro did not value is SIN_VALOR`. The item has `autoavaluo = null` and
    `motivo = "PREDIO_RUSTICO"`; expect categoria SIN_VALOR, deltaImpuesto null.
  - `a rustico predio catastro valued as urban is SIN_VALOR with PREDIO_RUSTICO, not SUBVALUADO`. The item is
    `636000.00` against a declared `12800.00`; expect categoria SIN_VALOR, motivo PREDIO_RUSTICO, and
    `impuestos.porPredio` without its delta.
  - `an omiso crossed to a rustico predio has no estimate`. Expect `impuestoCatastro == null` and
    `estimado == false`.
  - `the corrida counts the rusticos without value`. avisos contains
    "1 predios rústicos sin valor: el catastro aún no valúa terrenos rústicos (PREDIO_RUSTICO)".
- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.BrechaTest'`. Expect FAIL.
- [ ] **Step 3: Implement.** Apply `sinValorSiRustico` to every pair right after `cruzar`, before
  `conObrasDeLaDj` (Task 3) and before `valuados`.
- [ ] **Step 4:** `./gradlew test --tests 'srtm.brecha.BrechaTest'`. Expect PASS.
- [ ] **Step 5: Commit.** `fix(brecha): un predio rústico sin valor de catastro sale SIN_VALOR (#209)`

---

## Group #207: un código compartido por predios distintos no es un condominio

Branch: `fix/codigos-compartidos-ica` (`git checkout -b fix/codigos-compartidos-ica` from `fix/rusticos-dj`)

### Task 7: predio.codigo_origen

**Files:**
- Modify: `model/model.json` (`predio`), `src/main/kotlin/srtm/rentas/Dtos.kt` (`Predio.codigoOrigen`),
  `src/main/kotlin/srtm/rentas/Condominio.kt` (`PADRON_EXTERNO_PREDIO` + `"codigo_origen"`),
  `src/main/kotlin/srtm/rentas/DeclaracionService.kt` (`registrarPredio` and `actualizarPredio` write
  `Records.attributes(x) - CODIGO_ORIGEN`)
- Test: `model/test_model.py`, `src/test/kotlin/srtm/rentas/PadronExternoApiTest.kt`

**Interfaces:**
- Produces: `Predio.codigoOrigen: String? = null` (read-only, #195 pattern) and `const val CODIGO_ORIGEN = "codigo_origen"`.

- [ ] **Step 1: Write the failing tests.**
  - `test_model`: `test_the_predio_keeps_its_code_of_origin`. `predio.codigo_origen` is TEXT, not required, not
    unique.
  - `PadronExternoApiTest`: extend `the external padron's fields survive a portal save of the predio…`. Put
    `codigo_origen = "000000001-0001-0001"` through the generic API, PUT `/api/srtm/predios/{id}` with
    `codigo_origen = "OTRO"` in the body, and assert the stored value is still `000000001-0001-0001` and the ficha's
    `valuacion_origen.predio.codigo_origen` is `000000001-0001-0001`. A POST `/api/srtm/predios` with `codigo_origen`
    stores none.
- [ ] **Step 2:** `cd model && python3 -m unittest -v test_model`. Expect FAIL. Then
  `$REMOTO integrationTest --tests '*PadronExternoApiTest*'`. Expect FAIL.
- [ ] **Step 3: Implement.** Add the field after `codigo`: "Código de origen", description "El código del predio en
  el padrón de origen (Ica: el del SAT). Distinto de codigo cuando el padrón da un mismo código a predios distintos
  (#207): la brecha cruza por él.". Then the dto and the two writes.
- [ ] **Step 4:** Re-run both. Expect PASS. `python3 apply.py --validate-only`.
- [ ] **Step 5: Commit.** `feat(rentas): el predio guarda su código de origen, de solo lectura (#207)`

### Task 8: the importer splits a shared code into its distinct predios

**Files:**
- Modify: `model/import_padron_ica.py`, `README.md` («Cargar la Municipalidad de Ica»: «Códigos compartidos»),
  `docs/develop/alta-organizacion.md` (Pendiente: «confirmar con la municipalidad que un código del SAT agrupa
  predios distintos (#207)»)
- Test: `model/test_import_padron_ica.py`

**Interfaces:**
- Produces:
  - `identidad(row) -> tuple[str | None, Decimal | None, Decimal | None]`;
  - `codigos_derivados(rows) -> tuple[dict[tuple[str, str], str], list[dict]]`, which maps (SAT code, contribuyente)
    to the srtm code and lists the shared codes as `{"codigo", "fila", "djs", "predios": [codes]}`;
  - `Registro.codigo_predio` is now the srtm code for declaraciones and niveles alike;
  - every predio gets `attributes["codigo_origen"]` = the SAT code.

- [ ] **Step 1: Write the failing tests.** They go in a new class `CodigoCompartidoTests`, plus one in `CliTests`.
  P is `"000000009-0001-0001"`.

```python
def test_a_code_shared_by_two_distinct_predios_gives_two_predios_at_100(self):
    data = build([padron(codigo_predio=P, codigo_contribuyente="900001", direccion_del_predio_completa="JR LIMA 100", area_terreno="120", valor_predio="50000"),
                  padron(codigo_predio=P, codigo_contribuyente="900002", nro_documento="40000002", direccion_del_predio_completa="JR LIMA 200", area_terreno="300", valor_predio="90000")])
    self.assertEqual(set(data.predios), {f"{P}-1", f"{P}-2"})
    self.assertEqual({a["codigo_origen"] for a in data.predios.values()}, {P})
    self.assertEqual([(d.codigo_predio, d.attributes["porcentaje_condominio"]) for d in data.declaraciones], [(f"{P}-1", "100.00"), (f"{P}-2", "100.00")])
    self.assertEqual([d.attributes["valor_condominio"] for d in data.declaraciones], ["50000.00", "90000.00"])
    [r] = [r for r in data.reporte if r["categoria"] == "codigo_compartido"]
    self.assertEqual((r["codigo_predio"], r["codigo_contribuyente"]), (P, None))
    self.assertEqual(r["detalle"], f"2 DJ en 2 predios distintos: {P}-1 a {P}-2")
    self.assertEqual(categorias(data)["condominio_100_n"], 0)
```
  - `test_a_real_condominio_stays_at_100_n`. Same address, area and value: one predio `P` with `codigo_origen == P`,
    shares `["50.00", "50.00"]`, `condominio_100_n == 1`, no `codigo_compartido`.
  - `test_a_condominio_and_another_predio_under_one_code`. A and B are equal, C differs: `P-1` holds A and B at
    50/50 CONDOMINO, `P-2` holds C at 100 PROPIETARIO UNICO.
  - `test_the_numbering_follows_the_lowest_contribuyente_code_numeric_first`. Contribuyentes `"100000"` and `"99999"`:
    the predio of 99999 is `P-1`.
  - `test_the_niveles_follow_their_derived_predio`. A construccion of (P, 900002) gives a nivel whose
    `codigo_predio == f"{P}-2"`.
  - `test_a_derived_code_that_is_another_code_of_the_padron_is_rejected`. Expect a `codigo_derivado_repetido` report
    row, and that group is not loaded.
  - `test_the_shared_code_report_names_no_person`. No `nro_documento` and no name appears in any `codigo_compartido`
    row.
  - Replace `test_different_values_are_to_review` with `test_different_values_are_distinct_predios`.
  - In `CliTests`, `test_a_shared_code_core_already_has_is_skipped_and_reported`. Do
    `self.core.add_record("predio", {"codigo": P})` and run the load: no POST of `P-1`/`P-2` nor of their DJ and
    niveles, and the report has `codigo_compartido_en_core` for P.
  - `test_dry_run_counts_reports_and_calls_nothing` also asserts the line
    `códigos compartidos: 1 (2 predios, 2 DJ)` when its fixture shares one code.
- [ ] **Step 2:** `cd model && python3 -m unittest -v test_import_padron_ica`. Expect FAIL.
- [ ] **Step 3: Implement.**
  - `_Builder` computes `codigos_derivados(rows)` before `predios()`. `predios()`, `declaraciones()` (its `grupos` key
    becomes the srtm code) and `niveles()` read the srtm code through it.
  - `condominio()` runs per srtm predio. Drop `condominio_valores_distintos`.
  - `load()` drops every srtm code whose SAT code is shared when Core has a predio with that bare code, and reports it.
  - `main()` prints the summary line.
- [ ] **Step 4:** `python3 -m unittest -v test_import_padron_ica && python3 -m unittest`. Expect PASS.
- [ ] **Step 5: Commit.** `fix(modelo): un código del SAT con predios distintos da un predio por cada uno, al 100 % (#207)`

**Controller, not an implementer step:** run the dry-run on the real padrón (the user's OK, dev rules), paste the
`códigos compartidos` line into the PR, add the point to «Por confirmar» of épica #126 on GitHub, and open the
migration issue for the codes dev already has.

### Task 9: the cruce tries the code of origin

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/Brecha.kt` (`cruzar`), `README.md` («Cruce»: the new pass),
  `docs/manuales/sistema/manual-sistema.adoc` (the «Cruce» row of the brecha)
- Test: `src/test/kotlin/srtm/brecha/BrechaTest.kt`

**Interfaces:**
- Consumes: `Predio.codigoOrigen` (Task 7).

- [ ] **Step 1: Write the failing tests.**
  - `an item whose code is no predio's crosses by the codigo_origen, the first derived predio`. Predios `X-1` and
    `X-2` both have origin `X`, and the item is `X`: the item crosses `X-1`, and `X-2` is SIN_CATASTRO.
  - `a predio's own code wins over another's codigo_origen`. Predio `X`, plus predio `Y-1` with origin `X`: item `X`
    crosses `X`.
  - `the codigo_origen pass comes before the CPU`. Predio `X-1` has origin `X`; another predio has CPU `C`; the item
    is `X` with CPU `C`. The item crosses `X-1`.
- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.BrechaTest'`. Expect FAIL.
- [ ] **Step 3: Implement.** In `cruzar`, add
  `pasada({ llave(it.codigoMunicipal) }, indice { llave(it.codigoOrigen) })` right after the código municipal pass.
- [ ] **Step 4:** Re-run. Expect PASS.
- [ ] **Step 5: Commit.** `fix(brecha): el cruce prueba con el código de origen cuando el derivado no está en catastro (#207)`

---

## Group #204: brecha con la confianza del vínculo

Branch: `feat/brecha-confianza` (`git checkout -b feat/brecha-confianza` from `fix/codigos-compartidos-ica`)

### Task 10: confianza and SIN_LEVANTAR in the model

**Files:**
- Modify: `model/model.json`
- Test: `model/test_model.py`

- [ ] **Step 1: Write the failing test.** Update `test_the_brecha_is_a_corrida_and_its_predios`:
  - `brecha_corrida` ends with `…, "latido", "predios_confiables", "predios_dudosos", "predios_sin_levantar",
    "delta_impuesto_confiable", "delta_impuesto_dudoso", "delta_impuesto_sin_levantar"`;
  - `brecha_predio` ends with `…, "orden", "obras_dj", "confianza", "confianza_motivo"`;
  - `enums["confianza_brecha"] == ["CONFIABLE", "DUDOSO", "SIN_LEVANTAR"]`;
  - `enums["categoria_brecha"] == ["OMISO", "SIN_CATASTRO", "SIN_VALOR", "SUBVALUADO", "SOBREVALUADO", "COINCIDE", "SIN_LEVANTAR"]`.
- [ ] **Step 2:** `python3 -m unittest -v test_model`. Expect FAIL.
- [ ] **Step 3: Add the fields.** The three counts are INTEGER and the three deltas DECIMAL. `confianza` is the ENUM
  `confianza_brecha`; `confianza_motivo` is TEXT.
- [ ] **Step 4:** `python3 apply.py --validate-only && python3 -m unittest -v test_model`. Expect PASS.
- [ ] **Step 5: Commit.** `feat(modelo): confianza del vínculo y categoría SIN_LEVANTAR en la brecha (#204)`

### Task 11: catastro's new keys and its titulares

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/Modelo.kt`, `src/main/kotlin/srtm/brecha/ClienteCatastro.kt` (`item`,
  `titulares`, `CatastroException.estado`, `Catastro`)
- Test: `src/test/kotlin/srtm/brecha/ClienteCatastroTest.kt`, `src/test/kotlin/srtm/brecha/CatastrosTest.kt` (`Falso`)

**Interfaces:**
- Produces:
  - `ValuacionCatastral` gains `estadoLevantamiento: String?`, `fechaLevantamiento: String?`, `entrega: String?`,
    `vinculoMetodo: String?`, `vinculoRevisado: Boolean?`, `cuc: String?` and `codigoReferenciaCatastral: String?`,
    all `= null`.
  - `data class TitularCatastral(val codigoMunicipal: String?, val tipoDocumento: String?, val numeroDocumento: String?, val nombre: String?, val porcentaje: BigDecimal?, val condicion: String?)`.
  - `Catastro.titulares(porPagina: suspend () -> Unit = {}): List<TitularCatastral>`.
  - `class CatastroException(mensaje: String, val estado: Int? = null)`.
  - The constants `SIN_LEVANTAR = "SIN_LEVANTAR"`, `CODIGO_RENTAS = "CODIGO_RENTAS"` and `MANUAL = "MANUAL"`.

- [ ] **Step 1: Write the failing tests** in `ClienteCatastroTest`:
  - `an item carries the levantamiento and the vinculo, null when catastro has none`. Use one stub item with
    `"estadoLevantamiento":"SIN_LEVANTAR","fechaLevantamiento":"2026-08-01","entrega":"E-2026-01","vinculoMetodo":"MANUAL","vinculoRevisado":true,"cuc":"0101","codigoReferenciaCatastral":"110101…"`,
    and `ITEM_VALORADO` with all of them null.
  - `the titulares, every page`. A `/api/catastro/titulares` stub serves 3 items in pages of 2, `total = 3`; expect
    3 titulares in order, and `page=0&size=500` and `page=1` asked.
  - `a catastro without titulares fails with its status`. Stub 404: the `CatastroException` has `estado == 404`, and
    its message has no secret.
- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.ClienteCatastroTest'`. Expect FAIL.
- [ ] **Step 3: Implement.**
  - `item()` reads the 7 keys (`vinculoRevisado` as a boolean node or null).
  - `titulares` pages like `valuaciones`, on `/api/catastro/titulares?page&size=500`.
  - `autorizado` passes `e.statusCode.value()` to `CatastroException`.
  - `Falso.titulares` returns `emptyList()`.
- [ ] **Step 4:** `./gradlew test --tests 'srtm.brecha.*'`. Expect PASS.
- [ ] **Step 5: Commit.** `feat(brecha): el cliente lee el levantamiento, el vínculo y los titulares del catastro (#204)`

### Task 12: each row's confianza, and SIN_LEVANTAR as its own category

**Files:**
- Create: `src/main/kotlin/srtm/brecha/Confiabilidad.kt`, `src/test/kotlin/srtm/brecha/ConfiabilidadTest.kt`
- Modify: `src/main/kotlin/srtm/brecha/Brecha.kt` (`Padron`, `medir`), `src/main/kotlin/srtm/brecha/Modelo.kt`
  (`Categoria.SIN_LEVANTAR`, `FilaBrecha`/`PredioBrecha` `confianza` and `confianzaMotivo`)
- Test: `src/test/kotlin/srtm/brecha/BrechaTest.kt`

**Interfaces:**
- Consumes: `TitularCatastral`, the new `ValuacionCatastral` keys (Task 11).
- Produces:
  - `enum class Confianza { CONFIABLE, DUDOSO, SIN_LEVANTAR }` and `data class ConfianzaDeFila(val confianza: Confianza, val motivo: String)`;
  - `object Confiabilidad`, with
    - `fun documento(numero: String?): String?`,
    - `fun nombre(texto: String?): String?`, and
    - `fun de(item: ValuacionCatastral?, predio: Predio?, fichas: List<TitularCatastral>, titulares: List<Contribuyente>): ConfianzaDeFila`;
  - `Padron.contribuyentes: Map<String, Contribuyente> = emptyMap()`;
  - `Brecha.medir(..., titulares: List<TitularCatastral> = emptyList(), sinTitulares: String? = null)`.
- The motivo texts:
  - "Ficha sin levantar" + `" (entrega $e)"` when there is one;
  - "Vínculo por código de rentas" and "Vínculo manual revisado";
  - "Un titular de la ficha coincide por documento" and "Un titular de la ficha coincide por nombre";
  - "Vínculo ${metodo ?: "sin método"}, sin titular en común";
  - "Sin ficha del catastro" and "Sin predio en el padrón".

- [ ] **Step 1: Write the failing tests.**

```kotlin
// ConfiabilidadTest
@Test fun `a documento without leading zeros nor separators, a padded one is none`() {
    assertEquals("12345678", Confiabilidad.documento("00012345678")); assertEquals("40000001", Confiabilidad.documento(" 4000-0001 "))
    assertNull(Confiabilidad.documento("000000000")); assertNull(Confiabilidad.documento("  "))
}
@Test fun `a nombre with its words sorted, without accents nor case`() {
    assertEquals("GOMEZ JUAN PEREZ", Confiabilidad.nombre("Pérez Gómez, Juan")); assertEquals(Confiabilidad.nombre("JUAN PEREZ GOMEZ"), Confiabilidad.nombre("pérez  gómez juan"))
    assertNull(Confiabilidad.nombre(" , "))
}
@Test fun `a ficha sin levantar is SIN_LEVANTAR whatever its vinculo`()
@Test fun `a vinculo by codigo de rentas, or manual and revisado, is CONFIABLE`()
@Test fun `a manual vinculo not revisado, or a geometric one, without a titular in common is DUDOSO`()
@Test fun `a titular in common by documento or by nombre is CONFIABLE`()   // ficha "00040000001" vs DJ "40000001"; ficha "PEREZ GOMEZ JUAN" vs DJ nombreCompleto "JUAN PEREZ GOMEZ"
@Test fun `without a predio no vinculo makes it CONFIABLE`()               // item CODIGO_RENTAS, predio null -> DUDOSO "Sin predio en el padrón"
// BrechaTest
@Test fun `a ficha sin levantar is its own category, never OMISO nor COINCIDE`()  // crossed and uncrossed -> categoria SIN_LEVANTAR, no deltas; crossed predio has no SIN_CATASTRO row
@Test fun `a catastro without the new keys measures as before and every row is DUDOSO`()  // reuse `one row per catastro item…`'s data: same categoria/deltas, all confianza DUDOSO
@Test fun `the rows carry the titulares' confianza`()                       // medir(…, titulares = [TitularCatastral("A", …, "40000001", …)]) -> CONFIABLE
```

- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.ConfiabilidadTest' --tests 'srtm.brecha.BrechaTest'`. Expect FAIL.
- [ ] **Step 3: Implement.**
  - `de` follows the order in the Rulings. The DJ's titulares are compared through `documento(c.numeroDocumento)`
    and `nombre(srtm.emision.nombreDe(c))`.
  - `medir` indexes `titulares` by `llave(codigoMunicipal)`. Every row gets its confianza; SIN_CATASTRO rows call
    `de(null, predio, …)`. A SIN_LEVANTAR item is never in `valuados`. `sinTitulares`, when given, is added to `avisos`.
- [ ] **Step 4:** Re-run. Expect PASS.
- [ ] **Step 5: Commit.** `feat(brecha): cada fila dice qué tan seguro es su cruce; una ficha sin levantar va aparte (#204)`

### Task 13: panel, corrida and /predios by confianza

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/Brecha.kt` (`panel`), `src/main/kotlin/srtm/brecha/Modelo.kt` (`PanelBrecha`,
  `Kpis`, `RegistroCorrida`, `Corrida`, `corridaDe`), `src/main/kotlin/srtm/brecha/BrechaService.kt` (`correr`,
  `padron`, `pagina`), `src/main/kotlin/srtm/brecha/BrechaController.kt`, `README.md` («Brecha de recaudación»:
  «Confianza del vínculo», the categorías table, the API table, «siete»), `docs/manuales/referencia/api.adoc`
  (`[[ref-api-brecha]]`), `docs/manuales/sistema/manual-sistema.adoc` (the brecha table: «Lectura del catastro» with
  titulares, «Categorías», a new «Confianza» row)
- Test: `src/test/kotlin/srtm/brecha/BrechaTest.kt`, `src/test/kotlin/srtm/brecha/BrechaApiTest.kt`

**Interfaces:**
- Produces:
  - `data class PorConfianza(val predios: Int, val deltaImpuesto: BigDecimal, val deltaAutoavaluo: BigDecimal)` and
    `PanelBrecha.porConfianza: Map<String, PorConfianza>`, whose keys are CONFIABLE, DUDOSO and SIN_LEVANTAR in that
    order;
  - `Kpis.sinLevantar: Int = 0`;
  - the 6 fields of `RegistroCorrida` and `Corrida`;
  - `BrechaService.pagina(corrida, categoria, sector, confianza: Confianza?, page, size)`;
  - the parameter `GET /{anio}/predios?confianza=`.

- [ ] **Step 1: Write the failing tests.**
  - BrechaTest, `the panel adds up by confianza, every key present`. Rows: 2 CONFIABLE with Δ impuesto 10.00 and
    5.00, 1 DUDOSO with -3.00, and none SIN_LEVANTAR. Expect `porConfianza["CONFIABLE"] == PorConfianza(2, 15.00, …)`
    and `porConfianza["SIN_LEVANTAR"] == PorConfianza(0, 0.00, 0.00)`, and the keys in order.
  - BrechaTest, `sin levantar rows count apart and only the crossed ones are padron`. Expect `kpis.sinLevantar`, and
    `prediosPadron` that excludes an uncrossed SIN_LEVANTAR row.
  - BrechaApiTest, `the corrida stores its confianza counts and the predios filter by it`.
    1. The stub's items carry `vinculoMetodo = "CODIGO_RENTAS"` for A and null for B; `/titulares` is not served (404).
    2. The corrida is TERMINADA with `prediosConfiables == 1`.
    3. Its `mensaje` contains "la confianza no compara titulares".
    4. `?confianza=CONFIABLE` gives only A.
    5. `?confianza=X` answers 422.
  - The existing BrechaApiTest tests stay green. The stub's items have none of the new keys: this is Review Focus 3.
- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.BrechaTest'`. Expect FAIL. Then
  `$REMOTO integrationTest --tests '*BrechaApiTest*'`. Expect FAIL.
- [ ] **Step 3: Implement.**
  - `correr` reads `catastro.titulares { latir(id) }`. On a `CatastroException` with `estado` 403 or 404 it continues
    with `emptyList()` and
    `sinTitulares = "El catastro no da titulares (GET /api/catastro/titulares respondió $estado): la confianza no compara titulares"`.
  - `padron` always reads the contribuyentes with `byIds`.
  - The final `actualizar` writes the 6 fields from `panel.porConfianza`.
  - The controller validates `confianza` like `categoria`.
- [ ] **Step 4:** Re-run both. Expect PASS.
- [ ] **Step 5: Commit.** `feat(brecha): el panel, la corrida y la lista de predios por confianza del vínculo (#204)`

---

## Group #203: seguir los cambios del catastro

Branch: `feat/seguimiento-catastro` (`git checkout -b feat/seguimiento-catastro` from `feat/brecha-confianza`)

### Task 14: sincronizacion_catastro, cambio_catastro and the corrida's origen

**Files:**
- Modify: `model/model.json`, `docs/manuales/referencia/modelo-datos.adoc` (`[[ref-modelo-brecha]]`: two rows)
- Test: `model/test_model.py`, `model/test_apply.py` (the object list and the relationship list)

- [ ] **Step 1: Write the failing test** `test_the_seguimiento_keeps_its_cursor_and_its_cambios`:

```python
s = self.campos("sincronizacion_catastro")
self.assertEqual(list(s), ["cursor", "ultimo_cambio_en", "pendientes", "error"])
self.assertEqual(self.relaciones("sincronizacion_catastro"), {"ultima_corrida": ("brecha_corrida", False)})
c = self.campos("cambio_catastro")
self.assertEqual(list(c), ["codigo_municipal", "objeto", "operacion", "campos", "en", "entrega"])
self.assertEqual((c["objeto"]["type"], c["operacion"]["enum"], c["en"]["type"], c["campos"]["type"]), ("TEXT", "operacion_catastro", "DATETIME", "LONG_TEXT"))
self.assertEqual(self.model["enums"]["operacion_catastro"], ["CREATE", "UPDATE", "DELETE"])
self.assertEqual(self.model["enums"]["origen_corrida"], ["MANUAL", "CAMBIOS", "SEMANAL"])
self.assertEqual(self.campos("brecha_corrida")["origen"]["enum"], "origen_corrida")
o = next(o for o in self.model["objects"] if o["name"] == "cambio_catastro")
self.assertEqual((o["indexes"], o["appendOnly"]), ([["en"], ["codigo_municipal"]], True))
```
  Also add the two objects and `sincronizacion_catastro_ultima_corrida` to `test_apply.py`'s lists, and `"origen"` at
  the end of the brecha_corrida field list.
- [ ] **Step 2:** `python3 -m unittest -v test_model test_apply`. Expect FAIL.
- [ ] **Step 3: Implement** the objects, the enums, the relation and `brecha_corrida.origen`. `campos` holds the
  changed field names, joined by ", ".
- [ ] **Step 4:** `python3 apply.py --validate-only && python3 -m unittest`. Expect PASS.
- [ ] **Step 5: Commit.** `feat(modelo): sincronización y cambios del catastro, y el origen de la corrida (#203)`

### Task 15: ClienteCatastro.cambios

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/Modelo.kt`, `src/main/kotlin/srtm/brecha/ClienteCatastro.kt`
- Test: `src/test/kotlin/srtm/brecha/ClienteCatastroTest.kt`, `src/test/kotlin/srtm/brecha/CatastrosTest.kt` (`Falso`)

**Interfaces:**
- Produces:
  - `data class CambioCatastral(val codigoMunicipal: String?, val objeto: String?, val operacion: String?, val campos: List<String>, val en: String?, val entrega: String?)`;
  - `data class PaginaDeCambios(val items: List<CambioCatastral>, val cursor: String?, val hayMas: Boolean)`;
  - `Catastro.cambios(desde: String?, size: Int = ClienteCatastro.TAMANIO_PAGINA): PaginaDeCambios`, which reads
    `GET /api/catastro/cambios?desde=&size=` (no `desde` when null, url-encoded).

- [ ] **Step 1: Write the failing tests:**
  - `the cambios from a cursor, as the contract prints them`. The stub answers `desde` absent with 2 items, `cursor
    "c2"` and `hayMas true`, and `desde=c2` with `[]`, `"c2"` and `false`. Expect the items read field by field,
    `campos` a list, and the query `size=500`.
  - `a cursor is sent encoded`. `desde = "a+b/="`.
  - `a catastro without cambios fails with its status`. Stub 404: `estado == 404`.
- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.ClienteCatastroTest'`. Expect FAIL.
- [ ] **Step 3: Implement**, with the `autorizado` helper and `UriComponentsBuilder…encode()`. `Falso.cambios` throws
  `UnsupportedOperationException`.
- [ ] **Step 4:** `./gradlew test --tests 'srtm.brecha.*'`. Expect PASS.
- [ ] **Step 5: Commit.** `feat(brecha): el cliente lee los cambios del catastro desde un cursor (#203)`

### Task 16: when an automatic corrida runs, and the aviso, as pure rules

**Files:**
- Create: `src/main/kotlin/srtm/brecha/Seguimiento.kt`, `src/test/kotlin/srtm/brecha/SeguimientoTest.kt`

**Interfaces:**
- Produces:
  - `enum class Disparo { NINGUNO, CAMBIOS, SEMANAL }`;
  - `@JsonNaming(SnakeCase) data class RegistroCambio(val id: String? = null, val codigoMunicipal: String? = null, val objeto: String? = null, val operacion: String? = null, val campos: String? = null, val en: String? = null, val entrega: String? = null)`;
  - `@JsonNaming(SnakeCase) data class RegistroSincronizacion(val id: String? = null, val cursor: String? = null, val ultimoCambioEn: String? = null, val ultimaCorrida: String? = null, val pendientes: Int? = null, val error: String? = null)`;
  - `data class CambioDelPredio(val codigoMunicipal: String, val predio: String?, val codigoPredio: String?, val categoria: String?, val cambios: Int, val objetos: List<String>, val entregas: List<String>, val ultimoCambio: String?, val autoavaluoDeclarado: BigDecimal?, val autoavaluoCatastro: BigDecimal?, val deltaAutoavaluo: BigDecimal?, val marca: String?)`;
  - `object Seguimiento`, with
    - `val LIMA: ZoneId`,
    - `const val SUPERA_5_UIT = "SUPERA_5_UIT"`,
    - `fun disparo(ahora: ZonedDateTime, hora: Int, pendientes: Int, ultima: Instant?, ultimaFallida: Boolean): Disparo`,
      and
    - `fun aviso(cambios: List<RegistroCambio>, filas: List<FilaBrecha>, uit: BigDecimal): List<CambioDelPredio>`;
  - the constants `SINCRONIZACION_CATASTRO` and `CAMBIO_CATASTRO`.

- [ ] **Step 1: Write the failing tests.** All times are in America/Lima, `hora = 2`, and `hoy` = 2026-10-25.
  - `before the hour nothing runs`. 01:59 with `pendientes 3` and `ultima null` → NINGUNO.
  - `at or after the hour a change runs it, once a day`.
    - 02:00 with `pendientes 1` and `ultima` 2026-10-24T02:10 → CAMBIOS.
    - 23:00, same inputs → CAMBIOS.
    - `ultima` 2026-10-25T02:00:30 → NINGUNO.
  - `a failed automatic corrida runs again the next day`. `pendientes 0`, `ultima` 2026-10-24T02:00 and
    `ultimaFallida true` → CAMBIOS.
  - `without changes only the weekly runs`. `pendientes 0`:
    - `ultima` 2026-10-19 → NINGUNO;
    - `ultima` 2026-10-18 → SEMANAL;
    - `ultima` null → SEMANAL.
  - `the aviso groups the cambios by codigo and marks what passes 5 UIT`. `uit = 5500.00`, so the threshold is
    27500.00. Rows: A Δ 27500.01, B Δ 27500.00, C Δ -30000.00, D with no row. The cambios are A ×2 (objetos
    construccion and predio_catastral, entregas E1 and null), B, C, D, and one with `codigoMunicipal` null. Expect:
    - the order C, A, B, D (marked by |Δ| from highest, then the rest);
    - A has `cambios == 2`, `objetos == ["construccion", "predio_catastral"]` and `entregas == ["E1"]`;
    - the marca of A and C is `SUPERA_5_UIT`, and B's and D's is null;
    - the cambio without a code is left out.
  - `the aviso joins by the codigo as llave compares it`. The cambio `01-01-0006` joins the row whose
    `codigoCatastro` is `1-1-6`.
- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.SeguimientoTest'`. Expect FAIL.
- [ ] **Step 3: Implement.** Compare calendar dates in LIMA. The weekly rule is
  `ultima.date <= ahora.date.minusDays(7)`.
- [ ] **Step 4:** Re-run. Expect PASS.
- [ ] **Step 5: Commit.** `feat(brecha): cuándo corre la brecha sola y qué predios cambiados pasan de 5 UIT (#203)`

### Task 17: BrechaService.lanzarAutomatica as the platform

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/BrechaService.kt`, `src/main/kotlin/srtm/brecha/Modelo.kt` (`RegistroCorrida.origen`,
  `Corrida.origen`)
- Test: covered by Task 18's integration test (it needs Core)

**Interfaces:**
- Produces:
  - `enum class OrigenCorrida { MANUAL, CAMBIOS, SEMANAL }`;
  - `suspend fun lanzarAutomatica(organizacion: UUID, catastro: Catastro, anio: Int, origen: OrigenCorrida): Corrida`.
    The caller is already inside `asPlatform(organizacion)`. It runs the same candado and active check as `lanzar`
    (409 `ConflictException` when one is alive), creates the corrida with `origen`, and launches
    `scope.launch { records.asPlatform(organizacion, "srtm: brecha automática") { correr(id, anio, catastro) } }`.
    It checks no permissions.
  - `lanzar` writes `origen = MANUAL`.

- [ ] **Step 1:** Implement. `BrechaService` gains the dependency `wasichai.core.data.RecordService`. Factor the
  transaction of `lanzar` into
  `private suspend fun crearCorrida(organizacion: UUID, anio: Int, origen: OrigenCorrida): RegistroCorrida`, which
  both entry points use.
- [ ] **Step 2:** `./gradlew test --tests 'srtm.brecha.*'`, then `$REMOTO integrationTest --tests '*BrechaApiTest*'`.
  Expect PASS (`origen` MANUAL on a POSTed corrida: add that assertion to
  `a corrida crosses the padron with the catastro…`).
- [ ] **Step 3: Commit.** `feat(brecha): una corrida automática corre como la plataforma y dice su origen (#203)`

### Task 18: the seguimiento loop

**Files:**
- Create: `src/main/kotlin/srtm/brecha/SeguimientoCatastro.kt`, `src/test/kotlin/srtm/brecha/SeguimientoCatastroApiTest.kt`
- Modify: `src/main/kotlin/srtm/brecha/ClienteCatastro.kt` (`CatastroProperties.seguimiento`),
  `src/main/resources/application.yml` (`srtm.catastro.seguimiento.minutos: ${SRTM_CATASTRO_SEGUIMIENTO_MINUTOS:0}`,
  `hora: ${SRTM_CATASTRO_SEGUIMIENTO_HORA:2}`)

**Interfaces:**
- Consumes: `Catastro.cambios` (Task 15), `Seguimiento.disparo` (Task 16), `lanzarAutomatica` (Task 17).
- Produces:
  - `data class SeguimientoProperties(val minutos: Long = 0, val hora: Int = 2)`;
  - `@Component class SeguimientoCatastro(registros: Registros, records: RecordService, candado: ClusterLock, organizaciones: TenantDirectory, catastros: Catastros, brechas: BrechaService, config: CatastroProperties, transacciones: ReactiveTransactionManager)`,
    with
    - `@EventListener(ApplicationReadyEvent::class) fun arrancar()` (only when `minutos > 0` and `catastros.configurado`),
    - `@PreDestroy fun detener()`, and
    - `suspend fun vuelta(ahora: ZonedDateTime = ZonedDateTime.now(Seguimiento.LIMA), hora: Int = config.seguimiento.hora): Int`
      (the organizations it processed);
  - the lock key `"srtm:seguimiento-catastro:$organizacionId"`.
- What one organization's pass does, inside `candado.tryLock(key)?.use { records.asPlatform(org, "srtm: seguimiento del catastro") { … } }`:
  1. read or create the single `RegistroSincronizacion`;
  2. read pages until `!hayMas`, and write each one's `cambio_catastro` rows with `createAll` plus `cursor`,
     `ultimo_cambio_en` (the max `en`), `pendientes += n` and `error = null`, in one transaction;
  3. on a `CatastroException`, write `error = message` and skip step 4;
  4. compute `disparo(ahora, hora, pendientes, ultima.iniciado, ultima.estado == FALLIDA)`. When it is not NINGUNO,
     call `lanzarAutomatica(org, catastro, ahora.year, …)` and write `ultima_corrida = id, pendientes = 0`; a
     `ConflictException` leaves everything as it is.
- Organizations come from `organizationsWithObject(SINCRONIZACION_CATASTRO)` and `catastros.de(id, slug)`. One without
  a catastro, or configured twice, is skipped.

- [ ] **Step 1: Write the failing integration tests** (`SeguimientoCatastroApiTest : SrtmApiTest`). Its own stub
  serves `/api/auth/token`, `/api/catastro/cambios` (an `AtomicReference` of pages plus a call counter, and an
  optional status), `/api/catastro/valuaciones` and `/api/catastro/titulares` (404). `@DynamicPropertySource` sets the
  catastro for one slug, `seguimiento.minutos = 0` (the test drives `vuelta`) and `seguimiento.hora = 0`. The bean is
  `@Autowired`.
  - `a change read from catastro leaves its cambio_catastro and moves the cursor`. Run `vuelta(now, hora = 24)`; no
    hour reaches 24, so nothing is launched. Expect 1 row with every field, the cursor `c1` and `pendientes 1`. A
    second `vuelta` writes nothing more, and the stub was asked with `desde=c1`.
  - `a pending change starts the corrida of the hour, and the next pass the same day does not`. Expect one
    `brecha_corrida` with `origen` CAMBIOS that reaches TERMINADA, and `pendientes 0` with `ultima_corrida` set. A
    second `vuelta(now)` creates none.
  - `without changes there is no corrida but the weekly`. Expect `vuelta(now + 1 day)` → none, and
    `vuelta(now + 7 days)` → one with `origen` SEMANAL.
  - `catastro failing leaves its error and no corrida, and the next good read clears it`. Stub 500 (then 404):
    `error` contains "respondió 500" (then "respondió 404"), the cursor is unchanged, and no new corrida. After the
    stub answers 200: `error` null.
  - `a lock held elsewhere skips the organization`. With the test's own `candado.tryLock(key)` held, `vuelta` makes
    the stub's call counter stay at 0. Release it after.
- [ ] **Step 2:** `$REMOTO integrationTest --tests '*SeguimientoCatastroApiTest*'`. Expect FAIL (no bean).
- [ ] **Step 3: Implement** as in Interfaces. The loop follows `EnvioACaja.arrancar` (`delay(minutos)`, catch and log
  each pass's `Throwable`, and rethrow `CancellationException`).
- [ ] **Step 4:** Run `$REMOTO integrationTest --tests '*SeguimientoCatastroApiTest*' --tests '*BrechaApiTest*'` and
  `./gradlew test --tests 'srtm.brecha.*'`. Expect PASS.
- [ ] **Step 5: Commit.** `feat(brecha): srtm sigue los cambios del catastro y corre la brecha sola (#203)`

### Task 19: GET /brecha/{anio}/cambios and the docs

**Files:**
- Modify: `src/main/kotlin/srtm/brecha/BrechaService.kt` (`cambios`), `src/main/kotlin/srtm/brecha/BrechaController.kt`,
  `docs/manuales/referencia/configuracion.adoc` (`[[ref-config-catastro]]`: `SRTM_CATASTRO_SEGUIMIENTO_MINUTOS` and
  `_HORA`), `docs/manuales/sistema/manual-sistema.adoc` (the brecha table: «Seguimiento»; «Procesos asíncronos»: the
  row «Seguimiento del catastro»; «Estados y fallos»), `docs/manuales/referencia/api.adoc` (`GET /{anio}/cambios`, and
  `origen` in the corridas), `docs/manuales/referencia/permisos.adoc` (the row of the aviso), `README.md` («Brecha de
  recaudación»: «Seguimiento del catastro», the API table)
- Test: `src/test/kotlin/srtm/brecha/SeguimientoCatastroApiTest.kt`

**Interfaces:**
- Consumes: `Seguimiento.aviso` (Task 16), `Multas.uit(parametros, LocalDate.of(anio, 1, 1))`.
- Produces:
  - `suspend fun cambios(anio: Int, corrida: UUID?, desde: LocalDate?, objeto: String?): List<CambioDelPredio>`.
    It runs `corridaDelAnio` and `filas` as the caller, and
    `records.asPlatform(org) { registros.donde(CAMBIO_CATASTRO, RegistroCambio::class.java, listOfNotNull(Peticiones.entre("en", desde ?: 1 Jan, null)), filters = objeto?.let { mapOf("objeto" to it) } ?: emptyMap()) }`.
    Without the UIT it throws `class FaltanBrecha(faltan: List<String>) : Faltan("Faltan parámetros para el aviso de cambios: …", faltan)`.
  - `@GetMapping("/{anio}/cambios") suspend fun cambios(anio, corrida: UUID?, desde: LocalDate? (ISO), marca: String?, objeto: String?, page = 0, size = 25): PageResponse<CambioDelPredio>`.
    It reads after `leer()`, filters `marca == SUPERA_5_UIT` in memory, then `.pagina(page, size)`. It answers 422
    `ParametroInvalido` for a `marca` other than SUPERA_5_UIT, an `objeto` outside the four, or a bad page or size.

- [ ] **Step 1: Write the failing integration tests:**
  - `the aviso lists the changed predios with their difference and marks those above 5 UIT`.
    - Setup: after a TERMINADA corrida, predio A is declared 100000 with catastro 100000 + 5 × UIT(2026) + 0.01, and
      predio B 100000 with catastro 101000. The stub's cambios are 2 of A and 1 of B, and one `vuelta` runs.
      `Parametros.uit(2026)` gives the UIT.
    - Expect `GET /api/srtm/brecha/2026/cambios` → `totalElements == 2`, A first with `marca == "SUPERA_5_UIT"` and
      `cambios == 2`, then B with marca null.
    - Expect `?marca=SUPERA_5_UIT` → 1 row and `?marca=OTRA` → 422.
  - `the aviso asks the brecha's read`. A role without READ on `brecha_corrida` gets 403.
- [ ] **Step 2:** `$REMOTO integrationTest --tests '*SeguimientoCatastroApiTest*'`. Expect FAIL (404 on the route).
- [ ] **Step 3: Implement**, then write the docs.
  - `configuracion.adoc`: `SRTM_CATASTRO_SEGUIMIENTO_MINUTOS` (default `0`, off: «cada cuántos minutos lee
    `GET /api/catastro/cambios`») and `SRTM_CATASTRO_SEGUIMIENTO_HORA` (default `2`, hora de Lima).
  - The manual and the README explain the daily corrida with changes, the weekly one, the 5 UIT (LTM art. 14 b), the
    4 or 6 years (CT art. 43), the `ClusterLock` and the platform identity.
- [ ] **Step 4:** Run `$REMOTO integrationTest --tests '*SeguimientoCatastroApiTest*' --tests '*BrechaApiTest*'`,
  `./gradlew ktlintCheck test` and `cd docs/manuales && python3 herramientas/validar.py`. Expect PASS.
- [ ] **Step 5: Commit.** `feat(brecha): aviso de predios con cambios en catastro, con la marca de 5 UIT (#203)`
