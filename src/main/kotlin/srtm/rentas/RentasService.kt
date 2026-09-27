package srtm.rentas

import org.springframework.stereotype.Service
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.data.RecordQuery
import java.time.LocalDate
import java.util.UUID

// predios, declaraciones, and the fichas that sum them up
@Service
class RentasService(
    private val registros: Registros,
    private val contribuyentes: ContribuyenteService
) {
    suspend fun resumen(): Resumen =
        Resumen(
            anio = anioActual(),
            contribuyentes = registros.count(CONTRIBUYENTE),
            predios = registros.count(PREDIO),
            declaraciones = registros.count(DECLARACION)
        )

    suspend fun fichaContribuyente(
        id: UUID,
        anio: Int?
    ): ContribuyenteFicha {
        val year = anio ?: anioActual()
        val declaraciones = declaracionesPor("contribuyente", id, year)
        return ContribuyenteFicha(
            contribuyente = contribuyentes.get(id),
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
        val predios = registros.byIds(PREDIO, Predio::class.java, declaraciones.mapNotNull { it.predio })
        return declaraciones.map { DeclaracionDetalle(it, predio = predios[it.predio]) }
    }

    suspend fun predios(
        q: String?,
        page: Int?,
        size: Int?
    ): PageResponse<Predio> =
        registros.page(PREDIO, Predio::class.java, RecordQuery(page = PageRequest.of(page, size), sort = "codigo", search = q?.trim()?.ifBlank { null }))

    suspend fun predio(id: UUID): Predio = registros.get(PREDIO, Predio::class.java, id)

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
        val titulares = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, declaraciones.mapNotNull { it.contribuyente })
        return declaraciones.map { DeclaracionDetalle(it, contribuyente = titulares[it.contribuyente]) }
    }

    // every declaration of one contribuyente or predio, a year or all of them, newest year first
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
        return registros
            .all(DECLARACION, Declaracion::class.java, filters = filters, sort = "anio", descending = true)
            .sortedWith(compareByDescending<Declaracion> { it.anio }.thenBy { it.secuenciaUso })
    }

    private fun anioActual(): Int = LocalDate.now().year
}
