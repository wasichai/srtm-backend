package srtm.visor

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import srtm.brecha.Brecha
import srtm.brecha.Categoria
import srtm.brecha.Confianza
import srtm.brecha.NivelBrecha
import srtm.brecha.RegistroCorrida
import srtm.reportes.ESTADOS_DECISION
import srtm.reportes.FilaReportePredio
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal

// the visor de brechas (#260): catastro-ui's Brechas panel (catastro-backend's BrechasService), read only, over srtm's
// own corrida, which is what catastro loads as its corte. the same shape and the same snake_case names, so the same
// numbers come out on both sides. a row's estado is catastro's decision_brecha of the year (PENDIENTE without one,
// null when catastro could not be read); its ficha is its codigo_catastro, and a row without one (SIN_CATASTRO) cannot
// be decided: it is no pendiente and counts in sin_ficha, as catastro's rows without codigo_municipal do

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FilaVisor(
    val id: String?,
    val predio: String?,
    val declaracion: String?,
    val codigoCatastro: String?,
    val codigoPredio: String?,
    val sector: String?,
    val manzana: String?,
    val lote: String?,
    val uso: String?,
    val usoDescripcion: String?,
    val categoria: String?,
    val confianza: String?,
    val autoavaluoDeclarado: BigDecimal?,
    val autoavaluoCatastro: BigDecimal?,
    val deltaAutoavaluo: BigDecimal?,
    val impuestoDeclarado: BigDecimal?,
    val impuestoCatastro: BigDecimal?,
    val deltaImpuesto: BigDecimal?,
    val deltaArbitrios: BigDecimal?,
    val estimado: Boolean,
    // |Δ autoavalúo| above 5 UIT of 1 January (importar_corte_brecha.py's rule); null without the UIT or the Δ
    @get:JsonProperty("supera_5_uit") val supera5Uit: Boolean?,
    val estado: String?,
    val conGeometria: Boolean,
    val nivelesComparados: Int?
) {
    val conFicha: Boolean @JsonIgnore get() = codigoCatastro != null
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CorridaDelVisor(
    val id: String,
    val anio: Int?,
    val estado: String?,
    val terminado: String?,
    val versionCatastro: String?
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class KpisVisor(
    val fichas: Int,
    val deltaImpuesto: BigDecimal,
    val deltaImpuestoConfiable: BigDecimal,
    val omisosEstimado: BigDecimal,
    @get:JsonProperty("supera_5_uit") val supera5Uit: Int,
    // null when catastro's decisions could not be read (avisos says why)
    val pendientes: Int?,
    val conGeometria: Int,
    // rows without a ficha: they cannot be decided, so they are no pendientes
    val sinFicha: Int
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CuentaVisor(
    val clave: String?,
    val fichas: Int,
    val deltaImpuesto: BigDecimal
)

data class CuentaDeEstadoVisor(
    val clave: String,
    val fichas: Int
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SectorVisor(
    val sector: String,
    val fichas: Int,
    val deltaImpuesto: BigDecimal
)

// GET /visor/panel. avisos: what could not be read (catastro's decisions, the UIT), as the reportes say it
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PanelVisor(
    val corrida: CorridaDelVisor,
    val kpis: KpisVisor,
    val porCategoria: List<CuentaVisor>,
    val porConfianza: List<CuentaVisor>,
    val porEstado: List<CuentaDeEstadoVisor>,
    val topSectores: List<SectorVisor>,
    val avisos: List<String>
)

// a piso of a predio (brecha_nivel): delta is area_catastro − area_rentas, when it has both
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NivelVisor(
    val piso: Int?,
    val areaRentas: BigDecimal?,
    val areaCatastro: BigDecimal?,
    val estado: String?,
    val delta: BigDecimal?
)

// /predios, /lotes and /predios.csv: every filter given must hold. a set holds when the row's value is one of it;
// sector compares as the cruce compares codes (Brecha.llave: 01 is 1); min_delta against |delta_impuesto|, so a row
// without one is out; supera_5_uit false takes the rows without the mark too
data class FiltroVisor(
    val categorias: Set<String>? = null,
    val confianzas: Set<String>? = null,
    val sector: String? = null,
    val estados: Set<String>? = null,
    val supera5Uit: Boolean? = null,
    val minDelta: BigDecimal? = null
) {
    private val llaveSector = Brecha.llave(sector)

    fun admite(f: FilaVisor): Boolean =
        (categorias == null || f.categoria in categorias) &&
            (confianzas == null || f.confianza in confianzas) &&
            (llaveSector == null || Brecha.llave(f.sector) == llaveSector) &&
            (estados == null || f.estado in estados) &&
            (supera5Uit == null || (f.supera5Uit ?: false) == supera5Uit) &&
            (minDelta == null || (f.deltaImpuesto != null && f.deltaImpuesto.abs() >= minDelta))
}

// catastro's orders: by Δ impuesto (a row without one last), or by código de catastro, then the padrón's and the id
enum class OrdenVisor(
    val parametro: String,
    val comparador: Comparator<FilaVisor>
) {
    DELTA_DESC("delta_desc", compareBy<FilaVisor, BigDecimal?>(nullsLast(reverseOrder())) { it.deltaImpuesto }.then(porCodigo())),
    DELTA_ASC("delta_asc", compareBy<FilaVisor, BigDecimal?>(nullsLast()) { it.deltaImpuesto }.then(porCodigo())),
    CODIGO("codigo", porCodigo());

    companion object {
        fun de(parametro: String): OrdenVisor? = entries.firstOrNull { it.parametro == parametro }
    }
}

private fun porCodigo(): Comparator<FilaVisor> =
    compareBy<FilaVisor, String?>(nullsLast()) { it.codigoCatastro }
        .thenBy(nullsLast()) { it.codigoPredio }
        .thenBy(nullsLast()) { it.id }

object Visor {
    const val PENDIENTE = "PENDIENTE"
    const val TOP_SECTORES = 10
    private val CERO = BigDecimal.ZERO.setScale(2)

    // the reportes' rows (ReportesService.leer) as the visor shows them. conDecisiones false: catastro was not read,
    // no row has an estado. umbral: 5 UIT, null without the UIT
    fun filas(
        filas: List<FilaReportePredio>,
        conDecisiones: Boolean,
        umbral: BigDecimal?
    ): List<FilaVisor> =
        filas.map { f ->
            FilaVisor(
                id = f.id,
                predio = f.predio,
                declaracion = f.declaracion,
                codigoCatastro = f.codigoCatastro,
                codigoPredio = f.codigoPredio,
                sector = f.sector,
                manzana = f.manzana,
                lote = f.lote,
                uso = f.uso,
                usoDescripcion = f.usoDescripcion,
                categoria = f.categoria,
                confianza = f.confianza,
                autoavaluoDeclarado = f.autoavaluoDeclarado,
                autoavaluoCatastro = f.autoavaluoCatastro,
                deltaAutoavaluo = f.deltaAutoavaluo,
                impuestoDeclarado = f.impuestoDeclarado,
                impuestoCatastro = f.impuestoCatastro,
                deltaImpuesto = f.deltaImpuesto,
                deltaArbitrios = f.deltaArbitrios,
                estimado = f.estimado,
                supera5Uit = if (umbral == null) null else f.deltaAutoavaluo?.let { it.abs() > umbral },
                estado = if (conDecisiones) f.decision ?: PENDIENTE else null,
                conGeometria = f.conGeometria,
                nivelesComparados = f.nivelesComparados
            )
        }

    // the panel in one pass, as catastro's panel(): every categoria and estado (also those without rows), every
    // confianza and the rows without one as a null clave (only when there is one), the TOP_SECTORES of largest Δ
    fun panel(
        corrida: RegistroCorrida,
        filas: List<FilaVisor>,
        conDecisiones: Boolean,
        avisos: List<String>
    ): PanelVisor {
        var delta = CERO
        var confiable = CERO
        var omisos = CERO
        var supera = 0
        var pendientes = 0
        var conGeometria = 0
        var sinFicha = 0
        val categorias = linkedMapOf<String?, Acumulado>().apply { Categoria.entries.forEach { put(it.name, Acumulado()) } }
        val confianzas = linkedMapOf<String?, Acumulado>().apply { Confianza.entries.forEach { put(it.name, Acumulado()) } }
        val estados = linkedMapOf<String, Int>().apply { ESTADOS_DECISION.forEach { put(it, 0) } }
        val sectores = hashMapOf<String, Acumulado>()
        for (f in filas) {
            val d = f.deltaImpuesto ?: BigDecimal.ZERO
            delta += d
            if (f.confianza == Confianza.CONFIABLE.name) confiable += d
            if (f.categoria == Categoria.OMISO.name) omisos += d
            if (f.supera5Uit == true) supera++
            if (!f.conFicha) {
                sinFicha++
            } else if (f.estado == PENDIENTE) {
                pendientes++
            }
            if (f.conGeometria) conGeometria++
            categorias.getOrPut(f.categoria) { Acumulado() }.suma(d)
            confianzas.getOrPut(f.confianza) { Acumulado() }.suma(d)
            if (f.conFicha && f.estado != null) estados.merge(f.estado, 1, Int::plus)
            f.sector
                ?.trim()
                ?.ifEmpty { null }
                ?.let { sectores.getOrPut(it) { Acumulado() }.suma(d) }
        }
        return PanelVisor(
            corrida = CorridaDelVisor(corrida.id!!, corrida.anio, corrida.estado, corrida.terminado, corrida.versionCatastro),
            kpis = KpisVisor(filas.size, delta, confiable, omisos, supera, pendientes.takeIf { conDecisiones }, conGeometria, sinFicha),
            porCategoria = categorias.map { (clave, a) -> CuentaVisor(clave, a.fichas, a.delta) },
            porConfianza = confianzas.filter { (clave, a) -> clave != null || a.fichas > 0 }.map { (clave, a) -> CuentaVisor(clave, a.fichas, a.delta) },
            porEstado = estados.map { (clave, n) -> CuentaDeEstadoVisor(clave, n) },
            topSectores =
                sectores.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Acumulado>> { it.value.delta }.thenBy { it.key })
                    .take(TOP_SECTORES)
                    .map { (sector, a) -> SectorVisor(sector, a.fichas, a.delta) },
            avisos = avisos
        )
    }

    // the rows that match and have a lote (lotes: brecha_predio id -> its geometry, GeoJSON in EPSG:4326, as a map or
    // as its json: ReportesService's LoteJson), one Feature each with the properties the map colours by. its id is the
    // brecha_predio's: /predios/{id}/niveles
    fun lotes(
        filas: List<FilaVisor>,
        lotes: Map<String, Any>,
        filtro: FiltroVisor
    ): Map<String, Any?> =
        mapOf(
            "type" to "FeatureCollection",
            "features" to
                filas.filter(filtro::admite).mapNotNull { f ->
                    val geometria = f.id?.let(lotes::get) ?: return@mapNotNull null
                    mapOf(
                        "type" to "Feature",
                        "id" to f.id,
                        "geometry" to geometria,
                        "properties" to
                            linkedMapOf(
                                "codigo" to (f.codigoPredio ?: f.codigoCatastro),
                                "codigo_catastro" to f.codigoCatastro,
                                "codigo_predio" to f.codigoPredio,
                                "categoria" to f.categoria,
                                "confianza" to f.confianza,
                                "delta_impuesto" to f.deltaImpuesto,
                                "estado" to f.estado,
                                "supera_5_uit" to f.supera5Uit
                            )
                    )
                }
        )

    // by piso (a sótano is negative, so it goes first), the ones without piso last
    fun niveles(leidos: List<NivelBrecha>): List<NivelVisor> =
        leidos
            .sortedWith(compareBy(nullsLast()) { it.piso })
            .map {
                val delta = if (it.areaRentas != null && it.areaCatastro != null) it.areaCatastro - it.areaRentas else null
                NivelVisor(it.piso, it.areaRentas, it.areaCatastro, it.estado, delta)
            }

    private class Acumulado {
        var fichas = 0
        var delta: BigDecimal = CERO

        fun suma(d: BigDecimal) {
            fichas++
            delta += d
        }
    }
}
