package srtm.emision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteRecursively

// joins the partes the lotes left in the almacén (wasichai/srtm-backend#54) into the emission's file. a plain class: the
// worker that assembles builds it with the store and the merger it has
class EnsambladorEmision(
    private val almacen: AlmacenEmision,
    private val merger: PdfMerger
) {
    // the partes (keys, in the given order) into one file stored under `clave`; work files go under `temporales` and
    // are always removed. the partes are not deleted (the caller does, after). returns its size. if `clave` is already
    // there it is the file of an instance that died after storing it (a store is atomic), possibly after deleting some
    // partes: it is kept as is. any failure leaves nothing under `clave` and the exception goes up
    suspend fun ensamblar(
        formato: FormatoEmision,
        partes: List<String>,
        clave: String,
        temporales: Path
    ): Long {
        if (almacen.existe(clave)) return almacen.tamano(clave)
        val trabajo =
            withContext(Dispatchers.IO) {
                Files.createDirectories(temporales)
                Files.createTempDirectory(temporales, "ensamblado-")
            }
        try {
            val final = trabajo.resolve("final.${formato.extension}")
            when (formato) {
                FormatoEmision.PDF -> unirPdfs(partes, trabajo, final)
                FormatoEmision.ZIP -> unirZips(partes, trabajo, final)
            }
            // the store consumes the file: its size is read before
            val tamano = withContext(Dispatchers.IO) { Files.size(final) }
            almacen.guardar(clave, final)
            return tamano
        } finally {
            // also when cancelled: a leftover would stay until the next restart
            withContext(NonCancellable + Dispatchers.IO) { borrar(trabajo) }
        }
    }

    // every parte is fetched first (the merge reads them all from disk), then merged
    private suspend fun unirPdfs(
        partes: List<String>,
        trabajo: Path,
        final: Path
    ) {
        val locales =
            partes.mapIndexed { i, parte ->
                trabajo.resolve("%05d.pdf".format(i)).also { almacen.traer(parte, it) }
            }
        withContext(Dispatchers.IO) {
            if (locales.isEmpty()) {
                PDDocument().use { it.save(final.toFile()) }
            } else {
                merger.merge(locales, final)
            }
        }
    }

    // one parte at a time, so a big emission never has more than one partial zip on disk besides the result
    private suspend fun unirZips(
        partes: List<String>,
        trabajo: Path,
        final: Path
    ) {
        val parte = trabajo.resolve("parte.zip")
        val salida = withContext(Dispatchers.IO) { ZipOutputStream(BufferedOutputStream(Files.newOutputStream(final))) }
        salida.use {
            for (clave in partes) {
                almacen.traer(clave, parte)
                withContext(Dispatchers.IO) {
                    copiarEntradas(parte, salida)
                    Files.delete(parte)
                }
            }
        }
    }

    // each entry with its name (the contribuyente's folder included) and bytes. a ZipFile, not a ZipInputStream: a
    // truncated or corrupt parte fails here instead of reading as a zip with fewer entries
    private fun copiarEntradas(
        parte: Path,
        salida: ZipOutputStream
    ) {
        ZipFile(parte.toFile()).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                salida.putNextEntry(ZipEntry(entry.name))
                zip.getInputStream(entry).use { it.copyTo(salida) }
                salida.closeEntry()
            }
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun borrar(trabajo: Path) = trabajo.deleteRecursively()
}
