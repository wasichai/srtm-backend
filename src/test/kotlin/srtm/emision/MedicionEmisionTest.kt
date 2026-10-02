package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Locale

// the documents per second of the masiva's log lines
class MedicionEmisionTest {
    @Test
    fun `documents per second with one decimal`() {
        assertEquals("10.0", documentosPorSegundo(20, Duration.ofSeconds(2)))
        assertEquals("2.5", documentosPorSegundo(5, Duration.ofSeconds(2)))
        assertEquals("33.3", documentosPorSegundo(100, Duration.ofSeconds(3)))
    }

    @Test
    fun `a point whatever the default locale`() {
        val antes = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-PE"))
            assertEquals("2.5", documentosPorSegundo(5, Duration.ofSeconds(2)))
        } finally {
            Locale.setDefault(antes)
        }
    }

    @Test
    fun `no time to measure is not a division by zero`() {
        assertEquals("0.0", documentosPorSegundo(0, Duration.ZERO))
        assertEquals("3000.0", documentosPorSegundo(3, Duration.ofNanos(10)))
    }
}
