package srtm.normativa

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.core.common.ValidationException
import java.math.BigDecimal

// what POST /normativa/aranceles/importar would write, without writing (#188): its csv, the rows with two signatures
// VIGENTE and the others POR_VERIFICAR, idempotent by clave (id_aranc|anio) over the rows not ANULADO. FICTITIOUS
// figures
class PlanDeArancelesTest {
    private fun planear(
        csv: String,
        guardados: List<Arancel> = emptyList()
    ) = PlanDeAranceles.planear(CsvDeAranceles.leer(csv), guardados, USUARIO)

    @Test
    fun `the csv - its columns, comments, an empty cell null, unknown columns left out`() {
        val filas =
            CsvDeAranceles.leer(
                """
                # aranceles ficticios
                id_aranc,anio,valor_m2,cod_via,cuadra,lado,norma,fuente,transcribio,verifico,otra
                A-01,2026,12.50,V-001,1,I,"Ordenanza 1, art. 2",prueba.csv,ANA,BETO,x
                A-02,2026,0,,,,,,,
                """.trimIndent()
            )
        assertEquals(listOf(3, 4), filas.map { it.linea })
        assertEquals("Ordenanza 1, art. 2", filas[0].valores["norma"])
        assertTrue("otra" !in filas[0].valores)
        assertNull(filas[1].valores["cod_via"])
        assertThrows(ValidationException::class.java) { CsvDeAranceles.leer("id_aranc,valor_m2\nA-01,1") }
        assertThrows(ValidationException::class.java) { CsvDeAranceles.leer("id_aranc,anio,valor_m2\nA-01,2026,1,de más") }
    }

    @Test
    fun `two signatures enter VIGENTE, one or none POR_VERIFICAR, with the clave computed`() {
        val plan =
            planear(
                """
                id_aranc,anio,valor_m2,cod_via,transcribio,verifico
                A-01,2026,12.50,V-001,ANA,BETO
                A-02,2026,0,V-001,ANA,
                A-03,2026,7,V-002,,
                """.trimIndent()
            )
        assertEquals(listOf(EstadoParametro.VIGENTE, EstadoParametro.POR_VERIFICAR, EstadoParametro.POR_VERIFICAR), plan.crear.map { it.estado })
        assertEquals(listOf("A-01|2026", "A-02|2026", "A-03|2026"), plan.crear.map { it.arancel.clave })
        assertEquals(listOf("ANA", "ANA", USUARIO), plan.crear.map { it.transcribio })
        assertEquals(BigDecimal.ZERO, plan.crear[1].arancel.valorM2)
        assertEquals(emptyList<RechazoDeFila>(), plan.rechazos)
    }

    @Test
    fun `a row that does not fit is a rechazo by its line, and the others go on`() {
        val plan =
            planear(
                """
                id_aranc,anio,valor_m2,transcribio,verifico
                ,2026,1,ANA,BETO
                A-01,,1,ANA,BETO
                A-01,2026,-1,ANA,BETO
                A-01,2026,no,ANA,BETO
                A-01,dos mil,1,ANA,BETO
                A-01,2026,1,ANA,ana
                A-01,2026,1,,BETO
                A-01,2026,1,ANA,BETO
                A-01,2026,2,ANA,BETO
                """.trimIndent()
            )
        assertEquals((2..10).filter { it != 9 }, plan.rechazos.map { it.fila })
        val motivos = plan.rechazos.associate { it.fila to it.motivo }
        assertTrue(motivos.getValue(2).contains("id_aranc"), motivos.toString())
        assertTrue(motivos.getValue(3).contains("anio"), motivos.toString())
        assertTrue(motivos.getValue(4).contains("valor_m2"), motivos.toString())
        assertTrue(motivos.getValue(5).contains("valor_m2"), motivos.toString())
        assertTrue(motivos.getValue(6).contains("anio"), motivos.toString())
        assertTrue(motivos.getValue(7).contains("misma persona"), motivos.toString())
        assertTrue(motivos.getValue(8).contains("transcribio"), motivos.toString())
        assertTrue(motivos.getValue(10).contains("línea 9"), motivos.toString())
        assertEquals(listOf(9), plan.crear.map { it.fila })
    }

    @Test
    fun `against what is stored - the same is skipped, a POR_VERIFICAR one updated, a VIGENTE other is annulled first`() {
        val guardados =
            listOf(
                arancel("a", "A-01", "12.50", EstadoParametro.VIGENTE),
                arancel("b", "A-02", "8", EstadoParametro.POR_VERIFICAR),
                arancel("c", "A-03", "9", null),
                arancel("d", "A-04", "1", EstadoParametro.ANULADO),
                arancel("e", "A-05", "3", EstadoParametro.POR_VERIFICAR)
            )
        val plan =
            planear(
                """
                id_aranc,anio,valor_m2,cod_via,transcribio,verifico
                A-01,2026,12.500,V-001,OTRA,PERSONA
                A-02,2026,8.40,V-001,ANA,BETO
                A-03,2026,9.10,V-001,ANA,BETO
                A-04,2026,2,V-001,ANA,BETO
                A-05,2026,3,V-001,ANA,
                """.trimIndent(),
                guardados
            )
        assertEquals(2, plan.iguales)
        assertEquals(listOf("b"), plan.actualizar.map { it.id })
        assertEquals(EstadoParametro.VIGENTE, plan.actualizar.single().estado)
        assertEquals(listOf(4), plan.rechazos.map { it.fila })
        assertTrue(
            plan.rechazos
                .single()
                .motivo
                .contains("VIGENTE distinto: anule primero"),
            plan.rechazos.toString()
        )
        assertTrue(
            plan.rechazos
                .single()
                .motivo
                .contains("(c)"),
            plan.rechazos.toString()
        )
        // an ANULADO one is history: its clave is free
        assertEquals(listOf(5), plan.crear.map { it.fila })
        assertNull(plan.crear.single().id)
    }

    @Test
    fun `fifteen hundred rows are planned at once`() {
        val csv = "id_aranc,anio,valor_m2,transcribio,verifico\n" + (1..1500).joinToString("\n") { "T-$it,2026,$it.25,ANA,BETO" }
        val plan = planear(csv)
        assertEquals(1500, plan.crear.size)
        assertEquals(0, plan.rechazos.size)
    }

    private fun arancel(
        id: String,
        idAranc: String,
        valor: String,
        estado: String?
    ) = Arancel(
        id = id,
        clave = "$idAranc|2026",
        idAranc = idAranc,
        anio = 2026,
        valorM2 = BigDecimal(valor),
        codVia = "V-001",
        transcribio = "ANA",
        verifico = if (estado == EstadoParametro.POR_VERIFICAR) null else "BETO",
        estado = estado
    )

    private companion object {
        const val USUARIO = "carla@srtm.test"
    }
}
