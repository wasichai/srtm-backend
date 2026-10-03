package srtm

import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.data.PhysicalTableRecordStore
import wasichai.core.data.RecordRow
import wasichai.core.data.RecordStore
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// rentas' GRANT INSERT, SELECT: what srtm only adds (a cuota, an acta, an anuncio...) is never updated nor deleted, by
// anyone. core's permissions cannot say it (an ADMIN bypasses them, and /admin goes through the same api), but every
// write of every record goes through the RecordStore: this one wraps core's and asks each object's ReglaDeEscritura
// first. RecordService has already checked the caller's permissions, and calls the store in the caller's coroutine, so
// the mark EscrituraDeSrtm that Registros puts around its writes is seen here:
// - an insert holds its invariants, whoever sends it (400); one of an object soloDesdeElServicio without the mark is a
//   403 before anything is written;
// - an update is what the rule allows (by default nothing: 409), and with the mark when soloDesdeElServicio;
// - a delete or a state transition is a 409, always.
// a refusal for the missing mark leaves a WARN line that starts with ESCRITURA FUERA DE SRTM RECHAZADA. what it does
// not see: deleting the whole object (or a unique) from the metadata, a direct write to the database, and a module
// that writes without the RecordStore (caja-backend#20, wasichai#15)
class AlmacenGuardado(
    private val almacen: RecordStore,
    reglas: List<ReglaDeEscritura>
) : RecordStore by almacen {
    private val log = LoggerFactory.getLogger(javaClass)

    private val porObjeto: Map<String, ReglaDeEscritura> =
        reglas.flatMap { regla -> regla.objetos.map { it to regla } }.groupBy({ it.first }, { it.second }).mapValues { (objeto, suyas) ->
            check(suyas.size == 1) { "«$objeto» tiene ${suyas.size} reglas de escritura: una como mucho" }
            suyas.single()
        }

    override suspend fun insert(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        workflow: ObjectWorkflowState
    ): RecordRow {
        reglaDe(definition)?.let { regla ->
            exigirLaMarca(regla, "CREATE", definition, organizationId, userId, null)
            regla.alInsertar(definition.obj.name, attributes)
        }
        return almacen.insert(definition, organizationId, userId, attributes, sections, workflow)
    }

    // the rule compares the whole row: core's update replaces every field it is given (`definition` is what the
    // caller may write), and one missing from the request is cleared
    override suspend fun update(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        withState: Boolean
    ): RecordRow {
        reglaDe(definition)?.let { regla ->
            val guardado =
                almacen.findById(definition, organizationId, id)?.attributes ?: throw NotFoundException("Record $id does not exist")
            val campos = definition.fields.map { it.name }
            regla.alActualizar(definition.obj.name, campos.associateWith { guardado[it] }, campos.associateWith { attributes[it] })
            exigirLaMarca(regla, "UPDATE", definition, organizationId, userId, id)
        }
        return almacen.update(definition, organizationId, userId, id, attributes, sections, withState)
    }

    override suspend fun transitionState(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        from: String?,
        to: String
    ): RecordRow? {
        reglaDe(definition)?.alCambiarDeEstado(definition.obj.name)
        return almacen.transitionState(definition, organizationId, userId, id, from, to)
    }

    override suspend fun delete(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID
    ): Boolean {
        reglaDe(definition)?.alBorrar(definition.obj.name)
        return almacen.delete(definition, organizationId, id)
    }

    private fun reglaDe(definition: ObjectDefinition) = porObjeto[definition.obj.name]

    private suspend fun exigirLaMarca(
        regla: ReglaDeEscritura,
        operacion: String,
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID?
    ) {
        if (!regla.soloDesdeElServicio || currentCoroutineContext()[EscrituraDeSrtm.Clave] != null) return
        val objeto = definition.obj.name
        val motivo =
            "«$objeto» solo lo escribe srtm, por su api (/api/srtm/...), que corre sus reglas. La API genérica no las corre, " +
                "y no lo escribe nadie por ella, tampoco un ADMIN"
        log.warn(
            "ESCRITURA FUERA DE SRTM RECHAZADA: {} {} {} por el usuario {} de la organización {}",
            operacion,
            objeto,
            id ?: "(alta)",
            userId,
            organizationId
        )
        throw ForbiddenException(motivo)
    }
}

// core's RecordStore bean is @ConditionalOnMissingBean: this one takes its place, wrapped. its limit: another module
// that decorated the RecordStore too would be left out; there is none today
@Configuration(proxyBeanMethods = false)
class AlmacenDeRegistros {
    @Bean
    fun recordStore(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        types: FieldTypeRegistry,
        reglas: List<ReglaDeEscritura>
    ): RecordStore = AlmacenGuardado(PhysicalTableRecordStore(db, schemas, types), reglas)
}
