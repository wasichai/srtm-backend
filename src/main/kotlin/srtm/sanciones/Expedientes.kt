package srtm.sanciones

import srtm.legible
import srtm.rentas.Contribuyente
import srtm.rentas.Predio
import java.time.LocalDate

// the acts of an expediente in legal order (rentas' inf-exp, «Actos del expediente»): the notificación previa, the
// acta, the descargos, the resoluciones, the notificaciones of the resoluciones and the anulación. the order is legal,
// not a preference: without an acta there is no resolución, and without its notificación the sanción is not exigible.
// within a kind, by day. detalle is what each act says on `hoy`, as the operator reads it: dd/MM/yyyy and words,
// never the enum's code
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
            val hasta = d.presentadoHasta!!.legible()
            val plazo = if (d.enPlazo == true) "En plazo (hasta el $hasta)" else "Fuera de plazo (venció el $hasta)"
            acto(RECURSO.getValue(d.tipoRecurso!!), d.fecha!!, d.numeroExpediente, d.id!!, plazo)
        }
        for (r in resoluciones) {
            val res = r.resolucion
            val nombre = if (res.tipo == RESOLUCION_RECURSO) "Resolución del recurso" else "Resolución de sanción"
            val fallo = if (res.sentido != null && res.efecto != null) "${etiqueta(SENTIDO, res.sentido)}, ${etiqueta(EFECTO, res.efecto)}. " else ""
            acto(nombre, res.fecha!!, res.numero, res.id!!, fallo + estadoDeLaNotificacion(r.notificaciones))
        }
        for (r in resoluciones) {
            for (n in r.notificaciones) {
                val efecto = n.exigibleDesde?.let { ", exigible desde el ${it.legible()}" } ?: ""
                val resultado = etiqueta(RESULTADO, n.resultado!!)
                acto("Notificación de la resolución", n.fechaDiligencia!!, "${r.resolucion.numero} (intento ${n.intento})", n.id!!, resultado + efecto)
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
            subsanacion != null -> "Subsanada el ${subsanacion.fecha!!.legible()}"
            vencimiento == null -> "Sin plazo"
            Notificaciones.vencida(n, hoy) -> "Vencida el ${vencimiento.legible()}"
            else -> "Vence el ${vencimiento.legible()}"
        }
    }

    // a resolución's notificación: the first diligencia that took effect, else how many did not
    private fun estadoDeLaNotificacion(notificaciones: List<NotificacionResolucion>): String {
        val surtio = notificaciones.firstOrNull { it.resultado in SURTEN_EFECTO }
        return when {
            surtio != null -> "Notificada el ${surtio.fechaDiligencia!!.legible()}"
            notificaciones.isEmpty() -> "Sin notificar"
            else -> "Sin notificar (${notificaciones.size} ${if (notificaciones.size == 1) "intento" else "intentos"})"
        }
    }

    // an enum's code in words; one without a word (none today) shows as stored
    internal fun etiqueta(
        etiquetas: Map<String, String>,
        codigo: String
    ): String = etiquetas[codigo] ?: codigo

    // the sentido opens the detalle; the efecto follows it («Fundado, se deja sin efecto»)
    internal val SENTIDO =
        mapOf(
            "FUNDADO" to "Fundado",
            "FUNDADO_EN_PARTE" to "Fundado en parte",
            "INFUNDADO" to "Infundado",
            "IMPROCEDENTE" to "Improcedente"
        )

    internal val EFECTO = mapOf(SE_MANTIENE to "se mantiene", SE_DEJA_SIN_EFECTO to "se deja sin efecto", SE_REDUCE to "se reduce")

    internal val RESULTADO = mapOf("NOTIFICADO" to "Notificado", "NO_UBICADO" to "No ubicado", "RECHAZADO" to "Rechazado")

    internal val GRADO = mapOf(PRIMERA to "primera vez", SEGUNDA to "segunda vez", TERCERA_O_MAS to "tercera o más")

    internal val RECURSO =
        mapOf(
            "DESCARGO" to "Descargo",
            "RECONSIDERACION" to "Recurso de reconsideración",
            "APELACION" to "Recurso de apelación",
            "NULIDAD" to "Pedido de nulidad"
        )
}

// the parties of an expediente (the ficha's «partes»), from the records read: one the reader may not see keeps its id
// and nothing else. the domicilio fiscal is the obligado's in force, the one a resolución is notified at by default
object Partes {
    fun de(
        acta: Papeleta,
        personas: Map<String, Contribuyente>,
        predio: Predio?
    ): PartesDelActa {
        val obligado = personas[acta.obligado]
        return PartesDelActa(
            obligado = ObligadoDelActa(acta.obligado!!, nombre(obligado), documento(obligado), obligado?.let(::domicilioFiscalDe)),
            contribuyente = acta.contribuyente?.let { id -> ContribuyenteDelActa(id, nombre(personas[id]), documento(personas[id])) },
            predio = acta.predio?.let { id -> PredioDelActa(id, limpio(predio?.codigo), limpio(predio?.direccion)) }
        )
    }

    private fun nombre(c: Contribuyente?) = limpio(c?.nombreCompleto)

    private fun documento(c: Contribuyente?) = limpio(c?.numeroDocumento)

    private fun limpio(texto: String?) = texto?.trim()?.ifEmpty { null }
}
