package srtm.sanciones

import srtm.legible
import java.time.LocalDate

// an act this one answers: what it is, as the operator reads it («la infracción del acta AC-0001»), and its day
data class ActoPrevio(
    val nombre: String,
    val fecha: LocalDate
)

// rentas' OrdenDeLosActos (#402): no act is dated before an act it answers (the infracción, the presentación of the
// descargo, the resolución…) nor after today. the same day is fine on both sides. hoy is an argument (rule 6)
object OrdenDeLosActos {
    fun exigir(
        acto: String,
        fecha: LocalDate,
        hoy: LocalDate,
        vararg previos: ActoPrevio,
        campo: String = "fecha"
    ) = exigir(acto, fecha, hoy, previos.toList(), campo)

    // with the previos in a list, for an act whose previo exists or not (a resolución answers a descargo when it has one)
    fun exigir(
        acto: String,
        fecha: LocalDate,
        hoy: LocalDate,
        previos: List<ActoPrevio>,
        campo: String = "fecha"
    ) {
        // the latest one it breaks: the bound the operator has to respect, so the date is corrected once
        val incumplido = previos.filter { fecha < it.fecha }.maxByOrNull { it.fecha }
        if (incumplido != null) {
            throw ActoFueraDeOrden(acto, fecha, incumplido, campo, "es anterior a ${incumplido.nombre}, del ${incumplido.fecha.legible()}")
        }
        if (fecha > hoy) throw ActoFueraDeOrden(acto, fecha, null, campo, "es posterior a hoy, ${hoy.legible()}")
    }
}
