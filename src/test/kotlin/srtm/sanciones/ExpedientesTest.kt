package srtm.sanciones

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import srtm.rentas.Contribuyente
import srtm.rentas.Predio
import srtm.sanciones.Ficticios.anulacion
import srtm.sanciones.Ficticios.codigo
import srtm.sanciones.Ficticios.d
import srtm.sanciones.Ficticios.descargo
import srtm.sanciones.Ficticios.notificacion
import srtm.sanciones.Ficticios.resolucion
import srtm.sanciones.Ficticios.subsanacion

// rentas' inf-exp, «Actos del expediente»: the order is legal (previa, acta, descargos, resoluciones, their
// notificaciones, anulación), not by day, and each act says what it is on the day asked, in words and dd/MM/yyyy
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
                listOf(ResolucionConNotificaciones(ris, notificaciones, NOTIFICABLE)),
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
        assertEquals("Vencida el 08/08/2026", actos[0].detalle)
        assertEquals("Código A-042, reincidencia segunda vez", actos[1].detalle)
        assertEquals("En plazo (hasta el 17/08/2026)", actos[2].detalle)
        assertEquals("Fuera de plazo (venció el 17/08/2026)", actos[3].detalle)
        assertEquals("Notificada el 28/08/2026", actos[4].detalle)
        assertEquals("RIS-2026-000001 (intento 1)", actos[5].documento)
        assertEquals("No ubicado", actos[5].detalle)
        assertEquals("Notificado, exigible desde el 19/09/2026", actos[6].detalle)
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
        assertEquals("Vence el 15/08/2026", Expedientes.estadoDeLaPrevia(previa, null, d("2026-08-15")))
        assertEquals("Vencida el 15/08/2026", Expedientes.estadoDeLaPrevia(previa, null, d("2026-08-16")))
        assertEquals("Subsanada el 10/08/2026", Expedientes.estadoDeLaPrevia(previa, subsanacion(previa), d("2026-08-16")))
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
                    listOf(ResolucionConNotificaciones(ris, ns, NOTIFICABLE)),
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
                listOf(ResolucionConNotificaciones(recurso, emptyList(), NOTIFICABLE)),
                d("2026-09-30")
            )[1]
        assertEquals("Resolución del recurso", conFallo.acto)
        assertEquals("Fundado, se deja sin efecto. Sin notificar", conFallo.detalle)
    }

    @Test
    fun `every sentido, efecto and resultado reads in words, never its code`() {
        assertEquals(Opciones.SENTIDOS.toSet(), Expedientes.SENTIDO.keys)
        assertEquals(Opciones.EFECTOS.toSet(), Expedientes.EFECTO.keys)
        assertEquals(Opciones.RESULTADOS.toSet(), Expedientes.RESULTADO.keys)
        val rechazada =
            NotificacionResolucion(id = "nr1", intento = 1, fechaDiligencia = d("2026-08-26"), resultado = "RECHAZADO", exigibleDesde = d("2026-09-17"))
        val ris = resolucion()
        val actos =
            Expedientes.actos(
                acta,
                codigo(),
                HechosDelActa(resoluciones = listOf(ris)),
                emptyList(),
                listOf(ResolucionConNotificaciones(ris, listOf(rechazada), NOTIFICABLE)),
                d("2026-09-30")
            )
        assertEquals("Notificada el 26/08/2026", actos[1].detalle)
        assertEquals("Rechazado, exigible desde el 17/09/2026", actos[2].detalle)
    }

    @Test
    fun `the partes name the obligado with its domicilio fiscal, and the contribuyente and predio when the acta does`() {
        val obligado =
            Contribuyente(id = "c1", nombreCompleto = "FLORES OTINIANO JUNIOR", numeroDocumento = "12345678", domicilioFiscal = " JR. LIMA 123 ")
        val otro = Contribuyente(id = "c2", nombreCompleto = "EMPRESA FICTICIA SAC", numeroDocumento = "20123456789", domicilioFiscal = "AV. SOL 1")
        val predio = Predio(id = "p1", codigo = "P-0001", direccion = "JR. LIMA 123")
        val conTodo = acta.copy(obligado = "c1", contribuyente = "c2", predio = "p1")

        assertEquals(
            PartesDelActa(
                ObligadoDelActa("c1", "FLORES OTINIANO JUNIOR", "12345678", "JR. LIMA 123"),
                ContribuyenteDelActa("c2", "EMPRESA FICTICIA SAC", "20123456789"),
                PredioDelActa("p1", "P-0001", "JR. LIMA 123")
            ),
            Partes.de(conTodo, mapOf("c1" to obligado, "c2" to otro), predio)
        )
        // without a domicilio fiscal, a contribuyente nor a predio: null, never ""
        val solo = Partes.de(acta.copy(obligado = "c1"), mapOf("c1" to obligado.copy(domicilioFiscal = "  ")), null)
        assertEquals(PartesDelActa(ObligadoDelActa("c1", "FLORES OTINIANO JUNIOR", "12345678", null), null, null), solo)
        // what the reader may not see keeps its id
        assertEquals(
            PartesDelActa(ObligadoDelActa("c1", null, null, null), null, PredioDelActa("p1", null, null)),
            Partes.de(acta.copy(obligado = "c1", predio = "p1"), emptyMap(), null)
        )
    }

    private companion object {
        val NOTIFICABLE = AccionesDeLaResolucion(AccionDelActa(true, null))
    }
}
