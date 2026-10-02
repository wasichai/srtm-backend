package srtm.arbitrios

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.platform.WasichaiWebProperties
import java.time.LocalDate
import java.util.UUID

// the HLA of a contribuyente, inline like the HR and the PU (srtm.emision.EmisionController): 422 with `faltan` when
// it cannot be made yet, 404 when nothing is charged to it in the year
@RestController
@RequestMapping("/api/srtm")
class HlaController(
    private val documentos: DocumentosArbitrios,
    private val web: WasichaiWebProperties
) {
    @GetMapping("/contribuyentes/{id}/hla")
    suspend fun hla(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ): ResponseEntity<ByteArray> {
        val documento = documentos.hla(id, anio ?: LocalDate.now().year)
        return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .inline()
                    .filename(documento.nombre)
                    .build()
                    .toString()
            ).body(documento.bytes)
    }

    @ExceptionHandler(FaltanArbitrios::class)
    fun faltan(ex: FaltanArbitrios): ProblemDetail = problemaFaltan(ex, web)
}
