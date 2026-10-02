package srtm.arbitrios

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.platform.WasichaiWebProperties
import java.util.UUID

// the masiva de arbitrios: 202 with the job, which the portal follows by GET until it is TERMINADA or FALLIDA
@RestController
@RequestMapping("/api/srtm/arbitrios/determinaciones")
class DeterminacionMasivaController(
    private val masivas: DeterminacionMasivaService,
    private val web: WasichaiWebProperties
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun lanzar(
        @RequestBody pedido: PedidoDeterminacion
    ) = masivas.lanzar(pedido)

    @GetMapping
    suspend fun lista(
        @RequestParam(required = false) anio: Int?
    ) = masivas.lista(anio)

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ) = masivas.get(id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun eliminar(
        @PathVariable id: UUID
    ) = masivas.eliminar(id)

    @ExceptionHandler(FaltanArbitrios::class)
    fun faltan(ex: FaltanArbitrios): ProblemDetail = problemaFaltan(ex, web)
}
