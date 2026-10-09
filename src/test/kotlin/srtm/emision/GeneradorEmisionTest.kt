package srtm.emision

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import srtm.impuesto.ParametroTributario
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipInputStream

// the masiva's file, without core: contribuyentes already read, documents from a double
class GeneradorEmisionTest {
    @TempDir
    lateinit var dir: Path

    // an HR of 2 pages, a PU of 1, named as DocumentosPrediales names them; hr fails for the ids in `fallan`
    private class Documentos(
        val fallan: Set<UUID> = emptySet()
    ) : DocumentosDeEmision {
        val pedidos = mutableListOf<String>()
        val parametrosPedidos = mutableListOf<List<ParametroTributario>?>()

        override suspend fun hr(
            contribuyenteId: UUID,
            anio: Int,
            parametros: List<ParametroTributario>?
        ): Documento {
            pedidos += "HR $contribuyenteId"
            parametrosPedidos += parametros
            if (contribuyenteId in fallan) throw IllegalStateException("Faltan parámetros del año $anio")
            return Documento("HR-x-$anio.pdf", enBlanco(2))
        }

        override suspend fun pu(
            predioId: UUID,
            contribuyenteId: UUID?,
            anio: Int
        ): Documento {
            pedidos += "PU $predioId $contribuyenteId"
            return Documento("PU-${CODIGOS.getValue(predioId)}-$anio.pdf", enBlanco(1))
        }
    }

    private fun generar(
        formato: FormatoEmision,
        lotes: List<ContribuyenteAEmitir>,
        documentos: DocumentosDeEmision = Documentos(),
        parametros: List<ParametroTributario>? = null,
        avance: suspend (Int, List<ErrorEmision>) -> Unit = { _, _ -> }
    ): Pair<Path, ResultadoGeneracion> {
        val destino = dir.resolve("emision.${formato.extension}")
        val resultado = runBlocking { GeneradorEmision(documentos, PdfMerger()).generar(2026, formato, lotes, parametros, destino, avance) }
        return destino to resultado
    }

    @Test
    fun `one pdf with each contribuyente's hr followed by its pus`() {
        val documentos = Documentos()
        val (pdf, resultado) = generar(FormatoEmision.PDF, listOf(ANA, BETO), documentos)

        assertEquals(emptyList<ErrorEmision>(), resultado.errores)
        // ana: hr + 2 pus; beto: hr + 1 pu
        assertEquals(5, resultado.documentos)
        // ana: hr 2 + 2 pus; beto: hr 2 + 1 pu
        assertEquals(7, paginas(Files.readAllBytes(pdf)))
        assertEquals(
            listOf("HR ${ANA.id}", "PU $P1 ${ANA.id}", "PU $P2 ${ANA.id}", "HR ${BETO.id}", "PU $P2 ${BETO.id}"),
            documentos.pedidos
        )
    }

    @Test
    fun `every hr gets the parametros the caller read`() {
        val documentos = Documentos()
        val parametros = listOf(ParametroTributario(tipo = "UIT", valorNumerico = BigDecimal("5500")))

        generar(FormatoEmision.PDF, listOf(ANA, BETO), documentos, parametros)

        assertEquals(listOf(parametros, parametros), documentos.parametrosPedidos)
    }

    @Test
    fun `a zip with a folder per contribuyente, its hr and a pu per predio`() {
        val (zip, resultado) = generar(FormatoEmision.ZIP, listOf(ANA, BETO))

        val entradas = entradas(zip)
        assertEquals(
            listOf(
                "000001-FLORES-ANA/HR-2026.pdf",
                "000001-FLORES-ANA/PU-P-001-2026.pdf",
                "000001-FLORES-ANA/PU-P-002-2026.pdf",
                "000002-NUNEZ-BETO/HR-2026.pdf",
                "000002-NUNEZ-BETO/PU-P-002-2026.pdf"
            ),
            entradas.keys.toList()
        )
        assertEquals(5, resultado.documentos)
        assertEquals(2, paginas(entradas.getValue("000001-FLORES-ANA/HR-2026.pdf")))
        assertEquals(1, paginas(entradas.getValue("000002-NUNEZ-BETO/PU-P-002-2026.pdf")))
    }

    @Test
    fun `a contribuyente that fails goes to errores, without its documents, and the rest goes on`() {
        val (pdf, resultado) = generar(FormatoEmision.PDF, listOf(ANA, BETO), Documentos(fallan = setOf(ANA.id)))

        assertEquals(listOf(ErrorEmision("000001", "Faltan parámetros del año 2026")), resultado.errores)
        // only beto's: his hr and his pu
        assertEquals(2, resultado.documentos)
        assertEquals(3, paginas(Files.readAllBytes(pdf)))
    }

    @Test
    fun `a failed contribuyente leaves nothing in the zip`() {
        val (zip, resultado) = generar(FormatoEmision.ZIP, listOf(ANA, BETO), Documentos(fallan = setOf(BETO.id)))

        assertEquals(listOf("000002"), resultado.errores.map { it.contribuyente })
        assertEquals(3, resultado.documentos)
        assertFalse(entradas(zip).keys.any { it.startsWith("000002") })
    }

    @Test
    fun `the progress is told every 25 contribuyentes and at the end`() {
        val lotes = (1..60).map { ContribuyenteAEmitir(UUID.randomUUID(), "%06d".format(it), "C $it", emptyList()) }
        val avances = mutableListOf<Int>()

        generar(FormatoEmision.ZIP, lotes, avance = { procesados, _ -> avances += procesados })

        assertEquals(listOf(25, 50, 60), avances)
    }

    @Test
    fun `no contribuyentes is an empty zip and a pdf of no pages`() {
        assertEquals(0, generar(FormatoEmision.ZIP, emptyList()).second.documentos)
        assertEquals(emptyMap<String, ByteArray>(), entradas(generar(FormatoEmision.ZIP, emptyList()).first))
        assertEquals(0, paginas(Files.readAllBytes(generar(FormatoEmision.PDF, emptyList()).first)))
    }

    @Test
    fun `the folder is the code and the name without accents or symbols`() {
        assertEquals("000123-NUNEZ-DE-LA-O-MARIA-JOSE", carpeta("000123", "ÑÚÑEZ DE LA O, MARÍA / JOSÉ"))
        assertEquals("000123", carpeta("000123", ""))
    }

    private fun entradas(zip: Path): Map<String, ByteArray> =
        ZipInputStream(Files.newInputStream(zip)).use { input ->
            generateSequence { input.nextEntry }.associate { it.name to input.readAllBytes() }
        }

    private companion object {
        val P1: UUID = UUID.randomUUID()
        val P2: UUID = UUID.randomUUID()
        val CODIGOS = mapOf(P1 to "P-001", P2 to "P-002")
        val ANA = ContribuyenteAEmitir(UUID.randomUUID(), "000001", "FLORES ANA", listOf(P1, P2))
        val BETO = ContribuyenteAEmitir(UUID.randomUUID(), "000002", "NÚÑEZ BETO", listOf(P2))
    }
}
