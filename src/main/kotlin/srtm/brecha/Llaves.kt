package srtm.brecha

import srtm.impuesto.ParametroTributario
import srtm.vigente
import java.math.BigDecimal
import java.time.LocalDate

// #305: the parametro_tributario rows the brecha reads, Normativa's like every other figure (double signature, by
// organization, with its vigencia). TOLERANCIA_BRECHA <clave> -> valor_numerico, 0 or more: AREA in m2 (areas and
// frente), MONTO in soles. without a vigente row, ComparacionDelPredio's (0.50 m2 and S/ 1.00, the user's of
// 14-11-2026)
object Llaves {
    const val TOLERANCIA_BRECHA = "TOLERANCIA_BRECHA"
    const val AREA = "AREA"
    const val MONTO = "MONTO"

    val TIPOS = listOf(TOLERANCIA_BRECHA)
    val CLAVES_TOLERANCIA = setOf(AREA, MONTO)

    // the tolerances vigente on `dia`: each clave its row's value, else the default
    fun tolerancias(
        parametros: List<ParametroTributario>,
        dia: LocalDate
    ): Tolerancias {
        fun de(clave: String): BigDecimal? =
            parametros
                .filter { it.tipo == TOLERANCIA_BRECHA && it.clave?.trim() == clave && vigente(it.vigenciaDesde, it.vigenciaHasta, dia) }
                .maxByOrNull { it.vigenciaDesde!! }
                ?.valorNumerico
                ?.takeIf { it.signum() >= 0 }
        val omision = ComparacionDelPredio.TOLERANCIAS
        return Tolerancias(de(AREA) ?: omision.area, de(MONTO) ?: omision.monto)
    }
}
