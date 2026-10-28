package srtm.visor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import srtm.ParametroInvalido
import srtm.Permisos
import srtm.brecha.BRECHA_CORRIDA
import srtm.brecha.BRECHA_NIVEL
import srtm.brecha.BRECHA_PREDIO
import srtm.brecha.Catastro
import srtm.brecha.CatastroException
import srtm.brecha.Catastros
import srtm.brecha.FilaBrecha
import srtm.brecha.ImagenWms
import srtm.brecha.NivelBrecha
import srtm.brecha.RegistroCorrida
import srtm.brecha.SIN_CATASTRO_CONFIGURADO
import srtm.brecha.SIN_WMS
import srtm.brecha.delUsuario
import srtm.rentas.Registros
import srtm.reportes.Cache
import srtm.reportes.Insumos
import srtm.reportes.Lectura
import srtm.reportes.ReportesService
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.WasichaiException
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// a WMS layer the visor offers: its name in GeoServer and how the map names it
data class CapaWms(
    val layerName: String,
    val label: String
)

// catastro could not answer what the visor asked of it: a 502, the rest of the visor goes on
class CatastroNoResponde(
    mensaje: String
) : WasichaiException(HttpStatus.BAD_GATEWAY, mensaje)

// the visor de brechas (#260), read only: srtm's own corrida as the reportes read it (ReportesService: its rows, cached,
// with catastro's decisions), and from the organization's catastro, as its service account, only what srtm lacks: the
// vías con arancel and the WMS images. every query asks READ on brecha_corrida and brecha_predio first (Permisos), as
// the reportes do; a niveles' read is core's, as the caller. what catastro cannot answer is a 502 of that route only
@Service
class VisorService(
    private val reportes: ReportesService,
    private val permisos: Permisos,
    private val registros: Registros,
    private val catastros: Catastros,
    private val currentUser: CurrentUser,
    private val organizaciones: OrganizationRepository
) {
    // the layers confirmed per organization (a GetMap of 1 px each): GeoServer publishes them once, rarely changes them
    private val capasPorOrganizacion = Cache<UUID, List<CapaWms>>(VIGENCIA_CAPAS, 64)

    // #263: the visor's rows and its panel, the last made of each of the reportes' rows (ReportesService keeps those
    // while what they are made of does not change): a request with its cache full makes neither again
    private val filasDelVisor = Cache<Triple<String, Boolean, Boolean>, Pair<List<Any?>, List<FilaVisor>>>(null, DERIVADAS)
    private val paneles = Cache<Triple<String, Boolean, Boolean>, Pair<List<Any?>, PanelVisor>>(null, DERIVADAS)

    suspend fun panel(
        anio: Int,
        corrida: UUID?
    ): PanelVisor {
        val (l, filas) = leer(anio, corrida)
        // the same rows, corrida and avisos: the same panel
        val insumos = listOf(Insumos(filas), l.corrida, l.avisos)
        return paneles.get(l.derivada)?.takeIf { it.first == insumos }?.second
            ?: calcular { Visor.panel(l.corrida, filas, l.conDecisiones, l.avisos) }.also { paneles.put(l.derivada, insumos to it) }
    }

    suspend fun predios(
        anio: Int,
        corrida: UUID?,
        filtro: FiltroVisor,
        orden: OrdenVisor
    ): Pair<RegistroCorrida, List<FilaVisor>> {
        val (l, filas) = leer(anio, corrida)
        return l.corrida to calcular { filas.filter(filtro::admite).sortedWith(orden.comparador) }
    }

    suspend fun lotes(
        anio: Int,
        corrida: UUID?,
        filtro: FiltroVisor
    ): Map<String, Any?> {
        val (l, filas) = leer(anio, corrida)
        val lotes = reportes.lotes(l.corrida.id!!)
        return calcular { Visor.lotes(filas, lotes, filtro) }
    }

    // the niveles of one row of a corrida (brecha_nivel), read as the caller; a row that is none: 404
    suspend fun niveles(id: UUID): List<NivelVisor> {
        exigir()
        registros.get(BRECHA_PREDIO, FilaBrecha::class.java, id)
        return Visor.niveles(registros.all(BRECHA_NIVEL, NivelBrecha::class.java, filters = mapOf("brecha_predio" to id.toString())))
    }

    suspend fun vias(anio: Int): String {
        exigir()
        val catastro = catastro()
        return try {
            catastro.vias(anio)
        } catch (e: CatastroException) {
            throw CatastroNoResponde("No se pudieron leer las vías con arancel de catastro: ${e.message}")
        }
    }

    // the organization's layers of the visor's list that its catastro serves to srtm; none without a WMS
    suspend fun capas(): List<CapaWms> {
        exigir()
        val catastro = catastro()
        if (!catastro.conWms) return emptyList()
        return capasPorOrganizacion.obtener(currentUser.require().organizationId) { confirmar(catastro) }
    }

    // a GetMap checked here (PedidoWms) before it goes to catastro: a layer that is not the organization's own of the
    // list is a 403, a request it cannot serve a 422, and neither reaches catastro. a refusal of catastro is a 403 with
    // a short message (its reason goes to the log), anything else it fails with a 502
    suspend fun wms(parametros: Map<String, List<String>>): ImagenWms {
        exigir()
        val catastro = catastro()
        if (!catastro.conWms) throw ConflictException(SIN_WMS)
        val permitidas = permitidas(prefijo(catastro))
        val pedido =
            when (val p = PedidoWms.leer(parametros, permitidas.keys)) {
                is PedidoWms.Rechazado ->
                    if (p.estado == 403) throw ForbiddenException("Capa WMS no permitida: ${p.motivo}") else throw ParametroInvalido(p.parametro, p.motivo)
                is PedidoWms.Valido -> p
            }
        val imagen =
            try {
                catastro.wms(pedido.parametros)
            } catch (e: CatastroException) {
                if (e.estado == 403) {
                    log.warn("catastro rechazó la imagen WMS de {}: {}", pedido.capas, e.detalle ?: e.message)
                    throw ForbiddenException("Catastro no sirve esa capa a srtm")
                }
                throw CatastroNoResponde("Catastro no dio la imagen WMS: ${e.message}")
            }
        if (imagen.tipo?.type != "image") {
            descartar(imagen)
            log.warn("catastro respondió la imagen WMS de {} como {}", pedido.capas, imagen.tipo)
            throw CatastroNoResponde("Catastro no respondió una imagen WMS (${imagen.tipo})")
        }
        return imagen
    }

    // a GetMap of 1 px of each layer of the list, far from any lote: the layer is offered when catastro answers an
    // image. a refusal (403, 404) leaves it out; catastro not answering is a 502, and nothing is kept
    private suspend fun confirmar(catastro: Catastro): List<CapaWms> =
        permitidas(prefijo(catastro)).mapNotNull { (nombre, etiqueta) ->
            val imagen =
                try {
                    catastro.wms(sonda(nombre))
                } catch (e: CatastroException) {
                    if (e.estado == 403 || e.estado == 404) {
                        log.info("la capa WMS {} no se ofrece en el visor: {}", nombre, e.detalle ?: e.message)
                        return@mapNotNull null
                    }
                    throw CatastroNoResponde("Catastro no respondió al confirmar la capa $nombre: ${e.message}")
                }
            descartar(imagen)
            if (imagen.tipo?.type == "image") CapaWms(nombre, etiqueta) else null
        }

    // the first 8 hex digits of the catastro organization's id, as wasichai names its tables and layers
    // (catastro-backend's AutorizacionWms)
    private suspend fun prefijo(catastro: Catastro): String =
        try {
            catastro
                .organizacionCatastral()
                .replace("-", "")
                .take(8)
                .lowercase()
        } catch (e: CatastroException) {
            throw CatastroNoResponde("No se pudo leer la organización de catastro: ${e.message}")
        }

    private fun permitidas(prefijo: String): Map<String, String> =
        CAPAS.associate { (objeto, geometria, etiqueta) ->
            "${objeto}__${prefijo}__$geometria" to
                etiqueta
        }

    // read to its end, so catastro's connection goes back to the pool: ClienteCatastro.wms already released its
    // pooled buffers, what comes is on the heap (#264)
    private suspend fun descartar(imagen: ImagenWms) {
        try {
            imagen.cuerpo
                .then()
                .awaitSingleOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // nothing of it is used
        }
    }

    private suspend fun leer(
        anio: Int,
        corrida: UUID?
    ): Pair<Lectura, List<FilaVisor>> {
        exigir()
        val l = reportes.leer(anio, corrida)
        // the same reportes' rows and the same 5 UIT: the same rows of the visor
        val insumos = listOf(Insumos(l.filas), l.umbral)
        val filas =
            filasDelVisor.get(l.derivada)?.takeIf { it.first == insumos }?.second
                ?: calcular { Visor.filas(l.filas, l.conDecisiones, l.umbral) }.also { filasDelVisor.put(l.derivada, insumos to it) }
        return l to filas
    }

    private suspend fun exigir() {
        permisos.exigirLeer(BRECHA_CORRIDA, ACTO)
        permisos.exigirLeer(BRECHA_PREDIO, ACTO)
    }

    private suspend fun catastro(): Catastro = catastros.delUsuario(currentUser.require(), organizaciones) ?: throw ConflictException(SIN_CATASTRO_CONFIGURADO)

    // filtering and sorting tens of thousands of rows: off the event loop
    private suspend fun <T> calcular(bloque: () -> T): T = withContext(Dispatchers.Default) { bloque() }

    companion object {
        private val log = LoggerFactory.getLogger(VisorService::class.java)
        const val ACTO = "consultar el visor de brechas"
        val VIGENCIA_CAPAS: Duration = Duration.ofMinutes(10)

        // the rows and panels kept (#263): up to 4 per corrida, as ReportesService's
        const val DERIVADAS = 8

        // the layers the visor may show, of objects SRTM_LECTOR reads in catastro (never brecha_ficha): object,
        // geometry column, label
        val CAPAS =
            listOf(
                Triple("predio_catastral", "lote_geom", "Predios catastrales"),
                Triple("tramo_via", "geom", "Tramos de vía")
            )

        // a GetMap of one transparent pixel in the Gulf of Guinea: no lote is drawn
        fun sonda(capa: String): List<Pair<String, String>> =
            listOf(
                "service" to "WMS",
                "version" to "1.1.1",
                "request" to "GetMap",
                "layers" to capa,
                "styles" to "",
                "srs" to "EPSG:4326",
                "bbox" to "0,0,0.0001,0.0001",
                "width" to "1",
                "height" to "1",
                "format" to "image/png",
                "transparent" to "true"
            )
    }
}
