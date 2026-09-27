package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException

class DomicilioFiscalTest {
    private val fiscal = Domicilio(id = "1", tipoDomicilio = "FISCAL", estado = "ACTIVO")
    private val real = Domicilio(id = "2", tipoDomicilio = "REAL", estado = "ACTIVO")

    private fun field(block: () -> Unit): String = assertThrows<ValidationException>(block).violations.single().field

    @Test
    fun `the first domicilio is the active fiscal one`() {
        assertDoesNotThrow { exigirFiscal(otros = emptyList(), antes = null, despues = fiscal) }
        assertEquals("tipo_domicilio", field { exigirFiscal(emptyList(), null, real) })
        assertEquals("estado", field { exigirFiscal(emptyList(), null, fiscal.copy(estado = "INACTIVO")) })
    }

    @Test
    fun `no second active fiscal domicilio joins the first`() {
        val error = assertThrows<ValidationException> { exigirFiscal(listOf(fiscal), null, fiscal.copy(id = null)) }
        assertEquals("tipo_domicilio", error.violations.single().field)
        assertEquals("ya tiene otro domicilio fiscal activo: actualice ese domicilio", error.violations.single().message)
        assertEquals("tipo_domicilio", field { exigirFiscal(listOf(fiscal), real, real.copy(tipoDomicilio = "FISCAL")) })
        // an inactive fiscal one is history, and other kinds come freely
        assertDoesNotThrow { exigirFiscal(listOf(fiscal), null, fiscal.copy(id = null, estado = "INACTIVO")) }
        assertDoesNotThrow { exigirFiscal(listOf(fiscal), null, real) }
    }

    @Test
    fun `the only active fiscal domicilio stays fiscal, active and there`() {
        assertEquals("tipo_domicilio", field { exigirFiscal(listOf(real), fiscal, despues = null) })
        assertEquals("estado", field { exigirFiscal(listOf(real), fiscal, fiscal.copy(estado = "INACTIVO")) })
        assertEquals("tipo_domicilio", field { exigirFiscal(listOf(real), fiscal, fiscal.copy(tipoDomicilio = "REAL")) })
        // its address changes freely
        assertDoesNotThrow { exigirFiscal(listOf(real), fiscal, fiscal.copy(numero = "240")) }
        val error = assertThrows<ValidationException> { exigirFiscal(emptyList(), fiscal, null) }
        assertEquals("No se puede eliminar el único domicilio fiscal activo", error.message)
    }

    @Test
    fun `with another active fiscal one left, a fiscal one may step down or go`() {
        val otro = fiscal.copy(id = "3")
        assertDoesNotThrow { exigirFiscal(listOf(otro), fiscal, null) }
        assertDoesNotThrow { exigirFiscal(listOf(otro), fiscal, fiscal.copy(tipoDomicilio = "REAL")) }
        // but two cannot both stay active and fiscal
        assertEquals("tipo_domicilio", field { exigirFiscal(listOf(otro), fiscal, fiscal.copy(numero = "240")) })
    }

    @Test
    fun `a contribuyente without a fiscal domicilio keeps what it has until it registers one`() {
        // before the rule: only a real one. it can still be edited or removed
        assertDoesNotThrow { exigirFiscal(emptyList(), real, real.copy(numero = "10")) }
        assertDoesNotThrow { exigirFiscal(emptyList(), real, null) }
        assertEquals("tipo_domicilio", field { exigirFiscal(listOf(real), null, real.copy(id = null)) })
    }
}
