package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.rentas.UsoPredio
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant

// #303/#304: what the brecha of one predio says differs, past the tolerances and in a fixed order, and its avisos.
// every figure is synthetic
class ComparacionDelPredioTest {
    private val corrida = RegistroCorrida(id = "c1", anio = 2026, estado = TERMINADA, terminado = "2026-11-14T10:00:00Z", origen = "MANUAL")

    private fun d(v: String) = BigDecimal(v)

    private val subvaluado =
        FilaBrecha(
            id = "b1",
            predio = "p1",
            categoria = Categoria.SUBVALUADO.name,
            confianza = Confianza.CONFIABLE.name,
            uso = "010101",
            areaTerrenoDeclarada = d("200.00"),
            areaTerrenoCatastro = d("200.40"),
            areaConstruidaDeclarada = d("100.00"),
            areaConstruidaCatastro = d("150.00"),
            autoavaluoDeclarado = d("100000.00"),
            autoavaluoCatastro = d("150000.00"),
            deltaAutoavaluo = d("50000.00"),
            impuestoDeclarado = d("200.00"),
            impuestoCatastro = d("350.00"),
            deltaImpuesto = d("150.00"),
            deltaArbitrios = d("0.50"),
            terrenoDeclarado = d("40000.00"),
            construccionDeclarada = d("60000.00"),
            obrasDeclaradas = d("0.00"),
            terrenoCatastro = d("40000.80"),
            construccionCatastro = d("109999.20"),
            obrasCatastro = d("0.00"),
            usoDeclarado = "010101",
            frenteDeclarado = d("10.00"),
            frenteCatastro = d("12.00")
        )

    private fun comparar(
        fila: FilaBrecha,
        niveles: List<NivelBrecha> = emptyList(),
        contexto: ContextoDelPredio = ContextoDelPredio()
    ) = ComparacionDelPredio.comparar(corrida, fila, niveles, contexto)

    @Test
    fun `only what passes the tolerances differs, in the fixed order by grupo, and the rest compared is iguales`() {
        val r = comparar(subvaluado)
        assertEquals(
            listOf("areaConstruida", "construccion", "autoavaluo", "impuesto", "frente"),
            r.diferencias.map { it.campo }
        )
        assertEquals(listOf("AREAS", "VALORES", "VALORES", "VALORES", "USO"), r.diferencias.map { it.grupo })
        val construida = r.diferencias.first()
        assertEquals(d("50.00"), construida.delta)
        assertEquals(ComparacionDelPredio.M2, construida.unidad)
        // 0.40 m2, S/ 0.80 and a Δ arbitrios of S/ 0.50 are within the tolerances; the same uso and obras are equal
        assertEquals(setOf("areaTerreno", "terreno", "obras", "deltaArbitrios", "uso"), r.iguales.toSet())
        assertEquals(ComparacionDelPredio.TOLERANCIAS, r.tolerancias)
        assertEquals("c1", r.corrida.id)
        assertEquals("b1", r.fila.id)
    }

    @Test
    fun `an area of exactly the tolerance differs, a field missing on one side is neither different nor equal`() {
        val r = comparar(subvaluado.copy(areaTerrenoCatastro = d("200.50"), arancelDeclarado = d("50.00"), arancelCatastro = null))
        assertTrue(r.diferencias.any { it.campo == "areaTerreno" })
        assertFalse(r.diferencias.any { it.campo == "arancel" })
        assertFalse("arancel" in r.iguales)
    }

    @Test
    fun `the uso compares by clase and subclase when both codigos are in the catalog`() {
        val usos =
            mapOf(
                "010101" to UsoPredio(codigo = "010101", clase = "CASA HABITACION", subClase = "VIVIENDA", uso = "VIVIENDA UNIFAMILIAR"),
                "020101" to UsoPredio(codigo = "020101", clase = "COMERCIO", subClase = "TIENDA", uso = "BODEGA")
            )
        val r = comparar(subvaluado.copy(uso = "020101"), contexto = ContextoDelPredio(usos = usos))
        val deUso = r.diferencias.filter { it.grupo == "USO" && it.campo != "frente" }
        assertEquals(listOf("usoClase", "usoSubclase"), deUso.map { it.campo })
        assertEquals("CASA HABITACION", deUso[0].rentas)
        assertEquals("COMERCIO", deUso[0].catastro)
        // a text is the same without case nor spaces
        val igual = comparar(subvaluado.copy(usoDeclarado = "abc", uso = " A B C"))
        assertTrue("uso" in igual.iguales)
    }

    @Test
    fun `the niveles come by piso, those without one last, and an AMBIGUO without catastro area has delta null`() {
        val niveles =
            listOf(
                NivelBrecha(null, d("20"), null, EstadoNivel.AMBIGUO.name),
                NivelBrecha(2, d("50"), d("40"), EstadoNivel.PAR.name),
                NivelBrecha(1, d("100"), d("120"), EstadoNivel.PAR.name)
            )
        val r = comparar(subvaluado, niveles)
        assertEquals(listOf(1, 2, null), r.niveles.map { it.piso })
        assertEquals(d("20"), r.niveles[0].delta)
        assertEquals(d("-10"), r.niveles[1].delta)
        assertNull(r.niveles[2].delta)
        assertTrue(r.niveles[2].diferencias.isEmpty())
        // rentas has construction on three pisos (the sótano among them), catastro on two
        val pisos = r.diferencias.single { it.campo == "pisosConConstruccion" }
        assertEquals("NIVELES", pisos.grupo)
        assertEquals(d("3"), pisos.rentas)
        assertEquals(d("2"), pisos.catastro)
    }

    private val json = JsonMapper.builder().build()

    private fun detalle(vararg d: DetalleNivel) = json.writeValueAsString(d.toList())

    @Test
    fun `a PAR piso with one nivel on each side lists its caracteristicas apart and the five acabados as one`() {
        val rentas =
            DetalleNivel(
                area = d("100"),
                anioConstruccion = 1990,
                material = "CONCRETO",
                estadoConservacion = "BUENO",
                murosColumnas = "C",
                techos = "D",
                pisos = "I",
                puertasVentanas = "I",
                revestimientos = "I",
                banos = "I",
                instalaciones = "I",
                clasificacion = "CASA"
            )
        val catastro =
            rentas.copy(
                anioConstruccion = 1998,
                estadoConservacion = "REGULAR",
                pisos = "F",
                puertasVentanas = "F",
                revestimientos = "D",
                banos = "F",
                instalaciones = "F"
            )
        val n = NivelBrecha(1, d("100"), d("100"), EstadoNivel.PAR.name, 1, detalle(rentas), detalle(catastro))
        val piso = comparar(subvaluado, listOf(n)).niveles.single()
        assertEquals(listOf("estadoConservacion", "anioConstruccion", "acabados"), piso.diferencias.map { it.campo })
        val acabados = piso.diferencias.last()
        assertEquals("I·I·I·I·I", acabados.rentas)
        assertEquals("F·F·D·F·F", acabados.catastro)
        assertEquals(d("8"), piso.diferencias[1].delta)
        assertEquals(1, piso.detalleRentas!!.size)
        // two rentas niveles on a piso (AMBIGUO) or no catastro detalle (before catastro-backend#84): no par to compare
        val sinCatastro = comparar(subvaluado, listOf(n.copy(detalleCatastro = null))).niveles.single()
        assertTrue(sinCatastro.diferencias.isEmpty())
        assertNull(sinCatastro.detalleCatastro)
    }

    private fun codigos(
        fila: FilaBrecha,
        contexto: ContextoDelPredio = ContextoDelPredio()
    ) = comparar(fila, contexto = contexto).avisos.map { it.codigo }

    @Test
    fun `each aviso, and none when its case is absent`() {
        assertEquals(emptyList<String>(), codigos(subvaluado))
        assertTrue(ComparacionDelPredio.CATASTRO_SIN_CONSTRUCCION in codigos(subvaluado.copy(areaConstruidaCatastro = d("0"))))
        assertTrue(ComparacionDelPredio.RENTAS_SIN_CONSTRUCCION in codigos(subvaluado.copy(areaConstruidaDeclarada = null)))
        // 5 UIT of 5 500 is 27 500: a Δ of 50 000 passes it; without the UIT the aviso does not go
        assertTrue(Seguimiento.SUPERA_5_UIT in codigos(subvaluado, ContextoDelPredio(uit = d("5500"))))
        assertFalse(Seguimiento.SUPERA_5_UIT in codigos(subvaluado.copy(deltaAutoavaluo = d("27500.00")), ContextoDelPredio(uit = d("5500"))))
        assertTrue(ComparacionDelPredio.OBRAS_DE_LA_DJ in codigos(subvaluado.copy(obrasDj = d("3000.00"))))
        assertTrue(ComparacionDelPredio.SIN_DELTA_ARBITRIOS in codigos(subvaluado.copy(deltaArbitrios = null)))
        // an omiso has no Δ arbitrios to compute
        assertFalse(ComparacionDelPredio.SIN_DELTA_ARBITRIOS in codigos(subvaluado.copy(categoria = Categoria.OMISO.name, deltaArbitrios = null)))
        val despues = ContextoDelPredio(guardados = listOf(Instant.parse("2026-11-14T09:00:00Z"), Instant.parse("2026-11-14T11:00:00Z")))
        assertTrue(ComparacionDelPredio.DJ_POSTERIOR_A_LA_CORRIDA in codigos(subvaluado, despues))
        assertFalse(
            ComparacionDelPredio.DJ_POSTERIOR_A_LA_CORRIDA in codigos(subvaluado, ContextoDelPredio(guardados = listOf(Instant.parse("2026-11-14T09:00:00Z"))))
        )
        assertTrue(ComparacionDelPredio.CODIGO_COMPARTIDO in codigos(subvaluado, ContextoDelPredio(codigoCompartido = true)))
        // #304: the padrón's components that do not add up to the declared autoavalúo
        assertTrue(ComparacionDelPredio.COMPONENTES_NO_SUMAN in codigos(subvaluado.copy(terrenoDeclarado = d("80000.00"))))
    }

    @Test
    fun `the response carries no name nor documento`() {
        val r = comparar(subvaluado)
        val texto =
            JsonMapper
                .builder()
                .build()
                .writeValueAsString(r)
                .lowercase()
        for (campo in listOf("nombre", "documento", "titular", "contribuyente")) assertFalse(campo in texto, campo)
    }
}
