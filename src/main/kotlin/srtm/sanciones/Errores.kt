package srtm.sanciones

import org.springframework.http.HttpStatus
import srtm.Faltan
import srtm.legible
import wasichai.core.common.FieldViolation
import wasichai.core.common.WasichaiException
import java.time.LocalDate

// what keeps an act of the sanciones from being written: a 422 that names each missing thing (UIT 2026, PLAZO
// DESCARGO_PAPELETA 2026, FERIADOS 2026, CUIS A-042 porcentaje_uit_segunda). srtm.Faltan, like the other modules'
class FaltanSanciones(
    acto: String,
    faltan: List<String>
) : Faltan("No se puede $acto: ${faltan.joinToString("; ")}", faltan)

// an act the rules do not admit as asked: a 422, corrected by changing the request (an SE_REDUCE, a RECURSO without
// its descargo, a fase that is not one). a state the act cannot change is a ConflictException (409) instead
open class NoProcede(
    mensaje: String,
    violaciones: List<FieldViolation> = emptyList()
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, mensaje, violaciones)

// rentas' ActoFueraDeOrden (#402): an act dated before the act it answers, or after today. names both dates
class ActoFueraDeOrden(
    val acto: String,
    val fecha: LocalDate,
    val previo: ActoPrevio?,
    campo: String,
    motivo: String
) : NoProcede("${acto.replaceFirstChar { it.uppercase() }} no puede fecharse el ${fecha.legible()}: $motivo", listOf(FieldViolation(campo, motivo)))

// a figure, or what is missing to give it: never both. a query answers 200 with the faltan; an act throws them
data class Resultado<T>(
    val valor: T?,
    val faltan: List<String>
) {
    // the figure, or a FaltanSanciones that keeps `acto` from being written
    fun exigir(acto: String): T = valor ?: throw FaltanSanciones(acto, faltan)

    companion object {
        fun <T> de(valor: T): Resultado<T> = Resultado(valor, emptyList())

        fun <T> faltando(faltan: List<String>): Resultado<T> = Resultado(null, faltan.distinct())
    }
}
