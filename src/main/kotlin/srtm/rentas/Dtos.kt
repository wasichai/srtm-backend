package srtm.rentas

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.LocalDate

// the portal's own shapes. json keys are the model's field names (snake_case), so a validation
// violation from core (field = api name) lands on the same key the form uses, and Records converts
// a record's attributes to these classes and back without a hand-written map per field

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
    val domicilioDepartamento: String? = null,
    // 1. datos de la declaración. codigo, numero_declaracion and fecha_registro are the backend's
    val codigo: String? = null,
    val numeroDeclaracion: Int? = null,
    val fechaRegistro: LocalDate? = null,
    val motivo: String? = null,
    val medioDeterminacion: String? = null,
    val medioPresentacion: String? = null,
    val modificacionOficio: String? = null,
    val fechaPresentacion: LocalDate? = null,
    val tipoContribuyente: String? = null,
    val codigoAnterior: String? = null,
    // 2. identificación
    val fuenteInformacion: String? = null,
    // 3. datos personales
    val fechaNacimiento: LocalDate? = null,
    val fechaFallecimiento: LocalDate? = null,
    val estadoCivil: String? = null,
    val sexo: String? = null,
    val observacion: String? = null
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
    val ubicacionAreaVerde: String? = null,
    // the srtm's datos de la ubicación. with tipo_via set, the backend builds direccion from them
    val ubigeo: String? = null,
    val departamento: String? = null,
    val provincia: String? = null,
    val distrito: String? = null,
    val region: String? = null,
    val tipoVia: String? = null,
    val numeroAlterno: String? = null,
    val letra1: String? = null,
    val letra2: String? = null,
    val ucv: String? = null,
    val subLote: String? = null,
    val kilometro: String? = null,
    val edificacion: String? = null,
    val descripcionEdificacion: String? = null,
    val interior: String? = null,
    val descripcionInterior: String? = null,
    val piso: String? = null,
    val ingreso: String? = null,
    val tipoZona: String? = null,
    val subZona: String? = null,
    val descripcionSubZona: String? = null,
    val partidaRegistral: String? = null,
    val referencia: String? = null,
    val codigoCpu: String? = null,
    // the backend's, when the portal registers the predio
    val numeroRegistro: Int? = null,
    // the lote's polygon, GeoJSON in EPSG:4326 (stored in UTM 18S by wasichai-gis)
    val loteGeom: Map<String, Any?>? = null
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
    val valorAfecto: BigDecimal? = null,
    // the srtm's datos del predio. numero_declaracion is the backend's
    val numeroDeclaracion: Int? = null,
    val motivo: String? = null,
    val medioDeterminacion: String? = null,
    val medioPresentacion: String? = null,
    val modificacionOficio: String? = null,
    val fechaPresentacion: LocalDate? = null,
    val tipoAdquisicion: String? = null,
    val fechaAdquisicion: LocalDate? = null,
    val documentosSustento: String? = null,
    val folios: Int? = null,
    val condicionEspecial: String? = null,
    val condicionTipoDocumento: String? = null,
    val condicionNumeroDocumento: String? = null,
    val condicionFechaDocumento: LocalDate? = null,
    val condicionFechaInicio: LocalDate? = null,
    val condicionFechaFin: LocalDate? = null,
    val inhabitableTipoDocumento: String? = null,
    val inhabitableNumeroResolucion: String? = null,
    val inhabitableFechaResolucion: LocalDate? = null,
    val inhabitableFechaInicio: LocalDate? = null,
    // características
    val claseUso: String? = null,
    val subClaseUso: String? = null,
    val areaComunTerreno: BigDecimal? = null,
    val otrosDatos: String? = null
)

// the contribuyente's children. contribuyente is the parent's id: the path sets it, a body never moves one

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Domicilio(
    val id: String? = null,
    val contribuyente: String? = null,
    val tipoDomicilio: String? = null,
    val tipoPredio: String? = null,
    val ubigeo: String? = null,
    val departamento: String? = null,
    val provincia: String? = null,
    val distrito: String? = null,
    val tipoUnidadUrbana: String? = null,
    val unidadUrbana: String? = null,
    val tipoVia: String? = null,
    val via: String? = null,
    val numero: String? = null,
    val numeroAlterno: String? = null,
    val letra1: String? = null,
    val letra2: String? = null,
    val manzana: String? = null,
    val lote: String? = null,
    val subLote: String? = null,
    val kilometro: String? = null,
    val edificacion: String? = null,
    val nombreEdificacion: String? = null,
    val interior: String? = null,
    val descripcionInterior: String? = null,
    val piso: String? = null,
    val ingreso: String? = null,
    val subZona: String? = null,
    val descripcionSubZona: String? = null,
    val referencia: String? = null,
    // built by the backend (describir), whatever the body says
    val descripcion: String? = null,
    val estado: String? = null,
    // "buscar dirección": the point marked on the map, GeoJSON
    val ubicacion: Map<String, Any?>? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Relacionado(
    val id: String? = null,
    val contribuyente: String? = null,
    // the backend's: 001, 002... under its contribuyente
    val codigo: String? = null,
    val tipoRelacionado: String? = null,
    val tipoDocumento: String? = null,
    val numeroDocumento: String? = null,
    val fuenteInformacion: String? = null,
    val apellidoPaterno: String? = null,
    val apellidoMaterno: String? = null,
    val nombres: String? = null,
    // with RUC, instead of the names
    val razonSocial: String? = null,
    val telefonoCelular: String? = null,
    val telefonoFijo: String? = null,
    val anexo: String? = null,
    val correo: String? = null,
    val fechaInicio: LocalDate? = null,
    val fechaFin: LocalDate? = null,
    val fechaFallecimiento: LocalDate? = null,
    val estado: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MedioContacto(
    val id: String? = null,
    val contribuyente: String? = null,
    val tipo: String? = null,
    val valor: String? = null,
    val anexo: String? = null,
    val principal: Boolean? = null,
    val observacion: String? = null,
    val estado: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Sustento(
    val id: String? = null,
    val contribuyente: String? = null,
    val documento: String? = null,
    val numeroDocumento: String? = null,
    val tipoPresentacion: String? = null,
    val folios: Int? = null,
    val estado: String? = null
)

// the declaración jurada's children. declaracion is the parent's id

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Transferente(
    val id: String? = null,
    val declaracion: String? = null,
    // the backend's: 001, 002... under its declaración
    val codigo: String? = null,
    val porcentajeTransferido: BigDecimal? = null,
    val tipoDocumento: String? = null,
    val numeroDocumento: String? = null,
    val fuenteInformacion: String? = null,
    val apellidoPaterno: String? = null,
    val apellidoMaterno: String? = null,
    val nombres: String? = null,
    // with RUC, instead of the names
    val razonSocial: String? = null,
    val fechaNacimiento: LocalDate? = null,
    val estadoCivil: String? = null,
    val sexo: String? = null,
    val fechaFallecimiento: LocalDate? = null,
    val telefonoFijo: String? = null,
    val telefonoCelular: String? = null,
    val correo: String? = null,
    val ubigeo: String? = null,
    val departamento: String? = null,
    val provincia: String? = null,
    val distrito: String? = null,
    val descripcionDomicilio: String? = null,
    val estado: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NivelConstruccion(
    val id: String? = null,
    val declaracion: String? = null,
    val tipoNivel: String? = null,
    val numeroPiso: Int? = null,
    val anioConstruccion: Int? = null,
    val mesConstruccion: Int? = null,
    val material: String? = null,
    val estadoConservacion: String? = null,
    val areaConstruida: BigDecimal? = null,
    val areaComun: BigDecimal? = null,
    val porcentajeAreaComun: BigDecimal? = null,
    // the letter (A-I) of each column of the official unit-value table
    val murosColumnas: String? = null,
    val techos: String? = null,
    val pisos: String? = null,
    val puertasVentanas: String? = null,
    val revestimientos: String? = null,
    val banos: String? = null,
    val instalaciones: String? = null,
    val estado: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ObraComplementaria(
    val id: String? = null,
    val declaracion: String? = null,
    val ingreso: String? = null,
    val material: String? = null,
    val tipoObra: String? = null,
    val estadoConservacion: String? = null,
    val anioConstruccion: Int? = null,
    val mesConstruccion: Int? = null,
    val categoria: String? = null,
    val valor: BigDecimal? = null,
    val numeroPiso: Int? = null,
    val cantidad: BigDecimal? = null,
    val metrado: BigDecimal? = null,
    val unidadMedida: String? = null,
    // cantidad x metrado, the backend's
    val totalMetrado: BigDecimal? = null,
    val estado: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OtroFrente(
    val id: String? = null,
    val declaracion: String? = null,
    val tipoVia: String? = null,
    val via: String? = null,
    val numero: String? = null,
    val numeroAlterno: String? = null,
    val frontis: BigDecimal? = null,
    val lote: String? = null,
    val cuadra: String? = null,
    val lado: String? = null,
    val estado: String? = null
)

// a declaración jurada with what it is about: its predio and its contribuyente
data class DeclaracionJurada(
    val declaracion: Declaracion,
    val predio: Predio,
    val contribuyente: Contribuyente,
    // the srtm's "fecha de actualización": when the declaration was last saved
    val actualizado: java.time.Instant? = null
)

// presenting one: an existing predio (predio_id) or a new one (predio, its code generated when blank)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NuevaDeclaracion(
    val declaracion: Declaracion = Declaracion(),
    val predio: Predio? = null,
    val predioId: String? = null
)

// a condómino added from a declaración ("datos de los condóminos"): who, and its % of the predio
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NuevoCondomino(
    val contribuyente: String? = null,
    val porcentajeCondominio: BigDecimal? = null
)

// a lote of the catastro fiscal
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CatastroFiscal(
    val id: String? = null,
    val codigoCpu: String? = null,
    val codigoPredioMunicipal: String? = null,
    val partidaRegistral: String? = null,
    val tipoPredio: String? = null,
    val ubigeo: String? = null,
    val tipoVia: String? = null,
    val via: String? = null,
    val numero: String? = null,
    val tipoZona: String? = null,
    val zona: String? = null,
    val manzana: String? = null,
    val lote: String? = null,
    val kilometro: String? = null,
    val direccion: String? = null,
    val loteGeom: Map<String, Any?>? = null
)

// catalogs

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ObraCategoria(
    val id: String? = null,
    val tipoObra: String? = null,
    val numero: Int? = null,
    val descripcion: String? = null,
    val unidadMedida: String? = null,
    val material: String? = null
)

data class CategoriaValor(
    val columna: Int,
    val categoria: String,
    val letra: String,
    val descripcion: String
)

data class Ubigeo(
    val codigo: String,
    val departamento: String,
    val provincia: String,
    val distrito: String
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Via(
    val id: String? = null,
    val tipoVia: String? = null,
    val nombre: String? = null,
    val ubigeo: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class UnidadUrbana(
    val id: String? = null,
    val tipoUnidadUrbana: String? = null,
    val nombre: String? = null,
    val ubigeo: String? = null
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
