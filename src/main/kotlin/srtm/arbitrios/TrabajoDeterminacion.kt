package srtm.arbitrios

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import srtm.emision.EmisionProperties
import srtm.emision.IdentidadEmision
import srtm.emision.LoteDeTrabajo
import srtm.emision.Perdido
import srtm.emision.TrabajoPorLotes
import srtm.emision.conLatido
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private const val TERMINADA = "TERMINADA"
private const val FALLIDO = "FALLIDO"

internal const val SIN_USUARIO_DETERMINACION = "el usuario que lanzó la determinación ya no existe o está deshabilitado"

// the masiva de arbitrios' lotes, as the emission's workers take them (srtm.emision.TrabajoPorLotes), in turns with
// the emission's. a lote is processed as the user who created it (IdentidadEmision), with the year's ordinance,
// servicios, parameters and usos read once: each of its predios is determined and written like a POST of the portal
// would (ArbitriosService), its own transaction; one that cannot be goes to the lote's errores, by its code, and the
// rest go on. a cuota that already existed is not written again, so a lote taken over after its lease expired is safe
// to start over. every write of the lote is guarded by its take (MaquinaLotes)
@Component
class TrabajoDeterminacion(
    private val maquina: MaquinaDeterminacion,
    private val arbitrios: ArbitriosService,
    private val identidad: IdentidadEmision,
    private val config: EmisionProperties,
    private val db: DatabaseClient
) : TrabajoPorLotes {
    override suspend fun mantener(lease: Duration) {
        for ((_, id) in maquina.estado.fallarAbandonadas(lease)) log.warn("la determinación masiva {} no tenía quien la corriera: FALLIDA", id)
        cerrar()
    }

    override suspend fun procesarUno(
        instancia: String,
        lease: Duration
    ): Boolean {
        val lote = maquina.lotes.tomar(instancia, lease) ?: return false
        procesar(lote, instancia, lease)
        cerrar(lote.organizacion, lote.trabajo)
        return true
    }

    private suspend fun procesar(
        lote: LoteDeTrabajo,
        instancia: String,
        lease: Duration
    ) {
        val predios = prediosDe(lote.carga)
        if (lote.intentos > config.intentos) {
            fallar(lote, instancia, predios, "lote fallido tras ${lote.intentos - 1} intentos")
            return
        }
        val autenticacion =
            try {
                lote.creadoPor?.let { identidad.de(it) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { maquina.lotes.liberar(lote, instancia) } }
                throw e
            } catch (e: Throwable) {
                log.error("no se pudo leer quién creó el lote {} de la determinación masiva {}", lote.numero, lote.trabajo, e)
                maquina.lotes.liberar(lote, instancia)
                return
            }
        if (autenticacion == null) {
            fallar(lote, instancia, predios, SIN_USUARIO_DETERMINACION)
            return
        }
        val observacion = lote.delTrabajo["observacion"] ?: "Determinación masiva de arbitrios ${lote.anio}"
        try {
            val hecho =
                withContext(ReactiveSecurityContextHolder.withAuthentication(autenticacion).asCoroutineContext()) {
                    conLatido(lease.dividedBy(4), { maquina.lotes.latir(lote, instancia) }) {
                        // once per lote, as the lote's user: every predio of the lote uses them
                        val contexto = arbitrios.contexto(lote.anio)
                        val hoy = LocalDate.now()
                        var generadas = 0
                        val errores = mutableListOf<ErrorPredio>()
                        predios.forEachIndexed { i, p ->
                            try {
                                generadas += arbitrios.determinarEnLote(contexto, p.id, observacion, hoy).size
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: FaltanArbitrios) {
                                errores += ErrorPredio(p.codigo, e.faltan.joinToString("; "))
                            } catch (e: Throwable) {
                                errores += ErrorPredio(p.codigo, e.message ?: e.javaClass.simpleName)
                            }
                            if (!maquina.lotes.avanzar(lote, instancia, i + 1, erroresPrediosJson(errores))) throw Perdido()
                        }
                        generadas to errores
                    }
                }
            if (hecho != null) maquina.lotes.terminar(lote, instancia, predios.size, hecho.first, erroresPrediosJson(hecho.second))
        } catch (e: CancellationException) {
            // the group stops: the next taker starts it over without waiting for the lease
            withContext(NonCancellable) { runCatching { maquina.lotes.liberar(lote, instancia) } }
            throw e
        } catch (e: Throwable) {
            // the next taker tries again, up to srtm.emision.intentos
            log.error("el lote {} de la determinación masiva {} falló en el intento {}", lote.numero, lote.trabajo, lote.intentos, e)
            maquina.lotes.liberar(lote, instancia)
        }
    }

    // the lote could not be processed: FALLIDO, with all its predios among its errores
    private suspend fun fallar(
        lote: LoteDeTrabajo,
        instancia: String,
        predios: List<PredioALote>,
        mensaje: String
    ) {
        if (!maquina.lotes.fallar(lote, instancia, erroresPrediosJson(predios.map { ErrorPredio(it.codigo, mensaje) }))) return
        log.warn("el lote {} de la determinación masiva {} queda FALLIDO: {}", lote.numero, lote.trabajo, mensaje)
    }

    // the jobs EN_PROCESO whose lotes all ended (none with no lotes at all): TERMINADA, with what their lotes counted.
    // a lote FALLIDO counts its predios as processed: they are among the errores. two closers at once: the transition
    // applies once, from EN_PROCESO only. `organizacion` and `id` narrow the search
    internal suspend fun cerrar(
        organizacion: UUID? = null,
        id: UUID? = null
    ) {
        for (t in maquina.estado.tablas(organizacion)) {
            val e = t.trabajos
            val l = t.lotes
            var spec =
                db
                    .sql(
                        """
                        SELECT x.id FROM ${e.tabla} x
                        WHERE x.${e.columna("estado")} = 'EN_PROCESO' AND x.${e.columna("latido")} IS NOT NULL
                          AND NOT EXISTS (
                            SELECT 1 FROM ${l.tabla} y WHERE y.${l.columna("determinacion")} = x.id AND y.${l.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO')
                          )
                          ${if (id != null) "AND x.id = :id" else ""}
                        ORDER BY x.created_at
                        """.trimIndent()
                    )
            if (id != null) spec = spec.bind("id", id)
            val terminadas =
                spec
                    .map { row, _ -> row.get("id", UUID::class.java)!! }
                    .all()
                    .asFlow()
                    .toList()
            for (job in terminadas) {
                val partes = maquina.lotes.partes(t.organizacion, job)
                val errores = partes.flatMap { erroresPrediosDe(it.errores) }
                val procesados = partes.sumOf { if (it.estado == FALLIDO) erroresPrediosDe(it.errores).size else it.procesados }
                val generadas = partes.sumOf { it.producidos }
                val cerrada =
                    maquina.estado.transicion(
                        e,
                        job,
                        "e.${e.columna("estado")} = :en_proceso",
                        mapOf("en_proceso" to "EN_PROCESO"),
                        mapOf(
                            "estado" to TERMINADA,
                            "procesados" to procesados,
                            "generadas" to generadas,
                            "errores" to erroresPrediosJson(errores),
                            "terminado" to OffsetDateTime.now(ZoneOffset.UTC)
                        ),
                        latir = false,
                        usuario = null
                    )
                if (cerrada) log.info("determinación masiva {}: {} predios, {} cuotas, {} con errores", job, procesados, generadas, errores.size)
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(TrabajoDeterminacion::class.java)
    }
}
