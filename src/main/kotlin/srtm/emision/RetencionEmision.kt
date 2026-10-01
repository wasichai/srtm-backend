package srtm.emision

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

private const val TERMINADA = "TERMINADA"
private const val DEPURADO = "archivo depurado"

// the retention of the masivas' files (srtm.emision.conservar, srtm.emision.dias), after every assembly and at
// startup: the system's, so it updates the table straight and writes core's audit log itself (wasichai 0.2.0 has no
// system context for RecordService)
@Component
class RetencionEmision(
    private val db: DatabaseClient,
    private val tablasCore: TablasCore,
    private val almacen: AlmacenEmision,
    private val auditoria: AuditService,
    config: EmisionProperties
) {
    private val retencion = Retencion(config.conservar, config.dias)

    // the files of the TERMINADA jobs beyond it are deleted, and their jobs keep everything but the file (archivo
    // empty, mensaje "archivo depurado"): the download is then a 410. how many
    suspend fun depurar(): Int {
        var depuradas = 0
        for (t in tablasCore.de(EMISION_MASIVA, listOf("anio", "formato", "estado", "archivo", "mensaje", "terminado"))) {
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
                auditoria.record(
                    a.organizacion,
                    null,
                    EMISION_MASIVA,
                    a.id,
                    AuditOperation.UPDATE,
                    before = mapOf("archivo" to nombre, "mensaje" to mensaje),
                    after = mapOf("archivo" to null, "mensaje" to DEPURADO)
                )
                depuradas++
            }
        }
        if (depuradas > 0) log.info("{} archivos de emisiones masivas depurados", depuradas)
        return depuradas
    }

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

    private companion object {
        val log = LoggerFactory.getLogger(RetencionEmision::class.java)
    }
}
