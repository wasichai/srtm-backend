package srtm.impuesto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Month

// art. 15 of the TUO LTM: the last business day of february, may, august and november
class VencimientosTest {
    @Test
    fun `the four vencimientos of 2026`() {
        // 28-feb is a saturday; 31-may a sunday and 30-may a saturday; 31-aug a monday (30-aug is a sunday and a
        // feriado); 30-nov a monday
        assertEquals(
            listOf(LocalDate.of(2026, 2, 27), LocalDate.of(2026, 5, 29), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 11, 30)),
            Vencimientos.predial(2026)
        )
    }

    @Test
    fun `a feriado on a weekday moves the vencimiento back`() {
        // 2024: 31-aug is a saturday and 30-aug (santa rosa de lima) a friday
        assertEquals(LocalDate.of(2024, 8, 29), Vencimientos.ultimoDiaHabil(2024, Month.AUGUST))
        // 2029: 30-jun is a saturday and 29-jun (san pedro y san pablo) a friday
        assertEquals(LocalDate.of(2029, 6, 28), Vencimientos.ultimoDiaHabil(2029, Month.JUNE))
    }

    @Test
    fun `weekends and fixed feriados are not business days`() {
        assertFalse(Vencimientos.habil(LocalDate.of(2026, 2, 28))) // saturday
        assertFalse(Vencimientos.habil(LocalDate.of(2026, 3, 1))) // sunday
        assertFalse(Vencimientos.habil(LocalDate.of(2026, 7, 28))) // tuesday, fiestas patrias
        assertFalse(Vencimientos.habil(LocalDate.of(2026, 7, 29))) // wednesday, fiestas patrias
        assertFalse(Vencimientos.habil(LocalDate.of(2026, 12, 8))) // tuesday, inmaculada concepción
        assertTrue(Vencimientos.habil(LocalDate.of(2026, 8, 31))) // monday
    }

    @Test
    fun `the feriados live in one list`() {
        assertEquals(14, Vencimientos.FERIADOS_NACIONALES.size)
    }
}
