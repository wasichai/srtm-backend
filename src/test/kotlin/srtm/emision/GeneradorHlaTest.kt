package srtm.emision

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import srtm.impuesto.ParametroTributario
import wasichai.core.common.ValidationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipInputStream

// the HLA in the masiva (§6.3 of the plan): after each contribuyente's HR and PUs, apart from them. one that fails
// leaves the HR and PUs in and the contribuyente among the errors; one charged nothing has none, and is no error
class GeneradorHlaTest {
    @TempDir
    lateinit var dir: Path

    private val ana = ContribuyenteAEmitir(UUID.randomUUID(), "000001", "ANA", listOf(UUID.randomUUID()))
    private val beto = ContribuyenteAEmitir(UUID.randomUUID(), "000002", "BETO", listOf(UUID.randomUUID()))
    private val ciro = ContribuyenteAEmitir(UUID.randomUUID(), "000003", "CIRO", emptyList())

    private val documentos =
        object : DocumentosDeEmision {
            override suspend fun hr(
                contribuyenteId: UUID,
                anio: Int,
                parametros: List<ParametroTributario>?
            ) = Documento("HR.pdf", enBlanco(1))

            override suspend fun pu(
                predioId: UUID,
                contribuyenteId: UUID?,
                anio: Int
            ) = Documento("PU-$predioId-$anio.pdf", enBlanco(1))
        }

    // beto's cannot be made yet; ciro is charged nothing
    private val hla: suspend (UUID) -> Documento? = { id ->
        when (id) {
            beto.id -> throw IllegalStateException("Predio 01: 24 cuotas de arbitrios por determinar")
            ciro.id -> null
            else -> Documento("HLA.pdf", enBlanco(1))
        }
    }

    private fun zip(
        incluir: Set<DocumentoEmision>,
        hla: (suspend (UUID) -> Documento?)? = this.hla
    ): Pair<List<String>, ResultadoGeneracion> {
        val destino = dir.resolve("emision-${incluir.joinToString("")}.zip")
        val resultado =
            runBlocking {
                GeneradorEmision(
                    documentos,
                    PdfMerger()
                ).generar(2026, FormatoEmision.ZIP, listOf(ana, beto, ciro), null, destino, incluir, hla) { _, _ -> }
            }
        val entradas = ZipInputStream(Files.newInputStream(destino)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        return entradas to resultado
    }

    @Test
    fun `the HLA goes after the HR and the PUs of its contribuyente`() {
        val (entradas, resultado) = zip(setOf(DocumentoEmision.HR, DocumentoEmision.PU, DocumentoEmision.HLA))
        val deAna = entradas.filter { it.startsWith("000001-ANA/") }.map { it.substringAfter('/') }
        assertEquals(listOf("HR-2026.pdf", "PU-${ana.predios[0]}-2026.pdf", "HLA-2026.pdf"), deAna)
        // beto: HR and PU in, no HLA, and why; ciro: its HR, no HLA, no error
        assertEquals(listOf("HR-2026.pdf", "PU-${beto.predios[0]}-2026.pdf"), entradas.filter { it.startsWith("000002-BETO/") }.map { it.substringAfter('/') })
        assertEquals(listOf("000003-CIRO/HR-2026.pdf"), entradas.filter { it.startsWith("000003-CIRO/") })
        assertEquals(listOf(ErrorEmision("000002", "HLA: Predio 01: 24 cuotas de arbitrios por determinar")), resultado.errores)
        assertEquals(6, resultado.documentos)
    }

    @Test
    fun `only the HLA when only it is asked for`() {
        val (entradas, resultado) = zip(setOf(DocumentoEmision.HLA))
        assertEquals(listOf("000001-ANA/HLA-2026.pdf"), entradas)
        assertEquals(1, resultado.documentos)
        assertEquals(listOf("000002"), resultado.errores.map { it.contribuyente })
    }

    @Test
    fun `without HLA asked for, or none to make, the emission is the one before the HLA`() {
        val (antes, sin) = zip(POR_DEFECTO)
        assertEquals(5, sin.documentos)
        assertEquals(emptyList<ErrorEmision>(), sin.errores)
        assertEquals(antes, zip(setOf(DocumentoEmision.HR, DocumentoEmision.PU, DocumentoEmision.HLA), hla = null).first)
    }

    @Test
    fun `what a job asks for, as it keeps it and as a POST names it`() {
        assertEquals(POR_DEFECTO, documentosDe(null))
        assertEquals(POR_DEFECTO, documentosDe(""))
        assertEquals(setOf(DocumentoEmision.HLA, DocumentoEmision.HR), documentosDe(documentosJson(setOf(DocumentoEmision.HLA, DocumentoEmision.HR))))
        assertEquals("""["HR","HLA"]""", documentosJson(setOf(DocumentoEmision.HLA, DocumentoEmision.HR)))
        assertEquals(POR_DEFECTO, documentosPedidos(null))
        assertEquals(setOf(DocumentoEmision.HLA), documentosPedidos(listOf("HLA")))
        assertEquals("documentos", assertThrows<ValidationException> { documentosPedidos(listOf("HR", "RECIBO")) }.violations.single().field)
        assertEquals("documentos", assertThrows<ValidationException> { documentosPedidos(emptyList()) }.violations.single().field)
    }
}
