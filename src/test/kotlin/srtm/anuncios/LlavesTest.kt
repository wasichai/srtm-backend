package srtm.anuncios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

// the keys are written once on each side: the importer that checks the rows and the code that reads them must name
// the same tipos (rentas#376)
class LlavesTest {
    @Test
    fun `the tipos the code reads are the ones the importer checks`() {
        val importador = File("model/import_parametros.py").readText()
        val tupla = Regex("""TIPOS_ANUNCIO = \(([^)]*)\)""").find(importador)!!.groupValues[1]
        val constantes = Regex("""^(\w+) = "(\w+)"$""", RegexOption.MULTILINE).findAll(importador).associate { it.groupValues[1] to it.groupValues[2] }
        val tipos =
            tupla
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { constantes.getValue(it) }
        assertEquals(Llaves.TIPOS, tipos)
    }

    @Test
    fun `a missing tasa is named by its clase and year`() {
        assertEquals("TASA_ANUNCIO PANEL 2026", Llaves.falta("PANEL", 2026))
    }
}
