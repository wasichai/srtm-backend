package srtm

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import wasichai.core.common.ConflictException
import wasichai.core.common.GlobalExceptionHandler

// core lets the database refuse a repeated unique value and answers it as a 500 (wasichai 0.2.0). it is the
// client's conflict, not the server's failure: a 409, in core's problem+json. before core's handler, which takes
// every exception
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class Duplicados(
    private val errores: GlobalExceptionHandler
) {
    @ExceptionHandler(DuplicateKeyException::class)
    fun duplicado(ex: DuplicateKeyException): ProblemDetail =
        errores.handleWasichai(ConflictException("Ya existe un registro con ese valor: un campo único no se repite"))
}
