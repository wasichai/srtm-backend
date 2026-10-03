package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.Observacion
import wasichai.core.common.ValidationException
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
            fechaCalculo = LocalDate.of(2026, 3, 1),
            observacion = "Se determina para la prueba",
            clave = "p|s|2026|1|1"
        )

    private fun campos(c: CuotaArbitrio) = invariantes(c).map { it.field }

    @Test
    fun `a well formed cuota passes`() {
        assertEquals(emptyList<String>(), campos(cuota))
        assertEquals(emptyList<String>(), campos(cuota.copy(monto = BigDecimal.ZERO, clave = "p|s|2026|1|2")))
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
    fun `without the key of the parameter applied it is not written`() {
        assertEquals(listOf("parametro_aplicado"), campos(cuota.copy(parametroAplicado = "  ")))
        assertEquals(listOf("parametro_aplicado"), campos(cuota.copy(parametroAplicado = "X".repeat(121))))
        assertEquals(emptyList<String>(), campos(cuota.copy(parametroAplicado = "X".repeat(120))))
    }

    @Test
    fun `every relation, the year and the date of calculation are required`() {
        val vacia = CuotaArbitrio(periodo = 1, monto = BigDecimal.ONE, parametroAplicado = "x", observacion = "una razón")
        assertEquals(listOf("predio", "contribuyente", "servicio", "parametro", "anio", "fecha_calculo"), campos(vacia))
    }

    @Test
    fun `the clave is its predio, servicio, year, month and version`() {
        assertEquals(listOf("clave"), campos(cuota.copy(clave = "p|s|2026|2|1")))
        assertEquals(listOf("clave"), campos(cuota.copy(clave = "p|s|2026|1|0")))
        assertEquals(listOf("clave"), campos(cuota.copy(clave = null)))
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
