package srtm.reportes

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
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.Registros
import srtm.rentas.USO_PREDIO
import srtm.rentas.UsoPredio
import srtm.sanciones.Multas
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
// catastro's decisions are in, 5 UIT of the year (null without the UIT) and what could not be read
class Lectura(
    val corrida: RegistroCorrida,
    val brecha: List<FilaBrecha>,
    val filas: List<FilaReportePredio>,
    val conDecisiones: Boolean,
    val umbral: BigDecimal?,
    val avisos: List<String>
)

// a report's rows, all of them (the controller pages them or exports them), and the corrida they come from
class Listado<T>(
    val corrida: RegistroCorrida,
    val filas: List<T>
)

// the UIT of 1 de enero is missing: mayores-5-uit cannot be told
class FaltaUit(
    faltan: List<String>
) : Faltan("Faltan parámetros para el reporte de Δ mayores a 5 UIT: ${faltan.joinToString("; ")}", faltan)

// the reportes de brechas (#248): a corrida read as the caller (Permisos: read on brecha_corrida and brecha_predio,
// asked on every request, before any cache is touched) and kept in memory per corrida: its rows never change once
// TERMINADA, so they are kept with no vigencia, the srtm.reportes.corridas most recently used (#255). the padrón's
// titulares of its predios (read only for a caller who may read declaracion_predial and contribuyente) do change: per
// corrida for srtm.reportes.cache, the uso catalog per organization the same. a corrida that ends TERMINADA fills
// them in the background (Precalentador), and a read of what is being read waits for it (Cache.obtener) instead of
// reading it twice. catastro's decisions are read of the organization's catastro, kept for
// srtm.reportes.cache-decisiones (a minute: they change as catastro works), and only by what shows them (the resumen,
// the lists of predios and their exports; not the catalog nor contribuyentes). when catastro cannot answer they are
// left out with an aviso, never an error, and the failure is kept srtm.reportes.cache-fallo-decisiones (30 s): a
// catastro down is not waited for on every page. the counting and sorting run off the event loop (Dispatchers.Default)
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
    @param:Value("\${srtm.reportes.cache-decisiones:1m}") vigenciaDecisiones: Duration,
    @param:Value("\${srtm.reportes.cache-fallo-decisiones:30s}") vigenciaFallo: Duration
) {
    private val filasPorCorrida = Cache<String, List<FilaBrecha>>(null, corridas)
    private val titularesPorCorrida = Cache<String, Map<String, List<Titular>>>(vigencia, corridas)
    private val usosPorOrganizacion = Cache<UUID, Map<String, String>>(vigencia, corridas)
    private val decisionesPorAnio = Cache<Pair<UUID, Int>, Map<String, String>>(vigenciaDecisiones, corridas)
    private val falloDeDecisiones = Cache<Pair<UUID, Int>, String>(vigenciaFallo, corridas)

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
        return Listado(l.corrida, calcular { Reportes.ordenar(Reportes.filtrar(Reportes.del(reporte, l.filas, l.umbral), filtros), orden, dir) })
    }

    // the contribuyentes: the padrón's, so its readers only. the predios filtered (all but q), then grouped, then q
    // over the contribuyentes
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
        return Listado(
            l.corrida,
            calcular {
                val predios = Reportes.filtrar(Reportes.del(Reporte.CONTRIBUYENTES, l.filas, l.umbral), filtros.copy(q = null))
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
                titularesPorCorrida.obtener(id) { titulares(anio) }
            } else {
                avisos += "Sin lectura del padrón (declaracion_predial y contribuyente): las filas no traen su contribuyente"
                null
            }
        // a caller who may not read the catalog gets none, and none is kept for the others
        val usos = usosPorOrganizacion.get(organizacion) ?: usos()?.also { usosPorOrganizacion.put(organizacion, it) }.orEmpty()
        val decisiones = if (conDecisiones) decisiones(organizacion, anio, avisos) else null
        val umbral = umbral(anio, avisos)
        val filas = calcular { Reportes.filas(brecha, titulares, usos, decisiones) }
        return Lectura(r, brecha, filas, decisiones != null, umbral, avisos)
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
        }
        filas(corrida)
        titularesPorCorrida.obtener(corrida) { titulares(anio) }
        if (usosPorOrganizacion.get(organizacion) == null) usos()?.let { usosPorOrganizacion.put(organizacion, it) }
    }

    // whether the corrida's rows and titulares are kept: for the tests
    internal fun precalentada(corrida: String): Boolean = filasPorCorrida.get(corrida) != null && titularesPorCorrida.get(corrida) != null

    // the corrida's rows, without the lote's geometry (the reports never show it)
    private suspend fun filas(corrida: String): List<FilaBrecha> = filasPorCorrida.obtener(corrida) { brechas.filas(corrida).map { it.copy(loteGeom = null) } }

    // the vigente DJs of the year and their contribuyentes: each predio's titulares
    private suspend fun titulares(anio: Int): Map<String, List<Titular>> {
        val declaraciones = registros.all(DECLARACION, Declaracion::class.java, filters = mapOf("anio" to "$anio"))
        val contribuyentes = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, declaraciones.mapNotNull { it.contribuyente }.distinct())
        return Reportes.titulares(declaraciones, contribuyentes)
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
        decisionesPorAnio.get(clave)?.let { return it }
        falloDeDecisiones.get(clave)?.let {
            avisos += it
            return null
        }
        try {
            val catastro = catastros.delUsuario(currentUser.require(), organizaciones)
            if (catastro == null) {
                avisos += "$SIN_DECISIONES: catastro no está configurado para esta organización"
                return null
            }
            val leidas =
                catastro
                    .decisiones(anio)
                    .filter { it.anio == null || it.anio == anio }
                    .mapNotNull { d -> Brecha.llave(d.codigoMunicipal)?.let { c -> d.estado?.let { c to it } } }
                    .toMap()
            decisionesPorAnio.put(organizacion to anio, leidas)
            return leidas
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("los reportes de {} siguen sin las decisiones de catastro: {}", anio, e.message)
            val aviso = "$SIN_DECISIONES: ${e.message ?: e.javaClass.simpleName}"
            falloDeDecisiones.put(clave, aviso)
            avisos += aviso
            return null
        }
    }

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
    }
}
