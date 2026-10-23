package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// the pure rules of caja's conciliación (#166) and of its vigencia (#168)
class ConciliacionTest {
    @Test
    fun `la fecha es AAAA-MM-DD`() {
        assertEquals(LocalDate.of(2026, 10, 22), Conciliacion.fecha("2026-10-22"))
        assertEquals(LocalDate.of(2026, 10, 22), Conciliacion.fecha(" 2026-10-22 "))
        listOf(null, "", "  ", "22/10/2026", "2026-13-01", "2026-10-22T10:00").forEach {
            assertThrows(ValidationException::class.java) { Conciliacion.fecha(it) }
        }
    }

    @Test
    fun `los conteos son enteros y los importes cadenas de 2 decimales`() {
        val r = ResumenDelDia(4, 3, 1, BigDecimal("1234.5"), BigDecimal("0.125"))

        val j = JsonMapper.builder().build().readTree(Conciliacion.cuerpo(LocalDate.of(2026, 10, 22), r))

        assertEquals("2026-10-22", j["fecha"].asString())
        listOf("recibidos" to 4, "aplicados" to 3, "rechazados" to 0, "recibidos_a_cuenta" to 1).forEach { (campo, n) ->
            assertTrue(j[campo].isIntegralNumber, campo)
            assertEquals(n, j[campo].asInt())
        }
        assertEquals("1234.50", j["importe_aplicado"].asString())
        assertEquals("0.13", j["importe_a_cuenta"].asString())
    }

    @Test
    fun `la pregunta de vigencia trae las referencias en orden y con repetidas`() {
        val q = """{"ordenes":[{"referencia_externa":"A","importe":"1.00"},{"referencia_externa":"B"},{"referencia_externa":" A "}]}"""

        assertEquals(listOf("A", "B", "A"), VigenciaDeOrdenes.referencias(q))
    }

    @Test
    fun `una pregunta ilegible, vacia o de mas de 50 es un error de validacion`() {
        val de51 = """{"ordenes":[${(1..51).joinToString(",") { """{"referencia_externa":"R$it"}""" }}]}"""
        listOf(null, "", "{", "[]", """{"ordenes":{}}""", """{"ordenes":[]}""", """{"ordenes":[{}]}""", """{"ordenes":[{"referencia_externa":3}]}""", de51)
            .forEach { assertThrows(ValidationException::class.java, { VigenciaDeOrdenes.referencias(it) }, it) }
    }

    @Test
    fun `decidir vigencia`() {
        val orden = OrdenCaja(importe = BigDecimal("12.5"), estado = EstadoOrdenCaja.ENVIADA)

        val vigente = VigenciaDeOrdenes.decidir("R", orden, true, null, null, null)
        assertTrue(vigente.vigente)
        assertEquals("12.50", vigente.importe)
        assertNull(vigente.motivo)

        val desconocida = VigenciaDeOrdenes.decidir("R", null, false, null, null, null)
        assertFalse(desconocida.vigente)
        assertNull(desconocida.importe)
        assertEquals("no existe", desconocida.motivo)

        assertEquals("anulada: Error", VigenciaDeOrdenes.decidir("R", orden, false, null, "R2", "Error").motivo)
        assertEquals("sustituida por R2", VigenciaDeOrdenes.decidir("R", orden, false, null, "R2", null).motivo)
        assertEquals("ya pagada con el recibo 001-7", VigenciaDeOrdenes.decidir("R", orden, true, "001-7", null, null).motivo)
        // the cuota's reason first, even when srtm already withdrew the orden
        assertEquals("sustituida por R2", VigenciaDeOrdenes.decidir("R", orden.copy(estado = EstadoOrdenCaja.RETIRADA), false, null, "R2", null).motivo)
        assertEquals("retirada", VigenciaDeOrdenes.decidir("R", orden.copy(estado = EstadoOrdenCaja.POR_RETIRAR), true, null, null, null).motivo)
        assertFalse(VigenciaDeOrdenes.decidir("R", orden.copy(estado = EstadoOrdenCaja.POR_ENVIAR), true, null, null, null).vigente)
    }

    @Test
    fun `el cuerpo de vigencia lleva las cuatro claves y el importe nulo`() {
        val j = JsonMapper.builder().build().readTree(VigenciaDeOrdenes.cuerpo(listOf(VigenciaDeOrden("R", false, null, "no existe"))))

        val o = j["ordenes"].single()
        assertEquals("R", o["referencia_externa"].asString())
        assertEquals(false, o["vigente"].asBoolean())
        assertTrue(o["importe"].isNull)
        assertEquals("no existe", o["motivo"].asString())
    }
}
