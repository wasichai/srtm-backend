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
    val total: String,
    // one line per (secuencia, servicio, fórmula) distinct of the year, with its month range (calculosDe); empty
    // for a predio whose cuotas were written before the desglose (base null) - the HLA looks as before this PR
    val calculos: List<String>
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

private val MESES_ABREVIADOS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

// a predio's calculo lines: one per (secuencia, servicio, fórmula) distinct, with the month range it ruled in
// («ene–dic», or a single month alone, e.g. «mar»). per servicio (its filas' own order), the months are walked in
// order and grouped by (secuencia, fórmula); when the same group's months are not contiguous (the tasa changed and
// came back, say), each contiguous run gets its own line. a cuota written before the desglose (base null) has
// nothing to show and is skipped: a predio with none of those has no calculos, same as today
private fun calculosDe(m: MatrizArbitrios): List<String> {
    val lineas = mutableListOf<String>()
    for (f in m.filas) {
        val mesesPorGrupo = linkedMapOf<Pair<String, String>, MutableList<Int>>()
        for (periodo in Periodo.MESES) {
            val cuota = f.meses[periodo - 1] ?: continue
            for (d in cuota.desglose) {
                if (d.base == null) continue
                val grupo = (d.secuenciaUso ?: "") to d.formula
                mesesPorGrupo.getOrPut(grupo) { mutableListOf() }.add(periodo)
            }
        }
        val nombreServicio = f.servicio.nombre ?: f.servicio.codigo.orEmpty()
        for ((grupo, meses) in mesesPorGrupo) {
            val (secuencia, formula) = grupo
            for (rango in rangosContiguos(meses)) {
                lineas += "$nombreServicio (uso $secuencia), ${rangoDeMeses(rango)}: $formula"
            }
        }
    }
    return lineas
}

// periodos (ascending, distinct) cut into its contiguous runs: [1,2,3,7,8] -> [[1,2,3],[7,8]]
private fun rangosContiguos(periodos: List<Int>): List<List<Int>> {
    val rangos = mutableListOf<MutableList<Int>>()
    for (p in periodos) {
        val ultimo = rangos.lastOrNull()
        if (ultimo != null && ultimo.last() == p - 1) ultimo.add(p) else rangos.add(mutableListOf(p))
    }
    return rangos
}

// a contiguous run of months, as the HLA prints it: the abbreviation alone when it is one month, both ends joined
// by an en dash otherwise («ene–dic»)
private fun rangoDeMeses(periodos: List<Int>): String {
    val desde = MESES_ABREVIADOS[periodos.first() - 1]
    val hasta = MESES_ABREVIADOS[periodos.last() - 1]
    return if (periodos.first() == periodos.last()) desde else "$desde–$hasta"
}

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
                    total = soles(m.total),
                    calculos = calculosDe(m)
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
