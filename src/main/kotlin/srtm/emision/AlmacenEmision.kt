package srtm.emision

import org.springframework.core.io.Resource
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

// where the emisiones masivas' files live once generated (wasichai/srtm-backend#52): the result and, from the lotes on,
// the parts it is assembled from. the service only talks to this, so the files can be on the server's disk or in an
// object store shared by every instance. a new implementation passes AlmacenEmisionContractTest and is chosen by its
// own srtm.emision.almacen value.
//
// a key is relative and `/`-separated, made of segments of [A-Za-z0-9._-]: never empty, never `.` or `..`, no leading
// `/`. anything else is an IllegalArgumentException (exigirClave). implementations do their blocking IO in Dispatchers.IO
interface AlmacenEmision {
    // consumes `archivo`: when it returns, the local file no longer exists. an existing key is replaced
    suspend fun guardar(
        clave: String,
        archivo: Path
    )

    // for streaming: its contentLength() never reads the content
    suspend fun abrir(clave: String): Resource

    // copies the key to a local file, created or replaced
    suspend fun traer(
        clave: String,
        destino: Path
    )

    // a missing key is a no-op
    suspend fun borrar(clave: String)

    suspend fun existe(clave: String): Boolean

    suspend fun tamano(clave: String): Long

    // the keys that start with the prefix, sorted; none: empty. a prefix may end in `/`
    suspend fun listar(prefijo: String): List<String>
}

// abrir, traer and tamano of a key that is not there
class ClaveInexistenteException(
    clave: String
) : RuntimeException("No existe la clave $clave en el almacén")

private val SEGMENTO = Regex("[A-Za-z0-9._-]+")

// the key as it came, if it is a valid key (or, with `prefijo`, a valid prefix: it may end in `/`)
fun exigirClave(
    clave: String,
    prefijo: Boolean = false
): String {
    val segmentos = (if (prefijo) clave.removeSuffix("/") else clave).split('/')
    require(segmentos.all { it.matches(SEGMENTO) && it != "." && it != ".." }) {
        "Clave inválida del almacén: '$clave'"
    }
    return clave
}

// every key of an emission starts with this
fun prefijoEmision(id: UUID): String = "emision-$id/"

// the result file: the name it is downloaded with is the last segment
fun claveResultado(
    id: UUID,
    anio: Int,
    formato: FormatoEmision
): String = prefijoEmision(id) + nombreArchivo(anio, id.toString(), formato)

// the file of a lote, numbered from 1; the number's digits are the same in any locale
fun claveParte(
    id: UUID,
    numero: Int,
    formato: FormatoEmision
): String = prefijoEmision(id) + "parte-%05d.%s".format(Locale.ROOT, numero, formato.extension)
