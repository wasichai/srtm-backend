package srtm.arbitrios

import srtm.impuesto.ParametroTributario
import srtm.rentas.Predio
import java.time.LocalDate

// one dimension's contribution to a servicio's tasa key that month: Es when it resolves, Falta naming what is
// missing. a Falta is all-or-nothing, same as a missing zona/uso: Arbitrios.determinar adds its mensaje to faltan,
// which empties every cuota of the predio when it ends up non-empty. its enlace is where it gets fixed
// (Falta.enlace): the predio, for its ubicación respecto del área verde; none, for a missing mapping row
sealed interface ValorDimension {
    data class Es(
        val valor: String
    ) : ValorDimension

    data class Falta(
        val mensaje: String,
        val enlace: Enlace? = null
    ) : ValorDimension
}

// INFLUENCIA and AFLUENCIA, the two Dimension a servicio's DIMENSIONES_ARBITRIO can add besides zona and uso
// (Servicios.dimensiones), and how Arbitrios.determinar combines a servicio's dimensiones into its tasa key (valores)
object Dimensiones {
    // a predio's nivel de influencia: ARBITRIO_INFLUENCIA of its ubicacion_area_verde (model.json's enum), in force
    // that day. nombre is only for the faltan message, same as elsewhere (the predio's codigo, or its id)
    fun influencia(
        parametros: List<ParametroTributario>,
        predio: Predio,
        nombre: String,
        anio: Int,
        dia: LocalDate
    ): ValorDimension = influencia(IndiceParametros(parametros), predio, nombre, anio, dia)

    fun influencia(
        parametros: IndiceParametros,
        predio: Predio,
        nombre: String,
        anio: Int,
        dia: LocalDate
    ): ValorDimension {
        val ubicacion =
            predio.ubicacionAreaVerde?.trim()?.ifEmpty { null }
                ?: return ValorDimension.Falta("Ubicación respecto del área verde del predio $nombre", Enlace.PREDIO)
        val fila = parametros.enVigor(Llaves.ARBITRIO_INFLUENCIA, ubicacion, dia) { !it.texto.isNullOrBlank() }
        return fila?.texto?.trim()?.let { ValorDimension.Es(it) }
            ?: ValorDimension.Falta("${Llaves.ARBITRIO_INFLUENCIA} $ubicacion $anio")
    }

    // a servicio's nivel de afluencia for a uso código that day: ARBITRIO_AFLUENCIA's longest matching prefix,
    // servicio:prefijo winning outright over a bare prefijo (same two-family precedence as ARBITRIO_USO's prefix,
    // just split by servicio first)
    fun afluencia(
        parametros: List<ParametroTributario>,
        servicio: String,
        codigoUso: String,
        anio: Int,
        dia: LocalDate
    ): ValorDimension = afluencia(IndiceParametros(parametros), servicio, codigoUso, anio, dia)

    fun afluencia(
        parametros: IndiceParametros,
        servicio: String,
        codigoUso: String,
        anio: Int,
        dia: LocalDate
    ): ValorDimension {
        fun mejor(prefijoDe: (String) -> String?) =
            parametros
                .deTipo(Llaves.ARBITRIO_AFLUENCIA)
                .filter {
                    !it.texto.isNullOrBlank() && vigente(it.vigenciaDesde, it.vigenciaHasta, dia)
                }.mapNotNull { fila ->
                    fila.clave
                        ?.trim()
                        ?.let(prefijoDe)
                        ?.let { it to fila }
                }.filter { (prefijo, _) -> prefijo.isNotEmpty() && codigoUso.startsWith(prefijo) }
                .maxWithOrNull(compareBy<Pair<String, ParametroTributario>> { it.first.length }.thenBy { it.second.vigenciaDesde })
                ?.second

        val fila =
            mejor { clave -> clave.removePrefix("$servicio:").takeIf { it != clave } }
                ?: mejor { clave -> clave.takeIf { !it.contains(":") } }
        return fila?.texto?.trim()?.let { ValorDimension.Es(it) }
            ?: ValorDimension.Falta("${Llaves.ARBITRIO_AFLUENCIA} $codigoUso $anio")
    }

    // a servicio's dimensiones' valores, in the order they are listed (zona and uso already resolved): the ones
    // found, and the Falta of each one that is missing (its mensaje and enlace). the caller decides what to do with
    // faltan - today, all-or-nothing, same as zona/sector
    fun valores(
        dimensiones: List<Dimension>,
        zona: String,
        uso: String,
        influencia: () -> ValorDimension,
        afluencia: () -> ValorDimension
    ): Pair<List<String>, List<ValorDimension.Falta>> {
        val valores = mutableListOf<String>()
        val faltan = mutableListOf<ValorDimension.Falta>()
        for (dimension in dimensiones) {
            val valor =
                when (dimension) {
                    Dimension.ZONA -> ValorDimension.Es(zona)
                    Dimension.USO -> ValorDimension.Es(uso)
                    Dimension.INFLUENCIA -> influencia()
                    Dimension.AFLUENCIA -> afluencia()
                }
            when (valor) {
                is ValorDimension.Es -> valores += valor.valor
                is ValorDimension.Falta -> faltan += valor
            }
        }
        return valores to faltan
    }
}
