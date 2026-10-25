package srtm.impuesto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.rentas.Declaracion
import srtm.rentas.Predio

// #236: a declaración whose autoavalúo catastro did not value is liquidated on its stored valor_autoavaluo, with an aviso
class AvisosLiquidacionTest {
    private val predios = mapOf("p1" to Predio(id = "p1", codigo = "000123"), "p2" to Predio(id = "p2", codigo = "000456"))

    @Test
    fun `a declaracion por revisar gives an aviso with its predio's codigo and catastro's motivo`() {
        val declaraciones =
            listOf(
                Declaracion(id = "d1", predio = "p1", autoavaluoPorRevisar = true, autoavaluoMotivo = "Falta el porcentaje de actualización de 2027"),
                Declaracion(id = "d2", predio = "p2", autoavaluoPorRevisar = false)
            )

        assertEquals(
            listOf(AvisoLiquidacion("d1", "p1", "000123", "Falta el porcentaje de actualización de 2027")),
            avisosPorRevisar(declaraciones, predios)
        )
    }

    @Test
    fun `no declaracion por revisar gives no aviso`() {
        val declaraciones = listOf(Declaracion(id = "d1", predio = "p1"), Declaracion(id = "d2", predio = "p2", autoavaluoPorRevisar = false))

        assertTrue(avisosPorRevisar(declaraciones, predios).isEmpty())
    }

    @Test
    fun `without a motivo the aviso says the autoavaluo is por revisar`() {
        val declaraciones = listOf(Declaracion(id = "d1", predio = "p1", autoavaluoPorRevisar = true, autoavaluoMotivo = " "))

        assertEquals(listOf(AvisoLiquidacion("d1", "p1", "000123", AUTOAVALUO_POR_REVISAR)), avisosPorRevisar(declaraciones, predios))
    }

    @Test
    fun `a predio that is not found leaves its codigo null`() {
        val declaraciones = listOf(Declaracion(id = "d1", predio = "p9", autoavaluoPorRevisar = true, autoavaluoMotivo = "x"))

        assertEquals(listOf(AvisoLiquidacion("d1", "p9", null, "x")), avisosPorRevisar(declaraciones, predios))
    }

    @Test
    fun `the liquidacion has no aviso of its own`() {
        assertTrue(ImpuestoPredial.liquidar(2026, java.math.BigDecimal.TEN, Parametros.predial).avisos.isEmpty())
    }
}
