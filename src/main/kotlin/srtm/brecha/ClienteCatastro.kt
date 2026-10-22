package srtm.brecha

import kotlinx.coroutines.reactor.awaitSingleOrNull
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
import reactor.core.publisher.Mono
import srtm.PorOrganizacion
import srtm.TokenDeServicio
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
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
    val organizaciones: Map<String, CuentaCatastro> = emptyMap()
) {
    // every account by its organization (trimmed, lowercase: a slug matches without case, an id parses either way):
    // the single properties' when organizacion is set, then the map's, which replaces it for the same organization
    fun cuentas(): Map<String, CuentaCatastro> {
        val todas = linkedMapOf<String, CuentaCatastro>()
        val unica = organizacion.trim()
        if (unica.isNotEmpty()) todas[unica.lowercase()] = CuentaCatastro(unica, url, clientId, clientSecret, timeout)
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
            "organizaciones=$organizaciones)"
}

// one organization's catastro-backend and its service account
data class CuentaCatastro(
    val organizacion: String = "",
    val url: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val timeout: Duration? = null
) {
    // what it lacks to be read
    val faltan: List<String>
        get() = listOf("url" to url, "client-id" to clientId, "client-secret" to clientSecret).filter { it.second.isBlank() }.map { it.first }

    override fun toString() = "CuentaCatastro(organizacion=$organizacion, url=$url, clientId=$clientId, timeout=$timeout)"
}

// catastro-backend's valuations of a year, every page of them, and its engine on insumos that are not in it
interface Catastro {
    // porPagina runs after each page is read: the corrida beats its latido there
    suspend fun valuaciones(
        anio: Int,
        porPagina: suspend () -> Unit = {}
    ): Valuaciones

    // a declaración's autoavalúo sugerido (#149): nothing is stored on either side
    suspend fun valuar(insumos: InsumosValuacion): ValuacionDeInsumos
}

// catastro could not answer: the corrida fails with this message (never the secret nor a token)
class CatastroException(
    mensaje: String
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

const val NO_CONFIGURADO =
    "Catastro no configurado: srtm.catastro necesita enabled y, por organización, url, client-id y client-secret " +
        "(SRTM_CATASTRO_ENABLED; SRTM_CATASTRO_ORGANIZACIONES_<ORGANIZACION>_URL, _CLIENT_ID y _CLIENT_SECRET)"

const val SIN_CATASTRO_CONFIGURADO =
    "Catastro no configurado para esta organización: fije srtm.catastro.organizaciones.<slug o id> con su url, " +
        "client-id y client-secret (SRTM_CATASTRO_ORGANIZACIONES_<ORGANIZACION>_URL, _CLIENT_ID y _CLIENT_SECRET)"

fun dosCatastros(claves: Collection<String>) =
    "Catastro configurado dos veces para esta organización (srtm.catastro.organizaciones: " +
        "${claves.sorted().joinToString(" y ")}): deje una sola"

// catastro-backend by rest, as its service account: POST {url}/api/auth/token {clientId, clientSecret} -> {token,
// expiresAt}, kept until a minute before it expires (and dropped on a 401), then GET {url}/api/catastro/valuaciones
// ?anio&page&size=500 page after page until total is read (or a page comes short). an item's lote is its code, and
// loteGeom its geometry (GeoJSON in EPSG:4326, or null). a service account that may not read any of catastro's four
// objects gets a 403 for the whole request: the corrida fails with it. valuar: POST {url}/api/catastro/valuacion with
// the insumos, as the same account
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

    // a request as the service account; a 401 means the cached token is no longer good: once more with a new one.
    // que names it in the error
    private suspend fun autorizado(
        que: String,
        pedido: (String) -> Mono<String>
    ): String? {
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
                    throw CatastroException("catastro-backend respondió ${e.statusCode.value()} a $que")
                }
            } catch (e: Exception) {
                throw CatastroException("catastro-backend no respondió $que: ${e.javaClass.simpleName}")
            }
        }
        throw CatastroException("catastro-backend rechazó el token de la cuenta de servicio")
    }

    // the cached token while it has more than a minute left; else a new one
    private suspend fun token(): String =
        try {
            servicio.token()
        } catch (e: TokenDeServicio.TokenException) {
            throw CatastroException(e.message.orEmpty())
        }

    companion object {
        const val TAMANIO_PAGINA = 500
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
                loteGeom = geometria
            )
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
