package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException

class PersonasTest {
    @Test
    fun `a list's code is one more than the highest of its parent, three digits`() {
        assertEquals("001", siguienteCodigoLista(emptyList()))
        // a gap, a row from before the codes and a stray text do not count
        assertEquals("004", siguienteCodigoLista(listOf("001", "003", null, "X")))
        assertEquals("1000", siguienteCodigoLista(listOf("999")))
    }

    @Test
    fun `with RUC the razon social is required, not the names`() {
        assertEquals("razon_social", faltante { validarNombre("RUC", null, "DUBERLI") })
        assertEquals("razon_social", faltante { validarNombre("RUC", "  ", null) })
        assertDoesNotThrow { validarNombre("RUC", "INVERSIONES PERENE SAC", null) }
    }

    @Test
    fun `anyone else needs names`() {
        assertEquals("nombres", faltante { validarNombre("DNI", "INVERSIONES PERENE SAC", " ") })
        assertEquals("nombres", faltante { validarNombre(null, null, null) })
        assertDoesNotThrow { validarNombre("DNI", null, "DUBERLI") }
    }

    private fun faltante(check: () -> Unit): String =
        assertThrows<ValidationException>(check)
            .violations
            .single()
            .field
}
