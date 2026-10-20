package srtm.rentas

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.brecha.SIN_CATASTRO_CONFIGURADO
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

// #149: GET /declaraciones/{id}/autoavaluo-sugerido against a stub of catastro-backend's POST /api/catastro/valuacion
// (catastro-backend#17): what srtm sends of the declaración, and catastro's answer passed through. an organization
// without its catastro is refused, another's declaración is not found
class AutoavaluoSugeridoApiTest : SrtmApiTest() {
    // the organization of the catastro (srtm.catastro.organizacion), with the model and a uso of the catalog
    private fun organizacion(): String =
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

    // a declaración of 2026 with two niveles (one inactive) and an obra, its arancel as an external padrón brings it
    private fun declaracion(org: String): String {
        val codigo = "AS-${uniqueDocumento()}"
        val predio = post("/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"), org)
        val contribuyente = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)["id"].asString()
        val id =
            post(
                "/api/srtm/declaraciones",
                mapOf(
                    "contribuyente" to contribuyente,
                    "predio" to predio["id"].asString(),
                    "anio" to 2026,
                    "secuencia_uso" to "1",
                    "area_terreno" to 200,
                    "clase_uso" to "RESIDENCIAL",
                    "sub_clase_uso" to "UNIFAMILIAR",
                    "uso" to "CASA HABITACIÓN"
                ),
                org
            )["id"].asString()
        post("/api/srtm/declaraciones/$id/niveles", NIVEL, org)
        post("/api/srtm/declaraciones/$id/niveles", NIVEL + mapOf("numero_piso" to 2, "estado" to "INACTIVO"), org)
        post("/api/srtm/declaraciones/$id/obras", OBRA, org)
        val guardada = tree(send("GET", "/api/objects/declaracion_predial/records/$id", null, HttpStatus.OK, org))["attributes"]
        send(
            "PUT",
            "/api/objects/declaracion_predial/records/$id",
            mapOf("attributes" to fields(guardada) + ("arancel" to BigDecimal("45.50"))),
            HttpStatus.OK,
            org
        )
        return id
    }

    private fun sugerido(
        id: String,
        org: String,
        estado: HttpStatus = HttpStatus.OK
    ): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id/autoavaluo-sugerido", null, estado, org))

    @Test
    fun `the declaracion's insumos go to catastro and its valuation comes back`() {
        val org = organizacion()
        RESPUESTA.set(200 to VALUADA)
        val id = declaracion(org)
        val codigo = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK, org))["predio"]["codigo"].asString()

        val v = sugerido(id, org)

        val pedido = json.readTree(PEDIDO.get())
        assertEquals(2026, pedido["anio"].asInt())
        assertEquals(0, BigDecimal("200").compareTo(pedido["areaTerreno"].decimalValue()), pedido.toString())
        assertEquals(0, BigDecimal("45.50").compareTo(pedido["arancelM2"].decimalValue()), pedido.toString())
        assertEquals("010101", pedido["uso"].asString())
        assertEquals(codigo, pedido["codigoMunicipal"].asString())
        assertEquals(false, pedido["esEdificio"].asBoolean())
        // the inactive nivel is not valued
        assertEquals(1, pedido["construcciones"].size(), pedido.toString())
        val c = pedido["construcciones"][0]
        assertEquals("1", c["piso"].asString())
        assertEquals("CONCRETO", c["material"].asString())
        assertEquals("BUENO", c["estado"].asString())
        assertEquals("C", c["muros"].asString())
        assertEquals("D", c["techos"].asString())
        assertEquals("E", c["puertas"].asString())
        val o = pedido["obras"][0]
        assertEquals("MUROS PERIMETRICOS O CERCOS|3", o["codigoVu"].asString())
        assertEquals(0, BigDecimal("30").compareTo(o["cantidad"].decimalValue()), pedido.toString())

        assertEquals(0, BigDecimal("96752.21").compareTo(v["autoavaluo"].decimalValue()), v.toString())
        assertEquals("SELVA", v["region"].asString())
        assertEquals("CUERPO", v["origen_arancel"].asString())
        assertTrue(v["motivo"].isNull, v.toString())
        assertEquals(0, BigDecimal("787.816").compareTo(v["detalle"]["pisos"][0]["valor_unitario_depreciado"].decimalValue()), v.toString())
        assertEquals("MUROS PERIMETRICOS O CERCOS|1", v["detalle"]["obras"][0]["codigo_vu"].asString())
        // nothing is stored
        val guardada = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK, org))["declaracion"]
        assertTrue(guardada["valor_autoavaluo"].isNull, guardada.toString())
    }

    @Test
    fun `not valued, catastro's motivo comes back`() {
        val org = organizacion()
        RESPUESTA.set(200 to SIN_VALOR)

        val v = sugerido(declaracion(org), org)

        assertTrue(v["autoavaluo"].isNull, v.toString())
        assertTrue(v["detalle"].isNull, v.toString())
        assertEquals("PARAMETRO_FALTANTE:PORCENTAJE_ACTUALIZACION:2027", v["llave"].asString())
    }

    @Test
    fun `catastro failing is a 502 that says so`() {
        val org = organizacion()
        RESPUESTA.set(500 to "{}")

        val problema = sugerido(declaracion(org), org, HttpStatus.BAD_GATEWAY)

        assertTrue(problema["detail"].asString().contains("catastro-backend respondió 500"), problema.toString())
    }

    @Test
    fun `an organization without its catastro is refused`() {
        val otra = otraOrganizacion()
        val id =
            post("/api/srtm/declaraciones", mapOf("contribuyente" to inscribir(otra), "predio" to predio(otra), "anio" to 2026, "secuencia_uso" to "1"), otra)

        val problema = sugerido(id["id"].asString(), otra, HttpStatus.CONFLICT)

        assertEquals(SIN_CATASTRO_CONFIGURADO, problema["detail"].asString(), problema.toString())
    }

    @Test
    fun `another organization's declaracion, or none, is not found`() {
        val id = declaracion(organizacion())

        sugerido(id, otraOrganizacion(), HttpStatus.NOT_FOUND)
        sugerido(UUID.randomUUID().toString(), organizacion(), HttpStatus.NOT_FOUND)
    }

    private fun inscribir(org: String) = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)["id"].asString()

    private fun predio(org: String) =
        post(
            "/api/srtm/predios",
            mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"),
            org
        )["id"].asString()

    companion object {
        private val SLUG = "autoavaluo-${UUID.randomUUID().toString().take(8)}"
        private var organizacionDelCatastro: String? = null

        // the body catastro got last, and what it answers
        private val PEDIDO = AtomicReference<String>()
        private val RESPUESTA = AtomicReference(200 to "{}")

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
        val OBRA =
            mapOf(
                "ingreso" to "POR CATEGORIAS",
                "material" to "CONCRETO",
                "tipo_obra" to "MUROS PERIMETRICOS O CERCOS",
                "estado_conservacion" to "BUENO",
                "anio_construccion" to 2016,
                "mes_construccion" to 1,
                "categoria" to "3. MURO DE LADRILLO DE ARCILLA",
                "numero_piso" to 1,
                "cantidad" to 2,
                "metrado" to 15
            )

        // catastro-backend#17's example
        private const val VALUADA =
            """{"anio":2026,"version":"valuacion-v2","region":"SELVA","codigoMunicipal":null,"arancelM2":45.5,
            "origenArancel":"CUERPO","terreno":9100.0,"construccion":78781.6,"obras":8870.61,"autoavaluo":96752.21,
            "motivo":null,"llave":null,"detalle":{"pisos":[{"piso":"1","seccion":null,"areaConstruida":100,
            "valorUnitario":829.28,"incremento":0.0,"tablaDepreciacion":"01","antiguedad":10,"porcentajeDepreciacion":5,
            "valorUnitarioDepreciado":787.816,"valor":78781.6}],"obras":[{"codigoVu":"MUROS PERIMETRICOS O CERCOS|1",
            "cantidad":30,"valorUnitario":457.72,"factorOficializacion":0.68,"tablaDepreciacion":"01","antiguedad":10,
            "porcentajeDepreciacion":5,"valor":8870.61}]}}"""
        private const val SIN_VALOR =
            """{"anio":2026,"version":"valuacion-v2","region":"SELVA","codigoMunicipal":null,"arancelM2":45.5,
            "origenArancel":"CUERPO","terreno":null,"construccion":null,"obras":null,"autoavaluo":null,
            "motivo":"Falta el porcentaje de actualización de 2027","llave":"PARAMETRO_FALTANTE:PORCENTAJE_ACTUALIZACION:2027",
            "detalle":null}"""

        private val CATASTRO =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/auth/token") { ex ->
                    ex.requestBody.readAllBytes()
                    responder(ex, 200, """{"token":"t-autoavaluo","expiresAt":"2999-01-01T00:00:00Z","serviceAccount":"srtm"}""")
                }
                createContext("/api/catastro/valuacion") { ex ->
                    if (ex.requestHeaders.getFirst("Authorization") != "Bearer t-autoavaluo") {
                        responder(ex, 401, "{}")
                        return@createContext
                    }
                    PEDIDO.set(ex.requestBody.readAllBytes().decodeToString())
                    val (estado, cuerpo) = RESPUESTA.get()
                    responder(ex, estado, cuerpo)
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
            registro.add("srtm.catastro.client-id") { "srtm-autoavaluo" }
            registro.add("srtm.catastro.client-secret") { "secreto-de-prueba" }
            registro.add("srtm.catastro.organizacion") { SLUG }
        }

        @JvmStatic
        @AfterAll
        fun parar() = CATASTRO.stop(0)
    }
}
