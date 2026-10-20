package srtm.emision

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.buffer.DataBufferUtils
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import wasichai.files.FileStore
import java.util.UUID
import kotlin.random.Random

// wasichai-files' bytes in an s3 bucket (srtm.archivos.almacen=s3), against the minio of AlmacenS3Test: what the
// module's FileStore asks, under the prefix, and the app's configuration
@Tag("integration")
class ArchivosS3Test {
    private val s3 = MinioS3.cliente

    @Test
    fun `a file goes up, comes back whole under its prefix, and goes away`() =
        runBlocking<Unit> {
            val prefijo = aparte()
            val almacen = ArchivosS3(s3, BUCKET, prefijo)
            val bytes = Random(7).nextBytes(200 * 1024 + 5)
            val clave = clave()

            almacen.put(clave, bytes, "image/png")

            assertArrayEquals(bytes, leer(almacen, clave))
            val cabecera = s3.headObject { it.bucket(BUCKET).key("$prefijo/$clave") }
            assertEquals("image/png", cabecera.contentType())
            almacen.delete(clave)
            assertThrows(NoSuchKeyException::class.java) { runBlocking { leer(almacen, clave) } }
            // a key that is not there is no error
            almacen.delete(clave)
        }

    @Test
    fun `only the module's keys reach the bucket`() {
        val almacen = ArchivosS3(s3, BUCKET, aparte())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { almacen.put("../fuera", byteArrayOf(1), "image/png") } }
        assertThrows(IllegalArgumentException::class.java) { almacen.open("a/b") }
        assertThrows(IllegalArgumentException::class.java) { ArchivosS3(s3, BUCKET, "a/../b") }
    }

    @Test
    fun `with almacen s3 the app keeps the files in the bucket`() {
        contexto(BUCKET, "srtm.archivos.s3.prefijo=${aparte()}").run {
            assertEquals(null, it.startupFailure)
            val almacen = it.getBean(FileStore::class.java)
            assertTrue(almacen is ArchivosS3, "$almacen")
            val clave = clave()
            runBlocking {
                almacen.put(clave, byteArrayOf(1, 2, 3), "image/png")
                assertArrayEquals(byteArrayOf(1, 2, 3), leer(almacen, clave))
            }
        }
    }

    @Test
    fun `a bucket that is not there stops the startup with its name`() {
        val falta = "no-existe-${UUID.randomUUID().toString().take(8)}"
        contexto(falta).run {
            val causas = generateSequence(it.startupFailure) { e -> e.cause }.mapNotNull { e -> e.message }.toList()
            assertTrue("No se puede usar el bucket '$falta' de srtm.archivos.s3.bucket: no existe" in causas, "$causas")
        }
    }

    private suspend fun leer(
        almacen: FileStore,
        clave: String
    ): ByteArray {
        val junto = DataBufferUtils.join(almacen.open(clave)).awaitSingle()
        try {
            return ByteArray(junto.readableByteCount()).also { junto.read(it) }
        } finally {
            DataBufferUtils.release(junto)
        }
    }

    // the app's configuration against the minio, its credentials where the sdk's default chain looks first
    private fun contexto(
        bucket: String,
        vararg propiedades: String
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(ArchivosConfig::class.java)
            .withPropertyValues("srtm.archivos.almacen=s3", "srtm.archivos.s3.bucket=$bucket", "srtm.archivos.s3.endpoint=${MinioS3.endpoint}", *propiedades)
            .withSystemProperties("aws.accessKeyId=${MinioS3.usuario}", "aws.secretAccessKey=${MinioS3.clave}")

    // the module's keys: <organization id>/<file id>
    private fun clave(): String = FileStore.keyOf(UUID.randomUUID(), UUID.randomUUID())

    private fun aparte(): String = "archivos/${UUID.randomUUID()}"

    private companion object {
        val BUCKET = MinioS3.bucket("srtm-archivos")
    }
}
