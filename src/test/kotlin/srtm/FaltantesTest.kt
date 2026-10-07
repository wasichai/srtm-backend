package srtm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.anuncios.FaltanAnuncios
import srtm.emision.FaltanParametros
import srtm.sanciones.FaltanSanciones
import wasichai.core.common.GlobalExceptionHandler
import java.net.URI

// every module's 422 with `faltan` leaves as core's problem+json plus the missing things (Faltan.kt): one handler for
// all of them, so no endpoint drops them
class FaltantesTest {
    private val faltantes = Faltantes(GlobalExceptionHandler("https://srtm.test/problems/"))
    private val faltan = listOf("UIT 2026", "PLAZO RG_RECURSO 2026")

    @Test
    fun `it is core's 422 problem plus faltan`() {
        val problema = faltantes.faltan(FaltanSanciones("registrar el acta", faltan))
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT.value(), problema.status)
        assertEquals(URI.create("https://srtm.test/problems/422"), problema.type)
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT.reasonPhrase, problema.title)
        assertEquals("No se puede registrar el acta: UIT 2026; PLAZO RG_RECURSO 2026", problema.detail)
        assertEquals(faltan, problema.properties?.get("faltan"))
        assertNull(problema.properties?.get("errors"), "a faltan names no field")
    }

    @Test
    fun `every module's faltan is answered the same way, with its own message`() {
        val todos = listOf(FaltanSanciones("registrar el acta", faltan), FaltanAnuncios(faltan), FaltanParametros(2026, faltan))
        for (ex in todos) {
            val problema = faltantes.faltan(ex)
            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT.value(), problema.status, ex.javaClass.simpleName)
            assertEquals(ex.message, problema.detail, ex.javaClass.simpleName)
            assertEquals(faltan, problema.properties?.get("faltan"), ex.javaClass.simpleName)
        }
    }
}
