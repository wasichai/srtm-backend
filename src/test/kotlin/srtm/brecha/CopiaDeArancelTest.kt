package srtm.brecha

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

// ClienteCatastro's copy of an arancel (#189) against a stub of catastro's core (the jdk's http server): its records
// of arancel in memory, found by GET ?clave= (core's equality filter), created by POST, replaced by PUT and deleted
// by DELETE, as the service account (a 401 renews its token once)
class CopiaDeArancelTest {
    private val pedidos = CopyOnWriteArrayList<String>()
    private val registros = mutableMapOf<String, Map<String, Any?>>()
    private var tokens = 0
    private var rechazarUnaVez = false
    private var estadoForzado: Int? = null
    private val json = JsonMapper.builder().build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/api/auth/token") { ex ->
            ex.requestBody.readAllBytes()
            tokens++
            responder(ex, 200, """{"token":"t$tokens","expiresAt":"2999-01-01T00:00:00Z","serviceAccount":"srtm"}""")
        }
        server.createContext("/api/objects/arancel/records") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            val cuerpo = ex.requestBody.readAllBytes().decodeToString()
            val ruta =
                ex.requestURI.path
                    .removePrefix("/api/objects/arancel/records")
                    .removePrefix("/")
            pedidos += "${ex.requestMethod} ${ruta.ifEmpty { "-" }} ${ex.requestURI.rawQuery ?: ""} $auth".trim()
            if (rechazarUnaVez) {
                rechazarUnaVez = false
                return@createContext responder(ex, 401, "{}")
            }
            estadoForzado?.let { return@createContext responder(ex, it, """{"detail":"no"}""") }
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
                    val suyos = registros.filterValues { it["clave"] == clave }.map { (id, a) -> mapOf("id" to id, "attributes" to a) }
                    responder(ex, 200, json.writeValueAsString(mapOf("content" to suyos, "totalElements" to suyos.size)))
                }
                "POST" -> {
                    val id = UUID.randomUUID().toString()
                    registros[id] = atributos(cuerpo)
                    responder(ex, 201, """{"id":"$id"}""")
                }
                "PUT" -> {
                    registros[ruta] = atributos(cuerpo)
                    responder(ex, 200, """{"id":"$ruta"}""")
                }
                "DELETE" -> {
                    registros.remove(ruta)
                    ex.sendResponseHeaders(204, -1)
                    ex.close()
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun parar() = server.stop(0)

    @Suppress("UNCHECKED_CAST")
    private fun atributos(cuerpo: String) = (json.readValue(cuerpo, Map::class.java) as Map<String, Any?>)["attributes"] as Map<String, Any?>

    private fun cliente() = ClienteCatastro(CuentaCatastro(url = "http://127.0.0.1:${server.address.port}/", clientId = "cid", clientSecret = "secreto"))

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

    private val a01 = ArancelCatastral("A-01|2026", "A-01", 2026, BigDecimal("45.50"), "V-10", "2", "I")

    private fun guardado(
        valor: Any = 45.5,
        cuadra: String? = "2"
    ): String {
        val id = UUID.randomUUID().toString()
        registros[id] =
            mapOf("clave" to "A-01|2026", "id_aranc" to "A-01", "anio" to 2026, "valor_m2" to valor, "cod_via" to "V-10", "cuadra" to cuadra, "lado" to "I")
        return id
    }

    @Test
    fun `an arancel catastro lacks is created, found by its clave`() {
        assertEquals(CopiaDeArancel.CREADO, runBlocking { cliente().guardarArancel(a01) })
        val creado = registros.values.single()
        assertEquals("A-01|2026", creado["clave"])
        assertEquals(2026, creado["anio"])
        assertEquals(45.5, (creado["valor_m2"] as Number).toDouble())
        assertEquals("V-10", creado["cod_via"])
        // the clave goes encoded in the filter (its | is no separator), and every request as the service account
        assertEquals(listOf("GET - clave=A-01%7C2026&size=2 Bearer t1", "POST -  Bearer t1"), pedidos)
    }

    @Test
    fun `one that differs is replaced, one that is the same is left alone`() {
        val id = guardado(cuadra = "3")
        assertEquals(CopiaDeArancel.ACTUALIZADO, runBlocking { cliente().guardarArancel(a01) })
        assertEquals("PUT $id  Bearer t1", pedidos.last())
        assertEquals("2", registros.getValue(id)["cuadra"])

        pedidos.clear()
        // 45.5 as catastro answers it is 45.50: the same number
        assertEquals(CopiaDeArancel.IGUAL, runBlocking { cliente().guardarArancel(a01) })
        assertEquals(1, pedidos.size)
        assertTrue(pedidos.single().startsWith("GET "))
    }

    @Test
    fun `a value as text is compared by its number`() {
        val id = guardado(valor = "45.500")
        assertEquals(CopiaDeArancel.IGUAL, runBlocking { cliente().guardarArancel(a01) })
        registros[id] = registros.getValue(id) + ("valor_m2" to 46)
        assertEquals(CopiaDeArancel.ACTUALIZADO, runBlocking { cliente().guardarArancel(a01) })
        assertEquals(45.5, (registros.getValue(id)["valor_m2"] as Number).toDouble())
    }

    @Test
    fun `deleting one that is there deletes it, one that is not is no error`() {
        val id = guardado()
        assertEquals(CopiaDeArancel.BORRADO, runBlocking { cliente().borrarArancel("A-01|2026") })
        assertTrue(registros.isEmpty())
        assertTrue(pedidos.any { it.startsWith("DELETE $id ") }, pedidos.toString())

        pedidos.clear()
        assertEquals(CopiaDeArancel.NO_ESTABA, runBlocking { cliente().borrarArancel("A-01|2026") })
        assertFalse(pedidos.any { it.startsWith("DELETE") }, pedidos.toString())
    }

    @Test
    fun `the error names the act as spanish contracts it`() {
        server.removeContext("/api/objects/arancel/records")
        server.createContext("/api/objects/arancel/records") { ex ->
            ex.requestBody.readAllBytes()
            if (ex.requestMethod == "GET") responder(ex, 200, """{"content":[]}""") else responder(ex, 409, "{}")
        }
        val e = assertThrows(CatastroException::class.java) { runBlocking { cliente().guardarArancel(a01) } }
        assertEquals("catastro-backend respondió 409 al alta del arancel A-01|2026", e.message)
    }

    @Test
    fun `a 401 renews the token once`() {
        val c = cliente()
        runBlocking { c.guardarArancel(a01) }
        rechazarUnaVez = true
        assertEquals(CopiaDeArancel.IGUAL, runBlocking { c.guardarArancel(a01) })
        assertEquals(2, tokens)
    }

    @Test
    fun `a refusal of catastro fails with its status, never the secret`() {
        estadoForzado = 403
        val e = assertThrows(CatastroException::class.java) { runBlocking { cliente().guardarArancel(a01) } }
        assertTrue(e.message!!.contains("403"), e.message)
        assertTrue(e.message!!.contains("403 a la búsqueda del arancel A-01|2026"), e.message)
        assertFalse(e.message!!.contains("secreto"))
    }
}
