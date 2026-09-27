package srtm.rentas

import wasichai.core.common.ValidationException
import java.time.LocalDate

// a declaración is annulled, not deleted, once it has content: it stays listed but counts in no totales and no
// condominio. predio, contribuyente and declaración are deleted only while nothing hangs from them (core would answer
// the foreign key with a 500)

const val VIGENTE = "VIGENTE"
const val ANULADA = "ANULADA"
const val DESCARGO = "DESCARGO"

// the imported ones have no estado: they are vigentes
fun vigente(d: Declaracion) = d.estado != ANULADA

// no reactivation: an annulled declaración is read-only
fun modificable(d: Declaracion) {
    if (!vigente(d)) throw ValidationException("La declaración está anulada", "estado", "anulada: no se puede modificar")
}

// the srtm's descargo: its motivo, why and when
fun anulada(
    d: Declaracion,
    motivo: String?,
    hoy: LocalDate
): Declaracion {
    modificable(d)
    val porque = motivo?.trim()?.ifEmpty { null } ?: throw ValidationException("Falta el motivo", "motivo_anulacion", "indica por qué se anula")
    return d.copy(estado = ANULADA, motivo = DESCARGO, motivoAnulacion = porque, fechaAnulacion = hoy)
}

// why a predio or contribuyente ("El predio") cannot go: its declarations, whatever their estado. null when it can
fun bajaConDeclaraciones(
    quien: String,
    declaraciones: Long
): String? =
    when (declaraciones) {
        0L -> null
        1L -> "$quien tiene 1 declaración jurada: no se puede eliminar"
        else -> "$quien tiene $declaraciones declaraciones juradas: no se puede eliminar"
    }

private val LISTAS_DECLARACION =
    mapOf(
        TRANSFERENTE to "transferentes",
        NIVEL_CONSTRUCCION to "niveles de construcción",
        OBRA_COMPLEMENTARIA to "obras complementarias",
        OTRO_FRENTE to "otros frentes"
    )

// why a declaración cannot go: the lists (object names) it has rows in. null when it can
fun bajaDeDeclaracion(listas: List<String>): String? {
    if (listas.isEmpty()) return null
    val nombres = listas.map { LISTAS_DECLARACION[it] ?: it }
    val todas = if (nombres.size == 1) nombres.single() else nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()
    return "La declaración tiene $todas: anúlela en lugar de eliminarla"
}
