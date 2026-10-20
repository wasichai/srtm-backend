package srtm.rentas

import wasichai.core.common.ValidationException
import java.time.LocalDate

// a rectificatoria is a new declaración, numbered, that replaces the one it rectifies (sustituye) in its predio, year
// and secuencia de uso: that one stays SUSTITUIDA, read-only, and counts nothing. a year's declaración may also start
// from another year's (desde). both are copies (DeclaracionService.copiar)

const val SUSTITUIDA = "SUSTITUIDA"
const val RECTIFICACION = "RECTIFICACION"

// what a copy takes of a declaración: what it declares and its titular's data (the same contribuyente), not its
// number, estado, motivo, anulación, sustitución nor presentation (a copy is presented anew). the external padrón's
// valuation is not in the dto: it stays in the source
fun copiaDe(origen: Declaracion) =
    origen.copy(
        id = null,
        numeroDeclaracion = null,
        motivo = null,
        medioPresentacion = null,
        fechaPresentacion = null,
        estado = null,
        motivoAnulacion = null,
        fechaAnulacion = null,
        sustituye = null,
        fechaSustitucion = null
    )

// the declaración its rectificatoria replaces, on the day
fun sustituida(
    d: Declaracion,
    hoy: LocalDate
): Declaracion {
    modificable(d)
    return d.copy(estado = SUSTITUIDA, fechaSustitucion = hoy)
}

// a declaración of `anio` from another one (desde): a vigente one (a sustituida's data is its rectificatoria's), the
// same contribuyente's, on the same predio, of another year
fun desdeOtroAnio(
    origen: Declaracion,
    contribuyente: String,
    predio: String?,
    anio: Int?
) {
    when (origen.estado) {
        SUSTITUIDA -> throw ValidationException("La declaración fue sustituida: declara desde su rectificatoria", "desde", "debe ser una declaración vigente")
        ANULADA -> throw ValidationException("La declaración está anulada: no se puede declarar desde ella", "desde", "debe ser una declaración vigente")
    }
    if (origen.contribuyente != contribuyente) {
        throw ValidationException("La declaración de partida es de otro contribuyente", "desde", "debe ser del mismo contribuyente")
    }
    if (predio != origen.predio) throw ValidationException("La declaración de partida es de otro predio", "predio_id", "debe ser el mismo predio")
    if (anio == origen.anio) {
        throw ValidationException("En el mismo año corresponde rectificar la declaración", "anio", "es el año de la declaración de partida: rectifíquela")
    }
}

// why a declaración of a rectificación cannot go, or null: a sustituida is its rectificatoria's history, and without
// a rectificatoria the year would be left with no declaración
fun bajaDeRectificacion(d: Declaracion): String? =
    when {
        d.estado == SUSTITUIDA -> "La declaración fue sustituida por una rectificatoria: no se puede eliminar"
        d.sustituye != null -> "La declaración rectifica otra: anúlela en lugar de eliminarla"
        else -> null
    }

// a rectificatoria stays where the one it replaces was: the same predio, year and secuencia de uso
fun enSuLugar(
    stored: Declaracion,
    next: Declaracion
) {
    if (stored.sustituye == null || grupoDe(next) == grupoDe(stored)) return
    val campo =
        when {
            next.predio != stored.predio -> "predio"
            next.anio != stored.anio -> "anio"
            else -> "secuencia_uso"
        }
    throw ValidationException("Una rectificatoria no cambia de predio, año ni secuencia de uso", campo, "es el de la declaración que sustituye")
}
