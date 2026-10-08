package srtm.arbitrios

import org.springframework.stereotype.Component
import srtm.ReglaDeEscritura
import srtm.leerRegistro
import wasichai.core.common.ValidationException

// the invariantes of a cuota, whoever adds it (the determination never builds one that breaks them; the generic api
// may still try). that a cuota and its anulación are only added is the model's: appendOnly (ADR-040), a 409 for
// everyone on every path, which also keeps a predio, contribuyente, servicio or parámetro a cuota points at
@Component
class ReglaDeArbitrios : ReglaDeEscritura {
    override val objetos = setOf(CUOTA_ARBITRIO)
    override val soloDesdeElServicio = false

    override fun alInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    ) {
        val malas = invariantes(leerRegistro(CuotaArbitrio::class.java, attrs))
        if (malas.isNotEmpty()) throw ValidationException("La cuota de arbitrio no es válida", malas)
    }
}
