package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import srtm.rentas.ANULADA
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import java.math.BigDecimal
import java.time.LocalDate

// #158: what a redeterminación of a predio's year writes and annuls (Redeterminacion.comparar), the year computed
// whole again (Redeterminacion.completa), and which changes of a declaración call for it (Redeterminacion.cambia).
// every figure and code is FICTITIOUS
class RedeterminacionTest {
    private fun cuota(
        periodo: Int,
        monto: String = "8.50",
        servicio: String = "srv-LIMPIEZA",
        secuencia: String? = "001",
        version: Int = 1,
        id: String? = "q-$servicio-$periodo-$version"
    ) = CuotaArbitrio(
        id = id,
        predio = "predio-100",
        contribuyente = "c-200",
        servicio = servicio,
        parametro = "p-TASA_ARBITRIO-LIMPIEZA:Z1:CASA",
        anio = 2026,
        periodo = periodo,
        monto = BigDecimal(monto),
        parametroAplicado = "TASA_ARBITRIO:LIMPIEZA:Z1:CASA",
        zona = "Z1",
        usoArbitrio = "CASA",
        secuenciaUso = secuencia,
        base = "PREDIO",
        cantidadBase = BigDecimal.ONE,
        tasaUnitaria = BigDecimal(monto),
        fechaCalculo = LocalDate.of(2026, 3, 15),
        observacion = "Determinación de prueba",
        clave = claveDeCuota("predio-100", servicio, 2026, periodo, secuencia ?: "001", version)
    )

    // what determinar computes now: the next version, not yet written
    private fun calculada(
        periodo: Int,
        monto: String = "8.50",
        servicio: String = "srv-LIMPIEZA"
    ) = cuota(periodo, monto, servicio, version = 2, id = null).copy(fechaCalculo = LocalDate.of(2026, 10, 21), observacion = "Otra observación")

    @Test
    fun `the same result is nothing to write, whatever the scale, the date or the observación`() {
        val vigentes = (1..12).map { cuota(it, "8.50") }
        val calculadas = (1..12).map { calculada(it, "8.5") }
        assertEquals(Redeterminacion.Cambios(emptyList(), emptyList()), Redeterminacion.comparar(vigentes, calculadas))
    }

    @Test
    fun `a month whose monto changed is annulled and written again, the others untouched`() {
        val vigentes = (1..12).map { cuota(it) }
        val calculadas = (1..12).map { calculada(it, if (it >= 6) "10.00" else "8.50") }
        val cambios = Redeterminacion.comparar(vigentes, calculadas)
        assertEquals((6..12).toList(), cambios.anular.map { it.periodo })
        assertEquals((6..12).toList(), cambios.escribir.map { it.periodo })
        assertEquals(List(7) { 2 }, cambios.escribir.map { versionDe(it.clave) })
    }

    @Test
    fun `a base that changed with the same monto is written again too`() {
        val vigentes = listOf(cuota(1))
        val calculadas = listOf(calculada(1).copy(zona = "Z2"))
        val cambios = Redeterminacion.comparar(vigentes, calculadas)
        assertEquals(listOf("Z1"), cambios.anular.map { it.zona })
        assertEquals(listOf("Z2"), cambios.escribir.map { it.zona })
    }

    @Test
    fun `a month without a cuota is written, one no longer charged is annulled`() {
        val vigentes = listOf(cuota(1), cuota(2))
        val calculadas = listOf(calculada(2), calculada(3))
        val cambios = Redeterminacion.comparar(vigentes, calculadas)
        assertEquals(listOf(1), cambios.anular.map { it.periodo })
        assertEquals(listOf(3), cambios.escribir.map { it.periodo })
    }

    // one PU per predio before #84: its cuota has no secuencia, and counts as the lowest one's
    @Test
    fun `a cuota without secuencia is the one of the lowest secuencia`() {
        val vigentes = listOf(cuota(1, secuencia = null))
        val calculadas = listOf(calculada(1))
        assertEquals(Redeterminacion.Cambios(emptyList(), emptyList()), Redeterminacion.comparar(vigentes, calculadas))
    }

    @Test
    fun `the whole year is computed again, its existing cuotas as their next version`() {
        val parametros =
            listOf(
                param(Llaves.ARBITRIO_ZONA, "S-01", texto = "Z1"),
                param(Llaves.ARBITRIO_USO, "0101", texto = "CASA"),
                param(Llaves.TASA_ARBITRIO, "LIMPIEZA:Z1:CASA", valor = "8.50")
            )
        val c = ContextoArbitrios(2026, ordenanza, servicios, parametros, listOf(UsoPredio("010101", "RESIDENCIAL", "UNIFAMILIAR", "CASA HABITACIÓN")))
        val existentes = (1..12).map { cuota(it) }
        val p =
            PredioArbitrios(
                Predio(id = "predio-100", codigo = "01-01-0001", sectorCatastral = "S-01"),
                listOf(dj()),
                mapOf("c-200" to "00000200"),
                emptyList(),
                existentes
            )
        // determinar alone writes nothing: every month has its cuota
        assertTrue(Arbitrios.determinar(c, p, "Determinación de prueba", LocalDate.of(2026, 10, 21)).cuotas.isEmpty())
        val d = Redeterminacion.completa(c, p, "Redeterminación de prueba", LocalDate.of(2026, 10, 21))
        assertEquals((1..12).toList(), d.cuotas.map { it.periodo })
        assertEquals(List(12) { 2 }, d.cuotas.map { versionDe(it.clave) })
        assertEquals(Redeterminacion.Cambios(emptyList(), emptyList()), Redeterminacion.comparar(existentes, d.cuotas))
    }

    // determinar's "no declaración covers it" is no missing data here: every cuota it had is annulled (comparar)
    @Test
    fun `a predio no declaración covers any month of comes out without cuotas, not as missing a titular`() {
        val c = ContextoArbitrios(2026, ordenanza, servicios, emptyList(), emptyList())
        val anulada = dj().copy(estado = ANULADA, fechaAnulacion = LocalDate.of(2025, 12, 31))
        val p = PredioArbitrios(Predio(id = "predio-100", codigo = "01-01-0001"), listOf(anulada), emptyMap(), emptyList(), listOf(cuota(1)))
        assertEquals(Determinacion(emptyList(), emptyList()), Redeterminacion.completa(c, p, "Redeterminación de prueba", LocalDate.of(2026, 10, 21)))
    }

    @Test
    fun `a change of what the arbitrios read calls for a redeterminación, another does not`() {
        val antes = dj()
        assertTrue(Redeterminacion.cambia(antes, antes.copy(areaConstruida = BigDecimal("120"))))
        assertTrue(Redeterminacion.cambia(antes, antes.copy(longitudFrente = BigDecimal("12"))))
        assertTrue(Redeterminacion.cambia(antes, antes.copy(uso = "EDIFICIO")))
        assertTrue(Redeterminacion.cambia(antes, antes.copy(numeroHabitantes = 4)))
        assertTrue(Redeterminacion.cambia(antes, antes.copy(porcentajeCondominio = BigDecimal("50"))))
        assertTrue(Redeterminacion.cambia(antes, antes.copy(estado = ANULADA, fechaAnulacion = LocalDate.of(2026, 5, 20))))
        assertTrue(Redeterminacion.cambia(null, antes))
        assertTrue(Redeterminacion.cambia(antes, null))
        assertFalse(Redeterminacion.cambia(antes, antes.copy(folios = 3, valorAutoavaluo = BigDecimal("1000"))))
        assertFalse(Redeterminacion.cambia(antes, antes.copy(porcentajeCondominio = BigDecimal("100.00"))))
    }

    private val ordenanza =
        OrdenanzaArbitrio(
            id = "ord",
            anio = 2026,
            numero = "000 (ficticia)",
            acuerdoRatificacion = "Acuerdo 000",
            fechaRatificacion = LocalDate.of(2025, 12, 28)
        )
    private val servicios =
        listOf(ServicioArbitrio(id = "srv-LIMPIEZA", codigo = "LIMPIEZA", nombre = "LIMPIEZA", orden = 1, vigenciaDesde = LocalDate.of(2026, 1, 1)))

    private fun dj() =
        Declaracion(
            id = "dj-1",
            contribuyente = "c-200",
            predio = "predio-100",
            anio = 2026,
            secuenciaUso = "001",
            porcentajeCondominio = BigDecimal("100"),
            claseUso = "RESIDENCIAL",
            subClaseUso = "UNIFAMILIAR",
            uso = "CASA HABITACIÓN",
            areaConstruida = BigDecimal("100.00")
        )

    private fun param(
        tipo: String,
        clave: String,
        valor: String? = null,
        texto: String? = null
    ) = ParametroTributario(
        id = "p-$tipo-$clave",
        tipo = tipo,
        clave = clave,
        vigenciaDesde = LocalDate.of(2026, 1, 1),
        valorNumerico = valor?.let(::BigDecimal),
        texto = texto,
        norma = "Ordenanza ficticia de prueba"
    )
}
