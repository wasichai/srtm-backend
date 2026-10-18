package srtm.brecha

import srtm.arbitrios.Arbitrios
import srtm.arbitrios.ContextoArbitrios
import srtm.arbitrios.InafectacionArbitrio
import srtm.arbitrios.PredioArbitrios
import srtm.impuesto.ImpuestoPredial
import srtm.impuesto.ParametroTributario
import srtm.rentas.Declaracion
import srtm.rentas.Predio
import srtm.rentas.UsoPredio
import srtm.rentas.condominio
import srtm.rentas.grupoDe
import srtm.rentas.totalesDeContribuyente
import srtm.rentas.totalesDePredio
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.IdentityHashMap
import srtm.rentas.vigente as declaracionVigente

// what a corrida reads of srtm: every predio of the organization (the candidates of the cruce), every declaración of
// the year (an annulled one too: arbitrios may charge the months before a sale; the impuesto counts only the vigentes),
// and for the arbitrios the contribuyentes' codes (the titular principal's tie-break) and the inafectaciones
data class Padron(
    val predios: List<Predio>,
    val declaraciones: List<Declaracion>,
    val codigos: Map<String, String?> = emptyMap(),
    val inafectaciones: List<InafectacionArbitrio> = emptyList()
)

// the rows of a corrida, or what the year lacks to liquidate (then no row). avisos are what could not be computed
// without failing the corrida: the arbitrios of the year, or of some predios
data class Medicion(
    val filas: List<FilaBrecha>,
    val faltan: List<String>,
    val avisos: List<String> = emptyList()
)

// a predio's impuesto, attributed from its contribuyentes': the part of their declared impuesto anual that falls on
// it, and the part of their difference with the catastro's values
data class ImpuestoDelPredio(
    val declarado: BigDecimal,
    val delta: BigDecimal
)

// what a contribuyente pays as declared and with the catastro's autoavalúos
data class ImpuestoDelContribuyente(
    val declarado: BigDecimal,
    val catastro: BigDecimal
) {
    val delta: BigDecimal get() = catastro - declarado
}

data class Impuestos(
    val porContribuyente: Map<String, ImpuestoDelContribuyente>,
    val porPredio: Map<String, ImpuestoDelPredio>
)

// the arbitrios' difference of a predio, or why there is none
sealed interface DeltaArbitrios {
    data class Es(
        val delta: BigDecimal
    ) : DeltaArbitrios

    data class Falta(
        val motivo: String
    ) : DeltaArbitrios
}

// the brecha de recaudación: the padrón (the DJ) against the catastro's valuations of the year. pure: the service reads
// and writes. srtm's own rules are reused, not copied: the predio's declared autoavalúo is totalesDePredio's, each
// titular's valor afecto is condominio's (its % and deducción kept), the impuesto is ImpuestoPredial.liquidar's and the
// arbitrios Arbitrios.determinar's
object Brecha {
    // D10: COINCIDE while |Δ autoavalúo| <= 5 % of the declared one
    val TOLERANCIA = BigDecimal("0.05")

    // what the arbitrios' hypothetical cuotas carry (never written)
    private const val OBSERVACION = "Brecha de recaudación"

    // a code compared as the catastro and the padrón may write it: trimmed, upper case. blank is no code
    fun llave(codigo: String?): String? = codigo?.trim()?.uppercase()?.ifEmpty { null }

    private fun sml(
        sector: String?,
        manzana: String?,
        lote: String?
    ): String? {
        val partes = listOf(sector, manzana, lote).map(::llave)
        return if (partes.any { it == null }) null else partes.joinToString("|")
    }

    // D7: each catastro item to the padrón's predio of its código municipal (predio.codigo), else of its código CPU,
    // else of its sector, manzana and lote (sector_catastral, manzana_catastral, lote). a predio crosses once: the first
    // item takes it, and a later one tries its next key. among several predios of the same key, those with a
    // declaración of the year (preferidos) go first, then by código. null: an omiso
    fun cruzar(
        items: List<ValuacionCatastral>,
        predios: List<Predio>,
        preferidos: Set<String> = emptySet()
    ): List<Pair<ValuacionCatastral, Predio?>> {
        val ordenados = predios.filter { it.id != null }.sortedWith(compareBy<Predio>({ it.id !in preferidos }, { it.codigo }))

        fun indice(clave: (Predio) -> String?) =
            ordenados
                .mapNotNull { p -> clave(p)?.let { it to p } }
                .groupBy({ it.first }, { it.second })
        val porCodigo = indice { llave(it.codigo) }
        val porCpu = indice { llave(it.codigoCpu) }
        val porSml = indice { sml(it.sectorCatastral, it.manzanaCatastral, it.lote) }
        val tomados = HashSet<String>()
        return items.map { item ->
            val candidatos =
                listOf(
                    llave(item.codigoMunicipal)?.let(porCodigo::get),
                    llave(item.codigoCpu)?.let(porCpu::get),
                    sml(item.sector, item.manzana, item.lote)?.let(porSml::get)
                )
            val predio = candidatos.asSequence().flatMap { it.orEmpty() }.firstOrNull { it.id !in tomados }
            if (predio != null) tomados += predio.id!!
            item to predio
        }
    }

    // a crossed and valued predio, by its declared autoavalúo (D10: ±5 % both ends included is COINCIDE)
    fun categoria(
        declarado: BigDecimal,
        catastro: BigDecimal?
    ): Categoria =
        when {
            catastro == null -> Categoria.SIN_VALOR
            catastro > declarado * (BigDecimal.ONE + TOLERANCIA) -> Categoria.SUBVALUADO
            catastro < declarado * (BigDecimal.ONE - TOLERANCIA) -> Categoria.SOBREVALUADO
            else -> Categoria.COINCIDE
        }

    // `total` in parts proportional to `pesos`, to the cent (half up); the last one takes what rounding leaves, so they
    // add up to total exactly. all pesos zero: equal parts
    fun repartir(
        total: BigDecimal,
        pesos: List<BigDecimal>
    ): List<BigDecimal> {
        if (pesos.isEmpty()) return emptyList()
        val usados = if (pesos.all { it.signum() == 0 }) pesos.map { BigDecimal.ONE } else pesos
        val suma = usados.fold(BigDecimal.ZERO, BigDecimal::add)
        val partes = usados.dropLast(1).map { total.multiply(it).divide(suma, 2, RoundingMode.HALF_UP) }
        return partes + total.setScale(2, RoundingMode.HALF_UP).subtract(partes.fold(BigDecimal.ZERO, BigDecimal::add))
    }

    // D12: the weight of each secuencia de uso of a predio in the catastro's value: its declared autoavalúo (the
    // highest of its titulares, as totalesDePredio counts it); all zero, its área construida; all zero too, equal
    fun pesos(secuencias: List<List<Declaracion>>): List<BigDecimal> {
        val autoavaluos = secuencias.map { s -> s.mapNotNull { it.valorAutoavaluo }.maxOrNull() ?: BigDecimal.ZERO }
        if (autoavaluos.any { it.signum() > 0 }) return autoavaluos
        val areas = secuencias.map { s -> s.mapNotNull { it.areaConstruida }.maxOrNull() ?: BigDecimal.ZERO }
        if (areas.any { it.signum() > 0 }) return areas
        return secuencias.map { BigDecimal.ONE }
    }

    // a predio's declarations with the catastro's autoavalúo instead of the declared one: split among its secuencias
    // (D12) and recomputed by condominio, which keeps each titular's % and deducción. in the order they came
    fun conCatastro(
        declaraciones: List<Declaracion>,
        autoavaluo: BigDecimal
    ): List<Declaracion> {
        val secuencias = declaraciones.groupBy(::grupoDe).values.toList()
        val nuevas = IdentityHashMap<Declaracion, Declaracion>()
        secuencias.zip(repartir(autoavaluo, pesos(secuencias))).forEach { (secuencia, parte) ->
            secuencia.zip(condominio(secuencia.map { it.copy(valorAutoavaluo = parte) })).forEach { (antes, despues) -> nuevas[antes] = despues }
        }
        return declaraciones.map { nuevas.getValue(it) }
    }

    // the impuesto of each contribuyente with a vigente declaración of the year, as declared and with the catastro's
    // autoavalúo on the predios it valued (catastro: predio id -> autoavalúo), liquidated by ImpuestoPredial on the sum
    // of each one's valor afecto. its difference falls on its predios in proportion to how much each one moved its valor
    // afecto (in absolute value), and its declared impuesto in proportion to each one's valor afecto: each predio's sums
    // the parts of its contribuyentes, so Σ per predio = Σ per contribuyente. the year's parameters must be there
    // (medir checks them first)
    fun impuestos(
        anio: Int,
        vigentes: List<Declaracion>,
        catastro: Map<String, BigDecimal>,
        parametros: List<ParametroTributario>
    ): Impuestos {
        val nuevas = IdentityHashMap<Declaracion, Declaracion>()
        vigentes.groupBy { it.predio }.forEach { (predio, suyas) ->
            val valor = predio?.let(catastro::get) ?: return@forEach
            suyas.zip(conCatastro(suyas, valor)).forEach { (antes, despues) -> nuevas[antes] = despues }
        }

        fun afecto(d: Declaracion) = d.valorAfecto ?: BigDecimal.ZERO
        val porContribuyente = linkedMapOf<String, ImpuestoDelContribuyente>()
        val declarados = HashMap<String, BigDecimal>()
        val deltas = HashMap<String, BigDecimal>()
        vigentes
            .filter { it.contribuyente != null }
            .groupBy { it.contribuyente!! }
            .toSortedMap()
            .forEach { (contribuyente, suyas) ->
                val antes = totalesDeContribuyente(suyas).valorAfecto
                val despues = suyas.fold(BigDecimal.ZERO) { s, d -> s + afecto(nuevas[d] ?: d) }
                val impuesto =
                    ImpuestoDelContribuyente(
                        ImpuestoPredial.liquidar(anio, antes, parametros).impuestoAnual!!,
                        ImpuestoPredial.liquidar(anio, despues, parametros).impuestoAnual!!
                    )
                porContribuyente[contribuyente] = impuesto
                val porPredio = suyas.filter { it.predio != null }.groupBy { it.predio!! }.toSortedMap()
                val predios = porPredio.keys.toList()
                val declarado = repartir(impuesto.declarado, porPredio.values.map { ds -> ds.fold(BigDecimal.ZERO) { s, d -> s + afecto(d) } })
                val delta =
                    repartir(
                        impuesto.delta,
                        porPredio.values.map { ds -> ds.fold(BigDecimal.ZERO) { s, d -> s + afecto(nuevas[d] ?: d) - afecto(d) }.abs() }
                    )
                predios.forEachIndexed { i, p ->
                    declarados.merge(p, declarado[i], BigDecimal::add)
                    deltas.merge(p, delta[i], BigDecimal::add)
                }
            }
        return Impuestos(porContribuyente, declarados.keys.associateWith { ImpuestoDelPredio(declarados.getValue(it), deltas.getValue(it)) })
    }

    // a predio's declarations with what the catastro says of it for the arbitrios: its uso (the uso_predio of that
    // code, when the catalog has it), its área construida split among the secuencias as the autoavalúo (D12), and its
    // frente (the predio's, whole on each secuencia: a frontis is not shared out)
    fun conRasgosDelCatastro(
        declaraciones: List<Declaracion>,
        item: ValuacionCatastral,
        usos: List<UsoPredio>
    ): List<Declaracion> {
        val uso = llave(item.uso)?.let { codigo -> usos.firstOrNull { llave(it.codigo) == codigo } }
        val secuencias = declaraciones.groupBy(::grupoDe).values.toList()
        val areas = item.areaConstruida?.let { repartir(it, pesos(secuencias)) }
        val nuevas = IdentityHashMap<Declaracion, Declaracion>()
        secuencias.forEachIndexed { i, secuencia ->
            secuencia.forEach { d ->
                nuevas[d] =
                    d.copy(
                        claseUso = uso?.clase ?: d.claseUso,
                        subClaseUso = uso?.subClase ?: d.subClaseUso,
                        uso = uso?.uso ?: d.uso,
                        areaConstruida = areas?.get(i) ?: d.areaConstruida,
                        longitudFrente = item.longitudFrente ?: d.longitudFrente
                    )
            }
        }
        return declaraciones.map { nuevas.getValue(it) }
    }

    // what Arbitrios.determinar charges the whole year with the catastro's rasgos minus what it charges as declared:
    // no cuota counts as already written and there is no tope (no cuotas of the year before), so both are the year's
    // full charge. when either cannot be determined, why
    fun deltaArbitrios(
        c: ContextoArbitrios,
        p: PredioArbitrios,
        item: ValuacionCatastral,
        hoy: LocalDate
    ): DeltaArbitrios {
        val anual = p.copy(existentes = emptyList(), anuladas = emptySet(), anteriores = emptyList())
        val declarado = Arbitrios.determinar(c, anual, OBSERVACION, hoy)
        if (declarado.faltan.isNotEmpty()) return DeltaArbitrios.Falta(declarado.faltan.first())
        val catastro = Arbitrios.determinar(c, anual.copy(declaraciones = conRasgosDelCatastro(anual.declaraciones, item, c.usos)), OBSERVACION, hoy)
        if (catastro.faltan.isNotEmpty()) return DeltaArbitrios.Falta("con catastro: ${catastro.faltan.first()}")
        return DeltaArbitrios.Es(suma(catastro.cuotas.map { it.monto }) - suma(declarado.cuotas.map { it.monto }))
    }

    // the omiso's impuesto as if its autoavalúo were a contribuyente's whole base (no contribuyente to add it to)
    fun estimado(
        anio: Int,
        autoavaluo: BigDecimal,
        parametros: List<ParametroTributario>
    ): BigDecimal = ImpuestoPredial.liquidar(anio, autoavaluo, parametros).impuestoAnual!!

    // a corrida's rows: one per catastro item (crossed or OMISO) and one per padrón predio the catastro lacks
    // (SIN_CATASTRO). the padrón is the predios with a vigente declaración of the year: a catastro item crossed with a
    // predio without one is an OMISO too (it keeps the predio). without the year's impuesto parameters, faltan and no
    // row. contexto null (or a year without what Arbitrios needs): no Δ arbitrios, and avisos says why
    fun medir(
        anio: Int,
        items: List<ValuacionCatastral>,
        padron: Padron,
        parametros: List<ParametroTributario>,
        contexto: ContextoArbitrios?,
        hoy: LocalDate,
        sinArbitrios: String? = null
    ): Medicion {
        val faltan = ImpuestoPredial.liquidar(anio, BigDecimal.ZERO, parametros).faltan
        if (faltan.isNotEmpty()) return Medicion(emptyList(), faltan)

        val delAnio = padron.declaraciones.filter { it.anio == anio }
        val vigentes = delAnio.filter(::declaracionVigente)
        val vigentesPorPredio = vigentes.filter { it.predio != null }.groupBy { it.predio!! }
        val todasPorPredio = delAnio.filter { it.predio != null }.groupBy { it.predio!! }
        val pares = cruzar(items, padron.predios, vigentesPorPredio.keys)
        val valuados =
            pares
                .mapNotNull { (item, predio) ->
                    val valor = item.autoavaluo ?: return@mapNotNull null
                    predio?.id?.takeIf { it in vigentesPorPredio }?.let { it to valor }
                }.toMap()
        val impuestos = impuestos(anio, vigentes, valuados, parametros)
        val cero = BigDecimal.ZERO.setScale(2)

        val avisos = mutableListOf<String>()
        val arbitrios =
            when {
                contexto == null -> {
                    avisos += "Δ arbitrios no calculado: ${sinArbitrios ?: "sin el contexto de arbitrios del año"}"
                    null
                }
                Arbitrios.faltanDelAnio(contexto).isNotEmpty() -> {
                    avisos += "Δ arbitrios no calculado: faltan ${Arbitrios.faltanDelAnio(contexto).joinToString("; ")}"
                    null
                }
                else -> contexto
            }
        val inafectaciones = padron.inafectaciones.filter { it.predio != null }.groupBy { it.predio!! }
        val sinDelta = linkedMapOf<String, Int>()

        fun arbitriosDe(
            predio: Predio,
            item: ValuacionCatastral
        ): BigDecimal? {
            val c = arbitrios ?: return null
            val p = PredioArbitrios(predio, todasPorPredio[predio.id].orEmpty(), padron.codigos, inafectaciones[predio.id].orEmpty(), emptyList())
            return when (val d = deltaArbitrios(c, p, item, hoy)) {
                is DeltaArbitrios.Es -> d.delta
                is DeltaArbitrios.Falta -> {
                    sinDelta.merge(d.motivo, 1, Int::plus)
                    null
                }
            }
        }

        val filas = mutableListOf<FilaBrecha>()
        val cruzados = HashSet<String>()
        for ((item, predio) in pares) {
            val base =
                FilaBrecha(
                    predio = predio?.id,
                    codigoCatastro = codigoCatastro(item),
                    codigoPredio = predio?.codigo,
                    sector = item.sector ?: predio?.sectorCatastral,
                    manzana = item.manzana ?: predio?.manzanaCatastral,
                    lote = item.lote ?: predio?.lote,
                    uso = item.uso,
                    autoavaluoCatastro = item.autoavaluo,
                    motivo = item.motivo
                )
            val suyas = predio?.id?.let(vigentesPorPredio::get)
            if (predio == null || suyas == null) {
                val impuesto = item.autoavaluo?.let { estimado(anio, it, parametros) }
                filas +=
                    base.copy(
                        categoria = Categoria.OMISO.name,
                        deltaAutoavaluo = item.autoavaluo,
                        impuestoCatastro = impuesto,
                        deltaImpuesto = impuesto,
                        estimado = impuesto != null,
                        motivo = item.motivo ?: predio?.let { "El predio ${it.codigo} no tiene DJ vigente en $anio" },
                        loteGeom = poligono(item.loteGeom) ?: poligono(predio?.loteGeom)
                    )
                continue
            }
            cruzados += predio.id!!
            val declarado = totalesDePredio(suyas).autoavaluo.setScale(2, RoundingMode.HALF_UP)
            val impuesto = impuestos.porPredio[predio.id] ?: ImpuestoDelPredio(cero, cero)
            val valor = item.autoavaluo
            filas +=
                base.copy(
                    categoria = categoria(declarado, valor).name,
                    autoavaluoDeclarado = declarado,
                    deltaAutoavaluo = valor?.subtract(declarado),
                    impuestoDeclarado = impuesto.declarado,
                    impuestoCatastro = valor?.let { impuesto.declarado + impuesto.delta },
                    deltaImpuesto = valor?.let { impuesto.delta },
                    deltaArbitrios = valor?.let { arbitriosDe(predio, item) },
                    estimado = false,
                    loteGeom = poligono(predio.loteGeom) ?: poligono(item.loteGeom)
                )
        }
        val porId = padron.predios.filter { it.id != null }.associateBy { it.id!! }
        for ((id, suyas) in vigentesPorPredio.toSortedMap()) {
            if (id in cruzados) continue
            val predio = porId[id]
            filas +=
                FilaBrecha(
                    predio = id,
                    codigoPredio = predio?.codigo,
                    categoria = Categoria.SIN_CATASTRO.name,
                    sector = predio?.sectorCatastral,
                    manzana = predio?.manzanaCatastral,
                    lote = predio?.lote,
                    autoavaluoDeclarado = totalesDePredio(suyas).autoavaluo.setScale(2, RoundingMode.HALF_UP),
                    impuestoDeclarado = impuestos.porPredio[id]?.declarado ?: cero,
                    estimado = false,
                    loteGeom = poligono(predio?.loteGeom)
                )
        }
        if (sinDelta.isNotEmpty()) {
            avisos += "Δ arbitrios sin calcular en ${sinDelta.values.sum()} predios: " +
                sinDelta.entries.take(3).joinToString("; ") { "${it.key} (${it.value})" }
        }
        return Medicion(filas, emptyList(), avisos)
    }

    // the catastro item's code as srtm shows it: its código municipal, else its CPU, else sector-manzana-lote
    fun codigoCatastro(item: ValuacionCatastral): String? =
        item.codigoMunicipal?.trim()?.ifEmpty { null }
            ?: item.codigoCpu?.trim()?.ifEmpty { null }
            ?: listOf(item.sector, item.manzana, item.lote).takeIf { p -> p.all { !it.isNullOrBlank() } }?.joinToString("-") { it!!.trim() }

    // brecha_predio.lote_geom is a POLYGON: a Polygon goes as is, a MultiPolygon of one polygon as that polygon, any
    // other shape is left out (the row is written without its lote rather than refused)
    fun poligono(geometria: Map<String, Any?>?): Map<String, Any?>? =
        when (geometria?.get("type")) {
            "Polygon" -> geometria
            "MultiPolygon" -> (geometria["coordinates"] as? List<*>)?.singleOrNull()?.let { mapOf("type" to "Polygon", "coordinates" to it) }
            else -> null
        }

    // GET /brecha/{anio}/panel: the corrida's rows counted (PanelService's way: read, then count in memory). the padrón
    // is every row but the omisos, the catastro every row but those SIN_CATASTRO. Δ arbitrios is null while no row has
    // one
    fun panel(
        corrida: RegistroCorrida,
        filas: List<FilaBrecha>
    ): PanelBrecha {
        fun de(c: Categoria) = filas.count { it.categoria == c.name }
        val porCategoria =
            Categoria.entries.map { c ->
                val suyas = filas.filter { it.categoria == c.name }
                PorCategoria(
                    c.name,
                    suyas.size,
                    suma(suyas.map { it.deltaAutoavaluo }),
                    suma(suyas.map { it.deltaImpuesto }),
                    sumaONull(suyas.map { it.deltaArbitrios })
                )
            }
        val porSector =
            filas
                .groupBy { it.sector?.trim()?.ifEmpty { null } }
                .toSortedMap(nullsLast())
                .map { (sector, suyas) ->
                    PorSector(
                        sector,
                        suyas.size,
                        suma(suyas.map { it.deltaImpuesto }),
                        sumaONull(suyas.map { it.deltaArbitrios }),
                        suyas.count { it.categoria == Categoria.OMISO.name },
                        suyas.count { it.categoria == Categoria.SUBVALUADO.name }
                    )
                }
        return PanelBrecha(
            CorridaDelPanel(corrida.id!!, corrida.terminado, corrida.versionCatastro),
            Kpis(
                prediosCatastro = filas.size - de(Categoria.SIN_CATASTRO),
                prediosPadron = filas.size - de(Categoria.OMISO),
                omisos = de(Categoria.OMISO),
                subvaluados = de(Categoria.SUBVALUADO),
                sobrevaluados = de(Categoria.SOBREVALUADO),
                coinciden = de(Categoria.COINCIDE),
                sinCatastro = de(Categoria.SIN_CATASTRO),
                sinValor = de(Categoria.SIN_VALOR),
                autoavaluoDeclarado = suma(filas.map { it.autoavaluoDeclarado }),
                autoavaluoCatastro = suma(filas.map { it.autoavaluoCatastro }),
                deltaAutoavaluo = suma(filas.map { it.deltaAutoavaluo }),
                deltaImpuesto = suma(filas.map { it.deltaImpuesto }),
                deltaArbitrios = sumaONull(filas.map { it.deltaArbitrios })
            ),
            porCategoria,
            porSector
        )
    }

    // GET /brecha/{anio}/lotes: the rows with a lote as a GeoJSON FeatureCollection (EPSG:4326)
    fun lotes(filas: List<FilaBrecha>): Map<String, Any?> =
        mapOf(
            "type" to "FeatureCollection",
            "features" to
                filas.filter { it.loteGeom != null }.map {
                    mapOf(
                        "type" to "Feature",
                        "id" to it.id,
                        "geometry" to it.loteGeom,
                        "properties" to
                            mapOf("categoria" to it.categoria, "codigo" to (it.codigoPredio ?: it.codigoCatastro), "deltaImpuesto" to it.deltaImpuesto)
                    )
                }
        )

    private fun suma(valores: List<BigDecimal?>): BigDecimal = valores.fold(BigDecimal.ZERO.setScale(2)) { s, v -> if (v == null) s else s + v }

    private fun sumaONull(valores: List<BigDecimal?>): BigDecimal? = if (valores.all { it == null }) null else suma(valores)
}
