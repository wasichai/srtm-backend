package srtm.emision

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
import java.net.URI
import java.time.LocalDate
import java.util.UUID

// the predial documents as pdf, to see, print or download from the portal. under /api like the rest: core's jwt
// protects it and its handler answers every error as problem+json, except the pu's 409, which carries the titulares,
// and the hr's 422, which carries faltan
@RestController
@RequestMapping("/api/srtm")
class EmisionController(
    private val documentos: DocumentosPrediales,
    private val web: WasichaiWebProperties
) {
    // the PU of a predio and a titular (the only one, or `contribuyente`); without anio, the current year
    @GetMapping("/predios/{id}/pu")
    suspend fun pu(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?,
        @RequestParam(required = false) contribuyente: UUID?
    ): ResponseEntity<ByteArray> = inline(documentos.pu(id, contribuyente, anio ?: LocalDate.now().year))

    // the HR of a contribuyente; without anio, the current year
    @GetMapping("/contribuyentes/{id}/hr")
    suspend fun hr(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ): ResponseEntity<ByteArray> = inline(documentos.hr(id, anio ?: LocalDate.now().year))

    // the problem core's handler would write, plus the titulares to choose from
    @ExceptionHandler(VariosTitulares::class)
    fun variosTitulares(ex: VariosTitulares): ProblemDetail = problema(HttpStatus.CONFLICT, ex.message).apply { setProperty("titulares", ex.titulares) }

    // the same, plus the parameters of the year that are missing
    @ExceptionHandler(FaltanParametros::class)
    fun faltanParametros(ex: FaltanParametros): ProblemDetail =
        problema(HttpStatus.UNPROCESSABLE_CONTENT, ex.message).apply { setProperty("faltan", ex.faltan) }

    private fun problema(
        status: HttpStatus,
        detail: String?
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("${web.problemBaseUri.trimEnd('/')}/${status.value()}")
            title = status.reasonPhrase
        }

    private fun inline(documento: Documento): ResponseEntity<ByteArray> =
        ResponseEntity
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
