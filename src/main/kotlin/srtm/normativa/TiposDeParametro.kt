package srtm.normativa

import srtm.impuesto.DEDUCCION_ADULTO_MAYOR
import srtm.impuesto.DEDUCCION_PENSIONISTA
import srtm.impuesto.PREDIAL_MINIMO
import srtm.impuesto.TRAMO_PREDIAL
import srtm.impuesto.TRAMO_PREDIAL_LIMITE
import srtm.impuesto.UIT
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import srtm.anuncios.Llaves as LlavesAnuncios
import srtm.arbitrios.Llaves as LlavesArbitrios
import srtm.sanciones.Llaves as LlavesSanciones

// which of a row's values carries the figure: valor_numerico or texto
enum class ClaseDeValor { NUMERICO, TEXTO }

// a tipo of parametro_tributario as the normativa writes it: whether it has a clave (a tramo, a servicio:zona:uso) or is
// one value (the UIT), which value carries it and in which unit (null for a code or a mapping), and the module that
// reads it. admiteVacio: a row without its value says something too (a year without movable feriados)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TipoDeParametro(
    val tipo: String,
    val etiqueta: String,
    val usaClave: Boolean,
    val valor: ClaseDeValor,
    val unidad: String?,
    val modulo: String,
    val admiteVacio: Boolean = false
)

// every tipo a module reads, once: the predial's (srtm.impuesto), each Llaves of the arbitrios, the sanciones and the
// anuncios. TiposDeParametroTest keeps it equal to them, to model/import_parametros.py and to the shipped csvs. the
// shape of each key and value is still the importer's and each module's (Llaves): here only what the normativa's
// screen and its rule need
object TiposDeParametro {
    private const val IMPUESTO = "impuesto"
    private const val ARBITRIOS = "arbitrios"
    private const val SANCIONES = "sanciones"
    private const val ANUNCIOS = "anuncios"

    private fun numero(
        tipo: String,
        etiqueta: String,
        usaClave: Boolean,
        unidad: String?,
        modulo: String
    ) = TipoDeParametro(tipo, etiqueta, usaClave, ClaseDeValor.NUMERICO, unidad, modulo)

    private fun texto(
        tipo: String,
        etiqueta: String,
        usaClave: Boolean,
        modulo: String,
        unidad: String? = null,
        admiteVacio: Boolean = false
    ) = TipoDeParametro(tipo, etiqueta, usaClave, ClaseDeValor.TEXTO, unidad, modulo, admiteVacio)

    val TODOS: List<TipoDeParametro> =
        listOf(
            numero(UIT, "Unidad impositiva tributaria", false, "soles", IMPUESTO),
            numero(TRAMO_PREDIAL, "Alícuota de un tramo del predial", true, "%", IMPUESTO),
            numero(TRAMO_PREDIAL_LIMITE, "Límite de un tramo del predial", true, "UIT", IMPUESTO),
            numero(PREDIAL_MINIMO, "Impuesto predial mínimo", false, "% de la UIT", IMPUESTO),
            numero(DEDUCCION_PENSIONISTA, "Deducción del pensionista", false, "UIT", IMPUESTO),
            numero(DEDUCCION_ADULTO_MAYOR, "Deducción del adulto mayor", false, "UIT", IMPUESTO),
            numero(LlavesArbitrios.TASA_ARBITRIO, "Tasa mensual de un arbitrio (servicio:zona:uso)", true, "soles", ARBITRIOS),
            texto(LlavesArbitrios.ARBITRIO_ZONA, "Zona de un sector catastral", true, ARBITRIOS),
            texto(LlavesArbitrios.ARBITRIO_USO, "Uso de arbitrio de un uso de predio", true, ARBITRIOS),
            texto(LlavesArbitrios.ARBITRIO_VENCIMIENTO, "Vencimiento de un mes de arbitrios", true, ARBITRIOS, unidad = "fecha"),
            texto(LlavesArbitrios.BASE_ARBITRIO, "Base de cálculo de un servicio", true, ARBITRIOS),
            texto(LlavesArbitrios.DIMENSIONES_ARBITRIO, "Dimensiones de la tasa de un servicio", true, ARBITRIOS),
            numero(LlavesArbitrios.HABITANTES_PROMEDIO, "Habitantes promedio (servicio:zona:uso)", true, "habitantes", ARBITRIOS),
            numero(LlavesArbitrios.HABITANTES_VARIACION, "Variación por habitante (servicio:zona:uso)", true, "factor", ARBITRIOS),
            texto(LlavesArbitrios.REDONDEO_ARBITRIO, "Redondeo de las cuotas (decimales:modo)", false, ARBITRIOS),
            texto(LlavesArbitrios.ARBITRIO_INFLUENCIA, "Nivel de influencia de una ubicación del área verde", true, ARBITRIOS),
            texto(LlavesArbitrios.ARBITRIO_AFLUENCIA, "Nivel de afluencia de un uso de predio", true, ARBITRIOS),
            numero(LlavesArbitrios.TOPE_ARBITRIO, "Tope de aumento anual de un uso de predio", true, "%", ARBITRIOS),
            numero(LlavesArbitrios.ARBITRIO_PU_POR_USO, "Un PU por uso desde el 1 de enero", false, null, ARBITRIOS),
            numero(LlavesSanciones.PLAZO, "Plazo de un acto de sanciones", true, "días", SANCIONES),
            texto(LlavesSanciones.FERIADOS, "Feriados movibles de un año", true, SANCIONES, unidad = "fechas", admiteVacio = true),
            numero(LlavesAnuncios.TASA_ANUNCIO, "Tasa anual de una clase de anuncio", true, "soles", ANUNCIOS)
        )

    private val porTipo = TODOS.associateBy { it.tipo }

    fun de(tipo: String?): TipoDeParametro? = tipo?.let { porTipo[it] }
}
