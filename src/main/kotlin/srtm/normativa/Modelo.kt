package srtm.normativa

import srtm.impuesto.ParametroTributario
import java.util.Locale

// the normativa's states (model/model.json, enum estado_parametro), the same for every record it rules:
// - POR_VERIFICAR: registered by who transcribed it, still editable, and no calculation reads it;
// - VIGENTE: verified by another person (the double signature); never edited, and the only one the calculations read;
// - ANULADO: a VIGENTE annulled with its motivo, who and when. history: nothing reads it, and its key is free again
object EstadoParametro {
    const val POR_VERIFICAR = "POR_VERIFICAR"
    const val VIGENTE = "VIGENTE"
    const val ANULADO = "ANULADO"

    val TODOS = listOf(POR_VERIFICAR, VIGENTE, ANULADO)

    // a stored estado: empty is VIGENTE (the rows written before the field, and the importers' until the migration)
    fun de(valor: Any?): String = valor?.toString()?.trim()?.ifEmpty { null } ?: VIGENTE
}

// the fields of an anulación, and the data a transcriber writes (what verifying or annulling must leave alone)
object CamposParametro {
    const val ESTADO = "estado"
    const val TRANSCRIBIO = "transcribio"
    const val VERIFICO = "verifico"
    const val MOTIVO_ANULACION = "motivo_anulacion"
    const val ANULADO_POR = "anulado_por"
    const val ANULADO_EN = "anulado_en"

    val ANULACION = listOf(MOTIVO_ANULACION, ANULADO_POR, ANULADO_EN)

    val DATOS = listOf("tipo", "clave", "vigencia_desde", "vigencia_hasta", "valor_numerico", "texto", "norma", "fuente")
}

// what a calculation reads: a VIGENTE row, or one without estado
fun ParametroTributario.rige(): Boolean = EstadoParametro.de(estado) == EstadoParametro.VIGENTE

// who signs: normativa's signature is a name and a date ("ANA, 2026-01-05"), srtm's an email. the person is the part
// before the comma, in any case
fun firmante(firma: String?): String =
    firma
        .orEmpty()
        .substringBefore(',')
        .trim()
        .uppercase(Locale.ROOT)

// the natural key of a row: tipo, clave (empty when the tipo has none) and vigencia_desde
fun llaveDe(p: ParametroTributario): String = listOf(p.tipo.orEmpty(), p.clave.orEmpty(), p.vigenciaDesde?.toString().orEmpty()).joinToString("|")

// two rows of the same tipo and clave whose vigencias share a day (an empty vigencia_hasta is open)
fun seSolapan(
    a: ParametroTributario,
    b: ParametroTributario
): Boolean {
    if (a.tipo != b.tipo || a.clave.orEmpty() != b.clave.orEmpty()) return false
    val desdeA = a.vigenciaDesde ?: return false
    val desdeB = b.vigenciaDesde ?: return false
    return (b.vigenciaHasta == null || !desdeA.isAfter(b.vigenciaHasta)) && (a.vigenciaHasta == null || !desdeB.isAfter(a.vigenciaHasta))
}

// a row as a person reads it: UIT 2026-01-01..2026-12-31, TRAMO_PREDIAL 1 2004-11-15..
fun nombreDe(p: ParametroTributario): String =
    listOfNotNull(p.tipo, p.clave?.ifBlank { null }, "${p.vigenciaDesde}..${p.vigenciaHasta ?: ""}").joinToString(" ")
