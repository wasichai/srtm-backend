package srtm

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import wasichai.core.common.GlobalExceptionHandler
import wasichai.core.common.WasichaiException

// what keeps an act from being written or a document from being made, each missing thing named (UIT 2026, TASA_ANUNCIO
// PANEL 2026, PLAZO RG_RECURSO 2026): a 422 whose problem+json carries them as `faltan`, for the portal to list. each
// module words its own (FaltanSanciones, FaltanAnuncios, FaltanParametros)
abstract class Faltan(
    mensaje: String,
    val faltan: List<String>
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, mensaje)

// the problem core's handler writes, plus `faltan`, for every endpoint: before core's handler, which would drop them
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class Faltantes(
    private val errores: GlobalExceptionHandler
) {
    @ExceptionHandler(Faltan::class)
    fun faltan(ex: Faltan): ProblemDetail = errores.handleWasichai(ex).apply { setProperty("faltan", ex.faltan) }
}
