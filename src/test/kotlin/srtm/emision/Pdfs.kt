package srtm.emision

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

// what the emisión tests read back from a pdf: its text (as PDFTextStripper extracts it), its pages, a blank one

// the header the PU and HR tests print: the provisional data of model/data/municipalidad.json, with an address
val CABECERA =
    Cabecera(
        nombre = "MUNICIPALIDAD DISTRITAL DE PERENÉ",
        oficina = "GERENCIA DE ADMINISTRACIÓN TRIBUTARIA",
        ruc = "20195238961",
        gerencia = "SUB GERENCIA DE RENTAS",
        direccion = "JR. LIMA 123 - PERENÉ"
    )

fun texto(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

fun paginas(pdf: ByteArray): Int = Loader.loadPDF(pdf).use { it.numberOfPages }

fun tamanoPagina(pdf: ByteArray): PDRectangle = Loader.loadPDF(pdf).use { it.getPage(0).mediaBox }

// the names of the fonts the first page embeds (a subset is named ABCDEF+Family-Style)
fun fuentes(pdf: ByteArray): Set<String> =
    Loader.loadPDF(pdf).use { doc ->
        val recursos = doc.getPage(0).resources
        recursos.fontNames.mapNotNull { recursos.getFont(it)?.name }.toSet()
    }

// how many images the first page draws
fun imagenes(pdf: ByteArray): Int =
    Loader.loadPDF(pdf).use { doc ->
        val recursos = doc.getPage(0).resources
        recursos.xObjectNames.count { recursos.isImageXObject(it) }
    }

// a pdf of `n` blank A4 pages
fun enBlanco(n: Int): ByteArray =
    PDDocument().use { doc ->
        repeat(n) { doc.addPage(PDPage(PDRectangle.A4)) }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
