package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.arbitrios.ContextoArbitrios
import srtm.arbitrios.Llaves
import srtm.arbitrios.OrdenanzaArbitrio
import srtm.arbitrios.ServicioArbitrio
import srtm.impuesto.ImpuestoPredial
import srtm.impuesto.ParametroTributario
import srtm.impuesto.Parametros
import srtm.rentas.ANULADA
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import srtm.rentas.condominio
import java.math.BigDecimal
import java.time.LocalDate

// the brecha's pure rules: the cruce (D7), the categories (D10), the catastro's value split among the secuencias (D12)
// and among condóminos (condominio, deducción kept), the impuesto by contribuyente (mínimo included) and its
// attribution to the predios, the omisos' estimate, and the arbitrios' difference. the impuesto's figures are the
// verified 2026 parameters (Parametros); every figure of the arbitrios ordinance is FICTITIOUS
class BrechaTest {
    private val anio = 2026
    private val parametros = Parametros.predial
    private val hoy = LocalDate.of(2026, 3, 1)

    private fun liquidar(base: String) = ImpuestoPredial.liquidar(anio, BigDecimal(base), parametros).impuestoAnual!!

    private fun predio(
        id: String,
        codigo: String = "P-$id",
        cpu: String? = null,
        sector: String? = null,
        manzana: String? = null,
        lote: String? = null
    ) = Predio(id = id, codigo = codigo, codigoCpu = cpu, sectorCatastral = sector, manzanaCatastral = manzana, lote = lote)

    private fun item(
        municipal: String? = null,
        cpu: String? = null,
        sector: String? = null,
        manzana: String? = null,
        lote: String? = null,
        autoavaluo: String? = "100000.00",
        motivo: String? = null
    ) = ValuacionCatastral(
        codigoMunicipal = municipal,
        codigoCpu = cpu,
        sector = sector,
        manzana = manzana,
        lote = lote,
        autoavaluo = autoavaluo?.let(::BigDecimal),
        motivo = motivo
    )

    // a declaración as srtm stores it: its valor_condominio (its % of the autoavalúo) and valor_afecto (less its
    // deducción) computed, as condominio leaves them
    private fun dj(
        id: String,
        contribuyente: String,
        predio: String,
        autoavaluo: String,
        porcentaje: String = "100",
        secuencia: String = "1",
        deduccion: String? = null,
        area: String? = null
    ) = condominio(
        listOf(
            Declaracion(
                id = id,
                contribuyente = contribuyente,
                predio = predio,
                anio = anio,
                secuenciaUso = secuencia,
                porcentajeCondominio = BigDecimal(porcentaje),
                valorAutoavaluo = BigDecimal(autoavaluo),
                deduccion = deduccion?.let(::BigDecimal),
                areaConstruida = area?.let(::BigDecimal)
            ),
            // a phantom condómino so that its own % stands (condominio gives a sole titular 100 %)
            Declaracion(porcentajeCondominio = BigDecimal.ZERO)
        )
    ).first().copy(condicionPropiedad = null)

    private fun grupo(vararg ds: Declaracion) = condominio(ds.toList())

    // the cruce (D7)

    @Test
    fun `an item crosses by codigo municipal, else by CPU, else by sector, manzana and lote`() {
        val porCodigo = predio("a", codigo = "01-01-0001", cpu = "CPU-X")
        val porCpu = predio("b", codigo = "B", cpu = "CPU-1")
        val porSml = predio("c", codigo = "C", sector = "01", manzana = "A", lote = "7")
        val pares =
            Brecha.cruzar(
                listOf(
                    item(municipal = " 01-01-0001 ", cpu = "CPU-1"),
                    item(municipal = "NO-EXISTE", cpu = "cpu-1"),
                    item(sector = "01", manzana = "a", lote = "7"),
                    item(municipal = "OTRO", cpu = "OTRA", sector = "99", manzana = "Z", lote = "1")
                ),
                listOf(porSml, porCpu, porCodigo)
            )
        assertEquals(listOf("a", "b", "c", null), pares.map { it.second?.id })
    }

    @Test
    fun `a predio crosses once, a later item tries its next key, and without one is an omiso`() {
        val a = predio("a", codigo = "X", cpu = "CPU-1")
        val b = predio("b", codigo = "Y", cpu = "CPU-1")
        val pares = Brecha.cruzar(listOf(item(municipal = "X"), item(municipal = "X", cpu = "CPU-1"), item(municipal = "X")), listOf(a, b))
        assertEquals(listOf("a", "b", null), pares.map { it.second?.id })
    }

    @Test
    fun `among predios of the same key, the one with a declaration of the year goes first`() {
        val sinDj = predio("a", codigo = "A", cpu = "CPU-1")
        val conDj = predio("b", codigo = "B", cpu = "CPU-1")
        assertEquals(
            "b",
            Brecha
                .cruzar(listOf(item(cpu = "CPU-1")), listOf(sinDj, conDj), setOf("b"))
                .single()
                .second
                ?.id
        )
        assertEquals(
            "a",
            Brecha
                .cruzar(listOf(item(cpu = "CPU-1")), listOf(sinDj, conDj))
                .single()
                .second
                ?.id
        )
    }

    // the categories (D10)

    @Test
    fun `within 5 percent both ends included it coincides, a centimo past it does not`() {
        val declarado = BigDecimal("100000.00")
        assertEquals(Categoria.COINCIDE, Brecha.categoria(declarado, BigDecimal("105000.00")))
        assertEquals(Categoria.SUBVALUADO, Brecha.categoria(declarado, BigDecimal("105000.01")))
        assertEquals(Categoria.COINCIDE, Brecha.categoria(declarado, BigDecimal("95000.00")))
        assertEquals(Categoria.SOBREVALUADO, Brecha.categoria(declarado, BigDecimal("94999.99")))
        assertEquals(Categoria.SIN_VALOR, Brecha.categoria(declarado, null))
        assertEquals(Categoria.COINCIDE, Brecha.categoria(BigDecimal.ZERO, BigDecimal.ZERO))
        assertEquals(Categoria.SUBVALUADO, Brecha.categoria(BigDecimal.ZERO, BigDecimal("0.01")))
    }

    // the split (D12)

    @Test
    fun `the catastro's value is split among the secuencias by their declared autoavaluo`() {
        val ds = listOf(dj("1", "c", "p", "30000.00", secuencia = "1"), dj("2", "c", "p", "10000.00", secuencia = "2"))
        assertEquals(listOf(BigDecimal("75000.00"), BigDecimal("25000.00")), Brecha.conCatastro(ds, BigDecimal("100000.00")).map { it.valorAutoavaluo })
    }

    @Test
    fun `without declared autoavaluo by area construida, without that in equal parts, to the cent`() {
        val porArea = listOf(dj("1", "c", "p", "0", secuencia = "1", area = "30"), dj("2", "c", "p", "0", secuencia = "2", area = "90"))
        assertEquals(listOf(BigDecimal("25000.00"), BigDecimal("75000.00")), Brecha.conCatastro(porArea, BigDecimal("100000.00")).map { it.valorAutoavaluo })
        val iguales = listOf(dj("1", "c", "p", "0", secuencia = "1"), dj("2", "c", "p", "0", secuencia = "2"), dj("3", "c", "p", "0", secuencia = "3"))
        assertEquals(
            listOf(BigDecimal("33333.33"), BigDecimal("33333.33"), BigDecimal("33333.34")),
            Brecha.conCatastro(iguales, BigDecimal("100000.00")).map { it.valorAutoavaluo }
        )
    }

    @Test
    fun `condominos keep their percent and their deduccion`() {
        val ds = grupo(dj("1", "a", "p", "50000.00", porcentaje = "60"), dj("2", "b", "p", "50000.00", porcentaje = "40", deduccion = "10000.00"))
        val nuevas = Brecha.conCatastro(ds, BigDecimal("200000.00"))
        assertEquals(listOf(BigDecimal("120000.00"), BigDecimal("80000.00")), nuevas.map { it.valorCondominio })
        assertEquals(listOf(BigDecimal("120000.00"), BigDecimal("70000.00")), nuevas.map { it.valorAfecto })
    }

    // the impuesto

    @Test
    fun `each condomino pays on his part of the catastro's value, less his deduccion`() {
        val ds = grupo(dj("1", "a", "p", "50000.00", porcentaje = "60"), dj("2", "b", "p", "50000.00", porcentaje = "40", deduccion = "10000.00"))
        val i = Brecha.impuestos(anio, ds, mapOf("p" to BigDecimal("200000.00")), parametros)
        assertEquals(ImpuestoDelContribuyente(liquidar("30000.00"), liquidar("120000.00")), i.porContribuyente["a"])
        assertEquals(ImpuestoDelContribuyente(liquidar("10000.00"), liquidar("70000.00")), i.porContribuyente["b"])
        val p = i.porPredio.getValue("p")
        assertEquals(liquidar("30000.00") + liquidar("10000.00"), p.declarado)
        assertEquals(liquidar("120000.00") - liquidar("30000.00") + liquidar("70000.00") - liquidar("10000.00"), p.delta)
    }

    @Test
    fun `a small predio pays the minimo with either value`() {
        val i = Brecha.impuestos(anio, listOf(dj("1", "a", "p", "1000.00")), mapOf("p" to BigDecimal("2000.00")), parametros)
        val minimo = ImpuestoPredial.liquidar(anio, BigDecimal("1000.00"), parametros).minimo!!
        assertEquals(ImpuestoDelContribuyente(minimo, minimo), i.porContribuyente["a"])
        assertEquals(BigDecimal("0.00"), i.porPredio.getValue("p").delta)
    }

    @Test
    fun `the contribuyente's difference falls on its predios and adds up, whatever the rounding`() {
        // a has three predios (one the catastro did not value) and shares a fourth with b
        val compartido = grupo(dj("4a", "a", "p4", "40000.00", porcentaje = "50"), dj("4b", "b", "p4", "40000.00", porcentaje = "50"))
        val ds =
            listOf(dj("1", "a", "p1", "80000.00"), dj("2", "a", "p2", "120000.00"), dj("3", "a", "p3", "33333.33")) + compartido +
                dj("5", "b", "p5", "7777.77")
        val catastro = mapOf("p1" to BigDecimal("100000.01"), "p2" to BigDecimal("90000.00"), "p4" to BigDecimal("123456.78"), "p5" to BigDecimal("15000.00"))
        val i = Brecha.impuestos(anio, ds, catastro, parametros)
        assertEquals(i.porContribuyente.values.sumOf { it.delta }, i.porPredio.values.sumOf { it.delta })
        assertEquals(i.porContribuyente.values.sumOf { it.declarado }, i.porPredio.values.sumOf { it.declarado })
        // the one the catastro did not value moves nothing
        assertEquals(BigDecimal("0.00"), i.porPredio.getValue("p3").delta)
        // a's own by hand: 100000.01 + 90000.00 + 33333.33 (not valued) + half of 123456.78 against 253333.33 declared
        val a = i.porContribuyente.getValue("a")
        assertEquals(liquidar("285061.73") - liquidar("253333.33"), a.delta)
    }

    // medir: the rows of a corrida

    private val propio = predio("p1", codigo = "01-01-0001", sector = "01", manzana = "A", lote = "1")
    private val sinCatastro = predio("p2", codigo = "01-01-0002", sector = "01", manzana = "A", lote = "2")
    private val sinDj = predio("p3", codigo = "01-02-0001", sector = "02")

    private fun padron(vararg ds: Declaracion) = Padron(listOf(propio, sinCatastro, sinDj), ds.toList())

    @Test
    fun `one row per catastro item and per padron predio the catastro lacks`() {
        val m =
            Brecha.medir(
                anio,
                listOf(
                    item(municipal = "01-01-0001", autoavaluo = "120000.00"),
                    item(municipal = "NUEVO", sector = "03", manzana = "B", lote = "4", autoavaluo = "50000.00"),
                    item(municipal = "01-02-0001", autoavaluo = null, motivo = "Sin arancel")
                ),
                padron(dj("1", "a", "p1", "100000.00"), dj("2", "a", "p2", "20000.00"), dj("3", "b", "p3", "1.00").copy(estado = ANULADA)),
                parametros,
                null,
                hoy
            )
        assertEquals(emptyList<String>(), m.faltan)
        val porCodigo = m.filas.associateBy { it.codigoPredio ?: it.codigoCatastro }
        val sub = porCodigo.getValue("01-01-0001")
        assertEquals(Categoria.SUBVALUADO.name, sub.categoria)
        assertEquals(BigDecimal("100000.00"), sub.autoavaluoDeclarado)
        assertEquals(BigDecimal("20000.00"), sub.deltaAutoavaluo)
        assertEquals(sub.impuestoDeclarado!! + sub.deltaImpuesto!!, sub.impuestoCatastro)

        val omiso = porCodigo.getValue("NUEVO")
        assertEquals(Categoria.OMISO.name, omiso.categoria)
        assertNull(omiso.predio)
        assertEquals(true, omiso.estimado)
        assertEquals(liquidar("50000.00"), omiso.deltaImpuesto)
        assertEquals("03", omiso.sector)

        // a predio whose only declaración of the year is annulled is not in the padrón: an omiso that keeps its predio,
        // not valued, so no estimate
        val anulado = porCodigo.getValue("01-02-0001")
        assertEquals(Categoria.OMISO.name, anulado.categoria)
        assertEquals("p3", anulado.predio)
        assertEquals(false, anulado.estimado)
        assertEquals("Sin arancel", anulado.motivo)

        val falta = porCodigo.getValue("01-01-0002")
        assertEquals(Categoria.SIN_CATASTRO.name, falta.categoria)
        assertEquals(BigDecimal("20000.00"), falta.autoavaluoDeclarado)
        assertNull(falta.deltaImpuesto)
        assertEquals(4, m.filas.size)
        // no arbitrios context: said, not failed
        assertTrue(m.avisos.single().startsWith("Δ arbitrios no calculado"), m.avisos.toString())
        assertTrue(m.filas.all { it.deltaArbitrios == null })
    }

    @Test
    fun `a predio the catastro could not value is SIN_VALOR with its motivo and no difference`() {
        val m =
            Brecha.medir(
                anio,
                listOf(item(municipal = "01-01-0001", autoavaluo = null, motivo = "Celda .....")),
                padron(dj("1", "a", "p1", "1.00")),
                parametros,
                null,
                hoy
            )
        val fila = m.filas.first { it.predio == "p1" }
        assertEquals(Categoria.SIN_VALOR.name, fila.categoria)
        assertEquals("Celda .....", fila.motivo)
        assertNull(fila.deltaImpuesto)
        assertNull(fila.deltaAutoavaluo)
    }

    @Test
    fun `without the year's parameters there is no row, and faltan names them`() {
        val m = Brecha.medir(2099, listOf(item(municipal = "01-01-0001")), padron(dj("1", "a", "p1", "1.00").copy(anio = 2099)), parametros, null, hoy)
        assertEquals(listOf("UIT 2099"), m.faltan)
        assertTrue(m.filas.isEmpty())
    }

    // the arbitrios (fictitious ordinance)

    private val usos = listOf(UsoPredio("010101", "RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"), UsoPredio("020501", "COMERCIAL", "TIENDAS", "TIENDA"))

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
        texto = texto
    )

    private fun contexto(
        ordenanza: OrdenanzaArbitrio? =
            OrdenanzaArbitrio(id = "o", anio = 2026, acuerdoRatificacion = "AC 0 (ficticio)", fechaRatificacion = LocalDate.of(2025, 12, 1))
    ) = ContextoArbitrios(
        anio,
        ordenanza,
        listOf(ServicioArbitrio(id = "srv-LIMPIEZA", codigo = "LIMPIEZA", nombre = "Limpieza", orden = 1, vigenciaDesde = LocalDate.of(2026, 1, 1))),
        parametros +
            listOf(
                param(Llaves.ARBITRIO_ZONA, "01", texto = "Z1"),
                param(Llaves.ARBITRIO_USO, "0101", texto = "CASA"),
                param(Llaves.ARBITRIO_USO, "0205", texto = "COMERCIO"),
                param(Llaves.TASA_ARBITRIO, "LIMPIEZA:Z1:CASA", valor = "8.50"),
                param(Llaves.TASA_ARBITRIO, "LIMPIEZA:Z1:COMERCIO", valor = "12.00")
            ),
        usos
    )

    private val casa = dj("1", "a", "p1", "100000.00").copy(claseUso = "RESIDENCIAL", subClaseUso = "UNIFAMILIAR", uso = "CASA HABITACIÓN")

    @Test
    fun `the arbitrios' difference is a year with the catastro's uso minus a year as declared`() {
        val m = Brecha.medir(anio, listOf(item(municipal = "01-01-0001").copy(uso = "020501")), padron(casa), parametros, contexto(), hoy, null)
        // 12 months of LIMPIEZA at COMERCIO's tasa instead of CASA's
        assertEquals(BigDecimal("42.00"), m.filas.first { it.predio == "p1" }.deltaArbitrios)
        assertEquals(emptyList<String>(), m.avisos)
    }

    @Test
    fun `a year without its ordinance leaves no arbitrios difference, and says why`() {
        val m = Brecha.medir(anio, listOf(item(municipal = "01-01-0001")), padron(casa), parametros, contexto(ordenanza = null), hoy, null)
        assertNull(m.filas.first { it.predio == "p1" }.deltaArbitrios)
        assertEquals(listOf("Δ arbitrios no calculado: faltan Ordenanza de arbitrios 2026"), m.avisos)
    }

    @Test
    fun `a predio that cannot be determined has no arbitrios difference, and is counted`() {
        val sinUso = casa.copy(claseUso = null, subClaseUso = null, uso = null)
        val m = Brecha.medir(anio, listOf(item(municipal = "01-01-0001")), padron(sinUso), parametros, contexto(), hoy, null)
        assertNull(m.filas.first { it.predio == "p1" }.deltaArbitrios)
        assertTrue(m.avisos.single().startsWith("Δ arbitrios sin calcular en 1 predios: Uso del catálogo"), m.avisos.toString())
    }

    // the panel and the lotes

    @Test
    fun `the panel counts by categoria and sector and adds up the differences`() {
        val corrida = RegistroCorrida(id = "c", anio = anio, terminado = "2026-10-19T10:00:00Z", versionCatastro = "valuacion-v1")
        val filas =
            listOf(
                FilaBrecha(
                    categoria = "SUBVALUADO",
                    sector = "01",
                    autoavaluoDeclarado = BigDecimal("100.00"),
                    autoavaluoCatastro = BigDecimal("200.00"),
                    deltaAutoavaluo = BigDecimal("100.00"),
                    deltaImpuesto = BigDecimal("5.00"),
                    deltaArbitrios = BigDecimal("2.00")
                ),
                FilaBrecha(
                    categoria = "OMISO",
                    sector = "01",
                    autoavaluoCatastro = BigDecimal("50.00"),
                    deltaAutoavaluo = BigDecimal("50.00"),
                    deltaImpuesto = BigDecimal("33.00")
                ),
                FilaBrecha(categoria = "SIN_CATASTRO", sector = "02", autoavaluoDeclarado = BigDecimal("70.00")),
                FilaBrecha(
                    categoria = "COINCIDE",
                    sector = null,
                    autoavaluoDeclarado = BigDecimal("10.00"),
                    autoavaluoCatastro = BigDecimal("10.00"),
                    deltaAutoavaluo = BigDecimal("0.00"),
                    deltaImpuesto = BigDecimal("0.00")
                )
            )
        val p = Brecha.panel(corrida, filas)
        assertEquals(CorridaDelPanel("c", "2026-10-19T10:00:00Z", "valuacion-v1"), p.corrida)
        assertEquals(
            Kpis(
                prediosCatastro = 3,
                prediosPadron = 3,
                omisos = 1,
                subvaluados = 1,
                sobrevaluados = 0,
                coinciden = 1,
                sinCatastro = 1,
                sinValor = 0,
                autoavaluoDeclarado = BigDecimal("180.00"),
                autoavaluoCatastro = BigDecimal("260.00"),
                deltaAutoavaluo = BigDecimal("150.00"),
                deltaImpuesto = BigDecimal("38.00"),
                deltaArbitrios = BigDecimal("2.00")
            ),
            p.kpis
        )
        assertEquals(Categoria.entries.map { it.name }, p.porCategoria.map { it.categoria })
        assertEquals(PorCategoria("OMISO", 1, BigDecimal("50.00"), BigDecimal("33.00"), null), p.porCategoria.first())
        assertEquals(
            listOf(PorSector("01", 2, BigDecimal("38.00"), BigDecimal("2.00"), 1, 1), PorSector("02", 1, BigDecimal("0.00"), null, 0, 0)),
            p.porSector.take(2)
        )
        assertNull(p.porSector.last().sector)
    }

    @Test
    fun `the lotes are a FeatureCollection of the rows with a polygon`() {
        val cuadrado =
            mapOf(
                "type" to "Polygon",
                "coordinates" to listOf(listOf(listOf(-75.2, -10.9), listOf(-75.1, -10.9), listOf(-75.1, -10.8), listOf(-75.2, -10.9)))
            )
        val multi = mapOf("type" to "MultiPolygon", "coordinates" to listOf(cuadrado["coordinates"]))
        assertEquals(cuadrado, Brecha.poligono(multi))
        assertNull(Brecha.poligono(mapOf("type" to "Point", "coordinates" to listOf(1, 2))))
        val geo =
            Brecha.lotes(
                listOf(
                    FilaBrecha(id = "f1", categoria = "OMISO", codigoCatastro = "NUEVO", deltaImpuesto = BigDecimal("1.00"), loteGeom = cuadrado),
                    FilaBrecha(id = "f2", categoria = "SIN_CATASTRO", codigoPredio = "01-01-0002")
                )
            )
        assertEquals("FeatureCollection", geo["type"])
        val feature = (geo["features"] as List<*>).single() as Map<*, *>
        assertEquals(mapOf("categoria" to "OMISO", "codigo" to "NUEVO", "deltaImpuesto" to BigDecimal("1.00")), feature["properties"])
        assertEquals(cuadrado, feature["geometry"])
    }
}
