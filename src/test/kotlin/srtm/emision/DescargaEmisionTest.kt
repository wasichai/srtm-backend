package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import reactor.core.publisher.Flux
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// the body of GET /emisiones/{id}/archivo: in chunks, and a blocking stream (an s3 object) never read on the caller's
// thread, which in the server is netty's event loop
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
    fun `a file on disk is read as it is`() {
        val bytes = Random(5).nextBytes(200_000)
        val archivo = Files.write(dir.resolve("emision.pdf"), bytes)

        val (leido, trozos) = leer(cuerpoDeDescarga(FileSystemResource(archivo)))

        assertEquals(bytes.toList(), leido.toList())
        assertTrue(trozos.all { it <= 64 * 1024 }, "$trozos")
    }

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
