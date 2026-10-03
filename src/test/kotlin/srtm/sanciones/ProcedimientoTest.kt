package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import srtm.sanciones.Ficticios.anulacion
import srtm.sanciones.Ficticios.d
import srtm.sanciones.Ficticios.notificacion
import srtm.sanciones.Ficticios.resolucion
import srtm.sanciones.Ficticios.subsanacion
import srtm.sanciones.Procedimiento.CONSTATADA
import srtm.sanciones.Procedimiento.PREVENTIVA
import srtm.sanciones.Procedimiento.SANCIONADA
import wasichai.core.common.ConflictException

// rentas' ProcedimientoSancionadorRepositoryJdbcTest (#397, #411) and InfraccionesAdministrativasControllerTest, on
// the facts instead of the sql: the fase and the estado de la deuda, two vocabularies. rentas' PAGADA and COACTIVA
// cases are not ported: srtm has no cobranza
class ProcedimientoTest {
    private val corte = d("2026-08-13")

    // an acta of 2026-08-10 whose notificación previa of the same day gives 5 days: it ends on 08-15
    private val previa = notificacion("NP-0001", "2026-08-10", 5)

    @Test
    fun `an acta whose notificacion previa is still in plazo is PREVENTIVA`() {
        assertEquals(PREVENTIVA, Procedimiento.fase(HechosDelActa(previa = previa), corte))
    }

    @Test
    fun `without a notificacion previa it is CONSTATADA - the manual allows it`() {
        assertEquals(CONSTATADA, Procedimiento.fase(HechosDelActa(), corte))
    }

    @Test
    fun `with the previa subsanada it is CONSTATADA - it is no longer open`() {
        assertEquals(CONSTATADA, Procedimiento.fase(HechosDelActa(previa = previa, subsanacion = subsanacion(previa)), corte))
    }

    @Test
    fun `with its RIS it is SANCIONADA, and a RECURSO alone does not sanction`() {
        assertEquals(SANCIONADA, Procedimiento.fase(HechosDelActa(previa = previa, resoluciones = listOf(resolucion())), corte))
        val recurso = resolucion(RESOLUCION_RECURSO, efecto = SE_MANTIENE, descargo = "desc-1")
        assertEquals(CONSTATADA, Procedimiento.fase(HechosDelActa(resoluciones = listOf(recurso)), corte))
    }

    @Test
    fun `an anulada acta has no fase - never the closest one`() {
        val h = HechosDelActa(previa = previa, anulacion = anulacion(), resoluciones = listOf(resolucion()))
        assertNull(Procedimiento.fase(h, corte))
        assertEquals(Procedimiento.ANULADA, Procedimiento.estadoDeLaDeuda(h))
    }

    @Test
    fun `any resolucion that leaves the multa without effect ends it, of either tipo and not only the last`() {
        val deja = resolucion(RESOLUCION_RECURSO, efecto = SE_DEJA_SIN_EFECTO, descargo = "desc-1", correlativo = 1)
        val mantiene = resolucion(RESOLUCION_RECURSO, efecto = SE_MANTIENE, descargo = "desc-2", correlativo = 2)
        val h = HechosDelActa(resoluciones = listOf(resolucion(), deja, mantiene))
        assertEquals(Procedimiento.DEJADA_SIN_EFECTO, Procedimiento.estadoDeLaDeuda(h))
        assertNull(Procedimiento.fase(h, corte))
        assertEquals(Procedimiento.PENDIENTE, Procedimiento.estadoDeLaDeuda(HechosDelActa(resoluciones = listOf(resolucion(), mantiene))))
        // the anulación says it first
        assertEquals(Procedimiento.ANULADA, Procedimiento.estadoDeLaDeuda(h.copy(anulacion = anulacion())))
    }

    @Test
    fun `the same acta changes fase when its plazo ends, which is why the date travels`() {
        val h = HechosDelActa(previa = previa)
        assertEquals(PREVENTIVA, Procedimiento.fase(h, d("2026-08-14")))
        assertEquals(PREVENTIVA, Procedimiento.fase(h, d("2026-08-15")), "the last day the administrado is still in plazo (#411)")
        assertEquals(CONSTATADA, Procedimiento.fase(h, d("2026-08-16")))
    }

    @Test
    fun `on the frontier the fase, the padron of vencidas and the subsanacion agree (#411)`() {
        // 2026-08-10 with 9, 10 and 11 days ends on the 19th, the 20th and the 21st; the corte is the 20th
        val corte = d("2026-08-20")
        val previas = listOf(notificacion("NP-411-A", "2026-08-10", 9), notificacion("NP-411-B", "2026-08-10", 10), notificacion("NP-411-C", "2026-08-10", 11))
        for (n in previas) {
            val vencida = Notificaciones.vencida(n, corte)
            assertEquals(if (vencida) CONSTATADA else PREVENTIVA, Procedimiento.fase(HechosDelActa(previa = n), corte), n.numero)
            // the subsanación asks the same question
            if (vencida) {
                assertThrows(NoProcede::class.java) { Notificaciones.exigirSubsanable(n, false, false, corte) }
            } else {
                assertDoesNotThrow { Notificaciones.exigirSubsanable(n, false, false, corte) }
            }
        }
        assertEquals(PREVENTIVA, Procedimiento.fase(HechosDelActa(previa = previas[1]), corte), "the one that ends on the corte can still be subsanada")
        assertEquals(listOf("NP-411-A"), previas.filter { Notificaciones.vencida(it, corte) }.map { it.numero })
    }

    @Test
    fun `a previa without a plazo never ends`() {
        assertEquals(PREVENTIVA, Procedimiento.fase(HechosDelActa(previa = notificacion(plazoDias = null)), d("2030-01-01")))
    }

    @Test
    fun `the filter takes the three fases and names them when given another`() {
        assertEquals(listOf(PREVENTIVA, CONSTATADA, SANCIONADA), Procedimiento.FASES)
        assertNull(Procedimiento.faseDelFiltro(null))
        assertNull(Procedimiento.faseDelFiltro(" "))
        assertEquals(SANCIONADA, Procedimiento.faseDelFiltro("SANCIONADA"))
        for (otra in listOf("PAGADA", "COACTIVA", "ANULADA", "X")) {
            val e = assertThrows(NoProcede::class.java) { Procedimiento.faseDelFiltro(otra) }
            assertEquals(listOf("fase"), e.violations.map { it.field })
            assertTrue(e.message!!.contains("PREVENTIVA, CONSTATADA, SANCIONADA"), e.message)
        }
    }

    @Test
    fun `the filter finds exactly what the column shows, and an acta without fase under none`() {
        val actas =
            mapOf(
                "AC-F-PREV" to HechosDelActa(previa = previa),
                "AC-F-CONS" to HechosDelActa(),
                "AC-F-SANC" to HechosDelActa(resoluciones = listOf(resolucion())),
                "AC-F-ANUL" to HechosDelActa(resoluciones = listOf(resolucion()), anulacion = anulacion())
            )
        for (fase in Procedimiento.FASES) {
            val filtradas = actas.filterValues { Procedimiento.fase(it, corte) == Procedimiento.faseDelFiltro(fase) }.keys
            assertEquals(1, filtradas.size, fase)
            assertTrue("AC-F-ANUL" !in filtradas)
        }
    }

    @Test
    fun `nothing left to answer in an ANULADA or DEJADA_SIN_EFECTO acta (422), and an anulacion once (409)`() {
        val anulada = HechosDelActa(anulacion = anulacion())
        val dejada = HechosDelActa(resoluciones = listOf(resolucion(efecto = SE_DEJA_SIN_EFECTO, descargo = "desc-1")))
        assertThrows(NoProcede::class.java) { Procedimiento.exigirQueQuedeAlgoQue("impugnar", anulada) }
        val e = assertThrows(NoProcede::class.java) { Procedimiento.exigirQueQuedeAlgoQue("impugnar", dejada) }
        assertEquals("Una resolución dejó sin efecto la multa: no queda nada que impugnar", e.message)
        assertDoesNotThrow { Procedimiento.exigirQueQuedeAlgoQue("impugnar", HechosDelActa(resoluciones = listOf(resolucion()))) }
        assertThrows(ConflictException::class.java) { Procedimiento.exigirAnulable(anulada) }
        assertThrows(NoProcede::class.java) { Procedimiento.exigirAnulable(dejada) }
        assertDoesNotThrow { Procedimiento.exigirAnulable(HechosDelActa(previa = previa, resoluciones = listOf(resolucion()))) }
        assertEquals("El acta ya está anulada", Procedimiento.impedimentoDeAnular(anulada))
        assertNull(Procedimiento.impedimento("resolver", HechosDelActa()))
    }
}
