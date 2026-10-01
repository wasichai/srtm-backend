package srtm.emision

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

    // the job, its lotes and its files (wasichai/srtm-backend#47, #53): one still running is cancelled
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun eliminar(
        @PathVariable id: UUID
    ) = emisiones.eliminar(id)

    // the file, streamed from the almacén (never whole in memory), to download. its length comes from the store, so
    // the response has a Content-Length without reading the file. the name is rebuilt from the job
    @GetMapping("/{id}/archivo")
    suspend fun archivo(
        @PathVariable id: UUID
    ): ResponseEntity<Resource> {
        val descarga = emisiones.archivo(id)
        val job = descarga.job
        val formato = FormatoEmision.valueOf(job.formato!!)
        return ResponseEntity
            .ok()
            .contentType(formato.mediaType)
            .contentLength(descarga.tamano)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(nombreArchivo(job.anio!!, job.id, formato))
                    .build()
                    .toString()
            ).body(descarga.recurso)
    }
}
