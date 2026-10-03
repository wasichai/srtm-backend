package srtm.sanciones

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

// monday to sunday, both included
data class Semana(
    val desde: LocalDate,
    val hasta: LocalDate
)

// GET /infracciones/panel: the year's counts at al_dia (rentas' inf-panel). «En coactiva» has no figure here: srtm
// does not collect, so coactiva is always null and nota says why
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PanelDeInfracciones(
    val anio: Int,
    val alDia: LocalDate,
    val actas: Int,
    val resoluciones: Int,
    val notificadas: Int,
    val vencenEstaSemana: Int,
    val semana: Semana,
    val nota: String
) {
    val coactiva: Int? get() = null
}

// what the panel counts, as read: a superset is fine (contar keeps what belongs to the year and the week)
data class HechosDelPanel(
    val actas: List<Papeleta>,
    val resoluciones: List<ResolucionGerencia>,
    val notificaciones: List<NotificacionResolucion>,
    val previas: List<NotificacionAdministrativa>,
    // ids of the previas that are subsanadas, and of those an acta names
    val subsanadas: Set<String>,
    val conActa: Set<String>
)

// the panel of the infracciones: pure, the day as an argument
object Panel {
    const val NOTA = "En coactiva no existe en srtm: no hay cobranza"

    // the week (monday to sunday) of `dia`
    fun semana(dia: LocalDate): Semana {
        val lunes = dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return Semana(lunes, lunes.plusDays(6))
    }

    // actas: every acta whose infracción is in `anio`, anuladas and dejadas sin efecto included (they were levantadas);
    // resoluciones: the RIS (ADMINISTRATIVA; a RGR resolves a recurso, it does not sanction) dated in `anio`;
    // notificadas: those RIS with at least one notificación that takes effect (NOTIFICADO or RECHAZADO);
    // vencen_esta_semana: the previas neither subsanadas nor with an acta whose vencimiento (Notificaciones.vencimiento,
    // the one definition) falls in the week of `alDia`, whatever their year
    fun contar(
        anio: Int,
        alDia: LocalDate,
        hechos: HechosDelPanel
    ): PanelDeInfracciones {
        val semana = semana(alDia)
        val ris = hechos.resoluciones.filter { it.tipo == RESOLUCION_ADMINISTRATIVA && it.fecha!!.year == anio }
        val surtieronEfecto =
            hechos.notificaciones
                .filter { it.resultado in SURTEN_EFECTO }
                .mapNotNull { it.resolucion }
                .toSet()
        val vencen =
            hechos.previas.count { n ->
                n.id !in hechos.subsanadas &&
                    n.id !in hechos.conActa &&
                    Notificaciones.vencimiento(n.fecha!!, n.plazoDias)?.let { it >= semana.desde && it <= semana.hasta } == true
            }
        return PanelDeInfracciones(
            anio = anio,
            alDia = alDia,
            actas = hechos.actas.count { it.fechaInfraccion!!.year == anio },
            resoluciones = ris.size,
            notificadas = ris.count { it.id in surtieronEfecto },
            vencenEstaSemana = vencen,
            semana = semana,
            nota = NOTA
        )
    }
}
