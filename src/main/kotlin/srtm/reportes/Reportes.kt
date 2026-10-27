package srtm.reportes

import com.fasterxml.jackson.annotation.JsonIgnore
import srtm.brecha.Brecha
import srtm.brecha.Categoria
import srtm.brecha.Confianza
import srtm.brecha.FilaBrecha
import srtm.brecha.Kpis
import srtm.brecha.RegistroCorrida
import srtm.rentas.Contribuyente
import srtm.rentas.Declaracion
import srtm.rentas.grupoDe
import srtm.rentas.nombreCompleto
import java.math.BigDecimal
import java.text.Normalizer
import srtm.rentas.vigente as declaracionVigente

// the reportes de brechas (#248): rentas' reading of a TERMINADA corrida. each report is a list of its rows (a
// predio's, or a contribuyente's in `contribuyentes`); the filters, the order and every aggregate are computed here, in
// memory, from the corrida's brecha_predio rows (Brecha.panel's way). pure: ReportesService reads and caches
enum class Reporte(
    val clave: String,
    val titulo: String,
    val descripcion: String,
    // the order a request without `orden` gets
    val ordenPorOmision: Pair<Orden, Direccion>
) {
    SUBVALUADOS(
        "subvaluados",
        "Predios subvaluados",
        "El autoavalúo del catastro supera en más de 5 % al declarado.",
        Orden.DELTA_IMPUESTO to Direccion.DESC
    ),
    SOBREVALUADOS(
        "sobrevaluados",
        "Predios sobrevaluados",
        "El autoavalúo del catastro es menor en más de 5 % que el declarado.",
        Orden.DELTA_IMPUESTO to Direccion.ASC
    ),
    OMISOS("omisos", "Omisos", "Predios del catastro sin DJ vigente del año en el padrón.", Orden.DELTA_IMPUESTO to Direccion.DESC),
    SIN_CATASTRO("sin-catastro", "Sin catastro", "Predios del padrón con DJ vigente que el catastro no tiene.", Orden.CODIGO to Direccion.ASC),
    SIN_LEVANTAR("sin-levantar", "Sin levantar", "Fichas del catastro sin levantamiento: no se comparan.", Orden.CODIGO to Direccion.ASC),
    SIN_VALOR("sin-valor", "Sin valor", "Predios cruzados que el catastro no pudo valorizar.", Orden.CODIGO to Direccion.ASC),
    TERRENO(
        "terreno",
        "Diferencias de área de terreno",
        "El área de terreno del catastro difiere de la declarada.",
        Orden.AREA_TERRENO to Direccion.DESC
    ),
    CONSTRUCCION(
        "construccion",
        "Diferencias de área construida",
        "El área construida del catastro difiere de la declarada, o un piso del catastro es mayor que el declarado.",
        Orden.AREA_CONSTRUIDA to Direccion.DESC
    ),
    CONTRIBUYENTES(
        "contribuyentes",
        "Contribuyentes con mayor brecha",
        "La suma del Δ de los predios comparados de cada contribuyente (un predio en condominio, por su %).",
        Orden.DELTA_IMPUESTO to Direccion.DESC
    ),
    MAYORES_5_UIT(
        "mayores-5-uit",
        "Δ autoavalúo mayor a 5 UIT",
        "El valor absoluto del Δ autoavalúo supera 5 UIT del 1 de enero del año.",
        Orden.DELTA_AUTOAVALUO to Direccion.DESC
    );

    companion object {
        fun de(clave: String): Reporte? = entries.firstOrNull { it.clave == clave }
    }
}

// what a list sorts by: its name in the query (`orden`). areaTerreno and areaConstruida sort by the difference,
// catastro minus declarada (m2); codigo by the padrón's code, then catastro's
enum class Orden(
    val clave: String
) {
    DELTA_IMPUESTO("deltaImpuesto"),
    DELTA_AUTOAVALUO("deltaAutoavaluo"),
    AREA_TERRENO("areaTerreno"),
    AREA_CONSTRUIDA("areaConstruida"),
    CODIGO("codigo");

    companion object {
        fun de(clave: String): Orden? = entries.firstOrNull { it.clave == clave }
    }
}

enum class Direccion {
    ASC,
    DESC;

    companion object {
        fun de(texto: String): Direccion? = entries.firstOrNull { it.name.equals(texto, ignoreCase = true) }
    }
}

// what a list is asked with besides the corrida: sector and manzana compared as the cruce compares codes (Brecha.llave:
// 01 is 1, case ignored), uso a prefix of its code, confianza exact, q a part of a code or of a contribuyente's code or
// name (case and accents ignored). null: no filter
data class Filtros(
    val sector: String? = null,
    val manzana: String? = null,
    val uso: String? = null,
    val confianza: String? = null,
    val q: String? = null
) {
    // the ones given, by their query names: what the export prints
    fun dados(): List<Pair<String, String>> =
        listOf("sector" to sector, "manzana" to manzana, "uso" to uso, "confianza" to confianza, "q" to q).mapNotNull { (k, v) -> v?.let { k to it } }
}

// one of a predio's titulares in the padrón: a contribuyente of its vigente DJs of the year, its código and nombre, the
// DJ that names it and its share (peso, % of condominio: a sole titular 100)
data class Titular(
    val contribuyente: String,
    val codigo: String?,
    val nombre: String?,
    val declaracion: String?,
    val peso: BigDecimal
)

// a row of GET /reportes/{clave}: a brecha_predio of the corrida with its principal contribuyente (the titular of the
// largest share; null for an omiso), the uso's description and catastro's decision on it (null without one, or when
// catastro could not be read)
data class FilaReportePredio(
    val id: String?,
    val predio: String?,
    val codigoPredio: String?,
    val codigoCatastro: String?,
    val contribuyente: String?,
    val codigoContribuyente: String?,
    val nombreContribuyente: String?,
    val declaracion: String?,
    val sector: String?,
    val manzana: String?,
    val lote: String?,
    val uso: String?,
    val usoDescripcion: String?,
    val categoria: String?,
    val confianza: String?,
    val autoavaluoDeclarado: BigDecimal?,
    val autoavaluoCatastro: BigDecimal?,
    val deltaAutoavaluo: BigDecimal?,
    val impuestoDeclarado: BigDecimal?,
    val impuestoCatastro: BigDecimal?,
    val deltaImpuesto: BigDecimal?,
    val deltaArbitrios: BigDecimal?,
    val estimado: Boolean,
    val areaTerrenoDeclarada: BigDecimal?,
    val areaTerrenoCatastro: BigDecimal?,
    val areaConstruidaDeclarada: BigDecimal?,
    val areaConstruidaCatastro: BigDecimal?,
    val nivelesComparados: Int?,
    val nivelesCatastroMayor: Int?,
    val deltaAreaNiveles: BigDecimal?,
    val decision: String?,
    // every titular, the principal first: q searches them all, contribuyentes splits the Δ among them
    @get:JsonIgnore val titulares: List<Titular> = emptyList(),
    // its position in the corrida (Brecha.ordenadas): the tie-break of every order
    @get:JsonIgnore val orden: Int = 0
) {
    val deltaAreaTerreno: BigDecimal? @JsonIgnore get() = diferencia(areaTerrenoDeclarada, areaTerrenoCatastro)
    val deltaAreaConstruida: BigDecimal? @JsonIgnore get() = diferencia(areaConstruidaDeclarada, areaConstruidaCatastro)
}

// a row of GET /reportes/contribuyentes: its predios compared (with a Δ) and the sum of their Δ, its share of each
data class FilaReporteContribuyente(
    val contribuyente: String?,
    val codigoContribuyente: String?,
    val nombreContribuyente: String?,
    val predios: Int,
    val deltaAutoavaluo: BigDecimal?,
    val deltaImpuesto: BigDecimal?
)

// a page of a report
data class PaginaReporte<T>(
    val page: Int,
    val size: Int,
    val total: Int,
    val items: List<T>
)

// GET /reportes: a report with how many rows it has (contribuyentes in `contribuyentes`) and their Δ, null when none
// has one
data class EntradaCatalogo(
    val clave: String,
    val titulo: String,
    val descripcion: String,
    val predios: Int,
    val deltaImpuesto: BigDecimal?,
    val deltaAutoavaluo: BigDecimal?
)

data class CorridaDelResumen(
    val id: String,
    val anio: Int?,
    val estado: String?,
    val terminado: String?,
    val versionCatastro: String?
)

data class PorCategoriaReporte(
    val categoria: String,
    val predios: Int,
    val deltaAutoavaluo: BigDecimal,
    val deltaImpuesto: BigDecimal
)

data class PorConfianzaReporte(
    val confianza: String,
    val predios: Int,
    val deltaAutoavaluo: BigDecimal,
    val deltaImpuesto: BigDecimal
)

data class PorSectorReporte(
    val sector: String?,
    val predios: Int,
    val deltaAutoavaluo: BigDecimal,
    val deltaImpuesto: BigDecimal
)

data class PorUsoReporte(
    val uso: String?,
    val descripcion: String?,
    val predios: Int,
    val deltaAutoavaluo: BigDecimal,
    val deltaImpuesto: BigDecimal
)

data class PorManzanaReporte(
    val sector: String?,
    val manzana: String?,
    val predios: Int,
    val deltaAutoavaluo: BigDecimal,
    val deltaImpuesto: BigDecimal
)

// desde included, hasta excluded (S/ of Δ autoavalúo); null an open end
data class TramoHistograma(
    val desde: BigDecimal?,
    val hasta: BigDecimal?,
    val predios: Int
)

// the areas of the corrida: rows whose terreno differs (both sides known) and those where catastro's is larger, the
// niveles compared (PAR) and the predios with one larger in catastro; the m2 catastro finds above the DJ (terreno: the
// positive differences; niveles: their deltaAreaNiveles), null when no row could be compared
data class AreasReporte(
    val terrenoConDiferencia: Int,
    val terrenoCatastralMayor: Int,
    val nivelesComparados: Int,
    val prediosConNivelMayor: Int,
    val deltaM2Terreno: BigDecimal?,
    val deltaM2Niveles: BigDecimal?
)

// GET /reportes/resumen. decisiones: the rows of each estado of catastro's decision_brecha, null when catastro could
// not be read (avisos says why)
data class ResumenReportes(
    val corrida: CorridaDelResumen,
    val kpis: Kpis,
    val porCategoria: List<PorCategoriaReporte>,
    val porConfianza: List<PorConfianzaReporte>,
    val porSector: List<PorSectorReporte>,
    val porUso: List<PorUsoReporte>,
    val porManzana: List<PorManzanaReporte>,
    val histogramaDeltaAutoavaluo: List<TramoHistograma>,
    val areas: AreasReporte,
    val decisiones: Map<String, Int>?,
    val avisos: List<String>
)

// catastro's decision_brecha.estado (catastro-backend's estado_decision), in its order
val ESTADOS_DECISION = listOf("PENDIENTE", "A_INSPECCIONAR", "INSPECCIONADO", "A_FISCALIZAR", "DESCARTADO")

private fun diferencia(
    declarada: BigDecimal?,
    catastro: BigDecimal?
): BigDecimal? = if (declarada == null || catastro == null) null else catastro - declarada

object Reportes {
    // the histogram's edges (S/): eight tramos, the first and the last open
    val TRAMOS = listOf("-100000", "-50000", "-10000", "0", "10000", "50000", "100000").map(::BigDecimal)

    // porManzana's length
    const val TOP_MANZANAS = 20

    private val CIEN = BigDecimal(100)

    // each predio's titulares from its vigente DJs of the year: per secuencia de uso, each contribuyente's % of
    // condominio (Brecha.conPorcentaje fills the ones left empty; a sole titular is 100), added over the secuencias.
    // the largest share first, then by código. the DJ named is the one of the largest share of that contribuyente
    fun titulares(
        declaraciones: List<Declaracion>,
        contribuyentes: Map<String, Contribuyente>
    ): Map<String, List<Titular>> =
        declaraciones
            .filter { it.predio != null && it.contribuyente != null && declaracionVigente(it) }
            .groupBy { it.predio!! }
            .mapValues { (_, suyas) ->
                val conPeso =
                    suyas.groupBy(::grupoDe).values.flatMap { secuencia ->
                        val (completa, _) = Brecha.conPorcentaje(secuencia)
                        completa.map { d -> d to (if (completa.size == 1) d.porcentajeCondominio ?: CIEN else d.porcentajeCondominio ?: BigDecimal.ZERO) }
                    }
                conPeso
                    .groupBy { it.first.contribuyente!! }
                    .map { (id, deEl) ->
                        val c = contribuyentes[id]
                        Titular(
                            contribuyente = id,
                            codigo = c?.codigo,
                            nombre = c?.let(::nombreCompleto),
                            declaracion = deEl.maxByOrNull { it.second }?.first?.id,
                            peso = deEl.fold(BigDecimal.ZERO) { s, p -> s + p.second }
                        )
                    }.sortedWith(compareByDescending<Titular> { it.peso }.thenBy(nullsLast()) { it.codigo })
            }

    // the corrida's rows as the reports show them. titulares by predio (null: the caller may not read the padrón, no
    // contribuyente), usos and decisiones by Brecha.llave of the code (a row's decision by its código of catastro, else
    // its padrón's: catastro's codigo_municipal is the rentas code). decisiones null: catastro could not be read
    fun filas(
        filas: List<FilaBrecha>,
        titulares: Map<String, List<Titular>>?,
        usos: Map<String, String>,
        decisiones: Map<String, String>?
    ): List<FilaReportePredio> =
        filas.map { f ->
            val suyos = f.predio?.let { titulares?.get(it) }.orEmpty()
            val principal = suyos.firstOrNull()
            val decision =
                decisiones?.let { d -> (Brecha.llave(f.codigoCatastro)?.let(d::get)) ?: Brecha.llave(f.codigoPredio)?.let(d::get) }
            FilaReportePredio(
                id = f.id,
                predio = f.predio,
                codigoPredio = f.codigoPredio,
                codigoCatastro = f.codigoCatastro,
                contribuyente = principal?.contribuyente,
                codigoContribuyente = principal?.codigo,
                nombreContribuyente = principal?.nombre,
                declaracion = principal?.declaracion,
                sector = f.sector,
                manzana = f.manzana,
                lote = f.lote,
                uso = f.uso,
                usoDescripcion = Brecha.llave(f.uso)?.let(usos::get),
                categoria = f.categoria,
                confianza = f.confianza,
                autoavaluoDeclarado = f.autoavaluoDeclarado,
                autoavaluoCatastro = f.autoavaluoCatastro,
                deltaAutoavaluo = f.deltaAutoavaluo,
                impuestoDeclarado = f.impuestoDeclarado,
                impuestoCatastro = f.impuestoCatastro,
                deltaImpuesto = f.deltaImpuesto,
                deltaArbitrios = f.deltaArbitrios,
                estimado = f.estimado == true,
                areaTerrenoDeclarada = f.areaTerrenoDeclarada,
                areaTerrenoCatastro = f.areaTerrenoCatastro,
                areaConstruidaDeclarada = f.areaConstruidaDeclarada,
                areaConstruidaCatastro = f.areaConstruidaCatastro,
                nivelesComparados = f.nivelesComparados,
                nivelesCatastroMayor = f.nivelesCatastroMayor,
                deltaAreaNiveles = f.deltaAreaNiveles,
                decision = decision,
                titulares = suyos,
                orden = f.orden ?: 0
            )
        }

    // the rows a report of predios takes (not CONTRIBUYENTES: contribuyentes groups them). umbral: 5 UIT, null when the
    // UIT of the year is missing (then MAYORES_5_UIT takes none)
    fun del(
        reporte: Reporte,
        filas: List<FilaReportePredio>,
        umbral: BigDecimal?
    ): List<FilaReportePredio> =
        filas.filter { f ->
            when (reporte) {
                Reporte.SUBVALUADOS -> f.categoria == Categoria.SUBVALUADO.name
                Reporte.SOBREVALUADOS -> f.categoria == Categoria.SOBREVALUADO.name
                Reporte.OMISOS -> f.categoria == Categoria.OMISO.name
                Reporte.SIN_CATASTRO -> f.categoria == Categoria.SIN_CATASTRO.name
                Reporte.SIN_LEVANTAR -> f.categoria == Categoria.SIN_LEVANTAR.name
                Reporte.SIN_VALOR -> f.categoria == Categoria.SIN_VALOR.name
                Reporte.TERRENO -> f.deltaAreaTerreno?.let { it.signum() != 0 } == true
                Reporte.CONSTRUCCION -> f.deltaAreaConstruida?.let { it.signum() != 0 } == true || (f.nivelesCatastroMayor ?: 0) > 0
                Reporte.MAYORES_5_UIT -> umbral != null && f.deltaAutoavaluo?.let { it.abs() > umbral } == true
                Reporte.CONTRIBUYENTES -> conDelta(f)
            }
        }

    // a row compared: with a Δ
    private fun conDelta(f: FilaReportePredio) = f.deltaImpuesto != null || f.deltaAutoavaluo != null

    fun filtrar(
        filas: List<FilaReportePredio>,
        filtros: Filtros
    ): List<FilaReportePredio> {
        val sector = Brecha.llave(filtros.sector)
        val manzana = Brecha.llave(filtros.manzana)
        val uso = filtros.uso?.trim()?.ifEmpty { null }
        val q = filtros.q?.let(::plano)?.ifEmpty { null }
        return filas.filter { f ->
            (sector == null || Brecha.llave(f.sector) == sector) &&
                (manzana == null || Brecha.llave(f.manzana) == manzana) &&
                (uso == null || f.uso?.trim()?.startsWith(uso) == true) &&
                (filtros.confianza == null || f.confianza == filtros.confianza) &&
                (q == null || textos(f).any { plano(it).contains(q) })
        }
    }

    private fun textos(f: FilaReportePredio): List<String> =
        listOfNotNull(f.codigoPredio, f.codigoCatastro) + f.titulares.flatMap { listOfNotNull(it.codigo, it.nombre) }

    // lower case, without accents nor surrounding blanks: q's comparison
    private fun plano(texto: String): String =
        Normalizer
            .normalize(texto.trim(), Normalizer.Form.NFD)
            .replace(DIACRITICOS, "")
            .lowercase()

    private val DIACRITICOS = Regex("\\p{M}+")

    fun ordenar(
        filas: List<FilaReportePredio>,
        orden: Orden,
        dir: Direccion
    ): List<FilaReportePredio> {
        val comparador: Comparator<FilaReportePredio> =
            when (orden) {
                Orden.DELTA_IMPUESTO -> porValor(dir) { it.deltaImpuesto }
                Orden.DELTA_AUTOAVALUO -> porValor(dir) { it.deltaAutoavaluo }
                Orden.AREA_TERRENO -> porValor(dir) { it.deltaAreaTerreno }
                Orden.AREA_CONSTRUIDA -> porValor(dir) { it.deltaAreaConstruida }
                Orden.CODIGO -> porValor<FilaReportePredio, String>(dir) { it.codigoPredio }.then(porValor(dir) { it.codigoCatastro })
            }
        return filas.sortedWith(comparador.thenBy { it.orden })
    }

    // by a value in dir, nulls last either way
    private fun <T, V : Comparable<V>> porValor(
        dir: Direccion,
        valor: (T) -> V?
    ): Comparator<T> {
        val natural: Comparator<V> = if (dir == Direccion.ASC) naturalOrder() else reverseOrder()
        return Comparator.comparing(valor, Comparator.nullsLast(natural))
    }

    // each contribuyente of the compared rows (with a Δ) and the sum of its share of each Δ: a predio's Δ split by its
    // titulares' pesos to the cent (Brecha.repartir: the parts add up to it). an omiso, without titular, counts for none
    fun contribuyentes(filas: List<FilaReportePredio>): List<FilaReporteContribuyente> {
        data class Suma(
            val titular: Titular,
            var predios: Int = 0,
            var deltaAutoavaluo: BigDecimal? = null,
            var deltaImpuesto: BigDecimal? = null
        )
        val sumas = linkedMapOf<String, Suma>()
        for (f in filas) {
            if (!conDelta(f) || f.titulares.isEmpty()) continue
            val pesos = f.titulares.map { it.peso }
            val autoavaluos = f.deltaAutoavaluo?.let { Brecha.repartir(it, pesos) }
            val impuestos = f.deltaImpuesto?.let { Brecha.repartir(it, pesos) }
            f.titulares.forEachIndexed { i, t ->
                val s = sumas.getOrPut(t.contribuyente) { Suma(t) }
                s.predios++
                autoavaluos?.let { s.deltaAutoavaluo = (s.deltaAutoavaluo ?: BigDecimal.ZERO) + it[i] }
                impuestos?.let { s.deltaImpuesto = (s.deltaImpuesto ?: BigDecimal.ZERO) + it[i] }
            }
        }
        return sumas.values.map {
            FilaReporteContribuyente(it.titular.contribuyente, it.titular.codigo, it.titular.nombre, it.predios, it.deltaAutoavaluo, it.deltaImpuesto)
        }
    }

    fun buscarContribuyentes(
        filas: List<FilaReporteContribuyente>,
        q: String?
    ): List<FilaReporteContribuyente> {
        val buscado = q?.let(::plano)?.ifEmpty { null } ?: return filas
        return filas.filter { f -> listOfNotNull(f.codigoContribuyente, f.nombreContribuyente).any { plano(it).contains(buscado) } }
    }

    // by a sum or by código (areas are no contribuyente's: the controller refuses them), ties by código
    fun ordenarContribuyentes(
        filas: List<FilaReporteContribuyente>,
        orden: Orden,
        dir: Direccion
    ): List<FilaReporteContribuyente> {
        val comparador: Comparator<FilaReporteContribuyente> =
            when (orden) {
                Orden.DELTA_AUTOAVALUO -> porValor(dir) { it.deltaAutoavaluo }
                Orden.CODIGO -> porValor(dir) { it.codigoContribuyente }
                else -> porValor(dir) { it.deltaImpuesto }
            }
        return filas.sortedWith(comparador.then(porValor(Direccion.ASC) { it.codigoContribuyente }))
    }

    fun histograma(filas: List<FilaReportePredio>): List<TramoHistograma> {
        val bordes = listOf<BigDecimal?>(null) + TRAMOS + listOf(null)
        val deltas = filas.mapNotNull { it.deltaAutoavaluo }
        return bordes.zipWithNext().map { (desde, hasta) ->
            TramoHistograma(desde, hasta, deltas.count { (desde == null || it >= desde) && (hasta == null || it < hasta) })
        }
    }

    fun areas(filas: List<FilaReportePredio>): AreasReporte {
        val terrenos = filas.mapNotNull { it.deltaAreaTerreno }
        val niveles = filas.mapNotNull { it.deltaAreaNiveles }
        return AreasReporte(
            terrenoConDiferencia = terrenos.count { it.signum() != 0 },
            terrenoCatastralMayor = terrenos.count { it.signum() > 0 },
            nivelesComparados = filas.sumOf { it.nivelesComparados ?: 0 },
            prediosConNivelMayor = filas.count { (it.nivelesCatastroMayor ?: 0) > 0 },
            deltaM2Terreno = terrenos.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { s, d -> s + d.max(BigDecimal.ZERO) },
            deltaM2Niveles = niveles.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)
        )
    }

    // the dashboard: the panel's KPIs (Brecha.panel) and the rows counted by categoría (every one, in its order),
    // confianza (every one), sector and uso (by code, null last), the top manzanas by Δ impuesto; the histogram, the
    // areas and catastro's decisions (each estado's rows; null without them)
    fun resumen(
        corrida: RegistroCorrida,
        brecha: List<FilaBrecha>,
        filas: List<FilaReportePredio>,
        conDecisiones: Boolean,
        avisos: List<String>
    ): ResumenReportes {
        fun clave(texto: String?) = texto?.trim()?.ifEmpty { null }
        return ResumenReportes(
            corrida = CorridaDelResumen(corrida.id!!, corrida.anio, corrida.estado, corrida.terminado, corrida.versionCatastro),
            kpis = Brecha.panel(corrida, brecha).kpis,
            porCategoria =
                Categoria.entries.map { c ->
                    filas.filter { it.categoria == c.name }.let {
                        PorCategoriaReporte(
                            c.name,
                            it.size,
                            suma(it) { f -> f.deltaAutoavaluo },
                            suma(it) { f -> f.deltaImpuesto }
                        )
                    }
                },
            porConfianza =
                Confianza.entries.map { c ->
                    filas.filter { it.confianza == c.name }.let {
                        PorConfianzaReporte(
                            c.name,
                            it.size,
                            suma(it) { f -> f.deltaAutoavaluo },
                            suma(it) { f -> f.deltaImpuesto }
                        )
                    }
                },
            porSector =
                filas.groupBy { clave(it.sector) }.toSortedMap(nullsLast()).map { (sector, suyas) ->
                    PorSectorReporte(sector, suyas.size, suma(suyas) { it.deltaAutoavaluo }, suma(suyas) { it.deltaImpuesto })
                },
            porUso =
                filas.groupBy { clave(it.uso) }.toSortedMap(nullsLast()).map { (uso, suyas) ->
                    PorUsoReporte(
                        uso,
                        suyas.firstNotNullOfOrNull { it.usoDescripcion },
                        suyas.size,
                        suma(suyas) { it.deltaAutoavaluo },
                        suma(suyas) { it.deltaImpuesto }
                    )
                },
            porManzana =
                filas
                    .groupBy { clave(it.sector) to clave(it.manzana) }
                    .map { (sm, suyas) ->
                        PorManzanaReporte(sm.first, sm.second, suyas.size, suma(suyas) { it.deltaAutoavaluo }, suma(suyas) { it.deltaImpuesto })
                    }.sortedWith(
                        compareByDescending<PorManzanaReporte> { it.deltaImpuesto }.thenBy(nullsLast()) { it.sector }.thenBy(nullsLast()) { it.manzana }
                    ).take(TOP_MANZANAS),
            histogramaDeltaAutoavaluo = histograma(filas),
            areas = areas(filas),
            decisiones = if (conDecisiones) ESTADOS_DECISION.associateWith { e -> filas.count { it.decision == e } } else null,
            avisos = avisos
        )
    }

    // every report in its order, with its rows' count and sums; contribuyentes counts its contribuyentes
    fun catalogo(
        filas: List<FilaReportePredio>,
        umbral: BigDecimal?
    ): List<EntradaCatalogo> =
        Reporte.entries.map { r ->
            if (r == Reporte.CONTRIBUYENTES) {
                val c = contribuyentes(filas)
                EntradaCatalogo(r.clave, r.titulo, r.descripcion, c.size, sumaONull(c.map { it.deltaImpuesto }), sumaONull(c.map { it.deltaAutoavaluo }))
            } else {
                val suyas = del(r, filas, umbral)
                EntradaCatalogo(
                    r.clave,
                    r.titulo,
                    r.descripcion,
                    suyas.size,
                    sumaONull(suyas.map { it.deltaImpuesto }),
                    sumaONull(suyas.map { it.deltaAutoavaluo })
                )
            }
        }

    private fun suma(
        filas: List<FilaReportePredio>,
        valor: (FilaReportePredio) -> BigDecimal?
    ): BigDecimal = filas.fold(BigDecimal.ZERO.setScale(2)) { s, f -> valor(f)?.let { s + it } ?: s }

    fun sumaONull(valores: List<BigDecimal?>): BigDecimal? =
        if (valores.all { it == null }) null else valores.fold(BigDecimal.ZERO.setScale(2)) { s, v -> if (v == null) s else s + v }
}
