package srtm.rentas

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal

// object and relation names of model/model.json
const val CONTRIBUYENTE = "contribuyente"
const val PREDIO = "predio"
const val DECLARACION = "declaracion_predial"
const val DOMICILIO = "domicilio"
const val RELACIONADO = "relacionado"
const val MEDIO_CONTACTO = "medio_contacto"
const val SUSTENTO = "sustento"
const val UBIGEO = "ubigeo"
const val VIA = "via"
const val UNIDAD_URBANA = "unidad_urbana"
const val TRANSFERENTE = "transferente"
const val NIVEL_CONSTRUCCION = "nivel_construccion"
const val OBRA_COMPLEMENTARIA = "obra_complementaria"
const val OTRO_FRENTE = "otro_frente"
const val CATEGORIA_VALOR = "categoria_valor"
const val CATASTRO_FISCAL = "catastro_fiscal"
const val OBRA_CATEGORIA = "obra_categoria"

// a record's attributes <-> a portal dto. the dtos' json names are the field names, so jackson does the
// mapping: core reads DATE back as an iso string (LocalDate here), DECIMAL as BigDecimal, INTEGER as Long
// (Int here), relations as the id string. a field the dto does not know (added in the admin) is ignored
// on read; writes merge over the stored record (RentasService.replace), so it survives a portal save
object Records {
    private val json: JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()

    fun <T : Any> read(
        type: Class<T>,
        id: String,
        attributes: Map<String, Any?>
    ): T = json.convertValue(attributes + ("id" to id), type)

    inline fun <reified T : Any> read(
        id: String,
        attributes: Map<String, Any?>
    ): T = read(T::class.java, id, attributes)

    // every field of the dto, null included: core's update is a full replace
    @Suppress("UNCHECKED_CAST")
    fun attributes(dto: Any): Map<String, Any?> = (json.convertValue(dto, Map::class.java) as Map<String, Any?>) - "id"
}

// sums over the declarations of one year. a missing value counts as zero
fun totales(declaraciones: List<Declaracion>) =
    Totales(
        declaraciones = declaraciones.size,
        autoavaluo = declaraciones.sumOf { it.valorAutoavaluo ?: BigDecimal.ZERO },
        valorAfecto = declaraciones.sumOf { it.valorAfecto ?: BigDecimal.ZERO }
    )
