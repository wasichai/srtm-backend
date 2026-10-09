package srtm.sanciones

import com.fasterxml.jackson.annotation.JsonProperty
import srtm.impuesto.ParametroTributario
import srtm.impuesto.UIT
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.FieldViolation
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// grado_reincidencia: the inspector declares it (decision 1); each grado has its % in the CUIS
const val PRIMERA = "PRIMERA"
const val SEGUNDA = "SEGUNDA"
const val TERCERA_O_MAS = "TERCERA_O_MAS"

// the six amounts of an acta (rentas' Papeleta), calculated once and frozen in the row: reprinting an acta of 2026 in
// 2036 shows the same six figures
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Desglose(
    // the UIT of the infracción's day, as read
    val baseImponible: BigDecimal,
    // the % the code sets (first time)
    val porcentajeInfraccion: BigDecimal,
    // base × porcentaje_infraccion / 100
    val importeInfraccion: BigDecimal,
    // the % of the declared grado: what is charged
    @param:JsonProperty("porcentaje_a_cobrar") @get:JsonProperty("porcentaje_a_cobrar") val porcentajeACobrar: BigDecimal,
    // base × porcentaje_a_cobrar / 100
    @param:JsonProperty("importe_a_pagar") @get:JsonProperty("importe_a_pagar") val importeAPagar: BigDecimal,
    // no beneficio is ruled yet (decision 3): always empty
    val importeConBeneficio: BigDecimal? = null
)

// the multa of an acta from its CUIS version and the UIT (SPEC §6). the % of the CUIS is an alícuota of the UIT, not a
// tasa (rule 8); no figure of the CUIS nor the UIT is in the code (rule 5)
object Multas {
    val GRADOS = listOf(PRIMERA, SEGUNDA, TERCERA_O_MAS)

    private val CIEN = BigDecimal(100)

    // the only rounding of the sanciones: to the céntimo, half up (decision 2). the same as srtm.impuesto's
    // (ImpuestoPredial rounds a % of an amount with divide(100, 2, HALF_UP), equal to this on the exact product)
    fun redondear(importe: BigDecimal): BigDecimal = importe.setScale(2, RoundingMode.HALF_UP)

    // the UIT row in force on `dia` (the infracción's: its ejercicio's UIT), or "UIT 2026"
    fun uit(
        parametros: List<ParametroTributario>,
        dia: LocalDate
    ): Resultado<ParametroTributario> {
        val fila = vigente(parametros, UIT, null, dia)
        return when {
            fila == null -> Resultado.faltando(listOf("$UIT ${dia.year}"))
            fila.valorNumerico == null || fila.valorNumerico.signum() <= 0 -> Resultado.faltando(listOf("$UIT ${dia.year}: no es mayor que 0"))
            else -> Resultado.de(fila)
        }
    }

    // the % the CUIS version gives a grado, or null when it has none
    fun porcentajeDe(
        codigo: CodigoInfraccion,
        reincidencia: String
    ): BigDecimal? =
        when (reincidencia) {
            PRIMERA -> codigo.porcentajeUit
            SEGUNDA -> codigo.porcentajeUitSegunda
            TERCERA_O_MAS -> codigo.porcentajeUitTercera
            else -> throw NoProcede(
                "La reincidencia '$reincidencia' no es un grado",
                listOf(FieldViolation("reincidencia", "es ${GRADOS.joinToString(", ")}"))
            )
        }

    // the desglose, or what the CUIS lacks for the declared grado: "CUIS A-042 porcentaje_uit_segunda". a grado
    // declared without its % is never charged at another grado's
    fun calcular(
        codigo: CodigoInfraccion,
        uit: BigDecimal,
        reincidencia: String
    ): Resultado<Desglose> {
        require(uit.signum() > 0) { "la UIT es mayor que 0" }
        val faltan = mutableListOf<String>()
        val primera = codigo.porcentajeUit
        if (primera == null) faltan += "CUIS ${codigo.codigo} porcentaje_uit"
        val aCobrar = porcentajeDe(codigo, reincidencia)
        if (aCobrar == null && reincidencia != PRIMERA) faltan += "CUIS ${codigo.codigo} ${CAMPO_DEL_GRADO.getValue(reincidencia)}"
        if (primera == null || aCobrar == null) return Resultado.faltando(faltan)
        return Resultado.de(
            Desglose(
                baseImponible = uit,
                porcentajeInfraccion = primera,
                importeInfraccion = redondear(uit.multiply(primera).divide(CIEN)),
                porcentajeACobrar = aCobrar,
                importeAPagar = redondear(uit.multiply(aCobrar).divide(CIEN)),
                importeConBeneficio = null
            )
        )
    }

    private val CAMPO_DEL_GRADO = mapOf(SEGUNDA to "porcentaje_uit_segunda", TERCERA_O_MAS to "porcentaje_uit_tercera")
}
