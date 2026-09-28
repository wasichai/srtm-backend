package srtm.emision

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.name

// the retention of the masivas' files (wasichai/srtm-backend#47), apart from core: which files go, and the leftovers
// of a job that died mid-way. EmisionMasivaService reads the jobs and marks the ones whose file went

// a TERMINADA job whose file is still on disk
data class ArchivoDeEmision(
    val id: UUID,
    val organizacion: UUID,
    val anio: Int,
    val formato: FormatoEmision,
    val terminado: Instant?
)

// srtm.emision.conservar: the last n files of each year (per organization); srtm.emision.dias: none older than that.
// 0 turns a rule off
data class Retencion(
    val conservar: Int,
    val dias: Int
)

// the files the retention removes: beyond the last `conservar` of their organization and year, or older than `dias`.
// a job without terminado counts as the oldest
fun aDepurar(
    archivos: List<ArchivoDeEmision>,
    retencion: Retencion,
    ahora: Instant
): List<ArchivoDeEmision> {
    val limite = if (retencion.dias > 0) ahora.minus(Duration.ofDays(retencion.dias.toLong())) else null
    return archivos
        .groupBy { it.organizacion to it.anio }
        .values
        .flatMap { delAnio ->
            val recientes = delAnio.sortedWith(compareByDescending(nullsFirst()) { it.terminado })
            recientes.filterIndexed { i, a ->
                (retencion.conservar > 0 && i >= retencion.conservar) || (limite != null && (a.terminado == null || a.terminado < limite))
            }
        }
}

// a job that died mid-way leaves its `.part` and the `.partes-` directory of a pdf's documents: removed when no job
// runs. how many
@OptIn(ExperimentalPathApi::class)
fun limpiarTemporales(dir: Path): Int {
    if (!dir.isDirectory()) return 0
    val restos = Files.list(dir).use { s -> s.toList().filter { it.name.endsWith(".part") || it.name.startsWith(".partes-") } }
    restos.forEach { it.deleteRecursively() }
    return restos.size
}
