package srtm.arbitrios

import srtm.rentas.Declaracion
import srtm.rentas.UsoPredio
import srtm.rentas.vigente
import java.math.BigDecimal
import java.time.LocalDate

// the declarations are annual and the arbitrio monthly. a declaration of the year counts on a day that falls between
// its start and its end:
// - start: 1 january, or the day after its fecha_adquisicion when that falls in the year (one acquired later counts on
//   no day);
// - end: 31 december, or its fecha_anulacion when it was annulled (descargo; one annulled without its date counts on
//   no day).
// an imported one without dates covers the year. so a sale on 20 may: the seller (annulled on the 20th) january to
// may, the buyer (acquired on the 20th) june to december
fun cubre(
    d: Declaracion,
    dia: LocalDate
): Boolean {
    val anio = d.anio ?: return false
    if (dia.year != anio) return false
    val adquisicion = d.fechaAdquisicion
    if (adquisicion != null && adquisicion.year > anio) return false
    val inicio = adquisicion?.takeIf { it.year == anio }?.plusDays(1) ?: LocalDate.of(anio, 1, 1)
    val fin = if (vigente(d)) LocalDate.of(anio, 12, 31) else d.fechaAnulacion ?: return false
    return !dia.isBefore(inicio) && !dia.isAfter(fin)
}

// rentas' titular principal: the arbitrio is charged whole to one titular, the one with the largest share. ties go to
// the earliest acquisition, then to the lowest contribuyente code (then its id), then to the lowest secuencia de uso:
// a total order, so two runs never charge different people. the winning declaration also gives the predio's uso that
// day: with several secuencias, the first. codigos: each contribuyente's code by id
fun titularPrincipal(
    declaraciones: List<Declaracion>,
    codigos: Map<String, String?>
): Declaracion? =
    declaraciones.minWithOrNull(
        compareByDescending<Declaracion> { it.porcentajeCondominio ?: BigDecimal.ZERO }
            .thenBy(nullsLast()) { it.fechaAdquisicion }
            .thenBy(nullsLast()) { it.contribuyente?.let { id -> codigos[id] } }
            .thenBy(nullsLast()) { it.contribuyente }
            .thenBy(nullsLast()) { it.secuenciaUso }
            .thenBy(nullsLast()) { it.id }
    )

// the uso_predio code of a declaration's uso, as precise as the declaration: XXYYZZ with clase, sub clase and uso,
// XXYY with clase and sub clase, XX with the clase alone (the padrón's other groups). null without a clase, or when the
// catalog does not have it
fun codigoDeUso(
    d: Declaracion,
    usos: List<UsoPredio>
): String? {
    val clase = d.claseUso?.ifBlank { null } ?: return null
    val subClase = d.subClaseUso?.ifBlank { null }
    val uso = d.uso?.ifBlank { null }
    val deLaClase = usos.filter { it.clase == clase && it.codigo?.length == 6 }.sortedBy { it.codigo }
    return when {
        subClase != null && uso != null -> deLaClase.firstOrNull { it.subClase == subClase && it.uso == uso }?.codigo
        subClase != null -> deLaClase.firstOrNull { it.subClase == subClase }?.codigo?.take(4)
        else -> deLaClase.firstOrNull()?.codigo?.take(2)
    }
}
