package srtm.reportes

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import srtm.brecha.CorridaTerminada
import wasichai.core.data.RecordService
import kotlin.coroutines.cancellation.CancellationException

// #255: a corrida that ends TERMINADA fills the reports' cache (ReportesService.precalentar), so the first read of its
// tablero does not wait for its rows (~25 s with 30 000 predios). in this component's own scope, never the run's: the
// run hands the event off and goes on, and what fails here is a warning (the corrida stays TERMINADA). as the platform
// of the corrida's organization (asPlatform): the run of a manual corrida carries its caller, who may not read the
// padrón, while the cache serves whoever may (leer asks before reading it); the scope carries no request, so
// asPlatform's tripwire (ADR-039) holds. the cache is this instance's memory: the instance that ran the corrida warms
// its own, and a ClusterLock would keep nothing from being read twice
@Component
class Precalentador internal constructor(
    private val calentar: suspend (CorridaTerminada) -> Unit
) {
    @Autowired
    constructor(reportes: ReportesService, records: RecordService) : this({ e ->
        records.asPlatform(e.organizacion, FUENTE) { reportes.precalentar(e.organizacion, e.anio, e.corrida) }
    })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("reportes-precalentar"))

    @EventListener
    fun alTerminar(evento: CorridaTerminada) {
        lanzar(evento)
    }

    internal fun lanzar(evento: CorridaTerminada): Job =
        scope.launch {
            try {
                calentar(evento)
                log.info("los reportes de la corrida {} ({}) quedaron en caché", evento.corrida, evento.anio)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("no se precalentaron los reportes de la corrida {}: {}", evento.corrida, e.message, e)
            }
        }

    @PreDestroy
    fun detener() = scope.cancel()

    private companion object {
        val log = LoggerFactory.getLogger(Precalentador::class.java)
        const val FUENTE = "srtm:reportes-precalentar"
    }
}
