package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import srtm.sanciones.Ficticios.d
import srtm.sanciones.Ficticios.notificacion
import srtm.sanciones.Ficticios.resolucion

// rentas' inf-panel: actas levantadas, resoluciones dictadas, notificadas and the previas that end this week, counted
// at a day. «En coactiva» is not counted: srtm does not collect
class PanelTest {
    // a wednesday: its week runs from monday 2026-08-10 to sunday 2026-08-16
    private val alDia = d("2026-08-12")

    private fun acta(
        id: String,
        fecha: String
    ) = Papeleta(id = id, numero = "AC-$id", fechaInfraccion = d(fecha))

    private fun notificada(
        resolucion: String,
        resultado: String
    ) = NotificacionResolucion(id = "nr-$resolucion-$resultado", resultado = resultado, resolucion = resolucion)

    private fun vacio(
        actas: List<Papeleta> = emptyList(),
        resoluciones: List<ResolucionGerencia> = emptyList(),
        notificaciones: List<NotificacionResolucion> = emptyList(),
        previas: List<NotificacionAdministrativa> = emptyList(),
        subsanadas: Set<String> = emptySet(),
        conActa: Set<String> = emptySet()
    ) = HechosDelPanel(actas, resoluciones, notificaciones, previas, subsanadas, conActa)

    @Test
    fun `the week of a day runs from its monday to its sunday, both included`() {
        val semana = Semana(d("2026-08-10"), d("2026-08-16"))
        assertEquals(semana, Panel.semana(d("2026-08-10")))
        assertEquals(semana, Panel.semana(alDia))
        assertEquals(semana, Panel.semana(d("2026-08-16")))
        assertEquals(Semana(d("2026-08-17"), d("2026-08-23")), Panel.semana(d("2026-08-17")))
        assertEquals(Semana(d("2026-12-28"), d("2027-01-03")), Panel.semana(d("2027-01-01")), "a week may straddle two years")
    }

    @Test
    fun `the actas of the year are every one whose infraccion falls in it, whatever became of it`() {
        val panel =
            Panel.contar(
                2026,
                alDia,
                vacio(actas = listOf(acta("a", "2025-12-31"), acta("b", "2026-01-01"), acta("c", "2026-12-31"), acta("d", "2027-01-01")))
            )
        assertEquals(2, panel.actas)
        assertEquals(2026, panel.anio)
        assertEquals(alDia, panel.alDia)
    }

    @Test
    fun `the resoluciones are the RIS dictated in the year, and the notificadas those with a notificacion that takes effect`() {
        val ris = { n: Int, fecha: String -> resolucion(correlativo = n, acta = "a$n").copy(fecha = d(fecha)) }
        val resoluciones =
            listOf(
                ris(1, "2026-01-01"),
                ris(2, "2026-06-01"),
                ris(3, "2026-12-31"),
                ris(4, "2025-12-31"),
                resolucion(RESOLUCION_RECURSO, SE_MANTIENE, "desc-1", correlativo = 5)
            )
        val notificaciones =
            listOf(
                notificada("res-ADMINISTRATIVA-1", "NOTIFICADO"),
                notificada("res-ADMINISTRATIVA-1", "NO_UBICADO"),
                notificada("res-ADMINISTRATIVA-2", "RECHAZADO"),
                notificada("res-ADMINISTRATIVA-3", "NO_UBICADO"),
                notificada("res-ADMINISTRATIVA-4", "NOTIFICADO"),
                notificada("res-RECURSO-5", "NOTIFICADO")
            )
        val panel = Panel.contar(2026, alDia, vacio(resoluciones = resoluciones, notificaciones = notificaciones))
        assertEquals(3, panel.resoluciones, "the RGR resolves a recurso: it is not a resolución de sanción")
        assertEquals(2, panel.notificadas, "NO_UBICADO takes no effect")
    }

    @Test
    fun `vencen esta semana counts the previas ending monday to sunday, neither subsanadas nor with an acta`() {
        // fecha 2026-08-01: plazo 9 ends on monday 08-10, 15 on sunday 08-16
        val lunes = notificacion("NP-1", "2026-08-01", 9)
        val domingo = notificacion("NP-2", "2026-08-01", 15)
        val domingoAntes = notificacion("NP-3", "2026-08-01", 8)
        val lunesDespues = notificacion("NP-4", "2026-08-01", 16)
        val sinPlazo = notificacion("NP-5", "2026-08-10", null)
        val subsanada = notificacion("NP-6", "2026-08-01", 12)
        val conActa = notificacion("NP-7", "2026-08-01", 12)
        val deOtroAnio = notificacion("NP-8", "2025-12-01", 258)
        val panel =
            Panel.contar(
                2025,
                alDia,
                vacio(
                    previas = listOf(lunes, domingo, domingoAntes, lunesDespues, sinPlazo, subsanada, conActa, deOtroAnio),
                    subsanadas = setOf(subsanada.id!!),
                    conActa = setOf(conActa.id!!)
                )
            )
        assertEquals(d("2026-08-16"), Notificaciones.vencimiento(deOtroAnio.fecha!!, deOtroAnio.plazoDias))
        assertEquals(3, panel.vencenEstaSemana, "the week is al_dia's, whatever the anio asked")
        assertEquals(Semana(d("2026-08-10"), d("2026-08-16")), panel.semana)
    }

    @Test
    fun `there is no coactiva in srtm, and the nota says so`() {
        val panel = Panel.contar(2026, alDia, vacio())
        assertNull(panel.coactiva)
        assertEquals("En coactiva no existe en srtm: no hay cobranza", panel.nota)
        assertEquals(listOf(0, 0, 0, 0), listOf(panel.actas, panel.resoluciones, panel.notificadas, panel.vencenEstaSemana))
    }

    @Test
    fun `a RIS of an acta anulada or dejada sin efecto is dictated but has nothing left notified`() {
        val ris = { n: Int -> resolucion(correlativo = n, acta = "a$n").copy(fecha = d("2026-03-02")) }
        val resoluciones = listOf(ris(1), ris(2), ris(3))
        val notificaciones = resoluciones.map { notificada(it.id!!, "NOTIFICADO") }
        val panel =
            Panel.contar(2026, alDia, vacio(resoluciones = resoluciones, notificaciones = notificaciones).copy(sinEfecto = setOf("a1", "a2")))
        assertEquals(3, panel.resoluciones, "the RIS were dictated: they all count")
        assertEquals(1, panel.notificadas)
    }
}
