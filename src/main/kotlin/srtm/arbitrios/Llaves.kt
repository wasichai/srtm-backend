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

    // a servicio's codigo -> texto, its Base's name; PREDIO when there's no row in force or its texto is unknown
    const val BASE_ARBITRIO = "BASE_ARBITRIO"

    // a servicio's codigo -> texto, its Dimension names separated by commas; [ZONA, USO] when there's no row in
    // force or any of its names is unknown
    const val DIMENSIONES_ARBITRIO = "DIMENSIONES_ARBITRIO"

    // servicio:zona:uso -> valor_numerico, its average habitantes (used together with HABITANTES_VARIACION)
    const val HABITANTES_PROMEDIO = "HABITANTES_PROMEDIO"

    // servicio:zona:uso -> valor_numerico, its habitantes' variación; one without the other counts as absent
    const val HABITANTES_VARIACION = "HABITANTES_VARIACION"

    // the global key (clave "") -> texto, "decimales:modo" of the rounding to apply (Redondeo.de)
    const val REDONDEO_ARBITRIO = "REDONDEO_ARBITRIO"

    // a predio's ubicacion_area_verde (model.json's enum) -> texto, its nivel de influencia (Dimension.INFLUENCIA)
    const val ARBITRIO_INFLUENCIA = "ARBITRIO_INFLUENCIA"

    // a prefix of a uso_predio code (2, 4 or 6 digits), alone or as servicio:prefijo (which wins) -> texto, its nivel
    // de afluencia (Dimension.AFLUENCIA). the longest prefix wins within each family, same rule as ARBITRIO_USO
    const val ARBITRIO_AFLUENCIA = "ARBITRIO_AFLUENCIA"

    val TIPOS =
        listOf(
            TASA_ARBITRIO,
            ARBITRIO_ZONA,
            ARBITRIO_USO,
            ARBITRIO_VENCIMIENTO,
            BASE_ARBITRIO,
            DIMENSIONES_ARBITRIO,
            HABITANTES_PROMEDIO,
            HABITANTES_VARIACION,
            REDONDEO_ARBITRIO,
            ARBITRIO_INFLUENCIA,
            ARBITRIO_AFLUENCIA
        )

    fun tasa(
        servicio: String,
        zona: String,
        uso: String
    ) = tasa(servicio, listOf(zona, uso))

    fun tasa(
        servicio: String,
        valores: List<String>
    ) = (listOf(servicio) + valores).joinToString(":")

    // what a cuota keeps as parametro_aplicado: the exact key that was read
    fun aplicado(claveDeTasa: String) = "$TASA_ARBITRIO:$claveDeTasa"
}
