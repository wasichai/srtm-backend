package srtm.emision

import srtm.impuesto.Liquidacion
import srtm.rentas.Contribuyente
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import java.math.BigDecimal
import java.time.LocalDateTime

// the HR (hoja de resumen): what templates/emision/hr.html prints of a contribuyente in a year. its predios (a row per
// vigente declaración, so a predio with two usos has two) and the determinación of the impuesto predial with its
// cuotas, as the liquidación computed them. every value is text already: the template only lays it out

data class HojaHr(
    val cabecera: Cabecera,
    val anio: Int,
    // dd/MM/yyyy HH:mm, Lima's time
    val emitido: String,
    val contribuyente: ContribuyenteHr,
    val predios: List<FilaPredioHr>,
    val totales: TotalesHr,
    val impuesto: ImpuestoHr,
    val cuotas: List<FilaCuota>,
    // "Al contado: S/ … hasta el <vencimiento de la cuota 1>"
    val contado: String
)

data class ContribuyenteHr(
    val codigo: String,
    val nombre: String,
    val documento: String,
    // the descripción and its distrito / provincia / departamento
    val domicilioFiscal: String,
    // the condiciones especiales (pensionista…) its declaraciones state, if any
    val condicionEspecial: String
)

data class FilaPredioHr(
    val codigo: String,
    val direccion: String,
    val uso: String,
    val autoavaluo: String,
    val porcentaje: String,
    val valorAfecto: String
)

data class TotalesHr(
    val autoavaluo: String,
    val valorAfecto: String
)

data class ImpuestoHr(
    val uit: String,
    val base: String,
    val tramos: List<FilaTramo>,
    val calculado: String,
    val minimo: String,
    val minimoAplicado: Boolean,
    val anual: String
)

data class FilaTramo(
    val tramo: String,
    val desde: String,
    // "en adelante" in the last, open one
    val hasta: String,
    val alicuota: String,
    val monto: String,
    val impuesto: String
)

data class FilaCuota(
    val numero: String,
    val monto: String,
    val vencimiento: String
)

// `liquidacion` must be complete (no faltan): the caller answers 422 before. `predios` by id, the declaraciones'
fun hojaHr(
    cabecera: Cabecera,
    anio: Int,
    contribuyente: Contribuyente,
    predios: Map<String, Predio>,
    declaraciones: List<Declaracion>,
    liquidacion: Liquidacion,
    ahora: LocalDateTime
): HojaHr {
    require(liquidacion.faltan.isEmpty()) { "liquidación incompleta: faltan ${liquidacion.faltan}" }
    val filas =
        declaraciones
            .sortedWith(compareBy({ predios[it.predio]?.codigo.orEmpty() }, { it.secuenciaUso.orEmpty() }))
            .map { d ->
                val predio = predios[d.predio]
                FilaPredioHr(
                    codigo = predio?.codigo.orEmpty(),
                    direccion = predio?.direccion.orEmpty(),
                    uso = unir(" / ", d.claseUso, d.subClaseUso, d.uso),
                    autoavaluo = soles(d.valorAutoavaluo),
                    porcentaje = d.porcentajeCondominio?.let { "${numero(it)} %" }.orEmpty(),
                    valorAfecto = soles(d.valorAfecto)
                )
            }
    val cuotas = liquidacion.cuotas.map { FilaCuota(it.numero.toString(), soles(it.monto), it.vencimiento.format(FECHA)) }
    return HojaHr(
        cabecera = cabecera,
        anio = anio,
        emitido = ahora.format(FECHA_HORA),
        contribuyente =
            ContribuyenteHr(
                codigo = contribuyente.codigo.orEmpty(),
                nombre = nombreDe(contribuyente),
                documento = documentoDe(contribuyente),
                domicilioFiscal =
                    unir(
                        " — ",
                        contribuyente.domicilioFiscal,
                        unir(" / ", contribuyente.domicilioDistrito, contribuyente.domicilioProvincia, contribuyente.domicilioDepartamento)
                    ),
                condicionEspecial = declaraciones.mapNotNull { it.condicionEspecial?.trim()?.ifEmpty { null } }.distinct().joinToString(", ")
            ),
        predios = filas,
        totales =
            TotalesHr(
                autoavaluo = soles(declaraciones.sumOf { it.valorAutoavaluo ?: BigDecimal.ZERO }),
                // the liquidación's base: the same sum of valor afecto
                valorAfecto = soles(liquidacion.base)
            ),
        impuesto =
            ImpuestoHr(
                uit = soles(liquidacion.uit),
                base = soles(liquidacion.base),
                tramos =
                    liquidacion.tramos.map {
                        FilaTramo(
                            tramo = it.tramo.toString(),
                            desde = soles(it.desde),
                            hasta = it.hasta?.let(::soles) ?: "en adelante",
                            alicuota = "${it.alicuota.stripTrailingZeros().toPlainString()} %",
                            monto = soles(it.monto),
                            impuesto = soles(it.impuesto)
                        )
                    },
                calculado = soles(liquidacion.impuestoCalculado),
                minimo = soles(liquidacion.minimo),
                minimoAplicado = liquidacion.minimoAplicado == true,
                anual = soles(liquidacion.impuestoAnual)
            ),
        cuotas = cuotas,
        contado = "Al contado: ${soles(liquidacion.impuestoAnual)} hasta el ${cuotas.firstOrNull()?.vencimiento.orEmpty()}"
    )
}
