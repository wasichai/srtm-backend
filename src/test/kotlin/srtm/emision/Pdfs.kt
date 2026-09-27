package srtm.emision

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

// what the emisión tests read back from a pdf: its text (as PDFTextStripper extracts it), its pages, a blank one

fun texto(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

fun paginas(pdf: ByteArray): Int = Loader.loadPDF(pdf).use { it.numberOfPages }

fun tamanoPagina(pdf: ByteArray): PDRectangle = Loader.loadPDF(pdf).use { it.getPage(0).mediaBox }

// a pdf of `n` blank A4 pages
fun enBlanco(n: Int): ByteArray =
    PDDocument().use { doc ->
        repeat(n) { doc.addPage(PDPage(PDRectangle.A4)) }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
