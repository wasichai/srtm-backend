package srtm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// a page cut in memory (Paginas.kt): the one the actas by fase, the vencidas and the anuncios by estado answer
class PaginasTest {
    private val filas = (1..45).toList()

    @Test
    fun `a page of the rows, with the total of them`() {
        val segunda = filas.pagina(1, 20)
        assertEquals((21..40).toList(), segunda.content)
        assertEquals(1, segunda.page)
        assertEquals(20, segunda.size)
        assertEquals(45L, segunda.totalElements)
        assertEquals(3, segunda.totalPages)
        assertEquals((41..45).toList(), filas.pagina(2, 20).content, "the last one, short")
    }

    @Test
    fun `past the last row it is empty, however far the page`() {
        assertEquals(emptyList<Int>(), filas.pagina(3, 20).content)
        // page × size beyond an Int: no overflow into a negative drop (a 500)
        val lejos = filas.pagina(Int.MAX_VALUE, 200)
        assertEquals(emptyList<Int>(), lejos.content)
        assertEquals(45L, lejos.totalElements)
    }
}
