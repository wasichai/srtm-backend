package srtm.emision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.function.client.WebClient
import software.amazon.awssdk.core.sync.RequestBody
import srtm.impuesto.ParametroTributario
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

// srtm.emision.almacen=s3 (wasichai/srtm-backend#56): the app against a minio, with no shared disk. two instances
// are played by two GrupoTrabajadores of this context, each with its own work dir, and nothing goes to
// srtm.emision.dir: the parte one instance generates the other can only read from the bucket. the context runs no
// worker of its own; lotes of 1 contribuyente. the credentials reach the sdk by its default chain (system properties)
@TestPropertySource(
    properties = [
        "srtm.emision.almacen=s3",
        "srtm.emision.trabajadores=0",
        "srtm.emision.lote=1",
        "srtm.emision.lease=3s",
        "srtm.emision.espera=200ms"
    ]
)
class EmisionS3ApiTest : ConEscenarioApiTest() {
    @Autowired
    lateinit var trabajadores: TrabajadoresEmision

    @Autowired
    lateinit var documentosPrediales: DocumentosPrediales

    @Autowired
    lateinit var almacen: AlmacenEmision

    @Value("\${local.server.port}")
    var puerto: Int = 0

    private val grupos = mutableListOf<GrupoTrabajadores>()

    // the real documents; armed, the first HR asked for waits on `puerta`
    private val primera = AtomicBoolean(false)

    @Volatile
    private var puerta: CompletableDeferred<Unit>? = null

    private val documentos =
        object : DocumentosDeEmision {
            override suspend fun hr(
                contribuyenteId: UUID,
                anio: Int,
                parametros: List<ParametroTributario>?
            ): Documento {
                if (primera.compareAndSet(false, true)) puerta?.await()
                return documentosPrediales.hr(contribuyenteId, anio, parametros)
            }

            override suspend fun pu(
                predioId: UUID,
                contribuyenteId: UUID?,
                anio: Int
            ) = documentosPrediales.pu(predioId, contribuyenteId, anio)
        }

    @BeforeEach
    fun soloLaDelTest() = soloLasDelTest()

    @AfterEach
    fun detener() {
        puerta?.complete(Unit)
        runBlocking { grupos.forEach { it.detener() } }
        grupos.clear()
    }

    @Test
    fun `two instances without a shared disk complete a pdf and a zip emission`() {
        assertTrue(almacen is AlmacenS3, "${almacen::class}")
        grupo("instancia-a")
        grupo("instancia-b")

        val anioPdf = anio()
        val e = escenario(anioPdf)
        val pdf = emitirEntreDos(anioPdf, "PDF")
        esLaConcatenacion(descargar(pdf).cuerpo, documentos(e, anioPdf))
        enElBucket(pdf, anioPdf, FormatoEmision.PDF)

        val anioZip = anio()
        escenario(anioZip)
        val zip = emitirEntreDos(anioZip, "ZIP")
        val archivo = descargar(zip)
        assertEquals("application/zip", archivo.tipo.toString())
        val entradas =
            ZipInputStream(ByteArrayInputStream(archivo.cuerpo)).use { z ->
                generateSequence { z.nextEntry }.associate { it.name to z.readAllBytes() }
            }
        assertEquals(
            3,
            entradas.keys
                .map { it.substringBefore('/') }
                .toSet()
                .size,
            "${entradas.keys}"
        )
        assertEquals(3, entradas.keys.count { it.substringAfter('/') == "HR-$anioZip.pdf" }, "${entradas.keys}")
        assertEquals(5, entradas.keys.count { it.substringAfter('/').startsWith("PU-") }, "${entradas.keys}")
        entradas.forEach { (nombre, documento) -> assertTrue(paginas(documento) > 0, nombre) }
        enElBucket(zip, anioZip, FormatoEmision.ZIP)
    }

    @Test
    fun `a download of more than 100 MB streams`() {
        val anio = anio()
        val tamano = 101L * 1024 * 1024
        val id =
            post(
                "/api/objects/emision_masiva/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "anio" to anio,
                            "formato" to "PDF",
                            "estado" to "TERMINADA",
                            "total" to 0,
                            "procesados" to 0,
                            "archivo" to "emision-$anio-x.pdf",
                            "tamano" to tamano
                        )
                )
            )["id"].asString()
        // straight to the bucket, from a generated stream: no 101 MiB array anywhere
        MinioS3.cliente.putObject(
            { it.bucket(BUCKET).key("$PREFIJO/" + claveResultado(UUID.fromString(id), anio, FormatoEmision.PDF)) },
            RequestBody.fromContentProvider({ Generado(tamano) }, tamano, "application/pdf")
        )
        val esperado = CRC32()
        Generado(tamano).use { generado ->
            val trozo = ByteArray(64 * 1024)
            while (true) {
                val n = generado.read(trozo, 0, trozo.size)
                if (n < 0) break
                esperado.update(trozo, 0, n)
            }
        }

        // a plain client on the server's port, that counts the buffers as they come and lets each one go: the test
        // client would keep a copy of the body
        val crc = CRC32()
        var buffers = 0
        var mayor = 0
        val (longitud, leidos) =
            WebClient
                .create("http://localhost:$puerto")
                .get()
                .uri("/api/srtm/emisiones/$id/archivo")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchangeToMono { respuesta ->
                    assertEquals(200, respuesta.statusCode().value())
                    respuesta
                        .bodyToFlux(DataBuffer::class.java)
                        .reduce(0L) { total, buffer ->
                            try {
                                val n = buffer.readableByteCount()
                                buffers++
                                mayor = maxOf(mayor, n)
                                buffer.readableByteBuffers().use { it.forEach(crc::update) }
                                total + n
                            } finally {
                                DataBufferUtils.release(buffer)
                            }
                        }.map { respuesta.headers().contentLength().asLong to it }
                }.block(Duration.ofMinutes(2))!!

        assertEquals(tamano, longitud)
        assertEquals(tamano, leidos)
        assertEquals(esperado.value, crc.value)
        // it came in pieces, none of them anywhere near the file
        assertTrue(buffers > 1000, "$buffers buffers")
        assertTrue(mayor <= 64 * 1024, "un buffer de $mayor bytes")
    }

    // the emission run by both instances: the first lote taken waits until the other instance has taken one too, so
    // the one that assembles reads at least one parte the other generated. TERMINADA: its id
    private fun emitirEntreDos(
        anio: Int,
        formato: String
    ): String {
        primera.set(false)
        puerta = CompletableDeferred()
        val id = emitir(anio, formato)
        hastaQue("una sola instancia toma lotes") { lotesDe(id).mapNotNull { it.tomadoPor }.toSet().size == 2 }
        puerta!!.complete(Unit)
        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals(setOf("instancia-a", "instancia-b"), lotesDe(id).map { it.tomadoPor }.toSet())
        return id
    }

    // what the bucket keeps of the emission: its result, under the prefix, and no parte
    private fun enElBucket(
        id: String,
        anio: Int,
        formato: FormatoEmision
    ) {
        val resultado = claveResultado(UUID.fromString(id), anio, formato)
        val objetos =
            MinioS3.cliente
                .listObjectsV2Paginator { it.bucket(BUCKET).prefix("$PREFIJO/" + prefijoEmision(UUID.fromString(id))) }
                .contents()
                .map { it.key() }
        assertEquals(listOf("$PREFIJO/$resultado"), objetos)
        assertEquals(listOf(resultado), runBlocking { almacen.listar(prefijoEmision(UUID.fromString(id))) })
    }

    // a started group of this context playing an instance, with its own work dir
    private fun grupo(instancia: String): GrupoTrabajadores =
        trabajadores
            .grupo(instancia, 1, Path.of("build/emisiones-s3-grupos", instancia), documentos)
            .also {
                grupos += it
                it.iniciar()
            }

    // `tamano` bytes of a pattern, made as they are read
    private class Generado(
        private val tamano: Long
    ) : InputStream() {
        private var posicion = 0L

        override fun read(): Int = if (posicion < tamano) (posicion++ % 251).toInt() else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int
        ): Int {
            if (posicion >= tamano) return -1
            val n = minOf(len.toLong(), tamano - posicion).toInt()
            for (i in 0 until n) b[off + i] = ((posicion + i) % 251).toInt().toByte()
            posicion += n
            return n
        }
    }

    companion object {
        private const val BUCKET = "emisiones-api"

        private const val PREFIJO = "srtm/emisiones"

        // a year of its own per test, far from the padrón's and from the other classes'
        private val anios = AtomicInteger(6900 + (0..90).random() * 10)

        private fun anio() = anios.getAndIncrement()

        private val CREDENCIALES = listOf("aws.accessKeyId", "aws.secretAccessKey")

        // the system properties of the credentials as they were before the class
        private var antes: Map<String, String?>? = null

        // the minio, its bucket, and the credentials where the sdk's default chain looks first, until the class ends
        @JvmStatic
        @DynamicPropertySource
        fun s3(registro: DynamicPropertyRegistry) {
            MinioS3.bucket(BUCKET)
            if (antes == null) antes = CREDENCIALES.associateWith { System.getProperty(it) }
            System.setProperty("aws.accessKeyId", MinioS3.usuario)
            System.setProperty("aws.secretAccessKey", MinioS3.clave)
            registro.add("srtm.emision.s3.bucket") { BUCKET }
            registro.add("srtm.emision.s3.endpoint") { MinioS3.endpoint }
            registro.add("srtm.emision.s3.prefijo") { PREFIJO }
        }

        // no other class finds them set: nothing else of the suite should reach a bucket by the default chain
        @JvmStatic
        @AfterAll
        fun restaurarCredenciales() {
            antes?.forEach { (propiedad, valor) -> if (valor == null) System.clearProperty(propiedad) else System.setProperty(propiedad, valor) }
            antes = null
        }
    }
}
