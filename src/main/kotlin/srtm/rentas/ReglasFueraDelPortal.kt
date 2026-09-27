package srtm.rentas

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordChange
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordChangeListener
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.metadata.MetadataService
import java.math.BigDecimal

// core has no hook before a write: its listeners run right after it, inside the caller's request (ADR-0025). so a
// record saved outside the portal is completed by a second write, as the caller: what completar asks for, on the
// fields the caller may write. core's update replaces every field the caller may write, so it sends them all.
// RecordService is built with its listeners, this one among them: it is looked up on use, not injected
@Component
class ReglasFueraDelPortal(
    private val recordService: ObjectProvider<RecordService>,
    private val metadata: MetadataService
) : RecordChangeListener {
    override suspend fun recordChanged(change: RecordChange) {
        if (change.kind != RecordChangeKind.CREATED && change.kind != RecordChangeKind.UPDATED) return
        val derivados = completar(change.objectName, change.before, change.after ?: return)
        if (derivados.isEmpty()) return
        val definicion = metadata.definitionOf(change.objectName)
        val cambios = Registros.soloEscribibles(definicion, derivados)
        if (cambios.isEmpty()) return
        val records = recordService.getObject()
        val stored = records.get(change.objectName, change.recordId).attributes
        try {
            records.update(change.objectName, change.recordId, RecordRequest(Registros.soloEscribibles(definicion, stored) + cambios))
        } catch (_: ForbiddenException) {
            // a caller that may create but not update: the record stays as they saved it
        }
    }
}

// the portal's rules that read one record alone, as its services apply them (Reglas.kt): object -> the derived
// fields and the values they take. rules that read other records (codes, numbering, the fiscal domicilio copied to
// the contribuyente, condominio) stay in the services: a record saved outside the portal skips them. so does a
// predio's direccion: model/normalizar_padron.py splits the padrón's vías through core's api and keeps its text
private val REGLAS: Map<String, (Map<String, Any?>) -> Map<String, Any?>> =
    mapOf(
        CONTRIBUYENTE to { a ->
            val c = Records.read<Contribuyente>("", a)
            mapOf("tipo_persona" to (tipoPersona(c.tipoContribuyente) ?: c.tipoPersona), "nombre_completo" to nombreCompleto(c))
        },
        DOMICILIO to { a -> mapOf("descripcion" to describir(Records.read<Domicilio>("", a))) },
        OBRA_COMPLEMENTARIA to { a -> mapOf("total_metrado" to totalMetrado(Records.read<ObraComplementaria>("", a))) }
    )

// what a save outside the portal (the admin, core's own api) leaves for those rules to complete: field -> value.
// a new record gets only what it left blank (an import keeps the padrón's text); an edited one, a derived field
// whose rule now gives another result, unless that same write set the field by hand. a write whose derived values
// are already right (every portal save, and the completing write itself) gets nothing
fun completar(
    objeto: String,
    antes: Map<String, Any?>?,
    despues: Map<String, Any?>
): Map<String, Any?> {
    val regla = REGLAS[objeto] ?: return emptyMap()
    val previo = antes?.let(regla)
    return regla(despues).filter { (campo, valor) ->
        when {
            igual(despues[campo], valor) -> false
            antes == null || previo == null -> vacio(despues[campo])
            else -> !igual(previo[campo], valor) && igual(antes[campo], despues[campo])
        }
    }
}

private fun vacio(valor: Any?) = valor == null || (valor is String && valor.isBlank())

// as stored: a blank text is none, and a number is its value whatever its scale
private fun igual(
    a: Any?,
    b: Any?
): Boolean =
    when {
        vacio(a) || vacio(b) -> vacio(a) && vacio(b)
        a is BigDecimal && b is BigDecimal -> a.compareTo(b) == 0
        else -> a == b
    }
