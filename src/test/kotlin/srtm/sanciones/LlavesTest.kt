package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

// the keys are written once on each side: the importer that checks the rows and the code that reads them must name
// the same tipos and claves (rentas#376)
class LlavesTest {
    private val importador = File("model/import_parametros.py").readText()
    private val constantes = Regex("""^(\w+) = "(\w+)"$""", RegexOption.MULTILINE).findAll(importador).associate { it.groupValues[1] to it.groupValues[2] }

    private fun tupla(nombre: String) =
        Regex("""$nombre = \(([^)]*)\)""")
            .find(importador)!!
            .groupValues[1]
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { constantes.getValue(it) }

    @Test
    fun `the tipos the code reads are the ones the importer checks`() {
        assertEquals(Llaves.TIPOS, tupla("TIPOS_SANCIONES"))
    }

    @Test
    fun `the plazos and their unit too`() {
        assertEquals(listOf(Llaves.DESCARGO_PAPELETA, Llaves.RG_RECURSO), tupla("CLAVES_PLAZO"))
        assertEquals(Llaves.DIAS_HABILES, constantes.getValue("DIAS_HABILES"))
        assertEquals("5 DIAS_HABILES", Llaves.plazoTexto(5))
        assertEquals("2026", Llaves.feriados(2026))
    }
}
