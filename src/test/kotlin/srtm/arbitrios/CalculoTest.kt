package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.emision.soles
import srtm.rentas.Declaracion
import java.math.BigDecimal
import java.math.RoundingMode

// Calculo.calcular's arithmetic, apart from how Arbitrios.determinar reads base/tasa/habitantes/redondeo (Task 8).
// monto = tasa × cantidad (by base) × factor de habitantes, redondeado al final. two sets of figures are real, not
// invented (global-constraints.md): the manual M02 example and Miraflores 2026 (Ord. 665/MM)
class CalculoTest {
    private val quien = "0001-0001, uso 01, declaración 2026"

    private fun declaracion(
        longitudFrente: BigDecimal? = null,
        areaConstruida: BigDecimal? = null,
        numeroHabitantes: Int? = null
    ) = Declaracion(
        id = "dj-1",
        predio = "predio-100",
        anio = 2026,
        secuenciaUso = "01",
        longitudFrente = longitudFrente,
        areaConstruida = areaConstruida,
        numeroHabitantes = numeroHabitantes
    )

    @Test
    fun `M02 barrido, por frontis, sin habitantes - manual M02`() {
        // manual M02: 3.37 x 12 = 40.44
        val d = declaracion(longitudFrente = BigDecimal("12"))
        val calculado = Calculo.calcular(Base.FRONTIS_ML, BigDecimal("3.37"), d, null, null, quien)
        assertEquals(
            Calculado.Es(
                Desglose(
                    base = Base.FRONTIS_ML,
                    cantidadBase = BigDecimal("12"),
                    tasaUnitaria = BigDecimal("3.37"),
                    habitantes = null,
                    habitantesPresuntos = false,
                    promedioHabitantes = null,
                    variacionHabitante = null,
                    redondeo = null,
                    monto = BigDecimal("40.44")
                )
            ),
            calculado
        )
    }

    @Test
    fun `M02 recoleccion, por area construida, con habitantes declarados - manual M02`() {
        // manual M02: 0.12 x 180 x (1 + (5-1) x 1) = 108.00
        val d = declaracion(areaConstruida = BigDecimal("180"), numeroHabitantes = 5)
        val habitantes = Habitantes(BigDecimal("1"), BigDecimal("1"))
        val calculado = Calculo.calcular(Base.AREA_CONSTRUIDA_M2, BigDecimal("0.12"), d, habitantes, null, quien)
        assertEquals(
            Calculado.Es(
                Desglose(
                    base = Base.AREA_CONSTRUIDA_M2,
                    cantidadBase = BigDecimal("180"),
                    tasaUnitaria = BigDecimal("0.12"),
                    habitantes = 5,
                    habitantesPresuntos = false,
                    promedioHabitantes = BigDecimal("1"),
                    variacionHabitante = BigDecimal("1"),
                    redondeo = null,
                    monto = BigDecimal("108.00")
                )
            ),
            calculado
        )
    }

    @Test
    fun `Miraflores casa 01A, con redondeo - Ord 665-MM 2026`() {
        // Miraflores 2026 (Ord. 665/MM): 0.0514 x 100 x (1 + (4-2) x 0.5) = 10.28, redondeo 2:ABAJO
        val d = declaracion(areaConstruida = BigDecimal("100"), numeroHabitantes = 4)
        val habitantes = Habitantes(BigDecimal("2"), BigDecimal("0.5"))
        val redondeo = Redondeo(2, RoundingMode.DOWN)
        val calculado = Calculo.calcular(Base.AREA_CONSTRUIDA_M2, BigDecimal("0.0514"), d, habitantes, redondeo, quien)
        assertEquals(Calculado.Es::class, calculado::class)
        val desglose = (calculado as Calculado.Es).desglose
        assertEquals(BigDecimal("10.28"), desglose.monto)
        assertEquals(redondeo, desglose.redondeo)
    }

    @Test
    fun `barrido Zona 2, por frontis, con redondeo - Ord 665-MM 2026`() {
        // Miraflores 2026 (Ord. 665/MM): 5.0856 x 10.5 = 53.3988, redondeo 2:ABAJO -> 53.39
        val d = declaracion(longitudFrente = BigDecimal("10.5"))
        val redondeo = Redondeo(2, RoundingMode.DOWN)
        val calculado = Calculo.calcular(Base.FRONTIS_ML, BigDecimal("5.0856"), d, null, redondeo, quien)
        val desglose = (calculado as Calculado.Es).desglose
        assertEquals(BigDecimal("53.39"), desglose.monto)
    }

    @Test
    fun `numeroHabitantes nulo usa el promedio como presunto, factor 1`() {
        val d = declaracion(numeroHabitantes = null)
        val habitantes = Habitantes(BigDecimal("3"), BigDecimal("0.5"))
        val calculado = Calculo.calcular(Base.PREDIO, BigDecimal("10.00"), d, habitantes, null, quien)
        val desglose = (calculado as Calculado.Es).desglose
        assertEquals(0, BigDecimal("10.00").compareTo(desglose.monto))
        assertTrue(desglose.habitantesPresuntos)
        assertEquals(null, desglose.habitantes)
        assertEquals(BigDecimal("3"), desglose.promedioHabitantes)
        assertEquals(BigDecimal("0.5"), desglose.variacionHabitante)
    }

    @Test
    fun `numeroHabitantes en 0, por debajo del promedio, puede anular el monto`() {
        val d = declaracion(numeroHabitantes = 0)
        val habitantes = Habitantes(BigDecimal("2"), BigDecimal("0.5"))
        val calculado = Calculo.calcular(Base.PREDIO, BigDecimal("10.00"), d, habitantes, null, quien)
        val desglose = (calculado as Calculado.Es).desglose
        assertEquals(0, desglose.monto.compareTo(BigDecimal.ZERO))
        assertEquals(false, desglose.habitantesPresuntos)
        assertEquals(0, desglose.habitantes)
    }

    @Test
    fun `sin factor de habitantes, numeroHabitantes de la declaracion no se filtra al desglose`() {
        // una Declaracion se comparte entre servicios (barrido no reparte por habitantes aunque la declaración
        // sí los tenga, p.ej. para recolección): Desglose.habitantes debe quedar nulo, igual que los demás campos
        val d = declaracion(longitudFrente = BigDecimal("12"), numeroHabitantes = 4)
        val calculado = Calculo.calcular(Base.FRONTIS_ML, BigDecimal("3.37"), d, null, null, quien)
        val desglose = (calculado as Calculado.Es).desglose
        assertEquals(null, desglose.habitantes)
        assertEquals(false, desglose.habitantesPresuntos)
        assertEquals(null, desglose.promedioHabitantes)
        assertEquals(null, desglose.variacionHabitante)
    }

    @Test
    fun `un factor de habitantes negativo falla, sin monto`() {
        val d = declaracion(numeroHabitantes = 0)
        val habitantes = Habitantes(BigDecimal("2"), BigDecimal("0.8"))
        val calculado = Calculo.calcular(Base.PREDIO, BigDecimal("10.00"), d, habitantes, null, quien)
        assertEquals(
            Calculado.Falta("Factor de habitantes negativo en $quien: 1 + (H − P) × V = -0.6"),
            calculado
        )
    }

    @Test
    fun `frontis nulo falla, por nombre`() {
        val d = declaracion(longitudFrente = null)
        assertEquals(
            Calculado.Falta("Frontis del predio $quien"),
            Calculo.calcular(Base.FRONTIS_ML, BigDecimal("3.37"), d, null, null, quien)
        )
    }

    @Test
    fun `frontis en cero o negativo falla, igual que nulo`() {
        val cero = declaracion(longitudFrente = BigDecimal.ZERO)
        val negativo = declaracion(longitudFrente = BigDecimal("-1"))
        assertEquals(
            Calculado.Falta("Frontis del predio $quien"),
            Calculo.calcular(Base.FRONTIS_ML, BigDecimal("3.37"), cero, null, null, quien)
        )
        assertEquals(
            Calculado.Falta("Frontis del predio $quien"),
            Calculo.calcular(Base.FRONTIS_ML, BigDecimal("3.37"), negativo, null, null, quien)
        )
    }

    @Test
    fun `area construida nula o no positiva falla, por nombre`() {
        val nula = declaracion(areaConstruida = null)
        val negativa = declaracion(areaConstruida = BigDecimal("-10"))
        assertEquals(
            Calculado.Falta("Área construida del predio $quien"),
            Calculo.calcular(Base.AREA_CONSTRUIDA_M2, BigDecimal("0.12"), nula, null, null, quien)
        )
        assertEquals(
            Calculado.Falta("Área construida del predio $quien"),
            Calculo.calcular(Base.AREA_CONSTRUIDA_M2, BigDecimal("0.12"), negativa, null, null, quien)
        )
    }

    @Test
    fun `base PREDIO sin habitantes - monto es exactamente la tasa, misma escala`() {
        val d = declaracion()
        val tasa = BigDecimal("8.50")
        val calculado = Calculo.calcular(Base.PREDIO, tasa, d, null, null, quien)
        val desglose = (calculado as Calculado.Es).desglose
        assertEquals(tasa, desglose.monto)
        assertEquals(tasa.scale(), desglose.monto.scale())
    }

    // Calculo.formula: the human readable formula a DesgloseCuota shows. tasa con los decimales de la norma
    // (toPlainString), cantidad siempre con 2 decimales (aunque venga con otra escala), H entero y P/V como se
    // guardaron. las dos cifras con fuente de global-constraints.md: Miraflores 2026, Ord. 665/MM
    private fun cuota(
        base: Base?,
        tasaUnitaria: BigDecimal? = null,
        cantidadBase: BigDecimal? = null,
        monto: BigDecimal? = null,
        habitantes: Int? = null,
        promedioHabitantes: BigDecimal? = null,
        variacionHabitante: BigDecimal? = null,
        redondeo: String? = null
    ) = CuotaArbitrio(
        base = base?.name,
        tasaUnitaria = tasaUnitaria,
        cantidadBase = cantidadBase,
        monto = monto,
        habitantes = habitantes,
        promedioHabitantes = promedioHabitantes,
        variacionHabitante = variacionHabitante,
        redondeo = redondeo
    )

    @Test
    fun `formula de FRONTIS_ML, cantidad con 2 decimales aunque venga con otra escala - Miraflores Ord 665-MM 2026`() {
        val c = cuota(Base.FRONTIS_ML, BigDecimal("5.0856"), BigDecimal("10.5"), BigDecimal("53.39"), redondeo = "2:ABAJO")
        assertEquals("5.0856 S/ por ml × 10.50 ml = 53.39", Calculo.formula(c))
    }

    @Test
    fun `formula de AREA_CONSTRUIDA_M2, con el factor de habitantes - Miraflores Ord 665-MM 2026`() {
        val c =
            cuota(
                Base.AREA_CONSTRUIDA_M2,
                BigDecimal("0.0514"),
                BigDecimal("100.00"),
                BigDecimal("10.28"),
                habitantes = 4,
                promedioHabitantes = BigDecimal("2"),
                variacionHabitante = BigDecimal("0.50"),
                redondeo = "2:ABAJO"
            )
        assertEquals("0.0514 S/ por m² × 100.00 m² × (1 + (4 − 2) × 0.50) = 10.28", Calculo.formula(c))
    }

    @Test
    fun `formula de PREDIO con redondeo termina en el monto`() {
        val c = cuota(Base.PREDIO, BigDecimal("85.9875"), BigDecimal("1"), BigDecimal("85.99"), redondeo = "2:ABAJO")
        assertEquals("85.9875 S/ por predio = 85.99", Calculo.formula(c))
    }

    @Test
    fun `formula de PREDIO sin redondeo no repite el monto`() {
        val c = cuota(Base.PREDIO, BigDecimal("85.9875"), BigDecimal("1"), BigDecimal("85.9875"))
        assertEquals("85.9875 S/ por predio", Calculo.formula(c))
    }

    @Test
    fun `formula de una cuota sin desglose (anterior al PR) es soles(monto)`() {
        val c = cuota(null, monto = BigDecimal("8.50"))
        assertEquals(soles(BigDecimal("8.50")), Calculo.formula(c))
    }
}
