package srtm.arbitrios

import srtm.emision.Cabecera
import srtm.emision.ContribuyenteHr
import srtm.emision.FECHA
import srtm.emision.FECHA_HORA
import srtm.emision.contribuyenteHr
import srtm.emision.soles
import srtm.rentas.Contribuyente
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

// the HLA (hoja de liquidación de arbitrios): what templates/emision/hla.html prints of a contribuyente in a year. its
// cuotas by predio and month, as they were determined (the cuotas charged to it), the monthly total it owes with its
// due date, and the ordinance they come from. every value is text already: the template only lays it out

data class HojaHla(
    val cabecera: Cabecera,
    val anio: Int,
    // dd/MM/yyyy HH:mm, Lima's time
    val emitido: String,
    val contribuyente: ContribuyenteHr,
    // "Ordenanza N.° …, ratificada por …"
    val ordenanza: String,
    // the servicios' names, the columns of every predio's table
    val servicios: List<String>,
    val predios: List<PredioHla>,
    // the month's total over every predio, with its due date
    val cuotas: List<FilaCuotaHla>,
    val total: String,
    // the date of the latest cuota: the figures are the ones determined then
    val fechaCalculo: String
)

data class PredioHla(
    val codigo: String,
    val direccion: String,
    // the zonas and usos de arbitrio its cuotas were determined with
    val zona: String,
    val uso: String,
    val meses: List<MesHla>,
    // by servicio, in the columns' order
    val totales: List<String>,
    val total: String
)

data class MesHla(
    val mes: String,
    // by servicio, in the columns' order; "—" where there is no cuota
    val montos: List<String>,
    val total: String
)

data class FilaCuotaHla(
    val mes: String,
    val monto: String,
    val vencimiento: String
)

private val MESES =
    listOf("Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre")

fun nombreDelMes(periodo: Int) = MESES[periodo - 1]

// `arbitrios` with every month's due date in `vencimientos` (month -> date): the caller answers 422 before when one
// is missing. only the months with a cuota are printed
fun hojaHla(
    cabecera: Cabecera,
    contribuyente: Contribuyente,
    arbitrios: ArbitriosContribuyente,
    ordenanza: OrdenanzaArbitrio,
    vencimientos: Map<Int, LocalDate>,
    zonasYUsos: Map<String, Pair<List<String>, List<String>>>,
    ahora: LocalDateTime
): HojaHla {
    val columnas = arbitrios.predios.flatMap { m -> m.filas.map { it.servicio } }.distinctBy { it.id }
    val predios =
        arbitrios.predios
            .filter { m -> m.total.signum() != 0 || m.filas.any { f -> f.meses.any { it != null } } }
            .map { m ->
                val porServicio = m.filas.associateBy { it.servicio.id }
                val meses =
                    Periodo.MESES.filter { p -> m.filas.any { it.meses[p - 1] != null } }.map { p ->
                        MesHla(
                            nombreDelMes(p),
                            columnas.map { s -> porServicio[s.id]?.meses?.get(p - 1)?.let { soles(it.monto) } ?: "—" },
                            soles(m.totalesPorMes[p - 1])
                        )
                    }
                val (zonas, usos) = zonasYUsos[m.predio.id] ?: (emptyList<String>() to emptyList())
                PredioHla(
                    codigo = m.predio.codigo.orEmpty(),
                    direccion = m.predio.direccion.orEmpty(),
                    zona = zonas.joinToString(" / "),
                    uso = usos.joinToString(" / "),
                    meses = meses,
                    totales = columnas.map { s -> soles(porServicio[s.id]?.total ?: BigDecimal.ZERO) },
                    total = soles(m.total)
                )
            }
    val porMes = Periodo.MESES.associateWith { p -> arbitrios.predios.fold(BigDecimal.ZERO) { a, m -> a + m.totalesPorMes[p - 1] } }
    val cuotas =
        porMes
            .filterValues { it.signum() != 0 }
            .map { (p, monto) -> FilaCuotaHla(nombreDelMes(p), soles(monto), vencimientos.getValue(p).format(FECHA)) }
    return HojaHla(
        cabecera = cabecera,
        anio = arbitrios.anio,
        emitido = ahora.format(FECHA_HORA),
        contribuyente = contribuyenteHr(contribuyente, emptyList()),
        ordenanza = ordenanzaDe(ordenanza),
        servicios = columnas.map { it.nombre ?: it.codigo.orEmpty() },
        predios = predios,
        cuotas = cuotas,
        total = soles(arbitrios.total),
        fechaCalculo = arbitrios.fechaCalculo?.format(FECHA).orEmpty()
    )
}

// the ordinance as the HLA cites it: its number, and the provincial's ratification
fun ordenanzaDe(o: OrdenanzaArbitrio): String {
    val ratificacion =
        listOfNotNull(
            o.acuerdoRatificacion?.trim()?.ifEmpty { null },
            o.municipalidadRatificante
                ?.trim()
                ?.ifEmpty { null }
                ?.let { "de la $it" },
            o.fechaRatificacion?.let { "del ${it.format(FECHA)}" }
        ).joinToString(" ")
    return "Ordenanza N.° ${o.numero.orEmpty()}" + (if (ratificacion.isEmpty()) "" else ", ratificada por $ratificacion")
}
