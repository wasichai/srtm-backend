package srtm.emision

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private const val PENDIENTE = "PENDIENTE"
private const val EN_PROCESO = "EN_PROCESO"
private const val TERMINADO = "TERMINADO"
private const val FALLIDO = "FALLIDO"

private val ACTIVOS = listOf(PENDIENTE, EN_PROCESO)

// a kind of background job cut in lotes that the workers of any instance take (wasichai/srtm-backend#53, #54): the
// emission of documents, the determination of arbitrios. what the machinery needs of its model: its job object and its
// lote object (model/model.json, with estado_emision and estado_lote), the lote's field that points to its job, the
// lote's json of what it processes, and what a lote counts besides procesados
class TipoLotes(
    val trabajo: String,
    val lote: String,
    val relacion: String,
    val carga: String,
    val producidos: String,
    val camposTrabajo: List<String>,
    val camposLote: List<String>,
    // the job's fields a take returns besides its anio (the emission's formato)
    val delTrabajo: List<String> = emptyList()
)

// the lotes' table of an organization and the one of its jobs, which the lotes point to
internal class TablasLote(
    val lotes: TablaCore,
    val trabajos: TablaCore
) {
    val organizacion: UUID get() = lotes.organizacion
}

internal suspend fun TablasCore.lotes(
    tipo: TipoLotes,
    organizacion: UUID? = null
): List<TablasLote> {
    val trabajos = de(tipo.trabajo, tipo.camposTrabajo).associateBy { it.organizacion }
    return de(tipo.lote, tipo.camposLote)
        .filter { organizacion == null || it.organizacion == organizacion }
        .mapNotNull { l -> trabajos[l.organizacion]?.let { TablasLote(l, it) } }
}

// a take of a lote: the take that got it is the only one that writes it while it is EN_PROCESO
interface TomaDeLote {
    val id: UUID
    val organizacion: UUID
    val trabajo: UUID
    val intentos: Int
}

// a lote of any kind a worker of this instance took. carga is its json, as the job's preparation wrote it
data class LoteDeTrabajo(
    override val id: UUID,
    override val organizacion: UUID,
    override val trabajo: UUID,
    val numero: Int,
    val carga: String,
    override val intentos: Int,
    val creadoPor: UUID?,
    val anio: Int,
    val delTrabajo: Map<String, String?> = emptyMap()
) : TomaDeLote

// a lote as the job's end reads it. errores is its json
data class ParteDeTrabajo(
    val id: UUID,
    val numero: Int,
    val estado: String,
    val procesados: Int,
    val producidos: Int,
    val errores: String?,
    val parte: String?
)

// the PENDIENTE and EN_PROCESO lotes of a job go FALLIDO: their workers lose them at their next write. how many
internal suspend fun cancelarLotes(
    db: DatabaseClient,
    tipo: TipoLotes,
    lotes: TablaCore,
    trabajo: UUID
): Int {
    val e = lotes.columna("estado")
    return db
        .sql("UPDATE ${lotes.tabla} SET $e = :fallido, updated_at = now() WHERE ${lotes.columna(tipo.relacion)} = :trabajo AND $e IN (:activos)")
        .bind("fallido", FALLIDO)
        .bind("trabajo", trabajo)
        .bind("activos", ACTIVOS)
        .fetch()
        .awaitRowsUpdated()
        .toInt()
}

// the job's procesados is the sum of its lotes': each worker counts its own, none overwrites another's. two workers
// saving at once may each sum before the other's commit and leave a lower sum for a moment: the next write corrects
// it, and the job's end sets the final count
internal suspend fun sumarProcesadosLotes(
    db: DatabaseClient,
    tipo: TipoLotes,
    t: TablasLote,
    trabajo: UUID
) {
    val l = t.lotes
    val e = t.trabajos
    db
        .sql(
            "UPDATE ${e.tabla} SET ${e.columna("procesados")} = " +
                "(SELECT coalesce(sum(${l.columna("procesados")}), 0) FROM ${l.tabla} WHERE ${l.columna(tipo.relacion)} = :trabajo), " +
                "updated_at = now() WHERE id = :trabajo AND ${e.columna("estado")} IN ('EN_PROCESO', 'ENSAMBLANDO')"
        ).bind("trabajo", trabajo)
        .fetch()
        .awaitRowsUpdated()
}

// the lease as postgres takes it
internal fun segundos(lease: Duration): Double = lease.toMillis() / 1000.0

// the lotes of one kind of job, straight on their tables: the system's writes, without audit. a lote is taken with FOR
// UPDATE SKIP LOCKED, so two workers of any instance never take the same one; the take that got it is the only one
// that writes it while it is EN_PROCESO (tomado_por and intentos), and a write that finds it no longer so (cancelled,
// deleted, or taken again after its lease expired) is false: its worker stops
class MaquinaLotes internal constructor(
    private val db: DatabaseClient,
    private val tablas: TablasCore,
    val tipo: TipoLotes
) {
    // the organization of the last lote this instance took: the next take starts with the one after it
    private val ultima = AtomicReference<UUID?>(null)

    // the next lote of the oldest job EN_PROCESO: a PENDIENTE one, or one EN_PROCESO whose worker stopped beating
    // `lease` ago. it counts one more attempt and starts over. the organizations take turns: each take starts with the
    // one after the organization of the last lote taken, so one organization's job never waits for another's to end.
    // null: none to take
    suspend fun tomar(
        instancia: String,
        lease: Duration
    ): LoteDeTrabajo? {
        // in TablasCore's order (by organization), rotated to start after the last one; it may be gone: from the first
        val todas = tablas.lotes(tipo)
        val antes = ultima.get()
        val desde = (todas.indexOfFirst { it.organizacion == antes } + 1) % todas.size.coerceAtLeast(1)
        for (t in todas.drop(desde) + todas.take(desde)) {
            val l = t.lotes
            val e = t.trabajos
            val extras = tipo.delTrabajo.withIndex().joinToString("") { (i, c) -> ", e.${e.columna(c)} AS x$i" }
            val tomado =
                db
                    .sql(
                        """
                        UPDATE ${l.tabla} AS l SET ${l.columna("estado")} = :en_proceso, ${l.columna("tomado_por")} = :yo,
                            ${l.columna("latido")} = now(), ${l.columna("intentos")} = coalesce(l.${l.columna("intentos")}, 0) + 1,
                            ${l.columna("procesados")} = 0, ${l.columna(tipo.producidos)} = 0, ${l.columna("errores")} = NULL, updated_at = now()
                        FROM ${e.tabla} AS e
                        WHERE l.id = (
                            SELECT l2.id FROM ${l.tabla} l2 JOIN ${e.tabla} e2 ON e2.id = l2.${l.columna(tipo.relacion)}
                            WHERE e2.${e.columna("estado")} = :en_proceso
                              AND (l2.${l.columna("estado")} = :pendiente
                                OR (l2.${l.columna("estado")} = :en_proceso AND l2.${l.columna("latido")} < now() - make_interval(secs => :lease)))
                            ORDER BY e2.created_at, l2.${l.columna("numero")}
                            LIMIT 1
                            FOR UPDATE OF l2 SKIP LOCKED
                        ) AND e.id = l.${l.columna(tipo.relacion)}
                        RETURNING l.id AS id, l.organization_id AS organizacion, l.${l.columna(tipo.relacion)} AS trabajo,
                            l.${l.columna("numero")} AS numero, l.${l.columna(tipo.carga)} AS carga,
                            l.${l.columna("intentos")} AS intentos, l.created_by AS creado_por,
                            e.${e.columna("anio")} AS anio$extras
                        """.trimIndent()
                    ).bind("en_proceso", EN_PROCESO)
                    .bind("pendiente", PENDIENTE)
                    .bind("yo", instancia)
                    .bind("lease", segundos(lease))
                    .map { row, _ ->
                        LoteDeTrabajo(
                            id = row.get("id", UUID::class.java)!!,
                            organizacion = row.get("organizacion", UUID::class.java)!!,
                            trabajo = row.get("trabajo", UUID::class.java)!!,
                            numero = row.get("numero", Long::class.javaObjectType)!!.toInt(),
                            carga = row.get("carga", String::class.java)!!,
                            intentos = row.get("intentos", Long::class.javaObjectType)!!.toInt(),
                            creadoPor = row.get("creado_por", UUID::class.java),
                            anio = row.get("anio", Long::class.javaObjectType)!!.toInt(),
                            delTrabajo = tipo.delTrabajo.withIndex().associate { (i, c) -> c to row.get("x$i", String::class.java) }
                        )
                    }.one()
                    .awaitFirstOrNull()
            if (tomado != null) {
                ultima.set(tomado.organizacion)
                return tomado
            }
        }
        return null
    }

    // the lease renewed: false if the lote is no longer this instance's
    suspend fun latir(
        lote: TomaDeLote,
        instancia: String
    ): Boolean = escribir(lote, instancia, { "${it.columna("latido")} = now()" })

    // the lote's progress, and its job's with it. a progress saved is a beat too. errores: their json
    suspend fun avanzar(
        lote: TomaDeLote,
        instancia: String,
        procesados: Int,
        errores: String
    ): Boolean {
        val propio =
            escribir(
                lote,
                instancia,
                { "${it.columna("procesados")} = :procesados, ${it.columna("errores")} = :errores, ${it.columna("latido")} = now()" },
                "procesados" to procesados,
                "errores" to errores
            )
        if (propio) sumarProcesados(lote.organizacion, lote.trabajo)
        return propio
    }

    // the lote is done: TERMINADO, with what it counted, and the key of its parte in the almacén if it has one
    suspend fun terminar(
        lote: TomaDeLote,
        instancia: String,
        procesados: Int,
        producidos: Int,
        errores: String,
        parte: String? = null
    ): Boolean {
        val valores =
            listOfNotNull(
                "terminado" to TERMINADO,
                "procesados" to procesados,
                "producidos" to producidos,
                "errores" to errores,
                parte?.let { "parte" to it }
            )
        val propio =
            escribir(
                lote,
                instancia,
                {
                    "${it.columna("estado")} = :terminado, ${it.columna("procesados")} = :procesados, " +
                        "${it.columna(tipo.producidos)} = :producidos, ${it.columna("errores")} = :errores" +
                        (if (parte != null) ", ${it.columna("parte")} = :parte" else "")
                },
                *valores.toTypedArray()
            )
        if (propio) sumarProcesados(lote.organizacion, lote.trabajo)
        return propio
    }

    // the lote could not be processed (its attempts are over): FALLIDO, with why
    suspend fun fallar(
        lote: TomaDeLote,
        instancia: String,
        errores: String
    ): Boolean =
        escribir(
            lote,
            instancia,
            { "${it.columna("estado")} = :fallido, ${it.columna("errores")} = :errores" },
            "fallido" to FALLIDO,
            "errores" to errores
        )

    // the lote is let go (the instance stops, or the attempt failed): PENDIENTE again, for the next taker, which
    // counts one more attempt
    suspend fun liberar(
        lote: TomaDeLote,
        instancia: String
    ): Boolean =
        escribir(
            lote,
            instancia,
            { "${it.columna("estado")} = :pendiente, ${it.columna("tomado_por")} = NULL" },
            "pendiente" to PENDIENTE
        )

    // null: the row is gone (its job was deleted)
    suspend fun estado(lote: TomaDeLote): String? {
        val l = tablas.lotes(tipo, lote.organizacion).firstOrNull()?.lotes ?: return null
        return db
            .sql("SELECT ${l.columna("estado")} AS estado FROM ${l.tabla} WHERE id = :id")
            .bind("id", lote.id)
            .map { row, _ -> row.get("estado", String::class.java)!! }
            .one()
            .awaitFirstOrNull()
    }

    // the job's lotes still to process go FALLIDO. how many
    suspend fun cancelar(
        organizacion: UUID,
        trabajo: UUID
    ): Int = tablas.lotes(tipo, organizacion).sumOf { cancelarLotes(db, tipo, it.lotes, trabajo) }

    // the job's lote rows, before the job itself can go. how many
    suspend fun borrar(
        organizacion: UUID,
        trabajo: UUID
    ): Int =
        tablas.lotes(tipo, organizacion).sumOf { t ->
            db
                .sql("DELETE FROM ${t.lotes.tabla} WHERE ${t.lotes.columna(tipo.relacion)} = :trabajo")
                .bind("trabajo", trabajo)
                .fetch()
                .awaitRowsUpdated()
                .toInt()
        }

    // the job's lotes by numero
    suspend fun partes(
        organizacion: UUID,
        trabajo: UUID
    ): List<ParteDeTrabajo> {
        val l = tablas.lotes(tipo, organizacion).firstOrNull()?.lotes ?: return emptyList()
        val parte = if ("parte" in tipo.camposLote) l.columna("parte") else "NULL"
        return db
            .sql(
                "SELECT id, ${l.columna("numero")} AS numero, ${l.columna("estado")} AS estado, ${l.columna("procesados")} AS procesados, " +
                    "${l.columna(tipo.producidos)} AS producidos, ${l.columna("errores")} AS errores, $parte AS parte " +
                    "FROM ${l.tabla} WHERE ${l.columna(tipo.relacion)} = :trabajo ORDER BY ${l.columna("numero")}"
            ).bind("trabajo", trabajo)
            .map { row, _ ->
                ParteDeTrabajo(
                    id = row.get("id", UUID::class.java)!!,
                    numero = row.get("numero", Long::class.javaObjectType)!!.toInt(),
                    estado = row.get("estado", String::class.java)!!,
                    procesados = row.get("procesados", Long::class.javaObjectType)?.toInt() ?: 0,
                    producidos = row.get("producidos", Long::class.javaObjectType)?.toInt() ?: 0,
                    errores = row.get("errores", String::class.java),
                    parte = row.get("parte", String::class.java)
                )
            }.all()
            .asFlow()
            .toList()
    }

    private suspend fun sumarProcesados(
        organizacion: UUID,
        trabajo: UUID
    ) {
        tablas.lotes(tipo, organizacion).forEach { sumarProcesadosLotes(db, tipo, it, trabajo) }
    }

    // a write of the lote by the take that got it: only while it is EN_PROCESO and that take's. tomado_por alone
    // names the instance, and another worker of the same one may have taken it again after its lease expired: every
    // take counts one more intento, so the take is the instance and its intentos
    private suspend fun escribir(
        lote: TomaDeLote,
        instancia: String,
        asignaciones: (TablaCore) -> String,
        vararg valores: Pair<String, Any>
    ): Boolean {
        val l = tablas.lotes(tipo, lote.organizacion).firstOrNull()?.lotes ?: return false
        var spec =
            db
                .sql(
                    "UPDATE ${l.tabla} SET ${asignaciones(l)}, updated_at = now() " +
                        "WHERE id = :id AND ${l.columna("estado")} = :en_proceso AND ${l.columna("tomado_por")} = :yo " +
                        "AND ${l.columna("intentos")} = :intentos"
                ).bind("id", lote.id)
                .bind("en_proceso", EN_PROCESO)
                .bind("yo", instancia)
                .bind("intentos", lote.intentos)
        valores.forEach { (nombre, valor) -> spec = spec.bind(nombre, valor) }
        return spec.fetch().awaitRowsUpdated() > 0
    }
}
