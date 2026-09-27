package srtm.pide

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

// the state's identity registries through the PIDE (plataforma de interoperabilidad del estado): what they answer
// for a document fills the portal's datos personales. only RENIEC, for a DNI, so far

const val DNI = "DNI"

// the fuente de información of names that came from RENIEC (model.json's fuente_informacion)
const val PIDE_RENIEC = "PIDE RENIEC"

// what RENIEC says of a DNI, with the portal's field names (snake_case, as the forms send them)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DatosPersona(
    val tipoDocumento: String,
    val numeroDocumento: String,
    val apellidoPaterno: String?,
    val apellidoMaterno: String?,
    val nombres: String?,
    val estadoCivil: String? = null,
    val direccion: String? = null,
    val ubigeo: String? = null,
    val fuenteInformacion: String = PIDE_RENIEC
)

interface ConsultaDocumento {
    // null: no such person, a tipo the service does not answer, no service configured or a failed call. the clerk
    // then types the data (fuente MANUAL)
    suspend fun consultar(
        tipo: String,
        numero: String
    ): DatosPersona?
}

// the default: no convenio with the PIDE configured (srtm.pide.reniec.enabled)
class SinConsulta : ConsultaDocumento {
    override suspend fun consultar(
        tipo: String,
        numero: String
    ): DatosPersona? = null
}
