package srtm

import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

// the token of a service account of another backend: POST {tokenUrl} {clientId, clientSecret} -> {token, expiresAt},
// kept until a minute before it expires. olvidar() drops it (a 401 means it is no longer good). nombre is how the
// other backend is called in the messages (catastro-backend, caja-backend)
class TokenDeServicio(
    private val client: WebClient,
    private val tokenUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val timeout: Duration,
    private val ahora: () -> Instant = Instant::now,
    private val nombre: String = "servicio"
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val candado = Mutex()
    private var token: String? = null
    private var vence: Instant = Instant.MIN

    // the cached token while it has more than a minute left; else a new one
    suspend fun token(): String =
        candado.withLock {
            val vigente = token
            if (vigente != null && ahora() < vence.minus(MARGEN)) return@withLock vigente
            val respuesta =
                try {
                    client
                        .post()
                        .uri(tokenUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(mapOf("clientId" to clientId, "clientSecret" to clientSecret))
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(timeout)
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
                    log.warn("{}: no se obtuvo el token de la cuenta de servicio {}{}", nombre, clientId, estado)
                    throw TokenException("$nombre no dio el token de la cuenta de servicio $clientId$estado")
                }
            val nodo =
                try {
                    JSON.readTree(respuesta.orEmpty())
                } catch (e: Exception) {
                    throw TokenException("$nombre respondió el token con un cuerpo que no es JSON")
                }
            val nuevo =
                nodo
                    .path("token")
                    .takeIf { it.isValueNode && !it.isNull }
                    ?.asString()
                    ?.trim()
                    ?.ifEmpty { null }
                    ?: throw TokenException("$nombre respondió el token sin token")
            token = nuevo
            vence =
                nodo
                    .path("expiresAt")
                    .takeIf { it.isValueNode && !it.isNull }
                    ?.asString()
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: ahora()
            nuevo
        }

    suspend fun olvidar() = candado.withLock { token = null }

    // no token to be had: the message never carries the secret
    class TokenException(
        mensaje: String
    ) : RuntimeException(mensaje)

    companion object {
        private val MARGEN: Duration = Duration.ofSeconds(60)
        private val JSON = JsonMapper.builder().build()
    }
}
