package srtm.sanciones

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import wasichai.core.common.Actions
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.platform.WasichaiWebProperties
import java.time.LocalDate
import java.util.UUID

// a query parameter an endpoint cannot serve, or cannot read: a 422 that names it (rentas#541), never a filter
// silently dropped. srtm.arbitrios has its twin: the modules do not import each other
class ParametroInvalido(
    nombre: String,
    razon: String
) : NoProcede("El parámetro '$nombre' $razon", listOf(FieldViolation(nombre, razon)))

// the query parameters of the sanciones' endpoints. a blank value is no value (a form's empty field)
object Filtros {
    // the parameters, when every one is known
    fun soloConoce(
        params: Map<String, String>,
        vararg conocidos: String
    ): Map<String, String> {
        params.keys.firstOrNull { it !in conocidos }?.let { throw ParametroInvalido(it, "no es un filtro de esta consulta: ${conocidos.joinToString(", ")}") }
        return params
    }

    fun texto(
        params: Map<String, String>,
        nombre: String
    ): String? = params[nombre]?.trim()?.ifEmpty { null }

    // an iso day (AAAA-MM-DD)
    fun fecha(
        params: Map<String, String>,
        nombre: String
    ): LocalDate? {
        val valor = texto(params, nombre) ?: return null
        return runCatching { LocalDate.parse(valor) }.getOrNull() ?: throw ParametroInvalido(nombre, "no es una fecha AAAA-MM-DD: '$valor'")
    }

    fun id(
        params: Map<String, String>,
        nombre: String
    ): UUID? {
        val valor = texto(params, nombre) ?: return null
        return runCatching { UUID.fromString(valor) }.getOrNull() ?: throw ParametroInvalido(nombre, "no es un id: '$valor'")
    }

    fun entero(
        params: Map<String, String>,
        nombre: String,
        omision: Int,
        rango: IntRange
    ): Int {
        val valor = texto(params, nombre) ?: return omision
        return valor.toIntOrNull()?.takeIf { it in rango } ?: throw ParametroInvalido(nombre, "no es un número de ${rango.first} a ${rango.last}: '$valor'")
    }

    fun page(params: Map<String, String>) = entero(params, "page", 0, 0..Int.MAX_VALUE)

    fun size(params: Map<String, String>) = entero(params, "size", TAMANO, 1..PageRequest.MAX_SIZE)

    // the page the portal asks for
    const val TAMANO = 20

    // `campo` within [desde, hasta], both ends included and either open: a condition on the object's column
    fun entre(
        campo: String,
        desde: LocalDate?,
        hasta: LocalDate?
    ): RecordCriterion? {
        if (desde == null && hasta == null) return null
        return RecordCriterion { definition, bind ->
            val columna = "\"${definition.fields.first { it.name == campo }.columnName}\""
            listOfNotNull(desde?.let { "$columna >= ${bind(it)}" }, hasta?.let { "$columna <= ${bind(it)}" }).joinToString(" AND ")
        }
    }

    // `campo` (a relation) is one of `ids`
    fun entre(
        campo: String,
        ids: Collection<String>
    ): RecordCriterion =
        RecordCriterion { definition, bind ->
            val columna = "\"${definition.fields.first { it.name == campo }.columnName}\""
            "$columna = ANY(${bind(ids.map(UUID::fromString).toTypedArray())})"
        }
}

// a relation's id as an act's body sends it: none when blank, a 400 that names the field when it is no id
fun relacion(
    campo: String,
    valor: String?
): String? {
    val texto = valor?.trim()?.ifEmpty { null } ?: return null
    return runCatching { UUID.fromString(texto).toString() }.getOrNull()
        ?: throw ValidationException("El $campo no es un id", listOf(FieldViolation(campo, "es el id de un $campo")))
}

// the permission an act needs, asked before anything is read: who may not create the act's record gets a 403 that
// names the object, not a computation they cannot write
@Component
class Permisos(
    private val currentUser: CurrentUser,
    private val metadata: MetadataService
) {
    suspend fun exigirCrear(
        objeto: String,
        acto: String
    ) {
        val usuario = currentUser.require()
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(objeto).obj.id)
        } catch (_: ForbiddenException) {
            throw ForbiddenException("${acto.replaceFirstChar { it.uppercase() }} exige permiso de creación sobre $objeto")
        }
    }
}

// the 422 with `faltan` of every endpoint of the sanciones, before core's handler (which would drop the faltan)
@RestControllerAdvice(basePackageClasses = [Permisos::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ErroresDeSanciones(
    private val web: WasichaiWebProperties
) {
    @ExceptionHandler(FaltanSanciones::class)
    fun faltan(ex: FaltanSanciones): ProblemDetail = problemaFaltan(ex, web)
}
