package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// #164: why caja is told that a declaración changed
class MotivoEnCajaTest {
    private val dj = Declaracion(numeroDeclaracion = 12, estado = VIGENTE, motivo = "INSCRIPCION")

    @Test
    fun `an edit, a new one or one deleted is a redeterminacion by its numero`() {
        assertEquals("Redeterminación por la DJ n° 12", motivoEnCaja(dj, dj.copy(uso = "COMERCIO")))
        assertEquals("Redeterminación por la DJ n° 12", motivoEnCaja(null, dj))
        assertEquals("Redeterminación por la DJ n° 12", motivoEnCaja(dj, null))
        assertEquals("Redeterminación por la DJ del padrón", motivoEnCaja(null, Declaracion()))
    }

    @Test
    fun `a rectificatoria and its edits are the rectificatoria's`() {
        val r = dj.copy(numeroDeclaracion = 13, motivo = RECTIFICACION)
        assertEquals("Rectificatoria n° 13", motivoEnCaja(null, r))
        assertEquals("Rectificatoria n° 13", motivoEnCaja(r, r))
    }

    @Test
    fun `the anulacion of a declaracion`() {
        assertEquals("Anulación de la DJ n° 12", motivoEnCaja(dj, dj.copy(estado = ANULADA)))
    }
}
