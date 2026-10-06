package srtm.arbitrios

import srtm.emision.soles
import srtm.rentas.Declaracion
import java.math.BigDecimal
import java.math.RoundingMode

// a servicio's cuota breakdown for one declaración: its base's cantidad, its tasa, the habitantes factor (when the
// servicio has one) and the monto, after redondeo (null when there is none). kept apart from how it is applied
// (Arbitrios.determinar, Task 8) so the arithmetic is tested on its own
data class Desglose(
    val base: Base,
    val cantidadBase: BigDecimal,
    val tasaUnitaria: BigDecimal,
    val habitantes: Int?,
    val habitantesPresuntos: Boolean,
    val promedioHabitantes: BigDecimal?,
    val variacionHabitante: BigDecimal?,
    val redondeo: Redondeo?,
    val monto: BigDecimal
)

// Calculo.calcular's result: Es with the breakdown, Falta naming what is missing or wrong (a missing/non-positive
// frontis or área construida, or a negative habitantes factor) - all-or-nothing, same convention as ValorDimension
sealed interface Calculado {
    data class Es(
        val desglose: Desglose
    ) : Calculado

    data class Falta(
        val mensaje: String
    ) : Calculado
}

// monto = tasa × cantidad × factor de habitantes, redondeado al final (Servicios.redondeo, global a la ordenanza).
// cantidad depende de la base: PREDIO siempre es 1 (por eso, sin habitantes y sin redondeo, monto es exactamente la
// tasa - mismo BigDecimal, misma escala, nada se recorta ni se fuerza); FRONTIS_ML y AREA_CONSTRUIDA_M2 leen el
// valor propio de la declaración, que debe ser positivo o el predio no puede cobrarse por esa base. el factor,
// cuando el servicio tiene habitantes (Servicios.habitantes), es 1 + (H − P) × V: H es numeroHabitantes de la
// declaración, o el propio P cuando es nulo (habitantesPresuntos) - esa lectura anula el factor a 1 para un H
// presunto. un factor negativo (H declarado muy por debajo de P, con una V pronunciada) falla el servicio entero:
// nada se adivina
object Calculo {
    fun calcular(
        base: Base,
        tasa: BigDecimal,
        d: Declaracion,
        habitantes: Habitantes?,
        redondeo: Redondeo?,
        quien: String
    ): Calculado {
        val cantidad =
            when (base) {
                Base.PREDIO -> BigDecimal.ONE
                Base.FRONTIS_ML ->
                    d.longitudFrente?.takeIf { it > BigDecimal.ZERO }
                        ?: return Calculado.Falta("Frontis del predio $quien")
                Base.AREA_CONSTRUIDA_M2 ->
                    d.areaConstruida?.takeIf { it > BigDecimal.ZERO }
                        ?: return Calculado.Falta("Área construida del predio $quien")
            }

        val habitantesPresuntos = habitantes != null && d.numeroHabitantes == null
        val factor =
            if (habitantes == null) {
                BigDecimal.ONE
            } else {
                val h = d.numeroHabitantes?.let(::BigDecimal) ?: habitantes.promedio
                BigDecimal.ONE.add((h - habitantes.promedio).multiply(habitantes.variacion))
            }
        if (factor < BigDecimal.ZERO) {
            return Calculado.Falta("Factor de habitantes negativo en $quien: 1 + (H − P) × V = $factor")
        }

        val bruto = tasa.multiply(cantidad).multiply(factor)
        return Calculado.Es(
            Desglose(
                base = base,
                cantidadBase = cantidad,
                tasaUnitaria = tasa,
                habitantes = if (habitantes == null) null else d.numeroHabitantes,
                habitantesPresuntos = habitantesPresuntos,
                promedioHabitantes = habitantes?.promedio,
                variacionHabitante = habitantes?.variacion,
                redondeo = redondeo,
                monto = redondeo?.aplicar(bruto) ?: bruto
            )
        )
    }

    // the formula a DesgloseCuota shows, as the ordenanza reads: tasa × cantidad (unit per Base) × habitantes
    // factor (only when there is one, H non null) = monto. tasa is printed with the norm's own decimals
    // (toPlainString), cantidad always with 2 (setScale, whatever its stored scale); the base PREDIO's cantidad is
    // always 1 and never printed, so it only ends in "= monto" when there was a redondeo (FRONTIS_ML and
    // AREA_CONSTRUIDA_M2 always do, since their cantidad is printed). a cuota lowered by TOPE_ARBITRIO always ends in
    // "= monto_sin_tope, con tope de N %: monto". a cuota written before the desglose (base null) has nothing to show
    // but its monto
    fun formula(c: CuotaArbitrio): String {
        val base = c.base?.let { runCatching { Base.valueOf(it) }.getOrNull() } ?: return soles(c.monto)

        val sb = StringBuilder("${c.tasaUnitaria!!.toPlainString()} S/ por ${base.porUnidad}")
        if (base.unidad != null) {
            sb.append(" × ${c.cantidadBase!!.setScale(2, RoundingMode.HALF_UP).toPlainString()} ${base.unidad}")
        }
        val h = c.habitantes
        if (h != null) {
            sb.append(" × (1 + ($h − ${c.promedioHabitantes!!.toPlainString()}) × ${c.variacionHabitante!!.toPlainString()})")
        }
        val tope = c.topeAplicado
        val sinTope = c.montoSinTope
        if (tope != null && sinTope != null) {
            sb.append(" = ${sinTope.toPlainString()}, con tope de ${tope.stripTrailingZeros().toPlainString()} %: ${c.monto!!.toPlainString()}")
        } else if (base.unidad != null || c.redondeo != null) {
            sb.append(" = ${c.monto!!.toPlainString()}")
        }
        return sb.toString()
    }
}
