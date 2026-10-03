package srtm.anuncios

import srtm.Violaciones
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.FieldViolation
import java.math.BigDecimal
import java.time.LocalDate

const val ANUNCIO = "anuncio"
const val MOVIMIENTO_ANUNCIO = "movimiento_anuncio"

// movimiento_anuncio.tipo: the two that accrue the tasa, the two that end the anuncio
const val AUTORIZACION = "AUTORIZACION"
const val RENOVACION = "RENOVACION"
const val CESE = "CESE"
const val RETIRO = "RETIRO"
val DEVENGAN = setOf(AUTORIZACION, RENOVACION)

// the records of the anuncios (model/model.json). a relation is its target's id; an enum, its option

// an anuncio is not edited: its state derives from its movimientos
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Anuncio(
    val id: String? = null,
    val anio: Int? = null,
    val correlativo: Int? = null,
    val numero: String? = null,
    val clase: String? = null,
    val tipo: String? = null,
    val emplazamiento: String? = null,
    val forma: String? = null,
    val denominacion: String? = null,
    val direccion: String? = null,
    val area: BigDecimal? = null,
    val lados: Int? = null,
    val cantidad: Int? = null,
    val fechaAutorizacion: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null,
    val expediente: String? = null,
    val fechaExpediente: LocalDate? = null,
    val licenciaTexto: String? = null,
    val claveIdempotencia: String? = null,
    val observacion: String? = null,
    val contribuyente: String? = null,
    val predio: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MovimientoAnuncio(
    val id: String? = null,
    val tipo: String? = null,
    val fecha: LocalDate? = null,
    val anio: Int? = null,
    val referenciaCargo: String? = null,
    val tasa: BigDecimal? = null,
    val vigenciaHasta: LocalDate? = null,
    val motivo: String? = null,
    val clave: String? = null,
    val observacion: String? = null,
    val anuncio: String? = null,
    val parametro: String? = null
)

// AN-AAAA-NNNNNN
fun numeroDeAnuncio(
    anio: Int,
    correlativo: Int
) = "AN-%04d-%06d".format(anio, correlativo)

// rentas' four partial uniques in one: one authorization, one cese, one retiro, one renewal per ejercicio
fun claveDeMovimiento(
    anuncio: String,
    tipo: String,
    anio: Int? = null
) = if (tipo == RENOVACION) "$anuncio|$tipo|$anio" else "$anuncio|$tipo"

// the obligation an accrual is: one per anuncio and ejercicio, so an authorization and a renewal of the same year clash
fun referenciaDeCargo(
    anuncio: String,
    anio: Int
) = "ANUNCIO-$anuncio-$anio"

// lengths of rentas' columns: core's TEXT has none
object Largos {
    const val EMPLAZAMIENTO = 30
    const val FORMA = 30
    const val DENOMINACION = 240
    const val DIRECCION = 300
    const val EXPEDIENTE = 20
    const val LICENCIA = 40
    const val IDEMPOTENCIA = 64
    const val MOTIVO = 500
}

// what each record must be to be written, empty when it is: rentas' CHECKs, and the shape srtm's services always give
// them. ReglaDeAnuncios refuses the ones that come from anywhere else

fun invariantes(a: Anuncio): List<FieldViolation> {
    val v = Violaciones()
    v.requerido("anio", a.anio)
    if (v.requerido("correlativo", a.correlativo) && a.correlativo!! < 1) v.mal("correlativo", "empieza en 1")
    if (a.anio != null && a.correlativo != null) {
        v.clave("numero", a.numero, numeroDeAnuncio(a.anio, a.correlativo), "AN-AAAA-NNNNNN, con su anio y correlativo")
    }
    v.requerido("clase", a.clase)
    v.requerido("tipo", a.tipo)
    v.texto("emplazamiento", a.emplazamiento, Largos.EMPLAZAMIENTO)
    v.texto("forma", a.forma, Largos.FORMA)
    v.texto("denominacion", a.denominacion, Largos.DENOMINACION)
    v.texto("direccion", a.direccion, Largos.DIRECCION, obligatorio = true)
    if (v.requerido("area", a.area) && a.area!!.signum() <= 0) v.mal("area", "es mayor que 0")
    if (v.requerido("lados", a.lados) && a.lados!! < 1) v.mal("lados", "al menos 1")
    if (v.requerido("cantidad", a.cantidad) && a.cantidad!! < 1) v.mal("cantidad", "al menos 1")
    v.requerido("fecha_autorizacion", a.fechaAutorizacion)
    if (a.fechaAutorizacion != null && a.vigenciaHasta != null && a.vigenciaHasta < a.fechaAutorizacion) {
        v.mal("vigencia_hasta", "no es anterior a la autorización")
    }
    v.texto("expediente", a.expediente, Largos.EXPEDIENTE)
    v.texto("licencia_texto", a.licenciaTexto, Largos.LICENCIA)
    // empty is null: a "" would be one more unique value
    v.texto("clave_idempotencia", a.claveIdempotencia, Largos.IDEMPOTENCIA)
    v.observacion(a.observacion)
    v.requerido("contribuyente", a.contribuyente)
    return v.todas
}

fun invariantes(m: MovimientoAnuncio): List<FieldViolation> {
    val v = Violaciones()
    v.requerido("tipo", m.tipo)
    v.requerido("fecha", m.fecha)
    v.observacion(m.observacion)
    v.texto("motivo", m.motivo, Largos.MOTIVO)
    if (v.requerido("anuncio", m.anuncio) && m.tipo != null) {
        v.clave("clave", m.clave, claveDeMovimiento(m.anuncio!!, m.tipo, m.anio), "anuncio|tipo, y |anio en una RENOVACION")
    }
    if (m.tasa != null && m.tasa.signum() <= 0) v.mal("tasa", "es mayor que 0")
    val devenga = m.tipo in DEVENGAN
    // rentas' _devengo_ck: an accrual is its ejercicio, its referencia, its tasa and the row it was read from
    if (devenga != (m.anio != null && m.referenciaCargo != null && m.tasa != null && m.parametro != null)) {
        v.mal("referencia_cargo", "la autorización y la renovación devengan con anio, referencia_cargo, tasa y parametro, y solo ellas")
    }
    if (m.anuncio != null && m.anio != null && m.referenciaCargo != null) {
        v.clave("referencia_cargo", m.referenciaCargo, referenciaDeCargo(m.anuncio, m.anio), "ANUNCIO-<anuncio>-<anio>")
    }
    // rentas' _motivo_ck: the cese and the retiro say why, and only they
    if (m.tipo != null && (m.tipo in setOf(CESE, RETIRO)) != (m.motivo != null)) v.mal("motivo", "el cese y el retiro llevan motivo, y solo ellos")
    if (m.tipo in setOf(CESE, RETIRO) && m.vigenciaHasta != null) v.mal("vigencia_hasta", "el cese y el retiro no tienen vigencia")
    return v.todas
}
