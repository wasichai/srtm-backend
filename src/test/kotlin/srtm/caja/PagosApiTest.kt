package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import srtm.arbitrios.AvisoDeArbitrios
import srtm.arbitrios.Llaves
import srtm.rentas.Records
import srtm.rentas.Registros
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID
import kotlin.random.Random

// #165: caja's buzón delivers each pago with POST /api/srtm/caja/pagos, as the organization's service account (role
// CAJA). each orden is applied to its cuota, or kept as saldo a favor when it cannot be (202); a pagoId seen before
// answers 409 with the same body; a PAGO_ANULADO undoes its original. the events have the contract's shape
// (caja-backend's cuerpoPagoRegistrado and cuerpoPagoAnulado)
class PagosApiTest : ConPagosApiTest() {
    @Autowired
    lateinit var registros: Registros

    @Test
    fun `pagoNormalAplicaYLaCuotaQuedaPagada`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)

        val (estado, cuerpo) = pagar(registrado(listOf(referencia(cuota) to importe(cuota["monto"]))))

        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertEquals(APLICADO, cuerpo)
        assertTrue(celda(e, cuota)["pagada"].asBoolean())
        val o = ordenes(e.contribuyente, e.anio).getValue(referencia(cuota))
        assertTrue(o["pagada"].asBoolean(), o.toString())
        assertEquals(0, saldo(e).compareTo(BigDecimal.ZERO))
        // the other months are still debt
        assertEquals(1, lista(matriz(e)["filas"]) { f -> lista(f["meses"]) { it["pagada"].asBoolean() }.count { it } }.sum())
        // a sync does not send the paid one again
        PEDIDOS.clear()
        enviar(e.contribuyente, e.anio)
        assertTrue(PEDIDOS.isEmpty(), PEDIDOS.map { it.referencia }.toString())
    }

    @Test
    fun `pagoRepetidoEs409ConElMismoCuerpo`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        val pago = registrado(listOf(referencia(cuota) to importe(cuota["monto"])))
        assertEquals(HttpStatus.OK to APLICADO, pagar(pago))

        assertEquals(HttpStatus.CONFLICT to APLICADO, pagar(pago))
        assertEquals(1, registrosDePago(pago.id).size)
    }

    // the comment of #165: caja did not hear the 202 and sends it again; the 409 repeats a_cuenta, or caja loses it
    @Test
    fun `pagoACuentaRepetidoRepiteACuenta`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        val pago = registrado(listOf(referencia(cuota) to "1.00"))
        val (estado, primero) = pagar(pago)
        assertEquals(HttpStatus.ACCEPTED, estado, primero)

        val (otra, segundo) = pagar(pago)

        assertEquals(HttpStatus.CONFLICT, otra)
        assertEquals(primero, segundo)
        assertTrue(tree(segundo)["a_cuenta"].asBoolean())
    }

    @Test
    fun `importeDistintoVaACuenta`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)

        val (estado, cuerpo) = pagar(registrado(listOf(referencia(cuota) to "1.00")))

        assertEquals(HttpStatus.ACCEPTED, estado, cuerpo)
        val monto = importe(cuota["monto"])
        assertEquals("""{"aplicado":false,"a_cuenta":true,"motivo":"el importe no coincide: 1.00 en caja, $monto en srtm"}""", cuerpo)
        assertEquals(0, saldo(e).compareTo(BigDecimal("1.00")))
        assertFalse(celda(e, cuota)["pagada"].asBoolean())
    }

    @Test
    fun `cuotaSustituidaVaACuenta`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v1"
        val importe = importe(ordenes(e.contribuyente, e.anio).getValue(v1)["importe"])
        cambiarAutoavaluo(dj, 80000)

        val (estado, cuerpo) = pagar(registrado(listOf(v1 to importe)))

        assertEquals(HttpStatus.ACCEPTED, estado, cuerpo)
        val v2 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v2"
        assertEquals("la deuda $v1 fue sustituida por $v2", tree(cuerpo)["motivo"].asString())
        assertEquals(0, saldo(e).compareTo(BigDecimal(importe)))
    }

    @Test
    fun `anulacionRevierteElPago`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        val pago = registrado(listOf(referencia(cuota) to importe(cuota["monto"])))
        assertEquals(HttpStatus.OK to APLICADO, pagar(pago))
        assertTrue(celda(e, cuota)["pagada"].asBoolean())

        val (estado, cuerpo) = pagar(anulado(pago))

        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertEquals(APLICADO, cuerpo)
        assertFalse(celda(e, cuota)["pagada"].asBoolean())
        assertFalse(ordenes(e.contribuyente, e.anio).getValue(referencia(cuota))["pagada"].asBoolean())
    }

    // #175 (Ruling 36): C1-v1 was paid, then redetermined (D7 keeps it as it was paid), then its recibo annulled.
    // caja holds v1 as PENDIENTE again with the old importe: the sincronización after the anulación replaces it with
    // C1-v2 at the liquidación's importe, and caja's vigencia no longer collects v1. the retry of the anulación (409)
    // sends nothing more
    @Test
    fun `anulacionTrasRedeterminarSustituyeLaOrdenVieja`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v1"
        val v2 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v2"
        val importeViejo = importe(ordenes(e.contribuyente, e.anio).getValue(v1)["importe"])
        val pago = registrado(listOf(v1 to importeViejo))
        assertEquals(HttpStatus.OK to APLICADO, pagar(pago))
        cambiarAutoavaluo(dj, 80000)
        val antes = ordenes(e.contribuyente, e.anio)
        assertTrue(antes.getValue(v1)["pagada"].asBoolean(), antes.getValue(v1).toString())
        assertFalse(v2 in antes, antes.keys.toString())
        // what the liquidación says of C1 now
        val liquidacion = tree(send("GET", "/api/srtm/contribuyentes/${e.contribuyente}/liquidacion?anio=${e.anio}", null, HttpStatus.OK))
        val importeNuevo = importe(liquidacion["cuotas"][0]["monto"])
        assertTrue(importeNuevo != importeViejo, importeNuevo)
        PEDIDOS.clear()
        val anulacion = anulado(pago)

        val (estado, cuerpo) = pagar(anulacion)

        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertEquals(APLICADO, cuerpo)
        val alta = PEDIDOS.single { it.alta && it.referencia == v2 }
        assertEquals(v1, alta.cuerpo["sustituye_a"], alta.cuerpo.toString())
        assertEquals(importeNuevo, alta.cuerpo["importe"])
        val despues = ordenes(e.contribuyente, e.anio)
        assertTrue(despues.getValue(v1)["estado"].asString() in setOf(EstadoOrdenCaja.POR_RETIRAR, EstadoOrdenCaja.RETIRADA), despues.getValue(v1).toString())
        assertFalse(despues.getValue(v1)["pagada"].asBoolean())
        assertEquals(EstadoOrdenCaja.ENVIADA, despues.getValue(v2)["estado"].asString())
        assertEquals(importeNuevo, importe(despues.getValue(v2)["importe"]))
        assertFalse(vigenciaDe(v1, importeViejo)["vigente"].asBoolean())
        assertTrue(vigenciaDe(v2, importeNuevo)["vigente"].asBoolean())

        PEDIDOS.clear()
        assertEquals(HttpStatus.CONFLICT to APLICADO, pagar(anulacion))
        assertTrue(PEDIDOS.isEmpty(), PEDIDOS.map { it.referencia }.toString())
    }

    @Test
    fun `anulacionDeUnACuentaRevierteElSaldo`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        val pago = registrado(listOf(referencia(cuota) to "1.00"))
        assertEquals(HttpStatus.ACCEPTED, pagar(pago).first)
        assertEquals(0, saldo(e).compareTo(BigDecimal("1.00")))

        val anulacion = anulado(pago)
        val (estado, cuerpo) = pagar(anulacion)

        // an anulación is no payment: never a cuenta, even of a pago that was
        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertEquals(APLICADO, cuerpo)
        assertEquals(0, saldo(e).compareTo(BigDecimal.ZERO))
        // its retry repeats it: caja would read a_cuenta from the 409
        assertEquals(HttpStatus.CONFLICT to APLICADO, pagar(anulacion))
    }

    // caja refused the sustitución of an orden it had collected (D6: the new one CONFLICTO, the old one ENVIADA). its
    // pago is kept a cuenta; once it is annulled, caja holds the old debt as PENDIENTE again: srtm withdraws it
    @Test
    fun `anulacionReintentaElRetiro`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v1"
        val importe = importe(ordenes(e.contribuyente, e.anio).getValue(v1)["importe"])
        respuesta = { if (it.alta && it.cuerpo["sustituye_a"] == v1) 409 to """{"detail":"La orden que sustituye ya está pagada"}""" else null }
        cambiarAutoavaluo(dj, 80000)
        assertEquals(EstadoOrdenCaja.ENVIADA, ordenes(e.contribuyente, e.anio).getValue(v1)["estado"].asString())
        val pago = registrado(listOf(v1 to importe))
        assertEquals(HttpStatus.ACCEPTED, pagar(pago).first)
        respuesta = { null }
        PEDIDOS.clear()

        val (estado, cuerpo) = pagar(anulado(pago))

        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertEquals(listOf(v1), PEDIDOS.filter { !it.alta }.map { it.referencia })
        assertEquals(EstadoOrdenCaja.RETIRADA, ordenes(e.contribuyente, e.anio).getValue(v1)["estado"].asString())
    }

    // Ruling 28: caja refused the retiro of v1 (paid there: PAGADA_EN_CAJA) and its pago went a cuenta, the cuota being
    // gone. paid is then only what Pagadas says: GET /caja and the vigencia agree it is not, and once the debt is back
    // it reaches caja again
    @Test
    fun `pagadaEnCajaConPagoACuentaNoRetieneLaRanura`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v1"
        val importe = importe(ordenes(e.contribuyente, e.anio).getValue(v1)["importe"])
        respuesta = { if (!it.alta && it.referencia == v1) 409 to """{"detail":"Pagada con el recibo 001-0000123"}""" else null }
        escribir("POST", "/api/srtm/declaraciones/$dj/anular", mapOf("motivo_anulacion" to "Declarada dos veces"))
        assertEquals(EstadoOrdenCaja.PAGADA_EN_CAJA, ordenes(e.contribuyente, e.anio).getValue(v1)["estado"].asString())
        assertTrue(ordenes(e.contribuyente, e.anio).getValue(v1)["pagada"].asBoolean())
        assertEquals(HttpStatus.ACCEPTED, pagar(registrado(listOf(v1 to importe))).first)
        respuesta = { null }

        val pagadaAhora = ordenes(e.contribuyente, e.anio).getValue(v1)
        assertFalse(pagadaAhora["pagada"].asBoolean(), pagadaAhora.toString())
        val vigencia = vigenciaDe(v1, importe)
        assertFalse(vigencia["vigente"].asBoolean())
        assertEquals("retirada", vigencia["motivo"].asString(), vigencia.toString())

        // the debt is back: another declaración, with another autoavalúo (the next revision of each cuota)
        PEDIDOS.clear()
        val otro =
            post(
                "/api/srtm/predios",
                mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 125", "tipo_predio" to "PREDIO URBANO")
            )["id"].asString()
        post(
            "/api/srtm/declaraciones",
            mapOf("contribuyente" to e.contribuyente, "predio" to otro, "anio" to e.anio, "secuencia_uso" to "1", "valor_autoavaluo" to 80000)
        )
        enviar(e.contribuyente, e.anio)

        val v2 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v2"
        val alta = PEDIDOS.single { it.alta && it.referencia == v2 }
        assertFalse("sustituye_a" in alta.cuerpo, alta.cuerpo.toString())
        val despues = ordenes(e.contribuyente, e.anio)
        assertEquals(EstadoOrdenCaja.ENVIADA, despues.getValue(v2)["estado"].asString())
        assertFalse(despues.getValue(v2)["pagada"].asBoolean())
        assertTrue(vigenciaDe(v2, importe(despues.getValue(v2)["importe"]))["vigente"].asBoolean())
    }

    @Test
    fun `registradoRepetidoTrasAnulacionNoRevive`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        val pago = registrado(listOf(referencia(cuota) to importe(cuota["monto"])))
        pagar(pago)
        assertEquals(HttpStatus.OK, pagar(anulado(pago)).first)

        assertEquals(HttpStatus.CONFLICT to APLICADO, pagar(pago))
        assertFalse(celda(e, cuota)["pagada"].asBoolean())
    }

    // an orden_caja of another organization (the seeded one, written by srtm's service) is no debt of this one
    @Test
    fun `referenciaDeOtraOrganizacionVaACuenta`() {
        val ajeno = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), semilla)["id"].asString()
        val referencia = "ARB-otra-${UUID.randomUUID()}|1"
        val propio = token
        token = semilla
        try {
            comoAdmin {
                registros.create(
                    ObjetosCaja.ORDEN_CAJA,
                    OrdenCaja::class.java,
                    Records
                        .attributes(
                            OrdenCaja(
                                referencia = referencia,
                                tipo = TipoOrdenCaja.ARBITRIO,
                                ranura = "ARB|otra",
                                anio = 2026,
                                importe = BigDecimal("10.00"),
                                concepto = "Otra",
                                estado = EstadoOrdenCaja.ENVIADA,
                                intentos = 1,
                                contribuyente = ajeno
                            )
                        ).filterValues { it != null }
                )
            }
        } finally {
            token = propio
        }

        val (estado, cuerpo) = pagar(registrado(listOf(referencia to "10.00")))

        assertEquals(HttpStatus.ACCEPTED, estado, cuerpo)
        assertEquals("""{"aplicado":false,"a_cuenta":true,"motivo":"no existe"}""", cuerpo)
    }

    @Test
    fun `segundoPagoDeOrdenYaPagadaVaACuenta`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        assertEquals(HttpStatus.OK to APLICADO, pagar(registrado(listOf(referencia(cuota) to importe(cuota["monto"])), numero = "001-0000077")))

        val (estado, cuerpo) = pagar(registrado(listOf(referencia(cuota) to importe(cuota["monto"]))))

        assertEquals(HttpStatus.ACCEPTED, estado, cuerpo)
        assertEquals("""{"aplicado":false,"a_cuenta":true,"motivo":"ya pagada con el recibo 001-0000077"}""", cuerpo)
        assertEquals(0, saldo(e).compareTo(cuota["monto"].decimalValue()))
    }

    // caja never sends an anulación before its pago: one srtm does not know is asked again (503), and nothing is kept
    @Test
    fun `anuladoSinOriginalEs503`() {
        val huerfano = anulado(registrado(listOf("ARB-x" to "1.00")))

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE to """{"motivo":"no conozco el pago original"}""", pagar(huerfano))
        assertTrue(registrosDePago(huerfano.id).isEmpty())
    }

    // an organization without caja: a 409 would be caja's «delivered», so it is asked again later
    @Test
    fun `sinCajaEs503`() {
        val ajena = cuentaDeServicio(otraOrganizacion())

        val (estado, cuerpo) = pagar(registrado(listOf("ARB-x" to "1.00")), ajena)

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, estado, cuerpo)
        assertEquals("""{"motivo":"caja no está configurada para esta municipalidad"}""", cuerpo)
    }

    @Test
    fun `cuerpoIlegibleEs400`() {
        assertEquals(HttpStatus.BAD_REQUEST, pagar("{").first)
        assertEquals(HttpStatus.BAD_REQUEST, pagar(registrado(listOf("ARB-x" to "1.00")).json.replace("PAGO_REGISTRADO", "PAGO_RARO")).first)
        assertEquals(HttpStatus.BAD_REQUEST, pagar(registrado(listOf("ARB-x" to "uno"))).first)
    }

    @Test
    fun `sinTokenEs401`() {
        assertEquals(HttpStatus.UNAUTHORIZED, pagar(registrado(listOf("ARB-x" to "1.00")), null).first)
    }

    @Test
    fun `usuarioSinPermisoEs403`() {
        val lector = funcionario(listOf(permiso(ObjetosCaja.PAGO_CAJA, "READ")))
        val pago = registrado(listOf("ARB-x" to "1.00"))

        assertEquals(HttpStatus.FORBIDDEN, pagar(pago, lector).first)
        assertTrue(registrosDePago(pago.id).isEmpty())
    }

    // Ruling 29: the pagos are caja's, its service account's: a person, even the organization's admin, gets a 403
    @Test
    fun `adminHumanoEs403`() {
        val pago = registrado(listOf("ARB-x" to "1.00"))

        val (estado, cuerpo) = pagar(pago, token)

        assertEquals(HttpStatus.FORBIDDEN, estado, cuerpo)
        assertTrue(registrosDePago(pago.id).isEmpty())
    }

    // a cuota paid in caja is not redetermined: the nivel changes the twelve months of the first servicio, eleven
    // are annulled and written again, the paid one stays vigente, and the header counts it
    @Test
    fun `redeterminacionNoTocaMesesPagados`() {
        val e = Escenario()
        e.parametro(Llaves.BASE_ARBITRIO, e.codigos[0], texto = "AREA_CONSTRUIDA_M2")
        val dj = declaracionDe(e)
        post("/api/srtm/declaraciones/$dj/niveles", NIVEL)
        val cuotas = lista(tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))) { it }
        enviar(e.contribuyente, e.anio)
        val pagada = cuotas.first { it["servicio"].asString() == e.servicios[0] && it["periodo"].asInt() == 3 }
        assertEquals(HttpStatus.OK to APLICADO, pagar(registrado(listOf(referencia(pagada) to importe(pagada["monto"])))))
        PEDIDOS.clear()

        val aviso = arbitriosDe("/api/srtm/declaraciones/$dj/niveles", NIVEL + mapOf("numero_piso" to 2, "area_construida" to 20))

        assertEquals(11, aviso["redeterminadas"].asInt(), aviso.toString())
        assertEquals(11, aviso["anuladas"].asInt(), aviso.toString())
        assertEquals(1, aviso["pagadas"].asInt(), aviso.toString())
        val marzo = celda(e, pagada)
        assertEquals(pagada["id"].asString(), marzo["id"].asString())
        assertTrue(marzo["pagada"].asBoolean())
        assertEquals(0, marzo["monto"].decimalValue().compareTo(pagada["monto"].decimalValue()))
        // caja gets the eleven months replaced, and nothing of the paid one
        assertEquals(11, PEDIDOS.count { it.alta })
        assertTrue(PEDIDOS.none { it.referencia == referencia(pagada) || it.cuerpo["sustituye_a"] == referencia(pagada) })
    }

    @Test
    fun `anularCuotaPagadaEs409`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        val pago = registrado(listOf(referencia(cuota) to importe(cuota["monto"])), numero = "001-0000042")
        pagar(pago)

        val problema =
            tree(
                send(
                    "POST",
                    "/api/srtm/arbitrios/cuotas/${cuota["id"].asString()}/anulacion",
                    mapOf("motivo" to "Uso mal declarado", "observacion" to "Informe de fiscalización de prueba"),
                    HttpStatus.CONFLICT
                )
            )

        assertEquals("la cuota ya se pagó en caja (recibo 001-0000042)", problema["detail"].asString())
    }

    // the cuota's month in its predio's matriz
    private fun celda(
        e: Escenario,
        cuota: JsonNode
    ): JsonNode {
        val fila = lista(matriz(e)["filas"]) { it }.first { it["servicio"]["id"].asString() == cuota["servicio"].asString() }
        return fila["meses"][cuota["periodo"].asInt() - 1]
    }

    // caja's question of vigencia of one orden: its answer
    private fun vigenciaDe(
        referencia: String,
        importe: String
    ): JsonNode {
        val result =
            client
                .post()
                .uri("/api/srtm/caja/ordenes/vigencia")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"ordenes":[{"referencia_externa":"$referencia","importe":"$importe"}]}""")
                .header(HttpHeaders.AUTHORIZATION, caja)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(HttpStatus.OK.value(), result.status.value(), result.responseBody)
        return tree(result.responseBody!!)["ordenes"][0]
    }

    private fun saldo(e: Escenario): BigDecimal = caja(e.contribuyente, e.anio)["saldo_a_favor"].decimalValue()

    private fun registrosDePago(pagoId: String) =
        lista(tree(send("GET", "/api/objects/${ObjetosCaja.PAGO_CAJA}/records?pago_id=$pagoId", null, HttpStatus.OK))["content"]) { it }

    private fun declaracionDe(e: Escenario): String =
        tree(send("GET", "/api/objects/declaracion_predial/records?predio=${e.predio}", null, HttpStatus.OK))["content"].single()["id"].asString()

    // a write of a declaración: its Srtm-Arbitrios header
    private fun arbitriosDe(
        path: String,
        body: Map<String, Any?>
    ): JsonNode {
        val result =
            client
                .post()
                .uri(path)
                .bodyValue(body)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(HttpStatus.CREATED.value(), result.status.value(), result.responseBody)
        return tree(result.responseHeaders.getFirst(AvisoDeArbitrios.ENCABEZADO)!!)
    }

    private companion object {
        const val APLICADO = """{"aplicado":true,"a_cuenta":false,"motivo":null}"""

        val NIVEL =
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2016,
                "mes_construccion" to 1,
                "material" to "CONCRETO",
                "estado_conservacion" to "BUENO",
                "area_construida" to 100,
                "muros_columnas" to "C",
                "techos" to "D",
                "puertas_ventanas" to "E"
            )
    }
}
