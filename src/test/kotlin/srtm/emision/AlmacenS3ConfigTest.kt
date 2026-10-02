package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.auth.credentials.internal.WebIdentityCredentialsUtils
import software.amazon.awssdk.services.s3.S3Client

// which almacén srtm.emision.almacen makes (wasichai/srtm-backend#56), and what the sdk needs for irsa. no bucket is
// reached here: AlmacenS3Test starts the context against minio
class AlmacenS3ConfigTest {
    private val contexto =
        ApplicationContextRunner()
            .withUserConfiguration(EmisionConfig::class.java, AlmacenS3Config::class.java, AlmacenLocal::class.java)
            .withPropertyValues("srtm.emision.dir=build/almacen-config-test")
            .withSystemProperties("aws.accessKeyId=prueba", "aws.secretAccessKey=prueba")

    @Test
    fun `with almacen s3 and no bucket the app does not start and says why`() {
        contexto.withPropertyValues("srtm.emision.almacen=s3").run {
            val causas = generateSequence(it.startupFailure) { e -> e.cause }.map { e -> e.message }.toList()
            assertTrue("srtm.emision.s3.bucket es obligatorio con srtm.emision.almacen=s3" in causas, "$causas")
        }
    }

    @Test
    fun `with almacen s3 and a bucket it cannot reach the app does not start and names the bucket`() {
        // nothing listens on port 1
        contexto
            .withPropertyValues("srtm.emision.almacen=s3", "srtm.emision.s3.bucket=emisiones", "srtm.emision.s3.endpoint=http://127.0.0.1:1")
            .run {
                val causas = generateSequence(it.startupFailure) { e -> e.cause }.mapNotNull { e -> e.message }.toList()
                assertTrue(causas.any { m -> m.startsWith("No se puede usar el bucket 'emisiones' de srtm.emision.s3.bucket: ") }, "$causas")
            }
    }

    @Test
    fun `by default the almacen is the disk's and no s3 client is made`() {
        contexto.run {
            assertEquals(listOf(AlmacenLocal::class), it.getBeansOfType(AlmacenEmision::class.java).values.map { a -> a::class })
            assertTrue(it.getBeansOfType(S3Client::class.java).isEmpty())
        }
    }

    // the default chain's web identity provider (irsa) loads sts by reflection: without the module every call of an
    // eks pod fails with "To use web identity tokens, the 'sts' service module must be on the class path"
    @Test
    fun `the web identity of irsa finds sts on the classpath`() {
        assertEquals(
            "software.amazon.awssdk.services.sts.internal.StsWebIdentityCredentialsProviderFactory",
            WebIdentityCredentialsUtils.factory()::class.java.name
        )
    }
}
