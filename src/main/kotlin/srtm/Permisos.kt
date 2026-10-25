package srtm

import org.springframework.stereotype.Component
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService

// the permission an act needs, asked before anything is read: who may not create the act's record gets a 403 that
// names the act and the object, not a computation they cannot write. the modules' acts ask it here
@Component
class Permisos(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService
) {
    suspend fun exigirCrear(
        objeto: String,
        acto: String
    ) = exigir(Actions.CREATE, "creación", objeto, acto)

    // an act that changes a stored record (verifying or annulling a parámetro)
    suspend fun exigirActualizar(
        objeto: String,
        acto: String
    ) = exigir(Actions.UPDATE, "actualización", objeto, acto)

    suspend fun exigirBorrar(
        objeto: String,
        acto: String
    ) = exigir(Actions.DELETE, "borrado", objeto, acto)

    // a query of what an object keeps (the brecha's panel over brecha_corrida): a 403 that names it, before reading
    suspend fun exigirLeer(
        objeto: String,
        acto: String
    ) = exigir(Actions.READ, "lectura", objeto, acto)

    // whether the caller may read `objeto`: for what srtm reads of it outside RecordService (an aggregate in sql), which
    // only who could read its records gets. an object the organization lacks is no, and so is one the caller may not
    // read: definitionOf itself refuses it (#226: a recálculo by a user without the aranceles was a 403)
    suspend fun puedeLeer(objeto: String): Boolean {
        val usuario = currentUser.require()
        val definicion =
            try {
                metadata.definitionOf(objeto)
            } catch (_: NotFoundException) {
                return false
            } catch (_: ForbiddenException) {
                return false
            }
        return currentUser.hasPermission(usuario, Actions.READ, definicion.obj.id)
    }

    private suspend fun exigir(
        accion: String,
        permiso: String,
        objeto: String,
        acto: String
    ) {
        val usuario = currentUser.require()
        try {
            currentUser.requirePermission(usuario, accion, metadata.definitionOf(objeto).obj.id)
        } catch (_: ForbiddenException) {
            throw ForbiddenException("${acto.replaceFirstChar { it.uppercase() }} exige permiso de $permiso sobre $objeto")
        }
    }
}
