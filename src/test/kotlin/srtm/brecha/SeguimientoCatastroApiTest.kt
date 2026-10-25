package srtm.brecha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.impuesto.Parametros
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// the seguimiento loop (#203) against fakes of catastro-backend (the jdk's http server): the test drives vuelta
// (srtm.catastro.seguimiento.minutos = 0 keeps the loop off) at the hour it picks. every test has its own organization
// and its own fake, so none sees another's sincronizacion, cambios or corridas; a vuelta passes over every organization
// with a catastro, but a test only asserts on its own
class SeguimientoCatastroApiTest : SrtmApiTest() {
    @Autowired
    private lateinit var seguimiento: SeguimientoCatastro

    @Autowired
    private lateinit var candado: ClusterLock

    @Autowired
    private lateinit var directorio: TenantDirectory

    // a new organization of `falso`'s catastro, with the model and the verified predial parameters (a corrida reads
    // them): its admin's token and its id
    private fun organizacion(falso: CatastroFalso): Pair<String, UUID> {
        val email = "admin@${falso.slug}.test"
        val clave = "clave-de-la-organizacion-del-seguimiento"
        post("/api/organizations", mapOf("name" to falso.slug, "slug" to falso.slug, "adminEmail" to email, "adminPassword" to clave))
        val suyo = bearer(email, clave)
        aplicarModelo(suyo)
        for (p in Parametros.predial) {
            val attributes =
                mapOf(
                    "tipo" to p.tipo,
                    "clave" to p.clave,
                    "vigencia_desde" to p.vigenciaDesde.toString(),
                    "vigencia_hasta" to p.vigenciaHasta?.toString(),
                    "valor_numerico" to p.valorNumerico,
                    "texto" to p.texto,
                    "norma" to p.norma,
                    "fuente" to p.fuente,
                    "transcribio" to p.transcribio,
                    "verifico" to p.verifico
                ).filterValues { it != null }
            post("/api/objects/parametro_tributario/records", mapOf("attributes" to attributes), suyo)
        }
        val id = runBlocking { directorio.organizationsWithObject(SINCRONIZACION_CATASTRO) }.single { it.slug == falso.slug }.id
        return suyo to id
    }

    private fun registros(
        objeto: String,
        org: String
    ): List<JsonNode> =
        tree(send("GET", "/api/objects/$objeto/records?size=100", null, HttpStatus.OK, org))["content"]
            .iterator()
            .asSequence()
            .map { it["attributes"] }
            .toList()

    private fun sincronizacion(org: String): JsonNode = registros(SINCRONIZACION_CATASTRO, org).single()

    private fun corridas(
        org: String,
        anio: Int
    ): List<JsonNode> = tree(send("GET", "/api/srtm/brecha/corridas?anio=$anio", null, HttpStatus.OK, org)).iterator().asSequence().toList()

    // GET until it ends
    private fun terminada(
        org: String,
        id: String
    ): JsonNode {
        repeat(300) {
            val c = tree(send("GET", "/api/srtm/brecha/corridas/$id", null, HttpStatus.OK, org))
            if (c["estado"].asString() in setOf(TERMINADA, FALLIDA)) return c
            Thread.sleep(100)
        }
        error("la corrida $id no terminó")
    }

    private fun vuelta(
        ahora: ZonedDateTime,
        hora: Int = 0
    ) = runBlocking { seguimiento.vuelta(ahora, hora) }

    private fun ahora() = ZonedDateTime.now(Seguimiento.LIMA)

    @Test
    fun `a change read from catastro leaves its cambio_catastro and moves the cursor`() {
        val falso = FALSOS.getValue("lee")
        val (org, _) = organizacion(falso)
        falso.paginas.set(mapOf("" to pagina("c1", false, cambio("P-1", "2026-10-20T15:00:00Z"))))

        // no hour reaches 24: nothing is launched
        vuelta(ahora(), hora = 24)

        val cambio = registros(CAMBIO_CATASTRO, org).single()
        assertEquals("P-1", cambio["codigo_municipal"].asString())
        assertEquals("predio_catastral", cambio["objeto"].asString())
        assertEquals("UPDATE", cambio["operacion"].asString())
        assertEquals("area_terreno, uso", cambio["campos"].asString())
        assertEquals(Instant.parse("2026-10-20T15:00:00Z"), instante(cambio["en"].asString()))
        assertEquals("ENT-1", cambio["entrega"].asString())
        val s = sincronizacion(org)
        assertEquals("c1", s["cursor"].asString())
        assertEquals(Instant.parse("2026-10-20T15:00:00Z"), instante(s["ultimo_cambio_en"].asString()))
        assertEquals(1, s["pendientes"].asInt())
        assertTrue(s["error"]?.isNull ?: true, s.toString())
        assertTrue(corridas(org, ahora().year).isEmpty())

        vuelta(ahora(), hora = 24)
        assertEquals(1, registros(CAMBIO_CATASTRO, org).size)
        assertEquals(listOf("", "c1"), falso.desdes.toList())
        assertEquals(1, sincronizacion(org)["pendientes"].asInt())
    }

    @Test
    fun `a pending change starts the corrida of the hour, and the next pass the same day does not`() {
        val falso = FALSOS.getValue("cambia")
        val (org, _) = organizacion(falso)
        // two pages: the reader follows hayMas
        falso.paginas.set(
            mapOf(
                "" to pagina("p1", true, cambio("Q-1", "2026-10-20T15:00:00Z")),
                "p1" to pagina("p2", false, cambio("Q-2", "2026-10-21T15:00:00Z"))
            )
        )
        val ahora = ahora()

        vuelta(ahora)

        assertEquals(2, registros(CAMBIO_CATASTRO, org).size)
        val lanzada = corridas(org, ahora.year).single()
        assertEquals("CAMBIOS", lanzada["origen"].asString())
        assertEquals(TERMINADA, terminada(org, lanzada["id"].asString())["estado"].asString())
        val s = sincronizacion(org)
        assertEquals("p2", s["cursor"].asString())
        assertEquals(Instant.parse("2026-10-21T15:00:00Z"), instante(s["ultimo_cambio_en"].asString()))
        assertEquals(0, s["pendientes"].asInt())
        assertEquals(lanzada["id"].asString(), s["ultima_corrida"].asString())

        // one a day: the same day starts none more
        vuelta(ahora)
        assertEquals(1, corridas(org, ahora.year).size)
    }

    @Test
    fun `without changes there is no corrida but the weekly`() {
        val falso = FALSOS.getValue("semanal")
        val (org, _) = organizacion(falso)
        val ahora = ahora()

        // an empty history: the first pass launches the weekly one
        vuelta(ahora)
        val primera = corridas(org, ahora.year).single()
        assertEquals("SEMANAL", primera["origen"].asString())
        assertEquals(TERMINADA, terminada(org, primera["id"].asString())["estado"].asString())

        vuelta(ahora.plusDays(1))
        assertEquals(1, corridas(org, ahora.year).size)

        vuelta(ahora.plusDays(7))
        val despues = corridas(org, ahora.year)
        assertEquals(2, despues.size)
        val semanal = despues.single { it["id"].asString() != primera["id"].asString() }
        assertEquals("SEMANAL", semanal["origen"].asString())
        assertEquals(semanal["id"].asString(), sincronizacion(org)["ultima_corrida"].asString())
        terminada(org, semanal["id"].asString())
    }

    @Test
    fun `catastro failing leaves its error and no corrida, and the next good read clears it`() {
        val falso = FALSOS.getValue("falla")
        val (org, _) = organizacion(falso)
        falso.paginas.set(mapOf("" to pagina("c1", false, cambio("F-1", "2026-10-20T15:00:00Z"))))
        vuelta(ahora(), hora = 24)
        assertEquals("c1", sincronizacion(org)["cursor"].asString())

        // a failed read decides nothing, though there is a pendiente and the hour (0) is reached
        falso.estado.set(500)
        vuelta(ahora())
        var s = sincronizacion(org)
        assertTrue(s["error"].asString().contains("respondió 500"), s.toString())
        assertEquals("c1", s["cursor"].asString())
        assertEquals(1, s["pendientes"].asInt())
        assertTrue(corridas(org, ahora().year).isEmpty())

        falso.estado.set(404)
        vuelta(ahora())
        s = sincronizacion(org)
        assertTrue(s["error"].asString().contains("respondió 404"), s.toString())
        assertEquals("c1", s["cursor"].asString())
        assertTrue(corridas(org, ahora().year).isEmpty())

        falso.estado.set(200)
        vuelta(ahora(), hora = 24)
        s = sincronizacion(org)
        assertTrue(s["error"]?.isNull ?: true, s.toString())
        assertEquals("c1", s["cursor"].asString())
        assertEquals(1, registros(CAMBIO_CATASTRO, org).size)
    }

    @Test
    fun `a lock held elsewhere skips the organization`() {
        val falso = FALSOS.getValue("candado")
        val (org, id) = organizacion(falso)
        falso.paginas.set(mapOf("" to pagina("c1", false, cambio("L-1", "2026-10-20T15:00:00Z"))))
        val ajeno = runBlocking { candado.tryLock("srtm:seguimiento-catastro:$id") }
        assertNotNull(ajeno)
        try {
            vuelta(ahora())
            assertEquals(0, falso.llamadas.get())
            assertTrue(registros(SINCRONIZACION_CATASTRO, org).isEmpty())
        } finally {
            runBlocking { ajeno!!.release() }
        }
        vuelta(ahora(), hora = 24)
        assertEquals(1, falso.llamadas.get())
        assertNull(sincronizacion(org)["error"]?.takeUnless { it.isNull })
    }

    // one organization's catastro-backend: a token, its cambios by desde ("" from the start; any other desde answers
    // nothing new with the same cursor back, as catastro does) or `estado` when it is not 200, no valuation (a corrida
    // over an empty padrón ends TERMINADA) and no /titulares (404)
    class CatastroFalso(
        nombre: String
    ) {
        val slug = "seg-$nombre-${UUID.randomUUID().toString().take(8)}"
        val paginas = AtomicReference<Map<String, String>>(emptyMap())
        val estado = AtomicInteger(200)
        val llamadas = AtomicInteger()
        val desdes = CopyOnWriteArrayList<String>()
        val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/auth/token") { ex ->
                    responder(ex, 200, """{"token":"t-$nombre","expiresAt":"2999-01-01T00:00:00Z","serviceAccount":"srtm"}""")
                }
                createContext("/api/catastro/cambios") { ex ->
                    llamadas.incrementAndGet()
                    val desde = parametros(ex)["desde"].orEmpty()
                    desdes += desde
                    val suyo = estado.get()
                    if (suyo != 200) {
                        responder(ex, suyo, "{}")
                        return@createContext
                    }
                    val cursor = if (desde.isEmpty()) "null" else "\"$desde\""
                    responder(ex, 200, paginas.get()[desde] ?: """{"items":[],"cursor":$cursor,"hayMas":false}""")
                }
                createContext("/api/catastro/valuaciones") { ex ->
                    val anio = parametros(ex)["anio"]
                    responder(ex, 200, """{"anio":$anio,"version":"valuacion-v1","page":0,"size":500,"total":0,"items":[]}""")
                }
                createContext("/api/catastro/titulares") { ex -> responder(ex, 404, "{}") }
                start()
            }

        private fun parametros(ex: HttpExchange): Map<String, String> =
            ex.requestURI.query
                .orEmpty()
                .split("&")
                .filter { "=" in it }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
    }

    companion object {
        private val FALSOS = listOf("lee", "cambia", "semanal", "falla", "candado").associateWith { CatastroFalso(it) }

        private fun cambio(
            codigo: String,
            en: String
        ) = """{"codigoMunicipal":"$codigo","objeto":"predio_catastral","operacion":"UPDATE","campos":["area_terreno","uso"],
            "en":"$en","entrega":"ENT-1"}"""

        private fun pagina(
            cursor: String,
            hayMas: Boolean,
            vararg cambios: String
        ) = """{"items":[${cambios.joinToString(",")}],"cursor":"$cursor","hayMas":$hayMas}"""

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
        fun catastro(registro: DynamicPropertyRegistry) {
            registro.add("srtm.catastro.enabled") { true }
            for ((nombre, falso) in FALSOS) {
                registro.add("srtm.catastro.organizaciones.$nombre.organizacion") { falso.slug }
                registro.add("srtm.catastro.organizaciones.$nombre.url") { "http://127.0.0.1:${falso.server.address.port}" }
                registro.add("srtm.catastro.organizaciones.$nombre.client-id") { "srtm-$nombre" }
                registro.add("srtm.catastro.organizaciones.$nombre.client-secret") { "secreto-de-prueba" }
            }
            // the test drives vuelta; its hora is the test's
            registro.add("srtm.catastro.seguimiento.minutos") { 0 }
            registro.add("srtm.catastro.seguimiento.hora") { 0 }
        }

        @JvmStatic
        @AfterAll
        fun parar() = FALSOS.values.forEach { it.server.stop(0) }
    }
}
