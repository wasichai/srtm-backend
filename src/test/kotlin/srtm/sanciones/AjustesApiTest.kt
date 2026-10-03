package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate

// what the end-to-end verification adjusted (SPEC §10), on the whole app and PostGIS: nothing is notified of an acta
// anulada or dejada sin efecto (and the panel does not count it notified), the partial search of the notificaciones,
// the readable detalle, the partes of the expediente, the plazos loaded and the padrón at a past day. FICTITIOUS
// figures, in years only this class gives rows to
class AjustesApiTest : ConSancionesApiTest() {
    @Test
    fun `nothing of an acta anulada is notified, of one dejada sin efecto only the resolución that did it, and the panel knows`() {
        val (c, codigo) = preparar(ANIO)
        val anulada = registrarActa(codigo, c, "$ANIO-03-06")["id"].asString()
        val sinEfecto = registrarActa(codigo, c, "$ANIO-03-06")["id"].asString()
        val pendiente = registrarActa(codigo, c, "$ANIO-03-06")["id"].asString()

        // each RIS notified while the acta was pending
        val risAnulada = dictar(anulada, "$ANIO-03-20")["id"].asString()
        notificar(risAnulada, "$ANIO-03-21")
        post("$ACTAS/$anulada/anulacion", mapOf("motivo" to "Error material", "fecha" to "$ANIO-03-25", "observacion" to "Anulación de prueba"))
        val risSinEfecto = dictar(sinEfecto, "$ANIO-03-20")["id"].asString()
        notificar(risSinEfecto, "$ANIO-03-21")
        val descargo = descargar(sinEfecto, "$ANIO-03-08")["id"].asString()
        val rgr = dictar(sinEfecto, "$ANIO-03-27", RESOLUCION_RECURSO, descargo, efecto = SE_DEJA_SIN_EFECTO)["id"].asString()
        val risPendiente = dictar(pendiente, "$ANIO-03-20")["id"].asString()
        notificar(risPendiente, "$ANIO-03-21")

        for ((ris, motivo) in listOf(
            risAnulada to "El acta está anulada: no queda nada que notificar",
            risSinEfecto to "Una resolución dejó sin efecto la multa: esta ya no se notifica"
        )) {
            val problema = tree(send("POST", "$RESOLUCIONES/$ris/notificacion", pedidoNotificacion("$ANIO-03-30"), HttpStatus.UNPROCESSABLE_CONTENT))
            assertEquals(motivo, problema["detail"].asString(), problema.toString())
        }
        notificar(risPendiente, "$ANIO-03-30", resultado = "NO_UBICADO")

        // each resolución of the ficha carries the same text: the RGR that left the multa without effect is notifiable
        val fichaAnulada = expediente(anulada)
        assertEquals(
            listOf(false to "El acta está anulada: no queda nada que notificar"),
            fichaAnulada["resoluciones"].filas().map { it["acciones"]["notificacion"].let { a -> a["permitida"].asBoolean() to a["motivo"].asString() } }
        )
        val fichaSinEfecto = expediente(sinEfecto)
        assertEquals(
            mapOf(
                risSinEfecto to (false to "Una resolución dejó sin efecto la multa: esta ya no se notifica"),
                rgr to (true to null)
            ),
            fichaSinEfecto["resoluciones"].filas().associate {
                val a = it["acciones"]["notificacion"]
                it["id"].asString() to (a["permitida"].asBoolean() to a["motivo"].takeUnless { m -> m.isNull }?.asString())
            }
        )
        val notificable = expediente(pendiente)["resoluciones"][0]["acciones"]["notificacion"]
        assertTrue(notificable["permitida"].asBoolean())
        assertTrue(notificable["motivo"].isNull, notificable.toString())

        // the detalle reads in words and dd/MM/yyyy
        val actos = fichaSinEfecto["actos"].filas().associate { it["acto"].asString() + "|" + it["documento"].asString() to it["detalle"].asString() }
        val numeroRgr = fichaSinEfecto["resoluciones"].filas().single { it["tipo"].asString() == RESOLUCION_RECURSO }["numero"].asString()
        assertEquals("Infundado, se deja sin efecto. Sin notificar", actos["Resolución del recurso|$numeroRgr"], actos.toString())
        val numeroRis = fichaSinEfecto["resoluciones"].filas().single { it["tipo"].asString() == RESOLUCION_ADMINISTRATIVA }["numero"].asString()
        assertEquals("Notificada el 21/03/$ANIO", actos["Resolución de sanción|$numeroRis"], actos.toString())
        assertTrue(actos["Notificación de la resolución|$numeroRis (intento 1)"]!!.startsWith("Notificado, exigible desde el "), actos.toString())
        val noUbicado = expediente(pendiente)["actos"].filas().last()
        assertEquals("No ubicado", noUbicado["detalle"].asString())

        // the RGR is served like any other: its plazo (RG_RECURSO) runs from the diligencia
        val notificada = notificar(rgr, "$ANIO-03-30")
        assertEquals(1, notificada["intento"].asInt())
        assertFalse(notificada["exigible_desde"].isNull, notificada.toString())
        assertEquals("15 DIAS_HABILES", notificada["plazo_texto"].asString())

        // the three RIS were dictated; only the pending acta's is notified (a RGR is not a RIS: it does not count)
        val panel = tree(send("GET", "/api/srtm/infracciones/panel?anio=$ANIO", null, HttpStatus.OK))
        assertEquals(listOf(3, 3, 1), listOf(panel["actas"], panel["resoluciones"], panel["notificadas"]).map { it.asInt() }, panel.toString())
    }

    @Test
    fun `the partes of the expediente name the obligado with its domicilio fiscal, the contribuyente and the predio`() {
        val (c, codigo) = preparar(ANIO_PARTES)
        post("/api/srtm/contribuyentes/$c/domicilios", domicilioFiscal())
        val domicilio = registro("contribuyente", c)["domicilio_fiscal"].asString()
        val obligado = registro("contribuyente", c)
        val otro = inscribir()
        val p = predio()
        val conTodo = post(ACTAS, pedidoActa(codigo, c, "$ANIO_PARTES-03-06", contribuyente = otro, predio = p))["id"].asString()

        val partes = expediente(conTodo)["partes"]
        assertEquals(setOf("obligado", "contribuyente", "predio"), partes.propertyNames().toSet())
        assertEquals(setOf("id", "nombre", "documento", "domicilio_fiscal"), partes["obligado"].propertyNames().toSet())
        assertEquals(c, partes["obligado"]["id"].asString())
        assertEquals(obligado["nombre_completo"].asString(), partes["obligado"]["nombre"].asString())
        assertEquals(obligado["numero_documento"].asString(), partes["obligado"]["documento"].asString())
        assertEquals(domicilio, partes["obligado"]["domicilio_fiscal"].asString())
        assertEquals(setOf("id", "nombre", "documento"), partes["contribuyente"].propertyNames().toSet())
        assertEquals(otro, partes["contribuyente"]["id"].asString())
        assertEquals("FLORES OTINIANO JUNIOR", partes["contribuyente"]["nombre"].asString())
        assertEquals(setOf("id", "codigo", "direccion"), partes["predio"].propertyNames().toSet())
        assertEquals(p, partes["predio"]["id"].asString())
        assertEquals(registro("predio", p)["codigo"].asString(), partes["predio"]["codigo"].asString())

        // an obligado without a domicilio fiscal and an acta without a predio: nulls, present
        val sinDomicilio = inscribir()
        val soloContribuyente = registrarActa(codigo, sinDomicilio, "$ANIO_PARTES-03-06")["id"].asString()
        val sin = expediente(soloContribuyente)["partes"]
        assertTrue(sin["obligado"]["domicilio_fiscal"].isNull, sin.toString())
        assertEquals(sinDomicilio, sin["contribuyente"]["id"].asString())
        assertTrue(sin.has("predio") && sin["predio"].isNull, sin.toString())
        val soloPredio = post(ACTAS, pedidoActa(codigo, sinDomicilio, "$ANIO_PARTES-03-06", contribuyente = null, predio = p))["id"].asString()
        assertTrue(expediente(soloPredio)["partes"]["contribuyente"].isNull)
    }

    @Test
    fun `q finds the notificaciones whose número contains it, in any case, and numero stays exact`() {
        val c = inscribir()
        val marca = uniqueDocumento()
        val una = post(NOTIFICACIONES, notificacion("NP-$marca-A", "$ANIO_PADRON-01-05", c))["id"].asString()
        val otra = post(NOTIFICACIONES, notificacion("NP-$marca-B", "$ANIO_PADRON-01-06", c))["id"].asString()
        post(NOTIFICACIONES, notificacion("NP-${uniqueDocumento()}", "$ANIO_PADRON-01-06", c))

        assertEquals(listOf(otra, una), ids("q=np-$marca"))
        assertEquals(listOf(una), ids("q=${marca.takeLast(4)}-a&contribuyente=$c"))
        assertEquals(emptyList<String>(), ids("numero=NP-$marca"), "numero is the whole number")
        // LIKE's wildcards are taken literally
        assertEquals(emptyList<String>(), ids("q=%25&contribuyente=$c"))
        assertEquals(emptyList<String>(), ids("q=_P-&contribuyente=$c"))
    }

    @Test
    fun `the padrón at a past day shows each notificación as it stood then`() {
        val c = inscribir()
        uit(ANIO_PADRON)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PADRON-01-01"))
        val subsanada = post(NOTIFICACIONES, notificacion(np(), "$ANIO_PADRON-03-01", c, plazo = 10))["id"].asString()
        post("$NOTIFICACIONES/$subsanada/subsanacion", mapOf("fecha" to "$ANIO_PADRON-03-05", "observacion" to "Retiró el letrero"))
        val conActa = post(NOTIFICACIONES, notificacion(np(), "$ANIO_PADRON-03-01", c, plazo = 5))["id"].asString()
        val acta = registrarActa(codigo, c, "$ANIO_PADRON-03-15", previa = conActa)["id"].asString()
        val posterior = post(NOTIFICACIONES, notificacion(np(), "$ANIO_PADRON-03-20", c, plazo = 5))["id"].asString()

        // on the 4th: neither subsanada nor with an acta, and the one of the 20th did not exist yet
        val dia4 = filas("contribuyente=$c&vencidas_a=$ANIO_PADRON-03-04")
        assertEquals(setOf(subsanada, conActa), dia4.keys)
        assertTrue(dia4.values.all { it["subsanada"].isNull && it["acta"].isNull }, dia4.toString())
        // on the 10th: subsanada; the other is vencida and still without its acta
        val dia10 = filas("contribuyente=$c&vencidas_a=$ANIO_PADRON-03-10")
        assertEquals("$ANIO_PADRON-03-05", dia10.getValue(subsanada)["subsanada"]["fecha"].asString())
        assertTrue(dia10.getValue(conActa)["vencida"].asBoolean() && dia10.getValue(conActa)["acta"].isNull, dia10.toString())
        // its acta counts from the infracción's day; hasta does not reach past vencidas_a
        assertEquals(acta, filas("contribuyente=$c&vencidas_a=$ANIO_PADRON-03-15").getValue(conActa)["acta"]["id"].asString())
        assertEquals(setOf(subsanada, conActa), filas("contribuyente=$c&vencidas_a=$ANIO_PADRON-03-19&hasta=$ANIO_PADRON-12-31").keys)
        assertEquals(setOf(subsanada, conActa, posterior), filas("contribuyente=$c&vencidas_a=$ANIO_PADRON-03-31").keys)

        // the padrón of vencidas at a corte before the acta still has it
        assertTrue(conActa in vencidas("$ANIO_PADRON-03-10"))
        assertFalse(conActa in vencidas("$ANIO_PADRON-03-16"))
    }

    @Test
    fun `the plazos loaded for a year, with the feriados and what is missing`() {
        plazos(ANIO_PLAZOS, descargo = 5, recurso = 15, feriados = "$ANIO_PLAZOS-06-29,$ANIO_PLAZOS-04-17")
        val descargo = parametro(Llaves.PLAZO, Llaves.DESCARGO_PAPELETA, ANIO_PLAZOS)
        val feriados = parametro(Llaves.FERIADOS, Llaves.feriados(ANIO_PLAZOS), ANIO_PLAZOS)

        val cargados = plazosDe("?anio=$ANIO_PLAZOS")
        assertEquals(setOf("anio", "al_dia", "plazos", "feriados", "faltan"), cargados.propertyNames().toSet())
        assertEquals(ANIO_PLAZOS, cargados["anio"].asInt())
        assertEquals("$ANIO_PLAZOS-01-01", cargados["al_dia"].asString())
        assertEquals(listOf(Llaves.DESCARGO_PAPELETA, Llaves.RG_RECURSO), cargados["plazos"].filas().map { it["clave"].asString() })
        val primero = cargados["plazos"][0]
        assertEquals(
            setOf("clave", "dias", "unidad", "texto", "vigencia_desde", "vigencia_hasta", "parametro_id"),
            primero.propertyNames().toSet()
        )
        assertEquals(5, primero["dias"].asInt())
        assertEquals(Llaves.DIAS_HABILES, primero["unidad"].asString())
        assertEquals("5 DIAS_HABILES", primero["texto"].asString())
        assertEquals("$ANIO_PLAZOS-01-01", primero["vigencia_desde"].asString())
        assertEquals("$ANIO_PLAZOS-12-31", primero["vigencia_hasta"].asString())
        assertEquals(descargo, primero["parametro_id"].asString())
        assertEquals(15, cargados["plazos"][1]["dias"].asInt())
        assertEquals(listOf("$ANIO_PLAZOS-04-17", "$ANIO_PLAZOS-06-29"), cargados["feriados"]["fechas"].filas().map { it.asString() })
        assertEquals(feriados, cargados["feriados"]["parametro_id"].asString())
        assertEquals(0, cargados["faltan"].size())

        // a year without rows: a 200 that says what is missing
        val vacio = plazosDe("?anio=$ANIO_SIN_PLAZOS")
        assertEquals(0, vacio["plazos"].size())
        assertTrue(vacio["feriados"].isNull, vacio.toString())
        assertEquals(
            listOf("PLAZO DESCARGO_PAPELETA $ANIO_SIN_PLAZOS", "PLAZO RG_RECURSO $ANIO_SIN_PLAZOS", "FERIADOS $ANIO_SIN_PLAZOS"),
            vacio["faltan"].filas().map { it.asString() }
        )

        // without anio, the current year at today
        val hoy = plazosDe("")
        assertEquals(LocalDate.now().year, hoy["anio"].asInt())
        assertEquals(LocalDate.now().toString(), hoy["al_dia"].asString())

        for ((query, campo) in listOf("?anio=dos" to "anio", "?anio=0" to "anio", "?clave=PLAZO" to "clave")) {
            val problema = tree(send("GET", "$PLAZOS$query", null, HttpStatus.UNPROCESSABLE_CONTENT))
            assertEquals(campo, problema["errors"][0]["field"].asString(), problema.toString())
        }
    }

    private fun preparar(anio: Int): Pair<String, String> {
        uit(anio)
        plazos(anio)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$anio-01-01"))
        return inscribir() to codigo
    }

    private fun expediente(id: String): JsonNode = tree(send("GET", "$ACTAS/$id", null, HttpStatus.OK))

    private fun plazosDe(query: String): JsonNode = tree(send("GET", "$PLAZOS$query", null, HttpStatus.OK))

    private fun np() = "NP-${uniqueDocumento()}"

    // the ids of the padrón's first page, in its order
    private fun ids(query: String): List<String> =
        tree(send("GET", "$NOTIFICACIONES?$query&size=200", null, HttpStatus.OK))["content"].filas().map { it["id"].asString() }

    // the padrón's rows, by id
    private fun filas(query: String): Map<String, JsonNode> =
        tree(send("GET", "$NOTIFICACIONES?$query&size=200", null, HttpStatus.OK))["content"].filas().associateBy { it["id"].asString() }

    // the ids of every vencida at `corte`
    private fun vencidas(corte: String): Set<String> {
        val ids = mutableSetOf<String>()
        var page = 0
        do {
            val p = tree(send("GET", "$NOTIFICACIONES/vencidas?corte=$corte&page=$page&size=200", null, HttpStatus.OK))
            ids += p["content"].filas().map { it["id"].asString() }
            page++
        } while (page < p["totalPages"].asInt())
        return ids
    }

    private fun domicilioFiscal() =
        mapOf(
            "tipo_domicilio" to "FISCAL",
            "tipo_predio" to "PREDIO URBANO",
            "ubigeo" to "120302",
            "departamento" to "JUNIN",
            "provincia" to "CHANCHAMAYO",
            "distrito" to "PERENE",
            "tipo_via" to "AVENIDA",
            "via" to "MARGINAL",
            "numero" to "234"
        )

    private companion object {
        const val NOTIFICACIONES = "/api/srtm/infracciones/notificaciones"
        const val CUIS = "/api/srtm/infracciones/cuis"
        const val PLAZOS = "/api/srtm/infracciones/plazos"

        // years only this class gives rows to
        const val ANIO = 1989
        const val ANIO_PADRON = 1988
        const val ANIO_PLAZOS = 1987
        const val ANIO_SIN_PLAZOS = 1986
        const val ANIO_PARTES = 1985
    }
}
