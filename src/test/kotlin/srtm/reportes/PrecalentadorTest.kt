package srtm.reportes

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.brecha.CorridaTerminada
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

// #255: a TERMINADA corrida warms the reports' cache in the background; what fails there never reaches the run
class PrecalentadorTest {
    private val evento = CorridaTerminada(UUID.randomUUID(), 2026, UUID.randomUUID().toString())

    @Test
    fun `a TERMINADA corrida warms its organization's, year's and corrida's cache`() {
        val calentadas = CopyOnWriteArrayList<CorridaTerminada>()
        val p = Precalentador { calentadas += it }
        runBlocking { p.lanzar(evento).join() }
        assertEquals(listOf(evento), calentadas)
        p.detener()
    }

    @Test
    fun `a warm-up that throws is only a warning, the listener returns and its job ends without failing`() {
        val p = Precalentador { error("la base no responde") }
        val trabajo = p.lanzar(evento)
        runBlocking { trabajo.join() }
        assertTrue(trabajo.isCompleted && !trabajo.isCancelled)
        // the listener itself returns at once, whatever the warm-up does
        p.alTerminar(evento)
        p.detener()
    }
}
