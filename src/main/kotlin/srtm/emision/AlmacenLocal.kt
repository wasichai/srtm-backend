package srtm.emision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.invariantSeparatorsPathString

// the name of a file while it is being saved: it is not a key until it is moved to its own
private const val GUARDANDO = ".guardando-"

// the flat layout of before the almacén: emision-<anio>-<id>.<pdf|zip> straight under srtm.emision.dir
private val LEGADO = Regex("emision-(\\d{1,9})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(pdf|zip)")

// the files on the server's disk, `<dir>/<key>`. a real deployment points srtm.emision.dir at a persistent volume. it
// is the only class of srtm.emision that knows that property
@Component
@ConditionalOnProperty(prefix = "srtm.emision", name = ["almacen"], havingValue = "local", matchIfMissing = true)
class AlmacenLocal(
    @Value("\${srtm.emision.dir}") dir: String
) : AlmacenEmision {
    private val raiz: Path = Path.of(dir).toAbsolutePath().normalize()

    init {
        moverLegados()
    }

    // moves to a temp name next to the key and then renames it: a reader never sees a half-written key, and the file
    // can come from another filesystem (the move copies then)
    override suspend fun guardar(
        clave: String,
        archivo: Path
    ) {
        withContext(Dispatchers.IO) {
            val destino = ruta(clave)
            Files.createDirectories(destino.parent)
            val temporal = destino.resolveSibling("$GUARDANDO${UUID.randomUUID()}")
            try {
                Files.move(archivo, temporal)
                Files.move(temporal, destino, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporal)
            }
        }
    }

    // a file resource: the download is read from the disk as it goes and its length is a stat
    override suspend fun abrir(clave: String): Resource = withContext(Dispatchers.IO) { FileSystemResource(existente(clave)) }

    override suspend fun traer(
        clave: String,
        destino: Path
    ) {
        withContext(Dispatchers.IO) {
            Files.copy(existente(clave), destino, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun borrar(clave: String) {
        val archivo = ruta(clave)
        withContext(Dispatchers.IO) {
            if (Files.isRegularFile(archivo)) Files.deleteIfExists(archivo)
        }
    }

    override suspend fun existe(clave: String): Boolean {
        val archivo = ruta(clave)
        return withContext(Dispatchers.IO) { Files.isRegularFile(archivo) }
    }

    override suspend fun tamano(clave: String): Long = withContext(Dispatchers.IO) { Files.size(existente(clave)) }

    // walks from the directory the prefix is in and keeps the keys that start with it
    override suspend fun listar(prefijo: String): List<String> {
        exigirClave(prefijo, prefijo = true)
        return withContext(Dispatchers.IO) {
            val base = raiz.resolve(prefijo.substringBeforeLast('/', ""))
            if (!Files.isDirectory(base)) {
                emptyList()
            } else {
                Files.walk(base).use { archivos ->
                    archivos
                        .filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(GUARDANDO) }
                        .map { raiz.relativize(it).invariantSeparatorsPathString }
                        .filter { it.startsWith(prefijo) }
                        .toList()
                        .sorted()
                }
            }
        }
    }

    // the key is checked before it touches the disk: nothing that leaves `raiz` gets resolved
    private fun ruta(clave: String): Path = raiz.resolve(exigirClave(clave))

    private fun existente(clave: String): Path {
        val archivo = ruta(clave)
        if (!Files.isRegularFile(archivo)) throw ClaveInexistenteException(clave)
        return archivo
    }

    // once, when the store is made: the files of the old flat layout go under their emission's prefix. a file that
    // cannot be moved is logged and left, never a reason not to start
    private fun moverLegados() {
        if (!Files.isDirectory(raiz)) return
        val legados =
            Files.list(raiz).use { archivos -> archivos.toList() }.mapNotNull { archivo ->
                LEGADO.matchEntire(archivo.fileName.toString())?.takeIf { Files.isRegularFile(archivo) }?.let { archivo to it.groupValues[2] }
            }
        var movidos = 0
        for ((legado, id) in legados) {
            try {
                val destino = raiz.resolve("emision-$id").resolve(legado.fileName)
                Files.createDirectories(destino.parent)
                Files.move(legado, destino, StandardCopyOption.ATOMIC_MOVE)
                movidos++
            } catch (e: Exception) {
                log.warn("no se pudo mover el archivo de emisión {} a su prefijo", legado, e)
            }
        }
        if (movidos > 0) log.info("{} archivos de emisiones del formato anterior movidos a su prefijo", movidos)
    }

    private companion object {
        val log = LoggerFactory.getLogger(AlmacenLocal::class.java)
    }
}
