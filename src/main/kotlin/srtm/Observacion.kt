package srtm

import wasichai.core.common.ValidationException

// rentas' Observacion: every act of srtm (arbitrios, sanciones, anuncios) says why, 5 to 500 characters once trimmed.
// core's audit has no column for it, so it goes on the record. a missing or bad one is a 400 that names the field
object Observacion {
    const val MINIMO = 5
    const val MAXIMO = 500
    const val REGLA = "explica por qué, de $MINIMO a $MAXIMO caracteres"

    fun valida(texto: String?) = texto != null && texto.trim().length in MINIMO..MAXIMO

    fun de(texto: String?): String {
        val limpio = texto?.trim() ?: throw ValidationException("Falta la observación", "observacion", REGLA)
        if (limpio.length !in MINIMO..MAXIMO) {
            throw ValidationException("La observación tiene ${limpio.length} caracteres", "observacion", REGLA)
        }
        return limpio
    }
}
