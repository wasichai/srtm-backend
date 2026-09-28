package srtm.emision

import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID

// the body of POST /emisiones: without anio, the current year; without formato, one pdf
data class PedidoEmision(
    val anio: Int? = null,
    val formato: FormatoEmision? = null
)

// the masiva of the epic's contract (wasichai/srtm-backend#37). under /api like the rest: core's jwt protects it and
// its handler answers every error as problem+json (the 409s included)
@RestController
@RequestMapping("/api/srtm/emisiones")
class EmisionMasivaController(
    private val emisiones: EmisionMasivaService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun emitir(
        @RequestBody pedido: PedidoEmision
    ): Emision = emisiones.emitir(pedido.anio ?: LocalDate.now().year, pedido.formato ?: FormatoEmision.PDF)

    // the most recent first
    @GetMapping
    suspend fun lista(
        @RequestParam(required = false) anio: Int?
    ): List<Emision> = emisiones.lista(anio)

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ): Emision = emisiones.get(id)

    // the job and its file (wasichai/srtm-backend#47): 409 while it runs
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun eliminar(
        @PathVariable id: UUID
    ) = emisiones.eliminar(id)

    // the file, streamed from disk (never whole in memory), to download
    @GetMapping("/{id}/archivo")
    suspend fun archivo(
        @PathVariable id: UUID
    ): ResponseEntity<Resource> {
        val (job, archivo) = emisiones.archivo(id)
        val formato = FormatoEmision.valueOf(job.formato!!)
        return ResponseEntity
            .ok()
            .contentType(formato.mediaType)
            .contentLength(Files.size(archivo))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(archivo.fileName.toString())
                    .build()
                    .toString()
            ).body(FileSystemResource(archivo))
    }
}
