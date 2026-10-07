package srtm

import org.springframework.stereotype.Component
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
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
    ) {
        val usuario = currentUser.require()
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(objeto).obj.id)
        } catch (_: ForbiddenException) {
            throw ForbiddenException("${acto.replaceFirstChar { it.uppercase() }} exige permiso de creación sobre $objeto")
        }
    }
}
