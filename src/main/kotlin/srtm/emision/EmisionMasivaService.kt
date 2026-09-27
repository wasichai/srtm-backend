package srtm.emision

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
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
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.UnauthorizedException
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// model/model.json
const val EMISION_MASIVA = "emision_masiva"

private const val PENDIENTE = "PENDIENTE"
private const val EN_PROCESO = "EN_PROCESO"
private const val TERMINADA = "TERMINADA"
private const val FALLIDA = "FALLIDA"

private const val INTERRUMPIDA = "interrumpida por reinicio"

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

private val JSON: JsonMapper =
    JsonMapper
        .builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

fun erroresJson(errores: List<ErrorEmision>): String = JSON.writeValueAsString(errores)

fun emisionDe(r: RegistroEmision) =
    Emision(
        id = r.id!!,
        anio = r.anio,
        formato = r.formato,
        estado = r.estado,
        total = r.total ?: 0,
        procesados = r.procesados ?: 0,
        errores = r.errores?.takeIf { it.isNotBlank() }?.let { JSON.readValue<List<ErrorEmision>>(it) } ?: emptyList(),
        archivo = r.archivo,
        tamano = r.tamano,
        mensaje = r.mensaje,
        iniciado = r.iniciado,
        terminado = r.terminado
    )

// the masiva of a year in the background (wasichai/srtm-backend#41). a POST leaves a PENDIENTE job and returns; one
// worker at a time takes it: it reads the contribuyentes with vigente declaraciones of the year, writes the file under
// srtm.emision.dir (GeneradorEmision) and keeps the job's progress in core.
//
// the job runs as whoever asked for it: their authentication is taken from the request and carried into the job's
// coroutine (ReactorContext), so every read and write goes through Registros and core checks that user's permissions,
// as a request of theirs would. the jwt is not checked again after the request: its expiry does not stop a job
// already started. what needs no user is the startup recovery, which is the system's: it updates the table straight
@Service
class EmisionMasivaService(
    private val registros: Registros,
    documentos: DocumentosDeEmision,
    merger: PdfMerger,
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    @param:Value("\${srtm.emision.dir}") dir: String
) {
    private val dir: Path = Path.of(dir)
    private val generador = GeneradorEmision(documentos, merger)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // the check for a running job and the new one's creation, together
    private val alta = Mutex()
    private val worker = Semaphore(1)

    suspend fun emitir(
        anio: Int,
        formato: FormatoEmision
    ): Emision {
        val llamante =
            ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication
                ?: throw UnauthorizedException("Authentication required")
        val job =
            alta.withLock {
                activa()?.let { throw ConflictException("Ya hay una emisión ${it.estado} del año ${it.anio}: espere a que termine") }
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
            }
        scope.launch(ReactiveSecurityContextHolder.withAuthentication(llamante).asCoroutineContext()) {
            worker.withPermit { correr(UUID.fromString(job.id), anio, formato) }
        }
        return emisionDe(job)
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

    // the file of a TERMINADA job: its name is rebuilt from the job, never read from it
    suspend fun archivo(id: UUID): Pair<Emision, Path> {
        val job = get(id)
        if (job.estado != TERMINADA) throw ConflictException("La emisión está ${job.estado}: su archivo estará al terminar")
        val formato = FormatoEmision.valueOf(job.formato!!)
        val archivo = dir.resolve(nombreArchivo(job.anio!!, job.id, formato))
        if (!Files.isRegularFile(archivo)) throw NotFoundException("El archivo de la emisión ya no está en el servidor")
        return job to archivo
    }

    private suspend fun activa(): RegistroEmision? =
        listOf(PENDIENTE, EN_PROCESO).firstNotNullOfOrNull { estado ->
            registros.all(EMISION_MASIVA, RegistroEmision::class.java, filters = mapOf("estado" to estado)).firstOrNull()
        }

    private suspend fun correr(
        id: UUID,
        anio: Int,
        formato: FormatoEmision
    ) {
        val nombre = nombreArchivo(anio, id.toString(), formato)
        val destino = dir.resolve(nombre)
        val parcial = dir.resolve("$nombre.part")
        try {
            Files.createDirectories(dir)
            val contribuyentes = padron(anio)
            guardar(id, "estado" to EN_PROCESO, "total" to contribuyentes.size, "procesados" to 0)
            val errores =
                generador.generar(anio, formato, contribuyentes, parcial) { procesados, errores ->
                    guardar(id, "procesados" to procesados, "errores" to erroresJson(errores))
                }
            Files.move(parcial, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            guardar(
                id,
                "estado" to TERMINADA,
                "procesados" to contribuyentes.size,
                "errores" to erroresJson(errores),
                "archivo" to nombre,
                "tamano" to Files.size(destino),
                "terminado" to Instant.now().toString()
            )
        } catch (e: CancellationException) {
            // shutting down: the job stays EN_PROCESO and the next start fails it
            Files.deleteIfExists(parcial)
            throw e
        } catch (e: Throwable) {
            log.error("la emisión masiva {} falló", id, e)
            Files.deleteIfExists(parcial)
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

    @EventListener(ApplicationReadyEvent::class)
    fun alArrancar() {
        runBlocking {
            runCatching { recuperar() }
                .onSuccess { if (it > 0) log.warn("{} emisiones masivas interrumpidas por el reinicio pasan a FALLIDA", it) }
                .onFailure { log.error("no se pudieron recuperar las emisiones masivas interrumpidas", it) }
        }
    }

    // a job PENDIENTE or EN_PROCESO when the app starts lost its worker: FALLIDA, in every organization. there is no
    // user at startup, so it goes straight to the object's table (the columns as core's metadata names them). how
    // many jobs it failed
    suspend fun recuperar(): Long {
        val tablas =
            db
                .sql(
                    """
                    SELECT o.physical_table AS tabla,
                           max(CASE WHEN f.name = 'estado' THEN f.column_name END) AS estado,
                           max(CASE WHEN f.name = 'mensaje' THEN f.column_name END) AS mensaje,
                           max(CASE WHEN f.name = 'terminado' THEN f.column_name END) AS terminado
                    FROM ${schemas.metadata}.custom_objects o
                    JOIN ${schemas.metadata}.custom_fields f ON f.object_id = o.id
                    WHERE o.name = :nombre
                    GROUP BY o.physical_table
                    """.trimIndent()
                ).bind("nombre", EMISION_MASIVA)
                .map { row, _ ->
                    listOf("tabla", "estado", "mensaje", "terminado").map { row.get(it, String::class.java) }
                }.all()
                .asFlow()
                .toList()
        var fallidas = 0L
        for ((tabla, estado, mensaje, terminado) in tablas) {
            if (tabla == null || estado == null || mensaje == null || terminado == null) continue
            val e = SqlIdentifier.quote(estado)
            fallidas +=
                db
                    .sql(
                        "UPDATE ${schemas.dataTable(tabla)} SET $e = :fallida, ${SqlIdentifier.quote(mensaje)} = :mensaje, " +
                            "${SqlIdentifier.quote(terminado)} = now(), updated_at = now() WHERE $e IN (:activos)"
                    ).bind("fallida", FALLIDA)
                    .bind("mensaje", INTERRUMPIDA)
                    .bind("activos", listOf(PENDIENTE, EN_PROCESO))
                    .fetch()
                    .awaitRowsUpdated()
        }
        return fallidas
    }

    @PreDestroy
    fun cerrar() = scope.cancel()

    private companion object {
        val log = LoggerFactory.getLogger(EmisionMasivaService::class.java)
    }
}
