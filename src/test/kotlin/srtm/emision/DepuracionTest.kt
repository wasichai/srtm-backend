package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

// the retention of the masivas' files (wasichai/srtm-backend#47): which TERMINADA files go, and the leftovers of a
// job that died mid-way
class DepuracionTest {
    @TempDir
    lateinit var dir: Path

    private val ahora = Instant.parse("2026-09-28T12:00:00Z")
    private val municipio = UUID.randomUUID()

    private fun archivo(
        anio: Int,
        haceDias: Long,
        organizacion: UUID = municipio
    ) = ArchivoDeEmision(UUID.randomUUID(), organizacion, anio, FormatoEmision.PDF, ahora.minus(Duration.ofDays(haceDias)))

    @Test
    fun `the last n of each year are kept, the older ones go`() {
        val de2026 = (1L..4L).map { archivo(2026, it) }
        val de2025 = (10L..11L).map { archivo(2025, it) }

        val van = aDepurar(de2026.shuffled() + de2025, Retencion(conservar = 2, dias = 0), ahora)

        assertEquals(setOf(de2026[2], de2026[3]), van.toSet())
    }

    @Test
    fun `each organization keeps its own n`() {
        val otra = UUID.randomUUID()
        val nuestras = (1L..2L).map { archivo(2026, it) }
        val suyas = (1L..2L).map { archivo(2026, it, otra) }

        assertEquals(listOf(nuestras[1]), aDepurar(nuestras + suyas, Retencion(conservar = 1, dias = 0), ahora).filter { it in nuestras })
        assertEquals(listOf(suyas[1]), aDepurar(nuestras + suyas, Retencion(conservar = 1, dias = 0), ahora).filter { it in suyas })
    }

    @Test
    fun `files older than the days go even among the last n`() {
        val reciente = archivo(2026, 3)
        val vieja = archivo(2026, 40)

        assertEquals(listOf(vieja), aDepurar(listOf(reciente, vieja), Retencion(conservar = 5, dias = 30), ahora))
    }

    @Test
    fun `zero turns a rule off`() {
        val todas = (1L..8L).map { archivo(2026, it * 100) }

        assertEquals(emptyList<ArchivoDeEmision>(), aDepurar(todas, Retencion(conservar = 0, dias = 0), ahora))
        assertEquals(todas.drop(5).toSet(), aDepurar(todas, Retencion(conservar = 5, dias = 0), ahora).toSet())
        assertEquals(todas.toSet(), aDepurar(todas, Retencion(conservar = 0, dias = 30), ahora).toSet())
    }

    @Test
    fun `a job without terminado counts as the oldest`() {
        val sinFecha = ArchivoDeEmision(UUID.randomUUID(), municipio, 2026, FormatoEmision.ZIP, null)
        val conFecha = archivo(2026, 50)

        assertEquals(listOf(sinFecha), aDepurar(listOf(sinFecha, conFecha), Retencion(conservar = 1, dias = 0), ahora))
    }

    @Test
    fun `the leftovers of an interrupted job are removed, the finished files stay`() {
        val terminado = Files.writeString(dir.resolve("emision-2026-a.pdf"), "pdf")
        val parcial = Files.writeString(dir.resolve("emision-2026-b.zip.part"), "zip a medias")
        val partes = Files.createDirectory(dir.resolve(".partes-123"))
        Files.writeString(partes.resolve("00000000.pdf"), "parte")
        val lote = Files.writeString(dir.resolve("lote-1-1.part"), "lote a medias")
        val ensamblado = Files.createDirectory(dir.resolve("ensamblado-456"))
        Files.writeString(ensamblado.resolve("00000.pdf"), "parte traída")

        assertEquals(4, limpiarTemporales(dir))

        assertTrue(Files.exists(terminado))
        assertFalse(Files.exists(parcial))
        assertFalse(Files.exists(partes))
        assertFalse(Files.exists(lote))
        assertFalse(Files.exists(ensamblado))
    }

    @Test
    fun `without the directory there is nothing to clean`() {
        assertEquals(0, limpiarTemporales(dir.resolve("no-existe")))
    }
}
