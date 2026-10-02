package srtm.arbitrios

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Service
import srtm.emision.EmisionProperties
import srtm.emision.TrabajadoresEmision
import srtm.emision.conLatido
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Registros
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.UnauthorizedException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private const val PENDIENTE = "PENDIENTE"
private const val EN_PROCESO = "EN_PROCESO"
private val ACTIVAS = setOf(PENDIENTE, EN_PROCESO, "ENSAMBLANDO")

// the masiva de arbitrios of a year, on the emission's machinery (wasichai/srtm-backend#53, #54): a POST checks what the
// year needs, leaves a PENDIENTE job and returns; in the background, as the caller, the predios with declarations of
// the year are cut in lotes of srtm.emision.lote and the job goes EN_PROCESO. the workers of every instance take its
// lotes in turns with the emission's (TrabajoDeterminacion): each predio is determined as the caller, and the job ends
// TERMINADA when its lotes have. one active masiva per organization and year
@Service
class DeterminacionMasivaService(
    private val registros: Registros,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser,
    private val arbitrios: ArbitriosService,
    private val maquina: MaquinaDeterminacion,
    private val trabajadores: TrabajadoresEmision,
    private val config: EmisionProperties
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 202: the job PENDIENTE. before anything exists: 403 without the permissions its preparation and its lotes use,
    // 400 for the observación, 422 with what the year lacks, 409 if one of the year runs
    suspend fun lanzar(pedido: PedidoDeterminacion): DeterminacionMasiva {
        val llamante =
            ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication
                ?: throw UnauthorizedException("Authentication required")
        val usuario = exigirPermisos()
        val anio = pedido.anio ?: LocalDate.now().year
        if (anio !in
            ArbitriosService.ANIOS
        ) {
            throw ValidationException("El año no es válido", "anio", "de ${ArbitriosService.ANIOS.first} a ${ArbitriosService.ANIOS.last}")
        }
        val observacion = Observacion.de(pedido.observacion)
        val faltan = arbitrios.parametros(anio).faltan
        if (faltan.isNotEmpty()) throw FaltanArbitrios(anio, faltan)
        val organizacion = usuario.organizationId
        if (maquina.estado.activas(organizacion, anio).isNotEmpty()) throw ConflictException(enCurso(anio))
        val job =
            registros.create(
                DETERMINACION_MASIVA,
                RegistroDeterminacion::class.java,
                mapOf(
                    "anio" to anio,
                    "estado" to PENDIENTE,
                    "total" to 0,
                    "procesados" to 0,
                    "generadas" to 0,
                    "errores" to erroresPrediosJson(emptyList()),
                    "observacion" to observacion,
                    "iniciado" to Instant.now().toString(),
                    // the preparer's lease, from the start: a job whose preparer dies is failed by the workers
                    "latido" to Instant.now().toString()
                )
            )
        val id = UUID.fromString(job.id)
        // the lease again by the db's clock, the one fallarAbandonadas compares with
        maquina.estado.latirPreparacion(organizacion, id)
        // two POSTs at once both saw none: the oldest goes on, the other one goes
        val primera =
            maquina.estado
                .activas(organizacion, anio)
                .minWithOrNull(compareBy({ it.second }, { it.first }))
                ?.first
        if (primera != null && primera != id) {
            descartar(organizacion, id, usuario.userId)
            throw ConflictException(enCurso(anio))
        }
        scope.launch(ReactiveSecurityContextHolder.withAuthentication(llamante).asCoroutineContext()) {
            preparar(organizacion, id, anio, usuario.userId)
        }
        return determinacionDe(job)
    }

    suspend fun lista(anio: Int?): List<DeterminacionMasiva> =
        registros
            .all(
                DETERMINACION_MASIVA,
                RegistroDeterminacion::class.java,
                filters = anio?.let { mapOf("anio" to it.toString()) } ?: emptyMap(),
                sort = "created_at",
                descending = true
            ).map { conAvance(it) }

    suspend fun get(id: UUID): DeterminacionMasiva = conAvance(registros.get(DETERMINACION_MASIVA, RegistroDeterminacion::class.java, id))

    // the job (the caller may delete it: checked before anything is touched) and its lotes. one still running is
    // cancelled: its lotes go FALLIDO, and the workers that had them stop at their next write. the cuotas it wrote stay:
    // a cuota is never deleted
    suspend fun eliminar(id: UUID) {
        val job = get(id)
        val usuario = currentUser.require()
        currentUser.requirePermission(usuario, Actions.DELETE, metadata.definitionOf(DETERMINACION_MASIVA).obj.id)
        val organizacion = usuario.organizationId
        if (job.estado in ACTIVAS) maquina.lotes.cancelar(organizacion, id)
        maquina.lotes.borrar(organizacion, id)
        registros.delete(DETERMINACION_MASIVA, id)
    }

    // while it runs, the cuotas its ended lotes generated; once ended, the job's own count
    private suspend fun conAvance(r: RegistroDeterminacion): DeterminacionMasiva {
        if (r.estado != EN_PROCESO) return determinacionDe(r)
        val organizacion = currentUser.require().organizationId
        return determinacionDe(r, maquina.lotes.partes(organizacion, UUID.fromString(r.id)).sumOf { it.producidos })
    }

    private fun enCurso(anio: Int) = "Ya hay una determinación masiva de arbitrios de $anio en curso: espere a que termine"

    // the job of a POST that lost the race: deleted, or failed if the caller may not delete it
    private suspend fun descartar(
        organizacion: UUID,
        id: UUID,
        usuario: UUID
    ) {
        try {
            registros.delete(DETERMINACION_MASIVA, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            maquina.estado.fallar(organizacion, id, "otra determinación masiva del año empezó a la vez", usuario)
        }
    }

    // the preparation creates the lotes as the caller, its transitions of the job are the system's (audited as the
    // caller's), and its lotes write cuotas as the caller: a caller who may not do all of it is refused first
    private suspend fun exigirPermisos(): AuthenticatedUser {
        val usuario = currentUser.require()
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(DETERMINACION_MASIVA).obj.id)
            currentUser.requirePermission(usuario, Actions.UPDATE, metadata.definitionOf(DETERMINACION_MASIVA).obj.id)
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(DETERMINACION_LOTE).obj.id)
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(CUOTA_ARBITRIO).obj.id)
        } catch (_: ForbiddenException) {
            throw ForbiddenException(
                "Lanzar una determinación masiva exige permiso de creación y de edición sobre $DETERMINACION_MASIVA, " +
                    "y de creación sobre $DETERMINACION_LOTE y $CUOTA_ARBITRIO: el job guarda su avance en sus lotes, que escriben las cuotas"
            )
        }
        return usuario
    }

    // in the background, as the caller, while the lease is renewed: the predios with declarations of the year (an
    // annulled one too: it may cover months before a sale), by code, cut in lotes, and the job EN_PROCESO with its
    // total. a failure leaves it FALLIDA; the lotes of a job that does not start are cancelled
    private suspend fun preparar(
        organizacion: UUID,
        id: UUID,
        anio: Int,
        usuario: UUID
    ) {
        var iniciada = false
        try {
            val total =
                conLatido(config.lease.dividedBy(4), { maquina.estado.latirPreparacion(organizacion, id) }) {
                    val predios = predios(anio)
                    predios.chunked(config.lote).forEachIndexed { i, lote ->
                        registros.create(
                            DETERMINACION_LOTE,
                            RegistroDeterminacion::class.java,
                            mapOf(
                                "determinacion" to id.toString(),
                                "numero" to i + 1,
                                "predios" to prediosJson(lote),
                                "estado" to PENDIENTE,
                                "intentos" to 0,
                                "procesados" to 0,
                                "generadas" to 0
                            )
                        )
                    }
                    predios.size
                }
            if (total == null) {
                log.warn("la determinación masiva {} dejó de estar PENDIENTE mientras se preparaba", id)
                return
            }
            iniciada = withContext(NonCancellable) { maquina.estado.iniciar(organizacion, id, total, usuario) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("no se pudo preparar la determinación masiva {}", id, e)
            runCatching { maquina.estado.fallar(organizacion, id, e.message ?: e.javaClass.simpleName, usuario) }
        } finally {
            if (!iniciada) withContext(NonCancellable) { runCatching { maquina.lotes.cancelar(organizacion, id) } }
        }
        // with no predios there are no lotes: a worker's cycle closes it
        if (iniciada) trabajadores.despertar()
    }

    private suspend fun predios(anio: Int): List<PredioALote> {
        val ids =
            registros
                .all(DECLARACION, Declaracion::class.java, filters = mapOf("anio" to anio.toString()))
                .mapNotNull { it.predio }
                .distinct()
        return registros
            .byIds(PREDIO, Predio::class.java, ids)
            .map { (id, p) -> PredioALote(UUID.fromString(id), p.codigo ?: id) }
            .sortedBy { it.codigo }
    }

    @PreDestroy
    fun cerrar() = scope.cancel()

    private companion object {
        val log = LoggerFactory.getLogger(DeterminacionMasivaService::class.java)
    }
}
