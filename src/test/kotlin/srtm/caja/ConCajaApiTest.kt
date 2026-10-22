package srtm.caja

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.arbitrios.ConArbitriosApiTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.RoundingMode
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// #163: what the caja's api tests share. a caja falsa (the jdk's HttpServer) keeps every call it gets, with what it
// answered, and answers as the test programs it. the tests run as the admin of an organization of their own, the one
// the caja's entry names by its slug; the seeded one has no caja. an orden gets 3 tries
abstract class ConCajaApiTest : ConArbitriosApiTest() {
    @Autowired
    lateinit var envio: EnvioACaja

    // the seeded admin's: an organization without caja
    protected lateinit var semilla: String

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

    // the year's parameters of the predial (the csv's figures of 2026, in force only in the escenario's year) and, with
    // an autoavalúo, a second predio the contribuyente declares with it: that declaración's id
    protected fun conPredial(
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

    // the declaración with another autoavalúo
    protected fun cambiarAutoavaluo(
        dj: String,
        autoavaluo: Int
    ) {
        put(
            "/api/srtm/declaraciones/$dj",
            fields(tree(send("GET", "/api/srtm/declaraciones/$dj", null, HttpStatus.OK))["declaracion"]) + ("valor_autoavaluo" to autoavaluo)
        )
    }

    protected fun enviar(
        c: String,
        anio: Int
    ): JsonNode = tree(send("POST", "/api/srtm/contribuyentes/$c/caja?anio=$anio", null, HttpStatus.OK))

    protected fun caja(
        c: String,
        anio: Int
    ): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$c/caja?anio=$anio", null, HttpStatus.OK))

    // the contribuyente's ordenes by referencia
    protected fun ordenes(
        c: String,
        anio: Int
    ): Map<String, JsonNode> = lista(caja(c, anio)["ordenes"]) { it }.associateBy { it["referencia"].asString() }

    // its ordenes as core keeps them: every field, the queue's too
    protected fun registrosDe(c: String): List<JsonNode> =
        lista(
            tree(send("GET", "/api/objects/${ObjetosCaja.ORDEN_CAJA}/records?contribuyente=$c&size=100", null, HttpStatus.OK))["content"]
        ) { it["attributes"] }

    // until a vuelta takes nothing: another test's leftovers of this organization may come first
    protected fun vueltas() {
        repeat(20) { if (runBlocking { envio.vuelta() } == 0) return }
    }

    protected fun documentoDe(c: String) =
        tree(send("GET", "/api/objects/contribuyente/records/$c", null, HttpStatus.OK))["attributes"]["numero_documento"].asString()

    protected fun codigoDe(predio: String) = tree(send("GET", "/api/objects/predio/records/$predio", null, HttpStatus.OK))["attributes"]["codigo"].asString()

    protected fun altasDe(documento: String) = PEDIDOS.filter { it.alta && it.cuerpo["pagador_documento"] == documento }

    protected fun importe(n: JsonNode) = n.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString()

    // a call the caja falsa got, and the status it answered (null while it waits): caja holds an alta it answered 2xx
    // to even when srtm's client stopped waiting
    class Pedido(
        val ruta: String,
        val cuerpo: Map<String, String>
    ) {
        @Volatile
        var estado: Int? = null

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

        // the ordenes the caja falsa holds now, by what it answered: an alta adds one (and withdraws the one it
        // replaces), a retiro answered 200 or 404 withdraws one
        fun vivasEnCaja(): Set<String> {
            val vivas = linkedSetOf<String>()
            for (p in PEDIDOS) {
                val estado = p.estado ?: continue
                if (p.alta && estado in 200..201) {
                    p.cuerpo["sustituye_a"]?.let { vivas -= it }
                    vivas += p.referencia
                } else if (!p.alta && (estado == 200 || estado == 404)) {
                    vivas -= p.referencia
                }
            }
            return vivas
        }

        // never stopped: every class of the suite that extends this one shares it, and the jvm ends with the run
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
                    pedido.estado = estado
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
            registro.add("srtm.caja.intentos") { 3 }
            registro.add("srtm.emision.dir") { "build/emisiones-test" }
            registro.add("srtm.emision.espera") { "200ms" }
        }
    }
}
