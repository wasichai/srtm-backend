package srtm

import org.springframework.http.HttpStatus
import wasichai.core.common.FieldViolation
import wasichai.core.common.WasichaiException

// a query parameter an endpoint cannot serve, or cannot read: a 422 that names it (rentas#541), never a filter
// silently dropped. the modules' endpoints answer it the same way
class ParametroInvalido(
    nombre: String,
    razon: String
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, "El parámetro '$nombre' $razon", listOf(FieldViolation(nombre, razon)))
