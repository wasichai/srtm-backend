package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import srtm.impuesto.Parametros
import srtm.rentas.Declaracion
import srtm.rentas.NivelConstruccion
import srtm.rentas.Predio
import srtm.rentas.Records
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate

// #304: the values and características a corrida keeps of each side, so the comparison explains the Δ autoavalúo
// with what the corrida read. every figure is synthetic
class BrechaValoresTest {
    private val anio = 2026
    private val json = JsonMapper.builder().build()

    private fun dj(
        id: String,
        contribuyente: String,
        secuencia: String,
        autoavaluo: String,
        predio: String = "p1",
        uso: String? = null,
        arancel: String? = null,
        frente: String? = null,
        obras: String? = null
    ) = Declaracion(
        id = id,
        contribuyente = contribuyente,
        predio = predio,
        anio = anio,
        secuenciaUso = secuencia,
        porcentajeCondominio = BigDecimal("50"),
        valorAutoavaluo = BigDecimal(autoavaluo),
        valorCondominio = BigDecimal(autoavaluo).divide(BigDecimal(2)),
        valorAfecto = BigDecimal(autoavaluo).divide(BigDecimal(2)),
        uso = uso,
        arancel = arancel?.let(::BigDecimal),
        longitudFrente = frente?.let(::BigDecimal),
        valorObras = obras?.let(::BigDecimal)
    )

    private fun origen(
        terreno: String?,
        construccion: String?,
        modificado: Boolean = false
    ) = ValuacionDeOrigen(terreno?.let(::BigDecimal), construccion?.let(::BigDecimal), modificado)

    // two secuencias of two condóminos each; in each, the titular of highest autoavalúo is the one counted
    private val djs =
        listOf(
            dj("1", "a", "1", "100000.00", uso = "010101", arancel = "50.00", frente = "10"),
            dj("2", "b", "1", "90000.00", uso = "010101", arancel = "50.00", frente = "10"),
            dj("3", "a", "2", "150000.00", uso = "020101", arancel = "60.00", frente = "12"),
            dj("4", "b", "2", "150000.00", uso = "020101", arancel = "60.00", frente = "12")
        )

    private val origenes =
        mapOf(
            "1" to origen("40000.00", "60000.00"),
            "2" to origen("1.00", "1.00"),
            "3" to origen("50000.00", "100000.00"),
            "4" to origen("2.00", "2.00")
        )

    @Test
    fun `two secuencias of condominos keep the declared values once per secuencia, of its titular of highest autoavaluo`() {
        val d = Brecha.declarados(djs, Padron(emptyList(), djs, origen = origenes))
        assertEquals(BigDecimal("90000.00"), d.terreno)
        assertEquals(BigDecimal("160000.00"), d.construccion)
        // the arancel, uso and frente of the DJ of highest autoavalúo (the first on a tie)
        assertEquals("020101", d.uso)
        assertEquals(BigDecimal("60.00"), d.arancel)
        assertEquals(BigDecimal("12"), d.frente)
    }

    @Test
    fun `a DJ without its valuacion de origen, or with origen_modificado, leaves terreno and construccion null`() {
        val sinOrigen = Brecha.declarados(djs, Padron(emptyList(), djs, origen = origenes - "3"))
        assertNull(sinOrigen.terreno)
        assertNull(sinOrigen.construccion)
        val modificada = Brecha.declarados(djs, Padron(emptyList(), djs, origen = origenes + ("1" to origen("40000.00", "60000.00", modificado = true))))
        assertNull(modificada.terreno)
        assertNull(modificada.construccion)
        // the arancel, uso and frente are the DJ's own: they stay
        assertEquals("020101", modificada.uso)
    }

    @Test
    fun `the declared obras are the DJs' valor_obras, else the external padron's otras instalaciones`() {
        val conObras = listOf(dj("1", "a", "1", "100000.00", obras = "3000.00"))
        assertEquals(BigDecimal("3000.00"), Brecha.declarados(conObras, Padron(emptyList(), conObras)).obras)
        val delPadron = listOf(dj("1", "a", "1", "100000.00"))
        assertEquals(
            BigDecimal("2500.00"),
            Brecha.declarados(delPadron, Padron(emptyList(), delPadron, obrasDelPadron = mapOf("1" to BigDecimal("2500.00")))).obras
        )
    }

    private fun nivel(
        id: String,
        piso: Int?,
        area: String,
        anio: Int = 1990,
        acabados: String = "I"
    ) = NivelConstruccion(
        id = id,
        tipoNivel = "PISO",
        numeroPiso = piso,
        areaConstruida = BigDecimal(area),
        anioConstruccion = anio,
        material = "CONCRETO",
        estadoConservacion = "BUENO",
        murosColumnas = "C",
        techos = "D",
        pisos = acabados,
        puertasVentanas = acabados,
        revestimientos = acabados,
        banos = acabados,
        instalaciones = acabados,
        clasificacion = "CASA HABITACION"
    )

    private fun detalle(texto: String?): List<Map<*, *>> = json.readValue(texto, List::class.java).map { it as Map<*, *> }

    @Test
    fun `an AMBIGUO piso with two rentas niveles keeps both in detalle_rentas, with the padron's valores when it has them`() {
        val niveles =
            Brecha.niveles(
                listOf(nivel("n1", 1, "60"), nivel("n2", 1, "40", anio = 2005, acabados = "F")),
                listOf(ConstruccionCatastral(piso = 1, seccion = 1, areaConstruida = BigDecimal("100"))),
                mapOf("n1" to ValorDeNivel(BigDecimal("500.00"), BigDecimal("20"), BigDecimal("24000.00")))
            )
        val piso = niveles.single()
        assertEquals(EstadoNivel.AMBIGUO.name, piso.estado)
        assertEquals(2, piso.nivelesRentas)
        val d = detalle(piso.detalleRentas)
        assertEquals(2, d.size)
        assertEquals(1990, d[0]["anioConstruccion"])
        assertEquals("I", d[0]["pisos"])
        assertEquals("CASA HABITACION", d[0]["clasificacion"])
        assertEquals(0, BigDecimal("24000.00").compareTo(BigDecimal(d[0]["valor"].toString())))
        assertEquals(2005, d[1]["anioConstruccion"])
        assertEquals("F", d[1]["banos"])
        // a nivel the padrón did not value carries no valor key
        assertNull(d[1]["valor"])
        // catastro's secciones arrive with catastro-backend#84
        assertNull(piso.detalleCatastro)
    }

    // #304 (catastro-backend#84): catastro's secciones of a piso, each in detalle_catastro with rentas' keys; a catastro
    // that sends no detalle leaves it null
    @Test
    fun `a piso with two secciones in catastro keeps both in detalle_catastro`() {
        fun seccion(
            n: Int,
            area: String,
            estado: String
        ) = ConstruccionCatastral(
            piso = 1,
            seccion = n,
            areaConstruida = BigDecimal(area),
            detalle = DetalleNivel(area = BigDecimal(area), material = "CONCRETO", estadoConservacion = estado, valor = BigDecimal("1000.00"))
        )
        val piso = Brecha.niveles(listOf(nivel("n1", 1, "100")), listOf(seccion(1, "60", "BUENO"), seccion(2, "40", "REGULAR"))).single()
        assertEquals(EstadoNivel.PAR.name, piso.estado)
        val d = detalle(piso.detalleCatastro)
        assertEquals(listOf("BUENO", "REGULAR"), d.map { it["estadoConservacion"] })
        assertEquals(listOf(60, 40), d.map { (it["area"] as Number).toInt() })
        val sinDetalle = Brecha.niveles(listOf(nivel("n1", 1, "100")), listOf(ConstruccionCatastral(1, 1, BigDecimal("100")))).single()
        assertNull(sinDetalle.detalleCatastro)
    }

    @Test
    fun `the row keeps catastro's arancel, and an older catastro is told once`() {
        val predio = Predio(id = "p1", codigo = "01-01-0001")
        val solo = listOf(dj("1", "a", "1", "250000.00").copy(porcentajeCondominio = null))
        val conArancel = item("01-01-0001").copy(arancelM2 = BigDecimal("45.50"), conDetalle = true)
        val medida = Brecha.medir(anio, listOf(conArancel), Padron(listOf(predio), solo), parametros, null, hoy)
        assertEquals(BigDecimal("45.50"), medida.filas.single().arancelCatastro)
        assertNull(Brecha.sinDetalle(listOf(conArancel, item("X"))))
        val antiguo = Brecha.medir(anio, listOf(item("01-01-0001")), Padron(listOf(predio), solo), parametros, null, hoy)
        assertNull(antiguo.filas.single().arancelCatastro)
        assertEquals(Brecha.SIN_DETALLE, Brecha.sinDetalle(listOf(item("01-01-0001"), item("X"))))
        assertNull(Brecha.sinDetalle(emptyList()))
    }

    @Test
    fun `a piso only in catastro has no rentas detalle`() {
        val piso = Brecha.niveles(emptyList(), listOf(ConstruccionCatastral(piso = 1, seccion = 1, areaConstruida = BigDecimal("50")))).single()
        assertNull(piso.nivelesRentas)
        assertNull(piso.detalleRentas)
    }

    // medir

    private val parametros = Parametros.predial
    private val hoy = LocalDate.of(2026, 3, 1)

    private fun item(
        codigo: String,
        obras: String? = "5000.00"
    ) = ValuacionCatastral(
        codigoMunicipal = codigo,
        terreno = BigDecimal("120000.00"),
        construccion = BigDecimal("175000.00"),
        obras = obras?.let(::BigDecimal),
        autoavaluo = BigDecimal("120000.00") + BigDecimal("175000.00") + (obras?.let(::BigDecimal) ?: BigDecimal.ZERO),
        longitudFrente = BigDecimal("11.5"),
        estadoLevantamiento = "LEVANTADO",
        vinculoMetodo = "CODIGO",
        construcciones = emptyList()
    )

    @Test
    fun `a crossed row keeps both sides' values, and the declared ones as they came, even when they do not add up`() {
        val predio = Predio(id = "p1", codigo = "01-01-0001")
        // the padrón's components add up to 200 000, while its autoavalúo is 250 000 (synthetic: Ica's 3 % that does not)
        val solo = listOf(dj("1", "a", "1", "250000.00", uso = "010101", arancel = "50.00", frente = "10").copy(porcentajeCondominio = null))
        val padron = Padron(listOf(predio), solo, origen = mapOf("1" to origen("80000.00", "120000.00")))
        val fila = Brecha.medir(anio, listOf(item("01-01-0001")), padron, parametros, null, hoy).filas.single()
        assertEquals(BigDecimal("250000.00"), fila.autoavaluoDeclarado)
        assertEquals(BigDecimal("80000.00"), fila.terrenoDeclarado)
        assertEquals(BigDecimal("120000.00"), fila.construccionDeclarada)
        assertEquals("010101", fila.usoDeclarado)
        assertEquals(BigDecimal("50.00"), fila.arancelDeclarado)
        assertEquals(BigDecimal("10"), fila.frenteDeclarado)
        // the catastro side adds up to its autoavalúo
        assertEquals(fila.autoavaluoCatastro, fila.terrenoCatastro!! + fila.construccionCatastro!! + fila.obrasCatastro!!)
        assertEquals(BigDecimal("11.5"), fila.frenteCatastro)
        assertNull(fila.arancelCatastro)
        assertEquals("LEVANTADO", fila.estadoLevantamiento)
        assertEquals("CODIGO", fila.vinculoMetodo)
    }

    @Test
    fun `a ficha without obras takes the DJ's in obras_catastro, as in its autoavaluo (#208)`() {
        val predio = Predio(id = "p1", codigo = "01-01-0001")
        val solo = listOf(dj("1", "a", "1", "250000.00", obras = "3000.00").copy(porcentajeCondominio = null))
        val fila = Brecha.medir(anio, listOf(item("01-01-0001", obras = null)), Padron(listOf(predio), solo), parametros, null, hoy).filas.single()
        assertEquals(BigDecimal("3000.00"), fila.obrasCatastro)
        assertEquals(BigDecimal("3000.00"), fila.obrasDeclaradas)
        assertEquals(fila.autoavaluoCatastro, fila.terrenoCatastro!! + fila.construccionCatastro!! + fila.obrasCatastro!!)
    }

    @Test
    fun `an omiso keeps the catastro's values only, and the new fields are written in snake case`() {
        val fila = Brecha.medir(anio, listOf(item("NUEVO")), Padron(emptyList(), emptyList()), parametros, null, hoy).filas.single()
        assertEquals(Categoria.OMISO.name, fila.categoria)
        assertNull(fila.terrenoDeclarado)
        assertNull(fila.usoDeclarado)
        assertEquals(BigDecimal("120000.00"), fila.terrenoCatastro)
        val atributos = Records.attributes(fila)
        assertEquals(BigDecimal("120000.00"), atributos["terreno_catastro"])
        assertEquals("LEVANTADO", atributos["estado_levantamiento"])
    }
}
