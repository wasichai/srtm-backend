package srtm.impuesto

import org.springframework.stereotype.Component
import srtm.normativa.rige
import srtm.rentas.Registros

const val PARAMETRO_TRIBUTARIO = "parametro_tributario"

// the only read of parametro_tributario for the liquidación, as the caller. the masiva reads it once per lote and
// hands the rows on (wasichai/srtm-backend#55); the endpoints read it on each call. only the VIGENTE rows (and those
// without estado, written before it): a POR_VERIFICAR one has one signature, an ANULADO one is history (srtm.normativa)
@Component
class ParametrosTributarios(
    private val registros: Registros
) {
    suspend fun todos(): List<ParametroTributario> = registros.all(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java).filter { it.rige() }
}
