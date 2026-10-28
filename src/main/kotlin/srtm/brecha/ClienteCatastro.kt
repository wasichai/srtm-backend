package srtm.brecha

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import srtm.PorOrganizacion
import srtm.TokenDeServicio
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.organization.OrganizationRepository
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// srtm's access to catastro-backend: a catastro per organization of srtm, each its url and the service account that
// its catastro's model/crear_cuenta_srtm.py created (role SRTM_LECTOR). off by default; the secrets come from the
// environment (application.yml, README). organizaciones is keyed by the organization (its slug or its id), or by any
// name when the entry's organizacion says which: an environment variable cannot carry an id's dashes. the single url,
// client-id, client-secret and organizacion of before are one more entry (cuentas)
@ConfigurationProperties(prefix = "srtm.catastro")
data class CatastroProperties(
    val enabled: Boolean = false,
    val url: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    // each request: catastro values a page on the fly. an entry without its own timeout takes this one
    val timeout: Duration = ClienteCatastro.TIMEOUT,
    val organizacion: String = "",
    // #260: the single entry's WMS (catastro-ui's nginx in front of GeoServer), as CuentaCatastro.wmsUrl
    val wmsUrl: String = "",
    val organizaciones: Map<String, CuentaCatastro> = emptyMap(),
    // the background reader of catastro's cambios (#203, SeguimientoCatastro)
    val seguimiento: SeguimientoProperties = SeguimientoProperties()
) {
    // every account by its organization (trimmed, lowercase: a slug matches without case, an id parses either way):
    // the single properties' when organizacion is set, then the map's, which replaces it for the same organization
    fun cuentas(): Map<String, CuentaCatastro> {
        val todas = linkedMapOf<String, CuentaCatastro>()
        val unica = organizacion.trim()
        if (unica.isNotEmpty()) todas[unica.lowercase()] = CuentaCatastro(unica, url, clientId, clientSecret, timeout, wmsUrl)
        for ((clave, cuenta) in organizaciones) {
            val suya = cuenta.organizacion.trim().ifEmpty { clave.trim() }
            todas[suya.lowercase()] = cuenta.copy(organizacion = suya, timeout = cuenta.timeout ?: timeout)
        }
        return todas
    }

    // the organizations two entries of the map name (by their organizacion or their key), with those keys: neither is
    // read, the environment's order would pick one. the single entry replaced by one of the map is not one of these
    fun repetidas(): Map<String, List<String>> =
        organizaciones.keys
            .groupBy { clave ->
                organizaciones
                    .getValue(clave)
                    .organizacion
                    .trim()
                    .ifEmpty { clave.trim() }
                    .lowercase()
            }.filterValues { it.size > 1 }
            .mapValues { it.value.sorted() }

    // never the secret: this text may end up in a log
    override fun toString() =
        "CatastroProperties(enabled=$enabled, url=$url, clientId=$clientId, timeout=$timeout, organizacion=$organizacion, " +
            "wmsUrl=$wmsUrl, organizaciones=$organizaciones, seguimiento=$seguimiento)"
}

// minutos: how often the cambios are read (0, the default: never); hora: from which hour of the day (Lima) the
// automatic corrida of the day may start. an hour off the day fails the startup: 24 or more would never run it
data class SeguimientoProperties(
    val minutos: Long = 0,
    val hora: Int = 2
) {
    init {
        require(hora in 0..23) { "srtm.catastro.seguimiento.hora debe ser una hora del día, de 0 a 23: $hora" }
    }
}

// one organization's catastro-backend and its service account. wmsUrl (#260, optional): the WMS the visor de brechas
// relays its images from, catastro-ui's nginx in front of GeoServer (http://catastro-ui/geoserver/catastro/wms),
// which asks catastro-backend to authorize each GetMap; empty, the visor has no WMS layers
data class CuentaCatastro(
    val organizacion: String = "",
    val url: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val timeout: Duration? = null,
    val wmsUrl: String = ""
) {
    // what it lacks to be read
    val faltan: List<String>
        get() = listOf("url" to url, "client-id" to clientId, "client-secret" to clientSecret).filter { it.second.isBlank() }.map { it.first }

    override fun toString() = "CuentaCatastro(organizacion=$organizacion, url=$url, clientId=$clientId, timeout=$timeout, wmsUrl=$wmsUrl)"
}

// catastro-backend's valuations of a year, every page of them, and its engine on insumos that are not in it
interface Catastro {
    // porPagina runs after each page is read: the corrida beats its latido there
    suspend fun valuaciones(
        anio: Int,
        porPagina: suspend () -> Unit = {}
    ): Valuaciones

    // every titular of the padrón, every page (#204). an older catastro without GET /titulares answers 404 or 403:
    // CatastroException.estado carries it, and that is no failure of the corrida
    suspend fun titulares(porPagina: suspend () -> Unit = {}): List<TitularCatastral>

    // one page of catastro's cambios since desde (#203; null reads from the start), oldest first: the caller keeps
    // paging with the cursor it gets back until hayMas is false. an older catastro without GET /cambios answers 404
    // or 403: CatastroException.estado carries it, as titulares does
    suspend fun cambios(
        desde: String?,
        size: Int = ClienteCatastro.TAMANIO_PAGINA
    ): PaginaDeCambios

    // a declaración's autoavalúo sugerido (#149): nothing is stored on either side
    suspend fun valuar(insumos: InsumosValuacion): ValuacionDeInsumos

    // Normativa's copy (#189): catastro's arancel of a.clave becomes a. created when it lacks one, replaced when its
    // differs, left alone when it is the same
    suspend fun guardarArancel(a: ArancelCatastral): CopiaDeArancel

    // catastro's arancel of the clave is deleted; none is no error
    suspend fun borrarArancel(clave: String): CopiaDeArancel

    // the reportes (#248): every decision_brecha of the year, what catastro decided to do with each predio. a catastro
    // that cannot give them fails with a CatastroException: the reports go on without them
    suspend fun decisiones(anio: Int): List<DecisionCatastral> = throw CatastroException("Este catastro no da sus decisiones de brecha", 404)

    // the visor de brechas (#260): GET /api/catastro/vias/aranceles?anio, catastro's json as it answers it
    suspend fun vias(anio: Int): String = throw CatastroException("Este catastro no da sus vías con arancel", 404)

    // the id of the catastro organization the service account is of: its WMS layers are named after it
    suspend fun organizacionCatastral(): String = throw CatastroException("Este catastro no da su organización", 404)

    // whether a WMS is configured (CuentaCatastro.wmsUrl)
    val conWms: Boolean get() = false

    // a GetMap relayed to the WMS with these parameters (checked by the caller: visor.PedidoWms), its image as it
    // comes. a refusal fails with catastro's status, and its reason in detalle
    suspend fun wms(parametros: List<Pair<String, String>>): ImagenWms = throw CatastroException(SIN_WMS, 404)
}

// a WMS image as catastro serves it: its type and its bytes, streamed
class ImagenWms(
    val tipo: MediaType?,
    val cuerpo: Flux<DataBuffer>
)

// an arancel as catastro-backend keeps it (its object arancel, catastro-backend's model.json): srtm's VIGENTE one,
// without its estado nor its signatures. clave is id_aranc|anio, unique in catastro
data class ArancelCatastral(
    val clave: String,
    val idAranc: String,
    val anio: Int,
    val valorM2: BigDecimal,
    val codVia: String? = null,
    val cuadra: String? = null,
    val lado: String? = null
) {
    // the attributes of a write: every field, an empty one null (core's update replaces them all)
    fun atributos(): Map<String, Any?> =
        mapOf(
            "clave" to clave,
            "id_aranc" to idAranc,
            "anio" to anio,
            "valor_m2" to valorM2,
            "cod_via" to codVia?.trim()?.ifEmpty { null },
            "cuadra" to cuadra?.trim()?.ifEmpty { null },
            "lado" to lado?.trim()?.ifEmpty { null }
        )
}

// what a copy did in catastro
enum class CopiaDeArancel { CREADO, ACTUALIZADO, IGUAL, BORRADO, NO_ESTABA }

// catastro could not answer: the corrida fails with this message (never the secret nor a token). estado is catastro's
// http status, when the failure was one (404 or 403 on /titulares is no failure: callers check it themselves)
class CatastroException(
    mensaje: String,
    val estado: Int? = null,
    // the body of catastro's refusal, cut short: for the log, never for the caller (#260)
    val detalle: String? = null
) : RuntimeException(mensaje)

// the catastros of the install, each its organization's (the key: its id or its slug). a corrida reads its own
// organization's only, never another's. repetidas: the organizations two entries of the map name, with their keys
class Catastros(
    porOrganizacion: Map<String, Catastro>,
    repetidas: Map<String, Collection<String>> = emptyMap()
) {
    private val registro = PorOrganizacion(porOrganizacion, repetidas, ::dosCatastros)

    val configurado: Boolean get() = registro.configurado

    // some key is a slug: the caller's organization has to be read for it
    val necesitaSlug: Boolean get() = registro.necesitaSlug

    // this organization's catastro, or null when it has none; named twice: 409
    fun de(
        id: UUID,
        slug: String?
    ): Catastro? = registro.de(id, slug)
}

// the caller's organization's catastro, or null when it has none (or the install none): its slug is read only when
// some key is a slug. named twice: 409 (Catastros.de). the brecha refuses a null, the autoavalúo sugerido and Normativa's
// copy skip it
suspend fun Catastros.delUsuario(
    usuario: AuthenticatedUser,
    organizaciones: OrganizationRepository
): Catastro? {
    if (!configurado) return null
    val slug = if (necesitaSlug) organizaciones.findById(usuario.organizationId)?.slug else null
    return de(usuario.organizationId, slug)
}

const val NO_CONFIGURADO =
    "Catastro no configurado: srtm.catastro necesita enabled y, por organización, url, client-id y client-secret " +
        "(SRTM_CATASTRO_ENABLED; SRTM_CATASTRO_ORGANIZACIONES_<ORGANIZACION>_URL, _CLIENT_ID y _CLIENT_SECRET)"

const val SIN_CATASTRO_CONFIGURADO =
    "Catastro no configurado para esta organización: fije srtm.catastro.organizaciones.<slug o id> con su url, " +
        "client-id y client-secret (SRTM_CATASTRO_ORGANIZACIONES_<ORGANIZACION>_URL, _CLIENT_ID y _CLIENT_SECRET)"

const val SIN_WMS =
    "WMS de catastro no configurado para esta organización: fije srtm.catastro.organizaciones.<slug o id>.wms-url " +
        "(SRTM_CATASTRO_ORGANIZACIONES_<ORGANIZACION>_WMS_URL)"

fun dosCatastros(claves: Collection<String>) =
    "Catastro configurado dos veces para esta organización (srtm.catastro.organizaciones: " +
        "${claves.sorted().joinToString(" y ")}): deje una sola"

// catastro-backend by rest, as its service account: POST {url}/api/auth/token {clientId, clientSecret} -> {token,
// expiresAt}, kept until a minute before it expires (and dropped on a 401), then GET {url}/api/catastro/valuaciones
// ?anio&page&size=500 page after page until total is read (or a page comes short). an item's lote is its code, and
// loteGeom its geometry (GeoJSON in EPSG:4326, or null). a service account that may not read any of catastro's four
// objects gets a 403 for the whole request: the corrida fails with it. valuar: POST {url}/api/catastro/valuacion with
// the insumos, as the same account. Normativa's copy of the aranceles (#189) writes catastro's object arancel by core's
// rest, {url}/api/objects/arancel/records, as the same account (catastro-backend#19 lets its role write it)
class ClienteCatastro(
    private val cuenta: CuentaCatastro,
    private val ahora: () -> Instant = Instant::now
) : Catastro {
    private val timeout = cuenta.timeout ?: TIMEOUT

    private val log = LoggerFactory.getLogger(javaClass)

    // own builder: boot 4 hands out no WebClient.Builder bean (PideReniec). a page of 500 valued items is well over
    // the default 256 KiB
    private val client =
        WebClient
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(TAMANIO_MAXIMO) }
            .build()

    private val servicio =
        TokenDeServicio(client, cuenta.url.trimEnd('/') + "/api/auth/token", cuenta.clientId, cuenta.clientSecret, timeout, ahora, "catastro-backend")

    override suspend fun valuaciones(
        anio: Int,
        porPagina: suspend () -> Unit
    ): Valuaciones {
        val items = mutableListOf<ValuacionCatastral>()
        var version: String? = null
        var page = 0
        var anterior: JsonNode? = null
        while (true) {
            val pagina = pagina(anio, page)
            version = version ?: pagina.path("version").texto()
            val suyos =
                pagina
                    .path("items")
                    .iterator()
                    .asSequence()
                    .map(::item)
                    .toList()
            items += suyos
            porPagina()
            val total = pagina.path("total").takeIf { it.isNumber }?.asLong() ?: items.size.toLong()
            // the size catastro served (it may cap the one asked)
            val size = pagina.path("size").takeIf { it.isNumber }?.asInt() ?: TAMANIO_PAGINA
            if (items.size >= total) break
            avanza("las valuaciones de $anio", page, pagina, anterior, size)
            if (suyos.size < size) break
            anterior = pagina.path("items")
            page++
        }
        return Valuaciones(anio, version, items)
    }

    override suspend fun titulares(porPagina: suspend () -> Unit): List<TitularCatastral> {
        val items = mutableListOf<TitularCatastral>()
        var page = 0
        var anterior: JsonNode? = null
        while (true) {
            val pagina = paginaDeTitulares(page)
            val suyos =
                pagina
                    .path("items")
                    .iterator()
                    .asSequence()
                    .map(::titular)
                    .toList()
            items += suyos
            porPagina()
            val total = pagina.path("total").takeIf { it.isNumber }?.asLong() ?: items.size.toLong()
            val size = pagina.path("size").takeIf { it.isNumber }?.asInt() ?: TAMANIO_PAGINA
            if (items.size >= total) break
            avanza("los titulares", page, pagina, anterior, size)
            if (suyos.size < size) break
            anterior = pagina.path("items")
            page++
        }
        return items
    }

    // a page with items still pending that brings none, is served with size 0 or repeats the previous one would
    // loop for ever: fail instead
    private fun avanza(
        que: String,
        page: Int,
        pagina: JsonNode,
        anterior: JsonNode?,
        size: Int,
        items: JsonNode = pagina.path("items")
    ) {
        val motivo =
            when {
                size <= 0 -> "catastro sirvió páginas de tamaño $size"
                items.size() == 0 -> "la página vino vacía y faltan elementos"
                anterior != null && items == anterior -> "la página repite la anterior"
                else -> return
            }
        throw CatastroException("catastro no avanza al leer $que: página $page, $motivo")
    }

    // core's records of catastro's decision_brecha (SRTM_LECTOR reads it): GET /api/objects/decision_brecha/records
    // ?anio&sort=clave&page&size=200, core's page ({content: [{id, attributes}], totalElements, size}), every page
    override suspend fun decisiones(anio: Int): List<DecisionCatastral> {
        val decisiones = mutableListOf<DecisionCatastral>()
        var page = 0
        var anterior: JsonNode? = null
        while (true) {
            val pagina = paginaDeDecisiones(anio, page)
            val contenido = pagina.path("content")
            val suyas = contenido.values().map { decision(it.path("attributes")) }
            decisiones += suyas
            val total = pagina.path("totalElements").takeIf { it.isNumber }?.asLong() ?: decisiones.size.toLong()
            val size = pagina.path("size").takeIf { it.isNumber }?.asInt() ?: TAMANIO_REGISTROS
            if (decisiones.size >= total) break
            avanza("las decisiones de $anio", page, pagina, anterior, size, contenido)
            if (suyas.size < size) break
            anterior = contenido
            page++
        }
        return decisiones
    }

    private suspend fun paginaDeDecisiones(
        anio: Int,
        page: Int
    ): JsonNode {
        val uri =
            UriComponentsBuilder
                .fromUriString(cuenta.url.trimEnd('/'))
                .path(REGISTROS_DECISION)
                .queryParam("anio", anio)
                .queryParam("sort", "clave")
                .queryParam("page", page)
                .queryParam("size", TAMANIO_REGISTROS)
                .build()
                .toUri()
        val body =
            autorizado("las decisiones de brecha de $anio (página $page)") { token ->
                client
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String::class.java)
            }
        return DECIMALES.readTree(body.orEmpty())
    }

    override suspend fun cambios(
        desde: String?,
        size: Int
    ): PaginaDeCambios {
        val pagina = paginaDeCambios(desde, size)
        val items =
            pagina
                .path("items")
                .iterator()
                .asSequence()
                .map(::cambio)
                .toList()
        return PaginaDeCambios(
            items = items,
            cursor = pagina.path("cursor").texto(),
            hayMas = pagina.path("hayMas").booleano() ?: false
        )
    }

    override suspend fun valuar(insumos: InsumosValuacion): ValuacionDeInsumos {
        val uri = cuenta.url.trimEnd('/') + "/api/catastro/valuacion"
        val cuerpo = DECIMALES.writeValueAsString(insumos)
        val respuesta =
            autorizado("la valuación de los insumos de ${insumos.anio}") { token ->
                client
                    .post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(cuerpo)
                    .retrieve()
                    .bodyToMono(String::class.java)
            }
        return valuacion(DECIMALES.readTree(respuesta.orEmpty()))
    }

    override suspend fun guardarArancel(a: ArancelCatastral): CopiaDeArancel {
        val que = "del arancel ${a.clave}"
        val guardado = arancelDeCatastro(a.clave)
        val cuerpo = DECIMALES.writeValueAsString(mapOf("attributes" to a.atributos()))
        if (guardado == null) {
            autorizado("el alta $que") { token -> escribir(client.post().uri(registros()), token, cuerpo) }
            return CopiaDeArancel.CREADO
        }
        if (mismoArancel(a, guardado.path("attributes"))) return CopiaDeArancel.IGUAL
        val id = guardado.path("id").asString()
        autorizado("el cambio $que") { token -> escribir(client.put().uri(registros(id)), token, cuerpo) }
        return CopiaDeArancel.ACTUALIZADO
    }

    override suspend fun borrarArancel(clave: String): CopiaDeArancel {
        val guardado = arancelDeCatastro(clave) ?: return CopiaDeArancel.NO_ESTABA
        val id = guardado.path("id").asString()
        autorizado("el borrado del arancel $clave") { token ->
            client
                .delete()
                .uri(registros(id))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .bodyToMono(String::class.java)
        }
        return CopiaDeArancel.BORRADO
    }

    // catastro's record of the clave ({id, attributes}), or null: GET /api/objects/arancel/records?clave= (core's
    // equality filter; the clave is unique there)
    private suspend fun arancelDeCatastro(clave: String): JsonNode? {
        val uri =
            UriComponentsBuilder
                .fromUriString(cuenta.url.trimEnd('/'))
                .path(REGISTROS_ARANCEL)
                .queryParam("clave", "{clave}")
                .queryParam("size", 2)
                .encode()
                .buildAndExpand(clave)
                .toUri()
        val body =
            autorizado("la búsqueda del arancel $clave") { token ->
                client
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String::class.java)
            }
        return DECIMALES
            .readTree(body.orEmpty())
            .path("content")
            .values()
            .firstOrNull { it.path("attributes").path("clave").texto() == clave }
    }

    private fun registros(id: String? = null): String = cuenta.url.trimEnd('/') + REGISTROS_ARANCEL + (id?.let { "/$it" } ?: "")

    private fun escribir(
        pedido: WebClient.RequestBodySpec,
        token: String,
        cuerpo: String
    ): Mono<String> =
        pedido
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(cuerpo)
            .retrieve()
            .bodyToMono(String::class.java)

    // one page as its service account
    private suspend fun pagina(
        anio: Int,
        page: Int
    ): JsonNode {
        val uri =
            UriComponentsBuilder
                .fromUriString(cuenta.url.trimEnd('/'))
                .path("/api/catastro/valuaciones")
                .queryParam("anio", anio)
                .queryParam("page", page)
                .queryParam("size", TAMANIO_PAGINA)
                .build()
                .toUri()
        val body =
            autorizado("las valuaciones de $anio (página $page)") { token ->
                client
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String::class.java)
            }
        return DECIMALES.readTree(body.orEmpty())
    }

    // one page of titulares as its service account (#204)
    private suspend fun paginaDeTitulares(page: Int): JsonNode {
        val uri =
            UriComponentsBuilder
                .fromUriString(cuenta.url.trimEnd('/'))
                .path("/api/catastro/titulares")
                .queryParam("page", page)
                .queryParam("size", TAMANIO_PAGINA)
                .build()
                .toUri()
        val body =
            autorizado("los titulares (página $page)") { token ->
                client
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String::class.java)
            }
        return DECIMALES.readTree(body.orEmpty())
    }

    // one page of GET /api/catastro/cambios (#203): desde null reads from the start (no desde at all); desde is the
    // opaque cursor of a previous page, url-encoded exactly once (encode() before the template is expanded, never
    // after: that would encode it twice)
    private suspend fun paginaDeCambios(
        desde: String?,
        size: Int
    ): JsonNode {
        val builder =
            UriComponentsBuilder
                .fromUriString(cuenta.url.trimEnd('/'))
                .path("/api/catastro/cambios")
                .queryParam("size", size)
        val uri =
            if (desde == null) {
                builder.build().toUri()
            } else {
                builder
                    .queryParam("desde", "{desde}")
                    .encode()
                    .buildAndExpand(desde)
                    .toUri()
            }
        val body =
            autorizado("los cambios") { token ->
                client
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String::class.java)
            }
        return DECIMALES.readTree(body.orEmpty())
    }

    override suspend fun vias(anio: Int): String {
        val uri =
            UriComponentsBuilder
                .fromUriString(cuenta.url.trimEnd('/'))
                .path("/api/catastro/vias/aranceles")
                .queryParam("anio", anio)
                .build()
                .toUri()
        return autorizado("las vías con arancel de $anio") { token ->
            client
                .get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String::class.java)
        } ?: throw CatastroException("catastro-backend respondió sin cuerpo a las vías con arancel de $anio")
    }

    // read once: an account never moves to another organization
    @Volatile
    private var organizacionLeida: String? = null

    // GET /api/organizations/current as the account: {id, name, ...}
    override suspend fun organizacionCatastral(): String {
        organizacionLeida?.let { return it }
        val body =
            autorizado("la organización de la cuenta de servicio") { token ->
                client
                    .get()
                    .uri(cuenta.url.trimEnd('/') + "/api/organizations/current")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String::class.java)
            }
        val id =
            DECIMALES.readTree(body.orEmpty()).path("id").texto()
                ?: throw CatastroException("catastro-backend respondió la organización de la cuenta de servicio sin id")
        organizacionLeida = id
        return id
    }

    override val conWms: Boolean get() = cuenta.wmsUrl.isNotBlank()

    // each value url-encoded exactly once (encode() before the template is expanded). the image is not read here:
    // its bytes go to the caller as they arrive
    override suspend fun wms(parametros: List<Pair<String, String>>): ImagenWms {
        if (!conWms) throw CatastroException(SIN_WMS, 404)
        val builder = UriComponentsBuilder.fromUriString(cuenta.wmsUrl.trim())
        parametros.forEachIndexed { i, (nombre, _) -> builder.queryParam(nombre, "{v$i}") }
        val uri =
            builder
                .encode()
                .buildAndExpand(*parametros.map { it.second }.toTypedArray())
                .toUri()
        val respuesta =
            autorizado("la imagen WMS") { token ->
                client
                    .get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .toEntityFlux(DataBuffer::class.java)
            } ?: throw CatastroException("catastro no respondió la imagen WMS")
        return ImagenWms(respuesta.headers.contentType, (respuesta.body ?: Flux.empty()).timeout(timeout))
    }

    // a request as the service account; a 401 means the cached token is no longer good: once more with a new one.
    // que names it in the error, and a refusal's body goes in its detalle
    private suspend fun <T : Any> autorizado(
        que: String,
        pedido: (String) -> Mono<T>
    ): T? {
        repeat(2) { intento ->
            try {
                return pedido(token()).timeout(timeout).awaitSingleOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatastroException) {
                throw e
            } catch (e: WebClientResponseException) {
                if (e.statusCode == HttpStatus.UNAUTHORIZED && intento == 0) {
                    servicio.olvidar()
                } else {
                    throw CatastroException(
                        "catastro-backend respondió ${e.statusCode.value()} ${a(que)}",
                        e.statusCode.value(),
                        e.responseBodyAsString.take(DETALLE).ifEmpty { null }
                    )
                }
            } catch (e: Exception) {
                throw CatastroException("catastro-backend no respondió $que: ${e.javaClass.simpleName}")
            }
        }
        throw CatastroException("catastro-backend rechazó el token de la cuenta de servicio")
    }

    // "a" before what was asked, as Spanish contracts it: al alta, a las valuaciones
    private fun a(que: String): String = if (que.startsWith("el ")) "al ${que.removePrefix("el ")}" else "a $que"

    // the cached token while it has more than a minute left; else a new one
    private suspend fun token(): String =
        try {
            servicio.token()
        } catch (e: TokenDeServicio.TokenException) {
            throw CatastroException(e.message.orEmpty())
        }

    companion object {
        const val TAMANIO_PAGINA = 500

        // how much of a refusal's body CatastroException.detalle keeps
        private const val DETALLE = 300
        private const val REGISTROS_ARANCEL = "/api/objects/arancel/records"
        private const val REGISTROS_DECISION = "/api/objects/decision_brecha/records"

        // core's records list serves pages of 200 at most (PageRequest.MAX_SIZE)
        const val TAMANIO_REGISTROS = 200
        val TIMEOUT: Duration = Duration.ofSeconds(60)
        private const val TAMANIO_MAXIMO = 32 * 1024 * 1024
        private val JSON = JsonMapper.builder().build()

        // the items' figures as decimals, never through a double (a 17-digit autoavalúo would lose its céntimos)
        private val DECIMALES = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

        @Suppress("UNCHECKED_CAST")
        fun item(n: JsonNode): ValuacionCatastral {
            // the geometry's coordinates as doubles, as wasichai-gis takes them: read again by the plain mapper
            val geometria = n.path("loteGeom").takeIf { it.isObject }?.let { JSON.readValue(it.toString(), Map::class.java) as Map<String, Any?> }
            return ValuacionCatastral(
                codigoMunicipal = n.path("codigoMunicipal").texto(),
                codigoCpu = n.path("codigoCpu").texto(),
                sector = n.path("sector").texto(),
                manzana = n.path("manzana").texto(),
                lote = n.path("lote").texto(),
                uso = n.path("uso").texto(),
                areaTerreno = n.path("areaTerreno").decimal(),
                areaConstruida = n.path("areaConstruida").decimal(),
                longitudFrente = n.path("longitudFrente").decimal(),
                terreno = n.path("terreno").decimal(),
                construccion = n.path("construccion").decimal(),
                obras = n.path("obras").decimal(),
                autoavaluo = n.path("autoavaluo").decimal(),
                motivo = n.path("motivo").texto(),
                llave = n.path("llave").texto(),
                loteGeom = geometria,
                estadoLevantamiento = n.path("estadoLevantamiento").texto(),
                fechaLevantamiento = n.path("fechaLevantamiento").texto(),
                entrega = n.path("entrega").texto(),
                vinculoMetodo = n.path("vinculoMetodo").texto(),
                vinculoRevisado = n.path("vinculoRevisado").booleano(),
                cuc = n.path("cuc").texto(),
                codigoReferenciaCatastral = n.path("codigoReferenciaCatastral").texto(),
                construcciones =
                    n
                        .path("construcciones")
                        .takeIf { it.isArray }
                        ?.values()
                        ?.map(::construccion)
            )
        }

        // #247: one construcción of an item (catastro-backend#71)
        private fun construccion(n: JsonNode) =
            ConstruccionCatastral(
                piso = n.path("piso").entero(),
                seccion = n.path("seccion").entero(),
                areaConstruida = n.path("areaConstruida").decimal()
            )

        // the attributes of a decision_brecha record (#248): its anio an INTEGER, or its text
        fun decision(n: JsonNode) =
            DecisionCatastral(
                codigoMunicipal = n.path("codigo_municipal").texto(),
                anio = n.path("anio").entero() ?: n.path("anio").texto()?.toIntOrNull(),
                estado = n.path("estado").texto()
            )

        // one item of GET /api/catastro/titulares (#204)
        fun titular(n: JsonNode) =
            TitularCatastral(
                codigoMunicipal = n.path("codigoMunicipal").texto(),
                tipoDocumento = n.path("tipoDocumento").texto(),
                numeroDocumento = n.path("numeroDocumento").texto(),
                nombre = n.path("nombre").texto(),
                porcentaje = n.path("porcentaje").decimal(),
                condicion = n.path("condicion").texto()
            )

        // one item of GET /api/catastro/cambios (#203)
        fun cambio(n: JsonNode) =
            CambioCatastral(
                codigoMunicipal = n.path("codigoMunicipal").texto(),
                objeto = n.path("objeto").texto(),
                operacion = n.path("operacion").texto(),
                campos = n.path("campos").values().mapNotNull { it.texto() },
                en = n.path("en").texto(),
                entrega = n.path("entrega").texto()
            )

        // catastro's arancel says what a does: a number by its value, an empty text as null
        fun mismoArancel(
            a: ArancelCatastral,
            guardado: JsonNode
        ): Boolean {
            val esperado = a.atributos()
            return guardado.path("id_aranc").texto() == a.idAranc &&
                guardado.path("anio").let { it.isNumber && it.asLong() == a.anio.toLong() || it.texto() == a.anio.toString() } &&
                guardado.path("valor_m2").decimal()?.compareTo(a.valorM2) == 0 &&
                listOf("cod_via", "cuadra", "lado").all { guardado.path(it).texto() == esperado[it] }
        }

        // POST /valuacion's answer (catastro-backend#17): every key is there, null when not valued
        fun valuacion(n: JsonNode): ValuacionDeInsumos {
            val detalle = n.path("detalle").takeIf { it.isObject }
            return ValuacionDeInsumos(
                anio = n.path("anio").entero(),
                version = n.path("version").texto(),
                region = n.path("region").texto(),
                codigoMunicipal = n.path("codigoMunicipal").texto(),
                arancelM2 = n.path("arancelM2").decimal(),
                origenArancel = n.path("origenArancel").texto(),
                terreno = n.path("terreno").decimal(),
                construccion = n.path("construccion").decimal(),
                obras = n.path("obras").decimal(),
                autoavaluo = n.path("autoavaluo").decimal(),
                motivo = n.path("motivo").texto(),
                llave = n.path("llave").texto(),
                detalle =
                    detalle?.let { d ->
                        DetalleValuacion(
                            pisos = d.path("pisos").values().map(::detallePiso),
                            obras = d.path("obras").values().map(::detalleObra)
                        )
                    }
            )
        }

        private fun detallePiso(p: JsonNode) =
            DetallePisoValuacion(
                piso = p.path("piso").texto(),
                seccion = p.path("seccion").entero(),
                areaConstruida = p.path("areaConstruida").decimal(),
                valorUnitario = p.path("valorUnitario").decimal(),
                incremento = p.path("incremento").decimal(),
                tablaDepreciacion = p.path("tablaDepreciacion").texto(),
                antiguedad = p.path("antiguedad").entero(),
                porcentajeDepreciacion = p.path("porcentajeDepreciacion").decimal(),
                valorUnitarioDepreciado = p.path("valorUnitarioDepreciado").decimal(),
                valor = p.path("valor").decimal()
            )

        private fun detalleObra(o: JsonNode) =
            DetalleObraValuacion(
                codigoVu = o.path("codigoVu").texto(),
                cantidad = o.path("cantidad").decimal(),
                valorUnitario = o.path("valorUnitario").decimal(),
                factorOficializacion = o.path("factorOficializacion").decimal(),
                tablaDepreciacion = o.path("tablaDepreciacion").texto(),
                antiguedad = o.path("antiguedad").entero(),
                porcentajeDepreciacion = o.path("porcentajeDepreciacion").decimal(),
                valor = o.path("valor").decimal()
            )

        private fun JsonNode.entero(): Int? = takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()

        private fun JsonNode.texto(): String? =
            takeIf { it.isValueNode && !it.isNull }
                ?.asString()
                ?.trim()
                ?.ifEmpty { null }

        private fun JsonNode.decimal(): BigDecimal? =
            when {
                isNumber -> decimalValue()
                isString -> asString().trim().toBigDecimalOrNull()
                else -> null
            }

        private fun JsonNode.booleano(): Boolean? = takeIf { isBoolean }?.booleanValue()
    }
}

// a client per organization whose url and service account are set; none when off (no corrida starts)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatastroProperties::class)
class CatastroConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun catastros(properties: CatastroProperties): Catastros {
        if (!properties.enabled) return Catastros(emptyMap())
        if (properties.organizacion.isBlank() && properties.clientId.isNotBlank()) {
            log.warn("srtm.catastro.client-id sin organizacion: esa cuenta no es de ninguna organización y no se usa")
        }
        val repetidas = properties.repetidas()
        for ((organizacion, claves) in repetidas) {
            log.warn("srtm.catastro.organizaciones: {} nombra dos veces a {}: esa organización no corre la brecha", claves.joinToString(" y "), organizacion)
        }
        val clientes = linkedMapOf<String, Catastro>()
        for ((organizacion, cuenta) in properties.cuentas() - repetidas.keys) {
            val faltan = cuenta.faltan
            if (faltan.isEmpty()) {
                clientes[organizacion] = ClienteCatastro(cuenta)
            } else {
                log.warn("srtm.catastro: el catastro de {} no tiene {}: esa organización no corre la brecha", organizacion, faltan.joinToString(", "))
            }
        }
        if (clientes.isEmpty() && repetidas.isEmpty()) {
            log.warn("srtm.catastro.enabled sin ninguna organización con url, client-id y client-secret: no se corre la brecha")
        } else {
            log.info("srtm.catastro: catastro de {}", clientes.keys.joinToString(", "))
        }
        return Catastros(clientes, repetidas)
    }
}
