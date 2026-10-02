package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

// the padron cut in lotes and how many workers an instance runs (wasichai/srtm-backend#53, #54)
class LotesTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    private fun contribuyente(codigo: String) =
        ContribuyenteAEmitir(UUID.randomUUID(), codigo, "CONTRIBUYENTE $codigo", listOf(UUID.randomUUID(), UUID.randomUUID()))

    @Test
    fun `the padron is cut in lotes of the given size, in order`() {
        val padron = (1..5).map { contribuyente("C%03d".format(it)) }

        val lotes = cortarEnLotes(padron, 2)

        assertEquals(listOf(2, 2, 1), lotes.map { it.size })
        assertEquals(padron.map { it.codigo }, lotes.flatten().map { it.codigo })
    }

    @Test
    fun `a size below one is refused`() {
        assertThrows<IllegalArgumentException> { cortarEnLotes(listOf(contribuyente("C001")), 0) }
    }

    @Test
    fun `contribuyentes go to json and back`() {
        val lote = listOf(contribuyente("C001"), contribuyente("C002").copy(nombre = "ÑAÑEZ \"EL\" PEREZ", predios = emptyList()))

        assertEquals(lote, contribuyentesDe(contribuyentesJson(lote)))
    }

    @Test
    fun `errores come from json, none when empty`() {
        val errores = listOf(ErrorEmision("C001", "sin predios"))

        assertEquals(errores, erroresDe(erroresJson(errores)))
        assertEquals(emptyList<ErrorEmision>(), erroresDe(null))
        assertEquals(emptyList<ErrorEmision>(), erroresDe(" "))
    }

    @Test
    fun `auto workers are the cores when memory allows`() {
        assertEquals(8, cuantosTrabajadores("auto", 8, 8 * gib, 20))
    }

    @Test
    fun `auto workers are bounded by memory`() {
        // (1024 - 512) / 300 = 1
        assertEquals(1, cuantosTrabajadores("auto", 8, 1 * gib, 20))
    }

    @Test
    fun `auto workers are at least one`() {
        assertEquals(1, cuantosTrabajadores("auto", 4, 256 * mib, 20))
    }

    @Test
    fun `workers never take more than the pool minus two`() {
        assertEquals(18, cuantosTrabajadores("auto", 32, 64 * gib, 20))
        assertEquals(18, cuantosTrabajadores("50", 8, 8 * gib, 20))
    }

    @Test
    fun `zero workers is allowed`() {
        assertEquals(0, cuantosTrabajadores("0", 8, 8 * gib, 20))
    }

    @Test
    fun `a value that is no number nor auto is refused`() {
        assertThrows<IllegalArgumentException> { cuantosTrabajadores("muchos", 8, 8 * gib, 20) }
        assertThrows<IllegalArgumentException> { cuantosTrabajadores("-1", 8, 8 * gib, 20) }
    }
}
