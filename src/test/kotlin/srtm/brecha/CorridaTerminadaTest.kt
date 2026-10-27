package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

// #255: the run tells who listens that its corrida is TERMINADA, and what a listener throws never reaches the run
// (it would leave the corrida FALLIDA)
class CorridaTerminadaTest {
    private val evento = CorridaTerminada(UUID.randomUUID(), 2026, UUID.randomUUID().toString())

    @Test
    fun `the event is published with the corrida's organization, year and id`() {
        val publicados = mutableListOf<Any>()
        avisarTerminada({ publicados += it }, evento)
        assertEquals(listOf<Any>(evento), publicados)
    }

    @Test
    fun `a listener that throws does not reach the run`() {
        avisarTerminada({ throw IllegalStateException("el oyente falló") }, evento)
    }
}
