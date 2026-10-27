package srtm.brecha

import org.slf4j.LoggerFactory
import java.util.UUID

// a corrida reached TERMINADA (#255): its run publishes it (a Spring event) once the corrida says so, for what reads
// its rows (the reportes warm their cache). a listener runs in the run's thread: it must hand its work off and return
data class CorridaTerminada(
    val organizacion: UUID,
    val anio: Int,
    val corrida: String
)

private val log = LoggerFactory.getLogger(CorridaTerminada::class.java)

// what a listener throws is a warning, never the run's: the corrida is already TERMINADA and stays so
internal fun avisarTerminada(
    publicar: (Any) -> Unit,
    evento: CorridaTerminada
) {
    try {
        publicar(evento)
    } catch (e: Exception) {
        log.warn("la corrida de brecha {} terminó, pero un oyente de su fin falló: {}", evento.corrida, e.message, e)
    }
}
