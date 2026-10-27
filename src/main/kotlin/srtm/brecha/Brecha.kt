package srtm.brecha

import srtm.arbitrios.Arbitrios
import srtm.arbitrios.ContextoArbitrios
import srtm.arbitrios.InafectacionArbitrio
import srtm.arbitrios.PredioArbitrios
import srtm.impuesto.ImpuestoPredial
import srtm.impuesto.ParametroTributario
import srtm.rentas.Contribuyente
import srtm.rentas.Declaracion
import srtm.rentas.NivelConstruccion
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
// and for the arbitrios the contribuyentes' codes (the titular principal's tie-break) and the inafectaciones.
// obrasDelPadron: declaración id -> the otras instalaciones an external padrón brought (> 0), which no dto reads.
// contribuyentes: id -> the DJs' titulares, whose documento and nombre the confianza compares with the ficha's (#204).
// prediosDeBaja: those with a fecha_baja (#219), out of predios: no candidate of the cruce (#246), only the labels of
// the SIN_CATASTRO row of one that still has a vigente DJ of the year. niveles: declaración id -> its niveles de
// construcción (#247), read for the vigente DJs of the year only
data class Padron(
    val predios: List<Predio>,
    val declaraciones: List<Declaracion>,
    val codigos: Map<String, String?> = emptyMap(),
    val inafectaciones: List<InafectacionArbitrio> = emptyList(),
    val obrasDelPadron: Map<String, BigDecimal> = emptyMap(),
    val contribuyentes: Map<String, Contribuyente> = emptyMap(),
    val prediosDeBaja: List<Predio> = emptyList(),
    val niveles: Map<String, List<NivelConstruccion>> = emptyMap()
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
    val porPredio: Map<String, ImpuestoDelPredio>,
    // condóminos without a % whose share conPorcentaje filled, on the predios the catastro valued
    val sinPorcentaje: Int = 0,
    // declarations whose deducción of 5 % recalcular recomputed over the catastro's value, on the predios it valued
    val deduccionProporcional: Int = 0
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
// titular's valor afecto is condominio's (its % and deducción kept, the Amazonía's 5 % recomputed), the impuesto is ImpuestoPredial.liquidar's and the
// arbitrios Arbitrios.determinar's
object Brecha {
    // D10: COINCIDE while |Δ autoavalúo| <= 5 % of the declared one
    val TOLERANCIA = BigDecimal("0.05")

    private val CIEN = BigDecimal(100)

    // the Amazonía's deducción (Ley 27037): 5 % of the valor condominio. it is recognized in the DJ (a deducción that
    // is 5 % of its declared valor condominio, to the centimo), not by a parameter of the year
    val DEDUCCION_PROPORCIONAL = BigDecimal("0.05")
    private val TOLERANCIA_DEDUCCION = BigDecimal("0.01")

    // what the arbitrios' hypothetical cuotas carry (never written)
    private const val OBSERVACION = "Brecha de recaudación"

    private val DIGITOS = Regex("\\d+")

    // a code compared as the catastro and the padrón may write it: trimmed, upper case, and every run of digits without
    // its leading zeros, so 01-01-0006 and 1-1-6 (or a lote 08 and 8) are one key. blank is no code
    fun llave(codigo: String?): String? =
        codigo
            ?.trim()
            ?.uppercase()
            ?.ifEmpty { null }
            ?.let { DIGITOS.replace(it) { d -> d.value.trimStart('0').ifEmpty { "0" } } }

    private fun sml(
        sector: String?,
        manzana: String?,
        lote: String?
    ): String? {
        val partes = listOf(sector, manzana, lote).map(::llave)
        return if (partes.any { it == null }) null else partes.joinToString("|")
    }

    // D7: each catastro item to the padrón's predio of its código municipal (predio.codigo), else of a predio's
    // codigo_origen (#207: Ica's shared SAT code, split into X-1, X-2, ... each keeping the SAT code as origen), else
    // of its código CPU, else of its sector, manzana and lote (sector_catastral, manzana_catastral, lote). in passes
    // over every item, so an item's fallback key never takes a predio another item names by a stronger one, whatever
    // their order: first every código municipal, then codigo_origen, then the CPU of the items still alone, then
    // their sector-manzana-lote. a predio crosses once. in the first three passes, among several predios of the same
    // key, those with a declaración of the year (preferidos) go first, then by código. sector-manzana-lote is not an
    // identity: it crosses only when one item and one predio left share it; when several items or several predios
    // share it, none of them crosses (the items are OMISO and the predios SIN_CATASTRO) rather than pair them at
    // random. null: an omiso
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
        val tomados = HashSet<String>()
        val cruce = arrayOfNulls<Predio>(items.size)

        fun pasada(
            clave: (ValuacionCatastral) -> String?,
            porClave: Map<String, List<Predio>>
        ) = items.forEachIndexed { i, item ->
            if (cruce[i] != null) return@forEachIndexed
            val predio = clave(item)?.let(porClave::get)?.firstOrNull { it.id !in tomados } ?: return@forEachIndexed
            cruce[i] = predio
            tomados += predio.id!!
        }
        pasada({ llave(it.codigoMunicipal) }, indice { llave(it.codigo) })
        pasada({ llave(it.codigoMunicipal) }, indice { llave(it.codigoOrigen) })
        pasada({ llave(it.codigoCpu) }, indice { llave(it.codigoCpu) })

        val solos = items.indices.filter { cruce[it] == null }.groupBy { sml(items[it].sector, items[it].manzana, items[it].lote) }
        val libres = ordenados.filter { it.id !in tomados }.groupBy { sml(it.sectorCatastral, it.manzanaCatastral, it.lote) }
        for ((clave, suyos) in solos) {
            if (clave == null) continue
            val predio = libres[clave]?.singleOrNull() ?: continue
            val i = suyos.singleOrNull() ?: continue
            cruce[i] = predio
            tomados += predio.id!!
        }
        return items.mapIndexed { i, item -> item to cruce[i] }
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

    // #209: catastro has no rústico aranceles (per hectárea) yet, so any value it gives a rústico predio is the urban
    // engine's, not a real one. its terreno, construcción, obras and autoavalúo are nulled so the row reads as SIN_VALOR:
    // its own motivo if it has one (a ficha without tipo_predio that catastro still valued), else PREDIO_RUSTICO. applies
    // before conObrasDeLaDj and valuados (medir), so an OMISO crossed to a rústico predio gets no estimate either
    fun sinValorSiRustico(
        item: ValuacionCatastral,
        predio: Predio?
    ): ValuacionCatastral {
        if (predio?.tipoPredio != "PREDIO RUSTICO") return item
        return item.copy(autoavaluo = null, terreno = null, construccion = null, obras = null, motivo = item.motivo ?: PREDIO_RUSTICO)
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

    // a secuencia's condóminos with a % to compute with. an imported declaración may have none (the Excel had no
    // condominio, or an autoavalúo of 0: model/import_predios.py): a sole titular holds 100 % (condominio does it);
    // among several, those without one share equally what the others' % leave (never below 0). how many were filled
    fun conPorcentaje(secuencia: List<Declaracion>): Pair<List<Declaracion>, Int> {
        if (secuencia.size <= 1) return secuencia to 0
        val vacios = secuencia.count { it.porcentajeCondominio == null }
        if (vacios == 0) return secuencia to 0
        val conocido = secuencia.mapNotNull { it.porcentajeCondominio }.fold(BigDecimal.ZERO, BigDecimal::add)
        val parte = (CIEN - conocido).max(BigDecimal.ZERO).divide(BigDecimal(vacios), 4, RoundingMode.HALF_UP)
        return secuencia.map { if (it.porcentajeCondominio == null) it.copy(porcentajeCondominio = parte) else it } to vacios
    }

    // a predio's declarations recomputed by the same rule on both sides: condominio (each titular's %, filled by
    // conPorcentaje, and its deducción) over the declared autoavalúo, and over the catastro's split among the secuencias
    // (D12). the stored valor_afecto is never compared with a recomputed one: only the autoavalúo moves the difference.
    // a deducción that is 5 % of the recomputed declared valor condominio (± 0.01) is the Amazonía's: on the catastro's
    // side it is 5 % of the catastro's valor condominio; any other (a pensionista's) stays fixed. in the order they came
    data class Recalculo(
        val declarado: List<Declaracion>,
        val catastro: List<Declaracion>,
        val sinPorcentaje: Int,
        val deduccionProporcional: Int = 0
    )

    // 5 % of a valor condominio, to the centimo (half up)
    private fun cincoPorCiento(valorCondominio: BigDecimal) = valorCondominio.multiply(DEDUCCION_PROPORCIONAL).setScale(2, RoundingMode.HALF_UP)

    // whether a declaración as recomputed on the declared side deducts the Amazonía's 5 %
    private fun esProporcional(d: Declaracion): Boolean {
        val deduccion = d.deduccion?.takeIf { it.signum() > 0 } ?: return false
        val valorCondominio = d.valorCondominio ?: return false
        return (deduccion - cincoPorCiento(valorCondominio)).abs() <= TOLERANCIA_DEDUCCION
    }

    // the catastro's side of a declaración that deducts the 5 %: its deducción over its new valor condominio, and its
    // valor afecto as condominio computes it
    private fun conDeduccionProporcional(d: Declaracion): Declaracion {
        val valorCondominio = d.valorCondominio ?: return d
        val deduccion = cincoPorCiento(valorCondominio)
        return d.copy(deduccion = deduccion, valorAfecto = valorCondominio.subtract(deduccion).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP))
    }

    fun recalcular(
        declaraciones: List<Declaracion>,
        autoavaluo: BigDecimal
    ): Recalculo {
        val secuencias = declaraciones.groupBy(::grupoDe).values.toList()
        val declarado = IdentityHashMap<Declaracion, Declaracion>()
        val catastro = IdentityHashMap<Declaracion, Declaracion>()
        var sinPorcentaje = 0
        var deduccionProporcional = 0
        secuencias.zip(repartir(autoavaluo, pesos(secuencias))).forEach { (secuencia, parte) ->
            val (completa, llenados) = conPorcentaje(secuencia)
            sinPorcentaje += llenados
            val declaradas = condominio(completa)
            val conParte = condominio(completa.map { it.copy(valorAutoavaluo = parte) })
            secuencia.indices.forEach { i ->
                declarado[secuencia[i]] = declaradas[i]
                val proporcional = esProporcional(declaradas[i])
                if (proporcional) deduccionProporcional++
                catastro[secuencia[i]] = if (proporcional) conDeduccionProporcional(conParte[i]) else conParte[i]
            }
        }
        return Recalculo(
            declaraciones.map { declarado.getValue(it) },
            declaraciones.map { catastro.getValue(it) },
            sinPorcentaje,
            deduccionProporcional
        )
    }

    // the catastro side of recalcular
    fun conCatastro(
        declaraciones: List<Declaracion>,
        autoavaluo: BigDecimal
    ): List<Declaracion> = recalcular(declaraciones, autoavaluo).catastro

    // the impuesto of each contribuyente with a vigente declaración of the year, as declared and with the catastro's
    // autoavalúo on the predios it valued (catastro: predio id -> autoavalúo). the declared one is its liquidación
    // (ImpuestoPredial over the stored valor afecto, as /liquidacion); the catastro's adds to that base what the
    // catastro moves on each valued predio: recomputed valor afecto with the catastro's autoavalúo minus recomputed with
    // the declared one (recalcular), so equal values move nothing and a predio not valued is the same on both sides.
    // the difference falls on its predios in proportion to each one's move: signed when they all go the same way, in
    // absolute value when they go both ways (signed weights could then add up to ~0). the declared impuesto falls in
    // proportion to each one's stored valor afecto. each predio sums the parts of its contribuyentes, so Σ per predio =
    // Σ per contribuyente. the year's parameters must be there (medir checks them first)
    fun impuestos(
        anio: Int,
        vigentes: List<Declaracion>,
        catastro: Map<String, BigDecimal>,
        parametros: List<ParametroTributario>
    ): Impuestos {
        val mueve = IdentityHashMap<Declaracion, BigDecimal>()
        var sinPorcentaje = 0
        var deduccionProporcional = 0
        vigentes.groupBy { it.predio }.forEach { (predio, suyas) ->
            val valor = predio?.let(catastro::get) ?: return@forEach
            val r = recalcular(suyas, valor)
            sinPorcentaje += r.sinPorcentaje
            deduccionProporcional += r.deduccionProporcional
            suyas.indices.forEach { i -> mueve[suyas[i]] = afecto(r.catastro[i]) - afecto(r.declarado[i]) }
        }
        val porContribuyente = linkedMapOf<String, ImpuestoDelContribuyente>()
        val declarados = HashMap<String, BigDecimal>()
        val deltas = HashMap<String, BigDecimal>()
        vigentes
            .filter { it.contribuyente != null }
            .groupBy { it.contribuyente!! }
            .toSortedMap()
            .forEach { (contribuyente, suyas) ->
                val antes = totalesDeContribuyente(suyas).valorAfecto
                val despues = (antes + suyas.fold(BigDecimal.ZERO) { s, d -> s + (mueve[d] ?: BigDecimal.ZERO) }).max(BigDecimal.ZERO)
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
                        pesosDelDelta(
                            porPredio.values.map { ds ->
                                ds.fold(BigDecimal.ZERO) { s, d ->
                                    s +
                                        (mueve[d] ?: BigDecimal.ZERO)
                                }
                            }
                        )
                    )
                predios.forEachIndexed { i, p ->
                    declarados.merge(p, declarado[i], BigDecimal::add)
                    deltas.merge(p, delta[i], BigDecimal::add)
                }
            }
        return Impuestos(
            porContribuyente,
            declarados.keys.associateWith { ImpuestoDelPredio(declarados.getValue(it), deltas.getValue(it)) },
            sinPorcentaje,
            deduccionProporcional
        )
    }

    // the weights of a contribuyente's difference: each predio's move, signed when none goes the other way, else in
    // absolute value
    fun pesosDelDelta(movimientos: List<BigDecimal>): List<BigDecimal> =
        if (movimientos.all { it.signum() >= 0 } || movimientos.all { it.signum() <= 0 }) movimientos else movimientos.map { it.abs() }

    private fun afecto(d: Declaracion) = d.valorAfecto ?: BigDecimal.ZERO

    // a predio's declarations with what the catastro says of it for the arbitrios: its uso (the uso_predio of that
    // code, when the catalog has it), its área construida split among the secuencias as the autoavalúo (D12), and its
    // frente. Calculo charges a FRONTIS_ML base with each declaración's own longitud_frente, once per secuencia
    // charged: in a year of one PU per predio only one secuencia is charged each month, so each one carries the whole
    // frente; in a year of one PU per uso every secuencia is, so the frente is split among them (by each one's declared
    // frente, else D12) and the predio pays it once
    fun conRasgosDelCatastro(
        declaraciones: List<Declaracion>,
        item: ValuacionCatastral,
        usos: List<UsoPredio>,
        puPorUso: Boolean = false
    ): List<Declaracion> {
        val uso = llave(item.uso)?.let { codigo -> usos.firstOrNull { llave(it.codigo) == codigo } }
        val secuencias = declaraciones.groupBy(::grupoDe).values.toList()
        val areas = item.areaConstruida?.let { repartir(it, pesos(secuencias)) }
        val frentes =
            item.longitudFrente?.let { frente ->
                if (!puPorUso) return@let secuencias.map { frente }
                val declarados = secuencias.map { s -> s.mapNotNull { it.longitudFrente }.maxOrNull() ?: BigDecimal.ZERO }
                repartir(frente, if (declarados.any { it.signum() > 0 }) declarados else pesos(secuencias))
            }
        val nuevas = IdentityHashMap<Declaracion, Declaracion>()
        secuencias.forEachIndexed { i, secuencia ->
            secuencia.forEach { d ->
                nuevas[d] =
                    d.copy(
                        claseUso = uso?.clase ?: d.claseUso,
                        subClaseUso = uso?.subClase ?: d.subClaseUso,
                        uso = uso?.uso ?: d.uso,
                        areaConstruida = areas?.get(i) ?: d.areaConstruida,
                        longitudFrente = frentes?.get(i) ?: d.longitudFrente
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
        val catastro =
            Arbitrios.determinar(
                c,
                anual.copy(declaraciones = conRasgosDelCatastro(anual.declaraciones, item, c.usos, c.puPorUso)),
                OBSERVACION,
                hoy
            )
        if (catastro.faltan.isNotEmpty()) return DeltaArbitrios.Falta("con catastro: ${catastro.faltan.first()}")
        return DeltaArbitrios.Es(suma(catastro.cuotas.map { it.monto }) - suma(declarado.cuotas.map { it.monto }))
    }

    // #208: the obras a predio's DJs declare: each one's valor_obras (the engine's obras, the padrón's amount included),
    // else, not recalculated since, its padrón's otras instalaciones. once per secuencia, the highest of its titulares
    // (as totalesDePredio counts the autoavalúo). null: none has any
    fun obrasDeLaDj(
        declaraciones: List<Declaracion>,
        obrasDelPadron: Map<String, BigDecimal>
    ): BigDecimal? =
        declaraciones
            .groupBy(::grupoDe)
            .values
            .mapNotNull { s -> s.mapNotNull { it.valorObras ?: it.id?.let(obrasDelPadron::get) }.maxOrNull() }
            .takeIf { it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO, BigDecimal::add)

    // #208: a valued ficha without obras (null or 0) takes the DJ's on the catastro side, in its obras and its autoavalúo:
    // the ficha does not survey them, and the brecha would count what it lacks as a difference
    fun conObrasDeLaDj(
        item: ValuacionCatastral,
        obras: BigDecimal?
    ): ValuacionCatastral {
        val autoavaluo = item.autoavaluo ?: return item
        val monto = obras?.takeIf { it.signum() > 0 } ?: return item
        if (item.obras != null && item.obras.signum() != 0) return item
        return item.copy(autoavaluo = autoavaluo + monto, obras = monto)
    }

    // the omiso's impuesto as if its autoavalúo were a contribuyente's whole base (no contribuyente to add it to)
    fun estimado(
        anio: Int,
        autoavaluo: BigDecimal,
        parametros: List<ParametroTributario>
    ): BigDecimal = ImpuestoPredial.liquidar(anio, autoavaluo, parametros).impuestoAnual!!

    // #247: a predio's niveles, rentas against catastro, paired by piso. catastro's secciones of one piso are summed
    // into one; then each piso number (null included) is one row: AMBIGUO when it is null (a sótano, mezanine or
    // azotea of rentas, a piso of catastro that is no number) or when rentas has it on more than one nivel (two usos of
    // one floor: which part is which cannot be told); else PAR when both sides have it, SOLO_RENTAS or SOLO_CATASTRO
    // when one only. a rentas nivel INACTIVO is not declared. each side's area is the sum of its niveles (null when
    // none has one, or the side lacks the piso). by piso, the null one last
    fun niveles(
        rentas: List<NivelConstruccion>,
        catastro: List<ConstruccionCatastral>
    ): List<NivelBrecha> {
        val declarados = rentas.filter { it.estado != INACTIVO }.groupBy(::pisoDeRentas)
        val construidos = catastro.groupBy { it.piso }
        return (declarados.keys + construidos.keys).sortedWith(nullsLast()).map { piso ->
            val r = declarados[piso]
            val c = construidos[piso]
            val estado =
                when {
                    piso == null || (r?.size ?: 0) > 1 -> EstadoNivel.AMBIGUO
                    r != null && c != null -> EstadoNivel.PAR
                    r != null -> EstadoNivel.SOLO_RENTAS
                    else -> EstadoNivel.SOLO_CATASTRO
                }
            val areaRentas = r?.let { sumaDeAreas(it.map(NivelConstruccion::areaConstruida)) }
            val areaCatastro = c?.let { sumaDeAreas(it.map(ConstruccionCatastral::areaConstruida)) }
            NivelBrecha(piso, areaRentas, areaCatastro, estado.name)
        }
    }

    // a rentas nivel's piso as a number: a PISO's (or one without tipo) numero_piso; a sótano, mezanine or azotea has
    // none (catastro writes them S1, M1, AZ: no number either)
    private fun pisoDeRentas(n: NivelConstruccion): Int? = n.numeroPiso.takeIf { (n.tipoNivel ?: PISO) == PISO }

    // the areas that are there, added; null when none is
    private fun sumaDeAreas(areas: List<BigDecimal?>): BigDecimal? = areas.filterNotNull().takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)

    // #247: a row's niveles counted: the PAR, those whose catastro area is larger than a rentas one above 0, and the PAR's
    // positive differences (a PAR missing either area adds none)
    fun resumenDeNiveles(niveles: List<NivelBrecha>): ResumenDeNiveles {
        val pares = niveles.filter { it.estado == EstadoNivel.PAR.name }
        val mayor = pares.count { (it.areaRentas ?: BigDecimal.ZERO).signum() > 0 && (it.areaCatastro ?: BigDecimal.ZERO) > it.areaRentas }
        val delta =
            pares.fold(BigDecimal.ZERO) { s, n ->
                if (n.areaCatastro == null || n.areaRentas == null) s else s + (n.areaCatastro - n.areaRentas).max(BigDecimal.ZERO)
            }
        return ResumenDeNiveles(pares.size, mayor, delta)
    }

    // #247: an area of a predio's DJs (area_terreno, area_construida): once per secuencia, the highest of its titulares
    // (as pesos and totalesDePredio count them), added. null: none has it
    fun areaDeclarada(
        declaraciones: List<Declaracion>,
        area: (Declaracion) -> BigDecimal?
    ): BigDecimal? =
        declaraciones
            .groupBy(::grupoDe)
            .values
            .mapNotNull { s -> s.mapNotNull(area).maxOrNull() }
            .takeIf { it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO, BigDecimal::add)

    // #247: the niveles a predio declares: each secuencia's once, those of the titular whose active niveles add up the
    // most area (every condómino carries a copy; the first on a tie), the secuencias' together
    fun nivelesDeRentas(
        declaraciones: List<Declaracion>,
        niveles: Map<String, List<NivelConstruccion>>
    ): List<NivelConstruccion> =
        declaraciones.groupBy(::grupoDe).values.flatMap { secuencia ->
            secuencia
                .map { d -> d.id?.let(niveles::get).orEmpty() }
                .maxByOrNull { ns -> ns.filter { it.estado != INACTIVO }.fold(BigDecimal.ZERO) { s, n -> s + (n.areaConstruida ?: BigDecimal.ZERO) } }
                .orEmpty()
        }

    // #247: the row with what catastro and the DJs say of its areas, and its niveles when both sides can be compared
    // (a DJ, and an item that sends construcciones)
    private fun conAreas(
        fila: FilaBrecha,
        item: ValuacionCatastral?,
        declaraciones: List<Declaracion>?,
        delPadron: Map<String, List<NivelConstruccion>>
    ): FilaBrecha {
        val construcciones = item?.construcciones
        val comparados = if (construcciones == null || declaraciones == null) null else niveles(nivelesDeRentas(declaraciones, delPadron), construcciones)
        val resumen = comparados?.let(::resumenDeNiveles)
        return fila.copy(
            areaTerrenoDeclarada = declaraciones?.let { areaDeclarada(it, Declaracion::areaTerreno) },
            areaConstruidaDeclarada = declaraciones?.let { areaDeclarada(it, Declaracion::areaConstruida) },
            areaTerrenoCatastro = item?.areaTerreno,
            areaConstruidaCatastro = item?.areaConstruida,
            niveles = comparados,
            nivelesComparados = resumen?.comparados,
            nivelesCatastroMayor = resumen?.catastroMayor,
            deltaAreaNiveles = resumen?.delta
        )
    }

    // a corrida's rows: one per catastro item (crossed or OMISO) and one per padrón predio the catastro lacks
    // (SIN_CATASTRO). the padrón is the predios with a vigente declaración of the year: a catastro item crossed with a
    // predio without one is an OMISO too (it keeps the predio). #204: an item whose ficha is sin levantar is
    // SIN_LEVANTAR, crossed or not, never valued (whatever autoavalúo it carries) and with no difference; its predio has
    // no SIN_CATASTRO row. every row gets its confianza (Confiabilidad.de), with the ficha's titulares (titulares, by
    // código municipal) against its predio's DJ's (padron.contribuyentes); sinTitulares: why there are none, an aviso.
    // without the year's impuesto parameters, faltan and no row. contexto null (or a year without what Arbitrios
    // needs): no Δ arbitrios, and avisos says why
    fun medir(
        anio: Int,
        items: List<ValuacionCatastral>,
        padron: Padron,
        parametros: List<ParametroTributario>,
        contexto: ContextoArbitrios?,
        hoy: LocalDate,
        sinArbitrios: String? = null,
        titulares: List<TitularCatastral> = emptyList(),
        sinTitulares: String? = null
    ): Medicion {
        val faltan = ImpuestoPredial.liquidar(anio, BigDecimal.ZERO, parametros).faltan
        if (faltan.isNotEmpty()) return Medicion(emptyList(), faltan)

        val delAnio = padron.declaraciones.filter { it.anio == anio }
        val vigentes = delAnio.filter(::declaracionVigente)
        val vigentesPorPredio = vigentes.filter { it.predio != null }.groupBy { it.predio!! }
        val todasPorPredio = delAnio.filter { it.predio != null }.groupBy { it.predio!! }
        // the catastro side of each crossed pair, before anything is computed with it. sumadas: what conObrasDeLaDj added.
        // rusticos: #209's pairs sinValorSiRustico nulled
        val sumadas = IdentityHashMap<ValuacionCatastral, BigDecimal>()
        var rusticos = 0
        val pares =
            cruzar(items, padron.predios, vigentesPorPredio.keys)
                .map { (item, predio) ->
                    if (sinLevantar(item)) return@map item to predio
                    if (predio?.tipoPredio == "PREDIO RUSTICO") rusticos++
                    val sinRustico = sinValorSiRustico(item, predio)
                    val obras = predio?.id?.let(vigentesPorPredio::get)?.let { obrasDeLaDj(it, padron.obrasDelPadron) }
                    val conObras = conObrasDeLaDj(sinRustico, obras)
                    if (conObras !== sinRustico) sumadas[conObras] = obras
                    conObras to predio
                }
        val valuados =
            pares
                .mapNotNull { (item, predio) ->
                    if (sinLevantar(item)) return@mapNotNull null
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
        if (arbitrios != null && Arbitrios.conTope(arbitrios)) avisos += "Δ arbitrios sin tope: el año tiene TOPE_ARBITRIO"
        if (impuestos.sinPorcentaje > 0) {
            avisos += "${impuestos.sinPorcentaje} declaraciones de condóminos sin % de condominio: se repartió en partes iguales lo que dejan los demás"
        }
        if (impuestos.deduccionProporcional > 0) {
            avisos += "${impuestos.deduccionProporcional} declaraciones con la deducción del 5 % (Amazonía, Ley 27037): se recalculó sobre el valor catastral"
        }
        if (sumadas.isNotEmpty()) {
            avisos += "${sumadas.size} predios sin obras en la ficha: se sumaron S/ ${suma(sumadas.values.toList()).toPlainString()} de obras de sus DJ"
        }
        if (rusticos > 0) {
            avisos += "$rusticos predios rústicos sin valor: el catastro aún no valúa terrenos rústicos ($PREDIO_RUSTICO)"
        }
        if (sinTitulares != null) avisos += sinTitulares
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

        val fichas = titulares.mapNotNull { t -> llave(t.codigoMunicipal)?.let { it to t } }.groupBy({ it.first }, { it.second })

        // the row's confianza: the item's ficha titulares against the contribuyentes of its predio's vigente DJs
        fun confianza(
            item: ValuacionCatastral?,
            predio: Predio?
        ): ConfianzaDeFila {
            val suyos = item?.let { llave(it.codigoMunicipal) }?.let(fichas::get).orEmpty()
            val djs =
                predio
                    ?.id
                    ?.let(vigentesPorPredio::get)
                    .orEmpty()
                    .mapNotNull { it.contribuyente }
                    .distinct()
                    .mapNotNull(padron.contribuyentes::get)
            return Confiabilidad.de(item, predio, suyos, djs)
        }

        val filas = mutableListOf<FilaBrecha>()
        val cruzados = HashSet<String>()
        for ((item, predio) in pares) {
            val conf = confianza(item, predio)
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
                    motivo = item.motivo,
                    confianza = conf.confianza.name,
                    confianzaMotivo = conf.motivo
                )
            val suyas = predio?.id?.let(vigentesPorPredio::get)
            if (sinLevantar(item)) {
                // no value to compare: what the padrón declares of its predio, if any, and nothing else
                val delPadron = predio?.id?.takeIf { suyas != null }
                if (delPadron != null) cruzados += delPadron
                filas +=
                    conAreas(base, null, suyas, padron.niveles).copy(
                        categoria = Categoria.SIN_LEVANTAR.name,
                        autoavaluoCatastro = null,
                        autoavaluoDeclarado = suyas?.let { totalesDePredio(it).autoavaluo.setScale(2, RoundingMode.HALF_UP) },
                        impuestoDeclarado = delPadron?.let { impuestos.porPredio[it]?.declarado ?: cero },
                        estimado = false,
                        loteGeom = poligono(predio?.loteGeom) ?: poligono(item.loteGeom)
                    )
                continue
            }
            if (predio == null || suyas == null) {
                val impuesto = item.autoavaluo?.let { estimado(anio, it, parametros) }
                filas +=
                    conAreas(base, item, null, padron.niveles).copy(
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
                conAreas(base, item, suyas, padron.niveles).copy(
                    categoria = categoria(declarado, valor).name,
                    autoavaluoDeclarado = declarado,
                    deltaAutoavaluo = valor?.subtract(declarado),
                    impuestoDeclarado = impuesto.declarado,
                    impuestoCatastro = valor?.let { impuesto.declarado + impuesto.delta },
                    deltaImpuesto = valor?.let { impuesto.delta },
                    deltaArbitrios = valor?.let { arbitriosDe(predio, item) },
                    obrasDj = sumadas[item],
                    estimado = false,
                    loteGeom = poligono(predio.loteGeom) ?: poligono(item.loteGeom)
                )
        }
        val porId = (padron.predios + padron.prediosDeBaja).filter { it.id != null }.associateBy { it.id!! }
        for ((id, suyas) in vigentesPorPredio.toSortedMap()) {
            if (id in cruzados) continue
            val predio = porId[id]
            val conf = confianza(null, predio)
            filas +=
                conAreas(FilaBrecha(), null, suyas, padron.niveles).copy(
                    predio = id,
                    codigoPredio = predio?.codigo,
                    categoria = Categoria.SIN_CATASTRO.name,
                    sector = predio?.sectorCatastral,
                    manzana = predio?.manzanaCatastral,
                    lote = predio?.lote,
                    autoavaluoDeclarado = totalesDePredio(suyas).autoavaluo.setScale(2, RoundingMode.HALF_UP),
                    impuestoDeclarado = impuestos.porPredio[id]?.declarado ?: cero,
                    estimado = false,
                    loteGeom = poligono(predio?.loteGeom),
                    confianza = conf.confianza.name,
                    confianzaMotivo = conf.motivo
                )
        }
        if (sinDelta.isNotEmpty()) {
            avisos += "Δ arbitrios sin calcular en ${sinDelta.values.sum()} predios: " +
                sinDelta.entries.take(3).joinToString("; ") { "${it.key} (${it.value})" }
        }
        return Medicion(filas, emptyList(), avisos)
    }

    private fun sinLevantar(item: ValuacionCatastral) = item.estadoLevantamiento == SIN_LEVANTAR

    // a nivel_construccion's estado that is not declared any more, and the tipo_nivel of a floor with a number
    private const val INACTIVO = "INACTIVO"
    private const val PISO = "PISO"

    // catastro answered no item at all while the padrón has predios of the year: an empty or misconfigured catastro,
    // not a district of omisos. measuring it would call every predio SIN_CATASTRO: the corrida fails with why instead
    fun sinValuaciones(
        anio: Int,
        items: List<ValuacionCatastral>,
        padron: Padron
    ): String? {
        if (items.isNotEmpty()) return null
        val predios =
            padron.declaraciones
                .filter { it.anio == anio && it.predio != null && declaracionVigente(it) }
                .mapNotNull { it.predio }
                .toSet()
        if (predios.isEmpty()) return null
        return "El catastro no devolvió valuaciones de $anio y el padrón tiene ${predios.size} predios con DJ vigente: " +
            "revise el levantamiento del año en catastro-backend"
    }

    // the rows in the order /predios pages them: by the padrón's code, then the catastro's (nulls last), then as they
    // came; orden keeps that position, a sort core can do on one field and that never ties
    fun ordenadas(filas: List<FilaBrecha>): List<FilaBrecha> =
        filas
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<FilaBrecha>, String?>(nullsLast()) {
                    it.value.codigoPredio
                }.thenBy(nullsLast()) { it.value.codigoCatastro }.thenBy { it.index }
            ).mapIndexed { i, f -> f.value.copy(orden = i) }

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

    // the rows the autoavalúo KPIs compare: those valued on both sides and the omisos (catastro's value, nothing
    // declared). SIN_CATASTRO and SIN_VALOR have one side only and stay out, so declarado + Δ = catastro on the panel
    val COMPARADAS = setOf(Categoria.SUBVALUADO.name, Categoria.SOBREVALUADO.name, Categoria.COINCIDE.name, Categoria.OMISO.name)

    // GET /brecha/{anio}/panel: the corrida's rows counted (PanelService's way: read, then count in memory). the padrón
    // is every row but the omisos and the SIN_LEVANTAR ones without a declared autoavalúo (an empty ficha with no predio
    // of the padrón), the catastro every row but those SIN_CATASTRO. autoavalúo declarado, catastro and Δ are
    // over COMPARADAS (they reconcile); the *Total ones over every row, for reference. Δ arbitrios is null while no row
    // has one. porConfianza has every Confianza, over the rows that have one: an older corrida's are all zero
    fun panel(
        corrida: RegistroCorrida,
        filas: List<FilaBrecha>
    ): PanelBrecha {
        fun de(c: Categoria) = filas.count { it.categoria == c.name }
        val comparadas = filas.filter { it.categoria in COMPARADAS }
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
        val porConfianza =
            Confianza.entries.associate { c ->
                val suyas = filas.filter { it.confianza == c.name }
                c.name to PorConfianza(suyas.size, suma(suyas.map { it.deltaImpuesto }), suma(suyas.map { it.deltaAutoavaluo }))
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
                prediosPadron =
                    filas.count {
                        it.categoria != Categoria.OMISO.name && (it.categoria != Categoria.SIN_LEVANTAR.name || it.autoavaluoDeclarado != null)
                    },
                omisos = de(Categoria.OMISO),
                subvaluados = de(Categoria.SUBVALUADO),
                sobrevaluados = de(Categoria.SOBREVALUADO),
                coinciden = de(Categoria.COINCIDE),
                sinCatastro = de(Categoria.SIN_CATASTRO),
                sinValor = de(Categoria.SIN_VALOR),
                sinLevantar = de(Categoria.SIN_LEVANTAR),
                autoavaluoDeclarado = suma(comparadas.map { it.autoavaluoDeclarado }),
                autoavaluoCatastro = suma(comparadas.map { it.autoavaluoCatastro }),
                deltaAutoavaluo = suma(comparadas.map { it.deltaAutoavaluo }),
                deltaImpuesto = suma(filas.map { it.deltaImpuesto }),
                deltaArbitrios = sumaONull(filas.map { it.deltaArbitrios }),
                deltaImpuestoOmisosEstimado = suma(filas.filter { it.categoria == Categoria.OMISO.name }.map { it.deltaImpuesto }),
                autoavaluoDeclaradoTotal = suma(filas.map { it.autoavaluoDeclarado }),
                autoavaluoCatastroTotal = suma(filas.map { it.autoavaluoCatastro })
            ),
            porCategoria,
            porConfianza,
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
