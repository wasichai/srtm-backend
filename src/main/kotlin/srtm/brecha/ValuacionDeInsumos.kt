package srtm.brecha

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal

// the body of catastro-backend's POST /api/catastro/valuacion (its PedidoValuacion): the engine's Insumos with
// catastro's json names. what is null stays undeclared there: the engine answers its motivo, never a zero. the
// letters are one capital, A to J; material, estado and clasificacion in the models' shared enum spelling
data class InsumosValuacion(
    val anio: Int,
    val areaTerreno: BigDecimal?,
    val arancelM2: BigDecimal?,
    // six-digit uso_predio code
    val uso: String?,
    val esEdificio: Boolean,
    val construcciones: List<ConstruccionValuacion>,
    val obras: List<ObraValuacion>,
    // the ficha whose arancel catastro takes when arancelM2 is null
    val codigoMunicipal: String?,
    // "URBANO" or "RUSTICO" (AutoavaluoSugerido.tipoPredio); null when the predio's own is none of those
    val tipoPredio: String? = null
)

// piso: "1", "S1" (sótano), "M1" (mezanine), "AZ" (azotea)
data class ConstruccionValuacion(
    val piso: String,
    val areaConstruida: BigDecimal?,
    val anioConstruccion: Int?,
    val material: String?,
    val estado: String?,
    val muros: String?,
    val techos: String?,
    val puertas: String?,
    val techoIncluido: Boolean? = null,
    val clasificacion: String? = null,
    val seccion: Int? = null
)

// codigoVu: "TIPO DE OBRA|numero", the partida of the cuadro; cantidad in the partida's unit (m2, ml, und...)
data class ObraValuacion(
    val codigoVu: String?,
    val cantidad: BigDecimal?,
    val anioConstruccion: Int?,
    val material: String?,
    val estado: String?
)

// catastro's answer, in srtm's json (snake_case, as the portal's dtos). not valued: terreno, construccion, obras,
// autoavaluo and detalle are null and motivo and llave say why (ARANCEL_FALTANTE, PARAMETRO_FALTANTE:...).
// obras_padron_origen is srtm's: the external padrón's otras instalaciones it added (Derivados.conObrasDelPadron)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ValuacionDeInsumos(
    val anio: Int?,
    val version: String?,
    val region: String?,
    val codigoMunicipal: String?,
    val arancelM2: BigDecimal?,
    // CUERPO (the declaración's arancel), FICHA (the catastro's ficha) or null
    val origenArancel: String?,
    val terreno: BigDecimal?,
    val construccion: BigDecimal?,
    val obras: BigDecimal?,
    val autoavaluo: BigDecimal?,
    val motivo: String?,
    val llave: String?,
    val detalle: DetalleValuacion?,
    val obrasPadronOrigen: BigDecimal? = null
)

// how the engine got to construccion and obras: Σ valor of each list is that total, in the body's order
data class DetalleValuacion(
    val pisos: List<DetallePisoValuacion>,
    val obras: List<DetalleObraValuacion>
)

// incremento: what the edificio's 5 % adds to the VU (0 below its floor); valor_unitario_depreciado =
// (valor_unitario + incremento) x (1 - porcentaje_depreciacion / 100)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DetallePisoValuacion(
    val piso: String?,
    val seccion: Int?,
    val areaConstruida: BigDecimal?,
    val valorUnitario: BigDecimal?,
    val incremento: BigDecimal?,
    val tablaDepreciacion: String?,
    val antiguedad: Int?,
    val porcentajeDepreciacion: BigDecimal?,
    val valorUnitarioDepreciado: BigDecimal?,
    val valor: BigDecimal?
)

// descripcion names a row srtm adds (codigo_vu null): srtm.rentas.OBRAS_DEL_PADRON. catastro's rows have none
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DetalleObraValuacion(
    val codigoVu: String?,
    val cantidad: BigDecimal?,
    val valorUnitario: BigDecimal?,
    val factorOficializacion: BigDecimal?,
    val tablaDepreciacion: String?,
    val antiguedad: Int?,
    val porcentajeDepreciacion: BigDecimal?,
    val valor: BigDecimal?,
    val descripcion: String? = null
)
