package srtm.rentas

import java.math.BigDecimal

// the registration rules of the srtm forms, as pure functions: the service applies them, the tests pin them

// tipo_persona (what the padrón import and the lists use) follows the srtm's tipo_contribuyente
fun tipoPersona(tipoContribuyente: String?): String? =
    when (tipoContribuyente) {
        null -> null
        "PERSONA NATURAL", "SOCIEDAD CONYUGAL" -> "NATURAL"
        "SUCESION INDIVISA" -> "SUCESION"
        else -> "JURIDICA"
    }

// nombre_completo as the padrón writes it: surnames then names for a person, the razón social otherwise
fun nombreCompleto(c: Contribuyente): String? {
    val persona = listOfNotNull(c.apellidoPaterno, c.apellidoMaterno, c.nombres).map { it.trim() }.filter { it.isNotEmpty() }
    return when {
        tipoPersona(c.tipoContribuyente) == "NATURAL" && persona.isNotEmpty() -> persona.joinToString(" ")
        !c.razonSocial.isNullOrBlank() -> c.razonSocial.trim()
        persona.isNotEmpty() -> persona.joinToString(" ")
        else -> c.nombreCompleto
    }
}

const val SIN_DOCUMENTO = "SIN DOCUMENTO"

// the number as it is stored: trimmed, and none for SIN DOCUMENTO. one that already was SIN DOCUMENTO keeps the number
// it has, whatever is sent: the padrón's, the key import_predios.py knows it by. one that becomes SIN DOCUMENTO loses its
fun numeroDocumento(
    tipo: String?,
    numero: String?,
    anterior: Contribuyente? = null
): String? =
    when {
        tipo != SIN_DOCUMENTO -> numero?.trim()?.ifEmpty { null }
        anterior?.tipoDocumento == SIN_DOCUMENTO -> anterior.numeroDocumento
        else -> null
    }

private val RUC_PESOS = listOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)

// sunat's modulo 11 over the first ten digits: 11 - sum % 11, where 10 is written 0 and 11 is written 1
private fun digitoVerificadorRuc(ruc: String): Int = (11 - RUC_PESOS.withIndex().sumOf { (i, p) -> ruc[i].digitToInt() * p } % 11) % 10

// the number each tipo de documento takes: null when it fits, the reason otherwise. no tipo is the model's to refuse.
// the portal checks the same (errorDocumento in srtm-ui): change both together
fun errorDocumento(
    tipo: String?,
    numero: String?
): String? {
    if (tipo == null || tipo == SIN_DOCUMENTO) return null
    val n = numeroDocumento(tipo, numero) ?: return "Este dato es obligatorio"
    return when (tipo) {
        "DNI" -> if (Regex("\\d{8}").matches(n)) null else "El DNI tiene 8 dígitos"
        "RUC" ->
            when {
                !Regex("(10|15|16|17|20)\\d{9}").matches(n) -> "El RUC tiene 11 dígitos y empieza con 10, 15, 16, 17 o 20"
                digitoVerificadorRuc(n) != n.last().digitToInt() -> "El dígito verificador del RUC no es válido"
                else -> null
            }
        else -> if (Regex("[A-Za-z0-9]{1,12}").matches(n)) null else "Hasta 12 letras o dígitos"
    }
}

// the srtm writes the common types of vía and unidad urbana abbreviated (AV. ANDRES AVELINO CACERES): the records keep
// the model's word, the address its abbreviation; a type not here goes whole. the same two tables are in srtm-ui
// (forms/direccion.ts), and model/import_predios.py (split_tipo) reads each abbreviation back: change the three together
val ABREVIATURA_VIA =
    mapOf(
        "AVENIDA" to "AV.",
        "CALLE" to "CA.",
        "JIRON" to "JR.",
        "PASAJE" to "PSJE.",
        "PROLONGACION" to "PROL.",
        "CARRETERA" to "CARR."
    )

val ABREVIATURA_UNIDAD_URBANA =
    mapOf(
        "ASENTAMIENTO HUMANO" to "AA.HH.",
        "ASOCIACION DE VIVIENDA" to "AA.VV.",
        "CENTRO POBLADO" to "C.P.",
        "URBANIZACION" to "URB."
    )

// a vía or unidad urbana with its type in front, abbreviated. OTROS is not a word of the address, and a name that
// already starts with its type (a padrón's "JR. LIMA" not yet normalized) does not get it twice
fun conTipo(
    tipo: String?,
    nombre: String?,
    abreviaturas: Map<String, String>
): String? {
    val palabra = tipo?.trim()?.ifEmpty { null }?.takeIf { it != "OTROS" }
    val sigla = palabra?.let { abreviaturas[it] ?: it }
    val texto = nombre?.trim()?.ifEmpty { null }
    val repetido = palabra != null && texto != null && listOf(sigla, palabra).any { texto.startsWith("$it ") }
    return listOfNotNull(if (repetido) null else sigla, texto).joinToString(" ").ifEmpty { null }
}

// the one-line address of a domicilio, in the srtm's order: vía and number, the building, the lot,
// the unidad urbana, the sub zona, then DEPARTAMENTO-PROVINCIA-DISTRITO. the portal previews the same text
// (describirDomicilio in srtm-ui's forms/direccion.ts): change both together
fun describir(d: Domicilio): String {
    fun join(vararg parts: String?) = parts.mapNotNull { it?.trim()?.ifEmpty { null } }.joinToString(" ").ifEmpty { null }
    val numero = join(d.numero, d.letra1, d.letra2)
    val partes =
        listOf(
            conTipo(d.tipoVia, d.via, ABREVIATURA_VIA),
            numero?.let { "N° $it" },
            d.numeroAlterno?.ifBlank { null }?.let { "N° ALT. $it" },
            join(if (d.edificacion == "OTROS") null else d.edificacion, d.nombreEdificacion),
            join(if (d.interior == "OTROS") null else d.interior, d.descripcionInterior),
            d.piso?.ifBlank { null }?.let { "PISO $it" },
            d.ingreso?.ifBlank { null }?.let { "PUERTA $it" },
            d.manzana?.ifBlank { null }?.let { "MZ. $it" },
            d.lote?.ifBlank { null }?.let { "LT. $it" },
            d.subLote?.ifBlank { null }?.let { "SUB LT. $it" },
            d.kilometro?.ifBlank { null }?.let { "KM. $it" },
            conTipo(d.tipoUnidadUrbana, d.unidadUrbana, ABREVIATURA_UNIDAD_URBANA),
            join(if (d.subZona == "OTROS") null else d.subZona, d.descripcionSubZona),
            listOfNotNull(d.departamento, d.provincia, d.distrito).filter { it.isNotBlank() }.joinToString("-").ifEmpty { null }
        )
    return partes.filterNotNull().joinToString(", ")
}

// only an active fiscal domicilio is the contribuyente's domicilio_fiscal
fun esFiscalActivo(d: Domicilio): Boolean = d.tipoDomicilio == "FISCAL" && d.estado != "INACTIVO"

// codes are numbers written with a fixed width, so a text sort is a numeric sort
const val CODIGO_WIDTH = 6

fun siguienteCodigo(ultimo: String?): String = ((ultimo?.toIntOrNull() ?: 0) + 1).toString().padStart(CODIGO_WIDTH, '0')

// a predio's direccion from its srtm ubicación, in the same order as a domicilio's (srtm-ui previews it:
// describirUbicacion). only for a predio whose ubicación is the srtm's (tipo_via set): an imported one not yet
// normalized (model/normalizar_padron.py) keeps the padrón's text until someone fills its ubicación
fun describirUbicacion(p: Predio): String? =
    if (p.tipoVia == null) {
        p.direccion
    } else {
        describir(
            Domicilio(
                tipoVia = p.tipoVia,
                via = p.via,
                numero = p.numero,
                numeroAlterno = p.numeroAlterno,
                letra1 = p.letra1,
                letra2 = p.letra2,
                manzana = p.manzana,
                lote = p.lote,
                subLote = p.subLote,
                kilometro = p.kilometro,
                edificacion = p.edificacion,
                nombreEdificacion = p.descripcionEdificacion,
                interior = p.interior,
                descripcionInterior = p.descripcionInterior,
                piso = p.piso,
                ingreso = p.ingreso,
                tipoUnidadUrbana = p.tipoZona,
                unidadUrbana = p.habilitacionUrbana,
                subZona = p.subZona,
                descripcionSubZona = p.descripcionSubZona,
                departamento = p.departamento,
                provincia = p.provincia,
                distrito = p.distrito
            )
        )
    }

// a predio's code is sector-manzana-number, as in the padrón: 01-01-0001. the next number of that manzana
fun prefijoPredio(
    sector: String,
    manzana: String
): String = "${sector.trim().padStart(2, '0')}-${manzana.trim().padStart(2, '0')}-"

fun siguienteCodigoPredio(
    prefijo: String,
    ultimo: String?
): String {
    val numero = ultimo?.removePrefix(prefijo)?.toIntOrNull() ?: 0
    return prefijo + (numero + 1).toString().padStart(4, '0')
}

// the padrón numbers the usos of a predio with three digits (001, 002...): a number typed as 1 is written the same way,
// none is the first. model/import_predios.py (secuencia_uso) does the same
const val SECUENCIA_WIDTH = 3

fun secuenciaUso(valor: String?): String {
    val texto = valor?.trim()?.ifEmpty { null } ?: "1"
    return if (texto.all { it.isDigit() }) texto.padStart(SECUENCIA_WIDTH, '0') else texto
}

// what an obra complementaria declares in all: cantidad x metrado, once both are there
fun totalMetrado(o: ObraComplementaria): BigDecimal? = if (o.cantidad == null || o.metrado == null) null else o.cantidad.multiply(o.metrado)
