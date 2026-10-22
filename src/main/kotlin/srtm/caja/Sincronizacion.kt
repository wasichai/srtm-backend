package srtm.caja

import java.math.BigDecimal
import java.util.UUID

// an orden srtm wants the caja to hold: the debt as it is now. the ranura names the debt across versions, the
// referencia names one version of it
data class OrdenDeseada(
    val referencia: String,
    val ranura: String,
    val tipo: String,
    val importe: BigDecimal,
    val concepto: String,
    val detalle: String?,
    val cuotaArbitrio: UUID?,
    val cuotaPredial: UUID?,
    val pagada: Boolean
)

data class Alta(
    val deseada: OrdenDeseada,
    val sustituyeA: String?
)

data class Retiro(
    val ordenCaja: UUID,
    val referencia: String
)

data class Plan(
    val altas: List<Alta>,
    val retiros: List<Retiro>,
    val cancelar: List<UUID>
)

fun referenciaPredial(
    contribuyente: UUID,
    anio: Int,
    numero: Int,
    version: Int
) = "PREDIAL-$contribuyente-$anio-C$numero-v$version"

fun referenciaArbitrio(clave: String) = "ARB-$clave"

// the clave without its version: predio|servicio|anio|periodo|secuencia (or without secuencia in the legacy ones)
fun ranuraArbitrio(clave: String) = "ARB|${clave.substringBeforeLast('|')}"

// compares what srtm wants with what was already queued, ranura by ranura. pure: the caller persists the plan.
// CONFLICTO, ERROR, CANCELADA and RETIRADA rows are not vigentes
fun planificar(
    deseadas: List<OrdenDeseada>,
    actuales: List<OrdenCaja>
): Plan {
    val referencias = actuales.mapNotNull { it.referencia }.toSet()
    val porRanura = actuales.groupBy { it.ranura }
    val pagadas = actuales.filter { it.estado == EstadoOrdenCaja.PAGADA_EN_CAJA }.map { it.ranura }.toSet()
    // an importe of 0 is nothing to collect: the ranura goes on as if there were no deseada
    val vivas = deseadas.filter { it.importe.compareTo(BigDecimal.ZERO) != 0 }.associateBy { it.ranura }

    val altas = mutableListOf<Alta>()
    val nuevas = mutableMapOf<String, String>() // ranura -> referencia of the new alta
    for (d in vivas.values) {
        if (d.pagada || d.ranura in pagadas || d.referencia in referencias) continue
        val enviada = porRanura[d.ranura]?.firstOrNull { it.estado == EstadoOrdenCaja.ENVIADA }
        altas += Alta(d, enviada?.referencia)
        nuevas[d.ranura] = d.referencia
    }

    val retiros = mutableListOf<Retiro>()
    val cancelar = mutableListOf<UUID>()
    for (a in actuales) {
        val ranura = a.ranura ?: continue
        val id = a.id?.let { UUID.fromString(it) } ?: continue
        if (ranura in pagadas) continue
        if (ranura in vivas) {
            // a new version: what never reached the caja is not needed any more
            if (ranura in nuevas && a.estado == EstadoOrdenCaja.POR_ENVIAR && a.referencia != nuevas[ranura]) {
                cancelar += id
            }
        } else if (a.estado == EstadoOrdenCaja.ENVIADA) {
            retiros += Retiro(id, a.referencia.orEmpty())
        } else if (a.estado == EstadoOrdenCaja.POR_ENVIAR) {
            cancelar += id
        }
    }
    return Plan(altas, retiros, cancelar)
}
