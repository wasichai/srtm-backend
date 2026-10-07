package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import java.math.BigDecimal
import java.time.LocalDate

// TOPE_ARBITRIO (#87): what a predio's year may rise over what it paid the year before, the new cuotas lowered in
// proportion. every figure, percentage and code here is FICTITIOUS: no ordinance's tope is transcribed and verified
// yet. the case of the issue: 1 000.00 paid in 2025, 1 200.00 due in 2026, a tope of 8 % -> 1 080.00
class TopeTest {
    private val hoy = LocalDate.of(2026, 3, 15)
    private val observacion = "Se determina para la prueba"
    private val servicios = listOf(servicio("A", 1), servicio("B", 2), servicio("C", 3))
    private val usos =
        listOf(
            UsoPredio("010101", "RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"),
            UsoPredio("020501", "COMERCIAL", "TIENDAS", "TIENDA")
        )

    // 33.33 + 33.33 + 33.34 = 100.00 a month, 1 200.00 a year
    private val tasas = listOf(tasa("A:Z1:CASA", "33.33"), tasa("B:Z1:CASA", "33.33"), tasa("C:Z1:CASA", "33.34"))
    private val mapeos = listOf(param(Llaves.ARBITRIO_ZONA, "S-01", texto = "Z1"), param(Llaves.ARBITRIO_USO, "0101", texto = "CASA"))
    private val redondeo = param(Llaves.REDONDEO_ARBITRIO, "", texto = "2:ABAJO")
    private val tope = param(Llaves.TOPE_ARBITRIO, "0101", valor = "8", texto = "2025-12-31")
    private val predio = Predio(id = "predio-100", codigo = "01-01-0001", sectorCatastral = "S-01")
    private val dj = declaracion("dj-1", "01", presentada = LocalDate.of(2025, 3, 10))

    // what it paid in 2025: 1 000.00 in 12 cuotas of one servicio, the last one with the cents
    private val mil = pagado(List(11) { "83.33" } + "83.37")

    private fun contexto(parametros: List<ParametroTributario> = tasas + mapeos + redondeo + tope) =
        ContextoArbitrios(2026, ordenanza(), servicios, parametros, usos)

    private fun del(
        declaraciones: List<Declaracion> = listOf(dj),
        existentes: List<CuotaArbitrio> = emptyList(),
        anteriores: List<CuotaArbitrio> = mil
    ) = PredioArbitrios(predio, declaraciones, mapOf("c-200" to "00000200"), emptyList(), existentes, anteriores = anteriores)

    private fun determinar(
        c: ContextoArbitrios = contexto(),
        p: PredioArbitrios = del()
    ) = Arbitrios.determinar(c, p, observacion, hoy)

    private fun suma(cs: List<CuotaArbitrio>) = cs.fold(BigDecimal.ZERO) { s, c -> s + c.monto!! }

    private fun sumaSinTope(cs: List<CuotaArbitrio>) = cs.fold(BigDecimal.ZERO) { s, c -> s + (c.montoSinTope ?: c.monto)!! }

    @Test
    fun `1 000_00 paid, 1 200_00 due and a tope of 8 percent - it pays 1 080_00 exactly, rounded down, the cents on the last`() {
        val d = determinar()
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(36, d.cuotas.size)
        assertEquals(BigDecimal("1080.00"), suma(d.cuotas))
        assertEquals(BigDecimal("1200.00"), sumaSinTope(d.cuotas))
        assertTrue(d.cuotas.all { it.topeAplicado!!.compareTo(BigDecimal("8")) == 0 })
        // each one × 1 080 / 1 200, rounded down: 29.997 -> 29.99 and 30.006 -> 30.00; 1 079.76 in all
        val enero = d.cuotas.filter { it.periodo == 1 }
        assertEquals(listOf("29.99", "29.99", "30.00"), enero.map { it.monto!!.toPlainString() })
        assertEquals(listOf("33.33", "33.33", "33.34"), enero.map { it.montoSinTope!!.toPlainString() })
        // the 0.24 left goes to the last one: december's last servicio
        val ultima = d.cuotas.last()
        assertEquals(12, ultima.periodo)
        assertEquals("srv-C", ultima.servicio)
        assertEquals(BigDecimal("30.24"), ultima.monto)
        assertTrue(d.cuotas.dropLast(1).none { it.monto!! > BigDecimal("30.00") })
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
        assertTrue(d.cuotas.all { it.parametroAplicado!!.endsWith(";${Llaves.TOPE_ARBITRIO}:0101") })
        assertEquals("33.33 S/ por predio = 33.33, con tope de 8 %: 29.99", Calculo.formula(enero.first()))
    }

    @Test
    fun `without REDONDEO_ARBITRIO the tope rounds 2 ABAJO - same 1 080_00`() {
        val d = determinar(contexto(tasas + mapeos + tope))
        assertEquals(BigDecimal("1080.00"), suma(d.cuotas))
        assertEquals(BigDecimal("30.24"), d.cuotas.last().monto)
        assertNull(d.cuotas.first().redondeo) // the desglose's, untouched
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
    }

    @Test
    fun `without cuotas the year before there is no tope`() {
        val d = determinar(p = del(anteriores = emptyList()))
        assertEquals(BigDecimal("1200.00"), suma(d.cuotas))
        assertTrue(d.cuotas.all { it.montoSinTope == null && it.topeAplicado == null })
        assertTrue(d.cuotas.none { Llaves.TOPE_ARBITRIO in it.parametroAplicado!! })
        // nor with cuotas that add up to nothing
        val ceros = determinar(p = del(anteriores = pagado(List(12) { "0.00" })))
        assertEquals(BigDecimal("1200.00"), suma(ceros.cuotas))
    }

    @Test
    fun `a predio registered after the cut date has no tope - one registered on it, or a tope without a date, does`() {
        val despues = determinar(p = del(declaraciones = listOf(dj.copy(fechaPresentacion = LocalDate.of(2026, 1, 20)))))
        assertEquals(BigDecimal("1200.00"), suma(despues.cuotas))
        assertTrue(despues.cuotas.all { it.topeAplicado == null })

        val elMismoDia = determinar(p = del(declaraciones = listOf(dj.copy(fechaPresentacion = LocalDate.of(2025, 12, 31)))))
        assertEquals(BigDecimal("1080.00"), suma(elMismoDia.cuotas))

        val sinCorte = tope.copy(texto = null)
        val d = determinar(contexto(tasas + mapeos + redondeo + sinCorte), del(declaraciones = listOf(dj.copy(fechaPresentacion = LocalDate.of(2026, 1, 20)))))
        assertEquals(BigDecimal("1080.00"), suma(d.cuotas))
    }

    @Test
    fun `registered is the first DJ of any year - one of 2024 keeps the tope though its 2026 DJ was presented in 2026`() {
        val de2026 = listOf(dj.copy(fechaPresentacion = LocalDate.of(2026, 2, 1)))
        val d = determinar(p = del(declaraciones = de2026).copy(registrado = LocalDate.of(2024, 5, 10)))
        assertEquals(BigDecimal("1080.00"), suma(d.cuotas))
        assertTrue(d.cuotas.all { it.topeAplicado!!.compareTo(BigDecimal("8")) == 0 })
    }

    @Test
    fun `a predio first registered after the cut date has no tope, whatever its DJ of this year says`() {
        val d = determinar(p = del().copy(registrado = LocalDate.of(2026, 1, 20)))
        // this year's DJ (2025-03-10) is earlier: it counts too, so the predio was in the registry before the cut
        assertEquals(BigDecimal("1080.00"), suma(d.cuotas))

        val primeraDespues = del(declaraciones = listOf(dj.copy(fechaPresentacion = LocalDate.of(2026, 1, 20)))).copy(registrado = LocalDate.of(2026, 1, 20))
        val sinTope = determinar(p = primeraDespues)
        assertEquals(BigDecimal("1200.00"), suma(sinTope.cuotas))
        assertTrue(sinTope.cuotas.all { it.montoSinTope == null && it.topeAplicado == null })
    }

    // a migrated DJ has no fecha_presentacion: it counts from 1 January of its year. ArbitriosService reads the
    // earliest fecha_presentacion and the lowest anio among the DJs without one, of any year (Arbitrios.registrado)
    @Test
    fun `DJs of 2020 to 2025 without a date and the 2026 one presented on 1 february - registered in 2020, the tope applies`() {
        val registrado = Arbitrios.registrado(primeraPresentacion = LocalDate.of(2026, 2, 1), primerAnioSinFecha = 2020)
        assertEquals(LocalDate.of(2020, 1, 1), registrado)
        val de2026 = listOf(dj.copy(fechaPresentacion = LocalDate.of(2026, 2, 1)))
        val d = determinar(p = del(declaraciones = de2026).copy(registrado = registrado))
        assertEquals(BigDecimal("1080.00"), suma(d.cuotas))
        assertTrue(d.cuotas.all { it.topeAplicado!!.compareTo(BigDecimal("8")) == 0 })

        // the 2026 DJ alone: registered on 1 february, after the cut
        val soloLaDe2026 = Arbitrios.registrado(primeraPresentacion = LocalDate.of(2026, 2, 1), primerAnioSinFecha = null)
        assertEquals(BigDecimal("1200.00"), suma(determinar(p = del(declaraciones = de2026).copy(registrado = soloLaDe2026)).cuotas))
        // a dated DJ earlier than 1 January of the lowest undated year wins; nothing at all is no date
        assertEquals(LocalDate.of(2019, 6, 1), Arbitrios.registrado(LocalDate.of(2019, 6, 1), 2020))
        assertNull(Arbitrios.registrado(null, null))
    }

    @Test
    fun `this year's DJ without a date counts from 1 January of the year, like a migrated one`() {
        val sinFecha = listOf(dj.copy(fechaPresentacion = null))
        // 2026-01-01 is after the 2025-12-31 cut: no tope
        assertEquals(BigDecimal("1200.00"), suma(determinar(p = del(declaraciones = sinFecha)).cuotas))
        // an older DJ (registrado) keeps it
        assertEquals(BigDecimal("1080.00"), suma(determinar(p = del(declaraciones = sinFecha).copy(registrado = LocalDate.of(2023, 1, 1))).cuotas))
    }

    @Test
    fun `under the limit nothing changes`() {
        val d = determinar(p = del(anteriores = pagado(List(12) { "100.00" }))) // 1 200.00 x 1.08 = 1 296.00
        assertEquals(BigDecimal("1200.00"), suma(d.cuotas))
        assertTrue(d.cuotas.all { it.montoSinTope == null && it.topeAplicado == null })
    }

    @Test
    fun `without TOPE_ARBITRIO nothing changes - the same cuotas as without the year before`() {
        val sinTope = contexto(tasas + mapeos + redondeo)
        assertEquals(determinar(sinTope, del(anteriores = emptyList())), determinar(sinTope, del()))
        val cuotas = determinar(sinTope).cuotas
        assertEquals(BigDecimal("1200.00"), suma(cuotas))
        assertTrue(cuotas.all { it.montoSinTope == null && it.topeAplicado == null })
    }

    @Test
    fun `a partial determination - 6 months already written, only the new ones go down, to the limit`() {
        val primerSemestre = determinar(p = del(anteriores = emptyList())).cuotas.filter { it.periodo!! <= 6 }
        assertEquals(BigDecimal("600.00"), suma(primerSemestre))
        val d = determinar(p = del(existentes = primerSemestre))
        assertEquals(18, d.cuotas.size)
        assertTrue(d.cuotas.all { it.periodo!! > 6 })
        // 1 080.00 - 600.00 = 480.00 for the 600.00 still due: × 0.8, rounded down, 0.06 on the last
        assertEquals(BigDecimal("480.00"), suma(d.cuotas))
        assertEquals(listOf("26.66", "26.66", "26.67"), d.cuotas.filter { it.periodo == 7 }.map { it.monto!!.toPlainString() })
        assertEquals(BigDecimal("26.73"), d.cuotas.last().monto)
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
    }

    @Test
    fun `a partial determination never goes below 0 - what was written already passes the limit`() {
        val caras = determinar(p = del(anteriores = emptyList())).cuotas.filter { it.periodo!! <= 6 }.map { it.copy(monto = BigDecimal("200.00")) }
        val d = determinar(p = del(existentes = caras))
        assertEquals(18, d.cuotas.size)
        assertTrue(d.cuotas.all { it.monto!!.signum() == 0 })
        assertEquals(BigDecimal("600.00"), sumaSinTope(d.cuotas))
        assertTrue(d.cuotas.all { it.topeAplicado != null && invariantes(it).isEmpty() })
    }

    @Test
    fun `the group is the uso of the predio's lowest secuencia - the longest prefix, then star`() {
        val tienda =
            declaracion("dj-t", "01", presentada = LocalDate.of(2025, 3, 10))
                .copy(claseUso = "COMERCIAL", subClaseUso = "TIENDAS", uso = "TIENDA")
        val casa = dj.copy(id = "dj-c", secuenciaUso = "02")
        val parametros =
            tasas + mapeos + redondeo + tope +
                param(Llaves.ARBITRIO_USO, "02", texto = "TIENDA") +
                listOf("A", "B", "C").map { tasa("$it:Z1:TIENDA", "50.00") } +
                param(Llaves.TOPE_ARBITRIO, "*", valor = "18")
        // 150.00 + 100.00 a month, 3 000.00 a year; 2 000.00 paid × 1.18 = 2 360.00. the tienda (001) decides: *
        val d = determinar(contexto(parametros), del(declaraciones = listOf(tienda, casa), anteriores = pagado(List(12) { "166.66" } + "0.08")))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(72, d.cuotas.size)
        assertEquals(BigDecimal("2360.00"), suma(d.cuotas))
        assertTrue(d.cuotas.all { it.topeAplicado!!.compareTo(BigDecimal("18")) == 0 })
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() && it.parametroAplicado!!.endsWith(";${Llaves.TOPE_ARBITRIO}:*") })

        // without a row for its group (no *), no tope at all
        val sinGrupo = determinar(contexto(parametros.filterNot { it.clave == "*" }), del(declaraciones = listOf(tienda, casa), anteriores = mil))
        assertTrue(sinGrupo.cuotas.all { it.topeAplicado == null })
    }

    @Test
    fun `the cuotas of the year before that count - not annulled, the latest version of each`() {
        val v1 = cuotaDe2025(1, "10.00").copy(id = "q1")
        val v2 = cuotaDe2025(1, "12.00", version = 2).copy(id = "q2")
        val otra = cuotaDe2025(2, "10.00").copy(id = "q3")
        assertEquals(listOf(v2, otra), Arbitrios.vigentes(listOf(v1, v2, otra), setOf("q1")))
        assertEquals(listOf(v2, otra), Arbitrios.vigentes(listOf(v1, v2, otra), emptySet()))
        assertEquals(listOf(otra), Arbitrios.vigentes(listOf(v1, v2, otra), setOf("q1", "q2")))
    }

    private fun pagado(montos: List<String>) = montos.mapIndexed { i, m -> cuotaDe2025(i % 12 + 1, m, servicio = if (i < 12) "srv-A" else "srv-B") }

    private fun cuotaDe2025(
        periodo: Int,
        monto: String,
        version: Int = 1,
        servicio: String = "srv-A"
    ) = CuotaArbitrio(
        predio = "predio-100",
        contribuyente = "c-200",
        servicio = servicio,
        parametro = "p-2025",
        anio = 2025,
        periodo = periodo,
        monto = BigDecimal(monto),
        parametroAplicado = "TASA_ARBITRIO:A:Z1:CASA",
        secuenciaUso = "001",
        fechaCalculo = LocalDate.of(2025, 1, 10),
        observacion = observacion,
        clave = claveDeCuota("predio-100", servicio, 2025, periodo, "001", version)
    )

    private fun ordenanza() =
        OrdenanzaArbitrio(
            id = "ord-2026",
            anio = 2026,
            numero = "000-2025-MD (ficticia)",
            acuerdoRatificacion = "Acuerdo de Concejo 000 (ficticio)",
            fechaRatificacion = LocalDate.of(2025, 12, 28)
        )

    private fun servicio(
        codigo: String,
        orden: Int
    ) = ServicioArbitrio(id = "srv-$codigo", codigo = codigo, nombre = codigo, orden = orden, vigenciaDesde = LocalDate.of(2025, 1, 1))

    private fun param(
        tipo: String,
        clave: String,
        valor: String? = null,
        texto: String? = null
    ) = ParametroTributario(
        id = "p-$tipo-$clave",
        tipo = tipo,
        clave = clave,
        vigenciaDesde = LocalDate.of(2026, 1, 1),
        valorNumerico = valor?.let(::BigDecimal),
        texto = texto,
        norma = "Ordenanza ficticia de prueba"
    )

    private fun tasa(
        clave: String,
        valor: String
    ) = param(Llaves.TASA_ARBITRIO, clave, valor = valor)

    private fun declaracion(
        id: String,
        secuencia: String,
        presentada: LocalDate
    ) = Declaracion(
        id = id,
        contribuyente = "c-200",
        predio = "predio-100",
        anio = 2026,
        secuenciaUso = secuencia,
        porcentajeCondominio = BigDecimal("100"),
        claseUso = "RESIDENCIAL",
        subClaseUso = "UNIFAMILIAR",
        uso = "CASA HABITACIÓN",
        fechaPresentacion = presentada
    )
}
