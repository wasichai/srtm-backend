package srtm

import srtm.rentas.Records
import tools.jackson.core.JacksonException
import tools.jackson.databind.DatabindException
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale

// what AlmacenGuardado asks before it writes a record of `objetos`, whoever writes it: the generic api, /admin, an ADMIN
// or srtm's own services. each module has its rule as a bean of its package (srtm.arbitrios, srtm.sanciones,
// srtm.anuncios), and none knows the others. an object has one rule at most
interface ReglaDeEscritura {
    val objetos: Set<String>

    // only srtm's api writes them: an insert without the mark (EscrituraDeSrtm) is a 403, an ADMIN's too, because the
    // generic api does not run srtm's rules. a change that alActualizar lets through needs the mark as well
    val soloDesdeElServicio: Boolean

    // the invariants of a new record, by its attributes as they came: a ValidationException (400) that names each field
    fun alInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    )

    // a change of a stored record: every field of the object on both sides, null included (core's update replaces them
    // all, a field left out of the request is cleared). nothing changes unless the rule says what may: 409
    fun alActualizar(
        objeto: String,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ): Unit = throw noSeCambia(objeto)

    // nothing of a rule is ever deleted
    fun alBorrar(objeto: String): Nothing = throw noSeCambia(objeto)

    // nor moved by a workflow
    fun alCambiarDeEstado(objeto: String): Nothing = throw noSeCambia(objeto)

    // the 409 of a change, a delete or a transition
    fun noSeCambia(objeto: String) = ConflictException("«$objeto» solo se agrega: no se edita ni se borra, por ninguna puerta")
}

// a record's attributes as a module's dto, for its invariants. a value that is not of its field's type is a 400 that
// names the field (core would refuse it too, after the rule)
fun <T : Any> leerRegistro(
    type: Class<T>,
    attrs: Map<String, Any?>
): T =
    try {
        Records.read(type, "", attrs)
    } catch (e: JacksonException) {
        throw malFormado(e)
    } catch (e: IllegalArgumentException) {
        throw (e.cause as? JacksonException)?.let(::malFormado) ?: e
    }

private fun malFormado(e: JacksonException): ValidationException {
    val campo = (e as? DatabindException)?.path?.firstNotNullOfOrNull { it.propertyName } ?: "attributes"
    return ValidationException("Un valor no tiene el tipo de su campo", campo, "no tiene el tipo de su campo")
}

// two stored values are the same: null and "" alike, a number by its value (5500 and 5500.00), a date by its iso text
fun iguales(
    a: Any?,
    b: Any?
): Boolean {
    val x = a?.takeUnless { it is String && it.isEmpty() }
    val y = b?.takeUnless { it is String && it.isEmpty() }
    if (x == null || y == null) return x == y
    if (x is Number || y is Number) {
        val nx = x.toString().toBigDecimalOrNull()
        val ny = y.toString().toBigDecimalOrNull()
        if (nx != null && ny != null) return nx.compareTo(ny) == 0
    }
    return x.toString() == y.toString()
}

// a stored date, as core reads it back (iso text) or as a service sends it
fun fecha(valor: Any?): LocalDate? =
    when (valor) {
        is LocalDate -> valor
        is String -> runCatching { LocalDate.parse(valor) }.getOrNull()
        else -> null
    }

// the violations of a record, field by field: what a rule collects and throws as one 400
class Violaciones {
    val todas = mutableListOf<FieldViolation>()

    fun mal(
        campo: String,
        motivo: String
    ) {
        todas += FieldViolation(campo, motivo)
    }

    // a required value: true when it is there
    fun requerido(
        campo: String,
        valor: Any?
    ): Boolean {
        val hay = valor != null && !(valor is String && valor.isBlank())
        if (!hay) mal(campo, "es obligatorio")
        return hay
    }

    // a text of up to `maximo` characters, required or not
    fun texto(
        campo: String,
        valor: String?,
        maximo: Int,
        obligatorio: Boolean = false
    ) {
        if (valor == null) {
            if (obligatorio) mal(campo, "es obligatorio")
            return
        }
        if (valor.isBlank() || valor.length > maximo) mal(campo, "de 1 a $maximo caracteres")
    }

    // a code typed from a form: required, trimmed, upper-cased, up to `maximo` characters
    fun codigo(
        campo: String,
        valor: String?,
        maximo: Int
    ) {
        if (!requerido(campo, valor)) return
        if (valor!!.length > maximo || valor != valor.trim().uppercase(Locale.ROOT)) {
            mal(campo, "de 1 a $maximo caracteres, recortado y en mayúsculas")
        }
    }

    // an alícuota: above 0 and up to 100
    fun porcentaje(
        campo: String,
        valor: BigDecimal?,
        obligatorio: Boolean = false
    ) {
        if (valor == null) {
            if (obligatorio) mal(campo, "es obligatorio")
            return
        }
        if (valor.signum() <= 0 || valor > CIEN) mal(campo, "es mayor que 0 y hasta 100")
    }

    // an amount of money: required and not negative
    fun importe(
        campo: String,
        valor: BigDecimal?,
        obligatorio: Boolean = true
    ) {
        if (valor == null) {
            if (obligatorio) mal(campo, "es obligatorio")
            return
        }
        if (valor.signum() < 0) mal(campo, "no es negativo")
    }

    fun observacion(valor: String?) {
        if (!Observacion.valida(valor)) mal("observacion", Observacion.REGLA)
    }

    // a derived key: what it must be, when its parts are there
    fun clave(
        campo: String,
        valor: String?,
        esperada: String?,
        forma: String
    ) {
        if (esperada != null && valor != esperada) mal(campo, "es $forma")
    }

    // one 400 with every violation, if any
    fun lanzar(registro: String) {
        if (todas.isNotEmpty()) throw ValidationException("$registro no es válido", todas)
    }

    private companion object {
        val CIEN = BigDecimal(100)
    }
}
