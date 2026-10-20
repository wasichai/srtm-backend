package srtm.rentas

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// #156: every write of a declaración or of its niveles, obras and otros frentes recomputes what it derives of them
// (areas, longitud de frente) and its autoavalúo, valued by a stub of catastro-backend that answers 10 per m² of
// terreno and 100 per m² of each construcción; then its condominio's values. the suite's own organization has no
// catastro: there the autoavalúo is the user's
class DerivadosApiTest : SrtmApiTest() {
    // the organization of the catastro (srtm.catastro.organizacion), with the model and a uso of the catalog
    private fun conCatastro(): String =
        organizacionDelCatastro ?: run {
            val email = "admin@$SLUG.test"
            val clave = "clave-de-la-organizacion-del-catastro"
            post("/api/organizations", mapOf("name" to SLUG, "slug" to SLUG, "adminEmail" to email, "adminPassword" to clave))
            val suyo = bearer(email, clave)
            aplicarModelo(suyo)
            val uso = mapOf("codigo" to "010101", "clase" to "RESIDENCIAL", "sub_clase" to "UNIFAMILIAR", "uso" to "CASA HABITACIÓN")
            post("/api/objects/uso_predio/records", mapOf("attributes" to uso), suyo)
            suyo.also { organizacionDelCatastro = it }
        }

    @BeforeEach
    fun valuada() {
        MODO.set(Modo.VALUA)
    }

    private fun contribuyente(org: String) = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)["id"].asString()

    private fun predioDe(org: String) =
        post(
            "/api/srtm/predios",
            mapOf("codigo" to "DV-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"),
            org
        )["id"].asString()

    // a declaración of 2026 on 200 m² of terreno, casa habitación
    private fun declarar(
        org: String,
        extra: Map<String, Any?> = emptyMap(),
        contribuyente: String = contribuyente(org),
        predio: String = predioDe(org)
    ): String =
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "area_terreno" to 200,
                "clase_uso" to "RESIDENCIAL",
                "sub_clase_uso" to "UNIFAMILIAR",
                "uso" to "CASA HABITACIÓN"
            ) + extra,
            org
        )["id"].asString()

    private fun dj(
        id: String,
        org: String
    ): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK, org))

    private fun declaracion(
        id: String,
        org: String
    ): JsonNode = dj(id, org)["declaracion"]

    private fun piso(
        id: String,
        org: String,
        numero: Int,
        area: Int,
        comun: Int? = null
    ): String =
        post(
            "/api/srtm/declaraciones/$id/niveles",
            NIVEL + mapOf("numero_piso" to numero, "area_construida" to area) + (comun?.let { mapOf("area_comun" to it) } ?: emptyMap()),
            org
        )["id"].asString()

    private fun dec(node: JsonNode?) = node?.takeUnless { it.isNull }?.decimalValue()

    private fun assertDec(
        esperado: String?,
        node: JsonNode?
    ) {
        if (esperado == null) {
            assertTrue(node == null || node.isNull, node.toString())
        } else {
            assertEquals(0, BigDecimal(esperado).compareTo(dec(node)), "$esperado != $node")
        }
    }

    @Test
    fun `a piso added raises the area, the autoavaluo and its condominio's values, and one removed lowers them`() {
        val org = conCatastro()
        val c = contribuyente(org)
        val id = declarar(org, contribuyente = c)
        // a declaración without niveles: no area, its terreno valued
        declaracion(id, org).let {
            assertDec("0", it["area_construida"])
            assertDec("2000", it["valor_autoavaluo"])
            assertDec("2000", it["valor_afecto"])
        }

        piso(id, org, 1, 100, comun = 10)
        val segundo = piso(id, org, 2, 50)

        declaracion(id, org).let {
            assertDec("150", it["area_construida"])
            assertDec("10", it["area_comun_construida"])
            assertDec("17000", it["valor_autoavaluo"])
            assertDec("17000", it["valor_condominio"])
            assertDec("17000", it["valor_afecto"])
            assertFalse(it["autoavaluo_por_revisar"]?.asBoolean() ?: false, it.toString())
        }
        assertDec("17000", tree(send("GET", "/api/srtm/contribuyentes/$c/liquidacion?anio=2026", null, HttpStatus.OK, org))["base"])

        send("DELETE", "/api/srtm/niveles/$segundo", null, HttpStatus.NO_CONTENT, org)

        declaracion(id, org).let {
            assertDec("100", it["area_construida"])
            assertDec("12000", it["valor_autoavaluo"])
            assertDec("12000", it["valor_afecto"])
        }
        assertDec("12000", tree(send("GET", "/api/srtm/contribuyentes/$c/liquidacion?anio=2026", null, HttpStatus.OK, org))["base"])
    }

    @Test
    fun `a nivel changed or made inactive counts as it is now`() {
        val org = conCatastro()
        val id = declarar(org)
        val nivel = piso(id, org, 1, 100)

        put("/api/srtm/niveles/$nivel", NIVEL + mapOf("area_construida" to 80), org)
        assertDec("80", declaracion(id, org)["area_construida"])
        assertDec("10000", declaracion(id, org)["valor_autoavaluo"])

        put("/api/srtm/niveles/$nivel", NIVEL + mapOf("area_construida" to 80, "estado" to "INACTIVO"), org)
        assertDec("0", declaracion(id, org)["area_construida"])
        assertDec("2000", declaracion(id, org)["valor_autoavaluo"])
    }

    @Test
    fun `the longitud de frente is the frontis principal and the otros frentes`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("frontis_principal" to 10))
        assertDec("10", declaracion(id, org)["longitud_frente"])

        val frente = post("/api/srtm/declaraciones/$id/frentes", FRENTE + mapOf("frontis" to 4.5), org)["id"].asString()
        assertDec("14.5", declaracion(id, org)["longitud_frente"])

        put("/api/srtm/frentes/$frente", FRENTE + mapOf("frontis" to 6), org)
        assertDec("16", declaracion(id, org)["longitud_frente"])

        // the frontis principal changes in the declaración; what a client sends of the derived ones is not taken
        val d = declaracion(id, org)
        put(
            "/api/srtm/declaraciones/$id",
            fields(d) + mapOf("frontis_principal" to 12, "longitud_frente" to 999, "area_construida" to 999, "area_comun_construida" to 999),
            org
        )
        declaracion(id, org).let {
            assertDec("12", it["frontis_principal"])
            assertDec("18", it["longitud_frente"])
            assertDec("0", it["area_construida"])
            assertDec("0", it["area_comun_construida"])
        }

        send("DELETE", "/api/srtm/frentes/$frente", null, HttpStatus.NO_CONTENT, org)
        assertDec("12", declaracion(id, org)["longitud_frente"])
    }

    @Test
    fun `a client that still sends the longitud de frente alone declares its frontis principal`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("longitud_frente" to 8.5))
        declaracion(id, org).let {
            assertDec("8.5", it["frontis_principal"])
            assertDec("8.5", it["longitud_frente"])
        }
    }

    @Test
    fun `with catastro the autoavaluo is catastro's, whatever the client sends`() {
        val org = conCatastro()
        val id = declarar(org, mapOf("valor_autoavaluo" to 123))
        assertEquals(false, dj(id, org)["autoavaluo_manual"].asBoolean())
        assertDec("2000", declaracion(id, org)["valor_autoavaluo"])

        put("/api/srtm/declaraciones/$id", fields(declaracion(id, org)) + ("valor_autoavaluo" to 1), org)
        assertDec("2000", declaracion(id, org)["valor_autoavaluo"])

        // an engine input of the declaración: its terreno
        put("/api/srtm/declaraciones/$id", fields(declaracion(id, org)) + ("area_terreno" to 300), org)
        assertDec("3000", declaracion(id, org)["valor_autoavaluo"])
    }

    @Test
    fun `not valued, the autoavaluo stays and is marked with catastro's motivo until it is valued again`() {
        val org = conCatastro()
        val id = declarar(org)
        piso(id, org, 1, 100)
        MODO.set(Modo.MOTIVO)

        piso(id, org, 2, 50)

        declaracion(id, org).let {
            assertDec("150", it["area_construida"])
            assertDec("12000", it["valor_autoavaluo"])
            assertEquals(true, it["autoavaluo_por_revisar"].asBoolean(), it.toString())
            assertEquals("Falta el porcentaje de actualización de 2027", it["autoavaluo_motivo"].asString())
        }

        MODO.set(Modo.VALUA)
        // the button «Recalcular»: the same, without a change
        send("POST", "/api/srtm/declaraciones/$id/recalcular", null, HttpStatus.OK, org)

        declaracion(id, org).let {
            assertDec("17000", it["valor_autoavaluo"])
            assertFalse(it["autoavaluo_por_revisar"]?.asBoolean() ?: false, it.toString())
            assertTrue(it["autoavaluo_motivo"] == null || it["autoavaluo_motivo"].isNull, it.toString())
        }
    }

    @Test
    fun `catastro failing marks the autoavaluo as not answered and the write goes through`() {
        val org = conCatastro()
        val id = declarar(org)
        MODO.set(Modo.FALLA)

        piso(id, org, 1, 100)

        declaracion(id, org).let {
            assertDec("100", it["area_construida"])
            assertDec("2000", it["valor_autoavaluo"])
            assertEquals(true, it["autoavaluo_por_revisar"].asBoolean(), it.toString())
            assertTrue(it["autoavaluo_motivo"].asString().startsWith("El catastro no respondió: "), it.toString())
        }
    }

    @Test
    fun `without catastro the autoavaluo is the user's, and the areas still derive`() {
        // the suite's own organization
        val id = declarar(token, mapOf("valor_autoavaluo" to 50000, "area_construida" to 999))
        assertEquals(true, dj(id, token)["autoavaluo_manual"].asBoolean())
        declaracion(id, token).let {
            assertDec("50000", it["valor_autoavaluo"])
            assertDec("0", it["area_construida"])
        }

        piso(id, token, 1, 100)
        put("/api/srtm/declaraciones/$id", fields(declaracion(id, token)) + ("valor_autoavaluo" to 60000))

        declaracion(id, token).let {
            assertDec("100", it["area_construida"])
            assertDec("60000", it["valor_autoavaluo"])
            assertDec("60000", it["valor_afecto"])
            assertFalse(it["autoavaluo_por_revisar"]?.asBoolean() ?: false, it.toString())
        }
    }

    @Test
    fun `a condomino's values follow its own percent of the recomputed autoavaluo`() {
        val org = conCatastro()
        val id = declarar(org)
        piso(id, org, 1, 100)
        val otra = post("/api/srtm/declaraciones/$id/condominos", mapOf("contribuyente" to contribuyente(org), "porcentaje_condominio" to 40), org)
        val otraId = otra["id"].asString()
        declaracion(otraId, org).let {
            assertDec("100", it["area_construida"])
            assertDec("12000", it["valor_autoavaluo"])
            assertDec("4800", it["valor_condominio"])
        }

        piso(id, org, 2, 50)

        // the edited one: its 60 % of the new autoavalúo. the copy follows with #157
        declaracion(id, org).let {
            assertDec("17000", it["valor_autoavaluo"])
            assertDec("60", it["porcentaje_condominio"])
            assertDec("10200", it["valor_condominio"])
        }
        assertDec("40", declaracion(otraId, org)["porcentaje_condominio"])
    }

    @Test
    fun `a rectificatoria is valued once, its rows keep their fila`() {
        val org = conCatastro()
        val id = declarar(org)
        val nivel = piso(id, org, 1, 100)
        piso(id, org, 2, 50)
        post("/api/srtm/declaraciones/$id/obras", OBRA, org)
        post("/api/srtm/declaraciones/$id/frentes", FRENTE, org)
        val fila = tree(send("GET", "/api/srtm/declaraciones/$id/niveles", null, HttpStatus.OK, org)).first { it["id"].asString() == nivel }["fila"]
        assertNotNull(fila?.takeUnless { it.isNull }, "a nivel added has its fila")
        PEDIDOS.set(0)

        val r = tree(send("POST", "/api/srtm/declaraciones/$id/rectificar", null, HttpStatus.CREATED, org))["declaracion"]

        assertEquals(1, PEDIDOS.get())
        val rid = r["id"].asString()
        declaracion(rid, org).let {
            assertDec("150", it["area_construida"])
            assertDec("17000", it["valor_autoavaluo"])
            assertDec("4", it["longitud_frente"])
        }
        val niveles = tree(send("GET", "/api/srtm/declaraciones/$rid/niveles", null, HttpStatus.OK, org))
        val filas =
            niveles
                .iterator()
                .asSequence()
                .map { it["fila"].asString() }
                .toList()
        assertTrue(fila!!.asString() in filas, filas.toString())
    }

    @Test
    fun `a fila is the backend's`() {
        val org = conCatastro()
        val id = declarar(org)
        val propia = UUID.randomUUID().toString()
        val nivel = post("/api/srtm/declaraciones/$id/niveles", NIVEL + ("fila" to propia), org)
        val fila = nivel["fila"].asString()
        assertTrue(fila != propia, "a client's fila is not taken")

        val cambiado = put("/api/srtm/niveles/${nivel["id"].asString()}", NIVEL + mapOf("fila" to propia, "area_construida" to 90), org)
        assertEquals(fila, cambiado["fila"].asString())
    }

    companion object {
        private val SLUG = "derivados-${UUID.randomUUID().toString().take(8)}"
        private var organizacionDelCatastro: String? = null

        private enum class Modo { VALUA, MOTIVO, FALLA }

        private val MODO = AtomicReference(Modo.VALUA)

        // how many valuaciones catastro was asked for
        private val PEDIDOS = AtomicInteger()
        private val JSON = JsonMapper.builder().build()

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
        val OBRA = AutoavaluoSugeridoApiTest.OBRA
        val FRENTE = mapOf("tipo_via" to "JIRON", "via" to "AREQUIPA", "numero" to "45", "frontis" to 4)

        private const val SIN_VALOR =
            """{"anio":2026,"version":"valuacion-v2","region":"SELVA","autoavaluo":null,
            "motivo":"Falta el porcentaje de actualización de 2027","llave":"PARAMETRO_FALTANTE:PORCENTAJE_ACTUALIZACION:2027",
            "detalle":null}"""

        // 10 per m² of terreno, 100 per m² of each construcción
        private fun valuar(pedido: String): String {
            val p = JSON.readTree(pedido)
            val terreno = p["areaTerreno"]?.takeUnless { it.isNull }?.decimalValue() ?: BigDecimal.ZERO
            val construido = p["construcciones"].sumOf { it["areaConstruida"]?.takeUnless { a -> a.isNull }?.decimalValue() ?: BigDecimal.ZERO }
            val autoavaluo = terreno * BigDecimal(10) + construido * BigDecimal(100)
            return """{"anio":2026,"version":"valuacion-v2","region":"SELVA","autoavaluo":$autoavaluo,"motivo":null,"llave":null,"detalle":null}"""
        }

        private val CATASTRO =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/auth/token") { ex ->
                    ex.requestBody.readAllBytes()
                    responder(ex, 200, """{"token":"t-derivados","expiresAt":"2999-01-01T00:00:00Z","serviceAccount":"srtm"}""")
                }
                createContext("/api/catastro/valuacion") { ex ->
                    val pedido = ex.requestBody.readAllBytes().decodeToString()
                    PEDIDOS.incrementAndGet()
                    when (MODO.get()) {
                        Modo.VALUA -> responder(ex, 200, valuar(pedido))
                        Modo.MOTIVO -> responder(ex, 200, SIN_VALOR)
                        Modo.FALLA -> responder(ex, 500, "{}")
                    }
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
        fun catastro(registro: DynamicPropertyRegistry) {
            registro.add("srtm.catastro.enabled") { true }
            registro.add("srtm.catastro.url") { "http://127.0.0.1:${CATASTRO.address.port}" }
            registro.add("srtm.catastro.client-id") { "srtm-derivados" }
            registro.add("srtm.catastro.client-secret") { "secreto-de-prueba" }
            registro.add("srtm.catastro.organizacion") { SLUG }
        }

        @JvmStatic
        @AfterAll
        fun parar() = CATASTRO.stop(0)
    }
}
