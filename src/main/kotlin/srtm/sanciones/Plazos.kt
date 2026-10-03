package srtm.sanciones

import srtm.impuesto.ParametroTributario
import srtm.impuesto.Vencimientos
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.time.LocalDate

// the movable feriados of each year whose FERIADOS row was read (an empty set is a year declared without any). the
// fixed ones are Vencimientos.FERIADOS_NACIONALES, always. a year that is not here, or whose row is malformed, is not
// guessed: the computation that touches it names it in faltan (rentas' «sinFeriados» was an explicit statement, srtm
// asks for the row)
data class Calendario(
    val feriados: Map<Int, Set<LocalDate>>,
    val malformados: Map<Int, String> = emptyMap()
) {
    companion object {
        // the FERIADOS rows: clave the year, texto its iso dates separated by commas
        fun de(parametros: List<ParametroTributario>): Calendario {
            val feriados = mutableMapOf<Int, Set<LocalDate>>()
            val malformados = mutableMapOf<Int, String>()
            parametros
                .filter { it.tipo == Llaves.FERIADOS }
                .sortedBy { it.vigenciaDesde }
                .forEach { p ->
                    val anio =
                        p.clave
                            ?.trim()
                            ?.takeIf { it.length == 4 }
                            ?.toIntOrNull() ?: return@forEach
                    val textos =
                        p.texto
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                    val fechas = textos.map { runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { d -> d.year == anio } }
                    if (fechas.any { it == null }) {
                        feriados.remove(anio)
                        malformados[anio] = "${Llaves.FERIADOS} $anio: no son fechas AAAA-MM-DD de $anio separadas por comas"
                    } else {
                        feriados[anio] = fechas.filterNotNull().toSet()
                        malformados.remove(anio)
                    }
                }
            return Calendario(feriados, malformados)
        }
    }
}

// a plazo read from its PLAZO row: its days, its unit, the row (the procedencia the act keeps) and the text it copies
data class Plazo(
    val dias: Int,
    val unidad: String,
    val parametro: String?
) {
    val texto: String get() = Llaves.plazoTexto(dias, unidad)
}

// a PLAZO row as the acts would read it: its days, unit and text, its vigencia and the row (the procedencia)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PlazoCargado(
    val clave: String,
    val dias: Int,
    val unidad: String,
    val texto: String,
    val vigenciaDesde: LocalDate?,
    val vigenciaHasta: LocalDate?,
    val parametroId: String?
)

// the year's movable feriados (the fixed ones are Vencimientos.FERIADOS_NACIONALES, always) and their row
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FeriadosCargados(
    val fechas: List<LocalDate>,
    val parametroId: String?
)

// GET /infracciones/plazos: what a descargo, a resolución and its notificación of `anio` would read, at al_dia
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PlazosCargados(
    val anio: Int,
    val alDia: LocalDate,
    val plazos: List<PlazoCargado>,
    val feriados: FeriadosCargados?,
    val faltan: List<String>
)

// business days (art. 144 of Ley 27444): monday to friday, not a fixed national feriado
// (Vencimientos.FERIADOS_NACIONALES, the predial's list) nor one of the year's FERIADOS row. pure: the calendar is an
// argument, so recounting a plazo in 2036 gives the day it gave in 2026
object Plazos {
    fun habil(
        dia: LocalDate,
        calendario: Calendario
    ): Boolean = Vencimientos.habil(dia) && dia !in calendario.feriados[dia.year].orEmpty()

    // the first business day strictly after `fecha`
    fun siguienteHabil(
        fecha: LocalDate,
        calendario: Calendario
    ): LocalDate = generateSequence(fecha.plusDays(1)) { it.plusDays(1) }.first { habil(it, calendario) }

    // `dias` business days on from `desde`, not counting it; 0 gives `desde`
    fun sumarHabiles(
        desde: LocalDate,
        dias: Int,
        calendario: Calendario
    ): LocalDate {
        require(dias >= 0) { "un plazo no se cuenta hacia atrás: $dias" }
        return (1..dias).fold(desde) { dia, _ -> siguienteHabil(dia, calendario) }
    }

    // the last day of a plazo that runs from the business day after `desde` (rentas' RegistrarDescargo): an infracción
    // on wednesday 2026-03-04 with 5 gives thursday 2026-03-12
    fun hasta(
        desde: LocalDate,
        dias: Int,
        calendario: Calendario
    ): LocalDate = sumarHabiles(siguienteHabil(desde, calendario), dias, calendario)

    // rentas' Exigibilidad: a notificación takes effect the business day after the diligencia (art. 106 of the TUO del
    // Código Tributario), the plazo runs from there, and the day after it ends the resolución can be demanded
    fun exigibleDesde(
        diligencia: LocalDate,
        dias: Int,
        calendario: Calendario
    ): LocalDate = hasta(diligencia, dias, calendario).plusDays(1)

    // the FERIADOS rows a count from `desde` (not counted) to `hasta` needed and the calendar lacks, one per year. a
    // count is exact when this is empty: the years it never reached cannot move it
    fun faltan(
        calendario: Calendario,
        desde: LocalDate,
        hasta: LocalDate
    ): List<String> =
        (desde.plusDays(1).year..maxOf(desde.plusDays(1), hasta).year)
            .filter { it !in calendario.feriados }
            .map { calendario.malformados[it] ?: "${Llaves.FERIADOS} ${Llaves.feriados(it)}" }

    // the PLAZO row `clave` (DESCARGO_PAPELETA or RG_RECURSO) in force on `fecha`, or what is missing: never a default
    // (rentas: a missing descargo plazo taken as 0 would make every recurso late)
    fun plazo(
        parametros: List<ParametroTributario>,
        clave: String,
        fecha: LocalDate
    ): Resultado<Plazo> {
        val nombre = "${Llaves.PLAZO} $clave ${fecha.year}"
        val fila = vigente(parametros, Llaves.PLAZO, clave, fecha) ?: return Resultado.faltando(listOf(nombre))
        val dias =
            fila.valorNumerico
                ?.takeIf {
                    it.signum() > 0 && it.stripTrailingZeros().scale() <= 0
                }?.let { runCatching { it.intValueExact() }.getOrNull() }
        val unidad = fila.texto?.trim()
        if (dias == null || unidad != Llaves.DIAS_HABILES) {
            return Resultado.faltando(listOf("$nombre: es un número entero de días mayor que 0 en ${Llaves.DIAS_HABILES}"))
        }
        return Resultado.de(Plazo(dias, unidad, fila.id))
    }

    // the plazos loaded for `anio`: the two PLAZO rows in force on january 1st (on `hoy` in the current year: the ones an
    // act of today reads) and the year's FERIADOS row, read as Plazos.plazo and Calendario.de read them. what is missing,
    // or malformed, goes in faltan with the name an act's 422 would give it ("PLAZO RG_RECURSO 2027", "FERIADOS 2027")
    fun cargados(
        anio: Int,
        hoy: LocalDate,
        parametros: List<ParametroTributario>
    ): PlazosCargados {
        val alDia = if (anio == hoy.year) hoy else LocalDate.of(anio, 1, 1)
        val faltan = mutableListOf<String>()
        val plazos =
            listOf(Llaves.DESCARGO_PAPELETA, Llaves.RG_RECURSO).mapNotNull { clave ->
                val leido = plazo(parametros, clave, alDia)
                val p = leido.valor
                if (p == null) {
                    faltan += leido.faltan
                    null
                } else {
                    val fila = vigente(parametros, Llaves.PLAZO, clave, alDia)
                    PlazoCargado(clave, p.dias, p.unidad, p.texto, fila?.vigenciaDesde, fila?.vigenciaHasta, p.parametro)
                }
            }
        val calendario = Calendario.de(parametros)
        val fechas = calendario.feriados[anio]
        val feriados =
            fechas?.let {
                // the row Calendario.de kept: the last of the year's, by vigencia_desde
                val fila =
                    parametros
                        .filter { p -> p.tipo == Llaves.FERIADOS && p.clave?.trim() == Llaves.feriados(anio) }
                        .sortedBy { p -> p.vigenciaDesde }
                        .lastOrNull()
                FeriadosCargados(it.sorted(), fila?.id)
            }
        if (feriados == null) faltan += calendario.malformados[anio] ?: "${Llaves.FERIADOS} ${Llaves.feriados(anio)}"
        return PlazosCargados(anio, alDia, plazos, feriados, faltan)
    }
}

// the row of `tipo` and `clave` (blank for none) in force on `dia`, the latest when two are: both ends count
internal fun vigente(
    parametros: List<ParametroTributario>,
    tipo: String,
    clave: String?,
    dia: LocalDate
): ParametroTributario? =
    parametros
        .filter { it.tipo == tipo && it.clave?.trim()?.ifEmpty { null } == clave?.ifEmpty { null } }
        .filter { it.vigenciaDesde != null && it.vigenciaDesde <= dia && (it.vigenciaHasta == null || it.vigenciaHasta >= dia) }
        .maxByOrNull { it.vigenciaDesde!! }
