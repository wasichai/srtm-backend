package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import srtm.emision.Cabecera
import srtm.impuesto.ParametroTributario
import srtm.rentas.Contribuyente
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

// what the HLA prints of a contribuyente's year: only the months with a cuota, the backend's totals, each month's due
// date and the ordinance it comes from. FICTITIOUS figures
class HojaHlaTest {
    private val servicios =
        listOf(
            ServicioArbitrio(id = "s1", codigo = "LIMPIEZA", nombre = "Limpieza pública", orden = 1),
            ServicioArbitrio(id = "s2", codigo = "SERENAZGO", nombre = "Serenazgo", orden = 2)
        )

    private fun cuota(monto: String) = CuotaMes("c", BigDecimal(monto), "c1", LocalDate.of(2026, 3, 15), "TASA_ARBITRIO:X")

    // january to may only: the contribuyente sold in may
    private val matriz =
        MatrizArbitrios(
            anio = 2026,
            predio = PredioResumen("p1", "01-01-0001", "JR. LIMA 123"),
            filas =
                listOf(
                    FilaServicio(servicios[0], List(12) { if (it < 5) cuota("8.50") else null }, BigDecimal("42.50")),
                    FilaServicio(servicios[1], List(12) { if (it < 5) cuota("4.25") else null }, BigDecimal("21.25"))
                ),
            titulares = emptyList(),
            totalesPorMes = List(12) { if (it < 5) BigDecimal("12.75") else BigDecimal("0.00") },
            total = BigDecimal("63.75"),
            fechaCalculo = LocalDate.of(2026, 3, 15),
            pendientes = 0,
            faltan = emptyList()
        )
    private val ordenanza =
        OrdenanzaArbitrio(
            anio = 2026,
            numero = "000-2025-MD (ficticia)",
            acuerdoRatificacion = "Acuerdo de Concejo 000 (ficticio)",
            municipalidadRatificante = "MUNICIPALIDAD PROVINCIAL DE PRUEBA",
            fechaRatificacion = LocalDate.of(2025, 12, 28)
        )

    private fun hoja() =
        hojaHla(
            Cabecera("MUNICIPALIDAD DE PRUEBA"),
            Contribuyente(id = "c1", codigo = "000001", nombreCompleto = "ANA"),
            ArbitriosContribuyente(2026, Persona("c1", "000001", "ANA"), listOf(matriz), BigDecimal("63.75"), LocalDate.of(2026, 3, 15)),
            ordenanza,
            (1..12).associateWith { LocalDate.of(2026, it, 28) },
            mapOf("p1" to (listOf("Z1") to listOf("CASA"))),
            LocalDateTime.of(2026, 4, 1, 10, 30)
        )

    @Test
    fun `only the months with a cuota, by servicio, with the backend's totals`() {
        val h = hoja()
        assertEquals(listOf("Limpieza pública", "Serenazgo"), h.servicios)
        val p = h.predios.single()
        assertEquals(listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo"), p.meses.map { it.mes })
        assertEquals(listOf("S/ 8.50", "S/ 4.25"), p.meses[0].montos)
        assertEquals("S/ 12.75", p.meses[0].total)
        assertEquals(listOf("S/ 42.50", "S/ 21.25"), p.totales)
        assertEquals("S/ 63.75", p.total)
        assertEquals("Z1", p.zona)
        assertEquals("CASA", p.uso)
        assertEquals("S/ 63.75", h.total)
        assertEquals("15/03/2026", h.fechaCalculo)
        assertEquals("01/04/2026 10:30", h.emitido)
    }

    @Test
    fun `each month's cuota with its due date`() {
        val cuotas = hoja().cuotas
        assertEquals(5, cuotas.size)
        assertEquals(FilaCuotaHla("Enero", "S/ 12.75", "28/01/2026"), cuotas.first())
        assertEquals("28/05/2026", cuotas.last().vencimiento)
    }

    @Test
    fun `the ordinance and its ratification, as the HLA cites them`() {
        assertEquals(
            "Ordenanza N.° 000-2025-MD (ficticia), ratificada por Acuerdo de Concejo 000 (ficticio) de la MUNICIPALIDAD PROVINCIAL DE PRUEBA del 28/12/2025",
            hoja().ordenanza
        )
    }

    @Test
    fun `a month's due date is the one in force on its day 1, and must be a date`() {
        fun fila(
            clave: String,
            texto: String,
            desde: String
        ) = ParametroTributario(id = clave, tipo = Llaves.ARBITRIO_VENCIMIENTO, clave = clave, vigenciaDesde = LocalDate.parse(desde), texto = texto)
        val parametros =
            listOf(fila("3", "2026-03-31", "2026-01-01"), fila("3", "2026-04-15", "2026-02-01"), fila("4", "fin de abril", "2026-01-01"))
        assertEquals(LocalDate.of(2026, 4, 15), vencimiento(parametros, 2026, 3))
        assertNull(vencimiento(parametros, 2026, 4))
        assertNull(vencimiento(parametros, 2026, 5))
    }
}
