package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

// the keys the emisiones have in the almacén: the layout every implementation and every later step rely on
class ClavesEmisionTest {
    private val id = UUID.fromString("0b9a4c1e-5d2f-4a8e-9c3b-7e6d1f2a3b4c")

    @Test
    fun `the keys of an emission are built from its id`() {
        assertEquals("emision-$id/", prefijoEmision(id))
        assertEquals("emision-$id/emision-2026-$id.pdf", claveResultado(id, 2026, FormatoEmision.PDF))
        assertEquals("emision-$id/emision-2026-$id.zip", claveResultado(id, 2026, FormatoEmision.ZIP))
        assertEquals("emision-$id/parte-00001.pdf", claveParte(id, 1, FormatoEmision.PDF))
        assertEquals("emision-$id/parte-12345.zip", claveParte(id, 12345, FormatoEmision.ZIP))
    }

    @Test
    fun `the keys of an emission are valid keys`() {
        listOf(claveResultado(id, 2026, FormatoEmision.PDF), claveParte(id, 1, FormatoEmision.ZIP)).forEach { exigirClave(it) }
        exigirClave(prefijoEmision(id), prefijo = true)
    }
}
