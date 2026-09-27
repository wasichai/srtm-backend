package srtm.impuesto

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// one row of parametro_tributario (model/model.json): a verified normative value, in force from vigencia_desde to
// vigencia_hasta (open when empty). valor_numerico is the figure as the norm prints it: an alícuota or the mínimo in %,
// a tramo's límite in UIT, the UIT in soles
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ParametroTributario(
    val id: String? = null,
    val tipo: String? = null,
    val clave: String? = null,
    val vigenciaDesde: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null,
    val valorNumerico: BigDecimal? = null,
    val texto: String? = null,
    val norma: String? = null,
    val fuente: String? = null,
    val transcribio: String? = null,
    val verifico: String? = null
)

// a tramo of the scale, in soles: the part of the base between desde and hasta (open in the last one) pays its
// alícuota (in %)
data class TramoLiquidado(
    val tramo: Int,
    val desde: BigDecimal,
    val hasta: BigDecimal?,
    val alicuota: BigDecimal,
    val monto: BigDecimal,
    val impuesto: BigDecimal
)

data class Cuota(
    val numero: Int,
    val monto: BigDecimal,
    val vencimiento: LocalDate
)

// a contribuyente's impuesto predial of a year. when a parameter of the year is missing, faltan names it and there is
// no figure: uit, the amounts and minimoAplicado are null, tramos and cuotas empty. base is the contribuyente's still
data class Liquidacion(
    val anio: Int,
    val uit: BigDecimal?,
    val base: BigDecimal,
    val tramos: List<TramoLiquidado>,
    val impuestoCalculado: BigDecimal?,
    val minimo: BigDecimal?,
    val minimoAplicado: Boolean?,
    val impuestoAnual: BigDecimal?,
    val cuotas: List<Cuota>,
    val faltan: List<String>
)

const val UIT = "UIT"
const val TRAMO_PREDIAL = "TRAMO_PREDIAL"
const val TRAMO_PREDIAL_LIMITE = "TRAMO_PREDIAL_LIMITE"
const val PREDIAL_MINIMO = "PREDIAL_MINIMO"

// art. 13 of the TUO LTM: the base (the valor afecto of the contribuyente's predios) pays by progressive tramos in UIT,
// and never less than the mínimo, a % of the UIT, while there is a base. art. 15: four cuotas of a quarter each. the
// UIT, tramos, límites and mínimo are the ones in force on 1 january of the year
object ImpuestoPredial {
    private val CIEN = BigDecimal(100)
    private val CUATRO = BigDecimal(4)

    fun liquidar(
        anio: Int,
        valorAfecto: BigDecimal,
        parametros: List<ParametroTributario>
    ): Liquidacion {
        val base = dos(valorAfecto)
        val primeroDeEnero = LocalDate.of(anio, 1, 1)
        val faltan = mutableListOf<String>()

        fun valor(
            tipo: String,
            clave: String? = null
        ): BigDecimal? {
            val vigente =
                parametros
                    .filter { it.tipo == tipo && it.clave?.ifBlank { null } == clave && it.valorNumerico != null && vigenteEl(it, primeroDeEnero) }
                    .maxByOrNull { it.vigenciaDesde!! }
            if (vigente == null) faltan += listOfNotNull(tipo, clave, anio.toString()).joinToString(" ")
            return vigente?.valorNumerico
        }

        val uit = valor(UIT)
        val alicuotas = (1..3).map { valor(TRAMO_PREDIAL, it.toString()) }
        val limites = (1..2).map { valor(TRAMO_PREDIAL_LIMITE, it.toString()) }
        val porcentajeMinimo = valor(PREDIAL_MINIMO)
        if (faltan.isNotEmpty()) {
            return Liquidacion(anio, null, base, emptyList(), null, null, null, null, emptyList(), faltan)
        }

        // the tramos' bounds in soles: 0, límite 1, límite 2, open
        val cortes = listOf(BigDecimal.ZERO) + limites.map { dos(it!! * uit!!) } + listOf(null)
        val tramos =
            alicuotas.mapIndexed { i, alicuota ->
                val desde = cortes[i]!!
                val hasta = cortes[i + 1]
                val monto = (hasta?.let { base.min(it) } ?: base).subtract(desde).max(BigDecimal.ZERO)
                TramoLiquidado(i + 1, dos(desde), hasta, alicuota!!, dos(monto), porcentaje(monto, alicuota))
            }
        val calculado = tramos.sumOf { it.impuesto }
        val minimo = porcentaje(uit!!, porcentajeMinimo!!)
        val minimoAplicado = base.signum() > 0 && calculado < minimo
        val anual = if (minimoAplicado) minimo else calculado
        return Liquidacion(anio, uit, base, tramos, calculado, minimo, minimoAplicado, anual, cuotas(anio, anual), emptyList())
    }

    // a quarter each, to the centimo; the fourth takes what rounding left, so they add up to the annual tax
    private fun cuotas(
        anio: Int,
        anual: BigDecimal
    ): List<Cuota> {
        val cuarto = anual.divide(CUATRO, 2, RoundingMode.HALF_UP)
        val montos = List(3) { cuarto } + anual.subtract(cuarto.multiply(BigDecimal(3)))
        return Vencimientos.predial(anio).mapIndexed { i, vence -> Cuota(i + 1, montos[i], vence) }
    }

    private fun vigenteEl(
        p: ParametroTributario,
        dia: LocalDate
    ) = p.vigenciaDesde != null && !p.vigenciaDesde.isAfter(dia) && (p.vigenciaHasta == null || !p.vigenciaHasta.isBefore(dia))

    // a % of an amount, to the centimo (half up)
    private fun porcentaje(
        monto: BigDecimal,
        porcentaje: BigDecimal
    ) = monto.multiply(porcentaje).divide(CIEN, 2, RoundingMode.HALF_UP)

    private fun dos(valor: BigDecimal) = valor.setScale(2, RoundingMode.HALF_UP)
}
