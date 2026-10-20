package srtm.emision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import wasichai.files.FileStore

// where wasichai-files keeps the bytes of the FILE and IMAGE fields (the municipalidad's escudo): local, the module's
// own store under wasichai.files.local.path, or s3, this one. srtm never sets wasichai.files.store=s3: the module's s3
// store needs the sdk's async client, and its netty is left out of the build (build.gradle.kts)
@ConfigurationProperties("srtm.archivos")
data class ArchivosProperties(
    val almacen: String = "local",
    val s3: EmisionProperties.S3 = EmisionProperties.S3()
)

// wasichai-files' FileStore in an s3 bucket (or one that speaks its api, as minio), `<prefijo><organization>/<file>`,
// over the sync client the almacén of the emisiones uses: every instance sees the same files, with no shared disk
class ArchivosS3(
    private val s3: S3Client,
    private val bucket: String,
    prefijo: String = ""
) : FileStore,
    AutoCloseable {
    private val prefijo: String = prefijo.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

    init {
        require(this.prefijo.isEmpty() || runCatching { exigirClave(this.prefijo, prefijo = true) }.isSuccess) {
            "srtm.archivos.s3.prefijo inválido: '$prefijo'"
        }
    }

    override suspend fun put(
        key: String,
        content: ByteArray,
        contentType: String
    ) {
        val objeto = objeto(key)
        withContext(Dispatchers.IO) {
            s3.putObject({ it.bucket(bucket).key(objeto).contentType(contentType) }, RequestBody.fromBytes(content))
        }
    }

    // read on the blocking pool as it is consumed; a missing key fails on subscription, as the FileStore asks
    override fun open(key: String): Flux<DataBuffer> {
        val objeto = objeto(key)
        return DataBufferUtils
            .readInputStream({ s3.getObject { it.bucket(bucket).key(objeto) } }, DefaultDataBufferFactory.sharedInstance, BLOQUE)
            .subscribeOn(Schedulers.boundedElastic())
    }

    // s3 answers a delete of a missing key as one of an existing key
    override suspend fun delete(key: String) {
        val objeto = objeto(key)
        withContext(Dispatchers.IO) { s3.deleteObject { it.bucket(bucket).key(objeto) } }
    }

    // at startup: a wrong bucket or credentials without access stop the app with the bucket's name
    fun comprobarBucket() {
        try {
            s3.headBucket { it.bucket(bucket) }
        } catch (e: SdkException) {
            val motivo =
                when ((e as? S3Exception)?.statusCode()) {
                    404 -> "no existe"
                    403 -> "las credenciales no tienen acceso (s3:ListBucket)"
                    else -> e.message
                }
            throw IllegalStateException("No se puede usar el bucket '$bucket' de srtm.archivos.s3.bucket: $motivo", e)
        }
    }

    override fun close() = s3.close()

    private fun objeto(key: String): String = prefijo + FileStore.requireKey(key)

    private companion object {
        const val BLOQUE = 64 * 1024
    }
}

// with srtm.archivos.almacen=s3 this store replaces the module's local one (its FileStore backs off)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArchivosProperties::class)
class ArchivosConfig {
    @Bean
    @ConditionalOnProperty(prefix = "srtm.archivos", name = ["almacen"], havingValue = "s3")
    fun archivosS3(config: ArchivosProperties): FileStore =
        ArchivosS3(clienteS3(config.s3, "srtm.archivos"), config.s3.bucket, config.s3.prefijo).also { it.comprobarBucket() }
}
