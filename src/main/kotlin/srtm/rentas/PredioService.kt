package srtm.rentas

import org.springframework.stereotype.Service
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import java.util.UUID

// "buscar predios" (page 13) over the padrón and the catastro fiscal, and the catastro's lotes themselves
@Service
class PredioService(
    private val registros: Registros
) {
    suspend fun buscarPredios(
        filtros: FiltrosPredio,
        page: Int?,
        size: Int?
    ): PageResponse<Predio> = registros.page(PREDIO, Predio::class.java, query(condicionesPredio(filtros), "codigo", page, size))

    suspend fun buscarCatastro(
        filtros: FiltrosPredio,
        page: Int?,
        size: Int?
    ): PageResponse<CatastroFiscal> = registros.page(CATASTRO_FISCAL, CatastroFiscal::class.java, query(condicionesCatastro(filtros), "codigo_cpu", page, size))

    suspend fun lote(id: UUID): CatastroFiscal = registros.get(CATASTRO_FISCAL, CatastroFiscal::class.java, id)

    suspend fun crearLote(body: CatastroFiscal): CatastroFiscal {
        cpuLibre(body.codigoCpu, except = null)
        return registros.create(CATASTRO_FISCAL, CatastroFiscal::class.java, Records.attributes(body))
    }

    suspend fun actualizarLote(
        id: UUID,
        body: CatastroFiscal
    ): CatastroFiscal {
        cpuLibre(body.codigoCpu, except = id)
        return registros.replace(CATASTRO_FISCAL, CatastroFiscal::class.java, id, Records.attributes(body))
    }

    // the partidas of the instructivo, of one tipo de obra or all, in their order
    suspend fun obrasCategorias(tipoObra: String?): List<ObraCategoria> =
        registros
            .all(OBRA_CATEGORIA, ObraCategoria::class.java, filters = tipoObra?.ifBlank { null }?.let { mapOf("tipo_obra" to it) } ?: emptyMap())
            .sortedWith(compareBy({ it.tipoObra }, { it.numero }))

    // codigo_cpu is unique, and the database would answer a duplicate with a 500: say it on the field instead
    private suspend fun cpuLibre(
        cpu: String?,
        except: UUID?
    ) {
        if (cpu.isNullOrBlank()) return
        registros
            .all(CATASTRO_FISCAL, CatastroFiscal::class.java, filters = mapOf("codigo_cpu" to cpu))
            .firstOrNull { it.id != except?.toString() }
            ?: return
        throw ValidationException("Código CPU repetido", "codigo_cpu", "ya es de otro lote del catastro")
    }

    private fun query(
        condiciones: List<Condicion>,
        sort: String,
        page: Int?,
        size: Int?
    ) = RecordQuery(page = PageRequest.of(page, size ?: PAGINA), sort = sort, criteria = listOfNotNull(criterio(condiciones)))

    private companion object {
        const val PAGINA = 5
    }
}
