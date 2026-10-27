package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import srtm.impuesto.Parametros
import srtm.rentas.Declaracion
import srtm.rentas.NivelConstruccion
import srtm.rentas.Predio
import srtm.rentas.Records
import java.math.BigDecimal
import java.time.LocalDate

// #247: the areas a corrida keeps. the niveles of the vigente DJ against the catastro's construcciones, paired by piso
// (Brecha.niveles), what each row sums of them, and the terreno and construida of each side. every figure is synthetic
class BrechaNivelesTest {
    private val anio = 2026

    private fun nivel(
        piso: Int?,
        area: String?,
        tipo: String? = "PISO",
        estado: String? = null
    ) = NivelConstruccion(tipoNivel = tipo, numeroPiso = piso, areaConstruida = area?.let(::BigDecimal), estado = estado)

    private fun construccion(
        piso: Int?,
        area: String?,
        seccion: Int = 1
    ) = ConstruccionCatastral(piso = piso, seccion = seccion, areaConstruida = area?.let(::BigDecimal))

    private fun par(
        piso: Int?,
        rentas: String?,
        catastro: String?,
        estado: EstadoNivel
    ) = NivelBrecha(piso, rentas?.let(::BigDecimal), catastro?.let(::BigDecimal), estado.name)

    @Test
    fun `a piso once on each side is a PAR, the secciones of a catastro piso summed first`() {
        val niveles =
            Brecha.niveles(
                listOf(nivel(1, "100"), nivel(2, "80")),
                listOf(construccion(1, "60", seccion = 1), construccion(1, "50", seccion = 2), construccion(2, "80"))
            )
        assertEquals(listOf(par(1, "100", "110", EstadoNivel.PAR), par(2, "80", "80", EstadoNivel.PAR)), niveles)
    }

    @Test
    fun `a piso on one side only is SOLO_RENTAS or SOLO_CATASTRO`() {
        val niveles = Brecha.niveles(listOf(nivel(1, "100"), nivel(3, "40")), listOf(construccion(1, "100"), construccion(2, "70")))
        assertEquals(
            listOf(
                par(1, "100", "100", EstadoNivel.PAR),
                par(2, null, "70", EstadoNivel.SOLO_CATASTRO),
                par(3, "40", null, EstadoNivel.SOLO_RENTAS)
            ),
            niveles
        )
    }

    @Test
    fun `a piso repeated on separate niveles of rentas, or a catastro piso without number, is AMBIGUO`() {
        val niveles =
            Brecha.niveles(
                // piso 1 twice (two usos of the predio); a sótano has no number to pair by
                listOf(nivel(1, "60"), nivel(1, "40"), nivel(1, "30", tipo = "SOTANO")),
                listOf(construccion(1, "100"), construccion(null, "35"), construccion(null, "5", seccion = 2))
            )
        assertEquals(listOf(par(1, "100", "100", EstadoNivel.AMBIGUO), par(null, "30", "40", EstadoNivel.AMBIGUO)), niveles)
    }

    @Test
    fun `a nivel without tipo is a piso, a piso without number is AMBIGUO`() {
        val niveles = Brecha.niveles(listOf(nivel(1, "100", tipo = null), nivel(null, "20")), listOf(construccion(1, "100")))
        assertEquals(listOf(par(1, "100", "100", EstadoNivel.PAR), par(null, "20", null, EstadoNivel.AMBIGUO)), niveles)
    }

    @Test
    fun `an inactive nivel is not declared, and an area that is missing is null`() {
        val niveles = Brecha.niveles(listOf(nivel(1, "100", estado = "INACTIVO"), nivel(2, null)), listOf(construccion(1, "90")))
        assertEquals(listOf(par(1, null, "90", EstadoNivel.SOLO_CATASTRO), par(2, null, null, EstadoNivel.SOLO_RENTAS)), niveles)
    }

    @Test
    fun `the row counts the PAR, those where catastro is larger than a positive rentas, and their positive differences`() {
        val r =
            Brecha.resumenDeNiveles(
                listOf(
                    par(1, "100", "110", EstadoNivel.PAR),
                    par(2, "80", "70", EstadoNivel.PAR),
                    // rentas 0 is no larger catastro, but its difference counts
                    par(3, "0", "20", EstadoNivel.PAR),
                    par(4, null, "500", EstadoNivel.SOLO_CATASTRO),
                    // a PAR without one side's area is compared, but adds no difference
                    par(5, null, "300", EstadoNivel.PAR),
                    par(null, "1", "900", EstadoNivel.AMBIGUO)
                )
            )
        assertEquals(ResumenDeNiveles(4, 1, BigDecimal("30")), r)
        assertEquals(ResumenDeNiveles(0, 0, BigDecimal.ZERO), Brecha.resumenDeNiveles(emptyList()))
    }

    // medir

    private val parametros = Parametros.predial
    private val hoy = LocalDate.of(2026, 3, 1)
    private val propio = Predio(id = "p1", codigo = "01-01-0001")
    private val sinFicha = Predio(id = "p2", codigo = "01-01-0002")

    private fun dj(
        id: String,
        contribuyente: String,
        predio: String,
        secuencia: String,
        terreno: String?,
        construida: String?
    ) = Declaracion(
        id = id,
        contribuyente = contribuyente,
        predio = predio,
        anio = anio,
        secuenciaUso = secuencia,
        porcentajeCondominio = BigDecimal("50"),
        valorAutoavaluo = BigDecimal("100000.00"),
        valorCondominio = BigDecimal("50000.00"),
        valorAfecto = BigDecimal("50000.00"),
        areaTerreno = terreno?.let(::BigDecimal),
        areaConstruida = construida?.let(::BigDecimal)
    )

    private fun item(
        codigo: String,
        construcciones: List<ConstruccionCatastral>?
    ) = ValuacionCatastral(
        codigoMunicipal = codigo,
        autoavaluo = BigDecimal("200000.00"),
        areaTerreno = BigDecimal("300"),
        areaConstruida = BigDecimal("190"),
        construcciones = construcciones
    )

    // two secuencias of two condóminos each: the declared areas count each secuencia once, its highest
    private val djs =
        listOf(
            dj("1", "a", "p1", "1", "120", "100"),
            dj("2", "b", "p1", "1", "120.5", "90"),
            dj("3", "a", "p1", "2", "80", "50"),
            dj("4", "b", "p1", "2", null, null),
            dj("5", "a", "p2", "1", "70", "30")
        )

    // each condómino carries the niveles of its secuencia; the second one's copy of the secuencia 1 is smaller
    private val niveles =
        mapOf(
            "1" to listOf(nivel(1, "100")),
            "2" to listOf(nivel(1, "90")),
            "3" to listOf(nivel(2, "50")),
            "4" to listOf(nivel(2, "50")),
            "5" to listOf(nivel(1, "30"))
        )

    private fun medir(items: List<ValuacionCatastral>) =
        Brecha.medir(anio, items, Padron(listOf(propio, sinFicha), djs, niveles = niveles), parametros, null, hoy)

    @Test
    fun `a crossed row keeps both sides' areas and its niveles`() {
        val fila = medir(listOf(item("01-01-0001", listOf(construccion(1, "120"), construccion(2, "70"))))).filas.single { it.predio == "p1" }
        assertEquals(BigDecimal("200.5"), fila.areaTerrenoDeclarada)
        assertEquals(BigDecimal("150"), fila.areaConstruidaDeclarada)
        assertEquals(BigDecimal("300"), fila.areaTerrenoCatastro)
        assertEquals(BigDecimal("190"), fila.areaConstruidaCatastro)
        assertEquals(listOf(par(1, "100", "120", EstadoNivel.PAR), par(2, "50", "70", EstadoNivel.PAR)), fila.niveles)
        assertEquals(2, fila.nivelesComparados)
        assertEquals(2, fila.nivelesCatastroMayor)
        assertEquals(BigDecimal("40"), fila.deltaAreaNiveles)
    }

    @Test
    fun `a catastro without construcciones leaves the nivel fields null and writes no nivel`() {
        val fila = medir(listOf(item("01-01-0001", null))).filas.single { it.predio == "p1" }
        assertEquals(BigDecimal("300"), fila.areaTerrenoCatastro)
        assertEquals(BigDecimal("200.5"), fila.areaTerrenoDeclarada)
        assertNull(fila.niveles)
        assertNull(fila.nivelesComparados)
        assertNull(fila.nivelesCatastroMayor)
        assertNull(fila.deltaAreaNiveles)
    }

    @Test
    fun `an omiso has the catastro's areas only, a predio without ficha the declared ones only`() {
        val filas = medir(listOf(item("01-01-0001", emptyList()), item("NUEVO", listOf(construccion(1, "60"))))).filas
        val omiso = filas.single { it.categoria == Categoria.OMISO.name }
        assertEquals(BigDecimal("300"), omiso.areaTerrenoCatastro)
        assertNull(omiso.areaTerrenoDeclarada)
        assertNull(omiso.niveles)
        assertNull(omiso.nivelesComparados)
        val sinCatastro = filas.single { it.categoria == Categoria.SIN_CATASTRO.name }
        assertEquals(BigDecimal("70"), sinCatastro.areaTerrenoDeclarada)
        assertEquals(BigDecimal("30"), sinCatastro.areaConstruidaDeclarada)
        assertNull(sinCatastro.areaTerrenoCatastro)
        assertNull(sinCatastro.niveles)
        // an empty construcciones is a catastro that says the predio has none: every declared nivel is SOLO_RENTAS
        val cruzada = filas.single { it.predio == "p1" }
        assertEquals(listOf(par(1, "100", null, EstadoNivel.SOLO_RENTAS), par(2, "50", null, EstadoNivel.SOLO_RENTAS)), cruzada.niveles)
        assertEquals(0, cruzada.nivelesComparados)
    }

    @Test
    fun `a ficha sin levantar keeps the declared areas and nothing of the catastro`() {
        val fila =
            medir(listOf(item("01-01-0001", listOf(construccion(1, "120"))).copy(estadoLevantamiento = SIN_LEVANTAR)))
                .filas
                .single { it.predio == "p1" }
        assertEquals(BigDecimal("200.5"), fila.areaTerrenoDeclarada)
        assertNull(fila.areaTerrenoCatastro)
        assertNull(fila.areaConstruidaCatastro)
        assertNull(fila.niveles)
    }

    @Test
    fun `the niveles are never written as a field of brecha_predio`() {
        val fila = FilaBrecha(categoria = "COINCIDE", niveles = listOf(par(1, "1", "1", EstadoNivel.PAR)), nivelesComparados = 1)
        val atributos = Records.attributes(fila)
        assertFalse("niveles" in atributos, atributos.toString())
        assertEquals(1, atributos["niveles_comparados"])
    }
}
