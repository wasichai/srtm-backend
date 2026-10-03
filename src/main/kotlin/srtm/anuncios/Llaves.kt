package srtm.anuncios

// the parametro_tributario rows the anuncios read, written once. model/import_parametros.py checks their shape under
// the same names (TIPOS_ANUNCIO); LlavesTest compares both lists
object Llaves {
    // a clase_anuncio -> valor_numerico, the tasa of a whole ejercicio, above 0
    const val TASA_ANUNCIO = "TASA_ANUNCIO"

    val TIPOS = listOf(TASA_ANUNCIO)

    // what a missing tasa is named by in a 422: TASA_ANUNCIO PANEL 2026
    fun falta(
        clase: String,
        anio: Int
    ) = "$TASA_ANUNCIO $clase $anio"
}
