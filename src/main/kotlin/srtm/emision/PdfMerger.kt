package srtm.emision

import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

// pdfs one after another, in order, with pdfbox. the merge buffers in temp files, never all in memory
// (MemoryUsageSetting.setupTempFileOnly): the masiva merges a document per contribuyente and predio of the padrón
@Component
class PdfMerger {
    // pdfs already in memory (a contribuyente's HR and PUs) to a stream; `destino` is left open
    fun merge(
        partes: List<ByteArray>,
        destino: OutputStream
    ) {
        val merger = PDFMergerUtility()
        partes.forEach { merger.addSource(RandomAccessReadBuffer(it)) }
        merger.destinationStream = destino
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly().streamCache)
    }

    // pdfs on disk to a file (created or replaced), read one at a time. final: kotlin-spring opens a @Component's
    // members, and an open one cannot take @JvmName (both overloads erase to merge(List, …))
    @JvmName("mergeArchivos")
    final fun merge(
        partes: List<Path>,
        destino: Path
    ) {
        val merger = PDFMergerUtility()
        partes.forEach { merger.addSource(it.toFile()) }
        Files.newOutputStream(destino).use { out ->
            merger.destinationStream = out
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly().streamCache)
        }
    }
}
