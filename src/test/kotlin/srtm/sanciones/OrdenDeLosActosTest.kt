package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.http.HttpStatus
import srtm.sanciones.Ficticios.d

// rentas' OrdenDeLosActos (#402): no act before the act it answers nor after today; the same day is fine
class OrdenDeLosActosTest {
    private val hoy = d("2026-03-20")
    private val infraccion = ActoPrevio("la infracción del acta AC-0001", d("2026-03-04"))
    private val presentacion = ActoPrevio("la presentación del descargo EXP-0001", d("2026-03-10"))

    @Test
    fun `the same day as the previo and as today is fine`() {
        assertDoesNotThrow { OrdenDeLosActos.exigir("la anulación del acta AC-0001", d("2026-03-04"), hoy, infraccion) }
        assertDoesNotThrow { OrdenDeLosActos.exigir("la anulación del acta AC-0001", hoy, hoy, infraccion) }
        assertDoesNotThrow { OrdenDeLosActos.exigir("la anulación del acta AC-0001", hoy, hoy) }
    }

    @Test
    fun `before the previo is a 422 that names both dates`() {
        val e = assertThrows(ActoFueraDeOrden::class.java) { OrdenDeLosActos.exigir("la anulación del acta AC-0001", d("2026-03-03"), hoy, infraccion) }
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, e.status)
        assertEquals("La anulación del acta AC-0001 no puede fecharse el 2026-03-03: es anterior a la infracción del acta AC-0001, del 2026-03-04", e.message)
        assertEquals(listOf("fecha"), e.violations.map { it.field })
        assertEquals(infraccion, e.previo)
    }

    @Test
    fun `it names the latest previo it breaks, so the date is corrected once`() {
        val e =
            assertThrows(ActoFueraDeOrden::class.java) {
                OrdenDeLosActos.exigir("la resolución", d("2026-03-01"), hoy, listOf(infraccion, presentacion))
            }
        assertEquals(presentacion, e.previo)
    }

    @Test
    fun `after today is a 422 that names today, in the field it is told`() {
        val e =
            assertThrows(ActoFueraDeOrden::class.java) {
                OrdenDeLosActos.exigir("la notificación de la RIS-2026-000001", d("2026-03-21"), hoy, infraccion, campo = "fecha_diligencia")
            }
        assertEquals("La notificación de la RIS-2026-000001 no puede fecharse el 2026-03-21: es posterior a hoy, 2026-03-20", e.message)
        assertEquals(listOf("fecha_diligencia"), e.violations.map { it.field })
        assertNull(e.previo)
    }
}
