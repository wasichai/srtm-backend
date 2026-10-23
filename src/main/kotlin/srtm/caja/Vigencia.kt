package srtm.caja

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ValidationException
import java.math.RoundingMode

// one orden of the answer: caja's VigenciaPorHttp reads referencia_externa, vigente, importe and motivo. one it does
// not find in the answer is «no contesta», so every referencia asked is answered
data class VigenciaDeOrden(
    val referenciaExterna: String,
    val vigente: Boolean,
    val importe: String?,
    val motivo: String?
)

// #168: caja asks, before it collects, whether srtm's ordenes still stand: POST {url}/ordenes/vigencia
object VigenciaDeOrdenes {
    const val MAXIMO = 50

    // the referencias asked, in order (a repeated one is asked, and answered, each time): a 400 for a body that is no
    // question, for none, or for more than MAXIMO. the importe caja asks with is not read: srtm answers its own
    fun referencias(cuerpo: String?): List<String> {
        val raiz =
            try {
                JSON.readTree(cuerpo.orEmpty())
            } catch (_: JacksonException) {
                null
            }
        val ordenes = raiz?.get("ordenes")?.takeIf { it.isArray } ?: throw invalida("ordenes", "falta la lista")
        if (ordenes.size() == 0) throw invalida("ordenes", "está vacía")
        if (ordenes.size() > MAXIMO) throw invalida("ordenes", "son más de $MAXIMO")
        return ordenes.mapIndexed { i, o -> referencia(o) ?: throw invalida("ordenes[$i].referencia_externa", "falta") }
    }

    private fun referencia(o: JsonNode): String? =
        o
            .get("referencia_externa")
            ?.takeIf { it.isString }
            ?.asString()
            ?.trim()
            ?.ifEmpty { null }

    // an orden srtm collects: the one that exists in the organization, was sent (ENVIADA, or POR_ENVIAR or ERROR already
    // tried: caja asking about it proves it holds it, and the pagos take it as vigente too), is still its cuota's (not
    // annulled, not replaced) and was not paid in caja. otherwise the motivo says why (the cuota's first: an orden srtm
    // already withdrew still says why). importe: srtm's. conPago: a pago of caja came for it (Ruling 28): a
    // PAGADA_EN_CAJA whose pago went a cuenta is not paid, srtm withdrew it
    fun decidir(
        referencia: String,
        orden: OrdenCaja?,
        cuotaVigente: Boolean,
        recibo: String?,
        sustituta: String?,
        anulacion: String?,
        conPago: Boolean = false
    ): VigenciaDeOrden {
        if (orden == null) return VigenciaDeOrden(referencia, false, null, "no existe")
        val importe = orden.importe?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
        val motivo =
            when {
                !cuotaVigente && anulacion != null -> "anulada: $anulacion"
                !cuotaVigente && sustituta != null -> "sustituida por $sustituta"
                !cuotaVigente -> "la cuota ya no se cobra"
                // the recibo first: a PAGADA_EN_CAJA orden whose pago srtm already has names it
                recibo != null -> "ya pagada con el recibo $recibo"
                orden.estado == EstadoOrdenCaja.PAGADA_EN_CAJA && !conPago -> "ya pagada en caja"
                orden.estado in RETIRADAS_POR_SRTM || orden.estado == EstadoOrdenCaja.PAGADA_EN_CAJA -> "retirada"
                orden.estado != EstadoOrdenCaja.ENVIADA && !intentada(orden) && !errorIntentado(orden) -> "todavía no se envió a caja"
                else -> null
            }
        return VigenciaDeOrden(referencia, motivo == null, importe, motivo)
    }

    fun cuerpo(respuestas: List<VigenciaDeOrden>): String =
        JSON.writeValueAsString(
            mapOf(
                "ordenes" to
                    respuestas.map {
                        linkedMapOf(
                            "referencia_externa" to it.referenciaExterna,
                            "vigente" to it.vigente,
                            "importe" to it.importe,
                            "motivo" to it.motivo
                        )
                    }
            )
        )

    // the ordenes srtm withdrew or cancelled. not PagosService's SIN_DEUDA, which also has PAGADA_EN_CAJA (paid, not
    // withdrawn: here it has its own motivo)
    private val RETIRADAS_POR_SRTM = setOf(EstadoOrdenCaja.POR_RETIRAR, EstadoOrdenCaja.RETIRADA, EstadoOrdenCaja.CANCELADA)

    // Ruling 26: an ERROR already tried (a NoContesta exhausted) may be in caja, as an orden POR_ENVIAR tried
    private fun errorIntentado(o: OrdenCaja) = o.estado == EstadoOrdenCaja.ERROR && (o.intentos ?: 0) > 0

    private fun invalida(
        campo: String,
        razon: String
    ) = ValidationException("La pregunta de vigencia no se puede leer: $campo $razon", campo, razon)

    private val JSON: JsonMapper = JsonMapper.builder().build()
}
