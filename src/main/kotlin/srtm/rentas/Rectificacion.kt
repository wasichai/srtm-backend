package srtm.rentas

import wasichai.core.common.ValidationException
import java.time.LocalDate

// a rectificatoria is a new declaración, numbered, that replaces the one it rectifies (sustituye) in its predio, year
// and secuencia de uso: that one stays SUSTITUIDA, read-only, and counts nothing. a year's declaración may also start
// from another year's (desde). both are copies (DeclaracionService.copiar)

const val SUSTITUIDA = "SUSTITUIDA"
const val RECTIFICACION = "RECTIFICACION"

// what a copy takes of a declaración: what it declares and its titular's data (the same contribuyente), not its
// number, estado, motivo, anulación, sustitución nor presentation (a copy is presented anew). what the external
// padrón brought of the declaración (the dto's arancel is read-only, #195) is copied apart (padronDeLaCopia), of its
// niveles too (DeclaracionService.nivelesDelPadron)
fun copiaDe(origen: Declaracion) =
    origen.copy(
        id = null,
        arancel = null,
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

// #237: what a copy takes of the external padrón's fields of the declaración (PADRON_EXTERNO_DECLARACION), which the
// dto does not write. a rectificatoria (the same year) is the same declaración: all of them, the arancel with or
// without its tramo de vía (id_aranc), and the mark that it changed since (ORIGEN_MODIFICADO), as a condómino's copy.
// another year keeps the otras instalaciones (#208: a fixed amount, not depreciated again), not the figures of the
// source's year (its arancel, valor_terreno, valor_construccion): as a nivel's clasificación (nivelesDelPadron)
fun padronDeLaCopia(
    origen: Declaracion,
    copia: Declaracion
): List<String> = if (copia.anio == origen.anio) PADRON_EXTERNO_DECLARACION + ORIGEN_MODIFICADO else listOf(OTRAS_INSTALACIONES)

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
