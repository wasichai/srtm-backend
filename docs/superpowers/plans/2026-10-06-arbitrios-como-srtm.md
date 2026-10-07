# Arbitrios calculados como el SRTM web — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que `srtm.arbitrios` determine cada cuota mensual como el SRTM web del MEF: tasa × base (predio, ml de frontis o m² construido) × factor de habitantes, con zona por servicio resuelta por ubicación, parques por influencia y afluencia, un PU por uso, redondeo, tope y desglose visible.

**Architecture:** Todo lo que fija la ordenanza sigue siendo parámetro (`parametro_tributario`, con vigencia y doble firma), salvo la zonificación por ubicación, que es un objeto propio (`zona_arbitrio_ubicacion`) por volumen. El cálculo sigue siendo puro (`object Arbitrios` y funciones nuevas en `Servicios.kt`, `Zonas.kt` y `Calculo.kt`); `ArbitriosService` solo carga y escribe. Cada sub-issue es un PR apilado sobre el anterior.

**Tech Stack:** Kotlin 2.2 / Spring WebFlux sobre wasichai Core (`RecordService`), JUnit 5 (+ Testcontainers en `integrationTest`), Python 3.11 stdlib para `model/`, React + TypeScript + vitest en `srtm-ui`.

**Spec:** épica wasichai/srtm-backend#80 y sus sub-issues #81–#88, wasichai/srtm-ui#94 (léanse completos: los criterios de aceptación de cada issue son los de su PR). Fuentes de referencia: manual SRTM web M02 «Determinación de Deuda» págs. 17-18 y M21 «Parámetros» §5.5-5.6 (`~/Documents/Manuales SRTM WEB/CF 1 …`).

## Global Constraints

- Compatibilidad: sin ninguna fila de parámetro nueva y sin `zona_arbitrio_ubicacion`, un predio de **un solo uso** produce las mismas cuotas que hoy: mismo `monto`, mismo `parametro_aplicado`, mismas cantidades. Solo la `clave` cambia (gana la secuencia, PR #84).
- Ninguna cifra inventada: los tests usan cifras ficticias en un año propio, salvo los dos casos citados con fuente: el ejemplo del manual M02 (3.37×12=40.44; 0.12×180×(1+(5−1)×1)=108.00; 150.30; 6.61; total 305.35) y Miraflores 2026 Ord. 665/MM (tasas 0.0514, 5.0856, 85.9875, 61.0535; P=2, V=0.5; redondeo 2:ABAJO).
- Todo o nada: un dato o parámetro que falta hace fallar el predio entero; `faltan` nombra cada cosa una sola vez; no se escribe nada.
- Todo campo nuevo de una cuota debe existir en `model/model.json` (si no, `Registros.soloEscribibles` lo descarta en silencio).
- Mensajes, comentarios y README en español, con el estilo del repo (comentarios en inglés en Kotlin/Python como el código vecino). commitlint: cabecera ≤ 120, cuerpo ≤ 100 por línea, conventional commits, trailers `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` y `Claude-Session: https://claude.ai/code/session_01G1K7zCBvGsWCgfFEGSCCx2`.
- Cada PR: `./gradlew ktlintCheck test` y `cd model && python3 -m unittest` verdes localmente; `integrationTest` lo corre el CI (local es lento). PR del backend contra la rama del PR anterior (el primero contra `main`); UI contra `dev`. Nunca mezclar sin pedido explícito.
- `parametro_aplicado`: sube de 120 a 500 caracteres (`PARAMETRO_APLICADO_MAXIMO`) en el PR #84, porque guarda varias llaves separadas por `;`.

## Review Focus

- **Factor de habitantes ≤ 0** (H = 0 o V > 1/P): H = 0 en Miraflores da factor 0 y monto 0 (válido: «si no hay habitantes no hay residuos»); un factor negativo es `faltan` «Factor de habitantes negativo …», nunca un monto negativo. Test en Task 8.
- **Número de predio no numérico** («S/N», «245-A», vacío): `Zonas.numeroDe` toma los dígitos iniciales («245-A» → 245); sin dígitos, no hay match por ubicación y se cae al sector. Test en Task 3.
- **Varias declaraciones de la misma secuencia el mismo día** (condóminos): de cada secuencia se toma la del titular principal de esa secuencia; el contribuyente de todas las cuotas es el titular principal del día. Test en Task 8.
- **Redeterminación tras anular una cuota de la secuencia 2**: solo esa secuencia vuelve con `…|2`; la secuencia 1 no se duplica. Test en Task 8.
- **Tope con cuotas ya determinadas este año** (determinación parcial): el tope se aplica a la suma del año (existentes + nuevas) y solo reduce las nuevas, nunca por debajo de 0. Test en Task 13.

---

## Estructura de archivos

| Archivo | Responsabilidad | PR |
|---|---|---|
| `src/main/kotlin/srtm/arbitrios/Llaves.kt` | Nombres de tipos y armado de llaves | 81, 83, 87 |
| `src/main/kotlin/srtm/arbitrios/Servicios.kt` (nuevo) | Reglas por servicio leídas de parámetros: base, dimensiones, habitantes, redondeo, tope | 81, 87 |
| `src/main/kotlin/srtm/arbitrios/Zonas.kt` (nuevo) | Zona de un servicio para un predio: ubicación > sector | 82 |
| `src/main/kotlin/srtm/arbitrios/Dimensiones.kt` (nuevo) | Valor de cada dimensión (ZONA, USO, INFLUENCIA, AFLUENCIA) | 83 |
| `src/main/kotlin/srtm/arbitrios/Calculo.kt` (nuevo) | Monto = tasa × cantidad × factor, redondeo, desglose y fórmula legible | 84, 85 |
| `src/main/kotlin/srtm/arbitrios/Arbitrios.kt` | Bucle de determinación (mes × secuencia × servicio) | 82, 83, 84, 87 |
| `src/main/kotlin/srtm/arbitrios/Modelo.kt` | DTOs (`CuotaArbitrio`, `ZonaUbicacion`), `claveDeCuota`, `invariantes` | 82, 84, 87 |
| `src/main/kotlin/srtm/arbitrios/ArbitriosService.kt` | Carga de contexto (ubicaciones, cuotas del año anterior) y escritura | 82, 87 |
| `src/main/kotlin/srtm/arbitrios/Consultas.kt`, `HojaHla.kt`, `DocumentosArbitrios.kt`, `templates/emision/hla.html` | Matriz, HLA y desglose | 84, 85 |
| `model/model.json`, `model/test_model.py`, `model/test_apply.py`, `model/apply.py` (docstring) | Objeto `zona_arbitrio_ubicacion`, campos nuevos de `cuota_arbitrio` | 82, 84, 87 |
| `model/import_parametros.py` (+ tests) | Validación de los tipos nuevos | 81, 83, 87 |
| `model/import_zonas_arbitrio.py` (nuevo, + tests) | Carga de zonas por ubicación | 82 |
| `model/import_tasas_arbitrio.py` (nuevo, + tests, fixture ficticio) | Tabla de tasas del informe técnico → CSV de parámetros | 86 |
| `model/derivar_ipc_arbitrio.py` (nuevo, + tests) | Tasas derivadas por IPC | 88 |
| `README.md` § Arbitrios | Documentación de cada cambio | todos |
| `srtm-ui/src/portal/...` | Desglose, faltan con enlace, DJ obligatoria por base | ui#94 |

---

## PR 1 — #81 Parámetros por servicio (rama `feat/arbitrios-parametros-servicio`, base `main`)

### Task 1: Tipos y lectores de reglas por servicio

**Files:**
- Modify: `src/main/kotlin/srtm/arbitrios/Llaves.kt`
- Create: `src/main/kotlin/srtm/arbitrios/Servicios.kt`
- Test: `src/test/kotlin/srtm/arbitrios/ServiciosTest.kt`, `src/test/kotlin/srtm/arbitrios/LlavesTest.kt`

**Interfaces (Produces):**
```kotlin
object Llaves {
    const val TASA_ARBITRIO = "TASA_ARBITRIO"; const val ARBITRIO_ZONA = "ARBITRIO_ZONA"
    const val ARBITRIO_USO = "ARBITRIO_USO"; const val ARBITRIO_VENCIMIENTO = "ARBITRIO_VENCIMIENTO"
    const val BASE_ARBITRIO = "BASE_ARBITRIO"; const val DIMENSIONES_ARBITRIO = "DIMENSIONES_ARBITRIO"
    const val HABITANTES_PROMEDIO = "HABITANTES_PROMEDIO"; const val HABITANTES_VARIACION = "HABITANTES_VARIACION"
    const val REDONDEO_ARBITRIO = "REDONDEO_ARBITRIO"
    val TIPOS = listOf(TASA_ARBITRIO, ARBITRIO_ZONA, ARBITRIO_USO, ARBITRIO_VENCIMIENTO, BASE_ARBITRIO,
        DIMENSIONES_ARBITRIO, HABITANTES_PROMEDIO, HABITANTES_VARIACION, REDONDEO_ARBITRIO)
    fun tasa(servicio: String, zona: String, uso: String) = tasa(servicio, listOf(zona, uso))
    fun tasa(servicio: String, valores: List<String>) = (listOf(servicio) + valores).joinToString(":")
    fun aplicado(claveDeTasa: String) = "$TASA_ARBITRIO:$claveDeTasa"
}
enum class Base { PREDIO, FRONTIS_ML, AREA_CONSTRUIDA_M2 }
enum class Dimension { ZONA, USO, INFLUENCIA, AFLUENCIA }
data class Redondeo(val decimales: Int, val modo: java.math.RoundingMode) {
    fun aplicar(v: BigDecimal): BigDecimal = v.setScale(decimales, modo)
    override fun toString() = "$decimales:" + MODOS.entries.first { it.value == modo }.key
    companion object {
        val MODOS = mapOf("ABAJO" to RoundingMode.DOWN, "MEDIO" to RoundingMode.HALF_UP, "ARRIBA" to RoundingMode.UP)
        fun de(texto: String?): Redondeo? // "2:ABAJO" -> Redondeo(2, DOWN); null/blank/malformed -> null
    }
}
data class Habitantes(val promedio: BigDecimal, val variacion: BigDecimal)
object Servicios {
    fun base(parametros: List<ParametroTributario>, servicio: String, dia: LocalDate): Base   // default PREDIO
    fun dimensiones(parametros: List<ParametroTributario>, servicio: String, dia: LocalDate): List<Dimension> // default [ZONA, USO]
    fun habitantes(parametros: List<ParametroTributario>, servicio: String, zona: String, uso: String, dia: LocalDate): Habitantes? // both rows or null
    fun redondeo(parametros: List<ParametroTributario>, dia: LocalDate): Redondeo?
}
```
Reglas: cada lector toma la fila de su tipo y clave **en vigor ese día**, la de `vigencia_desde` más reciente (mismo criterio que `Arbitrios.enVigor`; extraer `enVigor` a `internal fun enVigor(...)` en `Servicios.kt` y que `Arbitrios` lo use). Un texto que no es un valor válido (base desconocida, dimensión desconocida) cuenta como **ausente** (el importador ya lo rechaza; aquí no se adivina).

- [ ] **Step 1: tests fallidos** en `ServiciosTest` (helpers `param(tipo, clave, valor?, texto?, desde)` como en `ArbitriosTest`):
  - sin filas: `base == PREDIO`, `dimensiones == [ZONA, USO]`, `habitantes == null`, `redondeo == null`;
  - `BASE_ARBITRIO BARRIDO texto FRONTIS_ML` → `FRONTIS_ML`; una fila 2027 no rige en 2026;
  - `DIMENSIONES_ARBITRIO PARQUES texto "ZONA, INFLUENCIA ,AFLUENCIA"` → `[ZONA, INFLUENCIA, AFLUENCIA]` (recorta espacios);
  - `HABITANTES_PROMEDIO RRSS:01A:2 = 2` y `HABITANTES_VARIACION RRSS:01A:2 = 0.5` → `Habitantes(2, 0.5)`; con solo una de las dos → `null`;
  - `REDONDEO_ARBITRIO "" texto "2:ABAJO"` → `Redondeo(2, DOWN)` y `aplicar(53.3988) == 53.39`; `"4:MEDIO"` → `aplicar(2.59619880) == 2.5962`;
  - `Redondeo.de("dos")`, `Redondeo.de("2:CERCA")` → `null`.
  - En `LlavesTest`: `Llaves.tasa("P", listOf("A","Frente","Baja")) == "P:A:Frente:Baja"`, y el test que compara `Llaves.TIPOS` con `TIPOS_ARBITRIO` de Python ya existente debe seguir pasando (fallará hasta Task 2).
- [ ] **Step 2:** `./gradlew test --tests 'srtm.arbitrios.ServiciosTest'` → FAIL (no compila).
- [ ] **Step 3:** implementar `Llaves` y `Servicios.kt` según la interfaz.
- [ ] **Step 4:** `./gradlew test --tests 'srtm.arbitrios.*'` → PASS salvo `LlavesTest` (Python aún no tiene los tipos).
- [ ] **Step 5:** commit local (sin push): `LlavesTest` quedará verde con la Task 2, que hace el push y el PR.

### Task 2: Validación en `import_parametros.py`

**Files:** Modify `model/import_parametros.py` (constantes :45-49, `_error_arbitrio` :97-118); Test `model/test_import_parametros.py` (`ArbitriosRowsTests` :142-190).

Reglas (mensajes nombran tipo, clave y motivo; exit 2 sin enviar nada):
- `BASE_ARBITRIO`: clave no vacía; texto ∈ {PREDIO, FRONTIS_ML, AREA_CONSTRUIDA_M2}.
- `DIMENSIONES_ARBITRIO`: clave no vacía; texto = lista separada por comas de {ZONA, USO, INFLUENCIA, AFLUENCIA}, sin repetidos, no vacía.
- `TASA_ARBITRIO`: la clave tiene 1 + n partes no vacías, donde n = número de dimensiones de la fila `DIMENSIONES_ARBITRIO` del mismo archivo para ese servicio vigente en la `vigencia_desde` de la tasa, o 2 si el archivo no trae ninguna. Valor ≥ 0 (como hoy).
- `HABITANTES_PROMEDIO` / `HABITANTES_VARIACION`: clave `servicio:zona:uso` (3 partes); valor > 0; cada clave+vigencia_desde de una debe estar también en la otra dentro del archivo.
- `REDONDEO_ARBITRIO`: clave vacía; texto `^\d:(ABAJO|MEDIO|ARRIBA)$`.
- `TIPOS_ARBITRIO` en el mismo orden que `Llaves.TIPOS` (tupla de nombres, formato que parsea `LlavesTest`).

- [ ] **Step 1:** tests fallidos en `ArbitriosRowsTests`, uno por regla (fila válida pasa; cada inválida da el mensaje), más: `TASA_ARBITRIO PARQUES:A:Frente:Baja` pasa con `DIMENSIONES_ARBITRIO PARQUES "ZONA,INFLUENCIA,AFLUENCIA"` y falla sin ella («la clave de una tasa de PARQUES tiene 3 partes después del servicio»); `HABITANTES_PROMEDIO` sin su `VARIACION` falla.
- [ ] **Step 2:** `cd model && python3 -m unittest test_import_parametros -v` → FAIL.
- [ ] **Step 3:** implementar (la regla de tasas necesita ver todo el archivo: calcular las dimensiones por servicio antes del bucle de `errores`).
- [ ] **Step 4:** `python3 -m unittest -v` y `./gradlew ktlintCheck test --tests 'srtm.arbitrios.*'` → PASS.
- [ ] **Step 5: README** § Arbitrios «Parámetros» (:331-342): tabla con los 5 tipos nuevos, cada uno con un ejemplo de Miraflores 2026 (BASE RRSS = AREA_CONSTRUIDA_M2; DIMENSIONES PARQUES = ZONA,INFLUENCIA,AFLUENCIA; HABITANTES P=2, V=0.5; REDONDEO 2:ABAJO) y la nota «sin estas filas el cálculo es el de siempre».
- [ ] **Step 6: Commit y PR**
```bash
git add -A && git commit -m "feat(arbitrios): parámetros por servicio: base, dimensiones, habitantes y redondeo" # cuerpo ≤100/línea + trailers
git push -u origin feat/arbitrios-parametros-servicio
gh pr create --base main --title "feat(arbitrios): parámetros por servicio (base, dimensiones, habitantes y redondeo)" --body "Cierra #81. Parte de #80. …"
```
El plan (este archivo) va en este primer PR.

---

## PR 2 — #82 Zona por servicio y por ubicación (rama `feat/arbitrios-zona-ubicacion`, base PR 1)

### Task 3: Resolución pura de la zona

**Files:** Create `src/main/kotlin/srtm/arbitrios/Zonas.kt`; Modify `Modelo.kt` (DTO), `Arbitrios.kt` (`ContextoArbitrios.ubicaciones`, uso por servicio); Test `ZonasTest.kt`, `ArbitriosTest.kt`.

**Interfaces:**
```kotlin
// Modelo.kt
const val ZONA_ARBITRIO_UBICACION = "zona_arbitrio_ubicacion"
@JsonNaming(SnakeCaseStrategy::class)
data class ZonaUbicacion(val id: String? = null, val servicio: String? = null, val tipoVia: String? = null,
    val via: String? = null, val manzana: String? = null, val numeroInicial: Int? = null, val numeroFinal: Int? = null,
    val paridad: String? = null, val zona: String? = null, val vigenciaDesde: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null, val norma: String? = null, val observacion: String? = null, val clave: String? = null)
fun claveDeZonaUbicacion(z: ZonaUbicacion): String // servicio|tipo_via|via|manzana|ini|fin|paridad|vigencia_desde, vacíos como ""
// Zonas.kt
sealed interface ZonaResuelta { data class Es(val zona: String, val fuente: String) : ZonaResuelta
    data class Ambigua(val zonas: List<String>) : ZonaResuelta; data object Ninguna : ZonaResuelta }
object Zonas {
    fun numeroDe(numero: String?): Int? // dígitos iniciales: "245-A" -> 245, "S/N" -> null
    fun de(ubicaciones: List<ZonaUbicacion>, parametros: List<ParametroTributario>, servicio: String, predio: Predio, dia: LocalDate): ZonaResuelta
}
// Arbitrios.kt
data class ContextoArbitrios(…, val ubicaciones: List<ZonaUbicacion> = emptyList())
```
Orden (de mayor a menor precisión; dentro de un nivel, varias filas con la misma zona = esa zona; con distinta = `Ambigua`):
1. ubicación con `servicio == código` y `manzana` igual a la del predio;
2. ubicación con ese servicio sin manzana;
3. ubicación sin servicio con manzana; 4. sin servicio sin manzana;
5. `ARBITRIO_ZONA` clave `servicio:sector`; 6. `ARBITRIO_ZONA` clave `sector` (hoy).
Una fila de ubicación coincide si: vigente ese día; `via` igual (trim, sin distinguir mayúsculas ni tildes: normalizar con `java.text.Normalizer` NFD sin diacríticos) a `predio.via`; `tipoVia` nula o igual a `predio.tipoVia`; `numeroDe(predio.numero)` en [ini, fin]; paridad AMBOS o la del número. `fuente` = la llave leída (`"ZONA_ARBITRIO_UBICACION:<clave>"` o `"ARBITRIO_ZONA:<clave>"`).

En `Arbitrios.determinar`: la zona deja de calcularse una vez por mes y pasa a resolverse **por servicio** (dentro del bucle de servicios). `Ninguna` con sector → `faltan` `"${ARBITRIO_ZONA} $sector $anio"` (mismo texto de hoy); `Ninguna` sin sector → `sinRasgos` `"Sector catastral del predio $nombre"` (hoy); `Ambigua` → `faltan` `"Zona ambigua de $servicio para ${predio.via} ${predio.numero}: ${zonas.joinToString()}"`. El sector deja de ser requisito si la ubicación resuelve.

- [ ] **Step 1:** tests fallidos en `ZonasTest`: los 6 niveles (uno gana al siguiente); Av. X 245 con PAR 200-298 Zona 2 + IMPAR 201-299 Zona 3 → Zona 3; «245-A» → 245; «S/N» cae al sector; tildes («Avenida Perú» = «AVENIDA PERU»); fila de 2027 no rige; dos filas del mismo nivel con zonas distintas → `Ambigua`. En `ArbitriosTest`: dos servicios con zonas distintas por ubicación → cada cuota con su `zona`; sin ubicaciones el test existente da lo mismo.
- [ ] **Step 2–4:** FAIL → implementar → `./gradlew test --tests 'srtm.arbitrios.*'` PASS.

### Task 4: Objeto en el modelo y carga en el servicio

**Files:** `model/model.json` (objeto nuevo **al final** de `objects`, enum `paridad_numeracion: ["PAR","IMPAR","AMBOS"]`), `model/test_model.py` (conteo de objetos 39→40 :101; `names[29:]` → `names[29:39]` en :237 y :273; test nuevo del objeto), `model/test_apply.py` (`OBJECTS = 40`, «81» → «82» en :144/:163/:415), `model/apply.py` docstring :7-8, README :78-79; `ArbitriosService.contexto` (:76) lee `ZONA_ARBITRIO_UBICACION` (todas) y las pasa al contexto; `CuotasInmutables`/regla de escritura: no aplica (objeto editable por la API genérica, como `parametro_tributario`).

Campos: `servicio` TEXT; `tipo_via` TEXT; `via` TEXT required; `manzana` TEXT; `numero_inicial` INTEGER required; `numero_final` INTEGER required; `paridad` ENUM `paridad_numeracion` required; `zona` TEXT required; `vigencia_desde` DATE required; `vigencia_hasta` DATE; `norma` TEXT; `observacion` TEXT; `clave` TEXT unique required. Sin relaciones.

- [ ] **Step 1:** test fallido en `test_model.py` (campos, requeridos, único {clave}, enum) y en `ArbitriosApiTest` (integración: crear 2 filas de ubicación por la API genérica y ver que la cuota de cada servicio toma su zona).
- [ ] **Step 2–4:** FAIL → implementar → `python3 apply.py --validate-only && python3 -m unittest -v` y `./gradlew test` PASS.

### Task 5: `import_zonas_arbitrio.py`

**Files:** Create `model/import_zonas_arbitrio.py`, `model/test_import_zonas_arbitrio.py` (sobre `fake_core.py`).

CLI y comportamiento calcados de `import_arbitrios.py`: `--csv` (cabecera `#` con la fuente; columnas `servicio,tipo_via,via,manzana,numero_inicial,numero_final,paridad,zona,vigencia_desde,vigencia_hasta,norma,observacion`), `--core/--email/--password`, `--dry-run`; clave natural = `claveDeZonaUbicacion` (misma fórmula en Python, `clave()`); crea lo que falta, actualiza en su lugar lo que cambió, salta lo igual, no borra; salida 0/1/2; imprime `zona_arbitrio_ubicacion: N created, M updated, K skipped`.
Errores de archivo (salida 2, nada enviado): requeridos; enteros; `numero_inicial > numero_final`; paridad fuera del enum (leer el enum de `model.json`); fecha ISO; dos filas del mismo servicio, vía, manzana y paridad cuyos rangos se solapan con distinta zona y vigencias que se cruzan.

- [ ] **Step 1:** tests fallidos: carga, segunda corrida 0 escrituras, dry-run, cada error, salida 1 con rechazo de Core.
- [ ] **Step 2–4:** FAIL → implementar → PASS.
- [ ] **Step 5:** README § Arbitrios: «Zona por servicio y por ubicación» (orden de resolución, CSV, comando). Commit, push, PR `--base feat/arbitrios-parametros-servicio`, «Cierra #82».

---

## PR 3 — #83 Parques por influencia y afluencia (rama `feat/arbitrios-influencia-afluencia`, base PR 2)

### Task 6: Dimensiones y clave de tasa por dimensiones

**Files:** Create `src/main/kotlin/srtm/arbitrios/Dimensiones.kt`; Modify `Llaves.kt` (`ARBITRIO_INFLUENCIA`, `ARBITRIO_AFLUENCIA` al final de `TIPOS`), `Arbitrios.kt`; `model/import_parametros.py` (+tests); Test `DimensionesTest.kt`, `ArbitriosTest.kt`.

**Interfaces:**
```kotlin
sealed interface ValorDimension { data class Es(val valor: String) : ValorDimension; data class Falta(val mensaje: String) : ValorDimension }
object Dimensiones {
    fun influencia(parametros: List<ParametroTributario>, predio: Predio, nombre: String, anio: Int, dia: LocalDate): ValorDimension
    fun afluencia(parametros: List<ParametroTributario>, servicio: String, codigoUso: String, anio: Int, dia: LocalDate): ValorDimension
    // valores de las dimensiones del servicio, en su orden; zona y uso ya resueltos
    fun valores(dimensiones: List<Dimension>, zona: String, uso: String, influencia: () -> ValorDimension, afluencia: () -> ValorDimension): Pair<List<String>, List<String>> // (valores, faltan)
}
```
- `influencia`: `predio.ubicacionAreaVerde` nula → `Falta("Ubicación respecto del área verde del predio $nombre")`; sin `ARBITRIO_INFLUENCIA` con esa clave vigente → `Falta("ARBITRIO_INFLUENCIA ${predio.ubicacionAreaVerde} $anio")`; si no `Es(texto)`.
- `afluencia`: `ARBITRIO_AFLUENCIA` con clave `servicio:prefijo` (gana) o `prefijo`, prefijo más largo de `codigoUso` (como `usoDe`); sin fila → `Falta("ARBITRIO_AFLUENCIA $codigoUso $anio")`.
- En `Arbitrios.determinar`: la clave de tasa pasa a `Llaves.tasa(servicio, valores)` con las dimensiones de `Servicios.dimensiones`; sin `DIMENSIONES_ARBITRIO` es `[ZONA, USO]` → misma clave que hoy. Una dimensión que falta → `faltan`.
- Python: `ARBITRIO_INFLUENCIA` clave ∈ enum `ubicacion_area_verde` de `model.json`, texto no vacío; `ARBITRIO_AFLUENCIA` clave `prefijo` o `servicio:prefijo` con prefijo `\d{2}|\d{4}|\d{6}`, texto no vacío.

- [ ] **Step 1:** tests fallidos: Miraflores parques (DIMENSIONES PARQUES = ZONA,INFLUENCIA,AFLUENCIA; ZONA por `ARBITRIO_ZONA PARQUES:S-01 → A`; INFLUENCIA «FRENTE A PARQUE» → «Frente a Parque y/o Área Verde»; AFLUENCIA prefijo del uso → «Baja»; tasa `PARQUES:A:Frente a Parque y/o Área Verde:Baja = 85.9875`) → cuota 85.9875; zona D + «OTRAS UBICACIONES» + «Muy alta» → 61.0535 (citar Ord. 665/MM Cuadro 20 en el comentario del test); ejemplo M02 (periferia/parque/baja → 150.30); predio sin ubicación de área verde → `faltan` y ninguna cuota; servicios sin DIMENSIONES siguen con su clave de hoy. Python: reglas de los 2 tipos.
- [ ] **Step 2–4:** FAIL → implementar → PASS.
- [ ] **Step 5:** README; commit, push, PR base PR 2, «Cierra #83».

---

## PR 4 — #84 Monto con base, habitantes, redondeo, un PU por uso y desglose (rama `feat/arbitrios-calculo-base`, base PR 3)

### Task 7: Cálculo puro

**Files:** Create `src/main/kotlin/srtm/arbitrios/Calculo.kt`; Test `CalculoTest.kt`.

```kotlin
data class Desglose(val base: Base, val cantidadBase: BigDecimal, val tasaUnitaria: BigDecimal,
    val habitantes: Int?, val habitantesPresuntos: Boolean, val promedioHabitantes: BigDecimal?,
    val variacionHabitante: BigDecimal?, val redondeo: Redondeo?, val monto: BigDecimal)
sealed interface Calculado { data class Es(val desglose: Desglose) : Calculado; data class Falta(val mensaje: String) : Calculado }
object Calculo {
    // cantidad: PREDIO -> 1; FRONTIS_ML -> d.longitudFrente; AREA_CONSTRUIDA_M2 -> d.areaConstruida (null o <= 0 -> Falta)
    fun calcular(base: Base, tasa: BigDecimal, d: Declaracion, habitantes: Habitantes?, redondeo: Redondeo?, quien: String): Calculado
}
```
Fórmula: `monto = tasa × cantidad × factor`, `factor = 1 + (H − P) × V` si `habitantes != null` (H = `d.numeroHabitantes` o, si es nulo, P con `habitantesPresuntos = true`), si no 1. Factor < 0 → `Falta("Factor de habitantes negativo en $quien: 1 + (H − P) × V = …")`. Redondeo al final; sin redondeo, `monto` sin escala forzada (`stripTrailingZeros()` no: dejar el BigDecimal tal cual del producto) — con base PREDIO y sin habitantes `monto == tasa` exactamente (compatibilidad). Mensajes de falta: `"Frontis del predio $quien"`, `"Área construida del predio $quien"` donde `quien` = `"$codigo, uso $secuencia, declaración $anio"`.

- [ ] **Step 1:** tests fallidos: M02 barrido 3.37 × 12 = 40.44; M02 recolección 0.12 × 180 × (1+(5−1)×1) = 108.00; Miraflores casa 01A: 0.0514 × 100 × (1+(4−2)×0.5) = 10.28 con `2:ABAJO`; barrido Zona 2: 5.0856 × 10.5 = 53.39 con `2:ABAJO`; H nulo → factor 1 y presuntos; H = 0 con P=2, V=0.5 → monto 0; P=2, V=0.8, H=0 → Falta factor negativo; frontis nulo → Falta; PREDIO sin habitantes → monto == tasa (mismo objeto de escala).
- [ ] **Step 2–4:** FAIL → implementar → PASS.

### Task 8: Un PU por uso en la determinación

**Files:** Modify `Arbitrios.kt` (bucle), `Modelo.kt` (`CuotaArbitrio` campos nuevos, `claveDeCuota`, `invariantes`, `PARAMETRO_APLICADO_MAXIMO = 500`), `Titulares.kt` (función nueva); `model/model.json` (campos de `cuota_arbitrio` + enum `base_arbitrio`), `model/test_model.py` (:183-206 lista exacta de campos); tests `ArbitriosTest`, `CuotaArbitrioTest`, `TitularesTest`, `ArbitriosApiTest` (:120), `AnulacionApiTest` (:30).

```kotlin
// Modelo.kt — campos nuevos de CuotaArbitrio (todos opcionales, después de usoArbitrio):
val secuenciaUso: String? = null, val base: String? = null, val cantidadBase: BigDecimal? = null,
val tasaUnitaria: BigDecimal? = null, val habitantes: Int? = null, val habitantesPresuntos: Boolean? = null,
val promedioHabitantes: BigDecimal? = null, val variacionHabitante: BigDecimal? = null,
val influencia: String? = null, val afluencia: String? = null, val redondeo: String? = null
fun claveDeCuota(predio: String, servicio: String, anio: Int, periodo: Int, secuencia: String, version: Int = 1) =
    "$predio|$servicio|$anio|$periodo|$secuencia|$version"
// Titulares.kt
fun porSecuencia(declaraciones: List<Declaracion>, codigos: Map<String, String?>): List<Declaracion> // la del titular principal de cada secuencia, ordenadas por secuencia
```
- `secuencia` = `secuenciaUso(d.secuenciaUso)` (normalizada a 3 dígitos con `rentas.Reglas.secuenciaUso`).
- Bucle: por mes → `titular` = `titularPrincipal(todas las que cubren)` (contribuyente de todas las cuotas) → por cada `porSecuencia(...)` → `codigoUso` de esa declaración → por servicio: zona (PR2), dimensiones (PR3), tasa, `Calculo.calcular(base, tasa, d, habitantes(servicio, zona, uso), redondeo, quien)`.
- «Ya determinada» y versión: por (servicio, anio, periodo, **secuencia**) — `vigentes` y `existentes` filtran también por `secuenciaUso` (una cuota antigua sin `secuencia_uso` cuenta como la secuencia menor del predio).
- `parametro_aplicado` = llaves leídas separadas por `;`: siempre `TASA_ARBITRIO:<clave>`; además, solo si existen, `BASE_ARBITRIO:<servicio>`, `HABITANTES_PROMEDIO:<clave>`, `HABITANTES_VARIACION:<clave>`, `REDONDEO_ARBITRIO`. Sin filas nuevas queda idéntico a hoy.
- `invariantes`: clave de 6 partes (mensaje «es predio|servicio|anio|periodo|secuencia|version»); si `base` no es nula: `cantidad_base > 0`, `tasa_unitaria ≥ 0`, y `monto` == recalcular con `Redondeo.de(redondeo)` (factor con H, P, V si están).
- `model.json` `cuota_arbitrio`: campos en el orden de arriba (`secuencia_uso` TEXT, `base` ENUM `base_arbitrio`, `cantidad_base` DECIMAL, `tasa_unitaria` DECIMAL, `habitantes` INTEGER, `habitantes_presuntos` BOOLEAN, `promedio_habitantes` DECIMAL, `variacion_habitante` DECIMAL, `influencia` TEXT, `afluencia` TEXT, `redondeo` TEXT); descripciones de `monto` y `clave` actualizadas.
- `Determinacion` gana `detalle: List<Falta> = emptyList()` con `data class Falta(val mensaje: String, val predio: String? = null, val declaracion: String? = null)`; `faltan` = mensajes (el UI #94 usa el detalle; PR 5 lo expone).

- [ ] **Step 1:** tests fallidos (ArbitriosTest): predio de un uso sin parámetros nuevos → mismas cuotas que hoy salvo clave `predio-100|srv-LIMPIEZA|2026|1|001|1`; predio con secuencias 001 (casa, 120 m²) y 002 (comercio, 40 m²) y base AREA → 2 cuotas por servicio y mes con su uso, base y tasa; secuencia 002 sin área → todo el predio falla con «Área construida del predio 01-01-0001, uso 002, declaración 2026»; dos condóminos en la secuencia 001 → datos de la DJ del principal de esa secuencia; anular una cuota de la secuencia 002 y redeterminar → solo `…|002|2`; ejemplo M02 completo con sus filas → 4 cuotas que suman 305.35. CuotaArbitrioTest: invariantes de clave de 6 partes y de desglose.
- [ ] **Step 2–4:** FAIL → implementar → `./gradlew ktlintCheck test` y `cd model && python3 -m unittest -v` PASS. Ajustar `ArbitriosApiTest:120` y `AnulacionApiTest:30` a la clave nueva (las cantidades 24/72/153.00 no cambian: un uso por predio).

### Task 9: Matriz y HLA con varias secuencias

**Files:** `Consultas.kt` (:105, `CuotaMes`), `DocumentosArbitrios.kt` (:61-64), tests `ConsultasTest`, `HojaHlaTest`.
- La celda (servicio, mes) suma las cuotas vigentes de todas sus secuencias (`suma` con HALF_UP a 2 como hoy); `CuotaMes.parametroAplicado` = los de cada cuota unidos por «; »; `CuotaMes.id` = el de la primera (por secuencia).
- `zonasYUsos` agrega por predio las zonas y usos distintos de todas sus cuotas (ya lo hace; verificar con dos secuencias).
- [ ] **Step 1–4:** test con dos secuencias (8.50 + 4.25 en el mismo mes → 12.75) FAIL → implementar → PASS.
- [ ] **Step 5:** README § Arbitrios «Cálculo» (:368-389): reemplazar «tal cual (sin área, prorrateo ni redondeo)» por la fórmula, un PU por uso, la clave de 6 partes, y la sección Anulación (:462-473) con `…|001|2`. Commit, push, PR base PR 3, «Cierra #84».

---

## PR 5 — #85 Desglose en la API, el PU y la HLA (rama `feat/arbitrios-desglose`, base PR 4)

### Task 10: Fórmula legible y API

**Files:** `Calculo.kt` (`fun formula(c: CuotaArbitrio): String`), `Consultas.kt` (`CuotaMes.desglose: List<DesgloseCuota>`), `ArbitriosController`/problem 422 (`faltan_detalle`), tests `CalculoTest`, `ConsultasTest`, `ArbitriosApiTest`.
```kotlin
@JsonNaming(SnakeCaseStrategy::class)
data class DesgloseCuota(val id: String?, val secuenciaUso: String?, val monto: BigDecimal?, val base: String?,
    val cantidadBase: BigDecimal?, val tasaUnitaria: BigDecimal?, val habitantes: Int?, val promedioHabitantes: BigDecimal?,
    val variacionHabitante: BigDecimal?, val habitantesPresuntos: Boolean?, val zona: String?, val usoArbitrio: String?,
    val influencia: String?, val afluencia: String?, val formula: String)
```
`formula`: «5.0856 S/ por ml × 10.50 ml = 53.39»; «0.0514 S/ por m² × 100.00 m² × (1 + (4 − 2) × 0.50) = 10.28»; base PREDIO: «85.9875 S/ por predio = 85.99» si hay redondeo o «85.9875 S/ por predio» si no; cuota sin `base` (antigua) → `soles(monto)`. Cantidades con 2 decimales, tasas con los decimales de la norma. `MatrizArbitrios.faltanDetalle` y el problem 422 de determinar llevan `faltan_detalle: [{mensaje, predio, declaracion}]`.
- [ ] **Step 1–4:** tests de cada fórmula (M02 y Miraflores) y del JSON → FAIL → implementar → PASS.

### Task 11: HLA

**Files:** `HojaHla.kt` (`PredioHla.calculos: List<String>`), `templates/emision/hla.html`, `HojaHlaTest`, `GeneradorHlaTest`.
- Por predio, una línea por (secuencia, servicio, fórmula) distinta del año, con el rango de meses: «Recolección (uso 001), ene–dic: 0.0514 S/ por m² × 100.00 m² × (1 + (4 − 2) × 0.50) = 10.28».
- Cuotas sin desglose: no hay líneas (la HLA se ve como hoy).
- [ ] **Step 1–4:** test con y sin desglose → FAIL → implementar → PASS (incluido el render PDF de `HlaApiTest` en CI).
- [ ] **Step 5:** README § HLA y API. Commit, push, PR base PR 4, «Cierra #85».

---

## PR 6 — #86 Importador de la tabla de tasas (rama `feat/arbitrios-import-tasas`, base PR 5)

### Task 12: `import_tasas_arbitrio.py`

**Files:** Create `model/import_tasas_arbitrio.py`, `model/test_import_tasas_arbitrio.py`, `model/data/arbitrios/ficticio-tasas.csv`, `ficticio-usos.csv`, `ficticio-influencias.csv` (cifras propias, año 2041, cabecera `#` que dice que son ficticias).

CLI: `python3 import_tasas_arbitrio.py --tasas <csv> --servicios BARRIDO_CALLES=BAR,RECOLECCION_RESIDUOS=RRS,PARQUES_JARDINES=PYJ,SERENAZGO=SER --usos <csv prefijo,uso> --influencias <csv enum,etiqueta> --vigencia-desde AAAA-MM-DD --norma "…" --fuente "…" --transcribio "X, fecha" --verifico "Y, fecha" [--redondeo 2:ABAJO] [--habitantes SERVICIO=P:V] --salida parametros.csv`.
Entrada: el CSV largo del formato de `arbitrios-miraflores-2026.csv` (`servicio, zona, uso_codigo, uso, ubicacion, nivel_afluencia, base, tasa_mensual`, con `#`).
Salida (CSV de `import_parametros.py`): por servicio `BASE_ARBITRIO` (de «S/ por ml…» → FRONTIS_ML, «m2» → AREA_CONSTRUIDA_M2, «predio» → PREDIO) y `DIMENSIONES_ARBITRIO` (ZONA si hay zona; USO si hay uso_codigo; INFLUENCIA si hay ubicación; AFLUENCIA si hay nivel); una `TASA_ARBITRIO` por fila (`servicio:zona:uso_codigo` o `servicio:zona:ubicacion:nivel`); `ARBITRIO_USO` desde `--usos`; `ARBITRIO_INFLUENCIA` desde `--influencias`; `HABITANTES_*` desde `--habitantes` (para cada `servicio:zona:uso` de casa habitación presente); `REDONDEO_ARBITRIO`. Sale con 2 (nada escrito) si: tasa repetida, base desconocida, uso de una tasa sin mapeo en `--usos`, ubicación sin mapeo en `--influencias`; el CSV generado pasa `import_parametros.errores` (se llama dentro, antes de escribir).
- [ ] **Step 1:** tests con el fixture ficticio: cantidades por tipo, idempotencia de la salida, cada error → exit 2; y un test **opcional** que, si existe `$SRTM_MIRAFLORES_CSV` apuntando al CSV de Miraflores (comentario de #86), comprueba 246 `TASA_ARBITRIO` (3/68/64/111) — se salta si la variable no está (el CSV real se versiona al cargar Miraflores).
- [ ] **Step 2:** test de punta a punta sobre `fake_core.py`: generar → `import_parametros.py --csv` → `import_arbitrios.py` (ordenanza y servicios ficticios) — y en Kotlin `ArbitriosTest` con las mismas filas, un predio por base da los montos esperados.
- [ ] **Step 3–4:** FAIL → implementar → PASS.
- [ ] **Step 5:** README; commit, push, PR base PR 5, «Cierra #86».

---

## PR 7 — #87 Tope de incremento (rama `feat/arbitrios-tope`, base PR 6)

### Task 13: Tope

**Files:** `Llaves.kt` (`TOPE_ARBITRIO`), `Servicios.kt` (`fun tope(parametros, codigoUso, dia): BigDecimal?` prefijo más largo o `*`), `Arbitrios.kt` (post-proceso por predio), `PredioArbitrios.anteriores: List<CuotaArbitrio> = emptyList()` (cuotas vigentes del año anterior), `ArbitriosService.delPredio` (las lee), `Modelo.kt`/`model.json` (`monto_sin_tope` DECIMAL, `tope_aplicado` DECIMAL), `import_parametros.py` (`TOPE_ARBITRIO`: clave prefijo o `*`, valor > 0, texto vacío o fecha ISO), tests.
Regla: grupo = uso de la secuencia menor del predio. Si `anteriores` está vacío (no pagó el año anterior) o la fecha de corte (`texto`) es anterior a la primera `fechaPresentacion` de sus declaraciones del año → sin tope. Si no: `límite = suma(anteriores) × (1 + tope/100)`; `año = suma(vigentes de este año) + suma(nuevas)`; si `año > límite`, las nuevas se escalan por `(límite − suma(vigentes)) / suma(nuevas)` (mínimo 0), cada una redondeada con el redondeo del año (o 2:ABAJO si no hay) y la diferencia de redondeo a la última; cada nueva guarda `monto_sin_tope` y `tope_aplicado`.
- [ ] **Step 1–4:** tests: 1 000.00 → 1 200.00 con 8 % → 1 080.00 exactos; sin cuotas el año anterior → sin tope; determinación parcial (6 meses ya escritos) → solo bajan las nuevas y nunca < 0; sin `TOPE_ARBITRIO` nada cambia. Invariante: si `tope_aplicado` no es nulo, `monto ≤ monto_sin_tope`.
- [ ] **Step 5:** README; commit, push, PR base PR 6, «Cierra #87».

---

## PR 8 — #88 Tasas derivadas por IPC (rama `feat/arbitrios-ipc`, base PR 7)

### Task 14: `derivar_ipc_arbitrio.py`

**Files:** Create `model/derivar_ipc_arbitrio.py`, `model/test_derivar_ipc_arbitrio.py`.
CLI: `--csv <parametros del año base> --desde <anio> --ipc <porcentaje> --norma "…" --redondeo <d:MODO> --transcribio … --verifico … --salida <csv>`. Toma las filas de `TIPOS_ARBITRIO` vigentes al 31/12 del año base; las `TASA_ARBITRIO` salen × (1 + ipc/100) redondeadas con `--redondeo`, con `vigencia_desde = 01/01` del año siguiente, `texto = "DERIVADA IPC <ipc>% de <clave> <anio>"` y `norma` la del reajuste; las demás (base, dimensiones, mapeos, habitantes, redondeo, tope, vencimientos con la fecha corrida un año) se copian con la vigencia nueva. Sin `--redondeo` → exit 2. La salida pasa `import_parametros.errores`.
- [ ] **Step 1–4:** tests: 2.5428 con 2.10 % y 4:MEDIO → 2.5962; cada fila cita la base; vencimiento 2026-02-28 → 2027-02-28; exit 2 sin redondeo → FAIL → implementar → PASS.
- [ ] **Step 5:** README; commit, push, PR base PR 7, «Cierra #88».

---

## PR 9 — srtm-ui#94 Desglose y faltan en el portal (repo `srtm-ui`, rama `feat/arbitrios-desglose`, base `dev`)

### Task 15: Tipos y desglose

**Files:** `src/portal/types.ts` (`CuotaMes.desglose?: DesgloseCuota[]`, `CuotaArbitrio` campos nuevos, `MatrizArbitrios.faltan_detalle?: Falta[]`), `src/portal/pages/Arbitrios.tsx` (`Matriz` :75-128: la celda muestra el monto y, al pasar o al tocar, un popover con una línea `formula` por secuencia; si `desglose` vacío o ausente, como hoy), `ArbitriosPages.tsx` (:97 igual); test `src/portal/arbitrios.test.tsx`.
- [ ] **Step 1–4:** test con una celda de dos secuencias (ve las dos fórmulas) y una antigua (ve solo el monto) → FAIL → implementar → `yarn lint && yarn typecheck && yarn test` PASS.

### Task 16: Faltan con enlace

**Files:** `Arbitrios.tsx` `Situacion` (:137-154), `components/DialogoDeActo.tsx` (:47-54, `RentasError` lee `faltan_detalle` en `api.ts` :104-143); test `arbitrios.test.tsx`, `determinarArbitrios.test.tsx`.
- Cada falta con `declaracion` enlaza a `/declaraciones/:id?tab=caracteristicas`; con `predio` a `/predios/:id?tab=ubicacion`; sin ninguno, texto como hoy.
- [ ] **Step 1–4:** FAIL → implementar → PASS.

### Task 17: DJ exige frontis y área cuando la ordenanza cobra por esa base

**Files:** `src/portal/forms/declaracionSpecs.tsx` (`CARACTERISTICAS_SECTIONS` :218-247 → `caracteristicasSections(bases: Set<string>)`), `pages/DeclaracionPage.tsx` (:129, :296, `siguientePendiente` :68-74 usan las mismas secciones), `queries.ts` (lee `parametrosArbitrio(anio)` y deriva las bases vigentes de `BASE_ARBITRIO`), `predio`: `ubicacion_area_verde` obligatoria si algún servicio usa INFLUENCIA (`ubicacionSections`, :135-215); tests `djCompleta.test.tsx`, `grisObligatorio.test.tsx`.
- `longitud_frente` requerido si hay base FRONTIS_ML; `area_construida` si hay AREA_CONSTRUIDA_M2; sin esas filas, como hoy (opcionales).
- [ ] **Step 1–4:** FAIL → implementar → PASS. De paso, quitar el `<SubnavArbitrios />` duplicado de `TasasArbitriosPage` (:134/:145).
- [ ] **Step 5:** commit, push, PR `--base dev`, «Cierra wasichai/srtm-ui#94. Parte de wasichai/srtm-backend#80».

---

## Cierre

- [ ] Revisión de toda la rama apilada (superpowers:requesting-code-review) antes de declarar terminado.
- [ ] Comentar en #80 el estado de cada PR y el CI.
- [ ] Actualizar la memoria `arbitrios-modulo`.
