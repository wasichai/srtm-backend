package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Flux
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// the body of GET /emisiones/{id}/archivo: a file on disk through spring's writer (its ranges answered), and a
// blocking stream (an s3 object) in chunks, never read on the caller's thread, which in the server is netty's event loop
class DescargaEmisionTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a stream that blocks is opened and read on boundedElastic, in chunks`() {
        val bytes = Random(3).nextBytes(300_000)
        val hilos = ConcurrentHashMap.newKeySet<String>()
        val recurso =
            object : AbstractResource() {
                override fun getDescription() = "un objeto de s3"

                override fun getInputStream(): InputStream {
                    hilos += Thread.currentThread().name
                    return object : ByteArrayInputStream(bytes) {
                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int
                        ): Int {
                            hilos += Thread.currentThread().name
                            return super.read(b, off, len)
                        }
                    }
                }
            }

        val (leido, trozos) = leer(cuerpoDeDescarga(recurso))

        assertEquals(bytes.toList(), leido.toList())
        assertTrue(hilos.isNotEmpty() && hilos.all { it.startsWith("boundedElastic-") }, "$hilos")
        assertTrue(trozos.all { it <= 64 * 1024 } && trozos.size >= 5, "$trozos")
    }

    @Test
    fun `a file on disk answers a range with a 206 and its part, and without one the whole file`() {
        val bytes = Random(5).nextBytes(200_000)
        val archivo = FileSystemResource(Files.write(dir.resolve("emision.pdf"), bytes))

        val parte = descargar(archivo, "bytes=100-199")
        val entera = descargar(archivo, null)

        assertEquals(HttpStatus.PARTIAL_CONTENT, parte.statusCode)
        assertEquals("bytes 100-199/200000", parte.headers.getFirst(HttpHeaders.CONTENT_RANGE))
        assertEquals(100, parte.headers.contentLength)
        assertEquals(bytes.copyOfRange(100, 200).toList(), cuerpo(parte).toList())
        assertTrue(entera.statusCode in setOf(null, HttpStatus.OK), "${entera.statusCode}")
        assertEquals(bytes.toList(), cuerpo(entera).toList())
    }

    @Test
    fun `a stream answers the whole file even to a range`() {
        val bytes = Random(7).nextBytes(1000)
        val recurso =
            object : AbstractResource() {
                override fun getDescription() = "un objeto de s3"

                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
            }

        val respuesta = descargar(recurso, "bytes=0-9")

        assertTrue(respuesta.statusCode in setOf(null, HttpStatus.OK), "${respuesta.statusCode}")
        assertEquals(bytes.toList(), cuerpo(respuesta).toList())
    }

    // the response escribirDescarga writes for a GET with that Range
    private fun descargar(
        recurso: Resource,
        rango: String?
    ): MockServerHttpResponse {
        val pedido = MockServerHttpRequest.get("/api/srtm/emisiones/x/archivo")
        if (rango != null) pedido.header(HttpHeaders.RANGE, rango)
        val exchange = MockServerWebExchange.from(pedido)
        escribirDescarga(recurso, MediaType.APPLICATION_PDF, exchange).block(Duration.ofSeconds(10))
        return exchange.response
    }

    private fun cuerpo(respuesta: MockServerHttpResponse): ByteArray = leer(respuesta.body).first

    // the bytes, and the size of each chunk they came in
    private fun leer(cuerpo: Flux<DataBuffer>): Pair<ByteArray, List<Int>> {
        val trozos =
            cuerpo
                .map { buffer ->
                    try {
                        ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
                    } finally {
                        DataBufferUtils.release(buffer)
                    }
                }.collectList()
                .block(Duration.ofSeconds(10))!!
        return trozos.fold(ByteArray(0)) { a, b -> a + b } to trozos.map { it.size }
    }
}
