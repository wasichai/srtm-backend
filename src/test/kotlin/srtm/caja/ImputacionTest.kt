package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// #165: what srtm does with each orden of a pago of caja (Imputacion.clasificar), the motivo it answers, and how it
// reads caja's event (leerEvento): a body it cannot read is a 400
class ImputacionTest {
    private val ref = "ARB-p1|LIMP|2026|3|1|1"

    private fun delPago(importe: String = "150.50") = OrdenDelPago("8f0c2a8e-1f0e-4d0b-9a8e-3c1d2b4a5e6f", ref, BigDecimal(importe))

    private val enSrtm = OrdenCaja(id = "o-1", referencia = ref, importe = BigDecimal("150.50"), estado = EstadoOrdenCaja.ENVIADA, contribuyente = "c-1")

    @Test
    fun `una orden vigente, del mismo importe y sin otro pago se aplica`() {
        val d = Imputacion.clasificar(delPago("150.5"), enSrtm, vigente = true, yaPagada = null)
        assertEquals(Detalle(ref, BigDecimal("150.5"), aplicado = true, motivo = null, orden = "o-1", contribuyente = "c-1"), d)
    }

    @Test
    fun `una orden que srtm no tiene va a cuenta`() {
        val d = Imputacion.clasificar(delPago(), null, vigente = false, yaPagada = null)
        assertEquals(false, d.aplicado)
        assertEquals("no existe", d.motivo)
        assertNull(d.orden)
        assertNull(d.contribuyente)
    }

    @Test
    fun `una orden sustituida va a cuenta y nombra la nueva`() {
        val d = Imputacion.clasificar(delPago(), enSrtm, vigente = false, yaPagada = null, sustituta = "ARB-p1|LIMP|2026|3|1|2")
        assertEquals(false, d.aplicado)
        assertEquals("la deuda $ref fue sustituida por ARB-p1|LIMP|2026|3|1|2", d.motivo)
        assertEquals("c-1", d.contribuyente)
    }

    @Test
    fun `una orden que ya no esta vigente va a cuenta`() {
        val d = Imputacion.clasificar(delPago(), enSrtm, vigente = false, yaPagada = null)
        assertEquals("la deuda $ref ya no está vigente", d.motivo)
    }

    @Test
    fun `otro importe va a cuenta`() {
        val d = Imputacion.clasificar(delPago("100"), enSrtm, vigente = true, yaPagada = null)
        assertEquals(false, d.aplicado)
        assertEquals("el importe no coincide: 100.00 en caja, 150.50 en srtm", d.motivo)
    }

    @Test
    fun `una orden ya pagada va a cuenta`() {
        val d = Imputacion.clasificar(delPago(), enSrtm, vigente = true, yaPagada = "001-0000007")
        assertEquals(false, d.aplicado)
        assertEquals("ya pagada con el recibo 001-0000007", d.motivo)
    }

    @Test
    fun `el recibo es serie y numero, sin repetir la serie que el numero impreso ya trae`() {
        assertEquals("001-0000001", Imputacion.recibo("001", "001-0000001"))
        assertEquals("B01-0000009", Imputacion.recibo("B01", "0000009"))
        assertEquals("0000009", Imputacion.recibo(null, "0000009"))
    }

    @Test
    fun `los motivos se unen con punto y coma, sin repetir, hasta 400 caracteres`() {
        val a = Detalle("a", BigDecimal.ONE, false, "no existe", null, null)
        val b = Detalle("b", BigDecimal.ONE, false, "no existe", null, null)
        val c = Detalle("c", BigDecimal.ONE, true, null, "o", "c")
        val d = Detalle("d", BigDecimal.ONE, false, "la deuda d ya no está vigente", "o", "c")
        assertEquals("no existe; la deuda d ya no está vigente", Imputacion.motivo(listOf(a, b, c, d)))
        assertNull(Imputacion.motivo(listOf(c)))
        val largo = Detalle("e", BigDecimal.ONE, false, "x".repeat(500), null, null)
        assertEquals(400, Imputacion.motivo(listOf(largo))!!.length)
    }

    @Test
    fun `las respuestas son las del contrato`() {
        assertEquals("""{"aplicado":true,"a_cuenta":false,"motivo":null}""", Imputacion.respuesta(true, false, null))
        assertEquals("""{"aplicado":false,"a_cuenta":true,"motivo":"no existe"}""", Imputacion.respuesta(false, true, "no existe"))
    }

    @Test
    fun `lee un pago registrado`() {
        val e = leerEvento(REGISTRADO) as PagoRegistrado
        assertEquals("11111111-1111-1111-1111-111111111111", e.pagoId)
        assertEquals("001-0000001", e.recibo.numero)
        assertEquals("001", e.recibo.serie)
        assertEquals(LocalDate.of(2026, 10, 22), e.recibo.fechaDePago)
        assertEquals("ana@muni.gob.pe", e.recibo.cajero)
        assertEquals("EFECTIVO", e.recibo.formaDePago)
        assertEquals("12345678", e.pagador?.documento)
        assertEquals(BigDecimal("150.50"), e.total)
        assertEquals(listOf(OrdenDelPago("22222222-2222-2222-2222-222222222222", ref, BigDecimal("150.50"))), e.ordenes)
    }

    @Test
    fun `lee un pago anulado`() {
        val e =
            leerEvento(
                """{"pagoId":"33333333-3333-3333-3333-333333333333","tipo":"PAGO_ANULADO","pagoOriginalId":"11111111-1111-1111-1111-111111111111",
                "recibo":{"numero":"001-0000001","serie":"001","fechaDePago":"2026-10-22","cajero":"ana@muni.gob.pe","formaDePago":"EFECTIVO"},
                "motivo":"Error de digitación","fecha":"2026-10-23","total":"150.50","otra":1}"""
            ) as PagoAnulado
        assertEquals("11111111-1111-1111-1111-111111111111", e.pagoOriginalId)
        assertEquals("Error de digitación", e.motivo)
        assertEquals(LocalDate.of(2026, 10, 23), e.fecha)
    }

    @Test
    fun `un cuerpo ilegible es un 400`() {
        val malos =
            listOf(
                null,
                "",
                "{",
                "[]",
                REGISTRADO.replace(""""pagoId":"11111111-1111-1111-1111-111111111111",""", ""),
                REGISTRADO.replace(""""tipo":"PAGO_REGISTRADO",""", ""),
                REGISTRADO.replace("PAGO_REGISTRADO", "PAGO_RARO"),
                REGISTRADO.replace(""""importe":"150.50"""", """"importe":"ciento cincuenta""""),
                REGISTRADO.replace(""""total":"150.50"""", """"total":"x""""),
                REGISTRADO.replace(""""ordenes":[""", """"ordenes":[], "nada":["""),
                """{"pagoId":"3","tipo":"PAGO_ANULADO","total":"1.00"}"""
            )
        for (m in malos) assertThrows<ValidationException>(m.toString()) { leerEvento(m) }
    }

    @Test
    fun `una orden repetida en el pago es un 400`() {
        val doble = REGISTRADO.replace("]}", """,{"ordenId":"x","referenciaExterna":"$ref","importe":"1.00","actualizadoA":"2026-10-22"}]}""")
        val e = assertThrows<ValidationException> { leerEvento(doble) }
        assertTrue(e.message!!.contains("repetida"), e.message)
    }

    private companion object {
        val REGISTRADO =
            """{"pagoId":"11111111-1111-1111-1111-111111111111","tipo":"PAGO_REGISTRADO","sistemaOrigen":"rentas",
            "recibo":{"numero":"001-0000001","serie":"001","fechaDePago":"2026-10-22","cajero":"ana@muni.gob.pe","formaDePago":"EFECTIVO"},
            "pagador":{"documento":"12345678","nombre":"FLORES OTINIANO JUNIOR","idExterno":null},
            "total":"150.50","actualizadoA":"2026-10-22",
            "ordenes":[{"ordenId":"22222222-2222-2222-2222-222222222222","referenciaExterna":"ARB-p1|LIMP|2026|3|1|1","importe":"150.50","actualizadoA":"2026-10-22"}]}"""
    }
}
