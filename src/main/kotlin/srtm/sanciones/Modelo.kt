package srtm.sanciones

import com.fasterxml.jackson.annotation.JsonProperty
import srtm.Violaciones
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.FieldViolation
import java.math.BigDecimal
import java.time.LocalDate

const val CODIGO_INFRACCION = "codigo_infraccion"
const val NOTIFICACION_ADMINISTRATIVA = "notificacion_administrativa"
const val SUBSANACION_NOTIFICACION = "subsanacion_notificacion"
const val PAPELETA = "papeleta"
const val ANULACION_PAPELETA = "anulacion_papeleta"
const val DESCARGO_PAPELETA = "descargo_papeleta"
const val RESOLUCION_GERENCIA = "resolucion_gerencia"
const val NOTIFICACION_RESOLUCION = "notificacion_resolucion"

// the only familia srtm has (decision 6): rentas' TRANSITO is not brought
const val ADMINISTRATIVA = "ADMINISTRATIVA"

// resolucion_gerencia.tipo: the RIS that sanctions an acta, or the one that resolves a recurso
const val RESOLUCION_ADMINISTRATIVA = "ADMINISTRATIVA"
const val RESOLUCION_RECURSO = "RECURSO"

// the results of a notificación that take effect
val SURTEN_EFECTO = setOf("NOTIFICADO", "RECHAZADO")

// the records of the sanciones (model/model.json). a relation is its target's id; an enum, its option

// the CUIS, versioned by vigencia: a change closes the version in force and adds the new one
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CodigoInfraccion(
    val id: String? = null,
    val familia: String? = null,
    val codigo: String? = null,
    val descripcion: String? = null,
    val materia: String? = null,
    val porcentajeUit: BigDecimal? = null,
    val porcentajeUitSegunda: BigDecimal? = null,
    val porcentajeUitTercera: BigDecimal? = null,
    val medidaComplementaria: String? = null,
    val baseLegal: String? = null,
    val vigenciaDesde: LocalDate? = null,
    val vigenciaHasta: LocalDate? = null,
    val observacion: String? = null,
    val clave: String? = null,
    val claveVigente: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NotificacionAdministrativa(
    val id: String? = null,
    val numero: String? = null,
    val fecha: LocalDate? = null,
    val direccion: String? = null,
    val motivo: String? = null,
    val plazoDias: Int? = null,
    val observacion: String? = null,
    val contribuyente: String? = null,
    val predio: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SubsanacionNotificacion(
    val id: String? = null,
    val fecha: LocalDate? = null,
    val observacion: String? = null,
    val clave: String? = null,
    val notificacion: String? = null
)

// the acta, with its multa calculated once and frozen
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Papeleta(
    val id: String? = null,
    val familia: String? = null,
    val numero: String? = null,
    val clave: String? = null,
    val fechaInfraccion: LocalDate? = null,
    val horaInfraccion: String? = null,
    val lugar: String? = null,
    val expediente: String? = null,
    val inspector: String? = null,
    val descripcionHecho: String? = null,
    val reincidencia: String? = null,
    val medidaComplementaria: String? = null,
    val baseImponible: BigDecimal? = null,
    val porcentajeInfraccion: BigDecimal? = null,
    val importeInfraccion: BigDecimal? = null,
    @param:JsonProperty("porcentaje_a_cobrar") @get:JsonProperty("porcentaje_a_cobrar") val porcentajeACobrar: BigDecimal? = null,
    @param:JsonProperty("importe_a_pagar") @get:JsonProperty("importe_a_pagar") val importeAPagar: BigDecimal? = null,
    val importeConBeneficio: BigDecimal? = null,
    val fechaCalculo: LocalDate? = null,
    val observacion: String? = null,
    val codigoInfraccion: String? = null,
    val uit: String? = null,
    val obligado: String? = null,
    val contribuyente: String? = null,
    val predio: String? = null,
    val notificacionPrevia: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnulacionPapeleta(
    val id: String? = null,
    val fecha: LocalDate? = null,
    val motivo: String? = null,
    val observacion: String? = null,
    val clave: String? = null,
    val papeleta: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DescargoPapeleta(
    val id: String? = null,
    val numeroExpediente: String? = null,
    val tipoRecurso: String? = null,
    val fecha: LocalDate? = null,
    val presentadoHasta: LocalDate? = null,
    val enPlazo: Boolean? = null,
    val plazoTexto: String? = null,
    val sustento: String? = null,
    val observacion: String? = null,
    val papeleta: String? = null,
    val plazo: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ResolucionGerencia(
    val id: String? = null,
    val tipo: String? = null,
    val anio: Int? = null,
    val correlativo: Int? = null,
    val numero: String? = null,
    val fecha: LocalDate? = null,
    val sentido: String? = null,
    val efecto: String? = null,
    val sancionAccesoria: String? = null,
    val sustento: String? = null,
    val plazoTexto: String? = null,
    val claveRis: String? = null,
    val claveDescargo: String? = null,
    val observacion: String? = null,
    val papeleta: String? = null,
    val descargo: String? = null,
    val plazo: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NotificacionResolucion(
    val id: String? = null,
    val intento: Int? = null,
    val clave: String? = null,
    val fechaDiligencia: LocalDate? = null,
    val modalidad: String? = null,
    val resultado: String? = null,
    val notificador: String? = null,
    val direccion: String? = null,
    val receptor: String? = null,
    val documentoReceptor: String? = null,
    val vinculo: String? = null,
    val acuse: String? = null,
    val exigibleDesde: LocalDate? = null,
    val plazoTexto: String? = null,
    val observacion: String? = null,
    val resolucion: String? = null,
    val plazo: String? = null
)

// the unique keys: core has no composite unique, so a unique field holds the parts joined

// one version per codigo and start day
fun claveDeCodigo(
    familia: String,
    codigo: String,
    desde: LocalDate
) = "$familia|$codigo|$desde"

// rentas' codigo_infraccion_vigente_uq: one version in force per codigo. empty (never "") once closed
fun claveVigenteDe(
    familia: String,
    codigo: String
) = "$familia|$codigo"

fun claveDePapeleta(
    familia: String,
    numero: String
) = "$familia|$numero"

// an acta's stable reference, derived from its id: not stored
fun referenciaDePapeleta(id: String) = "PAPELETA-$id"

// RIS-AAAA-NNNNNN (ADMINISTRATIVA) or RGR-AAAA-NNNNNN (RECURSO)
fun numeroDeResolucion(
    tipo: String,
    anio: Int,
    correlativo: Int
) = "%s-%04d-%06d".format(if (tipo == RESOLUCION_RECURSO) "RGR" else "RIS", anio, correlativo)

fun claveDeNotificacionResolucion(
    resolucion: String,
    intento: Int
) = "$resolucion|$intento"

// lengths of rentas' columns: core's TEXT has none
object Largos {
    const val CODIGO = 20
    const val DESCRIPCION = 500
    const val MATERIA = 60
    const val MEDIDA = 160
    const val BASE_LEGAL = 200
    const val NUMERO = 20
    const val DIRECCION = 300
    const val MOTIVO = 500
    const val PLAZO_DIAS = 32767
    const val EXPEDIENTE = 20
    const val INSPECTOR = 120
    const val HECHO = 1000
    const val SUSTENTO = 1000
    const val SANCION_ACCESORIA = 200
    const val PLAZO_TEXTO = 60
    const val NOTIFICADOR = 60
    const val RECEPTOR = 120
    const val DOCUMENTO = 20
    const val VINCULO = 40
    const val ACUSE = 80
}

private val HORA = Regex("""([01]\d|2[0-3]):[0-5]\d""")

// what each record must be to be written, empty when it is: rentas' CHECKs, and the shape srtm's services always give
// them. ReglaDeSanciones refuses the ones that come from anywhere else

fun invariantes(c: CodigoInfraccion): List<FieldViolation> {
    val v = Violaciones()
    v.requerido("familia", c.familia)
    v.codigo("codigo", c.codigo, Largos.CODIGO)
    v.texto("descripcion", c.descripcion, Largos.DESCRIPCION, obligatorio = true)
    v.texto("materia", c.materia, Largos.MATERIA)
    v.porcentaje("porcentaje_uit", c.porcentajeUit, obligatorio = true)
    v.porcentaje("porcentaje_uit_segunda", c.porcentajeUitSegunda)
    v.porcentaje("porcentaje_uit_tercera", c.porcentajeUitTercera)
    v.texto("medida_complementaria", c.medidaComplementaria, Largos.MEDIDA)
    v.texto("base_legal", c.baseLegal, Largos.BASE_LEGAL, obligatorio = true)
    v.requerido("vigencia_desde", c.vigenciaDesde)
    if (c.vigenciaDesde != null && c.vigenciaHasta != null && c.vigenciaHasta < c.vigenciaDesde) {
        v.mal("vigencia_hasta", "no es anterior a vigencia_desde")
    }
    v.observacion(c.observacion)
    if (c.familia != null && c.codigo != null && c.vigenciaDesde != null) {
        v.clave("clave", c.clave, claveDeCodigo(c.familia, c.codigo, c.vigenciaDesde), "familia|codigo|vigencia_desde")
        val vigente = if (c.vigenciaHasta == null) claveVigenteDe(c.familia, c.codigo) else null
        if (c.claveVigente != vigente) {
            v.mal("clave_vigente", "es familia|codigo mientras rige, y vacía (null) una vez cerrada")
        }
    }
    return v.todas
}

fun invariantes(n: NotificacionAdministrativa): List<FieldViolation> {
    val v = Violaciones()
    v.codigo("numero", n.numero, Largos.NUMERO)
    v.requerido("fecha", n.fecha)
    v.texto("direccion", n.direccion, Largos.DIRECCION, obligatorio = true)
    v.texto("motivo", n.motivo, Largos.MOTIVO, obligatorio = true)
    if (n.plazoDias != null && n.plazoDias !in 1..Largos.PLAZO_DIAS) v.mal("plazo_dias", "de 1 a ${Largos.PLAZO_DIAS} días, o ninguno")
    v.observacion(n.observacion)
    return v.todas
}

fun invariantes(s: SubsanacionNotificacion): List<FieldViolation> {
    val v = Violaciones()
    v.requerido("fecha", s.fecha)
    v.observacion(s.observacion)
    if (v.requerido("notificacion", s.notificacion)) v.clave("clave", s.clave, s.notificacion, "el id de la notificación")
    return v.todas
}

fun invariantes(p: Papeleta): List<FieldViolation> {
    val v = Violaciones()
    v.requerido("familia", p.familia)
    v.codigo("numero", p.numero, Largos.NUMERO)
    if (p.familia != null && p.numero != null) v.clave("clave", p.clave, claveDePapeleta(p.familia, p.numero), "familia|numero")
    v.requerido("fecha_infraccion", p.fechaInfraccion)
    if (p.horaInfraccion != null && !HORA.matches(p.horaInfraccion)) v.mal("hora_infraccion", "es HH:mm")
    v.texto("lugar", p.lugar, Largos.DIRECCION, obligatorio = true)
    v.texto("expediente", p.expediente, Largos.EXPEDIENTE)
    v.texto("inspector", p.inspector, Largos.INSPECTOR)
    v.texto("descripcion_hecho", p.descripcionHecho, Largos.HECHO)
    v.requerido("reincidencia", p.reincidencia)
    v.texto("medida_complementaria", p.medidaComplementaria, Largos.MEDIDA)
    if (v.requerido("base_imponible", p.baseImponible) && p.baseImponible!!.signum() <= 0) v.mal("base_imponible", "la UIT es mayor que 0")
    v.porcentaje("porcentaje_infraccion", p.porcentajeInfraccion, obligatorio = true)
    v.importe("importe_infraccion", p.importeInfraccion)
    v.porcentaje("porcentaje_a_cobrar", p.porcentajeACobrar, obligatorio = true)
    v.importe("importe_a_pagar", p.importeAPagar)
    v.importe("importe_con_beneficio", p.importeConBeneficio, obligatorio = false)
    v.requerido("fecha_calculo", p.fechaCalculo)
    v.observacion(p.observacion)
    v.requerido("codigo_infraccion", p.codigoInfraccion)
    v.requerido("uit", p.uit)
    v.requerido("obligado", p.obligado)
    // rentas' papeleta_familia_ck: an acta names a contribuyente or a predio, at least one
    if (p.contribuyente.isNullOrBlank() && p.predio.isNullOrBlank()) v.mal("contribuyente", "un acta nombra un contribuyente o un predio")
    return v.todas
}

fun invariantes(a: AnulacionPapeleta): List<FieldViolation> {
    val v = Violaciones()
    v.requerido("fecha", a.fecha)
    v.texto("motivo", a.motivo, Largos.MOTIVO, obligatorio = true)
    v.observacion(a.observacion)
    if (v.requerido("papeleta", a.papeleta)) v.clave("clave", a.clave, a.papeleta, "el id del acta")
    return v.todas
}

fun invariantes(d: DescargoPapeleta): List<FieldViolation> {
    val v = Violaciones()
    v.codigo("numero_expediente", d.numeroExpediente, Largos.EXPEDIENTE)
    v.requerido("tipo_recurso", d.tipoRecurso)
    v.requerido("fecha", d.fecha)
    v.requerido("presentado_hasta", d.presentadoHasta)
    // rentas' descargo_plazo_ck
    if (v.requerido("en_plazo", d.enPlazo) && d.fecha != null && d.presentadoHasta != null && d.enPlazo != d.fecha <= d.presentadoHasta) {
        v.mal("en_plazo", "es fecha <= presentado_hasta")
    }
    v.texto("plazo_texto", d.plazoTexto, Largos.PLAZO_TEXTO, obligatorio = true)
    v.texto("sustento", d.sustento, Largos.SUSTENTO, obligatorio = true)
    v.observacion(d.observacion)
    v.requerido("papeleta", d.papeleta)
    v.requerido("plazo", d.plazo)
    return v.todas
}

fun invariantes(r: ResolucionGerencia): List<FieldViolation> {
    val v = Violaciones()
    v.requerido("tipo", r.tipo)
    v.requerido("anio", r.anio)
    if (v.requerido("correlativo", r.correlativo) && r.correlativo!! < 1) v.mal("correlativo", "empieza en 1")
    if (r.tipo != null && r.anio != null && r.correlativo != null) {
        v.clave("numero", r.numero, numeroDeResolucion(r.tipo, r.anio, r.correlativo), "RIS-AAAA-NNNNNN o RGR-AAAA-NNNNNN, con su anio y correlativo")
    }
    v.requerido("fecha", r.fecha)
    v.texto("sancion_accesoria", r.sancionAccesoria, Largos.SANCION_ACCESORIA)
    v.texto("sustento", r.sustento, Largos.SUSTENTO, obligatorio = true)
    v.texto("plazo_texto", r.plazoTexto, Largos.PLAZO_TEXTO, obligatorio = true)
    v.observacion(r.observacion)
    v.requerido("plazo", r.plazo)
    if (v.requerido("papeleta", r.papeleta) && r.tipo != null) {
        // one RIS per acta; a RECURSO has none
        v.clave("clave_ris", r.claveRis, r.papeleta.takeIf { r.tipo == RESOLUCION_ADMINISTRATIVA }, "el id del acta en una ADMINISTRATIVA")
        if (r.tipo != RESOLUCION_ADMINISTRATIVA && r.claveRis != null) v.mal("clave_ris", "solo la lleva una ADMINISTRATIVA")
    }
    // one per descargo
    v.clave("clave_descargo", r.claveDescargo, r.descargo, "el id del descargo")
    if (r.descargo == null && r.claveDescargo != null) v.mal("clave_descargo", "va con el descargo")
    // rentas' _recurso_ck and _fallo_ck
    if (r.tipo == RESOLUCION_RECURSO && r.descargo == null) v.mal("descargo", "una resolución RECURSO resuelve un descargo")
    if ((r.descargo != null) != (r.sentido != null && r.efecto != null)) v.mal("sentido", "sentido y efecto van con el descargo, y solo con él")
    return v.todas
}

fun invariantes(n: NotificacionResolucion): List<FieldViolation> {
    val v = Violaciones()
    if (v.requerido("intento", n.intento) && n.intento!! < 1) v.mal("intento", "empieza en 1")
    if (v.requerido("resolucion", n.resolucion) && n.intento != null) {
        v.clave("clave", n.clave, claveDeNotificacionResolucion(n.resolucion!!, n.intento), "resolucion|intento")
    }
    v.requerido("fecha_diligencia", n.fechaDiligencia)
    v.requerido("modalidad", n.modalidad)
    v.requerido("resultado", n.resultado)
    v.texto("notificador", n.notificador, Largos.NOTIFICADOR, obligatorio = true)
    v.texto("direccion", n.direccion, Largos.DIRECCION, obligatorio = true)
    v.texto("receptor", n.receptor, Largos.RECEPTOR)
    v.texto("documento_receptor", n.documentoReceptor, Largos.DOCUMENTO)
    v.texto("vinculo", n.vinculo, Largos.VINCULO)
    v.texto("acuse", n.acuse, Largos.ACUSE)
    v.texto("plazo_texto", n.plazoTexto, Largos.PLAZO_TEXTO)
    v.observacion(n.observacion)
    // rentas' notificacion_exigibilidad_ck
    if (n.resultado != null && (n.resultado in SURTEN_EFECTO) != (n.exigibleDesde != null && n.plazo != null)) {
        v.mal("exigible_desde", "la notificación que surte efecto (NOTIFICADO o RECHAZADO) lleva exigible_desde y plazo, y solo ella")
    }
    if (n.exigibleDesde != null && n.fechaDiligencia != null && n.exigibleDesde < n.fechaDiligencia) {
        v.mal("exigible_desde", "no es anterior a la diligencia")
    }
    return v.todas
}
