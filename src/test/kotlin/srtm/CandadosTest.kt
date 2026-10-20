package srtm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

// the key of a lock that orders what happens inside one organization: the organization first, so two municipalidades
// do not wait on each other
class CandadosTest {
    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `the key starts with the organization`() {
        assertEquals("$a|AN|2026", Candados.clave(a, "AN|2026"))
        assertEquals("$b|RIS|2026", Candados.clave(b, "RIS|2026"))
    }

    @Test
    fun `the same serie of two organizations is two keys`() {
        assertNotEquals(Candados.clave(a, "AN|2026"), Candados.clave(b, "AN|2026"))
    }

    @Test
    fun `the same organization and serie is the same key`() {
        assertEquals(Candados.clave(a, "AN|2026"), Candados.clave(a, "AN|2026"))
    }
}
