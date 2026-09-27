package srtm.pide

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration

// the pide's rest service for RENIEC, against a stub on localhost: never the real one. the contract (the PIDE's
// published examples) is what the stub answers; the convenio's documentation has the last word
class PideReniecTest {
    private val json = JsonMapper.builder().build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    // what the stub received: the path with its query, and the body
    private val recibido = mutableListOf<Pair<String, String>>()

    private fun stub(
        status: Int = 200,
        demora: Long = 0,
        respuesta: String = exito()
    ): PideReniec {
        server.createContext("/Rest/RENIEC/Consultar") { exchange ->
            recibido += exchange.requestURI.toString() to exchange.requestBody.readAllBytes().decodeToString()
            Thread.sleep(demora)
            val bytes = respuesta.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return PideReniec(propiedades())
    }

    private fun propiedades() =
        PideReniecProperties(
            enabled = true,
            url = "http://127.0.0.1:${server.address.port}/Rest/RENIEC/Consultar",
            dniUsuario = "40404040",
            rucUsuario = "20131371455",
            password = "secreto",
            timeout = Duration.ofMillis(500)
        )

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `posts the institution's credentials with the dni, and reads the names`() {
        val datos = runBlocking { stub().consultar(DNI, "43554564") }

        assertEquals(DatosPersona(DNI, "43554564", "FLORES", "OTINIANO", "JUNIOR PAOLO", "SOLTERO", "JR. LIMA 123", "JUNIN/CHANCHAMAYO/PERENE"), datos)
        val (path, body) = recibido.single()
        assertEquals("/Rest/RENIEC/Consultar?out=json", path)
        assertEquals(
            mapOf("PIDE" to mapOf("nuDniConsulta" to "43554564", "nuDniUsuario" to "40404040", "nuRucUsuario" to "20131371455", "password" to "secreto")),
            json.readValue(body, Map::class.java)
        )
    }

    @Test
    fun `a blank name is none`() {
        val datos = runBlocking { stub(respuesta = exito(apSegundo = " ")).consultar(DNI, "43554564") }
        assertNull(datos?.apellidoMaterno)
        assertEquals("FLORES", datos?.apellidoPaterno)
    }

    @Test
    fun `any other result code is no data`() {
        val sinDatos = """{"consultarResponse":{"return":{"coResultado":"0001","deResultado":"El DNI no existe"}}}"""
        assertNull(runBlocking { stub(respuesta = sinDatos).consultar(DNI, "43554564") })
    }

    @Test
    fun `an answer without datosPersona, or not json, is no data`() {
        assertNull(leerRespuestaReniec("43554564", """{"consultarResponse":{"return":{"coResultado":"0000"}}}""").datos)
        assertNull(leerRespuestaReniec("43554564", """{"otra":"cosa"}""").datos)
        assertNull(runBlocking { stub(respuesta = "<html>mantenimiento</html>").consultar(DNI, "43554564") })
    }

    @Test
    fun `a failing service is no data`() {
        assertNull(runBlocking { stub(status = 500, respuesta = "error").consultar(DNI, "43554564") })
    }

    @Test
    fun `a slow service is no data, after the timeout`() {
        val inicio = System.nanoTime()
        assertNull(runBlocking { stub(demora = 3_000).consultar(DNI, "43554564") })
        assertTrue(Duration.ofNanos(System.nanoTime() - inicio) < Duration.ofSeconds(2))
    }

    @Test
    fun `only a dni is asked`() {
        val reniec = stub()
        assertNull(runBlocking { reniec.consultar("RUC", "20131312955") })
        assertNull(runBlocking { reniec.consultar("CARNET DE EXTRANJERIA", "001234567") })
        assertTrue(recibido.isEmpty())
    }

    @Test
    fun `the password never shows in the properties' text`() {
        val texto = propiedades().toString()
        assertFalse(texto.contains("secreto"))
        assertTrue(texto.contains("40404040"))
    }

    @Test
    fun `the consulta is PIDE RENIEC's only when enabled with its credentials`() {
        val config = PideConfig()
        assertInstanceOf(SinConsulta::class.java, config.consultaDocumento(PideReniecProperties()))
        assertInstanceOf(PideReniec::class.java, config.consultaDocumento(propiedades()))
        assertInstanceOf(SinConsulta::class.java, config.consultaDocumento(propiedades().copy(password = " ")))
        assertEquals(Duration.ofMinutes(30), PideReniecProperties().vigencia)
    }

    // the service's answer with out=json, as the PIDE publishes it (foto and restriccion are not read)
    private fun exito(apSegundo: String = "OTINIANO") =
        """
        {"consultarResponse":{"return":{"coResultado":"0000","deResultado":"La consulta se realizo exitosamente",
        "datosPersona":{"apPrimer":"FLORES","apSegundo":"$apSegundo","prenombres":"JUNIOR PAOLO","estadoCivil":"SOLTERO",
        "direccion":"JR. LIMA 123","ubigeo":"JUNIN/CHANCHAMAYO/PERENE","restriccion":"","foto":"iVBORw0KGgo="}}}}
        """.trimIndent()
}
