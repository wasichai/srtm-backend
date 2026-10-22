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
class PagosApiTest : ConCajaApiTest() {
    @Autowired
    lateinit var registros: Registros

    // the service account's token, in the caja's organization
    private lateinit var caja: String

    @BeforeEach
    fun comoLaCuentaDeCaja() {
        caja = tokenDeCaja()
    }

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

    @Test
    fun `anulacionDeUnACuentaRevierteElSaldo`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        val pago = registrado(listOf(referencia(cuota) to "1.00"))
        assertEquals(HttpStatus.ACCEPTED, pagar(pago).first)
        assertEquals(0, saldo(e).compareTo(BigDecimal("1.00")))

        val (estado, cuerpo) = pagar(anulado(pago))

        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertEquals("""{"aplicado":true,"a_cuenta":true,"motivo":null}""", cuerpo)
        assertEquals(0, saldo(e).compareTo(BigDecimal.ZERO))
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

    // a month paid in caja is not redetermined: the nivel changes the twelve months of the first servicio, eleven
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

    // the escenario's year determined and sent to caja: its first cuota
    private fun determinadaYEnviada(e: Escenario): JsonNode {
        val cuotas = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))
        enviar(e.contribuyente, e.anio)
        return cuotas[0]
    }

    private fun referencia(cuota: JsonNode) = "ARB-${cuota["clave"].asString()}"

    // the cuota's month in its predio's matriz
    private fun celda(
        e: Escenario,
        cuota: JsonNode
    ): JsonNode {
        val fila = lista(matriz(e)["filas"]) { it }.first { it["servicio"]["id"].asString() == cuota["servicio"].asString() }
        return fila["meses"][cuota["periodo"].asInt() - 1]
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

    // an event as caja-backend writes it: its pagoId and its json
    class Evento(
        val id: String,
        val json: String
    )

    private fun registrado(
        ordenes: List<Pair<String, String>>,
        numero: String = "001-${Random.nextInt(1_000_000, 9_999_999)}"
    ): Evento {
        val id = UUID.randomUUID().toString()
        val total = ordenes.sumOf { it.second.toBigDecimalOrNull() ?: BigDecimal.ZERO }.toPlainString()
        val lineas =
            ordenes.joinToString(",") { (ref, importe) ->
                """{"ordenId":"${UUID.randomUUID()}","referenciaExterna":"$ref","importe":"$importe","actualizadoA":"2026-10-22"}"""
            }
        return Evento(
            id,
            """{"pagoId":"$id","tipo":"PAGO_REGISTRADO","sistemaOrigen":"rentas",""" +
                """"recibo":{"numero":"$numero","serie":"001","fechaDePago":"2026-10-22","cajero":"ana@muni.gob.pe","formaDePago":"EFECTIVO"},""" +
                """"pagador":{"documento":"12345678","nombre":"FLORES OTINIANO JUNIOR","idExterno":null},""" +
                """"total":"$total","actualizadoA":"2026-10-22","ordenes":[$lineas]}"""
        )
    }

    private fun anulado(original: Evento): Evento {
        val id = UUID.randomUUID().toString()
        val o = tree(original.json)
        return Evento(
            id,
            """{"pagoId":"$id","tipo":"PAGO_ANULADO","pagoOriginalId":"${original.id}","recibo":${o["recibo"]},""" +
                """"motivo":"Error de digitación","fecha":"2026-10-22","total":"${o["total"].asString()}"}"""
        )
    }

    private fun pagar(
        evento: Evento,
        quien: String? = caja
    ) = pagar(evento.json, quien)

    // caja's POST {url}/pagos: the status and the body as they came
    private fun pagar(
        json: String,
        quien: String? = caja
    ): Pair<HttpStatus, String> {
        val spec =
            client
                .post()
                .uri("/api/srtm/caja/pagos")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
        val result =
            (quien?.let { spec.header(HttpHeaders.AUTHORIZATION, it) } ?: spec)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to (result.responseBody ?: "")
    }

    // docs/develop/alta-organizacion.md: the role CAJA and the service account caja of the organization, created once
    // per run; a token of its own for each test (it lives 15 minutes)
    private fun tokenDeCaja(): String {
        val (id, secreto) =
            credencial ?: run {
                val rol = "CAJA_${uniqueDocumento()}"
                send("POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED)
                val crear = listOf(ObjetosCaja.PAGO_CAJA, ObjetosCaja.PAGO_CAJA_DETALLE)
                val leer = listOf(ObjetosCaja.ORDEN_CAJA, "cuota_arbitrio", "anulacion_cuota_arbitrio", ObjetosCaja.CUOTA_PREDIAL, "contribuyente")
                val permisos = crear.flatMap { listOf(permiso(it, "CREATE"), permiso(it, "READ")) } + leer.map { permiso(it, "READ") }
                send("PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
                val cuenta = post("/api/service-accounts", mapOf("name" to "caja-${uniqueDocumento()}", "roles" to listOf(rol)))
                (cuenta["clientId"].asString() to cuenta["clientSecret"].asString()).also { credencial = it }
            }
        val respuesta =
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(mapOf("clientId" to id, "clientSecret" to secreto))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return "Bearer ${tree(respuesta)["token"].asString()}"
    }

    private companion object {
        const val APLICADO = """{"aplicado":true,"a_cuenta":false,"motivo":null}"""

        var credencial: Pair<String, String>? = null

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
