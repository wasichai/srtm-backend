package srtm.emision

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
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
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.UnauthorizedException
import wasichai.core.common.WasichaiException
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// model/model.json
const val EMISION_MASIVA = "emision_masiva"

private const val PENDIENTE = "PENDIENTE"
private const val EN_PROCESO = "EN_PROCESO"
private const val TERMINADA = "TERMINADA"
private const val FALLIDA = "FALLIDA"

private val ACTIVOS = setOf(PENDIENTE, EN_PROCESO)

private const val INTERRUMPIDA = "interrumpida por reinicio"
internal const val HUERFANA = "interrumpida: el proceso que la corría ya no está"
private const val DEPURADO = "archivo depurado"

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

// the masiva of a year in the background (wasichai/srtm-backend#41). a POST leaves a PENDIENTE job and returns; its
// worker reads the contribuyentes with vigente declaraciones of the year, writes the file under srtm.emision.temporales
// (GeneradorEmision), hands it to the AlmacenEmision and keeps the job's progress in core.
//
// the job runs as whoever asked for it: their authentication is taken from the request and carried into the job's
// coroutine (ReactorContext), so every read and write goes through Registros and core checks that user's permissions,
// as a request of theirs would. the jwt is not checked again after the request: its expiry does not stop a job
// already started. the job saves its progress, so the POST asks for update besides create (wasichai/srtm-backend#47)
// before any job exists: one it could not save would stay PENDIENTE.
//
// one worker in the whole deployment: a postgres advisory lock (CerrojoEmision) taken by the POST and held by the
// worker until it ends, in whichever instance. a job PENDIENTE or EN_PROCESO while nobody holds the lock has no worker:
// the next worker, or the next start, fails it. what needs no user is that maintenance, which is the system's: it
// updates the table straight and writes core's audit log itself (wasichai 0.2.0 has no system context for
// RecordService), as it does when the retention (srtm.emision.conservar, srtm.emision.dias) purges an old file
@Service
class EmisionMasivaService(
    private val registros: Registros,
    documentos: DocumentosDeEmision,
    merger: PdfMerger,
    private val db: DatabaseClient,
    private val tablasCore: TablasCore,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser,
    private val auditoria: AuditService,
    private val cerrojo: CerrojoEmision,
    private val almacen: AlmacenEmision,
    @Value("\${srtm.emision.temporales}") temporales: String,
    @param:Value("\${srtm.emision.conservar:5}") conservar: Int,
    @param:Value("\${srtm.emision.dias:0}") dias: Int
) {
    // the work files: the `.part` a job writes and the `.partes-` directory of a pdf's documents. the almacén is where
    // the result ends, and the only one that knows where that is
    private val temporales: Path = Path.of(temporales)
    private val retencion = Retencion(conservar, dias)
    private val generador = GeneradorEmision(documentos, merger)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // CoroutineStart.ATOMIC is delicate: the worker's body starts even if the scope was cancelled, to let the lock go
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun emitir(
        anio: Int,
        formato: FormatoEmision
    ): Emision {
        val llamante =
            ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication
                ?: throw UnauthorizedException("Authentication required")
        exigirPermisos()
        val tomado = cerrojo.tomar() ?: throw ConflictException("Ya hay una emisión masiva en curso: espere a que termine")
        val job =
            try {
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
                        "iniciado" to Instant.now().toString()
                    )
                )
            } catch (e: Throwable) {
                tomado.soltar()
                throw e
            }
        val id = UUID.fromString(job.id)
        scope.launch(ReactiveSecurityContextHolder.withAuthentication(llamante).asCoroutineContext(), CoroutineStart.ATOMIC) {
            try {
                mantener("fallar las emisiones sin worker") { interrumpir(excepto = id, mensaje = HUERFANA) }
                correr(id, anio, formato)
                mantener("depurar los archivos de emisiones") { depurar() }
            } finally {
                mantener("soltar el cerrojo de la emisión masiva") { tomado.soltar() }
            }
        }
        return emisionDe(job)
    }

    // the job saves its progress and its end: a caller who may create it but not update it would leave it PENDIENTE
    private suspend fun exigirPermisos() {
        val usuario = currentUser.require()
        val objeto = metadata.definitionOf(EMISION_MASIVA).obj.id
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, objeto)
            currentUser.requirePermission(usuario, Actions.UPDATE, objeto)
        } catch (_: ForbiddenException) {
            throw ForbiddenException(
                "Lanzar una emisión masiva exige permiso de creación y de edición sobre $EMISION_MASIVA: el job guarda su avance"
            )
        }
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

    // the file of a TERMINADA job: its key is rebuilt from the job, never read from it
    suspend fun archivo(id: UUID): DescargaEmision {
        val job = get(id)
        if (job.estado != TERMINADA) throw ConflictException("La emisión está ${job.estado}: su archivo estará al terminar")
        if (job.archivo == null) throw ArchivoDepuradoException("El archivo de la emisión fue depurado: vuelva a emitir el año")
        val clave = claveResultado(id, job.anio!!, FormatoEmision.valueOf(job.formato!!))
        if (!almacen.existe(clave)) throw NotFoundException("El archivo de la emisión ya no está en el servidor")
        return DescargaEmision(job, almacen.abrir(clave), almacen.tamano(clave))
    }

    // the job (core checks the caller may delete it) and its files. one PENDIENTE or EN_PROCESO whose worker runs is a
    // 409; with nobody holding the lock it has no worker, and goes
    suspend fun eliminar(id: UUID) {
        val job = get(id)
        if (job.estado in ACTIVOS) {
            val tomado = cerrojo.tomar() ?: throw ConflictException("La emisión está ${job.estado}: espere a que termine para eliminarla")
            tomado.soltar()
        }
        registros.delete(EMISION_MASIVA, id)
        almacen.listar(prefijoEmision(id)).forEach { almacen.borrar(it) }
    }

    private suspend fun correr(
        id: UUID,
        anio: Int,
        formato: FormatoEmision
    ) {
        val nombre = nombreArchivo(anio, id.toString(), formato)
        val clave = claveResultado(id, anio, formato)
        val parcial = temporales.resolve("$nombre.part")
        try {
            Files.createDirectories(temporales)
            val contribuyentes = padron(anio)
            guardar(id, "estado" to EN_PROCESO, "total" to contribuyentes.size, "procesados" to 0)
            val errores =
                generador.generar(anio, formato, contribuyentes, parcial) { procesados, errores ->
                    guardar(id, "procesados" to procesados, "errores" to erroresJson(errores))
                }
            almacen.guardar(clave, parcial)
            guardar(
                id,
                "estado" to TERMINADA,
                "procesados" to contribuyentes.size,
                "errores" to erroresJson(errores),
                "archivo" to nombre,
                "tamano" to almacen.tamano(clave),
                "terminado" to Instant.now().toString()
            )
        } catch (e: CancellationException) {
            // shutting down: the job stays EN_PROCESO and the next start (or worker) fails it
            Files.deleteIfExists(parcial)
            throw e
        } catch (e: Throwable) {
            log.error("la emisión masiva {} falló", id, e)
            Files.deleteIfExists(parcial)
            // if not even this can be saved, the job stays PENDIENTE or EN_PROCESO without a worker: the lock is let
            // go all the same, and the next worker or start fails it
            runCatching { guardar(id, "estado" to FALLIDA, "mensaje" to (e.message ?: e.javaClass.simpleName), "terminado" to Instant.now().toString()) }
                .onFailure { log.error("no se pudo marcar FALLIDA la emisión masiva {}", id, it) }
        }
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

    private suspend fun guardar(
        id: UUID,
        vararg campos: Pair<String, Any?>
    ) {
        registros.replace(EMISION_MASIVA, RegistroEmision::class.java, id, mapOf(*campos))
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
                .onSuccess {
                    when {
                        it == null -> log.info("otra instancia corre una emisión masiva: su worker recuperará las interrumpidas")
                        it > 0 -> log.warn("{} emisiones masivas interrumpidas por el reinicio pasan a FALLIDA", it)
                    }
                }.onFailure { log.error("no se pudieron recuperar las emisiones masivas interrumpidas", it) }
        }
    }

    // at startup, when no worker runs anywhere (nobody holds the lock): a job PENDIENTE or EN_PROCESO lost its worker
    // and is FALLIDA, in every organization; the files it left half-written go, and the retention runs. how many jobs
    // it failed; null if another instance's worker holds the lock, and then nothing is touched
    suspend fun recuperar(): Long? {
        val tomado = cerrojo.tomar() ?: return null
        try {
            val fallidas = interrumpir(excepto = null, mensaje = INTERRUMPIDA)
            mantener("limpiar los temporales de emisiones") { limpiarTemporales(temporales) }
            mantener("depurar los archivos de emisiones") { depurar() }
            return fallidas
        } finally {
            tomado.soltar()
        }
    }

    // with the lock held: every job PENDIENTE or EN_PROCESO but `excepto` has no worker. FALLIDA with `mensaje`,
    // straight on the table, audited by hand. how many
    private suspend fun interrumpir(
        excepto: UUID?,
        mensaje: String
    ): Long {
        var fallidas = 0L
        for (t in tablas()) {
            val e = t.columna("estado")
            val activos =
                db
                    .sql("SELECT id, organization_id, $e AS estado FROM ${t.tabla} WHERE $e IN (:activos)")
                    .bind("activos", ACTIVOS.toList())
                    .map { row, _ ->
                        Triple(row.get("id", UUID::class.java)!!, row.get("organization_id", UUID::class.java)!!, row.get("estado", String::class.java))
                    }.all()
                    .asFlow()
                    .toList()
                    .filter { it.first != excepto }
            for ((id, organizacion, estado) in activos) {
                val terminado = OffsetDateTime.now(ZoneOffset.UTC)
                val cambiadas =
                    db
                        .sql(
                            "UPDATE ${t.tabla} SET $e = :fallida, ${t.columna("mensaje")} = :mensaje, ${t.columna("terminado")} = :terminado, " +
                                "updated_at = now() WHERE id = :id AND $e IN (:activos)"
                        ).bind("fallida", FALLIDA)
                        .bind("mensaje", mensaje)
                        .bind("terminado", terminado)
                        .bind("id", id)
                        .bind("activos", ACTIVOS.toList())
                        .fetch()
                        .awaitRowsUpdated()
                if (cambiadas == 0L) continue
                auditar(organizacion, id, mapOf("estado" to estado), mapOf("estado" to FALLIDA, "mensaje" to mensaje, "terminado" to terminado.toString()))
                fallidas++
            }
        }
        return fallidas
    }

    // the retention: the files of the TERMINADA jobs beyond it are deleted, and their jobs keep everything but the
    // file (archivo empty, mensaje "archivo depurado"): the download is then a 410. how many
    suspend fun depurar(): Int {
        var depuradas = 0
        for (t in tablas()) {
            val archivo = t.columna("archivo")
            val terminadas =
                db
                    .sql(
                        "SELECT id, organization_id, ${t.columna("anio")} AS anio, ${t.columna("formato")} AS formato, " +
                            "${t.columna("terminado")} AS terminado, $archivo AS archivo, ${t.columna("mensaje")} AS mensaje " +
                            "FROM ${t.tabla} WHERE ${t.columna("estado")} = :terminada AND $archivo IS NOT NULL"
                    ).bind("terminada", TERMINADA)
                    .map { row, _ ->
                        FilaTerminada(
                            row.get("id", UUID::class.java)!!,
                            row.get("organization_id", UUID::class.java)!!,
                            row.get("anio", Long::class.javaObjectType),
                            row.get("formato", String::class.java),
                            row.get("terminado", OffsetDateTime::class.java)?.toInstant(),
                            row.get("archivo", String::class.java),
                            row.get("mensaje", String::class.java)
                        )
                    }.all()
                    .asFlow()
                    .toList()
                    .mapNotNull { f ->
                        val formato = FormatoEmision.entries.firstOrNull { it.name == f.formato }
                        if (formato == null || f.anio == null) {
                            null
                        } else {
                            ArchivoDeEmision(f.id, f.organizacion, f.anio.toInt(), formato, f.terminado) to (f.archivo to f.mensaje)
                        }
                    }
            val antes = terminadas.toMap()
            for (a in aDepurar(terminadas.map { it.first }, retencion, Instant.now())) {
                almacen.borrar(claveResultado(a.id, a.anio, a.formato))
                val cambiadas =
                    db
                        .sql(
                            "UPDATE ${t.tabla} SET $archivo = NULL, ${t.columna("mensaje")} = :mensaje, updated_at = now() " +
                                "WHERE id = :id AND $archivo IS NOT NULL"
                        ).bind("mensaje", DEPURADO)
                        .bind("id", a.id)
                        .fetch()
                        .awaitRowsUpdated()
                if (cambiadas == 0L) continue
                val (nombre, mensaje) = antes.getValue(a)
                auditar(a.organizacion, a.id, mapOf("archivo" to nombre, "mensaje" to mensaje), mapOf("archivo" to null, "mensaje" to DEPURADO))
                depuradas++
            }
        }
        if (depuradas > 0) log.info("{} archivos de emisiones masivas depurados", depuradas)
        return depuradas
    }

    // a write of the system in core's audit log: no user, the fields it changed
    private suspend fun auditar(
        organizacion: UUID,
        id: UUID,
        antes: Map<String, Any?>,
        despues: Map<String, Any?>
    ) = auditoria.record(organizacion, null, EMISION_MASIVA, id, AuditOperation.UPDATE, before = antes, after = despues)

    // the job's table in each organization, with the columns the maintenance writes
    private suspend fun tablas(): List<TablaCore> = tablasCore.de(EMISION_MASIVA, listOf("anio", "formato", "estado", "archivo", "mensaje", "terminado"))

    // a TERMINADA job with a file, as its row has it
    private class FilaTerminada(
        val id: UUID,
        val organizacion: UUID,
        val anio: Long?,
        val formato: String?,
        val terminado: Instant?,
        val archivo: String?,
        val mensaje: String?
    )

    @PreDestroy
    fun cerrar() = scope.cancel()

    private companion object {
        val log = LoggerFactory.getLogger(EmisionMasivaService::class.java)
    }
}
