package srtm.brecha

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

// #203's pure rules: when an automatic corrida runs (disparo) and the aviso of what the catastro changed (aviso)
class SeguimientoTest {
    private val hora = 2

    private fun ahora(
        hora: Int,
        minuto: Int = 0,
        segundo: Int = 0
    ) = ZonedDateTime.of(2026, 10, 25, hora, minuto, segundo, 0, Seguimiento.LIMA)

    private fun instante(
        anio: Int,
        mes: Int,
        dia: Int,
        hora: Int = 2,
        minuto: Int = 0,
        segundo: Int = 0
    ) = ZonedDateTime.of(anio, mes, dia, hora, minuto, segundo, 0, Seguimiento.LIMA).toInstant()

    @Test
    fun `before the hour nothing runs`() {
        assertEquals(Disparo.NINGUNO, Seguimiento.disparo(ahora(1, 59), hora, pendientes = 3, ultima = null, ultimaFallida = false))
    }

    @Test
    fun `at or after the hour a change runs it, once a day`() {
        val ultima = instante(2026, 10, 24, 2, 10)
        assertEquals(Disparo.CAMBIOS, Seguimiento.disparo(ahora(2, 0), hora, pendientes = 1, ultima = ultima, ultimaFallida = false))
        assertEquals(Disparo.CAMBIOS, Seguimiento.disparo(ahora(23, 0), hora, pendientes = 1, ultima = ultima, ultimaFallida = false))
        val ultimaDeHoy = instante(2026, 10, 25, 2, 0, 30)
        assertEquals(Disparo.NINGUNO, Seguimiento.disparo(ahora(23, 0), hora, pendientes = 1, ultima = ultimaDeHoy, ultimaFallida = false))
    }

    @Test
    fun `a failed automatic corrida runs again the next day`() {
        val ultima = instante(2026, 10, 24, 2, 0)
        assertEquals(Disparo.CAMBIOS, Seguimiento.disparo(ahora(2, 0), hora, pendientes = 0, ultima = ultima, ultimaFallida = true))
    }

    @Test
    fun `without changes only the weekly runs`() {
        assertEquals(
            Disparo.NINGUNO,
            Seguimiento.disparo(ahora(2, 0), hora, pendientes = 0, ultima = instante(2026, 10, 19, 2, 0), ultimaFallida = false)
        )
        assertEquals(
            Disparo.SEMANAL,
            Seguimiento.disparo(ahora(2, 0), hora, pendientes = 0, ultima = instante(2026, 10, 18, 2, 0), ultimaFallida = false)
        )
        assertEquals(Disparo.SEMANAL, Seguimiento.disparo(ahora(2, 0), hora, pendientes = 0, ultima = null, ultimaFallida = false))
    }

    private fun fila(
        codigo: String,
        delta: String
    ) = FilaBrecha(codigoCatastro = codigo, deltaAutoavaluo = BigDecimal(delta))

    private fun cambio(
        codigo: String?,
        objeto: String? = "predio_catastral",
        en: String = "2026-10-20T00:00:00Z",
        entrega: String? = null
    ) = RegistroCambio(codigoMunicipal = codigo, objeto = objeto, en = en, entrega = entrega)

    @Test
    fun `the aviso groups the cambios by codigo and marks what passes 5 UIT`() {
        val uit = BigDecimal("5500.00")
        val filas =
            listOf(
                fila("A", "27500.01"),
                fila("B", "27500.00"),
                fila("C", "-30000.00")
            )
        val cambios =
            listOf(
                cambio("A", objeto = "construccion", entrega = "E1"),
                cambio("A", objeto = "predio_catastral", entrega = null),
                cambio("B"),
                cambio("C"),
                cambio("D"),
                cambio(null)
            )

        val aviso = Seguimiento.aviso(cambios, filas, uit)

        assertEquals(listOf("C", "A", "B", "D"), aviso.map { it.codigoMunicipal })
        val a = aviso.single { it.codigoMunicipal == "A" }
        assertEquals(2, a.cambios)
        assertEquals(listOf("construccion", "predio_catastral"), a.objetos)
        assertEquals(listOf("E1"), a.entregas)
        assertEquals(Seguimiento.SUPERA_5_UIT, a.marca)
        assertEquals(Seguimiento.SUPERA_5_UIT, aviso.single { it.codigoMunicipal == "C" }.marca)
        assertNull(aviso.single { it.codigoMunicipal == "B" }.marca)
        assertNull(aviso.single { it.codigoMunicipal == "D" }.marca)
    }

    @Test
    fun `the aviso joins by the codigo as llave compares it`() {
        val filas = listOf(fila("1-1-6", "100.00"))
        val aviso = Seguimiento.aviso(listOf(cambio("01-01-0006")), filas, BigDecimal("5500.00"))

        assertEquals(BigDecimal("100.00"), aviso.single().deltaAutoavaluo)
    }
}
