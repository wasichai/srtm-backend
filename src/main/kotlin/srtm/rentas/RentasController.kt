package srtm.rentas

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// the end-user portal's api. under /api like core's routes, so core's jwt chain protects it and
// its error handler answers problem+json. handlers are suspend: the caller's security context
// lives in the request's coroutine, which RecordService reads
@RestController
@RequestMapping("/api/srtm")
class RentasController(
    private val rentas: RentasService
) {
    @GetMapping("/resumen")
    suspend fun resumen() = rentas.resumen()

    @GetMapping("/catalogos")
    suspend fun catalogos() = rentas.catalogos()

    @GetMapping("/contribuyentes")
    suspend fun contribuyentes(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ) = rentas.contribuyentes(q, page, size)

    @PostMapping("/contribuyentes")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun crearContribuyente(
        @RequestBody body: Contribuyente
    ) = rentas.crearContribuyente(body)

    @GetMapping("/contribuyentes/{id}")
    suspend fun contribuyente(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = rentas.fichaContribuyente(id, anio)

    @PutMapping("/contribuyentes/{id}")
    suspend fun actualizarContribuyente(
        @PathVariable id: UUID,
        @RequestBody body: Contribuyente
    ) = rentas.actualizarContribuyente(id, body)

    @GetMapping("/contribuyentes/{id}/declaraciones")
    suspend fun declaracionesDeContribuyente(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = rentas.declaracionesDeContribuyente(id, anio)

    @GetMapping("/predios")
    suspend fun predios(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?
    ) = rentas.predios(q, page, size)

    @PostMapping("/predios")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun crearPredio(
        @RequestBody body: Predio
    ) = rentas.crearPredio(body)

    @GetMapping("/predios/{id}")
    suspend fun predio(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = rentas.fichaPredio(id, anio)

    @PutMapping("/predios/{id}")
    suspend fun actualizarPredio(
        @PathVariable id: UUID,
        @RequestBody body: Predio
    ) = rentas.actualizarPredio(id, body)

    @GetMapping("/predios/{id}/declaraciones")
    suspend fun declaracionesDePredio(
        @PathVariable id: UUID,
        @RequestParam(required = false) anio: Int?
    ) = rentas.declaracionesDePredio(id, anio)

    @PostMapping("/declaraciones")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun crearDeclaracion(
        @RequestBody body: Declaracion
    ) = rentas.crearDeclaracion(body)

    @PutMapping("/declaraciones/{id}")
    suspend fun actualizarDeclaracion(
        @PathVariable id: UUID,
        @RequestBody body: Declaracion
    ) = rentas.actualizarDeclaracion(id, body)
}
