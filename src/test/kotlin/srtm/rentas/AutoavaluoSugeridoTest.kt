package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.brecha.ConstruccionValuacion
import srtm.brecha.ObraValuacion
import java.math.BigDecimal

// #149: a declaración, its predio, its niveles and obras as the insumos of catastro's engine (POST /api/catastro/valuacion)
class AutoavaluoSugeridoTest {
    private val usos =
        listOf(
            UsoPredio("010101", "RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN"),
            UsoPredio("010201", "RESIDENCIAL", "MULTIFAMILIAR", "CASA HABITACIÓN"),
            UsoPredio("020101", "COMERCIAL", "TIENDAS", "BODEGA")
        )

    private val declaracion =
        Declaracion(
            id = "d1",
            anio = 2026,
            areaTerreno = BigDecimal("200"),
            claseUso = "RESIDENCIAL",
            subClaseUso = "UNIFAMILIAR",
            uso = "CASA HABITACIÓN"
        )

    private val predio = Predio(id = "p1", codigo = "01-01-0006")

    private fun nivel(
        tipo: String = "PISO",
        numero: Int = 1,
        estado: String? = "ACTIVO",
        clasificacion: String? = null
    ) = NivelDeValuacion(
        NivelConstruccion(
            tipoNivel = tipo,
            numeroPiso = numero,
            anioConstruccion = 2016,
            material = "CONCRETO",
            estadoConservacion = "BUENO",
            areaConstruida = BigDecimal("100"),
            murosColumnas = "C",
            techos = "D",
            pisos = "B",
            puertasVentanas = "E",
            estado = estado
        ),
        clasificacion
    )

    private fun obra(
        categoria: String?,
        estado: String? = "ACTIVO"
    ) = ObraComplementaria(
        tipoObra = "MUROS PERIMETRICOS O CERCOS",
        categoria = categoria,
        anioConstruccion = 2016,
        material = "CONCRETO",
        estadoConservacion = "BUENO",
        cantidad = BigDecimal("2"),
        metrado = BigDecimal("15"),
        estado = estado
    )

    private fun insumos(
        arancel: BigDecimal? = null,
        predio: Predio? = this.predio,
        niveles: List<NivelDeValuacion> = listOf(nivel()),
        obras: List<ObraComplementaria> = emptyList(),
        declaracion: Declaracion = this.declaracion
    ) = AutoavaluoSugerido.insumos(declaracion, arancel, predio, niveles, obras, usos)

    @Test
    fun `the declaracion's land, arancel, uso code and the predio's code`() {
        val i = insumos(arancel = BigDecimal("45.50"))

        assertEquals(2026, i.anio)
        assertEquals(BigDecimal("200"), i.areaTerreno)
        assertEquals(BigDecimal("45.50"), i.arancelM2)
        assertEquals("010101", i.uso)
        assertEquals("01-01-0006", i.codigoMunicipal)
        assertFalse(i.esEdificio)
    }

    @Test
    fun `without the arancel it goes null, for catastro to take its ficha's`() {
        assertNull(insumos().arancelM2)
    }

    @Test
    fun `a nivel is one construccion with the engine's names`() {
        val c = insumos().construcciones.single()

        assertEquals(
            ConstruccionValuacion(
                piso = "1",
                areaConstruida = BigDecimal("100"),
                anioConstruccion = 2016,
                material = "CONCRETO",
                estado = "BUENO",
                muros = "C",
                techos = "D",
                puertas = "E",
                clasificacion = null
            ),
            c
        )
    }

    @Test
    fun `the piso of each tipo de nivel`() {
        val pisos =
            insumos(niveles = listOf(nivel(numero = 2), nivel("SOTANO", 1), nivel("MEZANINE", 1), nivel("AZOTEA", 3)))
                .construcciones
                .map { it.piso }

        assertEquals(listOf("2", "S1", "M1", "AZ"), pisos)
    }

    @Test
    fun `an inactive nivel or obra is not valued`() {
        val i = insumos(niveles = listOf(nivel(), nivel(numero = 2, estado = "INACTIVO")), obras = listOf(obra("1", estado = "INACTIVO")))

        assertEquals(listOf("1"), i.construcciones.map { it.piso })
        assertTrue(i.obras.isEmpty())
    }

    @Test
    fun `a nivel's clasificacion from the padron, else the declaracion's`() {
        val propia = "TIENDAS DEPOSITOS CENTROS DE RECREACION CLUBES E INSTITUCIONES"
        val deLaDj = "CASA HABITACION Y DEPARTAMENTOS PARA VIVIENDA"

        val i =
            insumos(
                niveles = listOf(nivel(clasificacion = propia), nivel(numero = 2)),
                declaracion = declaracion.copy(clasificacion = deLaDj)
            )

        assertEquals(listOf(propia, deLaDj), i.construcciones.map { it.clasificacion })
    }

    @Test
    fun `an obra's partida from its categoria's number, its cantidad the total metrado`() {
        val i = insumos(obras = listOf(obra("3. MURO DE LADRILLO DE ARCILLA"), obra("12")))

        assertEquals(
            ObraValuacion("MUROS PERIMETRICOS O CERCOS|3", BigDecimal("30"), 2016, "CONCRETO", "BUENO"),
            i.obras[0]
        )
        assertEquals(listOf("MUROS PERIMETRICOS O CERCOS|3", "MUROS PERIMETRICOS O CERCOS|12"), i.obras.map { it.codigoVu })
    }

    @Test
    fun `an obra without its partida's number goes as declared, for catastro's motivo to name it`() {
        val i = insumos(obras = listOf(obra(" MURO DE ADOBE "), obra(" "), obra(null), obra("1").copy(tipoObra = " ")))

        assertEquals(
            listOf("MUROS PERIMETRICOS O CERCOS|MURO DE ADOBE", "MUROS PERIMETRICOS O CERCOS", "MUROS PERIMETRICOS O CERCOS", null),
            i.obras.map { it.codigoVu }
        )
    }

    @Test
    fun `the stored total metrado wins over cantidad x metrado`() {
        val i = insumos(obras = listOf(obra("1").copy(totalMetrado = BigDecimal("31.5"))))

        assertEquals(BigDecimal("31.5"), i.obras.single().cantidad)
    }

    @Test
    fun `an edificio is a predio with its edificacion, or niveles above the first floor`() {
        assertTrue(insumos(predio = predio.copy(edificacion = "EDIFICIO")).esEdificio)
        assertTrue(insumos(niveles = listOf(nivel(), nivel(numero = 2))).esEdificio)
        // a departamento on the 6th floor, declared alone
        assertTrue(insumos(niveles = listOf(nivel(numero = 6))).esEdificio)
        // a sótano or an azotea is not another floor
        assertFalse(insumos(niveles = listOf(nivel(), nivel("SOTANO", 1), nivel("AZOTEA", 2))).esEdificio)
    }

    @Test
    fun `the uso code by clase, sub clase and uso, an ambiguous or unknown uso is none`() {
        assertEquals("010201", insumos(declaracion = declaracion.copy(subClaseUso = "MULTIFAMILIAR")).uso)
        // the uso alone, when the catalog has it once
        assertEquals("020101", insumos(declaracion = declaracion.copy(claseUso = null, subClaseUso = null, uso = "BODEGA")).uso)
        assertNull(insumos(declaracion = declaracion.copy(claseUso = null, subClaseUso = null)).uso)
        assertNull(insumos(declaracion = declaracion.copy(uso = "OTRO")).uso)
        assertNull(insumos(declaracion = declaracion.copy(uso = null)).uso)
    }

    @Test
    fun `a rustico predio sends RUSTICO, an urbano URBANO, none or another null`() {
        assertEquals("RUSTICO", insumos(predio = predio.copy(tipoPredio = "PREDIO RUSTICO")).tipoPredio)
        assertEquals("URBANO", insumos(predio = predio.copy(tipoPredio = "PREDIO URBANO")).tipoPredio)
        assertNull(insumos(predio = predio.copy(tipoPredio = null)).tipoPredio)
        assertNull(insumos(predio = null).tipoPredio)
    }
}
