package srtm.caja

import org.springframework.stereotype.Component
import srtm.ReglaDeEscritura

// the objects of the caja are only added, and only by srtm's service (srtm.GuardiaDeEscritura asks this rule before every
// write): an insert without the mark is a 403, even an ADMIN's. a change, a delete or a transition is a 409: the cuotas
// and the pagos are appendOnly, and the states of an orden are moved by direct UPDATE of the queue's worker, not by the api
@Component
class ReglasDeCaja : ReglaDeEscritura {
    override val objetos = ObjetosCaja.TODOS

    override val soloDesdeElServicio = true

    // the service builds them: its planner holds their invariants
    override fun alInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    ) = Unit
}
