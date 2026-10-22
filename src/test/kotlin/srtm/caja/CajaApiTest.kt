package srtm.caja

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.arbitrios.ConArbitriosApiTest
import srtm.emision.EMISION_LOTE
import srtm.emision.EMISION_MASIVA
import srtm.emision.TablasCore
import srtm.emision.TrabajadoresEmision
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// #163: the cuotas de predial and of arbitrios of a contribuyente as ordenes de cobro of its organization's caja. a caja
// falsa (the jdk's HttpServer) keeps every body it gets and answers as the test programs it. the tests run as the admin
// of an organization of their own, the one the caja's entry names by its slug; the seeded one has no caja
class CajaApiTest : ConArbitriosApiTest() {
    @Autowired
    lateinit var envio: EnvioACaja

    @Autowired
    lateinit var tablas: TablasCore

    @Autowired
    lateinit var trabajadores: TrabajadoresEmision

    // the seeded admin's: an organization without caja
    private lateinit var semilla: String

    @BeforeEach
    fun enLaOrganizacionDeLaCaja() {
        semilla = token
        token = organizacion ?: crearOrganizacion().also { organizacion = it }
        PEDIDOS.clear()
        respuesta = { null }
        retraso = { 0L }
    }

    private fun crearOrganizacion(): String {
        val email = "admin@$SLUG.test"
        val clave = "clave-de-la-organizacion-de-la-caja"
        post("/api/organizations", mapOf("name" to SLUG, "slug" to SLUG, "adminEmail" to email, "adminPassword" to clave))
        val suyo = bearer(email, clave)
        aplicarModelo(suyo)
        return suyo
    }

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
        put(
            "/api/srtm/declaraciones/$dj",
            fields(tree(send("GET", "/api/srtm/declaraciones/$dj", null, HttpStatus.OK))["declaracion"]) + ("valor_autoavaluo" to 80000)
        )
        // the caja takes longer than the client's timeout (1 s) to answer a sustitución
        retraso = { if (it.cuerpo["sustituye_a"] != null) 2500L else 0L }
        PEDIDOS.clear()

        val resumen = enviar(e.contribuyente, e.anio)

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

        val ordenes = lista(caja(e.contribuyente, e.anio)["ordenes"]) { it }
        assertEquals(List(28) { EstadoOrdenCaja.ENVIADA }, ordenes.map { it["estado"].asString() })
        assertTrue(altasDe(documentoDe(e.contribuyente)).all { it.cuerpo["observacion"] == "Emisión masiva ${e.anio}" })
    }

    // the year's parameters of the predial (the csv's figures of 2026, in force only in the escenario's year) and, with
    // an autoavalúo, a second predio the contribuyente declares with it: that declaración's id
    private fun conPredial(
        e: Escenario,
        autoavaluo: Int? = 60000
    ): String? {
        e.parametro("UIT", null, valor = "5500.00")
        listOf("0.2", "0.6", "1.0").forEachIndexed { i, v -> e.parametro("TRAMO_PREDIAL", "${i + 1}", valor = v) }
        listOf("15", "60").forEachIndexed { i, v -> e.parametro("TRAMO_PREDIAL_LIMITE", "${i + 1}", valor = v) }
        e.parametro("PREDIAL_MINIMO", null, valor = "0.6")
        if (autoavaluo == null) return null
        val otro =
            post(
                "/api/srtm/predios",
                mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 124", "tipo_predio" to "PREDIO URBANO")
            )["id"].asString()
        return post(
            "/api/srtm/declaraciones",
            mapOf("contribuyente" to e.contribuyente, "predio" to otro, "anio" to e.anio, "secuencia_uso" to "1", "valor_autoavaluo" to autoavaluo)
        )["id"].asString()
    }

    private fun enviar(
        c: String,
        anio: Int
    ): JsonNode = tree(send("POST", "/api/srtm/contribuyentes/$c/caja?anio=$anio", null, HttpStatus.OK))

    private fun caja(
        c: String,
        anio: Int
    ): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$c/caja?anio=$anio", null, HttpStatus.OK))

    // until a vuelta takes nothing: another test's leftovers of this organization may come first
    private fun vueltas() {
        repeat(20) { if (runBlocking { envio.vuelta() } == 0) return }
    }

    private fun documentoDe(c: String) =
        tree(send("GET", "/api/objects/contribuyente/records/$c", null, HttpStatus.OK))["attributes"]["numero_documento"].asString()

    private fun codigoDe(predio: String) = tree(send("GET", "/api/objects/predio/records/$predio", null, HttpStatus.OK))["attributes"]["codigo"].asString()

    private fun altasDe(documento: String) = PEDIDOS.filter { it.alta && it.cuerpo["pagador_documento"] == documento }

    private fun importe(n: JsonNode) = n.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString()

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

    // a call the caja falsa got
    class Pedido(
        val ruta: String,
        val cuerpo: Map<String, String>
    ) {
        val alta: Boolean get() = ruta.endsWith("/ordenes-de-cobro")
        val referencia: String get() = cuerpo["referencia_externa"].orEmpty()
    }

    companion object {
        private val SLUG = "caja-${UUID.randomUUID().toString().take(8)}"
        private var organizacion: String? = null

        private val JSON = JsonMapper.builder().build()

        val PEDIDOS = CopyOnWriteArrayList<Pedido>()

        // what the caja answers a call (null: an alta's 201, a retiro's 200), and how long it waits first
        @Volatile
        var respuesta: (Pedido) -> Pair<Int, String>? = { null }

        @Volatile
        var retraso: (Pedido) -> Long = { 0L }

        private val CAJA: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                // a call that waits never holds up the next one
                executor = Executors.newCachedThreadPool()
                createContext("/api/auth/token") { ex -> responder(ex, 200, """{"token":"t-caja","expiresAt":"2999-01-01T00:00:00Z"}""") }
                createContext("/api/caja/ordenes-de-cobro") { ex ->
                    if (ex.requestHeaders.getFirst("Authorization") != "Bearer t-caja") {
                        responder(ex, 401, "{}")
                        return@createContext
                    }
                    @Suppress("UNCHECKED_CAST")
                    val cuerpo = JSON.readValue(ex.requestBody.readAllBytes(), Map::class.java) as Map<String, String>
                    val pedido = Pedido(ex.requestURI.path, cuerpo)
                    PEDIDOS += pedido
                    Thread.sleep(retraso(pedido))
                    val (estado, texto) =
                        respuesta(pedido)
                            ?: if (pedido.alta) {
                                201 to """{"orden_id":"${UUID.randomUUID()}","estado":"PENDIENTE"}"""
                            } else {
                                200 to """{"orden_id":"${UUID.randomUUID()}","estado":"ANULADA"}"""
                            }
                    runCatching { responder(ex, estado, texto) }
                }
                start()
            }

        private fun responder(
            ex: HttpExchange,
            estado: Int,
            cuerpo: String
        ) {
            val bytes = cuerpo.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(estado, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun caja(registro: DynamicPropertyRegistry) {
            registro.add("srtm.caja.organizaciones.prueba.organizacion") { SLUG }
            registro.add("srtm.caja.organizaciones.prueba.url") { "http://127.0.0.1:${CAJA.address.port}" }
            registro.add("srtm.caja.organizaciones.prueba.client-id") { "rentas" }
            registro.add("srtm.caja.organizaciones.prueba.client-secret") { "secreto-de-prueba" }
            registro.add("srtm.caja.organizaciones.prueba.timeout") { "1s" }
            registro.add("srtm.caja.plazo-en-linea") { "PT30S" }
            registro.add("srtm.emision.dir") { "build/emisiones-test" }
            registro.add("srtm.emision.espera") { "200ms" }
        }

        @JvmStatic
        @AfterAll
        fun parar() {
            CAJA.stop(0)
        }
    }
}
