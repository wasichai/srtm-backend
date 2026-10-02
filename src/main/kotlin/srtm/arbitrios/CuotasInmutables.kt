package srtm.arbitrios

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.r2dbc.core.DatabaseClient
import srtm.rentas.Records
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import wasichai.core.data.ObjectWorkflowState
import wasichai.core.data.PhysicalTableRecordStore
import wasichai.core.data.RecordRow
import wasichai.core.data.RecordStore
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// rentas' GRANT INSERT, SELECT on determinacion_arbitrio: a cuota is never updated nor deleted, by anyone. core's
// permissions cannot say it (an ADMIN bypasses them, and /admin goes through the same api), but every write of every
// record goes through the RecordStore: this one wraps core's. an insert must hold the cuota's invariantes, whoever
// sends it (the determination never builds one that breaks them). its limit: deleting the whole object from the
// metadata still can
class CuotasInmutables(
    private val almacen: RecordStore
) : RecordStore by almacen {
    override suspend fun insert(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        workflow: ObjectWorkflowState
    ): RecordRow {
        if (esCuota(definition)) {
            val malas = invariantes(Records.read(CuotaArbitrio::class.java, "", attributes))
            if (malas.isNotEmpty()) throw ValidationException("La cuota de arbitrio no es válida", malas)
        }
        return almacen.insert(definition, organizationId, userId, attributes, sections, workflow)
    }

    override suspend fun update(
        definition: ObjectDefinition,
        organizationId: UUID,
        userId: UUID,
        id: UUID,
        attributes: Map<String, Any?>,
        sections: Map<String, Map<String, Any?>>,
        withState: Boolean
    ): RecordRow {
        if (esCuota(definition)) throw inmutable()
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
        if (esCuota(definition)) throw inmutable()
        return almacen.transitionState(definition, organizationId, userId, id, from, to)
    }

    override suspend fun delete(
        definition: ObjectDefinition,
        organizationId: UUID,
        id: UUID
    ): Boolean {
        if (esCuota(definition)) throw inmutable()
        return almacen.delete(definition, organizationId, id)
    }

    private fun esCuota(definition: ObjectDefinition) = definition.obj.name == CUOTA_ARBITRIO

    private fun inmutable() = ConflictException("Una cuota de arbitrio no se edita ni se borra: una corrección se agrega como anulación")
}

// core's RecordStore bean is @ConditionalOnMissingBean: this one takes its place, wrapped
@Configuration(proxyBeanMethods = false)
class AlmacenDeRegistros {
    @Bean
    fun recordStore(
        db: DatabaseClient,
        schemas: WasichaiSchemas,
        types: FieldTypeRegistry
    ): RecordStore = CuotasInmutables(PhysicalTableRecordStore(db, schemas, types))
}
