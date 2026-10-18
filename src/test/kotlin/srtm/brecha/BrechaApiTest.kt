package srtm.brecha

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.impuesto.ImpuestoPredial
import srtm.impuesto.Parametros
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

// the brecha end to end against a stub of catastro-backend (the jdk's http server, on the contract of task 5): predios
// and declaraciones through the portal's api, a corrida started by POST and followed until TERMINADA, and the panel,
// the rows and the lotes it answers. each test runs in an organization of its own, so the corrida reads only its data
class BrechaApiTest : SrtmApiTest() {
    // an organization with the model and the verified predial parameters: its admin's token
    private fun organizacion(): String {
        val suyo = otraOrganizacion()
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
        return suyo
    }

    private fun predio(
        org: String,
        codigo: String,
        cpu: String? = null
    ): String =
        post(
            "/api/srtm/predios",
            mapOf("codigo" to codigo, "codigo_cpu" to cpu, "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to "01"),
            org
        )["id"].asString()

    private fun declarar(
        org: String,
        contribuyente: String,
        predio: String,
        autoavaluo: String,
        anio: Int = 2026
    ) = post(
        "/api/srtm/declaraciones",
        mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to anio, "secuencia_uso" to "1", "valor_autoavaluo" to BigDecimal(autoavaluo)),
        org
    )

    private fun contribuyente(org: String) = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)["id"].asString()

    // POST, then GET until it ends
    private fun corrida(
        org: String,
        anio: Int
    ): JsonNode {
        val lanzada = tree(send("POST", "/api/srtm/brecha/corridas", mapOf("anio" to anio), HttpStatus.ACCEPTED, org))
        assertEquals("PENDIENTE", lanzada["estado"].asString())
        val id = lanzada["id"].asString()
        repeat(300) {
            val c = tree(send("GET", "/api/srtm/brecha/corridas/$id", null, HttpStatus.OK, org))
            if (c["estado"].asString() in setOf(TERMINADA, FALLIDA)) return c
            Thread.sleep(100)
        }
        error("la corrida $id no terminó")
    }

    private fun liquidar(base: String) = ImpuestoPredial.liquidar(2026, BigDecimal(base), Parametros.predial).impuestoAnual!!

    private fun dec(n: JsonNode) = n.decimalValue().setScale(2, RoundingMode.HALF_UP)

    @Test
    fun `a corrida crosses the padron with the catastro and the panel adds it up`() {
        val org = organizacion()
        val sufijo = uniqueDocumento()
        val c1 = contribuyente(org)
        val c2 = contribuyente(org)
        val a = predio(org, "A-$sufijo")
        val b = predio(org, "B-$sufijo")
        val c = predio(org, "C-$sufijo")
        val d = predio(org, "D-$sufijo", cpu = "CPU-$sufijo")
        declarar(org, c1, a, "100000.00")
        declarar(org, c1, b, "80000.00")
        declarar(org, c2, c, "50000.00")
        declarar(org, c2, d, "20000.00")
        ITEMS.set(
            listOf(
                item("A-$sufijo", "150000.00"),
                item("B-$sufijo", "80000.00"),
                // D by its CPU, not valued
                """{"codigoMunicipal":"X-$sufijo","codigoCpu":"CPU-$sufijo","sector":"01","manzana":"A","lote":"4","uso":"010101",
                "areaTerreno":100,"areaConstruida":0,"longitudFrente":null,"terreno":null,"construccion":null,"obras":null,
                "autoavaluo":null,"motivo":"Sin arancel","llave":"ARANCEL_FALTANTE","loteGeom":null}""",
                item("NUEVO-$sufijo", "40000.00", geometria = CUADRADO)
            )
        )

        val corrida = corrida(org, 2026)
        assertEquals(TERMINADA, corrida["estado"].asString(), corrida.toString())
        assertEquals("valuacion-v1", corrida["versionCatastro"].asString())
        assertEquals(5, corrida["totalPredios"].asInt())
        assertEquals(5, corrida["procesados"].asInt())
        // no ordinance in this organization: no Δ arbitrios, and the corrida says why
        assertTrue(corrida["mensaje"].asString().startsWith("Δ arbitrios no calculado"), corrida.toString())
        assertTrue(corrida["deltaArbitrios"].isNull)

        val panel = tree(send("GET", "/api/srtm/brecha/2026/panel", null, HttpStatus.OK, org))
        assertEquals(corrida["id"].asString(), panel["corrida"]["id"].asString())
        val k = panel["kpis"]
        assertEquals(
            listOf(4, 4, 1, 1, 0, 1, 1, 1),
            listOf("prediosCatastro", "prediosPadron", "omisos", "subvaluados", "sobrevaluados", "coinciden", "sinCatastro", "sinValor").map { k[it].asInt() }
        )
        assertEquals(BigDecimal("250000.00"), dec(k["autoavaluoDeclarado"]))
        assertEquals(BigDecimal("270000.00"), dec(k["autoavaluoCatastro"]))
        assertEquals(BigDecimal("90000.00"), dec(k["deltaAutoavaluo"]))
        // c1 goes from 180000 to 230000 of base; the omiso is estimated alone; c2 does not move
        val esperado = liquidar("230000.00") - liquidar("180000.00") + liquidar("40000.00")
        assertEquals(esperado, dec(k["deltaImpuesto"]))
        assertTrue(k["deltaArbitrios"].isNull)
        assertEquals(esperado, dec(corrida["deltaImpuesto"]))
        assertEquals(6, panel["porCategoria"].size())
        assertEquals("01", panel["porSector"][0]["sector"].asString())

        val omisos = tree(send("GET", "/api/srtm/brecha/2026/predios?categoria=OMISO", null, HttpStatus.OK, org))
        assertEquals(1, omisos["totalElements"].asInt())
        val omiso = omisos["content"][0]
        assertEquals("NUEVO-$sufijo", omiso["codigoCatastro"].asString())
        assertEquals(true, omiso["estimado"].asBoolean())
        assertEquals(liquidar("40000.00"), dec(omiso["deltaImpuesto"]))
        val sinValor = tree(send("GET", "/api/srtm/brecha/2026/predios?categoria=SIN_VALOR", null, HttpStatus.OK, org))["content"][0]
        assertEquals("D-$sufijo", sinValor["codigoPredio"].asString())
        assertEquals("Sin arancel", sinValor["motivo"].asString())
        send("GET", "/api/srtm/brecha/2026/predios?categoria=NINGUNA", null, HttpStatus.UNPROCESSABLE_CONTENT, org)

        val lotes = tree(send("GET", "/api/srtm/brecha/2026/lotes", null, HttpStatus.OK, org))
        assertEquals("FeatureCollection", lotes["type"].asString())
        val feature = lotes["features"].single()
        assertEquals("OMISO", feature["properties"]["categoria"].asString())
        assertEquals("NUEVO-$sufijo", feature["properties"]["codigo"].asString())
        assertEquals("Polygon", feature["geometry"]["type"].asString())
        assertEquals(-75.22, feature["geometry"]["coordinates"][0][0][0].asDouble(), 1e-6)

        assertEquals(1, tree(send("GET", "/api/srtm/brecha/corridas?anio=2026", null, HttpStatus.OK, org)).size())
    }

    @Test
    fun `a year without its parameters fails the corrida and names them, and there is no panel`() {
        val org = organizacion()
        ITEMS.set(listOf(item("Z-${uniqueDocumento()}", "1000.00")))
        val corrida = corrida(org, 2099)
        assertEquals(FALLIDA, corrida["estado"].asString())
        assertEquals("Faltan parámetros del impuesto predial: UIT 2099", corrida["mensaje"].asString())
        send("GET", "/api/srtm/brecha/2099/panel", null, HttpStatus.NOT_FOUND, org)
    }

    @Test
    fun `starting needs to write the corrida and its predios, reading needs to read the corrida`() {
        val lector = funcionario(listOf(permiso("predio", "READ")))
        send("POST", "/api/srtm/brecha/corridas", mapOf("anio" to 2026), HttpStatus.FORBIDDEN, lector)
        send("GET", "/api/srtm/brecha/2026/panel", null, HttpStatus.FORBIDDEN, lector)
        send("GET", "/api/srtm/brecha/corridas", null, HttpStatus.FORBIDDEN, lector)
    }

    private fun item(
        codigo: String,
        autoavaluo: String,
        geometria: String = "null"
    ) = """{"codigoMunicipal":"$codigo","codigoCpu":null,"sector":"01","manzana":"A","lote":"1","uso":"010101",
        "areaTerreno":200,"areaConstruida":100,"longitudFrente":10.5,"terreno":1.00,"construccion":1.00,"obras":0.00,
        "autoavaluo":$autoavaluo,"motivo":null,"llave":null,"loteGeom":$geometria}"""

    companion object {
        private val ITEMS = AtomicReference<List<String>>(emptyList())

        private const val CUADRADO =
            """{"type":"Polygon","coordinates":[[[-75.22,-10.95],[-75.2195,-10.95],[-75.2195,-10.9495],[-75.22,-10.9495],[-75.22,-10.95]]]}"""

        // catastro-backend's two endpoints: a token, and the items of ITEMS in one page
        private val CATASTRO: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/auth/token") { ex ->
                    responder(ex, """{"token":"t","expiresAt":"2999-01-01T00:00:00Z","serviceAccount":"srtm"}""")
                }
                createContext("/api/catastro/valuaciones") { ex ->
                    val anio = Regex("anio=(\\d+)").find(ex.requestURI.query)!!.groupValues[1]
                    val items = ITEMS.get()
                    responder(ex, """{"anio":$anio,"version":"valuacion-v1","page":0,"size":500,"total":${items.size},"items":[${items.joinToString(",")}]}""")
                }
                start()
            }

        private fun responder(
            ex: com.sun.net.httpserver.HttpExchange,
            cuerpo: String
        ) {
            val bytes = cuerpo.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun catastro(registro: DynamicPropertyRegistry) {
            registro.add("srtm.catastro.enabled") { true }
            registro.add("srtm.catastro.url") { "http://127.0.0.1:${CATASTRO.address.port}" }
            registro.add("srtm.catastro.client-id") { "srtm" }
            registro.add("srtm.catastro.client-secret") { "secreto-de-prueba" }
        }

        @JvmStatic
        @AfterAll
        fun parar() = CATASTRO.stop(0)
    }
}
