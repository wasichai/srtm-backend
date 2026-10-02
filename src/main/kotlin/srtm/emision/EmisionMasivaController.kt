package srtm.emision

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.ResolvableType
import org.springframework.core.io.Resource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.codec.ResourceHttpMessageWriter
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import wasichai.core.common.ValidationException
import wasichai.core.platform.WasichaiWebProperties
import java.net.URI
import java.time.LocalDate
import java.util.UUID

// the body of POST /emisiones: without anio, the current year; without formato, one pdf; without documentos, each
// contribuyente's HR and PUs (HR, PU, HLA: any of them)
data class PedidoEmision(
    val anio: Int? = null,
    val formato: FormatoEmision? = null,
    val documentos: List<String>? = null
)

// the documents a POST asks for: a name that is none of them, or none at all, is a 400 that names the field
fun documentosPedidos(nombres: List<String>?): Set<DocumentoEmision> {
    if (nombres == null) return POR_DEFECTO
    val conocidos = DocumentoEmision.entries.map { it.name }
    nombres
        .firstOrNull {
            it !in conocidos
        }?.let { throw ValidationException("Documento desconocido: $it", "documentos", "uno o más de ${conocidos.joinToString(", ")}") }
    if (nombres.isEmpty()) throw ValidationException("Sin documentos que emitir", "documentos", "uno o más de ${conocidos.joinToString(", ")}")
    return nombres.map(DocumentoEmision::valueOf).toSet()
}

// the masiva of the epic's contract (wasichai/srtm-backend#37). under /api like the rest: core's jwt protects it and
// its handler answers every error as problem+json (the 409s included)
@RestController
@RequestMapping("/api/srtm/emisiones")
class EmisionMasivaController(
    private val emisiones: EmisionMasivaService,
    private val web: WasichaiWebProperties
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun emitir(
        @RequestBody pedido: PedidoEmision
    ): Emision = emisiones.emitir(pedido.anio ?: LocalDate.now().year, pedido.formato ?: FormatoEmision.PDF, documentosPedidos(pedido.documentos))

    // the problem core's handler would write, plus what the year lacks for the documents asked for (the HLA's)
    @ExceptionHandler(FaltanParametros::class)
    fun faltanParametros(ex: FaltanParametros): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.message).apply {
            type = URI.create("${web.problemBaseUri.trimEnd('/')}/${HttpStatus.UNPROCESSABLE_CONTENT.value()}")
            title = HttpStatus.UNPROCESSABLE_CONTENT.reasonPhrase
            setProperty("faltan", ex.faltan)
        }

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
    // the response has a Content-Length without reading the file. the name is rebuilt from the job. written straight
    // to the response: a ResponseEntity has one body type, and the file and the stream need two (escribirDescarga)
    @GetMapping("/{id}/archivo")
    suspend fun archivo(
        @PathVariable id: UUID,
        exchange: ServerWebExchange
    ) {
        val descarga = emisiones.archivo(id)
        val job = descarga.job
        val formato = FormatoEmision.valueOf(job.formato!!)
        val cabeceras = exchange.response.headers
        cabeceras.contentType = formato.mediaType
        cabeceras.contentDisposition =
            ContentDisposition
                .attachment()
                .filename(nombreArchivo(job.anio!!, job.id, formato))
                .build()
        escribirDescarga(descarga.recurso, formato.mediaType, descarga.tamano, exchange).awaitSingleOrNull()
    }
}

// the chunks the download is written in
private const val TROZO = 64 * 1024

private val RECURSOS = ResourceHttpMessageWriter(TROZO)

// the file as it is read, never whole in memory, after the headers the caller set. a file on disk goes to spring's own
// writer, as a ResponseEntity<Resource> would: an async channel, and a Range answered with a 206 (or a 416). that
// writer sets the Content-Length of what it answers (the file's, a range's, a multipart's, none for a 416): one set
// here would stay on a multipart or a 416 and cut or hang the response. anything else (an s3 object) is a blocking
// stream, read on boundedElastic (cuerpoDeDescarga), with `tamano` as its length, and answers no Range: always the
// whole file
fun escribirDescarga(
    recurso: Resource,
    tipo: MediaType,
    tamano: Long,
    exchange: ServerWebExchange
): Mono<Void> =
    if (recurso.isFile) {
        val recursoTipo = ResolvableType.forClass(Resource::class.java)
        RECURSOS.write(Mono.just(recurso), recursoTipo, recursoTipo, tipo, exchange.request, exchange.response, emptyMap<String, Any>())
    } else {
        exchange.response.headers.contentLength = tamano
        exchange.response.writeWith(cuerpoDeDescarga(recurso))
    }

// a blocking stream in chunks, opened and read on boundedElastic: never on the event loop, which spring's own encoder
// would do
fun cuerpoDeDescarga(recurso: Resource): Flux<DataBuffer> =
    DataBufferUtils.readInputStream(recurso::getInputStream, DefaultDataBufferFactory.sharedInstance, TROZO).subscribeOn(Schedulers.boundedElastic())
