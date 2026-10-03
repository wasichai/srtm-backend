package srtm.sanciones

import srtm.legible
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import java.time.LocalDate
import java.util.Locale

// the CUIS is versioned by vigencia (rentas' CodigoInfraccion): a change never edits a row, it closes the version in
// force and adds a new one. an acta is explained with the version in force on the infracción's day, not today's
object Cuis {
    // a code as typed: trimmed and upper-cased
    fun normalizar(codigo: String): String = codigo.trim().uppercase(Locale.ROOT)

    // in force on `fecha`: both ends count
    fun rigeEn(
        c: CodigoInfraccion,
        fecha: LocalDate
    ): Boolean = c.vigenciaDesde != null && c.vigenciaDesde <= fecha && (c.vigenciaHasta == null || c.vigenciaHasta >= fecha)

    // among the versions of one code, the one in force on `fecha` (not the latest)
    fun vigenteA(
        versiones: List<CodigoInfraccion>,
        fecha: LocalDate
    ): CodigoInfraccion? = versiones.filter { rigeEn(it, fecha) }.maxByOrNull { it.vigenciaDesde!! }

    // the version in force closed the day before `nuevaDesde`: only vigencia_hasta and clave_vigente change. a closed
    // one is not closed again (409); a new version starts after the one in force (422)
    fun cerrar(
        vigente: CodigoInfraccion,
        nuevaDesde: LocalDate
    ): CodigoInfraccion {
        if (vigente.vigenciaHasta != null) {
            throw ConflictException(
                "La versión de ${vigente.codigo} del ${vigente.vigenciaDesde!!.legible()} ya está cerrada (el ${vigente.vigenciaHasta.legible()})"
            )
        }
        if (nuevaDesde <= vigente.vigenciaDesde!!) throw noPosterior(vigente, nuevaDesde)
        return vigente.copy(vigenciaHasta = nuevaDesde.minusDays(1), claveVigente = null)
    }

    // the version in force derogated: it rules until `hasta`, both ends counting, and no version follows it. as when a
    // new version closes it, only vigencia_hasta and clave_vigente change. a closed one is not closed again (409); it
    // ends on or after the day it started (422)
    fun derogar(
        vigente: CodigoInfraccion,
        hasta: LocalDate
    ): CodigoInfraccion {
        if (vigente.vigenciaHasta != null) {
            throw ConflictException(
                "La versión de ${vigente.codigo} del ${vigente.vigenciaDesde!!.legible()} ya está cerrada (el ${vigente.vigenciaHasta.legible()})"
            )
        }
        if (hasta < vigente.vigenciaDesde!!) {
            throw NoProcede(
                "La derogación de ${vigente.codigo} rige hasta el ${hasta.legible()}: es anterior a su versión del ${vigente.vigenciaDesde.legible()}",
                listOf(FieldViolation("vigencia_hasta", "no es anterior a la versión del ${vigente.vigenciaDesde.legible()}"))
            )
        }
        return vigente.copy(vigenciaHasta = hasta, claveVigente = null)
    }

    // what a new version from `nuevaDesde` writes besides itself: the version in force closed, or null for a code
    // without one. never overlaps: it starts after every version of the code
    fun versionNueva(
        versiones: List<CodigoInfraccion>,
        nuevaDesde: LocalDate
    ): CodigoInfraccion? {
        val abierta = versiones.filter { it.vigenciaHasta == null }.maxByOrNull { it.vigenciaDesde!! }
        val cerrada = abierta?.let { cerrar(it, nuevaDesde) }
        val pisada = versiones.filter { it != abierta && it.vigenciaHasta != null && it.vigenciaHasta >= nuevaDesde }.maxByOrNull { it.vigenciaDesde!! }
        if (pisada != null) throw noPosterior(pisada, nuevaDesde)
        return cerrada
    }

    private fun noPosterior(
        version: CodigoInfraccion,
        nuevaDesde: LocalDate
    ) = NoProcede(
        "La versión nueva de ${version.codigo} rige desde el ${nuevaDesde.legible()}: no es posterior a la del ${version.vigenciaDesde!!.legible()}" +
            (version.vigenciaHasta?.let { ", que rige hasta el ${it.legible()}" } ?: ", la vigente"),
        listOf(FieldViolation("vigencia_desde", "es posterior a la versión del ${version.vigenciaDesde!!.legible()}"))
    )
}
