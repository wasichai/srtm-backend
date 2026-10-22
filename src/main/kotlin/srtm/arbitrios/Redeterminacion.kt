package srtm.arbitrios

import srtm.rentas.Declaracion
import srtm.rentas.secuenciaUso
import java.math.BigDecimal
import java.time.LocalDate

// #158: a declaración that changes what its predio's arbitrios are charged on (cambia) redetermines the whole year of
// it: the twelve months computed again with the declaraciones as they are now (completa), and compared with the
// vigente cuotas (comparar). a month whose cuota comes out the same is not touched; one that changed is annulled and
// written again as the next version of its clave; one without a cuota that now has one is written; one no longer
// charged (its titular gone) is annulled. a month paid in caja (#165, D7) is neither annulled nor written again: it
// stays as it was paid, and is counted. ArbitriosService.redeterminarPredio reads and writes
object Redeterminacion {
    // what a redeterminación writes and annuls: never a cuota that comes out the same. pagadas: the months paid in
    // caja it would have changed, left as they are
    data class Cambios(
        val escribir: List<CuotaArbitrio>,
        val anular: List<CuotaArbitrio>,
        val pagadas: Int = 0
    )

    // Arbitrios.determinar with every cuota of the year counted as annulled: no month is skipped for having one, each
    // takes the next version of its clave, and the tope measures the whole year (none of it is "already written").
    // a predio no declaración covers any month of (its only one annulled, or deleted) has no cuotas: not a titular
    // missing, as it is for determinar
    fun completa(
        c: ContextoArbitrios,
        p: PredioArbitrios,
        observacion: String,
        hoy: LocalDate
    ): Determinacion {
        if (Periodo.MESES.none { m -> p.declaraciones.any { cubre(it, Periodo.atribucion(c.anio, m)) } }) return Determinacion(emptyList(), emptyList())
        return Arbitrios.determinar(c, p.copy(anuladas = p.existentes.mapNotNull { it.id }.toSet()), observacion, hoy)
    }

    // the vigente cuotas of a predio's year (Arbitrios.vigentes) against the ones computed now, by servicio, month and
    // secuencia. a cuota without secuencia (one PU per predio, before #84) is the lowest one's of its month. pagadas:
    // the ids of the vigentes paid in caja, whose month is not touched
    fun comparar(
        vigentes: List<CuotaArbitrio>,
        calculadas: List<CuotaArbitrio>,
        pagadas: Set<String> = emptySet()
    ): Cambios {
        val menores =
            calculadas
                .groupBy { it.servicio to it.periodo }
                .mapValues { (_, cs) -> cs.minOf { secuenciaUso(it.secuenciaUso) } }
        val deAntes = { q: CuotaArbitrio -> llave(q, menores[q.servicio to q.periodo] ?: secuenciaUso(null)) }
        val deAhora = { q: CuotaArbitrio -> llave(q, secuenciaUso(q.secuenciaUso)) }
        val antes = vigentes.associateBy(deAntes)
        val ahora = calculadas.associateBy(deAhora)
        val escribir = calculadas.filter { q -> antes[deAhora(q)]?.let { igual(it, q) } != true }
        val anular = vigentes.filter { q -> ahora[deAntes(q)]?.let { igual(q, it) } != true }
        val pagados = vigentes.filter { it.id != null && it.id in pagadas }.map(deAntes).toSet()
        if (pagados.isEmpty()) return Cambios(escribir, anular)
        val tocados = (anular.map(deAntes) + escribir.map(deAhora)).toSet()
        return Cambios(escribir.filter { deAhora(it) !in pagados }, anular.filter { deAntes(it) !in pagados }, pagados.count { it in tocados })
    }

    // whether a declaración's change (null: one created, or deleted) touches what the arbitrios read: who covers
    // which month (titularPrincipal, cubre), its uso (codigoDeUso) and its bases (Calculo)
    fun cambia(
        antes: Declaracion?,
        despues: Declaracion?
    ): Boolean = antes == null || despues == null || bases(antes) != bases(despues)

    private fun bases(d: Declaracion) =
        listOf(
            d.predio,
            d.anio,
            d.estado,
            d.contribuyente,
            secuenciaUso(d.secuenciaUso),
            d.porcentajeCondominio?.stripTrailingZeros(),
            d.condicionPropiedad,
            d.fechaAdquisicion,
            d.fechaAnulacion,
            d.fechaPresentacion,
            d.claseUso,
            d.subClaseUso,
            d.uso,
            d.numeroHabitantes,
            d.areaConstruida?.stripTrailingZeros(),
            d.longitudFrente?.stripTrailingZeros()
        )

    private fun llave(
        q: CuotaArbitrio,
        secuencia: String
    ) = Triple(q.servicio, q.periodo, secuencia)

    // what a cuota was determined with and to whom: not when, why (its observación) nor its clave's version
    private fun igual(
        a: CuotaArbitrio,
        b: CuotaArbitrio
    ) = lectura(a) == lectura(b)

    private fun lectura(q: CuotaArbitrio) =
        listOf(
            q.contribuyente,
            q.parametro,
            q.monto.valor(),
            q.parametroAplicado,
            q.zona,
            q.usoArbitrio,
            q.base,
            q.cantidadBase.valor(),
            q.tasaUnitaria.valor(),
            q.habitantes,
            q.habitantesPresuntos,
            q.promedioHabitantes.valor(),
            q.variacionHabitante.valor(),
            q.influencia,
            q.afluencia,
            q.redondeo,
            q.montoSinTope.valor(),
            q.topeAplicado.valor()
        )

    private fun BigDecimal?.valor() = this?.stripTrailingZeros()
}
