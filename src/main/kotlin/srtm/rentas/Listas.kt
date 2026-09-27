package srtm.rentas

import org.springframework.stereotype.Component
import wasichai.core.common.NotFoundException
import java.util.UUID

// the lists that hang from a record (a contribuyente's domicilios, a declaración's niveles...): listed under the
// parent, added under it, changed and removed by their own id. the parent never moves: a body cannot change it
@Component
class Listas(
    private val registros: Registros
) {
    suspend fun <T : Any> listar(
        objectName: String,
        type: Class<T>,
        parentField: String,
        parent: UUID
    ): List<T> = registros.all(objectName, type, filters = mapOf(parentField to parent.toString()), sort = "created_at")

    suspend fun <T : Any> agregar(
        objectName: String,
        type: Class<T>,
        parentField: String,
        parent: UUID,
        body: T
    ): T = registros.create(objectName, type, Records.attributes(body) + (parentField to parent.toString()))

    suspend fun <T : Any> cambiar(
        objectName: String,
        type: Class<T>,
        parentField: String,
        id: UUID,
        body: T
    ): T {
        val parent =
            registros.get(objectName, Map::class.java, id)[parentField]
                ?: throw NotFoundException("$objectName $id has no $parentField")
        return registros.replace(objectName, type, id, Records.attributes(body) + (parentField to parent))
    }

    suspend fun borrar(
        objectName: String,
        id: UUID
    ) = registros.delete(objectName, id)

    companion object {
        const val ACTIVO = "ACTIVO"
    }
}
