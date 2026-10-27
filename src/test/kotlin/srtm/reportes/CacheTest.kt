package srtm.reportes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

// the reports' cache: what was read, for its vigencia (or with none, until dropped), the most recently used only;
// a missing value read once (#255)
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

    @Test
    fun `without a vigencia a value is kept until its maximo drops it`() {
        val c = Cache<String, Int>(null, 2) { ahora }
        c.put("a", 1)
        ahora = ahora.plus(Duration.ofDays(30))
        assertEquals(1, c.get("a"))
        c.put("b", 2)
        c.put("c", 3)
        assertNull(c.get("a"))
        assertEquals(2, c.get("b"))
    }

    @Test
    fun `quitar drops a value before its time`() {
        val c = Cache<String, Int>(null, 2) { ahora }
        c.put("a", 1)
        c.put("b", 2)
        c.quitar("a")
        assertNull(c.get("a"))
        assertEquals(2, c.get("b"))
    }

    @Test
    fun `a maximo of zero keeps nothing, even without a vigencia`() {
        val c = Cache<String, Int>(null, 0) { ahora }
        c.put("a", 1)
        assertNull(c.get("a"))
    }

    @Test
    fun `obtener reads a missing value once, and the hit after it does not read again`() =
        runBlocking {
            val c = Cache<String, Int>(null, 2) { ahora }
            val lecturas = AtomicInteger()
            assertEquals(1, c.obtener("a") { lecturas.incrementAndGet() })
            assertEquals(1, c.obtener("a") { lecturas.incrementAndGet() })
            assertEquals(1, lecturas.get())
        }

    @Test
    fun `who asks while the value is being read waits for that read, never reading it again`() =
        runBlocking {
            val c = Cache<String, Int>(null, 2) { ahora }
            val lecturas = AtomicInteger()
            val puerta = CompletableDeferred<Unit>()
            val precalentado =
                async {
                    c.obtener("a") {
                        lecturas.incrementAndGet()
                        puerta.await()
                        7
                    }
                }
            yield()
            val pedido = async { c.obtener("a") { lecturas.incrementAndGet() + 100 } }
            yield()
            puerta.complete(Unit)
            assertEquals(7, precalentado.await())
            assertEquals(7, pedido.await())
            assertEquals(1, lecturas.get())
        }

    @Test
    fun `a failed read is neither kept nor shared, the next one reads again`() =
        runBlocking {
            val c = Cache<String, Int>(null, 2) { ahora }
            assertThrows<IllegalStateException> { c.obtener("a") { error("la base no responde") } }
            assertEquals(2, c.obtener("a") { 2 })
        }

    @Test
    fun `who waits on a read that fails reads the value itself`() =
        runBlocking {
            val c = Cache<String, Int>(null, 2) { ahora }
            val puerta = CompletableDeferred<Unit>()
            val primera =
                async {
                    runCatching {
                        c.obtener("a") {
                            puerta.await()
                            error("la base no responde")
                        }
                    }
                }
            yield()
            val segunda = async { c.obtener("a") { 5 } }
            yield()
            puerta.complete(Unit)
            assertEquals(true, primera.await().isFailure)
            assertEquals(5, segunda.await())
        }

    @Test
    fun `with a vigencia obtener reads again once it has passed`() =
        runBlocking {
            val c = Cache<String, Int>(Duration.ofMinutes(10), 2) { ahora }
            val lecturas = AtomicInteger()
            c.obtener("a") { lecturas.incrementAndGet() }
            ahora = ahora.plus(Duration.ofMinutes(10))
            assertEquals(2, c.obtener("a") { lecturas.incrementAndGet() })
        }
}
