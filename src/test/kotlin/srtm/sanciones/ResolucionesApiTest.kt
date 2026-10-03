package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import srtm.emision.texto
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

// the acts that answer an acta on the whole app and PostGIS: the descargo with its plazo copied, the RIS and the RGR
// with their número and paper, and the notificación of a resolución with its exigibilidad. FICTITIOUS figures: the UIT
// is Ficticios.UIT (4321.00) and the plazos 5 and 15 business days (7 in ANIO_CON_FERIADO, which has a movable feriado
// of its own); ANIO_SIN_PLAZOS has no PLAZO nor FERIADOS rows, ANIO_SIN_FERIADOS no FERIADOS, and ANIO_CONGELADO is
// this class's to change
class ResolucionesApiTest : ConSancionesApiTest() {
    @Test
    fun `a descargo in plazo and a late one are both recorded, with their plazo copied`() {
        val (c, codigo) = preparar(ANIO_PASADO)
        val acta = registrarActa(codigo, c)["id"].asString()
        val plazo = parametro(Llaves.PLAZO, Llaves.DESCARGO_PAPELETA, ANIO_PASADO)
        val expediente = "EXP-${uniqueDocumento()}"

        // the infracción on monday 03-04: the plazo runs from 03-05 and its 5th business day is 03-12, which still counts
        val enPlazo = post("$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-12", "reconsideracion", " ${expediente.lowercase()} "))
        assertEquals(expediente, enPlazo["numero_expediente"].asString())
        assertEquals("RECONSIDERACION", enPlazo["tipo_recurso"].asString())
        assertEquals("$ANIO_PASADO-03-12", enPlazo["fecha"].asString())
        assertEquals("$ANIO_PASADO-03-12", enPlazo["presentado_hasta"].asString())
        assertTrue(enPlazo["en_plazo"].asBoolean())
        assertEquals("5 DIAS_HABILES", enPlazo["plazo_texto"].asString())
        assertEquals("Tenía la licencia en trámite", enPlazo["sustento"].asString())
        assertEquals("Descargo de prueba", enPlazo["observacion"].asString())
        assertEquals(acta, enPlazo["papeleta"].asString())
        assertEquals(plazo, enPlazo["plazo"].asString())

        val tardio = descargar(acta, "$ANIO_PASADO-03-13")
        assertFalse(tardio["en_plazo"].asBoolean())
        assertEquals("$ANIO_PASADO-03-12", tardio["presentado_hasta"].asString())
        val ficha = expediente(acta)
        assertEquals(listOf(enPlazo["id"].asString(), tardio["id"].asString()), ficha["descargos"].filas().map { it["id"].asString() })
        val actos = ficha["actos"].filas()
        assertEquals(listOf("Acta de constatación", "Recurso de reconsideración", "Descargo"), actos.map { it["acto"].asString() })
        assertEquals("En plazo (hasta el $ANIO_PASADO-03-12)", actos[1]["detalle"].asString())
        assertEquals("Fuera de plazo (venció el $ANIO_PASADO-03-12)", actos[2]["detalle"].asString())

        val repetido = tree(send("POST", "$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-12", expediente = expediente), HttpStatus.CONFLICT))
        assertTrue(repetido["detail"].asString().contains(expediente), repetido.toString())
        val antes = tree(send("POST", "$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-03"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(antes["detail"].asString().contains("$ANIO_PASADO-03-03") && antes["detail"].asString().contains("$ANIO_PASADO-03-04"), antes.toString())
        send("POST", "$ACTAS/$acta/descargos", pedidoDescargo(LocalDate.now().plusDays(1).toString()), HttpStatus.UNPROCESSABLE_CONTENT)
        send("POST", "$ACTAS/${UUID.randomUUID()}/descargos", pedidoDescargo("$ANIO_PASADO-03-12"), HttpStatus.NOT_FOUND)
        rejected("POST", "$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-12", tipo = "QUEJA"), "tipo_recurso")
        rejected("POST", "$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-12") + ("numero_expediente" to " "), "numero_expediente")
        rejected("POST", "$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-12") + ("sustento" to " "), "sustento")
        rejected("POST", "$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-12") + ("observacion" to "no"), "observacion")
        rejected("POST", "$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-12") + ("fecha" to null), "fecha")
        assertEquals(2, expediente(acta)["descargos"].size())
    }

    @Test
    fun `without the year's PLAZO or FERIADOS rows nothing is written, and the 422 names what is missing`() {
        val c = inscribir()
        val codigo = nuevoCodigo()
        uit(ANIO_PASADO)
        plazos(ANIO_PASADO)
        uit(ANIO_SIN_PLAZOS)
        uit(ANIO_SIN_FERIADOS)
        plazos(ANIO_SIN_FERIADOS, conFeriados = false)
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))

        val sinPlazos = registrarActa(codigo, c, "$ANIO_SIN_PLAZOS-03-04")["id"].asString()
        val descargo = tree(send("POST", "$ACTAS/$sinPlazos/descargos", pedidoDescargo("$ANIO_SIN_PLAZOS-03-05"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(
            listOf("${Llaves.PLAZO} ${Llaves.DESCARGO_PAPELETA} $ANIO_SIN_PLAZOS", "${Llaves.FERIADOS} $ANIO_SIN_PLAZOS"),
            descargo["faltan"].filas().map { it.asString() }
        )
        val ris = tree(send("POST", "$ACTAS/$sinPlazos/resoluciones", pedidoResolucion("$ANIO_SIN_PLAZOS-03-20"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("${Llaves.PLAZO} ${Llaves.RG_RECURSO} $ANIO_SIN_PLAZOS"), ris["faltan"].filas().map { it.asString() })
        val nada = expediente(sinPlazos)
        assertEquals(0, nada["descargos"].size())
        assertEquals(0, nada["resoluciones"].size())

        val sinFeriados = registrarActa(codigo, c, "$ANIO_SIN_FERIADOS-03-04")["id"].asString()
        val tardio = tree(send("POST", "$ACTAS/$sinFeriados/descargos", pedidoDescargo("$ANIO_SIN_FERIADOS-03-05"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("${Llaves.FERIADOS} $ANIO_SIN_FERIADOS"), tardio["faltan"].filas().map { it.asString() })
        // the RIS only copies its plazo; its notificación counts it, and needs the feriados of the days it counts
        val dictada = dictar(sinFeriados, "$ANIO_SIN_FERIADOS-03-20")["id"].asString()
        val notificacion =
            tree(send("POST", "$RESOLUCIONES/$dictada/notificacion", pedidoNotificacion("$ANIO_SIN_FERIADOS-03-21"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("${Llaves.FERIADOS} $ANIO_SIN_FERIADOS"), notificacion["faltan"].filas().map { it.asString() })
        // one in a later year without its plazo names that year's
        val siguiente = dictar(registrarActa(codigo, c, "$ANIO_PASADO-03-04")["id"].asString(), "$ANIO_PASADO-03-20")["id"].asString()
        val otroAnio =
            tree(send("POST", "$RESOLUCIONES/$siguiente/notificacion", pedidoNotificacion("$ANIO_SIN_PLAZOS-03-22"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(
            listOf("${Llaves.PLAZO} ${Llaves.RG_RECURSO} $ANIO_SIN_PLAZOS", "${Llaves.FERIADOS} $ANIO_SIN_PLAZOS"),
            otroAnio["faltan"].filas().map { it.asString() }
        )
        val ficha = expediente(sinFeriados)
        assertEquals(0, ficha["descargos"].size())
        assertEquals(0, ficha["resoluciones"][0]["notificaciones"].size())
        // a NO_UBICADO counts nothing: it needs no plazo
        val noUbicado = notificar(dictada, "$ANIO_SIN_FERIADOS-03-21", "NO_UBICADO")
        assertTrue(noUbicado["exigible_desde"].isNull, noUbicado.toString())
    }

    @Test
    fun `RIS are numbered in sequence by tipo and year, and ten at once on different actas take distinct correlativos`() {
        val (c, codigo) = preparar(ANIO_PASADO)
        val primera = registrarActa(codigo, c)["id"].asString()
        val plazo = parametro(Llaves.PLAZO, Llaves.RG_RECURSO, ANIO_PASADO)

        val a = post("$ACTAS/$primera/resoluciones", pedidoResolucion("$ANIO_PASADO-03-20", sancion = "Clausura por 7 días"))
        val correlativo = a["correlativo"].asInt()
        assertEquals("ADMINISTRATIVA", a["tipo"].asString())
        assertEquals(ANIO_PASADO, a["anio"].asInt())
        assertEquals("RIS-$ANIO_PASADO-%06d".format(correlativo), a["numero"].asString())
        assertEquals("$ANIO_PASADO-03-20", a["fecha"].asString())
        assertEquals("15 DIAS_HABILES", a["plazo_texto"].asString())
        assertEquals(plazo, a["plazo"].asString())
        assertEquals(primera, a["papeleta"].asString())
        assertEquals(primera, a["clave_ris"].asString())
        assertEquals("Clausura por 7 días", a["sancion_accesoria"].asString())
        assertEquals("Se constató la infracción", a["sustento"].asString())
        for (campo in listOf("descargo", "sentido", "efecto", "clave_descargo")) assertTrue(a[campo].isNull, "$campo: $a")

        val b = dictar(registrarActa(codigo, c)["id"].asString(), "$ANIO_PASADO-03-21")
        assertEquals(correlativo + 1, b["correlativo"].asInt())
        assertEquals("RIS-$ANIO_PASADO-%06d".format(correlativo + 1), b["numero"].asString())
        // the RGR are a serie of their own
        val recurso = registrarActa(codigo, c)["id"].asString()
        val rgr = dictar(recurso, "$ANIO_PASADO-03-22", RESOLUCION_RECURSO, descargar(recurso, "$ANIO_PASADO-03-06")["id"].asString())
        assertTrue(rgr["numero"].asString().startsWith("RGR-$ANIO_PASADO-"), rgr.toString())
        assertTrue(rgr["clave_ris"].isNull, rgr.toString())
        assertEquals(correlativo + 2, dictar(registrarActa(codigo, c)["id"].asString(), "$ANIO_PASADO-03-23")["correlativo"].asInt())

        val actas = (1..10).map { registrarActa(codigo, c)["id"].asString() }
        val clerks = Executors.newFixedThreadPool(10)
        try {
            val respuestas =
                actas
                    .map { acta ->
                        CompletableFuture.supplyAsync({ exchange("POST", "$ACTAS/$acta/resoluciones", pedidoResolucion("$ANIO_PASADO-03-25")) }, clerks)
                    }.map { it.join() }
            assertTrue(respuestas.all { it.first == HttpStatus.CREATED }, respuestas.toString())
            val correlativos = respuestas.map { tree(it.second)["correlativo"].asInt() }
            assertEquals(10, correlativos.toSet().size, correlativos.toString())
            assertEquals(9, correlativos.max() - correlativos.min(), correlativos.toString())
        } finally {
            clerks.shutdown()
        }
    }

    @Test
    fun `one RIS per acta and one resolución per descargo, a RECURSO resolves a descargo and SE_REDUCE is refused`() {
        val (c, codigo) = preparar(ANIO_PASADO)
        val acta = registrarActa(codigo, c)["id"].asString()
        val resoluciones = "$ACTAS/$acta/resoluciones"
        val ris = dictar(acta, "$ANIO_PASADO-03-20")
        assertFalse(expediente(acta)["acciones"]["resolucion"]["permitida"].asBoolean())
        assertTrue(expediente(acta)["acciones"]["resolucion"]["motivo"].asString().contains(ris["numero"].asString()))

        val segunda = tree(send("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-21"), HttpStatus.CONFLICT))
        assertTrue(
            segunda["detail"].asString().contains("ya tiene su RIS") && segunda["detail"].asString().contains(ris["numero"].asString()),
            segunda.toString()
        )
        val sinDescargo = tree(send("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-21", RESOLUCION_RECURSO), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals("descargo", sinDescargo["errors"][0]["field"].asString(), sinDescargo.toString())

        val descargo = descargar(acta, "$ANIO_PASADO-03-22")["id"].asString()
        assertTrue(expediente(acta)["acciones"]["resolucion"]["permitida"].asBoolean())
        val reduce =
            tree(
                send(
                    "POST",
                    resoluciones,
                    pedidoResolucion("$ANIO_PASADO-03-25", RESOLUCION_RECURSO, descargo, efecto = SE_REDUCE),
                    HttpStatus.UNPROCESSABLE_CONTENT
                )
            )
        assertTrue(reduce["detail"].asString().contains("D-02b"), reduce.toString())
        send("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-25", RESOLUCION_RECURSO, descargo, efecto = null), HttpStatus.UNPROCESSABLE_CONTENT)
        send("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-25", sentido = "FUNDADO", efecto = SE_MANTIENE), HttpStatus.UNPROCESSABLE_CONTENT)
        // not before the presentación of the descargo it resolves
        val antes = tree(send("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-21", RESOLUCION_RECURSO, descargo), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(antes["detail"].asString().contains("$ANIO_PASADO-03-22"), antes.toString())
        // a descargo of another acta, or none at all
        val otra = registrarActa(codigo, c)["id"].asString()
        val ajeno =
            tree(
                send(
                    "POST",
                    "$ACTAS/$otra/resoluciones",
                    pedidoResolucion("$ANIO_PASADO-03-25", RESOLUCION_RECURSO, descargo),
                    HttpStatus.UNPROCESSABLE_CONTENT
                )
            )
        assertEquals("descargo", ajeno["errors"][0]["field"].asString(), ajeno.toString())
        send("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-25", RESOLUCION_RECURSO, UUID.randomUUID().toString()), HttpStatus.NOT_FOUND)
        send("POST", "$ACTAS/${UUID.randomUUID()}/resoluciones", pedidoResolucion("$ANIO_PASADO-03-25"), HttpStatus.NOT_FOUND)
        rejected("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-25", RESOLUCION_RECURSO, descargo, sentido = "A_MEDIAS"), "sentido")
        rejected("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-25") + ("sustento" to " "), "sustento")
        rejected("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-25") + ("observacion" to "no"), "observacion")
        send("POST", resoluciones, pedidoResolucion(LocalDate.now().plusDays(1).toString(), RESOLUCION_RECURSO, descargo), HttpStatus.UNPROCESSABLE_CONTENT)

        val rgr = dictar(acta, "$ANIO_PASADO-03-25", RESOLUCION_RECURSO, descargo)
        assertEquals(descargo, rgr["descargo"].asString())
        assertEquals(descargo, rgr["clave_descargo"].asString())
        assertEquals("INFUNDADO", rgr["sentido"].asString())
        assertEquals(SE_MANTIENE, rgr["efecto"].asString())
        val otraVez = tree(send("POST", resoluciones, pedidoResolucion("$ANIO_PASADO-03-26", RESOLUCION_RECURSO, descargo), HttpStatus.CONFLICT))
        assertTrue(otraVez["detail"].asString().contains(rgr["numero"].asString()), otraVez.toString())
        val e = expediente(acta)
        assertEquals("SANCIONADA", e["fase"].asString())
        assertEquals(listOf(ris["id"].asString(), rgr["id"].asString()), e["resoluciones"].filas().map { it["id"].asString() })
        assertFalse(e["acciones"]["resolucion"]["permitida"].asBoolean())
        assertTrue(e["acciones"]["descargo"]["permitida"].asBoolean())
    }

    @Test
    fun `a resolución that leaves the multa without effect ends the procedimiento`() {
        val (c, codigo) = preparar(ANIO_PASADO)
        val acta = registrarActa(codigo, c)["id"].asString()
        dictar(acta, "$ANIO_PASADO-03-20")
        val descargo = descargar(acta, "$ANIO_PASADO-03-21")["id"].asString()
        val rgr = post("$ACTAS/$acta/resoluciones", pedidoResolucion("$ANIO_PASADO-03-25", RESOLUCION_RECURSO, descargo, "FUNDADO", SE_DEJA_SIN_EFECTO))
        assertEquals(SE_DEJA_SIN_EFECTO, rgr["efecto"].asString())

        val e = expediente(acta)
        assertTrue(e["fase"].isNull, e.toString())
        assertEquals("DEJADA_SIN_EFECTO", e["estado_de_la_deuda"].asString())
        val fila = tree(send("GET", "$ACTAS?numero=${e["acta"]["numero"].asString()}", null, HttpStatus.OK))["content"][0]
        assertTrue(fila["fase"].isNull, fila.toString())
        assertEquals("DEJADA_SIN_EFECTO", fila["estado_de_la_deuda"].asString())
        val anulacion = tree(send("POST", "$ACTAS/$acta/anulacion", mapOf("motivo" to "Error", "observacion" to "Se anula"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(anulacion["detail"].asString().contains("sin efecto"), anulacion.toString())
        val otroDescargo = tree(send("POST", "$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-26"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(otroDescargo["detail"].asString().contains("no queda nada que impugnar"), otroDescargo.toString())
        // one anulada has nothing to resolve either
        val anulada = registrarActa(codigo, c)["id"].asString()
        post("$ACTAS/$anulada/anulacion", mapOf("motivo" to "Error", "fecha" to "$ANIO_PASADO-03-05", "observacion" to "Se anula"))
        val nada = tree(send("POST", "$ACTAS/$anulada/resoluciones", pedidoResolucion("$ANIO_PASADO-03-20"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(nada["detail"].asString().contains("no queda nada que resolver"), nada.toString())
        assertTrue(expediente(anulada)["acciones"]["resolucion"]["motivo"].asString().contains("anulada"))
    }

    @Test
    fun `the paper is drawn again from the frozen rows, the same after the UIT, the CUIS and the plazo change`() {
        val (c, codigo) = preparar(ANIO_CONGELADO)
        val nombre = registro("contribuyente", c)["nombre_completo"].asString()
        val acta = registrarActa(codigo, c, "$ANIO_CONGELADO-03-04")
        val id = acta["id"].asString()
        val descargo = descargar(id, "$ANIO_CONGELADO-03-11")["id"].asString()
        val ris = dictar(id, "$ANIO_CONGELADO-03-20")
        val rgr = dictar(id, "$ANIO_CONGELADO-03-21", RESOLUCION_RECURSO, descargo)

        val (status, cabeceras, pdf) = bajar("$RESOLUCIONES/${ris["id"].asString()}/pdf")
        assertEquals(HttpStatus.OK, status)
        assertTrue(MediaType.APPLICATION_PDF.isCompatibleWith(cabeceras.contentType), "${cabeceras.contentType}")
        assertEquals("inline; filename=\"${ris["numero"].asString()}.pdf\"", cabeceras.getFirst(HttpHeaders.CONTENT_DISPOSITION))
        val texto = texto(pdf)
        for (esperado in listOf(
            ris["numero"].asString(),
            "Fecha: 20/03/$ANIO_CONGELADO",
            acta["numero"].asString(),
            codigo,
            "Ordenanza ficticia 001",
            "S/ 4,321.00",
            "S/ 432.10",
            nombre,
            "Plazo para impugnar: 15 DIAS_HABILES",
            CONSIDERANDOS,
            "Gerente",
            "Secretario"
        )) {
            assertTrue(esperado in texto, "$esperado: $texto")
        }
        val delRecurso = texto(bajar("$RESOLUCIONES/${rgr["id"].asString()}/pdf").third)
        assertTrue("Presentado dentro del plazo: SÍ, el plazo vencía el 12/03/$ANIO_CONGELADO" in delRecurso, delRecurso)
        assertTrue("Infundado" in delRecurso, delRecurso)

        // the UIT row and the plazo row are corrected, and the code gets a new version
        for ((fila, valor) in listOf(uit(ANIO_CONGELADO) to "5000.00", parametro(Llaves.PLAZO, Llaves.RG_RECURSO, ANIO_CONGELADO) to "20")) {
            val ruta = "/api/objects/parametro_tributario/records/$fila"
            val atributos = fields(tree(send("GET", ruta, null, HttpStatus.OK))["attributes"])
            send("PUT", ruta, mapOf("attributes" to atributos + ("valor_numerico" to valor)), HttpStatus.OK)
        }
        post(CUIS, version(codigo, "$ANIO_CONGELADO-02-01", porcentaje = 20) + ("base_legal" to "Ordenanza ficticia 002"))
        assertEquals(texto, texto(bajar("$RESOLUCIONES/${ris["id"].asString()}/pdf").third))
        assertEquals(delRecurso, texto(bajar("$RESOLUCIONES/${rgr["id"].asString()}/pdf").third))

        send("GET", "$RESOLUCIONES/${UUID.randomUUID()}/pdf", null, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a notificación goes to the obligado's domicilio fiscal unless told otherwise, and a NO_UBICADO makes nothing exigible`() {
        val (c, codigo) = preparar(ANIO_PASADO)
        post("/api/srtm/contribuyentes/$c/domicilios", domicilioFiscal())
        val domicilio = registro("contribuyente", c)["domicilio_fiscal"].asString()
        val acta = registrarActa(codigo, c)["id"].asString()
        val ris = dictar(acta, "$ANIO_PASADO-03-20")
        val id = ris["id"].asString()
        val plazo = parametro(Llaves.PLAZO, Llaves.RG_RECURSO, ANIO_PASADO)

        val noUbicado = notificar(id, "$ANIO_PASADO-03-21", "no_ubicado", direccion = null)
        assertEquals(1, noUbicado["intento"].asInt())
        assertEquals("$id|1", noUbicado["clave"].asString())
        assertEquals(domicilio, noUbicado["direccion"].asString())
        assertEquals("NO_UBICADO", noUbicado["resultado"].asString())
        assertEquals("PERSONAL", noUbicado["modalidad"].asString())
        assertEquals(id, noUbicado["resolucion"].asString())
        for (campo in listOf("exigible_desde", "plazo_texto", "plazo", "receptor", "documento_receptor", "vinculo", "acuse")) {
            assertTrue(noUbicado[campo].isNull, "$campo: $noUbicado")
        }

        // friday 03-22: takes effect on monday 03-25, its 15th business day is 04-15, exigible the day after
        val notificado = notificar(id, "$ANIO_PASADO-03-22", direccion = null)
        assertEquals(2, notificado["intento"].asInt())
        assertEquals(domicilio, notificado["direccion"].asString())
        assertEquals("$ANIO_PASADO-04-16", notificado["exigible_desde"].asString())
        assertEquals("15 DIAS_HABILES", notificado["plazo_texto"].asString())
        assertEquals(plazo, notificado["plazo"].asString())
        val otra = notificar(id, "$ANIO_PASADO-03-22", "RECHAZADO", direccion = " AV. OTRA 1 ")
        assertEquals("AV. OTRA 1", otra["direccion"].asString())
        assertEquals("$ANIO_PASADO-04-16", otra["exigible_desde"].asString(), "a rejected one takes effect (art. 104 a)")

        val ficha = expediente(acta)
        assertEquals(listOf(1, 2, 3), ficha["resoluciones"][0]["notificaciones"].filas().map { it["intento"].asInt() })
        assertEquals("Notificación de la resolución", ficha["actos"].filas().last()["acto"].asString())
        assertTrue(ficha["actos"].filas().any { it["detalle"].asString() == "NOTIFICADO, exigible desde el $ANIO_PASADO-04-16" }, ficha.toString())

        // an obligado without a domicilio fiscal: the direccion has to be given
        val sinDomicilio = dictar(registrarActa(codigo, inscribir())["id"].asString(), "$ANIO_PASADO-03-20")["id"].asString()
        val problema =
            tree(
                send(
                    "POST",
                    "$RESOLUCIONES/$sinDomicilio/notificacion",
                    pedidoNotificacion("$ANIO_PASADO-03-22", direccion = null),
                    HttpStatus.UNPROCESSABLE_CONTENT
                )
            )
        assertEquals("direccion", problema["errors"][0]["field"].asString(), problema.toString())
        assertEquals("JR. LIMA 123", notificar(sinDomicilio, "$ANIO_PASADO-03-22")["direccion"].asString())

        // not before the resolución nor after today
        val antes = tree(send("POST", "$RESOLUCIONES/$id/notificacion", pedidoNotificacion("$ANIO_PASADO-03-19"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals("fecha_diligencia", antes["errors"][0]["field"].asString(), antes.toString())
        send("POST", "$RESOLUCIONES/$id/notificacion", pedidoNotificacion(LocalDate.now().plusDays(1).toString()), HttpStatus.UNPROCESSABLE_CONTENT)
        send("POST", "$RESOLUCIONES/${UUID.randomUUID()}/notificacion", pedidoNotificacion("$ANIO_PASADO-03-22"), HttpStatus.NOT_FOUND)
        rejected("POST", "$RESOLUCIONES/$id/notificacion", pedidoNotificacion("$ANIO_PASADO-03-22") + ("modalidad" to "PALOMA"), "modalidad")
        rejected("POST", "$RESOLUCIONES/$id/notificacion", pedidoNotificacion("$ANIO_PASADO-03-22") + ("notificador" to " "), "notificador")
        rejected("POST", "$RESOLUCIONES/$id/notificacion", pedidoNotificacion("$ANIO_PASADO-03-22") + ("observacion" to "no"), "observacion")
        assertEquals(3, expediente(acta)["resoluciones"][0]["notificaciones"].size())
    }

    @Test
    fun `exigible_desde counts the plazo of the diligencia's day and the year's movable feriados`() {
        // 7 business days, and thursday 03-30 a feriado of that year (FICTITIOUS, both)
        val (c, codigo) = preparar(ANIO_CON_FERIADO, recurso = 7, feriados = "$ANIO_CON_FERIADO-03-30")
        val ris = dictar(registrarActa(codigo, c, "$ANIO_CON_FERIADO-03-04")["id"].asString(), "$ANIO_CON_FERIADO-03-21")
        // wednesday 03-22: takes effect on 03-23; 03-24, 27, 28, 29, (30 is a feriado), 31, 04-03 and 04-04 is the 7th
        val n = notificar(ris["id"].asString(), "$ANIO_CON_FERIADO-03-22")
        assertEquals("$ANIO_CON_FERIADO-04-05", n["exigible_desde"].asString())
        assertEquals("7 DIAS_HABILES", n["plazo_texto"].asString())
        assertEquals("7 DIAS_HABILES", ris["plazo_texto"].asString())
    }

    @Test
    fun `only srtm writes them, and who may only read gets a 403 that names the object`() {
        val (c, codigo) = preparar(ANIO_PASADO)
        val acta = registrarActa(codigo, c)["id"].asString()
        val descargo = descargar(acta, "$ANIO_PASADO-03-06")["id"].asString()
        val ris = dictar(acta, "$ANIO_PASADO-03-20")["id"].asString()
        val notificacion = notificar(ris, "$ANIO_PASADO-03-22")["id"].asString()
        for ((objeto, id) in listOf(DESCARGO_PAPELETA to descargo, RESOLUCION_GERENCIA to ris, NOTIFICACION_RESOLUCION to notificacion)) {
            val registro = "/api/objects/$objeto/records/$id"
            val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
            send("PUT", registro, mapOf("attributes" to atributos + ("observacion" to "Cambiada por la API")), HttpStatus.CONFLICT)
            send("DELETE", registro, null, HttpStatus.CONFLICT)
        }
        val plazo = parametro(Llaves.PLAZO, Llaves.DESCARGO_PAPELETA, ANIO_PASADO)
        val generico = Ejemplos.descargo(acta, plazo) + ("numero_expediente" to "EXP-${uniqueDocumento()}")
        send("POST", "/api/objects/$DESCARGO_PAPELETA/records", mapOf("attributes" to generico), HttpStatus.FORBIDDEN)

        val lector = funcionario(listOf(permiso(null, "READ")))
        assertEquals(HttpStatus.OK, bajar("$RESOLUCIONES/$ris/pdf", lector).first)
        for ((ruta, cuerpo, objeto) in listOf(
            Triple("$ACTAS/$acta/descargos", pedidoDescargo("$ANIO_PASADO-03-06"), DESCARGO_PAPELETA),
            Triple("$ACTAS/$acta/resoluciones", pedidoResolucion("$ANIO_PASADO-03-21", RESOLUCION_RECURSO, descargo), RESOLUCION_GERENCIA),
            Triple("$RESOLUCIONES/$ris/notificacion", pedidoNotificacion("$ANIO_PASADO-03-22"), NOTIFICACION_RESOLUCION)
        )) {
            val problema = tree(send("POST", ruta, cuerpo, HttpStatus.FORBIDDEN, lector))
            assertTrue(problema["detail"].asString().contains(objeto), problema.toString())
        }
        // without read on the resoluciones, no paper
        val ajeno = funcionario(listOf(permiso("contribuyente", "READ")))
        assertEquals(HttpStatus.FORBIDDEN, bajar("$RESOLUCIONES/$ris/pdf", ajeno).first)
        assertEquals(1, expediente(acta)["resoluciones"].size())
    }

    // a contribuyente and a code whose first version rules from `anio`'s first day, with the UIT and plazos of `anio`
    private fun preparar(
        anio: Int,
        recurso: Int = 15,
        feriados: String? = null
    ): Pair<String, String> {
        uit(anio)
        plazos(anio, recurso = recurso, feriados = feriados)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$anio-01-01"))
        return inscribir() to codigo
    }

    private fun expediente(id: String): JsonNode = tree(send("GET", "$ACTAS/$id", null, HttpStatus.OK))

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

    // status, headers and body of a GET that answers bytes
    private fun bajar(
        path: String,
        token: String = this.token
    ): Triple<HttpStatus, HttpHeaders, ByteArray> {
        val result =
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(ByteArray::class.java)
                .returnResult()
        return Triple(HttpStatus.valueOf(result.status.value()), result.responseHeaders, result.responseBody ?: ByteArray(0))
    }

    private companion object {
        const val CUIS = "/api/srtm/infracciones/cuis"

        // years only this class gives rows to: a UIT and nothing else; a UIT and the PLAZO rows without FERIADOS; plazos
        // with a movable feriado of their own; and the UIT, CUIS and plazo rows this class corrects
        const val ANIO_SIN_PLAZOS = 1993
        const val ANIO_SIN_FERIADOS = 1994
        const val ANIO_CON_FERIADO = 1995
        const val ANIO_CONGELADO = 1996
    }
}
