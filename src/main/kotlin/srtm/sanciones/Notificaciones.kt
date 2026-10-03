package srtm.sanciones

import wasichai.core.common.ConflictException
import java.time.LocalDate

// the notificación previa: when it ends and when it has ended (rentas #411). the one definition of «vencida»: the
// padrón of vencidas, the fase of the acta and the subsanación all ask here
object Notificaciones {
    // the last day of its plazo, in calendar days: fecha + plazo_dias. none without a plazo
    fun vencimiento(
        fecha: LocalDate,
        plazoDias: Int?
    ): LocalDate? = plazoDias?.let { fecha.plusDays(it.toLong()) }

    // ended on `corte`: the last day still counts (it ends the day after); without a plazo it never ends
    fun vencida(
        fecha: LocalDate,
        plazoDias: Int?,
        corte: LocalDate
    ): Boolean = vencimiento(fecha, plazoDias)?.let { corte > it } ?: false

    fun vencida(
        n: NotificacionAdministrativa,
        corte: LocalDate
    ): Boolean = vencida(n.fecha!!, n.plazoDias, corte)

    // a subsanación on `fecha` (decision 5): once (409), not of one that already has its acta, nor after the plazo
    // (422; rentas answered 409 to the late one: it is the date that is wrong)
    fun exigirSubsanable(
        n: NotificacionAdministrativa,
        subsanada: Boolean,
        conActa: Boolean,
        fecha: LocalDate
    ) {
        if (subsanada) throw ConflictException("La notificación ${n.numero} ya está subsanada")
        if (conActa) throw NoProcede("La notificación ${n.numero} ya originó un acta: no se subsana")
        if (vencida(n, fecha)) {
            throw NoProcede("La notificación ${n.numero} venció el ${vencimiento(n.fecha!!, n.plazoDias)}: no se subsana el $fecha")
        }
    }

    // an acta names its notificación previa only while that one is not subsanada (decision 5): what was corrected
    // originates nothing
    fun exigirQueOrigineActa(
        n: NotificacionAdministrativa,
        subsanada: Boolean
    ) {
        if (subsanada) throw NoProcede("La notificación ${n.numero} está subsanada: no origina un acta")
    }
}
