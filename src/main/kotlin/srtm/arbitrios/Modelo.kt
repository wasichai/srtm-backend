package srtm.arbitrios

import srtm.Observacion
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
const val ZONA_ARBITRIO_UBICACION = "zona_arbitrio_ubicacion"

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
    val secuenciaUso: String? = null,
    val base: String? = null,
    val cantidadBase: BigDecimal? = null,
    val tasaUnitaria: BigDecimal? = null,
    val habitantes: Int? = null,
    val habitantesPresuntos: Boolean? = null,
    val promedioHabitantes: BigDecimal? = null,
    val variacionHabitante: BigDecimal? = null,
    val influencia: String? = null,
    val afluencia: String? = null,
    val redondeo: String? = null,
    val montoSinTope: BigDecimal? = null,
    val topeAplicado: BigDecimal? = null,
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

// a zona by ubicación: via, tramo (numero range and paridad) and manzana, optionally narrowed to one servicio.
// more precise than ARBITRIO_ZONA's sector catastral, and tried first (Zonas.de)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ZonaUbicacion(
    val id: String? = null,
    val servicio: String? = null,
    val tipoVia: String? = null,
    val via: String? = null,
    val manzana: String? = null,
    val numeroInicial: Int? = null,
    val numeroFinal: Int? = null,
    val paridad: String? = null,
    val zona: String? = null,
    val vigenciaDesde: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null,
    val norma: String? = null,
    val observacion: String? = null,
    val clave: String? = null
)

// a zona_arbitrio_ubicacion row's unique key: servicio, tipo_via, via, manzana, numero_inicial, numero_final,
// paridad and vigencia_desde joined by "|", empty ends as ""
fun claveDeZonaUbicacion(z: ZonaUbicacion) =
    listOf(
        z.servicio?.trim().orEmpty(),
        z.tipoVia?.trim().orEmpty(),
        z.via?.trim().orEmpty(),
        z.manzana?.trim().orEmpty(),
        z.numeroInicial?.toString().orEmpty(),
        z.numeroFinal?.toString().orEmpty(),
        z.paridad?.trim().orEmpty(),
        z.vigenciaDesde?.toString().orEmpty()
    ).joinToString("|")

// the version a cuota's clave carries: 1 for the first one, one more for each redetermination after an annulment
fun versionDe(clave: String?): Int? = clave?.substringAfterLast('|', "")?.toIntOrNull()

// rentas' det_arbitrio_uq, one PU per uso: one cuota per predio, servicio, year, month and secuencia de uso. core
// has no composite unique, so the unique field holds them joined; the version goes up with each annulment. the
// cuotas written before the secuencia (one PU per predio) keep their predio|servicio|anio|periodo|version
fun claveDeCuota(
    predio: String,
    servicio: String,
    anio: Int,
    periodo: Int,
    secuencia: String,
    version: Int = 1
) = "$predio|$servicio|$anio|$periodo|$secuencia|$version"

// several keys read, separated by ";" (TASA_ARBITRIO always; BASE_ARBITRIO, HABITANTES_* and REDONDEO_ARBITRIO when
// there were rows)
const val PARAMETRO_APLICADO_MAXIMO = 500

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
        malas += FieldViolation("parametro_aplicado", "las llaves que se leyeron, separadas por ;, de 1 a $PARAMETRO_APLICADO_MAXIMO caracteres")
    }
    if (!Observacion.valida(c.observacion)) malas += FieldViolation("observacion", Observacion.REGLA)
    if (c.predio != null && c.servicio != null && c.anio != null && c.periodo != null) {
        val version = versionDe(c.clave)
        val secuencia = c.secuenciaUso
        if (secuencia.isNullOrBlank() || version == null || version < 1 ||
            c.clave != claveDeCuota(c.predio, c.servicio, c.anio, c.periodo, secuencia, version)
        ) {
            malas += FieldViolation("clave", "es predio|servicio|anio|periodo|secuencia|version")
        }
    }
    malas += topeMalo(c)
    if (c.base != null) malas += desgloseMalo(c)
    return malas
}

// a cuota lowered by TOPE_ARBITRIO: tope_aplicado (above 0) and monto_sin_tope go together, and its monto never goes
// above what it was before the tope (nor below 0, checked above)
private fun topeMalo(c: CuotaArbitrio): List<FieldViolation> {
    val malas = mutableListOf<FieldViolation>()
    if (c.topeAplicado != null && c.topeAplicado.signum() <= 0) malas += FieldViolation("tope_aplicado", "es mayor que 0")
    if (c.topeAplicado == null && c.montoSinTope != null) malas += FieldViolation("tope_aplicado", "va con monto_sin_tope: los dos o ninguno")
    if (c.topeAplicado != null && c.montoSinTope == null) malas += FieldViolation("monto_sin_tope", "va con tope_aplicado: los dos o ninguno")
    if (c.topeAplicado != null && c.montoSinTope != null && c.monto != null && c.monto > c.montoSinTope) {
        malas += FieldViolation("monto", "con tope no supera monto_sin_tope: ${c.montoSinTope}")
    }
    return malas
}

// a cuota with its desglose (base not null): a known base, a positive cantidad, a tasa not negative, P and V
// together, a known redondeo, and a monto that squares with them (montoDelDesglose) - monto_sin_tope's, when a tope
// lowered it
private fun desgloseMalo(c: CuotaArbitrio): List<FieldViolation> {
    val malas = mutableListOf<FieldViolation>()
    if (Base.entries.none { it.name == c.base }) malas += FieldViolation("base", "es ${Base.entries.joinToString()}")
    if (c.cantidadBase == null || c.cantidadBase.signum() <= 0) malas += FieldViolation("cantidad_base", "es mayor que 0")
    if (c.tasaUnitaria == null || c.tasaUnitaria.signum() < 0) malas += FieldViolation("tasa_unitaria", "no es negativa")
    if ((c.promedioHabitantes == null) != (c.variacionHabitante == null)) {
        malas += FieldViolation("variacion_habitante", "va con promedio_habitantes: las dos o ninguna")
    }
    if (!c.redondeo.isNullOrBlank() && Redondeo.de(c.redondeo) == null) {
        malas += FieldViolation("redondeo", "es decimales:modo, con modo ${Redondeo.MODOS.keys.joinToString()}")
    }
    val esperado = if (malas.isEmpty()) montoDelDesglose(c) else null
    val calculado = c.montoSinTope ?: c.monto
    if (esperado != null && calculado != null && calculado.compareTo(esperado) != 0) {
        val campo = if (c.montoSinTope != null) "monto_sin_tope" else "monto"
        malas += FieldViolation(campo, "es tasa_unitaria × cantidad_base × (1 + (H − P) × V), redondeado: $esperado")
    }
    return malas
}

// what a well formed desglose gives, as Calculo.calcular computes it: tasa × cantidad × factor de habitantes (H the
// declared ones, or P when presumed), rounded at the end
private fun montoDelDesglose(c: CuotaArbitrio): BigDecimal {
    val promedio = c.promedioHabitantes
    val variacion = c.variacionHabitante
    val factor =
        if (promedio == null || variacion == null) {
            BigDecimal.ONE
        } else {
            BigDecimal.ONE.add(((c.habitantes?.let(::BigDecimal) ?: promedio) - promedio).multiply(variacion))
        }
    val bruto = c.tasaUnitaria!!.multiply(c.cantidadBase!!).multiply(factor)
    return Redondeo.de(c.redondeo)?.aplicar(bruto) ?: bruto
}

// in force on dia: from desde (required) to hasta (open when empty), both ends included
fun vigente(
    desde: LocalDate?,
    hasta: LocalDate?,
    dia: LocalDate
) = desde != null && !desde.isAfter(dia) && (hasta == null || !hasta.isBefore(dia))
