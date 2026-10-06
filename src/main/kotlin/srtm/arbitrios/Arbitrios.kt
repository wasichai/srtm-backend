package srtm.arbitrios

import srtm.impuesto.ParametroTributario
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import srtm.rentas.secuenciaUso
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

// what a determination needs that is the same for every predio of the year: read once per determination, and once
// per lote in the masiva. ordenanza is the year's (null when there is none). porVia is ubicaciones grouped by their
// normalized via, built the first time a predio needs it and kept for every other predio of the contexto (#82)
data class ContextoArbitrios(
    val anio: Int,
    val ordenanza: OrdenanzaArbitrio?,
    val servicios: List<ServicioArbitrio>,
    val parametros: List<ParametroTributario>,
    val usos: List<UsoPredio>,
    val ubicaciones: List<ZonaUbicacion> = emptyList()
) {
    val porVia: UbicacionesPorVia by lazy { UbicacionesPorVia(ubicaciones) }
}

// one predio's: its declarations of the year (every titular's), its contribuyentes' codes by id, its inafectaciones,
// the cuotas of the year it already has and the ids of the ones annulled (which count for nothing). anteriores are
// its vigente cuotas of the year before (Arbitrios.vigentes), every servicio's: what TOPE_ARBITRIO measures the year
// against. registrado is when the predio entered the registry: the earliest of its declaraciones of any year, each
// by its fecha_presentacion or, for a migrated one without it, 1 January of its anio (Arbitrios.registrado;
// TOPE_ARBITRIO's cut date is measured against it). ArbitriosService reads both only when the year has a
// TOPE_ARBITRIO row; without anteriores there is no tope
data class PredioArbitrios(
    val predio: Predio,
    val declaraciones: List<Declaracion>,
    val codigos: Map<String, String?>,
    val inafectaciones: List<InafectacionArbitrio>,
    val existentes: List<CuotaArbitrio>,
    val anuladas: Set<String> = emptySet(),
    val anteriores: List<CuotaArbitrio> = emptyList(),
    val registrado: LocalDate? = null
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
// the next version of its clave. last, the predio's tope de incremento (conTope, TOPE_ARBITRIO): without one the
// cuotas are the ones above, untouched
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
        // the predio's zona_arbitrio_ubicacion rows (its via's, tramo's and paridad's), narrowed once, not per servicio
        // and month
        val ubicaciones = c.porVia.delPredio(p.predio)
        // TOPE_ARBITRIO's group: the uso código of the predio's lowest secuencia, the first month it has one
        var grupo: Pair<String, LocalDate>? = null

        for (periodo in Periodo.MESES) {
            val dia = Periodo.atribucion(c.anio, periodo)
            val cubren = p.declaraciones.filter { cubre(it, dia) }
            val titular = titularPrincipal(cubren, p.codigos) ?: continue
            algunMesConTitular = true
            // one PU per uso: each secuencia's declaration. a cuota of before the secuencia counts as the lowest's
            val usos = porSecuencia(cubren, p.codigos).map { it to secuenciaUso(it.secuenciaUso) }
            val menor = usos.first().second
            if (grupo == null) grupo = codigoDeUso(usos.first().first, c.usos)?.let { it to dia }
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
            // same for every secuencia. whether the month has a zona does not depend on what is pending: a due
            // servicio already determined is tried too, until one resolves, only to decide it (its problems are not
            // named, there is nothing of it to write) - so a run with nothing pending checks the usos the same as
            // the first one did, with or without a sector
            var rasgosPorUbicacion = false
            val zonas = mutableMapOf<String, String>()
            for (servicio in debidos) {
                val pendiente = servicio in servicios
                if (!pendiente && (sector != null || rasgosPorUbicacion)) continue
                val codigoServicio = servicio.codigo!!.trim()
                when (val resuelta = Zonas.de(ubicaciones, c.parametros, codigoServicio, p.predio, dia)) {
                    is ZonaResuelta.Es -> {
                        if (pendiente) zonas[servicio.id!!] = resuelta.zona
                        if (sector == null) rasgosPorUbicacion = true
                    }
                    is ZonaResuelta.Ambigua -> {
                        if (sector == null) rasgosPorUbicacion = true
                        if (pendiente) falta("Zona ambigua de $codigoServicio para ${p.predio.via} ${p.predio.numero}: ${resuelta.zonas.joinToString()}")
                    }
                    ZonaResuelta.Ninguna ->
                        if (pendiente) falta(if (sector != null) "${Llaves.ARBITRIO_ZONA} $sector ${c.anio}" else "Sector catastral del predio $nombre")
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
                    val claveDeTasaSinZona = Llaves.tasaSinZona(codigoServicio, valoresDimensiones, dimensiones.firstOrNull() == Dimension.ZONA)

                    fun tasaDe(clave: String) = enVigor(c.parametros, Llaves.TASA_ARBITRIO, dia) { it.clave?.trim() == clave && it.valorNumerico != null }
                    // the exact key always wins; the empty-zona one (when the servicio's first dimension is ZONA)
                    // is tried only when it does not exist (import_tasas_arbitrio.py, #86)
                    val tasa = tasaDe(claveDeTasa) ?: claveDeTasaSinZona?.let(::tasaDe)
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
                            parametroAplicado =
                                aplicado(
                                    tasa.clave!!.trim(),
                                    codigoServicio,
                                    baseLeida,
                                    habitantes,
                                    Llaves.tasa(codigoServicio, zona, uso),
                                    redondeo
                                ),
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
                        falta(
                            "${Llaves.TASA_ARBITRIO} ${tasa.clave!!.trim()} ${c.anio}: " +
                                malas.joinToString("; ") { "${it.field} ${it.message}" }
                        )
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
        if (faltan.isEmpty()) {
            val topadas = conTope(c, p, cuotas, grupo)
            topadas.forEach { q ->
                val malas = invariantes(q)
                if (malas.isNotEmpty()) falta("${Llaves.TOPE_ARBITRIO} ${c.anio}: " + malas.joinToString("; ") { "${it.field} ${it.message}" })
            }
            if (faltan.isEmpty()) return Determinacion(topadas, emptyList())
        }
        val detalle = faltan.values.toList()
        return Determinacion(emptyList(), detalle.map { it.mensaje }, detalle)
    }

    // TOPE_ARBITRIO (#87): what a predio's year may rise over what it paid the year before (anteriores, every
    // servicio's). its row is its group's: the uso código of its lowest secuencia (Servicios.tope, the longest prefix
    // or "*"). no tope without that row, without anteriores (or with ones that add up to 0), or when the predio was
    // registered after the row's cut date (the earliest of its declaraciones of any year, each by its
    // fecha_presentacion or, without one, 1 January of its anio: registrado, or this year's when it is earlier or
    // absent). otherwise
    // límite = anteriores × (1 + tope/100); when the cuotas of the year already written plus the new ones pass it,
    // only the new ones go down, in proportion, to límite − written (never below 0): each one rounded with its own
    // redondeo (2:ABAJO when it has none), what the rounding leaves goes to the last ones (in the order they were
    // determined: month, secuencia, servicio) without any going above what it was, and the target itself is cut
    // down to the last one's decimales - a tope is a maximum. each new one keeps monto_sin_tope and tope_aplicado
    private fun conTope(
        c: ContextoArbitrios,
        p: PredioArbitrios,
        nuevas: List<CuotaArbitrio>,
        grupo: Pair<String, LocalDate>?
    ): List<CuotaArbitrio> {
        if (nuevas.isEmpty() || p.anteriores.isEmpty() || grupo == null) return nuevas
        val tope = Servicios.tope(c.parametros, grupo.first, grupo.second) ?: return nuevas
        val deEsteAnio = p.declaraciones.mapNotNull { it.fechaPresentacion ?: it.anio?.let { anio -> LocalDate.of(anio, 1, 1) } }
        val registrado = listOfNotNull(p.registrado, deEsteAnio.minOrNull()).minOrNull()
        if (tope.corte != null && registrado != null && tope.corte.isBefore(registrado)) return nuevas
        val pagado = suma(p.anteriores)
        if (pagado.signum() <= 0) return nuevas
        val limite = pagado.multiply(BigDecimal.ONE + tope.porcentaje.movePointLeft(2))
        val escritas = suma(p.vigentes.filter { it.anio == c.anio })
        val debidas = suma(nuevas)
        if (escritas + debidas <= limite || debidas.signum() <= 0) return nuevas

        val objetivo = (limite - escritas).max(BigDecimal.ZERO).setScale(redondeoDe(nuevas.last()).decimales, RoundingMode.DOWN)
        val montos =
            nuevas
                .map { q -> redondeoDe(q).aplicar(q.monto!!.multiply(objetivo).divide(debidas, MathContext.DECIMAL128)).min(q.monto) }
                .toMutableList()
        var resto = objetivo - suma(nuevas.mapIndexed { i, q -> q.copy(monto = montos[i]) })
        for (i in montos.indices.reversed()) {
            if (resto.signum() == 0) break
            val ajuste = if (resto.signum() > 0) resto.min(nuevas[i].monto!! - montos[i]) else resto.max(montos[i].negate())
            montos[i] += ajuste
            resto -= ajuste
        }
        return nuevas.mapIndexed { i, q ->
            q.copy(
                monto = montos[i],
                montoSinTope = q.monto,
                topeAplicado = tope.porcentaje,
                parametroAplicado = "${q.parametroAplicado};${Llaves.TOPE_ARBITRIO}:${tope.clave}"
            )
        }
    }

    // when a predio entered the registry, for TOPE_ARBITRIO's cut date (#87): the earliest, over its declaraciones of
    // any year, of each one's fecha_presentacion or, for one without it (migrated), 1 January of its anio. that is
    // the earlier of the first fecha_presentacion and 1 January of the lowest anio among those without one: the two
    // rows ArbitriosService reads. null when there is neither
    fun registrado(
        primeraPresentacion: LocalDate?,
        primerAnioSinFecha: Int?
    ) = listOfNotNull(primeraPresentacion, primerAnioSinFecha?.let { LocalDate.of(it, 1, 1) }).minOrNull()

    // whether the year has a TOPE_ARBITRIO row in force on some month's day: only then does ArbitriosService read a
    // predio's cuotas of the year before. without one, nothing more is read and nothing changes
    fun conTope(c: ContextoArbitrios) =
        c.parametros.any { p ->
            p.tipo == Llaves.TOPE_ARBITRIO && Periodo.MESES.any { vigente(p.vigenciaDesde, p.vigenciaHasta, Periodo.atribucion(c.anio, it)) }
        }

    private fun suma(cuotas: List<CuotaArbitrio>) = cuotas.fold(BigDecimal.ZERO) { s, q -> s + (q.monto ?: BigDecimal.ZERO) }

    private fun redondeoDe(q: CuotaArbitrio) = Redondeo.de(q.redondeo) ?: Redondeo(2, RoundingMode.DOWN)

    // the cuotas of a year that count: not annulled, and of each predio, servicio, month and secuencia only the
    // latest version (a correction replaces the cuota it corrects). in the order they came
    fun vigentes(
        cuotas: List<CuotaArbitrio>,
        anuladas: Set<String>
    ): List<CuotaArbitrio> {
        val cuentan = cuotas.filter { it.id == null || it.id !in anuladas }
        val ultimas =
            cuentan
                .groupBy { listOf(it.predio, it.servicio, it.anio, it.periodo, it.secuenciaUso?.let(::secuenciaUso)) }
                .values
                .map { cs -> cs.maxBy { versionDe(it.clave) ?: 0 } }
        return cuentan.filter { q -> ultimas.any { it === q } }
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
