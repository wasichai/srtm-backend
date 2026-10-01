package srtm.emision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.Resource
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.ContentStreamProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// below this a file goes up in one put; from it, in parts of this size (s3 wants at least 5 MiB per part but the last)
const val PARTE_S3: Long = 16L * 1024 * 1024

// the files in an s3 bucket (or one that speaks its api, as minio), `<prefijo><key>`: every instance sees the same
// ones, so a deployment needs no shared disk (wasichai/srtm-backend#56). a key is only visible once it is whole:
// a put and the completion of a multipart upload are atomic, and an upload that fails is aborted
class AlmacenS3(
    private val s3: S3Client,
    private val bucket: String,
    prefijo: String = "",
    // the size of the parts, and of the largest file that goes in a single put
    private val parte: Long = PARTE_S3
) : AlmacenEmision {
    private val prefijo: String = normalizarPrefijo(prefijo)

    init {
        require(bucket.isNotBlank()) { "srtm.emision.s3.bucket es obligatorio con srtm.emision.almacen=s3" }
        require(parte >= 5L * 1024 * 1024) { "las partes de s3 son de al menos 5 MiB: $parte" }
    }

    override suspend fun guardar(
        clave: String,
        archivo: Path
    ) {
        val objeto = objeto(clave)
        withContext(Dispatchers.IO) {
            val tamano = Files.size(archivo)
            if (tamano < parte) {
                s3.putObject({ it.bucket(bucket).key(objeto) }, RequestBody.fromFile(archivo))
            } else {
                subirEnPartes(objeto, archivo, tamano)
            }
            Files.deleteIfExists(archivo)
        }
    }

    // a resource that measures with a head and opens the object only when it is read: the download goes from s3 to
    // the client as it comes
    override suspend fun abrir(clave: String): Resource {
        val objeto = objeto(clave)
        val tamano = withContext(Dispatchers.IO) { cabecera(clave, objeto) }
        return RecursoS3(s3, bucket, objeto, tamano)
    }

    override suspend fun traer(
        clave: String,
        destino: Path
    ) {
        val objeto = objeto(clave)
        withContext(Dispatchers.IO) {
            val respuesta = sinClave(clave) { s3.getObject { it.bucket(bucket).key(objeto) } }
            try {
                Files.copy(respuesta, destino, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                // half a copy is no copy, and the rest of the object is not read
                respuesta.abort()
                Files.deleteIfExists(destino)
                throw e
            } finally {
                respuesta.close()
            }
        }
    }

    // s3 answers a delete of a missing key as one of an existing key
    override suspend fun borrar(clave: String) {
        val objeto = objeto(clave)
        withContext(Dispatchers.IO) { s3.deleteObject { it.bucket(bucket).key(objeto) } }
    }

    override suspend fun existe(clave: String): Boolean {
        val objeto = objeto(clave)
        return withContext(Dispatchers.IO) {
            try {
                cabecera(clave, objeto)
                true
            } catch (_: ClaveInexistenteException) {
                false
            }
        }
    }

    override suspend fun tamano(clave: String): Long {
        val objeto = objeto(clave)
        return withContext(Dispatchers.IO) { cabecera(clave, objeto) }
    }

    override suspend fun listar(prefijo: String): List<String> {
        exigirClave(prefijo, prefijo = true)
        return withContext(Dispatchers.IO) {
            s3
                .listObjectsV2Paginator { it.bucket(bucket).prefix(this@AlmacenS3.prefijo + prefijo) }
                .contents()
                .map { it.key().removePrefix(this@AlmacenS3.prefijo) }
                .sorted()
        }
    }

    // the parts are read from the file one at a time, never whole in memory. what fails aborts the upload: no
    // half object is ever under the key, and s3 keeps no parts to bill
    private fun subirEnPartes(
        objeto: String,
        archivo: Path,
        tamano: Long
    ) {
        val subida =
            s3
                .createMultipartUpload { it.bucket(bucket).key(objeto).checksumAlgorithm(ChecksumAlgorithm.CRC32) }
                .uploadId()
        try {
            val partes =
                (0 until (tamano + parte - 1) / parte).map { i ->
                    val desde = i * parte
                    val largo = minOf(parte, tamano - desde)
                    val numero = (i + 1).toInt()
                    TramoDeArchivo(archivo, desde, largo).use { tramo ->
                        val subido =
                            s3.uploadPart(
                                {
                                    it
                                        .bucket(bucket)
                                        .key(objeto)
                                        .uploadId(subida)
                                        .partNumber(numero)
                                        .checksumAlgorithm(ChecksumAlgorithm.CRC32)
                                },
                                RequestBody.fromContentProvider(tramo, largo, "application/octet-stream")
                            )
                        CompletedPart
                            .builder()
                            .partNumber(numero)
                            .eTag(subido.eTag())
                            .checksumCRC32(subido.checksumCRC32())
                            .build()
                    }
                }
            s3.completeMultipartUpload {
                it
                    .bucket(bucket)
                    .key(objeto)
                    .uploadId(subida)
                    .multipartUpload { m -> m.parts(partes) }
            }
        } catch (e: Throwable) {
            runCatching { s3.abortMultipartUpload { it.bucket(bucket).key(objeto).uploadId(subida) } }.onFailure(e::addSuppressed)
            throw e
        }
    }

    // the object's size; a missing key is a ClaveInexistenteException
    private fun cabecera(
        clave: String,
        objeto: String
    ): Long = sinClave(clave) { s3.headObject { it.bucket(bucket).key(objeto) }.contentLength() }

    // a 404 of s3 (a head has no body to say NoSuchKey) is a missing key
    private fun <T> sinClave(
        clave: String,
        llamada: () -> T
    ): T =
        try {
            llamada()
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) throw ClaveInexistenteException(clave)
            throw e
        }

    // the key is checked before it reaches s3
    private fun objeto(clave: String): String = prefijo + exigirClave(clave)

    private companion object {
        // "", or segments of a key ending in `/`. the slashes around it are dropped
        fun normalizarPrefijo(prefijo: String): String {
            val limpio = prefijo.trim().trim('/')
            if (limpio.isEmpty()) return ""
            require(runCatching { exigirClave(limpio) }.isSuccess) { "srtm.emision.s3.prefijo inválido: '$prefijo'" }
            return "$limpio/"
        }
    }
}

// one part of a file, for the sdk to read (again, on a retry). each stream it asks for closes the one before it
private class TramoDeArchivo(
    private val archivo: Path,
    private val desde: Long,
    private val largo: Long
) : ContentStreamProvider,
    AutoCloseable {
    private var abierto: InputStream? = null

    override fun newStream(): InputStream {
        abierto?.close()
        val canal = FileChannel.open(archivo).position(desde)
        return Limitado(Channels.newInputStream(canal), largo).also { abierto = it }
    }

    override fun close() {
        abierto?.close()
    }
}

// the first `restante` bytes of a stream
private class Limitado(
    entrada: InputStream,
    private var restante: Long
) : FilterInputStream(entrada) {
    override fun read(): Int {
        if (restante <= 0) return -1
        return super.read().also { if (it >= 0) restante-- }
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int
    ): Int {
        if (restante <= 0) return -1
        return super.read(b, off, minOf(len.toLong(), restante).toInt()).also { if (it > 0) restante -= it }
    }

    override fun skip(n: Long): Long = super.skip(minOf(n, restante)).also { restante -= it }

    override fun available(): Int = minOf(super.available().toLong(), restante).toInt()

    override fun markSupported() = false
}

// an object of the bucket as a spring resource: its length is the head's, its content a get opened on each read
private class RecursoS3(
    private val s3: S3Client,
    private val bucket: String,
    private val objeto: String,
    private val tamano: Long
) : AbstractResource() {
    override fun getDescription(): String = "s3://$bucket/$objeto"

    override fun getFilename(): String = objeto.substringAfterLast('/')

    override fun contentLength(): Long = tamano

    override fun exists(): Boolean =
        try {
            s3.headObject { it.bucket(bucket).key(objeto) }
            true
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) false else throw e
        }

    override fun isReadable(): Boolean = true

    override fun getInputStream(): InputStream = HastaElFinal(s3.getObject { it.bucket(bucket).key(objeto) })
}

// closing a get before its end aborts the connection: the http client would otherwise read the rest of the object
// (gigabytes, for a download the client dropped) to reuse it
private class HastaElFinal(
    private val respuesta: ResponseInputStream<GetObjectResponse>
) : FilterInputStream(respuesta) {
    private var leido = false

    override fun read(): Int = super.read().also { if (it < 0) leido = true }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int
    ): Int = super.read(b, off, len).also { if (it < 0) leido = true }

    override fun close() {
        if (!leido) respuesta.abort()
        super.close()
    }
}

// the client and the almacén of srtm.emision.almacen=s3. the credentials come from the sdk's default chain: the
// environment (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY), the pod's web identity (irsa), a profile, the instance
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "srtm.emision", name = ["almacen"], havingValue = "s3")
class AlmacenS3Config {
    @Bean
    fun clienteS3(config: EmisionProperties): S3Client = clienteS3(config.s3)

    @Bean
    fun almacenS3(
        s3: S3Client,
        config: EmisionProperties
    ): AlmacenEmision = AlmacenS3(s3, config.s3.bucket, config.s3.prefijo)
}

// without region, the sdk's chain (AWS_REGION, the profile, the instance); against another endpoint (minio), whose
// region rarely matters, us-east-1. another endpoint goes path-style: its buckets are not dns names
fun clienteS3(config: EmisionProperties.S3): S3Client {
    require(config.bucket.isNotBlank()) { "srtm.emision.s3.bucket es obligatorio con srtm.emision.almacen=s3" }
    val endpoint = config.endpoint?.takeIf { it.isNotBlank() }
    val cliente = S3Client.builder()
    when {
        config.region.isNotBlank() -> cliente.region(Region.of(config.region))
        endpoint != null -> cliente.region(Region.US_EAST_1)
    }
    if (endpoint != null) cliente.endpointOverride(URI.create(endpoint)).forcePathStyle(true)
    return cliente.build()
}
