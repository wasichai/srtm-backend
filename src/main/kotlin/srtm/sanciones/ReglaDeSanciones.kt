package srtm.sanciones

import org.springframework.stereotype.Component
import srtm.ReglaDeEscritura
import srtm.fecha
import srtm.iguales
import srtm.leerRegistro
import srtm.legible
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException

// rentas' GRANT INSERT, SELECT on the sanciones: an acta, a notificación, a descargo, a resolución... are only added,
// and only by srtm's api, which runs their rules (srtm.AlmacenGuardado asks this one before every write). an insert
// holds its invariantes; an update, a delete or a transition is a 409. the one exception is the CUIS: closing the
// version in force (vigencia_hasta, once, and clave_vigente emptied) when a new one is added
@Component
class ReglaDeSanciones : ReglaDeEscritura {
    override val objetos =
        setOf(
            CODIGO_INFRACCION,
            NOTIFICACION_ADMINISTRATIVA,
            SUBSANACION_NOTIFICACION,
            PAPELETA,
            ANULACION_PAPELETA,
            DESCARGO_PAPELETA,
            RESOLUCION_GERENCIA,
            NOTIFICACION_RESOLUCION
        )

    override val soloDesdeElServicio = true

    override fun alInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    ) {
        val malas: List<FieldViolation> =
            when (objeto) {
                CODIGO_INFRACCION -> invariantes(leerRegistro(CodigoInfraccion::class.java, attrs))
                NOTIFICACION_ADMINISTRATIVA -> invariantes(leerRegistro(NotificacionAdministrativa::class.java, attrs))
                SUBSANACION_NOTIFICACION -> invariantes(leerRegistro(SubsanacionNotificacion::class.java, attrs))
                PAPELETA -> invariantes(leerRegistro(Papeleta::class.java, attrs))
                ANULACION_PAPELETA -> invariantes(leerRegistro(AnulacionPapeleta::class.java, attrs))
                DESCARGO_PAPELETA -> invariantes(leerRegistro(DescargoPapeleta::class.java, attrs))
                RESOLUCION_GERENCIA -> invariantes(leerRegistro(ResolucionGerencia::class.java, attrs))
                NOTIFICACION_RESOLUCION -> invariantes(leerRegistro(NotificacionResolucion::class.java, attrs))
                else -> emptyList()
            }
        if (malas.isNotEmpty()) throw ValidationException("«$objeto» no es válido", malas)
    }

    // a version of the CUIS is closed once: its vigencia_hasta goes from empty to a day not before it started, its
    // clave_vigente to empty, and nothing else changes
    override fun alActualizar(
        objeto: String,
        guardado: Map<String, Any?>,
        nuevo: Map<String, Any?>
    ) {
        if (objeto != CODIGO_INFRACCION) throw noSeCambia(objeto)
        val version = guardado["clave"]
        if (fecha(guardado[VIGENCIA_HASTA]) != null) {
            throw ConflictException("La versión $version del CUIS ya está cerrada: se cierra una sola vez")
        }
        val otros = (guardado.keys + nuevo.keys).filter { it !in CIERRE && !iguales(guardado[it], nuevo[it]) }
        if (otros.isNotEmpty()) {
            throw ConflictException(
                "De una versión del CUIS solo se cierra la vigencia: ${otros.joinToString(", ")} no cambia. Un cambio es una versión nueva"
            )
        }
        val hasta =
            fecha(nuevo[VIGENCIA_HASTA])
                ?: throw ConflictException("De una versión del CUIS solo se cierra la vigencia: cerrarla es poner vigencia_hasta")
        val desde = fecha(guardado["vigencia_desde"])
        if (desde != null && hasta < desde) {
            throw ValidationException("La vigencia termina antes de empezar", VIGENCIA_HASTA, "no es anterior a vigencia_desde (${desde.legible()})")
        }
        if (nuevo[CLAVE_VIGENTE] != null) {
            throw ValidationException("Una versión cerrada deja de ser la vigente", CLAVE_VIGENTE, "queda vacía (null) al cerrarse")
        }
    }

    override fun noSeCambia(objeto: String) =
        ConflictException(
            "«$objeto» solo se agrega: no se edita ni se borra, por ninguna puerta. Un acta se corrige agregando su anulación, " +
                "y un código del CUIS cambia con una versión nueva"
        )

    private companion object {
        const val VIGENCIA_HASTA = "vigencia_hasta"
        const val CLAVE_VIGENTE = "clave_vigente"
        val CIERRE = setOf(VIGENCIA_HASTA, CLAVE_VIGENTE)
    }
}
