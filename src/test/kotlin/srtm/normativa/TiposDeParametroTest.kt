package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.TIPOS_PREDIAL
import java.io.File
import srtm.anuncios.Llaves as LlavesAnuncios
import srtm.arbitrios.Llaves as LlavesArbitrios
import srtm.sanciones.Llaves as LlavesSanciones

// the catalog of the tipos is written once more: what the code reads (each module's Llaves), what the importer checks
// (model/import_parametros.py) and what the shipped csvs bring must all be in it, and nothing else (as LlavesTest)
class TiposDeParametroTest {
    private val catalogo = TiposDeParametro.TODOS.map { it.tipo }

    @Test
    fun `the catalog is the tipos the modules read, each once`() {
        val leidos = TIPOS_PREDIAL + LlavesArbitrios.TIPOS + LlavesSanciones.TIPOS + LlavesAnuncios.TIPOS
        assertEquals(leidos.sorted(), catalogo.sorted())
        assertEquals(catalogo.size, catalogo.toSet().size)
    }

    @Test
    fun `every tipo the importer checks is in the catalog`() {
        val importador = File("model/import_parametros.py").readText()
        val constantes = Regex("""^(\w+) = "(\w+)"$""", RegexOption.MULTILINE).findAll(importador).associate { it.groupValues[1] to it.groupValues[2] }
        val tuplas = Regex("""^TIPOS_\w+ = \(([^)]*)\)""", RegexOption.MULTILINE).findAll(importador).toList()
        assertTrue(tuplas.size >= 3, "TIPOS_ARBITRIO, TIPOS_SANCIONES, TIPOS_ANUNCIO")
        val tipos =
            tuplas
                .flatMap { it.groupValues[1].split(",") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { constantes.getValue(it) }
        val faltan = tipos.filter { it !in catalogo }
        assertEquals(emptyList<String>(), faltan)
    }

    @Test
    fun `every tipo of the shipped csvs is in the catalog`() {
        val tipos =
            File("model/data")
                .walk()
                .filter { it.isFile && it.extension == "csv" }
                .filter { f -> f.useLines { lineas -> lineas.firstOrNull { !it.startsWith("#") }?.contains("transcribio") == true } }
                .flatMap { CsvDeParametros.leer(it.readText()).mapNotNull { fila -> fila.valores["tipo"] } }
                .toSet()
        assertTrue("UIT" in tipos && "TASA_ANUNCIO" in tipos && "TASA_ARBITRIO" in tipos, tipos.toString())
        assertEquals(emptySet<String>(), tipos - catalogo.toSet())
    }

    @Test
    fun `each tipo says how it is written`() {
        TiposDeParametro.TODOS.forEach {
            assertTrue(it.etiqueta.isNotBlank(), it.tipo)
            assertTrue(it.modulo in setOf("impuesto", "arbitrios", "sanciones", "anuncios"), it.tipo)
        }
        val uit = TiposDeParametro.de("UIT")!!
        assertEquals(false, uit.usaClave)
        assertEquals(ClaseDeValor.NUMERICO, uit.valor)
        assertEquals("soles", uit.unidad)
        assertEquals(true, TiposDeParametro.de("TRAMO_PREDIAL")!!.usaClave)
        assertEquals(ClaseDeValor.TEXTO, TiposDeParametro.de("ARBITRIO_ZONA")!!.valor)
        // a year without movable feriados is a row too: its texto empty
        assertEquals(true, TiposDeParametro.de("FERIADOS")!!.admiteVacio)
        assertNull(TiposDeParametro.de("NO_EXISTE"))
    }
}
