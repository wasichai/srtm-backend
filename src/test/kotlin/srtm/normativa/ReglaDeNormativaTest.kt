package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.PARAMETRO_TRIBUTARIO
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException

// what a parametro_tributario must be to be written, whoever writes it (srtm.normativa, #187): the double signature,
// the states and that a VIGENTE row is not edited, by any door
class ReglaDeNormativaTest {
    private val regla = ReglaDeNormativa()

    private fun campos(attrs: Map<String, Any?>): List<String> =
        try {
            regla.alInsertar(PARAMETRO_TRIBUTARIO, attrs)
            emptyList()
        } catch (e: ValidationException) {
            e.violations.map { it.field }
        }

    // every field of the object, as the guard hands a stored row: null included
    private fun fila(vararg cambios: Pair<String, Any?>): Map<String, Any?> = CAMPOS.associateWith { null } + VIGENTE + cambios.toMap()

    private fun conflicto(
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ): String = assertThrows(ConflictException::class.java) { regla.alActualizar(PARAMETRO_TRIBUTARIO, guardado, nuevo) }.message!!

    private fun pasa(
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ) = regla.alActualizar(PARAMETRO_TRIBUTARIO, guardado, nuevo)

    @Test
    fun `it rules parametro_tributario and lets the importers write it`() {
        assertEquals(setOf(PARAMETRO_TRIBUTARIO), regla.objetos)
        assertEquals(false, regla.soloDesdeElServicio)
    }

    @Test
    fun `a row with both signatures enters VIGENTE, with or without its estado`() {
        assertEquals(emptyList<String>(), campos(VIGENTE))
        assertEquals(emptyList<String>(), campos(VIGENTE - "estado"))
        assertEquals(emptyList<String>(), campos(VIGENTE + ("estado" to null)))
        assertEquals(emptyList<String>(), campos(VIGENTE + ("valor_numerico" to null) + ("texto" to "Z1")))
    }

    @Test
    fun `a VIGENTE row needs two different people`() {
        assertEquals(listOf("verifico"), campos(VIGENTE + ("verifico" to null)))
        assertEquals(listOf("transcribio"), campos(VIGENTE + ("transcribio" to "")))
        // normativa's signature is a name and a date: the name is the person
        assertEquals(listOf("verifico"), campos(VIGENTE + ("transcribio" to "ANA, 2026-01-05") + ("verifico" to "ana, 2026-01-09")))
    }

    @Test
    fun `a POR_VERIFICAR row has its transcriber and waits for its verifier`() {
        assertEquals(emptyList<String>(), campos(POR_VERIFICAR))
        assertEquals(listOf("verifico"), campos(POR_VERIFICAR + ("verifico" to "BETO")))
        assertEquals(listOf("transcribio"), campos(POR_VERIFICAR + ("transcribio" to null)))
    }

    @Test
    fun `nothing enters ANULADO, nor with an anulación`() {
        assertEquals(listOf("estado"), campos(VIGENTE + ("estado" to "ANULADO")))
        assertEquals(listOf("estado"), campos(VIGENTE + ("estado" to "OTRO")))
        assertEquals(listOf("motivo_anulacion"), campos(VIGENTE + ("motivo_anulacion" to "no")))
    }

    @Test
    fun `a value, a range and a known tipo`() {
        assertEquals(listOf("valor_numerico"), campos(VIGENTE + ("valor_numerico" to null)))
        assertEquals(listOf("vigencia_hasta"), campos(VIGENTE + ("vigencia_hasta" to "2025-12-31")))
        assertEquals(emptyList<String>(), campos(VIGENTE + ("vigencia_hasta" to "2026-01-01")))
        assertEquals(listOf("tipo"), campos(VIGENTE + ("tipo" to "NO_EXISTE")))
        assertEquals(listOf("vigencia_desde"), campos(VIGENTE + ("vigencia_desde" to null)))
        // a year without movable feriados: no value is a statement too
        assertEquals(emptyList<String>(), campos(VIGENTE + ("tipo" to "FERIADOS") + ("clave" to "2026") + ("valor_numerico" to null)))
    }

    @Test
    fun `a POR_VERIFICAR row changes its data and stays so`() {
        val antes = fila(*POR_VERIFICAR.toList().toTypedArray())
        pasa(antes, antes + ("valor_numerico" to "5600.00") + ("transcribio" to "CARLA"))
        // still a valid row
        assertThrows(ValidationException::class.java) { pasa(antes, antes + ("valor_numerico" to null)) }
    }

    @Test
    fun `verifying is the estado and the verifier, by another person, and nothing else`() {
        val antes = fila(*POR_VERIFICAR.toList().toTypedArray())
        pasa(antes, antes + ("estado" to "VIGENTE") + ("verifico" to "BETO"))
        assertTrue(conflicto(antes, antes + ("estado" to "VIGENTE") + ("verifico" to "ana")).contains("transcribió"))
        assertTrue(conflicto(antes, antes + ("estado" to "VIGENTE")).contains("verific"))
        val msg = conflicto(antes, antes + ("estado" to "VIGENTE") + ("verifico" to "BETO") + ("valor_numerico" to "1"))
        assertTrue(msg.contains("valor_numerico"), msg)
    }

    @Test
    fun `a VIGENTE row is not edited - it is annulled with its motivo, who and when, and nothing else`() {
        val antes = fila()
        val msg = conflicto(antes, antes + ("valor_numerico" to "5000.00"))
        assertTrue(msg.contains("VIGENTE") && msg.contains("anúlelo") && msg.contains("valor_numerico"), msg)
        val anulado = antes + ANULACION
        pasa(antes, anulado)
        // a row stored before the estado existed is VIGENTE
        pasa(antes + ("estado" to null), anulado)
        assertTrue(conflicto(antes, anulado + ("motivo_anulacion" to null)).contains("motivo_anulacion"))
        assertTrue(conflicto(antes, anulado + ("anulado_en" to null)).contains("anulado_en"))
        assertTrue(conflicto(antes, anulado + ("valor_numerico" to "1")).contains("valor_numerico"))
        assertTrue(conflicto(antes, antes + ("estado" to "POR_VERIFICAR") + ("verifico" to null)).contains("VIGENTE"))
    }

    @Test
    fun `an open VIGENTE row is closed - its vigencia_hasta, from vigencia_desde on, and nothing else`() {
        val abierto = fila("vigencia_hasta" to null)
        pasa(abierto, abierto + ("vigencia_hasta" to "2026-06-30"))
        pasa(abierto, abierto + ("vigencia_hasta" to "2026-01-01"))
        pasa(abierto + ("estado" to null), abierto + ("vigencia_hasta" to "2026-06-30"))
        assertTrue(conflicto(abierto, abierto + ("vigencia_hasta" to "2025-12-31")).contains("vigencia_desde"))
        assertTrue(conflicto(abierto, abierto + ("vigencia_hasta" to "2026-06-30") + ("valor_numerico" to "1")).contains("valor_numerico"))
        // one already closed is not closed again, nor reopened
        val cerrado = fila()
        assertTrue(conflicto(cerrado, cerrado + ("vigencia_hasta" to "2026-06-30")).contains("cerrada"))
        assertTrue(conflicto(cerrado, cerrado + ("vigencia_hasta" to null)).contains("cerrada"))
    }

    @Test
    fun `the same VIGENTE row written again is no change - the migration's estado included`() {
        val antes = fila()
        pasa(antes, antes)
        pasa(antes + ("estado" to null), antes)
        // core reads a DECIMAL back as a number: 5500.0 is 5500.00
        pasa(antes + ("valor_numerico" to 5500.0), antes + ("valor_numerico" to "5500.00"))
    }

    @Test
    fun `an ANULADO row does not change, and a POR_VERIFICAR one is deleted, not annulled`() {
        val anulado = fila(*ANULACION.toList().toTypedArray())
        assertTrue(conflicto(anulado, anulado + ("estado" to "VIGENTE")).contains("ANULADO"))
        assertTrue(conflicto(anulado, anulado + ("motivo_anulacion" to "otro")).contains("ANULADO"))
        val borrador = fila(*POR_VERIFICAR.toList().toTypedArray())
        assertTrue(conflicto(borrador, borrador + ANULACION).contains("POR_VERIFICAR"))
    }

    @Test
    fun `only a POR_VERIFICAR row is deleted`() {
        regla.alBorrar(PARAMETRO_TRIBUTARIO, fila(*POR_VERIFICAR.toList().toTypedArray()))
        listOf(fila(), fila("estado" to null), fila(*ANULACION.toList().toTypedArray())).forEach {
            val e = assertThrows(ConflictException::class.java) { regla.alBorrar(PARAMETRO_TRIBUTARIO, it) }
            assertTrue(e.message!!.contains("POR_VERIFICAR"), e.message)
        }
        assertThrows(ConflictException::class.java) { regla.alCambiarDeEstado(PARAMETRO_TRIBUTARIO) }
    }

    private companion object {
        val CAMPOS =
            listOf(
                "tipo",
                "clave",
                "vigencia_desde",
                "vigencia_hasta",
                "valor_numerico",
                "texto",
                "norma",
                "fuente",
                "transcribio",
                "verifico",
                "estado",
                "motivo_anulacion",
                "anulado_por",
                "anulado_en"
            )

        // a UIT as the csv brings it: FICTITIOUS figures
        val VIGENTE =
            mapOf(
                "tipo" to "UIT",
                "vigencia_desde" to "2026-01-01",
                "vigencia_hasta" to "2026-12-31",
                "valor_numerico" to "5500.00",
                "norma" to "Norma ficticia de prueba",
                "transcribio" to "ANA",
                "verifico" to "BETO",
                "estado" to "VIGENTE"
            )

        val POR_VERIFICAR = VIGENTE + ("estado" to "POR_VERIFICAR") + ("verifico" to null)

        val ANULACION =
            mapOf(
                "estado" to "ANULADO",
                "motivo_anulacion" to "La cifra se copió mal",
                "anulado_por" to "CARLA",
                "anulado_en" to "2026-10-24T10:00:00Z"
            )
    }
}
