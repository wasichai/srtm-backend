package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.rentas.Records
import srtm.sanciones.Ficticios.codigo
import srtm.sanciones.Ficticios.d
import srtm.sanciones.Ficticios.uit
import java.math.BigDecimal

// the multa of an acta (SPEC §6, decisions 1-3): rentas' Papeleta took the six amounts as typed from the paper acta;
// srtm calculates them once from the CUIS version and the UIT and freezes them in the row. every figure is FICTITIOUS
class MultasTest {
    private val uit = BigDecimal(Ficticios.UIT)

    private fun desglose(
        c: CodigoInfraccion = codigo(),
        grado: String = PRIMERA
    ) = Multas.calcular(c, uit, grado).also { assertEquals(emptyList<String>(), it.faltan) }.valor!!

    @Test
    fun `the first time charges the code's % of the UIT`() {
        val m = desglose()
        assertEquals(uit, m.baseImponible)
        assertEquals(BigDecimal("10"), m.porcentajeInfraccion)
        assertEquals(BigDecimal("432.10"), m.importeInfraccion)
        assertEquals(BigDecimal("10"), m.porcentajeACobrar)
        assertEquals(BigDecimal("432.10"), m.importeAPagar)
        assertNull(m.importeConBeneficio, "no beneficio is ruled yet")
    }

    @Test
    fun `a reincidencia charges its grado's %, and the infraccion's amount stays the code's`() {
        val segunda = desglose(grado = SEGUNDA)
        assertEquals(BigDecimal("432.10"), segunda.importeInfraccion)
        assertEquals(BigDecimal("15"), segunda.porcentajeACobrar)
        assertEquals(BigDecimal("648.15"), segunda.importeAPagar)
        val tercera = desglose(grado = TERCERA_O_MAS)
        assertEquals(BigDecimal("20"), tercera.porcentajeACobrar)
        assertEquals(BigDecimal("864.20"), tercera.importeAPagar)
    }

    @Test
    fun `one rounding, half up to the centimo`() {
        // 4321.00 x 12.5 % = 540.125: half up gives 540.13 (half even would give 540.12)
        assertEquals(BigDecimal("540.13"), desglose(codigo(primera = "12.5")).importeAPagar)
        // 4321.00 x 0.35 % = 15.1235: rounded once, never per step
        assertEquals(BigDecimal("15.12"), desglose(codigo(primera = "0.35")).importeAPagar)
        assertEquals(2, desglose().importeAPagar.scale())
        assertEquals(BigDecimal("0.01"), Multas.redondear(BigDecimal("0.005")))
        assertEquals(BigDecimal("0.00"), Multas.redondear(BigDecimal("0.0049")))
    }

    @Test
    fun `a grado declared without its % is missing, never charged at another grado's`() {
        val sinSegunda = Multas.calcular(codigo(segunda = null), uit, SEGUNDA)
        assertNull(sinSegunda.valor)
        assertEquals(listOf("CUIS A-042 porcentaje_uit_segunda"), sinSegunda.faltan)
        assertEquals(listOf("CUIS A-042 porcentaje_uit_tercera"), Multas.calcular(codigo(tercera = null), uit, TERCERA_O_MAS).faltan)
        // the first time does not need the others
        assertEquals(BigDecimal("432.10"), desglose(codigo(segunda = null, tercera = null)).importeAPagar)
        val e = assertThrows(FaltanSanciones::class.java) { sinSegunda.exigir("registrar el acta") }
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, e.status)
        assertEquals(listOf("CUIS A-042 porcentaje_uit_segunda"), e.faltan)
    }

    @Test
    fun `a reincidencia that is not a grado is a 422 that names the field`() {
        val e = assertThrows(NoProcede::class.java) { Multas.calcular(codigo(), uit, "CUARTA") }
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, e.status)
        assertEquals(listOf("reincidencia"), e.violations.map { it.field })
    }

    @Test
    fun `the UIT is the row in force on the infraccion's day, or it is missing`() {
        val parametros = listOf(uit(2025, "4000.00"), uit(2026))
        assertEquals("uit-2026", Multas.uit(parametros, d("2026-03-04")).valor!!.id)
        assertEquals("uit-2025", Multas.uit(parametros, d("2025-12-31")).valor!!.id)
        assertEquals(listOf("UIT 2027"), Multas.uit(parametros, d("2027-01-02")).faltan)
        assertEquals(listOf("UIT 2026: no es mayor que 0"), Multas.uit(listOf(uit(2026, "0")), d("2026-03-04")).faltan)
    }

    @Test
    fun `the desglose travels with the model's names`() {
        val json = Records.attributes(desglose())
        assertEquals(
            setOf("base_imponible", "porcentaje_infraccion", "importe_infraccion", "porcentaje_a_cobrar", "importe_a_pagar", "importe_con_beneficio"),
            json.keys
        )
        assertTrue(json.containsKey("importe_con_beneficio") && json["importe_con_beneficio"] == null)
    }
}
