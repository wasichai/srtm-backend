package srtm.emision

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import wasichai.core.audit.AuditService
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private const val EN_PROCESO = "EN_PROCESO"
private const val ENSAMBLANDO = "ENSAMBLANDO"
private const val TERMINADA = "TERMINADA"

private val LOTES_ACTIVOS = listOf("PENDIENTE", "EN_PROCESO")

// an emission whose lotes all ended, claimed by this instance to assemble it. `latido` is its token: the one the claim
// or the last beat gave, which the next beat and the end compare to
data class EmisionAEnsamblar(
    val id: UUID,
    val organizacion: UUID,
    val anio: Int,
    val formato: FormatoEmision,
    val latido: OffsetDateTime,
    // when the emission was started, for the log's total time
    val iniciado: OffsetDateTime? = null
)

// the transitions of emision_masiva (wasichai/srtm-backend#54): EstadoTrabajos on it, and the assembly, which only the
// emission has (it ends in a file)
@Component
class EstadoEmisiones(
    private val db: DatabaseClient,
    private val tablas: TablasCore,
    auditoria: AuditService
) {
    private val trabajos = EstadoTrabajos(db, tablas, auditoria, TIPO_EMISION)

    // the emissions of the year not yet ended: id and created_at, oldest first
    suspend fun activas(
        organizacion: UUID,
        anio: Int
    ): List<Pair<UUID, Instant>> = trabajos.activas(organizacion, anio)

    // whether its row is still there: it may be deleted while an instance works on it
    suspend fun existe(
        organizacion: UUID,
        id: UUID
    ): Boolean = trabajos.existe(organizacion, id)

    // the preparer's lease, while it reads the padron and cuts it: only while PENDIENTE
    suspend fun latirPreparacion(
        organizacion: UUID,
        id: UUID
    ): Boolean = trabajos.latirPreparacion(organizacion, id)

    // its lotes are written: PENDIENTE -> EN_PROCESO, with how many contribuyentes it has
    suspend fun iniciar(
        organizacion: UUID,
        id: UUID,
        total: Int,
        usuario: UUID?
    ): Boolean = trabajos.iniciar(organizacion, id, total, usuario)

    // procesados = the sum of its lotes'
    suspend fun sumarProcesados(
        organizacion: UUID,
        id: UUID
    ) = trabajos.sumarProcesados(organizacion, id)

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
            val e = t.trabajos
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
                            e.${e.columna("iniciado")} AS iniciado, antes.estado AS antes
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
                            latido = row.get("latido", OffsetDateTime::class.java)!!,
                            iniciado = row.get("iniciado", OffsetDateTime::class.java)
                        ) to row.get("antes", String::class.java)
                    }.one()
                    .awaitFirstOrNull()
                    ?: continue
            val (emision, antes) = reclamada
            trabajos.auditar(emision.organizacion, emision.id, null, mapOf("estado" to antes), mapOf("estado" to ENSAMBLANDO))
            return emision
        }
        return null
    }

    // the assembler's lease: compare-and-set on its token. the new token; null if another instance claimed it since
    suspend fun latirEnsamblado(e: EmisionAEnsamblar): EmisionAEnsamblar? {
        val t = tablas.lotes(e.organizacion).firstOrNull()?.trabajos ?: return null
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
        val t = tablas.lotes(e.organizacion).firstOrNull()?.trabajos ?: return false
        return trabajos.transicion(
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
    ): Boolean = trabajos.fallar(organizacion, id, mensaje, usuario)

    // the emissions nobody works on any more (EstadoTrabajos.fallarAbandonadas). (organizacion, id) of each
    suspend fun fallarAbandonadas(lease: Duration): List<Pair<UUID, UUID>> = trabajos.fallarAbandonadas(lease)
}
