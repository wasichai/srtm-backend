package srtm.emision

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val PENDIENTE = "PENDIENTE"
private const val EN_PROCESO = "EN_PROCESO"
private const val ENSAMBLANDO = "ENSAMBLANDO"
private const val FALLIDA = "FALLIDA"

internal val TRABAJOS_ACTIVOS = listOf(PENDIENTE, EN_PROCESO, ENSAMBLANDO)

// the transitions of one kind of job by lotes (estado_emision), straight on its table and audited by hand: the
// system's writes, done by whichever instance (wasichai 0.2.0 has no system context for RecordService). each one
// applies only from the states it names, so two instances never both make it. latido is the lease of the instance
// that prepares (or ends) the job; renewing it is no transition, and is not audited
class EstadoTrabajos internal constructor(
    private val db: DatabaseClient,
    private val tablas: TablasCore,
    private val auditoria: AuditService,
    val tipo: TipoLotes
) {
    internal suspend fun tablas(organizacion: UUID? = null) = tablas.lotes(tipo, organizacion)

    // the jobs of the year not yet ended: id and created_at, oldest first
    suspend fun activas(
        organizacion: UUID,
        anio: Int
    ): List<Pair<UUID, Instant>> {
        val e = tablas(organizacion).firstOrNull()?.trabajos ?: return emptyList()
        return db
            .sql(
                "SELECT id, created_at FROM ${e.tabla} WHERE ${e.columna("anio")} = :anio AND ${e.columna("estado")} IN (:activos) " +
                    "ORDER BY created_at"
            ).bind("anio", anio)
            .bind("activos", TRABAJOS_ACTIVOS)
            .map { row, _ -> row.get("id", UUID::class.java)!! to row.get("created_at", OffsetDateTime::class.java)!!.toInstant() }
            .all()
            .asFlow()
            .toList()
    }

    // whether its row is still there: it may be deleted while an instance works on it
    suspend fun existe(
        organizacion: UUID,
        id: UUID
    ): Boolean {
        val e = tablas(organizacion).firstOrNull()?.trabajos ?: return false
        return db
            .sql("SELECT count(*) AS n FROM ${e.tabla} WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one()
            .awaitFirstOrNull()
            ?.let { it > 0 } ?: false
    }

    // the preparer's lease, while it reads what to process and cuts it: only while PENDIENTE
    suspend fun latirPreparacion(
        organizacion: UUID,
        id: UUID
    ): Boolean {
        val e = tablas(organizacion).firstOrNull()?.trabajos ?: return false
        return db
            .sql("UPDATE ${e.tabla} SET ${e.columna("latido")} = now(), updated_at = now() WHERE id = :id AND ${e.columna("estado")} = :pendiente")
            .bind("id", id)
            .bind("pendiente", PENDIENTE)
            .fetch()
            .awaitRowsUpdated() > 0
    }

    // its lotes are written: PENDIENTE -> EN_PROCESO, with how many it has to process
    suspend fun iniciar(
        organizacion: UUID,
        id: UUID,
        total: Int,
        usuario: UUID?
    ): Boolean {
        val e = tablas(organizacion).firstOrNull()?.trabajos ?: return false
        return transicion(
            e,
            id,
            "e.${e.columna("estado")} = :pendiente",
            mapOf("pendiente" to PENDIENTE),
            mapOf("estado" to EN_PROCESO, "total" to total),
            latir = true,
            usuario = usuario
        )
    }

    // procesados = the sum of its lotes'
    suspend fun sumarProcesados(
        organizacion: UUID,
        id: UUID
    ) {
        tablas(organizacion).forEach { sumarProcesadosLotes(db, tipo, it, id) }
    }

    // from any state not yet ended -> FALLIDA, with why; its lotes still to process are cancelled
    suspend fun fallar(
        organizacion: UUID,
        id: UUID,
        mensaje: String,
        usuario: UUID? = null
    ): Boolean {
        val t = tablas(organizacion).firstOrNull() ?: return false
        return fallida(t, id, "e.${t.trabajos.columna("estado")} IN (:activos)", mapOf("activos" to TRABAJOS_ACTIVOS), mensaje, usuario)
    }

    // the jobs nobody works on any more: PENDIENTE whose preparer stopped beating `lease` ago (or never did), and
    // EN_PROCESO without latido (an emission of the version before the lotes, which has none and never ends). FALLIDA,
    // by the system, and their lotes cancelled. (organizacion, id) of each
    suspend fun fallarAbandonadas(lease: Duration): List<Pair<UUID, UUID>> {
        val fallidas = mutableListOf<Pair<UUID, UUID>>()
        for (t in tablas()) {
            val e = t.trabajos
            val estado = e.columna("estado")
            val latido = e.columna("latido")
            val abandonada =
                "((e.$estado = :pendiente AND (e.$latido IS NULL OR e.$latido < now() - make_interval(secs => :lease))) " +
                    "OR (e.$estado = :en_proceso AND e.$latido IS NULL))"
            val valores = mapOf("pendiente" to PENDIENTE, "en_proceso" to EN_PROCESO, "lease" to segundos(lease))
            var spec = db.sql("SELECT e.id FROM ${e.tabla} e WHERE $abandonada ORDER BY e.created_at")
            valores.forEach { (nombre, valor) -> spec = spec.bind(nombre, valor) }
            val candidatas =
                spec
                    .map { row, _ -> row.get("id", UUID::class.java)!! }
                    .all()
                    .asFlow()
                    .toList()
            for (id in candidatas) {
                if (fallida(t, id, abandonada, valores, HUERFANA, null)) fallidas += t.organizacion to id
            }
        }
        return fallidas
    }

    private suspend fun fallida(
        t: TablasLote,
        id: UUID,
        condicion: String,
        valores: Map<String, Any>,
        mensaje: String,
        usuario: UUID?
    ): Boolean {
        val fallo =
            transicion(
                t.trabajos,
                id,
                condicion,
                valores,
                mapOf("estado" to FALLIDA, "mensaje" to mensaje, "terminado" to OffsetDateTime.now(ZoneOffset.UTC)),
                latir = false,
                usuario = usuario
            )
        if (fallo) cancelarLotes(db, tipo, t.lotes, id)
        return fallo
    }

    // one job's transition: its `cambios` when `condicion` holds (on `e`, its row), audited with the values the fields
    // had (read under the row's lock, in the same statement). `latir` renews the lease too. false: it did not hold
    internal suspend fun transicion(
        t: TablaCore,
        id: UUID,
        condicion: String,
        valores: Map<String, Any>,
        cambios: Map<String, Any>,
        latir: Boolean,
        usuario: UUID?
    ): Boolean {
        val campos = cambios.keys.toList()
        val leidos = campos.withIndex().joinToString(", ") { (i, c) -> "${t.columna(c)} AS a$i" }
        val asignaciones =
            campos.withIndex().joinToString(", ") { (i, c) -> "${t.columna(c)} = :v$i" } +
                (if (latir) ", ${t.columna("latido")} = now()" else "")
        var spec =
            db
                .sql(
                    """
                    WITH antes AS MATERIALIZED (SELECT id, $leidos FROM ${t.tabla} WHERE id = :id FOR UPDATE)
                    UPDATE ${t.tabla} AS e SET $asignaciones, updated_at = now()
                    FROM antes WHERE e.id = antes.id AND $condicion
                    RETURNING ${campos.indices.joinToString(", ") { "antes.a$it AS a$it" }}
                    """.trimIndent()
                ).bind("id", id)
        cambios.values.forEachIndexed { i, v -> spec = spec.bind("v$i", v) }
        valores.forEach { (nombre, valor) -> spec = spec.bind(nombre, valor) }
        val antes =
            spec
                .map { row, _ -> campos.withIndex().associate { (i, c) -> c to auditable(row.get("a$i")) } }
                .one()
                .awaitFirstOrNull()
                ?: return false
        auditar(t.organizacion, id, usuario, antes, cambios.mapValues { auditable(it.value) })
        return true
    }

    // a transition in core's audit log: the fields it changed, by `usuario` (null: the system)
    internal suspend fun auditar(
        organizacion: UUID,
        id: UUID,
        usuario: UUID?,
        antes: Map<String, Any?>,
        despues: Map<String, Any?>
    ) = auditoria.record(organizacion, usuario, tipo.trabajo, id, AuditOperation.UPDATE, before = antes, after = despues)

    // a datetime as its text, as the audit log keeps it
    private fun auditable(valor: Any?): Any? = if (valor is OffsetDateTime) valor.toString() else valor
}
