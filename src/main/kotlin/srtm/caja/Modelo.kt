package srtm.caja

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// the objects of the integration with the caja (model/model.json) and the states of an orden. the object is not called
// Caja: that is the name of the http client of the caja
object ObjetosCaja {
    const val CUOTA_PREDIAL = "cuota_predial"
    const val ORDEN_CAJA = "orden_caja"
    const val PAGO_CAJA = "pago_caja"
    const val PAGO_CAJA_DETALLE = "pago_caja_detalle"

    val TODOS = setOf(CUOTA_PREDIAL, ORDEN_CAJA, PAGO_CAJA, PAGO_CAJA_DETALLE)
}

// estado_orden_caja, tipo_orden_caja and tipo_pago_caja
object EstadoOrdenCaja {
    const val POR_ENVIAR = "POR_ENVIAR"
    const val ENVIADA = "ENVIADA"
    const val POR_RETIRAR = "POR_RETIRAR"
    const val RETIRADA = "RETIRADA"
    const val CANCELADA = "CANCELADA"
    const val PAGADA_EN_CAJA = "PAGADA_EN_CAJA"
    const val CONFLICTO = "CONFLICTO"
    const val ERROR = "ERROR"
}

object TipoOrdenCaja {
    const val PREDIAL = "PREDIAL"
    const val ARBITRIO = "ARBITRIO"
}

object TipoPagoCaja {
    const val PAGO_REGISTRADO = "PAGO_REGISTRADO"
    const val PAGO_ANULADO = "PAGO_ANULADO"
}

// an orden srtm asks the caja to collect: a cuota de predial or of arbitrio. a relation is its target's id. its states
// change by direct UPDATE of the queue's worker, never through the api
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrdenCaja(
    val id: String? = null,
    val referencia: String? = null,
    val tipo: String? = null,
    val ranura: String? = null,
    val anio: Int? = null,
    val importe: BigDecimal? = null,
    val concepto: String? = null,
    val detalle: String? = null,
    val fechaExigibilidad: LocalDate? = null,
    val pagadorDocumento: String? = null,
    val pagadorNombre: String? = null,
    val sustituyeA: String? = null,
    val motivo: String? = null,
    val estado: String? = null,
    val intentos: Int? = null,
    val tomadoHasta: Instant? = null,
    val ultimoError: String? = null,
    val ordenId: String? = null,
    val enviadaEn: Instant? = null,
    val retiradaEn: Instant? = null,
    val contribuyente: String? = null,
    val cuotaArbitrio: String? = null,
    val cuotaPredial: String? = null
)
