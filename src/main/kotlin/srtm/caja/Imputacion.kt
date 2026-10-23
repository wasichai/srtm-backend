package srtm.caja

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException

// #165: what caja's buzón delivers with POST {url}/pagos (caja-backend's cuerpoPagoRegistrado and cuerpoPagoAnulado,
// in camelCase, the importes as strings), read here. what else it carries is ignored: the contract may grow

// the recibo of a pago, as caja printed it. numero is the numero impreso, its serie included (001-0000123)
data class ReciboDeCaja(
    val numero: String?,
    val serie: String?,
    val fechaDePago: LocalDate?,
    val cajero: String?,
    val formaDePago: String?
)

data class PagadorDeCaja(
    val documento: String?,
    val nombre: String?
)

// an orden a pago covers: caja's id of it, srtm's referencia and what caja collected for it
data class OrdenDelPago(
    val ordenId: String?,
    val referencia: String,
    val importe: BigDecimal
)

sealed interface EventoDeCaja {
    val pagoId: String
    val recibo: ReciboDeCaja
    val total: BigDecimal?
}

data class PagoRegistrado(
    override val pagoId: String,
    override val recibo: ReciboDeCaja,
    val pagador: PagadorDeCaja?,
    override val total: BigDecimal?,
    val ordenes: List<OrdenDelPago>
) : EventoDeCaja

data class PagoAnulado(
    override val pagoId: String,
    val pagoOriginalId: String,
    override val recibo: ReciboDeCaja,
    val motivo: String?,
    val fecha: LocalDate?,
    override val total: BigDecimal?
) : EventoDeCaja

// what srtm does with one orden of a pago: applied to its cuota, or kept a cuenta (saldo a favor) with the reason.
// orden and contribuyente: srtm's orden_caja and its contribuyente, when srtm has it
data class Detalle(
    val referencia: String,
    val importe: BigDecimal,
    val aplicado: Boolean,
    val motivo: String?,
    val orden: String?,
    val contribuyente: String?
)

// the event as it travels, every value as a string: read and checked by leerEvento
@JsonIgnoreProperties(ignoreUnknown = true)
private data class Evento(
    val pagoId: String? = null,
    val tipo: String? = null,
    val pagoOriginalId: String? = null,
    val recibo: Recibo? = null,
    val pagador: Pagador? = null,
    val total: String? = null,
    val motivo: String? = null,
    val fecha: String? = null,
    val ordenes: List<Orden>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class Recibo(
    val numero: String? = null,
    val serie: String? = null,
    val fechaDePago: String? = null,
    val cajero: String? = null,
    val formaDePago: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class Pagador(
    val documento: String? = null,
    val nombre: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class Orden(
    val ordenId: String? = null,
    val referenciaExterna: String? = null,
    val importe: String? = null
)

private val JSON: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

private const val ILEGIBLE = "El pago de caja no se puede leer"

private fun ilegible(
    campo: String,
    razon: String
): Nothing = throw ValidationException("$ILEGIBLE: $campo $razon", campo, razon)

private fun importe(
    texto: String?,
    campo: String
): BigDecimal = texto?.trim()?.toBigDecimalOrNull() ?: ilegible(campo, "no es un importe")

private fun fecha(
    texto: String?,
    campo: String
): LocalDate? =
    texto?.takeIf { it.isNotBlank() }?.let {
        try {
            LocalDate.parse(it.trim().take(10))
        } catch (_: DateTimeParseException) {
            ilegible(campo, "no es una fecha")
        }
    }

// the event, or a 400 that names what it lacks: broken json, no pagoId or tipo, an unknown tipo, an importe that is
// no decimal, a PAGO_REGISTRADO without ordenes (or with one twice), a PAGO_ANULADO without its original. a 400 is
// caja's MUERTO: only for what will never be read
fun leerEvento(cuerpo: String?): EventoDeCaja {
    val e =
        try {
            JSON.readValue(cuerpo.orEmpty(), Evento::class.java)
        } catch (_: JacksonException) {
            null
        } ?: ilegible("cuerpo", "no es un evento de caja en json")
    val pagoId = e.pagoId?.trim()?.ifEmpty { null } ?: ilegible("pagoId", "falta")
    val total = e.total?.let { importe(it, "total") }
    val r = e.recibo
    val recibo = ReciboDeCaja(r?.numero, r?.serie, fecha(r?.fechaDePago, "recibo.fechaDePago"), r?.cajero, r?.formaDePago)
    return when (e.tipo) {
        TipoPagoCaja.PAGO_REGISTRADO -> {
            val ordenes =
                e.ordenes.orEmpty().mapIndexed { i, o ->
                    val referencia = o.referenciaExterna?.trim()?.ifEmpty { null } ?: ilegible("ordenes[$i].referenciaExterna", "falta")
                    OrdenDelPago(o.ordenId, referencia, importe(o.importe, "ordenes[$i].importe"))
                }
            if (ordenes.isEmpty()) ilegible("ordenes", "el pago no trae órdenes")
            ordenes.groupBy { it.referencia }.values.firstOrNull { it.size > 1 }?.let {
                ilegible("ordenes", "la orden ${it.first().referencia} está repetida en el pago")
            }
            PagoRegistrado(pagoId, recibo, e.pagador?.let { PagadorDeCaja(it.documento, it.nombre) }, total, ordenes)
        }
        TipoPagoCaja.PAGO_ANULADO -> {
            val original = e.pagoOriginalId?.trim()?.ifEmpty { null } ?: ilegible("pagoOriginalId", "falta")
            PagoAnulado(pagoId, original, recibo, e.motivo, fecha(e.fecha, "fecha"), total)
        }
        null -> ilegible("tipo", "falta")
        else -> ilegible("tipo", "no es ${TipoPagoCaja.PAGO_REGISTRADO} ni ${TipoPagoCaja.PAGO_ANULADO}")
    }
}

// the pure rules of a pago's imputación: each orden on its own
object Imputacion {
    // what srtm answers caja is at most this long
    const val MOTIVO_MAXIMO = 400

    private val RESPUESTAS: JsonMapper = JsonMapper.builder().build()

    // an orden is applied when its orden_caja is of this organization (enSrtm), its cuota is still vigente, caja
    // collected its importe, and no other pago (applied, not annulled) has it: yaPagada is that pago's recibo.
    // otherwise it goes a cuenta, with the reason. sustituta: the referencia of the orden that replaced it, when it
    // is no longer vigente
    fun clasificar(
        orden: OrdenDelPago,
        enSrtm: OrdenCaja?,
        vigente: Boolean,
        yaPagada: String?,
        sustituta: String? = null
    ): Detalle {
        val ref = orden.referencia
        val motivo =
            when {
                enSrtm == null -> "no existe"
                !vigente && sustituta != null -> "la deuda $ref fue sustituida por $sustituta"
                !vigente -> "la deuda $ref ya no está vigente"
                enSrtm.importe == null -> "el importe no coincide: ${soles(orden.importe)} en caja, sin importe en srtm"
                enSrtm.importe.compareTo(orden.importe) != 0 -> "el importe no coincide: ${soles(orden.importe)} en caja, ${soles(enSrtm.importe)} en srtm"
                yaPagada != null -> "ya pagada con el recibo $yaPagada"
                else -> null
            }
        return Detalle(ref, orden.importe, motivo == null, motivo, enSrtm?.id, enSrtm?.contribuyente)
    }

    // why some went a cuenta: each reason once, joined by "; ", up to 400 characters. null: every one was applied
    fun motivo(detalles: List<Detalle>): String? =
        detalles
            .mapNotNull { it.motivo }
            .distinct()
            .joinToString("; ")
            .ifEmpty { null }
            ?.take(MOTIVO_MAXIMO)

    // the body of the answer: {"aplicado":…,"a_cuenta":…,"motivo":…}, in that order
    fun respuesta(
        aplicado: Boolean,
        aCuenta: Boolean,
        motivo: String?
    ): String = RESPUESTAS.writeValueAsString(linkedMapOf("aplicado" to aplicado, "a_cuenta" to aCuenta, "motivo" to motivo))

    // a recibo as the cajero reads it: serie-numero. caja's numero impreso already starts with its serie
    fun recibo(
        serie: String?,
        numero: String?
    ): String {
        val n = numero.orEmpty()
        if (serie.isNullOrBlank() || n.startsWith("$serie-")) return n
        return "$serie-$n"
    }

    private fun soles(importe: BigDecimal) = importe.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
