package srtm.reportes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.brecha.Categoria
import srtm.brecha.FilaBrecha
import srtm.brecha.RegistroCorrida
import srtm.rentas.Contribuyente
import srtm.rentas.Declaracion
import java.math.BigDecimal

// the reportes de brechas (#248) as pure functions over a corrida's rows: which rows each report takes, the filters,
// the order, the aggregates of /resumen, the catalog and the contribuyentes' sums
class ReportesTest {
    private fun d(v: String?) = v?.let(::BigDecimal)

    private var siguiente = 0

    private fun fila(
        categoria: Categoria,
        deltaImpuesto: String? = null,
        deltaAutoavaluo: String? = null,
        predio: String? = null,
        codigoPredio: String? = null,
        codigoCatastro: String? = null,
        sector: String? = "01",
        manzana: String? = "A",
        uso: String? = "010101",
        confianza: String? = "CONFIABLE",
        terreno: Pair<String?, String?> = null to null,
        construida: Pair<String?, String?> = null to null,
        nivelesComparados: Int? = null,
        nivelesCatastroMayor: Int? = null,
        deltaAreaNiveles: String? = null
    ): FilaBrecha {
        val n = siguiente++
        return FilaBrecha(
            id = "b$n",
            corrida = "c1",
            predio = predio,
            codigoPredio = codigoPredio,
            codigoCatastro = codigoCatastro ?: "CM-$n",
            categoria = categoria.name,
            sector = sector,
            manzana = manzana,
            lote = "$n",
            uso = uso,
            deltaImpuesto = d(deltaImpuesto),
            deltaAutoavaluo = d(deltaAutoavaluo),
            confianza = confianza,
            areaTerrenoDeclarada = d(terreno.first),
            areaTerrenoCatastro = d(terreno.second),
            areaConstruidaDeclarada = d(construida.first),
            areaConstruidaCatastro = d(construida.second),
            nivelesComparados = nivelesComparados,
            nivelesCatastroMayor = nivelesCatastroMayor,
            deltaAreaNiveles = d(deltaAreaNiveles),
            orden = n
        )
    }

    private fun filas(
        brecha: List<FilaBrecha>,
        titulares: Map<String, List<Titular>>? = emptyMap(),
        usos: Map<String, String> = emptyMap(),
        decisiones: Map<String, String>? = emptyMap()
    ) = Reportes.filas(brecha, titulares, usos, decisiones)

    // which rows each report takes

    @Test
    fun `each categoria report takes its categoria only`() {
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, "10"),
                    fila(Categoria.SOBREVALUADO, "-5"),
                    fila(Categoria.OMISO, "7"),
                    fila(Categoria.SIN_CATASTRO),
                    fila(Categoria.SIN_LEVANTAR),
                    fila(Categoria.SIN_VALOR),
                    fila(Categoria.COINCIDE, "0")
                )
            )
        val esperado =
            mapOf(
                Reporte.SUBVALUADOS to Categoria.SUBVALUADO,
                Reporte.SOBREVALUADOS to Categoria.SOBREVALUADO,
                Reporte.OMISOS to Categoria.OMISO,
                Reporte.SIN_CATASTRO to Categoria.SIN_CATASTRO,
                Reporte.SIN_LEVANTAR to Categoria.SIN_LEVANTAR,
                Reporte.SIN_VALOR to Categoria.SIN_VALOR
            )
        for ((reporte, categoria) in esperado) {
            assertEquals(listOf(categoria.name), Reportes.del(reporte, todas, null).map { it.categoria }, reporte.clave)
        }
    }

    @Test
    fun `terreno takes the rows whose two areas differ, construccion those whose construida differs or a nivel is larger`() {
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, terreno = "100" to "120"),
                    fila(Categoria.SUBVALUADO, terreno = "100" to "100.00"),
                    fila(Categoria.SUBVALUADO, terreno = null to "100"),
                    fila(Categoria.COINCIDE, construida = "80" to "60"),
                    fila(Categoria.COINCIDE, construida = "80" to "80", nivelesComparados = 2, nivelesCatastroMayor = 1, deltaAreaNiveles = "5"),
                    fila(Categoria.COINCIDE, construida = "80" to "80", nivelesComparados = 2, nivelesCatastroMayor = 0, deltaAreaNiveles = "0")
                )
            )
        assertEquals(listOf("0"), Reportes.del(Reporte.TERRENO, todas, null).map { it.lote })
        assertEquals(listOf("3", "4"), Reportes.del(Reporte.CONSTRUCCION, todas, null).map { it.lote })
    }

    @Test
    fun `mayores-5-uit takes the rows whose absolute delta autoavaluo is above 5 UIT, none without the UIT`() {
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, deltaAutoavaluo = "26750.01"),
                    fila(Categoria.SUBVALUADO, deltaAutoavaluo = "26750.00"),
                    fila(Categoria.SOBREVALUADO, deltaAutoavaluo = "-30000"),
                    fila(Categoria.SIN_CATASTRO)
                )
            )
        val umbral = BigDecimal("5350").multiply(BigDecimal(5))
        assertEquals(listOf("0", "2"), Reportes.del(Reporte.MAYORES_5_UIT, todas, umbral).map { it.lote })
        assertEquals(emptyList<String>(), Reportes.del(Reporte.MAYORES_5_UIT, todas, null).map { it.lote })
    }

    // the filters

    @Test
    fun `sector and manzana match as the cruce compares codes, uso by its prefix, confianza exactly`() {
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, sector = "01", manzana = "a", uso = "010101", confianza = "CONFIABLE"),
                    fila(Categoria.SUBVALUADO, sector = "2", manzana = "B", uso = "020101", confianza = "DUDOSO"),
                    fila(Categoria.SUBVALUADO, sector = null, manzana = null, uso = null, confianza = null)
                )
            )
        assertEquals(listOf("0"), Reportes.filtrar(todas, Filtros(sector = "1")).map { it.lote })
        assertEquals(listOf("1"), Reportes.filtrar(todas, Filtros(sector = "02", manzana = "b")).map { it.lote })
        assertEquals(listOf("1"), Reportes.filtrar(todas, Filtros(uso = "02")).map { it.lote })
        assertEquals(listOf("0"), Reportes.filtrar(todas, Filtros(confianza = "CONFIABLE")).map { it.lote })
        assertEquals(listOf("0", "1", "2"), Reportes.filtrar(todas, Filtros()).map { it.lote })
    }

    @Test
    fun `q finds a row by its codes or by any of its contribuyentes' code or name, ignoring case and accents`() {
        val titulares =
            mapOf(
                "p1" to
                    listOf(
                        Titular("k1", "000123", "QUISPE MAMANI, JUAN", "d1", BigDecimal(50)),
                        Titular("k2", "000456", "PÉREZ ROJAS, ANA", "d2", BigDecimal(50))
                    )
            )
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, predio = "p1", codigoPredio = "01-01-0001"),
                    fila(Categoria.OMISO, codigoCatastro = "PM-77")
                ),
                titulares
            )
        assertEquals(listOf("0"), Reportes.filtrar(todas, Filtros(q = "01-01-0001")).map { it.lote })
        assertEquals(listOf("1"), Reportes.filtrar(todas, Filtros(q = "pm-77")).map { it.lote })
        assertEquals(listOf("0"), Reportes.filtrar(todas, Filtros(q = "perez")).map { it.lote })
        assertEquals(listOf("0"), Reportes.filtrar(todas, Filtros(q = "000123")).map { it.lote })
        assertEquals(emptyList<String>(), Reportes.filtrar(todas, Filtros(q = "nadie")).map { it.lote })
    }

    // #273: a contribuyente's record as the reportes read it, by the model's field names, with its prico
    @Test
    fun `a contribuyente read for the reportes keeps its name fields and its prico`() {
        val leido =
            srtm.rentas.Records.read(
                ContribuyenteDelReporte::class.java,
                "k1",
                mapOf(
                    "codigo" to "000001",
                    "tipo_contribuyente" to "PERSONA NATURAL",
                    "apellido_paterno" to "QUISPE",
                    "apellido_materno" to "MAMANI",
                    "nombres" to "JUAN",
                    "prico" to true,
                    "otro_campo" to "x"
                )
            )
        assertEquals(true, leido.prico)
        assertEquals("QUISPE MAMANI JUAN", srtm.rentas.nombreCompleto(leido.contribuyente()))
        assertEquals("000001", leido.contribuyente().codigo)
    }

    // the rows

    @Test
    fun `a row carries its principal contribuyente, the uso's description and catastro's decision`() {
        val titulares =
            mapOf("p1" to listOf(Titular("k1", "000123", "QUISPE MAMANI, JUAN", "d1", BigDecimal(100))))
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, predio = "p1", codigoPredio = "01-01-0001", codigoCatastro = "01-01-0001", uso = "010101"),
                    fila(Categoria.SIN_CATASTRO, predio = "p9", codigoPredio = "9-9-9", codigoCatastro = null, uso = "999999")
                ),
                titulares,
                usos = mapOf("10101" to "CASA HABITACIÓN"),
                decisiones = mapOf("1-1-1" to "A_INSPECCIONAR", "9-9-9" to "DESCARTADO")
            )
        val (a, b) = todas
        assertEquals(listOf("k1", "000123", "QUISPE MAMANI, JUAN", "d1"), listOf(a.contribuyente, a.codigoContribuyente, a.nombreContribuyente, a.declaracion))
        assertEquals("CASA HABITACIÓN", a.usoDescripcion)
        assertEquals("A_INSPECCIONAR", a.decision)
        assertNull(b.contribuyente)
        assertNull(b.usoDescripcion)
        // no codigo of catastro: the padrón's names the decision (catastro's codigo_municipal is the rentas code)
        assertEquals("DESCARTADO", b.decision)
    }

    @Test
    fun `without catastro's decisions every row's decision is null`() {
        val todas = filas(listOf(fila(Categoria.SUBVALUADO, codigoCatastro = "X")), decisiones = null)
        assertNull(todas.single().decision)
    }

    // the order

    @Test
    fun `rows sort by delta, area difference or code, nulls last either way, ties in the corrida's order`() {
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, "10", "100", codigoPredio = "B", terreno = "100" to "150", construida = "50" to "40"),
                    fila(Categoria.SUBVALUADO, null, null, codigoPredio = null, codigoCatastro = "Z"),
                    fila(Categoria.SUBVALUADO, "30", "50", codigoPredio = "A", terreno = "100" to "110", construida = "50" to "90"),
                    fila(Categoria.SUBVALUADO, "10", "70", codigoPredio = "C")
                )
            )

        fun orden(
            o: Orden,
            dir: Direccion
        ) = Reportes.ordenar(todas, o, dir).map { it.lote }
        assertEquals(listOf("2", "0", "3", "1"), orden(Orden.DELTA_IMPUESTO, Direccion.DESC))
        assertEquals(listOf("0", "3", "2", "1"), orden(Orden.DELTA_IMPUESTO, Direccion.ASC))
        assertEquals(listOf("0", "3", "2", "1"), orden(Orden.DELTA_AUTOAVALUO, Direccion.DESC))
        assertEquals(listOf("0", "2", "1", "3"), orden(Orden.AREA_TERRENO, Direccion.DESC))
        assertEquals(listOf("2", "0", "1", "3"), orden(Orden.AREA_CONSTRUIDA, Direccion.DESC))
        // the padrón's code, then catastro's
        assertEquals(listOf("2", "0", "3", "1"), orden(Orden.CODIGO, Direccion.ASC))
        assertEquals(listOf("3", "0", "2", "1"), orden(Orden.CODIGO, Direccion.DESC))
    }

    @Test
    fun `orden and dir read their names, and each report has its own default`() {
        assertEquals(Orden.AREA_TERRENO, Orden.de("areaTerreno"))
        assertNull(Orden.de("nada"))
        assertEquals(Direccion.ASC, Direccion.de("asc"))
        assertEquals(Orden.DELTA_IMPUESTO to Direccion.DESC, Reporte.SUBVALUADOS.ordenPorOmision)
        assertEquals(Orden.DELTA_IMPUESTO to Direccion.ASC, Reporte.SOBREVALUADOS.ordenPorOmision)
        assertEquals(Orden.CODIGO to Direccion.ASC, Reporte.SIN_CATASTRO.ordenPorOmision)
        assertEquals(Orden.AREA_TERRENO to Direccion.DESC, Reporte.TERRENO.ordenPorOmision)
        assertEquals(Reporte.MAYORES_5_UIT, Reporte.de("mayores-5-uit"))
        assertNull(Reporte.de("SUBVALUADOS"))
    }

    // the contribuyentes

    @Test
    fun `the padron's titulares of a predio are its vigente DJs' contribuyentes by their share, the principal first`() {
        val dj = { id: String, predio: String, contribuyente: String, porcentaje: String?, secuencia: String ->
            Declaracion(id = id, predio = predio, contribuyente = contribuyente, anio = 2026, secuenciaUso = secuencia, porcentajeCondominio = d(porcentaje))
        }
        val contribuyentes =
            mapOf(
                "k1" to Contribuyente(id = "k1", codigo = "000001", apellidoPaterno = "QUISPE", nombres = "JUAN", tipoContribuyente = "PERSONA NATURAL"),
                "k2" to Contribuyente(id = "k2", codigo = "000002", razonSocial = "EMPRESA SAC", tipoContribuyente = "PERSONA JURIDICA"),
                "k3" to Contribuyente(id = "k3", codigo = "000003", nombreCompleto = "ROJAS ANA")
            )
        val titulares =
            Reportes.titulares(
                listOf(
                    dj("d1", "p1", "k1", "30", "1"),
                    dj("d2", "p1", "k2", "70", "1"),
                    // an annulled one is no titular
                    Declaracion(id = "d3", predio = "p2", contribuyente = "k3", anio = 2026, secuenciaUso = "1", estado = "ANULADA"),
                    dj("d4", "p3", "k3", null, "1")
                ),
                contribuyentes
            )
        assertEquals(listOf("k2", "k1"), titulares.getValue("p1").map { it.contribuyente })
        assertEquals(listOf("000002", "EMPRESA SAC", "d2"), titulares.getValue("p1").first().let { listOf(it.codigo, it.nombre, it.declaracion) })
        assertEquals("QUISPE JUAN", titulares.getValue("p1").last().nombre)
        assertEquals(setOf("p1", "p3"), titulares.keys)
        assertEquals(0, BigDecimal(100).compareTo(titulares.getValue("p3").single().peso))
    }

    @Test
    fun `contribuyentes sum the delta of their compared predios, a shared predio split by share`() {
        val titulares =
            mapOf(
                "p1" to listOf(Titular("k1", "000001", "UNO", "d1", BigDecimal(100))),
                "p2" to listOf(Titular("k1", "000001", "UNO", "d2", BigDecimal(50)), Titular("k2", "000002", "DOS", "d3", BigDecimal(50))),
                "p3" to listOf(Titular("k2", "000002", "DOS", "d4", BigDecimal(100)))
            )
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, "100.00", "1000.00", predio = "p1"),
                    fila(Categoria.SUBVALUADO, "10.01", "200.00", predio = "p2"),
                    // no Δ: not compared, not counted
                    fila(Categoria.SIN_CATASTRO, predio = "p3"),
                    // an omiso has no contribuyente
                    fila(Categoria.OMISO, "50.00", "500.00")
                ),
                titulares
            )
        val porContribuyente = Reportes.contribuyentes(todas).associateBy { it.contribuyente }
        assertEquals(setOf("k1", "k2"), porContribuyente.keys)
        val uno = porContribuyente.getValue("k1")
        assertEquals(2, uno.predios)
        assertEquals(BigDecimal("105.01"), uno.deltaImpuesto)
        assertEquals(BigDecimal("1100.00"), uno.deltaAutoavaluo)
        val dos = porContribuyente.getValue("k2")
        assertEquals(1, dos.predios)
        assertEquals(BigDecimal("5.00"), dos.deltaImpuesto)
        // split to the cent: nothing lost
        assertEquals(BigDecimal("110.01"), uno.deltaImpuesto!! + dos.deltaImpuesto!!)
        assertEquals(listOf("000001", "UNO"), listOf(uno.codigoContribuyente, uno.nombreContribuyente))
    }

    @Test
    fun `contribuyentes filter by code or name and sort by their sums`() {
        val c =
            listOf(
                FilaReporteContribuyente("k1", "000001", "UNO", 1, BigDecimal("10"), BigDecimal("5")),
                FilaReporteContribuyente("k2", "000002", "DOS", 1, BigDecimal("30"), BigDecimal("1")),
                FilaReporteContribuyente("k3", "000003", "TRES", 1, null, null)
            )
        assertEquals(listOf("k2"), Reportes.buscarContribuyentes(c, "dos").map { it.contribuyente })
        assertEquals(listOf("k3"), Reportes.buscarContribuyentes(c, "000003").map { it.contribuyente })
        assertEquals(listOf("k1", "k2", "k3"), Reportes.ordenarContribuyentes(c, Orden.DELTA_IMPUESTO, Direccion.DESC).map { it.contribuyente })
        assertEquals(listOf("k2", "k1", "k3"), Reportes.ordenarContribuyentes(c, Orden.DELTA_AUTOAVALUO, Direccion.DESC).map { it.contribuyente })
        assertEquals(listOf("k3", "k2", "k1"), Reportes.ordenarContribuyentes(c, Orden.CODIGO, Direccion.DESC).map { it.contribuyente })
    }

    // the aggregates

    @Test
    fun `the histogram counts the rows with a delta autoavaluo in fixed tramos, open at both ends`() {
        val todas =
            filas(
                listOf("-150000", "-100000", "-20000", "-1", "0", "9999.99", "10000", "60000", "100000", "250000").map {
                    fila(Categoria.SUBVALUADO, deltaAutoavaluo = it)
                } + fila(Categoria.SIN_CATASTRO)
            )
        val h = Reportes.histograma(todas)
        assertEquals(Reportes.TRAMOS.size + 1, h.size)
        assertNull(h.first().desde)
        assertNull(h.last().hasta)
        assertEquals(10, h.sumOf { it.predios })
        // desde included, hasta not
        assertEquals(1, h.single { it.hasta == BigDecimal("-100000") }.predios)
        assertEquals(1, h.single { it.desde == BigDecimal("-100000") }.predios)
        assertEquals(2, h.single { it.desde == BigDecimal.ZERO }.predios)
        assertEquals(2, h.single { it.desde == BigDecimal("100000") }.predios)
    }

    @Test
    fun `the areas count the terreno differences and the niveles, and add the m2 catastro finds above the DJ`() {
        val todas =
            filas(
                listOf(
                    fila(Categoria.SUBVALUADO, terreno = "100" to "130.5", nivelesComparados = 2, nivelesCatastroMayor = 1, deltaAreaNiveles = "12.25"),
                    fila(Categoria.SUBVALUADO, terreno = "100" to "90", nivelesComparados = 1, nivelesCatastroMayor = 0, deltaAreaNiveles = "0"),
                    fila(Categoria.SUBVALUADO, terreno = "100" to "100")
                )
            )
        val a = Reportes.areas(todas)
        assertEquals(2, a.terrenoConDiferencia)
        assertEquals(1, a.terrenoCatastralMayor)
        assertEquals(3, a.nivelesComparados)
        assertEquals(1, a.prediosConNivelMayor)
        assertEquals(0, BigDecimal("30.5").compareTo(a.deltaM2Terreno))
        assertEquals(0, BigDecimal("12.25").compareTo(a.deltaM2Niveles))
        val vacias = Reportes.areas(filas(listOf(fila(Categoria.OMISO))))
        assertNull(vacias.deltaM2Terreno)
        assertNull(vacias.deltaM2Niveles)
    }

    @Test
    fun `the resumen adds up the corrida by categoria, confianza, sector, uso and the top manzanas`() {
        val brecha =
            listOf(
                fila(Categoria.SUBVALUADO, "10.00", "100.00", sector = "02", manzana = "B", uso = "010101", confianza = "CONFIABLE", codigoCatastro = "K1"),
                fila(Categoria.SUBVALUADO, "20.00", "200.00", sector = "01", manzana = "A", uso = "010101", confianza = "DUDOSO"),
                fila(Categoria.SOBREVALUADO, "-5.00", "-50.00", sector = null, manzana = null, uso = null, confianza = "DUDOSO"),
                fila(Categoria.SIN_CATASTRO, sector = "01", manzana = "A", uso = "020101", confianza = "CONFIABLE")
            )
        val corrida = RegistroCorrida(id = "c1", anio = 2026, estado = "TERMINADA", terminado = "2026-10-20T15:00:00Z", versionCatastro = "v2")
        val todas = filas(brecha, usos = mapOf("10101" to "CASA HABITACIÓN"), decisiones = mapOf("K1" to "A_FISCALIZAR"))
        val r = Reportes.resumen(corrida, brecha, todas, conDecisiones = true, avisos = listOf("uno"))
        assertEquals(listOf("c1", "2026", "v2"), listOf(r.corrida.id, "${r.corrida.anio}", r.corrida.versionCatastro))
        assertEquals(2, r.kpis.subvaluados)
        assertEquals(Categoria.entries.map { it.name }, r.porCategoria.map { it.categoria })
        assertEquals(BigDecimal("30.00"), r.porCategoria.single { it.categoria == "SUBVALUADO" }.deltaImpuesto)
        assertEquals(listOf("CONFIABLE", "DUDOSO", "SIN_LEVANTAR"), r.porConfianza.map { it.confianza })
        assertEquals(listOf(2, 2, 0), r.porConfianza.map { it.predios })
        assertEquals(listOf("01", "02", null), r.porSector.map { it.sector })
        assertEquals(2, r.porSector.first().predios)
        assertEquals(listOf("010101", "020101", null), r.porUso.map { it.uso })
        assertEquals("CASA HABITACIÓN", r.porUso.first().descripcion)
        assertEquals(BigDecimal("300.00"), r.porUso.first().deltaAutoavaluo)
        // the top manzanas by Δ impuesto
        assertEquals(listOf("01" to "A", "02" to "B", null to null), r.porManzana.map { it.sector to it.manzana })
        assertEquals(mapOf("PENDIENTE" to 0, "A_INSPECCIONAR" to 0, "INSPECCIONADO" to 0, "A_FISCALIZAR" to 1, "DESCARTADO" to 0), r.decisiones)
        assertEquals(listOf("uno"), r.avisos)
        assertNull(Reportes.resumen(corrida, brecha, todas, conDecisiones = false, avisos = emptyList()).decisiones)
    }

    @Test
    fun `porManzana keeps the top 20`() {
        val brecha = (1..25).map { fila(Categoria.SUBVALUADO, "$it", sector = "01", manzana = "M$it") }
        val r = Reportes.resumen(RegistroCorrida(id = "c1", anio = 2026), brecha, filas(brecha), conDecisiones = false, avisos = emptyList())
        assertEquals(20, r.porManzana.size)
        assertEquals("M25", r.porManzana.first().manzana)
    }

    @Test
    fun `the catalog has every report, in the issue's order, with its count and sums`() {
        val todas =
            filas(
                listOf(fila(Categoria.SUBVALUADO, "10.00", "100.00"), fila(Categoria.SUBVALUADO, "5.00", "50.00"), fila(Categoria.SIN_CATASTRO)),
                titulares = mapOf()
            )
        val c = Reportes.catalogo(todas, null)
        assertEquals(
            listOf(
                "subvaluados",
                "sobrevaluados",
                "omisos",
                "sin-catastro",
                "sin-levantar",
                "sin-valor",
                "terreno",
                "construccion",
                "contribuyentes",
                "mayores-5-uit"
            ),
            c.map { it.clave }
        )
        val sub = c.first()
        assertEquals(2, sub.predios)
        assertEquals(BigDecimal("15.00"), sub.deltaImpuesto)
        assertEquals(BigDecimal("150.00"), sub.deltaAutoavaluo)
        assertTrue(sub.titulo.isNotBlank() && sub.descripcion.isNotBlank())
        assertNull(c.single { it.clave == "sin-catastro" }.deltaImpuesto)
    }
}
