package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MotivoTest {
    @Test
    fun `an edit through the portal is an actualizacion, whatever the record had`() {
        assertEquals("ACTUALIZACION", motivoAlEditar("INSCRIPCION"))
        assertEquals("ACTUALIZACION", motivoAlEditar("ACTUALIZACION"))
        // imported from the padrón: no motivo yet
        assertEquals("ACTUALIZACION", motivoAlEditar(null))
    }

    @Test
    fun `an annulled declaracion keeps its descargo`() {
        assertEquals("DESCARGO", motivoAlEditar("DESCARGO"))
    }
}
