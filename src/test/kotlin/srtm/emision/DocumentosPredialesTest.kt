package srtm.emision

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import srtm.rentas.Registros
import java.util.UUID

class DocumentosPredialesTest {
    // wasichai/srtm-backend#40 implements it
    @Test
    fun `the hr is a stub for now`() {
        val documentos = DocumentosPrediales(mock(Registros::class.java), PdfRenderer(), "MUNICIPALIDAD")
        assertThrows<NotImplementedError> { runBlocking { documentos.hr(UUID.randomUUID(), 2026) } }
    }
}
