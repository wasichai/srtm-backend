package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// the same cases are in srtm-ui (errorDocumento): both sides must accept and refuse the same numbers
class DocumentoTest {
    @Test
    fun `a dni is eight digits`() {
        assertNull(errorDocumento("DNI", "43554564"))
        assertNull(errorDocumento("DNI", " 43554564 "))
        assertEquals("El DNI tiene 8 dígitos", errorDocumento("DNI", "4355456"))
        assertEquals("El DNI tiene 8 dígitos", errorDocumento("DNI", "435545641"))
        assertEquals("El DNI tiene 8 dígitos", errorDocumento("DNI", "4355456A"))
        assertEquals("Este dato es obligatorio", errorDocumento("DNI", null))
        assertEquals("Este dato es obligatorio", errorDocumento("DNI", "  "))
    }

    @Test
    fun `a ruc is eleven digits with sunat's prefix and check digit`() {
        // sunat's own ruc, and a persona natural's (10 + dni + check digit)
        assertNull(errorDocumento("RUC", "20131312955"))
        assertNull(errorDocumento("RUC", "10460278975"))
        assertEquals("El dígito verificador del RUC no es válido", errorDocumento("RUC", "20131312954"))
        assertEquals("El RUC tiene 11 dígitos y empieza con 10, 15, 16, 17 o 20", errorDocumento("RUC", "30131312955"))
        assertEquals("El RUC tiene 11 dígitos y empieza con 10, 15, 16, 17 o 20", errorDocumento("RUC", "2013131295"))
        assertEquals("Este dato es obligatorio", errorDocumento("RUC", null))
    }

    @Test
    fun `any other document is up to twelve letters or digits`() {
        assertNull(errorDocumento("CARNET DE EXTRANJERIA", "001234567"))
        assertNull(errorDocumento("PASAPORTE", "AB1234567890"))
        assertNull(errorDocumento("SUCESION", "S123"))
        assertEquals("Hasta 12 letras o dígitos", errorDocumento("PASAPORTE", "AB12345678901"))
        assertEquals("Hasta 12 letras o dígitos", errorDocumento("CARNET DE EXTRANJERIA", "AB-123"))
        assertEquals("Este dato es obligatorio", errorDocumento("PASAPORTE", ""))
    }

    @Test
    fun `sin documento takes no number, and no tipo is left to the model's required tipo`() {
        assertNull(errorDocumento("SIN DOCUMENTO", null))
        assertNull(errorDocumento("SIN DOCUMENTO", "cualquier cosa"))
        assertNull(errorDocumento(null, "x"))
    }

    @Test
    fun `the number is stored trimmed, and none for sin documento`() {
        assertEquals("43554564", numeroDocumento("DNI", " 43554564 "))
        assertNull(numeroDocumento("DNI", "  "))
        assertNull(numeroDocumento("SIN DOCUMENTO", "00012"))
        assertNull(numeroDocumento("SIN DOCUMENTO", null))
    }
}
