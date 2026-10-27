package srtm.reportes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.brecha.Categoria
import srtm.brecha.FilaBrecha
import srtm.brecha.RegistroCorrida
import java.math.BigDecimal
import java.time.Instant
import kotlin.random.Random
import kotlin.system.measureTimeMillis

// a district of ~30 000 predios (#248): what a request computes in memory over its cached rows (the rows as the
// reports show them, the resumen, the catalog, a report filtered and sorted, the exports) stays well within a request.
// prints its times on stderr; the bound is loose, a ci machine is slower
class ReportesRendimientoTest {
    private val n = 30_000

    private fun filas(): Pair<List<FilaBrecha>, Map<String, List<Titular>>> {
        val r = Random(248)
        val categorias = Categoria.entries
        val brecha =
            (0 until n).map { i ->
                val categoria = categorias[r.nextInt(categorias.size)]
                val delta = BigDecimal(r.nextInt(-200_000, 400_000)).setScale(2)
                FilaBrecha(
                    id = "b$i",
                    predio = "p$i",
                    codigoPredio = "%02d-%03d-%04d".format(i % 12, i % 300, i),
                    codigoCatastro = "CM-$i",
                    categoria = categoria.name,
                    sector = "%02d".format(i % 12),
                    manzana = "M${i % 300}",
                    lote = "${i % 40}",
                    uso = listOf("010101", "020101", "030201")[i % 3],
                    deltaAutoavaluo = delta,
                    deltaImpuesto = delta.movePointLeft(2),
                    confianza = listOf("CONFIABLE", "DUDOSO", "SIN_LEVANTAR")[i % 3],
                    areaTerrenoDeclarada = BigDecimal(200),
                    areaTerrenoCatastro = BigDecimal(200 + r.nextInt(-20, 40)),
                    nivelesComparados = 2,
                    nivelesCatastroMayor = r.nextInt(0, 2),
                    deltaAreaNiveles = BigDecimal(r.nextInt(0, 30)),
                    orden = i
                )
            }
        val titulares =
            (0 until n).associate { i ->
                "p$i" to
                    listOf(Titular("k${i / 2}", "%06d".format(i / 2), "CONTRIBUYENTE ${i / 2}", "d$i", BigDecimal(100)))
            }
        return brecha to titulares
    }

    @Test
    fun `30 000 rows are counted, sorted and exported within a request`() {
        val (brecha, titulares) = filas()
        val decisiones = (0 until n step 7).associate { "CM-$it" to "A_INSPECCIONAR" }
        val corrida = RegistroCorrida(id = "c1", anio = 2026, estado = "TERMINADA")
        lateinit var todas: List<FilaReportePredio>
        val tiempos = linkedMapOf<String, Long>()
        tiempos["filas"] = measureTimeMillis { todas = Reportes.filas(brecha, titulares, mapOf("10101" to "CASA"), decisiones) }
        tiempos["resumen"] = measureTimeMillis { Reportes.resumen(corrida, brecha, todas, true, emptyList()) }
        tiempos["catalogo"] = measureTimeMillis { Reportes.catalogo(todas, BigDecimal(27_500)) }
        lateinit var sub: List<FilaReportePredio>
        tiempos["subvaluados"] =
            measureTimeMillis {
                sub =
                    Reportes.ordenar(
                        Reportes.filtrar(Reportes.del(Reporte.SUBVALUADOS, todas, null), Filtros(q = "contribuyente 1")),
                        Orden.DELTA_IMPUESTO,
                        Direccion.DESC
                    )
            }
        tiempos["contribuyentes"] = measureTimeMillis { Reportes.ordenarContribuyentes(Reportes.contribuyentes(todas), Orden.DELTA_IMPUESTO, Direccion.DESC) }
        lateinit var xlsx: ByteArray
        tiempos["xlsx"] =
            measureTimeMillis {
                xlsx =
                    Exportacion.xlsx(
                        Exportable(Reporte.SUBVALUADOS, corrida, Filtros(), Orden.DELTA_IMPUESTO, Direccion.DESC, Instant.now()),
                        Exportacion.PREDIOS,
                        todas
                    )
            }
        lateinit var csv: ByteArray
        tiempos["csv"] = measureTimeMillis { csv = Exportacion.csv(Exportacion.PREDIOS, todas) }
        System.err.println("reportes, $n filas: $tiempos ms; xlsx ${xlsx.size / 1024} KiB, csv ${csv.size / 1024} KiB")
        assertEquals(n, todas.size)
        assertTrue(sub.isNotEmpty())
        assertTrue(tiempos.filterKeys { it !in setOf("xlsx", "csv") }.values.all { it < 5_000 }, "$tiempos")
        assertTrue(tiempos.getValue("xlsx") < 30_000, "$tiempos")
    }
}
