package srtm.arbitrios

import srtm.impuesto.ParametroTributario
import java.time.LocalDate

// the parametro_tributario rows of a contexto, grouped once (ContextoArbitrios.indice): by tipo, and within a tipo by
// clave, trimmed. a determination looks a row up for every servicio, month and secuencia: instead of comparing every
// row of every module each time, it reads only those of its tipo (and clave). each group keeps the rows in the order
// they came, so enVigor answers exactly as it does over the whole list: the row in force that day that matches, the
// latest vigencia_desde, the first one listed among equals
class IndiceParametros(
    parametros: List<ParametroTributario>
) {
    private val porTipo: Map<String?, List<ParametroTributario>> = parametros.groupBy { it.tipo }
    private val porClave: Map<String?, Map<String?, List<ParametroTributario>>> =
        porTipo.mapValues { (_, filas) -> filas.groupBy { it.clave?.trim() } }

    // every row of a tipo
    fun deTipo(tipo: String): List<ParametroTributario> = porTipo[tipo].orEmpty()

    // the rows of a tipo whose clave, trimmed, is this one
    fun deClave(
        tipo: String,
        clave: String
    ): List<ParametroTributario> = porClave[tipo]?.get(clave).orEmpty()

    // enVigor among a tipo's rows
    fun enVigor(
        tipo: String,
        dia: LocalDate,
        coincide: (ParametroTributario) -> Boolean
    ) = enVigor(deTipo(tipo), tipo, dia, coincide)

    // enVigor among a tipo's rows of one clave
    fun enVigor(
        tipo: String,
        clave: String,
        dia: LocalDate,
        coincide: (ParametroTributario) -> Boolean
    ) = enVigor(deClave(tipo, clave), tipo, dia, coincide)
}
