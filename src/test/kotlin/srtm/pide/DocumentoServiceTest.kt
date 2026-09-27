package srtm.pide

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.NotFoundException
import java.time.Duration

// GET /api/srtm/documentos/{tipo}/{numero}: RENIEC's names for a DNI, remembered to back a save with PIDE RENIEC
class DocumentoServiceTest {
    // a registry that knows one person, and tells what it was asked
    private class Doble : ConsultaDocumento {
        val preguntas = mutableListOf<Pair<String, String>>()

        override suspend fun consultar(
            tipo: String,
            numero: String
        ): DatosPersona? {
            preguntas += tipo to numero
            return if (numero == "43554564") DatosPersona(tipo, numero, "FLORES", "OTINIANO", "JUNIOR PAOLO") else null
        }
    }

    private val doble = Doble()
    private val consultas = ConsultasReniec(Duration.ofMinutes(30))
    private val service = DocumentoService(doble, consultas)

    @Test
    fun `answers RENIEC's names and remembers the consulta`() {
        val datos = runBlocking { service.consultar(DNI, " 43554564 ") }
        assertEquals("FLORES", datos.apellidoPaterno)
        assertEquals("43554564", datos.numeroDocumento)
        assertEquals(PIDE_RENIEC, datos.fuenteInformacion)
        assertEquals(listOf(DNI to "43554564"), doble.preguntas)
        assertEquals(datos, consultas.vigente(DNI, "43554564"))
    }

    @Test
    fun `no data is a 404, and nothing to back a save`() {
        assertThrows<NotFoundException> { runBlocking { service.consultar(DNI, "43554565") } }
        assertNull(consultas.vigente(DNI, "43554565"))
    }

    @Test
    fun `only a well formed dni is asked`() {
        assertThrows<NotFoundException> { runBlocking { service.consultar("RUC", "20131312955") } }
        assertThrows<NotFoundException> { runBlocking { service.consultar(DNI, "4355456") } }
        assertThrows<NotFoundException> { runBlocking { service.consultar(DNI, "4355456A") } }
        assertTrue(doble.preguntas.isEmpty())
    }

    @Test
    fun `without a convenio every dni is a 404`() {
        val sin = DocumentoService(SinConsulta(), consultas)
        assertThrows<NotFoundException> { runBlocking { sin.consultar(DNI, "43554564") } }
    }
}
