package srtm.brecha

import com.fasterxml.jackson.annotation.JsonIgnore
import srtm.Faltan
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal

// model/model.json
const val BRECHA_CORRIDA = "brecha_corrida"
const val BRECHA_PREDIO = "brecha_predio"
const val BRECHA_NIVEL = "brecha_nivel"

// cambio_catastro's objeto (#203), as catastro-backend names it
val OBJETOS_CAMBIO_CATASTRO = setOf("predio_catastral", "construccion", "obra_complementaria", "titular_catastral")

// what keeps GET /{anio}/cambios from answering: the UIT of 1 de enero del año, to decide SUPERA_5_UIT
class FaltanBrecha(
    faltan: List<String>
) : Faltan("Faltan parámetros para el aviso de cambios: ${faltan.joinToString("; ")}", faltan)

const val PENDIENTE = "PENDIENTE"
const val EN_CURSO = "EN_CURSO"
const val TERMINADA = "TERMINADA"
const val FALLIDA = "FALLIDA"
val ACTIVAS = setOf(PENDIENTE, EN_CURSO)

// who launched a corrida (#203): a POST (MANUAL), or an automatic one from the catastro feed's cambios (CAMBIOS) or
// its weekly fallback (SEMANAL)
enum class OrigenCorrida { MANUAL, CAMBIOS, SEMANAL }

// catastro's motivo for a rústico predio (#209): it has no rústico aranceles (per hectárea) yet, so it never values
// one, even with a ficha. used by the autoavalúo sugerido and the brecha (task 6)
const val PREDIO_RUSTICO = "PREDIO_RUSTICO"

// #204: an item's estadoLevantamiento when its ficha has no levantamiento yet (autoavaluo is null, motivo is this)
const val SIN_LEVANTAR = "SIN_LEVANTAR"

// #204: an item's vinculoMetodo when catastro matched it to the padrón by the predio's codigo_rentas, or by hand
const val CODIGO_RENTAS = "CODIGO_RENTAS"
const val MANUAL = "MANUAL"

// what a catastro item is next to the padrón (D7, D10): never in the padrón (OMISO), a padrón predio the catastro
// lacks (SIN_CATASTRO), crossed but not valued (SIN_VALOR), or crossed and valued above, below or within ±5 % of its
// declared autoavalúo. #204: a ficha sin levantar is SIN_LEVANTAR, crossed or not, with no difference
enum class Categoria { OMISO, SIN_CATASTRO, SIN_VALOR, SUBVALUADO, SOBREVALUADO, COINCIDE, SIN_LEVANTAR }

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
    val loteGeom: Map<String, Any?>? = null,
    // #204: its ficha's levantamiento (SIN_LEVANTAR, PARCIAL, COMPLETO) and when, and the entrega that last touched it
    val estadoLevantamiento: String? = null,
    val fechaLevantamiento: String? = null,
    val entrega: String? = null,
    // #204: how it was crossed to the padrón (CODIGO_RENTAS, LLAVE_HU_MZ_LOTE, GEOMETRICO, MANUAL), whether a person
    // checked it, and its cuc and codigoReferenciaCatastral (the catastro's own ids, not the padrón's)
    val vinculoMetodo: String? = null,
    val vinculoRevisado: Boolean? = null,
    val cuc: String? = null,
    val codigoReferenciaCatastral: String? = null,
    // #247 (catastro-backend#71): each piso and sección of its ficha, [] when it has none; null from an older catastro
    // that does not send them (then the row compares no nivel)
    val construcciones: List<ConstruccionCatastral>? = null
)

// one construcción of a ValuacionCatastral: piso null when the ficha's is no number (S1, a sótano), and a piso may
// repeat across secciones
data class ConstruccionCatastral(
    val piso: Int? = null,
    val seccion: Int? = null,
    val areaConstruida: BigDecimal? = null
)

// #247: how a piso of a predio paired (Brecha.niveles): once on each side (PAR), on one side only (SOLO_RENTAS,
// SOLO_CATASTRO), or not pairable (AMBIGUO: rentas repeats it on separate niveles, or it has no number)
enum class EstadoNivel { PAR, SOLO_RENTAS, SOLO_CATASTRO, AMBIGUO }

// #247: the brecha_nivel record (its corrida and brecha_predio are added when it is written): a piso, the area each
// side gives it (null when that side has none) and its EstadoNivel
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NivelBrecha(
    val piso: Int? = null,
    val areaRentas: BigDecimal? = null,
    val areaCatastro: BigDecimal? = null,
    val estado: String? = null
)

// #247: what a row sums of its niveles: how many are PAR, how many PAR have a catastro area larger than a positive
// rentas one, and the PAR's positive differences (catastro - rentas, m2)
data class ResumenDeNiveles(
    val comparados: Int,
    val catastroMayor: Int,
    val delta: BigDecimal
)

// every valued item of a year, and the version of the engine that valued them
data class Valuaciones(
    val anio: Int,
    val version: String?,
    val items: List<ValuacionCatastral>
)

// one titular of GET /api/catastro/titulares: a padrón predio may have several, each its share (porcentaje) and
// condicion (e.g. PROPIETARIO)
data class TitularCatastral(
    val codigoMunicipal: String?,
    val tipoDocumento: String?,
    val numeroDocumento: String?,
    val nombre: String?,
    val porcentaje: BigDecimal?,
    val condicion: String?
)

// one item of GET /api/catastro/cambios (#203): what catastro's objeto (predio_catastral, construccion,
// obra_complementaria or titular_catastral) did since desde (CREATE, UPDATE or DELETE), which campos changed, en
// (when, ISO-8601) and the entrega that carried it (or null)
data class CambioCatastral(
    val codigoMunicipal: String?,
    val objeto: String?,
    val operacion: String?,
    val campos: List<String>,
    val en: String?,
    val entrega: String?
)

// one page of GET /api/catastro/cambios, oldest first: cursor is opaque (pass it back as desde for the next page),
// hayMas says whether there is more after this page
data class PaginaDeCambios(
    val items: List<CambioCatastral>,
    val cursor: String?,
    val hayMas: Boolean
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
    val latido: String? = null,
    // #204: the rows and Δ impuesto of each confianza (PanelBrecha.porConfianza), null before it was measured
    val prediosConfiables: Int? = null,
    val prediosDudosos: Int? = null,
    val prediosSinLevantar: Int? = null,
    val deltaImpuestoConfiable: BigDecimal? = null,
    val deltaImpuestoDudoso: BigDecimal? = null,
    val deltaImpuestoSinLevantar: BigDecimal? = null,
    // #203: who launched it (null is an older MANUAL, from before this field)
    val origen: String? = null
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
    val orden: Int? = null,
    // #208: the DJ's obras the catastro side added, its ficha without obras (Brecha.conObrasDeLaDj)
    val obrasDj: BigDecimal? = null,
    // #204: how sure its cruce is (Confianza) and why (Confiabilidad.de)
    val confianza: String? = null,
    val confianzaMotivo: String? = null,
    // #247: the terreno and construida (m2) of the vigente DJ (each secuencia once, Brecha.areaDeclarada) and of the
    // catastro's item; the niveles' summary (Brecha.resumenDeNiveles), null when they were not compared
    val areaTerrenoDeclarada: BigDecimal? = null,
    val areaTerrenoCatastro: BigDecimal? = null,
    val areaConstruidaDeclarada: BigDecimal? = null,
    val areaConstruidaCatastro: BigDecimal? = null,
    val nivelesComparados: Int? = null,
    val nivelesCatastroMayor: Int? = null,
    val deltaAreaNiveles: BigDecimal? = null,
    // #247: its brecha_nivel rows, written apart (never a field of brecha_predio): null when they were not compared
    @get:JsonIgnore
    val niveles: List<NivelBrecha>? = null
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
    val deltaArbitrios: BigDecimal?,
    val prediosConfiables: Int?,
    val prediosDudosos: Int?,
    val prediosSinLevantar: Int?,
    val deltaImpuestoConfiable: BigDecimal?,
    val deltaImpuestoDudoso: BigDecimal?,
    val deltaImpuestoSinLevantar: BigDecimal?,
    val origen: String?
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
        deltaArbitrios = r.deltaArbitrios,
        prediosConfiables = r.prediosConfiables,
        prediosDudosos = r.prediosDudosos,
        prediosSinLevantar = r.prediosSinLevantar,
        deltaImpuestoConfiable = r.deltaImpuestoConfiable,
        deltaImpuestoDudoso = r.deltaImpuestoDudoso,
        deltaImpuestoSinLevantar = r.deltaImpuestoSinLevantar,
        origen = r.origen
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
    val motivo: String?,
    val obrasDj: BigDecimal?,
    val confianza: String?,
    val confianzaMotivo: String?,
    val areaTerrenoDeclarada: BigDecimal?,
    val areaTerrenoCatastro: BigDecimal?,
    val areaConstruidaDeclarada: BigDecimal?,
    val areaConstruidaCatastro: BigDecimal?,
    val nivelesComparados: Int?,
    val nivelesCatastroMayor: Int?,
    val deltaAreaNiveles: BigDecimal?
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
        motivo = f.motivo,
        obrasDj = f.obrasDj,
        confianza = f.confianza,
        confianzaMotivo = f.confianzaMotivo,
        areaTerrenoDeclarada = f.areaTerrenoDeclarada,
        areaTerrenoCatastro = f.areaTerrenoCatastro,
        areaConstruidaDeclarada = f.areaConstruidaDeclarada,
        areaConstruidaCatastro = f.areaConstruidaCatastro,
        nivelesComparados = f.nivelesComparados,
        nivelesCatastroMayor = f.nivelesCatastroMayor,
        deltaAreaNiveles = f.deltaAreaNiveles
    )

data class PedidoCorrida(
    val anio: Int? = null
)

// GET /brecha/{anio}/panel. porConfianza: every Confianza, in its order (#204)
data class PanelBrecha(
    val corrida: CorridaDelPanel,
    val kpis: Kpis,
    val porCategoria: List<PorCategoria>,
    val porConfianza: Map<String, PorConfianza>,
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
    // #204: the fichas sin levantar, crossed or not
    val sinLevantar: Int = 0,
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

// the rows of a confianza (#204)
data class PorConfianza(
    val predios: Int,
    val deltaImpuesto: BigDecimal,
    val deltaAutoavaluo: BigDecimal
)

data class PorSector(
    val sector: String?,
    val predios: Int,
    val deltaImpuesto: BigDecimal,
    val deltaArbitrios: BigDecimal?,
    val omisos: Int,
    val subvaluados: Int
)
