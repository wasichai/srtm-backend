package srtm.anuncios

import org.springframework.stereotype.Component
import srtm.ReglaDeEscritura
import srtm.leerRegistro
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException

// rentas' GRANT INSERT, SELECT on the anuncios: an anuncio and its movimientos are only added, and only by srtm's api,
// which runs their rules (srtm.AlmacenGuardado asks this one before every write). an insert holds its invariantes; an
// update, a delete or a transition is a 409
@Component
class ReglaDeAnuncios : ReglaDeEscritura {
    override val objetos = setOf(ANUNCIO, MOVIMIENTO_ANUNCIO)

    override val soloDesdeElServicio = true

    override fun alInsertar(
        objeto: String,
        attrs: Map<String, Any?>
    ) {
        val malas: List<FieldViolation> =
            when (objeto) {
                ANUNCIO -> invariantes(leerRegistro(Anuncio::class.java, attrs))
                MOVIMIENTO_ANUNCIO -> invariantes(leerRegistro(MovimientoAnuncio::class.java, attrs))
                else -> emptyList()
            }
        if (malas.isNotEmpty()) throw ValidationException("«$objeto» no es válido", malas)
    }

    override fun noSeCambia(objeto: String) =
        ConflictException(
            "«$objeto» solo se agrega: no se edita ni se borra, por ninguna puerta. Un anuncio cambia con un movimiento " +
                "(renovación, cese o retiro)"
        )
}
