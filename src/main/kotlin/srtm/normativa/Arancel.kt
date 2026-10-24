package srtm.normativa

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.OffsetDateTime

// model/model.json's object
const val ARANCEL = "arancel"

// an arancel (#188): the valor arancelario (S/ por m²) of a tramo de vía (id_aranc) in a year. srtm owns it and
// catastro-backend keeps a copy it values with (sincronizado_en, error_sincronizacion). clave is id_aranc|anio, one
// alive (not ANULADO) per clave. its estados and signatures are a parámetro's (EstadoParametro, CamposParametro)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Arancel(
    val id: String? = null,
    val clave: String? = null,
    val idAranc: String? = null,
    val anio: Int? = null,
    val valorM2: BigDecimal? = null,
    val codVia: String? = null,
    val cuadra: String? = null,
    val lado: String? = null,
    val norma: String? = null,
    val fuente: String? = null,
    val transcribio: String? = null,
    val verifico: String? = null,
    val estado: String? = null,
    val motivoAnulacion: String? = null,
    val anuladoPor: String? = null,
    val anuladoEn: OffsetDateTime? = null,
    val sincronizadoEn: OffsetDateTime? = null,
    val errorSincronizacion: String? = null
) {
    // what the DJ reads: a VIGENTE one, or one without estado
    fun rige(): Boolean = EstadoParametro.de(estado) == EstadoParametro.VIGENTE

    // as a person reads it: A-01 2026
    fun nombre(): String = "${idAranc.orEmpty()} ${anio ?: ""}".trim()
}

object CamposArancel {
    const val CLAVE = "clave"
    const val ID_ARANC = "id_aranc"
    const val ANIO = "anio"
    const val VALOR_M2 = "valor_m2"
    const val SINCRONIZADO_EN = "sincronizado_en"
    const val ERROR_SINCRONIZACION = "error_sincronizacion"

    // what catastro's copy writes back, in any estado
    val SINCRONIZACION = setOf(SINCRONIZADO_EN, ERROR_SINCRONIZACION)

    // what a transcriber writes (the clave is the service's: claveDe)
    val DATOS = listOf(ID_ARANC, ANIO, VALOR_M2, "cod_via", "cuadra", "lado", "norma", "fuente")
}

// wasichai has no compound unique: the clave a service writes, id_aranc|anio
fun claveDeArancel(
    idAranc: String?,
    anio: Any?
): String = "${idAranc?.trim().orEmpty()}|${anio?.toString()?.trim().orEmpty()}"
