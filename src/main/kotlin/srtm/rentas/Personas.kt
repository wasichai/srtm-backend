package srtm.rentas

import wasichai.core.common.ValidationException

// the people a contribuyente's or a declaración's lists name (relacionados, transferentes): the rules of their srtm
// forms, as pure functions like Reglas.kt

// where the data came from, when the form does not say
const val FUENTE_MANUAL = "MANUAL"

// with RUC it is a company: its razón social instead of surnames and names
fun esEmpresa(tipoDocumento: String?): Boolean = tipoDocumento == "RUC"

// the form asks the same: a 400 on the field that is missing
fun validarNombre(
    tipoDocumento: String?,
    razonSocial: String?,
    nombres: String?
) {
    if (esEmpresa(tipoDocumento)) {
        if (razonSocial.isNullOrBlank()) throw ValidationException("Falta la razón social", "razon_social", "es obligatoria con RUC")
    } else if (nombres.isNullOrBlank()) {
        throw ValidationException("Faltan los nombres", "nombres", "es obligatorio")
    }
}

// a row's code among its parent's rows: 001, 002... one more than the highest there
const val CODIGO_LISTA_WIDTH = 3

fun siguienteCodigoLista(codigos: List<String?>): String =
    ((codigos.mapNotNull { it?.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString().padStart(CODIGO_LISTA_WIDTH, '0')
