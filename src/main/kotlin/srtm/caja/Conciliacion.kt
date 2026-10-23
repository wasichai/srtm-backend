package srtm.caja

import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException

// what srtm kept of one day's pagos (Pagadas.delDia): the pagos not annulled with that fecha_pago. aplicados: all their
// detalles applied; aCuenta: at least one detalle kept a cuenta (a mixed pago is only here)
data class ResumenDelDia(
    val recibidos: Long,
    val aplicados: Long,
    val aCuenta: Long,
    val importeAplicado: BigDecimal,
    val importeACuenta: BigDecimal
)

// #166: caja's conciliación asks GET {url}/pagos/conciliacion?fecha= and compares the answer with its own day. the
// shape is what caja-backend's Conciliacion.kt (aplicadoDe) reads: counts as json integers, importes as strings
object Conciliacion {
    // the fecha caja asks for, AAAA-MM-DD: a 400 otherwise
    fun fecha(texto: String?): LocalDate {
        val t = texto?.trim()?.takeIf { it.isNotEmpty() } ?: throw invalida("falta")
        try {
            return LocalDate.parse(t)
        } catch (_: DateTimeParseException) {
            throw invalida("no es una fecha AAAA-MM-DD")
        }
    }

    // rechazados is always 0: srtm does not reject a pago it could read
    fun cuerpo(
        fecha: LocalDate,
        r: ResumenDelDia
    ): String =
        JSON.writeValueAsString(
            linkedMapOf(
                "fecha" to fecha.toString(),
                "recibidos" to r.recibidos,
                "aplicados" to r.aplicados,
                "rechazados" to 0,
                "recibidos_a_cuenta" to r.aCuenta,
                "importe_aplicado" to dinero(r.importeAplicado),
                "importe_a_cuenta" to dinero(r.importeACuenta)
            )
        )

    private fun dinero(v: BigDecimal) = v.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun invalida(razon: String) = ValidationException("La conciliación pide una fecha: fecha $razon", "fecha", razon)

    private val JSON: JsonMapper = JsonMapper.builder().build()
}
