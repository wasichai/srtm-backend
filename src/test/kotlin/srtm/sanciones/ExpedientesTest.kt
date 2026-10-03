package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import srtm.sanciones.Ficticios.anulacion
import srtm.sanciones.Ficticios.codigo
import srtm.sanciones.Ficticios.d
import srtm.sanciones.Ficticios.descargo
import srtm.sanciones.Ficticios.notificacion
import srtm.sanciones.Ficticios.resolucion
import srtm.sanciones.Ficticios.subsanacion

// rentas' inf-exp, «Actos del expediente»: the order is legal (previa, acta, descargos, resoluciones, their
// notificaciones, anulación), not by day, and each act says what it is on the day asked
class ExpedientesTest {
    private val acta =
        Papeleta(id = "acta", numero = "AC-0001", fechaInfraccion = d("2026-08-10"), reincidencia = SEGUNDA, codigoInfraccion = "cuis-A-042-2026-01-01")

    @Test
    fun `the acts go in legal order, numbered, each kind by day`() {
        val previa = notificacion("NP-0001", "2026-08-03", 5)
        val tardio = descargo(id = "d2").copy(fecha = d("2026-08-20"), enPlazo = false, presentadoHasta = d("2026-08-17"))
        val aTiempo = descargo(id = "d1").copy(enPlazo = true, presentadoHasta = d("2026-08-17"))
        val ris = resolucion(correlativo = 1)
        val notificaciones =
            listOf(
                NotificacionResolucion(id = "nr1", intento = 1, fechaDiligencia = d("2026-08-26"), resultado = "NO_UBICADO"),
                NotificacionResolucion(id = "nr2", intento = 2, fechaDiligencia = d("2026-08-28"), resultado = "NOTIFICADO", exigibleDesde = d("2026-09-19"))
            )
        val actos =
            Expedientes.actos(
                acta,
                codigo(),
                HechosDelActa(previa = previa, anulacion = anulacion(), resoluciones = listOf(ris)),
                listOf(aTiempo, tardio),
                listOf(ResolucionConNotificaciones(ris, notificaciones)),
                d("2026-09-30")
            )

        assertEquals((1..8).toList(), actos.map { it.orden })
        assertEquals(
            listOf(
                "Notificación previa",
                "Acta de constatación",
                "Descargo",
                "Descargo",
                "Resolución de sanción",
                "Notificación de la resolución",
                "Notificación de la resolución",
                "Anulación"
            ),
            actos.map { it.acto }
        )
        assertEquals(listOf("np-NP-0001", "acta", "d1", "d2", ris.id, "nr1", "nr2", "an-acta"), actos.map { it.id })
        assertEquals("Vencida el 2026-08-08", actos[0].detalle)
        assertEquals("Código A-042, reincidencia segunda vez", actos[1].detalle)
        assertEquals("En plazo (hasta el 2026-08-17)", actos[2].detalle)
        assertEquals("Fuera de plazo (venció el 2026-08-17)", actos[3].detalle)
        assertEquals("Notificada el 2026-08-28", actos[4].detalle)
        assertEquals("RIS-2026-000001 (intento 1)", actos[5].documento)
        assertEquals("NO_UBICADO", actos[5].detalle)
        assertEquals("NOTIFICADO, exigible desde el 2026-09-19", actos[6].detalle)
        assertEquals("Error material", actos[7].detalle)
    }

    @Test
    fun `without a previa nor anything after it, the acta alone`() {
        val actos = Expedientes.actos(acta, codigo(), HechosDelActa(), emptyList(), emptyList(), d("2026-09-30"))
        assertEquals(listOf(ActoDelExpediente(1, "Acta de constatación", d("2026-08-10"), "AC-0001", "acta", "Código A-042, reincidencia segunda vez")), actos)
    }

    @Test
    fun `the previa on a day - running, vencida the day after its last, subsanada or without a plazo`() {
        val previa = notificacion("NP-0001", "2026-08-10", 5)
        assertEquals("Vence el 2026-08-15", Expedientes.estadoDeLaPrevia(previa, null, d("2026-08-15")))
        assertEquals("Vencida el 2026-08-15", Expedientes.estadoDeLaPrevia(previa, null, d("2026-08-16")))
        assertEquals("Subsanada el 2026-08-10", Expedientes.estadoDeLaPrevia(previa, subsanacion(previa), d("2026-08-16")))
        assertEquals("Sin plazo", Expedientes.estadoDeLaPrevia(notificacion(plazoDias = null), null, d("2030-01-01")))
    }

    @Test
    fun `a resolución not notified yet says so, with its attempts`() {
        val ris = resolucion()
        val fallido = NotificacionResolucion(id = "nr1", intento = 1, fechaDiligencia = d("2026-08-26"), resultado = "NO_UBICADO")

        fun detalle(ns: List<NotificacionResolucion>) =
            Expedientes
                .actos(
                    acta,
                    codigo(),
                    HechosDelActa(resoluciones = listOf(ris)),
                    emptyList(),
                    listOf(ResolucionConNotificaciones(ris, ns)),
                    d("2026-09-30")
                )[1]
                .detalle
        assertEquals("Sin notificar", detalle(emptyList()))
        assertEquals("Sin notificar (1 intento)", detalle(listOf(fallido)))
        val recurso = resolucion(RESOLUCION_RECURSO, SE_DEJA_SIN_EFECTO, "d1", correlativo = 2)
        val conFallo =
            Expedientes.actos(
                acta,
                codigo(),
                HechosDelActa(resoluciones = listOf(recurso)),
                emptyList(),
                listOf(ResolucionConNotificaciones(recurso, emptyList())),
                d("2026-09-30")
            )[1]
        assertEquals("Resolución del recurso", conFallo.acto)
        assertEquals("FUNDADO, SE_DEJA_SIN_EFECTO. Sin notificar", conFallo.detalle)
    }
}
