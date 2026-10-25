package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.brecha.DetalleValuacion
import srtm.brecha.ValuacionDeInsumos
import java.math.BigDecimal

// #156: what a declaración derives of its lists, and of catastro's valuation
class DerivadosTest {
    @Test
    fun `the areas add up the active niveles, to the cent`() {
        val t =
            Derivados.totales(
                listOf(
                    nivel("100.255", comun = "10"),
                    nivel("50", comun = null),
                    nivel("999", comun = "99", estado = "INACTIVO")
                ),
                emptyList(),
                null
            )
        assertEquals(BigDecimal("150.26"), t.areaConstruida)
        assertEquals(BigDecimal("10.00"), t.areaComunConstruida)
    }

    @Test
    fun `no niveles is no area`() {
        val t = Derivados.totales(emptyList(), emptyList(), null)
        assertEquals(BigDecimal("0.00"), t.areaConstruida)
        assertEquals(BigDecimal("0.00"), t.areaComunConstruida)
        assertNull(t.longitudFrente)
    }

    @Test
    fun `the longitud de frente is the frontis principal and the active otros frentes`() {
        val frentes = listOf(OtroFrente(frontis = BigDecimal("4.5")), OtroFrente(frontis = null), OtroFrente(frontis = BigDecimal("7"), estado = "INACTIVO"))
        assertEquals(BigDecimal("16.50"), Derivados.totales(emptyList(), frentes, BigDecimal("12")).longitudFrente)
        assertEquals(BigDecimal("4.50"), Derivados.totales(emptyList(), frentes, null).longitudFrente)
        assertEquals(BigDecimal("12.00"), Derivados.totales(emptyList(), emptyList(), BigDecimal("12")).longitudFrente)
        assertNull(Derivados.totales(emptyList(), listOf(OtroFrente()), null).longitudFrente)
    }

    @Test
    fun `catastro's valuation is the autoavaluo, its motivo a mark to look at`() {
        assertEquals(Autoavaluo.Valuado(BigDecimal("96752.21")), Derivados.autoavaluo(valuacion(autoavaluo = BigDecimal("96752.21"))))
        assertEquals(
            Autoavaluo.PorRevisar("Falta el porcentaje de actualización de 2027"),
            Derivados.autoavaluo(valuacion(motivo = "Falta el porcentaje de actualización de 2027", llave = "PARAMETRO_FALTANTE:X"))
        )
        assertEquals(Autoavaluo.PorRevisar("ARANCEL_FALTANTE"), Derivados.autoavaluo(valuacion(llave = "ARANCEL_FALTANTE")))
        assertEquals(Autoavaluo.PorRevisar("El catastro no valuó la declaración"), Derivados.autoavaluo(valuacion()))
    }

    @Test
    fun `a valued declaracion takes the autoavaluo and drops its mark`() {
        val antes = Declaracion(valorAutoavaluo = BigDecimal("10"), autoavaluoPorRevisar = true, autoavaluoMotivo = "x")
        val d = Derivados.aplicar(antes, totales(), Autoavaluo.Valuado(BigDecimal("20")))
        assertEquals(BigDecimal("20"), d.valorAutoavaluo)
        assertNull(d.autoavaluoPorRevisar)
        assertNull(d.autoavaluoMotivo)
        assertEquals(BigDecimal("1.00"), d.areaConstruida)
        assertEquals(BigDecimal("2.00"), d.areaComunConstruida)
        assertEquals(BigDecimal("3.00"), d.longitudFrente)
    }

    @Test
    fun `one not valued keeps its autoavaluo, marked, and a manual one is left alone`() {
        val antes = Declaracion(valorAutoavaluo = BigDecimal("10"))
        val marcada = Derivados.aplicar(antes, totales(), Autoavaluo.PorRevisar("motivo"))
        assertEquals(BigDecimal("10"), marcada.valorAutoavaluo)
        assertEquals(true, marcada.autoavaluoPorRevisar)
        assertEquals("motivo", marcada.autoavaluoMotivo)
        val manual = Derivados.aplicar(antes.copy(autoavaluoPorRevisar = true, autoavaluoMotivo = "viejo"), totales(), Autoavaluo.Manual)
        assertEquals(BigDecimal("10"), manual.valorAutoavaluo)
        assertEquals(true, manual.autoavaluoPorRevisar)
        assertEquals("viejo", manual.autoavaluoMotivo)
    }

    @Test
    fun `catastro's PREDIO_RUSTICO keeps the declared autoavaluo, marked to review`() {
        val antes = Declaracion(valorAutoavaluo = BigDecimal("12800.00"))
        val v = valuacion(motivo = "PREDIO_RUSTICO")
        val d = Derivados.aplicar(antes, totales(), Derivados.autoavaluo(v))
        assertEquals(BigDecimal("12800.00"), d.valorAutoavaluo)
        assertEquals(true, d.autoavaluoPorRevisar)
        assertEquals("PREDIO_RUSTICO", d.autoavaluoMotivo)
    }

    @Test
    fun `catastro not answering is a mark that says so`() {
        assertEquals(Autoavaluo.PorRevisar("El catastro no respondió: catastro-backend respondió 500"), Derivados.noResponde("catastro-backend respondió 500"))
    }

    @Test
    fun `the derived fields compare by value`() {
        val a = Declaracion(areaConstruida = BigDecimal("100"), valorAutoavaluo = BigDecimal("5.0"))
        val b = Declaracion(areaConstruida = BigDecimal("100.00"), valorAutoavaluo = BigDecimal("5"))
        assertEquals(emptySet<String>(), Derivados.cambiados(a, b))
        assertEquals(
            setOf("area_construida", "autoavaluo_por_revisar"),
            Derivados.cambiados(a, b.copy(areaConstruida = BigDecimal("1"), autoavaluoPorRevisar = true))
        )
    }

    @Test
    fun `the padron's otras instalaciones are added while the DJ declares no obra`() {
        val r = Derivados.conObrasDelPadron(valuada(), emptyList(), BigDecimal("10000.00"))
        assertEquals(BigDecimal("100000.00"), r.autoavaluo)
        assertEquals(BigDecimal("10000.00"), r.obras)
        assertEquals(BigDecimal("10000.00"), r.obrasPadronOrigen)
        val fila = r.detalle!!.obras.last()
        assertEquals(OBRAS_DEL_PADRON, fila.descripcion)
        assertNull(fila.codigoVu)
        assertEquals(BigDecimal("10000.00"), fila.valor)
    }

    @Test
    fun `an active obra stops it, an inactive one does not`() {
        val v = valuada()
        assertEquals(v, Derivados.conObrasDelPadron(v, listOf(ObraComplementaria(estado = null)), BigDecimal("10000.00")))
        assertEquals(v, Derivados.conObrasDelPadron(v, listOf(ObraComplementaria(estado = "ACTIVO")), BigDecimal("10000.00")))
        val r = Derivados.conObrasDelPadron(v, listOf(ObraComplementaria(estado = "INACTIVO")), BigDecimal("10000.00"))
        assertEquals(BigDecimal("100000.00"), r.autoavaluo)
    }

    @Test
    fun `not valued, no amount or zero leaves catastro's answer`() {
        val noValuada = valuacion(motivo = "Falta el arancel", llave = "ARANCEL_FALTANTE")
        assertEquals(noValuada, Derivados.conObrasDelPadron(noValuada, emptyList(), BigDecimal("10000.00")))
        assertEquals(valuada(), Derivados.conObrasDelPadron(valuada(), emptyList(), null))
        assertEquals(valuada(), Derivados.conObrasDelPadron(valuada(), emptyList(), BigDecimal.ZERO))
    }

    @Test
    fun `a valued declaracion keeps its obras, one not valued its stored ones`() {
        val antes = Declaracion(valorObras = BigDecimal("5.00"))
        assertEquals(
            BigDecimal("10000.00"),
            Derivados.aplicar(antes, totales(), Autoavaluo.Valuado(BigDecimal("100000.00"), BigDecimal("10000.00"))).valorObras
        )
        assertEquals(BigDecimal("5.00"), Derivados.aplicar(antes, totales(), Autoavaluo.PorRevisar("X")).valorObras)
        assertEquals(BigDecimal("5.00"), Derivados.aplicar(antes, totales(), Autoavaluo.Manual).valorObras)
        assertTrue("valor_obras" in Derivados.campos(antes).keys)
    }

    // 1, 2 and 3: what the lists add up to
    private fun totales() = TotalesDeLaDeclaracion(BigDecimal("1.00"), BigDecimal("2.00"), BigDecimal("3.00"))

    private fun nivel(
        area: String,
        comun: String?,
        estado: String? = "ACTIVO"
    ) = NivelConstruccion(areaConstruida = BigDecimal(area), areaComun = comun?.let(::BigDecimal), estado = estado)

    private fun valuacion(
        autoavaluo: BigDecimal? = null,
        motivo: String? = null,
        llave: String? = null
    ) = ValuacionDeInsumos(2026, null, null, null, null, null, null, null, null, autoavaluo, motivo, llave, null)

    // valued at 90000.00, no obra in its detail
    private fun valuada() =
        valuacion(autoavaluo = BigDecimal("90000.00")).copy(obras = BigDecimal("0.00"), detalle = DetalleValuacion(emptyList(), emptyList()))
}
