package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// the job as the api answers it, from its core record: errores travels as a json text
class EmisionTest {
    @Test
    fun `errores is read back as the list it was saved from`() {
        val errores = listOf(ErrorEmision("000123", "Faltan parámetros del año"), ErrorEmision("000124", "Sin \"DJ\" vigente"))
        val registro = RegistroEmision(id = "e1", anio = 2026, formato = "ZIP", estado = "TERMINADA", errores = erroresJson(errores))

        val emision = emisionDe(registro)

        assertEquals(errores, emision.errores)
        assertEquals("e1", emision.id)
        assertEquals("ZIP", emision.formato)
    }

    @Test
    fun `a job without errores has an empty list`() {
        assertEquals(emptyList<ErrorEmision>(), emisionDe(RegistroEmision(id = "e1", errores = null)).errores)
        assertEquals(emptyList<ErrorEmision>(), emisionDe(RegistroEmision(id = "e1", errores = " ")).errores)
    }

    @Test
    fun `the counters default to zero`() {
        val emision = emisionDe(RegistroEmision(id = "e1"))
        assertEquals(0, emision.total)
        assertEquals(0, emision.procesados)
    }
}
