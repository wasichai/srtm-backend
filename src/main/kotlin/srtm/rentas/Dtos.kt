package srtm.rentas

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal

// the portal's own shapes. json keys are the model's field names (snake_case), so a validation
// violation from core (field = api name) lands on the same key the form uses

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Contribuyente(
    val id: String? = null,
    val tipoPersona: String? = null,
    val tipoDocumento: String? = null,
    val numeroDocumento: String? = null,
    val nombreCompleto: String? = null,
    val apellidoPaterno: String? = null,
    val apellidoMaterno: String? = null,
    val nombres: String? = null,
    val razonSocial: String? = null,
    val domicilioFiscal: String? = null,
    val domicilioDistrito: String? = null,
    val domicilioProvincia: String? = null,
    val domicilioDepartamento: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Predio(
    val id: String? = null,
    val codigo: String? = null,
    val sectorCatastral: String? = null,
    val manzanaCatastral: String? = null,
    val condicion: String? = null,
    val direccion: String? = null,
    val via: String? = null,
    val numero: String? = null,
    val manzana: String? = null,
    val lote: String? = null,
    val habilitacionUrbana: String? = null,
    val ubicacionAreaVerde: String? = null
)

// contribuyente and predio are the related records' ids
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Declaracion(
    val id: String? = null,
    val contribuyente: String? = null,
    val predio: String? = null,
    val anio: Int? = null,
    val secuenciaUso: String? = null,
    val condicionPropiedad: String? = null,
    val porcentajeCondominio: BigDecimal? = null,
    val uso: String? = null,
    val clasificacion: String? = null,
    val estadoConstruccion: String? = null,
    val areaTerreno: BigDecimal? = null,
    val areaConstruida: BigDecimal? = null,
    val longitudFrente: BigDecimal? = null,
    val numeroHabitantes: Int? = null,
    val valorAutoavaluo: BigDecimal? = null,
    val valorCondominio: BigDecimal? = null,
    val deduccion: BigDecimal? = null,
    val valorAfecto: BigDecimal? = null
)

// a declaration with the record on its other side: the predio seen from a contribuyente, or the reverse
data class DeclaracionDetalle(
    val declaracion: Declaracion,
    val predio: Predio? = null,
    val contribuyente: Contribuyente? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Totales(
    val declaraciones: Int,
    val autoavaluo: BigDecimal,
    val valorAfecto: BigDecimal
)

data class ContribuyenteFicha(
    val contribuyente: Contribuyente,
    val anio: Int,
    val predios: Int,
    val totales: Totales
)

data class PredioFicha(
    val predio: Predio,
    val anio: Int,
    val titulares: Int,
    val totales: Totales
)

data class Resumen(
    val anio: Int,
    val contribuyentes: Long,
    val predios: Long,
    val declaraciones: Long
)
