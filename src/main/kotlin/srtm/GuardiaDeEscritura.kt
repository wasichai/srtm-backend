package srtm

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard

// rentas' GRANT INSERT, SELECT: what srtm only adds (an acta, an anuncio...) is never updated nor deleted, by anyone.
// wasichai calls every RecordWriteGuard before the store, on every path (RecordService, links, workflow transitions,
// automations, the platform: ADR-040), in the caller's coroutine, after the caller's permissions, appendOnly and the
// relation targets (D29). this one asks each object's ReglaDeEscritura:
// - an insert holds its invariants, whoever sends it (400); one of an object soloDesdeElServicio without the mark
//   EscrituraDeSrtm is a 403 before anything is written (the platform and automations carry no mark either);
// - an update is what the rule allows (by default nothing: 409), and with the mark when soloDesdeElServicio;
// - a delete is what the rule allows (by default nothing: 409); a state transition is a 409, always.
// a refusal for the missing mark leaves a WARN line that starts with ESCRITURA FUERA DE SRTM RECHAZADA. what it does
// not see: a direct write to the database (the lotes' states, srtm.emision.TablasCore) and a module that writes
// through the RecordStore without RecordWriteGuards
@Component
class GuardiaDeEscritura(
    reglas: List<ReglaDeEscritura>
) : RecordWriteGuard {
    private val log = LoggerFactory.getLogger(javaClass)

    private val porObjeto: Map<String, ReglaDeEscritura> =
        reglas.flatMap { regla -> regla.objetos.map { it to regla } }.groupBy({ it.first }, { it.second }).mapValues { (objeto, suyas) ->
            check(suyas.size == 1) { "«$objeto» tiene ${suyas.size} reglas de escritura: una como mucho" }
            suyas.single()
        }

    override suspend fun beforeWrite(change: RecordWrite) {
        val regla = porObjeto[change.objectName] ?: return
        val objeto = change.objectName
        when (change.kind) {
            RecordChangeKind.CREATED -> {
                exigirLaMarca(regla, "CREATE", change)
                regla.alInsertar(objeto, change.attributes.orEmpty())
            }
            // the rule compares the whole row: core's update replaces every field it is given, and one missing from
            // the request is cleared
            RecordChangeKind.UPDATED -> {
                val guardado = change.before.orEmpty()
                val nuevo = change.attributes.orEmpty()
                val campos = guardado.keys + nuevo.keys
                regla.alActualizar(objeto, campos.associateWith { guardado[it] }, campos.associateWith { nuevo[it] })
                exigirLaMarca(regla, "UPDATE", change)
            }
            RecordChangeKind.DELETED -> regla.alBorrar(objeto, change.before.orEmpty())
            RecordChangeKind.TRANSITIONED -> regla.alCambiarDeEstado(objeto)
        }
    }

    private suspend fun exigirLaMarca(
        regla: ReglaDeEscritura,
        operacion: String,
        change: RecordWrite
    ) {
        if (!regla.soloDesdeElServicio || currentCoroutineContext()[EscrituraDeSrtm.Clave] != null) return
        val objeto = change.objectName
        log.warn(
            "ESCRITURA FUERA DE SRTM RECHAZADA: {} {} {} por el usuario {} de la organización {}",
            operacion,
            objeto,
            change.recordId ?: "(alta)",
            change.userId ?: "(plataforma)",
            change.organizationId
        )
        throw ForbiddenException(
            "«$objeto» solo lo escribe srtm, por su api (/api/srtm/...), que corre sus reglas. La API genérica no las corre, " +
                "y no lo escribe nadie por ella, tampoco un ADMIN"
        )
    }
}
