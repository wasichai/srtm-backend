package srtm.arbitrios

import srtm.impuesto.ParametroTributario
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// how a servicio's cuota is based: on the predio as a unit (today's only case, and the default), on its frontis
// (ml) or on its built area (m2). porUnidad and unidad are how Calculo.formula prints it: "S/ por <porUnidad>",
// and, for a base that reads a cantidad (unlike PREDIO, always 1), "× <cantidad> <unidad>"
enum class Base(
    val porUnidad: String,
    val unidad: String?
) {
    PREDIO("predio", null),
    FRONTIS_ML("ml", "ml"),
    AREA_CONSTRUIDA_M2("m²", "m²")
}

// the rasgos a servicio's tasa can key on, besides zona and uso (today's defaults)
enum class Dimension { ZONA, USO, INFLUENCIA, AFLUENCIA }

// a monetary rounding: decimales digits, by modo. read from a row's texto as "decimales:modo"
// (model/import_parametros.py)
data class Redondeo(
    val decimales: Int,
    val modo: RoundingMode
) {
    fun aplicar(v: BigDecimal): BigDecimal = v.setScale(decimales, modo)

    override fun toString() = "$decimales:" + MODOS.entries.first { it.value == modo }.key

    companion object {
        val MODOS = mapOf("ABAJO" to RoundingMode.DOWN, "MEDIO" to RoundingMode.HALF_UP, "ARRIBA" to RoundingMode.UP)

        // "2:ABAJO" -> Redondeo(2, DOWN); null, blank or malformed (unknown modo, no decimales) -> null, not guessed
        fun de(texto: String?): Redondeo? {
            if (texto.isNullOrBlank()) return null
            val partes = texto.split(":")
            if (partes.size != 2) return null
            val decimales = partes[0].toIntOrNull() ?: return null
            val modo = MODOS[partes[1]] ?: return null
            return Redondeo(decimales, modo)
        }
    }
}

// a servicio:zona:uso's habitantes (por vivienda), used to split its tasa when the base is an area or a frontis
data class Habitantes(
    val promedio: BigDecimal,
    val variacion: BigDecimal
)

// a predio's tope de incremento (TOPE_ARBITRIO): the row's clave (a uso prefix or "*"), its % and, when it has one,
// the cut date of the predio's registration
data class Tope(
    val clave: String,
    val porcentaje: BigDecimal,
    val corte: LocalDate?
)

// the per-servicio reading of an ordenanza's rows: its base, its dimensiones, its habitantes and the global
// redondeo. each reads the row of its tipo and clave in force that day (the latest vigencia_desde), same rule as
// Arbitrios' enVigor. a texto that is not a known value (an unknown base, an unknown dimension) counts as absent,
// same as a missing row: the importer already rejects it, so nothing here guesses
object Servicios {
    // a servicio's base; PREDIO when there's no row in force for it, or its texto is not a known Base
    fun base(
        parametros: List<ParametroTributario>,
        servicio: String,
        dia: LocalDate
    ): Base = baseLeida(parametros, servicio, dia) ?: Base.PREDIO

    // the base its row in force says, null without one (or with an unknown texto): whether BASE_ARBITRIO was read
    fun baseLeida(
        parametros: List<ParametroTributario>,
        servicio: String,
        dia: LocalDate
    ): Base? {
        val fila = enVigor(parametros, Llaves.BASE_ARBITRIO, dia) { it.clave?.trim() == servicio && !it.texto.isNullOrBlank() }
        return fila?.texto?.trim()?.let { runCatching { Base.valueOf(it) }.getOrNull() }
    }

    // a servicio's dimensiones, comma-separated in the row's texto; [ZONA, USO] when there's no row in force for
    // it, or any of its names is not a known Dimension
    fun dimensiones(
        parametros: List<ParametroTributario>,
        servicio: String,
        dia: LocalDate
    ): List<Dimension> {
        val fila = enVigor(parametros, Llaves.DIMENSIONES_ARBITRIO, dia) { it.clave?.trim() == servicio && !it.texto.isNullOrBlank() }
        val nombres =
            fila
                ?.texto
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
        val dimensiones = nombres?.map { runCatching { Dimension.valueOf(it) }.getOrNull() }
        return if (dimensiones != null && dimensiones.none { it == null }) {
            dimensiones.filterNotNull()
        } else {
            listOf(Dimension.ZONA, Dimension.USO)
        }
    }

    // a servicio:zona:uso's habitantes; null unless both HABITANTES_PROMEDIO and HABITANTES_VARIACION have a row
    // in force that day
    fun habitantes(
        parametros: List<ParametroTributario>,
        servicio: String,
        zona: String,
        uso: String,
        dia: LocalDate
    ): Habitantes? {
        val clave = Llaves.tasa(servicio, zona, uso)
        val promedio = enVigor(parametros, Llaves.HABITANTES_PROMEDIO, dia) { it.clave?.trim() == clave && it.valorNumerico != null }?.valorNumerico
        val variacion = enVigor(parametros, Llaves.HABITANTES_VARIACION, dia) { it.clave?.trim() == clave && it.valorNumerico != null }?.valorNumerico
        return if (promedio != null && variacion != null) Habitantes(promedio, variacion) else null
    }

    // the rounding in force that day, global to the ordenanza (clave ""); null when there's no row in force, or
    // its texto is malformed (Redondeo.de)
    fun redondeo(
        parametros: List<ParametroTributario>,
        dia: LocalDate
    ): Redondeo? {
        val fila = enVigor(parametros, Llaves.REDONDEO_ARBITRIO, dia) { it.clave?.trim().isNullOrEmpty() && !it.texto.isNullOrBlank() }
        return Redondeo.de(fila?.texto)
    }

    // the tope of a uso_predio code that day: the row of its longest prefix in force, "*" when none matches, the
    // latest vigencia among equals. null without one. a row without a % above 0, or whose texto is not empty nor a
    // date, counts as absent (the importer already rejects it)
    fun tope(
        parametros: List<ParametroTributario>,
        codigoUso: String,
        dia: LocalDate
    ): Tope? {
        val fila =
            parametros
                .filter {
                    val clave = it.clave?.trim()
                    it.tipo == Llaves.TOPE_ARBITRIO && !clave.isNullOrEmpty() && (clave == "*" || codigoUso.startsWith(clave)) &&
                        it.valorNumerico != null && it.valorNumerico.signum() > 0 && (it.texto.isNullOrBlank() || corte(it.texto) != null) &&
                        vigente(it.vigenciaDesde, it.vigenciaHasta, dia)
                }.maxWithOrNull(compareBy<ParametroTributario> { it.clave!!.trim().let { c -> if (c == "*") 0 else c.length } }.thenBy { it.vigenciaDesde })
                ?: return null
        return Tope(fila.clave!!.trim(), fila.valorNumerico!!, corte(fila.texto))
    }

    // a tope's cut date: its texto as YYYY-MM-DD, null when blank or malformed
    private fun corte(texto: String?): LocalDate? = texto?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

// the row of a tipo in force that day that matches; the latest one when several do (Zonas' ARBITRIO_ZONA, usoDe and
// the tasa itself use this same rule)
internal fun enVigor(
    parametros: List<ParametroTributario>,
    tipo: String,
    dia: LocalDate,
    coincide: (ParametroTributario) -> Boolean
) = parametros
    .filter { it.tipo == tipo && vigente(it.vigenciaDesde, it.vigenciaHasta, dia) && coincide(it) }
    .maxByOrNull { it.vigenciaDesde!! }
