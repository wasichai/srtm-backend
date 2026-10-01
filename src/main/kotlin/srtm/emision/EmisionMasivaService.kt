package srtm.emision

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Service
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Registros
import srtm.rentas.vigente
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.UnauthorizedException
import wasichai.core.common.WasichaiException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// model/model.json
const val EMISION_MASIVA = "emision_masiva"

private const val PENDIENTE = "PENDIENTE"
private const val EN_PROCESO = "EN_PROCESO"
private const val ENSAMBLANDO = "ENSAMBLANDO"
private const val TERMINADA = "TERMINADA"

private val ACTIVOS = setOf(PENDIENTE, EN_PROCESO, ENSAMBLANDO)

internal const val HUERFANA = "interrumpida: el proceso que la corría ya no está"

// a TERMINADA job whose file the retention removed
class ArchivoDepuradoException(
    message: String
) : WasichaiException(HttpStatus.GONE, message)

// the emision_masiva record as core keeps it: errores is a json text
data class RegistroEmision(
    val id: String? = null,
    val anio: Int? = null,
    val formato: String? = null,
    val estado: String? = null,
    val total: Int? = null,
    val procesados: Int? = null,
    val errores: String? = null,
    val archivo: String? = null,
    val tamano: Long? = null,
    val mensaje: String? = null,
    val iniciado: String? = null,
    val terminado: String? = null
)

// the job of the epic's contract (wasichai/srtm-backend#37)
data class Emision(
    val id: String,
    val anio: Int?,
    val formato: String?,
    val estado: String?,
    val total: Int,
    val procesados: Int,
    val errores: List<ErrorEmision>,
    val archivo: String?,
    val tamano: Long?,
    val mensaje: String?,
    val iniciado: String?,
    val terminado: String?
)

fun emisionDe(r: RegistroEmision) =
    Emision(
        id = r.id!!,
        anio = r.anio,
        formato = r.formato,
        estado = r.estado,
        total = r.total ?: 0,
        procesados = r.procesados ?: 0,
        errores = erroresDe(r.errores),
        archivo = r.archivo,
        tamano = r.tamano,
        mensaje = r.mensaje,
        iniciado = r.iniciado,
        terminado = r.terminado
    )

// the file of a TERMINADA job, to download: streamed from the almacén, with its size from there too
class DescargaEmision(
    val job: Emision,
    val recurso: Resource,
    val tamano: Long
)

// the lote of the masiva a POST writes (its id: the rest is the workers')
private data class RegistroLote(
    val id: String? = null
)

// the masiva of a year (wasichai/srtm-backend#41, #53, #54). a POST leaves a PENDIENTE job and returns; in the
// background, as the caller, it reads the contribuyentes with vigente declaraciones of the year and cuts them in lotes
// of srtm.emision.lote (emision_lote), and the job goes EN_PROCESO. the workers of every instance (TrabajadoresEmision)
// take the lotes, each one's parte goes to the almacén, and the one that ends the last lote assembles the file: the job
// goes ENSAMBLANDO and then TERMINADA (or FALLIDA). a job's progress is the sum of its lotes'.
//
// the preparation runs as whoever asked for it: their authentication is taken from the request and carried into its
// coroutine (ReactorContext), so core checks that user's permissions on every read and on the lotes it creates, as a
// request of theirs would; the lotes are generated as that same user (IdentidadEmision). the jwt is not checked again
// after the request. one active masiva per organization and year: two organizations emit at once. what needs no user
// is the system's (EstadoEmisiones, LotesEmision, RetencionEmision): it writes the tables straight and core's audit
// log itself (wasichai 0.2.0 has no system context for RecordService)
@Service
class EmisionMasivaService(
    private val registros: Registros,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser,
    private val almacen: AlmacenEmision,
    private val lotes: LotesEmision,
    private val estado: EstadoEmisiones,
    private val retencion: RetencionEmision,
    private val trabajadores: TrabajadoresEmision,
    private val config: EmisionProperties
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun emitir(
        anio: Int,
        formato: FormatoEmision
    ): Emision {
        val llamante =
            ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication
                ?: throw UnauthorizedException("Authentication required")
        val usuario = exigirPermisos()
        val organizacion = usuario.organizationId
        if (estado.activas(organizacion, anio).isNotEmpty()) throw ConflictException(enCurso(anio))
        val job =
            registros.create(
                EMISION_MASIVA,
                RegistroEmision::class.java,
                mapOf(
                    "anio" to anio,
                    "formato" to formato.name,
                    "estado" to PENDIENTE,
                    "total" to 0,
                    "procesados" to 0,
                    "errores" to erroresJson(emptyList()),
                    "iniciado" to Instant.now().toString(),
                    // the preparer's lease, from the start: a job whose preparer dies is failed by the workers
                    "latido" to Instant.now().toString()
                )
            )
        val id = UUID.fromString(job.id)
        // two POSTs at once both saw none: the oldest goes on, the other one goes
        val primera = estado.activas(organizacion, anio).minWithOrNull(compareBy({ it.second }, { it.first }))?.first
        if (primera != null && primera != id) {
            descartar(organizacion, id, usuario.userId)
            throw ConflictException(enCurso(anio))
        }
        scope.launch(ReactiveSecurityContextHolder.withAuthentication(llamante).asCoroutineContext()) {
            preparar(organizacion, id, anio, usuario.userId)
        }
        return emisionDe(job)
    }

    private fun enCurso(anio: Int) = "Ya hay una emisión masiva de $anio en curso: espere a que termine"

    // the job of a POST that lost the race: deleted, or failed if the caller may not delete it
    private suspend fun descartar(
        organizacion: UUID,
        id: UUID,
        usuario: UUID
    ) {
        try {
            registros.delete(EMISION_MASIVA, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            estado.fallar(organizacion, id, "otra emisión masiva del año empezó a la vez", usuario)
        }
    }

    // the preparation creates the lotes; the job is saved as it goes (the system's writes, but the caller's job): a
    // caller who may create it but not update it, or not create its lotes, is refused before anything exists
    private suspend fun exigirPermisos(): AuthenticatedUser {
        val usuario = currentUser.require()
        val objeto = metadata.definitionOf(EMISION_MASIVA).obj.id
        val lote = metadata.definitionOf(EMISION_LOTE).obj.id
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, objeto)
            currentUser.requirePermission(usuario, Actions.UPDATE, objeto)
            currentUser.requirePermission(usuario, Actions.CREATE, lote)
        } catch (_: ForbiddenException) {
            throw ForbiddenException(
                "Lanzar una emisión masiva exige permiso de creación y de edición sobre $EMISION_MASIVA, y de creación sobre $EMISION_LOTE: " +
                    "el job guarda su avance en sus lotes"
            )
        }
        return usuario
    }

    // in the background, as the caller, while the lease is renewed: the padron cut in lotes, written in order, and
    // the job EN_PROCESO with its total. a failure leaves it FALLIDA; a job no longer PENDIENTE (deleted, or failed
    // because its beats did not arrive) stops it. the lotes of a job that does not start are cancelled
    private suspend fun preparar(
        organizacion: UUID,
        id: UUID,
        anio: Int,
        usuario: UUID
    ) {
        var iniciada = false
        try {
            val total =
                conLatido(config.lease.dividedBy(4), { estado.latirPreparacion(organizacion, id) }) {
                    val contribuyentes = padron(anio)
                    cortarEnLotes(contribuyentes, config.lote).forEachIndexed { i, lote ->
                        registros.create(
                            EMISION_LOTE,
                            RegistroLote::class.java,
                            mapOf(
                                "emision" to id.toString(),
                                "numero" to i + 1,
                                "contribuyentes" to contribuyentesJson(lote),
                                "estado" to PENDIENTE,
                                "intentos" to 0,
                                "procesados" to 0
                            )
                        )
                    }
                    contribuyentes.size
                }
            if (total == null) {
                log.warn("la emisión masiva {} dejó de estar PENDIENTE mientras se preparaba", id)
                return
            }
            // its outcome must be known: a job EN_PROCESO whose lotes were cancelled would end empty
            iniciada = withContext(NonCancellable) { estado.iniciar(organizacion, id, total, usuario) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("no se pudo preparar la emisión masiva {}", id, e)
            mantener("marcar FALLIDA la emisión masiva $id") { estado.fallar(organizacion, id, e.message ?: e.javaClass.simpleName, usuario) }
        } finally {
            if (!iniciada) withContext(NonCancellable) { mantener("cancelar los lotes de la emisión masiva $id") { lotes.cancelar(organizacion, id) } }
        }
        // with no contribuyentes there are no lotes: a worker's claim assembles the empty file
        if (iniciada) trabajadores.despertar()
    }

    suspend fun lista(anio: Int?): List<Emision> =
        registros
            .all(
                EMISION_MASIVA,
                RegistroEmision::class.java,
                filters = anio?.let { mapOf("anio" to it.toString()) } ?: emptyMap(),
                sort = "created_at",
                descending = true
            ).map(::emisionDe)

    suspend fun get(id: UUID): Emision = emisionDe(registros.get(EMISION_MASIVA, RegistroEmision::class.java, id))

    // the file of a TERMINADA job: its key is rebuilt from the job, never read from it. a file purged between the
    // calls to the almacén is a 404 too
    suspend fun archivo(id: UUID): DescargaEmision {
        val job = get(id)
        if (job.estado != TERMINADA) throw ConflictException("La emisión está ${job.estado}: su archivo estará al terminar")
        if (job.archivo == null) throw ArchivoDepuradoException("El archivo de la emisión fue depurado: vuelva a emitir el año")
        val clave = claveResultado(id, job.anio!!, FormatoEmision.valueOf(job.formato!!))
        return try {
            DescargaEmision(job, almacen.abrir(clave), almacen.tamano(clave))
        } catch (_: ClaveInexistenteException) {
            throw NotFoundException("El archivo de la emisión ya no está en el servidor")
        }
    }

    // the job (the caller may delete it: checked before anything is touched), its lotes and its files. one still
    // running is cancelled: its lotes go FALLIDO, and the workers that had them stop at their next write and leave
    // nothing behind
    suspend fun eliminar(id: UUID) {
        val job = get(id)
        val usuario = currentUser.require()
        currentUser.requirePermission(usuario, Actions.DELETE, metadata.definitionOf(EMISION_MASIVA).obj.id)
        val organizacion = usuario.organizationId
        if (job.estado in ACTIVOS) lotes.cancelar(organizacion, id)
        lotes.borrar(organizacion, id)
        registros.delete(EMISION_MASIVA, id)
        almacen.listar(prefijoEmision(id)).forEach { almacen.borrar(it) }
    }

    // the contribuyentes with vigente declaraciones of the year, by code; each one's predios by code
    private suspend fun padron(anio: Int): List<ContribuyenteAEmitir> {
        val vigentes =
            registros
                .all(DECLARACION, Declaracion::class.java, filters = mapOf("anio" to anio.toString()))
                .filter { vigente(it) && it.contribuyente != null && it.predio != null }
        val porContribuyente = vigentes.groupBy { it.contribuyente!! }
        val contribuyentes = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, porContribuyente.keys)
        val predios = registros.byIds(PREDIO, Predio::class.java, vigentes.map { it.predio!! })
        return porContribuyente
            .map { (id, declaraciones) ->
                val c = contribuyentes[id]
                ContribuyenteAEmitir(
                    id = UUID.fromString(id),
                    codigo = c?.codigo ?: id,
                    nombre = c?.let(::nombreDe).orEmpty(),
                    predios =
                        declaraciones
                            .map { it.predio!! }
                            .distinct()
                            .sortedBy { predios[it]?.codigo ?: it }
                            .map(UUID::fromString)
                )
            }.sortedBy { it.codigo }
    }

    // maintenance that must not fail its caller: logged
    private suspend fun mantener(
        que: String,
        paso: suspend () -> Any
    ) {
        try {
            paso()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("no se pudo {}", que, e)
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun alArrancar() {
        runBlocking {
            runCatching { recuperar() }
                .onSuccess { if (it > 0) log.warn("{} emisiones masivas sin quien las corriera pasan a FALLIDA", it) }
                .onFailure { log.error("no se pudieron recuperar las emisiones masivas interrumpidas", it) }
        }
    }

    // at startup: the jobs nobody works on any more (a preparer that died, or a job of the version before the lotes,
    // left EN_PROCESO without latido) are FALLIDA, in every organization, and the retention runs. the lotes of a job
    // that lives are not touched: they are retaken when their lease expires. each group of workers cleans its own work
    // dir when it starts. how many jobs it failed
    suspend fun recuperar(): Int {
        val fallidas = estado.fallarAbandonadas(config.lease).size
        mantener("depurar los archivos de emisiones") { retencion.depurar() }
        return fallidas
    }

    @PreDestroy
    fun cerrar() = scope.cancel()

    private companion object {
        val log = LoggerFactory.getLogger(EmisionMasivaService::class.java)
    }
}
