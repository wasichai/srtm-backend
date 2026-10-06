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

// Arbitrios' zona resolution: zona_arbitrio_ubicacion (by via, tramo and manzana) first, ARBITRIO_ZONA (by sector
// catastral) as a fallback. within each family, servicio-specific rows win over general ones, and (for
// zona_arbitrio_ubicacion) manzana-specific rows win over general ones. the first level with any matching row
// decides: several rows of that level agreeing on the zona win it, disagreeing ones are Ambigua
object Zonas {
    // the leading digits of a predio's numero ("245-A" -> 245); null when there are none ("S/N")
    fun numeroDe(numero: String?): Int? = numero?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

    // a servicio's zona that day, for a predio: zona_arbitrio_ubicacion's most precise level that has a match,
    // ARBITRIO_ZONA (servicio:sector, then sector alone) as a fallback
    fun de(
        ubicaciones: List<ZonaUbicacion>,
        parametros: List<ParametroTributario>,
        servicio: String,
        predio: Predio,
        dia: LocalDate
    ): ZonaResuelta {
        val numero = numeroDe(predio.numero)
        val candidatas = ubicaciones.filter { !it.zona.isNullOrBlank() && coincide(it, predio, numero, dia) }
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

    // a zona_arbitrio_ubicacion row matches the predio that day when: it's in force, its via is the predio's
    // (trimmed, case and accent insensitive), its tipo_via is unset or the predio's (compared the same way), the
    // predio's numero falls in its tramo (unset ends are open) and its paridad is AMBOS, unset or the number's
    private fun coincide(
        fila: ZonaUbicacion,
        predio: Predio,
        numero: Int?,
        dia: LocalDate
    ): Boolean {
        if (!vigente(fila.vigenciaDesde, fila.vigenciaHasta, dia)) return false
        if (normaliza(fila.via) != normaliza(predio.via)) return false
        if (!fila.tipoVia.isNullOrBlank() && normaliza(fila.tipoVia) != normaliza(predio.tipoVia)) return false
        if (!enRango(numero, fila.numeroInicial, fila.numeroFinal)) return false
        if (!paridadCoincide(fila.paridad, numero)) return false
        return true
    }

    private fun enRango(
        numero: Int?,
        inicial: Int?,
        final: Int?
    ): Boolean {
        if (inicial == null && final == null) return true
        if (numero == null) return false
        return (inicial == null || numero >= inicial) && (final == null || numero <= final)
    }

    private fun paridadCoincide(
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
    private fun normaliza(s: String?) =
        s?.trim()?.let {
            Normalizer.normalize(it, Normalizer.Form.NFD).replace("\\p{M}+".toRegex(), "").uppercase()
        }
}
