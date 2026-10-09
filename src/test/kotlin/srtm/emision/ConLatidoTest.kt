package srtm.emision

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// the lease a worker keeps while it works (conLatido), in real time with short beats
class ConLatidoTest {
    private val cada = Duration.ofMillis(10)

    @Test
    fun `the work's result when every beat finds it alive`() {
        val latidos = AtomicInteger()

        val hecho = runBlocking { conLatido(cada, { latidos.incrementAndGet() > 0 }) { delay(100).let { "hecho" } } }

        assertEquals("hecho", hecho)
        assertTrue(latidos.get() > 1, "${latidos.get()} latidos")
    }

    @Test
    fun `a beat that finds it lost cancels the work and returns null`() {
        val cancelado = AtomicBoolean(false)

        val hecho =
            runBlocking {
                withTimeout(5_000) {
                    conLatido(cada, { false }) {
                        try {
                            delay(60_000)
                            "hecho"
                        } finally {
                            cancelado.set(true)
                        }
                    }
                }
            }

        assertNull(hecho)
        assertTrue(cancelado.get())
    }

    @Test
    fun `the work throwing Perdido returns null too`() {
        assertNull(runBlocking { conLatido(cada, { true }) { throw Perdido() } })
    }

    @Test
    fun `a beat that throws counts as alive`() {
        val latidos = AtomicInteger()

        val hecho =
            runBlocking {
                conLatido(cada, {
                    latidos.incrementAndGet()
                    throw IllegalStateException("la base, un momento")
                }) { delay(100).let { "hecho" } }
            }

        assertEquals("hecho", hecho)
        assertTrue(latidos.get() > 1, "${latidos.get()} latidos")
    }

    @Test
    fun `a failure of the work goes up`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { conLatido(cada, { true }) { throw IllegalArgumentException("roto") } }
        }
    }

    @Test
    fun `no beat is in flight when it returns, and none runs after`() {
        val empezados = AtomicInteger()
        val terminados = AtomicInteger()
        // a slow beat: the work ends while one is in flight
        val latir: suspend () -> Boolean = {
            empezados.incrementAndGet()
            delay(80)
            terminados.incrementAndGet()
            true
        }

        runBlocking {
            conLatido(cada, latir) { delay(30) }
            assertEquals(empezados.get(), terminados.get(), "un latido sigue en curso")
            val alVolver = empezados.get()
            assertTrue(alVolver >= 1)
            delay(100)
            assertEquals(alVolver, empezados.get(), "un latido corrió después")
        }
    }
}
