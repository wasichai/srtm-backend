package srtm.reportes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

// the reports' cache: what was read, for its vigencia, the most recently used only
class CacheTest {
    private var ahora = Instant.parse("2026-10-27T10:00:00Z")

    @Test
    fun `a value is kept for its vigencia`() {
        val c = Cache<String, Int>(Duration.ofMinutes(10), 4) { ahora }
        c.put("a", 1)
        ahora = ahora.plus(Duration.ofMinutes(9))
        assertEquals(1, c.get("a"))
        ahora = ahora.plus(Duration.ofMinutes(1))
        assertNull(c.get("a"))
    }

    @Test
    fun `past its maximo the least recently used is dropped`() {
        val c = Cache<String, Int>(Duration.ofMinutes(10), 2) { ahora }
        c.put("a", 1)
        c.put("b", 2)
        c.get("a")
        c.put("c", 3)
        assertEquals(1, c.get("a"))
        assertNull(c.get("b"))
        assertEquals(3, c.get("c"))
    }

    @Test
    fun `a vigencia of zero keeps nothing`() {
        val c = Cache<String, Int>(Duration.ZERO, 2) { ahora }
        c.put("a", 1)
        assertNull(c.get("a"))
    }
}
