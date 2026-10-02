package srtm.arbitrios

// the parametro_tributario rows of an ordinance that the arbitrios read, written once. model/import_parametros.py checks
// their shape under the same names (TIPOS_ARBITRIO); LlavesTest compares both lists
object Llaves {
    // servicio:zona:uso -> valor_numerico, the monthly tasa in soles
    const val TASA_ARBITRIO = "TASA_ARBITRIO"

    // a sector catastral -> texto, its zona
    const val ARBITRIO_ZONA = "ARBITRIO_ZONA"

    // a prefix of a uso_predio code, 2, 4 or 6 digits -> texto, its uso de arbitrio. the longest prefix wins
    const val ARBITRIO_USO = "ARBITRIO_USO"

    // a month -> texto, its due date (the HLA prints it)
    const val ARBITRIO_VENCIMIENTO = "ARBITRIO_VENCIMIENTO"

    val TIPOS = listOf(TASA_ARBITRIO, ARBITRIO_ZONA, ARBITRIO_USO, ARBITRIO_VENCIMIENTO)

    fun tasa(
        servicio: String,
        zona: String,
        uso: String
    ) = "$servicio:$zona:$uso"

    // what a cuota keeps as parametro_aplicado: the exact key that was read
    fun aplicado(claveDeTasa: String) = "$TASA_ARBITRIO:$claveDeTasa"
}
