package srtm.caja

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.awaitRowsUpdated
import srtm.emision.EMISION_LOTE
import srtm.emision.EMISION_MASIVA
import srtm.emision.TablasCore
import srtm.emision.TrabajadoresEmision
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

// #163: the cuotas de predial and of arbitrios of a contribuyente as ordenes de cobro of its organization's caja, and
// what each answer of the caja does to them (D6)
class CajaApiTest : ConCajaApiTest() {
    @Autowired
    lateinit var tablas: TablasCore

    @Autowired
    lateinit var trabajadores: TrabajadoresEmision

    @Test
    fun `unPredioDa4PredialesYLasCuotasDeArbitrios`() {
        val e = Escenario()
        conPredial(e)
        val cuotas = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))
        val c = e.contribuyente
        val documento = documentoDe(c)

        val resumen = enviar(c, e.anio)

        val liquidacion = tree(send("GET", "/api/srtm/contribuyentes/$c/liquidacion?anio=${e.anio}", null, HttpStatus.OK))
        val altas = altasDe(documento)
        val prediales = altas.filter { it.referencia.startsWith("PREDIAL-") }.sortedBy { it.referencia }
        assertEquals((1..4).map { "PREDIAL-$c-${e.anio}-C$it-v1" }, prediales.map { it.referencia })
        assertEquals(lista(liquidacion["cuotas"]) { importe(it["monto"]) }, prediales.map { it.cuerpo["importe"] })
        val primera = prediales.first().cuerpo
        assertEquals("Impuesto predial ${e.anio} cuota 1", primera["concepto"])
        assertFalse("detalle" in primera, primera.toString())
        assertEquals("${e.anio}-01-01", primera["fecha_exigibilidad"])
        assertEquals("Enviado a pedido desde srtm", primera["observacion"])
        assertEquals(documento, primera["pagador_documento"])
        assertTrue(primera["pagador_nombre"]!!.contains("FLORES"), primera.toString())
        assertFalse("pagador_externo_id" in primera || "sustituye_a" in primera, primera.toString())

        val arbitrios = altas.filter { it.referencia.startsWith("ARB-") }
        assertEquals(
            lista(cuotas) { "ARB-${it["clave"].asString()}" to importe(it["monto"]) }.toMap(),
            arbitrios.associate {
                it.referencia to
                    it.cuerpo["importe"]
            }
        )
        val enero = arbitrios.single { it.referencia == "ARB-${cuotas[0]["clave"].asString()}" }.cuerpo
        assertEquals("Servicio ${e.codigos[0]} ${e.anio}-01", enero["concepto"])
        assertEquals(codigoDe(e.predio), enero["detalle"])
        // what the predio's matriz charges
        assertEquals(matriz(e)["total"].decimalValue().compareTo(arbitrios.sumOf { BigDecimal(it.cuerpo["importe"]) }), 0)

        assertEquals(28, resumen["encoladas"].asInt(), resumen.toString())
        assertEquals(28, resumen["enviadas"].asInt(), resumen.toString())
        assertEquals(0, resumen["pendientes"].asInt(), resumen.toString())
        val estado = caja(c, e.anio)
        assertEquals(List(28) { EstadoOrdenCaja.ENVIADA }, lista(estado["ordenes"]) { it["estado"].asString() })
        assertTrue(lista(estado["ordenes"]) { it["pagada"].asBoolean() }.none { it })
        assertEquals(0, estado["saldo_a_favor"].decimalValue().signum())
        // the cuotas de predial it wrote, in their first revision
        val escritas = tree(send("GET", "/api/objects/${ObjetosCaja.CUOTA_PREDIAL}/records?contribuyente=$c&size=10", null, HttpStatus.OK))
        assertEquals(setOf(1), lista(escritas["content"]) { it["attributes"]["revision"].asInt() }.toSet())
        assertEquals(4, escritas["totalElements"].asInt())
    }

    @Test
    fun `reenviarNoDuplica`() {
        val e = Escenario()
        conPredial(e)
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val documento = documentoDe(e.contribuyente)
        enviar(e.contribuyente, e.anio)
        val antes = altasDe(documento).size

        val otra = enviar(e.contribuyente, e.anio)

        assertEquals(28, antes)
        assertEquals(antes, altasDe(documento).size)
        assertEquals(0, otra["encoladas"].asInt(), otra.toString())
        assertEquals(0, otra["enviadas"].asInt(), otra.toString())
        assertEquals(28, lista(caja(e.contribuyente, e.anio)["ordenes"]) { it }.size)
    }

    @Test
    fun `cajaCaidaDejaPendienteYLaVueltaLaManda`() {
        val e = Escenario()
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        respuesta = { if (it.alta) 503 to """{"detail":"caída"}""" else null }

        val resumen = enviar(e.contribuyente, e.anio)

        assertEquals(24, resumen["pendientes"].asInt(), resumen.toString())
        assertTrue(resumen["error"].asString().contains("503"), resumen.toString())
        val caidas = lista(caja(e.contribuyente, e.anio)["ordenes"]) { it }
        assertEquals(List(24) { EstadoOrdenCaja.POR_ENVIAR }, caidas.map { it["estado"].asString() })
        assertTrue(caidas.all { it["ultimo_error"].asString().contains("503") }, caidas.first().toString())

        respuesta = { null }
        vueltas()

        assertEquals(List(24) { EstadoOrdenCaja.ENVIADA }, lista(caja(e.contribuyente, e.anio)["ordenes"]) { it["estado"].asString() })
        // each one once in the caja that answered: 2 bodies, the 503's and the 201's
        assertTrue(altasDe(documentoDe(e.contribuyente)).groupBy { it.referencia }.values.all { it.size == 2 })
    }

    @Test
    fun `sinCajaEs409YNoEscribe`() {
        val c = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), semilla)["id"].asString()

        for (metodo in listOf("POST", "GET")) {
            val problema = tree(send(metodo, "/api/srtm/contribuyentes/$c/caja?anio=2026", null, HttpStatus.CONFLICT, semilla))
            assertEquals(SIN_CAJA_CONFIGURADA, problema["detail"].asString(), problema.toString())
        }
        val (estado, cuerpo) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to 2026, "formato" to "PDF", "enviar_a_caja" to true), semilla)
        assertEquals(HttpStatus.CONFLICT, estado, cuerpo)
        assertEquals(SIN_CAJA_CONFIGURADA, tree(cuerpo)["detail"].asString())

        assertTrue(PEDIDOS.isEmpty())
        val ordenes = tree(send("GET", "/api/objects/${ObjetosCaja.ORDEN_CAJA}/records?contribuyente=$c&size=1", null, HttpStatus.OK, semilla))
        assertEquals(0, ordenes["totalElements"].asInt())
    }

    @Test
    fun `cuotaEnCeroNoSeEnvia`() {
        // the year's parameters, and a declaración without autoavalúo: four cuotas de predial of 0.00
        val e = Escenario()
        conPredial(e, autoavaluo = null)
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val liquidacion = tree(send("GET", "/api/srtm/contribuyentes/${e.contribuyente}/liquidacion?anio=${e.anio}", null, HttpStatus.OK))
        assertEquals(List(4) { "0.00" }, lista(liquidacion["cuotas"]) { importe(it["monto"]) })

        enviar(e.contribuyente, e.anio)

        val altas = altasDe(documentoDe(e.contribuyente))
        assertEquals(24, altas.size)
        assertTrue(altas.none { it.referencia.startsWith("PREDIAL-") })
        val escritas = tree(send("GET", "/api/objects/${ObjetosCaja.CUOTA_PREDIAL}/records?contribuyente=${e.contribuyente}&size=1", null, HttpStatus.OK))
        assertEquals(0, escritas["totalElements"].asInt())
    }

    @Test
    fun `sinDocumentoNoSeEnvia`() {
        val e = Escenario()
        val c =
            post(
                "/api/srtm/contribuyentes",
                personaNatural(uniqueDocumento()) + mapOf("tipo_documento" to "SIN DOCUMENTO", "numero_documento" to null)
            )["id"].asString()
        val predio = e.predioDeclarado(c)
        send("POST", "/api/srtm/predios/$predio/arbitrios", pedido(e.anio), HttpStatus.CREATED)

        val resumen = enviar(c, e.anio)

        assertEquals(1, resumen["sin_documento"].asInt(), resumen.toString())
        assertEquals(0, resumen["encoladas"].asInt(), resumen.toString())
        assertTrue(PEDIDOS.isEmpty())
        assertTrue(lista(caja(c, e.anio)["ordenes"]) { it }.isEmpty())
    }

    @Test
    fun `dosTomasDeLaMismaOrdenLaMandanUnaVez`() {
        val e = Escenario()
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        respuesta = { if (it.alta) 503 to "{}" else null }
        enviar(e.contribuyente, e.anio)
        val ids = lista(caja(e.contribuyente, e.anio)["ordenes"]) { UUID.fromString(it["id"].asString()) }
        PEDIDOS.clear()
        respuesta = { null }
        retraso = { 50L }

        val (a, b) =
            runBlocking {
                val a = async(Dispatchers.IO) { envio.enviar(ids, Duration.ofSeconds(60)) }
                val b = async(Dispatchers.IO) { envio.enviar(ids, Duration.ofSeconds(60)) }
                a.await() to b.await()
            }

        val altas = altasDe(documentoDe(e.contribuyente))
        assertEquals(24, altas.size)
        assertEquals(24, altas.map { it.referencia }.toSet().size)
        assertEquals(24, a.enviadas + b.enviadas, "$a $b")
        assertEquals(List(24) { EstadoOrdenCaja.ENVIADA }, lista(caja(e.contribuyente, e.anio)["ordenes"]) { it["estado"].asString() })
    }

    @Test
    fun `sustitucionReintentadaDespuesDeTimeout`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val primeras = (1..4).map { "PREDIAL-${e.contribuyente}-${e.anio}-C$it-v1" }
        // the caja takes longer than the client's timeout (1 s) to answer a sustitución
        retraso = { if (it.cuerpo["sustituye_a"] != null) 2500L else 0L }
        PEDIDOS.clear()

        // the write of the declaración sends them (#164)
        val resumen = cambiarAutoavaluo(dj, 80000)!!

        assertEquals(4, resumen["pendientes"].asInt(), resumen.toString())
        val segundas = (1..4).map { "PREDIAL-${e.contribuyente}-${e.anio}-C$it-v2" }
        val porReferencia = lista(caja(e.contribuyente, e.anio)["ordenes"]) { it }.associateBy { it["referencia"].asString() }
        assertEquals(List(4) { EstadoOrdenCaja.POR_ENVIAR }, segundas.map { porReferencia.getValue(it)["estado"].asString() })
        assertEquals(List(4) { EstadoOrdenCaja.ENVIADA }, primeras.map { porReferencia.getValue(it)["estado"].asString() })

        retraso = { 0L }
        vueltas()

        val despues = lista(caja(e.contribuyente, e.anio)["ordenes"]) { it }.associateBy { it["referencia"].asString() }
        assertEquals(List(4) { EstadoOrdenCaja.ENVIADA }, segundas.map { despues.getValue(it)["estado"].asString() })
        assertEquals(List(4) { EstadoOrdenCaja.RETIRADA }, primeras.map { despues.getValue(it)["estado"].asString() })
        // the vuelta sent the same body again, with what it replaces
        for ((i, referencia) in segundas.withIndex()) {
            val cuerpos = PEDIDOS.filter { it.alta && it.referencia == referencia }.map { it.cuerpo }
            assertEquals(2, cuerpos.size, referencia)
            assertEquals(cuerpos[0], cuerpos[1])
            assertEquals(primeras[i], cuerpos[0]["sustituye_a"])
        }
    }

    @Test
    fun `emisionMasivaConEnviarACaja`() {
        val e = Escenario()
        conPredial(e)
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        soloLaDelTest()
        val (estado, cuerpo) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to e.anio, "formato" to "PDF", "enviar_a_caja" to true))
        assertEquals(HttpStatus.ACCEPTED, estado, cuerpo)
        val id = tree(cuerpo)["id"].asString()
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }

        val grupo = trabajadores.grupo("caja", 1, Path.of("build/emisiones-test-grupos", "caja"))
        grupo.iniciar()
        try {
            val terminada = esperar(id) { it["estado"].asString() in setOf("TERMINADA", "FALLIDA") }
            assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        } finally {
            runBlocking { grupo.detener() }
        }
        // the masiva only queues them: the loop sends them
        assertTrue(lista(caja(e.contribuyente, e.anio)["ordenes"]) { it["estado"].asString() }.all { it == EstadoOrdenCaja.POR_ENVIAR })
        vueltas()

        val ordenes = lista(caja(e.contribuyente, e.anio)["ordenes"]) { it }
        assertEquals(List(28) { EstadoOrdenCaja.ENVIADA }, ordenes.map { it["estado"].asString() })
        assertTrue(altasDe(documentoDe(e.contribuyente)).all { it.cuerpo["observacion"] == "Emisión masiva ${e.anio}" })
    }

    // the masiva with enviar_a_caja writes ordenes and cuotas de predial as its caller: who may launch it but not
    // create those is refused at once, not in each lote
    @Test
    fun `emisionACajaSinPermisoDeOrdenesEs403`() {
        val sin =
            funcionario(
                listOf(permiso(EMISION_MASIVA, "CREATE"), permiso(EMISION_MASIVA, "UPDATE"), permiso(EMISION_MASIVA, "READ"), permiso(EMISION_LOTE, "CREATE"))
            )

        val (estado, cuerpo) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to 2026, "formato" to "PDF", "enviar_a_caja" to true), sin)

        assertEquals(HttpStatus.FORBIDDEN, estado, cuerpo)
        assertTrue(tree(cuerpo)["detail"].asString().contains(ObjetosCaja.ORDEN_CAJA), cuerpo)
    }

    // a timeout leaves v2 POR_ENVIAR while caja holds it: a v3 waits for it (no alta, no cancel), and caja never holds
    // two ordenes of one cuota
    @Test
    fun `timeoutYOtroCambioNoDejanDosOrdenes`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val referencias = { v: Int -> (1..4).map { "PREDIAL-${e.contribuyente}-${e.anio}-C$it-v$v" } }
        retraso = { if (it.cuerpo["sustituye_a"] != null) 2500L else 0L }
        cambiarAutoavaluo(dj, 80000)
        assertTrue(referencias(2).all { ordenes(e.contribuyente, e.anio).getValue(it)["estado"].asString() == EstadoOrdenCaja.POR_ENVIAR })

        // another change while v2 is in caja's hands: v3 is not sent, v2 is (the same body again)
        retraso = { 0L }
        val diferida = cambiarAutoavaluo(dj, 90000)!!
        assertTrue(diferida["pendientes"].asInt() >= 4, diferida.toString())
        assertTrue(PEDIDOS.none { it.referencia in referencias(3) })
        assertTrue(referencias(2).all { ordenes(e.contribuyente, e.anio).getValue(it)["estado"].asString() == EstadoOrdenCaja.ENVIADA })

        // once v2 is resolved, v3 replaces it
        enviar(e.contribuyente, e.anio)

        val despues = ordenes(e.contribuyente, e.anio)
        assertEquals(List(4) { EstadoOrdenCaja.ENVIADA }, referencias(3).map { despues.getValue(it)["estado"].asString() })
        assertEquals(List(8) { EstadoOrdenCaja.RETIRADA }, (referencias(1) + referencias(2)).map { despues.getValue(it)["estado"].asString() })
        assertEquals(referencias(2), referencias(3).map { r -> PEDIDOS.last { it.alta && it.referencia == r }.cuerpo["sustituye_a"] })
        val prediales = vivasEnCaja().filter { it.startsWith("PREDIAL-${e.contribuyente}-") }
        assertEquals(referencias(3).toSet(), prediales.toSet())
    }

    // D6: a retiro answered 200 or 404 is RETIRADA, one answered 409 was paid in caja
    @Test
    fun `retiroSegunLaRespuesta`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = (1..4).map { "PREDIAL-${e.contribuyente}-${e.anio}-C$it-v1" }
        respuesta = {
            when {
                it.alta -> null
                it.referencia == v1[1] -> 404 to """{"detail":"no existe"}"""
                it.referencia == v1[2] -> 409 to """{"detail":"Pagada con el recibo 001-000123"}"""
                else -> null
            }
        }
        // without the declaración, the predial is 0.00: nothing to collect. its anulación withdraws them (#164)
        val numero = tree(send("GET", "/api/srtm/declaraciones/$dj", null, HttpStatus.OK))["declaracion"]["numero_declaracion"].asString()

        val resumen = escribir("POST", "/api/srtm/declaraciones/$dj/anular", mapOf("motivo_anulacion" to "Declarada dos veces")).second!!

        val o = ordenes(e.contribuyente, e.anio)
        assertEquals(
            listOf(EstadoOrdenCaja.RETIRADA, EstadoOrdenCaja.RETIRADA, EstadoOrdenCaja.PAGADA_EN_CAJA, EstadoOrdenCaja.RETIRADA),
            v1.map { o.getValue(it)["estado"].asString() }
        )
        assertTrue(o.getValue(v1[2])["pagada"].asBoolean())
        assertEquals(3, resumen["retiradas"].asInt(), resumen.toString())
        assertEquals(1, resumen["pagadas_en_caja"].asInt(), resumen.toString())
        val retiros = PEDIDOS.filter { !it.alta && it.referencia in v1 }
        assertEquals(4, retiros.size)
        assertTrue(retiros.all { it.cuerpo["motivo"] == "Anulación de la DJ n° $numero" }, retiros.map { it.cuerpo }.toString())
    }

    // D6: an alta answered 409 is CONFLICTO; 400, 422 and 404 are ERROR, each with why
    @Test
    fun `altaSegunLaRespuesta`() {
        val e = Escenario()
        val cuotas = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))
        val r = lista(cuotas) { "ARB-${it["clave"].asString()}" }
        respuesta = {
            when {
                !it.alta -> null
                it.referencia == r[0] -> 409 to """{"detail":"La referencia ya tiene otra deuda"}"""
                it.referencia == r[1] -> 400 to """{"detail":"importe inválido"}"""
                it.referencia == r[2] -> 422 to "{}"
                it.referencia == r[3] -> 404 to "{}"
                else -> null
            }
        }

        val resumen = enviar(e.contribuyente, e.anio)

        val o = ordenes(e.contribuyente, e.anio)
        assertEquals(
            listOf(EstadoOrdenCaja.CONFLICTO, EstadoOrdenCaja.ERROR, EstadoOrdenCaja.ERROR, EstadoOrdenCaja.ERROR),
            r.take(4).map { o.getValue(it)["estado"].asString() }
        )
        assertEquals(
            listOf("La referencia ya tiene otra deuda", "importe inválido", "caja-backend respondió 422", "caja-backend respondió 404"),
            r.take(4).map { o.getValue(it)["ultimo_error"].asString() }
        )
        assertEquals(1, resumen["conflictos"].asInt(), resumen.toString())
        assertEquals(20, resumen["enviadas"].asInt(), resumen.toString())
        assertTrue(r.drop(4).all { o.getValue(it)["estado"].asString() == EstadoOrdenCaja.ENVIADA })
    }

    // Ruling 26: caja answers an alta it already withdrew (a retry after a timeout, its vigencia said no) with 200 and
    // estado ANULADA: the orden is not in caja to collect, so it is CONFLICTO with why, never ENVIADA
    @Test
    fun `altaAnuladaEnCajaEsConflicto`() {
        val e = Escenario()
        val r = lista(tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))) { "ARB-${it["clave"].asString()}" }
        respuesta = { if (it.alta && it.referencia == r[0]) 200 to """{"orden_id":"${UUID.randomUUID()}","estado":"ANULADA"}""" else null }

        val resumen = enviar(e.contribuyente, e.anio)

        val o = ordenes(e.contribuyente, e.anio).getValue(r[0])
        assertEquals(EstadoOrdenCaja.CONFLICTO, o["estado"].asString(), o.toString())
        assertEquals("caja-backend tiene la orden ANULADA", o["ultimo_error"].asString())
        assertEquals(1, resumen["conflictos"].asInt(), resumen.toString())
        assertEquals(23, resumen["enviadas"].asInt(), resumen.toString())
    }

    // srtm.caja.intentos=3: the third try caja does not answer is ERROR, and it is not tried again
    @Test
    fun `intentosAgotadosEsError`() {
        val e = Escenario()
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val documento = documentoDe(e.contribuyente)
        respuesta = { if (it.alta && it.cuerpo["pagador_documento"] == documento) 503 to "{}" else null }

        enviar(e.contribuyente, e.anio)
        vueltas()

        val o = registrosDe(e.contribuyente)
        assertEquals(List(24) { EstadoOrdenCaja.ERROR }, o.map { it["estado"].asString() })
        assertTrue(o.all { it["intentos"].asInt() == 3 && it["ultimo_error"].asString().contains("503") }, o.first().toString())
        assertTrue(altasDe(documento).groupBy { it.referencia }.values.all { it.size == 3 })
    }

    // Ruling 27: ERROR is not for ever. the POST a pedido queues again the ordenes in ERROR srtm still wants, as tried
    // (caja may hold them), and sends them now
    @Test
    fun `errorDeseadoVuelveAEnviarseAPedido`() {
        val e = Escenario()
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val documento = documentoDe(e.contribuyente)
        respuesta = { if (it.alta && it.cuerpo["pagador_documento"] == documento) 503 to "{}" else null }
        enviar(e.contribuyente, e.anio)
        vueltas()
        assertEquals(List(24) { EstadoOrdenCaja.ERROR }, registrosDe(e.contribuyente).map { it["estado"].asString() })
        respuesta = { null }
        PEDIDOS.clear()

        val resumen = enviar(e.contribuyente, e.anio)

        assertEquals(24, resumen["enviadas"].asInt(), resumen.toString())
        assertEquals(0, resumen["encoladas"].asInt(), resumen.toString())
        val o = registrosDe(e.contribuyente)
        assertEquals(List(24) { EstadoOrdenCaja.ENVIADA }, o.map { it["estado"].asString() })
        assertTrue(o.all { it["intentos"].asInt() == 4 }, o.first().toString())
        // the same orden, once each: no new version
        assertEquals(24, altasDe(documento).map { it.referencia }.toSet().size)
        assertEquals(24, altasDe(documento).size)
    }

    // nothing of another class or run is left for this test's group to take: only its emission runs
    private fun soloLaDelTest() {
        runBlocking {
            for (t in tablas.de(EMISION_LOTE, listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDO' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO')")
                    .fetch()
                    .awaitRowsUpdated()
            }
            for (t in tablas.de(EMISION_MASIVA, listOf("estado"))) {
                db
                    .sql(
                        "UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDA' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO', 'ENSAMBLANDO')"
                    ).fetch()
                    .awaitRowsUpdated()
            }
        }
    }

    private fun esperar(
        id: String,
        listo: (JsonNode) -> Boolean
    ): JsonNode {
        val limite = System.nanoTime() + 90_000_000_000L
        while (true) {
            val job = tree(send("GET", "/api/srtm/emisiones/$id", null, HttpStatus.OK))
            if (listo(job)) return job
            assertTrue(System.nanoTime() < limite, "la emisión no avanza: $job")
            Thread.sleep(100)
        }
    }
}
