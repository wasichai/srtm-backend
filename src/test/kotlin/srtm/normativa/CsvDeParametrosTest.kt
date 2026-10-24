package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException
import java.io.File

// the csv POST /normativa/parametros/importar reads: model/data/parametros-predial.csv's columns, its '#' lines
// comments, a quoted cell may hold commas and "" is a quote. a row is named by its line in the file
class CsvDeParametrosTest {
    @Test
    fun `the shipped csv - 13 rows, comments left out, quoted commas kept`() {
        val filas = CsvDeParametros.leer(File("model/data/parametros-predial.csv").readText())
        assertEquals(13, filas.size)
        val tramo = filas.first { it.valores["tipo"] == "TRAMO_PREDIAL" && it.valores["clave"] == "1" }
        assertEquals("TUO de la Ley de Tributación Municipal (D.S. 156-2004-EF), artículo 13", tramo.valores["norma"])
        assertEquals("JNA", tramo.valores["transcribio"])
        // the header is on line 9: the first row on 10
        assertEquals(10, filas.first().linea)
        assertNull(filas.first().valores["clave"])
    }

    @Test
    fun `an empty cell is null, a quote is doubled, unknown columns are ignored, blank lines skipped`() {
        val texto =
            """
            # comentario
            tipo,clave,vigencia_desde,valor_numerico,texto,otra
            UIT,,2026-01-01,5500.00,"dice ""hola"", y más",x

            ARBITRIO_ZONA,S1,2026-01-01,,Z1,
            """.trimIndent()
        val filas = CsvDeParametros.leer(texto)
        assertEquals(listOf(3, 5), filas.map { it.linea })
        assertEquals("dice \"hola\", y más", filas[0].valores["texto"])
        assertNull(filas[0].valores["clave"])
        assertTrue("otra" !in filas[0].valores)
        assertNull(filas[1].valores["valor_numerico"])
    }

    @Test
    fun `without tipo or vigencia_desde in the header, or with a row longer than it, nothing is read`() {
        assertThrows(ValidationException::class.java) { CsvDeParametros.leer("clave,vigencia_desde\nX,2026-01-01") }
        val vacio = assertThrows(ValidationException::class.java) { CsvDeParametros.leer("# solo comentarios\n") }
        assertEquals("El CSV no tiene cabecera: tipo,clave,vigencia_desde,...", vacio.message)
        val e = assertThrows(ValidationException::class.java) { CsvDeParametros.leer("tipo,vigencia_desde\nUIT,2026-01-01,de más") }
        assertTrue(e.message!!.contains("2"), e.message)
        assertThrows(ValidationException::class.java) { CsvDeParametros.leer("tipo,vigencia_desde\n\"UIT,2026-01-01") }
    }
}
