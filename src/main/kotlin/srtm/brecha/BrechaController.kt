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
import srtm.pagina
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import java.time.LocalDate
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
        @RequestParam(required = false) confianza: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "25") size: Int
    ): PageResponse<PredioBrecha> {
        leer()
        val deCategoria =
            categoria?.trim()?.ifEmpty { null }?.let { c ->
                Categoria.entries.firstOrNull { it.name == c.uppercase() }
                    ?: throw ParametroInvalido("categoria", "debe ser una de ${Categoria.entries.joinToString()}")
            }
        val deConfianza =
            confianza?.trim()?.ifEmpty { null }?.let { c ->
                Confianza.entries.firstOrNull { it.name == c.uppercase() }
                    ?: throw ParametroInvalido("confianza", "debe ser una de ${Confianza.entries.joinToString()}")
            }
        if (page < 0) throw ParametroInvalido("page", "debe ser 0 o más")
        if (size !in 1..PageRequest.MAX_SIZE) throw ParametroInvalido("size", "debe ser de 1 a ${PageRequest.MAX_SIZE}")
        val r = brechas.corridaDelAnio(anio, corrida)
        return brechas.pagina(r.id!!, deCategoria, sector?.trim()?.ifEmpty { null }, deConfianza, page, size)
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

    // GET /{anio}/cambios (#203): predios con cambios en catastro desde `desde` (por omisión, el 1 de enero del
    // año), con el Δ autoavalúo de la última corrida TERMINADA (o de `corrida`) y la marca SUPERA_5_UIT. la lista
    // de trabajo de fiscalización
    @GetMapping("/{anio}/cambios")
    suspend fun cambios(
        @PathVariable anio: Int,
        @RequestParam(required = false) corrida: UUID?,
        @RequestParam(required = false) desde: String?,
        @RequestParam(required = false) marca: String?,
        @RequestParam(required = false) objeto: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "25") size: Int
    ): PageResponse<CambioDelPredio> {
        leer()
        val fecha =
            desde?.trim()?.ifEmpty { null }?.let {
                runCatching { LocalDate.parse(it) }.getOrNull() ?: throw ParametroInvalido("desde", "no es una fecha AAAA-MM-DD: '$it'")
            }
        val deObjeto =
            objeto?.trim()?.ifEmpty { null }?.also {
                if (it !in OBJETOS_CAMBIO_CATASTRO) throw ParametroInvalido("objeto", "debe ser uno de ${OBJETOS_CAMBIO_CATASTRO.joinToString()}")
            }
        val deMarca =
            marca?.trim()?.ifEmpty { null }?.also {
                if (it != Seguimiento.SUPERA_5_UIT) throw ParametroInvalido("marca", "debe ser ${Seguimiento.SUPERA_5_UIT}")
            }
        if (page < 0) throw ParametroInvalido("page", "debe ser 0 o más")
        if (size !in 1..PageRequest.MAX_SIZE) throw ParametroInvalido("size", "debe ser de 1 a ${PageRequest.MAX_SIZE}")
        val cambios = brechas.cambios(anio, corrida, fecha, deObjeto)
        return (if (deMarca == null) cambios else cambios.filter { it.marca == deMarca }).pagina(page, size)
    }

    private suspend fun leer() = permisos.exigirLeer(BRECHA_CORRIDA, "consultar la brecha de recaudación")
}
