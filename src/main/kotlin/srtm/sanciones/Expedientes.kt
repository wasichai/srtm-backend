package srtm.sanciones

import java.time.LocalDate

// the acts of an expediente in legal order (rentas' inf-exp, «Actos del expediente»): the notificación previa, the
// acta, the descargos, the resoluciones, the notificaciones of the resoluciones and the anulación. the order is legal,
// not a preference: without an acta there is no resolución, and without its notificación the sanción is not exigible.
// within a kind, by day. detalle is what each act says on `hoy`
object Expedientes {
    fun actos(
        acta: Papeleta,
        codigo: CodigoInfraccion,
        h: HechosDelActa,
        descargos: List<DescargoPapeleta>,
        resoluciones: List<ResolucionConNotificaciones>,
        hoy: LocalDate
    ): List<ActoDelExpediente> {
        val actos = mutableListOf<ActoDelExpediente>()

        fun acto(
            nombre: String,
            fecha: LocalDate,
            documento: String?,
            id: String,
            detalle: String?
        ) {
            actos += ActoDelExpediente(actos.size + 1, nombre, fecha, documento, id, detalle)
        }

        h.previa?.let { n -> acto("Notificación previa", n.fecha!!, n.numero, n.id!!, estadoDeLaPrevia(n, h.subsanacion, hoy)) }
        acto(
            "Acta de constatación",
            acta.fechaInfraccion!!,
            acta.numero,
            acta.id!!,
            "Código ${codigo.codigo}, reincidencia ${GRADO.getValue(acta.reincidencia!!)}"
        )
        for (d in descargos) {
            val plazo = if (d.enPlazo == true) "En plazo (hasta el ${d.presentadoHasta})" else "Fuera de plazo (venció el ${d.presentadoHasta})"
            acto(RECURSO.getValue(d.tipoRecurso!!), d.fecha!!, d.numeroExpediente, d.id!!, plazo)
        }
        for (r in resoluciones) {
            val res = r.resolucion
            val nombre = if (res.tipo == RESOLUCION_RECURSO) "Resolución del recurso" else "Resolución de sanción"
            val fallo = if (res.sentido != null && res.efecto != null) "${res.sentido}, ${res.efecto}. " else ""
            acto(nombre, res.fecha!!, res.numero, res.id!!, fallo + estadoDeLaNotificacion(r.notificaciones))
        }
        for (r in resoluciones) {
            for (n in r.notificaciones) {
                val efecto = n.exigibleDesde?.let { ", exigible desde el $it" } ?: ""
                acto("Notificación de la resolución", n.fechaDiligencia!!, "${r.resolucion.numero} (intento ${n.intento})", n.id!!, n.resultado + efecto)
            }
        }
        h.anulacion?.let { a -> acto("Anulación", a.fecha!!, null, a.id!!, a.motivo) }
        return actos
    }

    // a notificación previa on `hoy`: subsanada, vencida, running or without a plazo (Notificaciones.vencida)
    fun estadoDeLaPrevia(
        n: NotificacionAdministrativa,
        subsanacion: SubsanacionNotificacion?,
        hoy: LocalDate
    ): String {
        val vencimiento = Notificaciones.vencimiento(n.fecha!!, n.plazoDias)
        return when {
            subsanacion != null -> "Subsanada el ${subsanacion.fecha}"
            vencimiento == null -> "Sin plazo"
            Notificaciones.vencida(n, hoy) -> "Vencida el $vencimiento"
            else -> "Vence el $vencimiento"
        }
    }

    // a resolución's notificación: the first diligencia that took effect, else how many did not
    private fun estadoDeLaNotificacion(notificaciones: List<NotificacionResolucion>): String {
        val surtio = notificaciones.firstOrNull { it.resultado in SURTEN_EFECTO }
        return when {
            surtio != null -> "Notificada el ${surtio.fechaDiligencia}"
            notificaciones.isEmpty() -> "Sin notificar"
            else -> "Sin notificar (${notificaciones.size} ${if (notificaciones.size == 1) "intento" else "intentos"})"
        }
    }

    internal val GRADO = mapOf(PRIMERA to "primera vez", SEGUNDA to "segunda vez", TERCERA_O_MAS to "tercera o más")

    internal val RECURSO =
        mapOf(
            "DESCARGO" to "Descargo",
            "RECONSIDERACION" to "Recurso de reconsideración",
            "APELACION" to "Recurso de apelación",
            "NULIDAD" to "Pedido de nulidad"
        )
}
