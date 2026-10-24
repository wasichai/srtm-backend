package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException

// what an arancel must be to be written, whoever writes it (srtm.normativa, #188): its clave, a valor_m2 not
// negative, the double signature and its states as a parámetro's, and the sincronización with catastro, which any
// estado may change. FICTITIOUS figures
class ReglaDeArancelTest {
    private val regla = ReglaDeArancel()

    private fun campos(attrs: Map<String, Any?>): List<String> =
        try {
            regla.alInsertar(ARANCEL, attrs)
            emptyList()
        } catch (e: ValidationException) {
            e.violations.map { it.field }
        }

    // every field of the object, as the guard hands a stored row: null included
    private fun fila(
        base: Map<String, Any?>,
        vararg cambios: Pair<String, Any?>
    ): Map<String, Any?> = CAMPOS.associateWith { null } + base + cambios.toMap()

    private fun conflicto(
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ): String = assertThrows(ConflictException::class.java) { regla.alActualizar(ARANCEL, guardado, nuevo) }.message!!

    private fun pasa(
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ) = regla.alActualizar(ARANCEL, guardado, nuevo)

    @Test
    fun `it rules arancel alone and lets the importers write it`() {
        assertEquals(setOf(ARANCEL), regla.objetos)
        assertEquals(false, regla.soloDesdeElServicio)
    }

    @Test
    fun `a row with both signatures enters VIGENTE, one with its transcriber alone POR_VERIFICAR`() {
        assertEquals(emptyList<String>(), campos(VIGENTE))
        assertEquals(emptyList<String>(), campos(VIGENTE - "estado"))
        assertEquals(emptyList<String>(), campos(POR_VERIFICAR))
        assertEquals(listOf("verifico"), campos(VIGENTE + ("verifico" to "ana, 2026-01-09")))
        assertEquals(listOf("verifico"), campos(POR_VERIFICAR + ("verifico" to "BETO")))
        assertEquals(listOf("estado"), campos(VIGENTE + ("estado" to "ANULADO")))
        assertEquals(listOf("motivo_anulacion"), campos(VIGENTE + ("motivo_anulacion" to "no")))
    }

    @Test
    fun `its clave is id_aranc and anio, its valor_m2 not negative - a 0 is a value`() {
        assertEquals(emptyList<String>(), campos(VIGENTE + ("valor_m2" to 0)))
        assertEquals(listOf("valor_m2"), campos(VIGENTE + ("valor_m2" to "-0.01")))
        assertEquals(listOf("valor_m2"), campos(VIGENTE + ("valor_m2" to null)))
        assertEquals(listOf("clave"), campos(VIGENTE + ("clave" to "A-01|2025")))
        assertEquals(listOf("clave"), campos(VIGENTE + ("clave" to null)))
        assertEquals(listOf("id_aranc"), campos(VIGENTE + ("id_aranc" to " ") + ("clave" to " |2026")))
        assertEquals(listOf("anio"), campos(VIGENTE + ("anio" to null) + ("clave" to "A-01|")))
    }

    @Test
    fun `a new row is not born synchronized`() {
        assertEquals(listOf("sincronizado_en"), campos(VIGENTE + ("sincronizado_en" to "2026-10-24T10:00:00Z")))
        assertEquals(listOf("error_sincronizacion"), campos(POR_VERIFICAR + ("error_sincronizacion" to "catastro no responde")))
    }

    @Test
    fun `a VIGENTE row is not edited - it is annulled with its motivo, who and when - and there is no closing`() {
        val vigente = fila(VIGENTE)
        assertTrue(conflicto(vigente, vigente + ("valor_m2" to "13.00")).contains("Un arancel VIGENTE no se edita"))
        assertTrue(conflicto(vigente, vigente + ("anio" to 2027) + ("clave" to "A-01|2027")).contains("no se edita"))
        pasa(vigente, vigente + ANULADO)
        assertTrue(conflicto(vigente, vigente + ANULADO + ("anulado_por" to null)).contains("falta: anulado_por"))
        assertTrue(conflicto(vigente, vigente + ANULADO + ("valor_m2" to "1")).contains("cambia también: valor_m2"))
        assertTrue(conflicto(vigente, vigente + ("estado" to "POR_VERIFICAR")).contains("no vuelve a POR_VERIFICAR"))
        // a write that changes nothing passes
        pasa(vigente, vigente)
        pasa(vigente, vigente + ("valor_m2" to java.math.BigDecimal("12.500")))
    }

    @Test
    fun `the sincronizacion changes in any estado, alone or with a transition`() {
        val sync = mapOf("sincronizado_en" to "2026-10-24T10:00:00Z", "error_sincronizacion" to null)
        val error = mapOf("sincronizado_en" to null, "error_sincronizacion" to "catastro no responde")
        val vigente = fila(VIGENTE)
        pasa(vigente, vigente + sync)
        pasa(vigente + sync, vigente + error)
        val anulado = fila(VIGENTE + ANULADO)
        pasa(anulado, anulado + sync)
        pasa(anulado + sync, anulado + error)
        val porVerificar = fila(POR_VERIFICAR)
        pasa(porVerificar, porVerificar + error)
        // with a transition: annulled and synced in one write; verified and synced
        pasa(vigente, vigente + ANULADO + sync)
        pasa(porVerificar + sync, porVerificar + sync + ("valor_m2" to "14.00"))
        pasa(porVerificar, porVerificar + ("estado" to "VIGENTE") + ("verifico" to "BETO") + sync)
        // but nothing else
        assertTrue(conflicto(anulado, anulado + sync + ("valor_m2" to "1")).contains("ANULADO no cambia"))
    }

    @Test
    fun `a POR_VERIFICAR row is corrected, verified by another person, and deleted - and only srtm verifies it`() {
        val porVerificar = fila(POR_VERIFICAR)
        pasa(porVerificar, porVerificar + ("valor_m2" to "14.00"))
        val corregido = assertThrows(ValidationException::class.java) { pasa(porVerificar, porVerificar + ("clave" to "OTRA|2026")) }
        assertEquals(listOf("clave"), corregido.violations.map { it.field })
        val verificado = porVerificar + ("estado" to "VIGENTE") + ("verifico" to "BETO")
        pasa(porVerificar, verificado)
        assertTrue(conflicto(porVerificar, verificado + ("verifico" to "ana")).contains("doble firma"))
        assertTrue(conflicto(porVerificar, verificado + ("valor_m2" to "1")).contains("cambia también: valor_m2"))
        assertTrue(regla.sinLaMarca(ARANCEL, porVerificar, verificado)!!.contains("/api/srtm/normativa/aranceles/verificar"))
        assertNull(regla.sinLaMarca(ARANCEL, porVerificar, porVerificar + ("valor_m2" to "14.00")))
        assertNull(regla.sinLaMarca(ARANCEL, fila(VIGENTE), fila(VIGENTE) + ANULADO))

        regla.alBorrar(ARANCEL, porVerificar)
        assertTrue(assertThrows(ConflictException::class.java) { regla.alBorrar(ARANCEL, fila(VIGENTE)) }.message!!.contains("se anula"))
        assertThrows(ConflictException::class.java) { regla.alBorrar(ARANCEL, fila(VIGENTE + ANULADO)) }
    }

    private companion object {
        val CAMPOS =
            listOf(
                "clave",
                "id_aranc",
                "anio",
                "valor_m2",
                "cod_via",
                "cuadra",
                "lado",
                "norma",
                "fuente",
                "transcribio",
                "verifico",
                "estado",
                "motivo_anulacion",
                "anulado_por",
                "anulado_en",
                "sincronizado_en",
                "error_sincronizacion"
            )

        val VIGENTE =
            mapOf(
                "clave" to "A-01|2026",
                "id_aranc" to "A-01",
                "anio" to 2026,
                "valor_m2" to "12.50",
                "cod_via" to "V-001",
                "cuadra" to "1",
                "lado" to "I",
                "norma" to "Ordenanza ficticia de prueba",
                "transcribio" to "ANA, 2026-01-05",
                "verifico" to "BETO, 2026-01-09",
                "estado" to "VIGENTE"
            )

        val POR_VERIFICAR = VIGENTE + mapOf("estado" to "POR_VERIFICAR", "verifico" to null)

        val ANULADO =
            mapOf("estado" to "ANULADO", "motivo_anulacion" to "La cifra se copió mal", "anulado_por" to "beto", "anulado_en" to "2026-02-01T10:00:00Z")
    }
}
