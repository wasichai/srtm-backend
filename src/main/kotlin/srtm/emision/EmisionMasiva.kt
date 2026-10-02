package srtm.emision

import org.apache.pdfbox.pdmodel.PDDocument
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import srtm.impuesto.ParametroTributario
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.deleteRecursively

// the masiva's files (wasichai/srtm-backend#41), apart from core and the job: the contribuyentes come already read, the
// documents from DocumentosDeEmision. a worker (TrabajadoresEmision) runs it for each lote

enum class FormatoEmision(
    val extension: String,
    val mediaType: MediaType
) {
    PDF("pdf", MediaType.APPLICATION_PDF),
    ZIP("zip", MediaType.parseMediaType("application/zip"))
}

// a contribuyente that could not be emitted: its code and why
data class ErrorEmision(
    val contribuyente: String,
    val mensaje: String
)

// a contribuyente with vigente declaraciones in the year: its predios, in the order their PUs go. a lote keeps them as
// json (contribuyentesJson)
data class ContribuyenteAEmitir(
    val id: UUID,
    val codigo: String,
    val nombre: String,
    val predios: List<UUID>
)

// what the masiva emits: DocumentosPrediales' HR and PU. a seam so the job's tests do not depend on the HR
// (wasichai/srtm-backend#40)
interface DocumentosDeEmision {
    // `parametros`: the year's parámetros tributarios, read once by the lote; null reads them
    suspend fun hr(
        contribuyenteId: UUID,
        anio: Int,
        parametros: List<ParametroTributario>?
    ): Documento

    suspend fun pu(
        predioId: UUID,
        contribuyenteId: UUID?,
        anio: Int
    ): Documento
}

@Component
class DocumentosPredialesDeEmision(
    private val documentos: DocumentosPrediales
) : DocumentosDeEmision {
    override suspend fun hr(
        contribuyenteId: UUID,
        anio: Int,
        parametros: List<ParametroTributario>?
    ) = documentos.hr(contribuyenteId, anio, parametros)

    override suspend fun pu(
        predioId: UUID,
        contribuyenteId: UUID?,
        anio: Int
    ) = documentos.pu(predioId, contribuyenteId, anio)
}

// the file's name, the last part of its key in the almacén (claveResultado), and the download's
fun nombreArchivo(
    anio: Int,
    id: String,
    formato: FormatoEmision
) = "emision-$anio-$id.${formato.extension}"

// a contribuyente's folder in the zip: `<codigo>-<nombre>`, the name without accents and anything but letters and digits
fun carpeta(
    codigo: String,
    nombre: String
): String {
    val limpio =
        Normalizer
            .normalize(nombre.uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^A-Z0-9]+"), "-")
            .trim('-')
            .take(MAX_NOMBRE)
            .trimEnd('-')
    return if (limpio.isEmpty()) codigo else "$codigo-$limpio"
}

private const val MAX_NOMBRE = 60

// how often the job's progress is saved
const val AVANCE_CADA = 25

// what a generation left out, and how many documents (HR and PU) it wrote
data class ResultadoGeneracion(
    val errores: List<ErrorEmision>,
    val documentos: Int
)

class GeneradorEmision(
    private val documentos: DocumentosDeEmision,
    private val merger: PdfMerger
) {
    // every contribuyente's HR and then its PUs, into `destino`: one pdf (the documents go to temp files next to it
    // and are merged at the end) or a zip written as it goes. a contribuyente whose documents fail is left out and
    // returned among the errors; the rest go on. `avance` is told how many were processed every AVANCE_CADA and at the
    // end. the documents counted are the ones written. `parametros` go to every HR: the caller reads them once
    suspend fun generar(
        anio: Int,
        formato: FormatoEmision,
        contribuyentes: List<ContribuyenteAEmitir>,
        parametros: List<ParametroTributario>?,
        destino: Path,
        avance: suspend (procesados: Int, errores: List<ErrorEmision>) -> Unit
    ): ResultadoGeneracion {
        val errores = mutableListOf<ErrorEmision>()
        var escritos = 0
        val salida = if (formato == FormatoEmision.PDF) SalidaPdf(destino, merger) else SalidaZip(destino)
        salida.use {
            contribuyentes.forEachIndexed { i, c ->
                val docs =
                    try {
                        listOf("HR-$anio.pdf" to documentos.hr(c.id, anio, parametros)) +
                            c.predios.map { p -> documentos.pu(p, c.id, anio).let { it.nombre to it } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        errores += ErrorEmision(c.codigo, e.message ?: e.javaClass.simpleName)
                        null
                    }
                docs?.let {
                    salida.agregar(carpeta(c.codigo, c.nombre), it.map { (nombre, d) -> nombre to d.bytes })
                    escritos += it.size
                }
                val procesados = i + 1
                if (procesados % AVANCE_CADA == 0 || procesados == contribuyentes.size) avance(procesados, errores.toList())
            }
            salida.terminar()
        }
        return ResultadoGeneracion(errores, escritos)
    }

    private interface Salida : AutoCloseable {
        fun agregar(
            carpeta: String,
            pdfs: List<Pair<String, ByteArray>>
        )

        fun terminar()
    }

    private class SalidaZip(
        destino: Path
    ) : Salida {
        private val zip = ZipOutputStream(BufferedOutputStream(Files.newOutputStream(destino)))

        override fun agregar(
            carpeta: String,
            pdfs: List<Pair<String, ByteArray>>
        ) {
            pdfs.forEach { (nombre, bytes) ->
                zip.putNextEntry(ZipEntry("$carpeta/$nombre"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        override fun terminar() = zip.finish()

        override fun close() = zip.close()
    }

    private class SalidaPdf(
        private val destino: Path,
        private val merger: PdfMerger
    ) : Salida {
        private val partes = Files.createTempDirectory(destino.toAbsolutePath().parent, ".partes-")
        private val escritas = mutableListOf<Path>()

        override fun agregar(
            carpeta: String,
            pdfs: List<Pair<String, ByteArray>>
        ) {
            pdfs.forEach { (_, bytes) ->
                escritas.add(Files.write(partes.resolve("%08d.pdf".format(escritas.size)), bytes))
            }
        }

        override fun terminar() {
            if (escritas.isEmpty()) {
                PDDocument().use { it.save(destino.toFile()) }
            } else {
                merger.merge(escritas, destino)
            }
        }

        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        override fun close() = partes.deleteRecursively()
    }
}
