package srtm.sanciones

import org.springframework.stereotype.Service
import srtm.rentas.Registros
import wasichai.core.common.PageRequest
import wasichai.core.data.RecordCriterion
import java.time.LocalDate

// the panel of the infracciones: reads what Panel.contar counts, one query per kind. core's RecordService checks the
// caller's read permission on each object, as in every query of the sanciones
@Service
class PanelService(
    private val registros: Registros,
    private val notificaciones: NotificacionesService,
    private val actas: ActasService
) {
    suspend fun panel(
        anio: Int,
        alDia: LocalDate
    ): PanelDeInfracciones {
        val enero = LocalDate.of(anio, 1, 1)
        val diciembre = LocalDate.of(anio, 12, 31)
        val delAnio = registros.donde(PAPELETA, Papeleta::class.java, listOfNotNull(Filtros.entre("fecha_infraccion", enero, diciembre)))
        val ris =
            registros.donde(
                RESOLUCION_GERENCIA,
                ResolucionGerencia::class.java,
                listOfNotNull(Filtros.entre("fecha", enero, diciembre)),
                filters = mapOf("tipo" to RESOLUCION_ADMINISTRATIVA)
            )
        val notificadas =
            ris.mapNotNull { it.id }.chunked(PageRequest.MAX_SIZE).flatMap {
                registros.donde(NOTIFICACION_RESOLUCION, NotificacionResolucion::class.java, listOf(Filtros.entre("resolucion", it)))
            }
        val previas =
            registros.donde(NOTIFICACION_ADMINISTRATIVA, NotificacionAdministrativa::class.java, listOf(vencenEntre(Panel.semana(alDia))))
        val hechos = notificaciones.hechos(previas)
        // the actas of the RIS (whatever their year), to leave out of notificadas those anuladas or sin efecto
        val actasDeLasRis = registros.byIds(PAPELETA, Papeleta::class.java, ris.mapNotNull { it.papeleta }).values.toList()
        val sinEfecto = actas.hechos(actasDeLasRis).filterValues { Procedimiento.estadoDeLaDeuda(it) != Procedimiento.PENDIENTE }.keys
        return Panel.contar(
            anio,
            alDia,
            HechosDelPanel(delAnio, ris, notificadas, previas, hechos.subsanaciones.keys, hechos.actas.keys, sinEfecto)
        )
    }

    // a notificación whose fecha + plazo_dias falls in the week: Panel.contar decides again with Notificaciones. core
    // stores an INTEGER as bigint, and postgres adds only an integer to a date (plazo_dias is at most 32767)
    private fun vencenEntre(semana: Semana) =
        RecordCriterion { definition, bind ->
            fun columna(campo: String) = "\"${definition.fields.first { it.name == campo }.columnName}\""
            val plazo = columna("plazo_dias")
            "$plazo IS NOT NULL AND ${columna("fecha")} + CAST($plazo AS integer) BETWEEN ${bind(semana.desde)} AND ${bind(semana.hasta)}"
        }
}
