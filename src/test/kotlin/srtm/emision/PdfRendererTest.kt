package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// a thymeleaf template of templates/emision to an A4 pdf, with the embedded font: accents and ñ survive
class PdfRendererTest {
    private val renderer = PdfRenderer()

    @Test
    fun `a template renders to a pdf`() {
        val pdf = renderer.render("prueba", mapOf("titulo" to "Condominio", "nombre" to "x"))
        assertEquals("%PDF", String(pdf, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun `the text keeps its accents and enie`() {
        val pdf = renderer.render("prueba", mapOf("titulo" to "Condominio", "nombre" to "PEÑA ÑAUPARI, JOSÉ — Nº 1"))
        val texto = texto(pdf)
        assertTrue("Condominio" in texto, texto)
        assertTrue("PEÑA ÑAUPARI, JOSÉ — Nº 1" in texto, texto)
    }

    @Test
    fun `the page is A4`() {
        val pagina = tamanoPagina(renderer.render("prueba", mapOf("titulo" to "t", "nombre" to "n")))
        // 210 x 297 mm in points, give or take the rounding
        assertEquals(595.0, pagina.width.toDouble(), 1.0)
        assertEquals(842.0, pagina.height.toDouble(), 1.0)
    }

    @Test
    fun `the model's values are escaped`() {
        val texto = texto(renderer.render("prueba", mapOf("titulo" to "<b>A & B</b>", "nombre" to "n")))
        assertTrue("<b>A & B</b>" in texto, texto)
    }
}
