package srtm.reportes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import srtm.ParametroInvalido
import srtm.brecha.Confianza
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import java.time.Instant
import java.util.UUID

// the reportes de brechas (#248): the dashboard (/resumen), the catalog (/) and each report's page, xlsx and csv, all
// of a corrida: the latest TERMINADA of `anio` unless `corrida` names one. the reports read brecha_corrida and
// brecha_predio as the caller (ReportesService asks both first); the export takes the same filters and order as the
// page and every row of them
@RestController
@RequestMapping("/api/srtm/reportes")
class ReportesController(
    private val reportes: ReportesService
) {
    @GetMapping("/resumen")
    suspend fun resumen(
        @RequestParam anio: Int,
        @RequestParam(required = false) corrida: UUID?
    ): ResumenReportes = reportes.resumen(anio, corrida)

    @GetMapping
    suspend fun catalogo(
        @RequestParam anio: Int,
        @RequestParam(required = false) corrida: UUID?
    ): List<EntradaCatalogo> = reportes.catalogo(anio, corrida)

    // a page of a report: predios, or contribuyentes in `contribuyentes`
    @GetMapping("/{clave}")
    suspend fun reporte(
        @PathVariable clave: String,
        @RequestParam anio: Int,
        @RequestParam(required = false) corrida: UUID?,
        @RequestParam(required = false) sector: String?,
        @RequestParam(required = false) manzana: String?,
        @RequestParam(required = false) uso: String?,
        @RequestParam(required = false) confianza: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) orden: String?,
        @RequestParam(required = false) dir: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "25") size: Int
    ): PaginaReporte<*> {
        val consulta = consulta(clave, sector, manzana, uso, confianza, q, orden, dir)
        if (page < 0) throw ParametroInvalido("page", "debe ser 0 o más")
        if (size !in 1..PageRequest.MAX_SIZE) throw ParametroInvalido("size", "debe ser de 1 a ${PageRequest.MAX_SIZE}")
        return pagina(filas(consulta, anio, corrida).filas, page, size)
    }

    @GetMapping("/{clave}.xlsx")
    suspend fun xlsx(
        @PathVariable clave: String,
        @RequestParam anio: Int,
        @RequestParam(required = false) corrida: UUID?,
        @RequestParam(required = false) sector: String?,
        @RequestParam(required = false) manzana: String?,
        @RequestParam(required = false) uso: String?,
        @RequestParam(required = false) confianza: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) orden: String?,
        @RequestParam(required = false) dir: String?
    ): ResponseEntity<ByteArray> {
        val c = consulta(clave, sector, manzana, uso, confianza, q, orden, dir)
        val listado = filas(c, anio, corrida)
        val exportable = Exportable(c.reporte, listado.corrida, c.filtros, c.orden, c.dir, Instant.now())
        val bytes =
            withContext(Dispatchers.Default) {
                @Suppress("UNCHECKED_CAST")
                if (c.reporte == Reporte.CONTRIBUYENTES) {
                    Exportacion.xlsx(exportable, Exportacion.CONTRIBUYENTES, listado.filas as List<FilaReporteContribuyente>)
                } else {
                    Exportacion.xlsx(exportable, Exportacion.PREDIOS, listado.filas as List<FilaReportePredio>)
                }
            }
        return descarga(bytes, XLSX, Exportacion.nombre(c.reporte, anio, "xlsx"))
    }

    @GetMapping("/{clave}.csv")
    suspend fun csv(
        @PathVariable clave: String,
        @RequestParam anio: Int,
        @RequestParam(required = false) corrida: UUID?,
        @RequestParam(required = false) sector: String?,
        @RequestParam(required = false) manzana: String?,
        @RequestParam(required = false) uso: String?,
        @RequestParam(required = false) confianza: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) orden: String?,
        @RequestParam(required = false) dir: String?
    ): ResponseEntity<ByteArray> {
        val c = consulta(clave, sector, manzana, uso, confianza, q, orden, dir)
        val listado = filas(c, anio, corrida)

        @Suppress("UNCHECKED_CAST")
        val bytes =
            if (c.reporte == Reporte.CONTRIBUYENTES) {
                Exportacion.csv(Exportacion.CONTRIBUYENTES, listado.filas as List<FilaReporteContribuyente>)
            } else {
                Exportacion.csv(Exportacion.PREDIOS, listado.filas as List<FilaReportePredio>)
            }
        return descarga(bytes, CSV, Exportacion.nombre(c.reporte, anio, "csv"))
    }

    // what a request asks: its report (404 when there is none), its filters and its order (the report's own when
    // none is given). 422 for a confianza, orden or dir it cannot read, and for an area order of contribuyentes
    private class Consulta(
        val reporte: Reporte,
        val filtros: Filtros,
        val orden: Orden,
        val dir: Direccion
    )

    private fun consulta(
        clave: String,
        sector: String?,
        manzana: String?,
        uso: String?,
        confianza: String?,
        q: String?,
        orden: String?,
        dir: String?
    ): Consulta {
        val reporte = Reporte.de(clave) ?: throw NotFoundException("No hay un reporte '$clave': ${Reporte.entries.joinToString { it.clave }}")
        val deConfianza =
            lleno(confianza)?.let { c ->
                Confianza.entries.firstOrNull { it.name == c.uppercase() }?.name
                    ?: throw ParametroInvalido("confianza", "debe ser una de ${Confianza.entries.joinToString()}")
            }
        val deOrden =
            lleno(orden)?.let {
                Orden.de(it) ?: throw ParametroInvalido("orden", "debe ser uno de ${Orden.entries.joinToString { o -> o.clave }}")
            }
        if (reporte == Reporte.CONTRIBUYENTES && deOrden in setOf(Orden.AREA_TERRENO, Orden.AREA_CONSTRUIDA)) {
            throw ParametroInvalido("orden", "un contribuyente no tiene áreas: debe ser deltaImpuesto, deltaAutoavaluo o codigo")
        }
        val deDir = lleno(dir)?.let { Direccion.de(it) ?: throw ParametroInvalido("dir", "debe ser asc o desc") }
        val (porOmision, dirPorOmision) = reporte.ordenPorOmision
        val elegido = deOrden ?: porOmision
        val direccion =
            deDir ?: if (deOrden == null) {
                dirPorOmision
            } else if (elegido == Orden.CODIGO) {
                Direccion.ASC
            } else {
                Direccion.DESC
            }
        return Consulta(reporte, Filtros(lleno(sector), lleno(manzana), lleno(uso), deConfianza, lleno(q)), elegido, direccion)
    }

    private suspend fun filas(
        c: Consulta,
        anio: Int,
        corrida: UUID?
    ): Listado<*> =
        if (c.reporte == Reporte.CONTRIBUYENTES) {
            reportes.contribuyentes(anio, corrida, c.filtros, c.orden, c.dir)
        } else {
            reportes.predios(c.reporte, anio, corrida, c.filtros, c.orden, c.dir)
        }

    private fun lleno(texto: String?) = texto?.trim()?.ifEmpty { null }

    // past the last row an empty page; page × size in Long (srtm.pagina's rule)
    private fun <T> pagina(
        filas: List<T>,
        page: Int,
        size: Int
    ): PaginaReporte<T> {
        val desde = page.toLong() * size
        val items = if (desde >= filas.size) emptyList() else filas.drop(desde.toInt()).take(size)
        return PaginaReporte(page, size, filas.size, items)
    }

    // the file to save, under its name (EmisionMasivaController's way)
    private fun descarga(
        bytes: ByteArray,
        tipo: MediaType,
        nombre: String
    ): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(tipo)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(nombre)
                    .build()
                    .toString()
            ).body(bytes)

    private companion object {
        val XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        val CSV = MediaType.parseMediaType("text/csv;charset=UTF-8")
    }
}
