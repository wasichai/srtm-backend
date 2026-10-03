package srtm.sanciones

import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import java.time.LocalDate
import java.util.Locale

// what was recorded about an acta, as a service reads it: its notificación previa and that one's subsanación, its
// anulación and its resoluciones (RIS and RGR). the fase and the estado de la deuda are derived from these, never
// stored: a stored one would be a second truth
data class HechosDelActa(
    val previa: NotificacionAdministrativa? = null,
    val subsanacion: SubsanacionNotificacion? = null,
    val anulacion: AnulacionPapeleta? = null,
    val resoluciones: List<ResolucionGerencia> = emptyList()
)

// rentas' FaseDelProcedimiento and the estado de la deuda: two vocabularies, published with two names so no screen
// draws one where it promises the other (#397)
object Procedimiento {
    const val PREVENTIVA = "PREVENTIVA"
    const val CONSTATADA = "CONSTATADA"
    const val SANCIONADA = "SANCIONADA"

    // rentas' PAGADA and COACTIVA are not produced: srtm has no cobranza
    val FASES = listOf(PREVENTIVA, CONSTATADA, SANCIONADA)

    const val ANULADA = "ANULADA"
    const val DEJADA_SIN_EFECTO = "DEJADA_SIN_EFECTO"
    const val PENDIENTE = "PENDIENTE"

    val ESTADOS = listOf(PENDIENTE, ANULADA, DEJADA_SIN_EFECTO)

    // ANULADA with its anulación; else DEJADA_SIN_EFECTO when ANY resolución (of either tipo, not «the last») left the
    // multa without effect (#385); else PENDIENTE
    fun estadoDeLaDeuda(h: HechosDelActa): String =
        when {
            h.anulacion != null -> ANULADA
            h.resoluciones.any { it.efecto == SE_DEJA_SIN_EFECTO } -> DEJADA_SIN_EFECTO
            else -> PENDIENTE
        }

    // the fase on `corte` (it travels as fase_al_dia): none for an ANULADA or DEJADA_SIN_EFECTO one (never «the
    // closest»: that would put a plausible and wrong figure in the grid); SANCIONADA with a RIS (a RECURSO does not
    // sanction); PREVENTIVA while its notificación previa is neither subsanada nor vencida on `corte`; else CONSTATADA
    fun fase(
        h: HechosDelActa,
        corte: LocalDate
    ): String? =
        when {
            estadoDeLaDeuda(h) != PENDIENTE -> null
            h.resoluciones.any { it.tipo == RESOLUCION_ADMINISTRATIVA } -> SANCIONADA
            h.previa != null && h.subsanacion == null && !Notificaciones.vencida(h.previa, corte) -> PREVENTIVA
            else -> CONSTATADA
        }

    // the fase a filter asks for: none when blank, a 422 that names the accepted ones when it is not one
    fun faseDelFiltro(texto: String?): String? {
        val fase = texto?.trim()?.uppercase(Locale.ROOT)?.ifEmpty { null } ?: return null
        if (fase !in FASES) {
            val admitidas = FASES.joinToString(", ")
            throw NoProcede("La fase '$texto' no es una de $admitidas", listOf(FieldViolation("fase", "es $admitidas")))
        }
        return fase
    }

    // why a descargo («impugnar») or a resolución («resolver») has nothing left to answer, or null: an ANULADA or
    // DEJADA_SIN_EFECTO acta. the ficha's acciones show this same text
    fun impedimento(
        verbo: String,
        h: HechosDelActa
    ): String? =
        when (estadoDeLaDeuda(h)) {
            ANULADA -> "El acta está anulada: no queda nada que $verbo"
            DEJADA_SIN_EFECTO -> "Una resolución dejó sin efecto la multa: no queda nada que $verbo"
            else -> null
        }

    // why an anulación does not proceed, or null: once, and not of an acta a resolución already left without effect
    fun impedimentoDeAnular(h: HechosDelActa): String? = if (h.anulacion != null) ANULADA_YA else impedimento("anular", h)

    // a descargo or a resolución needs something left to answer (422)
    fun exigirQueQuedeAlgoQue(
        verbo: String,
        h: HechosDelActa
    ) {
        impedimento(verbo, h)?.let { throw NoProcede(it) }
    }

    // why `resolucion` is not notified now, or null: nothing of an ANULADA acta; of a DEJADA_SIN_EFECTO one, only the
    // resolución that left the multa without effect (it is served like any other, and its plazo runs from it). the
    // ficha's acciones.notificacion shows this same text
    fun impedimentoDeNotificar(
        h: HechosDelActa,
        resolucion: ResolucionGerencia
    ): String? =
        when (estadoDeLaDeuda(h)) {
            ANULADA -> impedimento("notificar", h)
            DEJADA_SIN_EFECTO ->
                if (resolucion.efecto == SE_DEJA_SIN_EFECTO) null else "Una resolución dejó sin efecto la multa: esta ya no se notifica"
            else -> null
        }

    // a notificación of `resolucion` needs it to be notifiable (422)
    fun exigirNotificable(
        h: HechosDelActa,
        resolucion: ResolucionGerencia
    ) {
        impedimentoDeNotificar(h, resolucion)?.let { throw NoProcede(it) }
    }

    // an anulación: once (409), and not of an acta left without effect (422)
    fun exigirAnulable(h: HechosDelActa) {
        if (h.anulacion != null) throw ConflictException(ANULADA_YA)
        impedimento("anular", h)?.let { throw NoProcede(it) }
    }

    private const val ANULADA_YA = "El acta ya está anulada"
}
