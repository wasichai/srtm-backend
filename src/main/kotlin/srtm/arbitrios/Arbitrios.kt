package srtm.arbitrios

import srtm.impuesto.ParametroTributario
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import java.time.LocalDate

// what a determination needs that is the same for every predio of the year: read once per determination, and once
// per lote in the masiva. ordenanza is the year's (null when there is none)
data class ContextoArbitrios(
    val anio: Int,
    val ordenanza: OrdenanzaArbitrio?,
    val servicios: List<ServicioArbitrio>,
    val parametros: List<ParametroTributario>,
    val usos: List<UsoPredio>
)

// one predio's: its declarations of the year (every titular's), its contribuyentes' codes by id, its inafectaciones
// and the cuotas of the year it already has
data class PredioArbitrios(
    val predio: Predio,
    val declaraciones: List<Declaracion>,
    val codigos: Map<String, String?>,
    val inafectaciones: List<InafectacionArbitrio>,
    val existentes: List<CuotaArbitrio>
)

// the cuotas to write, or what is missing to write any: never both. faltan names each missing thing once
data class Determinacion(
    val cuotas: List<CuotaArbitrio>,
    val faltan: List<String>
)

// rentas' DeterminarArbitrios, without the writing (rentas#31). each month of the year is decided on its day of
// atribución (Periodo): its titular (the titular principal of the declarations that cover that day, charged whole)
// and the predio's rasgos, its zona (ARBITRIO_ZONA of the sector catastral) and its uso de arbitrio (ARBITRIO_USO of
// the declaration's uso). each servicio in force that day not covered by an inafectación and not yet determined gets
// a cuota of its tasa (TASA_ARBITRIO servicio:zona:uso) as is: no area, no proration, no rounding.
// a month without a titular or without rasgos is not charged: a predio that appears in july owes nothing before. a
// predio without a titular in any month, or without rasgos in any month with one, cannot be determined. a missing
// parameter fails the whole predio: everything is computed before anything is written, so nothing stays half done.
// the cuotas that exist are not recomputed, so running it again adds nothing
object Arbitrios {
    // what the year lacks, before any predio: the ordinance, ratified (D-02b), and the servicios it charges
    fun faltanDelAnio(c: ContextoArbitrios): List<String> {
        val faltan = mutableListOf<String>()
        val ordenanza = c.ordenanza?.takeIf { it.anio == c.anio }
        if (ordenanza == null) {
            faltan += "Ordenanza de arbitrios ${c.anio}"
        } else if (!ratificada(ordenanza)) {
            faltan += "Ratificación de la ordenanza de arbitrios ${c.anio} (acuerdo y fecha)"
        }
        if (Periodo.MESES.none { serviciosDelDia(c.servicios, Periodo.atribucion(c.anio, it)).isNotEmpty() }) {
            faltan += "Servicios de arbitrio vigentes en ${c.anio}"
        }
        return faltan
    }

    fun determinar(
        c: ContextoArbitrios,
        p: PredioArbitrios,
        observacion: String,
        hoy: LocalDate
    ): Determinacion {
        val delAnio = faltanDelAnio(c)
        if (delAnio.isNotEmpty()) return Determinacion(emptyList(), delAnio)

        val predioId = requireNotNull(p.predio.id) { "El predio no tiene id" }
        val nombre = p.predio.codigo ?: predioId
        val sector =
            p.predio.sectorCatastral
                ?.trim()
                ?.ifEmpty { null }
        val faltan = linkedSetOf<String>()
        val sinRasgos = linkedSetOf<String>()
        val cuotas = mutableListOf<CuotaArbitrio>()
        var algunMesConTitular = false
        var algunMesConRasgos = false

        for (periodo in Periodo.MESES) {
            val dia = Periodo.atribucion(c.anio, periodo)
            val titular = titularPrincipal(p.declaraciones.filter { cubre(it, dia) }, p.codigos) ?: continue
            algunMesConTitular = true
            val codigoUso = codigoDeUso(titular, c.usos)
            if (sector == null) sinRasgos += "Sector catastral del predio $nombre"
            if (codigoUso == null) sinRasgos += "Uso del catálogo en la declaración ${c.anio} del predio $nombre"
            if (sector == null || codigoUso == null) continue
            algunMesConRasgos = true

            val zona = zonaDe(c.parametros, sector, dia)
            val uso = usoDe(c.parametros, codigoUso, dia)
            if (zona == null) faltan += "${Llaves.ARBITRIO_ZONA} $sector ${c.anio}"
            if (uso == null) faltan += "${Llaves.ARBITRIO_USO} $codigoUso ${c.anio}"
            if (zona == null || uso == null) continue

            for (servicio in serviciosDelDia(c.servicios, dia)) {
                val servicioId = requireNotNull(servicio.id) { "El servicio no tiene id" }
                if (p.inafectaciones.any { it.servicio == servicioId && vigente(it.vigenciaDesde, it.vigenciaHasta, dia) }) continue
                if (p.existentes.any { it.servicio == servicioId && it.anio == c.anio && it.periodo == periodo }) continue
                val claveDeTasa = Llaves.tasa(servicio.codigo!!.trim(), zona, uso)
                val tasa = enVigor(c.parametros, Llaves.TASA_ARBITRIO, dia) { it.clave?.trim() == claveDeTasa && it.valorNumerico != null }
                if (tasa == null) {
                    faltan += "${Llaves.TASA_ARBITRIO} $claveDeTasa ${c.anio}"
                    continue
                }
                val cuota =
                    CuotaArbitrio(
                        predio = predioId,
                        contribuyente = titular.contribuyente,
                        servicio = servicioId,
                        parametro = tasa.id,
                        anio = c.anio,
                        periodo = periodo,
                        monto = tasa.valorNumerico,
                        parametroAplicado = Llaves.aplicado(claveDeTasa),
                        zona = zona,
                        usoArbitrio = uso,
                        fechaCalculo = hoy,
                        observacion = observacion,
                        clave = claveDeCuota(predioId, servicioId, c.anio, periodo)
                    )
                val malas = invariantes(cuota)
                if (malas.isEmpty()) {
                    cuotas += cuota
                } else {
                    faltan += "${Llaves.TASA_ARBITRIO} $claveDeTasa ${c.anio}: " + malas.joinToString("; ") { "${it.field} ${it.message}" }
                }
            }
        }
        if (!algunMesConTitular) {
            faltan += "Titular del predio $nombre en ${c.anio}: ninguna declaración jurada lo cubre"
        } else if (!algunMesConRasgos) {
            faltan += sinRasgos
        }
        return if (faltan.isEmpty()) Determinacion(cuotas, emptyList()) else Determinacion(emptyList(), faltan.toList())
    }

    // in force that day, in the order they are listed and printed
    fun serviciosDelDia(
        servicios: List<ServicioArbitrio>,
        dia: LocalDate
    ) = servicios
        .filter { it.id != null && !it.codigo.isNullOrBlank() && vigente(it.vigenciaDesde, it.vigenciaHasta, dia) }
        .sortedWith(compareBy(nullsLast()) { it: ServicioArbitrio -> it.orden }.thenBy { it.codigo })

    // the zona of a sector catastral that day
    fun zonaDe(
        parametros: List<ParametroTributario>,
        sector: String,
        dia: LocalDate
    ) = enVigor(parametros, Llaves.ARBITRIO_ZONA, dia) { it.clave?.trim() == sector && !it.texto.isNullOrBlank() }?.texto?.trim()

    // the uso de arbitrio of a uso_predio code that day: the mapping of its longest prefix
    fun usoDe(
        parametros: List<ParametroTributario>,
        codigoUso: String,
        dia: LocalDate
    ) = parametros
        .filter {
            val prefijo = it.clave?.trim()
            it.tipo == Llaves.ARBITRIO_USO && !prefijo.isNullOrEmpty() && codigoUso.startsWith(prefijo) && !it.texto.isNullOrBlank() &&
                vigente(it.vigenciaDesde, it.vigenciaHasta, dia)
        }.maxWithOrNull(compareBy<ParametroTributario> { it.clave!!.trim().length }.thenBy { it.vigenciaDesde })
        ?.texto
        ?.trim()

    // the row of a tipo in force that day that matches; the latest one when several do
    private fun enVigor(
        parametros: List<ParametroTributario>,
        tipo: String,
        dia: LocalDate,
        coincide: (ParametroTributario) -> Boolean
    ) = parametros
        .filter { it.tipo == tipo && vigente(it.vigenciaDesde, it.vigenciaHasta, dia) && coincide(it) }
        .maxByOrNull { it.vigenciaDesde!! }
}
