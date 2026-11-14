package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import srtm.impuesto.ParametroTributario
import srtm.normativa.DatosDeParametro
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// #305: the tolerances an organization's TOLERANCIA_BRECHA rows give, else the defaults. FICTITIOUS figures
class LlavesTest {
    private val hoy = LocalDate.of(2026, 11, 14)

    private fun fila(
        clave: String,
        valor: String,
        desde: String = "2026-01-01",
        hasta: String? = null
    ) = ParametroTributario(
        tipo = Llaves.TOLERANCIA_BRECHA,
        clave = clave,
        vigenciaDesde = LocalDate.parse(desde),
        vigenciaHasta = hasta?.let(LocalDate::parse),
        valorNumerico = BigDecimal(valor)
    )

    @Test
    fun `without rows, the defaults`() {
        assertEquals(ComparacionDelPredio.TOLERANCIAS, Llaves.tolerancias(emptyList(), hoy))
    }

    @Test
    fun `each clave its vigente row, the latest when two are, and one that is no longer vigente does not count`() {
        val t =
            Llaves.tolerancias(
                listOf(
                    fila("AREA", "2", desde = "2025-01-01", hasta = "2025-12-31"),
                    fila("AREA", "1.5", desde = "2026-01-01"),
                    fila("AREA", "3", desde = "2026-06-01"),
                    fila("OTRA", "9")
                ),
                hoy
            )
        assertEquals(BigDecimal("3"), t.area)
        assertEquals(ComparacionDelPredio.TOLERANCIA_MONTO, t.monto)
        assertEquals(BigDecimal("0"), Llaves.tolerancias(listOf(fila("MONTO", "0")), hoy).monto)
    }

    @Test
    fun `Normativa takes AREA or MONTO, 0 or more`() {
        fun datos(
            clave: String,
            valor: String
        ) = mapOf<String, Any?>("tipo" to Llaves.TOLERANCIA_BRECHA, "clave" to clave, "vigencia_desde" to "2026-01-01", "valor_numerico" to valor)
        assertEquals(BigDecimal("0.75"), DatosDeParametro.validar(datos("AREA", "0.75")).valorNumerico)
        val negativo = assertThrows<ValidationException> { DatosDeParametro.validar(datos("AREA", "-1")) }
        assertEquals(listOf("valor_numerico"), negativo.violations.map { it.field })
        val otraClave = assertThrows<ValidationException> { DatosDeParametro.validar(datos("RELATIVA", "1")) }
        assertEquals(listOf("clave"), otraClave.violations.map { it.field })
    }
}
