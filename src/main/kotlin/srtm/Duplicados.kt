package srtm

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import wasichai.core.common.ConflictException
import wasichai.core.common.GlobalExceptionHandler

// a repeated unique value is the client's conflict, not the server's failure: a 409, in core's problem+json. since
// 0.3.0 core answers a 409 too, with errors[].field (D23) but in english; this one is kept to give the portal the
// message in castellano, and the field is lost. follow-up: translate core's detail or pass the field (#76). before
// core's handler, which takes every exception
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class Duplicados(
    private val errores: GlobalExceptionHandler
) {
    @ExceptionHandler(DuplicateKeyException::class)
    fun duplicado(ex: DuplicateKeyException): ProblemDetail =
        errores.handleWasichai(ConflictException("Ya existe un registro con ese valor: un campo único no se repite"))
}
