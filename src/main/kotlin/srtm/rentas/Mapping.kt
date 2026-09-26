package srtm.rentas

import java.math.BigDecimal

// object and relation names of model/model.json
const val CONTRIBUYENTE = "contribuyente"
const val PREDIO = "predio"
const val DECLARACION = "declaracion_predial"

// record attributes <-> portal dtos. writes send every field, null included: core's update keeps a
// field that is left out, so leaving nulls out would make "clear this field" impossible

fun toContribuyente(
    id: String,
    a: Map<String, Any?>
) = Contribuyente(
    id = id,
    tipoPersona = text(a["tipo_persona"]),
    tipoDocumento = text(a["tipo_documento"]),
    numeroDocumento = text(a["numero_documento"]),
    nombreCompleto = text(a["nombre_completo"]),
    apellidoPaterno = text(a["apellido_paterno"]),
    apellidoMaterno = text(a["apellido_materno"]),
    nombres = text(a["nombres"]),
    razonSocial = text(a["razon_social"]),
    domicilioFiscal = text(a["domicilio_fiscal"]),
    domicilioDistrito = text(a["domicilio_distrito"]),
    domicilioProvincia = text(a["domicilio_provincia"]),
    domicilioDepartamento = text(a["domicilio_departamento"])
)

fun Contribuyente.attributes(): Map<String, Any?> =
    mapOf(
        "tipo_persona" to tipoPersona,
        "tipo_documento" to tipoDocumento,
        "numero_documento" to numeroDocumento,
        "nombre_completo" to nombreCompleto,
        "apellido_paterno" to apellidoPaterno,
        "apellido_materno" to apellidoMaterno,
        "nombres" to nombres,
        "razon_social" to razonSocial,
        "domicilio_fiscal" to domicilioFiscal,
        "domicilio_distrito" to domicilioDistrito,
        "domicilio_provincia" to domicilioProvincia,
        "domicilio_departamento" to domicilioDepartamento
    )

fun toPredio(
    id: String,
    a: Map<String, Any?>
) = Predio(
    id = id,
    codigo = text(a["codigo"]),
    sectorCatastral = text(a["sector_catastral"]),
    manzanaCatastral = text(a["manzana_catastral"]),
    condicion = text(a["condicion"]),
    direccion = text(a["direccion"]),
    via = text(a["via"]),
    numero = text(a["numero"]),
    manzana = text(a["manzana"]),
    lote = text(a["lote"]),
    habilitacionUrbana = text(a["habilitacion_urbana"]),
    ubicacionAreaVerde = text(a["ubicacion_area_verde"])
)

fun Predio.attributes(): Map<String, Any?> =
    mapOf(
        "codigo" to codigo,
        "sector_catastral" to sectorCatastral,
        "manzana_catastral" to manzanaCatastral,
        "condicion" to condicion,
        "direccion" to direccion,
        "via" to via,
        "numero" to numero,
        "manzana" to manzana,
        "lote" to lote,
        "habilitacion_urbana" to habilitacionUrbana,
        "ubicacion_area_verde" to ubicacionAreaVerde
    )

fun toDeclaracion(
    id: String,
    a: Map<String, Any?>
) = Declaracion(
    id = id,
    contribuyente = text(a["contribuyente"]),
    predio = text(a["predio"]),
    anio = integer(a["anio"]),
    secuenciaUso = text(a["secuencia_uso"]),
    condicionPropiedad = text(a["condicion_propiedad"]),
    porcentajeCondominio = decimal(a["porcentaje_condominio"]),
    uso = text(a["uso"]),
    clasificacion = text(a["clasificacion"]),
    estadoConstruccion = text(a["estado_construccion"]),
    areaTerreno = decimal(a["area_terreno"]),
    areaConstruida = decimal(a["area_construida"]),
    longitudFrente = decimal(a["longitud_frente"]),
    numeroHabitantes = integer(a["numero_habitantes"]),
    valorAutoavaluo = decimal(a["valor_autoavaluo"]),
    valorCondominio = decimal(a["valor_condominio"]),
    deduccion = decimal(a["deduccion"]),
    valorAfecto = decimal(a["valor_afecto"])
)

fun Declaracion.attributes(): Map<String, Any?> =
    mapOf(
        "contribuyente" to contribuyente,
        "predio" to predio,
        "anio" to anio,
        "secuencia_uso" to secuenciaUso,
        "condicion_propiedad" to condicionPropiedad,
        "porcentaje_condominio" to porcentajeCondominio,
        "uso" to uso,
        "clasificacion" to clasificacion,
        "estado_construccion" to estadoConstruccion,
        "area_terreno" to areaTerreno,
        "area_construida" to areaConstruida,
        "longitud_frente" to longitudFrente,
        "numero_habitantes" to numeroHabitantes,
        "valor_autoavaluo" to valorAutoavaluo,
        "valor_condominio" to valorCondominio,
        "deduccion" to deduccion,
        "valor_afecto" to valorAfecto
    )

// sums over the declarations of one year. a missing value counts as zero
fun totales(declaraciones: List<Declaracion>) =
    Totales(
        declaraciones = declaraciones.size,
        autoavaluo = declaraciones.sumOf { it.valorAutoavaluo ?: BigDecimal.ZERO },
        valorAfecto = declaraciones.sumOf { it.valorAfecto ?: BigDecimal.ZERO }
    )

// core reads relations and uuids back as strings, decimals as BigDecimal, integers as Int or Long
internal fun text(value: Any?): String? = value?.toString()

internal fun integer(value: Any?): Int? =
    when (value) {
        null -> null
        is Number -> value.toInt()
        else -> value.toString().toInt()
    }

internal fun decimal(value: Any?): BigDecimal? =
    when (value) {
        null -> null
        is BigDecimal -> value
        else -> BigDecimal(value.toString())
    }
