package srtm.emision

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.InputStreamResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

// the almacén of the files on the server's disk: the contract, and what only it has (the legacy layout)
class AlmacenLocalTest : AlmacenEmisionContractTest() {
    @TempDir
    lateinit var raiz: Path

    // the root does not exist yet: the store makes it when it first saves
    override fun nuevo(): AlmacenEmision = AlmacenLocal(raiz.resolve("almacen").toString())

    @Test
    fun `a download streams from the disk and measures with a stat`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            almacen.guardar("emision-a/x.pdf", Files.writeString(local.resolve("x.bin"), "pdf"))

            val recurso = almacen.abrir("emision-a/x.pdf")

            assertTrue(recurso.isFile)
            assertFalse(recurso is InputStreamResource)
            assertEquals(3L, recurso.contentLength())
        }

    @Test
    fun `an unfinished save is not a key`() =
        runBlocking<Unit> {
            val almacen = nuevo()
            almacen.guardar("emision-a/x.pdf", Files.writeString(local.resolve("x.bin"), "pdf"))
            // what a save that died between its two moves leaves
            Files.writeString(raiz.resolve("almacen/emision-a/.guardando-1234"), "a medias")

            assertEquals(listOf("emision-a/x.pdf"), almacen.listar("emision-a/"))
        }

    @Test
    fun `a legacy file is moved under its emission's prefix`() {
        val id = UUID.randomUUID()
        val plano = Files.writeString(raiz.resolve("emision-2026-$id.pdf"), "pdf")

        val almacen = AlmacenLocal(raiz.toString())

        runBlocking {
            assertTrue(almacen.existe(claveResultado(id, 2026, FormatoEmision.PDF)))
            assertEquals("pdf", almacen.abrir(claveResultado(id, 2026, FormatoEmision.PDF)).inputStream.use { String(it.readAllBytes()) })
        }
        assertFalse(Files.exists(plano))
    }

    @Test
    fun `only the flat files of the old layout are moved, and moving again changes nothing`() {
        val pdf = UUID.randomUUID()
        val zip = UUID.randomUUID()
        Files.writeString(raiz.resolve("emision-2025-$pdf.pdf"), "pdf")
        Files.writeString(raiz.resolve("emision-2026-$zip.zip"), "zip")
        // not legacy files: a leftover of a job, another extension, a name that is not an id, a file already in its prefix
        Files.writeString(raiz.resolve("emision-2026-$pdf.pdf.part"), "a medias")
        Files.writeString(raiz.resolve("emision-2026-$pdf.csv"), "csv")
        Files.writeString(raiz.resolve("emision-2026-no-es-un-id.pdf"), "pdf")
        Files.writeString(raiz.resolve("notas.txt"), "notas")
        val otro = UUID.randomUUID()
        Files.createDirectories(raiz.resolve("emision-$otro"))
        Files.writeString(raiz.resolve("emision-$otro/emision-2026-$otro.pdf"), "ya movido")

        AlmacenLocal(raiz.toString())
        val almacen = AlmacenLocal(raiz.toString())

        runBlocking {
            assertEquals(listOf(claveResultado(pdf, 2025, FormatoEmision.PDF)), almacen.listar(prefijoEmision(pdf)))
            assertEquals(listOf(claveResultado(zip, 2026, FormatoEmision.ZIP)), almacen.listar(prefijoEmision(zip)))
            assertEquals(listOf(claveResultado(otro, 2026, FormatoEmision.PDF)), almacen.listar(prefijoEmision(otro)))
        }
        val enLaRaiz = Files.list(raiz).use { s -> s.toList().map { it.fileName.toString() }.toSet() }
        assertEquals(
            setOf(
                "emision-2026-$pdf.pdf.part",
                "emision-2026-$pdf.csv",
                "emision-2026-no-es-un-id.pdf",
                "notas.txt",
                "emision-$pdf",
                "emision-$zip",
                "emision-$otro"
            ),
            enLaRaiz
        )
    }

    @Test
    fun `a legacy file that cannot be moved does not stop the others or the startup`() {
        val atascado = UUID.randomUUID()
        val libre = UUID.randomUUID()
        // a regular file where the prefix of the first one should be: its directory cannot be made
        Files.writeString(raiz.resolve("emision-$atascado"), "estorba")
        Files.writeString(raiz.resolve("emision-2026-$atascado.pdf"), "pdf")
        Files.writeString(raiz.resolve("emision-2026-$libre.pdf"), "pdf")

        val almacen = AlmacenLocal(raiz.toString())

        assertTrue(Files.exists(raiz.resolve("emision-2026-$atascado.pdf")))
        assertTrue(runBlocking { almacen.existe(claveResultado(libre, 2026, FormatoEmision.PDF)) })
    }
}
