package srtm.rentas

import srtm.brecha.ValuacionDeInsumos
import java.math.BigDecimal
import java.math.RoundingMode

// #156: what a declaración derives of its lists, to the cent (half up): its areas of the active niveles, its
// longitud de frente of the frente principal and the active otros frentes. no frontis at all is none
data class TotalesDeLaDeclaracion(
    val areaConstruida: BigDecimal,
    val areaComunConstruida: BigDecimal,
    val longitudFrente: BigDecimal?
)

// the autoavalúo a write leaves: catastro's value, the stored one marked with why catastro gave none, or the user's
// (the organization has no catastro: nothing is marked nor overwritten)
sealed interface Autoavaluo {
    data class Valuado(
        val valor: BigDecimal
    ) : Autoavaluo

    data class PorRevisar(
        val motivo: String
    ) : Autoavaluo

    data object Manual : Autoavaluo
}

// what DeclaracionService.recalcular left of a declaración: as it was and as it is now. the hooks that follow a write
// start from it (#157 replicates to the condóminos, #158 redetermines the arbitrios when a base changed)
data class Recalculo(
    val antes: Declaracion,
    val despues: Declaracion
) {
    // the derived fields that changed, by their model names (Derivados.campos)
    val cambiados: Set<String> get() = Derivados.cambiados(antes, despues)
}

// pure: DeclaracionService.recalcular reads the lists and asks catastro
object Derivados {
    private const val INACTIVO = "INACTIVO"

    fun totales(
        niveles: List<NivelConstruccion>,
        frentes: List<OtroFrente>,
        frontisPrincipal: BigDecimal?
    ): TotalesDeLaDeclaracion {
        val activos = niveles.filter { it.estado != INACTIVO }
        val frontis = listOfNotNull(frontisPrincipal) + frentes.filter { it.estado != INACTIVO }.mapNotNull { it.frontis }
        return TotalesDeLaDeclaracion(
            areaConstruida = centavos(activos.sumOf { it.areaConstruida ?: BigDecimal.ZERO }),
            areaComunConstruida = centavos(activos.sumOf { it.areaComun ?: BigDecimal.ZERO }),
            longitudFrente = if (frontis.isEmpty()) null else centavos(frontis.sumOf { it })
        )
    }

    // catastro's answer: its autoavalúo, else its motivo (its llave when it has no text)
    fun autoavaluo(v: ValuacionDeInsumos): Autoavaluo =
        v.autoavaluo?.let { Autoavaluo.Valuado(it) }
            ?: Autoavaluo.PorRevisar(v.motivo?.ifBlank { null } ?: v.llave?.ifBlank { null } ?: "El catastro no valuó la declaración")

    fun noResponde(mensaje: String?) = Autoavaluo.PorRevisar("El catastro no respondió: ${mensaje ?: "sin detalle"}")

    // the declaración with its totales and autoavalúo; the condominio's values follow from it (condominioCon)
    fun aplicar(
        d: Declaracion,
        t: TotalesDeLaDeclaracion,
        autoavaluo: Autoavaluo
    ): Declaracion {
        val conTotales = d.copy(areaConstruida = t.areaConstruida, areaComunConstruida = t.areaComunConstruida, longitudFrente = t.longitudFrente)
        return when (autoavaluo) {
            is Autoavaluo.Valuado -> conTotales.copy(valorAutoavaluo = autoavaluo.valor, autoavaluoPorRevisar = null, autoavaluoMotivo = null)
            is Autoavaluo.PorRevisar -> conTotales.copy(autoavaluoPorRevisar = true, autoavaluoMotivo = autoavaluo.motivo)
            Autoavaluo.Manual -> conTotales
        }
    }

    // the fields the backend derives of a declaración (its lists, catastro, its condominio), by their model names
    fun campos(d: Declaracion): Map<String, Any?> =
        mapOf(
            "area_construida" to d.areaConstruida,
            "area_comun_construida" to d.areaComunConstruida,
            "longitud_frente" to d.longitudFrente,
            "valor_autoavaluo" to d.valorAutoavaluo,
            "autoavaluo_por_revisar" to d.autoavaluoPorRevisar,
            "autoavaluo_motivo" to d.autoavaluoMotivo,
            "condicion_propiedad" to d.condicionPropiedad,
            "porcentaje_condominio" to d.porcentajeCondominio,
            "valor_condominio" to d.valorCondominio,
            "valor_afecto" to d.valorAfecto
        )

    // the derived fields that differ, compared by value: 100 and 100.00 are the same
    fun cambiados(
        antes: Declaracion,
        despues: Declaracion
    ): Set<String> {
        val a = campos(antes)
        return campos(despues).filter { (campo, valor) -> comparable(valor) != comparable(a[campo]) }.keys
    }

    private fun comparable(valor: Any?) = if (valor is BigDecimal) valor.stripTrailingZeros() else valor

    private fun centavos(valor: BigDecimal) = valor.setScale(2, RoundingMode.HALF_UP)
}
