package srtm.emision

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// the partes of an emission joined into its file, on a real local almacén
class EnsambladorEmisionTest {
    @TempDir
    lateinit var dir: Path

    private val almacen by lazy { AlmacenLocal(dir.resolve("almacen").toString()) }
    private val ensamblador by lazy { EnsambladorEmision(almacen, PdfMerger()) }
    private val temporales by lazy { dir.resolve("temporales") }

    // a pdf of `n` pages that are `ancho` points wide: the width tells which parte a page came from
    private fun pdf(
        n: Int,
        ancho: Float
    ): ByteArray =
        PDDocument().use { doc ->
            repeat(n) { doc.addPage(PDPage(PDRectangle(ancho, 200f))) }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }

    private fun zip(vararg entradas: Pair<String, String>): ByteArray =
        ByteArrayOutputStream()
            .also { salida ->
                ZipOutputStream(salida).use { zip ->
                    entradas.forEach { (nombre, contenido) ->
                        zip.putNextEntry(ZipEntry(nombre))
                        zip.write(contenido.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()

    private fun guardar(
        clave: String,
        bytes: ByteArray
    ) = runBlocking { almacen.guardar(clave, Files.write(Files.createTempFile(dir, "parte", ".tmp"), bytes)) }

    private fun resultado(clave: String): ByteArray = runBlocking { almacen.abrir(clave).inputStream.use { it.readAllBytes() } }

    private fun anchos(pdf: ByteArray): List<Float> = Loader.loadPDF(pdf).use { doc -> doc.pages.map { it.mediaBox.width } }

    private fun entradas(zip: ByteArray): List<Pair<String, String>> =
        ZipInputStream(zip.inputStream()).use { entrada ->
            generateSequence { entrada.nextEntry }.map { it.name to String(entrada.readAllBytes()) }.toList()
        }

    private fun sinRestos() = !Files.exists(temporales) || Files.list(temporales).use { it.toList() }.isEmpty()

    @Test
    fun `the pdf has the pages of every parte in order`() {
        guardar("emision-a/parte-00001.pdf", pdf(1, 100f))
        guardar("emision-a/parte-00002.pdf", pdf(2, 200f))
        guardar("emision-a/parte-00003.pdf", pdf(3, 300f))

        val tamano =
            runBlocking {
                ensamblador.ensamblar(
                    FormatoEmision.PDF,
                    listOf("emision-a/parte-00001.pdf", "emision-a/parte-00002.pdf", "emision-a/parte-00003.pdf"),
                    "emision-a/final.pdf",
                    temporales
                )
            }

        val final = resultado("emision-a/final.pdf")
        assertEquals(listOf(100f, 200f, 200f, 300f, 300f, 300f), anchos(final))
        assertEquals(final.size.toLong(), tamano)
        assertTrue(sinRestos())
    }

    @Test
    fun `the partes go in the order given, not the order of their keys`() {
        guardar("emision-a/parte-00001.pdf", pdf(1, 100f))
        guardar("emision-a/parte-00002.pdf", pdf(2, 200f))

        runBlocking {
            ensamblador.ensamblar(
                FormatoEmision.PDF,
                listOf("emision-a/parte-00002.pdf", "emision-a/parte-00001.pdf"),
                "emision-a/final.pdf",
                temporales
            )
        }

        assertEquals(listOf(200f, 200f, 100f), anchos(resultado("emision-a/final.pdf")))
    }

    @Test
    fun `the zip has every entry of every parte in order with its folders`() {
        val primera = zip("001-ANA/HR-2026.pdf" to "hr de ana", "001-ANA/PU-A1-2026.pdf" to "pu de ana")
        val segunda = zip("002-BETO/HR-2026.pdf" to "hr de beto")
        guardar("emision-a/parte-00001.zip", primera)
        guardar("emision-a/parte-00002.zip", segunda)

        val tamano =
            runBlocking {
                ensamblador.ensamblar(
                    FormatoEmision.ZIP,
                    listOf("emision-a/parte-00001.zip", "emision-a/parte-00002.zip"),
                    "emision-a/final.zip",
                    temporales
                )
            }

        val final = resultado("emision-a/final.zip")
        assertEquals(
            listOf(
                "001-ANA/HR-2026.pdf" to "hr de ana",
                "001-ANA/PU-A1-2026.pdf" to "pu de ana",
                "002-BETO/HR-2026.pdf" to "hr de beto"
            ),
            entradas(final)
        )
        assertEquals(final.size.toLong(), tamano)
        assertTrue(sinRestos())
    }

    @Test
    fun `no partes is a pdf of no pages and an empty zip`() {
        runBlocking {
            ensamblador.ensamblar(FormatoEmision.PDF, emptyList(), "emision-a/final.pdf", temporales)
            ensamblador.ensamblar(FormatoEmision.ZIP, emptyList(), "emision-a/final.zip", temporales)
        }

        assertEquals(0, paginas(resultado("emision-a/final.pdf")))
        assertEquals(emptyList<Pair<String, String>>(), entradas(resultado("emision-a/final.zip")))
        assertTrue(sinRestos())
    }

    @Test
    fun `a parte that cannot be fetched fails the assembly, stores nothing and leaves no temp file`() {
        guardar("emision-a/parte-00001.pdf", pdf(1, 100f))
        guardar("emision-a/parte-00001.zip", zip("001-ANA/HR-2026.pdf" to "hr"))

        for (formato in FormatoEmision.entries) {
            val ext = formato.extension
            val clave = "emision-a/final.$ext"

            assertThrows(ClaveInexistenteException::class.java) {
                runBlocking {
                    ensamblador.ensamblar(formato, listOf("emision-a/parte-00001.$ext", "emision-a/parte-00002.$ext"), clave, temporales)
                }
            }

            assertFalse(runBlocking { almacen.existe(clave) })
            assertTrue(sinRestos())
        }
    }

    @Test
    fun `a corrupt parte fails the assembly and leaves no temp file`() {
        guardar("emision-a/parte-00001.pdf", pdf(1, 100f))
        guardar("emision-a/parte-00002.pdf", "no es un pdf".toByteArray())

        assertThrows(Exception::class.java) {
            runBlocking {
                ensamblador.ensamblar(
                    FormatoEmision.PDF,
                    listOf("emision-a/parte-00001.pdf", "emision-a/parte-00002.pdf"),
                    "emision-a/final.pdf",
                    temporales
                )
            }
        }

        assertFalse(runBlocking { almacen.existe("emision-a/final.pdf") })
        assertTrue(sinRestos())
    }

    @Test
    fun `a corrupt zip parte fails the assembly instead of being skipped`() {
        guardar("emision-a/parte-00001.zip", zip("001-ANA/HR-2026.pdf" to "hr"))
        guardar("emision-a/parte-00002.zip", "no es un zip".toByteArray())

        assertThrows(Exception::class.java) {
            runBlocking {
                ensamblador.ensamblar(
                    FormatoEmision.ZIP,
                    listOf("emision-a/parte-00001.zip", "emision-a/parte-00002.zip"),
                    "emision-a/final.zip",
                    temporales
                )
            }
        }

        assertFalse(runBlocking { almacen.existe("emision-a/final.zip") })
        assertTrue(sinRestos())
    }

    @Test
    fun `the partes are kept`() {
        guardar("emision-a/parte-00001.pdf", pdf(1, 100f))

        runBlocking { ensamblador.ensamblar(FormatoEmision.PDF, listOf("emision-a/parte-00001.pdf"), "emision-a/final.pdf", temporales) }

        assertTrue(runBlocking { almacen.existe("emision-a/parte-00001.pdf") })
    }

    // an instance that died after storing the file and before deleting the partes: the retaker finds the file and the
    // partes may be gone
    @Test
    fun `a final file already stored is not assembled again`() {
        guardar("emision-a/final.pdf", pdf(2, 150f))

        val tamano =
            runBlocking {
                ensamblador.ensamblar(FormatoEmision.PDF, listOf("emision-a/parte-00001.pdf"), "emision-a/final.pdf", temporales)
            }

        assertEquals(listOf(150f, 150f), anchos(resultado("emision-a/final.pdf")))
        assertEquals(resultado("emision-a/final.pdf").size.toLong(), tamano)
    }
}
