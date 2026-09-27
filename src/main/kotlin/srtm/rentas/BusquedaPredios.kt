package srtm.rentas

import wasichai.core.data.RecordCriterion

// the srtm's "buscar predios" filters (page 13): the same ones over the padrón (predio) and over the catastro fiscal.
// each filter maps to a field of the object searched; a text matches anywhere in the value (case-insensitive), an
// enum exactly. values are bound, never written into the sql
data class FiltrosPredio(
    val tipoPredio: String? = null,
    val codigo: String? = null,
    val codigoCpu: String? = null,
    val partidaRegistral: String? = null,
    val tipoVia: String? = null,
    val via: String? = null,
    val tipoZona: String? = null,
    val zona: String? = null,
    val numero: String? = null,
    val manzana: String? = null,
    val lote: String? = null,
    val kilometro: String? = null
) {
    companion object {
        fun of(params: Map<String, String>) =
            FiltrosPredio(
                tipoPredio = params["tipo_predio"],
                codigo = params["codigo"],
                codigoCpu = params["codigo_cpu"],
                partidaRegistral = params["partida_registral"],
                tipoVia = params["tipo_via"],
                via = params["via"],
                tipoZona = params["tipo_zona"],
                zona = params["zona"],
                numero = params["numero"],
                manzana = params["manzana"],
                lote = params["lote"],
                kilometro = params["kilometro"]
            )
    }
}

// a condition on one field: exact (an enum) or contained (a text)
data class Condicion(
    val field: String,
    val value: String,
    val exact: Boolean
)

// the padrón writes the tipo de predio as URBANO / RUSTICO, the catastro (and the srtm) as PREDIO URBANO / PREDIO RUSTICO
fun condicionDelPadron(tipoPredio: String): String = tipoPredio.removePrefix("PREDIO ").trim()

fun condicionesPredio(f: FiltrosPredio): List<Condicion> =
    listOfNotNull(
        f.tipoPredio?.let { Condicion("condicion", condicionDelPadron(it), exact = true) },
        f.codigo?.let { Condicion("codigo", it, exact = false) },
        f.codigoCpu?.let { Condicion("codigo_cpu", it, exact = false) },
        f.partidaRegistral?.let { Condicion("partida_registral", it, exact = false) },
        f.tipoVia?.let { Condicion("tipo_via", it, exact = true) },
        f.via?.let { Condicion("via", it, exact = false) },
        f.tipoZona?.let { Condicion("tipo_zona", it, exact = true) },
        f.zona?.let { Condicion("habilitacion_urbana", it, exact = false) },
        f.numero?.let { Condicion("numero", it, exact = false) },
        f.manzana?.let { Condicion("manzana", it, exact = false) },
        f.lote?.let { Condicion("lote", it, exact = false) },
        f.kilometro?.let { Condicion("kilometro", it, exact = false) }
    ).filter { it.value.isNotBlank() }

fun condicionesCatastro(f: FiltrosPredio): List<Condicion> =
    listOfNotNull(
        f.tipoPredio?.let { Condicion("tipo_predio", it, exact = true) },
        f.codigo?.let { Condicion("codigo_predio_municipal", it, exact = false) },
        f.codigoCpu?.let { Condicion("codigo_cpu", it, exact = false) },
        f.partidaRegistral?.let { Condicion("partida_registral", it, exact = false) },
        f.tipoVia?.let { Condicion("tipo_via", it, exact = true) },
        f.via?.let { Condicion("via", it, exact = false) },
        f.tipoZona?.let { Condicion("tipo_zona", it, exact = true) },
        f.zona?.let { Condicion("zona", it, exact = false) },
        f.numero?.let { Condicion("numero", it, exact = false) },
        f.manzana?.let { Condicion("manzana", it, exact = false) },
        f.lote?.let { Condicion("lote", it, exact = false) },
        f.kilometro?.let { Condicion("kilometro", it, exact = false) }
    ).filter { it.value.isNotBlank() }

// ILIKE's own wildcards in what the clerk typed are literal
fun contiene(value: String): String =
    "%" +
        value
            .trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"

// the conditions as one criterion, ANDed. a field the object does not have (removed in the admin) is skipped
fun criterio(condiciones: List<Condicion>): RecordCriterion? {
    if (condiciones.isEmpty()) return null
    return RecordCriterion { definition, bind ->
        val parts =
            condiciones.mapNotNull { c ->
                val column = definition.fields.firstOrNull { it.name == c.field }?.columnName ?: return@mapNotNull null
                if (c.exact) "$column = ${bind(c.value.trim())}" else "$column ILIKE ${bind(contiene(c.value))}"
            }
        if (parts.isEmpty()) "TRUE" else parts.joinToString(" AND ")
    }
}
