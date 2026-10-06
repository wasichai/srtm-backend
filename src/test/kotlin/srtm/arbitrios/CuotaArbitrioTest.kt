package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.Observacion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ValidationException
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

// rentas' CuotaDeArbitrioTest and the CHECKs of determinacion_arbitrio, the period of atribución and the observación
class CuotaArbitrioTest {
    private val cuota =
        CuotaArbitrio(
            predio = "p",
            contribuyente = "c",
            servicio = "s",
            parametro = "t",
            anio = 2026,
            periodo = 1,
            monto = BigDecimal("10"),
            parametroAplicado = "TASA_ARBITRIO:LIMPIEZA:Z1:CASA",
            secuenciaUso = "001",
            fechaCalculo = LocalDate.of(2026, 3, 1),
            observacion = "Se determina para la prueba",
            clave = "p|s|2026|1|001|1"
        )

    private fun campos(c: CuotaArbitrio) = invariantes(c).map { it.field }

    @Test
    fun `a well formed cuota passes`() {
        assertEquals(emptyList<String>(), campos(cuota))
        assertEquals(emptyList<String>(), campos(cuota.copy(monto = BigDecimal.ZERO, clave = "p|s|2026|1|001|2")))
    }

    @Test
    fun `the periodo goes from 1 to 12 - monthly arbitrios`() {
        assertEquals(listOf("periodo", "clave"), campos(cuota.copy(periodo = 0)))
        assertEquals(listOf("periodo", "clave"), campos(cuota.copy(periodo = 13)))
    }

    @Test
    fun `the monto cannot be negative`() {
        assertEquals(listOf("monto"), campos(cuota.copy(monto = BigDecimal("-10"))))
    }

    @Test
    fun `without the keys of the parameters applied it is not written - up to 500 characters, several keys`() {
        assertEquals(listOf("parametro_aplicado"), campos(cuota.copy(parametroAplicado = "  ")))
        assertEquals(listOf("parametro_aplicado"), campos(cuota.copy(parametroAplicado = "X".repeat(501))))
        assertEquals(emptyList<String>(), campos(cuota.copy(parametroAplicado = "X".repeat(500))))
    }

    @Test
    fun `every relation, the year and the date of calculation are required`() {
        val vacia = CuotaArbitrio(periodo = 1, monto = BigDecimal.ONE, parametroAplicado = "x", observacion = "una razón")
        assertEquals(listOf("predio", "contribuyente", "servicio", "parametro", "anio", "fecha_calculo"), campos(vacia))
    }

    @Test
    fun `the clave is its predio, servicio, year, month, secuencia de uso and version`() {
        assertEquals(listOf("clave"), campos(cuota.copy(clave = "p|s|2026|2|001|1")))
        assertEquals(listOf("clave"), campos(cuota.copy(clave = "p|s|2026|1|001|0")))
        assertEquals(listOf("clave"), campos(cuota.copy(clave = "p|s|2026|1|001|01")))
        assertEquals(listOf("clave"), campos(cuota.copy(clave = null)))
        // the 5 parts of before the PU por uso are not a new cuota's clave
        assertEquals(listOf("clave"), campos(cuota.copy(clave = "p|s|2026|1|1")))
        // its secuencia is the cuota's own
        assertEquals(listOf("clave"), campos(cuota.copy(clave = "p|s|2026|1|002|1")))
        assertEquals(listOf("clave"), campos(cuota.copy(secuenciaUso = null)))
        assertEquals(emptyList<String>(), campos(cuota.copy(secuenciaUso = "002", clave = "p|s|2026|1|002|3")))
        assertEquals("p|s|2026|1|002|3", claveDeCuota("p", "s", 2026, 1, "002", 3))
    }

    // the two cases with a source (global-constraints.md): Miraflores 2026, Ord. 665/MM - barrido 5.0856 S/ por ml,
    // recolección 0.0514 S/ por m² with P = 2 and V = 0.5, redondeo 2:ABAJO
    private val barrido =
        cuota.copy(
            monto = BigDecimal("53.39"),
            base = "FRONTIS_ML",
            cantidadBase = BigDecimal("10.5"),
            tasaUnitaria = BigDecimal("5.0856"),
            redondeo = "2:ABAJO"
        )
    private val recoleccion =
        cuota.copy(
            monto = BigDecimal("10.28"),
            base = "AREA_CONSTRUIDA_M2",
            cantidadBase = BigDecimal("100"),
            tasaUnitaria = BigDecimal("0.0514"),
            habitantes = 4,
            habitantesPresuntos = false,
            promedioHabitantes = BigDecimal("2"),
            variacionHabitante = BigDecimal("0.5"),
            redondeo = "2:ABAJO"
        )

    @Test
    fun `the monto squares with its desglose - tasa x cantidad x factor de habitantes, rounded`() {
        assertEquals(emptyList<String>(), campos(barrido))
        assertEquals(emptyList<String>(), campos(barrido.copy(monto = BigDecimal("53.390")))) // the same amount
        assertEquals(listOf("monto"), campos(barrido.copy(monto = BigDecimal("53.40"))))
        assertEquals(listOf("monto"), campos(barrido.copy(redondeo = null))) // 53.3988 unrounded
        assertEquals(emptyList<String>(), campos(barrido.copy(redondeo = null, monto = BigDecimal("53.3988"))))
        assertEquals(emptyList<String>(), campos(recoleccion))
        assertEquals(listOf("monto"), campos(recoleccion.copy(habitantes = 5)))
        // H not declared: P is presumed, the factor is 1
        assertEquals(emptyList<String>(), campos(recoleccion.copy(habitantes = null, habitantesPresuntos = true, monto = BigDecimal("5.14"))))
        // a cuota of base PREDIO is its tasa, as is
        assertEquals(emptyList<String>(), campos(cuota.copy(base = "PREDIO", cantidadBase = BigDecimal.ONE, tasaUnitaria = BigDecimal("10"))))
    }

    @Test
    fun `a cuota with a tope squares its monto_sin_tope with the desglose, and its monto never goes above it`() {
        // fictitious 8 %: barrido's 53.39 lowered to 48.05
        val topada = barrido.copy(monto = BigDecimal("48.05"), montoSinTope = BigDecimal("53.39"), topeAplicado = BigDecimal("8"))
        assertEquals(emptyList<String>(), campos(topada))
        assertEquals(emptyList<String>(), campos(topada.copy(monto = BigDecimal.ZERO)))
        assertEquals(emptyList<String>(), campos(topada.copy(monto = BigDecimal("53.39"))))
        assertEquals(listOf("monto"), campos(topada.copy(monto = BigDecimal("53.40"))))
        // monto_sin_tope is what squares with the desglose
        assertEquals(listOf("monto_sin_tope"), campos(topada.copy(montoSinTope = BigDecimal("53.40"), monto = BigDecimal("50"))))
        assertEquals(listOf("monto"), campos(topada.copy(monto = BigDecimal("-1"))))
        // the two together, or neither; the tope above 0
        assertEquals(listOf("monto_sin_tope", "monto"), campos(topada.copy(montoSinTope = null)))
        assertEquals(listOf("tope_aplicado"), campos(topada.copy(topeAplicado = null)))
        assertEquals(listOf("tope_aplicado"), campos(topada.copy(topeAplicado = BigDecimal.ZERO)))
        // a cuota without desglose still keeps its monto under its monto_sin_tope
        val sinDesglose = cuota.copy(monto = BigDecimal("9"), montoSinTope = BigDecimal("10"), topeAplicado = BigDecimal("8"))
        assertEquals(emptyList<String>(), campos(sinDesglose))
        assertEquals(listOf("monto"), campos(sinDesglose.copy(monto = BigDecimal("11"))))
    }

    @Test
    fun `a desglose needs a positive cantidad, a tasa not negative, a known redondeo and P with V`() {
        assertEquals(listOf("cantidad_base"), campos(barrido.copy(cantidadBase = BigDecimal.ZERO)))
        assertEquals(listOf("cantidad_base"), campos(barrido.copy(cantidadBase = null)))
        assertEquals(listOf("tasa_unitaria"), campos(barrido.copy(tasaUnitaria = BigDecimal("-5.0856"))))
        assertEquals(listOf("tasa_unitaria"), campos(barrido.copy(tasaUnitaria = null)))
        assertEquals(listOf("redondeo"), campos(barrido.copy(redondeo = "2:CERCA")))
        assertEquals(listOf("variacion_habitante"), campos(recoleccion.copy(variacionHabitante = null)))
        assertEquals(listOf("base"), campos(barrido.copy(base = "FRONTIS")))
        // a cuota without base (before the desglose) is not checked against one
        assertEquals(emptyList<String>(), campos(cuota.copy(cantidadBase = BigDecimal.ZERO)))
    }

    @Test
    fun `every field of a cuota is model json's, so none is dropped when written`() {
        val json = JsonMapper.builder().build()
        val modelo = json.readTree(File("model/model.json"))

        fun JsonNode.todos() = iterator().asSequence()
        val objeto = modelo["objects"].todos().first { it["name"].asString() == CUOTA_ARBITRIO }
        val campos = objeto["fields"].todos().map { it["name"].asString() }
        val relaciones =
            modelo["relationships"]
                .todos()
                .filter { it["source"].asString() == CUOTA_ARBITRIO }
                .map { it["fieldName"].asString() }
        val propios = json.valueToTree<JsonNode>(CuotaArbitrio()).propertyNames().toSet()
        assertEquals((campos + relaciones + "id").toSet(), propios)
        assertEquals(Base.entries.map { it.name }, modelo["enums"]["base_arbitrio"].todos().map { it.asString() }.toList())
    }

    @Test
    fun `the observación is 5 to 500 characters once trimmed`() {
        assertEquals(listOf("observacion"), campos(cuota.copy(observacion = "  ok  ")))
        assertEquals(listOf("observacion"), campos(cuota.copy(observacion = "x".repeat(501))))
        assertEquals("Por la ordenanza", Observacion.de("  Por la ordenanza "))
        for (mala in listOf(null, "", " abc ", "x".repeat(501))) {
            val e = assertThrows(ValidationException::class.java) { Observacion.de(mala) }
            assertEquals(HttpStatus.BAD_REQUEST, e.status)
            assertEquals("observacion", e.violations.single().field)
        }
    }

    @Test
    fun `each month is attributed to its day 1`() {
        assertEquals(LocalDate.of(2026, 1, 1), Periodo.atribucion(2026, 1))
        assertEquals(LocalDate.of(2026, 12, 1), Periodo.atribucion(2026, 12))
        assertThrows(IllegalArgumentException::class.java) { Periodo.atribucion(2026, 0) }
        assertThrows(IllegalArgumentException::class.java) { Periodo.atribucion(2026, 13) }
    }
}
