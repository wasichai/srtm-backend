package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// Servicios' readers: Arbitrios' enVigor, extracted here so every reader of a servicio's rules shares it (Task 1 of
// wasichai/srtm-backend#80). an unknown value (base, dimension, malformed redondeo) counts as absent, same as a
// missing row: the importer already rejects it, so nothing here guesses
class ServiciosTest {
    private val dia = LocalDate.of(2026, 3, 15)

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

    @Test
    fun `without rows, every reader defaults`() {
        assertEquals(Base.PREDIO, Servicios.base(emptyList(), "BARRIDO", dia))
        assertEquals(listOf(Dimension.ZONA, Dimension.USO), Servicios.dimensiones(emptyList(), "PARQUES", dia))
        assertNull(Servicios.habitantes(emptyList(), "RRSS", "01A", "2", dia))
        assertNull(Servicios.redondeo(emptyList(), dia))
    }

    @Test
    fun `a servicio's base is read by its codigo, in force that day`() {
        val fila = param(Llaves.BASE_ARBITRIO, "BARRIDO", texto = "FRONTIS_ML")
        assertEquals(Base.FRONTIS_ML, Servicios.base(listOf(fila), "BARRIDO", dia))

        val de2027 = param(Llaves.BASE_ARBITRIO, "BARRIDO", texto = "FRONTIS_ML", desde = LocalDate.of(2027, 1, 1))
        assertEquals(Base.PREDIO, Servicios.base(listOf(de2027), "BARRIDO", dia))
    }

    @Test
    fun `a base that is not a known value counts as absent`() {
        val fila = param(Llaves.BASE_ARBITRIO, "BARRIDO", texto = "KILOS")
        assertEquals(Base.PREDIO, Servicios.base(listOf(fila), "BARRIDO", dia))
    }

    @Test
    fun `a servicio's dimensiones are read comma-separated, trimmed`() {
        val fila = param(Llaves.DIMENSIONES_ARBITRIO, "PARQUES", texto = "ZONA, INFLUENCIA ,AFLUENCIA")
        assertEquals(listOf(Dimension.ZONA, Dimension.INFLUENCIA, Dimension.AFLUENCIA), Servicios.dimensiones(listOf(fila), "PARQUES", dia))
    }

    @Test
    fun `a dimension that is not a known value counts as absent, the whole row`() {
        val fila = param(Llaves.DIMENSIONES_ARBITRIO, "PARQUES", texto = "ZONA, CLIMA")
        assertEquals(listOf(Dimension.ZONA, Dimension.USO), Servicios.dimensiones(listOf(fila), "PARQUES", dia))
    }

    @Test
    fun `habitantes needs both the promedio and the variacion, by servicio, zona and uso`() {
        val promedio = param(Llaves.HABITANTES_PROMEDIO, "RRSS:01A:2", valor = "2")
        val variacion = param(Llaves.HABITANTES_VARIACION, "RRSS:01A:2", valor = "0.5")
        assertEquals(
            Habitantes(BigDecimal("2"), BigDecimal("0.5")),
            Servicios.habitantes(listOf(promedio, variacion), "RRSS", "01A", "2", dia)
        )

        assertNull(Servicios.habitantes(listOf(promedio), "RRSS", "01A", "2", dia))
        assertNull(Servicios.habitantes(listOf(variacion), "RRSS", "01A", "2", dia))
    }

    @Test
    fun `the redondeo is global, read by its clave vacía`() {
        val abajo = param(Llaves.REDONDEO_ARBITRIO, "", texto = "2:ABAJO")
        val redondeo = Servicios.redondeo(listOf(abajo), dia)
        assertEquals(Redondeo(2, RoundingMode.DOWN), redondeo)
        assertEquals(BigDecimal("53.39"), redondeo!!.aplicar(BigDecimal("53.3988")))

        val medio = param(Llaves.REDONDEO_ARBITRIO, "", texto = "4:MEDIO")
        val redondeoMedio = Servicios.redondeo(listOf(medio), dia)
        assertEquals(BigDecimal("2.5962"), redondeoMedio!!.aplicar(BigDecimal("2.59619880")))
    }

    @Test
    fun `Redondeo de rejects a malformed texto`() {
        assertNull(Redondeo.de("dos"))
        assertNull(Redondeo.de("2:CERCA"))
        assertNull(Redondeo.de(null))
        assertNull(Redondeo.de(""))
    }
}
