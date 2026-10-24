package srtm.normativa

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Normativa's copy of the aranceles to catastro (#189) over http, against a stub of catastro's core (the jdk's http
// server: its records of arancel in memory, by GET ?clave=, POST, PUT and DELETE, as the service account), never a real
// catastro. the organization of SLUG has it (srtm.catastro.organizaciones); the other normativa tests' organization has
// none. each test takes a year of its own (anio()) and tramos of its own: FICTITIOUS figures
class SincronizacionApiTest : ConNormativaApiTest() {
    // the normativa organization without catastro, and its clerks
    private lateinit var sinCatastro: Triple<String, String, String>

    @BeforeEach
    fun enLaDelCatastro() {
        sinCatastro = Triple(token, ana, beto)
        val (admin, a, b) =
            conCatastro ?: run {
                token = bearer()
                val email = "admin@$SLUG.test"
                val clave = "clave-de-la-organizacion-del-catastro"
                post("/api/organizations", mapOf("name" to SLUG, "slug" to SLUG, "adminEmail" to email, "adminPassword" to clave))
                token = bearer(email, clave)
                aplicarModelo(token)
                Triple(token, funcionario(normativa), funcionario(normativa)).also { conCatastro = it }
            }
        token = admin
        ana = a
        beto = b
        CAIDO.set(false)
    }

    @Test
    fun `a verified arancel is copied to catastro and marked synchronized`() {
        val anio = anio()
        val t = tramo()
        val id = post(ARANCELES, arancel(t, anio), ana)["id"].asString()
        // POR_VERIFICAR: catastro does not see it
        assertNull(CATASTRO["$t|$anio"])

        val r = verificarAranceles(listOf(id))
        assertEquals(listOf(id), r["verificados"].elementos().map { it.asString() })
        assertEquals(1, r["sincronizacion"]["sincronizados"].asInt(), r.toString())
        assertEquals(0, r["sincronizacion"]["errores"].size())
        val copia = CATASTRO.getValue("$t|$anio")
        assertEquals(t, copia["id_aranc"])
        assertEquals(anio, (copia["anio"] as Number).toInt())
        assertEquals(0, BigDecimal(copia["valor_m2"].toString()).compareTo(BigDecimal("12.50")))
        assertEquals(setOf("clave", "id_aranc", "anio", "valor_m2", "cod_via", "cuadra", "lado"), copia.keys)
        val fila = uno(t, anio)
        assertFalse(fila["sincronizado_en"].isNull, fila.toString())
        assertTrue(fila["error_sincronizacion"].isNull, fila.toString())
    }

    @Test
    fun `an annulled arancel leaves catastro`() {
        val anio = anio()
        val t = tramo()
        val id = vigenteArancel(arancel(t, anio))
        assertTrue(CATASTRO.containsKey("$t|$anio"))

        val anulado = tree(send("POST", "$ARANCELES/$id/anular", mapOf("motivo" to "La cifra se copió mal"), HttpStatus.OK, beto))
        assertEquals("ANULADO", anulado["estado"].asString())
        assertEquals(1, anulado["sincronizacion"]["sincronizados"].asInt(), anulado.toString())
        // the arancel answered is read after its copy: flat, as before, with its sincronización
        assertFalse(anulado["sincronizado_en"].isNull, anulado.toString())
        assertEquals(id, anulado["id"].asString())
        assertNull(CATASTRO["$t|$anio"])
    }

    @Test
    fun `catastro down - the verificacion stands with its error, and sincronizar retries and clears it`() {
        val anio = anio()
        val t = tramo()
        val id = post(ARANCELES, arancel(t, anio), ana)["id"].asString()
        CAIDO.set(true)
        val r = verificarAranceles(listOf(id))
        assertEquals(listOf(id), r["verificados"].elementos().map { it.asString() })
        val error = r["sincronizacion"]["errores"].single()
        assertEquals(0, r["sincronizacion"]["sincronizados"].asInt())
        assertEquals(id, error["id"].asString())
        assertEquals("$t|$anio", error["clave"].asString())
        assertTrue(error["error"].asString().contains("503"), error.toString())
        assertFalse(error["error"].asString().contains("secreto"), error.toString())
        val caido = uno(t, anio)
        assertEquals("VIGENTE", caido["estado"].asString())
        assertTrue(caido["sincronizado_en"].isNull, caido.toString())
        assertEquals(error["error"].asString(), caido["error_sincronizacion"].asString())
        assertNull(CATASTRO["$t|$anio"])

        CAIDO.set(false)
        val otra = tree(send("POST", "$ARANCELES/sincronizar?anio=$anio", null, HttpStatus.OK, beto))
        assertEquals(1, otra["sincronizados"].asInt(), otra.toString())
        assertEquals(0, otra["errores"].size())
        val sincronizado = uno(t, anio)
        assertFalse(sincronizado["sincronizado_en"].isNull, sincronizado.toString())
        assertTrue(sincronizado["error_sincronizacion"].isNull, sincronizado.toString())
        assertTrue(CATASTRO.containsKey("$t|$anio"))
        // nothing pending any more
        assertEquals(0, tree(send("POST", "$ARANCELES/sincronizar?anio=$anio", null, HttpStatus.OK, beto))["sincronizados"].asInt())
        // an anio that is no year is a 422, as the page's
        send("POST", "$ARANCELES/sincronizar?anio=1800", null, HttpStatus.UNPROCESSABLE_CONTENT, beto)
    }

    @Test
    fun `an importacion without dry_run copies the rows it leaves VIGENTE`() {
        val anio = anio()
        val (a, b, c) = Triple(tramo(), tramo(), tramo())
        val csv =
            """
            id_aranc,anio,valor_m2,cod_via,cuadra,lado,norma,fuente,transcribio,verifico
            $a,$anio,10.00,V-1,1,I,Ordenanza ficticia,SincronizacionApiTest,ANA,BETO
            $b,$anio,11.00,V-1,2,I,Ordenanza ficticia,SincronizacionApiTest,ANA,BETO
            $c,$anio,12.00,V-1,3,I,Ordenanza ficticia,SincronizacionApiTest,,
            """.trimIndent()
        val (seco, ensayo) = importar(csv, true, base = ARANCELES)
        assertEquals(HttpStatus.OK, seco)
        assertTrue(ensayo!!["sincronizacion"].isNull, ensayo.toString())

        val (estado, r) = importar(csv, false, base = ARANCELES)
        assertEquals(HttpStatus.OK, estado, r.toString())
        assertEquals(3, r!!["crear"].size())
        assertEquals(2, r["sincronizacion"]["sincronizados"].asInt(), r.toString())
        assertTrue(CATASTRO.containsKey("$a|$anio") && CATASTRO.containsKey("$b|$anio"))
        assertNull(CATASTRO["$c|$anio"])
        assertTrue(uno(c, anio)["sincronizado_en"].isNull)
    }

    @Test
    fun `an organization without catastro copies nothing and fails nothing`() {
        val (admin, a, b) = sinCatastro
        token = admin
        ana = a
        beto = b
        val anio = anio()
        val t = tramo()
        val id = post(ARANCELES, arancel(t, anio), ana)["id"].asString()
        val r = verificarAranceles(listOf(id))
        assertEquals(listOf(id), r["verificados"].elementos().map { it.asString() })
        assertTrue(r.has("sincronizacion") && r["sincronizacion"].isNull, r.toString())
        assertTrue(uno(t, anio)["sincronizado_en"].isNull)
        val anulado = tree(send("POST", "$ARANCELES/$id/anular", mapOf("motivo" to "prueba"), HttpStatus.OK, beto))
        assertTrue(anulado.has("sincronizacion") && anulado["sincronizacion"].isNull, anulado.toString())
        val s = tree(send("POST", "$ARANCELES/sincronizar?anio=$anio", null, HttpStatus.OK, beto))
        assertEquals(0, s["sincronizados"].asInt())
        assertEquals(0, s["errores"].size())
        assertNull(CATASTRO["$t|$anio"])
    }

    @Test
    fun `sincronizar asks UPDATE on arancel`() {
        val lector = funcionario(listOf(permiso(ARANCEL, "READ")))
        val p = tree(send("POST", "$ARANCELES/sincronizar", null, HttpStatus.FORBIDDEN, lector))
        assertTrue(p.toString().contains("arancel"), p.toString())
        send("POST", "$ARANCELES/sincronizar", null, HttpStatus.OK, beto)
    }

    private fun tramo(): String = "S${uniqueDocumento()}"

    private fun arancel(
        idAranc: String,
        anio: Int,
        valor: String = "12.50"
    ) = mapOf(
        "id_aranc" to idAranc,
        "anio" to anio,
        "valor_m2" to valor,
        "cod_via" to "V-001",
        "cuadra" to "1",
        "lado" to "I",
        "norma" to "Ordenanza ficticia",
        "fuente" to "SincronizacionApiTest"
    )

    private fun verificarAranceles(
        ids: List<String>,
        quien: String = beto
    ): JsonNode = tree(send("POST", "$ARANCELES/verificar", mapOf("ids" to ids), HttpStatus.OK, quien))

    private fun vigenteArancel(cuerpo: Map<String, Any?>): String {
        val id = post(ARANCELES, cuerpo, ana)["id"].asString()
        check(verificarAranceles(listOf(id))["verificados"].size() == 1)
        return id
    }

    private fun uno(
        t: String,
        anio: Int
    ): JsonNode = tree(send("GET", "$ARANCELES?q=$t&anio=$anio", null, HttpStatus.OK, ana))["content"].single()

    private companion object {
        const val ARANCELES = "/api/srtm/normativa/aranceles"

        private val SLUG = "normativa-catastro-${UUID.randomUUID().toString().take(8)}"
        private var conCatastro: Triple<String, String, String>? = null

        // catastro's records of arancel, by their clave; while CAIDO its core answers 503
        private val CATASTRO = ConcurrentHashMap<String, Map<String, Any?>>()
        private val IDS = ConcurrentHashMap<String, String>()
        private val CAIDO = AtomicBoolean(false)
        private val TOKENS = AtomicInteger()
        private val JSON = JsonMapper.builder().build()

        private val STUB =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/auth/token") { ex ->
                    ex.requestBody.readAllBytes()
                    responder(ex, 200, """{"token":"t${TOKENS.incrementAndGet()}","expiresAt":"2999-01-01T00:00:00Z","serviceAccount":"srtm"}""")
                }
                createContext("/api/objects/arancel/records") { ex -> registros(ex) }
                start()
            }

        @Suppress("UNCHECKED_CAST")
        private fun registros(ex: HttpExchange) {
            val cuerpo = ex.requestBody.readAllBytes().decodeToString()
            if (CAIDO.get()) return responder(ex, 503, "{}")
            if (ex.requestHeaders.getFirst("Authorization")?.startsWith("Bearer t") != true) return responder(ex, 401, "{}")
            val id =
                ex.requestURI.path
                    .removePrefix("/api/objects/arancel/records")
                    .removePrefix("/")

            fun atributos() = (JSON.readValue(cuerpo, Map::class.java) as Map<String, Any?>)["attributes"] as Map<String, Any?>
            when (ex.requestMethod) {
                "GET" -> {
                    val clave =
                        URLDecoder.decode(
                            ex.requestURI.rawQuery
                                .split('&')
                                .first { it.startsWith("clave=") }
                                .removePrefix("clave="),
                            Charsets.UTF_8
                        )
                    val suyo = CATASTRO[clave]?.let { listOf(mapOf("id" to IDS.getValue(clave), "attributes" to it)) }.orEmpty()
                    responder(ex, 200, JSON.writeValueAsString(mapOf("content" to suyo)))
                }
                "POST" -> {
                    val a = atributos()
                    val clave = a["clave"].toString()
                    if (CATASTRO.putIfAbsent(clave, a) != null) return responder(ex, 409, "{}")
                    IDS[clave] = UUID.randomUUID().toString()
                    responder(ex, 201, """{"id":"${IDS[clave]}"}""")
                }
                "PUT" -> {
                    val a = atributos()
                    CATASTRO[a["clave"].toString()] = a
                    responder(ex, 200, """{"id":"$id"}""")
                }
                "DELETE" -> {
                    val clave = IDS.entries.firstOrNull { it.value == id }?.key ?: return responder(ex, 404, "{}")
                    CATASTRO.remove(clave)
                    IDS.remove(clave)
                    ex.sendResponseHeaders(204, -1)
                    ex.close()
                }
                else -> responder(ex, 405, "{}")
            }
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
        fun catastro(registro: DynamicPropertyRegistry) {
            registro.add("srtm.catastro.enabled") { true }
            registro.add("srtm.catastro.organizaciones.normativa.organizacion") { SLUG }
            registro.add("srtm.catastro.organizaciones.normativa.url") { "http://127.0.0.1:${STUB.address.port}" }
            registro.add("srtm.catastro.organizaciones.normativa.client-id") { "srtm-normativa" }
            registro.add("srtm.catastro.organizaciones.normativa.client-secret") { "secreto-de-prueba" }
        }

        @JvmStatic
        @AfterAll
        fun parar() = STUB.stop(0)
    }
}
