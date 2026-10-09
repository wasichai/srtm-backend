package srtm.arbitrios

import java.time.LocalDate

// rentas' PeriodoDeArbitrio: the arbitrio is monthly, and each month is decided on its day 1 (rentas#443): the titular,
// the uso, the zona, the inafectaciones and the tasas in force that day, not on the day of the run. if an ordinance
// says another day, it changes here and only here
object Periodo {
    const val PRIMERO = 1
    const val ULTIMO = 12
    val MESES = PRIMERO..ULTIMO

    fun atribucion(
        anio: Int,
        periodo: Int
    ): LocalDate {
        require(periodo in MESES) { "El periodo de un arbitrio es un mes, de $PRIMERO a $ULTIMO: $periodo" }
        return LocalDate.of(anio, periodo, 1)
    }
}
