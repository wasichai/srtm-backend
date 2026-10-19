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
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/api/auth/token") { ex ->
            val body = ex.requestBody.readAllBytes().decodeToString()
            pedidos += "token $body"
            tokens++
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
        server.start()
    }

    @AfterEach
    fun parar() = server.stop(0)

    private fun cliente(ahora: () -> Instant = Instant::now) =
        ClienteCatastro(CatastroProperties(enabled = true, url = "http://127.0.0.1:${server.address.port}/", clientId = "cid", clientSecret = "secreto"), ahora)

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
    fun `the catastro is one organization's, by its id or its slug, and none set refuses every corrida`() {
        val id = java.util.UUID.randomUUID()
        assertNull(otraOrganizacion(id.toString(), id, null))
        assertNull(otraOrganizacion(" perene ", id, "PERENE"))
        assertEquals(
            DE_OTRA_ORGANIZACION,
            otraOrganizacion(
                java.util.UUID
                    .randomUUID()
                    .toString(),
                id,
                null
            )
        )
        assertEquals(DE_OTRA_ORGANIZACION, otraOrganizacion("perene", id, "otra"))
        assertEquals(DE_OTRA_ORGANIZACION, otraOrganizacion("perene", id, null))
        assertEquals(SIN_ORGANIZACION, otraOrganizacion("  ", id, "perene"))
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
    fun `an error of catastro fails with its status, never the secret`() {
        server.removeContext("/api/catastro/valuaciones")
        server.createContext("/api/catastro/valuaciones") { ex -> responder(ex, 403, "{}") }
        val e = assertThrows(CatastroException::class.java) { runBlocking { cliente().valuaciones(2026) } }
        assertTrue(e.message!!.contains("403"), e.message)
        assertTrue(!e.message!!.contains("secreto"))
    }

    private companion object {
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
