package srtm.brecha

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// model/model.json
const val SINCRONIZACION_CATASTRO = "sincronizacion_catastro"
const val CAMBIO_CATASTRO = "cambio_catastro"

// why an automatic corrida ran, or why it did not (#203): CAMBIOS from pendientes (or a retry of a FALLIDA one),
// SEMANAL when there was none in the last 7 days, NINGUNO before the hour, already run today, or neither applies
enum class Disparo { NINGUNO, CAMBIOS, SEMANAL }

// one cambio_catastro record as core keeps it
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RegistroCambio(
    val id: String? = null,
    val codigoMunicipal: String? = null,
    val objeto: String? = null,
    val operacion: String? = null,
    val campos: String? = null,
    val en: String? = null,
    val entrega: String? = null
)

// the sincronizacion_catastro record: one per organization, written by the background reader under ClusterLock
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RegistroSincronizacion(
    val id: String? = null,
    val cursor: String? = null,
    val ultimoCambioEn: String? = null,
    val ultimaCorrida: String? = null,
    val pendientes: Int? = null,
    val error: String? = null
)

// GET /{anio}/cambios: a predio changed since desde, its cambios (how many, their objetos and entregas, the latest
// one) next to the last TERMINADA corrida's row, when it has one
data class CambioDelPredio(
    val codigoMunicipal: String,
    val predio: String?,
    val codigoPredio: String?,
    val categoria: String?,
    val cambios: Int,
    val objetos: List<String>,
    val entregas: List<String>,
    val ultimoCambio: String?,
    val autoavaluoDeclarado: BigDecimal?,
    val autoavaluoCatastro: BigDecimal?,
    val deltaAutoavaluo: BigDecimal?,
    val marca: String?
)

// pure: when the catastro feed launches an automatic corrida, and the aviso of what it changed. no Spring, no I/O
object Seguimiento {
    val LIMA: ZoneId = ZoneId.of("America/Lima")

    const val SUPERA_5_UIT = "SUPERA_5_UIT"

    private val CINCO = BigDecimal(5)

    // the schedule (ruling #203): nothing before `hora` (Lima); at most one automatic corrida a day, so none once
    // one already ran today; then CAMBIOS while there are pendientes or the last automatic corrida is FALLIDA;
    // else SEMANAL when there was none, or the last one's calendar date is 7 or more days behind ahora's
    fun disparo(
        ahora: ZonedDateTime,
        hora: Int,
        pendientes: Int,
        ultima: Instant?,
        ultimaFallida: Boolean
    ): Disparo {
        val enLima = ahora.withZoneSameInstant(LIMA)
        if (enLima.hour < hora) return Disparo.NINGUNO
        val hoy = enLima.toLocalDate()
        val fechaUltima = ultima?.atZone(LIMA)?.toLocalDate()
        if (fechaUltima == hoy) return Disparo.NINGUNO
        if (pendientes > 0 || ultimaFallida) return Disparo.CAMBIOS
        if (fechaUltima == null || fechaUltima <= hoy.minusDays(7)) return Disparo.SEMANAL
        return Disparo.NINGUNO
    }

    // the aviso (ruling #203): one row per código municipal the cambios name (joined by Brecha.llave; one without a
    // code is left out), next to the row of the same llave (if any). marked rows first, then |Δ| from highest
    // (nulls last), then by código
    fun aviso(
        cambios: List<RegistroCambio>,
        filas: List<FilaBrecha>,
        uit: BigDecimal
    ): List<CambioDelPredio> {
        val umbral = uit.multiply(CINCO)
        val porLlave = filas.mapNotNull { f -> Brecha.llave(f.codigoCatastro)?.let { it to f } }.toMap()
        val porCodigo =
            cambios
                .mapNotNull { c -> Brecha.llave(c.codigoMunicipal)?.let { it to c } }
                .groupBy({ it.first }, { it.second })
        return porCodigo
            .map { (llave, suyos) ->
                val fila = porLlave[llave]
                val delta = fila?.deltaAutoavaluo
                CambioDelPredio(
                    codigoMunicipal = suyos.first().codigoMunicipal!!,
                    predio = fila?.predio,
                    codigoPredio = fila?.codigoPredio,
                    categoria = fila?.categoria,
                    cambios = suyos.size,
                    objetos = suyos.mapNotNull { it.objeto },
                    entregas = suyos.mapNotNull { it.entrega },
                    ultimoCambio = suyos.mapNotNull { it.en }.maxOrNull(),
                    autoavaluoDeclarado = fila?.autoavaluoDeclarado,
                    autoavaluoCatastro = fila?.autoavaluoCatastro,
                    deltaAutoavaluo = delta,
                    marca = delta?.takeIf { it.abs() > umbral }?.let { SUPERA_5_UIT }
                )
            }.sortedWith(
                compareBy<CambioDelPredio> { it.marca == null }
                    .thenByDescending { it.deltaAutoavaluo?.abs() ?: BigDecimal.ONE.negate() }
                    .thenBy { it.codigoMunicipal }
            )
    }
}
