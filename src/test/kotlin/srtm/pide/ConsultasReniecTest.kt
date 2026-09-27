package srtm.pide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import java.time.Duration
import java.time.Instant

// what backs a fuente PIDE RENIEC: a consulta of that DNI, recent, that answered the same names
class ConsultasReniecTest {
    private var ahora = Instant.parse("2026-09-27T10:00:00Z")
    private val consultas = ConsultasReniec(Duration.ofMinutes(30)) { ahora }

    private val flores = DatosPersona(DNI, "43554564", "FLORES", "OTINIANO", "JUNIOR PAOLO")

    private fun persona(
        numero: String = "43554564",
        paterno: String? = "FLORES",
        materno: String? = "OTINIANO",
        nombres: String? = "JUNIOR PAOLO",
        fuente: String? = PIDE_RENIEC,
        tipo: String? = DNI
    ) = Persona(tipo, numero, paterno, materno, nombres, fuente)

    // the field a refusal names
    private fun rechazo(
        persona: Persona,
        anterior: Persona? = null
    ): String = assertThrows<ValidationException> { consultas.respaldar(persona, anterior) }.violations.single().field

    @Test
    fun `a consulta is kept for its vigencia`() {
        consultas.registrar(flores)
        assertEquals(flores, consultas.vigente(DNI, "43554564"))
        assertEquals(flores, consultas.vigente(DNI, " 43554564 "))
        assertNull(consultas.vigente(DNI, "43554565"))
        assertNull(consultas.vigente("RUC", "43554564"))

        ahora = ahora.plus(Duration.ofMinutes(30))
        assertEquals(flores, consultas.vigente(DNI, "43554564"))
        ahora = ahora.plusSeconds(1)
        assertNull(consultas.vigente(DNI, "43554564"))
    }

    @Test
    fun `a new consulta of the same dni replaces the old one`() {
        consultas.registrar(flores)
        ahora = ahora.plus(Duration.ofMinutes(20))
        consultas.registrar(flores.copy(nombres = "JUNIOR"))
        ahora = ahora.plus(Duration.ofMinutes(20))
        assertEquals("JUNIOR", consultas.vigente(DNI, "43554564")?.nombres)
    }

    @Test
    fun `any other fuente needs no consulta`() {
        assertDoesNotThrow { consultas.respaldar(persona(fuente = "MANUAL"), null) }
        assertDoesNotThrow { consultas.respaldar(persona(fuente = null), null) }
        assertDoesNotThrow { consultas.respaldar(persona(fuente = "PIDE SUNAT", tipo = "RUC", numero = "20131312955"), null) }
    }

    @Test
    fun `PIDE RENIEC takes the names of a recent consulta, whatever their case and spacing`() {
        consultas.registrar(flores)
        assertDoesNotThrow { consultas.respaldar(persona(), null) }
        assertDoesNotThrow { consultas.respaldar(persona(paterno = " flores ", nombres = "JUNIOR  PAOLO"), null) }
    }

    @Test
    fun `a missing apellido materno is a blank one`() {
        consultas.registrar(flores.copy(apellidoMaterno = null))
        assertDoesNotThrow { consultas.respaldar(persona(materno = ""), null) }
        assertDoesNotThrow { consultas.respaldar(persona(materno = null), null) }
        assertEquals("fuente_informacion", rechazo(persona(materno = "OTINIANO")))
    }

    @Test
    fun `PIDE RENIEC without a consulta, an old one or other names is refused on the fuente`() {
        assertEquals("fuente_informacion", rechazo(persona()))

        consultas.registrar(flores)
        assertEquals("fuente_informacion", rechazo(persona(nombres = "JUNIOR")))
        assertEquals("fuente_informacion", rechazo(persona(paterno = "FLOREZ")))
        assertEquals("fuente_informacion", rechazo(persona(numero = "43554565")))
        assertEquals("fuente_informacion", rechazo(persona(tipo = "CARNET DE EXTRANJERIA")))

        ahora = ahora.plus(Duration.ofMinutes(31))
        assertEquals("fuente_informacion", rechazo(persona()))
    }

    @Test
    fun `a record that had PIDE RENIEC keeps it while its document and names stay`() {
        val guardado = persona()
        assertDoesNotThrow { consultas.respaldar(persona(), guardado) }
        assertDoesNotThrow { consultas.respaldar(persona(nombres = "junior paolo"), guardado) }

        assertEquals("fuente_informacion", rechazo(persona(nombres = "JUNIOR"), guardado))
        assertEquals("fuente_informacion", rechazo(persona(numero = "43554565"), guardado))
        assertEquals("fuente_informacion", rechazo(persona(), guardado.copy(fuenteInformacion = "MANUAL")))
    }
}
