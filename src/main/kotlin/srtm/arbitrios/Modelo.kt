package srtm.arbitrios

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.FieldViolation
import java.math.BigDecimal
import java.time.LocalDate

const val ORDENANZA_ARBITRIO = "ordenanza_arbitrio"
const val SERVICIO_ARBITRIO = "servicio_arbitrio"
const val INAFECTACION_ARBITRIO = "inafectacion_arbitrio"
const val CUOTA_ARBITRIO = "cuota_arbitrio"
const val ANULACION_CUOTA_ARBITRIO = "anulacion_cuota_arbitrio"

// the records of the arbitrios (model/model.json). a relation is its target's id

// the ordinance of a year. it rules only once the provincial ratifies it (D-02b)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrdenanzaArbitrio(
    val id: String? = null,
    val anio: Int? = null,
    val numero: String? = null,
    val fechaPublicacion: LocalDate? = null,
    val acuerdoRatificacion: String? = null,
    val fechaRatificacion: LocalDate? = null,
    val municipalidadRatificante: String? = null,
    val informeCostos: String? = null,
    val observacion: String? = null
)

fun ratificada(o: OrdenanzaArbitrio) = !o.acuerdoRatificacion.isNullOrBlank() && o.fechaRatificacion != null

// a servicio the ordinance charges; its codigo is the key of its tasas and inafectaciones
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ServicioArbitrio(
    val id: String? = null,
    val codigo: String? = null,
    val nombre: String? = null,
    val orden: Int? = null,
    val vigenciaDesde: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null,
    val ordenanza: String? = null
)

// a predio that does not pay a servicio while it is in force (both ends count)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class InafectacionArbitrio(
    val id: String? = null,
    val predio: String? = null,
    val servicio: String? = null,
    val vigenciaDesde: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null,
    val tipoDocumento: String? = null,
    val numeroDocumento: String? = null,
    val motivo: String? = null,
    val observacion: String? = null
)

// what was determined for a predio, a servicio and a month, to whom and with which parameter. never edited nor
// deleted: a correction will be an annulment and a new version of the clave
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CuotaArbitrio(
    val id: String? = null,
    val predio: String? = null,
    val contribuyente: String? = null,
    val servicio: String? = null,
    val parametro: String? = null,
    val anio: Int? = null,
    val periodo: Int? = null,
    val monto: BigDecimal? = null,
    val parametroAplicado: String? = null,
    val zona: String? = null,
    val usoArbitrio: String? = null,
    val fechaCalculo: LocalDate? = null,
    val observacion: String? = null,
    val clave: String? = null
)

// the correction of a cuota: added, never edited nor deleted. clave is the cuota's id, so a cuota is annulled once.
// predio and anio are the cuota's: what a predio's year reads them by
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnulacionCuotaArbitrio(
    val id: String? = null,
    val cuota: String? = null,
    val predio: String? = null,
    val anio: Int? = null,
    val motivo: String? = null,
    val observacion: String? = null,
    val fecha: LocalDate? = null,
    val clave: String? = null
)

// the version a cuota's clave carries: 1 for the first one, one more for each redetermination after an annulment
fun versionDe(clave: String?): Int? = clave?.substringAfterLast('|', "")?.toIntOrNull()

// rentas' det_arbitrio_uq: one cuota per predio, servicio, year and month. core has no composite unique, so the
// unique field holds them joined; the version goes up with each annulment
fun claveDeCuota(
    predio: String,
    servicio: String,
    anio: Int,
    periodo: Int,
    version: Int = 1
) = "$predio|$servicio|$anio|$periodo|$version"

const val PARAMETRO_APLICADO_MAXIMO = 120

// rentas' CuotaDeArbitrio and the CHECKs of determinacion_arbitrio: what a cuota must be to be written. empty when it
// is. the determination never builds one that breaks them; the store refuses the ones that come from anywhere else
fun invariantes(c: CuotaArbitrio): List<FieldViolation> {
    val malas = mutableListOf<FieldViolation>()

    fun falta(
        campo: String,
        valor: Any?
    ) {
        if (valor == null || (valor is String && valor.isBlank())) malas += FieldViolation(campo, "es obligatorio")
    }
    falta("predio", c.predio)
    falta("contribuyente", c.contribuyente)
    falta("servicio", c.servicio)
    falta("parametro", c.parametro)
    falta("anio", c.anio)
    falta("fecha_calculo", c.fechaCalculo)
    if (c.periodo == null || c.periodo !in Periodo.MESES) {
        malas += FieldViolation("periodo", "el arbitrio es mensual: de ${Periodo.PRIMERO} a ${Periodo.ULTIMO}")
    }
    if (c.monto == null || c.monto.signum() < 0) malas += FieldViolation("monto", "no es negativo")
    if (c.parametroAplicado.isNullOrBlank() || c.parametroAplicado.trim().length > PARAMETRO_APLICADO_MAXIMO) {
        malas += FieldViolation("parametro_aplicado", "la llave que se leyó, de 1 a $PARAMETRO_APLICADO_MAXIMO caracteres")
    }
    if (!Observacion.valida(c.observacion)) malas += FieldViolation("observacion", Observacion.REGLA)
    if (c.predio != null && c.servicio != null && c.anio != null && c.periodo != null) {
        val base = claveDeCuota(c.predio, c.servicio, c.anio, c.periodo, 1).removeSuffix("|1")
        val version = c.clave?.removePrefix("$base|")?.toIntOrNull()
        if (c.clave?.startsWith("$base|") != true || version == null || version < 1) {
            malas += FieldViolation("clave", "es predio|servicio|anio|periodo|version")
        }
    }
    return malas
}

// in force on dia: from desde (required) to hasta (open when empty), both ends included
fun vigente(
    desde: LocalDate?,
    hasta: LocalDate?,
    dia: LocalDate
) = desde != null && !desde.isAfter(dia) && (hasta == null || !hasta.isBefore(dia))
