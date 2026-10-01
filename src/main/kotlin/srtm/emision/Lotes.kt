package srtm.emision

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

// the lotes of a masiva (wasichai/srtm-backend#53, #54), apart from core: how the padron is cut, how a lote's
// contribuyentes and errores are kept as json, and how many workers an instance runs

private val JSON: JsonMapper =
    JsonMapper
        .builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

// the padron in lotes of `tamano`, in its order: the parts are assembled in the lotes' order
fun cortarEnLotes(
    contribuyentes: List<ContribuyenteAEmitir>,
    tamano: Int
): List<List<ContribuyenteAEmitir>> {
    require(tamano >= 1) { "El tamaño de un lote debe ser al menos 1: $tamano" }
    return contribuyentes.chunked(tamano)
}

fun contribuyentesJson(lote: List<ContribuyenteAEmitir>): String = JSON.writeValueAsString(lote)

fun contribuyentesDe(json: String): List<ContribuyenteAEmitir> = JSON.readValue(json)

fun erroresJson(errores: List<ErrorEmision>): String = JSON.writeValueAsString(errores)

fun erroresDe(json: String?): List<ErrorEmision> = json?.takeIf { it.isNotBlank() }?.let { JSON.readValue(it) } ?: emptyList()

// what a worker needs besides the jvm's base, and that base (a document in memory, the pdfbox of its merge)
private const val MIB = 1024L * 1024
private const val BASE = 512 * MIB
private const val POR_TRABAJADOR = 300 * MIB

// a worker holds a connection while it writes its progress: two of the pool are left for the requests
private const val CONEXIONES_LIBRES = 2

// srtm.emision.trabajadores: "auto" is one per core as far as the memory goes (at least one); a number is taken as is,
// 0 being an instance that runs none. neither takes more than the pool allows
fun cuantosTrabajadores(
    valor: String,
    nucleos: Int,
    memoriaMaxima: Long,
    poolMaximo: Int
): Int {
    val pedidos =
        if (valor.trim().equals("auto", ignoreCase = true)) {
            val porMemoria = ((memoriaMaxima - BASE) / POR_TRABAJADOR).coerceAtMost(nucleos.toLong()).toInt()
            porMemoria.coerceIn(1, maxOf(1, nucleos))
        } else {
            valor.trim().toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("srtm.emision.trabajadores debe ser auto o un número desde 0: $valor")
        }
    return minOf(pedidos, maxOf(1, poolMaximo - CONEXIONES_LIBRES))
}
