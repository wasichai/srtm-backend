package srtm.anuncios

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.FieldViolation
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.WasichaiException
import wasichai.core.platform.WasichaiWebProperties
import java.net.URI
import java.time.LocalDate
import java.util.UUID

// a query parameter the endpoint cannot serve, or one it cannot read: a 422 that names it, never a filter silently
// dropped
class FiltroInvalido(
    nombre: String,
    razon: String
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, "El parámetro '$nombre' $razon", listOf(FieldViolation(nombre, razon)))

// the tasa de anuncios y propaganda, under /api/srtm like the rest of the portal's api (SPEC §7, Anuncios)
@RestController
@RequestMapping("/api/srtm")
class AnunciosController(
    private val anuncios: AnunciosService,
    private val web: WasichaiWebProperties
) {
    @GetMapping("/anuncios")
    suspend fun padron(
        @RequestParam params: Map<String, String>
    ): PageResponse<AnuncioEnPadron> {
        soloConoce(params, "contribuyente", "clase", "estado", "vigentes_a", "q", "page", "size")
        val filtros =
            FiltrosPadron(
                contribuyente = params["contribuyente"]?.let { id("contribuyente", it) },
                clase = params["clase"]?.let { uno("clase", it, CLASES) },
                estado = params["estado"]?.let { uno("estado", it, ESTADOS) },
                vigentesA = fecha(params, "vigentes_a") ?: LocalDate.now(),
                q = params["q"]
            )
        return anuncios.padron(filtros, entero(params, "page", 0, 0..Int.MAX_VALUE), entero(params, "size", 25, 1..PageRequest.MAX_SIZE))
    }

    // 201 with the anuncio and its AUTORIZACION; 200 when the same Idempotency-Key was registered already
    @PostMapping("/anuncios")
    suspend fun registrar(
        @RequestHeader("Idempotency-Key", required = false) clave: String?,
        @RequestBody pedido: PedidoAnuncio
    ): ResponseEntity<AnuncioRegistrado> {
        val registrado = anuncios.registrar(pedido, clave)
        return ResponseEntity.status(if (registrado.yaExistia) HttpStatus.OK else HttpStatus.CREATED).body(registrado)
    }

    @GetMapping("/anuncios/tasas")
    suspend fun tasas(
        @RequestParam params: Map<String, String>
    ): TasasAnuncios {
        soloConoce(params, "anio")
        val anio = params["anio"]?.let { valor -> valor.toIntOrNull()?.takeIf { it in ANIOS } ?: throw FiltroInvalido("anio", "no es un año: '$valor'") }
        return anuncios.tasas(anio ?: LocalDate.now().year)
    }

    @GetMapping("/anuncios/{id}")
    suspend fun ficha(
        @PathVariable id: UUID,
        @RequestParam params: Map<String, String>
    ): FichaAnuncio {
        soloConoce(params)
        return anuncios.ficha(id)
    }

    @PostMapping("/anuncios/{id}/renovacion")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun renovar(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoRenovacion
    ) = anuncios.renovar(id, pedido)

    @PostMapping("/anuncios/{id}/cese")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun cesar(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoBaja
    ) = anuncios.baja(id, CESE, pedido)

    @PostMapping("/anuncios/{id}/retiro")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun retirar(
        @PathVariable id: UUID,
        @RequestBody pedido: PedidoBaja
    ) = anuncios.baja(id, RETIRO, pedido)

    @GetMapping("/contribuyentes/{id}/anuncios")
    suspend fun delContribuyente(
        @PathVariable id: UUID,
        @RequestParam params: Map<String, String>
    ): AnunciosDe {
        soloConoce(params)
        return anuncios.delContribuyente(id)
    }

    @GetMapping("/predios/{id}/anuncios")
    suspend fun delPredio(
        @PathVariable id: UUID,
        @RequestParam params: Map<String, String>
    ): AnunciosDe {
        soloConoce(params)
        return anuncios.delPredio(id)
    }

    // the problem core's handler would write, plus what is missing
    @ExceptionHandler(FaltanAnuncios::class)
    fun faltan(ex: FaltanAnuncios): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.message).apply {
            type = URI.create("${web.problemBaseUri.trimEnd('/')}/${HttpStatus.UNPROCESSABLE_CONTENT.value()}")
            title = HttpStatus.UNPROCESSABLE_CONTENT.reasonPhrase
            setProperty("faltan", ex.faltan)
        }

    private fun soloConoce(
        params: Map<String, String>,
        vararg conocidos: String
    ) {
        params.keys.firstOrNull { it !in conocidos }?.let {
            throw FiltroInvalido(
                it,
                if (conocidos.isEmpty()) "no es un filtro: esta consulta no tiene" else "no es un filtro de esta consulta: ${conocidos.joinToString(", ")}"
            )
        }
    }

    private fun uno(
        nombre: String,
        valor: String,
        admitidos: List<String>
    ) = valor.takeIf { it in admitidos } ?: throw FiltroInvalido(nombre, "es uno de ${admitidos.joinToString(", ")}: no '$valor'")

    private fun id(
        nombre: String,
        valor: String
    ): UUID = runCatching { UUID.fromString(valor) }.getOrNull() ?: throw FiltroInvalido(nombre, "no es un id: '$valor'")

    private fun fecha(
        params: Map<String, String>,
        nombre: String
    ): LocalDate? {
        val valor = params[nombre] ?: return null
        return runCatching { LocalDate.parse(valor) }.getOrNull() ?: throw FiltroInvalido(nombre, "no es una fecha AAAA-MM-DD: '$valor'")
    }

    private fun entero(
        params: Map<String, String>,
        nombre: String,
        omision: Int,
        rango: IntRange
    ): Int {
        val valor = params[nombre] ?: return omision
        return valor.toIntOrNull()?.takeIf { it in rango } ?: throw FiltroInvalido(nombre, "no es un número de ${rango.first} a ${rango.last}: '$valor'")
    }

    private companion object {
        val ANIOS = 1900..9999
    }
}
