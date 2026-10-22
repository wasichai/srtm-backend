package srtm.brecha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// ClienteCatastro against a stub of catastro-backend (the jdk's http server): the token once while it lasts, every
// page of the year, the item as the contract (task 5) prints it, and a 401 that renews the token
class ClienteCatastroTest {
    private val pedidos = CopyOnWriteArrayList<String>()
    private var tokens = 0
    private var expira: Instant = Instant.now().plusSeconds(3600)
    private var rechazarUnaVez = false
    private var cuerpoDelToken: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/api/auth/token") { ex ->
            val body = ex.requestBody.readAllBytes().decodeToString()
            pedidos += "token $body"
            tokens++
            cuerpoDelToken?.let { return@createContext responder(ex, 200, it) }
            responder(ex, 200, """{"token":"t$tokens","expiresAt":"$expira","serviceAccount":"srtm"}""")
        }
        server.createContext("/api/catastro/valuaciones") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            pedidos += "GET ${ex.requestURI.query} $auth"
            if (rechazarUnaVez) {
                rechazarUnaVez = false
                responder(ex, 401, "{}")
                return@createContext
            }
            val page = Regex("page=(\\d+)").find(ex.requestURI.query)!!.groupValues[1].toInt()
            // 3 items in pages of 2 (the stub ignores size=500 to make paging visible)
            val items = listOf(ITEM_VALORADO, ITEM_SIN_VALOR, ITEM_GRANDE).drop(page * 2).take(2)
            responder(ex, 200, """{"anio":2026,"version":"valuacion-v1","page":$page,"size":2,"total":3,"items":[${items.joinToString(",")}]}""")
        }
        server.createContext("/api/catastro/valuacion") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            pedidos += "${ex.requestMethod} valuacion ${ex.requestBody.readAllBytes().decodeToString()} $auth"
            if (rechazarUnaVez) {
                rechazarUnaVez = false
                responder(ex, 401, "{}")
                return@createContext
            }
            responder(ex, 200, VALUADA)
        }
        server.start()
    }

    @AfterEach
    fun parar() = server.stop(0)

    private fun cliente(ahora: () -> Instant = Instant::now) =
        ClienteCatastro(CuentaCatastro(url = "http://127.0.0.1:${server.address.port}/", clientId = "cid", clientSecret = "secreto"), ahora)

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

    @Test
    fun `every page of the year with one token, the items as the contract prints them`() {
        val v = runBlocking { cliente().valuaciones(2026) }
        assertEquals("valuacion-v1", v.version)
        assertEquals(listOf("PM-1", "PM-2", "PM-3"), v.items.map { it.codigoMunicipal })
        val a = v.items[0]
        assertEquals("L01", a.lote)
        assertEquals("010101", a.uso)
        assertEquals(BigDecimal("96752.21"), a.autoavaluo)
        assertEquals(BigDecimal("10.5"), a.longitudFrente)
        assertEquals("Polygon", a.loteGeom!!["type"])
        val b = v.items[1]
        assertNull(b.autoavaluo)
        assertNull(b.loteGeom)
        assertEquals("VU_SIN_VALOR:TECHOS:J", b.llave)
        assertEquals(1, tokens)
        assertEquals("""token {"clientId":"cid","clientSecret":"secreto"}""", pedidos.first())
        assertEquals(listOf("anio=2026&page=0&size=500 Bearer t1", "anio=2026&page=1&size=500 Bearer t1"), pedidos.drop(1).map { it.removePrefix("GET ") })
    }

    @Test
    fun `the figures are read as decimals, never through a double, the geometry as doubles`() {
        val v = runBlocking { cliente().valuaciones(2026) }
        // a double keeps ~16 digits: this one would come back 12345678901234568
        assertEquals(BigDecimal("12345678901234567.89"), v.items[2].autoavaluo)
        assertEquals(BigDecimal("0.10"), v.items[2].obras)
        val anillo = (v.items[0].loteGeom!!["coordinates"] as List<*>).first() as List<*>
        assertEquals(listOf(-75.22, -10.95), anillo.first())
    }

    @Test
    fun `a token is asked again a minute before it expires, and after a 401`() {
        expira = Instant.now().plusSeconds(30)
        val c = cliente()
        runBlocking { c.valuaciones(2026) }
        // it lasts less than a minute: every request asks a new one
        assertEquals(2, tokens)

        expira = Instant.now().plusSeconds(3600)
        val otro = cliente()
        runBlocking { otro.valuaciones(2026) }
        rechazarUnaVez = true
        runBlocking { otro.valuaciones(2026) }
        assertEquals(4, tokens)
    }

    @Test
    fun `a token that is not json fails with a CatastroException`() {
        cuerpoDelToken = "<html>no es json</html>"
        val e = assertThrows(CatastroException::class.java) { runBlocking { cliente().valuaciones(2026) } }
        assertTrue(e.message!!.contains("catastro-backend"))
    }

    @Test
    fun `an error of catastro fails with its status, never the secret`() {
        server.removeContext("/api/catastro/valuaciones")
        server.createContext("/api/catastro/valuaciones") { ex -> responder(ex, 403, "{}") }
        val e = assertThrows(CatastroException::class.java) { runBlocking { cliente().valuaciones(2026) } }
        assertTrue(e.message!!.contains("403"), e.message)
        assertTrue(!e.message!!.contains("secreto"))
    }

    private val insumos =
        InsumosValuacion(
            anio = 2026,
            areaTerreno = BigDecimal("200"),
            arancelM2 = BigDecimal("45.50"),
            uso = "010101",
            esEdificio = false,
            construcciones =
                listOf(
                    ConstruccionValuacion("1", BigDecimal("100"), 2016, "CONCRETO", "BUENO", "C", "D", "E")
                ),
            obras = listOf(ObraValuacion("MUROS PERIMETRICOS O CERCOS|1", BigDecimal("30"), 2016, "CONCRETO", "BUENO")),
            codigoMunicipal = "01-01-0006"
        )

    @Test
    fun `insumos are posted as catastro's body, numbers as numbers, and its answer read as decimals`() {
        val v = runBlocking { cliente().valuar(insumos) }

        val pedido = pedidos.last()
        assertTrue(pedido.startsWith("POST valuacion "), pedido)
        assertTrue(pedido.endsWith(" Bearer t1"), pedido)
        val cuerpo = JsonMapper.builder().build().readTree(pedido.removePrefix("POST valuacion ").removeSuffix(" Bearer t1"))
        assertEquals(
            setOf("anio", "areaTerreno", "arancelM2", "uso", "esEdificio", "construcciones", "obras", "codigoMunicipal"),
            cuerpo.propertyNames().toSet()
        )
        assertTrue(cuerpo["arancelM2"].isNumber, cuerpo.toString())
        assertEquals(45.5, cuerpo["arancelM2"].asDouble())
        assertEquals(false, cuerpo["esEdificio"].asBoolean())
        assertEquals(
            setOf(
                "piso",
                "areaConstruida",
                "anioConstruccion",
                "material",
                "estado",
                "muros",
                "techos",
                "puertas",
                "techoIncluido",
                "clasificacion",
                "seccion"
            ),
            cuerpo["construcciones"][0].propertyNames().toSet()
        )
        assertEquals("C", cuerpo["construcciones"][0]["muros"].asString())
        assertEquals(setOf("codigoVu", "cantidad", "anioConstruccion", "material", "estado"), cuerpo["obras"][0].propertyNames().toSet())

        assertEquals("SELVA", v.region)
        assertEquals("CUERPO", v.origenArancel)
        assertEquals(BigDecimal("96752.21"), v.autoavaluo)
        assertEquals(BigDecimal("9100.00"), v.terreno)
        val piso = v.detalle!!.pisos.single()
        assertEquals("1", piso.piso)
        assertNull(piso.seccion)
        assertEquals(BigDecimal("787.816"), piso.valorUnitarioDepreciado)
        assertEquals(10, piso.antiguedad)
        assertEquals("01", piso.tablaDepreciacion)
        val obra = v.detalle!!.obras.single()
        assertEquals(BigDecimal("0.68"), obra.factorOficializacion)
        assertEquals(BigDecimal("8870.61"), obra.valor)
    }

    @Test
    fun `not valued, the motivo and its llave and no detalle`() {
        server.removeContext("/api/catastro/valuacion")
        server.createContext("/api/catastro/valuacion") { ex -> responder(ex, 200, SIN_VALOR) }

        val v = runBlocking { cliente().valuar(insumos) }

        assertNull(v.autoavaluo)
        assertNull(v.detalle)
        assertNull(v.origenArancel)
        assertEquals("ARANCEL_FALTANTE", v.llave)
        assertEquals("Sin arancel", v.motivo)
    }

    @Test
    fun `a 401 to the valuacion renews the token once`() {
        val c = cliente()
        runBlocking { c.valuar(insumos) }
        rechazarUnaVez = true
        val v = runBlocking { c.valuar(insumos) }
        assertEquals(BigDecimal("96752.21"), v.autoavaluo)
        assertEquals(2, tokens)
    }

    @Test
    fun `a refused valuacion fails with catastro's status`() {
        server.removeContext("/api/catastro/valuacion")
        server.createContext("/api/catastro/valuacion") { ex -> responder(ex, 422, "{}") }
        val e = assertThrows(CatastroException::class.java) { runBlocking { cliente().valuar(insumos) } }
        assertTrue(e.message!!.contains("422"), e.message)
    }

    private companion object {
        // catastro-backend#17's example
        val VALUADA =
            """{"anio":2026,"version":"valuacion-v2","region":"SELVA","codigoMunicipal":"01-01-0006","arancelM2":45.5,
            "origenArancel":"CUERPO","terreno":9100.00,"construccion":78781.6,"obras":8870.61,"autoavaluo":96752.21,
            "motivo":null,"llave":null,"detalle":{"pisos":[{"piso":"1","seccion":null,"areaConstruida":100,
            "valorUnitario":829.28,"incremento":0.0,"tablaDepreciacion":"01","antiguedad":10,"porcentajeDepreciacion":5,
            "valorUnitarioDepreciado":787.816,"valor":78781.6}],"obras":[{"codigoVu":"MUROS PERIMETRICOS O CERCOS|1",
            "cantidad":30,"valorUnitario":457.72,"factorOficializacion":0.68,"tablaDepreciacion":"01","antiguedad":10,
            "porcentajeDepreciacion":5,"valor":8870.61}]}}"""
        val SIN_VALOR =
            """{"anio":2026,"version":"valuacion-v2","region":"SELVA","codigoMunicipal":"01-01-0006","arancelM2":null,
            "origenArancel":null,"terreno":null,"construccion":null,"obras":null,"autoavaluo":null,"motivo":"Sin arancel",
            "llave":"ARANCEL_FALTANTE","detalle":null}"""

        // the example of task-5-report.md
        val ITEM_VALORADO =
            """{"codigoMunicipal":"PM-1","codigoCpu":"CPU-1","sector":"S01","manzana":"M01","lote":"L01","uso":"010101",
            "areaTerreno":200,"areaConstruida":100,"longitudFrente":10.5,"terreno":9100.00,"construccion":78781.60,"obras":8870.61,
            "autoavaluo":96752.21,"motivo":null,"llave":null,
            "loteGeom":{"type":"Polygon","coordinates":[[[-75.22,-10.95],[-75.2195,-10.95],[-75.2195,-10.9495],[-75.22,-10.95]]]}}"""
        val ITEM_GRANDE =
            ITEM_VALORADO
                .replace("PM-1", "PM-3")
                .replace("96752.21", "12345678901234567.89")
                .replace("8870.61", "0.10")
        val ITEM_SIN_VALOR =
            """{"codigoMunicipal":"PM-2","codigoCpu":null,"sector":"S01","manzana":"M01","lote":"L01","uso":"010101",
            "areaTerreno":200,"areaConstruida":100,"longitudFrente":null,"terreno":null,"construccion":null,"obras":null,"autoavaluo":null,
            "motivo":"La celda es .....","llave":"VU_SIN_VALOR:TECHOS:J","loteGeom":null}"""
    }
}
