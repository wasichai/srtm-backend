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
    val usos: List<UsoPredio>,
    val ubicaciones: List<ZonaUbicacion> = emptyList()
)

// one predio's: its declarations of the year (every titular's), its contribuyentes' codes by id, its inafectaciones,
// the cuotas of the year it already has and the ids of the ones annulled (which count for nothing)
data class PredioArbitrios(
    val predio: Predio,
    val declaraciones: List<Declaracion>,
    val codigos: Map<String, String?>,
    val inafectaciones: List<InafectacionArbitrio>,
    val existentes: List<CuotaArbitrio>,
    val anuladas: Set<String> = emptySet()
) {
    // the ones that count: not annulled
    val vigentes: List<CuotaArbitrio> get() = existentes.filter { it.id == null || it.id !in anuladas }
}

// the cuotas to write, or what is missing to write any: never both. faltan names each missing thing once
data class Determinacion(
    val cuotas: List<CuotaArbitrio>,
    val faltan: List<String>
)

// rentas' DeterminarArbitrios, without the writing (rentas#31). each month of the year is decided on its day of
// atribución (Periodo): its titular (the titular principal of the declarations that cover that day, charged whole)
// and the predio's rasgos, its uso de arbitrio (ARBITRIO_USO of the declaration's uso) and, per servicio, its zona
// (Zonas.de: zona_arbitrio_ubicacion by via and tramo, ARBITRIO_ZONA of the sector catastral as a fallback). each
// servicio in force that day not covered by an inafectación and not yet determined gets
// a cuota of its tasa (TASA_ARBITRIO servicio:zona:uso) as is: no area, no proration, no rounding.
// a month without a titular is not charged: a predio that appears in july owes nothing before. a predio without a
// titular in any month cannot be determined. a zona/sector problem (no zona for a servicio, by ubicación or by
// sector, or an ambiguous one) always fails the whole predio - the sector is a requirement unless some servicio's
// zona resolves by ubicación instead. a month without a uso código, though, is skipped silently, same as a month
// without a titular - unless NO month ever has both a zona (by sector or ubicación) and a uso código, in which
// case the predio cannot be determined at all and it's named. a missing parameter fails the whole predio:
// everything is computed before anything is written, so nothing stays half done. the cuotas that exist are not
// recomputed, so running it again adds nothing. one annulled is determined again, with the next version of its
// clave
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
        val sinUso = linkedSetOf<String>()
        val cuotas = mutableListOf<CuotaArbitrio>()
        var algunMesConTitular = false
        var algunMesConRasgos = false

        for (periodo in Periodo.MESES) {
            val dia = Periodo.atribucion(c.anio, periodo)
            val titular = titularPrincipal(p.declaraciones.filter { cubre(it, dia) }, p.codigos) ?: continue
            algunMesConTitular = true
            val codigoUso = codigoDeUso(titular, c.usos)

            // the servicios due this month, their inafectaciones and existing cuotas already filtered out
            val servicios =
                serviciosDelDia(c.servicios, dia).filter { servicio ->
                    val servicioId = requireNotNull(servicio.id) { "El servicio no tiene id" }
                    !p.inafectaciones.any { it.servicio == servicioId && vigente(it.vigenciaDesde, it.vigenciaHasta, dia) } &&
                        !p.vigentes.any { it.servicio == servicioId && it.anio == c.anio && it.periodo == periodo }
                }

            // each due servicio's zona, tried before giving up on a missing codigoUso/uso de arbitrio below (so the
            // sector/zona problems are always named, same as today). unlike the uso código below, a zona/sector
            // problem always fails the whole predio: it never gets silently skipped. a resolved or ambiguous zona
            // (by ubicación, without a sector) lifts the sector requirement for the whole month: the uso mapping is
            // only worth checking then, same as when the sector itself is present
            var rasgosPorUbicacion = false
            val zonas = mutableMapOf<String, String>()
            for (servicio in servicios) {
                val codigoServicio = servicio.codigo!!.trim()
                when (val resuelta = Zonas.de(c.ubicaciones, c.parametros, codigoServicio, p.predio, dia)) {
                    is ZonaResuelta.Es -> {
                        zonas[servicio.id!!] = resuelta.zona
                        if (sector == null) rasgosPorUbicacion = true
                    }
                    is ZonaResuelta.Ambigua -> {
                        if (sector == null) rasgosPorUbicacion = true
                        faltan += "Zona ambigua de $codigoServicio para ${p.predio.via} ${p.predio.numero}: ${resuelta.zonas.joinToString()}"
                    }
                    ZonaResuelta.Ninguna ->
                        faltan += if (sector != null) "${Llaves.ARBITRIO_ZONA} $sector ${c.anio}" else "Sector catastral del predio $nombre"
                }
            }

            // rasgos complete: a zona is available this month (sector present, or ubicación resolved some servicio
            // without one) and the declaración has a uso código. same as today: a month missing either (unlike a
            // zona/sector problem) is just skipped - it only fails the predio when NO month ever has both
            val rasgos = sector != null || rasgosPorUbicacion
            if (codigoUso == null) {
                sinUso += "Uso del catálogo en la declaración ${c.anio} del predio $nombre"
            } else if (rasgos) {
                algunMesConRasgos = true
            }

            val uso = if (rasgos && codigoUso != null) usoDe(c.parametros, codigoUso, dia) else null
            if (rasgos && codigoUso != null && uso == null) faltan += "${Llaves.ARBITRIO_USO} $codigoUso ${c.anio}"
            if (!rasgos || codigoUso == null || uso == null) continue

            for (servicio in servicios) {
                val servicioId = servicio.id!!
                val zona = zonas[servicioId] ?: continue
                val codigoServicio = servicio.codigo!!.trim()
                val version =
                    1 +
                        (
                            p.existentes
                                .filter { it.servicio == servicioId && it.anio == c.anio && it.periodo == periodo }
                                .mapNotNull {
                                    versionDe(
                                        it.clave
                                    )
                                }.maxOrNull()
                                ?: 0
                        )
                val claveDeTasa = Llaves.tasa(codigoServicio, zona, uso)
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
                        clave = claveDeCuota(predioId, servicioId, c.anio, periodo, version)
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
            faltan += sinUso
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
}
