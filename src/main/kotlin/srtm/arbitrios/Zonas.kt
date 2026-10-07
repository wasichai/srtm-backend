package srtm.arbitrios

import srtm.impuesto.ParametroTributario
import srtm.rentas.Predio
import java.text.Normalizer
import java.time.LocalDate

// a servicio's zona that day, for a predio: Es when exactly one zona is found (possibly agreed on by several
// rows), Ambigua when several rows of the same level of precision disagree, Ninguna when none matches
sealed interface ZonaResuelta {
    data class Es(
        val zona: String,
        val fuente: String
    ) : ZonaResuelta

    data class Ambigua(
        val zonas: List<String>
    ) : ZonaResuelta

    data object Ninguna : ZonaResuelta
}

// the diacritics NFD leaves apart: compiled once, not on every comparison
private val MARCAS = "\\p{M}+".toRegex()

// the zona_arbitrio_ubicacion rows of a contexto (those with a zona), grouped by their normalized via once: a
// predio's are looked up by its via instead of comparing every row's, on every servicio and month (#82). the
// contexto builds it once (ContextoArbitrios.porVia), lazily, and the masiva once per lote
class UbicacionesPorVia(
    ubicaciones: List<ZonaUbicacion>
) {
    private val porVia = ubicaciones.filter { !it.zona.isNullOrBlank() }.groupBy { Zonas.normaliza(it.via) }

    // a predio's rows, once per determination: those of its via, tipo_via, tramo and paridad (what does not depend
    // on the day), in the order they came. Zonas.de decides the rest (vigencia, servicio and manzana) each day
    fun delPredio(predio: Predio): UbicacionesDelPredio {
        val numero = Zonas.numeroDe(predio.numero)
        val tipoVia = Zonas.normaliza(predio.tipoVia)
        return UbicacionesDelPredio(
            porVia[Zonas.normaliza(predio.via)].orEmpty().filter { fila ->
                (fila.tipoVia.isNullOrBlank() || Zonas.normaliza(fila.tipoVia) == tipoVia) &&
                    Zonas.enRango(numero, fila.numeroInicial, fila.numeroFinal) &&
                    Zonas.paridadCoincide(fila.paridad, numero)
            }
        )
    }
}

// the zona_arbitrio_ubicacion rows a predio may match (UbicacionesPorVia.delPredio): its via's, tipo_via's, tramo's
// and paridad's, whatever the day
class UbicacionesDelPredio(
    val filas: List<ZonaUbicacion>
)

// Arbitrios' zona resolution: zona_arbitrio_ubicacion (by via, tramo and manzana) first, ARBITRIO_ZONA (by sector
// catastral) as a fallback. within each family, servicio-specific rows win over general ones, and (for
// zona_arbitrio_ubicacion) manzana-specific rows win over general ones. the first level with any matching row
// decides: several rows of that level agreeing on the zona win it, disagreeing ones are Ambigua
object Zonas {
    // the leading digits of a predio's numero ("245-A" -> 245); null when there are none ("S/N")
    fun numeroDe(numero: String?): Int? = numero?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

    // a servicio's zona that day, for a predio, from every row: the same as from the predio's rows of the index
    // (below), which is what a determination uses
    fun de(
        ubicaciones: List<ZonaUbicacion>,
        parametros: List<ParametroTributario>,
        servicio: String,
        predio: Predio,
        dia: LocalDate
    ) = de(UbicacionesPorVia(ubicaciones).delPredio(predio), parametros, servicio, predio, dia)

    // a servicio's zona that day, for a predio: zona_arbitrio_ubicacion's most precise level that has a match,
    // ARBITRIO_ZONA (servicio:sector, then sector alone) as a fallback. a row matches the predio that day when it
    // is one of its rows (UbicacionesPorVia.delPredio: its via, trimmed, case and accent insensitive; its tipo_via
    // unset or the predio's, compared the same way; the predio's numero in its tramo, unset ends open; its paridad
    // AMBOS, unset or the number's) and it is in force
    fun de(
        ubicaciones: UbicacionesDelPredio,
        parametros: List<ParametroTributario>,
        servicio: String,
        predio: Predio,
        dia: LocalDate
    ): ZonaResuelta {
        val candidatas = ubicaciones.filas.filter { vigente(it.vigenciaDesde, it.vigenciaHasta, dia) }
        val niveles =
            listOf(
                candidatas.filter { it.servicio?.trim() == servicio && manzanaIgual(it.manzana, predio.manzana) },
                candidatas.filter { it.servicio?.trim() == servicio && it.manzana.isNullOrBlank() },
                candidatas.filter { it.servicio.isNullOrBlank() && manzanaIgual(it.manzana, predio.manzana) },
                candidatas.filter { it.servicio.isNullOrBlank() && it.manzana.isNullOrBlank() }
            )
        for (nivel in niveles) {
            if (nivel.isNotEmpty()) return resuelveNivel(nivel)
        }

        val sector = predio.sectorCatastral?.trim()?.ifEmpty { null } ?: return ZonaResuelta.Ninguna
        val porServicio = "$servicio:$sector"
        enVigor(parametros, Llaves.ARBITRIO_ZONA, dia) { it.clave?.trim() == porServicio && !it.texto.isNullOrBlank() }
            ?.let { return ZonaResuelta.Es(it.texto!!.trim(), "${Llaves.ARBITRIO_ZONA}:$porServicio") }
        enVigor(parametros, Llaves.ARBITRIO_ZONA, dia) { it.clave?.trim() == sector && !it.texto.isNullOrBlank() }
            ?.let { return ZonaResuelta.Es(it.texto!!.trim(), "${Llaves.ARBITRIO_ZONA}:$sector") }
        return ZonaResuelta.Ninguna
    }

    // one level's rows: a single zona among them wins it (the one of the latest vigencia_desde, for a stable
    // fuente); more than one, in disagreement, is Ambigua
    private fun resuelveNivel(nivel: List<ZonaUbicacion>): ZonaResuelta {
        val zonas = nivel.mapNotNull { it.zona?.trim()?.ifEmpty { null } }.distinct()
        if (zonas.size > 1) return ZonaResuelta.Ambigua(zonas)
        val fila = nivel.filter { it.zona?.trim() == zonas.first() }.maxBy { it.vigenciaDesde ?: LocalDate.MIN }
        return ZonaResuelta.Es(zonas.first(), "ZONA_ARBITRIO_UBICACION:${claveDeZonaUbicacion(fila)}")
    }

    private fun manzanaIgual(
        filaManzana: String?,
        predioManzana: String?
    ): Boolean {
        val fila = filaManzana?.trim()?.ifEmpty { null } ?: return false
        return fila == predioManzana?.trim()?.ifEmpty { null }
    }

    internal fun enRango(
        numero: Int?,
        inicial: Int?,
        final: Int?
    ): Boolean {
        if (inicial == null && final == null) return true
        if (numero == null) return false
        return (inicial == null || numero >= inicial) && (final == null || numero <= final)
    }

    internal fun paridadCoincide(
        paridad: String?,
        numero: Int?
    ): Boolean {
        val p = paridad?.trim()?.uppercase()
        if (p.isNullOrEmpty() || p == "AMBOS") return true
        if (numero == null) return false
        return when (p) {
            "PAR" -> numero % 2 == 0
            "IMPAR" -> numero % 2 != 0
            else -> true // an unknown paridad does not restrict, same as an absent one
        }
    }

    // trimmed, uppercased, without diacritics: "Avenida Perú" and "AVENIDA PERU" compare equal
    // (import_zonas_arbitrio.py's normaliza mirrors it)
    internal fun normaliza(s: String?) =
        s?.trim()?.let {
            Normalizer.normalize(it, Normalizer.Form.NFD).replace(MARCAS, "").uppercase()
        }
}
