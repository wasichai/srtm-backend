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

// diferidas: the ranuras whose plan waits for an orden caja may still answer for (POR_ENVIAR already tried, or
// POR_RETIRAR): neither an alta nor a cancel for them until that one is resolved
data class Plan(
    val altas: List<Alta>,
    val retiros: List<Retiro>,
    val cancelar: List<UUID>,
    val diferidas: Int = 0
)

fun referenciaPredial(
    contribuyente: UUID,
    anio: Int,
    numero: Int,
    version: Int
) = "PREDIAL-$contribuyente-$anio-C$numero-v$version"

fun referenciaArbitrio(clave: String) = "ARB-$clave"

// a cuota de predial without its revision
fun ranuraPredial(
    contribuyente: UUID,
    anio: Int,
    numero: Int
) = "PREDIAL|$contribuyente|$anio|$numero"

// the clave without its version: predio|servicio|anio|periodo|secuencia (or without secuencia in the legacy ones)
fun ranuraArbitrio(clave: String) = "ARB|${clave.substringBeforeLast('|')}"

// an orden POR_ENVIAR a take already tried: caja may hold it (a timeout, an answer lost, a send in flight)
fun intentada(o: OrdenCaja) = o.estado == EstadoOrdenCaja.POR_ENVIAR && (o.intentos ?: 0) > 0

// what caja may still answer for (#164): an alta already tried, a retiro queued (caja may say it was paid), or an
// ERROR already tried (Ruling 27: a NoContesta exhausted may be in caja). while one of them is in the ranura, a new
// alta could charge the cuota twice
private fun esperaACaja(o: OrdenCaja) = intentada(o) || o.estado == EstadoOrdenCaja.POR_RETIRAR || (o.estado == EstadoOrdenCaja.ERROR && (o.intentos ?: 0) > 0)

// Ruling 27: what a sincronización a pedido does first with the ordenes in ERROR (caja may hold any of them): the one
// whose referencia srtm still wants is sent again, the others are withdrawn, so none holds its ranura for ever. a ranura
// paid (its deseada, or a PAGADA_EN_CAJA) is left as it is. pure: the id of each and its new estado
fun reintentosAPedido(
    deseadas: List<OrdenDeseada>,
    actuales: List<OrdenCaja>
): Map<UUID, String> {
    val vivas = deseadas.filter { it.importe.compareTo(BigDecimal.ZERO) != 0 && !it.pagada }.map { it.referencia }.toSet()
    val pagadas =
        deseadas.filter { it.pagada }.map { it.ranura }.toSet() +
            actuales.filter { it.estado == EstadoOrdenCaja.PAGADA_EN_CAJA }.mapNotNull { it.ranura }
    return actuales
        .filter { it.estado == EstadoOrdenCaja.ERROR && it.id != null && it.ranura !in pagadas }
        .associate { UUID.fromString(it.id) to if (it.referencia in vivas) EstadoOrdenCaja.POR_ENVIAR else EstadoOrdenCaja.POR_RETIRAR }
}

// compares what srtm wants with what was queued, ranura by ranura. pure: the caller persists the plan.
// CONFLICTO, ERROR, CANCELADA and RETIRADA rows are not vigentes. only an orden POR_ENVIAR never tried is cancelled: one
// already tried, a retiro queued, or an ERROR tried, defers its ranura (Plan.diferidas) until the queue resolves it, so
// caja never holds two of one cuota.
// altas follow the deseadas' order, retiros and cancelaciones the actuales'
fun planificar(
    deseadas: List<OrdenDeseada>,
    actuales: List<OrdenCaja>
): Plan {
    val referencias = actuales.mapNotNull { it.referencia }.toSet()
    val porRanura = actuales.groupBy { it.ranura }
    val pagadas = actuales.filter { it.estado == EstadoOrdenCaja.PAGADA_EN_CAJA }.map { it.ranura }.toSet()
    // an importe of 0 is nothing to collect: the ranura goes on as if there were no deseada. one deseada per ranura
    // (the caller's precondition): associateBy keeps only the last of several
    val vivas = deseadas.filter { it.importe.compareTo(BigDecimal.ZERO) != 0 }.associateBy { it.ranura }

    val altas = mutableListOf<Alta>()
    val diferidas = mutableSetOf<String>()
    val nuevas = mutableMapOf<String, String>() // ranura -> referencia of the new alta
    for (d in vivas.values) {
        if (d.pagada || d.ranura in pagadas || d.referencia in referencias) continue
        val suyas = porRanura[d.ranura].orEmpty()
        if (suyas.any(::esperaACaja)) {
            diferidas += d.ranura
            continue
        }
        val enviada = suyas.firstOrNull { it.estado == EstadoOrdenCaja.ENVIADA }
        altas += Alta(d, enviada?.referencia)
        nuevas[d.ranura] = d.referencia
    }

    val retiros = mutableListOf<Retiro>()
    val cancelar = mutableListOf<UUID>()
    for (a in actuales) {
        val ranura = a.ranura ?: continue
        val id = a.id?.let { UUID.fromString(it) } ?: continue
        if (ranura in pagadas || ranura in diferidas) continue
        if (ranura in vivas) {
            // a new version: what never reached the caja is not needed any more
            if (ranura in nuevas && a.estado == EstadoOrdenCaja.POR_ENVIAR && a.referencia != nuevas[ranura]) {
                cancelar += id
            }
        } else if (a.estado == EstadoOrdenCaja.ENVIADA) {
            retiros += Retiro(id, a.referencia.orEmpty())
        } else if (intentada(a)) {
            // nothing to collect, but caja may hold it: it is withdrawn once the queue resolves it
            diferidas += ranura
        } else if (a.estado == EstadoOrdenCaja.POR_ENVIAR) {
            cancelar += id
        }
    }
    return Plan(altas, retiros, cancelar, diferidas.size)
}
