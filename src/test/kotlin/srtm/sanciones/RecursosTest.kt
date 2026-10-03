package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import srtm.sanciones.Ficticios.anulacion
import srtm.sanciones.Ficticios.d
import srtm.sanciones.Ficticios.descargo
import srtm.sanciones.Ficticios.feriados
import srtm.sanciones.Ficticios.plazo
import srtm.sanciones.Ficticios.resolucion
import wasichai.core.common.ConflictException

// rentas' DescargosResolucionesYDepositoTest (the descargo, the resolución and its notificación; the depósito is
// tránsito's) and ElPlazoQueConcedeCadaResolucionJdbcTest's dates. the plazos and feriados are FICTITIOUS
class RecursosTest {
    private val infraccion = d("2026-03-04")
    private val parametros = listOf(plazo(Llaves.DESCARGO_PAPELETA, "5"), plazo(Llaves.RG_RECURSO, "15"), feriados(2026))

    @Test
    fun `a descargo says whether it came in plazo - the last day counts, the late one is recorded`() {
        val enPlazo = Descargos.registrar(infraccion, d("2026-03-06"), parametros).valor!!
        assertEquals(d("2026-03-12"), enPlazo.presentadoHasta)
        assertTrue(enPlazo.enPlazo)
        assertEquals("5 DIAS_HABILES", enPlazo.plazoTexto)
        assertEquals("plazo-DESCARGO_PAPELETA-2026-01-01", enPlazo.plazo)
        assertTrue(Descargos.registrar(infraccion, d("2026-03-12"), parametros).valor!!.enPlazo, "the last day counts")
        val tardio = Descargos.registrar(infraccion, d("2026-03-13"), parametros).valor!!
        assertFalse(tardio.enPlazo)
        // what it writes holds rentas' descargo_plazo_ck
        val fila = DescargoPapeleta(fecha = d("2026-03-13"), presentadoHasta = tardio.presentadoHasta, enPlazo = tardio.enPlazo)
        assertTrue(invariantes(fila).none { it.field == "en_plazo" })
    }

    @Test
    fun `its plazo is the one in force on the infraccion's day, not the presentacion's`() {
        val dos =
            listOf(plazo(Llaves.DESCARGO_PAPELETA, "5", hasta = "2026-03-09"), plazo(Llaves.DESCARGO_PAPELETA, "10", desde = "2026-03-10"), feriados(2026))
        assertEquals(d("2026-03-12"), Descargos.registrar(infraccion, d("2026-03-11"), dos).valor!!.presentadoHasta)
    }

    @Test
    fun `without its plazo or the year's feriados no descargo is computed - each is named`() {
        assertEquals(listOf("PLAZO DESCARGO_PAPELETA 2026", "FERIADOS 2026"), Descargos.registrar(infraccion, infraccion, emptyList()).faltan)
        val sinFeriados = Descargos.registrar(infraccion, infraccion, listOf(plazo(Llaves.DESCARGO_PAPELETA, "5")))
        assertNull(sinFeriados.valor)
        assertEquals(listOf("FERIADOS 2026"), sinFeriados.faltan)
        val e = assertThrows(FaltanSanciones::class.java) { sinFeriados.exigir("registrar el descargo") }
        assertEquals("No se puede registrar el descargo: FERIADOS 2026", e.message)
    }

    @Test
    fun `SE_REDUCE is a 422 - the reduced amount is the ordinance's (D-02b)`() {
        val e =
            assertThrows(NoProcede::class.java) {
                Resoluciones.validar("acta", RESOLUCION_RECURSO, descargo(), "FUNDADO_EN_PARTE", SE_REDUCE, HechosDelActa(resoluciones = listOf(resolucion())))
            }
        assertEquals("No hay regla de reducción: la fija la ordenanza (D-02b)", e.message)
        assertEquals(listOf("efecto"), e.violations.map { it.field })
    }

    @Test
    fun `a RECURSO resolves a descargo, and the fallo goes with the descargo or does not go`() {
        val h = HechosDelActa(resoluciones = listOf(resolucion()))
        assertThrows(NoProcede::class.java) { Resoluciones.validar("acta", RESOLUCION_RECURSO, null, null, null, h) }
        val sinDescargo =
            assertThrows(
                NoProcede::class.java
            ) { Resoluciones.validar("acta", RESOLUCION_ADMINISTRATIVA, null, "FUNDADO", SE_DEJA_SIN_EFECTO, HechosDelActa()) }
        assertTrue(sinDescargo.message!!.contains("Sin descargo no hay fallo"), sinDescargo.message)
        assertThrows(NoProcede::class.java) { Resoluciones.validar("acta", RESOLUCION_RECURSO, descargo(), "FUNDADO", null, h) }
        assertThrows(NoProcede::class.java) { Resoluciones.validar("acta", RESOLUCION_RECURSO, descargo(), null, null, h) }
        assertDoesNotThrow { Resoluciones.validar("acta", RESOLUCION_RECURSO, descargo(), "INFUNDADO", SE_MANTIENE, h) }
        // a RIS without descargo, and one that weighs a descargo with its fallo
        assertDoesNotThrow { Resoluciones.validar("acta", RESOLUCION_ADMINISTRATIVA, null, null, null, HechosDelActa()) }
        assertDoesNotThrow { Resoluciones.validar("acta", RESOLUCION_ADMINISTRATIVA, descargo(), "INFUNDADO", SE_MANTIENE, HechosDelActa()) }
    }

    @Test
    fun `the descargo is this acta's`() {
        val e =
            assertThrows(
                NoProcede::class.java
            ) { Resoluciones.validar("acta", RESOLUCION_RECURSO, descargo(acta = "otra"), "INFUNDADO", SE_MANTIENE, HechosDelActa()) }
        assertEquals(listOf("descargo"), e.violations.map { it.field })
    }

    @Test
    fun `nothing is resolved in an ANULADA or DEJADA_SIN_EFECTO acta`() {
        assertThrows(
            NoProcede::class.java
        ) { Resoluciones.validar("acta", RESOLUCION_ADMINISTRATIVA, null, null, null, HechosDelActa(anulacion = anulacion())) }
        val dejada = HechosDelActa(resoluciones = listOf(resolucion(RESOLUCION_RECURSO, SE_DEJA_SIN_EFECTO, "desc-1")))
        val e = assertThrows(NoProcede::class.java) { Resoluciones.validar("acta", RESOLUCION_ADMINISTRATIVA, null, null, null, dejada) }
        assertTrue(e.message!!.contains("no queda nada que resolver"), e.message)
    }

    @Test
    fun `one RIS per acta and one resolucion per descargo (409)`() {
        val conRis = HechosDelActa(resoluciones = listOf(resolucion()))
        val e = assertThrows(ConflictException::class.java) { Resoluciones.validar("acta", RESOLUCION_ADMINISTRATIVA, null, null, null, conRis) }
        assertTrue(e.message!!.contains("RIS-2026-000001"), e.message)
        val resuelto = HechosDelActa(resoluciones = listOf(resolucion(), resolucion(RESOLUCION_RECURSO, SE_MANTIENE, "desc-1", correlativo = 2)))
        assertThrows(
            ConflictException::class.java
        ) { Resoluciones.validar("acta", RESOLUCION_RECURSO, descargo(id = "desc-1"), "INFUNDADO", SE_MANTIENE, resuelto) }
        assertDoesNotThrow { Resoluciones.validar("acta", RESOLUCION_RECURSO, descargo(id = "desc-2"), "INFUNDADO", SE_MANTIENE, resuelto) }
    }

    @Test
    fun `a tipo that is not one is a 422`() {
        assertThrows(NoProcede::class.java) { Resoluciones.validar("acta", "SANCIONADORA", null, null, null, HechosDelActa()) }
        assertThrows(NoProcede::class.java) { Resoluciones.plazoQueConcede("ORDINARIA") }
    }

    @Test
    fun `a notificacion that takes effect fixes its exigibilidad and its plazo`() {
        for (resultado in listOf("NOTIFICADO", "RECHAZADO")) {
            val e = NotificacionesDeResolucion.exigibilidad(RESOLUCION_ADMINISTRATIVA, resultado, d("2026-08-03"), parametros).valor!!
            assertTrue(e.surteEfecto)
            // 15 business days from 08-04, with 6 august (national): ends 08-26, exigible 08-27
            assertEquals(d("2026-08-27"), e.exigibleDesde)
            assertEquals("15 DIAS_HABILES", e.plazo!!.texto)
            assertEquals("plazo-RG_RECURSO-2026-01-01", e.plazo.parametro)
        }
    }

    @Test
    fun `one that does not take effect makes nothing exigible, and needs no plazo`() {
        val e = NotificacionesDeResolucion.exigibilidad(RESOLUCION_ADMINISTRATIVA, "NO_UBICADO", d("2026-08-03"), emptyList())
        assertEquals(Exigibilidad(false, null, null), e.valor)
        // what it writes holds rentas' notificacion_exigibilidad_ck, both ways
        assertTrue(invariantes(NotificacionResolucion(resultado = "NO_UBICADO")).none { it.field == "exigible_desde" })
        assertTrue(
            invariantes(NotificacionResolucion(resultado = "NOTIFICADO", exigibleDesde = d("2026-08-27"), plazo = "p")).none {
                it.field ==
                    "exigible_desde"
            }
        )
    }

    @Test
    fun `one that takes effect without its plazo or its feriados names them`() {
        assertEquals(
            listOf("PLAZO RG_RECURSO 2026", "FERIADOS 2026"),
            NotificacionesDeResolucion.exigibilidad(RESOLUCION_RECURSO, "NOTIFICADO", d("2026-08-03"), emptyList()).faltan
        )
        // a diligencia late in december counts into the next year
        val sin2027 = NotificacionesDeResolucion.exigibilidad(RESOLUCION_ADMINISTRATIVA, "NOTIFICADO", d("2026-12-15"), parametros)
        assertEquals(listOf("FERIADOS 2027"), sin2027.faltan)
        val con2027 = NotificacionesDeResolucion.exigibilidad(RESOLUCION_ADMINISTRATIVA, "NOTIFICADO", d("2026-12-15"), parametros + feriados(2027))
        assertEquals(emptyList<String>(), con2027.faltan)
    }
}
