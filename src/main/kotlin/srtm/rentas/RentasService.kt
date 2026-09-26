package srtm.rentas

import org.springframework.stereotype.Service
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordResponse
import wasichai.core.data.RecordService
import wasichai.core.metadata.MetadataService
import java.time.LocalDate
import java.util.UUID

// the portal's use cases on top of wasichai's services, in process. RecordService checks the caller's
// object and field permissions and validates every write, so nothing here repeats that
@Service
class RentasService(
    private val records: RecordService,
    private val metadata: MetadataService
) {
    suspend fun resumen(): Resumen =
        Resumen(
            anio = anioActual(),
            contribuyentes = count(CONTRIBUYENTE),
            predios = count(PREDIO),
            declaraciones = count(DECLARACION)
        )

    // enum options by object and field, what the forms offer
    suspend fun catalogos(): Map<String, Map<String, List<String>>> =
        listOf(CONTRIBUYENTE, PREDIO, DECLARACION).associateWith { name ->
            metadata
                .definitionOf(name)
                .fields
                .filter { it.enumOptions != null }
                .associate { it.name to it.enumOptions!! }
        }

    suspend fun contribuyentes(
        q: String?,
        page: Int?,
        size: Int?
    ): PageResponse<Contribuyente> = search(CONTRIBUYENTE, "nombre_completo", q, page, size) { toContribuyente(it.id, it.attributes) }

    suspend fun contribuyente(id: UUID): Contribuyente = records.get(CONTRIBUYENTE, id).let { toContribuyente(it.id, it.attributes) }

    suspend fun fichaContribuyente(
        id: UUID,
        anio: Int?
    ): ContribuyenteFicha {
        val year = anio ?: anioActual()
        val declaraciones = declaracionesPor("contribuyente", id, year)
        return ContribuyenteFicha(
            contribuyente = contribuyente(id),
            anio = year,
            predios = declaraciones.mapNotNull { it.predio }.distinct().size,
            totales = totales(declaraciones)
        )
    }

    // each declaration with its predio
    suspend fun declaracionesDeContribuyente(
        id: UUID,
        anio: Int?
    ): List<DeclaracionDetalle> {
        val declaraciones = declaracionesPor("contribuyente", id, anio)
        val predios = byIds(PREDIO, declaraciones.mapNotNull { it.predio }) { toPredio(it.id, it.attributes) }
        return declaraciones.map { DeclaracionDetalle(it, predio = predios[it.predio]) }
    }

    suspend fun crearContribuyente(body: Contribuyente): Contribuyente =
        records.create(CONTRIBUYENTE, RecordRequest(body.attributes())).let { toContribuyente(it.id, it.attributes) }

    suspend fun actualizarContribuyente(
        id: UUID,
        body: Contribuyente
    ): Contribuyente = records.update(CONTRIBUYENTE, id, RecordRequest(body.attributes())).let { toContribuyente(it.id, it.attributes) }

    suspend fun predios(
        q: String?,
        page: Int?,
        size: Int?
    ): PageResponse<Predio> = search(PREDIO, "codigo", q, page, size) { toPredio(it.id, it.attributes) }

    suspend fun predio(id: UUID): Predio = records.get(PREDIO, id).let { toPredio(it.id, it.attributes) }

    suspend fun fichaPredio(
        id: UUID,
        anio: Int?
    ): PredioFicha {
        val year = anio ?: anioActual()
        val declaraciones = declaracionesPor("predio", id, year)
        return PredioFicha(
            predio = predio(id),
            anio = year,
            titulares = declaraciones.mapNotNull { it.contribuyente }.distinct().size,
            totales = totales(declaraciones)
        )
    }

    // each declaration with its contribuyente: the predio's titulares
    suspend fun declaracionesDePredio(
        id: UUID,
        anio: Int?
    ): List<DeclaracionDetalle> {
        val declaraciones = declaracionesPor("predio", id, anio)
        val contribuyentes = byIds(CONTRIBUYENTE, declaraciones.mapNotNull { it.contribuyente }) { toContribuyente(it.id, it.attributes) }
        return declaraciones.map { DeclaracionDetalle(it, contribuyente = contribuyentes[it.contribuyente]) }
    }

    suspend fun crearPredio(body: Predio): Predio = records.create(PREDIO, RecordRequest(body.attributes())).let { toPredio(it.id, it.attributes) }

    suspend fun actualizarPredio(
        id: UUID,
        body: Predio
    ): Predio = records.update(PREDIO, id, RecordRequest(body.attributes())).let { toPredio(it.id, it.attributes) }

    suspend fun crearDeclaracion(body: Declaracion): Declaracion =
        records.create(DECLARACION, RecordRequest(body.attributes())).let { toDeclaracion(it.id, it.attributes) }

    suspend fun actualizarDeclaracion(
        id: UUID,
        body: Declaracion
    ): Declaracion = records.update(DECLARACION, id, RecordRequest(body.attributes())).let { toDeclaracion(it.id, it.attributes) }

    private suspend fun <T> search(
        objectName: String,
        sort: String,
        q: String?,
        page: Int?,
        size: Int?,
        map: (RecordResponse) -> T
    ): PageResponse<T> {
        val result =
            records.list(
                objectName,
                RecordQuery(page = PageRequest.of(page, size), sort = sort, search = q?.trim()?.ifBlank { null })
            )
        return PageResponse(result.content.map(map), result.page, result.size, result.totalElements, result.totalPages)
    }

    // every declaration of one contribuyente or predio, a year or all of them. one record never has
    // more than a few, so the rest are walked page by page only as a safeguard
    private suspend fun declaracionesPor(
        relation: String,
        id: UUID,
        anio: Int?
    ): List<Declaracion> {
        val filters =
            buildMap {
                put(relation, id.toString())
                anio?.let { put("anio", it.toString()) }
            }
        return allPages { page ->
            records.list(
                DECLARACION,
                RecordQuery(page = page, sort = "anio", descending = true, filters = filters)
            )
        }.map { toDeclaracion(it.id, it.attributes) }
            .sortedWith(compareByDescending<Declaracion> { it.anio }.thenBy { it.secuenciaUso })
    }

    // the records behind a set of relation ids, in one query per page instead of one per id
    private suspend fun <T> byIds(
        objectName: String,
        ids: List<String>,
        map: (RecordResponse) -> T
    ): Map<String, T> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return distinct
            .chunked(PageRequest.MAX_SIZE)
            .flatMap { chunk ->
                records
                    .list(
                        objectName,
                        RecordQuery(page = PageRequest.of(0, chunk.size), ids = chunk.map(UUID::fromString))
                    ).content
            }.associate { it.id to map(it) }
    }

    private suspend fun allPages(fetch: suspend (PageRequest) -> PageResponse<RecordResponse>): List<RecordResponse> {
        val rows = mutableListOf<RecordResponse>()
        var page = 0
        do {
            val result = fetch(PageRequest.of(page, PageRequest.MAX_SIZE))
            rows += result.content
            page++
        } while (page < result.totalPages)
        return rows
    }

    private suspend fun count(objectName: String): Long = records.list(objectName, RecordQuery(page = PageRequest.of(0, 1))).totalElements

    private fun anioActual(): Int = LocalDate.now().year
}
