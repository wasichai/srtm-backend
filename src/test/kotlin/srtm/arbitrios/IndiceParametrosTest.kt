package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import java.math.BigDecimal
import java.time.LocalDate

// IndiceParametros: the rows of a tipo, and of a tipo and trimmed clave, in the order they came. its enVigor answers
// what enVigor answers over the whole list: the row in force that day that matches, the latest vigencia_desde, the
// first one listed among equals
class IndiceParametrosTest {
    private val dia = LocalDate.of(2026, 3, 15)

    private fun param(
        id: String,
        tipo: String?,
        clave: String?,
        desde: LocalDate = LocalDate.of(2026, 1, 1),
        hasta: LocalDate? = null,
        valor: String? = "1"
    ) = ParametroTributario(id = id, tipo = tipo, clave = clave, vigenciaDesde = desde, vigenciaHasta = hasta, valorNumerico = valor?.let(::BigDecimal))

    private val filas =
        listOf(
            param("otro-modulo", "UIT", "LIMPIEZA:Z1:CASA"),
            param("vieja", Llaves.TASA_ARBITRIO, "LIMPIEZA:Z1:CASA", desde = LocalDate.of(2025, 1, 1)),
            param("primera", Llaves.TASA_ARBITRIO, " LIMPIEZA:Z1:CASA "),
            param("empate", Llaves.TASA_ARBITRIO, "LIMPIEZA:Z1:CASA"),
            param("futura", Llaves.TASA_ARBITRIO, "LIMPIEZA:Z1:CASA", desde = LocalDate.of(2026, 6, 1)),
            param("vencida", Llaves.TASA_ARBITRIO, "LIMPIEZA:Z2:CASA", hasta = LocalDate.of(2026, 2, 28)),
            param("sin-valor", Llaves.TASA_ARBITRIO, "LIMPIEZA:Z3:CASA", valor = null),
            param("sin-clave", Llaves.REDONDEO_ARBITRIO, null),
            param("sin-tipo", null, "LIMPIEZA:Z1:CASA")
        )
    private val indice = IndiceParametros(filas)

    @Test
    fun `groups by tipo and by trimmed clave, in the order they came`() {
        assertEquals(listOf("vieja", "primera", "empate", "futura", "vencida", "sin-valor"), indice.deTipo(Llaves.TASA_ARBITRIO).map { it.id })
        assertEquals(listOf("vieja", "primera", "empate", "futura"), indice.deClave(Llaves.TASA_ARBITRIO, "LIMPIEZA:Z1:CASA").map { it.id })
        assertEquals(listOf("sin-clave"), indice.deTipo(Llaves.REDONDEO_ARBITRIO).map { it.id })
        assertEquals(emptyList<ParametroTributario>(), indice.deTipo(Llaves.ARBITRIO_ZONA))
        assertEquals(emptyList<ParametroTributario>(), indice.deClave(Llaves.TASA_ARBITRIO, " LIMPIEZA:Z1:CASA "))
    }

    @Test
    fun `answers what enVigor answers over the whole list`() {
        for (clave in listOf("LIMPIEZA:Z1:CASA", "LIMPIEZA:Z2:CASA", "LIMPIEZA:Z3:CASA", "LIMPIEZA:Z9:CASA")) {
            for (d in listOf(dia, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), LocalDate.of(2025, 5, 1))) {
                val coincide = { it: ParametroTributario -> it.clave?.trim() == clave && it.valorNumerico != null }
                assertSame(
                    enVigor(filas, Llaves.TASA_ARBITRIO, d, coincide),
                    indice.enVigor(Llaves.TASA_ARBITRIO, clave, d) { it.valorNumerico != null },
                    "$clave $d"
                )
                assertSame(enVigor(filas, Llaves.TASA_ARBITRIO, d, coincide), indice.enVigor(Llaves.TASA_ARBITRIO, d, coincide), "$clave $d")
            }
        }
        // among equals, the first one listed
        assertEquals("primera", indice.enVigor(Llaves.TASA_ARBITRIO, "LIMPIEZA:Z1:CASA", dia) { true }?.id)
        assertNull(indice.enVigor(Llaves.TASA_ARBITRIO, "LIMPIEZA:Z3:CASA", dia) { it.valorNumerico != null })
    }
}
