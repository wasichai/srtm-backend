package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.rentas.ANULADA
import srtm.rentas.Declaracion
import srtm.rentas.UsoPredio
import java.math.BigDecimal
import java.time.LocalDate

// rentas' TitularPrincipalPorElPuertoTest, and which declarations cover which day (decision 3 of the plan)
class TitularesTest {
    private val codigos = mapOf("c-500" to "00000500", "c-501" to "00000501", "c-502" to "00000502", "c-503" to "00000503")

    private fun dj(
        id: String,
        contribuyente: String,
        porcentaje: String = "100",
        adquisicion: LocalDate? = null,
        secuencia: String = "01"
    ) = Declaracion(
        id = id,
        contribuyente = contribuyente,
        anio = 2026,
        secuenciaUso = secuencia,
        porcentajeCondominio = BigDecimal(porcentaje),
        fechaAdquisicion = adquisicion
    )

    @Test
    fun `with one titular, that one`() {
        assertEquals("c-501", titularPrincipal(listOf(dj("1", "c-501")), codigos)?.contribuyente)
    }

    @Test
    fun `with several, the largest share - the arbitrio is charged to who holds most`() {
        val djs = listOf(dj("1", "c-501", "30"), dj("2", "c-502", "70"), dj("3", "c-503", "0.5"))
        assertEquals("c-502", titularPrincipal(djs, codigos)?.contribuyente)
    }

    @Test
    fun `a tie is broken the same way in every run - the order is total`() {
        // what matters of a tie is not which one but that it never changes: two runs of the same determination cannot
        // charge the arbitrio to different people
        val djs = listOf(dj("1", "c-503", "50"), dj("2", "c-502", "50"))
        assertEquals("c-502", titularPrincipal(djs, codigos)?.contribuyente) // the lowest code
        assertEquals("c-502", titularPrincipal(djs.reversed(), codigos)?.contribuyente)

        val antes = listOf(dj("1", "c-502", "50", LocalDate.of(2020, 1, 1)), dj("2", "c-501", "50", LocalDate.of(2021, 1, 1)))
        assertEquals("c-502", titularPrincipal(antes, codigos)?.contribuyente) // the earliest acquisition
        assertEquals("c-502", titularPrincipal(antes.reversed(), codigos)?.contribuyente)
    }

    @Test
    fun `with several secuencias de uso, the first one's declaration`() {
        val djs = listOf(dj("2", "c-501", secuencia = "02"), dj("1", "c-501", secuencia = "01"))
        assertEquals("01", titularPrincipal(djs, codigos)?.secuenciaUso)
    }

    @Test
    fun `a predio without a titular gives no one, and it is not an error`() {
        assertNull(titularPrincipal(emptyList(), codigos))
    }

    @Test
    fun `an imported declaration without dates covers the year`() {
        val d = dj("1", "c-501")
        assertTrue(cubre(d, LocalDate.of(2026, 1, 1)))
        assertTrue(cubre(d, LocalDate.of(2026, 12, 1)))
        assertFalse(cubre(d, LocalDate.of(2027, 1, 1)))
        assertFalse(cubre(d, LocalDate.of(2025, 12, 1)))
    }

    @Test
    fun `an acquisition in the year counts from the next day, and an earlier one from 1 january`() {
        val julio = dj("1", "c-501", adquisicion = LocalDate.of(2026, 7, 1))
        assertFalse(cubre(julio, LocalDate.of(2026, 7, 1)))
        assertTrue(cubre(julio, LocalDate.of(2026, 7, 2)))
        assertTrue(cubre(julio, LocalDate.of(2026, 8, 1)))
        assertTrue(cubre(dj("2", "c-501", adquisicion = LocalDate.of(2019, 7, 1)), LocalDate.of(2026, 1, 1)))
        assertFalse(cubre(dj("3", "c-501", adquisicion = LocalDate.of(2027, 2, 1)), LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun `an annulled declaration covers up to its date, and without one, nothing`() {
        val descargo = dj("1", "c-501").copy(estado = ANULADA, fechaAnulacion = LocalDate.of(2026, 5, 20))
        assertTrue(cubre(descargo, LocalDate.of(2026, 5, 1)))
        assertFalse(cubre(descargo, LocalDate.of(2026, 6, 1)))
        assertFalse(cubre(dj("2", "c-501").copy(estado = ANULADA), LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `the date travels - who was titular in march is not today's`() {
        // rentas#24 and #366: resolving with the clock would give the buyer of july
        val vendedor = dj("1", "c-501").copy(estado = ANULADA, fechaAnulacion = LocalDate.of(2026, 6, 30))
        val comprador = dj("2", "c-502", adquisicion = LocalDate.of(2026, 6, 30))

        fun el(dia: LocalDate) = titularPrincipal(listOf(vendedor, comprador).filter { cubre(it, dia) }, codigos)?.contribuyente
        assertEquals("c-501", el(LocalDate.of(2026, 3, 1)))
        assertEquals("c-502", el(LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `the uso code is as precise as the declaration`() {
        val usos = listOf(UsoPredio("010101", "RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"), UsoPredio("010201", "RESIDENCIAL", "MULTIFAMILIAR", "EDIFICIO"))
        val casa = Declaracion(claseUso = "RESIDENCIAL", subClaseUso = "UNIFAMILIAR", uso = "CASA HABITACIÓN")
        assertEquals("010101", codigoDeUso(casa, usos))
        assertEquals("0102", codigoDeUso(casa.copy(subClaseUso = "MULTIFAMILIAR", uso = null), usos))
        assertEquals("01", codigoDeUso(casa.copy(subClaseUso = null, uso = null), usos))
        assertNull(codigoDeUso(casa.copy(claseUso = null), usos))
        assertNull(codigoDeUso(casa.copy(claseUso = "COMERCIO"), usos))
        assertNull(codigoDeUso(casa.copy(uso = "QUINTA"), usos))
    }
}
