package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate

// #147: a rectificatoria is a new declaración that replaces the one it rectifies (SUSTITUIDA, read-only); a year's
// declaración may start from another year's
class RectificacionTest {
    private val hoy = LocalDate.of(2026, 10, 20)

    private val origen =
        Declaracion(
            id = "d-1",
            contribuyente = "c-1",
            predio = "p-1",
            anio = 2025,
            secuenciaUso = "001",
            condicionPropiedad = "SOCIEDAD CONYUGAL",
            porcentajeCondominio = BigDecimal("100"),
            uso = "CASA HABITACION",
            areaTerreno = BigDecimal("200"),
            valorAutoavaluo = BigDecimal("50000"),
            valorCondominio = BigDecimal("50000"),
            deduccion = BigDecimal("1000"),
            valorAfecto = BigDecimal("49000"),
            numeroDeclaracion = 17,
            motivo = "ACTUALIZACION",
            medioDeterminacion = "DECLARACION JURADA",
            medioPresentacion = "VIRTUAL",
            fechaPresentacion = LocalDate.of(2025, 3, 1),
            tipoAdquisicion = "COMPRA VENTA",
            fechaAdquisicion = LocalDate.of(2020, 5, 4),
            documentosSustento = "MINUTA",
            condicionEspecial = "PENSIONISTA",
            estado = VIGENTE
        )

    @Test
    fun `a sustituida declaration is not vigente, nor modified again`() {
        val sustituida = Declaracion(estado = SUSTITUIDA)
        assertFalse(vigente(sustituida))
        val e = assertThrows<ValidationException> { modificable(sustituida) }
        assertEquals("estado", e.violations.single().field)
        // each estado says its own why
        val anulada = assertThrows<ValidationException> { modificable(Declaracion(estado = ANULADA)) }
        assertEquals("La declaración está anulada", anulada.message)
        assertEquals("La declaración fue sustituida por una rectificatoria", e.message)
    }

    @Test
    fun `the copy keeps what is declared and the titular's data, not its number, estado, motivo or presentation`() {
        val copia = copiaDe(origen.copy(estado = ANULADA, motivo = DESCARGO, motivoAnulacion = "error", fechaAnulacion = hoy, sustituye = "d-0"))

        assertNull(copia.id)
        assertNull(copia.numeroDeclaracion)
        assertNull(copia.estado)
        assertNull(copia.motivo)
        assertNull(copia.motivoAnulacion)
        assertNull(copia.fechaAnulacion)
        assertNull(copia.sustituye)
        assertNull(copia.fechaSustitucion)
        assertNull(copia.medioPresentacion)
        assertNull(copia.fechaPresentacion)
        assertEquals(origen.contribuyente, copia.contribuyente)
        assertEquals(origen.predio, copia.predio)
        assertEquals(origen.anio, copia.anio)
        assertEquals(origen.secuenciaUso, copia.secuenciaUso)
        assertEquals(origen.uso, copia.uso)
        assertEquals(origen.valorAutoavaluo, copia.valorAutoavaluo)
        assertEquals(origen.porcentajeCondominio, copia.porcentajeCondominio)
        assertEquals(origen.condicionPropiedad, copia.condicionPropiedad)
        assertEquals(origen.deduccion, copia.deduccion)
        assertEquals(origen.tipoAdquisicion, copia.tipoAdquisicion)
        assertEquals(origen.fechaAdquisicion, copia.fechaAdquisicion)
        assertEquals(origen.documentosSustento, copia.documentosSustento)
        assertEquals(origen.condicionEspecial, copia.condicionEspecial)
        assertEquals(origen.medioDeterminacion, copia.medioDeterminacion)
    }

    @Test
    fun `rectifying leaves the declaration sustituida on the day, with what it said`() {
        val d = sustituida(origen, hoy)
        assertEquals(SUSTITUIDA, d.estado)
        assertEquals(hoy, d.fechaSustitucion)
        assertEquals(origen.copy(estado = SUSTITUIDA, fechaSustitucion = hoy), d)
        assertEquals("estado", assertThrows<ValidationException> { sustituida(d, hoy) }.violations.single().field)
        assertEquals("estado", assertThrows<ValidationException> { sustituida(origen.copy(estado = ANULADA), hoy) }.violations.single().field)
    }

    @Test
    fun `another year is declared from one of the same predio and contribuyente`() {
        desdeOtroAnio(origen, "c-1", "p-1", 2026)
        assertEquals("predio_id", assertThrows<ValidationException> { desdeOtroAnio(origen, "c-1", "p-2", 2026) }.violations.single().field)
        assertEquals("desde", assertThrows<ValidationException> { desdeOtroAnio(origen, "c-2", "p-1", 2026) }.violations.single().field)
    }

    @Test
    fun `the same year is rectified, not declared again`() {
        val e = assertThrows<ValidationException> { desdeOtroAnio(origen, "c-1", "p-1", 2025) }
        assertEquals("anio", e.violations.single().field)
        assertEquals("En el mismo año corresponde rectificar la declaración", e.message)
    }

    @Test
    fun `a rectificatoria and the one it replaces are annulled, not deleted`() {
        assertNull(bajaDeRectificacion(origen))
        assertEquals("La declaración fue sustituida por una rectificatoria: no se puede eliminar", bajaDeRectificacion(origen.copy(estado = SUSTITUIDA)))
        assertEquals("La declaración rectifica otra: anúlela en lugar de eliminarla", bajaDeRectificacion(origen.copy(sustituye = "d-0")))
    }

    @Test
    fun `a rectificatoria stays in the predio, year and secuencia of the one it replaces`() {
        val rectificatoria = origen.copy(sustituye = "d-0")
        enSuLugar(rectificatoria, rectificatoria.copy(uso = "COMERCIO", secuenciaUso = "1"))
        assertEquals("anio", assertThrows<ValidationException> { enSuLugar(rectificatoria, rectificatoria.copy(anio = 2026)) }.violations.single().field)
        assertEquals("predio", assertThrows<ValidationException> { enSuLugar(rectificatoria, rectificatoria.copy(predio = "p-2")) }.violations.single().field)
        assertEquals(
            "secuencia_uso",
            assertThrows<ValidationException> { enSuLugar(rectificatoria, rectificatoria.copy(secuenciaUso = "002")) }.violations.single().field
        )
        // any other declaración moves
        enSuLugar(origen, origen.copy(anio = 2026))
    }

    @Test
    fun `a rectificatoria keeps its motivo when edited`() {
        assertEquals(RECTIFICACION, motivoAlEditar(RECTIFICACION))
    }
}
