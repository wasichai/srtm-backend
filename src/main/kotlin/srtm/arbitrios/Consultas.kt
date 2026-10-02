package srtm.arbitrios

import srtm.rentas.Contribuyente
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// what the portal shows of the arbitrios. every total is computed here, never by the client; every figure carries
// the date it was determined on

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Persona(
    val id: String?,
    val codigo: String?,
    val nombre: String?
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PredioResumen(
    val id: String?,
    val codigo: String?,
    val direccion: String?
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CuotaMes(
    val id: String?,
    val monto: BigDecimal,
    val contribuyente: String?,
    val fechaCalculo: LocalDate?,
    val parametroAplicado: String?
)

// one servicio's twelve months: null where there is no cuota
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FilaServicio(
    val servicio: ServicioArbitrio,
    val meses: List<CuotaMes?>,
    val total: BigDecimal
)

// who the rule charges that month (the titular principal of the declarations that cover its day 1); null: no one
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TitularMes(
    val periodo: Int,
    val titular: Persona?
)

// a predio's year: servicio by month, the titular of each month, the totals, the date of the latest cuota, and how
// many cuotas a determination would add now (or what it lacks to)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MatrizArbitrios(
    val anio: Int,
    val predio: PredioResumen,
    val filas: List<FilaServicio>,
    val titulares: List<TitularMes>,
    val totalesPorMes: List<BigDecimal>,
    val total: BigDecimal,
    val fechaCalculo: LocalDate?,
    val pendientes: Int,
    val faltan: List<String>
)

// a contribuyente's year: each predio with the cuotas charged to it, and its total
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ArbitriosContribuyente(
    val anio: Int,
    val contribuyente: Persona,
    val predios: List<MatrizArbitrios>,
    val total: BigDecimal,
    val fechaCalculo: LocalDate?
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ParametrosArbitrios(
    val anio: Int,
    val ordenanza: OrdenanzaArbitrio?,
    val servicios: List<ServicioArbitrio>,
    val parametros: List<srtm.impuesto.ParametroTributario>,
    val faltan: List<String>
)

object Consultas {
    private const val CONSULTA = "Consulta de lo pendiente"

    // soloDe: only the cuotas charged to that contribuyente (its own view); null, all of them
    fun matriz(
        contexto: ContextoArbitrios,
        datos: PredioArbitrios,
        personas: Map<String, Contribuyente>,
        hoy: LocalDate,
        soloDe: String? = null
    ): MatrizArbitrios {
        val anio = contexto.anio
        val cuotas = datos.existentes.filter { it.anio == anio && (soloDe == null || it.contribuyente == soloDe) }
        val conCuotas = cuotas.mapNotNull { it.servicio }.toSet()
        val servicios =
            contexto.servicios.filter { s ->
                s.id in conCuotas || Periodo.MESES.any { vigente(s.vigenciaDesde, s.vigenciaHasta, Periodo.atribucion(anio, it)) }
            }
        val filas =
            ordenados(servicios).map { servicio ->
                val meses = Periodo.MESES.map { m -> cuotas.firstOrNull { it.servicio == servicio.id && it.periodo == m }?.let(::celda) }
                FilaServicio(servicio, meses, suma(meses.mapNotNull { it?.monto }))
            }
        val totalesPorMes = Periodo.MESES.map { m -> suma(filas.mapNotNull { it.meses[m - 1]?.monto }) }
        val titulares =
            Periodo.MESES.map { m ->
                val dia = Periodo.atribucion(anio, m)
                val principal = titularPrincipal(datos.declaraciones.filter { cubre(it, dia) }, datos.codigos)
                TitularMes(m, principal?.contribuyente?.let { persona(it, personas[it]) })
            }
        val pendiente = Arbitrios.determinar(contexto, datos, CONSULTA, hoy)
        return MatrizArbitrios(
            anio,
            PredioResumen(datos.predio.id, datos.predio.codigo, datos.predio.direccion),
            filas,
            titulares,
            totalesPorMes,
            suma(totalesPorMes),
            cuotas.mapNotNull { it.fechaCalculo }.maxOrNull(),
            pendiente.cuotas.size,
            pendiente.faltan
        )
    }

    fun persona(
        id: String,
        c: Contribuyente?
    ) = Persona(id, c?.codigo, c?.nombreCompleto)

    fun suma(montos: List<BigDecimal>): BigDecimal = montos.fold(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP)

    // in the order they are listed and printed (as Arbitrios.serviciosDelDia), whatever their vigencia
    private fun ordenados(servicios: List<ServicioArbitrio>) =
        servicios.sortedWith(compareBy(nullsLast()) { it: ServicioArbitrio -> it.orden }.thenBy { it.codigo })

    private fun celda(c: CuotaArbitrio) = CuotaMes(c.id, c.monto ?: BigDecimal.ZERO, c.contribuyente, c.fechaCalculo, c.parametroAplicado)
}
