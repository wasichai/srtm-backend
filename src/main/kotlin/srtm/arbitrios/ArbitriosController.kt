package srtm.arbitrios

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.platform.WasichaiWebProperties
import java.net.URI
import java.time.LocalDate
import java.util.UUID

// the arbitrios of the portal, under /api like the rest of its api. without anio, the current year's. a query
// parameter an endpoint does not know, or cannot read, is a 422 that names it
@RestController
@RequestMapping("/api/srtm")
class ArbitriosController(
    private val arbitrios: ArbitriosService,
    private val web: WasichaiWebProperties
) {
    @GetMapping("/arbitrios/servicios")
    suspend fun servicios(
        @RequestParam params: Map<String, String>
    ) = arbitrios.servicios(anio(soloConoce(params, "anio")))

    @GetMapping("/arbitrios/parametros")
    suspend fun parametros(
        @RequestParam params: Map<String, String>
    ) = arbitrios.parametros(anio(soloConoce(params, "anio")))

    @GetMapping("/arbitrios")
    suspend fun cuotas(
        @RequestParam params: Map<String, String>
    ): PageResponse<CuotaArbitrio> {
        soloConoce(params, "anio", *RELACIONES.toTypedArray(), "page", "size")
        val filtros = mapOf("anio" to anio(params).toString()) + RELACIONES.mapNotNull { f -> params[f]?.let { f to id(f, it).toString() } }
        return arbitrios.pagina(filtros, entero(params, "page", 0, 0..Int.MAX_VALUE), entero(params, "size", 25, 1..PageRequest.MAX_SIZE))
    }

    @GetMapping("/predios/{id}/arbitrios")
    suspend fun delPredio(
        @PathVariable id: UUID,
        @RequestParam params: Map<String, String>
    ) = arbitrios.matrizDelPredio(id, anio(soloConoce(params, "anio")))

    @GetMapping("/contribuyentes/{id}/arbitrios")
    suspend fun delContribuyente(
        @PathVariable id: UUID,
        @RequestParam params: Map<String, String>
    ) = arbitrios.delContribuyente(id, anio(soloConoce(params, "anio")))

    // 201 with what it wrote; 200 with nothing when nothing was pending
    @PostMapping("/predios/{id}/arbitrios")
    suspend fun determinarPredio(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoDeterminacion
    ) = escritas(arbitrios.determinarPredio(id, pedido))

    @PostMapping("/contribuyentes/{id}/arbitrios")
    suspend fun determinarContribuyente(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoDeterminacion
    ) = escritas(arbitrios.determinarContribuyente(id, pedido))

    // the problem core's handler would write, plus what is missing
    @ExceptionHandler(FaltanArbitrios::class)
    fun faltan(ex: FaltanArbitrios): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.message).apply {
            type = URI.create("${web.problemBaseUri.trimEnd('/')}/${HttpStatus.UNPROCESSABLE_CONTENT.value()}")
            title = HttpStatus.UNPROCESSABLE_CONTENT.reasonPhrase
            setProperty("faltan", ex.faltan)
        }

    private fun escritas(cuotas: List<CuotaArbitrio>) = ResponseEntity.status(if (cuotas.isEmpty()) HttpStatus.OK else HttpStatus.CREATED).body(cuotas)

    private fun soloConoce(
        params: Map<String, String>,
        vararg conocidos: String
    ): Map<String, String> {
        params.keys.firstOrNull { it !in conocidos }?.let { throw ParametroInvalido(it, "no es un filtro de esta consulta: ${conocidos.joinToString(", ")}") }
        return params
    }

    private fun anio(params: Map<String, String>): Int {
        val valor = params["anio"] ?: return LocalDate.now().year
        return valor.toIntOrNull()?.takeIf { it in ArbitriosService.ANIOS } ?: throw ParametroInvalido("anio", "no es un año: '$valor'")
    }

    private fun id(
        nombre: String,
        valor: String
    ): UUID = runCatching { UUID.fromString(valor) }.getOrNull() ?: throw ParametroInvalido(nombre, "no es un id: '$valor'")

    private fun entero(
        params: Map<String, String>,
        nombre: String,
        omision: Int,
        rango: IntRange
    ): Int {
        val valor = params[nombre] ?: return omision
        return valor.toIntOrNull()?.takeIf { it in rango } ?: throw ParametroInvalido(nombre, "no es un número de ${rango.first} a ${rango.last}: '$valor'")
    }

    private companion object {
        val RELACIONES = listOf("predio", "contribuyente", "servicio")
    }
}
