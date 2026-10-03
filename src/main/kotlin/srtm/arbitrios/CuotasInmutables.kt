package srtm.arbitrios

import org.springframework.stereotype.Component
import srtm.ReglaDeEscritura
import srtm.rentas.Records
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException

// rentas' GRANT INSERT, SELECT on determinacion_arbitrio: a cuota (and the anulación that corrects one) is never
// updated nor deleted, by anyone (srtm.AlmacenGuardado asks this rule before every write). an insert of a cuota must
// hold its invariantes, whoever sends it (the determination never builds one that breaks them). the generic api may
// still add them: the arbitrios ask for no mark
@Component
class CuotasInmutables : ReglaDeEscritura {
    override val objetos = setOf(CUOTA_ARBITRIO, ANULACION_CUOTA_ARBITRIO)

    override val soloDesdeElServicio = false

    override fun alInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    ) {
        if (objeto != CUOTA_ARBITRIO) return
        val malas = invariantes(Records.read(CuotaArbitrio::class.java, "", attrs))
        if (malas.isNotEmpty()) throw ValidationException("La cuota de arbitrio no es válida", malas)
    }

    override fun noSeCambia(objeto: String) =
        ConflictException("Una cuota de arbitrio y su anulación no se editan ni se borran: una corrección se agrega como anulación")
}
