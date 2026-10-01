package srtm.emision

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.core.io.InputStreamResource
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

// the almacén in an s3 bucket (wasichai/srtm-backend#56), against a minio of testcontainers: the contract, and what
// only it has (the parts, the resource that streams, the prefix)
@Tag("integration")
class AlmacenS3Test : AlmacenEmisionContractTest() {
    private val s3 = MinioS3.cliente

    // a prefix of its own per store in the class's bucket: the tests never see each other's keys
    override fun nuevo(): AlmacenEmision = AlmacenS3(s3, BUCKET, aparte())

    @Test
    fun `a file bigger than one part goes up in parts`() =
        runBlocking<Unit> {
            val prefijo = aparte()
            // parts of 5 MiB, the least s3 takes: 8 whole ones and a short last one
            val almacen = AlmacenS3(s3, BUCKET, prefijo, parte = 5L * MIB)
            val bytes = Random(11).nextBytes(40 * MIB.toInt() + 123)
            val entrada = Files.write(local.resolve("grande.bin"), bytes)

            almacen.guardar("emision-a/emision-2026-a.pdf", entrada)

            assertFalse(Files.exists(entrada))
            val copia = local.resolve("copia.bin")
            almacen.traer("emision-a/emision-2026-a.pdf", copia)
            assertArrayEquals(bytes, Files.readAllBytes(copia))
            assertEquals(bytes.size.toLong(), almacen.tamano("emision-a/emision-2026-a.pdf"))
            // the etag of a multipart upload ends in its number of parts
            val etag = s3.headObject { it.bucket(BUCKET).key("$prefijo/emision-a/emision-2026-a.pdf") }.eTag()
            assertTrue(etag.trim('"').endsWith("-9"), etag)
        }

    @Test
    fun `an upload in parts that fails is aborted and leaves nothing under the key`() =
        runBlocking<Unit> {
            val prefijo = aparte()
            val subidas = AtomicInteger()
            // the second part never goes up. a proxy: kotlin's `by` would not delegate the sdk's default methods
            val fallido =
                Proxy.newProxyInstance(S3Client::class.java.classLoader, arrayOf(S3Client::class.java)) { _, metodo, argumentos ->
                    if (metodo.name == "uploadPart" && subidas.incrementAndGet() == 2) throw IllegalStateException("se cayó la red")
                    try {
                        metodo.invoke(s3, *argumentos.orEmpty())
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                } as S3Client
            val almacen = AlmacenS3(fallido, BUCKET, prefijo, parte = 5L * MIB)
            val entrada = Files.write(local.resolve("grande.bin"), Random(13).nextBytes(12 * MIB.toInt()))

            val error = assertThrows(IllegalStateException::class.java) { runBlocking { almacen.guardar("emision-a/x.pdf", entrada) } }

            assertEquals("se cayó la red", error.message)
            assertFalse(almacen.existe("emision-a/x.pdf"))
            assertEquals(emptyList<Any>(), s3.listMultipartUploads { it.bucket(BUCKET).prefix("$prefijo/emision-a/x.pdf") }.uploads())
            // a save that failed leaves the local file where it was
            assertTrue(Files.exists(entrada))
        }

    @Test
    fun `a download streams from the bucket and measures with a head`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            almacen.guardar("emision-a/emision-2026-a.pdf", Files.writeString(local.resolve("x.bin"), "pdf"))

            val recurso = almacen.abrir("emision-a/emision-2026-a.pdf")

            assertFalse(recurso is InputStreamResource)
            assertFalse(recurso.isFile)
            assertEquals(3L, recurso.contentLength())
            assertEquals("emision-2026-a.pdf", recurso.filename)
            assertTrue(recurso.exists())
            // each read opens the object again
            assertEquals("pdf", recurso.inputStream.use { String(it.readAllBytes()) })
            assertEquals("pdf", recurso.inputStream.use { String(it.readAllBytes()) })
            // a read dropped halfway gives its connection back
            recurso.inputStream.use { it.read() }
            almacen.borrar("emision-a/emision-2026-a.pdf")
            assertFalse(recurso.exists())
        }

    @Test
    fun `the keys go under the prefix, with its slashes trimmed, and an empty one puts them at the root`() =
        runBlocking<Unit> {
            val raiz = aparte()
            val almacen = AlmacenS3(s3, BUCKET, "/$raiz/emisiones/")

            almacen.guardar("emision-a/x.pdf", Files.writeString(local.resolve("x.bin"), "pdf"))

            assertEquals(3L, s3.headObject { it.bucket(BUCKET).key("$raiz/emisiones/emision-a/x.pdf") }.contentLength())
            assertEquals(listOf("emision-a/x.pdf"), almacen.listar("emision-a/"))
            val enLaRaiz = AlmacenS3(s3, MinioS3.bucket("emisiones-raiz"), "")
            val clave = "emision-${UUID.randomUUID()}/x.pdf"
            enLaRaiz.guardar(clave, Files.writeString(local.resolve("y.bin"), "pdf"))
            assertEquals(3L, s3.headObject { it.bucket("emisiones-raiz").key(clave) }.contentLength())
        }

    @Test
    fun `a store without bucket or with a prefix that is not a key is refused`() {
        val sinBucket = assertThrows(IllegalArgumentException::class.java) { AlmacenS3(s3, " ", "") }
        assertEquals("srtm.emision.s3.bucket es obligatorio con srtm.emision.almacen=s3", sinBucket.message)
        listOf("a/../b", "a b", "a//b").forEach { prefijo ->
            assertThrows(IllegalArgumentException::class.java, { AlmacenS3(s3, BUCKET, prefijo) }, prefijo)
        }
    }

    @Test
    fun `the client of the configuration reaches the bucket with the sdk's default credentials`() {
        val antes = listOf("aws.accessKeyId", "aws.secretAccessKey").associateWith { System.getProperty(it) }
        System.setProperty("aws.accessKeyId", MinioS3.usuario)
        System.setProperty("aws.secretAccessKey", MinioS3.clave)
        try {
            clienteS3(EmisionProperties.S3(bucket = BUCKET, endpoint = MinioS3.endpoint)).use { cliente ->
                runBlocking {
                    val almacen = AlmacenS3(cliente, BUCKET, aparte())
                    almacen.guardar("emision-a/x.pdf", Files.writeString(local.resolve("x.bin"), "pdf"))
                    assertTrue(almacen.existe("emision-a/x.pdf"))
                }
            }
        } finally {
            // as they were: EmisionS3ApiTest's context reads them too
            antes.forEach { (propiedad, valor) -> if (valor == null) System.clearProperty(propiedad) else System.setProperty(propiedad, valor) }
        }
        val sinBucket = assertThrows(IllegalArgumentException::class.java) { clienteS3(EmisionProperties.S3(endpoint = MinioS3.endpoint)) }
        assertEquals("srtm.emision.s3.bucket es obligatorio con srtm.emision.almacen=s3", sinBucket.message)
    }

    private fun aparte(): String = "pruebas/${UUID.randomUUID()}"

    private companion object {
        const val MIB = 1024L * 1024

        val BUCKET = MinioS3.bucket("emisiones-almacen")
    }
}

// one minio for the suite's s3 tests, started on first use (testcontainers' ryuk removes it with the jvm). the image
// is the community fork: minio no longer publishes minio/minio on docker hub
object MinioS3 {
    private val contenedor: MinIOContainer by lazy {
        MinIOContainer(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z").asCompatibleSubstituteFor("minio/minio")).also { it.start() }
    }

    val endpoint: String get() = contenedor.s3URL

    val usuario: String get() = contenedor.userName

    val clave: String get() = contenedor.password

    // with its own credentials, never the sdk's chain
    val cliente: S3Client by lazy {
        S3Client
            .builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(usuario, clave)))
            .build()
    }

    // the bucket, created if it is not there yet
    @Synchronized
    fun bucket(nombre: String): String {
        try {
            cliente.headBucket { it.bucket(nombre) }
        } catch (e: S3Exception) {
            if (e.statusCode() != 404) throw e
            cliente.createBucket { it.bucket(nombre) }
        }
        return nombre
    }
}
