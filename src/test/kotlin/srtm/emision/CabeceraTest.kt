package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

// the PU and HR's header: the organization's municipalidad record (the oldest, if the admin left two), the
// srtm.municipalidad.nombre fallback without one, and the escudo as a data: uri
class CabeceraTest {
    @Test
    fun `the municipalidad record makes the header, blanks left out`() {
        val cabecera =
            cabeceraDe(
                listOf(
                    Municipalidad(nombre = "MUNICIPALIDAD DISTRITAL DE PERENÉ", oficina = "  ", ruc = "20195238961", gerencia = "", direccion = "JR. LIMA 123")
                ),
                POR_DEFECTO,
                null
            )
        assertEquals(Cabecera("MUNICIPALIDAD DISTRITAL DE PERENÉ", ruc = "20195238961", direccion = "JR. LIMA 123"), cabecera)
    }

    @Test
    fun `the oldest record wins when there are several`() {
        val cabecera = cabeceraDe(listOf(Municipalidad(nombre = "PRIMERA"), Municipalidad(nombre = "SEGUNDA")), POR_DEFECTO, null)
        assertEquals("PRIMERA", cabecera.nombre)
    }

    @Test
    fun `without a record the configured name, and nothing else`() {
        assertEquals(Cabecera(POR_DEFECTO), cabeceraDe(emptyList(), POR_DEFECTO, null))
        // a record without its nombre (the field is required, but an old row could lack it) falls back too
        assertEquals(POR_DEFECTO, cabeceraDe(listOf(Municipalidad(ruc = "1")), POR_DEFECTO, null).nombre)
    }

    @Test
    fun `the escudo goes along as given`() {
        assertEquals("data:image/png;base64,AA==", cabeceraDe(emptyList(), POR_DEFECTO, "data:image/png;base64,AA==").escudo)
    }

    @Test
    fun `a png or a jpg becomes a data uri`() {
        val png = imagen("png")
        assertTrue(escudoDe(png, "escudo.png").startsWith("data:image/png;base64,"))
        assertTrue(escudoDe(imagen("jpg"), "escudo.jpg").startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `anything else is refused at startup, naming the file`() {
        val error = assertThrows<IllegalStateException> { escudoDe("<svg/>".toByteArray(), "escudo.svg") }
        assertTrue("escudo.svg" in error.message!!, error.message)
    }

    @Test
    fun `no escudo configured is no escudo`() {
        assertNull(escudoConfigurado("", { error("no se lee") }))
    }

    private fun imagen(formato: String): ByteArray {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(img, formato, it) }.toByteArray()
    }

    private companion object {
        const val POR_DEFECTO = "MUNICIPALIDAD DISTRITAL DE PERENÉ"
    }
}
