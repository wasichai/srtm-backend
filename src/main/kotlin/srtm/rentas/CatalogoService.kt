package srtm.rentas

import org.springframework.stereotype.Service
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.data.RecordQuery
import wasichai.core.metadata.MetadataService

// what the forms offer: enum options from the model, and the catalog objects model/import_catalogos.py loads
@Service
class CatalogoService(
    private val registros: Registros,
    private val metadata: MetadataService
) {
    // enum options by object and field
    suspend fun opciones(): Map<String, Map<String, List<String>>> =
        OBJETOS.associateWith { name ->
            metadata
                .definitionOf(name)
                .fields
                .filter { it.enumOptions != null }
                .associate { it.name to it.enumOptions!! }
        }

    // the whole INEI list (under 1 900 rows): the form cascades departamento -> provincia -> distrito on its side
    suspend fun ubigeos(): List<Ubigeo> =
        registros
            .all(UBIGEO, Map::class.java, sort = "codigo")
            .map { Ubigeo(it["codigo"].toString(), it["departamento"].toString(), it["provincia"].toString(), it["distrito"].toString()) }

    // the letters of the official unit-value table, column by column, with their descriptions
    suspend fun categoriasValor(): List<CategoriaValor> =
        registros
            .all(CATEGORIA_VALOR, Map::class.java)
            .map { CategoriaValor((it["columna"] as Number).toInt(), it["categoria"].toString(), it["letra"].toString(), it["descripcion"].toString()) }
            .sortedWith(compareBy({ it.columna }, { it.letra }))

    suspend fun vias(
        q: String?,
        tipo: String?,
        ubigeo: String?,
        size: Int?
    ): PageResponse<Via> = registros.page(VIA, Via::class.java, busqueda(q, "tipo_via" to tipo, "ubigeo" to ubigeo, size = size))

    suspend fun unidadesUrbanas(
        q: String?,
        tipo: String?,
        ubigeo: String?,
        size: Int?
    ): PageResponse<UnidadUrbana> =
        registros.page(UNIDAD_URBANA, UnidadUrbana::class.java, busqueda(q, "tipo_unidad_urbana" to tipo, "ubigeo" to ubigeo, size = size))

    private fun busqueda(
        q: String?,
        vararg filters: Pair<String, String?>,
        size: Int?
    ) = RecordQuery(
        page = PageRequest.of(0, size ?: SUGERENCIAS),
        sort = "nombre",
        search = q?.trim()?.ifBlank { null },
        filters = filters.mapNotNull { (field, value) -> value?.ifBlank { null }?.let { field to it } }.toMap()
    )

    private companion object {
        val OBJETOS =
            listOf(
                CONTRIBUYENTE,
                PREDIO,
                DECLARACION,
                DOMICILIO,
                RELACIONADO,
                MEDIO_CONTACTO,
                SUSTENTO,
                VIA,
                UNIDAD_URBANA,
                TRANSFERENTE,
                NIVEL_CONSTRUCCION,
                OBRA_COMPLEMENTARIA,
                OTRO_FRENTE
            )
        const val SUGERENCIAS = 20
    }
}
