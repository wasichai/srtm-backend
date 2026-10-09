package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// the panel of the infracciones on the whole app and PostGIS: the year's actas, RIS and notificadas, and the previas
// that end in the week of today. FICTITIOUS figures: ANIO and SIGUIENTE are years only this class gives rows to (the
// UIT is Ficticios.UIT, the plazos 5 and 15 business days), so their counts are exact; the week is today's, shared with
// every other class, so it is read as a difference
class PanelApiTest : ConSancionesApiTest() {
    @Test
    fun `the year's actas, RIS and notificadas, whatever became of them, and nothing of the next year`() {
        val c = inscribir()
        val codigo = nuevoCodigo()
        for (anio in listOf(ANIO, SIGUIENTE)) {
            uit(anio)
            plazos(anio)
        }
        post(CUIS, version(codigo, "$ANIO-01-01"))

        // the first and the last day of the year count; an anulada was levantada too
        val primera = registrarActa(codigo, c, "$ANIO-01-01")["id"].asString()
        val ultima = registrarActa(codigo, c, "$ANIO-12-31")["id"].asString()
        val anulada = registrarActa(codigo, c, "$ANIO-06-02")["id"].asString()
        post("$ACTAS/$anulada/anulacion", mapOf("motivo" to "Error material", "fecha" to "$ANIO-06-03", "observacion" to "Anulación de prueba"))
        val recurrida = registrarActa(codigo, c, "$ANIO-03-03")["id"].asString()
        registrarActa(codigo, c, "$SIGUIENTE-01-01")

        // a RIS notified, one not found (NO_UBICADO takes no effect), a RGR (it resolves a recurso, it does not
        // sanction) and a RIS of the last acta dictated the next year, rejected (it takes effect)
        notificar(dictar(primera, "$ANIO-02-03")["id"].asString(), "$ANIO-02-10")
        notificar(dictar(recurrida, "$ANIO-04-01")["id"].asString(), "$ANIO-04-02", resultado = "NO_UBICADO")
        val descargo = descargar(recurrida, "$ANIO-03-05")["id"].asString()
        notificar(dictar(recurrida, "$ANIO-04-10", RESOLUCION_RECURSO, descargo)["id"].asString(), "$ANIO-04-11")
        notificar(dictar(ultima, "$SIGUIENTE-01-05")["id"].asString(), "$SIGUIENTE-01-06", resultado = "RECHAZADO")

        val panel = panel("?anio=$ANIO")
        assertEquals(ANIO, panel["anio"].asInt())
        assertEquals(LocalDate.now().toString(), panel["al_dia"].asString())
        assertEquals(4, panel["actas"].asInt(), panel.toString())
        assertEquals(2, panel["resoluciones"].asInt(), panel.toString())
        assertEquals(1, panel["notificadas"].asInt(), panel.toString())
        val siguiente = panel("?anio=$SIGUIENTE")
        assertEquals(listOf(1, 1, 1), listOf(siguiente["actas"], siguiente["resoluciones"], siguiente["notificadas"]).map { it.asInt() })
        // a year nobody wrote: zeros, not an error
        assertEquals(listOf(0, 0, 0), panel("?anio=$VACIO").let { p -> listOf(p["actas"], p["resoluciones"], p["notificadas"]).map { it.asInt() } })
    }

    @Test
    fun `vencen esta semana counts the previas ending monday to sunday of today's week, not a subsanada`() {
        val hoy = LocalDate.now()
        val lunes = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val domingo = lunes.plusDays(6)
        val antes = panel("")
        assertEquals(lunes.toString(), antes["semana"]["desde"].asString())
        assertEquals(domingo.toString(), antes["semana"]["hasta"].asString())

        // dated ten days ago, with the plazo that makes each end on the day it is named after
        val c = inscribir()
        val fecha = hoy.minusDays(10)

        fun previa(vence: LocalDate?) =
            post(NOTIFICACIONES, notificacion("NP-${uniqueDocumento()}", fecha.toString(), c, vence?.let { ChronoUnit.DAYS.between(fecha, it).toInt() }))
        previa(lunes)
        previa(domingo)
        previa(lunes.minusDays(1))
        previa(domingo.plusDays(1))
        previa(null)
        // sunday has not passed: it can be subsanada today, and then it ends nothing. one with an acta is PanelTest's: its
        // acta, dated this year, would need a UIT of the current year in the shared db
        val subsanada = previa(domingo)["id"].asString()
        post("$NOTIFICACIONES/$subsanada/subsanacion", mapOf("observacion" to "Retiró el letrero"))

        val despues = panel("")
        assertEquals(2, despues["vencen_esta_semana"].asInt() - antes["vencen_esta_semana"].asInt(), despues.toString())
    }

    @Test
    fun `the panel has exactly its fields, no coactiva and a nota that says why, and refuses what it does not know`() {
        val panel = panel("")
        assertEquals(
            setOf("anio", "al_dia", "actas", "resoluciones", "notificadas", "vencen_esta_semana", "semana", "coactiva", "nota"),
            panel.propertyNames().toSet()
        )
        assertEquals(LocalDate.now().year, panel["anio"].asInt(), "without anio, the current year")
        assertTrue(panel["coactiva"].isNull, panel.toString())
        assertEquals("En coactiva no existe en srtm: no hay cobranza", panel["nota"].asString())
        assertEquals(setOf("desde", "hasta"), panel["semana"].propertyNames().toSet())

        for ((query, campo) in listOf("?estado=VENCIDA" to "estado", "?anio=dos" to "anio", "?anio=0" to "anio")) {
            val problema = tree(send("GET", "$PANEL$query", null, HttpStatus.UNPROCESSABLE_CONTENT))
            assertEquals(campo, problema["errors"][0]["field"].asString(), problema.toString())
        }
    }

    @Test
    fun `who may read the actas reads the panel, and who may not gets a 403`() {
        val lector = funcionario(listOf(permiso(null, "READ")))
        send("GET", PANEL, null, HttpStatus.OK, lector)
        val ajeno = funcionario(listOf(permiso("contribuyente", "READ")))
        send("GET", PANEL, null, HttpStatus.FORBIDDEN, ajeno)
    }

    private fun panel(query: String): JsonNode = tree(send("GET", "$PANEL$query", null, HttpStatus.OK))

    private companion object {
        const val PANEL = "/api/srtm/infracciones/panel"
        const val NOTIFICACIONES = "/api/srtm/infracciones/notificaciones"
        const val CUIS = "/api/srtm/infracciones/cuis"

        // years only this class gives rows to; VACIO, none at all
        const val ANIO = 1997
        const val SIGUIENTE = 1998
        const val VACIO = 1999
    }
}
