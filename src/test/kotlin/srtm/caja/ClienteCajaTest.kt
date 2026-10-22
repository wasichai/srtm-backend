package srtm.caja

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

// ClienteCaja against a stub of caja-backend (the jdk's http server): the keys caja takes, each status as a
// ResultadoCaja, the timeout, and a 401 that renews the token once
class ClienteCajaTest {
    private val cuerpos = CopyOnWriteArrayList<String>()
    private val auth = CopyOnWriteArrayList<String?>()
    private var tokens = 0
    private var estadoAlta = 201
    private var respuestaAlta = ORDEN
    private var estadoRetiro = 200
    private var respuestaRetiro = ORDEN
    private var rechazos = 0
    private var demora = 0L
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/api/auth/token") { ex ->
            ex.requestBody.readAllBytes()
            tokens++
            responder(ex, 200, """{"token":"t$tokens","expiresAt":"${java.time.Instant.now().plusSeconds(900)}"}""")
        }
        server.createContext("/api/caja/ordenes-de-cobro") { ex ->
            val cuerpo = ex.requestBody.readAllBytes().decodeToString()
            cuerpos += cuerpo
            auth += ex.requestHeaders.getFirst("Authorization")
            if (demora > 0) Thread.sleep(demora)
            if (rechazos > 0) {
                rechazos--
                responder(ex, 401, "{}")
                return@createContext
            }
            if (ex.requestURI.path.endsWith("/retiro")) responder(ex, estadoRetiro, respuestaRetiro) else responder(ex, estadoAlta, respuestaAlta)
        }
        server.start()
    }

    @AfterEach
    fun parar() = server.stop(0)

    private fun cliente(timeout: Duration? = null) =
        ClienteCaja(
            CuentaCaja(organizacion = "ica", url = "http://127.0.0.1:${server.address.port}/", clientId = "cid", clientSecret = "secreto", timeout = timeout)
        )

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

    private val orden =
        AltaDeOrden(
            referencia = "PREDIAL-x-2026-C1-v1",
            concepto = "Predial 2026 cuota 1",
            importe = BigDecimal("150.5"),
            fechaExigibilidad = LocalDate.of(2026, 2, 28),
            actualizadoA = LocalDate.of(2026, 10, 22),
            pagadorDocumento = "12345678",
            pagadorNombre = "Ana Pérez",
            observacion = "Declaración jurada 2026"
        )

    @Test
    fun altaMandaLasClavesDeCaja() {
        runBlocking { cliente().alta(orden) }
        val json = JsonMapper.builder().build().readTree(cuerpos.single())
        assertEquals(
            setOf("referencia_externa", "concepto", "importe", "fecha_exigibilidad", "actualizado_a", "pagador_documento", "pagador_nombre", "observacion"),
            json.propertyNames().toSet()
        )
        assertTrue(json.path("importe").isString)
        assertEquals("150.50", json.path("importe").asString())
        assertEquals("2026-02-28", json.path("fecha_exigibilidad").asString())
        assertEquals("Bearer t1", auth.single())
    }

    @Test
    fun altaOmiteLosOpcionalesAusentes() {
        runBlocking { cliente().alta(orden.copy(pagadorDocumento = " ", pagadorNombre = null, detalle = "", sustituyeA = "PREDIAL-x-2026-C1-v0")) }
        val json = JsonMapper.builder().build().readTree(cuerpos.single())
        assertFalse(json.has("pagador_documento"))
        assertFalse(json.has("pagador_nombre"))
        assertFalse(json.has("detalle"))
        assertEquals("PREDIAL-x-2026-C1-v0", json.path("sustituye_a").asString())
    }

    @Test
    fun alta201y200SonHecho() {
        val nuevo = runBlocking { cliente().alta(orden) }
        assertEquals(ResultadoCaja.Hecho("0b1f6c1e-0000-4000-8000-000000000001", "PENDIENTE"), nuevo)
        estadoAlta = 200
        assertEquals(nuevo, runBlocking { cliente().alta(orden) })
    }

    @Test
    fun alta409EsConflictoConElDetail() {
        estadoAlta = 409
        respuestaAlta = """{"title":"Conflict","status":409,"detail":"el contribuyente tiene otra deuda"}"""
        assertEquals(ResultadoCaja.Conflicto("el contribuyente tiene otra deuda"), runBlocking { cliente().alta(orden) })
    }

    @Test
    fun alta400y403SonRechazo() {
        estadoAlta = 400
        respuestaAlta = """{"detail":"importe inválido"}"""
        assertEquals(ResultadoCaja.Rechazo("importe inválido"), runBlocking { cliente().alta(orden) })
        estadoAlta = 403
        assertEquals(ResultadoCaja.Rechazo("importe inválido"), runBlocking { cliente().alta(orden) })
    }

    @Test
    fun alta404EsNoExiste() {
        estadoAlta = 404
        assertEquals(ResultadoCaja.NoExiste, runBlocking { cliente().alta(orden.copy(sustituyeA = "otra")) })
    }

    @Test
    fun retiroEsHecho() {
        respuestaRetiro = ORDEN.replace("PENDIENTE", "ANULADA")
        val r = runBlocking { cliente().retiro("ARB-1", "cambió la declaración") }
        assertEquals(ResultadoCaja.Hecho("0b1f6c1e-0000-4000-8000-000000000001", "ANULADA"), r)
        val json = JsonMapper.builder().build().readTree(cuerpos.single())
        assertEquals(setOf("referencia_externa", "motivo"), json.propertyNames().toSet())
    }

    @Test
    fun retiro409EsConflicto() {
        estadoRetiro = 409
        respuestaRetiro = """{"detail":"la orden ya está pagada"}"""
        assertEquals(ResultadoCaja.Conflicto("la orden ya está pagada"), runBlocking { cliente().retiro("ARB-1", "m") })
    }

    @Test
    fun retiro404EsNoExiste() {
        estadoRetiro = 404
        assertEquals(ResultadoCaja.NoExiste, runBlocking { cliente().retiro("ARB-1", "m") })
    }

    @Test
    fun un5xxEsNoContesta() {
        estadoAlta = 503
        assertTrue(runBlocking { cliente().alta(orden) } is ResultadoCaja.NoContesta)
    }

    @Test
    fun timeoutEsNoContesta() {
        demora = 600
        assertTrue(runBlocking { cliente(Duration.ofMillis(150)).alta(orden) } is ResultadoCaja.NoContesta)
    }

    @Test
    fun un401RenuevaElTokenUnaVez() {
        rechazos = 1
        val r = runBlocking { cliente().alta(orden) }
        assertTrue(r is ResultadoCaja.Hecho)
        assertEquals(2, tokens)
        assertEquals(listOf("Bearer t1", "Bearer t2"), auth.toList())
    }

    @Test
    fun un401RepetidoEsNoContesta() {
        rechazos = 2
        assertTrue(runBlocking { cliente().alta(orden) } is ResultadoCaja.NoContesta)
        assertEquals(2, tokens)
    }

    @Test
    fun sinConfiguracionNoHayCaja() {
        val cajas = Cajas(emptyMap())
        assertNull(cajas.de(UUID.randomUUID(), "ica"))
        assertFalse(cajas.configurado)
    }

    @Test
    fun cajasBuscaPorIdOPorSlugSinMayusculas() {
        val id = UUID.randomUUID()
        val a = cliente()
        val b = cliente()
        val cajas = Cajas(mapOf(id.toString() to a, "Ica" to b))
        assertTrue(cajas.configurado)
        assertTrue(cajas.necesitaSlug)
        assertEquals(a, cajas.de(id, null))
        assertEquals(b, cajas.de(UUID.randomUUID(), "ICA"))
        assertNull(cajas.de(UUID.randomUUID(), "otra"))
        assertEquals(setOf("Ica", id.toString()), cajas.organizaciones())
    }

    @Test
    fun toStringOcultaElSecreto() {
        val cuenta = CuentaCaja(organizacion = "ica", url = "http://x", clientId = "cid", clientSecret = "secreto-muy-largo")
        assertFalse(cuenta.toString().contains("secreto-muy-largo"))
        val props = CajaProperties(organizaciones = mapOf("ica" to cuenta))
        assertFalse(props.toString().contains("secreto-muy-largo"))
    }

    @Test
    fun laCajaSigueApagadaSinUnaEntradaCompleta() {
        val incompleta = CuentaCaja(url = "http://x", clientId = "cid")
        assertEquals(listOf("client-secret"), incompleta.faltan)
        val cajas = CajaConfig().cajas(CajaProperties(organizaciones = mapOf("ica" to incompleta)))
        assertFalse(cajas.configurado)
        val completa = incompleta.copy(clientSecret = "s")
        assertTrue(CajaConfig().cajas(CajaProperties(organizaciones = mapOf("ica" to completa))).configurado)
    }

    @Test
    fun losValoresPorDefecto() {
        val p = CajaProperties()
        assertEquals(Duration.ofSeconds(30), p.intervalo)
        assertEquals(50, p.lote)
        assertEquals(20, p.intentos)
        assertEquals(Duration.ofSeconds(5), p.plazoEnLinea)
    }

    companion object {
        private const val ORDEN = """{"orden_id":"0b1f6c1e-0000-4000-8000-000000000001","estado":"PENDIENTE"}"""
    }
}
