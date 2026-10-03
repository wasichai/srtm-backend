package srtm.anuncios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import srtm.Observacion
import srtm.impuesto.ParametroTributario
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

// rentas' AnuncioYSusActosTest: the anuncio, its derived estado and its acts, without database nor clock. every tasa
// is FICTITIOUS
class AnunciosTest {
    @Nested
    inner class ElEstado {
        @Test
        fun `authorized and within its term - VIGENTE`() {
            assertEquals(VIGENTE, Anuncios.estado(listOf(autorizacion(AUTORIZADO, fin(2026))), AUTORIZADO))
        }

        @Test
        fun `the same anuncio is VIGENTE on its last day and VENCIDO the next - the day is an argument`() {
            val historial = listOf(autorizacion(AUTORIZADO, fin(2026)))
            assertEquals(VIGENTE, Anuncios.estado(historial, fin(2026)), "the last day of its vigencia still rules")
            assertEquals(VENCIDO, Anuncios.estado(historial, fin(2026).plusDays(1)))
        }

        @Test
        fun `a movimiento after the day asked does not count`() {
            val cese = LocalDate.of(2026, 6, 30)
            val historial = listOf(autorizacion(AUTORIZADO, fin(2026)), cese(cese))
            assertEquals(VIGENTE, Anuncios.estado(historial, cese.minusDays(1)), "a padrón of may does not say july's estado")
            assertEquals(CESADO, Anuncios.estado(historial, cese))
        }

        @Test
        fun `the retiro wins over the cese, and the cese over the vencimiento`() {
            val cese = LocalDate.of(2026, 6, 30)
            val retiro = LocalDate.of(2026, 7, 15)
            assertEquals(CESADO, Anuncios.estado(listOf(autorizacion(AUTORIZADO, fin(2026)), cese(cese)), fin(2027)))
            assertEquals(RETIRADO, Anuncios.estado(listOf(autorizacion(AUTORIZADO, fin(2026)), cese(cese), retiro(retiro)), retiro))
        }

        @Test
        fun `without a term it never expires`() {
            assertEquals(VIGENTE, Anuncios.estado(listOf(autorizacion(AUTORIZADO, null)), AUTORIZADO.plusYears(10)))
        }

        @Test
        fun `only VIGENTE and VENCIDO admit a renewal`() {
            assertTrue(Anuncios.admiteRenovacion(VIGENTE))
            assertTrue(Anuncios.admiteRenovacion(VENCIDO))
            assertFalse(Anuncios.admiteRenovacion(CESADO), "a cesado anuncio accrues no other tasa")
            assertFalse(Anuncios.admiteRenovacion(RETIRADO))
        }
    }

    @Nested
    inner class LaVigencia {
        private val historial = listOf(autorizacion(AUTORIZADO, fin(2026)), renovacion(LocalDate.of(2027, 1, 15), 2027, fin(2027)))

        @Test
        fun `the vigencia in force is the last accruing act's, not the anuncio's`() {
            assertEquals(fin(2027), Anuncios.vigencia(historial, LocalDate.of(2027, 2, 1)))
            assertEquals(VIGENTE, Anuncios.estado(historial, LocalDate.of(2027, 6, 1)))
        }

        @Test
        fun `tomorrow's prorroga does not count today`() {
            assertEquals(fin(2026), Anuncios.vigencia(historial, LocalDate.of(2026, 12, 1)))
        }

        @Test
        fun `two acts of the same day - the later in the list`() {
            val dia = LocalDate.of(2026, 12, 1)
            val dos = listOf(autorizacion(dia, fin(2026)), renovacion(dia, 2027, fin(2027)))
            assertEquals(fin(2027), Anuncios.vigencia(dos, dia))
            assertEquals(fin(2026), Anuncios.vigencia(dos.reversed(), dia))
        }

        @Test
        fun `the cese does not move the vigencia - it ends it`() {
            assertTrue("vigencia_hasta" in campos(cese(AUTORIZADO).copy(vigenciaHasta = fin(2027))))
            assertNull(Anuncios.vigencia(listOf(cese(AUTORIZADO)), fin(2027)))
        }
    }

    @Nested
    inner class ElDevengo {
        @Test
        fun `an accrual carries its clave, its referencia, the tasa and the row it was read from`() {
            val m = Anuncios.devengo("a", RENOVACION, LocalDate.of(2026, 12, 15), 2027, tasa("t", "30.00"), fin(2027), PORQUE)
            assertEquals(emptyList<String>(), campos(m))
            assertEquals("a|RENOVACION|2027", m.clave)
            assertEquals("ANUNCIO-a-2027", m.referenciaCargo)
            assertEquals(BigDecimal("30.00"), m.tasa)
            assertEquals("t", m.parametro)
        }

        @Test
        fun `an authorization without its referencia is not written`() {
            assertTrue("referencia_cargo" in campos(autorizacion(AUTORIZADO, fin(2026)).copy(referenciaCargo = null)))
        }

        @Test
        fun `nor a cese WITH one`() {
            val conCargo = cese(AUTORIZADO).copy(anio = 2026, referenciaCargo = "ANUNCIO-a-2026", tasa = BigDecimal("12.50"), parametro = "t")
            assertTrue("referencia_cargo" in campos(conCargo))
        }

        @Test
        fun `a tasa of 0 is no tasa - the clase without one is left without row`() {
            assertTrue("tasa" in campos(autorizacion(AUTORIZADO, fin(2026)).copy(tasa = BigDecimal.ZERO)))
            assertNull(Anuncios.tasa(listOf(tasa("t", "0.00")), "PANEL", AUTORIZADO), "a row at 0 is not read as a tasa")
        }

        @Test
        fun `the cese and the retiro say why - the authorization and the renewal do not`() {
            assertTrue("motivo" in campos(cese(AUTORIZADO).copy(motivo = null)))
            assertTrue("motivo" in campos(autorizacion(AUTORIZADO, fin(2026)).copy(motivo = "Porque sí")))
        }

        @Test
        fun `without observacion nothing is written`() {
            assertFalse(Observacion.valida("  "))
            assertTrue("observacion" in campos(cese(AUTORIZADO).copy(observacion = "  ")))
        }

        @Test
        fun `what was accrued up to a day is the sum of its accruing acts - a cese accrues nothing`() {
            val historial =
                listOf(
                    autorizacion(AUTORIZADO, fin(2026)),
                    renovacion(LocalDate.of(2026, 12, 15), 2027, fin(2027), "30.00"),
                    cese(LocalDate.of(2027, 3, 1))
                )
            assertEquals(BigDecimal("0.00"), Anuncios.devengado(historial, AUTORIZADO.minusDays(1)))
            assertEquals(BigDecimal("12.50"), Anuncios.devengado(historial, LocalDate.of(2026, 12, 14)))
            assertEquals(BigDecimal("42.50"), Anuncios.devengado(historial, LocalDate.of(2027, 6, 1)))
        }
    }

    // rentas#417: the ejercicio a renewal accrues is the one it renews, not the act's. each case renews on a day whose
    // year is NOT the expected one: in january both coincide, and the old rule would pass
    @Nested
    inner class ElEjercicioQueRenueva {
        private val diciembre = LocalDate.of(2026, 12, 15)

        @Test
        fun `in force in december and extended to the next year - the next year`() {
            assertEquals(2027, Anuncios.ejercicioQueRenueva(fin(2026), diciembre, fin(2027)))
        }

        @Test
        fun `a vigencia that already reaches the next year counts from its next day`() {
            assertEquals(2027, Anuncios.ejercicioQueRenueva(LocalDate.of(2027, 6, 30), diciembre, fin(2027)))
        }

        @Test
        fun `expired and renewed in january - the vigencia's, which is the act's too`() {
            assertEquals(2027, Anuncios.ejercicioQueRenueva(fin(2026), LocalDate.of(2027, 1, 15), fin(2027)))
        }

        @Test
        fun `without a term - the act's year`() {
            assertEquals(2026, Anuncios.ejercicioQueRenueva(fin(2026), diciembre, null))
        }

        @Test
        fun `a prorroga from 2027 to 2028 spans two ejercicios and is not charged as one`() {
            val e = assertThrows(ActoNoAdmitido::class.java) { Anuncios.ejercicioQueRenueva(fin(2026), diciembre, fin(2028)) }
            assertTrue(e.message.contains("2027-01-01") && e.message.contains("2028-12-31"), e.message)
            assertEquals(422, e.status.value())
        }

        @Test
        fun `expired, it counts from the act - december and the next year are two`() {
            val e = assertThrows(ActoNoAdmitido::class.java) { Anuncios.ejercicioQueRenueva(fin(2025), diciembre, fin(2027)) }
            assertTrue(e.message.contains(diciembre.toString()), e.message)
        }

        @Test
        fun `without a current vigencia it counts from the act too`() {
            assertThrows(ActoNoAdmitido::class.java) { Anuncios.ejercicioQueRenueva(null, diciembre, fin(2027)) }
            assertEquals(2026, Anuncios.ejercicioQueRenueva(null, diciembre, fin(2026)))
        }
    }

    @Nested
    inner class LaReferenciaYElNumero {
        @Test
        fun `two ejercicios of the same anuncio are two referencias`() {
            assertEquals("ANUNCIO-a-2026", referenciaDeCargo("a", 2026))
            assertNotEquals(referenciaDeCargo("a", 2026), referenciaDeCargo("a", 2027), "without the year, the first renewal could not be")
        }

        @Test
        fun `the number is AN-AAAA-NNNNNN`() {
            assertEquals("AN-2026-000001", numeroDeAnuncio(2026, 1))
        }
    }

    @Nested
    inner class LaAutorizacion {
        private val anuncio = Ejemplos.anuncio()

        @Test
        fun `an anuncio of area 0 takes up nothing`() {
            assertEquals(listOf("area"), camposDe(anuncio + ("area" to "0")))
        }

        @Test
        fun `a vigencia that ends before it starts is born expired`() {
            assertEquals(listOf("vigencia_hasta"), camposDe(anuncio + ("vigencia_hasta" to "2026-03-01")))
        }

        @Test
        fun `an anuncio without a face does not exist`() {
            assertEquals(listOf("lados"), camposDe(anuncio + ("lados" to 0)))
        }

        @Test
        fun `the clases and tipos are model json's`() {
            val enums = JsonMapper.builder().build().readTree(File("model/model.json"))["enums"]
            assertEquals(
                CLASES,
                enums["clase_anuncio"]
                    .iterator()
                    .asSequence()
                    .map { it.asString() }
                    .toList()
            )
            assertEquals(
                TIPOS_DE_ANUNCIO,
                enums["tipo_anuncio"]
                    .iterator()
                    .asSequence()
                    .map { it.asString() }
                    .toList()
            )
        }
    }

    @Nested
    inner class LaTasa {
        private val filas =
            listOf(
                tasa("p25", "10.00", desde = LocalDate.of(2025, 1, 1), hasta = fin(2025)),
                tasa("p26", "12.50", desde = LocalDate.of(2026, 1, 1), hasta = null),
                tasa("p26b", "14.00", desde = LocalDate.of(2026, 7, 1), hasta = null),
                tasa("t26", "5.00", clase = "TOLDO", desde = LocalDate.of(2026, 4, 1), hasta = null),
                ParametroTributario("u", "UIT", "2026", LocalDate.of(2026, 1, 1), null, BigDecimal("5500"))
            )

        @Test
        fun `the row of the clase in force on the day - the latest when several are`() {
            assertEquals("p25", Anuncios.tasa(filas, "PANEL", LocalDate.of(2025, 6, 1))?.id)
            assertEquals("p26", Anuncios.tasa(filas, "PANEL", LocalDate.of(2026, 3, 2))?.id)
            assertEquals("p26b", Anuncios.tasa(filas, "PANEL", LocalDate.of(2026, 7, 1))?.id)
            assertNull(Anuncios.tasa(filas, "PANEL", LocalDate.of(2024, 12, 31)))
            assertNull(Anuncios.tasa(filas, "LETRERO", LocalDate.of(2026, 3, 2)))
        }

        @Test
        fun `the tasa of an ejercicio - january 1's, else the first that starts in the year`() {
            assertEquals("p26", Anuncios.tasaDelEjercicio(filas, "PANEL", 2026)?.id)
            assertEquals("t26", Anuncios.tasaDelEjercicio(filas, "TOLDO", 2026)?.id)
            assertNull(Anuncios.tasaDelEjercicio(filas, "TOLDO", 2025))
        }
    }

    @Nested
    inner class ElOrdenYLasAcciones {
        private val hoy = LocalDate.of(2026, 10, 3)

        @Test
        fun `no act is dated after today nor before the act it follows`() {
            val previo = listOf(ActoPrevio("la autorización AN-2026-000001", AUTORIZADO))
            Anuncios.exigirOrden("el cese", AUTORIZADO, hoy, previo)
            Anuncios.exigirOrden("el cese", hoy, hoy, previo)
            val futuro = assertThrows(ActoNoAdmitido::class.java) { Anuncios.exigirOrden("el cese", hoy.plusDays(1), hoy, previo) }
            assertTrue(futuro.message.contains("2026-10-04") && futuro.message.contains("2026-10-03"), futuro.message)
            val antes = assertThrows(ActoNoAdmitido::class.java) { Anuncios.exigirOrden("el cese", AUTORIZADO.minusDays(1), hoy, previo) }
            assertTrue(antes.message.contains("2026-03-15") && antes.message.contains("2026-03-16"), antes.message)
        }

        @Test
        fun `the acts left to an anuncio follow its estado - and say why not`() {
            val vigente = Anuncios.acciones(listOf(autorizacion(AUTORIZADO, fin(2026))), hoy)
            assertEquals(Accion(true, null), vigente.renovacion)
            assertEquals(Accion(true, null), vigente.cese)
            assertFalse(vigente.retiro.permitida)
            assertTrue(vigente.retiro.motivo!!.contains("cese"))

            val cesado = Anuncios.acciones(listOf(autorizacion(AUTORIZADO, fin(2026)), cese(AUTORIZADO.plusDays(1))), hoy)
            assertFalse(cesado.renovacion.permitida)
            assertFalse(cesado.cese.permitida)
            assertEquals(Accion(true, null), cesado.retiro)

            val retirado =
                Anuncios.acciones(listOf(autorizacion(AUTORIZADO, fin(2026)), cese(AUTORIZADO.plusDays(1)), retiro(AUTORIZADO.plusDays(2))), hoy)
            listOf(retirado.renovacion, retirado.cese, retirado.retiro).forEach { assertFalse(it.permitida) }
        }
    }

    private companion object {
        val AUTORIZADO: LocalDate = LocalDate.of(2026, 3, 16)
        const val PORQUE = "Se registra para la prueba"

        fun fin(anio: Int): LocalDate = LocalDate.of(anio, 12, 31)

        fun tasa(
            id: String,
            valor: String,
            clase: String = "PANEL",
            desde: LocalDate = LocalDate.of(2026, 1, 1),
            hasta: LocalDate? = fin(2026)
        ) = ParametroTributario(id, Llaves.TASA_ANUNCIO, clase, desde, hasta, BigDecimal(valor))

        fun autorizacion(
            fecha: LocalDate,
            vigencia: LocalDate?
        ) = Anuncios.devengo("a", AUTORIZACION, fecha, fecha.year, tasa("t", "12.50"), vigencia, PORQUE)

        fun renovacion(
            fecha: LocalDate,
            anio: Int,
            vigencia: LocalDate?,
            valor: String = "12.50"
        ) = Anuncios.devengo("a", RENOVACION, fecha, anio, tasa("t", valor), vigencia, PORQUE)

        fun cese(fecha: LocalDate) = Anuncios.baja("a", CESE, fecha, "Cese solicitado por el titular", PORQUE)

        fun retiro(fecha: LocalDate) = Anuncios.baja("a", RETIRO, fecha, "Retirado y verificado en campo", PORQUE)

        // the fields ReglaDeAnuncios refuses of a movimiento
        fun campos(m: MovimientoAnuncio) = invariantes(m).map { it.field }

        fun camposDe(anuncio: Map<String, Any?>) = invariantes(srtm.leerRegistro(Anuncio::class.java, anuncio)).map { it.field }
    }
}
