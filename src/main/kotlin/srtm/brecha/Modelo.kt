package srtm.brecha

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal

// model/model.json
const val BRECHA_CORRIDA = "brecha_corrida"
const val BRECHA_PREDIO = "brecha_predio"

const val PENDIENTE = "PENDIENTE"
const val EN_CURSO = "EN_CURSO"
const val TERMINADA = "TERMINADA"
const val FALLIDA = "FALLIDA"
val ACTIVAS = setOf(PENDIENTE, EN_CURSO)

// what a catastro item is next to the padrón (D7, D10): never in the padrón (OMISO), a padrón predio the catastro
// lacks (SIN_CATASTRO), crossed but not valued (SIN_VALOR), or crossed and valued above, below or within ±5 % of its
// declared autoavalúo
enum class Categoria { OMISO, SIN_CATASTRO, SIN_VALOR, SUBVALUADO, SOBREVALUADO, COINCIDE }

// one item of catastro-backend's GET /api/catastro/valuaciones: a predio catastral valued for the year. autoavaluo is
// null when it could not be valued (motivo says why); loteGeom is its lote, GeoJSON in EPSG:4326
data class ValuacionCatastral(
    val codigoMunicipal: String? = null,
    val codigoCpu: String? = null,
    val sector: String? = null,
    val manzana: String? = null,
    val lote: String? = null,
    val uso: String? = null,
    val areaTerreno: BigDecimal? = null,
    val areaConstruida: BigDecimal? = null,
    val longitudFrente: BigDecimal? = null,
    val terreno: BigDecimal? = null,
    val construccion: BigDecimal? = null,
    val obras: BigDecimal? = null,
    val autoavaluo: BigDecimal? = null,
    val motivo: String? = null,
    val llave: String? = null,
    val loteGeom: Map<String, Any?>? = null
)

// every valued item of a year, and the version of the engine that valued them
data class Valuaciones(
    val anio: Int,
    val version: String?,
    val items: List<ValuacionCatastral>
)

// the brecha_corrida record as core keeps it
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RegistroCorrida(
    val id: String? = null,
    val anio: Int? = null,
    val estado: String? = null,
    val versionCatastro: String? = null,
    val iniciado: String? = null,
    val terminado: String? = null,
    val totalPredios: Int? = null,
    val procesados: Int? = null,
    val mensaje: String? = null,
    val declarado: BigDecimal? = null,
    val catastro: BigDecimal? = null,
    val deltaImpuesto: BigDecimal? = null,
    val deltaArbitrios: BigDecimal? = null,
    val latido: String? = null
)

// the brecha_predio record: one per catastro item, plus one per padrón predio the catastro lacks. corrida and predio are
// the related records' ids; loteGeom is GeoJSON in EPSG:4326 (stored in UTM 18S by wasichai-gis)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FilaBrecha(
    val id: String? = null,
    val corrida: String? = null,
    val predio: String? = null,
    val codigoCatastro: String? = null,
    val codigoPredio: String? = null,
    val categoria: String? = null,
    val sector: String? = null,
    val manzana: String? = null,
    val lote: String? = null,
    val uso: String? = null,
    val autoavaluoDeclarado: BigDecimal? = null,
    val autoavaluoCatastro: BigDecimal? = null,
    val deltaAutoavaluo: BigDecimal? = null,
    val impuestoDeclarado: BigDecimal? = null,
    val impuestoCatastro: BigDecimal? = null,
    val deltaImpuesto: BigDecimal? = null,
    val deltaArbitrios: BigDecimal? = null,
    val estimado: Boolean? = null,
    val motivo: String? = null,
    val loteGeom: Map<String, Any?>? = null,
    // its position in /predios (Brecha.ordenadas)
    val orden: Int? = null
)

// the corrida as the portal shows it (camelCase, like /liquidacion)
data class Corrida(
    val id: String,
    val anio: Int?,
    val estado: String?,
    val versionCatastro: String?,
    val iniciado: String?,
    val terminado: String?,
    val totalPredios: Int,
    val procesados: Int,
    val mensaje: String?,
    val declarado: BigDecimal?,
    val catastro: BigDecimal?,
    val deltaImpuesto: BigDecimal?,
    val deltaArbitrios: BigDecimal?
)

fun corridaDe(r: RegistroCorrida) =
    Corrida(
        id = r.id!!,
        anio = r.anio,
        estado = r.estado,
        versionCatastro = r.versionCatastro,
        iniciado = r.iniciado,
        terminado = r.terminado,
        totalPredios = r.totalPredios ?: 0,
        procesados = r.procesados ?: 0,
        mensaje = r.mensaje,
        declarado = r.declarado,
        catastro = r.catastro,
        deltaImpuesto = r.deltaImpuesto,
        deltaArbitrios = r.deltaArbitrios
    )

// a row of GET /brecha/{anio}/predios
data class PredioBrecha(
    val id: String,
    val predio: String?,
    val codigoPredio: String?,
    val codigoCatastro: String?,
    val categoria: String?,
    val sector: String?,
    val manzana: String?,
    val lote: String?,
    val uso: String?,
    val autoavaluoDeclarado: BigDecimal?,
    val autoavaluoCatastro: BigDecimal?,
    val deltaAutoavaluo: BigDecimal?,
    val impuestoDeclarado: BigDecimal?,
    val impuestoCatastro: BigDecimal?,
    val deltaImpuesto: BigDecimal?,
    val deltaArbitrios: BigDecimal?,
    val estimado: Boolean,
    val motivo: String?
)

fun predioBrechaDe(f: FilaBrecha) =
    PredioBrecha(
        id = f.id!!,
        predio = f.predio,
        codigoPredio = f.codigoPredio,
        codigoCatastro = f.codigoCatastro,
        categoria = f.categoria,
        sector = f.sector,
        manzana = f.manzana,
        lote = f.lote,
        uso = f.uso,
        autoavaluoDeclarado = f.autoavaluoDeclarado,
        autoavaluoCatastro = f.autoavaluoCatastro,
        deltaAutoavaluo = f.deltaAutoavaluo,
        impuestoDeclarado = f.impuestoDeclarado,
        impuestoCatastro = f.impuestoCatastro,
        deltaImpuesto = f.deltaImpuesto,
        deltaArbitrios = f.deltaArbitrios,
        estimado = f.estimado == true,
        motivo = f.motivo
    )

data class PedidoCorrida(
    val anio: Int? = null
)

// GET /brecha/{anio}/panel
data class PanelBrecha(
    val corrida: CorridaDelPanel,
    val kpis: Kpis,
    val porCategoria: List<PorCategoria>,
    val porSector: List<PorSector>
)

data class CorridaDelPanel(
    val id: String,
    val terminado: String?,
    val versionCatastro: String?
)

data class Kpis(
    val prediosCatastro: Int,
    val prediosPadron: Int,
    val omisos: Int,
    val subvaluados: Int,
    val sobrevaluados: Int,
    val coinciden: Int,
    val sinCatastro: Int,
    val sinValor: Int,
    // over the rows compared (valued on both sides, and the omisos): declarado + Δ = catastro
    val autoavaluoDeclarado: BigDecimal,
    val autoavaluoCatastro: BigDecimal,
    val deltaAutoavaluo: BigDecimal,
    // net: what the subvaluados add less what the sobrevaluados take, plus the omisos' estimate
    val deltaImpuesto: BigDecimal,
    val deltaArbitrios: BigDecimal?,
    // the omisos' part of deltaImpuesto (each liquidated alone: an estimate)
    val deltaImpuestoOmisosEstimado: BigDecimal,
    // every declared autoavalúo of the padrón (SIN_CATASTRO and SIN_VALOR too) and every catastro value: they do not
    // reconcile with deltaAutoavaluo
    val autoavaluoDeclaradoTotal: BigDecimal,
    val autoavaluoCatastroTotal: BigDecimal
)

data class PorCategoria(
    val categoria: String,
    val predios: Int,
    val deltaAutoavaluo: BigDecimal,
    val deltaImpuesto: BigDecimal,
    val deltaArbitrios: BigDecimal?
)

data class PorSector(
    val sector: String?,
    val predios: Int,
    val deltaImpuesto: BigDecimal,
    val deltaArbitrios: BigDecimal?,
    val omisos: Int,
    val subvaluados: Int
)
