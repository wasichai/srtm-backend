package srtm.impuesto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

// art. 13 of the TUO LTM with the verified parameters of 2026 (Parametros): progressive tramos in UIT, the mínimo,
// and the four cuotas of art. 15
class ImpuestoPredialTest {
    private val uit = Parametros.uit(2026)
    private val limite1 = Parametros.valor("TRAMO_PREDIAL_LIMITE", "1")
    private val limite2 = Parametros.valor("TRAMO_PREDIAL_LIMITE", "2")
    private val alicuota1 = Parametros.valor("TRAMO_PREDIAL", "1")
    private val alicuota2 = Parametros.valor("TRAMO_PREDIAL", "2")
    private val alicuota3 = Parametros.valor("TRAMO_PREDIAL", "3")
    private val minimo = pct(uit, Parametros.valor("PREDIAL_MINIMO"))
    private val centimo = BigDecimal("0.01")

    private fun liquidar(
        base: BigDecimal,
        anio: Int = 2026
    ) = ImpuestoPredial.liquidar(anio, base, Parametros.predial)

    @Test
    fun `base 0 pays nothing, and no minimo`() {
        val l = liquidar(BigDecimal.ZERO)
        assertEquals(uit, l.uit)
        assertEquals(dos(BigDecimal.ZERO), l.impuestoCalculado)
        assertEquals(dos(BigDecimal.ZERO), l.impuestoAnual)
        assertEquals(minimo, l.minimo)
        assertEquals(false, l.minimoAplicado)
        assertEquals(List(4) { dos(BigDecimal.ZERO) }, l.cuotas.map { it.monto })
        assertEquals(emptyList<String>(), l.faltan)
    }

    @Test
    fun `a small base pays the minimo`() {
        val base = BigDecimal("1000.00")
        val l = liquidar(base)
        assertEquals(pct(base, alicuota1), l.impuestoCalculado)
        assertTrue(l.impuestoCalculado!! < minimo)
        assertEquals(true, l.minimoAplicado)
        assertEquals(minimo, l.impuestoAnual)
    }

    @Test
    fun `15 UIT is all in the first tramo, a centimo more starts the second`() {
        val borde = dos(uit * limite1)
        val en = liquidar(borde)
        assertEquals(listOf(borde, dos(BigDecimal.ZERO), dos(BigDecimal.ZERO)), en.tramos.map { it.monto })
        assertEquals(pct(borde, alicuota1), en.impuestoCalculado)

        val mas = liquidar(borde + centimo)
        assertEquals(listOf(borde, centimo, dos(BigDecimal.ZERO)), mas.tramos.map { it.monto })
        assertEquals(pct(borde, alicuota1) + pct(centimo, alicuota2), mas.impuestoCalculado)
    }

    @Test
    fun `60 UIT fills the second tramo, a centimo more starts the third`() {
        val borde = dos(uit * limite2)
        val segundo = dos(uit * (limite2 - limite1))
        val en = liquidar(borde)
        assertEquals(listOf(dos(uit * limite1), segundo, dos(BigDecimal.ZERO)), en.tramos.map { it.monto })
        assertEquals(pct(uit * limite1, alicuota1) + pct(segundo, alicuota2), en.impuestoCalculado)

        val mas = liquidar(borde + centimo)
        assertEquals(listOf(dos(uit * limite1), segundo, centimo), mas.tramos.map { it.monto })
        assertEquals(en.impuestoCalculado!! + pct(centimo, alicuota3), mas.impuestoCalculado)
    }

    @Test
    fun `100 UIT pays 15 at the first rate, 45 at the second and 40 at the third`() {
        val l = liquidar(uit * BigDecimal(100))
        val esperado = pct(uit * BigDecimal(15), alicuota1) + pct(uit * BigDecimal(45), alicuota2) + pct(uit * BigDecimal(40), alicuota3)
        assertEquals(esperado, l.impuestoCalculado)
        assertEquals(esperado, l.impuestoAnual)
        assertFalse(l.minimoAplicado!!)
    }

    @Test
    fun `the tramos, in soles`() {
        val l = liquidar(uit * BigDecimal(100))
        assertEquals(listOf(1, 2, 3), l.tramos.map { it.tramo })
        assertEquals(listOf(dos(BigDecimal.ZERO), dos(uit * limite1), dos(uit * limite2)), l.tramos.map { it.desde })
        assertEquals(listOf(dos(uit * limite1), dos(uit * limite2), null), l.tramos.map { it.hasta })
        assertEquals(listOf(alicuota1, alicuota2, alicuota3), l.tramos.map { it.alicuota })
        assertEquals(l.impuestoCalculado, l.tramos.sumOf { it.impuesto })
    }

    @Test
    fun `the cuotas add up to the annual tax exactly, the remainder in the fourth`() {
        // an annual tax that is no multiple of 4 centimos
        val l = liquidar(BigDecimal("123456.78"))
        val anual = l.impuestoAnual!!
        assertEquals(anual, l.cuotas.sumOf { it.monto })
        val cuarto = anual.divide(BigDecimal(4), 2, RoundingMode.HALF_UP)
        assertEquals(listOf(cuarto, cuarto, cuarto, anual - cuarto * BigDecimal(3)), l.cuotas.map { it.monto })
        assertTrue(l.cuotas[3].monto != cuarto)
        assertEquals(listOf(1, 2, 3, 4), l.cuotas.map { it.numero })
        assertEquals(Vencimientos.predial(2026), l.cuotas.map { it.vencimiento })
    }

    @Test
    fun `a year takes the UIT in force on its 1 of January`() {
        assertEquals(Parametros.uit(2025), liquidar(BigDecimal.ONE, anio = 2025).uit)
    }

    @Test
    fun `a year without its UIT is not liquidated, and no figure is zero`() {
        val l = liquidar(BigDecimal("50000.00"), anio = 2027)
        assertEquals(listOf("UIT 2027"), l.faltan)
        assertNull(l.uit)
        assertNull(l.impuestoCalculado)
        assertNull(l.minimo)
        assertNull(l.minimoAplicado)
        assertNull(l.impuestoAnual)
        assertEquals(emptyList<Any>(), l.tramos)
        assertEquals(emptyList<Any>(), l.cuotas)
        assertEquals(BigDecimal("50000.00"), l.base)
    }

    @Test
    fun `every missing parameter is listed`() {
        val l = ImpuestoPredial.liquidar(2026, BigDecimal.TEN, Parametros.predial.filter { it.tipo == "UIT" })
        assertEquals(
            listOf(
                "TRAMO_PREDIAL 1 2026",
                "TRAMO_PREDIAL 2 2026",
                "TRAMO_PREDIAL 3 2026",
                "TRAMO_PREDIAL_LIMITE 1 2026",
                "TRAMO_PREDIAL_LIMITE 2 2026",
                "PREDIAL_MINIMO 2026"
            ),
            l.faltan
        )
        assertNull(l.impuestoAnual)
    }

    @Test
    fun `a parameter that ended before the year is missing`() {
        val vencida = Parametros.predial.map { if (it.tipo == "PREDIAL_MINIMO") it.copy(vigenciaHasta = java.time.LocalDate.of(2025, 12, 31)) else it }
        assertEquals(listOf("PREDIAL_MINIMO 2026"), ImpuestoPredial.liquidar(2026, BigDecimal.TEN, vencida).faltan)
    }

    // a % of an amount, to the centimo
    private fun pct(
        monto: BigDecimal,
        porcentaje: BigDecimal
    ): BigDecimal = (monto * porcentaje).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)

    private fun dos(valor: BigDecimal): BigDecimal = valor.setScale(2, RoundingMode.HALF_UP)
}
