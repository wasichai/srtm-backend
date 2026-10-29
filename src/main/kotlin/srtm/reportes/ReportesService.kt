package srtm.reportes

import com.fasterxml.jackson.annotation.JsonRawValue
import com.fasterxml.jackson.annotation.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import srtm.Faltan
import srtm.Permisos
import srtm.brecha.BRECHA_CORRIDA
import srtm.brecha.BRECHA_PREDIO
import srtm.brecha.Brecha
import srtm.brecha.BrechaService
import srtm.brecha.Catastros
import srtm.brecha.FilaBrecha
import srtm.brecha.RegistroCorrida
import srtm.brecha.TERMINADA
import srtm.brecha.delUsuario
import srtm.impuesto.ParametrosTributarios
import srtm.rentas.CAMPOS_DEL_NOMBRE
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.Registros
import srtm.rentas.USO_PREDIO
import srtm.rentas.UsoPredio
import srtm.sanciones.Multas
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

// what a report reads: the corrida, its rows (as brecha_predio keeps them and as the reports show them), whether
// catastro's decisions are in, 5 UIT of the year (null without the UIT) and what could not be read. derivada names
// which of the corrida's rows these are (#263): those of who reads the padrón or not, with or without the decisiones;
// the same filas (the very object) while what they are made of does not change
class Lectura(
    val corrida: RegistroCorrida,
    val brecha: List<FilaBrecha>,
    val filas: List<FilaReportePredio>,
    val conDecisiones: Boolean,
    val umbral: BigDecimal?,
    val avisos: List<String>,
    val derivada: Triple<String, Boolean, Boolean>
)

// a lote's geometry as its GeoJSON, written as it is into a response (#263): far lighter than its maps
class LoteJson(
    @get:JsonValue @get:JsonRawValue val json: String
)

// a report's rows, all of them (the controller pages them or exports them), and the corrida they come from
class Listado<T>(
    val corrida: RegistroCorrida,
    val filas: List<T>
)

// #269: a corrida's titulares as they were read, and whether whole: by the platform (Precalentador) or by a caller who
// reads declaracion_predial and contribuyente whole (Permisos.leeEntero). what each caller sees of them is decided per
// response (Vista), never by who read them
class TitularesLeidos(
    val titulares: Map<String, List<Titular>>,
    val enteros: Boolean
)

// the UIT of 1 de enero is missing: mayores-5-uit cannot be told
class FaltaUit(
    faltan: List<String>
) : Faltan("Faltan parámetros para el reporte de Δ mayores a 5 UIT: ${faltan.joinToString("; ")}", faltan)

// the reportes de brechas (#248): a corrida read as the caller (Permisos: read on brecha_corrida and brecha_predio,
// asked on every request, before any cache is touched) and kept in memory per corrida: its rows never change once
// TERMINADA, so they are kept with no vigencia, the srtm.reportes.corridas most recently used (#255). the padrón's
// titulares of its predios (read only for a caller who may read declaracion_predial and contribuyente) do change: per
// corrida for srtm.reportes.cache, the uso catalog per organization the same. the titulares are kept for everyone as
// they are, read whole (#269: TitularesLeidos), and each response shows of them what its caller may see (Vista: the
// name and the código of the contribuyente by its field permissions, in the rows, contribuyentes and the exports). a corrida that ends TERMINADA fills
// them in the background (Precalentador), and a read of what is being read waits for it (Cache.obtener) instead of
// reading it twice. catastro's decisions are read of the organization's catastro, kept for
// srtm.reportes.cache-decisiones (2 minutes: they change as catastro works; #263), read once however many ask at
// once (the visor asks for its panel, its predios and its lotes together), and only by what shows them (the resumen,
// the lists of predios and their exports; not the catalog nor contribuyentes). when catastro cannot answer they are
// left out with an aviso, never an error, and the failure is kept srtm.reportes.cache-fallo-decisiones (30 s): a
// catastro down is not waited for on every page. the rows as the reports show them are made once per what they are
// made of (Insumos: the same cached rows, titulares, usos and decisiones), not on every request (#263). the counting
// and sorting run off the event loop (Dispatchers.Default)
@Service
class ReportesService(
    private val brechas: BrechaService,
    private val registros: Registros,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val catastros: Catastros,
    private val organizaciones: OrganizationRepository,
    private val parametros: ParametrosTributarios,
    @param:Value("\${srtm.reportes.cache:10m}") vigencia: Duration,
    @param:Value("\${srtm.reportes.corridas:8}") corridas: Int,
    @param:Value("\${srtm.reportes.cache-decisiones:2m}") vigenciaDecisiones: Duration,
    @param:Value("\${srtm.reportes.cache-fallo-decisiones:30s}") vigenciaFallo: Duration
) {
    private val filasPorCorrida = Cache<String, List<FilaBrecha>>(null, corridas)
    private val titularesPorCorrida = Cache<String, TitularesLeidos>(vigencia, corridas)
    private val usosPorOrganizacion = Cache<UUID, Map<String, String>>(vigencia, corridas)
    private val decisionesPorAnio = Cache<Pair<UUID, Int>, Map<String, String>>(vigenciaDecisiones, corridas)
    private val falloDeDecisiones = Cache<Pair<UUID, Int>, String>(vigenciaFallo, corridas)

    // #260: the lotes of the LOTES most recently used corridas, by brecha_predio id (the visor's map): read with the
    // rows, from the same read, and kept apart because they are most of its weight. kept as their json (LoteJson,
    // #263): 30 000 lotes are some 19 MiB so, 47 MiB as maps of lists of boxed numbers
    private val lotesPorCorrida = Cache<String, Map<String, LoteJson>>(null, LOTES)

    // #263: the rows as the reports show them, the last made of each corrida for who reads the padrón and who does
    // not, with and without the decisiones, with what they were made of: made again only when one of those changed
    private val filasDerivadas = Cache<Triple<String, Boolean, Boolean>, Pair<Insumos, List<FilaReportePredio>>>(null, DERIVADAS)

    // the corrida last warmed per organization and year
    private val precalentadas = ConcurrentHashMap<Pair<UUID, Int>, String>()

    suspend fun resumen(
        anio: Int,
        corrida: UUID?
    ): ResumenReportes {
        val l = leer(anio, corrida)
        return calcular { Reportes.resumen(l.corrida, l.brecha, l.filas, l.conDecisiones, l.avisos) }
    }

    suspend fun catalogo(
        anio: Int,
        corrida: UUID?
    ): List<EntradaCatalogo> {
        val l = leer(anio, corrida, conDecisiones = false)
        return calcular { Reportes.catalogo(l.filas, l.umbral) }
    }

    // a report of predios: its rows filtered and sorted. mayores-5-uit without the UIT of the year: 422
    suspend fun predios(
        reporte: Reporte,
        anio: Int,
        corrida: UUID?,
        filtros: Filtros,
        orden: Orden,
        dir: Direccion
    ): Listado<FilaReportePredio> {
        val l = leer(anio, corrida)
        if (reporte == Reporte.MAYORES_5_UIT && l.umbral == null) throw FaltaUit(listOf("UIT $anio"))
        val vista = vista(l)
        return Listado(
            l.corrida,
            calcular { Reportes.paraElUsuario(Reportes.ordenar(Reportes.filtrar(Reportes.del(reporte, l.filas, l.umbral), filtros, vista), orden, dir), vista) }
        )
    }

    // the contribuyentes: the padrón's, so its readers only. the predios filtered (all but q), as the caller may see
    // them (Vista), then grouped, then q over the contribuyentes
    suspend fun contribuyentes(
        anio: Int,
        corrida: UUID?,
        filtros: Filtros,
        orden: Orden,
        dir: Direccion
    ): Listado<FilaReporteContribuyente> {
        permisos.exigirLeer(CONTRIBUYENTE, "consultar el reporte de contribuyentes")
        permisos.exigirLeer(DECLARACION, "consultar el reporte de contribuyentes")
        val l = leer(anio, corrida, conDecisiones = false)
        val vista = vista(l)
        return Listado(
            l.corrida,
            calcular {
                val predios = Reportes.paraElUsuario(Reportes.filtrar(Reportes.del(Reporte.CONTRIBUYENTES, l.filas, l.umbral), filtros.copy(q = null)), vista)
                Reportes.ordenarContribuyentes(Reportes.buscarContribuyentes(Reportes.contribuyentes(predios), filtros.q), orden, dir)
            }
        )
    }

    // conDecisiones false: the caller does not show them (catastro is not asked)
    suspend fun leer(
        anio: Int,
        corrida: UUID?,
        conDecisiones: Boolean = true
    ): Lectura {
        permisos.exigirLeer(BRECHA_CORRIDA, "consultar los reportes de brechas")
        permisos.exigirLeer(BRECHA_PREDIO, "consultar los reportes de brechas")
        val r = brechas.corridaDelAnio(anio, corrida)
        if (r.estado != TERMINADA) throw ConflictException("La corrida ${r.id} no está terminada (${r.estado}): sus reportes saldrían incompletos")
        val id = r.id!!
        val organizacion = currentUser.require().organizationId
        val avisos =
            r.mensaje
                ?.lines()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
                .toMutableList()
        val brecha = filas(id)
        val titulares =
            if (permisos.puedeLeer(DECLARACION) && permisos.puedeLeer(CONTRIBUYENTE)) {
                titularesDe(id, anio)
            } else {
                avisos += "Sin lectura del padrón (declaracion_predial y contribuyente): las filas no traen su contribuyente"
                null
            }
        // a caller who may not read the catalog gets none, and none is kept for the others
        val usos = usosPorOrganizacion.get(organizacion) ?: usos()?.also { usosPorOrganizacion.put(organizacion, it) }.orEmpty()
        val decisiones = if (conDecisiones) decisiones(organizacion, anio, avisos) else null
        val umbral = umbral(anio, avisos)
        val insumos = Insumos(brecha, titulares, usos, decisiones)
        val derivada = Triple(id, titulares != null, decisiones != null)
        val filas =
            filasDerivadas.get(derivada)?.takeIf { it.first == insumos }?.second
                ?: calcular { Reportes.filas(brecha, titulares, usos, decisiones) }.also { filasDerivadas.put(derivada, insumos to it) }
        return Lectura(r, brecha, filas, decisiones != null, umbral, avisos, derivada)
    }

    // #255: what leer keeps of a TERMINADA corrida (its rows, its titulares and its organization's usos), read before
    // anyone asks for it. no permission is asked here: Precalentador runs it as the platform, and leer asks for them on
    // every request before it reads the cache. the corrida warmed before it for the same organization and year is no
    // longer the latest: it is dropped, so the daily automatic corridas do not fill the cache with what nobody reads
    suspend fun precalentar(
        organizacion: UUID,
        anio: Int,
        corrida: String
    ) {
        precalentadas.put(organizacion to anio, corrida)?.takeIf { it != corrida }?.let {
            filasPorCorrida.quitar(it)
            titularesPorCorrida.quitar(it)
            lotesPorCorrida.quitar(it)
        }
        filas(corrida)
        if (!titularesPorCorrida.obtener(corrida) { TitularesLeidos(titulares(anio), enteros = true) }.enteros) {
            titularesPorCorrida.put(corrida, TitularesLeidos(titulares(anio), enteros = true))
        }
        if (usosPorOrganizacion.get(organizacion) == null) usos()?.let { usosPorOrganizacion.put(organizacion, it) }
    }

    // whether the corrida's rows and titulares are kept: for the tests
    internal fun precalentada(corrida: String): Boolean = filasPorCorrida.get(corrida) != null && titularesPorCorrida.get(corrida) != null

    // the corrida's rows, without the lote's geometry (the reports never show it, the visor reads it from lotes): only
    // whether they had one. the geometries of the same read are kept for lotes
    private suspend fun filas(corrida: String): List<FilaBrecha> =
        filasPorCorrida.obtener(corrida) {
            val leidas = brechas.filas(corrida)
            lotesPorCorrida.put(corrida, lotesDe(leidas))
            leidas.map { it.copy(loteGeom = null, conLote = it.loteGeom != null) }
        }

    // #260: the corrida's lotes by brecha_predio id. no permission is asked here: the visor calls it after leer, which
    // asks them; the rows are read again only when the lotes were dropped before them
    suspend fun lotes(corrida: String): Map<String, LoteJson> = lotesPorCorrida.obtener(corrida) { lotesDe(brechas.filas(corrida)) }

    private fun lotesDe(filas: List<FilaBrecha>): Map<String, LoteJson> =
        filas.mapNotNull { f -> f.id?.let { id -> f.loteGeom?.let { id to LoteJson(GEOJSON.writeValueAsString(it)) } } }.toMap()

    // #269: what the caller may see of the titulares of the rows it reads; all of it when they have none (who may not
    // read the padrón: nothing to hide, nothing asked)
    private suspend fun vista(l: Lectura): Vista =
        if (!l.derivada.second) {
            Vista.TODO
        } else {
            Vista(
                nombre = permisos.puedeLeerCampos(CONTRIBUYENTE, CAMPOS_DEL_NOMBRE),
                codigo = permisos.puedeLeerCampos(CONTRIBUYENTE, CAMPO_CODIGO)
            )
        }

    // #269: the corrida's titulares, kept for everyone. a caller reads them as itself when they are missing (asPlatform
    // refuses inside a request, ADR-039), and they are kept as whole or not by whether it reads the padrón whole.
    // what was not read whole is read again, once, by the first caller who reads it whole, and kept for everyone: no
    // caller's restrictions ever reach another's response. the platform's warm-up (precalentar) reads them whole
    private suspend fun titularesDe(
        corrida: String,
        anio: Int
    ): Map<String, List<Titular>> {
        var entero: Boolean? = null

        suspend fun leeEntero() = entero ?: (permisos.leeEntero(DECLARACION) && permisos.leeEntero(CONTRIBUYENTE)).also { entero = it }
        val leidos = titularesPorCorrida.obtener(corrida) { TitularesLeidos(titulares(anio), leeEntero()) }
        if (leidos.enteros || !leeEntero()) return leidos.titulares
        // dropped first: who reads it whole at the same time waits for this read instead of making its own
        titularesPorCorrida.quitar(corrida)
        return titularesPorCorrida.obtener(corrida) { TitularesLeidos(titulares(anio), enteros = true) }.titulares
    }

    // the vigente DJs of the year and their contribuyentes: each predio's titulares
    private suspend fun titulares(anio: Int): Map<String, List<Titular>> {
        val declaraciones = registros.all(DECLARACION, Declaracion::class.java, filters = mapOf("anio" to "$anio"))
        val leidos = registros.byIds(CONTRIBUYENTE, ContribuyenteDelReporte::class.java, declaraciones.mapNotNull { it.contribuyente }.distinct())
        val pricos = leidos.filterValues { it.prico == true }.keys
        return Reportes.titulares(declaraciones, leidos.mapValues { it.value.contribuyente() }, pricos)
    }

    // uso_predio's código (Brecha.llave) -> its uso; null for a caller who may not read the catalog
    private suspend fun usos(): Map<String, String>? =
        try {
            registros
                .all(USO_PREDIO, UsoPredio::class.java)
                .mapNotNull { u -> Brecha.llave(u.codigo)?.let { c -> u.uso?.let { c to it } } }
                .toMap()
        } catch (_: ForbiddenException) {
            null
        }

    // catastro's decision_brecha of the year by its codigo_municipal (Brecha.llave); null with an aviso when there is
    // no catastro for the organization or it could not be read
    private suspend fun decisiones(
        organizacion: UUID,
        anio: Int,
        avisos: MutableList<String>
    ): Map<String, String>? {
        val clave = organizacion to anio
        return try {
            decisionesPorAnio.obtener(clave) {
                // who waited on a read that failed tries again: the failure kept answers it, catastro is not asked
                falloDeDecisiones.get(clave)?.let { throw SinDecisiones(it) }
                leerDecisiones(clave)
            }
        } catch (e: SinDecisiones) {
            avisos += e.aviso
            null
        }
    }

    // catastro's decisions, or SinDecisiones with the aviso (kept srtm.reportes.cache-fallo-decisiones when catastro
    // failed; not when the organization has no catastro: that is no failure)
    private suspend fun leerDecisiones(clave: Pair<UUID, Int>): Map<String, String> {
        val anio = clave.second
        try {
            val catastro =
                catastros.delUsuario(currentUser.require(), organizaciones)
                    ?: throw SinDecisiones("$SIN_DECISIONES: catastro no está configurado para esta organización")
            return catastro
                .decisiones(anio)
                .filter { it.anio == null || it.anio == anio }
                .mapNotNull { d -> Brecha.llave(d.codigoMunicipal)?.let { c -> d.estado?.let { c to it } } }
                .toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SinDecisiones) {
            throw e
        } catch (e: Exception) {
            log.warn("los reportes de {} siguen sin las decisiones de catastro: {}", anio, e.message)
            val aviso = "$SIN_DECISIONES: ${e.message ?: e.javaClass.simpleName}"
            falloDeDecisiones.put(clave, aviso)
            throw SinDecisiones(aviso)
        }
    }

    private class SinDecisiones(
        val aviso: String
    ) : RuntimeException(aviso, null, false, false)

    // 5 UIT of the 1st of January (Seguimiento's rule for SUPERA_5_UIT); null with an aviso without it
    private suspend fun umbral(
        anio: Int,
        avisos: MutableList<String>
    ): BigDecimal? {
        val todos =
            try {
                parametros.todos()
            } catch (_: ForbiddenException) {
                avisos += "Sin lectura de los parámetros tributarios: el reporte de Δ mayores a 5 UIT queda vacío"
                return null
            }
        val uit = Multas.uit(todos, LocalDate.of(anio, 1, 1)).valor?.valorNumerico
        if (uit == null) avisos += "Falta la UIT de $anio: el reporte de Δ mayores a 5 UIT queda vacío"
        return uit?.multiply(CINCO)
    }

    // counting and sorting tens of thousands of rows: off the event loop
    private suspend fun <T> calcular(bloque: () -> T): T = withContext(Dispatchers.Default) { bloque() }

    private companion object {
        val log = LoggerFactory.getLogger(ReportesService::class.java)
        val CINCO = BigDecimal(5)
        const val SIN_DECISIONES = "Sin las decisiones de catastro"
        val CAMPO_CODIGO = setOf("codigo")

        // the corridas whose lotes are kept (#260): a corrida of Ica's is some tens of MB of them
        const val LOTES = 2

        // the rows made for the reports kept (#263): up to 4 per corrida, each a few MiB (its values are the cached ones')
        const val DERIVADAS = 8
        val GEOJSON: JsonMapper = JsonMapper.builder().build()
    }
}
