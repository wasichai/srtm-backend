package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.sanciones.Ficticios.d
import srtm.sanciones.Ficticios.feriados
import srtm.sanciones.Ficticios.plazo
import java.time.LocalDate

// rentas' CalendarioHabil and Exigibilidad (and their dates in SancionesJdbcTest and
// ElPlazoQueConcedeCadaResolucionJdbcTest), and ElPlazoQueConcedeCadaTipoTest. srtm's calendar always has the fixed
// national feriados (Vencimientos.FERIADOS_NACIONALES) plus each year's FERIADOS row. the movable feriados here are
// FICTITIOUS
class PlazosTest {
    // 2026 declared without movable feriados
    private val sinMovibles = Calendario(mapOf(2026 to emptySet()))

    @Test
    fun `weekends, national feriados and the year's feriados are not business days`() {
        val conUno = Calendario.de(listOf(feriados(2026, "2026-03-09")))
        assertFalse(Plazos.habil(d("2026-03-07"), sinMovibles)) // saturday
        assertFalse(Plazos.habil(d("2026-03-08"), sinMovibles)) // sunday
        assertFalse(Plazos.habil(d("2026-07-28"), sinMovibles)) // tuesday, fiestas patrias
        assertTrue(Plazos.habil(d("2026-03-09"), sinMovibles))
        assertFalse(Plazos.habil(d("2026-03-09"), conUno), "a monday the year's row declares")
    }

    @Test
    fun `siguienteHabil is strictly after, sumarHabiles does not count the first day`() {
        assertEquals(d("2026-03-05"), Plazos.siguienteHabil(d("2026-03-04"), sinMovibles))
        assertEquals(d("2026-03-09"), Plazos.siguienteHabil(d("2026-03-06"), sinMovibles)) // friday to monday
        assertEquals(d("2026-03-04"), Plazos.sumarHabiles(d("2026-03-04"), 0, sinMovibles))
        assertEquals(d("2026-03-10"), Plazos.sumarHabiles(d("2026-03-05"), 3, sinMovibles))
        assertThrows(IllegalArgumentException::class.java) { Plazos.sumarHabiles(d("2026-03-05"), -1, sinMovibles) }
    }

    @Test
    fun `a descargo against an infraccion of wednesday 2026-03-04 with 5 business days, by 2026-03-12`() {
        // rentas' example: 03-05 is the first business day, then 06, 09, 10, 11, 12
        assertEquals(d("2026-03-12"), Plazos.hasta(d("2026-03-04"), 5, sinMovibles))
        // a movable feriado inside it moves it a day
        assertEquals(d("2026-03-13"), Plazos.hasta(d("2026-03-04"), 5, Calendario.de(listOf(feriados(2026, "2026-03-10")))))
    }

    @Test
    fun `a resolucion notified on 2026-08-03 with 15 business days, exigible on 2026-08-26 without feriados`() {
        // rentas' example counts no feriado: surte efecto 08-04, ends 08-25, exigible 08-26
        val ninguno = Calendario(mapOf(2026 to emptySet()))
        val sinNingunFeriado = generateSequence(d("2026-08-05")) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(15).last()
        assertEquals(d("2026-08-25"), sinNingunFeriado)
        // srtm always has the national ones: 6 august (batalla de junín) is a thursday in 2026, so it ends a day later
        assertFalse(Plazos.habil(d("2026-08-06"), ninguno))
        assertEquals(d("2026-08-27"), Plazos.exigibleDesde(d("2026-08-03"), 15, ninguno))
        // rentas' other example, 7 days from the same diligencia (exigible 08-14 there), crosses the same thursday: it
        // ends on 08-14 here
        assertEquals(d("2026-08-14"), Plazos.hasta(d("2026-08-03"), 7, ninguno))
    }

    @Test
    fun `a count names each year's FERIADOS it needed and does not have`() {
        val solo2026 = Calendario(mapOf(2026 to emptySet()))
        assertEquals(emptyList<String>(), Plazos.faltan(solo2026, d("2026-03-04"), d("2026-03-12")))
        // a count across the new year needs both years
        val hasta = Plazos.hasta(d("2026-12-28"), 5, solo2026)
        assertEquals(listOf("FERIADOS 2027"), Plazos.faltan(solo2026, d("2026-12-28"), hasta))
        assertEquals(listOf("FERIADOS 2026"), Plazos.faltan(Calendario(emptyMap()), d("2026-03-04"), d("2026-03-12")))
        // the first day is not counted: from 31 december only the next year is touched
        assertEquals(listOf("FERIADOS 2027"), Plazos.faltan(Calendario(emptyMap()), d("2026-12-31"), d("2027-01-08")))
    }

    @Test
    fun `a malformed FERIADOS row is missing, with why`() {
        val c = Calendario.de(listOf(feriados(2026, "2026-04-02", "2025-04-03")))
        assertFalse(2026 in c.feriados)
        assertEquals(listOf("FERIADOS 2026: no son fechas AAAA-MM-DD de 2026 separadas por comas"), Plazos.faltan(c, d("2026-03-04"), d("2026-03-12")))
        // an empty one is a year declared without movable feriados
        assertEquals(mapOf(2026 to emptySet<LocalDate>()), Calendario.de(listOf(feriados(2026))).feriados)
    }

    @Test
    fun `a plazo is its PLAZO row in force on the day, never a default`() {
        val filas = listOf(plazo(Llaves.DESCARGO_PAPELETA, "5", hasta = "2026-06-30"), plazo(Llaves.DESCARGO_PAPELETA, "7", desde = "2026-07-01"))
        val marzo = Plazos.plazo(filas, Llaves.DESCARGO_PAPELETA, d("2026-03-04")).valor!!
        assertEquals(5, marzo.dias)
        assertEquals("5 DIAS_HABILES", marzo.texto)
        assertEquals("plazo-DESCARGO_PAPELETA-2026-01-01", marzo.parametro)
        assertEquals(7, Plazos.plazo(filas, Llaves.DESCARGO_PAPELETA, d("2026-07-01")).valor!!.dias)
        assertEquals(listOf("PLAZO RG_RECURSO 2026"), Plazos.plazo(filas, Llaves.RG_RECURSO, d("2026-03-04")).faltan)
        assertEquals(listOf("PLAZO DESCARGO_PAPELETA 2025"), Plazos.plazo(filas, Llaves.DESCARGO_PAPELETA, d("2025-12-31")).faltan)
    }

    @Test
    fun `a plazo that is not a whole number of DIAS_HABILES is missing, with why`() {
        for (fila in listOf(plazo(Llaves.RG_RECURSO, "2.5"), plazo(Llaves.RG_RECURSO, "0"), plazo(Llaves.RG_RECURSO, "15", unidad = "DIAS_CALENDARIO"))) {
            val r = Plazos.plazo(listOf(fila), Llaves.RG_RECURSO, d("2026-03-04"))
            assertEquals(listOf("PLAZO RG_RECURSO 2026: es un número entero de días mayor que 0 en DIAS_HABILES"), r.faltan, fila.toString())
        }
        assertEquals(15, Plazos.plazo(listOf(plazo(Llaves.RG_RECURSO, "15.00")), Llaves.RG_RECURSO, d("2026-03-04")).valor!!.dias)
    }

    @Test
    fun `the RIS and the RGR grant the plazo to appeal, and without it they do not borrow another (#410)`() {
        // distinct figures in each key: answering with another key's would show
        val filas = listOf(plazo(Llaves.DESCARGO_PAPELETA, "5"), plazo(Llaves.RG_RECURSO, "15"))
        for (tipo in Resoluciones.TIPOS) {
            assertEquals(Llaves.RG_RECURSO, Resoluciones.plazoQueConcede(tipo))
            assertEquals("15 DIAS_HABILES", Resoluciones.plazo(tipo, d("2026-08-03"), filas).valor!!.texto)
        }
        val sinRecurso = Resoluciones.plazo(RESOLUCION_ADMINISTRATIVA, d("2026-08-03"), listOf(plazo(Llaves.DESCARGO_PAPELETA, "5")))
        assertEquals(listOf("PLAZO RG_RECURSO 2026"), sinRecurso.faltan)
    }
}
