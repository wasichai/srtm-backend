package srtm.sanciones

import srtm.emision.Cabecera
import srtm.emision.FECHA
import srtm.emision.documentoDe
import srtm.emision.nombreDe
import srtm.emision.numero
import srtm.emision.soles
import srtm.rentas.Contribuyente
import java.math.BigDecimal

// the paper of a resolución (decision 8): what templates/emision/resolucion.html prints, drawn from the frozen rows
// only (the resolución, its acta with the multa as computed then, the CUIS version that acta used, the descargo it
// resolves) and the obligado. nothing is computed again: printing a 1991 resolución in 2036 draws the same figures
// (rentas' ModeloDeLaResolucionDeGerencia). every value is text already: the template only lays it out. no considerando
// is invented: the municipality writes them (a marked placeholder until it does), and there is no digital signature
// (D-05)

data class HojaResolucion(
    val cabecera: Cabecera,
    // RESOLUCIÓN DE SANCIÓN (RIS) or RESOLUCIÓN DEL RECURSO (RGR)
    val titulo: String,
    val numero: String,
    // dd/MM/yyyy: the resolución's day, also the header's (a reprint carries the same date)
    val fecha: String,
    val acta: ActaDeLaHoja,
    val obligado: ObligadoDeLaHoja,
    // the descargo a RGR resolves (or a RIS weighs): null without one
    val recurso: RecursoDeLaHoja?,
    val sentido: String?,
    val efecto: String?,
    val sancionAccesoria: String?,
    val sustento: String,
    val considerandos: String,
    // "Plazo para impugnar: 15 DIAS_HABILES": the plazo_texto copied when it was dictated
    val plazo: String,
    // the norm, cited: no figure of it is in the code
    val basePlazo: String,
    val firmas: List<String>,
    val sinFirma: String,
    val pie: String
)

data class ActaDeLaHoja(
    val numero: String,
    val referencia: String,
    val fecha: String,
    val hora: String?,
    val lugar: String,
    val codigo: String,
    val descripcion: String,
    val baseLegal: String,
    val reincidencia: String,
    val medidaComplementaria: String?,
    // the multa as frozen in the acta: concepto -> valor
    val desglose: List<FilaDeLaHoja>,
    // "Multa calculada el dd/MM/yyyy"
    val calculo: String
)

data class FilaDeLaHoja(
    val concepto: String,
    val valor: String
)

data class ObligadoDeLaHoja(
    val nombre: String,
    val documento: String,
    val domicilio: String
)

data class RecursoDeLaHoja(
    val expediente: String,
    val tipo: String,
    val presentado: String,
    // «SÍ, el plazo vencía el …» or «NO, el plazo venció el …»
    val enPlazo: String
)

// the considerandos are the municipality's: until it gives them, the paper says so
const val CONSIDERANDOS = "Considerandos: los provee la municipalidad (decisión 8)"

// `obligado`: the acta's obligado, as read now (srtm keeps no history of domicilios: its domicilio fiscal is the one in
// force). `codigo`: the CUIS version the acta used (closed since, maybe: its texts do not change). `descargo`: the
// resolución's, or null
fun hojaResolucion(
    cabecera: Cabecera,
    resolucion: ResolucionGerencia,
    acta: Papeleta,
    codigo: CodigoInfraccion,
    obligado: Contribuyente,
    descargo: DescargoPapeleta?
): HojaResolucion {
    val recurso = resolucion.tipo == RESOLUCION_RECURSO
    val numero = resolucion.numero.orEmpty()
    return HojaResolucion(
        cabecera = cabecera,
        titulo = if (recurso) "RESOLUCIÓN DEL RECURSO — RGR" else "RESOLUCIÓN DE SANCIÓN — RIS",
        numero = numero,
        fecha = resolucion.fecha?.format(FECHA).orEmpty(),
        acta = actaDeLaHoja(acta, codigo),
        obligado =
            ObligadoDeLaHoja(
                nombre = nombreDe(obligado),
                documento = documentoDe(obligado),
                domicilio = domicilioFiscalDe(obligado) ?: "Sin domicilio fiscal registrado"
            ),
        recurso = descargo?.let(::recursoDeLaHoja),
        sentido = resolucion.sentido?.let { SENTIDOS[it] ?: it },
        efecto = resolucion.efecto?.let { EFECTOS[it] ?: it },
        sancionAccesoria = resolucion.sancionAccesoria?.trim()?.ifEmpty { null },
        sustento = resolucion.sustento.orEmpty(),
        considerandos = CONSIDERANDOS,
        plazo = "Plazo para impugnar: ${resolucion.plazoTexto.orEmpty()}",
        basePlazo = "Contado desde el día hábil siguiente a la notificación de esta resolución (art. 218.2 del TUO de la Ley 27444).",
        firmas = listOf("Gerente", "Secretario"),
        sinFirma = "Documento sin firma digital (D-05): se firma a mano.",
        pie = "$numero · Acta ${acta.numero.orEmpty()} · ${referenciaDePapeleta(acta.id.orEmpty())}"
    )
}

// a contribuyente's domicilio fiscal (the active fiscal domicilio, kept in step by ContribuyenteService, or the
// padrón's), or null when it has none
fun domicilioFiscalDe(c: Contribuyente): String? = c.domicilioFiscal?.trim()?.ifEmpty { null }

private fun actaDeLaHoja(
    p: Papeleta,
    codigo: CodigoInfraccion
) = ActaDeLaHoja(
    numero = p.numero.orEmpty(),
    referencia = referenciaDePapeleta(p.id.orEmpty()),
    fecha = p.fechaInfraccion?.format(FECHA).orEmpty(),
    hora = p.horaInfraccion,
    lugar = p.lugar.orEmpty(),
    codigo = codigo.codigo.orEmpty(),
    descripcion = codigo.descripcion.orEmpty(),
    baseLegal = codigo.baseLegal.orEmpty(),
    reincidencia = p.reincidencia?.let { Expedientes.GRADO[it] }?.replaceFirstChar { it.uppercase() } ?: p.reincidencia.orEmpty(),
    medidaComplementaria = p.medidaComplementaria?.trim()?.ifEmpty { null },
    desglose =
        listOfNotNull(
            FilaDeLaHoja("Base imponible (UIT)", soles(p.baseImponible)),
            FilaDeLaHoja("Porcentaje de la infracción", porcentaje(p.porcentajeInfraccion)),
            FilaDeLaHoja("Importe de la infracción", soles(p.importeInfraccion)),
            FilaDeLaHoja("Porcentaje a cobrar por la reincidencia", porcentaje(p.porcentajeACobrar)),
            FilaDeLaHoja("Importe a pagar", soles(p.importeAPagar)),
            p.importeConBeneficio?.let { FilaDeLaHoja("Importe con beneficio", soles(it)) }
        ),
    calculo = "Multa calculada el ${p.fechaCalculo?.format(FECHA).orEmpty()}, con la UIT y el CUIS vigentes el día de la infracción."
)

private fun recursoDeLaHoja(d: DescargoPapeleta): RecursoDeLaHoja {
    val hasta = d.presentadoHasta?.format(FECHA).orEmpty()
    return RecursoDeLaHoja(
        expediente = d.numeroExpediente.orEmpty(),
        tipo = d.tipoRecurso?.let { Expedientes.RECURSO[it] } ?: d.tipoRecurso.orEmpty(),
        presentado = d.fecha?.format(FECHA).orEmpty(),
        enPlazo = if (d.enPlazo == true) "SÍ, el plazo vencía el $hasta" else "NO, el plazo venció el $hasta"
    )
}

private fun porcentaje(valor: BigDecimal?) = "${numero(valor)} %"

private val SENTIDOS =
    mapOf(
        "FUNDADO" to "Fundado",
        "FUNDADO_EN_PARTE" to "Fundado en parte",
        "INFUNDADO" to "Infundado",
        "IMPROCEDENTE" to "Improcedente"
    )

private val EFECTOS =
    mapOf(
        SE_MANTIENE to "Se mantiene la multa",
        SE_DEJA_SIN_EFECTO to "Se deja sin efecto la multa",
        SE_REDUCE to "Se reduce la multa"
    )
