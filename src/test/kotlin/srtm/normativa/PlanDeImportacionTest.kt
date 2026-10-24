package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.impuesto.ParametroTributario
import wasichai.core.common.ValidationException
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

// what POST /normativa/parametros/importar would write, without writing: the rows with two signatures enter VIGENTE,
// the others POR_VERIFICAR; idempotent by tipo, clave and vigencia_desde over the rows not ANULADO. FICTITIOUS figures
// but the shipped csv's
class PlanDeImportacionTest {
    private fun planear(
        csv: String,
        guardados: List<ParametroTributario> = emptyList()
    ) = PlanDeImportacion.planear(CsvDeParametros.leer(csv), guardados, USUARIO)

    @Test
    fun `the shipped csv on an empty organization - every row created VIGENTE with its signatures`() {
        val plan = planear(File("model/data/parametros-predial.csv").readText())
        assertEquals(13, plan.crear.size)
        assertTrue(plan.crear.all { it.estado == EstadoParametro.VIGENTE && it.transcribio == "JNA" && it.verifico == "HNA" })
        assertEquals(emptyList<RechazoDeFila>(), plan.rechazos)
        assertEquals(0, plan.iguales)
        assertEquals(10, plan.crear.first().fila)
    }

    @Test
    fun `one signature or none - POR_VERIFICAR, transcribed by the csv's or by the user`() {
        val plan =
            planear(
                """
                tipo,clave,vigencia_desde,valor_numerico,transcribio,verifico
                UIT,,2097-01-01,9000,ANA,
                UIT,,2098-01-01,9100,,
                """.trimIndent()
            )
        assertEquals(listOf(EstadoParametro.POR_VERIFICAR, EstadoParametro.POR_VERIFICAR), plan.crear.map { it.estado })
        assertEquals(listOf("ANA", USUARIO), plan.crear.map { it.transcribio })
        assertTrue(plan.crear.all { it.verifico == null })
    }

    @Test
    fun `a row that does not fit is a rechazo that names its line, and the others go on`() {
        val plan =
            planear(
                """
                tipo,clave,vigencia_desde,vigencia_hasta,valor_numerico,texto,transcribio,verifico
                NO_EXISTE,,2097-01-01,,1,,ANA,BETO
                UIT,,2097-01-01,2096-01-01,1,,ANA,BETO
                UIT,,2097-01-01,,1,,ANA,ana
                UIT,,2097-01-01,,1,,,BETO
                TRAMO_PREDIAL,,2097-01-01,,1,,ANA,BETO
                UIT,1,2097-01-01,,1,,ANA,BETO
                UIT,,2097-01-01,,no,,ANA,BETO
                ARBITRIO_ZONA,S1,2097-01-01,,,,ANA,BETO
                UIT,,2097-01-01,,9000,,ANA,BETO
                UIT,,2097-01-01,,9001,,ANA,BETO
                """.trimIndent()
            )
        assertEquals((2..11).filter { it != 10 }, plan.rechazos.map { it.fila })
        val motivos = plan.rechazos.associate { it.fila to it.motivo }
        assertTrue(motivos.getValue(2).contains("tipo"), motivos.toString())
        assertTrue(motivos.getValue(3).contains("vigencia_hasta"), motivos.toString())
        assertTrue(motivos.getValue(4).contains("misma persona"), motivos.toString())
        assertTrue(motivos.getValue(5).contains("transcribio"), motivos.toString())
        assertTrue(motivos.getValue(6).contains("clave"), motivos.toString())
        assertTrue(motivos.getValue(7).contains("clave"), motivos.toString())
        assertTrue(motivos.getValue(8).contains("valor_numerico"), motivos.toString())
        assertTrue(motivos.getValue(9).contains("texto"), motivos.toString())
        assertTrue(motivos.getValue(11).contains("línea 10"), motivos.toString())
        assertEquals(listOf(10), plan.crear.map { it.fila })
    }

    @Test
    fun `against what is stored - the same is skipped, a POR_VERIFICAR one updated, a VIGENTE one is annulled first`() {
        val guardados =
            listOf(
                uit("a", 2097, "9000", EstadoParametro.VIGENTE),
                uit("b", 2098, "9100", EstadoParametro.POR_VERIFICAR),
                uit("c", 2099, "9200", null),
                // history: its key is free
                uit("d", 2096, "1", EstadoParametro.ANULADO)
            )
        val plan =
            planear(
                """
                tipo,clave,vigencia_desde,vigencia_hasta,valor_numerico,transcribio,verifico
                UIT,,2097-01-01,2097-12-31,9000.00,OTRA,PERSONA
                UIT,,2098-01-01,2098-12-31,9150,ANA,BETO
                UIT,,2099-01-01,2099-12-31,9300,ANA,BETO
                UIT,,2096-01-01,2096-12-31,8000,ANA,BETO
                """.trimIndent(),
                guardados
            )
        assertEquals(1, plan.iguales)
        assertEquals(listOf("b"), plan.actualizar.map { it.id })
        assertEquals(EstadoParametro.VIGENTE, plan.actualizar.single().estado)
        assertEquals(listOf(4), plan.rechazos.map { it.fila })
        assertTrue(
            plan.rechazos
                .single()
                .motivo
                .contains("anúlelo"),
            plan.rechazos.toString()
        )
        assertEquals(listOf(5), plan.crear.map { it.fila })
        assertNull(plan.crear.single().id)
    }

    @Test
    fun `a POR_VERIFICAR row as it is stored is the same, and a new VIGENTE row that overlaps another is a rechazo`() {
        val guardados = listOf(uit("b", 2098, "9100", EstadoParametro.POR_VERIFICAR), uit("a", 2097, "9000", EstadoParametro.VIGENTE))
        val plan =
            planear(
                """
                tipo,clave,vigencia_desde,vigencia_hasta,valor_numerico,transcribio,verifico
                UIT,,2098-01-01,2098-12-31,9100,ANA,
                UIT,,2097-06-01,,9050,ANA,BETO
                """.trimIndent(),
                guardados
            )
        assertEquals(1, plan.iguales)
        assertEquals(listOf(3), plan.rechazos.map { it.fila })
        assertTrue(
            plan.rechazos
                .single()
                .motivo
                .contains("se solapa con UIT 2097-01-01..2097-12-31"),
            plan.rechazos.toString()
        )
    }

    @Test
    fun `a malformed file is a 400`() {
        assertThrows(ValidationException::class.java) { planear("clave\nX") }
    }

    private fun uit(
        id: String,
        anio: Int,
        valor: String,
        estado: String?
    ) = ParametroTributario(
        id = id,
        tipo = "UIT",
        vigenciaDesde = LocalDate.of(anio, 1, 1),
        vigenciaHasta = LocalDate.of(anio, 12, 31),
        valorNumerico = BigDecimal(valor),
        transcribio = "ANA",
        verifico = if (estado == EstadoParametro.POR_VERIFICAR) null else "BETO",
        estado = estado
    )

    private companion object {
        const val USUARIO = "carla@srtm.test"
    }
}
