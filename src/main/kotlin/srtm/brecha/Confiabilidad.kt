package srtm.brecha

import srtm.emision.nombreDe
import srtm.rentas.Contribuyente
import srtm.rentas.Predio
import java.text.Normalizer
import java.util.Locale

// #204: how sure a row's cruce is (brecha_predio.confianza, the enum confianza_brecha)
enum class Confianza { CONFIABLE, DUDOSO, SIN_LEVANTAR }

data class ConfianzaDeFila(
    val confianza: Confianza,
    val motivo: String
)

// #204: a row's confianza, in this order: a ficha sin levantar; a crossed row whose vínculo catastro made by the código
// de rentas, or by hand and checked; a titular of the ficha with the documento, else the nombre, of one of the DJ's;
// anything else is DUDOSO, a row without a ficha (SIN_CATASTRO) or without a predio (an uncrossed OMISO) too
object Confiabilidad {
    private val NO_ALFANUMERICO = Regex("[^A-Z0-9]")
    private val NO_LETRA = Regex("[^A-Z]+")
    private val MARCAS = Regex("\\p{M}")

    // a documento as both sides may write it: alphanumerics only, upper case, without leading zeros. empty is none
    fun documento(numero: String?): String? =
        numero
            ?.uppercase(Locale.ROOT)
            ?.replace(NO_ALFANUMERICO, "")
            ?.trimStart('0')
            ?.ifEmpty { null }

    // a nombre as both sides may write it: upper case, without accents (Ñ is N), its words in alphabetical order, so
    // "Pérez Gómez, Juan" and "JUAN PEREZ GOMEZ" are one. empty is none
    fun nombre(texto: String?): String? =
        texto
            ?.let { Normalizer.normalize(it.uppercase(Locale.ROOT), Normalizer.Form.NFD) }
            ?.replace(MARCAS, "")
            ?.split(NO_LETRA)
            ?.filter { it.isNotEmpty() }
            ?.sorted()
            ?.joinToString(" ")
            ?.ifEmpty { null }

    // item: the catastro item (null on a SIN_CATASTRO row); predio: the padrón predio it crossed; fichas: the item's
    // titulares in catastro; titulares: the contribuyentes of the predio's DJs
    fun de(
        item: ValuacionCatastral?,
        predio: Predio?,
        fichas: List<TitularCatastral>,
        titulares: List<Contribuyente>
    ): ConfianzaDeFila {
        if (item?.estadoLevantamiento == SIN_LEVANTAR) {
            return ConfianzaDeFila(Confianza.SIN_LEVANTAR, "Ficha sin levantar" + (item.entrega?.let { " (entrega $it)" } ?: ""))
        }
        if (item == null) return ConfianzaDeFila(Confianza.DUDOSO, "Sin ficha del catastro")
        if (predio == null) return ConfianzaDeFila(Confianza.DUDOSO, "Sin predio en el padrón")
        if (item.vinculoMetodo == CODIGO_RENTAS) return ConfianzaDeFila(Confianza.CONFIABLE, "Vínculo por código de rentas")
        if (item.vinculoMetodo == MANUAL && item.vinculoRevisado == true) return ConfianzaDeFila(Confianza.CONFIABLE, "Vínculo manual revisado")
        val documentos = titulares.mapNotNull { documento(it.numeroDocumento) }.toSet()
        if (fichas.any { f -> documento(f.numeroDocumento)?.let { it in documentos } == true }) {
            return ConfianzaDeFila(Confianza.CONFIABLE, "Un titular de la ficha coincide por documento")
        }
        val nombres = titulares.mapNotNull { nombre(nombreDe(it)) }.toSet()
        if (fichas.any { f -> nombre(f.nombre)?.let { it in nombres } == true }) {
            return ConfianzaDeFila(Confianza.CONFIABLE, "Un titular de la ficha coincide por nombre")
        }
        return ConfianzaDeFila(Confianza.DUDOSO, "Vínculo ${item.vinculoMetodo ?: "sin método"}, sin titular en común")
    }
}
