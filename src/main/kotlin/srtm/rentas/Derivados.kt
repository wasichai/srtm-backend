package srtm.rentas

import srtm.brecha.DetalleObraValuacion
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

// #208: the row of the valuation's detail that is the external padrón's otras instalaciones
const val OBRAS_DEL_PADRON = "Obras del padrón de origen"

// the autoavalúo a write leaves: catastro's value (and its obras), the stored one marked with why catastro gave none,
// or the user's (the organization has no catastro: nothing is marked nor overwritten)
sealed interface Autoavaluo {
    data class Valuado(
        val valor: BigDecimal,
        val obras: BigDecimal? = null
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

    // catastro's answer: its autoavalúo and obras, else its motivo (its llave when it has no text)
    fun autoavaluo(v: ValuacionDeInsumos): Autoavaluo =
        v.autoavaluo?.let { Autoavaluo.Valuado(it, v.obras) }
            ?: Autoavaluo.PorRevisar(v.motivo?.ifBlank { null } ?: v.llave?.ifBlank { null } ?: "El catastro no valuó la declaración")

    // #208: while the declaración has no active obra, catastro's valuation adds the external padrón's otras
    // instalaciones as one more obra of its detail. the amount is fixed: it is not depreciated again in later years.
    // not valued, or no amount: catastro's answer as it is
    fun conObrasDelPadron(
        v: ValuacionDeInsumos,
        obras: List<ObraComplementaria>,
        valorOtrasInstalaciones: BigDecimal?
    ): ValuacionDeInsumos {
        val autoavaluo = v.autoavaluo ?: return v
        val monto = valorOtrasInstalaciones?.takeIf { it.signum() > 0 } ?: return v
        if (obras.any { it.estado != INACTIVO }) return v
        val fila =
            DetalleObraValuacion(
                codigoVu = null,
                cantidad = null,
                valorUnitario = null,
                factorOficializacion = null,
                tablaDepreciacion = null,
                antiguedad = null,
                porcentajeDepreciacion = null,
                valor = monto,
                descripcion = OBRAS_DEL_PADRON
            )
        return v.copy(
            autoavaluo = autoavaluo + monto,
            obras = (v.obras ?: BigDecimal.ZERO) + monto,
            obrasPadronOrigen = monto,
            detalle = v.detalle?.let { it.copy(obras = it.obras + fila) }
        )
    }

    fun noResponde(mensaje: String?) = Autoavaluo.PorRevisar("El catastro no respondió: ${mensaje ?: "sin detalle"}")

    // the declaración with its totales and autoavalúo; the condominio's values follow from it (condominioCon)
    fun aplicar(
        d: Declaracion,
        t: TotalesDeLaDeclaracion,
        autoavaluo: Autoavaluo
    ): Declaracion {
        val conTotales = d.copy(areaConstruida = t.areaConstruida, areaComunConstruida = t.areaComunConstruida, longitudFrente = t.longitudFrente)
        return when (autoavaluo) {
            is Autoavaluo.Valuado ->
                conTotales.copy(valorAutoavaluo = autoavaluo.valor, valorObras = autoavaluo.obras, autoavaluoPorRevisar = null, autoavaluoMotivo = null)
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
            "valor_obras" to d.valorObras,
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
