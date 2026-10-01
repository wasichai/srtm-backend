package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.s3.S3Client

// which almacén srtm.emision.almacen makes (wasichai/srtm-backend#56). no bucket is reached: the client is only built
class AlmacenS3ConfigTest {
    private val contexto =
        ApplicationContextRunner()
            .withUserConfiguration(EmisionConfig::class.java, AlmacenS3Config::class.java, AlmacenLocal::class.java)
            .withPropertyValues("srtm.emision.dir=build/almacen-config-test")

    @Test
    fun `with almacen s3 the almacen is the bucket's and not the disk's`() {
        contexto
            .withPropertyValues("srtm.emision.almacen=s3", "srtm.emision.s3.bucket=emisiones", "srtm.emision.s3.endpoint=http://localhost:9000")
            .run {
                assertNotNull(it.getBean(S3Client::class.java))
                assertEquals(listOf(AlmacenS3::class), it.getBeansOfType(AlmacenEmision::class.java).values.map { a -> a::class })
            }
    }

    @Test
    fun `with almacen s3 and no bucket the app does not start and says why`() {
        contexto.withPropertyValues("srtm.emision.almacen=s3").run {
            val causas = generateSequence(it.startupFailure) { e -> e.cause }.map { e -> e.message }.toList()
            assertTrue("srtm.emision.s3.bucket es obligatorio con srtm.emision.almacen=s3" in causas, "$causas")
        }
    }

    @Test
    fun `by default the almacen is the disk's and no s3 client is made`() {
        contexto.run {
            assertEquals(listOf(AlmacenLocal::class), it.getBeansOfType(AlmacenEmision::class.java).values.map { a -> a::class })
            assertTrue(it.getBeansOfType(S3Client::class.java).isEmpty())
        }
    }
}
