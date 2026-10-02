package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import srtm.rentas.ANULADA
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import java.math.BigDecimal
import java.time.LocalDate

// rentas' DeterminarArbitriosTest, case by case, plus what srtm adds: the ordinance, the servicios and the mappings of
// zona and uso. every figure, code and name of the ordinance here is FICTITIOUS: there is no verified transcription
// of Perené's yet (D-02b)
class ArbitriosTest {
    private val hoy = LocalDate.of(2026, 3, 15)
    private val observacion = "Se determina para la prueba"
    private val servicios = listOf(servicio("LIMPIEZA", 1), servicio("PARQUES", 2), servicio("SERENAZGO", 3))
    private val usos =
        listOf(
            UsoPredio("010101", "RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"),
            UsoPredio("010201", "RESIDENCIAL", "MULTIFAMILIAR", "EDIFICIO"),
            UsoPredio("040101", "RECREACIONAL", "DEPORTIVO", "PARQUE METROPOLITANO")
        )
    private val tasas = servicios.map { tasa("${it.codigo}:Z1:CASA", "8.50") }
    private val mapeos = listOf(param(Llaves.ARBITRIO_ZONA, "S-01", texto = "Z1"), param(Llaves.ARBITRIO_USO, "0101", texto = "CASA"))
    private val predio = Predio(id = "predio-100", codigo = "01-01-0001", sectorCatastral = "S-01")
    private val dj = declaracion("dj-1", "c-200")

    private fun contexto(
        anio: Int = 2026,
        ordenanza: OrdenanzaArbitrio? = ordenanza(anio),
        servicios: List<ServicioArbitrio> = this.servicios,
        parametros: List<ParametroTributario> = tasas + mapeos
    ) = ContextoArbitrios(anio, ordenanza, servicios, parametros, usos)

    private fun del(
        declaraciones: List<Declaracion> = listOf(dj),
        predio: Predio = this.predio,
        inafectaciones: List<InafectacionArbitrio> = emptyList(),
        existentes: List<CuotaArbitrio> = emptyList()
    ) = PredioArbitrios(predio, declaraciones, mapOf("c-200" to "00000200", "c-201" to "00000201"), inafectaciones, existentes)

    private fun determinar(
        c: ContextoArbitrios = contexto(),
        p: PredioArbitrios = del()
    ) = Arbitrios.determinar(c, p, observacion, hoy)

    @Test
    fun `the 12 monthly cuotas of each servicio, at its tasa as is`() {
        val d = determinar()
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(36, d.cuotas.size) // 3 servicios x 12 months
        assertTrue(d.cuotas.all { it.monto!!.compareTo(BigDecimal("8.50")) == 0 })
        val enero = d.cuotas.first()
        assertEquals("srv-LIMPIEZA", enero.servicio)
        assertEquals(1, enero.periodo)
        assertEquals("p-TASA_ARBITRIO-LIMPIEZA:Z1:CASA", enero.parametro)
        assertEquals("TASA_ARBITRIO:LIMPIEZA:Z1:CASA", enero.parametroAplicado)
        assertEquals("Z1", enero.zona)
        assertEquals("CASA", enero.usoArbitrio)
        assertEquals("c-200", enero.contribuyente)
        assertEquals(hoy, enero.fechaCalculo)
        assertEquals(observacion, enero.observacion)
        assertEquals("predio-100|srv-LIMPIEZA|2026|1|1", enero.clave)
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
    }

    @Test
    fun `running it again adds nothing`() {
        val primera = determinar().cuotas
        val segunda = determinar(p = del(existentes = primera))
        assertEquals(Determinacion(emptyList(), emptyList()), segunda)
    }

    @Test
    fun `an annulled cuota is determined again, with the next version of its clave`() {
        val todas = determinar().cuotas.mapIndexed { i, c -> c.copy(id = "q$i") }
        val anulada = todas.single { it.servicio == "srv-PARQUES" && it.periodo == 7 }
        val otra = determinar(p = del(existentes = todas).copy(anuladas = setOf(anulada.id!!)))
        val nueva = otra.cuotas.single()
        assertEquals("predio-100|srv-PARQUES|2026|7|2", nueva.clave)
        assertEquals(7, nueva.periodo)

        // annulled again: the third version
        val segunda = nueva.copy(id = "q-v2")
        val tercera = determinar(p = del(existentes = todas + segunda).copy(anuladas = setOf(anulada.id!!, "q-v2"))).cuotas.single()
        assertEquals("predio-100|srv-PARQUES|2026|7|3", tercera.clave)
        assertTrue(invariantes(tercera).isEmpty())
    }

    @Test
    fun `only what is missing is determined`() {
        val todas = determinar().cuotas
        val falta = todas.single { it.servicio == "srv-PARQUES" && it.periodo == 7 }
        assertEquals(listOf(falta), determinar(p = del(existentes = todas - falta)).cuotas)
    }

    @Test
    fun `a predio without the cleaning servicio does not get that arbitrio, and does get the other two`() {
        val d = determinar(p = del(inafectaciones = listOf(inafectacion("srv-LIMPIEZA", LocalDate.of(2026, 1, 1), null))))
        assertEquals(24, d.cuotas.size) // parques and serenazgo
        assertTrue(d.cuotas.none { it.servicio == "srv-LIMPIEZA" })
    }

    @Test
    fun `without a uso or a sector it cannot be determined`() {
        val sinSector = determinar(p = del(predio = predio.copy(sectorCatastral = " ")))
        assertEquals(listOf("Sector catastral del predio 01-01-0001"), sinSector.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), sinSector.cuotas)

        val sinUso = determinar(p = del(declaraciones = listOf(dj.copy(claseUso = null, subClaseUso = null, uso = null))))
        assertEquals(listOf("Uso del catálogo en la declaración 2026 del predio 01-01-0001"), sinUso.faltan)
    }

    @Test
    fun `without a tasa it fails naming it, and determines nothing`() {
        val d = determinar(contexto(parametros = tasas.drop(1) + mapeos))
        assertEquals(listOf("TASA_ARBITRIO LIMPIEZA:Z1:CASA 2026"), d.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `rentas#443 - 2027's determination does not see the inafectación that ends on 31-12-2026`() {
        val hasta2026 = inafectacion("srv-LIMPIEZA", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        val d =
            Arbitrios.determinar(
                contexto(anio = 2027),
                del(declaraciones = listOf(dj.copy(anio = 2027)), inafectaciones = listOf(hasta2026)),
                observacion,
                LocalDate.of(2026, 12, 20)
            )
        assertEquals(12, d.cuotas.count { it.servicio == "srv-LIMPIEZA" })
        assertEquals(36, d.cuotas.size)
    }

    @Test
    fun `rentas#443 - a predio acquired on 15 july owes from august, not from january`() {
        val d = determinar(p = del(declaraciones = listOf(dj.copy(fechaAdquisicion = LocalDate.of(2026, 7, 15)))))
        assertTrue(d.cuotas.all { it.periodo!! >= 8 })
        assertEquals(15, d.cuotas.size) // august to december, three servicios
    }

    @Test
    fun `rentas#443 - the titular is each month's - the sale of may splits the year`() {
        val venta = LocalDate.of(2026, 5, 20)
        val vendedor = dj.copy(estado = ANULADA, fechaAnulacion = venta)
        val comprador = declaracion("dj-2", "c-201").copy(fechaAdquisicion = venta)
        val d = determinar(p = del(declaraciones = listOf(vendedor, comprador)))
        assertEquals(
            (1..5).toSet(),
            d.cuotas
                .filter { it.contribuyente == "c-200" }
                .map { it.periodo }
                .toSet()
        )
        assertEquals(
            (6..12).toSet(),
            d.cuotas
                .filter { it.contribuyente == "c-201" }
                .map { it.periodo }
                .toSet()
        )
    }

    @Test
    fun `the largest share is charged whole`() {
        val d =
            determinar(
                p =
                    del(
                        declaraciones =
                            listOf(
                                dj.copy(porcentajeCondominio = BigDecimal("30")),
                                declaracion("dj-2", "c-201").copy(porcentajeCondominio = BigDecimal("70"))
                            )
                    )
            )
        assertEquals(setOf("c-201"), d.cuotas.map { it.contribuyente }.toSet())
        assertTrue(d.cuotas.all { it.monto!!.compareTo(BigDecimal("8.50")) == 0 })
    }

    @Test
    fun `a predio without a titular in any month cannot be determined`() {
        val d = determinar(p = del(declaraciones = emptyList()))
        assertEquals(listOf("Titular del predio 01-01-0001 en 2026: ninguna declaración jurada lo cubre"), d.faltan)
        val anulada = determinar(p = del(declaraciones = listOf(dj.copy(estado = ANULADA, fechaAnulacion = LocalDate.of(2025, 12, 31)))))
        assertEquals(1, anulada.faltan.size)
    }

    @Test
    fun `without its ratified ordinance the year cannot be determined (D-02b)`() {
        assertEquals(listOf("Ordenanza de arbitrios 2026"), determinar(contexto(ordenanza = null)).faltan)
        assertEquals(listOf("Ordenanza de arbitrios 2026"), determinar(contexto(ordenanza = ordenanza(2025))).faltan)
        val sinRatificar = ordenanza(2026).copy(fechaRatificacion = null)
        assertEquals(listOf("Ratificación de la ordenanza de arbitrios 2026 (acuerdo y fecha)"), determinar(contexto(ordenanza = sinRatificar)).faltan)
        assertEquals(listOf("Servicios de arbitrio vigentes en 2026"), determinar(contexto(servicios = emptyList())).faltan)
    }

    @Test
    fun `a servicio that starts in july is charged from july`() {
        val desdeJulio = servicios.map { if (it.codigo == "SERENAZGO") it.copy(vigenciaDesde = LocalDate.of(2026, 7, 1)) else it }
        val d = determinar(contexto(servicios = desdeJulio))
        assertEquals((7..12).toList(), d.cuotas.filter { it.servicio == "srv-SERENAZGO" }.map { it.periodo })
        assertEquals(30, d.cuotas.size)
    }

    @Test
    fun `the tasa is the one in force each month`() {
        val enero = tasa("LIMPIEZA:Z1:CASA", "8.50").copy(vigenciaHasta = LocalDate.of(2026, 6, 30))
        val julio = tasa("LIMPIEZA:Z1:CASA", "9.00", desde = LocalDate.of(2026, 7, 1)).copy(id = "p-julio")
        val d = determinar(contexto(parametros = listOf(enero, julio) + tasas.drop(1) + mapeos))
        val limpieza = d.cuotas.filter { it.servicio == "srv-LIMPIEZA" }
        assertEquals(List(6) { "8.50" } + List(6) { "9.00" }, limpieza.map { it.monto!!.toPlainString() })
        assertEquals("p-julio", limpieza.last().parametro)
    }

    @Test
    fun `the most specific uso mapping wins, and a declaration of only a clase reads its clase's`() {
        val mapeosPorClase = mapeos + param(Llaves.ARBITRIO_USO, "01", texto = "VIVIENDA")
        val conClase = servicios.map { tasa("${it.codigo}:Z1:VIVIENDA", "5.00") }
        val c = contexto(parametros = tasas + conClase + mapeosPorClase)
        assertEquals(setOf("CASA"), determinar(c).cuotas.map { it.usoArbitrio }.toSet())

        val edificio = dj.copy(subClaseUso = "MULTIFAMILIAR", uso = "EDIFICIO") // 010201: only 01 maps it
        assertEquals(setOf("VIVIENDA"), determinar(c, del(declaraciones = listOf(edificio))).cuotas.map { it.usoArbitrio }.toSet())

        val soloClase = dj.copy(subClaseUso = null, uso = null) // 01
        assertEquals(setOf("VIVIENDA"), determinar(c, del(declaraciones = listOf(soloClase))).cuotas.map { it.usoArbitrio }.toSet())
    }

    @Test
    fun `a sector or a uso without its mapping is named`() {
        val sinZona = determinar(contexto(parametros = tasas + mapeos.drop(1)))
        assertEquals(listOf("ARBITRIO_ZONA S-01 2026"), sinZona.faltan)

        val parque = dj.copy(claseUso = "RECREACIONAL", subClaseUso = "DEPORTIVO", uso = "PARQUE METROPOLITANO")
        val sinUso = determinar(p = del(declaraciones = listOf(parque)))
        assertEquals(listOf("ARBITRIO_USO 040101 2026"), sinUso.faltan)
    }

    @Test
    fun `a negative tasa is not a cuota`() {
        val negativa = listOf(tasa("LIMPIEZA:Z1:CASA", "-1")) + tasas.drop(1)
        val d = determinar(contexto(parametros = negativa + mapeos))
        assertEquals(listOf("TASA_ARBITRIO LIMPIEZA:Z1:CASA 2026: monto no es negativo"), d.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    private fun ordenanza(anio: Int) =
        OrdenanzaArbitrio(
            id = "ord-$anio",
            anio = anio,
            numero = "000-${anio - 1}-MD (ficticia)",
            acuerdoRatificacion = "Acuerdo de Concejo 000 (ficticio)",
            fechaRatificacion = LocalDate.of(anio - 1, 12, 28)
        )

    private fun servicio(
        codigo: String,
        orden: Int
    ) = ServicioArbitrio(id = "srv-$codigo", codigo = codigo, nombre = codigo, orden = orden, vigenciaDesde = LocalDate.of(2026, 1, 1))

    private fun param(
        tipo: String,
        clave: String,
        valor: String? = null,
        texto: String? = null,
        desde: LocalDate = LocalDate.of(2026, 1, 1)
    ) = ParametroTributario(
        id = "p-$tipo-$clave",
        tipo = tipo,
        clave = clave,
        vigenciaDesde = desde,
        valorNumerico = valor?.let(::BigDecimal),
        texto = texto,
        norma = "Ordenanza ficticia de prueba"
    )

    private fun tasa(
        clave: String,
        valor: String,
        desde: LocalDate = LocalDate.of(2026, 1, 1)
    ) = param(Llaves.TASA_ARBITRIO, clave, valor = valor, desde = desde)

    private fun declaracion(
        id: String,
        contribuyente: String
    ) = Declaracion(
        id = id,
        contribuyente = contribuyente,
        predio = "predio-100",
        anio = 2026,
        secuenciaUso = "01",
        porcentajeCondominio = BigDecimal("100"),
        claseUso = "RESIDENCIAL",
        subClaseUso = "UNIFAMILIAR",
        uso = "CASA HABITACIÓN"
    )

    private fun inafectacion(
        servicio: String,
        desde: LocalDate,
        hasta: LocalDate?
    ) = InafectacionArbitrio(
        id = "inaf-1",
        predio = "predio-100",
        servicio = servicio,
        vigenciaDesde = desde,
        vigenciaHasta = hasta,
        motivo = "SIN SERVICIO",
        observacion = "Informe de prueba"
    )
}
