package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

// pdfs one after another, through temp files: the masiva merges thousands
class PdfMergerTest {
    private val merger = PdfMerger()

    @Test
    fun `three pdfs merge into the sum of their pages`() {
        val destino = ByteArrayOutputStream()
        merger.merge(listOf(enBlanco(1), enBlanco(2), enBlanco(3)), destino)
        assertEquals(6, paginas(destino.toByteArray()))
    }

    @Test
    fun `the parts keep their order`() {
        val renderer = PdfRenderer()
        val partes = listOf("PRIMERO", "SEGUNDO", "TERCERO").map { renderer.render("prueba", mapOf("titulo" to it, "nombre" to "n")) }
        val destino = ByteArrayOutputStream()
        merger.merge(partes, destino)
        val texto = texto(destino.toByteArray())
        assertEquals(listOf("PRIMERO", "SEGUNDO", "TERCERO"), listOf("PRIMERO", "SEGUNDO", "TERCERO").sortedBy { texto.indexOf(it) })
    }

    @Test
    fun `files on disk merge into a file`(
        @TempDir dir: Path
    ) {
        val partes = listOf(1, 2, 3).map { n -> dir.resolve("parte-$n.pdf").also { Files.write(it, enBlanco(n)) } }
        val destino = dir.resolve("todo.pdf")
        merger.merge(partes, destino)
        assertEquals(6, paginas(Files.readAllBytes(destino)))
    }
}
