package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import srtm.impuesto.Parametros
import srtm.rentas.ANULADA
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import tools.jackson.databind.json.JsonMapper
import java.io.File
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
            UsoPredio("040101", "RECREACIONAL", "DEPORTIVO", "PARQUE METROPOLITANO"),
            UsoPredio("020501", "COMERCIAL", "TIENDAS", "TIENDA"),
            // matches the ficticio ARBITRIO_USO 0103->15 of data/arbitrios/ficticio-parametros.csv (#86), the
            // "otro uso" that has no zona of its own in RECOLECCION_RESIDUOS (the empty-zona TASA_ARBITRIO)
            UsoPredio("010301", "OTRO", "OTRO", "OTRO USO FICTICIO 2")
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
        assertEquals("predio-100|srv-LIMPIEZA|2026|1|001|1", enero.clave)
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
    }

    @Test
    fun `without the new parameters a predio of one uso has today's cuotas, its clave with the secuencia`() {
        val enero = determinar().cuotas.first()
        assertEquals(BigDecimal("8.50"), enero.monto) // the tasa as is: same value, same scale
        assertEquals("TASA_ARBITRIO:LIMPIEZA:Z1:CASA", enero.parametroAplicado)
        assertEquals("001", enero.secuenciaUso) // "01" in the declaration, as the padrón numbers it
        assertEquals("predio-100|srv-LIMPIEZA|2026|1|001|1", enero.clave)
        // its desglose: one predio at its tasa, no habitantes, no rounding
        assertEquals("PREDIO", enero.base)
        assertEquals(BigDecimal.ONE, enero.cantidadBase)
        assertEquals(BigDecimal("8.50"), enero.tasaUnitaria)
        assertEquals(listOf(null, null, null, null), listOf(enero.habitantes, enero.habitantesPresuntos, enero.promedioHabitantes, enero.variacionHabitante))
        assertEquals(listOf(null, null, null), listOf(enero.influencia, enero.afluencia, enero.redondeo))
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
        assertEquals("predio-100|srv-PARQUES|2026|7|001|2", nueva.clave)
        assertEquals(7, nueva.periodo)

        // annulled again: the third version
        val segunda = nueva.copy(id = "q-v2")
        val tercera = determinar(p = del(existentes = todas + segunda).copy(anuladas = setOf(anulada.id!!, "q-v2"))).cuotas.single()
        assertEquals("predio-100|srv-PARQUES|2026|7|001|3", tercera.clave)
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
    fun `sector and uso código both missing are both named, before any mapping is even checked`() {
        val d =
            determinar(
                p =
                    del(
                        predio = predio.copy(sectorCatastral = " "),
                        declaraciones = listOf(dj.copy(claseUso = null, subClaseUso = null, uso = null))
                    )
            )
        assertEquals(
            listOf("Sector catastral del predio 01-01-0001", "Uso del catálogo en la declaración 2026 del predio 01-01-0001"),
            d.faltan
        )
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `a missing sector alone stops the uso mapping from being checked at all that month`() {
        val sinMapeoUso = mapeos.filterNot { it.tipo == Llaves.ARBITRIO_USO } // the uso código resolves, its mapping doesn't exist
        val d = determinar(contexto(parametros = tasas + sinMapeoUso), p = del(predio = predio.copy(sectorCatastral = " ")))
        assertEquals(listOf("Sector catastral del predio 01-01-0001"), d.faltan) // not ARBITRIO_USO too: never reached
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `a sector present, a missing zona and a missing uso mapping are both named`() {
        val d = determinar(contexto(parametros = tasas))
        assertEquals(listOf("${Llaves.ARBITRIO_ZONA} S-01 2026", "${Llaves.ARBITRIO_USO} 010101 2026"), d.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `without a tasa it fails naming it, and determines nothing`() {
        val d = determinar(contexto(parametros = tasas.drop(1) + mapeos))
        assertEquals(listOf("TASA_ARBITRIO LIMPIEZA:Z1:CASA 2026"), d.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    // enlace (wasichai/srtm-ui#94): where a falta gets fixed. the DJ, for what its declaración lacks; the predio's
    // ubicación, for what the predio lacks; nowhere the portal can fix it, for a row of the ordinance, a titular or
    // an invariant - though every one still names its predio
    @Test
    fun `a falta of the ordinance links nowhere, one of the predio to its ubicación, one of the DJ to the DJ`() {
        val sinTasa = determinar(contexto(parametros = tasas.drop(1) + mapeos))
        assertEquals(listOf(Falta("TASA_ARBITRIO LIMPIEZA:Z1:CASA 2026", "predio-100", null, enlace = null)), sinTasa.detalle)

        val sinSector = determinar(p = del(predio = predio.copy(sectorCatastral = " ")))
        assertEquals(listOf(Falta("Sector catastral del predio 01-01-0001", "predio-100", null, Enlace.PREDIO)), sinSector.detalle)

        val sinUso = determinar(p = del(declaraciones = listOf(dj.copy(claseUso = null, subClaseUso = null, uso = null))))
        assertEquals(
            listOf(Falta("Uso del catálogo en la declaración 2026 del predio 01-01-0001", "predio-100", "dj-1", Enlace.DECLARACION)),
            sinUso.detalle
        )

        // a mapping row is the ordinance's, even when it names the predio's sector or uso
        val sinMapeos = determinar(contexto(parametros = tasas))
        assertEquals(listOf("${Llaves.ARBITRIO_ZONA} S-01 2026", "${Llaves.ARBITRIO_USO} 010101 2026"), sinMapeos.faltan)
        assertEquals(listOf(null, null), sinMapeos.detalle.map { it.enlace })

        val sinTitular = determinar(p = del(declaraciones = emptyList()))
        assertEquals(listOf(null), sinTitular.detalle.map { it.enlace })

        val sinOrdenanza = determinar(contexto(ordenanza = null))
        assertTrue(sinOrdenanza.detalle.isNotEmpty())
        assertTrue(sinOrdenanza.detalle.all { it.enlace == null })
    }

    @Test
    fun `a falta is snake_case JSON, its enlace a name or null`() {
        val json = JsonMapper.builder().build()
        assertEquals(
            """{"mensaje":"Frontis","predio":"p-1","declaracion":"dj-1","enlace":"DECLARACION"}""",
            json.writeValueAsString(Falta("Frontis", "p-1", "dj-1", Enlace.DECLARACION))
        )
        assertEquals("""{"mensaje":"TASA","predio":"p-1","declaracion":null,"enlace":null}""", json.writeValueAsString(Falta("TASA", "p-1")))
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
    fun `a buyer without uso info is silently skipped, same as today, while the seller's months still count`() {
        val venta = LocalDate.of(2026, 5, 20)
        val vendedor = dj.copy(estado = ANULADA, fechaAnulacion = venta)
        val comprador = declaracion("dj-2", "c-201").copy(fechaAdquisicion = venta, claseUso = null, subClaseUso = null, uso = null)
        val d = determinar(p = del(declaraciones = listOf(vendedor, comprador)))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals((1..5).toSet(), d.cuotas.map { it.periodo }.toSet())
        assertEquals(15, d.cuotas.size) // enero a mayo, tres servicios
        assertTrue(d.cuotas.all { it.contribuyente == "c-200" })
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
    fun `each servicio's zona can come from its own ubicación, the other falling back to the sector`() {
        val conVia = predio.copy(via = "AV. X", numero = "100")
        val ubicaciones =
            listOf(
                ubicacion(servicio = "LIMPIEZA", zona = "Z9"),
                ubicacion(servicio = "PARQUES", zona = "Z8")
            )
        val tasasPorZona = listOf(tasa("LIMPIEZA:Z9:CASA", "8.50"), tasa("PARQUES:Z8:CASA", "8.50"), tasa("SERENAZGO:Z1:CASA", "8.50"))
        val c = contexto(parametros = tasasPorZona + mapeos).copy(ubicaciones = ubicaciones)
        val d = determinar(c, p = del(predio = conVia))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(
            setOf("Z9"),
            d.cuotas
                .filter { it.servicio == "srv-LIMPIEZA" }
                .map { it.zona }
                .toSet()
        )
        assertEquals(
            setOf("Z8"),
            d.cuotas
                .filter { it.servicio == "srv-PARQUES" }
                .map { it.zona }
                .toSet()
        )
        assertEquals(
            setOf("Z1"),
            d.cuotas
                .filter { it.servicio == "srv-SERENAZGO" }
                .map { it.zona }
                .toSet()
        )
    }

    @Test
    fun `an ambiguous zona is named alone, and the whole predio gets no cuota`() {
        val conVia = predio.copy(via = "AV. X", numero = "100")
        val ubicaciones =
            listOf(
                ubicacion(servicio = "LIMPIEZA", zona = "Z9"),
                ubicacion(servicio = "LIMPIEZA", zona = "Z10")
            )
        val c = contexto(parametros = tasas + mapeos).copy(ubicaciones = ubicaciones)
        val d = determinar(c, p = del(predio = conVia))
        assertEquals(listOf("Zona ambigua de LIMPIEZA para AV. X 100: Z9, Z10"), d.faltan)
        assertEquals(listOf(Enlace.PREDIO), d.detalle.map { it.enlace }) // the predio's via and número
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas) // todo o nada: the servicios with a zona get none either
    }

    @Test
    fun `without a sector, ubicación resolving every servicio still produces every cuota`() {
        val conVia = predio.copy(sectorCatastral = null, via = "AV. X", numero = "100")
        val ubicaciones = servicios.map { ubicacion(servicio = it.codigo, zona = "Z9") }
        val tasasPorZona = servicios.map { tasa("${it.codigo}:Z9:CASA", "8.50") }
        val c = contexto(parametros = tasasPorZona + mapeos).copy(ubicaciones = ubicaciones)
        val d = determinar(c, p = del(predio = conVia))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(36, d.cuotas.size)
        assertTrue(d.cuotas.all { it.zona == "Z9" })
    }

    @Test
    fun `without a sector, a servicio ubicación does not resolve fails the whole predio (todo o nada)`() {
        val conVia = predio.copy(sectorCatastral = null, via = "AV. X", numero = "100")
        val ubicaciones = listOf(ubicacion(servicio = "LIMPIEZA", zona = "Z9"))
        val c = contexto(parametros = tasas + mapeos + listOf(tasa("LIMPIEZA:Z9:CASA", "8.50"))).copy(ubicaciones = ubicaciones)
        val d = determinar(c, p = del(predio = conVia))
        assertEquals(listOf("Sector catastral del predio 01-01-0001"), d.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `without a sector, an ubicación starting mid-year does not rescue the earlier months (todo o nada)`() {
        val conVia = predio.copy(sectorCatastral = null, via = "AV. X", numero = "100")
        val ubicaciones = listOf(ubicacion(servicio = "LIMPIEZA", zona = "Z9", desde = LocalDate.of(2026, 7, 1)))
        val c = contexto(parametros = tasas + mapeos + listOf(tasa("LIMPIEZA:Z9:CASA", "8.50"))).copy(ubicaciones = ubicaciones)
        val d = determinar(c, p = del(predio = conVia))
        assertEquals(listOf("Sector catastral del predio 01-01-0001"), d.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `a negative tasa is not a cuota`() {
        val negativa = listOf(tasa("LIMPIEZA:Z1:CASA", "-1")) + tasas.drop(1)
        val d = determinar(contexto(parametros = negativa + mapeos))
        assertEquals(listOf("TASA_ARBITRIO LIMPIEZA:Z1:CASA 2026: monto no es negativo; tasa_unitaria no es negativa"), d.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    // PARQUES' tasa by zona, influencia y afluencia (wasichai/srtm-backend#83): the figures 85.9875 and 61.0535 are
    // real, from Miraflores 2026 Ord. 665/MM, Cuadro 20 (El Peruano 20/12/2025) - the zona, influencia and afluencia
    // names and mappings around them are fictitious, built only to exercise the key
    @Test
    fun `PARQUES por zona, influencia y afluencia - Miraflores 2026, Ord 665-MM Cuadro 20`() {
        val dimensiones = param(Llaves.DIMENSIONES_ARBITRIO, "PARQUES", texto = "ZONA,INFLUENCIA,AFLUENCIA")
        val zonaDeParques = param(Llaves.ARBITRIO_ZONA, "PARQUES:S-01", texto = "A")
        val influencia = param(Llaves.ARBITRIO_INFLUENCIA, "FRENTE A PARQUE", texto = "Frente a Parque y/o Área Verde")
        val afluencia = param(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "Baja")
        val tasaDeParques = tasa("PARQUES:A:Frente a Parque y/o Área Verde:Baja", "85.9875")
        val c = contexto(parametros = tasas + mapeos + listOf(dimensiones, zonaDeParques, influencia, afluencia, tasaDeParques))
        val d = determinar(c, p = del(predio = predio.copy(ubicacionAreaVerde = "FRENTE A PARQUE")))
        assertEquals(emptyList<String>(), d.faltan)
        val parques = d.cuotas.filter { it.servicio == "srv-PARQUES" }
        assertEquals(12, parques.size)
        assertTrue(parques.all { it.monto!!.compareTo(BigDecimal("85.9875")) == 0 })
        assertTrue(parques.all { it.zona == "A" })
        assertTrue(parques.all { it.parametroAplicado == "TASA_ARBITRIO:PARQUES:A:Frente a Parque y/o Área Verde:Baja" })
        // LIMPIEZA and SERENAZGO have no DIMENSIONES_ARBITRIO: their key stays zona:uso, same as before this feature
        assertTrue(d.cuotas.filter { it.servicio != "srv-PARQUES" }.all { it.parametroAplicado!!.endsWith(":Z1:CASA") })
    }

    @Test
    fun `PARQUES zona D, OTRAS UBICACIONES y Muy alta - Miraflores 2026, Ord 665-MM Cuadro 20`() {
        val sectorD = predio.copy(sectorCatastral = "S-02", ubicacionAreaVerde = "OTRAS UBICACIONES")
        val mapeosDeD = listOf(param(Llaves.ARBITRIO_ZONA, "S-02", texto = "Z1"), param(Llaves.ARBITRIO_USO, "0101", texto = "CASA"))
        val dimensiones = param(Llaves.DIMENSIONES_ARBITRIO, "PARQUES", texto = "ZONA,INFLUENCIA,AFLUENCIA")
        val zonaDeParques = param(Llaves.ARBITRIO_ZONA, "PARQUES:S-02", texto = "D")
        val influencia = param(Llaves.ARBITRIO_INFLUENCIA, "OTRAS UBICACIONES", texto = "Otras Ubicaciones")
        val afluencia = param(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "Muy alta")
        val tasaDeParques = tasa("PARQUES:D:Otras Ubicaciones:Muy alta", "61.0535")
        val c = contexto(parametros = tasas + mapeosDeD + listOf(dimensiones, zonaDeParques, influencia, afluencia, tasaDeParques))
        val d = determinar(c, p = del(predio = sectorD))
        assertEquals(emptyList<String>(), d.faltan)
        val parques = d.cuotas.filter { it.servicio == "srv-PARQUES" }
        assertEquals(12, parques.size)
        assertTrue(parques.all { it.monto!!.compareTo(BigDecimal("61.0535")) == 0 })
        assertTrue(parques.all { it.zona == "D" })
    }

    // the 150.30 figure is real too, del manual SRTM web M02 «Determinación de Deuda», págs. 17-18; zona, influencia
    // y afluencia son ficticias, igual que arriba
    @Test
    fun `PARQUES periferia, parque y baja - manual SRTM web M02, Determinación de Deuda, pág 17-18`() {
        val sectorPeriferia = predio.copy(sectorCatastral = "S-03", ubicacionAreaVerde = "FRENTE A PARQUE")
        val mapeosDePeriferia = listOf(param(Llaves.ARBITRIO_ZONA, "S-03", texto = "Z1"), param(Llaves.ARBITRIO_USO, "0101", texto = "CASA"))
        val dimensiones = param(Llaves.DIMENSIONES_ARBITRIO, "PARQUES", texto = "ZONA,INFLUENCIA,AFLUENCIA")
        val zonaDeParques = param(Llaves.ARBITRIO_ZONA, "PARQUES:S-03", texto = "Periferia")
        val influencia = param(Llaves.ARBITRIO_INFLUENCIA, "FRENTE A PARQUE", texto = "Parque")
        val afluencia = param(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "Baja")
        val tasaDeParques = tasa("PARQUES:Periferia:Parque:Baja", "150.30")
        val c = contexto(parametros = tasas + mapeosDePeriferia + listOf(dimensiones, zonaDeParques, influencia, afluencia, tasaDeParques))
        val d = determinar(c, p = del(predio = sectorPeriferia))
        assertEquals(emptyList<String>(), d.faltan)
        val parques = d.cuotas.filter { it.servicio == "srv-PARQUES" }
        assertEquals(12, parques.size)
        assertTrue(parques.all { it.monto!!.compareTo(BigDecimal("150.30")) == 0 })
    }

    @Test
    fun `sin ubicación de área verde del predio, PARQUES con INFLUENCIA falla todo el predio (todo o nada)`() {
        val dimensiones = param(Llaves.DIMENSIONES_ARBITRIO, "PARQUES", texto = "ZONA,INFLUENCIA,AFLUENCIA")
        val afluencia = param(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "Baja") // so only INFLUENCIA is the one missing
        val c = contexto(parametros = tasas + mapeos + listOf(dimensiones, afluencia))
        val d = determinar(c, p = del(predio = predio.copy(ubicacionAreaVerde = null)))
        assertEquals(listOf("Ubicación respecto del área verde del predio 01-01-0001"), d.faltan)
        assertEquals(listOf(Enlace.PREDIO), d.detalle.map { it.enlace })
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas) // todo o nada: ni LIMPIEZA ni SERENAZGO se escriben
    }

    // un PU por uso (wasichai/srtm-backend#84): each secuencia de uso of the declarations that cover the month is
    // determined apart, with its own uso, área, frontis and habitantes - from the year ARBITRIO_PU_POR_USO rules on
    // its 1 January (#80). every figure here is fictitious
    private val puPorUso = param(Llaves.ARBITRIO_PU_POR_USO, "", valor = "1")
    private val tarifasPorArea =
        mapeos + param(Llaves.ARBITRIO_USO, "02", texto = "COMERCIO") +
            servicios.map { param(Llaves.BASE_ARBITRIO, it.codigo!!, texto = "AREA_CONSTRUIDA_M2") } +
            servicios.flatMap { listOf(tasa("${it.codigo}:Z1:CASA", "0.10"), tasa("${it.codigo}:Z1:COMERCIO", "0.25")) }
    private val porArea = tarifasPorArea + puPorUso
    private val casa = dj.copy(secuenciaUso = "1", areaConstruida = BigDecimal("120"))
    private val tienda =
        declaracion("dj-2", "c-200").copy(
            secuenciaUso = "002",
            claseUso = "COMERCIAL",
            subClaseUso = "TIENDAS",
            uso = "TIENDA",
            areaConstruida = BigDecimal("40")
        )

    @Test
    fun `a predio of two usos has two cuotas per servicio and month, each with its uso, base and tasa`() {
        val d = determinar(contexto(parametros = porArea), del(declaraciones = listOf(tienda, casa)))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(72, d.cuotas.size) // 3 servicios x 12 months x 2 usos
        val enero = d.cuotas.filter { it.servicio == "srv-LIMPIEZA" && it.periodo == 1 }
        assertEquals(listOf("001", "002"), enero.map { it.secuenciaUso })
        assertEquals(listOf("CASA", "COMERCIO"), enero.map { it.usoArbitrio })
        assertEquals(listOf("AREA_CONSTRUIDA_M2", "AREA_CONSTRUIDA_M2"), enero.map { it.base })
        assertEquals(listOf(BigDecimal("120"), BigDecimal("40")), enero.map { it.cantidadBase })
        assertEquals(listOf(BigDecimal("0.10"), BigDecimal("0.25")), enero.map { it.tasaUnitaria })
        assertEquals(listOf("12.00", "10.00"), enero.map { it.monto!!.toPlainString() })
        assertEquals(
            listOf("TASA_ARBITRIO:LIMPIEZA:Z1:CASA;BASE_ARBITRIO:LIMPIEZA", "TASA_ARBITRIO:LIMPIEZA:Z1:COMERCIO;BASE_ARBITRIO:LIMPIEZA"),
            enero.map { it.parametroAplicado }
        )
        assertEquals(listOf("predio-100|srv-LIMPIEZA|2026|1|001|1", "predio-100|srv-LIMPIEZA|2026|1|002|1"), enero.map { it.clave })
        assertEquals(listOf("p-TASA_ARBITRIO-LIMPIEZA:Z1:CASA", "p-TASA_ARBITRIO-LIMPIEZA:Z1:COMERCIO"), enero.map { it.parametro })
        assertTrue(d.cuotas.all { it.contribuyente == "c-200" })
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
        assertEquals(
            Determinacion(emptyList(), emptyList()),
            determinar(contexto(parametros = porArea), del(declaraciones = listOf(tienda, casa), existentes = d.cuotas))
        )
    }

    @Test
    fun `a secuencia without the área its base asks for fails the whole predio, naming the secuencia`() {
        val sinArea = tienda.copy(areaConstruida = null)
        val d = determinar(contexto(parametros = porArea), del(declaraciones = listOf(casa, sinArea)))
        val mensaje = "Área construida del predio 01-01-0001, uso 002, declaración 2026"
        assertEquals(listOf(mensaje), d.faltan) // once, not once per month and servicio
        assertEquals(listOf(Falta(mensaje, "predio-100", "dj-2", Enlace.DECLARACION)), d.detalle)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `a secuencia without a uso código in any month fails the predio, naming the secuencia`() {
        val sinClase = tienda.copy(claseUso = null, subClaseUso = null, uso = null)
        val d = determinar(contexto(parametros = porArea), del(declaraciones = listOf(casa, sinClase)))
        val mensaje = "Uso del catálogo en la declaración 2026 del predio 01-01-0001, uso 002"
        assertEquals(listOf(mensaje), d.faltan)
        assertEquals(listOf(Falta(mensaje, "predio-100", "dj-2", Enlace.DECLARACION)), d.detalle)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `a secuencia that loses its uso mid-year is charged while it had one, and the rest is skipped silently`() {
        val cambio = LocalDate.of(2026, 5, 20)
        val conUso = tienda.copy(estado = ANULADA, fechaAnulacion = cambio)
        val sinUso = declaracion("dj-4", "c-200").copy(secuenciaUso = "002", fechaAdquisicion = cambio, claseUso = null, subClaseUso = null, uso = null)
        val d = determinar(contexto(parametros = porArea), del(declaraciones = listOf(casa, conUso, sinUso)))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals((1..5).toList(), d.cuotas.filter { it.servicio == "srv-LIMPIEZA" && it.secuenciaUso == "002" }.map { it.periodo })
        assertEquals((1..12).toList(), d.cuotas.filter { it.servicio == "srv-LIMPIEZA" && it.secuenciaUso == "001" }.map { it.periodo })
    }

    // a predio without a sector, every servicio's zona by ubicación, determined for the whole year; then its
    // secuencia 002 loses its uso from june on (a sale registered later). running it again has nothing pending: it
    // writes nothing and names nothing, the same as with a sector - not a 422 for the months without a uso
    @Test
    fun `without a sector, running it again with nothing pending adds nothing though a secuencia lost its uso since`() {
        val sinSector = predio.copy(sectorCatastral = null, via = "AV. X", numero = "100")
        val c = contexto(parametros = porArea).copy(ubicaciones = servicios.map { ubicacion(servicio = it.codigo, zona = "Z1") })
        val primera = determinar(c, del(declaraciones = listOf(casa, tienda), predio = sinSector))
        assertEquals(emptyList<String>(), primera.faltan)
        assertEquals(72, primera.cuotas.size)

        val cambio = LocalDate.of(2026, 5, 20)
        val conUso = tienda.copy(estado = ANULADA, fechaAnulacion = cambio)
        val sinUso = declaracion("dj-4", "c-200").copy(secuenciaUso = "002", fechaAdquisicion = cambio, claseUso = null, subClaseUso = null, uso = null)
        val despues = listOf(casa, conUso, sinUso)
        val nada = Determinacion(emptyList(), emptyList())
        assertEquals(nada, determinar(contexto(parametros = porArea), del(declaraciones = despues, existentes = primera.cuotas)))
        assertEquals(nada, determinar(c, del(declaraciones = despues, predio = sinSector, existentes = primera.cuotas)))
    }

    @Test
    fun `with condóminos in a secuencia, its data are its titular principal's - the contribuyente, the day's`() {
        val casa30 = casa.copy(porcentajeCondominio = BigDecimal("30"), areaConstruida = BigDecimal("100"))
        val casa70 = declaracion("dj-3", "c-201").copy(secuenciaUso = "001", porcentajeCondominio = BigDecimal("70"), areaConstruida = BigDecimal("150"))
        val d = determinar(contexto(parametros = porArea), del(declaraciones = listOf(casa30, casa70, tienda)))
        assertEquals(emptyList<String>(), d.faltan)
        val enero = d.cuotas.filter { it.servicio == "srv-LIMPIEZA" && it.periodo == 1 }
        assertEquals(listOf(BigDecimal("150"), BigDecimal("40")), enero.map { it.cantidadBase })
        // the titular principal of the day is the one with the largest share of any secuencia: c-200's 100 % tienda
        assertTrue(d.cuotas.all { it.contribuyente == "c-200" })
    }

    @Test
    fun `an annulled cuota of the secuencia 002 is determined again alone, with its next version`() {
        val todas =
            determinar(contexto(parametros = porArea), del(declaraciones = listOf(casa, tienda))).cuotas.mapIndexed { i, c -> c.copy(id = "q$i") }
        val anulada = todas.single { it.servicio == "srv-LIMPIEZA" && it.periodo == 7 && it.secuenciaUso == "002" }
        val p = del(declaraciones = listOf(casa, tienda), existentes = todas).copy(anuladas = setOf(anulada.id!!))
        val otra = determinar(contexto(parametros = porArea), p)
        assertEquals(listOf("predio-100|srv-LIMPIEZA|2026|7|002|2"), otra.cuotas.map { it.clave })
    }

    @Test
    fun `a cuota of before the secuencia counts as the predio's lowest secuencia`() {
        // written by the determination of one PU per predio: no secuencia_uso, a clave of 5 parts
        val antiguas =
            determinar().cuotas.mapIndexed { i, c ->
                c.copy(
                    id = "q$i",
                    secuenciaUso = null,
                    clave = "predio-100|${c.servicio}|2026|${c.periodo}|1",
                    base = null,
                    cantidadBase = null,
                    tasaUnitaria = null
                )
            }
        assertEquals(Determinacion(emptyList(), emptyList()), determinar(p = del(existentes = antiguas)))

        // a second uso declared later is the only one determined, from the year of one PU per uso
        val comercio = listOf(param(Llaves.ARBITRIO_USO, "02", texto = "COMERCIO")) + servicios.map { tasa("${it.codigo}:Z1:COMERCIO", "4.25") }
        val unPu = determinar(contexto(parametros = tasas + mapeos + comercio), del(declaraciones = listOf(dj, tienda), existentes = antiguas))
        assertEquals(Determinacion(emptyList(), emptyList()), unPu) // before it, one PU per predio: nothing is pending
        val conTienda = determinar(contexto(parametros = tasas + mapeos + comercio + puPorUso), del(declaraciones = listOf(dj, tienda), existentes = antiguas))
        assertEquals(emptyList<String>(), conTienda.faltan)
        assertEquals(setOf("002"), conTienda.cuotas.map { it.secuenciaUso }.toSet())
        assertEquals(36, conTienda.cuotas.size)

        // and one annulled comes back as the next version of the lowest secuencia
        val anulada = antiguas.single { it.servicio == "srv-PARQUES" && it.periodo == 7 }
        val otra = determinar(p = del(existentes = antiguas).copy(anuladas = setOf(anulada.id!!)))
        assertEquals(listOf("predio-100|srv-PARQUES|2026|7|001|2"), otra.cuotas.map { it.clave })
    }

    // before the year ARBITRIO_PU_POR_USO rules (#80), one PU per predio as before #84: each month, only the
    // titular principal's declaration (its lowest secuencia), with its uso, área and frontis. the cuota keeps its
    // secuencia in the clave of 6 parts
    @Test
    fun `without ARBITRIO_PU_POR_USO a predio of two usos pays one PU - its titular principal's lowest secuencia`() {
        val d = determinar(contexto(parametros = tarifasPorArea), del(declaraciones = listOf(tienda, casa)))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(36, d.cuotas.size) // 3 servicios x 12 months, one uso
        assertTrue(d.cuotas.all { it.secuenciaUso == "001" && it.usoArbitrio == "CASA" && it.cantidadBase == BigDecimal("120") })
        val enero = d.cuotas.first()
        assertEquals("12.00", enero.monto!!.toPlainString())
        assertEquals("predio-100|srv-LIMPIEZA|2026|1|001|1", enero.clave)
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
        // running it again adds nothing: the secuencia 002 is not pending
        assertEquals(
            Determinacion(emptyList(), emptyList()),
            determinar(contexto(parametros = tarifasPorArea), del(declaraciones = listOf(tienda, casa), existentes = d.cuotas))
        )
    }

    @Test
    fun `with ARBITRIO_PU_POR_USO in force on 1 January every secuencia pays its PU, otherwise one PU per predio`() {
        fun cuotas(fila: ParametroTributario) = determinar(contexto(parametros = tarifasPorArea + fila), del(declaraciones = listOf(casa, tienda))).cuotas
        assertEquals(72, cuotas(puPorUso).size)
        assertEquals(setOf("001", "002"), cuotas(puPorUso).map { it.secuenciaUso }.toSet())
        // not in force on 1 January: from july, ended in 2025, or a valor other than 1
        assertEquals(36, cuotas(puPorUso.copy(vigenciaDesde = LocalDate.of(2026, 7, 1))).size)
        assertEquals(36, cuotas(puPorUso.copy(vigenciaDesde = LocalDate.of(2025, 1, 1), vigenciaHasta = LocalDate.of(2025, 12, 31))).size)
        assertEquals(36, cuotas(puPorUso.copy(valorNumerico = BigDecimal("2"))).size)
        // in force since an earlier year, it still rules
        assertEquals(72, cuotas(puPorUso.copy(vigenciaDesde = LocalDate.of(2024, 1, 1))).size)
    }

    @Test
    fun `a row from 2027 - 2026 pays one PU per predio, 2027 one per uso`() {
        val desde2027 = puPorUso.copy(vigenciaDesde = LocalDate.of(2027, 1, 1))
        val de2026 = determinar(contexto(parametros = tarifasPorArea + desde2027), del(declaraciones = listOf(casa, tienda)))
        assertEquals(emptyList<String>(), de2026.faltan)
        assertEquals(setOf("001"), de2026.cuotas.map { it.secuenciaUso }.toSet())
        assertEquals(36, de2026.cuotas.size)

        val de2027 =
            determinar(contexto(anio = 2027, parametros = tarifasPorArea + desde2027), del(declaraciones = listOf(casa, tienda).map { it.copy(anio = 2027) }))
        assertEquals(emptyList<String>(), de2027.faltan)
        assertEquals(setOf("001", "002"), de2027.cuotas.map { it.secuenciaUso }.toSet())
        assertEquals(72, de2027.cuotas.size)
        assertEquals("predio-100|srv-LIMPIEZA|2027|1|002|1", de2027.cuotas.first { it.secuenciaUso == "002" }.clave)
    }

    @Test
    fun `before the year of one PU per uso a secuencia without a uso código is ignored, not named`() {
        val sinClase = tienda.copy(claseUso = null, subClaseUso = null, uso = null)
        val d = determinar(contexto(parametros = tarifasPorArea), del(declaraciones = listOf(casa, sinClase)))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(setOf("001"), d.cuotas.map { it.secuenciaUso }.toSet())
        // and the lowest secuencia without one fails it as before #84, once no month has one: without naming a uso
        val sinNinguno =
            determinar(contexto(parametros = tarifasPorArea), del(declaraciones = listOf(casa.copy(claseUso = null, subClaseUso = null, uso = null), tienda)))
        assertEquals(listOf("Uso del catálogo en la declaración 2026 del predio 01-01-0001"), sinNinguno.faltan)
    }

    // as before #84, the declaration is the titular principal's (titularPrincipal): its largest share wins over its
    // lowest secuencia. c-200 has 30 % of the 001 and 100 % of the 002: the 002 is charged, whole, to c-200
    @Test
    fun `before the year of one PU per uso the declaration is the titular principal's, as before #84`() {
        val casa30 = casa.copy(porcentajeCondominio = BigDecimal("30"))
        val casa70 = declaracion("dj-3", "c-201").copy(secuenciaUso = "001", porcentajeCondominio = BigDecimal("70"), areaConstruida = BigDecimal("150"))
        val d = determinar(contexto(parametros = tarifasPorArea), del(declaraciones = listOf(casa30, casa70, tienda)))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(36, d.cuotas.size)
        assertTrue(d.cuotas.all { it.secuenciaUso == "002" && it.usoArbitrio == "COMERCIO" && it.contribuyente == "c-200" })
    }

    // a year determined before #84: one cuota per servicio and month, without secuencia_uso and with a clave of 5
    // parts. before the year of one PU per uso nothing of a predio of two usos is pending; from it, the 002's are
    @Test
    fun `a predio of two usos determined before #84 has nothing pending until the year of one PU per uso`() {
        val antiguas =
            determinar(contexto(parametros = tarifasPorArea), del(declaraciones = listOf(casa, tienda))).cuotas.mapIndexed { i, c ->
                c.copy(
                    id = "q$i",
                    secuenciaUso = null,
                    clave = "predio-100|${c.servicio}|2026|${c.periodo}|1",
                    base = null,
                    cantidadBase = null,
                    tasaUnitaria = null
                )
            }
        val nada = Determinacion(emptyList(), emptyList())
        assertEquals(nada, determinar(contexto(parametros = tarifasPorArea), del(declaraciones = listOf(casa, tienda), existentes = antiguas)))
        // an annulled one comes back alone, the next version of the secuencia charged
        val anulada = antiguas.single { it.servicio == "srv-PARQUES" && it.periodo == 7 }
        val otra =
            determinar(
                contexto(parametros = tarifasPorArea),
                del(declaraciones = listOf(casa, tienda), existentes = antiguas).copy(anuladas = setOf(anulada.id!!))
            )
        assertEquals(listOf("predio-100|srv-PARQUES|2026|7|001|2"), otra.cuotas.map { it.clave })

        val porUso = determinar(contexto(parametros = porArea), del(declaraciones = listOf(casa, tienda), existentes = antiguas))
        assertEquals(36, porUso.cuotas.size)
        assertEquals(setOf("002"), porUso.cuotas.map { it.secuenciaUso }.toSet())
    }

    // the example of the manual SRTM web M02 «Determinación de Deuda», págs. 17-18 (global-constraints.md): barrido
    // 3.37 x 12 ml = 40.44, recolección 0.12 x 180 m² x (1 + (5 - 1) x 1) = 108.00, parques 150.30 and serenazgo
    // 6.61: 305.35 a month. the zona, uso, influencia and afluencia names are fictitious
    @Test
    fun `the manual M02 example - 4 cuotas a month that add up to 305_35`() {
        val m02 = listOf(servicio("BARRIDO", 1), servicio("RECOLECCION", 2), servicio("PARQUES", 3), servicio("SERENAZGO", 4))
        val filas =
            listOf(
                param(Llaves.ARBITRIO_ZONA, "S-03", texto = "Z1"),
                param(Llaves.ARBITRIO_USO, "0101", texto = "CASA"),
                param(Llaves.BASE_ARBITRIO, "BARRIDO", texto = "FRONTIS_ML"),
                param(Llaves.BASE_ARBITRIO, "RECOLECCION", texto = "AREA_CONSTRUIDA_M2"),
                param(Llaves.HABITANTES_PROMEDIO, "RECOLECCION:Z1:CASA", valor = "1"),
                param(Llaves.HABITANTES_VARIACION, "RECOLECCION:Z1:CASA", valor = "1"),
                param(Llaves.DIMENSIONES_ARBITRIO, "PARQUES", texto = "ZONA,INFLUENCIA,AFLUENCIA"),
                param(Llaves.ARBITRIO_ZONA, "PARQUES:S-03", texto = "Periferia"),
                param(Llaves.ARBITRIO_INFLUENCIA, "FRENTE A PARQUE", texto = "Parque"),
                param(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "Baja"),
                tasa("BARRIDO:Z1:CASA", "3.37"),
                tasa("RECOLECCION:Z1:CASA", "0.12"),
                tasa("PARQUES:Periferia:Parque:Baja", "150.30"),
                tasa("SERENAZGO:Z1:CASA", "6.61")
            )
        val m02Predio = predio.copy(sectorCatastral = "S-03", ubicacionAreaVerde = "FRENTE A PARQUE")
        val m02Dj = dj.copy(longitudFrente = BigDecimal("12"), areaConstruida = BigDecimal("180"), numeroHabitantes = 5)
        val d = determinar(contexto(servicios = m02, parametros = filas), del(declaraciones = listOf(m02Dj), predio = m02Predio))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(48, d.cuotas.size)
        val enero = d.cuotas.filter { it.periodo == 1 }
        assertEquals(listOf("40.44", "108.00", "150.30", "6.61"), enero.map { it.monto!!.setScale(2).toPlainString() })
        assertEquals(0, BigDecimal("305.35").compareTo(enero.sumOf { it.monto!! }))
        val recoleccion = enero[1]
        assertEquals(listOf(5, false), listOf(recoleccion.habitantes, recoleccion.habitantesPresuntos))
        assertEquals(
            "TASA_ARBITRIO:RECOLECCION:Z1:CASA;BASE_ARBITRIO:RECOLECCION;HABITANTES_PROMEDIO:RECOLECCION:Z1:CASA;" +
                "HABITANTES_VARIACION:RECOLECCION:Z1:CASA",
            recoleccion.parametroAplicado
        )
        val parques = enero[2]
        assertEquals(listOf("Periferia", "Parque", "Baja"), listOf(parques.zona, parques.influencia, parques.afluencia))
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
    }

    // Miraflores 2026, Ord. 665/MM (global-constraints.md): recolección of a casa habitación in the zona 01A at
    // 0.0514 S/ por m² with P = 2 and V = 0.5, barrido of the Zona 2 at 5.0856 S/ por ml, redondeo 2:ABAJO. the
    // sector and uso names around them are fictitious
    private val miraflores = listOf(servicio("RRSS", 1), servicio("BARRIDO", 2))
    private val filasMiraflores =
        listOf(
            param(Llaves.ARBITRIO_ZONA, "RRSS:S-01", texto = "01A"),
            param(Llaves.ARBITRIO_ZONA, "BARRIDO:S-01", texto = "2"),
            param(Llaves.ARBITRIO_USO, "0101", texto = "CASA"),
            param(Llaves.BASE_ARBITRIO, "RRSS", texto = "AREA_CONSTRUIDA_M2"),
            param(Llaves.BASE_ARBITRIO, "BARRIDO", texto = "FRONTIS_ML"),
            param(Llaves.HABITANTES_PROMEDIO, "RRSS:01A:CASA", valor = "2"),
            param(Llaves.HABITANTES_VARIACION, "RRSS:01A:CASA", valor = "0.5"),
            param(Llaves.REDONDEO_ARBITRIO, "", texto = "2:ABAJO"),
            tasa("RRSS:01A:CASA", "0.0514"),
            tasa("BARRIDO:2:CASA", "5.0856")
        )
    private val djMiraflores = dj.copy(areaConstruida = BigDecimal("100"), longitudFrente = BigDecimal("10.5"), numeroHabitantes = 4)

    @Test
    fun `Miraflores 2026 - 10_28 of recolección and 53_39 of barrido, rounded down to 2 decimals`() {
        val d = determinar(contexto(servicios = miraflores, parametros = filasMiraflores), del(declaraciones = listOf(djMiraflores)))
        assertEquals(emptyList<String>(), d.faltan)
        val (rrss, barrido) = d.cuotas.filter { it.periodo == 1 }
        assertEquals(BigDecimal("10.28"), rrss.monto)
        assertEquals(
            listOf(4, false, BigDecimal("2"), BigDecimal("0.5")),
            listOf(rrss.habitantes, rrss.habitantesPresuntos, rrss.promedioHabitantes, rrss.variacionHabitante)
        )
        assertEquals("2:ABAJO", rrss.redondeo)
        assertEquals(
            "TASA_ARBITRIO:RRSS:01A:CASA;BASE_ARBITRIO:RRSS;HABITANTES_PROMEDIO:RRSS:01A:CASA;HABITANTES_VARIACION:RRSS:01A:CASA;REDONDEO_ARBITRIO",
            rrss.parametroAplicado
        )
        assertEquals(BigDecimal("53.39"), barrido.monto)
        assertEquals(listOf("FRONTIS_ML", BigDecimal("10.5"), BigDecimal("5.0856")), listOf(barrido.base, barrido.cantidadBase, barrido.tasaUnitaria))
        assertEquals(listOf(null, null), listOf(barrido.habitantes, barrido.habitantesPresuntos))
        assertEquals("TASA_ARBITRIO:BARRIDO:2:CASA;BASE_ARBITRIO:BARRIDO;REDONDEO_ARBITRIO", barrido.parametroAplicado)
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
    }

    @Test
    fun `habitantes not declared - the factor is 1, and the cuota says they were presumed`() {
        val sinHabitantes = djMiraflores.copy(numeroHabitantes = null)
        val d = determinar(contexto(servicios = miraflores, parametros = filasMiraflores), del(declaraciones = listOf(sinHabitantes)))
        val rrss = d.cuotas.first()
        assertEquals(BigDecimal("5.14"), rrss.monto)
        assertEquals(listOf(null, true), listOf(rrss.habitantes, rrss.habitantesPresuntos))
        assertTrue(invariantes(rrss).isEmpty())
    }

    // #86: the exact file import_tasas_arbitrio.py generates from the ficticio tasas, usos and influencias (the
    // fixed run cited in its header's regeneration command / test_import_tasas_arbitrio.py's
    // generar_ficticio_parametros); a byte-equality test on the Python side keeps this file honest. Only the id
    // is filled in here (the importer's CSV has none): Parametros.leer already parses commas inside quotes
    // (DIMENSIONES_ARBITRIO's texto)
    private fun leerFicticioParametros(): List<ParametroTributario> =
        Parametros.leer(File("model/data/arbitrios/ficticio-parametros.csv")).mapIndexed { i, p -> p.copy(id = "fp-$i") }

    private val servicioPorBase = listOf(servicio("BAR", 1), servicio("RRS", 2), servicio("PYJ", 3), servicio("SER", 4))

    // ARBITRIO_ZONA (por servicio, sector S-01) y ARBITRIO_AFLUENCIA (prefijo 01): lo único que
    // import_tasas_arbitrio.py no deriva de la tabla de tasas y que este test agrega, con los mismos textos
    // normalizados que el importador escribe en las claves de TASA_ARBITRIO (ZONA_1, ZONA_A, AREA_0, ALTA).
    // ARBITRIO_USO ya viene en el archivo (desde --usos): no hace falta agregarlo
    private val mapeosPorBase =
        listOf(
            param(Llaves.ARBITRIO_ZONA, "BAR:S-01", texto = "ZONA_1"),
            param(Llaves.ARBITRIO_ZONA, "RRS:S-01", texto = "ZONA_1"),
            param(Llaves.ARBITRIO_ZONA, "PYJ:S-01", texto = "ZONA_A"),
            param(Llaves.ARBITRIO_ZONA, "SER:S-01", texto = "AREA_0"),
            param(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "ALTA")
        )

    // un predio en el sector S-01, con ubicación FRENTE A PARQUE (para PYJ) y el uso de la declaración por
    // defecto (CASA HABITACIÓN, código 010101 -> ARBITRIO_USO 0101 -> "2", la misma casa habitación de
    // ficticio-tasas.csv): un predio por Base, sobre el CSV que import_tasas_arbitrio.py realmente genera
    @Test
    fun `un predio por base - FRONTIS_ML, AREA_CONSTRUIDA_M2 con habitantes y PREDIO dan los montos esperados`() {
        val filas = leerFicticioParametros() + mapeosPorBase
        val predioDeTodos = predio.copy(sectorCatastral = "S-01", ubicacionAreaVerde = "FRENTE A PARQUE")
        val declaracionDeTodos = dj.copy(anio = 2041, longitudFrente = BigDecimal("10"), areaConstruida = BigDecimal("100"), numeroHabitantes = 7)
        val d =
            determinar(
                contexto(anio = 2041, servicios = servicioPorBase, parametros = filas),
                del(declaraciones = listOf(declaracionDeTodos), predio = predioDeTodos)
            )
        assertEquals(emptyList<String>(), d.faltan)
        val enero = d.cuotas.filter { it.periodo == 1 }.associateBy { it.servicio }
        // BAR (FRONTIS_ML): 3.1000 S/ por ml x 10 ml = 31.00
        assertEquals(BigDecimal("31.00"), enero["srv-BAR"]!!.monto)
        // RRS (AREA_CONSTRUIDA_M2, con habitantes P=4 V=0.3 de ficticio-parametros.csv): 0.0500 x 100 x
        // (1 + (7 - 4) x 0.3) = 0.0500 x 100 x 1.9 = 9.50
        assertEquals(BigDecimal("9.50"), enero["srv-RRS"]!!.monto)
        assertEquals(listOf(7, false), listOf(enero["srv-RRS"]!!.habitantes, enero["srv-RRS"]!!.habitantesPresuntos))
        // PYJ (PREDIO, con INFLUENCIA y AFLUENCIA además de ZONA): 50.0000
        assertEquals(BigDecimal("50.00"), enero["srv-PYJ"]!!.monto)
        assertEquals(
            listOf("ZONA_A", "Frente a parque ficticio", "ALTA"),
            listOf(enero["srv-PYJ"]!!.zona, enero["srv-PYJ"]!!.influencia, enero["srv-PYJ"]!!.afluencia)
        )
        // SER (PREDIO, sin habitantes: no se pidió --habitantes para SERENAZGO): 20.0000
        assertEquals(BigDecimal("20.00"), enero["srv-SER"]!!.monto)
        assertEquals(listOf(null, null), listOf(enero["srv-SER"]!!.habitantes, enero["srv-SER"]!!.habitantesPresuntos))
        assertTrue(d.cuotas.all { invariantes(it).isEmpty() })
    }

    // la fila RECOLECCION_RESIDUOS,,15,Otro uso ficticio 2 de ficticio-tasas.csv no tiene zona (no varía por
    // zona, a diferencia de la de casa habitación del mismo servicio): import_tasas_arbitrio.py escribe
    // "RRS::15" (#86). Un predio en S-01 (zona exacta "ZONA_1" para RRS) con ese otro uso no tiene una
    // TASA_ARBITRIO "RRS:ZONA_1:15" exacta, pero sí usa el fallback de zona vacía
    @Test
    fun `un predio por base - la TASA_ARBITRIO de zona vacia del CSV generado se usa cuando la exacta no existe`() {
        val filas = leerFicticioParametros() + listOf(param(Llaves.ARBITRIO_ZONA, "RRS:S-01", texto = "ZONA_1"))
        val predioDeOtroUso = predio.copy(sectorCatastral = "S-01")
        val declaracionOtroUso = dj.copy(anio = 2041, areaConstruida = BigDecimal("20"), claseUso = "OTRO", subClaseUso = "OTRO", uso = "OTRO USO FICTICIO 2")
        val d =
            determinar(
                contexto(anio = 2041, servicios = listOf(servicio("RRS", 1)), parametros = filas),
                del(declaraciones = listOf(declaracionOtroUso), predio = predioDeOtroUso)
            )
        assertEquals(emptyList<String>(), d.faltan)
        val rrs = d.cuotas.first()
        // 0.1500 S/ por m2 x 20 m2, sin habitantes (no es casa habitación) = 3.00
        assertEquals(BigDecimal("3.00"), rrs.monto)
        assertEquals("ZONA_1", rrs.zona) // el predio sigue resolviendo su propia zona, como siempre
        assertEquals("TASA_ARBITRIO:RRS::15;BASE_ARBITRIO:RRS;REDONDEO_ARBITRIO", rrs.parametroAplicado)
        assertTrue(invariantes(rrs).isEmpty())
    }

    // a TASA_ARBITRIO whose zona parte is empty means "every zona of the servicio" (import_tasas_arbitrio.py,
    // #86): Arbitrios.determinar tries the exact key first (RRSS:01A:CASA) and the empty-zona one second
    // (RRSS::CASA), since RRSS's first dimension is ZONA (its default, ZONA then USO)
    @Test
    fun `una TASA_ARBITRIO de zona vacia se usa cuando no hay una exacta de esa zona`() {
        val sinLaExacta =
            filasMiraflores.filterNot { it.tipo == Llaves.TASA_ARBITRIO && it.clave == "RRSS:01A:CASA" } +
                tasa("RRSS::CASA", "0.0514")
        val d = determinar(contexto(servicios = miraflores, parametros = sinLaExacta), del(declaraciones = listOf(djMiraflores)))
        assertEquals(emptyList<String>(), d.faltan)
        val rrss = d.cuotas.first()
        assertEquals(BigDecimal("10.28"), rrss.monto)
        assertEquals("01A", rrss.zona) // el predio sigue resolviendo su propia zona, como siempre
        assertEquals(
            "TASA_ARBITRIO:RRSS::CASA;BASE_ARBITRIO:RRSS;HABITANTES_PROMEDIO:RRSS:01A:CASA;HABITANTES_VARIACION:RRSS:01A:CASA;REDONDEO_ARBITRIO",
            rrss.parametroAplicado
        )
        assertTrue(invariantes(rrss).isEmpty())
    }

    @Test
    fun `la TASA_ARBITRIO exacta gana sobre la de zona vacia cuando existen las dos`() {
        val ambas = filasMiraflores + tasa("RRSS::CASA", "999.9999")
        val d = determinar(contexto(servicios = miraflores, parametros = ambas), del(declaraciones = listOf(djMiraflores)))
        assertEquals(emptyList<String>(), d.faltan)
        assertEquals(BigDecimal("10.28"), d.cuotas.first().monto)
        assertEquals(
            "TASA_ARBITRIO:RRSS:01A:CASA;BASE_ARBITRIO:RRSS;HABITANTES_PROMEDIO:RRSS:01A:CASA;HABITANTES_VARIACION:RRSS:01A:CASA;REDONDEO_ARBITRIO",
            d.cuotas.first().parametroAplicado
        )
    }

    @Test
    fun `sin la tasa exacta ni la de zona vacia, la falta es la misma de siempre`() {
        val sinNinguna = filasMiraflores.filterNot { it.tipo == Llaves.TASA_ARBITRIO && it.clave == "RRSS:01A:CASA" }
        val d = determinar(contexto(servicios = miraflores, parametros = sinNinguna), del(declaraciones = listOf(djMiraflores)))
        assertEquals(listOf("${Llaves.TASA_ARBITRIO} RRSS:01A:CASA 2026"), d.faltan)
        assertEquals(emptyList<CuotaArbitrio>(), d.cuotas)
    }

    @Test
    fun `no habitantes is a factor of 0, a cuota of 0 - a negative factor fails the whole predio`() {
        val cero =
            determinar(contexto(servicios = miraflores, parametros = filasMiraflores), del(declaraciones = listOf(djMiraflores.copy(numeroHabitantes = 0))))
        assertEquals(emptyList<String>(), cero.faltan)
        assertEquals(
            0,
            cero.cuotas
                .first()
                .monto!!
                .signum()
        )

        // fictitious: a V of 0.8 with P = 2 and H = 0 gives 1 + (0 - 2) x 0.8 = -0.6
        val pronunciada = filasMiraflores.map { if (it.tipo == Llaves.HABITANTES_VARIACION) it.copy(valorNumerico = BigDecimal("0.8")) else it }
        val d = determinar(contexto(servicios = miraflores, parametros = pronunciada), del(declaraciones = listOf(djMiraflores.copy(numeroHabitantes = 0))))
        assertEquals(listOf("Factor de habitantes negativo en 01-01-0001, uso 001, declaración 2026: 1 + (H − P) × V = -0.6"), d.faltan)
        assertEquals(listOf(null), d.detalle.map { it.enlace })
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

    private fun ubicacion(
        servicio: String? = null,
        via: String = "AV. X",
        zona: String,
        desde: LocalDate = LocalDate.of(2026, 1, 1)
    ) = ZonaUbicacion(id = "u-$servicio-$zona", servicio = servicio, via = via, zona = zona, vigenciaDesde = desde, norma = "Ordenanza ficticia de prueba")

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
