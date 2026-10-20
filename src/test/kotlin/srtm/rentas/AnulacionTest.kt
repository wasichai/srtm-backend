package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import java.time.LocalDate

// an annulled declaración stays on record but counts nothing; a record with dependents is not deleted
class AnulacionTest {
    private val hoy = LocalDate.of(2026, 9, 27)

    @Test
    fun `a declaration is vigente unless annulled, the imported ones without estado included`() {
        assertTrue(vigente(Declaracion()))
        assertTrue(vigente(Declaracion(estado = VIGENTE)))
        assertFalse(vigente(Declaracion(estado = ANULADA)))
        assertFalse(vigente(Declaracion(estado = SUSTITUIDA)))
    }

    @Test
    fun `annulling is a descargo, with its motivo and date`() {
        val d = anulada(Declaracion(estado = VIGENTE, motivo = "INSCRIPCION"), "  duplicada por error ", hoy)
        assertEquals(ANULADA, d.estado)
        assertEquals("DESCARGO", d.motivo)
        assertEquals("duplicada por error", d.motivoAnulacion)
        assertEquals(hoy, d.fechaAnulacion)
    }

    @Test
    fun `annulling needs a motivo`() {
        val e = assertThrows<ValidationException> { anulada(Declaracion(), " ", hoy) }
        assertEquals("motivo_anulacion", e.violations.single().field)
    }

    @Test
    fun `an annulled declaration is not changed again, nor annulled twice`() {
        val anulada = Declaracion(estado = ANULADA)
        assertEquals("estado", assertThrows<ValidationException> { modificable(anulada) }.violations.single().field)
        assertEquals("estado", assertThrows<ValidationException> { anulada(anulada, "otra vez", hoy) }.violations.single().field)
        modificable(Declaracion())
    }

    @Test
    fun `a predio or contribuyente with declarations is not deleted`() {
        assertNull(bajaConDeclaraciones("El predio", 0))
        assertEquals("El predio tiene 1 declaración jurada: no se puede eliminar", bajaConDeclaraciones("El predio", 1))
        assertEquals("El contribuyente tiene 3 declaraciones juradas: no se puede eliminar", bajaConDeclaraciones("El contribuyente", 3))
    }

    @Test
    fun `a predio or contribuyente with cuotas de arbitrio or their anulaciones is not deleted`() {
        assertNull(bajaConCuotas("El predio", 0, 0))
        assertEquals("El predio tiene 1 cuota de arbitrio: no se puede eliminar, una cuota no se borra nunca", bajaConCuotas("El predio", 1))
        assertEquals(
            "El contribuyente tiene 24 cuotas de arbitrio: no se puede eliminar, una cuota no se borra nunca",
            bajaConCuotas("El contribuyente", 24)
        )
        assertEquals(
            "El predio tiene 2 anulaciones de cuotas de arbitrio: no se puede eliminar, una anulación no se borra nunca",
            bajaConCuotas("El predio", 0, 2)
        )
    }

    @Test
    fun `a declaration with lists is annulled instead of deleted`() {
        assertNull(bajaDeDeclaracion(emptyList()))
        assertEquals("La declaración tiene otros frentes: anúlela en lugar de eliminarla", bajaDeDeclaracion(listOf(OTRO_FRENTE)))
        assertEquals(
            "La declaración tiene transferentes, niveles de construcción y obras complementarias: anúlela en lugar de eliminarla",
            bajaDeDeclaracion(listOf(TRANSFERENTE, NIVEL_CONSTRUCCION, OBRA_COMPLEMENTARIA))
        )
    }
}
