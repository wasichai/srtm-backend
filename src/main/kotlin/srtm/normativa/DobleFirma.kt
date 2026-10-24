package srtm.normativa

import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.identity.CurrentUser
import java.util.UUID

// what Normativa's services share (parámetros, aranceles)

// who signs: a person by their email, a service account by its name
suspend fun CurrentUser.firma(): String = require().let { it.serviceAccount ?: it.email }

// POST .../verificar: each id on its own (`uno`, in its own transaction), so one rechazo does not stop the others. a
// ConflictException is the rechazo's motivo; a record that does not exist, «no existe»; a text that is no id, «no es
// un id». `plural`: what the ids are, for the 400 of an empty list
suspend fun verificarCada(
    pedido: PedidoVerificacion,
    plural: String,
    uno: suspend (UUID) -> Unit
): ResultadoVerificacion {
    val ids =
        pedido.ids
            .orEmpty()
            .map { it.trim() }
            .distinct()
    if (ids.isEmpty()) throw ValidationException("No hay qué verificar", "ids", "es obligatorio: los ids de los $plural")
    val verificados = mutableListOf<String>()
    val rechazados = mutableListOf<RechazoDeVerificacion>()
    for (texto in ids) {
        try {
            val id = runCatching { UUID.fromString(texto) }.getOrNull() ?: throw ConflictException("no es un id")
            uno(id)
            verificados += texto
        } catch (e: ConflictException) {
            rechazados += RechazoDeVerificacion(texto, e.message ?: "no se verifica")
        } catch (_: NotFoundException) {
            rechazados += RechazoDeVerificacion(texto, "no existe")
        }
    }
    return ResultadoVerificacion(verificados, rechazados)
}

// `campo` is one of `valores` (a text field): a condition on the object's column
fun unoDe(
    campo: String,
    valores: Collection<String>
): RecordCriterion =
    RecordCriterion { definition, bind ->
        "\"${definition.fields.first { it.name == campo }.columnName}\" = ANY(${bind(valores.toTypedArray())})"
    }
