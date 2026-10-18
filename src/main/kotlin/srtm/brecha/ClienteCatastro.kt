package srtm.brecha

import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

// srtm's access to catastro-backend: its url and the service account that catastro's model/crear_cuenta_srtm.py
// created (role SRTM_LECTOR). off by default; the secret comes from the environment (application.yml, README).
// organizacion is the srtm organization (its slug or its id) whose catastro that is: the configuration is the
// install's, and only that organization's corridas may read it
@ConfigurationProperties(prefix = "srtm.catastro")
data class CatastroProperties(
    val enabled: Boolean = false,
    val url: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    // each request: catastro values a page on the fly
    val timeout: Duration = Duration.ofSeconds(60),
    val organizacion: String = ""
) {
    val completa: Boolean get() = listOf(url, clientId, clientSecret).none { it.isBlank() }

    // never the secret: this text may end up in a log
    override fun toString() = "CatastroProperties(enabled=$enabled, url=$url, clientId=$clientId, timeout=$timeout, organizacion=$organizacion)"
}

// catastro-backend's valuations of a year, every page of them
interface Catastro {
    val configurado: Boolean

    // porPagina runs after each page is read: the corrida beats its latido there
    suspend fun valuaciones(
        anio: Int,
        porPagina: suspend () -> Unit = {}
    ): Valuaciones
}

// catastro could not answer: the corrida fails with this message (never the secret nor a token)
class CatastroException(
    mensaje: String
) : RuntimeException(mensaje)

// without the configuration: no corrida starts (BrechaService answers 409 before)
class SinCatastro : Catastro {
    override val configurado = false

    override suspend fun valuaciones(
        anio: Int,
        porPagina: suspend () -> Unit
    ): Valuaciones = throw CatastroException(NO_CONFIGURADO)
}

const val NO_CONFIGURADO =
    "Catastro no configurado: srtm.catastro necesita enabled, url, client-id y client-secret " +
        "(SRTM_CATASTRO_ENABLED, SRTM_CATASTRO_URL, SRTM_CATASTRO_CLIENT_ID, SRTM_CATASTRO_CLIENT_SECRET)"

const val SIN_ORGANIZACION =
    "Catastro sin organización: fije srtm.catastro.organizacion (SRTM_CATASTRO_ORGANIZACION) con el slug o el id de la " +
        "organización de srtm cuyo catastro es"

const val DE_OTRA_ORGANIZACION = "Catastro no configurado para esta organización: srtm.catastro.organizacion es otra"

// why a corrida of this organization may not read the configured catastro, or null when it may: configurada is
// srtm.catastro.organizacion, an id or a slug (slug: this organization's, when configurada is not an id)
fun otraOrganizacion(
    configurada: String,
    id: java.util.UUID,
    slug: String?
): String? {
    val valor = configurada.trim()
    if (valor.isEmpty()) return SIN_ORGANIZACION
    val comoId = runCatching { java.util.UUID.fromString(valor) }.getOrNull()
    val es = if (comoId != null) comoId == id else slug != null && slug.equals(valor, ignoreCase = true)
    return if (es) null else DE_OTRA_ORGANIZACION
}

// catastro-backend by rest, as its service account: POST {url}/api/auth/token {clientId, clientSecret} -> {token,
// expiresAt}, kept until a minute before it expires (and dropped on a 401), then GET {url}/api/catastro/valuaciones
// ?anio&page&size=500 page after page until total is read (or a page comes short). an item's lote is its code, and
// loteGeom its geometry (GeoJSON in EPSG:4326, or null). a service account that may not read any of catastro's four
// objects gets a 403 for the whole request: the corrida fails with it
class ClienteCatastro(
    private val properties: CatastroProperties,
    private val ahora: () -> Instant = Instant::now
) : Catastro {
    private val log = LoggerFactory.getLogger(javaClass)

    // own builder: boot 4 hands out no WebClient.Builder bean (PideReniec). a page of 500 valued items is well over
    // the default 256 KiB
    private val client =
        WebClient
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(TAMANIO_MAXIMO) }
            .build()

    private val candado = Mutex()
    private var token: String? = null
    private var vence: Instant = Instant.MIN

    override val configurado = true

    override suspend fun valuaciones(
        anio: Int,
        porPagina: suspend () -> Unit
    ): Valuaciones {
        val items = mutableListOf<ValuacionCatastral>()
        var version: String? = null
        var page = 0
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
            if (suyos.size < size || items.size >= total) break
            page++
        }
        return Valuaciones(anio, version, items)
    }

    // one page as its service account; a 401 means the cached token is no longer good: once more with a new one
    private suspend fun pagina(
        anio: Int,
        page: Int
    ): JsonNode {
        val uri =
            UriComponentsBuilder
                .fromUriString(properties.url.trimEnd('/'))
                .path("/api/catastro/valuaciones")
                .queryParam("anio", anio)
                .queryParam("page", page)
                .queryParam("size", TAMANIO_PAGINA)
                .build()
                .toUri()
        repeat(2) { intento ->
            try {
                val body =
                    client
                        .get()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token()}")
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(properties.timeout)
                        .awaitSingleOrNull()
                return DECIMALES.readTree(body.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatastroException) {
                throw e
            } catch (e: WebClientResponseException) {
                if (e.statusCode == HttpStatus.UNAUTHORIZED && intento == 0) {
                    candado.withLock { token = null }
                } else {
                    throw CatastroException("catastro-backend respondió ${e.statusCode.value()} a las valuaciones de $anio (página $page)")
                }
            } catch (e: Exception) {
                throw CatastroException("catastro-backend no respondió las valuaciones de $anio (página $page): ${e.javaClass.simpleName}")
            }
        }
        throw CatastroException("catastro-backend rechazó el token de la cuenta de servicio")
    }

    // the cached token while it has more than a minute left; else a new one
    private suspend fun token(): String =
        candado.withLock {
            val vigente = token
            if (vigente != null && ahora() < vence.minus(MARGEN)) return@withLock vigente
            val respuesta =
                try {
                    client
                        .post()
                        .uri(properties.url.trimEnd('/') + "/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(mapOf("clientId" to properties.clientId, "clientSecret" to properties.clientSecret))
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(properties.timeout)
                        .awaitSingleOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the reason only: the request carries the secret
                    val estado =
                        (e as? WebClientResponseException)
                            ?.statusCode
                            ?.value()
                            ?.let { " ($it)" }
                            .orEmpty()
                    log.warn("catastro: no se obtuvo el token de la cuenta de servicio {}{}", properties.clientId, estado)
                    throw CatastroException("catastro-backend no dio el token de la cuenta de servicio ${properties.clientId}$estado")
                }
            val nodo = JSON.readTree(respuesta.orEmpty())
            val nuevo = nodo.path("token").texto() ?: throw CatastroException("catastro-backend respondió el token sin token")
            token = nuevo
            vence = nodo.path("expiresAt").texto()?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: ahora()
            nuevo
        }

    companion object {
        const val TAMANIO_PAGINA = 500
        private const val TAMANIO_MAXIMO = 32 * 1024 * 1024
        private val MARGEN: Duration = Duration.ofSeconds(60)
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
                loteGeom = geometria
            )
        }

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
    }
}

// catastro-backend when its url and service account are configured, no corrida otherwise
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatastroProperties::class)
class CatastroConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun catastro(properties: CatastroProperties): Catastro {
        if (!properties.enabled) return SinCatastro()
        if (!properties.completa) {
            log.warn("srtm.catastro.enabled sin url, client-id o client-secret: no se corre la brecha")
            return SinCatastro()
        }
        if (properties.organizacion.isBlank()) {
            log.warn("srtm.catastro.enabled sin organizacion: ninguna corrida de la brecha empieza hasta fijarla")
        }
        return ClienteCatastro(properties)
    }
}
