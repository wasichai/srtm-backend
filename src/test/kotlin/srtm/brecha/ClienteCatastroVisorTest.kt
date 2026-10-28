package srtm.brecha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.PooledDataBuffer
import org.springframework.http.MediaType
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

// what the visor de brechas (#260) reads of catastro as the service account, against a stub of catastro-backend and
// of catastro-ui's nginx in front of GeoServer (the jdk's http server): the vías con arancel as catastro answers them,
// the catastro organization of the account (read once), and a WMS image relayed as bytes with its type, a 401 renewing
// the token once and a 403 failing with catastro's status and reason
class ClienteCatastroVisorTest {
    private val pedidos = CopyOnWriteArrayList<String>()
    private val crudas = CopyOnWriteArrayList<String>()
    private var tokens = 0
    private var rechazarUnaVez = false
    private var prohibir = false
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/api/auth/token") { ex ->
            ex.requestBody.readAllBytes()
            tokens++
            responder(ex, 200, """{"token":"t$tokens","expiresAt":"2999-01-01T00:00:00Z"}""".toByteArray(), "application/json")
        }
        server.createContext("/api/catastro/vias/aranceles") { ex ->
            pedidos += "vias ${ex.requestURI.query} ${ex.requestHeaders.getFirst("Authorization")}"
            if (rechazarUnaVez) {
                rechazarUnaVez = false
                return@createContext responder(ex, 401, "{}".toByteArray(), "application/json")
            }
            responder(ex, 200, VIAS.toByteArray(), "application/json")
        }
        server.createContext("/api/organizations/current") { ex ->
            pedidos += "organizacion ${ex.requestHeaders.getFirst("Authorization")}"
            responder(ex, 200, """{"id":"7c45fb26-1234-4abc-9def-0123456789ab","name":"Muni de prueba"}""".toByteArray(), "application/json")
        }
        server.createContext("/geoserver/catastro/wms") { ex ->
            pedidos += "wms ${ex.requestURI.query} ${ex.requestHeaders.getFirst("Authorization")}"
            crudas += ex.requestURI.rawQuery
            when {
                rechazarUnaVez -> {
                    rechazarUnaVez = false
                    responder(ex, 401, ByteArray(0), "text/plain")
                }
                prohibir ->
                    responder(
                        ex,
                        403,
                        """{"message":"La capa x no se sirve por WMS a este usuario: tiene un alcance de lectura"}""".toByteArray(),
                        "application/json"
                    )
                else -> responder(ex, 200, PNG, "image/png")
            }
        }
        server.start()
    }

    @AfterEach
    fun parar() = server.stop(0)

    private val raiz get() = "http://127.0.0.1:${server.address.port}"

    private fun cliente(wms: String = "$raiz/geoserver/catastro/wms") =
        ClienteCatastro(CuentaCatastro(url = "$raiz/", clientId = "cid", clientSecret = "secreto", wmsUrl = wms))

    private fun responder(
        ex: HttpExchange,
        estado: Int,
        cuerpo: ByteArray,
        tipo: String
    ) {
        ex.responseHeaders.add("Content-Type", tipo)
        ex.sendResponseHeaders(estado, if (cuerpo.isEmpty()) -1 else cuerpo.size.toLong())
        ex.responseBody.use { it.write(cuerpo) }
    }

    private val getMap =
        listOf(
            "service" to "WMS",
            "request" to "GetMap",
            "layers" to "predio_catastral__7c45fb26__lote_geom",
            "srs" to "EPSG:3857",
            "bbox" to "-8430000,-1240000,-8420000,-1230000",
            "width" to "256",
            "height" to "256",
            "format" to "image/png"
        )

    private fun bytes(imagen: ImagenWms): ByteArray =
        runBlocking {
            DataBufferUtils.join(imagen.cuerpo).awaitSingle().let { b ->
                ByteArray(b.readableByteCount()).also {
                    b.read(it)
                    DataBufferUtils.release(b)
                }
            }
        }

    @Test
    fun `the vias of a year as catastro answers them, a 401 renewing the token`() {
        val c = cliente()
        assertEquals(VIAS, runBlocking { c.vias(2026) })
        rechazarUnaVez = true
        assertEquals(VIAS, runBlocking { c.vias(2027) })
        assertEquals(listOf("vias anio=2026 Bearer t1", "vias anio=2027 Bearer t1", "vias anio=2027 Bearer t2"), pedidos)
    }

    @Test
    fun `the organization of the account is read once`() {
        val c = cliente()
        assertEquals("7c45fb26-1234-4abc-9def-0123456789ab", runBlocking { c.organizacionCatastral() })
        assertEquals("7c45fb26-1234-4abc-9def-0123456789ab", runBlocking { c.organizacionCatastral() })
        assertEquals(listOf("organizacion Bearer t1"), pedidos)
    }

    @Test
    fun `a GetMap is relayed as bytes with its type, its parameters encoded once`() {
        val c = cliente()
        assertTrue(c.conWms)
        val imagen = runBlocking { c.wms(getMap) }
        assertEquals(MediaType.IMAGE_PNG, imagen.tipo)
        assertArrayEquals(PNG, bytes(imagen))
        val pedido = pedidos.single()
        assertTrue(pedido.startsWith("wms service=WMS&request=GetMap&layers=predio_catastral__7c45fb26__lote_geom&srs=EPSG:3857&bbox="), pedido)
        assertTrue(pedido.endsWith("&width=256&height=256&format=image/png Bearer t1"), pedido)
        // a ':' or a ',' of a value goes encoded, and nothing twice
        assertTrue(crudas.single().contains("srs=EPSG%3A3857&bbox=-8430000%2C-1240000"), crudas.single())
    }

    @Test
    fun `the image comes on the heap, with no buffer of catastro's connection to release (#264)`() {
        val trozos =
            runBlocking {
                cliente()
                    .wms(getMap)
                    .cuerpo
                    .collectList()
                    .awaitSingle()
            }
        assertTrue(trozos.isNotEmpty())
        assertTrue(trozos.none { it is PooledDataBuffer }, trozos.toString())
    }

    @Test
    fun `a 401 to the WMS renews the token once`() {
        val c = cliente()
        rechazarUnaVez = true
        assertArrayEquals(PNG, bytes(runBlocking { c.wms(getMap) }))
        assertEquals(listOf("t1", "t2"), pedidos.map { it.substringAfterLast(' ') })
    }

    @Test
    fun `a 403 of catastro fails with its status and its reason`() {
        prohibir = true
        val e = assertThrows(CatastroException::class.java) { runBlocking { cliente().wms(getMap) } }
        assertEquals(403, e.estado)
        assertTrue(e.detalle.orEmpty().contains("alcance de lectura"), e.detalle)
        assertFalse(e.message.orEmpty().contains("secreto"))
    }

    @Test
    fun `without a wms url there is no WMS`() {
        val c = cliente(wms = " ")
        assertFalse(c.conWms)
        val e = assertThrows(CatastroException::class.java) { runBlocking { c.wms(getMap) } }
        assertEquals(404, e.estado)
        assertTrue(pedidos.isEmpty())
    }

    @Test
    fun `the wms url comes from the environment's key`() {
        val p = CatastroProperties(enabled = true, organizaciones = mapOf("ica" to CuentaCatastro(url = "u", clientId = "c", clientSecret = "s", wmsUrl = "w")))
        assertEquals("w", p.cuentas().getValue("ica").wmsUrl)
    }

    private companion object {
        const val VIAS =
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-75.7,-14.0],[-75.71,-14.01]]},""" +
                """"properties":{"cod_via_eje":"V1","tip_via":"AV","nom_via":"PRUEBA","cuadra":"1","lado":"I","id_aranc":"A1","asignacion":"EJE","anio":2026,"valor_m2":120.5}}]}"""

        // the 8 bytes of a png's signature and a few more: what matters is that they come back as they went
        val PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82)
    }
}
