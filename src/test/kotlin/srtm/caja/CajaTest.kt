package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import srtm.impuesto.Cuota
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

// the pure rules of the queue: what srtm sends as each orden's texts, the cuotas de predial a liquidación asks for, and
// the resumen of a sincronización
class CajaTest {
    private val c = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun cuota(
        numero: Int,
        monto: String
    ) = Cuota(numero, BigDecimal(monto), LocalDate.of(2026, 2, 27))

    private fun escrita(
        numero: Int,
        revision: Int,
        monto: String
    ) = CuotaPredial(id = UUID.randomUUID().toString(), numero = numero, revision = revision, monto = BigDecimal(monto))

    @Test
    fun `los conceptos son los del plan`() {
        assertEquals("Impuesto predial 2026 cuota 3", conceptoPredial(2026, 3))
        assertEquals("Limpieza pública 2026-03", conceptoArbitrio("Limpieza pública", 2026, 3))
        assertEquals("Serenazgo 2026-11", conceptoArbitrio("Serenazgo", 2026, 11))
    }

    @Test
    fun `la ranura de predial no lleva la version`() {
        assertEquals("PREDIAL|$c|2026|2", ranuraPredial(c, 2026, 2))
    }

    @Test
    fun `la observacion es de una linea y de 5 a 500 caracteres`() {
        assertEquals("Enviado a pedido desde srtm", observacion("Enviado a pedido desde srtm"))
        assertEquals("Enviado desde srtm", observacion("abc"))
        assertEquals("Enviado desde srtm", observacion("   "))
        assertEquals("una dos", observacion("una\ndos"))
        assertEquals(500, observacion("x".repeat(900)).length)
    }

    @Test
    fun `el motivo de un retiro es de una linea y de 1 a 200 caracteres`() {
        assertEquals("Ya no se cobra", motivoDeRetiro("Ya no se cobra"))
        assertEquals("una dos", motivoDeRetiro("una\r\ndos"))
        assertEquals(200, motivoDeRetiro("x".repeat(300)).length)
        assertEquals("Retirada desde srtm", motivoDeRetiro(" "))
    }

    @Test
    fun `una cuota sin escribir va en su revision 1`() {
        val p = prediales(listOf(cuota(1, "10.00")), emptyList())
        assertEquals(1, p.single().revision)
        assertNull(p.single().escrita)
    }

    @Test
    fun `la misma cuota es la vigente escrita`() {
        val vigente = escrita(1, 2, "10.0")
        val p = prediales(listOf(cuota(1, "10.00")), listOf(escrita(1, 1, "8.00"), vigente))
        assertEquals(2, p.single().revision)
        assertEquals(vigente, p.single().escrita)
    }

    @Test
    fun `otro monto va en la revision siguiente`() {
        val p = prediales(listOf(cuota(1, "12.00"), cuota(2, "12.00")), listOf(escrita(1, 1, "8.00"), escrita(1, 2, "10.00")))
        assertEquals(listOf(3 to null, 1 to null), p.map { it.revision to it.escrita })
    }

    @Test
    fun `una cuota en cero no se escribe ni se envia`() {
        assertEquals(listOf(2), prediales(listOf(cuota(1, "0.00"), cuota(2, "5.00")), emptyList()).map { it.cuota.numero })
    }

    @Test
    fun `los resumenes se suman`() {
        val a = ResumenCaja(encoladas = 2, enviadas = 1, pendientes = 1, error = "caja-backend respondió 503")
        val b = ResumenCaja(encoladas = 1, enviadas = 1, sustituidas = 1, retiradas = 1, pagadasEnCaja = 1, conflictos = 1, sinDocumento = 1)
        assertEquals(
            ResumenCaja(3, 2, 1, 1, 1, 1, 1, 1, "caja-backend respondió 503"),
            a + b
        )
        assertEquals("otro", (ResumenCaja() + ResumenCaja(error = "otro")).error)
    }

    @Test
    fun `tras no contestar la espera se duplica hasta una hora`() {
        val base = Duration.ofSeconds(30)
        assertEquals(listOf(30L, 60L, 120L, 240L), (1..4).map { esperaTrasNoContesta(base, it).seconds })
        assertEquals(Duration.ofHours(1), esperaTrasNoContesta(base, 20))
        assertEquals(Duration.ZERO, esperaTrasNoContesta(Duration.ZERO, 5))
    }

    @Test
    fun `el lease cubre tres veces el timeout mas largo`() {
        assertEquals(Duration.ofMinutes(2), lease(CajaProperties()))
        val lenta = CuentaCaja(url = "http://caja", clientId = "rentas", clientSecret = "s", timeout = Duration.ofMinutes(1))
        assertEquals(Duration.ofMinutes(3), lease(CajaProperties(organizaciones = mapOf("ica" to lenta, "perene" to CuentaCaja()))))
    }
}
