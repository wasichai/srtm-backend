package srtm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import srtm.impuesto.enVigor
import srtm.impuesto.vigenteEl
import java.math.BigDecimal
import java.time.LocalDate

// the one vigencia the modules read their rows by (Vigencia.kt), and the parámetro in force that the predial, the
// anuncios and the sanciones read with it
class VigenciaTest {
    @Test
    fun `both ends count`() {
        assertTrue(vigente(d("2026-01-01"), d("2026-06-30"), d("2026-01-01")), "the first day")
        assertTrue(vigente(d("2026-01-01"), d("2026-06-30"), d("2026-06-30")), "the last day")
        assertFalse(vigente(d("2026-01-01"), d("2026-06-30"), d("2025-12-31")), "the day before")
        assertFalse(vigente(d("2026-01-01"), d("2026-06-30"), d("2026-07-01")), "the day after")
    }

    @Test
    fun `without hasta it rules from desde on, and without desde never`() {
        assertTrue(vigente(d("2026-01-01"), null, d("2099-12-31")))
        assertFalse(vigente(d("2026-01-01"), null, d("2025-12-31")))
        assertFalse(vigente(null, null, d("2026-01-01")))
        assertFalse(vigente(null, d("2026-12-31"), d("2026-01-01")))
    }

    @Test
    fun `a parametro is in force by its own vigencia`() {
        val uit = parametro("UIT", desde = "2026-01-01", hasta = "2026-12-31")
        assertTrue(uit.vigenteEl(d("2026-12-31")))
        assertFalse(uit.vigenteEl(d("2027-01-01")))
    }

    @Test
    fun `the row in force is the latest that started, of its tipo`() {
        val vieja = parametro("UIT", desde = "2025-01-01", valor = "5350")
        val nueva = parametro("UIT", desde = "2026-01-01", valor = "5500")
        val otra = parametro("PREDIAL_MINIMO", desde = "2026-02-01", valor = "0.6")
        val filas = listOf(nueva, otra, vieja)
        assertEquals(nueva, filas.enVigor("UIT", d("2026-03-01")))
        assertEquals(vieja, filas.enVigor("UIT", d("2025-12-31")), "the newer one had not started")
        assertNull(filas.enVigor("UIT", d("2024-12-31")), "none had started")
    }

    @Test
    fun `only the rows that coincide count`() {
        val primero = parametro("TRAMO_PREDIAL", clave = "1", desde = "2026-01-01", valor = "0.2")
        val segundo = parametro("TRAMO_PREDIAL", clave = "2", desde = "2026-01-01", valor = "0.6")
        val filas = listOf(primero, segundo)
        assertEquals(segundo, filas.enVigor("TRAMO_PREDIAL", d("2026-01-01")) { it.clave == "2" })
        assertNull(filas.enVigor("TRAMO_PREDIAL", d("2026-01-01")) { it.clave == "3" })
    }

    private fun d(texto: String) = LocalDate.parse(texto)

    private fun parametro(
        tipo: String,
        clave: String? = null,
        desde: String,
        hasta: String? = null,
        valor: String = "1"
    ) = ParametroTributario(
        tipo = tipo,
        clave = clave,
        vigenciaDesde = d(desde),
        vigenciaHasta = hasta?.let(::d),
        valorNumerico = BigDecimal(valor)
    )
}
