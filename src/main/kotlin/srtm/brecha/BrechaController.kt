package srtm.brecha

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import srtm.ParametroInvalido
import srtm.Permisos
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import java.util.UUID

// the brecha de recaudación: corridas (202, followed by GET until TERMINADA or FALLIDA) and the queries over the latest
// TERMINADA corrida of a year (or ?corrida=). every query asks read on brecha_corrida first (Permisos), and core checks
// the caller's read of each row it serves
@RestController
@RequestMapping("/api/srtm/brecha")
class BrechaController(
    private val brechas: BrechaService,
    private val permisos: Permisos
) {
    @PostMapping("/corridas")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun lanzar(
        @RequestBody pedido: PedidoCorrida
    ) = brechas.lanzar(pedido)

    @GetMapping("/corridas")
    suspend fun corridas(
        @RequestParam(required = false) anio: Int?
    ): List<Corrida> {
        leer()
        return brechas.lista(anio)
    }

    @GetMapping("/corridas/{id}")
    suspend fun corrida(
        @PathVariable id: UUID
    ): Corrida {
        leer()
        return brechas.get(id)
    }

    @GetMapping("/{anio}/panel")
    suspend fun panel(
        @PathVariable anio: Int,
        @RequestParam(required = false) corrida: UUID?
    ): PanelBrecha {
        leer()
        val r = brechas.corridaDelAnio(anio, corrida)
        return Brecha.panel(r, brechas.filas(r.id!!))
    }

    @GetMapping("/{anio}/predios")
    suspend fun predios(
        @PathVariable anio: Int,
        @RequestParam(required = false) corrida: UUID?,
        @RequestParam(required = false) categoria: String?,
        @RequestParam(required = false) sector: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "25") size: Int
    ): PageResponse<PredioBrecha> {
        leer()
        val deCategoria =
            categoria?.trim()?.ifEmpty { null }?.let { c ->
                Categoria.entries.firstOrNull { it.name == c.uppercase() }
                    ?: throw ParametroInvalido("categoria", "debe ser una de ${Categoria.entries.joinToString()}")
            }
        if (page < 0) throw ParametroInvalido("page", "debe ser 0 o más")
        if (size !in 1..PageRequest.MAX_SIZE) throw ParametroInvalido("size", "debe ser de 1 a ${PageRequest.MAX_SIZE}")
        val r = brechas.corridaDelAnio(anio, corrida)
        return brechas.pagina(r.id!!, deCategoria, sector?.trim()?.ifEmpty { null }, page, size)
    }

    @GetMapping("/{anio}/lotes")
    suspend fun lotes(
        @PathVariable anio: Int,
        @RequestParam(required = false) corrida: UUID?
    ): Map<String, Any?> {
        leer()
        val r = brechas.corridaDelAnio(anio, corrida)
        return Brecha.lotes(brechas.filas(r.id!!))
    }

    private suspend fun leer() = permisos.exigirLeer(BRECHA_CORRIDA, "consultar la brecha de recaudación")
}
