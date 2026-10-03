package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

// the notificaciones previas of the portal on the whole app and PostGIS: the padrón with what is derived at a day, the
// alta, the subsanación with the frontier of #411 (the last day still counts), the vencidas and the ones of a
// contribuyente. FICTITIOUS figures
class NotificacionesApiTest : ConSancionesApiTest() {
    @Test
    fun `a notificación is registered and answered with what is derived from it today`() {
        val c = inscribir()
        val hoy = LocalDate.now()
        val numero = numero()
        val n = post(NOTIFICACIONES, notificacion(" ${numero.lowercase()} ", hoy.minusDays(2).toString(), c, plazo = 5))

        assertEquals(numero, n["numero"].asString())
        assertEquals(hoy.minusDays(2).toString(), n["fecha"].asString())
        assertEquals("JR. LIMA 123", n["direccion"].asString())
        assertEquals("Letrero sin licencia", n["motivo"].asString())
        assertEquals(5, n["plazo_dias"].asInt())
        assertEquals("Notificación de prueba", n["observacion"].asString())
        assertEquals(c, n["contribuyente"].asString())
        assertTrue(n["predio"].isNull, n.toString())
        assertEquals(hoy.plusDays(3).toString(), n["vencimiento"].asString())
        assertFalse(n["vencida"].asBoolean())
        assertEquals(hoy.toString(), n["vencidas_a"].asString())
        assertTrue(n["subsanada"].isNull && n["acta"].isNull, n.toString())
        assertEquals("FLORES OTINIANO JUNIOR", n["contribuyente_nombre"].asString())
        assertFalse(n.has("corte"), n.toString())

        // without a plazo it never ends
        val sinPlazo = post(NOTIFICACIONES, notificacion(numero(), "2026-01-05", c, plazo = null))
        assertTrue(sinPlazo["vencimiento"].isNull, sinPlazo.toString())
        assertFalse(sinPlazo["vencida"].asBoolean())
    }

    @Test
    fun `an alta that cannot be is refused, and says why`() {
        val c = inscribir()
        val numero = numero()
        post(NOTIFICACIONES, notificacion(numero, "2026-03-02", c))

        send("POST", NOTIFICACIONES, notificacion(numero(), "2026-03-02", UUID.randomUUID().toString()), HttpStatus.NOT_FOUND)
        send("POST", NOTIFICACIONES, notificacion(numero(), "2026-03-02", c) + ("predio" to UUID.randomUUID().toString()), HttpStatus.NOT_FOUND)
        val repetida = tree(send("POST", NOTIFICACIONES, notificacion(numero.lowercase(), "2026-03-02", c), HttpStatus.CONFLICT))
        assertTrue(repetida["detail"].asString().contains(numero), repetida.toString())
        val futura = tree(send("POST", NOTIFICACIONES, notificacion(numero(), LocalDate.now().plusDays(1).toString(), c), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals("fecha", futura["errors"][0]["field"].asString(), futura.toString())
        rejected("POST", NOTIFICACIONES, notificacion(numero(), "2026-03-02", c) + ("observacion" to "no"), "observacion")
        rejected("POST", NOTIFICACIONES, notificacion(numero(), "2026-03-02", c, plazo = 0), "plazo_dias")
        rejected("POST", NOTIFICACIONES, notificacion(numero(), "2026-03-02", "no-es-un-id"), "contribuyente")
    }

    @Test
    fun `the padrón filters by número, contribuyente and fecha, and derives vencida at vencidas_a`() {
        val c = inscribir()
        val numero = numero()
        val id = post(NOTIFICACIONES, notificacion(numero, "2026-03-02", c, plazo = 5))["id"].asString()
        post(NOTIFICACIONES, notificacion(numero(), "2026-04-10", c))

        // the last day of the plazo (fecha + 5) it is not vencida yet; the day after it is (#411)
        assertFalse(fila("numero=$numero&vencidas_a=2026-03-07")["vencida"].asBoolean())
        val vencida = fila("numero=${numero.lowercase()}&vencidas_a=2026-03-08")
        assertTrue(vencida["vencida"].asBoolean())
        assertEquals("2026-03-07", vencida["vencimiento"].asString())
        assertEquals("2026-03-08", vencida["vencidas_a"].asString())
        assertEquals(id, vencida["id"].asString())

        val delContribuyente = pagina("contribuyente=$c")
        assertEquals(2, delContribuyente["totalElements"].asInt())
        // the newest first
        assertEquals("2026-04-10", delContribuyente["content"][0]["fecha"].asString())
        assertEquals(listOf(numero), pagina("contribuyente=$c&desde=2026-03-01&hasta=2026-03-02")["content"].filas().map { it["numero"].asString() })
        assertEquals(1, pagina("contribuyente=$c&desde=2026-03-03")["totalElements"].asInt())
        val primera = pagina("contribuyente=$c&page=1&size=1")
        assertEquals(1, primera["page"].asInt())
        assertEquals(2, primera["totalPages"].asInt())

        for ((query, campo) in listOf("estado=EMITIDA" to "estado", "contribuyente=x" to "contribuyente", "desde=2026-3-1" to "desde", "size=0" to "size")) {
            val problema = tree(send("GET", "$NOTIFICACIONES?$query", null, HttpStatus.UNPROCESSABLE_CONTENT))
            assertEquals(campo, problema["errors"][0]["field"].asString(), problema.toString())
        }
    }

    @Test
    fun `a notificación is subsanada once, up to the last day of its plazo`() {
        val c = inscribir()
        val enPlazo = post(NOTIFICACIONES, notificacion(numero(), "2026-03-02", c, plazo = 5))["id"].asString()
        val tarde = post(NOTIFICACIONES, notificacion(numero(), "2026-03-02", c, plazo = 5))["id"].asString()

        // the last day still counts (#411)
        val s = post(subsanacion(enPlazo), mapOf("fecha" to "2026-03-07", "observacion" to "Retiró el letrero"))
        assertEquals("2026-03-07", s["fecha"].asString())
        assertEquals(enPlazo, s["notificacion"].asString())
        assertEquals(enPlazo, s["clave"].asString())
        assertEquals("Retiró el letrero", s["observacion"].asString())
        val padron = fila("numero=${registro(NOTIFICACION_ADMINISTRATIVA, enPlazo)["numero"].asString()}")
        assertEquals("2026-03-07", padron["subsanada"]["fecha"].asString())

        // once
        send("POST", subsanacion(enPlazo), mapOf("fecha" to "2026-03-07", "observacion" to "Otra vez"), HttpStatus.CONFLICT)
        // the day after, it is vencida
        val vencida = tree(send("POST", subsanacion(tarde), mapOf("fecha" to "2026-03-08", "observacion" to "Tarde"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(vencida["detail"].asString().contains("venció el 07/03/2026"), vencida.toString())
        // not after today, nor before the notificación
        for (fecha in listOf(LocalDate.now().plusDays(1).toString(), "2026-03-01")) {
            send("POST", subsanacion(tarde), mapOf("fecha" to fecha, "observacion" to "Fuera de orden"), HttpStatus.UNPROCESSABLE_CONTENT)
        }
        rejected("POST", subsanacion(tarde), mapOf("observacion" to "no"), "observacion")
        send("POST", subsanacion(UUID.randomUUID().toString()), mapOf("observacion" to "No existe"), HttpStatus.NOT_FOUND)

        // without a fecha, today: a notificación of today with a plazo
        val hoy = post(NOTIFICACIONES, notificacion(numero(), LocalDate.now().toString(), c, plazo = 3))["id"].asString()
        assertEquals(LocalDate.now().toString(), post(subsanacion(hoy), mapOf("observacion" to "Subsanó hoy"))["fecha"].asString())
    }

    @Test
    fun `a notificación that originated an acta is not subsanada, and the padrón names its acta`() {
        val c = inscribir()
        uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val numero = numero()
        val n = post(NOTIFICACIONES, notificacion(numero, "$ANIO_PASADO-03-02", c, plazo = 5))["id"].asString()
        val acta = registrarActa(codigo, c, "$ANIO_PASADO-03-04", previa = n)

        val problema =
            tree(send("POST", subsanacion(n), mapOf("fecha" to "$ANIO_PASADO-03-04", "observacion" to "Con acta"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(problema["detail"].asString().contains("acta"), problema.toString())
        val fila = fila("numero=$numero")
        assertEquals(acta["id"].asString(), fila["acta"]["id"].asString())
        assertEquals(acta["numero"].asString(), fila["acta"]["numero"].asString())
    }

    @Test
    fun `the vencidas at a corte are the ones neither subsanadas nor with an acta`() {
        val c = inscribir()
        uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val vencida = post(NOTIFICACIONES, notificacion(numero(), "$ANIO_PASADO-05-04", c, plazo = 5))["id"].asString()
        val ultimoDia = post(NOTIFICACIONES, notificacion(numero(), "$ANIO_PASADO-05-05", c, plazo = 5))["id"].asString()
        val subsanada = post(NOTIFICACIONES, notificacion(numero(), "$ANIO_PASADO-05-04", c, plazo = 5))["id"].asString()
        val conActa = post(NOTIFICACIONES, notificacion(numero(), "$ANIO_PASADO-05-04", c, plazo = 5))["id"].asString()
        val sinPlazo = post(NOTIFICACIONES, notificacion(numero(), "$ANIO_PASADO-05-04", c, plazo = null))["id"].asString()
        post(subsanacion(subsanada), mapOf("fecha" to "$ANIO_PASADO-05-06", "observacion" to "Retiró el letrero"))
        registrarActa(codigo, c, "$ANIO_PASADO-05-04", previa = conActa)

        // corte the 10th: the one of the 4th ended on the 9th; the one of the 5th has its last day
        val filas = vencidas("$ANIO_PASADO-05-10").filter { it["contribuyente"].asString() == c }
        assertEquals(listOf(vencida), filas.map { it["id"].asString() })
        val f = filas.single()
        assertEquals("$ANIO_PASADO-05-09", f["vencimiento"].asString())
        assertEquals("$ANIO_PASADO-05-10", f["corte"].asString())
        assertTrue(f["vencida"].asBoolean())
        assertEquals("FLORES OTINIANO JUNIOR", f["contribuyente_nombre"].asString())
        assertEquals(
            setOf(vencida, ultimoDia),
            vencidas("$ANIO_PASADO-05-11").filter { it["contribuyente"].asString() == c }.map { it["id"].asString() }.toSet()
        )
        assertFalse(vencidas("$ANIO_PASADO-12-31").any { it["id"].asString() == sinPlazo })

        assertEquals("corte", tree(send("GET", "$NOTIFICACIONES/vencidas?corte=ayer", null, HttpStatus.UNPROCESSABLE_CONTENT))["errors"][0]["field"].asString())
    }

    @Test
    fun `the notificaciones of a contribuyente`() {
        val c = inscribir()
        val otro = inscribir()
        val mia = post(NOTIFICACIONES, notificacion(numero(), "2026-03-02", c))["id"].asString()
        post(NOTIFICACIONES, notificacion(numero(), "2026-03-02", otro))

        val suyas = tree(send("GET", "$NOTIFICACIONES/por-contribuyente?contribuyente=$c", null, HttpStatus.OK))
        assertEquals(listOf(mia), suyas["content"].filas().map { it["id"].asString() })
        assertEquals(LocalDate.now().toString(), suyas["content"][0]["vencidas_a"].asString())
        assertTrue(suyas["content"][0]["vencida"].asBoolean())
        send("GET", "$NOTIFICACIONES/por-contribuyente?contribuyente=${UUID.randomUUID()}", null, HttpStatus.NOT_FOUND)
        val sin = tree(send("GET", "$NOTIFICACIONES/por-contribuyente", null, HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals("contribuyente", sin["errors"][0]["field"].asString())
    }

    @Test
    fun `only srtm writes them, and who may only read gets a 403 that names the object`() {
        val c = inscribir()
        val n = post(NOTIFICACIONES, notificacion(numero(), "2026-03-02", c))["id"].asString()
        val s = post(subsanacion(n), mapOf("fecha" to "2026-03-03", "observacion" to "Retiró el letrero"))["id"].asString()
        for ((objeto, id) in listOf(NOTIFICACION_ADMINISTRATIVA to n, SUBSANACION_NOTIFICACION to s)) {
            val registro = "/api/objects/$objeto/records/$id"
            val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
            send("PUT", registro, mapOf("attributes" to atributos + ("observacion" to "Cambiada por la API")), HttpStatus.CONFLICT)
            send("DELETE", registro, null, HttpStatus.CONFLICT)
        }
        send("POST", "/api/objects/$NOTIFICACION_ADMINISTRATIVA/records", mapOf("attributes" to Ejemplos.notificacion(numero(), c)), HttpStatus.FORBIDDEN)

        val lector = funcionario(listOf(permiso(null, "READ")))
        assertEquals(1, tree(send("GET", "$NOTIFICACIONES?contribuyente=$c", null, HttpStatus.OK, lector))["totalElements"].asInt())
        val alta = tree(send("POST", NOTIFICACIONES, notificacion(numero(), "2026-03-02", c), HttpStatus.FORBIDDEN, lector))
        assertTrue(alta["detail"].asString().contains(NOTIFICACION_ADMINISTRATIVA), alta.toString())
        val otra = post(NOTIFICACIONES, notificacion(numero(), "2026-03-02", c))["id"].asString()
        val sub = tree(send("POST", subsanacion(otra), mapOf("fecha" to "2026-03-03", "observacion" to "Sin permiso"), HttpStatus.FORBIDDEN, lector))
        assertTrue(sub["detail"].asString().contains(SUBSANACION_NOTIFICACION), sub.toString())
    }

    private fun numero() = "NP-${uniqueDocumento()}"

    private fun subsanacion(id: String) = "$NOTIFICACIONES/$id/subsanacion"

    private fun pagina(query: String): JsonNode = tree(send("GET", "$NOTIFICACIONES?$query", null, HttpStatus.OK))

    // the one row a query by número gives
    private fun fila(query: String): JsonNode = pagina(query)["content"].filas().single()

    // every vencida at `corte`, page by page
    private fun vencidas(corte: String): List<JsonNode> {
        val filas = mutableListOf<JsonNode>()
        var page = 0
        do {
            val p = tree(send("GET", "$NOTIFICACIONES/vencidas?corte=$corte&page=$page&size=200", null, HttpStatus.OK))
            filas += p["content"].filas()
            page++
        } while (page < p["totalPages"].asInt())
        return filas
    }

    private companion object {
        const val NOTIFICACIONES = "/api/srtm/infracciones/notificaciones"
        const val CUIS = "/api/srtm/infracciones/cuis"
    }
}
