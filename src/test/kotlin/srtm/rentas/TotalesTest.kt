package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

// valor_autoavaluo is the whole predio's (or secuencia's), valor_condominio a condómino's part of it
class TotalesTest {
    @Test
    fun `a contribuyente adds up its parts, and a sole owner's whole autoavaluo`() {
        val t =
            totalesDeContribuyente(
                listOf(
                    declaracion("A", "P", valorCondominio = "6000.25", valorAfecto = "6000.25"),
                    declaracion("A", "Q", valorAutoavaluo = "5000.50", valorAfecto = "5000.50")
                )
            )
        assertEquals(2, t.declaraciones)
        assertEquals(BigDecimal("11000.75"), t.autoavaluo)
        assertEquals(BigDecimal("11000.75"), t.valorAfecto)
    }

    @Test
    fun `a contribuyente counts a missing value as zero`() {
        val t =
            totalesDeContribuyente(
                listOf(
                    Declaracion(valorAutoavaluo = BigDecimal("100.50"), valorAfecto = BigDecimal("50")),
                    Declaracion(valorAutoavaluo = BigDecimal("20")),
                    Declaracion()
                )
            )
        assertEquals(3, t.declaraciones)
        assertEquals(BigDecimal("120.50"), t.autoavaluo)
        assertEquals(BigDecimal("50"), t.valorAfecto)
    }

    @Test
    fun `a predio counts its autoavaluo once, however many condominos declare it`() {
        val t =
            totalesDePredio(
                listOf(
                    declaracion("A", "P", valorCondominio = "6000.25", valorAfecto = "6000.25"),
                    declaracion("B", "P", valorCondominio = "4000.25", valorAfecto = "4000.25")
                )
            )
        assertEquals(2, t.declaraciones)
        assertEquals(BigDecimal("10000.50"), t.autoavaluo)
        // each titular's afecto is its own part: they add up
        assertEquals(BigDecimal("10000.50"), t.valorAfecto)
    }

    @Test
    fun `a predio adds up its secuencias de uso`() {
        val t =
            totalesDePredio(
                listOf(
                    declaracion("A", "P", valorCondominio = "6000.25"),
                    declaracion("B", "P", valorCondominio = "4000.25"),
                    declaracion("A", "P", secuenciaUso = "2", valorAutoavaluo = "3000")
                )
            )
        assertEquals(BigDecimal("13000.50"), t.autoavaluo)
    }

    @Test
    fun `condominos that disagree on the autoavaluo count the highest, and a missing one counts as zero`() {
        val t =
            totalesDePredio(
                listOf(
                    declaracion("A", "P", valorAutoavaluo = "9000", valorCondominio = "5400"),
                    declaracion("B", "P", valorCondominio = "4000.25"),
                    declaracion("C", "P", valorAutoavaluo = null),
                    declaracion("A", "P", secuenciaUso = "2", valorAutoavaluo = null)
                )
            )
        assertEquals(BigDecimal("10000.50"), t.autoavaluo)
        assertEquals(BigDecimal.ZERO, t.valorAfecto)
    }

    // a condómino's declaration carries its part; a sole owner's does not
    private fun declaracion(
        contribuyente: String,
        predio: String,
        secuenciaUso: String = "1",
        valorAutoavaluo: String? = "10000.50",
        valorCondominio: String? = null,
        valorAfecto: String? = null
    ) = Declaracion(
        contribuyente = contribuyente,
        predio = predio,
        anio = 2026,
        secuenciaUso = secuenciaUso,
        condicionPropiedad = if (valorCondominio == null) "PROPIETARIO UNICO" else "CONDOMINO",
        valorAutoavaluo = valorAutoavaluo?.let(::BigDecimal),
        valorCondominio = valorCondominio?.let(::BigDecimal),
        valorAfecto = valorAfecto?.let(::BigDecimal)
    )
}
