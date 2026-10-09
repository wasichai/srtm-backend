package srtm.sanciones

// the parametro_tributario rows the sanciones read, written once. model/import_parametros.py checks their shape under
// the same names (TIPOS_SANCIONES); LlavesTest compares both lists. the UIT is the predial's (srtm.impuesto.UIT)
object Llaves {
    // DESCARGO_PAPELETA or RG_RECURSO -> valor_numerico, its days; texto, their unit
    const val PLAZO = "PLAZO"

    // a year -> texto, its movable holidays: iso dates separated by commas (Vencimientos.FERIADOS_NACIONALES has the
    // fixed ones)
    const val FERIADOS = "FERIADOS"

    val TIPOS = listOf(PLAZO, FERIADOS)

    // the plazos: to file a descargo against an acta, and to appeal a resolución
    const val DESCARGO_PAPELETA = "DESCARGO_PAPELETA"
    const val RG_RECURSO = "RG_RECURSO"

    // the only unit of a plazo, for now
    const val DIAS_HABILES = "DIAS_HABILES"

    // a year's FERIADOS row
    fun feriados(anio: Int) = "$anio"

    // what a descargo or a resolución copies of the plazo it read: 5 DIAS_HABILES
    fun plazoTexto(
        dias: Int,
        unidad: String = DIAS_HABILES
    ) = "$dias $unidad"
}
