package srtm.visor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.brecha.Categoria
import srtm.brecha.FilaBrecha
import srtm.brecha.NivelBrecha
import srtm.brecha.RegistroCorrida
import srtm.reportes.LoteJson
import srtm.reportes.Reportes
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

// the visor de brechas (#260) as pure functions over a corrida's rows: the panel catastro-ui's Brechas shows (its
// BrechasService's shape and rules), the filters and orders of its table, the lotes and the niveles
class VisorTest {
    private fun d(v: String?) = v?.let(::BigDecimal)

    private var siguiente = 0

    private fun fila(
        categoria: Categoria,
        deltaImpuesto: String? = null,
        deltaAutoavaluo: String? = null,
        codigoCatastro: String? = "auto",
        sector: String? = "01",
        confianza: String? = "CONFIABLE",
        conLote: Boolean = true
    ): FilaBrecha {
        val n = siguiente++
        return FilaBrecha(
            id = "b$n",
            corrida = "c1",
            codigoPredio = "P-$n",
            codigoCatastro = if (codigoCatastro == "auto") "CM-$n" else codigoCatastro,
            categoria = categoria.name,
            sector = sector,
            manzana = "A",
            lote = "$n",
            deltaImpuesto = d(deltaImpuesto),
            deltaAutoavaluo = d(deltaAutoavaluo),
            confianza = confianza,
            conLote = conLote,
            orden = n
        )
    }

    private val corrida = RegistroCorrida(id = "c1", anio = 2026, estado = "TERMINADA", terminado = "2026-10-20T15:00:00Z", versionCatastro = "v1")

    // 5 UIT
    private val umbral = BigDecimal(27500)

    private val brecha =
        listOf(
            fila(Categoria.SUBVALUADO, "300", "50000", sector = "02"),
            fila(Categoria.SUBVALUADO, "100", "20000", confianza = "DUDOSO"),
            fila(Categoria.SOBREVALUADO, "-50", "-30000", conLote = false),
            fila(Categoria.OMISO, "200", "40000", confianza = "DUDOSO", sector = "03"),
            // no ficha: it cannot be decided
            fila(Categoria.SIN_CATASTRO, codigoCatastro = null, confianza = null, sector = null, conLote = false)
        )

    private val decisiones = mapOf("CM-0" to "A_INSPECCIONAR", "CM-3" to "A_FISCALIZAR")

    private fun filas(conDecisiones: Boolean = true) =
        Visor.filas(
            Reportes.filas(
                brecha,
                null,
                emptyMap(),
                decisiones.takeIf {
                    conDecisiones
                }
            ),
            conDecisiones,
            umbral
        )

    @Test
    fun `a row carries its decision, PENDIENTE without one, and whether its delta is above 5 UIT`() {
        val f = filas()
        assertEquals(listOf("A_INSPECCIONAR", "PENDIENTE", "PENDIENTE", "A_FISCALIZAR", "PENDIENTE"), f.map { it.estado })
        assertEquals(listOf(true, false, true, true, null), f.map { it.supera5Uit })
        assertEquals(listOf(true, true, false, true, false), f.map { it.conGeometria })
        // catastro unread: no estado at all, never a made-up PENDIENTE
        assertTrue(filas(conDecisiones = false).all { it.estado == null })
        // without the UIT nothing is above it
        assertTrue(Visor.filas(Reportes.filas(brecha, null, emptyMap(), decisiones), true, null).all { it.supera5Uit == null })
    }

    @Test
    fun `the panel counts as catastro's does`() {
        val p = Visor.panel(corrida, filas(), true, listOf("un aviso"))
        val k = p.kpis
        assertEquals(5, k.fichas)
        assertEquals(0, BigDecimal(550).compareTo(k.deltaImpuesto))
        assertEquals(0, BigDecimal(250).compareTo(k.deltaImpuestoConfiable))
        assertEquals(0, BigDecimal(200).compareTo(k.omisosEstimado))
        assertEquals(3, k.supera5Uit)
        // the SIN_CATASTRO row has no ficha: not pending, counted apart
        assertEquals(2, k.pendientes)
        assertEquals(1, k.sinFicha)
        assertEquals(3, k.conGeometria)
        assertEquals(Categoria.entries.map { it.name }, p.porCategoria.map { it.clave })
        val sub = p.porCategoria.single { it.clave == "SUBVALUADO" }
        assertEquals(2, sub.fichas)
        assertEquals(0, BigDecimal(400).compareTo(sub.deltaImpuesto))
        // every confianza, and the rows without one as a null clave
        assertEquals(listOf("CONFIABLE", "DUDOSO", "SIN_LEVANTAR", null), p.porConfianza.map { it.clave })
        assertEquals(listOf(2, 2, 0, 1), p.porConfianza.map { it.fichas })
        assertEquals(
            listOf("PENDIENTE" to 2, "A_INSPECCIONAR" to 1, "INSPECCIONADO" to 0, "A_FISCALIZAR" to 1, "DESCARTADO" to 0),
            p.porEstado.map { it.clave to it.fichas }
        )
        // by Δ, the largest first; a row without sector is in none
        assertEquals(listOf("02", "03", "01"), p.topSectores.map { it.sector })
        assertEquals(0, BigDecimal(50).compareTo(p.topSectores.last().deltaImpuesto))
        assertEquals("c1", p.corrida.id)
        assertEquals(listOf("un aviso"), p.avisos)
        // catastro unread: no pendientes at all, never a count of rows nobody decided on
        assertEquals(null, Visor.panel(corrida, filas(conDecisiones = false), false, emptyList()).kpis.pendientes)
    }

    @Test
    fun `the panel keeps the ten sectores of largest delta`() {
        val muchas = (1..12).map { fila(Categoria.SUBVALUADO, "$it", sector = "S%02d".format(it)) }
        val p = Visor.panel(corrida, Visor.filas(Reportes.filas(muchas, null, emptyMap(), emptyMap()), true, umbral), true, emptyList())
        assertEquals((12 downTo 3).map { "S%02d".format(it) }, p.topSectores.map { it.sector })
    }

    @Test
    fun `the json names are catastro's, in snake case`() {
        val json = JsonMapper.builder().build()
        val p = json.readTree(json.writeValueAsString(Visor.panel(corrida, filas(), true, emptyList())))
        assertEquals(
            setOf(
                "fichas",
                "delta_impuesto",
                "delta_impuesto_confiable",
                "omisos_estimado",
                "supera_5_uit",
                "pendientes",
                "con_geometria",
                "sin_ficha",
                "pricos"
            ),
            p["kpis"].propertyNames().toSet()
        )
        assertEquals(setOf("corrida", "kpis", "por_categoria", "por_confianza", "por_estado", "top_sectores", "avisos"), p.propertyNames().toSet())
        assertEquals(setOf("clave", "fichas", "delta_impuesto"), p["por_categoria"][0].propertyNames().toSet())
        assertEquals(setOf("sector", "fichas", "delta_impuesto"), p["top_sectores"][0].propertyNames().toSet())
        assertEquals("version_catastro" in p["corrida"].propertyNames().toSet(), true)
        val f = json.readTree(json.writeValueAsString(filas()[0]))
        for (campo in listOf("codigo_catastro", "delta_impuesto", "supera_5_uit", "estado", "con_geometria", "delta_arbitrios")) {
            assertTrue(f.has(campo), "$campo: $f")
        }
    }

    @Test
    fun `the filters hold together, the lists by any of their values`() {
        val f = filas()

        fun ids(filtro: FiltroVisor) = f.filter(filtro::admite).map { it.id }
        assertEquals(listOf("b0", "b1"), ids(FiltroVisor(categorias = setOf("SUBVALUADO"))))
        assertEquals(listOf("b0", "b1", "b3"), ids(FiltroVisor(categorias = setOf("SUBVALUADO", "OMISO"))))
        assertEquals(listOf("b1", "b3"), ids(FiltroVisor(confianzas = setOf("DUDOSO"))))
        assertEquals(listOf("b1", "b2"), ids(FiltroVisor(sector = "1")))
        assertEquals(listOf("b0", "b3"), ids(FiltroVisor(estados = setOf("A_INSPECCIONAR", "A_FISCALIZAR"))))
        assertEquals(listOf("b0", "b2", "b3"), ids(FiltroVisor(supera5Uit = true)))
        assertEquals(listOf("b1", "b4"), ids(FiltroVisor(supera5Uit = false)))
        // |Δ impuesto|: the sobrevaluado's -50 is in, the row without a Δ is out
        assertEquals(listOf("b0", "b1", "b2", "b3"), ids(FiltroVisor(minDelta = BigDecimal(50))))
        assertEquals(listOf("b0"), ids(FiltroVisor(categorias = setOf("SUBVALUADO"), minDelta = BigDecimal(150))))
    }

    @Test
    fun `the orders are catastro's, a row without delta last`() {
        val f = filas()
        assertEquals(listOf("b0", "b3", "b1", "b2", "b4"), f.sortedWith(OrdenVisor.DELTA_DESC.comparador).map { it.id })
        assertEquals(listOf("b2", "b1", "b3", "b0", "b4"), f.sortedWith(OrdenVisor.DELTA_ASC.comparador).map { it.id })
        // by código de catastro, the one without it last
        assertEquals(listOf("b0", "b1", "b2", "b3", "b4"), f.sortedWith(OrdenVisor.CODIGO.comparador).map { it.id })
        assertEquals(OrdenVisor.DELTA_ASC, OrdenVisor.de("delta_asc"))
        assertNull(OrdenVisor.de("otro"))
    }

    @Test
    fun `the lotes are the rows that match with a geometry, with the map's properties`() {
        val geometria = mapOf("type" to "Polygon", "coordinates" to listOf(listOf(listOf(-75.0, -12.0), listOf(-75.1, -12.0), listOf(-75.0, -12.1))))
        val lotes = mapOf("b0" to geometria, "b1" to geometria, "b3" to geometria)
        val fc = Visor.lotes(filas(), lotes, FiltroVisor(confianzas = setOf("CONFIABLE", "DUDOSO")))
        assertEquals("FeatureCollection", fc["type"])

        @Suppress("UNCHECKED_CAST")
        val features = fc["features"] as List<Map<String, Any?>>
        assertEquals(listOf("b0", "b1", "b3"), features.map { it["id"] })
        assertEquals(geometria, features[0]["geometry"])

        @Suppress("UNCHECKED_CAST")
        val p = features[0]["properties"] as Map<String, Any?>
        assertEquals("SUBVALUADO", p["categoria"])
        assertEquals("CONFIABLE", p["confianza"])
        assertEquals(0, BigDecimal(300).compareTo(p["delta_impuesto"] as BigDecimal))
        assertEquals("A_INSPECCIONAR", p["estado"])
        assertEquals(true, p["supera_5_uit"])
        assertEquals("CM-0", p["codigo_catastro"])
        assertEquals(1, (Visor.lotes(filas(), lotes, FiltroVisor(estados = setOf("A_FISCALIZAR")))["features"] as List<*>).size)
    }

    @Test
    fun `the predios of a lote are the rows drawn with its feature's geometry, the name only when it may be shown (#265)`() {
        val lote =
            listOf(
                FilaBrecha(
                    id = "x1",
                    predio = "p1",
                    codigoPredio = "0101-7-2",
                    codigoCatastro = "CM-7",
                    categoria = "SUBVALUADO",
                    sector = "01",
                    manzana = "A",
                    lote = "7",
                    deltaImpuesto = d("10"),
                    conLote = true
                ),
                FilaBrecha(id = "x0", codigoCatastro = "CM-7B", categoria = "OMISO", sector = "1", manzana = "a", lote = "07", conLote = true),
                // no lote: no feature, though its sector, manzana and lote are the same
                FilaBrecha(id = "x2", predio = "p2", codigoPredio = "0101-7-1", categoria = "SIN_CATASTRO", sector = "01", manzana = "A", lote = "7"),
                // the same sector, manzana and lote, drawn apart: another feature
                FilaBrecha(
                    id = "x3",
                    predio = "p3",
                    codigoPredio = "0101-7-3",
                    categoria = "COINCIDE",
                    sector = "01",
                    manzana = "A",
                    lote = "7",
                    conLote = true
                )
            )
        val geometrias = mapOf("x1" to LoteJson(G7), "x0" to LoteJson(G7), "x3" to LoteJson(G8))
        val titulares = mapOf("p1" to listOf(srtm.reportes.Titular("k1", "000001", "FLORES OTINIANO JUNIOR", "d1", BigDecimal(100))))
        val reportes = Reportes.filas(lote, titulares, emptyMap(), mapOf("CM-7" to "A_INSPECCIONAR"))
        val visor = Visor.filas(reportes, true, umbral)
        // /lotes draws x1 and x0 with the same geometry, x3 with its own
        val fc = Visor.lotes(visor, geometrias, FiltroVisor())

        @Suppress("UNCHECKED_CAST")
        val codigos = (fc["features"] as List<Map<String, Any?>>).map { (it["properties"] as Map<String, Any?>)["codigo"] }
        assertEquals(listOf("0101-7-2", "CM-7B", "0101-7-3"), codigos)
        val p = Visor.prediosDelLote(visor, reportes, geometrias, "0101-7-2", conNombre = true)!!
        // by codigo_predio, the omiso without one last
        assertEquals(listOf("x1", "x0"), p.map { it.id })
        val conDj = p[0]
        assertEquals("p1", conDj.predio)
        assertEquals("d1", conDj.declaracion)
        assertEquals("k1", conDj.contribuyente)
        assertEquals("FLORES OTINIANO JUNIOR", conDj.contribuyenteNombre)
        assertEquals("A_INSPECCIONAR", conDj.estado)
        assertNull(p[1].predio)
        assertNull(p[1].contribuyente)
        assertEquals("PENDIENTE", p[1].estado)
        // the omiso's feature is the same lote
        assertEquals(p, Visor.prediosDelLote(visor, reportes, geometrias, "CM-7B", conNombre = true))
        assertNull(Visor.prediosDelLote(visor, reportes, geometrias, "0101-7-2", conNombre = false)!![0].contribuyenteNombre)
        assertEquals("k1", Visor.prediosDelLote(visor, reportes, geometrias, "0101-7-2", conNombre = false)!![0].contribuyente)
        assertEquals(listOf("x3"), Visor.prediosDelLote(visor, reportes, geometrias, "0101-7-3", conNombre = true)!!.map { it.id })
        // a code of a row without a lote, or of none, is no feature
        assertNull(Visor.prediosDelLote(visor, reportes, geometrias, "0101-7-1", conNombre = true))
        assertNull(Visor.prediosDelLote(visor, reportes, geometrias, "NO-EXISTE", conNombre = true))
        val json = JsonMapper.builder().build().writeValueAsString(conDj)
        assertEquals(
            """{"id":"x1","predio":"p1","codigo_predio":"0101-7-2","declaracion":"d1","contribuyente":"k1","contribuyente_nombre":"FLORES OTINIANO JUNIOR",""" +
                """"categoria":"SUBVALUADO","confianza":null,"delta_impuesto":10,"estado":"A_INSPECCIONAR","prico":false}""",
            json
        )
    }

    // #273
    @Test
    fun `a predio is a PRICO by its principal titular, filtered and summed, the mark only to who may read it`() {
        val brecha =
            listOf(
                FilaBrecha(id = "q1", predio = "p1", codigoPredio = "P-1", codigoCatastro = "CM-1", categoria = "SUBVALUADO", deltaImpuesto = d("300")),
                FilaBrecha(id = "q2", predio = "p2", codigoPredio = "P-2", codigoCatastro = "CM-2", categoria = "SUBVALUADO", deltaImpuesto = d("100")),
                // a PRICO as a second titular: the predio is not a PRICO's
                FilaBrecha(id = "q3", predio = "p3", codigoPredio = "P-3", codigoCatastro = "CM-3", categoria = "SOBREVALUADO", deltaImpuesto = d("-40")),
                FilaBrecha(id = "q4", codigoCatastro = "CM-4", categoria = "OMISO", deltaImpuesto = d("50"))
            )
        val titulares =
            Reportes.titulares(
                listOf(
                    srtm.rentas.Declaracion(id = "d1", contribuyente = "k1", predio = "p1", anio = 2026, porcentajeCondominio = BigDecimal(100)),
                    srtm.rentas.Declaracion(id = "d2", contribuyente = "k2", predio = "p2", anio = 2026, porcentajeCondominio = BigDecimal(100)),
                    srtm.rentas.Declaracion(id = "d3", contribuyente = "k2", predio = "p3", anio = 2026, porcentajeCondominio = BigDecimal(60)),
                    srtm.rentas.Declaracion(id = "d4", contribuyente = "k1", predio = "p3", anio = 2026, porcentajeCondominio = BigDecimal(40))
                ),
                mapOf("k1" to srtm.rentas.Contribuyente(id = "k1", codigo = "000001"), "k2" to srtm.rentas.Contribuyente(id = "k2", codigo = "000002")),
                pricos = setOf("k1")
            )
        val filas = Visor.filas(Reportes.filas(brecha, titulares, emptyMap(), null), false, umbral)
        assertEquals(listOf(true, false, false, false), filas.map { it.pricoDelTitular })
        // kept for everyone without the mark
        assertTrue(filas.none { it.prico })

        fun ids(filtro: FiltroVisor) = filas.filter(filtro::admite).map { it.id }
        assertEquals(listOf("q1"), ids(FiltroVisor(prico = true)))
        assertEquals(listOf("q2", "q3", "q4"), ids(FiltroVisor(prico = false)))
        assertEquals(listOf("q1"), ids(FiltroVisor(prico = true, categorias = setOf("SUBVALUADO"))))
        assertEquals(emptyList<String>(), ids(FiltroVisor(prico = true, categorias = setOf("OMISO"))))
        // who may not read the mark sees no PRICO
        assertEquals(emptyList<String>(), ids(FiltroVisor(prico = true, verPrico = false)))
        assertEquals(listOf("q1", "q2", "q3", "q4"), ids(FiltroVisor(prico = false, verPrico = false)))
        val k = Visor.panel(corrida, filas, false, emptyList()).kpis.pricos!!
        assertEquals(1, k.predios)
        assertEquals(0, BigDecimal(300).compareTo(k.deltaImpuesto))
        assertEquals(listOf(true, false, false, false), Visor.paraElUsuario(filas, conNombre = false, conPrico = true).map { it.prico })
        assertTrue(Visor.paraElUsuario(filas, conNombre = true, conPrico = false).none { it.prico })
        val json = JsonMapper.builder().build()
        val fila = json.readTree(json.writeValueAsString(Visor.paraElUsuario(filas, conNombre = false, conPrico = true)[0]))
        assertTrue(fila["prico"].asBoolean())
        assertFalse(fila.has("prico_del_titular"))
        val geometria = mapOf("type" to "Point", "coordinates" to listOf(-75.0, -12.0))

        @Suppress("UNCHECKED_CAST")
        fun pricosDelMapa(filtro: FiltroVisor) =
            (Visor.lotes(filas, mapOf("q1" to geometria, "q2" to geometria), filtro)["features"] as List<Map<String, Any?>>).map {
                (it["properties"] as Map<String, Any?>)["prico"]
            }
        assertEquals(listOf(true, false), pricosDelMapa(FiltroVisor()))
        assertEquals(listOf(false, false), pricosDelMapa(FiltroVisor(verPrico = false)))
        assertEquals(listOf(true), pricosDelMapa(FiltroVisor(prico = true)))
        val reportes = Reportes.filas(brecha, titulares, emptyMap(), null)
        val lote = mapOf("q1" to geometria, "q2" to geometria)
        assertEquals(listOf(true, false), Visor.prediosDelLote(filas, reportes, lote, "P-1", conNombre = false, conPrico = true)!!.map { it.prico })
        assertEquals(listOf(false, false), Visor.prediosDelLote(filas, reportes, lote, "P-1", conNombre = false, conPrico = false)!!.map { it.prico })
    }

    @Test
    fun `a row of predios gives its contribuyente, and its name only to who may read it (#270)`() {
        val brecha =
            listOf(
                FilaBrecha(id = "y1", predio = "p1", codigoPredio = "P-1", codigoCatastro = "CM-1", categoria = "SUBVALUADO"),
                FilaBrecha(id = "y2", codigoCatastro = "CM-2", categoria = "OMISO")
            )
        val titulares = mapOf("p1" to listOf(srtm.reportes.Titular("k1", "000001", "FLORES OTINIANO JUNIOR", "d1", BigDecimal(100))))
        val filas = Visor.filas(Reportes.filas(brecha, titulares, emptyMap(), null), false, umbral)
        // kept for everyone without the name
        assertEquals(listOf("k1", null), filas.map { it.contribuyente })
        assertTrue(filas.all { it.contribuyenteNombre == null })
        val conNombre = Visor.paraElUsuario(filas, conNombre = true)
        assertEquals(listOf("FLORES OTINIANO JUNIOR", null), conNombre.map { it.contribuyenteNombre })
        assertEquals(filas, Visor.paraElUsuario(filas, conNombre = false))
        val json = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(conNombre[0]))
        assertEquals("k1", json["contribuyente"].asString())
        assertEquals("FLORES OTINIANO JUNIOR", json["contribuyente_nombre"].asString())
        // the name kept for the response is never written
        assertFalse(
            JsonMapper
                .builder()
                .build()
                .writeValueAsString(filas[0])
                .contains("FLORES")
        )
    }

    @Test
    fun `a lote kept as its json goes into the response as it is (#263)`() {
        val json = """{"type":"Polygon","coordinates":[[[-75.0,-12.0],[-75.1,-12.0],[-75.0,-12.1]]]}"""
        val fc = Visor.lotes(filas(), mapOf("b0" to LoteJson(json)), FiltroVisor())
        val escrito = JsonMapper.builder().build().writeValueAsString(fc)
        assertTrue(escrito.contains("\"geometry\":$json"), escrito)
    }

    @Test
    fun `the niveles by piso, the delta when both sides have an area`() {
        val n =
            Visor.niveles(
                listOf(
                    NivelBrecha(2, d("80"), d("95.5"), "PAR"),
                    NivelBrecha(null, d("10"), null, "AMBIGUO"),
                    NivelBrecha(1, null, d("40"), "SOLO_CATASTRO"),
                    NivelBrecha(-1, d("30"), d("30"), "PAR")
                )
            )
        assertEquals(listOf(-1, 1, 2, null), n.map { it.piso })
        assertEquals(0, BigDecimal("15.5").compareTo(n[2].delta))
        assertNull(n[1].delta)
        assertEquals(0, BigDecimal.ZERO.compareTo(n[0].delta))
        val json = JsonMapper.builder().build()
        assertEquals(
            setOf("piso", "area_rentas", "area_catastro", "estado", "delta"),
            json.readTree(json.writeValueAsString(n[0])).propertyNames().toSet()
        )
        assertFalse(n.isEmpty())
    }

    private companion object {
        const val G7 = """{"type":"Polygon","coordinates":[[[-75.0,-12.0],[-75.1,-12.0],[-75.0,-12.1],[-75.0,-12.0]]]}"""
        const val G8 = """{"type":"Polygon","coordinates":[[[-75.2,-12.0],[-75.3,-12.0],[-75.2,-12.1],[-75.2,-12.0]]]}"""
    }
}
