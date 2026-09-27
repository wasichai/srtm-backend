package srtm.impuesto

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay

// art. 15 of the TUO LTM: the impuesto predial is paid by the last business day of february (al contado, or the first
// cuota) and of may, august and november (the other three). a business day is monday to friday and no fixed-date
// national feriado. the feriados of movable date (jueves and viernes santo) and the ones a decree adds for a year
// are not here: they never fall at the end of those four months
object Vencimientos {
    // the fixed-date national feriados. the only list of them
    val FERIADOS_NACIONALES: List<MonthDay> =
        listOf(
            MonthDay.of(Month.JANUARY, 1), // año nuevo
            MonthDay.of(Month.MAY, 1), // día del trabajo
            MonthDay.of(Month.JUNE, 7), // batalla de arica y día de la bandera
            MonthDay.of(Month.JUNE, 29), // san pedro y san pablo
            MonthDay.of(Month.JULY, 23), // día de la fuerza aérea
            MonthDay.of(Month.JULY, 28), // fiestas patrias
            MonthDay.of(Month.JULY, 29), // fiestas patrias
            MonthDay.of(Month.AUGUST, 6), // batalla de junín
            MonthDay.of(Month.AUGUST, 30), // santa rosa de lima
            MonthDay.of(Month.OCTOBER, 8), // combate de angamos
            MonthDay.of(Month.NOVEMBER, 1), // todos los santos
            MonthDay.of(Month.DECEMBER, 8), // inmaculada concepción
            MonthDay.of(Month.DECEMBER, 9), // batalla de ayacucho
            MonthDay.of(Month.DECEMBER, 25) // navidad
        )

    private val CUOTAS = listOf(Month.FEBRUARY, Month.MAY, Month.AUGUST, Month.NOVEMBER)

    fun habil(dia: LocalDate): Boolean = dia.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && MonthDay.from(dia) !in FERIADOS_NACIONALES

    fun ultimoDiaHabil(
        anio: Int,
        mes: Month
    ): LocalDate =
        generateSequence(LocalDate.of(anio, mes, 1).plusMonths(1).minusDays(1)) { it.minusDays(1) }
            .first(::habil)

    // the four cuotas' vencimientos, in order
    fun predial(anio: Int): List<LocalDate> = CUOTAS.map { ultimoDiaHabil(anio, it) }
}
