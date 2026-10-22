package srtm.caja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.Instant

// the wait after caja did not answer (srtm.caja.espera-reintento, an hour here): neither the loop nor another POST
// tries the orden again before it ends
@TestPropertySource(properties = ["srtm.caja.espera-reintento=PT1H"])
class CajaEsperaApiTest : ConCajaApiTest() {
    @Test
    fun `noContestaEsperaAntesDeReintentar`() {
        val e = Escenario()
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val documento = documentoDe(e.contribuyente)
        respuesta = { if (it.alta) 503 to "{}" else null }

        enviar(e.contribuyente, e.anio)

        val antes = registrosDe(e.contribuyente)
        val pronto = Instant.now().plus(Duration.ofMinutes(50))
        assertTrue(antes.all { Instant.parse(it["tomado_hasta"].asString()).isAfter(pronto) }, antes.first().toString())

        respuesta = { null }
        PEDIDOS.clear()
        runBlocking { envio.vuelta() }
        enviar(e.contribuyente, e.anio)

        assertTrue(altasDe(documento).isEmpty())
        val despues = registrosDe(e.contribuyente)
        assertEquals(List(24) { EstadoOrdenCaja.POR_ENVIAR }, despues.map { it["estado"].asString() })
        assertTrue(despues.all { it["intentos"].asInt() == 1 })
    }
}
