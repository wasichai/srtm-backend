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
import java.time.Instant
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
        assertEquals("la cuota ya no se cobra", VigenciaDeOrdenes.decidir("R", orden, false, null, null, null).motivo)
        // paid in caja and srtm has the recibo: the recibo is named; without it, «ya pagada en caja»
        val enCaja = orden.copy(estado = EstadoOrdenCaja.PAGADA_EN_CAJA)
        assertEquals("ya pagada con el recibo 001-7", VigenciaDeOrdenes.decidir("R", enCaja, true, "001-7", null, null).motivo)
        assertEquals("ya pagada en caja", VigenciaDeOrdenes.decidir("R", enCaja, true, null, null, null).motivo)
        assertEquals("retirada", VigenciaDeOrdenes.decidir("R", orden.copy(estado = EstadoOrdenCaja.POR_RETIRAR), true, null, null, null).motivo)
        assertFalse(VigenciaDeOrdenes.decidir("R", orden.copy(estado = EstadoOrdenCaja.POR_ENVIAR), true, null, null, null).vigente)
    }

    // caja asking for an orden POR_ENVIAR already tried proves it holds it (a timeout): it is vigente, like an ENVIADA
    @Test
    fun `una orden por enviar ya intentada es vigente`() {
        val intentada = OrdenCaja(importe = BigDecimal("12.5"), estado = EstadoOrdenCaja.POR_ENVIAR, intentos = 1)

        assertTrue(VigenciaDeOrdenes.decidir("R", intentada, true, null, null, null).vigente)
        val nunca = VigenciaDeOrdenes.decidir("R", intentada.copy(intentos = 0), true, null, null, null)
        assertFalse(nunca.vigente)
        assertEquals("todavía no se envió a caja", nunca.motivo)
    }

    // Ruling 26 for ERROR: only an alta that ran out of tries (never ENVIADA) may be caja's to collect. a retiro that
    // ran out of them (it had been sent: enviada_en) is srtm withdrawing it: not vigente
    @Test
    fun `un error ya intentado es vigente solo si fue un alta`() {
        val alta = OrdenCaja(importe = BigDecimal("12.5"), estado = EstadoOrdenCaja.ERROR, intentos = 20)
        val retiro = alta.copy(enviadaEn = Instant.parse("2026-10-22T15:04:05Z"), ordenId = "o-1")

        assertTrue(VigenciaDeOrdenes.decidir("R", alta, true, null, null, null).vigente)
        val r = VigenciaDeOrdenes.decidir("R", retiro, true, null, null, null)
        assertFalse(r.vigente)
        assertEquals("retirada", r.motivo)
    }

    // Ruling 28: a PAGADA_EN_CAJA orden whose pago arrived and went a cuenta is no longer paid in caja: it was withdrawn
    @Test
    fun `una pagada en caja cuyo pago fue a cuenta es retirada`() {
        val enCaja = OrdenCaja(importe = BigDecimal("12.5"), estado = EstadoOrdenCaja.PAGADA_EN_CAJA)

        val conPago = VigenciaDeOrdenes.decidir("R", enCaja, true, null, null, null, conPago = true)

        assertFalse(conPago.vigente)
        assertEquals("retirada", conPago.motivo)
        assertEquals("ya pagada en caja", VigenciaDeOrdenes.decidir("R", enCaja, true, null, null, null, conPago = false).motivo)
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
