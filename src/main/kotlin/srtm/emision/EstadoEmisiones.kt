package srtm.emision

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
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
private const val TERMINADA = "TERMINADA"
private const val FALLIDA = "FALLIDA"

private val ACTIVOS = listOf(PENDIENTE, EN_PROCESO, ENSAMBLANDO)
private val LOTES_ACTIVOS = listOf("PENDIENTE", "EN_PROCESO")

// an emission whose lotes all ended, claimed by this instance to assemble it. `latido` is its token: the one the claim
// or the last beat gave, which the next beat and the end compare to
data class EmisionAEnsamblar(
    val id: UUID,
    val organizacion: UUID,
    val anio: Int,
    val formato: FormatoEmision,
    val latido: OffsetDateTime
)

// the transitions of emision_masiva (wasichai/srtm-backend#54), straight on its tables and audited by hand: the
// system's writes, done by whichever instance (wasichai 0.2.0 has no system context for RecordService). each one
// applies only from the states it names, so two instances never both make it. latido is the lease of the instance
// that prepares or assembles the emission; renewing it is no transition, and is not audited
@Component
class EstadoEmisiones(
    private val db: DatabaseClient,
    private val tablas: TablasCore,
    private val auditoria: AuditService
) {
    // the emissions of the year not yet ended: id and created_at, oldest first
    suspend fun activas(
        organizacion: UUID,
        anio: Int
    ): List<Pair<UUID, Instant>> {
        val e = tablas.lotes(organizacion).firstOrNull()?.emisiones ?: return emptyList()
        return db
            .sql(
                "SELECT id, created_at FROM ${e.tabla} WHERE ${e.columna("anio")} = :anio AND ${e.columna("estado")} IN (:activos) " +
                    "ORDER BY created_at"
            ).bind("anio", anio)
            .bind("activos", ACTIVOS)
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
        val e = tablas.lotes(organizacion).firstOrNull()?.emisiones ?: return false
        return db
            .sql("SELECT count(*) AS n FROM ${e.tabla} WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one()
            .awaitFirstOrNull()
            ?.let { it > 0 } ?: false
    }

    // the preparer's lease, while it reads the padron and cuts it: only while PENDIENTE
    suspend fun latirPreparacion(
        organizacion: UUID,
        id: UUID
    ): Boolean {
        val e = tablas.lotes(organizacion).firstOrNull()?.emisiones ?: return false
        return db
            .sql("UPDATE ${e.tabla} SET ${e.columna("latido")} = now(), updated_at = now() WHERE id = :id AND ${e.columna("estado")} = :pendiente")
            .bind("id", id)
            .bind("pendiente", PENDIENTE)
            .fetch()
            .awaitRowsUpdated() > 0
    }

    // its lotes are written: PENDIENTE -> EN_PROCESO, with how many contribuyentes it has
    suspend fun iniciar(
        organizacion: UUID,
        id: UUID,
        total: Int,
        usuario: UUID?
    ): Boolean {
        val t = tablas.lotes(organizacion).firstOrNull() ?: return false
        val e = t.emisiones
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
        tablas.lotes(organizacion).forEach { sumarProcesadosLotes(db, it, id) }
    }

    // an emission to assemble: EN_PROCESO with no lote left to generate, or ENSAMBLANDO by an instance that stopped
    // beating `lease` ago. one EN_PROCESO without latido is a job of the version before the lotes: it has none, and is
    // never assembled (fallarAbandonadas fails it). FOR UPDATE SKIP LOCKED: exactly one claimer gets it. `organizacion`
    // and `id` narrow the search; null: none
    suspend fun reclamarEnsamblado(
        lease: Duration,
        organizacion: UUID? = null,
        id: UUID? = null
    ): EmisionAEnsamblar? {
        for (t in tablas.lotes(organizacion)) {
            val e = t.emisiones
            val l = t.lotes
            val estado = e.columna("estado")
            val latido = e.columna("latido")
            var spec =
                db
                    .sql(
                        """
                        WITH antes AS MATERIALIZED (
                            SELECT x.id, x.$estado AS estado FROM ${e.tabla} x
                            WHERE ((x.$estado = :en_proceso AND x.$latido IS NOT NULL)
                                OR (x.$estado = :ensamblando AND x.$latido < now() - make_interval(secs => :lease)))
                              AND NOT EXISTS (
                                SELECT 1 FROM ${l.tabla} l WHERE l.${l.columna("emision")} = x.id AND l.${l.columna("estado")} IN (:lotes_activos)
                              )
                              ${if (id != null) "AND x.id = :id" else ""}
                            ORDER BY x.created_at
                            LIMIT 1
                            FOR UPDATE OF x SKIP LOCKED
                        )
                        UPDATE ${e.tabla} AS e SET $estado = :ensamblando, $latido = now(), updated_at = now()
                        FROM antes WHERE e.id = antes.id
                        RETURNING e.id AS id, e.${e.columna("anio")} AS anio, e.${e.columna("formato")} AS formato, e.$latido AS latido,
                            antes.estado AS antes
                        """.trimIndent()
                    ).bind("en_proceso", EN_PROCESO)
                    .bind("ensamblando", ENSAMBLANDO)
                    .bind("lease", segundos(lease))
                    .bind("lotes_activos", LOTES_ACTIVOS)
            if (id != null) spec = spec.bind("id", id)
            val reclamada =
                spec
                    .map { row, _ ->
                        EmisionAEnsamblar(
                            id = row.get("id", UUID::class.java)!!,
                            organizacion = e.organizacion,
                            anio = row.get("anio", Long::class.javaObjectType)!!.toInt(),
                            formato = FormatoEmision.valueOf(row.get("formato", String::class.java)!!),
                            latido = row.get("latido", OffsetDateTime::class.java)!!
                        ) to row.get("antes", String::class.java)
                    }.one()
                    .awaitFirstOrNull()
                    ?: continue
            val (emision, antes) = reclamada
            auditar(emision.organizacion, emision.id, null, mapOf("estado" to antes), mapOf("estado" to ENSAMBLANDO))
            return emision
        }
        return null
    }

    // the assembler's lease: compare-and-set on its token. the new token; null if another instance claimed it since
    suspend fun latirEnsamblado(e: EmisionAEnsamblar): EmisionAEnsamblar? {
        val t = tablas.lotes(e.organizacion).firstOrNull()?.emisiones ?: return null
        val latido = t.columna("latido")
        return db
            .sql(
                "UPDATE ${t.tabla} SET $latido = now(), updated_at = now() " +
                    "WHERE id = :id AND ${t.columna("estado")} = :ensamblando AND $latido = :token RETURNING $latido AS latido"
            ).bind("id", e.id)
            .bind("ensamblando", ENSAMBLANDO)
            .bind("token", e.latido)
            .map { row, _ -> row.get("latido", OffsetDateTime::class.java)!! }
            .one()
            .awaitFirstOrNull()
            ?.let { e.copy(latido = it) }
    }

    // the file is in the almacén: ENSAMBLANDO (still with this token) -> TERMINADA
    suspend fun terminar(
        e: EmisionAEnsamblar,
        archivo: String,
        tamano: Long,
        procesados: Int,
        errores: List<ErrorEmision>
    ): Boolean {
        val t = tablas.lotes(e.organizacion).firstOrNull()?.emisiones ?: return false
        return transicion(
            t,
            e.id,
            "e.${t.columna("estado")} = :ensamblando AND e.${t.columna("latido")} = :token",
            mapOf("ensamblando" to ENSAMBLANDO, "token" to e.latido),
            mapOf(
                "estado" to TERMINADA,
                "procesados" to procesados,
                "errores" to erroresJson(errores),
                "archivo" to archivo,
                "tamano" to tamano,
                "terminado" to OffsetDateTime.now(ZoneOffset.UTC)
            ),
            latir = false,
            usuario = null
        )
    }

    // from any state not yet ended -> FALLIDA, with why; its lotes still to generate are cancelled
    suspend fun fallar(
        organizacion: UUID,
        id: UUID,
        mensaje: String,
        usuario: UUID? = null
    ): Boolean {
        val t = tablas.lotes(organizacion).firstOrNull() ?: return false
        return fallida(t, id, "e.${t.emisiones.columna("estado")} IN (:activos)", mapOf("activos" to ACTIVOS), mensaje, usuario)
    }

    // the emissions nobody works on any more: PENDIENTE whose preparer stopped beating `lease` ago (or never did), and
    // EN_PROCESO without latido (a job of the version before the lotes, which has none and never ends). FALLIDA, by
    // the system, and their lotes cancelled. (organizacion, id) of each
    suspend fun fallarAbandonadas(lease: Duration): List<Pair<UUID, UUID>> {
        val fallidas = mutableListOf<Pair<UUID, UUID>>()
        for (t in tablas.lotes()) {
            val e = t.emisiones
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
                t.emisiones,
                id,
                condicion,
                valores,
                mapOf("estado" to FALLIDA, "mensaje" to mensaje, "terminado" to OffsetDateTime.now(ZoneOffset.UTC)),
                latir = false,
                usuario = usuario
            )
        if (fallo) cancelarLotes(db, t.lotes, id)
        return fallo
    }

    // one emission's transition: its `cambios` when `condicion` holds (on `e`, its row), audited with the values the
    // fields had (read under the row's lock, in the same statement). `latir` renews the lease too. false: it did not hold
    private suspend fun transicion(
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
    private suspend fun auditar(
        organizacion: UUID,
        id: UUID,
        usuario: UUID?,
        antes: Map<String, Any?>,
        despues: Map<String, Any?>
    ) = auditoria.record(organizacion, usuario, EMISION_MASIVA, id, AuditOperation.UPDATE, before = antes, after = despues)

    // a datetime as its text, as the audit log keeps it
    private fun auditable(valor: Any?): Any? = if (valor is OffsetDateTime) valor.toString() else valor
}
