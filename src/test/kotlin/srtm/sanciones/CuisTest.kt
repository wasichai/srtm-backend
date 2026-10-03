package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.sanciones.Ficticios.codigo
import srtm.sanciones.Ficticios.d
import wasichai.core.common.ConflictException

// rentas' CodigoInfraccionTest and MantenerCatalogoDeInfraccionesTest: the CUIS is never edited, a change closes the
// version in force and adds one. the % are FICTITIOUS
class CuisTest {
    @Test
    fun `a new version has no vigencia_hasta and is the one in force`() {
        val c = codigo(desde = "2026-01-01")
        assertNull(c.vigenciaHasta)
        assertEquals(claveVigenteDe(ADMINISTRATIVA, "A-042"), c.claveVigente)
        assertEquals(emptyList<Any>(), invariantes(c))
    }

    @Test
    fun `without its base legal it is not written`() {
        assertEquals(listOf("base_legal"), invariantes(codigo().copy(baseLegal = "  ")).map { it.field })
    }

    @Test
    fun `the code is trimmed and upper-cased`() {
        assertEquals("G-01", Cuis.normalizar("  g-01  "))
    }

    @Test
    fun `it rules on both ends of its vigencia`() {
        val cerrado = Cuis.cerrar(codigo(desde = "2026-01-01"), d("2026-07-01"))
        assertTrue(Cuis.rigeEn(cerrado, d("2026-01-01")), "the first day")
        assertTrue(Cuis.rigeEn(cerrado, d("2026-06-30")), "the last day")
        assertFalse(Cuis.rigeEn(cerrado, d("2026-07-01")), "the day after")
        assertFalse(Cuis.rigeEn(cerrado, d("2025-12-31")), "the day before")
    }

    @Test
    fun `closing changes vigencia_hasta and clave_vigente and nothing else`() {
        val vigente = codigo(desde = "2026-01-01")
        val cerrado = Cuis.cerrar(vigente, d("2026-07-01"))
        assertEquals(d("2026-06-30"), cerrado.vigenciaHasta)
        assertNull(cerrado.claveVigente)
        assertEquals(vigente.copy(vigenciaHasta = cerrado.vigenciaHasta, claveVigente = null), cerrado)
        assertEquals(emptyList<Any>(), invariantes(cerrado), "the closed row still holds")
    }

    @Test
    fun `a closed version is not closed again (409)`() {
        val cerrado = Cuis.cerrar(codigo(desde = "2026-01-01"), d("2026-03-01"))
        assertThrows(ConflictException::class.java) { Cuis.cerrar(cerrado, d("2026-04-01")) }
    }

    @Test
    fun `a new version starts after the one in force (422)`() {
        val vigente = codigo(desde = "2026-06-01")
        val antes = assertThrows(NoProcede::class.java) { Cuis.cerrar(vigente, d("2026-01-01")) }
        assertEquals(listOf("vigencia_desde"), antes.violations.map { it.field })
        assertThrows(NoProcede::class.java) { Cuis.cerrar(vigente, d("2026-06-01")) }
    }

    @Test
    fun `the version on a day is the one in force then, not the latest`() {
        val v1 = Cuis.cerrar(codigo(desde = "2026-01-01", primera = "8"), d("2026-07-01"))
        val v2 = codigo(desde = "2026-07-01", primera = "10")
        assertEquals(v1, Cuis.vigenteA(listOf(v1, v2), d("2026-03-01")))
        assertEquals(v2, Cuis.vigenteA(listOf(v1, v2), d("2026-08-01")))
        assertNull(Cuis.vigenteA(listOf(v1, v2), d("2025-12-31")), "before the first, none")
    }

    @Test
    fun `a new version closes the one in force the day before, and the old one stays`() {
        val v1 = codigo(desde = "2026-01-01")
        val cerrada = Cuis.versionNueva(listOf(v1), d("2026-07-01"))!!
        assertEquals(d("2026-06-30"), cerrada.vigenciaHasta)
        assertEquals(v1.descripcion, cerrada.descripcion)
        assertEquals(v1.id, cerrada.id)
    }

    @Test
    fun `a code without a version in force gets its first, and never overlaps an old one`() {
        assertNull(Cuis.versionNueva(emptyList(), d("2026-01-01")))
        val cerrada = codigo(desde = "2026-01-01", hasta = "2026-06-30")
        assertNull(Cuis.versionNueva(listOf(cerrada), d("2026-07-01")))
        assertThrows(NoProcede::class.java) { Cuis.versionNueva(listOf(cerrada), d("2026-06-30")) }
        // nor one in force after a closed one
        val vigente = codigo(desde = "2026-07-01")
        assertThrows(NoProcede::class.java) { Cuis.versionNueva(listOf(cerrada, vigente), d("2026-03-01")) }
    }
}
