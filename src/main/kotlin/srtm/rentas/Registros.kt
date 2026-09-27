package srtm.rentas

import org.springframework.stereotype.Component
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import java.util.UUID

// wasichai's RecordService with the portal's dtos on it. RecordService checks the caller's object and field
// permissions and validates every write, so nothing here repeats that
@Component
class Registros(
    private val records: RecordService
) {
    suspend fun <T : Any> page(
        objectName: String,
        type: Class<T>,
        query: RecordQuery
    ): PageResponse<T> {
        val result = records.list(objectName, query)
        return PageResponse(result.content.map { read(type, it) }, result.page, result.size, result.totalElements, result.totalPages)
    }

    // every record that matches, page by page: for the few rows a contribuyente or predio has, and catalogs
    suspend fun <T : Any> all(
        objectName: String,
        type: Class<T>,
        filters: Map<String, String> = emptyMap(),
        sort: String? = null,
        descending: Boolean = false
    ): List<T> {
        val rows = mutableListOf<RecordResponse>()
        var page = 0
        do {
            val result =
                records.list(
                    objectName,
                    RecordQuery(page = PageRequest.of(page, PageRequest.MAX_SIZE), sort = sort, descending = descending, filters = filters)
                )
            rows += result.content
            page++
        } while (page < result.totalPages)
        return rows.map { read(type, it) }
    }

    suspend fun <T : Any> get(
        objectName: String,
        type: Class<T>,
        id: UUID
    ): T = read(type, records.get(objectName, id))

    // the records behind a set of relation ids, one query per page of ids instead of one per id
    // a record with when it was last saved (the srtm's "fecha de actualización")
    suspend fun <T : Any> getConFecha(
        objectName: String,
        type: Class<T>,
        id: UUID
    ): Pair<T, java.time.Instant?> {
        val response = records.get(objectName, id)
        return read(type, response) to response.updatedAt
    }

    suspend fun <T : Any> byIds(
        objectName: String,
        type: Class<T>,
        ids: Collection<String>
    ): Map<String, T> =
        ids
            .distinct()
            .chunked(PageRequest.MAX_SIZE)
            .flatMap { chunk ->
                records.list(objectName, RecordQuery(page = PageRequest.of(0, chunk.size), ids = chunk.map(UUID::fromString))).content
            }.associate { it.id to read(type, it) }

    suspend fun <T : Any> create(
        objectName: String,
        type: Class<T>,
        attributes: Map<String, Any?>
    ): T = read(type, records.create(objectName, request(attributes)))

    // core's update replaces every editable field. the portal sends what its dto knows, merged over what is
    // stored, so a field added in the admin (and absent from the dto) is not blanked by a portal save
    suspend fun <T : Any> replace(
        objectName: String,
        type: Class<T>,
        id: UUID,
        attributes: Map<String, Any?>
    ): T {
        val stored = records.get(objectName, id).attributes
        return read(type, records.update(objectName, id, request(stored + attributes)))
    }

    suspend fun delete(
        objectName: String,
        id: UUID
    ) = records.delete(objectName, id)

    suspend fun count(objectName: String): Long = records.list(objectName, RecordQuery(page = PageRequest.of(0, 1))).totalElements

    // the highest value a field holds, among those starting with `prefix` when given. nulls are left out:
    // postgres sorts them first when descending
    suspend fun highest(
        objectName: String,
        field: String,
        prefix: String? = null
    ): String? {
        val criterion =
            RecordCriterion { definition, bind ->
                val column = definition.fields.first { it.name == field }.columnName
                if (prefix == null) "$column IS NOT NULL" else "$column LIKE ${bind(prefix.replace("%", "").replace("_", "\\_") + "%")}"
            }
        return records
            .list(objectName, RecordQuery(page = PageRequest.of(0, 1), sort = field, descending = true, criteria = listOf(criterion)))
            .content
            .firstOrNull()
            ?.attributes
            ?.get(field)
            ?.toString()
    }

    // a geometry travels in core's "geometries" section, not in the attributes: the dtos carry it as one more field
    private fun <T : Any> read(
        type: Class<T>,
        response: RecordResponse
    ): T = Records.read(type, response.id, response.attributes + response.sections[GEOMETRIES].orEmpty())

    private fun request(attributes: Map<String, Any?>): RecordRequest {
        val (plain, geometries) = separarGeometrias(attributes)
        val request = RecordRequest(plain)
        // a dto always carries its geometry field, null when the form had no map: null keeps the stored geometry
        // (the portal never clears a lote, it replaces it)
        if (geometries.isNotEmpty()) request.sections[GEOMETRIES] = geometries
        return request
    }

    companion object {
        // attributes -> (the plain ones, the geometries that carry a value)
        fun separarGeometrias(attributes: Map<String, Any?>): Pair<Map<String, Any?>, Map<String, Any?>> =
            (attributes - GEOMETRIAS) to attributes.filter { (key, value) -> key in GEOMETRIAS && value != null }

        // wasichai-gis's section name (wasichai.gis.GEOMETRIES)
        const val GEOMETRIES = "geometries"

        // the model's geometry fields (model.json, type GEOMETRY)
        val GEOMETRIAS = setOf("lote_geom", "ubicacion")
    }
}
