package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertEquals(emptyList<Falta>(), m.faltanDetalle)
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
        val enero = m.filas[0].meses[0]!!
        assertEquals("TASA_ARBITRIO:LIMPIEZA:Z1:CASA", enero.parametroAplicado)
        // the cell's desglose: one entry, base PREDIO (no BASE_ARBITRIO row in this fixture), no redondeo -> the
        // formula does not repeat the monto (Calculo.formula)
        assertEquals(1, enero.desglose.size)
        assertEquals("8.50 S/ por predio", enero.desglose[0].formula)
    }

    // #165: a month whose every cuota was paid in caja says so
    @Test
    fun `a cell whose cuotas were paid in caja is pagada`() {
        val todas = guardadas(hoy, Arbitrios.determinar(contexto, datos(emptyList()), "Determinación de prueba", hoy).cuotas)
        val enero = todas.first { it.periodo == 1 }
        val m = Consultas.matriz(contexto, datos(todas), personas, hoy, pagadas = setOf(enero.id!!))
        val fila = m.filas.first { it.servicio.id == enero.servicio }
        assertTrue(fila.meses[0]!!.pagada)
        assertEquals(List(11) { false }, fila.meses.drop(1).map { it!!.pagada })
    }

    @Test
    fun `an annulled cuota counts for nothing, and is pending again`() {
        val todas = guardadas(hoy, Arbitrios.determinar(contexto, datos(emptyList()), "Determinación de prueba", hoy).cuotas)
        val anulada = todas.first()
        val m = Consultas.matriz(contexto, datos(todas).copy(anuladas = setOf(anulada.id!!)), personas, hoy)
        assertEquals(BigDecimal("144.50"), m.total) // 153.00 less one 8.50
        assertNull(m.filas[0].meses[0])
        assertEquals(1, m.pendientes)
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

    // un PU por uso (#84): a predio of two usos has two cuotas of the same servicio and month, one per secuencia.
    // the cell sums them, with both keys
    @Test
    fun `two secuencias of the same servicio and month sum into one cell`() {
        val soloLimpieza =
            contexto.copy(
                servicios = listOf(servicios[1]), // LIMPIEZA alone, to keep the fixture small
                parametros =
                    parametros + param("ARBITRIO_USO", "02", texto = "COMERCIO") + param("TASA_ARBITRIO", "LIMPIEZA:Z1:COMERCIO", valor = "4.25") + puPorUso,
                usos = contexto.usos + UsoPredio("020101", "COMERCIAL", "TIENDAS", "TIENDA")
            )
        val casa = vendedor.copy(id = "dj-10", estado = null, fechaAnulacion = null)
        val tienda = casa.copy(id = "dj-11", secuenciaUso = "02", claseUso = "COMERCIAL", subClaseUso = "TIENDAS", uso = "TIENDA")
        val datosDosUsos = PredioArbitrios(predio, listOf(casa, tienda), mapOf("c-1" to "001"), emptyList(), emptyList())
        val determinacion = Arbitrios.determinar(soloLimpieza, datosDosUsos, "Determinación de prueba", hoy)
        assertEquals(emptyList<String>(), determinacion.faltan)
        val existentes = guardadas(hoy, determinacion.cuotas)
        val m = Consultas.matriz(soloLimpieza, datosDosUsos.copy(existentes = existentes), personas, hoy)
        val enero = m.filas.single().meses[0]!!
        assertEquals(BigDecimal("12.75"), enero.monto) // 8.50 (CASA, secuencia 001) + 4.25 (COMERCIO, secuencia 002)
        assertEquals("TASA_ARBITRIO:LIMPIEZA:Z1:CASA; TASA_ARBITRIO:LIMPIEZA:Z1:COMERCIO", enero.parametroAplicado)
        assertEquals(existentes.first { it.periodo == 1 && it.secuenciaUso == "001" }.id, enero.id)
        assertEquals(BigDecimal("153.00"), m.filas.single().total) // 12 months of 12.75
        // the cell's desglose keeps the two secuencias apart, in order, each with its own formula
        assertEquals(listOf("001", "002"), enero.desglose.map { it.secuenciaUso })
        assertEquals(listOf("8.50 S/ por predio", "4.25 S/ por predio"), enero.desglose.map { it.formula })
    }

    // a predio of two usos whose 2026 was determined before the year of one PU per uso (#80): its cuotas are the
    // lowest secuencia's - written before #84 (no secuencia_uso, a clave of 5 parts) or after it (001). nothing is
    // pending in its matriz, and nothing keeps its titular's HLA (faltanDeLaHla: no 422). from the year of one PU per
    // uso, the 002's would be
    @Test
    fun `a predio of two usos determined with one PU per predio has nothing pending, and its HLA nothing missing`() {
        val dosUsos =
            contexto.copy(
                parametros =
                    parametros + param("ARBITRIO_USO", "02", texto = "COMERCIO") + param("TASA_ARBITRIO", "LIMPIEZA:Z1:COMERCIO", valor = "4.25") +
                        param("TASA_ARBITRIO", "SERENAZGO:Z1:COMERCIO", valor = "2.00"),
                usos = contexto.usos + UsoPredio("020101", "COMERCIAL", "TIENDAS", "TIENDA")
            )
        val casa = vendedor.copy(id = "dj-10", estado = null, fechaAnulacion = null)
        val tienda = casa.copy(id = "dj-11", secuenciaUso = "02", claseUso = "COMERCIAL", subClaseUso = "TIENDAS", uso = "TIENDA")
        val sinCuotas = PredioArbitrios(predio, listOf(casa, tienda), mapOf("c-1" to "001"), emptyList(), emptyList())
        val unPu = Arbitrios.determinar(dosUsos, sinCuotas, "Determinación de prueba", hoy).cuotas
        assertEquals(24, unPu.size) // LIMPIEZA and SERENAZGO x 12 months, the casa's
        val antesDe84 = unPu.map { it.copy(secuenciaUso = null, clave = "p|${it.servicio}|2026|${it.periodo}|1") }

        for (existentes in listOf(guardadas(hoy, unPu), guardadas(hoy, antesDe84))) {
            val datos = sinCuotas.copy(existentes = existentes)
            val m = Consultas.matriz(dosUsos, datos, personas, hoy)
            assertEquals(0, m.pendientes)
            assertEquals(emptyList<String>(), m.faltan)
            assertEquals(BigDecimal("153.00"), m.total) // 12 x (8.50 + 4.25): the casa's alone
            val delTitular = Consultas.matriz(dosUsos, datos, personas, hoy, soloDe = "c-1")
            val hla = ArbitriosContribuyente(2026, Consultas.persona("c-1", personas["c-1"]), listOf(delTitular), delTitular.total, delTitular.fechaCalculo)
            assertEquals(emptyList<String>(), faltanDeLaHla(dosUsos, hla))

            // from the year of one PU per uso the tienda's are pending, and the HLA says so
            val porUso = dosUsos.copy(parametros = dosUsos.parametros + puPorUso)
            assertEquals(24, Consultas.matriz(porUso, datos, personas, hoy).pendientes)
            val pendiente = Consultas.matriz(porUso, datos, personas, hoy, soloDe = "c-1")
            assertEquals(
                listOf("Predio 01-01-0001: 24 cuotas de arbitrios por determinar"),
                faltanDeLaHla(porUso, hla.copy(predios = listOf(pendiente)))
            )
        }
    }

    @Test
    fun `what keeps the rest from being determined is said`() {
        val sinTasa = contexto.copy(parametros = parametros.dropLast(1))
        val m = Consultas.matriz(sinTasa, datos(emptyList()), personas, hoy)
        assertEquals(0, m.pendientes)
        assertEquals(listOf("TASA_ARBITRIO SERENAZGO:Z1:CASA 2026"), m.faltan)
        assertEquals(m.faltan, m.faltanDetalle.map { it.mensaje }) // faltan_detalle: the same, with predio and declaración
        assertEquals(predio.id, m.faltanDetalle[0].predio)
    }

    // the year from which a predio pays one PU per uso (#80)
    private val puPorUso = param("ARBITRIO_PU_POR_USO", "", valor = "1")

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
