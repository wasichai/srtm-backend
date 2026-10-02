package srtm.arbitrios

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import srtm.emision.EstadoTrabajos
import srtm.emision.MaquinaLotes
import srtm.emision.TablasCore
import srtm.emision.TipoLotes
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import wasichai.core.audit.AuditService
import java.util.UUID

// model/model.json
const val DETERMINACION_MASIVA = "determinacion_arbitrio_masiva"
const val DETERMINACION_LOTE = "determinacion_arbitrio_lote"

// the masiva de arbitrios as a kind of job by lotes (srtm.emision.MaquinaLotes): its lotes carry predios and count the
// cuotas they generated, and a take returns the job's observación, which every cuota it writes keeps
val TIPO_DETERMINACION =
    TipoLotes(
        trabajo = DETERMINACION_MASIVA,
        lote = DETERMINACION_LOTE,
        relacion = "determinacion",
        carga = "predios",
        producidos = "generadas",
        camposTrabajo = listOf("anio", "estado", "total", "procesados", "generadas", "errores", "mensaje", "observacion", "iniciado", "terminado", "latido"),
        camposLote = listOf("determinacion", "numero", "predios", "estado", "tomado_por", "latido", "intentos", "procesados", "generadas", "errores"),
        delTrabajo = listOf("observacion")
    )

// a predio of a lote, in the order it is determined
data class PredioALote(
    val id: UUID,
    val codigo: String
)

// a predio that could not be determined, by its code, and why
data class ErrorPredio(
    val predio: String,
    val mensaje: String
)

private val JSON: JsonMapper =
    JsonMapper
        .builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

fun prediosJson(predios: List<PredioALote>): String = JSON.writeValueAsString(predios)

fun prediosDe(json: String): List<PredioALote> = JSON.readValue(json)

fun erroresPrediosJson(errores: List<ErrorPredio>): String = JSON.writeValueAsString(errores)

fun erroresPrediosDe(json: String?): List<ErrorPredio> = json?.takeIf { it.isNotBlank() }?.let { JSON.readValue(it) } ?: emptyList()

// the determinacion_arbitrio_masiva record as core keeps it: errores is a json text
data class RegistroDeterminacion(
    val id: String? = null,
    val anio: Int? = null,
    val estado: String? = null,
    val total: Int? = null,
    val procesados: Int? = null,
    val generadas: Int? = null,
    val errores: String? = null,
    val mensaje: String? = null,
    val observacion: String? = null,
    val iniciado: String? = null,
    val terminado: String? = null
)

// the job as the portal shows it
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DeterminacionMasiva(
    val id: String,
    val anio: Int?,
    val estado: String?,
    val total: Int,
    val procesados: Int,
    val generadas: Int,
    val errores: List<ErrorPredio>,
    val mensaje: String?,
    val observacion: String?,
    val iniciado: String?,
    val terminado: String?
)

fun determinacionDe(
    r: RegistroDeterminacion,
    generadas: Int? = null
) = DeterminacionMasiva(
    id = r.id!!,
    anio = r.anio,
    estado = r.estado,
    total = r.total ?: 0,
    procesados = r.procesados ?: 0,
    generadas = generadas ?: r.generadas ?: 0,
    errores = erroresPrediosDe(r.errores),
    mensaje = r.mensaje,
    observacion = r.observacion,
    iniciado = r.iniciado,
    terminado = r.terminado
)

// the masiva's lotes and its transitions: one per instance, shared by its POST and its workers (the take's turns
// between organizations live in it)
@Component
class MaquinaDeterminacion(
    db: DatabaseClient,
    tablas: TablasCore,
    auditoria: AuditService
) {
    val lotes = MaquinaLotes(db, tablas, TIPO_DETERMINACION)
    val estado = EstadoTrabajos(db, tablas, auditoria, TIPO_DETERMINACION)
}
