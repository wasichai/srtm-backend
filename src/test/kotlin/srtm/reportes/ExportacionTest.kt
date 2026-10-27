package srtm.reportes

import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import srtm.brecha.RegistroCorrida
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant

// the export of a report (#248): csv for Excel in es-PE (utf-8 with a BOM, `;`, stable headers: the json's field names)
// and xlsx with the sheets Resumen, Detalle and Notas, parsed back here
class ExportacionTest {
    private val fila =
        FilaReportePredio(
            id = "b1",
            predio = "p1",
            codigoPredio = "01-01-0001",
            codigoCatastro = "PM-1",
            contribuyente = "k1",
            codigoContribuyente = "000123",
            nombreContribuyente = "QUISPE; \"EL CHATO\" JUAN",
            declaracion = "d1",
            sector = "01",
            manzana = "A",
            lote = "1",
            uso = "010101",
            usoDescripcion = "CASA HABITACIÓN",
            categoria = "SUBVALUADO",
            confianza = "CONFIABLE",
            autoavaluoDeclarado = BigDecimal("100000.00"),
            autoavaluoCatastro = BigDecimal("150000.50"),
            deltaAutoavaluo = BigDecimal("50000.50"),
            impuestoDeclarado = BigDecimal("330.00"),
            impuestoCatastro = BigDecimal("630.00"),
            deltaImpuesto = BigDecimal("300.00"),
            deltaArbitrios = null,
            estimado = false,
            areaTerrenoDeclarada = BigDecimal("200"),
            areaTerrenoCatastro = BigDecimal("240.5"),
            areaConstruidaDeclarada = null,
            areaConstruidaCatastro = BigDecimal("180"),
            nivelesComparados = 2,
            nivelesCatastroMayor = 1,
            deltaAreaNiveles = BigDecimal("12.25"),
            decision = "A_INSPECCIONAR"
        )

    private val exportable =
        Exportable(
            reporte = Reporte.SUBVALUADOS,
            corrida = RegistroCorrida(id = "c-123", anio = 2026, estado = "TERMINADA", terminado = "2026-10-20T15:00:00Z", versionCatastro = "valuacion-v2"),
            filtros = Filtros(sector = "01", q = "quispe"),
            orden = Orden.DELTA_IMPUESTO,
            dir = Direccion.DESC,
            generado = Instant.parse("2026-10-27T10:00:00Z")
        )

    @Test
    fun `the csv is utf-8 with a BOM, separated by semicolons, with the json's names as headers`() {
        val bytes = Exportacion.csv(Exportacion.PREDIOS, listOf(fila))
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        val lineas = String(bytes, 3, bytes.size - 3, Charsets.UTF_8).split("\r\n")
        assertEquals(Exportacion.PREDIOS.map { it.clave }.joinToString(";"), lineas[0])
        assertTrue(lineas[0].startsWith("codigoPredio;codigoCatastro;"), lineas[0])
        val valores =
            Exportacion.PREDIOS
                .map { it.clave }
                .zip(partir(lineas[1]))
                .toMap()
        // a ; or a quote is quoted, its quotes doubled; numbers plain, with a point; null empty
        assertEquals("QUISPE; \"EL CHATO\" JUAN", valores["nombreContribuyente"])
        assertEquals("50000.50", valores["deltaAutoavaluo"])
        assertEquals("", valores["areaConstruidaDeclarada"])
        assertEquals("CASA HABITACIÓN", valores["usoDescripcion"])
        assertEquals("A_INSPECCIONAR", valores["decision"])
        // the last line ends too
        assertEquals("", lineas.last())
        assertEquals(3, lineas.size)
    }

    @Test
    fun `the contribuyentes' csv has their columns`() {
        val bytes =
            Exportacion.csv(
                Exportacion.CONTRIBUYENTES,
                listOf(FilaReporteContribuyente("k1", "000123", "UNO", 3, BigDecimal("90000"), BigDecimal("640.5")))
            )
        val lineas = String(bytes, 3, bytes.size - 3, Charsets.UTF_8).split("\r\n")
        assertEquals("codigoContribuyente;nombreContribuyente;predios;deltaAutoavaluo;deltaImpuesto", lineas[0])
        assertEquals("000123;UNO;3;90000;640.5", lineas[1])
    }

    @Test
    fun `the xlsx has Resumen, Detalle and Notas`() {
        val bytes =
            Exportacion.xlsx(
                exportable,
                Exportacion.PREDIOS,
                listOf(fila, fila.copy(id = "b2", codigoPredio = "01-01-0002", deltaImpuesto = BigDecimal("10.00")))
            )
        ReadableWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            assertEquals(listOf("Resumen", "Detalle", "Notas"), libro.sheets.map { it.name }.toList())
            val resumen =
                libro.sheets
                    .toList()[0]
                    .read()
                    .map { r -> (0 until r.cellCount).map { r.getCellText(it) } }
            val texto = resumen.flatten().joinToString("|")
            for (esperado in listOf("Predios subvaluados", "c-123", "2026", "valuacion-v2", "sector", "01", "q", "quispe", "deltaImpuesto desc")) {
                assertTrue(esperado in texto, "falta $esperado en $texto")
            }
            val filas = resumen.single { it.firstOrNull() == "Filas" }
            assertEquals("2", filas[1])
            val totalImpuesto = resumen.single { it.firstOrNull() == "Δ impuesto" }
            assertEquals(0, BigDecimal("310").compareTo(BigDecimal(totalImpuesto[1])))

            val detalle = libro.sheets.toList()[1].read()
            val cabecera = detalle[0].let { r -> (0 until r.cellCount).map { r.getCellText(it) } }
            assertEquals(Exportacion.PREDIOS.map { it.titulo }, cabecera)
            assertEquals(3, detalle.size)
            val primera = detalle[1]
            assertEquals("01-01-0001", primera.getCellText(cabecera.indexOf("Código del predio")))
            // money and areas are numbers, not text
            assertEquals(0, BigDecimal("50000.50").compareTo(primera.getCellAsNumber(cabecera.indexOf("Δ autoavalúo")).get()))
            assertEquals(0, BigDecimal("240.5").compareTo(primera.getCellAsNumber(cabecera.indexOf("Área de terreno catastro (m²)")).get()))
            assertEquals("", primera.getCellText(cabecera.indexOf("Área construida declarada (m²)")))

            val notas =
                libro.sheets
                    .toList()[2]
                    .read()
                    .flatMap { r -> (0 until r.cellCount).map { r.getCellText(it) } }
                    .joinToString("|")
            assertTrue("brecha_predio" in notas, notas)
            for (c in Exportacion.PREDIOS) assertTrue(c.titulo in notas, "falta la definición de ${c.titulo}")
        }
    }

    @Test
    fun `the file is named by its report and year`() {
        assertEquals("brecha-subvaluados-2026.xlsx", Exportacion.nombre(Reporte.SUBVALUADOS, 2026, "xlsx"))
        assertEquals("brecha-mayores-5-uit-2025.csv", Exportacion.nombre(Reporte.MAYORES_5_UIT, 2025, "csv"))
    }

    // a csv line's fields: quotes as Excel reads them
    private fun partir(linea: String): List<String> {
        val campos = mutableListOf<String>()
        val actual = StringBuilder()
        var entreComillas = false
        var i = 0
        while (i < linea.length) {
            val c = linea[i]
            when {
                entreComillas && c == '"' && linea.getOrNull(i + 1) == '"' -> {
                    actual.append('"')
                    i++
                }
                c == '"' -> entreComillas = !entreComillas
                c == ';' && !entreComillas -> {
                    campos += actual.toString()
                    actual.clear()
                }
                else -> actual.append(c)
            }
            i++
        }
        campos += actual.toString()
        return campos
    }
}
