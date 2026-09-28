package srtm.emision

import srtm.rentas.Contribuyente
import srtm.rentas.Declaracion
import srtm.rentas.NivelConstruccion
import srtm.rentas.ObraComplementaria
import srtm.rentas.Predio
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// the PU (predio urbano): what templates/emision/pu.html prints of a predio and one titular, a section per uso
// (secuencia_uso) it declares that year. every value is text already: the template only lays it out. the values are
// the declared ones (no revaluation: that is the epic's out of scope)

// one uso of the predio: the titular's declaración of that secuencia, with its niveles and obras complementarias
data class UsoDeclarado(
    val declaracion: Declaracion,
    val niveles: List<NivelConstruccion> = emptyList(),
    val obras: List<ObraComplementaria> = emptyList()
)

data class HojaPu(
    val municipalidad: String,
    val anio: Int,
    // the numbers of the declaraciones printed, one per uso
    val declaraciones: String,
    val emitido: String,
    val contribuyente: TitularPu,
    val predio: UbicacionPu,
    val usos: List<SeccionPu>
)

data class TitularPu(
    val codigo: String,
    val nombre: String,
    val documento: String,
    val domicilioFiscal: String,
    // of the first uso: a titular holds the same part of every uso of a predio
    val condicion: String,
    val porcentaje: String
)

data class UbicacionPu(
    val codigo: String,
    val direccion: String,
    val sectorManzanaLote: String,
    val habilitacion: String,
    val ubigeo: String
)

data class SeccionPu(
    val secuencia: String,
    val numeroDeclaracion: String,
    val condicion: String,
    val porcentaje: String,
    val uso: String,
    val clasificacion: String,
    val estado: String,
    val areaTerreno: String,
    val areaConstruida: String,
    val areaComun: String,
    val frente: String,
    val niveles: List<FilaNivel>,
    val obras: List<FilaObra>,
    val valores: ValoresPu
)

data class FilaNivel(
    val tipo: String,
    val piso: String,
    val anioMes: String,
    val material: String,
    val conservacion: String,
    // the letters of the seven columns of the cuadro de valores: muros y columnas, techos, pisos, puertas y
    // ventanas, revestimientos, baños, instalaciones
    val categorias: List<String>,
    val area: String,
    val areaComun: String
)

data class FilaObra(
    val tipo: String,
    val partida: String,
    val material: String,
    val conservacion: String,
    val anioMes: String,
    val cantidad: String,
    val metrado: String,
    val unidad: String,
    val total: String
)

data class ValoresPu(
    val autoavaluo: String,
    val valorCondominio: String,
    val deduccion: String,
    val valorAfecto: String
)

fun hojaPu(
    municipalidad: String,
    anio: Int,
    predio: Predio,
    contribuyente: Contribuyente,
    usos: List<UsoDeclarado>,
    hoy: LocalDate
): HojaPu {
    val secciones = usos.sortedBy { it.declaracion.secuenciaUso.orEmpty() }.map(::seccion)
    val primera = secciones.firstOrNull()
    return HojaPu(
        municipalidad = municipalidad,
        anio = anio,
        declaraciones = secciones.map { it.numeroDeclaracion }.filter { it.isNotEmpty() }.joinToString(", "),
        emitido = hoy.format(FECHA),
        contribuyente =
            TitularPu(
                codigo = contribuyente.codigo.orEmpty(),
                nombre = nombreDe(contribuyente),
                documento = documentoDe(contribuyente),
                domicilioFiscal = contribuyente.domicilioFiscal.orEmpty(),
                condicion = primera?.condicion.orEmpty(),
                porcentaje = primera?.porcentaje.orEmpty()
            ),
        predio =
            UbicacionPu(
                codigo = predio.codigo.orEmpty(),
                direccion = predio.direccion.orEmpty(),
                sectorManzanaLote = unir(" / ", predio.sectorCatastral, predio.manzanaCatastral ?: predio.manzana, predio.lote),
                habilitacion = predio.habilitacionUrbana ?: unir(" ", predio.tipoZona, predio.subZona),
                ubigeo = unir(" · ", predio.ubigeo, unir(" / ", predio.departamento, predio.provincia, predio.distrito))
            ),
        usos = secciones
    )
}

// the declared name; an imported contribuyente may only have its parts
internal fun nombreDe(c: Contribuyente): String =
    c.nombreCompleto?.ifBlank { null }
        ?: c.razonSocial?.ifBlank { null }
        ?: unir(" ", c.apellidoPaterno, c.apellidoMaterno, c.nombres)

// "DNI 12345678"
internal fun documentoDe(c: Contribuyente): String = listOfNotNull(c.tipoDocumento, c.numeroDocumento).joinToString(" ")

private fun seccion(uso: UsoDeclarado): SeccionPu {
    val d = uso.declaracion
    return SeccionPu(
        secuencia = d.secuenciaUso.orEmpty(),
        numeroDeclaracion = d.numeroDeclaracion?.toString().orEmpty(),
        condicion = d.condicionPropiedad.orEmpty(),
        porcentaje = d.porcentajeCondominio?.let { "${numero(it)} %" }.orEmpty(),
        uso = unir(" / ", d.claseUso, d.subClaseUso, d.uso),
        clasificacion = d.clasificacion.orEmpty(),
        estado = d.estadoConstruccion.orEmpty(),
        areaTerreno = numero(d.areaTerreno),
        areaConstruida = numero(d.areaConstruida),
        areaComun = numero(d.areaComunTerreno),
        frente = numero(d.longitudFrente),
        niveles = uso.niveles.filter { activo(it.estado) }.map(::fila),
        obras = uso.obras.filter { activo(it.estado) }.map(::fila),
        valores =
            ValoresPu(
                autoavaluo = soles(d.valorAutoavaluo),
                valorCondominio = soles(d.valorCondominio),
                deduccion = soles(d.deduccion),
                valorAfecto = soles(d.valorAfecto)
            )
    )
}

private fun fila(n: NivelConstruccion) =
    FilaNivel(
        tipo = n.tipoNivel.orEmpty(),
        piso = n.numeroPiso?.toString().orEmpty(),
        anioMes = anioMes(n.anioConstruccion, n.mesConstruccion),
        material = n.material.orEmpty(),
        conservacion = n.estadoConservacion.orEmpty(),
        categorias = listOf(n.murosColumnas, n.techos, n.pisos, n.puertasVentanas, n.revestimientos, n.banos, n.instalaciones).map { it.orEmpty() },
        area = numero(n.areaConstruida),
        areaComun = numero(n.areaComun)
    )

private fun fila(o: ObraComplementaria) =
    FilaObra(
        tipo = o.tipoObra.orEmpty(),
        partida = o.categoria.orEmpty(),
        material = o.material.orEmpty(),
        conservacion = o.estadoConservacion.orEmpty(),
        anioMes = anioMes(o.anioConstruccion, o.mesConstruccion),
        cantidad = numero(o.cantidad),
        metrado = numero(o.metrado),
        unidad = o.unidadMedida.orEmpty(),
        total = numero(o.totalMetrado)
    )

// a row without estado (the imported ones) is active
private fun activo(estado: String?) = estado != "INACTIVO"

private fun anioMes(
    anio: Int?,
    mes: Int?
): String =
    when {
        anio == null -> ""
        mes == null -> anio.toString()
        else -> "%02d/%d".format(mes, anio)
    }

internal fun unir(
    separador: String,
    vararg partes: String?
): String = partes.mapNotNull { it?.trim()?.ifEmpty { null } }.joinToString(separador)

// 1,234.56: the thousands with a comma and the cents with a point, as the municipality's forms
internal fun numero(valor: BigDecimal?): String = valor?.let { DecimalFormat("#,##0.00", PUNTO).format(it.setScale(2, RoundingMode.HALF_UP)) }.orEmpty()

// an amount; one not declared prints 0.00
internal fun soles(valor: BigDecimal?): String = "S/ ${numero(valor ?: BigDecimal.ZERO)}"

private val PUNTO = DecimalFormatSymbols(Locale.US)
internal val FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy")
