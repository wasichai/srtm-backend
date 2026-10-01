package srtm.emision

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

// model/model.json
const val EMISION_LOTE = "emision_lote"

private const val PENDIENTE = "PENDIENTE"
private const val EN_PROCESO = "EN_PROCESO"
private const val TERMINADO = "TERMINADO"
private const val FALLIDO = "FALLIDO"

private val ACTIVOS = listOf(PENDIENTE, EN_PROCESO)

internal val CAMPOS_LOTE =
    listOf("emision", "numero", "contribuyentes", "estado", "tomado_por", "latido", "intentos", "procesados", "documentos", "errores", "parte")
internal val CAMPOS_EMISION =
    listOf("anio", "formato", "estado", "total", "procesados", "errores", "archivo", "tamano", "mensaje", "iniciado", "terminado", "latido")

// a lote a worker of this instance took: what it needs to generate its part
data class LoteTomado(
    val id: UUID,
    val organizacion: UUID,
    val emision: UUID,
    val numero: Int,
    val contribuyentes: List<ContribuyenteAEmitir>,
    val intentos: Int,
    val creadoPor: UUID?,
    val anio: Int,
    val formato: FormatoEmision
)

// a lote as the assembly reads it
data class ParteLote(
    val id: UUID,
    val numero: Int,
    val estado: String,
    val procesados: Int,
    val documentos: Int,
    val errores: List<ErrorEmision>,
    val parte: String?
)

// the lotes' table of an organization and the one of its emissions, which the lotes point to
internal class TablasLote(
    val lotes: TablaCore,
    val emisiones: TablaCore
) {
    val organizacion: UUID get() = lotes.organizacion
}

internal suspend fun TablasCore.lotes(organizacion: UUID? = null): List<TablasLote> {
    val emisiones = de(EMISION_MASIVA, CAMPOS_EMISION).associateBy { it.organizacion }
    return de(EMISION_LOTE, CAMPOS_LOTE)
        .filter { organizacion == null || it.organizacion == organizacion }
        .mapNotNull { l -> emisiones[l.organizacion]?.let { TablasLote(l, it) } }
}

// the PENDIENTE and EN_PROCESO lotes of an emission go FALLIDO: their workers lose them at their next write. how many
internal suspend fun cancelarLotes(
    db: DatabaseClient,
    lotes: TablaCore,
    emision: UUID
): Int {
    val e = lotes.columna("estado")
    return db
        .sql("UPDATE ${lotes.tabla} SET $e = :fallido, updated_at = now() WHERE ${lotes.columna("emision")} = :emision AND $e IN (:activos)")
        .bind("fallido", FALLIDO)
        .bind("emision", emision)
        .bind("activos", ACTIVOS)
        .fetch()
        .awaitRowsUpdated()
        .toInt()
}

// the emission's procesados is the sum of its lotes': each worker counts its own, none overwrites another's
internal suspend fun sumarProcesadosLotes(
    db: DatabaseClient,
    t: TablasLote,
    emision: UUID
) {
    val l = t.lotes
    val e = t.emisiones
    db
        .sql(
            "UPDATE ${e.tabla} SET ${e.columna("procesados")} = " +
                "(SELECT coalesce(sum(${l.columna("procesados")}), 0) FROM ${l.tabla} WHERE ${l.columna("emision")} = :emision), " +
                "updated_at = now() WHERE id = :emision AND ${e.columna("estado")} IN ('EN_PROCESO', 'ENSAMBLANDO')"
        ).bind("emision", emision)
        .fetch()
        .awaitRowsUpdated()
}

// the lease as postgres takes it
internal fun segundos(lease: Duration): Double = lease.toMillis() / 1000.0

// the lotes of the masivas (wasichai/srtm-backend#53, #54), straight on their tables: the system's writes, without
// audit. a lote is taken with FOR UPDATE SKIP LOCKED, so two workers of any instance never take the same one; the one
// who took it is the only one who writes it while it is EN_PROCESO (tomado_por), and a write that finds it no longer
// so (cancelled, deleted, or taken again after its lease expired) is false: its worker stops
@Component
class LotesEmision(
    private val db: DatabaseClient,
    private val tablas: TablasCore
) {
    // the next lote of the oldest emission EN_PROCESO: a PENDIENTE one, or one EN_PROCESO whose worker stopped beating
    // `lease` ago. it counts one more attempt and starts over. organizations are tried in turn; null: none to take
    suspend fun tomar(
        instancia: String,
        lease: Duration
    ): LoteTomado? {
        for (t in tablas.lotes()) {
            val l = t.lotes
            val e = t.emisiones
            val tomado =
                db
                    .sql(
                        """
                        UPDATE ${l.tabla} AS l SET ${l.columna("estado")} = :en_proceso, ${l.columna("tomado_por")} = :yo,
                            ${l.columna("latido")} = now(), ${l.columna("intentos")} = coalesce(l.${l.columna("intentos")}, 0) + 1,
                            ${l.columna("procesados")} = 0, ${l.columna("documentos")} = 0, ${l.columna("errores")} = NULL, updated_at = now()
                        FROM ${e.tabla} AS e
                        WHERE l.id = (
                            SELECT l2.id FROM ${l.tabla} l2 JOIN ${e.tabla} e2 ON e2.id = l2.${l.columna("emision")}
                            WHERE e2.${e.columna("estado")} = :en_proceso
                              AND (l2.${l.columna("estado")} = :pendiente
                                OR (l2.${l.columna("estado")} = :en_proceso AND l2.${l.columna("latido")} < now() - make_interval(secs => :lease)))
                            ORDER BY e2.created_at, l2.${l.columna("numero")}
                            LIMIT 1
                            FOR UPDATE OF l2 SKIP LOCKED
                        ) AND e.id = l.${l.columna("emision")}
                        RETURNING l.id AS id, l.organization_id AS organizacion, l.${l.columna("emision")} AS emision,
                            l.${l.columna("numero")} AS numero, l.${l.columna("contribuyentes")} AS contribuyentes,
                            l.${l.columna("intentos")} AS intentos, l.created_by AS creado_por,
                            e.${e.columna("anio")} AS anio, e.${e.columna("formato")} AS formato
                        """.trimIndent()
                    ).bind("en_proceso", EN_PROCESO)
                    .bind("pendiente", PENDIENTE)
                    .bind("yo", instancia)
                    .bind("lease", segundos(lease))
                    .map { row, _ ->
                        LoteTomado(
                            id = row.get("id", UUID::class.java)!!,
                            organizacion = row.get("organizacion", UUID::class.java)!!,
                            emision = row.get("emision", UUID::class.java)!!,
                            numero = row.get("numero", Long::class.javaObjectType)!!.toInt(),
                            contribuyentes = contribuyentesDe(row.get("contribuyentes", String::class.java)!!),
                            intentos = row.get("intentos", Long::class.javaObjectType)!!.toInt(),
                            creadoPor = row.get("creado_por", UUID::class.java),
                            anio = row.get("anio", Long::class.javaObjectType)!!.toInt(),
                            formato = FormatoEmision.valueOf(row.get("formato", String::class.java)!!)
                        )
                    }.one()
                    .awaitFirstOrNull()
            if (tomado != null) return tomado
        }
        return null
    }

    // the lease renewed: false if the lote is no longer this instance's
    suspend fun latir(
        lote: LoteTomado,
        instancia: String
    ): Boolean = escribir(lote, instancia, { "${it.columna("latido")} = now()" })

    // the lote's progress, and its emission's with it. a progress saved is a beat too
    suspend fun avanzar(
        lote: LoteTomado,
        instancia: String,
        procesados: Int,
        errores: List<ErrorEmision>
    ): Boolean {
        val propio =
            escribir(
                lote,
                instancia,
                { "${it.columna("procesados")} = :procesados, ${it.columna("errores")} = :errores, ${it.columna("latido")} = now()" },
                "procesados" to procesados,
                "errores" to erroresJson(errores)
            )
        if (propio) sumarProcesados(lote)
        return propio
    }

    // the lote's part is in the almacén under `parte`: TERMINADO, with what it counted
    suspend fun terminar(
        lote: LoteTomado,
        instancia: String,
        procesados: Int,
        documentos: Int,
        errores: List<ErrorEmision>,
        parte: String
    ): Boolean {
        val propio =
            escribir(
                lote,
                instancia,
                {
                    "${it.columna("estado")} = :terminado, ${it.columna("procesados")} = :procesados, " +
                        "${it.columna("documentos")} = :documentos, ${it.columna("errores")} = :errores, ${it.columna("parte")} = :parte"
                },
                "terminado" to TERMINADO,
                "procesados" to procesados,
                "documentos" to documentos,
                "errores" to erroresJson(errores),
                "parte" to parte
            )
        if (propio) sumarProcesados(lote)
        return propio
    }

    // the lote could not be generated (its attempts are over): FALLIDO, with why
    suspend fun fallar(
        lote: LoteTomado,
        instancia: String,
        errores: List<ErrorEmision>
    ): Boolean =
        escribir(
            lote,
            instancia,
            { "${it.columna("estado")} = :fallido, ${it.columna("errores")} = :errores" },
            "fallido" to FALLIDO,
            "errores" to erroresJson(errores)
        )

    // the lote is let go (the instance stops): PENDIENTE again, for the next taker, which counts one more attempt
    suspend fun liberar(
        lote: LoteTomado,
        instancia: String
    ): Boolean =
        escribir(
            lote,
            instancia,
            { "${it.columna("estado")} = :pendiente, ${it.columna("tomado_por")} = NULL" },
            "pendiente" to PENDIENTE
        )

    // null: the row is gone (its emission was deleted)
    suspend fun estado(lote: LoteTomado): String? {
        val l = tablas.lotes(lote.organizacion).firstOrNull()?.lotes ?: return null
        return db
            .sql("SELECT ${l.columna("estado")} AS estado FROM ${l.tabla} WHERE id = :id")
            .bind("id", lote.id)
            .map { row, _ -> row.get("estado", String::class.java)!! }
            .one()
            .awaitFirstOrNull()
    }

    // the emission's lotes still to generate go FALLIDO. how many
    suspend fun cancelar(
        organizacion: UUID,
        emision: UUID
    ): Int = tablas.lotes(organizacion).sumOf { cancelarLotes(db, it.lotes, emision) }

    // the emission's lote rows, before the emission itself can go. how many
    suspend fun borrar(
        organizacion: UUID,
        emision: UUID
    ): Int =
        tablas.lotes(organizacion).sumOf { t ->
            db
                .sql("DELETE FROM ${t.lotes.tabla} WHERE ${t.lotes.columna("emision")} = :emision")
                .bind("emision", emision)
                .fetch()
                .awaitRowsUpdated()
                .toInt()
        }

    // the emission's lotes by numero, the order of their parts
    suspend fun partes(
        organizacion: UUID,
        emision: UUID
    ): List<ParteLote> {
        val l = tablas.lotes(organizacion).firstOrNull()?.lotes ?: return emptyList()
        return db
            .sql(
                "SELECT id, ${l.columna("numero")} AS numero, ${l.columna("estado")} AS estado, ${l.columna("procesados")} AS procesados, " +
                    "${l.columna("documentos")} AS documentos, ${l.columna("errores")} AS errores, ${l.columna("parte")} AS parte " +
                    "FROM ${l.tabla} WHERE ${l.columna("emision")} = :emision ORDER BY ${l.columna("numero")}"
            ).bind("emision", emision)
            .map { row, _ ->
                ParteLote(
                    id = row.get("id", UUID::class.java)!!,
                    numero = row.get("numero", Long::class.javaObjectType)!!.toInt(),
                    estado = row.get("estado", String::class.java)!!,
                    procesados = row.get("procesados", Long::class.javaObjectType)?.toInt() ?: 0,
                    documentos = row.get("documentos", Long::class.javaObjectType)?.toInt() ?: 0,
                    errores = erroresDe(row.get("errores", String::class.java)),
                    parte = row.get("parte", String::class.java)
                )
            }.all()
            .asFlow()
            .toList()
    }

    private suspend fun sumarProcesados(lote: LoteTomado) {
        tablas.lotes(lote.organizacion).forEach { sumarProcesadosLotes(db, it, lote.emision) }
    }

    // a write of the lote by the one who took it: only while it is EN_PROCESO and theirs
    private suspend fun escribir(
        lote: LoteTomado,
        instancia: String,
        asignaciones: (TablaCore) -> String,
        vararg valores: Pair<String, Any>
    ): Boolean {
        val l = tablas.lotes(lote.organizacion).firstOrNull()?.lotes ?: return false
        var spec =
            db
                .sql(
                    "UPDATE ${l.tabla} SET ${asignaciones(l)}, updated_at = now() " +
                        "WHERE id = :id AND ${l.columna("estado")} = :en_proceso AND ${l.columna("tomado_por")} = :yo"
                ).bind("id", lote.id)
                .bind("en_proceso", EN_PROCESO)
                .bind("yo", instancia)
        valores.forEach { (nombre, valor) -> spec = spec.bind(nombre, valor) }
        return spec.fetch().awaitRowsUpdated() > 0
    }
}
