package srtm.emision

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import java.util.Optional
import java.util.UUID

// an object's table in one organization, with its columns as core's metadata names them. `tabla` is schema-qualified
class TablaCore(
    val organizacion: UUID,
    val tabla: String,
    private val columnas: Map<String, String>
) {
    fun columna(campo: String): String = SqlIdentifier.quote(columnas.getValue(campo))
}

// the system's writes go straight to an object's physical table (wasichai 0.2.0 has no system context for
// RecordService). core makes one table per organization and names its columns: both come from its metadata
@Component
class TablasCore(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    // one per organization that has `objeto` with every one of `campos` (a relation is a field too)
    suspend fun de(
        objeto: String,
        campos: List<String>
    ): List<TablaCore> {
        val columnas =
            (
                listOf("o.organization_id AS organizacion", "o.physical_table AS tabla") +
                    campos.indices.map { "max(CASE WHEN f.name = :campo$it THEN f.column_name END) AS c$it" }
            ).joinToString(",\n")
        var spec =
            db
                .sql(
                    """
                    SELECT
                    $columnas
                    FROM ${schemas.metadata}.custom_objects o
                    JOIN ${schemas.metadata}.custom_fields f ON f.object_id = o.id
                    WHERE o.name = :nombre
                    GROUP BY o.organization_id, o.physical_table
                    ORDER BY o.organization_id
                    """.trimIndent()
                ).bind("nombre", objeto)
        campos.forEachIndexed { i, c -> spec = spec.bind("campo$i", c) }
        return spec
            .map { row, _ ->
                Triple(
                    row.get("organizacion", UUID::class.java),
                    Optional.ofNullable(row.get("tabla", String::class.java)),
                    campos.withIndex().mapNotNull { (i, c) -> row.get("c$i", String::class.java)?.let { c to it } }.toMap()
                )
            }.all()
            .asFlow()
            .toList()
            .mapNotNull { (organizacion, tabla, cols) ->
                if (organizacion == null || tabla.isEmpty || cols.size < campos.size) null else TablaCore(organizacion, schemas.dataTable(tabla.get()), cols)
            }
    }
}
