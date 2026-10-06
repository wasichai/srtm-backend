package srtm.arbitrios

import srtm.impuesto.ParametroTributario
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import srtm.rentas.secuenciaUso
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
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

// the cuotas to write, or what is missing to write any: never both. faltan names each missing thing once; detalle
// is the same, each with the predio and the declaración to fix when it is theirs (the portal links to them)
data class Determinacion(
    val cuotas: List<CuotaArbitrio>,
    val faltan: List<String>,
    val detalle: List<Falta> = emptyList()
)

// one missing thing: its mensaje (what faltan lists), the predio it was found on and the declaración that lacks it,
// when a declaración can fix it (an área, a frontis, a uso)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Falta(
    val mensaje: String,
    val predio: String? = null,
    val declaracion: String? = null
)

// rentas' DeterminarArbitrios, without the writing (rentas#31), one PU per uso as the SRTM web (#84). each month of
// the year is decided on its day of atribución (Periodo): its titular (the titular principal of the declarations that
// cover that day, charged whole, every secuencia's cuotas included), and each secuencia de uso of those declarations
// apart (porSecuencia: the declaration of its own titular principal), with its own uso de arbitrio (ARBITRIO_USO of the
// declaration's uso), área, frontis and habitantes. per servicio, its zona is the predio's (Zonas.de:
// zona_arbitrio_ubicacion by via and tramo, ARBITRIO_ZONA of the sector catastral as a fallback). each servicio in
// force that day not covered by an inafectación and not yet determined for that secuencia gets a cuota: its tasa
// (TASA_ARBITRIO servicio, plus one parte per Dimension of Servicios.dimensiones - zona and uso by default,
// Dimensiones.valores resolves INFLUENCIA and AFLUENCIA too when listed) × its base's cantidad × the habitantes
// factor, rounded (Calculo.calcular), its desglose kept on the cuota. without BASE_ARBITRIO, habitantes or
// REDONDEO_ARBITRIO rows that is the tasa as is, same as before.
// a month without a titular is not charged: a predio that appears in july owes nothing before. a predio without a
// titular in any month cannot be determined. a zona/sector problem (no zona for a servicio, by ubicación or by
// sector, or an ambiguous one) always fails the whole predio - the sector is a requirement unless every servicio's
// zona resolves by ubicación instead. a secuencia's month without a uso código, though, is skipped silently, same as
// a month without a titular, once that secuencia had one some month - a secuencia that never had one fails the
// predio, named. and when NO month and secuencia ever has both a zona (by sector or ubicación) and a uso código, the
// predio cannot be determined at all and each one without it is named. a missing parameter fails the whole
// predio, same as a dimension (INFLUENCIA/AFLUENCIA) that does not resolve, or a secuencia without the frontis or
// the área its base asks for: everything is computed before anything is written, so nothing stays half done. the
// cuotas that exist are not recomputed, so running it again adds nothing. one annulled is determined again, with
// the next version of its clave
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
        if (delAnio.isNotEmpty()) return Determinacion(emptyList(), delAnio, delAnio.map { Falta(it) })

        val predioId = requireNotNull(p.predio.id) { "El predio no tiene id" }
        val nombre = p.predio.codigo ?: predioId
        val sector =
            p.predio.sectorCatastral
                ?.trim()
                ?.ifEmpty { null }
        // each missing thing once, by its mensaje: the first place it was found names its declaración
        val faltan = linkedMapOf<String, Falta>()
        // per secuencia: the declaración of its first month without a uso código, and whether some month had one
        val sinUso = linkedMapOf<String, String?>()
        val conUso = mutableSetOf<String>()

        fun falta(
            mensaje: String,
            declaracion: String? = null
        ) {
            faltan.putIfAbsent(mensaje, Falta(mensaje, predioId, declaracion))
        }
        val cuotas = mutableListOf<CuotaArbitrio>()
        val vigentes = p.vigentes // filtered once, not per month, secuencia and servicio
        var algunMesConTitular = false
        var algunMesConRasgos = false

        for (periodo in Periodo.MESES) {
            val dia = Periodo.atribucion(c.anio, periodo)
            val cubren = p.declaraciones.filter { cubre(it, dia) }
            val titular = titularPrincipal(cubren, p.codigos) ?: continue
            algunMesConTitular = true
            // one PU per uso: each secuencia's declaration. a cuota of before the secuencia counts as the lowest's
            val usos = porSecuencia(cubren, p.codigos).map { it to secuenciaUso(it.secuenciaUso) }
            val menor = usos.first().second
            val redondeo = Servicios.redondeo(c.parametros, dia)

            // the servicios due this month, their inafectaciones filtered out; and for each secuencia, the ones it
            // has no cuota of yet
            val debidos =
                serviciosDelDia(c.servicios, dia).filter { servicio ->
                    !p.inafectaciones.any { it.servicio == servicio.id && vigente(it.vigenciaDesde, it.vigenciaHasta, dia) }
                }
            val pendientes =
                usos.map { (d, secuencia) ->
                    Triple(
                        d,
                        secuencia,
                        debidos.filter { servicio ->
                            vigentes.none { it.servicio == servicio.id && it.anio == c.anio && it.periodo == periodo && secuenciaDe(it, menor) == secuencia }
                        }
                    )
                }
            val servicios = debidos.filter { servicio -> pendientes.any { servicio in it.third } }

            // each due servicio's zona, tried before giving up on a missing codigoUso/uso de arbitrio below (so the
            // sector/zona problems are always named, same as today). unlike the uso código below, a zona/sector
            // problem always fails the whole predio: it never gets silently skipped. a resolved or ambiguous zona
            // (by ubicación, without a sector) lifts the sector requirement for the whole month: the uso mapping is
            // only worth checking then, same as when the sector itself is present. the zona is the predio's: the
            // same for every secuencia
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
                        falta("Zona ambigua de $codigoServicio para ${p.predio.via} ${p.predio.numero}: ${resuelta.zonas.joinToString()}")
                    }
                    ZonaResuelta.Ninguna ->
                        falta(if (sector != null) "${Llaves.ARBITRIO_ZONA} $sector ${c.anio}" else "Sector catastral del predio $nombre")
                }
            }
            val rasgos = sector != null || rasgosPorUbicacion

            for ((d, secuencia, suyos) in pendientes) {
                // rasgos complete: a zona is available this month (sector present, or ubicación resolved some
                // servicio without one) and the secuencia's declaración has a uso código. same as today: a month
                // missing either (unlike a zona/sector problem) is just skipped - it only fails the predio when the
                // secuencia never has a uso código, or NO month and secuencia ever has both (see the end)
                val codigoUso = codigoDeUso(d, c.usos)
                if (codigoUso == null) {
                    sinUso.putIfAbsent(secuencia, d.id)
                } else {
                    conUso += secuencia
                    if (rasgos) algunMesConRasgos = true
                }

                val uso = if (rasgos && codigoUso != null) usoDe(c.parametros, codigoUso, dia) else null
                if (rasgos && codigoUso != null && uso == null) falta("${Llaves.ARBITRIO_USO} $codigoUso ${c.anio}")
                if (!rasgos || codigoUso == null || uso == null) continue

                val quien = "$nombre, uso $secuencia, declaración ${c.anio}"
                for (servicio in suyos) {
                    val servicioId = servicio.id!!
                    val zona = zonas[servicioId] ?: continue
                    val codigoServicio = servicio.codigo!!.trim()
                    val dimensiones = Servicios.dimensiones(c.parametros, codigoServicio, dia)
                    val (valoresDimensiones, faltanDimensiones) =
                        Dimensiones.valores(
                            dimensiones,
                            zona,
                            uso,
                            { Dimensiones.influencia(c.parametros, p.predio, nombre, c.anio, dia) },
                            { Dimensiones.afluencia(c.parametros, codigoServicio, codigoUso, c.anio, dia) }
                        )
                    if (faltanDimensiones.isNotEmpty()) {
                        faltanDimensiones.forEach { falta(it) }
                        continue
                    }
                    val claveDeTasa = Llaves.tasa(codigoServicio, valoresDimensiones)
                    val tasa = enVigor(c.parametros, Llaves.TASA_ARBITRIO, dia) { it.clave?.trim() == claveDeTasa && it.valorNumerico != null }
                    if (tasa == null) {
                        falta("${Llaves.TASA_ARBITRIO} $claveDeTasa ${c.anio}")
                        continue
                    }
                    val baseLeida = Servicios.baseLeida(c.parametros, codigoServicio, dia)
                    val habitantes = Servicios.habitantes(c.parametros, codigoServicio, zona, uso, dia)
                    val desglose =
                        when (val calculado = Calculo.calcular(baseLeida ?: Base.PREDIO, tasa.valorNumerico!!, d, habitantes, redondeo, quien)) {
                            is Calculado.Falta -> {
                                falta(calculado.mensaje, d.id)
                                continue
                            }
                            is Calculado.Es -> calculado.desglose
                        }
                    val porDimension = dimensiones.zip(valoresDimensiones).toMap()
                    val version =
                        1 +
                            (
                                p.existentes
                                    .filter { it.servicio == servicioId && it.anio == c.anio && it.periodo == periodo && secuenciaDe(it, menor) == secuencia }
                                    .mapNotNull { versionDe(it.clave) }
                                    .maxOrNull()
                                    ?: 0
                            )
                    val cuota =
                        CuotaArbitrio(
                            predio = predioId,
                            contribuyente = titular.contribuyente,
                            servicio = servicioId,
                            parametro = tasa.id,
                            anio = c.anio,
                            periodo = periodo,
                            monto = desglose.monto,
                            parametroAplicado = aplicado(claveDeTasa, codigoServicio, baseLeida, habitantes, Llaves.tasa(codigoServicio, zona, uso), redondeo),
                            zona = zona,
                            usoArbitrio = uso,
                            secuenciaUso = secuencia,
                            base = desglose.base.name,
                            cantidadBase = desglose.cantidadBase,
                            tasaUnitaria = desglose.tasaUnitaria,
                            habitantes = desglose.habitantes,
                            habitantesPresuntos = desglose.habitantesPresuntos.takeIf { habitantes != null },
                            promedioHabitantes = desglose.promedioHabitantes,
                            variacionHabitante = desglose.variacionHabitante,
                            influencia = porDimension[Dimension.INFLUENCIA],
                            afluencia = porDimension[Dimension.AFLUENCIA],
                            redondeo = desglose.redondeo?.toString(),
                            fechaCalculo = hoy,
                            observacion = observacion,
                            clave = claveDeCuota(predioId, servicioId, c.anio, periodo, secuencia, version)
                        )
                    val malas = invariantes(cuota)
                    if (malas.isEmpty()) {
                        cuotas += cuota
                    } else {
                        falta("${Llaves.TASA_ARBITRIO} $claveDeTasa ${c.anio}: " + malas.joinToString("; ") { "${it.field} ${it.message}" })
                    }
                }
            }
        }
        if (!algunMesConTitular) {
            falta("Titular del predio $nombre en ${c.anio}: ninguna declaración jurada lo cubre")
        } else {
            // a secuencia's months without a uso código are skipped silently once it had one some month (a sale to a
            // buyer without it); one that never had one fails the predio, named when the predio has several. when no
            // month and secuencia ever had complete rasgos, every one without it is named, same as before
            val varios = (sinUso.keys + conUso).size > 1
            sinUso
                .filterKeys { !algunMesConRasgos || it !in conUso }
                .forEach { (secuencia, declaracion) ->
                    falta("Uso del catálogo en la declaración ${c.anio} del predio $nombre" + (if (varios) ", uso $secuencia" else ""), declaracion)
                }
        }
        val detalle = faltan.values.toList()
        return if (detalle.isEmpty()) Determinacion(cuotas, emptyList()) else Determinacion(emptyList(), detalle.map { it.mensaje }, detalle)
    }

    // the secuencia de uso a cuota is of: its own, or, for one written before cuotas had one (one PU per predio, the
    // lowest secuencia's), the lowest of the month's
    private fun secuenciaDe(
        cuota: CuotaArbitrio,
        menor: String
    ) = cuota.secuenciaUso?.let(::secuenciaUso) ?: menor

    // what a cuota keeps as parametro_aplicado: every key read, separated by ";". the tasa's always; the base, the
    // habitantes and the redondeo only when they had rows, so without them it is the tasa's alone, same as before
    private fun aplicado(
        claveDeTasa: String,
        servicio: String,
        base: Base?,
        habitantes: Habitantes?,
        claveDeHabitantes: String,
        redondeo: Redondeo?
    ) = buildList {
        add(Llaves.aplicado(claveDeTasa))
        if (base != null) add("${Llaves.BASE_ARBITRIO}:$servicio")
        if (habitantes != null) {
            add("${Llaves.HABITANTES_PROMEDIO}:$claveDeHabitantes")
            add("${Llaves.HABITANTES_VARIACION}:$claveDeHabitantes")
        }
        if (redondeo != null) add(Llaves.REDONDEO_ARBITRIO)
    }.joinToString(";")

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
