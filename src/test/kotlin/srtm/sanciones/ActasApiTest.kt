package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

// the actas of the portal on the whole app and PostGIS: the alta with its multa computed once and frozen, the grid with
// the fase and the estado de la deuda at today (and a fase filter that finds what the column shows), the ficha with its
// acts in legal order, the anulación, and the actas of a contribuyente or a predio. FICTITIOUS figures: the UIT of
// ANIO_PASADO is Ficticios.UIT (4321.00), ANIO_PASADO_SIN_UIT has none, and ANIO_CONGELADO is this class's to change
class ActasApiTest : ConSancionesApiTest() {
    @Test
    fun `an acta is registered with its multa computed from the CUIS and the UIT of the day, and answered flat`() {
        val c = inscribir()
        val uit = uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        val version = post(CUIS, version(codigo, "$ANIO_PASADO-01-01", porcentaje = 10, segunda = 15))["id"].asString()
        val numero = "AC-${uniqueDocumento()}"
        val cuerpo =
            pedidoActa(codigo.lowercase(), c, numero = " ${numero.lowercase()} ", reincidencia = "SEGUNDA") +
                mapOf("expediente" to "EXP-0001", "inspector" to "INSPECTOR DE PRUEBA", "descripcion_hecho" to "Letrero sin licencia")
        val a = post(ACTAS, cuerpo)

        val id = a["id"].asString()
        assertEquals(numero, a["numero"].asString())
        assertEquals("ADMINISTRATIVA", a["familia"].asString())
        assertEquals("ADMINISTRATIVA|$numero", a["clave"].asString())
        assertEquals("$ANIO_PASADO-03-04", a["fecha_infraccion"].asString())
        assertEquals("10:30", a["hora_infraccion"].asString())
        assertEquals("JR. LIMA 123", a["lugar"].asString())
        assertEquals("EXP-0001", a["expediente"].asString())
        assertEquals("SEGUNDA", a["reincidencia"].asString())
        assertEquals("Clausura temporal", a["medida_complementaria"].asString())
        // 4321.00 × 10 % the first time and × 15 % the second, rounded once to the céntimo
        assertEquals(4321.00, a["base_imponible"].asDouble())
        assertEquals(10.0, a["porcentaje_infraccion"].asDouble())
        assertEquals(432.10, a["importe_infraccion"].asDouble())
        assertEquals(15.0, a["porcentaje_a_cobrar"].asDouble())
        assertEquals(648.15, a["importe_a_pagar"].asDouble())
        assertTrue(a["importe_con_beneficio"].isNull, a.toString())
        assertEquals(LocalDate.now().toString(), a["fecha_calculo"].asString())
        assertEquals("Acta de prueba", a["observacion"].asString())
        // the relations to what was read: the version of the day and the UIT row
        assertEquals(version, a["codigo_infraccion"].asString())
        assertEquals(uit, a["uit"].asString())
        assertEquals(c, a["obligado"].asString())
        assertEquals(c, a["contribuyente"].asString())
        assertTrue(a["predio"].isNull && a["notificacion_previa"].isNull, a.toString())
        assertEquals("PAPELETA-$id", a["referencia"].asString())
        val d = a["desglose"]
        assertEquals(4321.00, d["base_imponible"].asDouble())
        assertEquals(432.10, d["importe_infraccion"].asDouble())
        assertEquals(15.0, d["porcentaje_a_cobrar"].asDouble())
        assertEquals(648.15, d["importe_a_pagar"].asDouble())
        assertTrue(d["importe_con_beneficio"].isNull, d.toString())
        assertEquals(LocalDate.now().toString(), d["fecha_calculo"].asString())
        assertEquals(648.15, registro(PAPELETA, id)["importe_a_pagar"].asDouble())

        // a predio without a contribuyente is enough
        val p = predio()
        val delPredio = post(ACTAS, pedidoActa(codigo, c, contribuyente = null, predio = p))
        assertEquals(p, delPredio["predio"].asString())
        assertTrue(delPredio["contribuyente"].isNull, delPredio.toString())
    }

    @Test
    fun `the multa stays frozen when the UIT row changes and a new CUIS version comes`() {
        val c = inscribir()
        val uit = uit(ANIO_CONGELADO)
        val codigo = nuevoCodigo()
        val primera = post(CUIS, version(codigo, "$ANIO_CONGELADO-01-01", porcentaje = 10))["id"].asString()
        val acta = registrarActa(codigo, c, "$ANIO_CONGELADO-03-04")
        val id = acta["id"].asString()
        assertEquals(432.10, acta["importe_a_pagar"].asDouble())

        // the UIT row is corrected and the code gets a new version from june
        val fila = "/api/objects/parametro_tributario/records/$uit"
        val atributos = fields(tree(send("GET", fila, null, HttpStatus.OK))["attributes"])
        send("PUT", fila, mapOf("attributes" to atributos + ("valor_numerico" to "5000.00")), HttpStatus.OK)
        post(CUIS, version(codigo, "$ANIO_CONGELADO-06-01", porcentaje = 20))

        val ficha = expediente(id)
        for (campo in listOf("base_imponible", "porcentaje_infraccion", "importe_infraccion", "porcentaje_a_cobrar", "importe_a_pagar")) {
            assertEquals(acta[campo].asDouble(), ficha["acta"][campo].asDouble(), campo)
        }
        assertEquals(primera, ficha["codigo_infraccion"]["id"].asString())
        assertEquals(10.0, ficha["codigo_infraccion"]["porcentaje_uit"].asDouble())
        assertEquals("$ANIO_CONGELADO-05-31", ficha["codigo_infraccion"]["vigencia_hasta"].asString())
        val enLaGrilla = filas("codigo=$codigo").single()
        assertEquals(432.10, enLaGrilla["importe_a_pagar"].asDouble())
        assertEquals(10.0, enLaGrilla["porcentaje_infraccion"].asDouble())

        // a new acta reads what is in force on its day: the corrected UIT, and the version of that day
        assertEquals(500.00, registrarActa(codigo, c, "$ANIO_CONGELADO-03-05")["importe_a_pagar"].asDouble())
        assertEquals(1000.00, registrarActa(codigo, c, "$ANIO_CONGELADO-07-01")["importe_a_pagar"].asDouble())
    }

    @Test
    fun `a code that does not rule on the infracción's day, or a missing UIT or grado, keeps the acta from being written`() {
        val c = inscribir()
        uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-06-01", tercera = null))

        val antes = tree(send("POST", ACTAS, pedidoActa(codigo, c, "$ANIO_PASADO-03-04"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(antes["detail"].asString().contains(codigo) && antes["detail"].asString().contains("$ANIO_PASADO-03-04"), antes.toString())
        assertEquals("codigo", antes["errors"][0]["field"].asString())
        val inexistente = tree(send("POST", ACTAS, pedidoActa("NO-${uniqueDocumento()}", c), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(inexistente["detail"].asString().contains("no está en el CUIS"), inexistente.toString())

        // the grado declared without its %: never charged at another grado's
        val tercera = tree(send("POST", ACTAS, pedidoActa(codigo, c, "$ANIO_PASADO-07-01", reincidencia = "TERCERA_O_MAS"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("CUIS $codigo porcentaje_uit_tercera"), tercera["faltan"].filas().map { it.asString() })

        // a year without a UIT: everything missing at once
        val sinUit = nuevoCodigo()
        post(CUIS, version(sinUit, "$ANIO_PASADO_SIN_UIT-01-01", tercera = null))
        val faltan = tree(send("POST", ACTAS, pedidoActa(sinUit, c, "$ANIO_PASADO_SIN_UIT-03-04"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("UIT $ANIO_PASADO_SIN_UIT"), faltan["faltan"].filas().map { it.asString() })
        val todo =
            tree(send("POST", ACTAS, pedidoActa(sinUit, c, "$ANIO_PASADO_SIN_UIT-03-04", reincidencia = "TERCERA_O_MAS"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("UIT $ANIO_PASADO_SIN_UIT", "CUIS $sinUit porcentaje_uit_tercera"), todo["faltan"].filas().map { it.asString() })
    }

    @Test
    fun `an alta that cannot be is refused, and says why`() {
        val c = inscribir()
        uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val numero = "AC-${uniqueDocumento()}"
        post(ACTAS, pedidoActa(codigo, c, numero = numero))

        val repetida = tree(send("POST", ACTAS, pedidoActa(codigo, c, numero = numero.lowercase()), HttpStatus.CONFLICT))
        assertTrue(repetida["detail"].asString().contains(numero), repetida.toString())
        val otro = UUID.randomUUID().toString()
        send("POST", ACTAS, pedidoActa(codigo, otro), HttpStatus.NOT_FOUND)
        send("POST", ACTAS, pedidoActa(codigo, c, contribuyente = otro), HttpStatus.NOT_FOUND)
        send("POST", ACTAS, pedidoActa(codigo, c, predio = otro), HttpStatus.NOT_FOUND)
        send("POST", ACTAS, pedidoActa(codigo, c, previa = otro), HttpStatus.NOT_FOUND)
        val futura = tree(send("POST", ACTAS, pedidoActa(codigo, c, LocalDate.now().plusDays(1).toString()), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals("fecha_infraccion", futura["errors"][0]["field"].asString(), futura.toString())
        rejected("POST", ACTAS, pedidoActa(codigo, c, contribuyente = null), "contribuyente")
        rejected("POST", ACTAS, pedidoActa(codigo, c) + ("hora_infraccion" to "25:00"), "hora_infraccion")
        rejected("POST", ACTAS, pedidoActa(codigo, c, reincidencia = "CUARTA"), "reincidencia")
        rejected("POST", ACTAS, pedidoActa(codigo, c) + ("observacion" to "no"), "observacion")
        rejected("POST", ACTAS, pedidoActa(codigo, "no-es-un-id"), "obligado")
        rejected("POST", ACTAS, pedidoActa(codigo, c) + ("numero" to " "), "numero")
        rejected("POST", ACTAS, pedidoActa(codigo, c) + ("codigo" to null), "codigo")
    }

    @Test
    fun `an acta names a notificación previa only when it is not subsanada, and not before it`() {
        val c = inscribir()
        uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val subsanada = post(NOTIFICACIONES, notificacion(numeroNp(), "$ANIO_PASADO-03-02", c))["id"].asString()
        post("$NOTIFICACIONES/$subsanada/subsanacion", mapOf("fecha" to "$ANIO_PASADO-03-03", "observacion" to "Retiró el letrero"))

        val problema = tree(send("POST", ACTAS, pedidoActa(codigo, c, previa = subsanada), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(problema["detail"].asString().contains("subsanada"), problema.toString())
        val posterior = post(NOTIFICACIONES, notificacion(numeroNp(), "$ANIO_PASADO-03-10", c))["id"].asString()
        val antes = tree(send("POST", ACTAS, pedidoActa(codigo, c, "$ANIO_PASADO-03-04", previa = posterior), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(antes["detail"].asString().contains("$ANIO_PASADO-03-04") && antes["detail"].asString().contains("$ANIO_PASADO-03-10"), antes.toString())
        assertEquals(posterior, registrarActa(codigo, c, "$ANIO_PASADO-03-10", previa = posterior)["notificacion_previa"].asString())
    }

    @Test
    fun `an acta and a subsanación of its previa at once cannot both be written`() {
        val c = inscribir()
        uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val clerks = Executors.newFixedThreadPool(2)
        try {
            repeat(5) {
                val n = post(NOTIFICACIONES, notificacion(numeroNp(), "$ANIO_PASADO-03-02", c, plazo = 5))["id"].asString()
                val acta = CompletableFuture.supplyAsync({ exchange("POST", ACTAS, pedidoActa(codigo, c, previa = n)).first }, clerks)
                val subsanacion =
                    CompletableFuture.supplyAsync(
                        {
                            exchange(
                                "POST",
                                "$NOTIFICACIONES/$n/subsanacion",
                                mapOf("fecha" to "$ANIO_PASADO-03-04", "observacion" to "Retiró el letrero")
                            ).first
                        },
                        clerks
                    )
                val estados = listOf(acta.join(), subsanacion.join())
                assertEquals(listOf(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_CONTENT), estados.sorted(), estados.toString())
            }
        } finally {
            clerks.shutdown()
        }
    }

    @Test
    fun `the grid gives each acta with its administrado, code, importe, fase and estado today, and filters them`() {
        val c = inscribir()
        val documento = registro("contribuyente", c)["numero_documento"].asString()
        uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val vieja = registrarActa(codigo, c, "$ANIO_PASADO-03-04")
        val nueva = registrarActa(codigo, c, "$ANIO_PASADO-04-04")

        val pagina = pagina("codigo=${codigo.lowercase()}")
        assertEquals(2, pagina["totalElements"].asInt())
        // the newest infracción first
        assertEquals(listOf(nueva, vieja).map { it["id"].asString() }, pagina["content"].filas().map { it["id"].asString() })
        val f = pagina["content"][1]
        assertEquals(vieja["numero"].asString(), f["numero"].asString())
        assertEquals("$ANIO_PASADO-03-04", f["fecha_infraccion"].asString())
        assertEquals("FLORES OTINIANO JUNIOR", f["administrado"].asString())
        assertEquals(documento, f["documento"].asString())
        assertEquals(codigo, f["codigo"].asString())
        assertEquals("No exhibir la licencia (ficticio)", f["descripcion_infraccion"].asString())
        assertEquals(10.0, f["porcentaje_infraccion"].asDouble())
        assertEquals(432.10, f["importe_a_pagar"].asDouble())
        assertEquals(LocalDate.now().toString(), f["fecha_calculo"].asString())
        assertEquals("Clausura temporal", f["medida_complementaria"].asString())
        assertEquals("CONSTATADA", f["fase"].asString())
        assertEquals(LocalDate.now().toString(), f["fase_al_dia"].asString())
        assertEquals("PENDIENTE", f["estado_de_la_deuda"].asString())

        assertEquals(listOf(vieja["id"].asString()), ids("numero=${vieja["numero"].asString().lowercase()}"))
        assertEquals(2, ids("codigo=$codigo&administrado=$documento").size)
        assertEquals(2, ids("codigo=$codigo&administrado=otiniano").size)
        assertEquals(0, ids("codigo=$codigo&administrado=ZZZ-NADIE").size)
        assertEquals(listOf(vieja["id"].asString()), ids("codigo=$codigo&desde=$ANIO_PASADO-03-01&hasta=$ANIO_PASADO-03-31"))
        assertEquals(0, ids("codigo=NO-${uniqueDocumento()}").size)
        val primera = pagina("codigo=$codigo&page=1&size=1")
        assertEquals(1, primera["page"].asInt())
        assertEquals(2, primera["totalPages"].asInt())

        for ((query, campo) in listOf("estado=ANULADA" to "estado", "desde=2026-3-1" to "desde", "size=0" to "size")) {
            val problema = tree(send("GET", "$ACTAS?$query", null, HttpStatus.UNPROCESSABLE_CONTENT))
            assertEquals(campo, problema["errors"][0]["field"].asString(), problema.toString())
        }
        val fase = tree(send("GET", "$ACTAS?fase=ANULADA", null, HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals("fase", fase["errors"][0]["field"].asString())
        assertTrue(listOf("PREVENTIVA", "CONSTATADA", "SANCIONADA").all { fase["detail"].asString().contains(it) }, fase.toString())
    }

    @Test
    fun `the fase moves at the previa's vencimiento, and the fase filter finds exactly what the column shows`() {
        val c = inscribir()
        val uit = uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val hoy = LocalDate.now()
        val fechaPrevia = LocalDate.parse("$ANIO_PASADO-01-02")
        val hastaHoy = ChronoUnit.DAYS.between(fechaPrevia, hoy).toInt()

        // its last day is today: still PREVENTIVA (#411); the one whose last day was yesterday is CONSTATADA
        val enPlazo = post(NOTIFICACIONES, notificacion(numeroNp(), fechaPrevia.toString(), c, plazo = hastaHoy))["id"].asString()
        val vencida = post(NOTIFICACIONES, notificacion(numeroNp(), fechaPrevia.toString(), c, plazo = hastaHoy - 1))["id"].asString()
        val preventiva = registrarActa(codigo, c, previa = enPlazo)["id"].asString()
        val constatada = registrarActa(codigo, c, previa = vencida)["id"].asString()
        val sinPrevia = registrarActa(codigo, c)["id"].asString()
        val anulada = registrarActa(codigo, c)["id"].asString()
        val sancionada = registrarActa(codigo, c)["id"].asString()
        val sinEfecto = registrarActa(codigo, c)["id"].asString()
        post("$ACTAS/$anulada/anulacion", mapOf("motivo" to "Error en el número", "observacion" to "Se anula"))
        ris(sancionada, uit)
        val descargo = descargo(sinEfecto, uit)
        ris(sinEfecto, uit)
        recurso(sinEfecto, descargo, uit, "SE_DEJA_SIN_EFECTO")

        val fases = filas("codigo=$codigo").associate { it["id"].asString() to it["fase"] }
        assertEquals("PREVENTIVA", fases.getValue(preventiva).asString())
        assertEquals("CONSTATADA", fases.getValue(constatada).asString())
        assertEquals("CONSTATADA", fases.getValue(sinPrevia).asString())
        assertEquals("SANCIONADA", fases.getValue(sancionada).asString())
        assertTrue(fases.getValue(anulada).isNull && fases.getValue(sinEfecto).isNull, fases.toString())
        val estados = filas("codigo=$codigo").associate { it["id"].asString() to it["estado_de_la_deuda"].asString() }
        assertEquals("ANULADA", estados.getValue(anulada))
        assertEquals("DEJADA_SIN_EFECTO", estados.getValue(sinEfecto))
        assertEquals("PENDIENTE", estados.getValue(sancionada))

        for (fase in Procedimiento.FASES) {
            val esperadas = fases.filterValues { !it.isNull && it.asString() == fase }.keys
            val filtradas = pagina("codigo=$codigo&fase=${fase.lowercase()}&size=1")
            assertEquals(esperadas.size, filtradas["totalElements"].asInt(), fase)
            assertEquals(esperadas, filas("codigo=$codigo&fase=$fase").map { it["id"].asString() }.toSet(), fase)
            assertTrue(filas("codigo=$codigo&fase=$fase").all { it["fase"].asString() == fase }, fase)
        }

        assertEquals("PREVENTIVA", expediente(preventiva)["fase"].asString())
        assertEquals("Vence el $hoy", expediente(preventiva)["actos"][0]["detalle"].asString())
        assertEquals("Vencida el ${hoy.minusDays(1)}", expediente(constatada)["actos"][0]["detalle"].asString())
        val anuladaFicha = expediente(anulada)
        assertTrue(anuladaFicha["fase"].isNull, anuladaFicha.toString())
        assertEquals("ANULADA", anuladaFicha["estado_de_la_deuda"].asString())
    }

    @Test
    fun `the ficha gives the acta, the version it used, its acts in legal order and what the rules allow now`() {
        val c = inscribir()
        val uit = uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        val version = post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))["id"].asString()
        val npNumero = numeroNp()
        val n = post(NOTIFICACIONES, notificacion(npNumero, "$ANIO_PASADO-03-02", c, plazo = 5))["id"].asString()
        val a = registrarActa(codigo, c, previa = n)
        val id = a["id"].asString()

        val e = expediente(id)
        assertEquals(id, e["acta"]["id"].asString())
        assertEquals(432.10, e["acta"]["importe_a_pagar"].asDouble())
        assertEquals("PAPELETA-$id", e["referencia"].asString())
        assertEquals(version, e["codigo_infraccion"]["id"].asString())
        assertEquals(n, e["notificacion_previa"]["id"].asString())
        assertEquals(0, e["descargos"].size())
        assertEquals(0, e["resoluciones"].size())
        assertTrue(e["anulacion"].isNull, e.toString())
        assertEquals("CONSTATADA", e["fase"].asString())
        assertEquals(LocalDate.now().toString(), e["fase_al_dia"].asString())
        assertEquals("PENDIENTE", e["estado_de_la_deuda"].asString())
        for (accion in listOf("descargo", "resolucion", "anulacion")) {
            assertTrue(e["acciones"][accion]["permitida"].asBoolean(), accion)
            assertTrue(e["acciones"][accion]["motivo"].isNull, accion)
        }
        val actos = e["actos"].filas()
        assertEquals(listOf(1, 2), actos.map { it["orden"].asInt() })
        assertEquals(listOf("Notificación previa", "Acta de constatación"), actos.map { it["acto"].asString() })
        assertEquals(listOf("$ANIO_PASADO-03-02", "$ANIO_PASADO-03-04"), actos.map { it["fecha"].asString() })
        assertEquals(listOf(npNumero, a["numero"].asString()), actos.map { it["documento"].asString() })
        assertEquals(listOf(n, id), actos.map { it["id"].asString() })
        assertEquals("Vencida el $ANIO_PASADO-03-07", actos[0]["detalle"].asString())

        // the acts B5 writes, as its services will: a descargo, the RIS notified, the recurso that leaves it without effect
        val descargo = descargo(id, uit)
        val ris = ris(id, uit)
        val notificada = conLaMarca(NOTIFICACION_RESOLUCION, notificacionDe(ris, uit))
        val recurso = recurso(id, descargo, uit, "SE_DEJA_SIN_EFECTO")
        val f = expediente(id)
        assertEquals(
            listOf(
                "Notificación previa",
                "Acta de constatación",
                "Descargo",
                "Resolución de sanción",
                "Resolución del recurso",
                "Notificación de la resolución"
            ),
            f["actos"].filas().map { it["acto"].asString() }
        )
        assertEquals(listOf(n, id, descargo, ris, recurso, notificada), f["actos"].filas().map { it["id"].asString() })
        assertEquals((1..6).toList(), f["actos"].filas().map { it["orden"].asInt() })
        assertEquals(listOf(descargo), f["descargos"].filas().map { it["id"].asString() })
        assertEquals(listOf(ris, recurso), f["resoluciones"].filas().map { it["id"].asString() })
        assertEquals(listOf(notificada), f["resoluciones"][0]["notificaciones"].filas().map { it["id"].asString() })
        assertEquals(0, f["resoluciones"][1]["notificaciones"].size())
        assertTrue(f["fase"].isNull, f.toString())
        assertEquals("DEJADA_SIN_EFECTO", f["estado_de_la_deuda"].asString())
        for (accion in listOf("descargo", "resolucion", "anulacion")) {
            assertFalse(f["acciones"][accion]["permitida"].asBoolean(), accion)
            assertTrue(f["acciones"][accion]["motivo"].asString().contains("sin efecto"), accion)
        }
        send("GET", "$ACTAS/${UUID.randomUUID()}", null, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an acta is anulada once, not before its infracción, and not when a resolución left it without effect`() {
        val c = inscribir()
        val uit = uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val id = registrarActa(codigo, c)["id"].asString()
        val anulacion = "$ACTAS/$id/anulacion"

        send(
            "POST",
            anulacion,
            mapOf("motivo" to "Error", "fecha" to "$ANIO_PASADO-03-03", "observacion" to "Antes de la infracción"),
            HttpStatus.UNPROCESSABLE_CONTENT
        )
        send(
            "POST",
            anulacion,
            mapOf("motivo" to "Error", "fecha" to LocalDate.now().plusDays(1).toString(), "observacion" to "Mañana"),
            HttpStatus.UNPROCESSABLE_CONTENT
        )
        rejected("POST", anulacion, mapOf("motivo" to " ", "observacion" to "Sin motivo"), "motivo")
        rejected("POST", anulacion, mapOf("motivo" to "Error", "observacion" to "no"), "observacion")
        send("POST", "$ACTAS/${UUID.randomUUID()}/anulacion", mapOf("motivo" to "Error", "observacion" to "No existe"), HttpStatus.NOT_FOUND)

        val a = post(anulacion, mapOf("motivo" to " Error en el número ", "fecha" to "$ANIO_PASADO-03-05", "observacion" to "Se anula"))
        assertEquals("$ANIO_PASADO-03-05", a["fecha"].asString())
        assertEquals("Error en el número", a["motivo"].asString())
        assertEquals("Se anula", a["observacion"].asString())
        assertEquals(id, a["clave"].asString())
        assertEquals(id, a["papeleta"].asString())
        val otra = tree(send("POST", anulacion, mapOf("motivo" to "Otra vez", "observacion" to "Se anula otra vez"), HttpStatus.CONFLICT))
        assertTrue(otra["detail"].asString().contains("ya está anulada"), otra.toString())
        val e = expediente(id)
        assertEquals(a["id"].asString(), e["anulacion"]["id"].asString())
        val ultimo = e["actos"].filas().last()
        assertEquals("Anulación", ultimo["acto"].asString())
        assertEquals("Error en el número", ultimo["detalle"].asString())
        assertEquals("El acta ya está anulada", e["acciones"]["anulacion"]["motivo"].asString())
        assertTrue(e["acciones"]["descargo"]["motivo"].asString().contains("anulada"), e.toString())

        // without a fecha, today
        val hoy = registrarActa(codigo, c)["id"].asString()
        assertEquals(LocalDate.now().toString(), post("$ACTAS/$hoy/anulacion", mapOf("motivo" to "Error", "observacion" to "Se anula hoy"))["fecha"].asString())

        // one a resolución left without effect has nothing left to anular (422)
        val sinEfecto = registrarActa(codigo, c)["id"].asString()
        recurso(sinEfecto, descargo(sinEfecto, uit), uit, "SE_DEJA_SIN_EFECTO")
        val problema =
            tree(send("POST", "$ACTAS/$sinEfecto/anulacion", mapOf("motivo" to "Error", "observacion" to "Se anula"), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(problema["detail"].asString().contains("sin efecto"), problema.toString())
    }

    @Test
    fun `the infracciones of a contribuyente are its actas as obligado or contribuyente, and a predio's the ones that name it`() {
        val c1 = inscribir()
        val c2 = inscribir()
        val p = predio()
        uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val a = post(ACTAS, pedidoActa(codigo, c1, "$ANIO_PASADO-03-04", contribuyente = c2))["id"].asString()
        val b = post(ACTAS, pedidoActa(codigo, c2, "$ANIO_PASADO-03-05"))["id"].asString()
        val d = post(ACTAS, pedidoActa(codigo, c1, "$ANIO_PASADO-03-06", contribuyente = null, predio = p))["id"].asString()

        val de1 = tree(send("GET", "/api/srtm/contribuyentes/$c1/infracciones", null, HttpStatus.OK))
        assertEquals(LocalDate.now().toString(), de1["al_dia"].asString())
        assertEquals(listOf(d, a), de1["actas"].filas().map { it["id"].asString() })
        val fila = de1["actas"][1]
        assertEquals("CONSTATADA", fila["fase"].asString())
        assertEquals("PENDIENTE", fila["estado_de_la_deuda"].asString())
        assertEquals(LocalDate.now().toString(), fila["fase_al_dia"].asString())
        assertEquals(432.10, fila["importe_a_pagar"].asDouble())
        assertEquals(codigo, fila["codigo"].asString())
        assertEquals(
            listOf(b, a),
            tree(send("GET", "/api/srtm/contribuyentes/$c2/infracciones", null, HttpStatus.OK))["actas"].filas().map { it["id"].asString() }
        )
        assertEquals(listOf(d), tree(send("GET", "/api/srtm/predios/$p/infracciones", null, HttpStatus.OK))["actas"].filas().map { it["id"].asString() })
        assertEquals(0, tree(send("GET", "/api/srtm/predios/${predio()}/infracciones", null, HttpStatus.OK))["actas"].size())
        send("GET", "/api/srtm/contribuyentes/${UUID.randomUUID()}/infracciones", null, HttpStatus.NOT_FOUND)
        send("GET", "/api/srtm/predios/${UUID.randomUUID()}/infracciones", null, HttpStatus.NOT_FOUND)
        send("GET", "/api/srtm/contribuyentes/$c1/infracciones?anio=2026", null, HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `only srtm writes them, and who may only read gets a 403 that names the object`() {
        val c = inscribir()
        val uit = uit(ANIO_PASADO)
        val codigo = nuevoCodigo()
        post(CUIS, version(codigo, "$ANIO_PASADO-01-01"))
        val acta = registrarActa(codigo, c)["id"].asString()
        val anulacion = post("$ACTAS/$acta/anulacion", mapOf("motivo" to "Error en el número", "observacion" to "Se anula"))["id"].asString()
        for ((objeto, id) in listOf(PAPELETA to acta, ANULACION_PAPELETA to anulacion)) {
            val registro = "/api/objects/$objeto/records/$id"
            val atributos = fields(tree(send("GET", registro, null, HttpStatus.OK))["attributes"])
            send("PUT", registro, mapOf("attributes" to atributos + ("observacion" to "Cambiada por la API")), HttpStatus.CONFLICT)
            send("DELETE", registro, null, HttpStatus.CONFLICT)
        }
        val generico = Ejemplos.papeleta("AC-${uniqueDocumento()}", registro(PAPELETA, acta)["codigo_infraccion"].asString(), uit, c)
        send("POST", "/api/objects/$PAPELETA/records", mapOf("attributes" to generico), HttpStatus.FORBIDDEN)

        val lector = funcionario(listOf(permiso(null, "READ")))
        val otra = registrarActa(codigo, c)["id"].asString()
        assertEquals(2, tree(send("GET", "$ACTAS?codigo=$codigo", null, HttpStatus.OK, lector))["totalElements"].asInt())
        assertEquals(otra, tree(send("GET", "$ACTAS/$otra", null, HttpStatus.OK, lector))["acta"]["id"].asString())
        send("GET", "/api/srtm/contribuyentes/$c/infracciones", null, HttpStatus.OK, lector)
        val alta = tree(send("POST", ACTAS, pedidoActa(codigo, c), HttpStatus.FORBIDDEN, lector))
        assertTrue(alta["detail"].asString().contains(PAPELETA), alta.toString())
        val anular = tree(send("POST", "$ACTAS/$otra/anulacion", mapOf("motivo" to "Error", "observacion" to "Sin permiso"), HttpStatus.FORBIDDEN, lector))
        assertTrue(anular["detail"].asString().contains(ANULACION_PAPELETA), anular.toString())
    }

    private fun numeroNp() = "NP-${uniqueDocumento()}"

    private fun pagina(query: String): JsonNode = tree(send("GET", "$ACTAS?$query", null, HttpStatus.OK))

    // every row a query gives, page by page
    private fun filas(query: String): List<JsonNode> {
        val filas = mutableListOf<JsonNode>()
        var page = 0
        do {
            val p = pagina("$query&page=$page&size=200")
            filas += p["content"].filas()
            page++
        } while (page < p["totalPages"].asInt())
        return filas
    }

    private fun ids(query: String) = filas(query).map { it["id"].asString() }

    private fun expediente(id: String): JsonNode = tree(send("GET", "$ACTAS/$id", null, HttpStatus.OK))

    // what B5's services will write, written here as they will: the plazo relation is any parametro_tributario row
    private fun descargo(
        acta: String,
        plazo: String
    ): String =
        conLaMarca(
            DESCARGO_PAPELETA,
            Ejemplos.descargo(acta, plazo, fecha = "$ANIO_PASADO-03-06", presentadoHasta = "$ANIO_PASADO-03-12") +
                ("numero_expediente" to "EXP-${uniqueDocumento()}")
        )

    private fun ris(
        acta: String,
        plazo: String
    ): String = conLaMarca(RESOLUCION_GERENCIA, resolucion(acta, RESOLUCION_ADMINISTRATIVA, null, plazo, "$ANIO_PASADO-03-20"))

    private fun recurso(
        acta: String,
        descargo: String,
        plazo: String,
        efecto: String
    ): String = conLaMarca(RESOLUCION_GERENCIA, resolucion(acta, RESOLUCION_RECURSO, descargo, plazo, "$ANIO_PASADO-04-20") + ("efecto" to efecto))

    // a resolución with a correlativo of its own (the número is unique)
    private fun resolucion(
        acta: String,
        tipo: String,
        descargo: String?,
        plazo: String,
        fecha: String
    ): Map<String, Any?> {
        val correlativo = uniqueDocumento().take(6).toInt() + 1
        return Ejemplos.resolucion(acta, tipo, descargo) +
            mapOf(
                "anio" to ANIO_PASADO,
                "correlativo" to correlativo,
                "numero" to numeroDeResolucion(tipo, ANIO_PASADO, correlativo),
                "fecha" to fecha,
                "plazo" to plazo
            )
    }

    private fun notificacionDe(
        resolucion: String,
        plazo: String
    ): Map<String, Any?> =
        Ejemplos.notificacionResolucion(resolucion) +
            mapOf("fecha_diligencia" to "$ANIO_PASADO-03-23", "exigible_desde" to "$ANIO_PASADO-04-14", "plazo" to plazo)

    private companion object {
        const val CUIS = "/api/srtm/infracciones/cuis"
        const val NOTIFICACIONES = "/api/srtm/infracciones/notificaciones"

        // the UIT row this class corrects
        const val ANIO_CONGELADO = 1992
    }
}
