package srtm.caja

import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import srtm.PorOrganizacion
import srtm.TokenDeServicio
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// srtm's access to caja-backend: a caja per organization of srtm, each its url (caja's root) and the service account
// rentas that caja's admin created. off unless one entry is complete; the secrets come from the environment
// (application.yml). organizaciones is keyed by the organization (its slug or its id), or by any name when the
// entry's organizacion says which: an environment variable cannot carry an id's dashes
@ConfigurationProperties(prefix = "srtm.caja")
data class CajaProperties(
    // how often the worker looks for the ordenes to send, how many it takes, and how many tries one gets before ERROR
    val intervalo: Duration = Duration.ofSeconds(30),
    val lote: Int = 50,
    val intentos: Int = 20,
    // how long a write of srtm waits for caja before leaving the orden to the worker
    val plazoEnLinea: Duration = Duration.ofSeconds(5),
    // how long an orden caja did not answer waits before its next try, doubled on each one (up to an hour)
    val esperaReintento: Duration = Duration.ofSeconds(30),
    val organizaciones: Map<String, CuentaCaja> = emptyMap()
) {
    // every account by its organization (trimmed, lowercase: a slug matches without case, an id parses either way)
    fun cuentas(): Map<String, CuentaCaja> {
        val todas = linkedMapOf<String, CuentaCaja>()
        for ((clave, cuenta) in organizaciones) {
            val suya = cuenta.organizacion.trim().ifEmpty { clave.trim() }
            todas[suya.lowercase()] = cuenta.copy(organizacion = suya)
        }
        return todas
    }

    // the organizations two entries name (by their organizacion or their key), with those keys: neither is read
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
        "CajaProperties(intervalo=$intervalo, lote=$lote, intentos=$intentos, plazoEnLinea=$plazoEnLinea, esperaReintento=$esperaReintento, organizaciones=$organizaciones)"
}

// one organization's caja-backend and its service account
data class CuentaCaja(
    val organizacion: String = "",
    val url: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val timeout: Duration? = null
) {
    // what it lacks to be read
    val faltan: List<String>
        get() = listOf("url" to url, "client-id" to clientId, "client-secret" to clientSecret).filter { it.second.isBlank() }.map { it.first }

    override fun toString() = "CuentaCaja(organizacion=$organizacion, url=$url, clientId=$clientId, timeout=$timeout)"
}

// an orden de cobro as caja takes it: snake_case keys, every value a string, an absent optional left out
data class AltaDeOrden(
    val referencia: String,
    val concepto: String,
    val detalle: String? = null,
    val importe: BigDecimal,
    val fechaExigibilidad: LocalDate,
    val actualizadoA: LocalDate,
    val pagadorDocumento: String? = null,
    val pagadorNombre: String? = null,
    val observacion: String,
    val sustituyeA: String? = null
) {
    // pagador_externo_id is never sent, nor a key with nothing in it (caja answers 400 to any other key)
    fun cuerpo(): Map<String, String> {
        val cuerpo = linkedMapOf<String, String>()
        cuerpo["referencia_externa"] = referencia
        cuerpo["concepto"] = concepto
        detalle?.takeIf { it.isNotBlank() }?.let { cuerpo["detalle"] = it }
        cuerpo["importe"] = importe.setScale(2, RoundingMode.HALF_UP).toPlainString()
        cuerpo["fecha_exigibilidad"] = fechaExigibilidad.toString()
        cuerpo["actualizado_a"] = actualizadoA.toString()
        pagadorDocumento?.takeIf { it.isNotBlank() }?.let { cuerpo["pagador_documento"] = it }
        pagadorNombre?.takeIf { it.isNotBlank() }?.let { cuerpo["pagador_nombre"] = it }
        cuerpo["observacion"] = observacion
        sustituyeA?.takeIf { it.isNotBlank() }?.let { cuerpo["sustituye_a"] = it }
        return cuerpo
    }
}

// what caja answered. never thrown: the worker decides what each one means for the orden
sealed interface ResultadoCaja {
    // 200 and 201: the orden is in caja (ordenId) in this estado
    data class Hecho(
        val ordenId: String,
        val estado: String
    ) : ResultadoCaja

    // 409: caja holds it (other debt, a resend of an ANULADA, a PAGADA); detalle is caja's own
    data class Conflicto(
        val detalle: String
    ) : ResultadoCaja

    // 404: caja knows no such orden
    data object NoExiste : ResultadoCaja

    // 400 and 403: caja will not take it as sent
    data class Rechazo(
        val detalle: String
    ) : ResultadoCaja

    // 5xx, timeout, io, a token that cannot be had, a 401 twice: try again later
    data class NoContesta(
        val causa: String
    ) : ResultadoCaja
}

// the orden de cobro of an organization's caja
interface Caja {
    suspend fun alta(orden: AltaDeOrden): ResultadoCaja

    // the orden of this referencia is withdrawn (ANULADA); motivo is one line of 1 to 200 characters
    suspend fun retiro(
        referencia: String,
        motivo: String
    ): ResultadoCaja
}

// the cajas of the install, each its organization's (the key: its id or its slug). repetidas: the organizations two
// entries name, with their keys
class Cajas(
    porOrganizacion: Map<String, Caja>,
    repetidas: Map<String, Collection<String>> = emptyMap()
) {
    private val registro = PorOrganizacion(porOrganizacion, repetidas, ::dosCajas)

    val configurado: Boolean get() = registro.configurado

    // some key is a slug: the caller's organization has to be read for it
    val necesitaSlug: Boolean get() = registro.necesitaSlug

    fun organizaciones(): Set<String> = registro.claves()

    // this organization's caja, or null when it has none; named twice: 409
    fun de(
        id: UUID,
        slug: String?
    ): Caja? = registro.de(id, slug)
}

const val SIN_CAJA_CONFIGURADA = "caja no está configurada para esta municipalidad"

fun dosCajas(claves: Collection<String>) =
    "Caja configurada dos veces para esta organización (srtm.caja.organizaciones: " +
        "${claves.sorted().joinToString(" y ")}): deje una sola"

// caja-backend by rest, as its service account: POST {url}/api/auth/token {clientId, clientSecret} -> {token,
// expiresAt}, then POST {url}/api/caja/ordenes-de-cobro (alta) and .../retiro with the Bearer. a 401 drops the token
// and tries once more; a second one is NoContesta
class ClienteCaja(
    private val cuenta: CuentaCaja,
    ahora: () -> Instant = Instant::now
) : Caja {
    private val timeout = cuenta.timeout ?: TIMEOUT
    private val log = LoggerFactory.getLogger(javaClass)
    private val raiz = cuenta.url.trimEnd('/')

    // own builder: boot 4 hands out no WebClient.Builder bean
    private val client = WebClient.builder().build()
    private val servicio = TokenDeServicio(client, "$raiz/api/auth/token", cuenta.clientId, cuenta.clientSecret, timeout, ahora, "caja-backend")

    override suspend fun alta(orden: AltaDeOrden): ResultadoCaja = enviar("$raiz/api/caja/ordenes-de-cobro", orden.cuerpo())

    override suspend fun retiro(
        referencia: String,
        motivo: String
    ): ResultadoCaja = enviar("$raiz/api/caja/ordenes-de-cobro/retiro", mapOf("referencia_externa" to referencia, "motivo" to motivo))

    private suspend fun enviar(
        uri: String,
        cuerpo: Map<String, String>
    ): ResultadoCaja {
        val json = JSON.writeValueAsString(cuerpo)
        repeat(2) { intento ->
            val token =
                try {
                    servicio.token()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TokenDeServicio.TokenException) {
                    return ResultadoCaja.NoContesta(e.message.orEmpty())
                } catch (e: Exception) {
                    return ResultadoCaja.NoContesta("caja-backend no dio el token: ${e.javaClass.simpleName}")
                }
            val (estado, texto) =
                try {
                    client
                        .post()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                        .bodyValue(json)
                        .exchangeToMono { r -> r.bodyToMono(String::class.java).defaultIfEmpty("").map { r.statusCode().value() to it } }
                        .timeout(timeout)
                        .awaitSingle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the class only: never the request, which names a contribuyente
                    log.warn("caja {}: no contestó ({})", cuenta.organizacion, e.javaClass.simpleName)
                    return ResultadoCaja.NoContesta("caja-backend no respondió: ${e.javaClass.simpleName}")
                }
            if (estado == 401) {
                if (intento == 0) {
                    servicio.olvidar()
                    return@repeat
                }
                return ResultadoCaja.NoContesta("caja-backend rechazó el token de la cuenta de servicio")
            }
            return resultado(estado, texto)
        }
        return ResultadoCaja.NoContesta("caja-backend rechazó el token de la cuenta de servicio")
    }

    private fun resultado(
        estado: Int,
        texto: String
    ): ResultadoCaja {
        val nodo = runCatching { JSON.readTree(texto) }.getOrNull()

        fun campo(nombre: String) =
            nodo
                ?.path(nombre)
                ?.takeIf { it.isValueNode && !it.isNull }
                ?.asString()
                ?.trim()
                ?.ifEmpty { null }
        val detalle = campo("detail") ?: "caja-backend respondió $estado"
        return when {
            estado == 200 || estado == 201 -> {
                val ordenId = campo("orden_id")
                if (ordenId == null) {
                    ResultadoCaja.NoContesta("caja-backend respondió $estado sin orden_id")
                } else {
                    ResultadoCaja.Hecho(ordenId, campo("estado").orEmpty())
                }
            }
            estado == 409 -> ResultadoCaja.Conflicto(detalle)
            estado == 404 -> ResultadoCaja.NoExiste
            estado >= 500 -> ResultadoCaja.NoContesta("caja-backend respondió $estado")
            else -> ResultadoCaja.Rechazo(detalle)
        }
    }

    companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
        private val JSON = JsonMapper.builder().build()
    }
}

// a client per organization whose url and service account are set; none when no entry is complete (caja is off)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CajaProperties::class)
class CajaConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun cajas(properties: CajaProperties): Cajas {
        val repetidas = properties.repetidas()
        for ((organizacion, claves) in repetidas) {
            log.warn("srtm.caja.organizaciones: {} nombra dos veces a {}: esa organización no usa caja", claves.joinToString(" y "), organizacion)
        }
        val clientes = linkedMapOf<String, Caja>()
        for ((organizacion, cuenta) in properties.cuentas() - repetidas.keys) {
            val faltan = cuenta.faltan
            if (faltan.isEmpty()) {
                clientes[organizacion] = ClienteCaja(cuenta)
            } else {
                log.warn("srtm.caja: la caja de {} no tiene {}: esa organización no usa caja", organizacion, faltan.joinToString(", "))
            }
        }
        if (clientes.isNotEmpty()) log.info("srtm.caja: caja de {}", clientes.keys.joinToString(", "))
        return Cajas(clientes, repetidas)
    }
}
