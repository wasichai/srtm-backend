package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import srtm.rentas.ANULADA
import srtm.rentas.Contribuyente
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import java.math.BigDecimal
import java.time.LocalDate

// what the portal shows of a predio's year: every total computed here. FICTITIOUS figures, as in ArbitriosTest
class ConsultasTest {
    private val hoy = LocalDate.of(2026, 3, 15)
    private val servicios =
        listOf(
            ServicioArbitrio(id = "s-2", codigo = "SERENAZGO", orden = 2, vigenciaDesde = LocalDate.of(2026, 1, 1)),
            ServicioArbitrio(id = "s-1", codigo = "LIMPIEZA", orden = 1, vigenciaDesde = LocalDate.of(2026, 1, 1)),
            ServicioArbitrio(id = "s-viejo", codigo = "VIEJO", orden = 3, vigenciaDesde = LocalDate.of(2020, 1, 1), vigenciaHasta = LocalDate.of(2020, 12, 31))
        )
    private val parametros =
        listOf(
            param("ARBITRIO_ZONA", "S-01", texto = "Z1"),
            param("ARBITRIO_USO", "0101", texto = "CASA"),
            param("TASA_ARBITRIO", "LIMPIEZA:Z1:CASA", valor = "8.50"),
            param("TASA_ARBITRIO", "SERENAZGO:Z1:CASA", valor = "4.25")
        )
    private val contexto =
        ContextoArbitrios(
            2026,
            OrdenanzaArbitrio(id = "o", anio = 2026, acuerdoRatificacion = "AC (ficticio)", fechaRatificacion = LocalDate.of(2025, 12, 28)),
            servicios,
            parametros,
            listOf(UsoPredio("010101", "RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"))
        )
    private val predio = Predio(id = "p", codigo = "01-01-0001", direccion = "JR. LIMA 123", sectorCatastral = "S-01")
    private val vendedor =
        Declaracion(
            id = "dj-1",
            contribuyente = "c-1",
            anio = 2026,
            secuenciaUso = "01",
            porcentajeCondominio = BigDecimal(100),
            claseUso = "RESIDENCIAL",
            subClaseUso = "UNIFAMILIAR",
            uso = "CASA HABITACIÓN",
            estado = ANULADA,
            fechaAnulacion = LocalDate.of(2026, 5, 20)
        )
    private val comprador =
        vendedor.copy(
            id = "dj-2",
            contribuyente = "c-2",
            estado = null,
            fechaAnulacion = null,
            fechaAdquisicion = LocalDate.of(2026, 5, 20)
        )
    private val personas =
        mapOf(
            "c-1" to Contribuyente(id = "c-1", codigo = "001", nombreCompleto = "ANA"),
            "c-2" to Contribuyente(id = "c-2", codigo = "002", nombreCompleto = "BETO")
        )

    private fun datos(existentes: List<CuotaArbitrio>) =
        PredioArbitrios(predio, listOf(vendedor, comprador), mapOf("c-1" to "001", "c-2" to "002"), emptyList(), existentes)

    // what a determination of today writes, as if it were stored, with a date of calculation each
    private fun guardadas(
        fecha: LocalDate,
        cuotas: List<CuotaArbitrio>
    ) = cuotas.mapIndexed { i, c -> c.copy(id = "cuota-$i", fechaCalculo = fecha) }

    @Test
    fun `nothing determined yet - empty rows, zero totals, and how many are pending`() {
        val m = Consultas.matriz(contexto, datos(emptyList()), personas, hoy)
        assertEquals(listOf("LIMPIEZA", "SERENAZGO"), m.filas.map { it.servicio.codigo }) // the 2020 one is not of the year
        assertEquals(List(12) { null }, m.filas[0].meses)
        assertEquals(BigDecimal("0.00"), m.total)
        assertNull(m.fechaCalculo)
        assertEquals(24, m.pendientes)
        assertEquals(emptyList<String>(), m.faltan)
    }

    @Test
    fun `the totals by servicio, by month and of the year, and the date of the latest cuota`() {
        val todas = Arbitrios.determinar(contexto, datos(emptyList()), "Determinación de prueba", hoy).cuotas
        val existentes =
            guardadas(LocalDate.of(2026, 2, 1), todas.filter { it.periodo!! <= 6 }) + guardadas(LocalDate.of(2026, 3, 1), todas.filter { it.periodo == 7 })
        val m = Consultas.matriz(contexto, datos(existentes), personas, hoy)
        assertEquals(listOf("59.50", "29.75"), m.filas.map { it.total.toPlainString() }) // 7 months of 8.50 and of 4.25
        assertEquals(List(7) { "12.75" } + List(5) { "0.00" }, m.totalesPorMes.map { it.toPlainString() })
        assertEquals(BigDecimal("89.25"), m.total)
        assertEquals(LocalDate.of(2026, 3, 1), m.fechaCalculo)
        assertEquals(10, m.pendientes)
        assertEquals("TASA_ARBITRIO:LIMPIEZA:Z1:CASA", m.filas[0].meses[0]!!.parametroAplicado)
    }

    @Test
    fun `each month's titular, by the rule - the sale of may`() {
        val m = Consultas.matriz(contexto, datos(emptyList()), personas, hoy)
        assertEquals(List(5) { "ANA" } + List(7) { "BETO" }, m.titulares.map { it.titular?.nombre })
        assertEquals(
            "002",
            m.titulares
                .last()
                .titular
                ?.codigo
        )
    }

    @Test
    fun `a contribuyente's view counts only the cuotas charged to it`() {
        val todas = guardadas(hoy, Arbitrios.determinar(contexto, datos(emptyList()), "Determinación de prueba", hoy).cuotas)
        val delVendedor = Consultas.matriz(contexto, datos(todas), personas, hoy, soloDe = "c-1")
        assertEquals(BigDecimal("63.75"), delVendedor.total) // january to may, 12.75 a month
        assertEquals(5, delVendedor.filas[0].meses.count { it != null })
    }

    @Test
    fun `what keeps the rest from being determined is said`() {
        val sinTasa = contexto.copy(parametros = parametros.dropLast(1))
        val m = Consultas.matriz(sinTasa, datos(emptyList()), personas, hoy)
        assertEquals(0, m.pendientes)
        assertEquals(listOf("TASA_ARBITRIO SERENAZGO:Z1:CASA 2026"), m.faltan)
    }

    private fun param(
        tipo: String,
        clave: String,
        valor: String? = null,
        texto: String? = null
    ) = ParametroTributario(
        id = "p-$clave",
        tipo = tipo,
        clave = clave,
        vigenciaDesde = LocalDate.of(2026, 1, 1),
        valorNumerico = valor?.let(::BigDecimal),
        texto = texto
    )
}
