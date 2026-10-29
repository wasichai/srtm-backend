package srtm

import org.springframework.stereotype.Component
import wasichai.core.common.Actions
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService

// the permission an act needs, asked before anything is read: who may not create the act's record gets a 403 that
// names the act and the object, not a computation they cannot write. the modules' acts ask it here
@Component
class Permisos(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService,
    private val accesos: AccessPolicy
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

    // whether the caller may read `objeto` and every one of its `campos` (no field restriction of a role hides one):
    // for what srtm shows of a record it did not read as the caller, like the reportes' cached titulares (#265). a
    // field the caller's definition lacks counts as hidden
    suspend fun puedeLeerCampos(
        objeto: String,
        campos: Collection<String>
    ): Boolean {
        if (!puedeLeer(objeto)) return false
        val definicion = metadata.definitionOf(objeto)
        val acceso = accesos.fieldAccess(currentUser.require(), definicion.obj.id)
        val porNombre = definicion.fields.associateBy { it.name }
        return campos.all { c -> porNombre[c]?.let { acceso.canRead(it.id) } ?: false }
    }

    // #269: whether the caller reads every record and every field of `objeto` (ADMIN, or roles that hide none and do not
    // keep it to its own records): what it reads of it is what the platform would read. srtm narrows no read with a
    // RecordReadScope (ADR-048); one that did would have to be asked here too
    suspend fun leeEntero(objeto: String): Boolean {
        if (!puedeLeer(objeto)) return false
        val usuario = currentUser.require()
        val definicion = metadata.loadDefinition(usuario.organizationId, objeto)
        if (accesos.ownRecordsOnly(usuario)) return false
        val acceso = accesos.fieldAccess(usuario, definicion.obj.id)
        return definicion.fields.all { acceso.canRead(it.id) }
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
