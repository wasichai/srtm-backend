package srtm.emision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

// the PU and HR's header: the organization's municipalidad record (the oldest, if the admin left two), the
// fallback name without one (srtm.municipalidad.nombre, else the organization's own), and the escudo as a data: uri:
// the record's, else srtm.municipalidad.escudo
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
    fun `the fallback name is the configured one, else the organization's name, else its slug`() {
        assertEquals("CONFIGURADO", nombreDeRespaldo("CONFIGURADO", "Municipalidad de Ica", "ica", ID))
        assertEquals("CONFIGURADO", nombreDeRespaldo("  CONFIGURADO ", null, null, ID))
        assertEquals("Municipalidad de Ica", nombreDeRespaldo("", "Municipalidad de Ica", "ica", ID))
        assertEquals("Municipalidad de Ica", nombreDeRespaldo("  ", " Municipalidad de Ica ", "ica", ID))
        assertEquals("ica", nombreDeRespaldo("", " ", "ica", ID))
        assertEquals("ica", nombreDeRespaldo("", null, "ica", ID))
        // an organization core cannot find: never an empty header
        assertEquals(ID.toString(), nombreDeRespaldo("", null, null, ID))
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

    @Test
    fun `the record's escudo wins over the configured one`() =
        runBlocking<Unit> {
            val png = imagen("png")
            val leidos = mutableListOf<UUID>()
            val escudo =
                escudoElegido(Municipalidad(id = ID.toString(), escudo = DESCRIPTOR), CONFIGURADO) {
                    leidos += it
                    png
                }
            assertEquals(escudoDe(png, "escudo.png"), escudo)
            assertEquals(listOf(ID), leidos)
            assertTrue(escudoElegido(Municipalidad(id = ID.toString(), escudo = DESCRIPTOR), null) { imagen("jpg") }!!.startsWith("data:image/jpeg;base64,"))
        }

    @Test
    fun `without an escudo in the record, or without a record, the configured one`() =
        runBlocking<Unit> {
            assertEquals(CONFIGURADO, escudoElegido(Municipalidad(id = ID.toString(), nombre = "X"), CONFIGURADO) { error("no se lee") })
            assertEquals(CONFIGURADO, escudoElegido(null, CONFIGURADO) { error("no se lee") })
            assertNull(escudoElegido(null, null) { error("no se lee") })
        }

    @Test
    fun `a record's escudo that is no image, or cannot be read, prints none and never fails the document`() =
        runBlocking<Unit> {
            val registro = Municipalidad(id = ID.toString(), escudo = DESCRIPTOR)
            assertNull(escudoElegido(registro, CONFIGURADO) { "<svg/>".toByteArray() })
            // a png's first bytes and nothing after them: the pdf could not draw it
            assertNull(escudoElegido(registro, CONFIGURADO) { imagen("png").copyOf(40) })
            assertNull(escudoElegido(registro, CONFIGURADO) { throw IllegalStateException("el almacén no lo tiene") })
        }

    @Test
    fun `a cancelled read is not swallowed`() {
        assertThrows<CancellationException> {
            runBlocking { escudoElegido(Municipalidad(id = ID.toString(), escudo = DESCRIPTOR), CONFIGURADO) { throw CancellationException("fin") } }
        }
    }

    private fun imagen(formato: String): ByteArray {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(img, formato, it) }.toByteArray()
    }

    private companion object {
        val ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        const val POR_DEFECTO = "MUNICIPALIDAD DISTRITAL DE PERENÉ"
        const val CONFIGURADO = "data:image/png;base64,Q09ORklHVVJBRE8="

        // what core reads of an IMAGE field (wasichai-files' descriptor)
        val DESCRIPTOR =
            mapOf(
                "id" to "00000000-0000-0000-0000-0000000000aa",
                "name" to "escudo.png",
                "contentType" to "image/png",
                "size" to 10,
                "sha256" to "x"
            )
    }
}
