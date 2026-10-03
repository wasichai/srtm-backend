package srtm.anuncios

// records of the anuncios as srtm's services write them, attribute by attribute, the way the generic api would send
// them. FICTITIOUS values: no tasa of Perené is used. a relation is any id the caller gives
object Ejemplos {
    fun anuncio(
        contribuyente: String = "c",
        correlativo: Int = 1
    ): Map<String, Any?> =
        mapOf(
            "anio" to 2026,
            "correlativo" to correlativo,
            "numero" to "AN-2026-%06d".format(correlativo),
            "clase" to "PANEL",
            "tipo" to "AVISO_LUMINOSO",
            "emplazamiento" to "FACHADA",
            "forma" to "RECTANGULAR",
            "denominacion" to "BODEGA DE PRUEBA",
            "direccion" to "JR. LIMA 123",
            "area" to "2.50",
            "lados" to 1,
            "cantidad" to 1,
            "fecha_autorizacion" to "2026-03-02",
            "vigencia_hasta" to "2026-12-31",
            "expediente" to "EXP-0003",
            "fecha_expediente" to "2026-02-27",
            "licencia_texto" to "LF-0001",
            "clave_idempotencia" to null,
            "observacion" to "Alta de prueba",
            "contribuyente" to contribuyente,
            "predio" to null
        )

    // an authorization by default: it accrues the year's tasa
    fun movimiento(
        anuncio: String = "a",
        tipo: String = "AUTORIZACION",
        parametro: String = "t"
    ): Map<String, Any?> {
        val devenga = tipo in DEVENGAN
        return mapOf(
            "tipo" to tipo,
            "fecha" to "2026-03-02",
            "anio" to (if (devenga) 2026 else null),
            "referencia_cargo" to (if (devenga) "ANUNCIO-$anuncio-2026" else null),
            "tasa" to (if (devenga) "12.50" else null),
            "vigencia_hasta" to (if (devenga) "2026-12-31" else null),
            "motivo" to (if (devenga) null else "Cerró el local"),
            "clave" to if (tipo == RENOVACION) "$anuncio|$tipo|2026" else "$anuncio|$tipo",
            "observacion" to "Movimiento de prueba",
            "anuncio" to anuncio,
            "parametro" to (if (devenga) parametro else null)
        )
    }
}
