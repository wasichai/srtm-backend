package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

// the keys are written once on each side: the importer that checks the ordinance's rows and the code that reads them
// must name the same tipos (rentas#376)
class LlavesTest {
    @Test
    fun `the tipos the code reads are the ones the importer checks`() {
        val importador = File("model/import_parametros.py").readText()
        val tupla = Regex("""TIPOS_ARBITRIO = \(([^)]*)\)""").find(importador)!!.groupValues[1]
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
    fun `a tasa's key and what the cuota keeps of it`() {
        assertEquals("LIMPIEZA:Z1:CASA", Llaves.tasa("LIMPIEZA", "Z1", "CASA"))
        assertEquals("TASA_ARBITRIO:LIMPIEZA:Z1:CASA", Llaves.aplicado("LIMPIEZA:Z1:CASA"))
    }
}
