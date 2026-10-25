package srtm.brecha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.emision.TablasCore
import srtm.impuesto.Parametros
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory
import java.math.BigDecimal
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

    @Autowired
    private lateinit var tablas: TablasCore

    @Autowired
    private lateinit var db: DatabaseClient

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

    // #203: the aviso's fixture, as BrechaApiTest's (a predio with a DJ and catastro's valued item, through the
    // portal's own api)
    private fun predio(
        org: String,
        codigo: String
    ) = post(
        "/api/srtm/predios",
        mapOf("codigo" to codigo, "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to "01"),
        org
    )["id"].asString()

    private fun contribuyente(org: String) = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)["id"].asString()

    private fun declarar(
        org: String,
        contribuyente: String,
        predio: String,
        autoavaluo: String,
        anio: Int
    ) {
        val id =
            post(
                "/api/srtm/declaraciones",
                mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to anio, "secuencia_uso" to "1"),
                org
            )["id"].asString()
        val guardada = tree(send("GET", "/api/objects/declaracion_predial/records/$id", null, HttpStatus.OK, org))["attributes"]
        val valores =
            mapOf(
                "valor_autoavaluo" to autoavaluo.toBigDecimal(),
                "valor_condominio" to autoavaluo.toBigDecimal(),
                "valor_afecto" to autoavaluo.toBigDecimal()
            )
        send("PUT", "/api/objects/declaracion_predial/records/$id", mapOf("attributes" to fields(guardada) + valores), HttpStatus.OK, org)
    }

    private fun item(
        codigo: String,
        autoavaluo: String
    ) = """{"codigoMunicipal":"$codigo","codigoCpu":null,"sector":"01","manzana":"A","lote":"1","uso":"010101",
        "areaTerreno":200,"areaConstruida":100,"longitudFrente":10.5,"terreno":1.00,"construccion":1.00,"obras":0.00,
        "autoavaluo":$autoavaluo,"motivo":null,"llave":null,"loteGeom":null}"""

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

    @Test
    fun `a change without a codigo is kept and the cursor moves on`() {
        val falso = FALSOS.getValue("sincodigo")
        val (org, _) = organizacion(falso)
        val sinCodigo = """{"codigoMunicipal":null,"objeto":"titular_catastral","operacion":"DELETE","campos":[],
            "en":"2026-10-20T15:00:00Z","entrega":null}"""
        falso.paginas.set(mapOf("" to pagina("c1", false, sinCodigo, cambio("S-1", "2026-10-20T16:00:00Z"))))

        vuelta(ahora(), hora = 24)

        val cambios = registros(CAMBIO_CATASTRO, org)
        assertEquals(2, cambios.size)
        assertTrue(cambios.any { it["codigo_municipal"]?.isNull ?: true }, cambios.toString())
        val s = sincronizacion(org)
        assertEquals("c1", s["cursor"].asString())
        assertEquals(2, s["pendientes"].asInt())
        assertTrue(s["error"]?.isNull ?: true, s.toString())
    }

    @Test
    fun `a page core refuses to write leaves its error, its cursor and no corrida`() {
        val falso = FALSOS.getValue("escritura")
        val (org, _) = organizacion(falso)
        // core refuses an operacion off its enum: the whole page rolls back, its good cambio too
        val ajena = """{"codigoMunicipal":"W-2","objeto":"predio_catastral","operacion":"OTRA","campos":[],
            "en":"2026-10-20T16:00:00Z","entrega":null}"""
        falso.paginas.set(mapOf("" to pagina("c1", false, cambio("W-1", "2026-10-20T15:00:00Z"), ajena)))

        // hora 0 and no corrida yet: a pass that got to decide would launch the weekly one
        vuelta(ahora(), hora = 0)

        var s = sincronizacion(org)
        assertTrue(!s["error"].isNull && s["error"].asString().isNotBlank(), s.toString())
        assertTrue(s["cursor"]?.isNull ?: true, s.toString())
        assertTrue(registros(CAMBIO_CATASTRO, org).isEmpty())
        assertTrue(corridas(org, ahora().year).isEmpty())

        // once catastro sends what core takes, the feed goes on
        falso.paginas.set(mapOf("" to pagina("c1", false, cambio("W-1", "2026-10-20T15:00:00Z"))))
        vuelta(ahora(), hora = 24)
        s = sincronizacion(org)
        assertTrue(s["error"]?.isNull ?: true, s.toString())
        assertEquals("c1", s["cursor"].asString())
        assertEquals(1, registros(CAMBIO_CATASTRO, org).size)
    }

    @Test
    fun `a corrida whose ultima_corrida cannot be written is rolled back with it and never runs`() {
        val falso = FALSOS.getValue("atomica")
        val (org, id) = organizacion(falso)
        falso.paginas.set(mapOf("" to pagina("c1", false, cambio("A-1", "2026-10-20T15:00:00Z"))))
        // hora 24: the cambio is read and nothing is decided yet
        vuelta(ahora(), hora = 24)
        assertEquals(1, sincronizacion(org)["pendientes"].asInt())

        // the db refuses any ultima_corrida of this organization's sincronizacion: the pass's write after the corrida
        // fails, as a lost connection or a refused write would
        val t = runBlocking { tablas.de(SINCRONIZACION_CATASTRO, listOf("ultima_corrida")) }.single { it.organizacion == id }
        val restriccion = "sin_ultima_corrida_${id.toString().take(8)}"
        runBlocking {
            db.sql("ALTER TABLE ${t.tabla} ADD CONSTRAINT $restriccion CHECK (${t.columna("ultima_corrida")} IS NULL) NOT VALID").fetch().awaitRowsUpdated()
        }
        try {
            // hora 0 with a pendiente: the pass decides the corrida of the hour
            vuelta(ahora(), hora = 0)

            // no corrida was left, none ran, and the sincronizacion is as it was: the next pass decides again
            assertTrue(corridas(org, ahora().year).isEmpty())
            Thread.sleep(1000)
            assertEquals(0, falso.valuaciones.get())
            val s = sincronizacion(org)
            assertEquals(1, s["pendientes"].asInt())
            assertTrue(s["ultima_corrida"]?.isNull ?: true, s.toString())
        } finally {
            runBlocking { db.sql("ALTER TABLE ${t.tabla} DROP CONSTRAINT $restriccion").fetch().awaitRowsUpdated() }
        }

        // once the write goes through, the same pass launches it and keeps it
        vuelta(ahora(), hora = 0)
        val lanzada = corridas(org, ahora().year).single()
        assertEquals(TERMINADA, terminada(org, lanzada["id"].asString())["estado"].asString())
        val s = sincronizacion(org)
        assertEquals(0, s["pendientes"].asInt())
        assertEquals(lanzada["id"].asString(), s["ultima_corrida"].asString())
    }

    @Test
    fun `a page that does not move the cursor is an error, never a loop`() {
        val falso = FALSOS.getValue("cursor")
        val (org, _) = organizacion(falso)
        // cambios with no cursor to follow them
        falso.paginas.set(mapOf("" to """{"items":[${cambio("K-1", "2026-10-20T15:00:00Z")}],"cursor":null,"hayMas":false}"""))
        vuelta(ahora(), hora = 24)
        var s = sincronizacion(org)
        assertTrue(s["error"].asString().contains("cursor"), s.toString())
        assertTrue(registros(CAMBIO_CATASTRO, org).isEmpty())

        falso.paginas.set(mapOf("" to pagina("c1", false, cambio("K-1", "2026-10-20T15:00:00Z"))))
        vuelta(ahora(), hora = 24)
        assertEquals("c1", sincronizacion(org)["cursor"].asString())

        // the cursor it was asked with, and more to come: the pass ends with its error, the rows are not written again
        for (atascada in listOf(pagina("c1", true, cambio("K-2", "2026-10-21T15:00:00Z")), pagina("c1", true))) {
            falso.paginas.set(mapOf("c1" to atascada))
            runBlocking { withTimeout(60_000) { seguimiento.vuelta(ahora(), 24) } }
            s = sincronizacion(org)
            assertTrue(s["error"].asString().contains("cursor"), s.toString())
            assertEquals("c1", s["cursor"].asString())
            assertEquals(1, registros(CAMBIO_CATASTRO, org).size)
        }
    }

    // #203: GET /{anio}/cambios, task 19
    @Test
    fun `the aviso lists the changed predios with their difference and marks those above 5 UIT`() {
        val falso = FALSOS.getValue("aviso")
        val (org, _) = organizacion(falso)
        val sufijo = uniqueDocumento()
        val anio = ahora().year
        val a = "A-$sufijo"
        val b = "B-$sufijo"
        val contribuyente = contribuyente(org)
        declarar(org, contribuyente, predio(org, a), "100000.00", anio)
        declarar(org, contribuyente, predio(org, b), "100000.00", anio)
        val uit = Parametros.uit(anio)
        // A: catastro pasa de 5 UIT la diferencia por 0.01; B: se queda muy por debajo
        val catastroA = BigDecimal("100000.00").add(uit.multiply(BigDecimal(5))).add(BigDecimal("0.01"))
        falso.items.set(listOf(item(a, catastroA.toPlainString()), item(b, "101000.00")))
        falso.paginas.set(
            mapOf(
                "" to
                    pagina(
                        "c1",
                        false,
                        cambio(a, "2026-10-20T15:00:00Z"),
                        cambio(a, "2026-10-21T15:00:00Z"),
                        cambio(b, "2026-10-20T16:00:00Z")
                    )
            )
        )

        // pendientes > 0: the hour's pass launches CAMBIOS (hora 0: the pass always reaches it)
        vuelta(ahora(), hora = 0)
        val lanzada = corridas(org, anio).single()
        assertEquals("CAMBIOS", lanzada["origen"].asString())
        assertEquals(TERMINADA, terminada(org, lanzada["id"].asString())["estado"].asString())

        val resp = tree(send("GET", "/api/srtm/brecha/$anio/cambios", null, HttpStatus.OK, org))
        assertEquals(2, resp["totalElements"].asInt())
        val content = resp["content"]
        assertEquals(a, content[0]["codigoMunicipal"].asString())
        assertEquals("SUPERA_5_UIT", content[0]["marca"].asString())
        assertEquals(2, content[0]["cambios"].asInt())
        // core's instant, utc and with its seconds, whatever the session's offset
        assertEquals("2026-10-21T15:00:00Z", content[0]["ultimoCambio"].asString())
        assertEquals(b, content[1]["codigoMunicipal"].asString())
        assertTrue(content[1]["marca"]?.isNull ?: true, content[1].toString())

        val marcados = tree(send("GET", "/api/srtm/brecha/$anio/cambios?marca=SUPERA_5_UIT", null, HttpStatus.OK, org))
        assertEquals(1, marcados["totalElements"].asInt())
        assertEquals(a, marcados["content"][0]["codigoMunicipal"].asString())

        // a blank marca is no marca, as a blank desde or objeto
        assertEquals(2, tree(send("GET", "/api/srtm/brecha/$anio/cambios?marca=", null, HttpStatus.OK, org))["totalElements"].asInt())
        send("GET", "/api/srtm/brecha/$anio/cambios?marca=OTRA", null, HttpStatus.UNPROCESSABLE_CONTENT, org)
    }

    // one organization's catastro-backend: a token, its cambios by desde ("" from the start; any other desde answers
    // nothing new with the same cursor back, as catastro does) or `estado` when it is not 200, no valuation (a corrida
    // over an empty padrón ends TERMINADA) and no /titulares (404)
    class CatastroFalso(
        nombre: String
    ) {
        val slug = "seg-$nombre-${UUID.randomUUID().toString().take(8)}"
        val paginas = AtomicReference<Map<String, String>>(emptyMap())

        // #203: the items its /valuaciones answers, for the aviso's test (its corrida needs catastro's autoavalúo)
        val items = AtomicReference<List<String>>(emptyList())
        val estado = AtomicInteger(200)
        val llamadas = AtomicInteger()

        // the /valuaciones it answered: a corrida that ran asked them
        val valuaciones = AtomicInteger()
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
                    valuaciones.incrementAndGet()
                    val anio = parametros(ex)["anio"]
                    val todos = items.get()
                    responder(
                        ex,
                        200,
                        """{"anio":$anio,"version":"valuacion-v1","page":0,"size":500,"total":${todos.size},"items":[${todos.joinToString(",")}]}"""
                    )
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
        private val FALSOS =
            listOf(
                "lee",
                "cambia",
                "semanal",
                "falla",
                "candado",
                "aviso",
                "sincodigo",
                "escritura",
                "cursor",
                "atomica"
            ).associateWith { CatastroFalso(it) }

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
