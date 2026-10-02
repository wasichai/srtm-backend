package srtm.impuesto

import org.springframework.stereotype.Component
import srtm.rentas.Registros

const val PARAMETRO_TRIBUTARIO = "parametro_tributario"

// the only read of parametro_tributario for the liquidación, as the caller. the masiva reads it once per lote and
// hands the rows on (wasichai/srtm-backend#55); the endpoints read it on each call
@Component
class ParametrosTributarios(
    private val registros: Registros
) {
    suspend fun todos(): List<ParametroTributario> = registros.all(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java)
}
