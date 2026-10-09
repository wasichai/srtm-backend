package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import srtm.sanciones.Ficticios.d
import srtm.sanciones.Ficticios.notificacion
import srtm.sanciones.Ficticios.subsanacion
import wasichai.core.common.ConflictException

// rentas' NotificacionAdministrativaTest and SubsanarNotificacionTest: when a notificación previa ends (#411, one
// definition) and when it can be subsanada (decision 5)
class NotificacionesTest {
    private val fecha = d("2026-03-01")

    @Test
    fun `without a plazo there is no vencimiento, with one it is fecha plus plazo`() {
        assertNull(Notificaciones.vencimiento(fecha, null))
        assertEquals(d("2026-03-11"), Notificaciones.vencimiento(fecha, 10))
    }

    @Test
    fun `the last day of the plazo it is not vencida yet, the next day it is (#411)`() {
        val vence = fecha.plusDays(10)
        assertFalse(Notificaciones.vencida(fecha, 10, vence.minusDays(1)))
        assertFalse(Notificaciones.vencida(fecha, 10, vence), "the day it ends it can still be subsanada")
        assertTrue(Notificaciones.vencida(fecha, 10, vence.plusDays(1)))
    }

    @Test
    fun `without a plazo it never ends (#47 AC3, #411)`() {
        assertFalse(Notificaciones.vencida(fecha, null, fecha.plusYears(50)))
    }

    @Test
    fun `a subsanacion within the plazo, and on its last day, is admitted`() {
        val n = notificacion(fecha = "2026-03-01", plazoDias = 10)
        assertDoesNotThrow { Notificaciones.exigirSubsanable(n, subsanada = false, conActa = false, fecha = d("2026-03-05")) }
        assertDoesNotThrow { Notificaciones.exigirSubsanable(n, subsanada = false, conActa = false, fecha = d("2026-03-11")) }
    }

    @Test
    fun `after the vencimiento it is not subsanada - a 422 that names the day it ended`() {
        val n = notificacion(fecha = "2026-03-01", plazoDias = 10)
        val e = assertThrows(NoProcede::class.java) { Notificaciones.exigirSubsanable(n, subsanada = false, conActa = false, fecha = d("2026-03-12")) }
        assertTrue(e.message!!.contains("venció el 11/03/2026: no se subsana el 12/03/2026"), e.message)
    }

    @Test
    fun `without a plazo it is always subsanable`() {
        val n = notificacion(plazoDias = null)
        assertDoesNotThrow { Notificaciones.exigirSubsanable(n, subsanada = false, conActa = false, fecha = d("2030-01-01")) }
    }

    @Test
    fun `a subsanada one is not subsanada again (409), nor one that has its acta (422)`() {
        val n = notificacion()
        assertThrows(ConflictException::class.java) { Notificaciones.exigirSubsanable(n, subsanada = true, conActa = false, fecha = n.fecha!!) }
        assertThrows(NoProcede::class.java) { Notificaciones.exigirSubsanable(n, subsanada = false, conActa = true, fecha = n.fecha!!) }
    }

    @Test
    fun `a subsanada notificacion originates no acta (422)`() {
        val n = notificacion()
        assertThrows(NoProcede::class.java) { Notificaciones.exigirQueOrigineActa(n, subsanada = true) }
        assertDoesNotThrow { Notificaciones.exigirQueOrigineActa(n, subsanada = false) }
    }

    @Test
    fun `derived at a past day, a subsanación or an acta dated after it had not happened yet`() {
        val n = notificacion("NP-0001", "2026-03-01", 10)
        val hechos =
            HechosDeNotificaciones(
                mapOf(n.id!! to subsanacion(n).copy(fecha = d("2026-03-05"))),
                mapOf(n.id!! to Papeleta(id = "acta", numero = "AC-0001", fechaInfraccion = d("2026-03-08"))),
                emptyMap()
            )

        val antes = NotificacionesService.derivar(n, d("2026-03-04"), hechos)
        assertNull(antes.subsanada)
        assertNull(antes.acta)
        assertEquals(d("2026-03-04"), antes.vencidasA)
        // the same day counts
        assertEquals(Subsanada(d("2026-03-05")), NotificacionesService.derivar(n, d("2026-03-05"), hechos).subsanada)
        assertNull(NotificacionesService.derivar(n, d("2026-03-07"), hechos).acta)
        val despues = NotificacionesService.derivar(n, d("2026-03-08"), hechos)
        assertEquals(ActaDeLaNotificacion("acta", "AC-0001"), despues.acta)
        assertEquals(d("2026-03-11"), despues.vencimiento)
        assertFalse(despues.vencida)
        assertTrue(NotificacionesService.derivar(n, d("2026-03-12"), hechos).vencida)
    }
}
