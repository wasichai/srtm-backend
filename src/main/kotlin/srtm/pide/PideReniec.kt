package srtm.pide

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

// the institution's access to RENIEC through the PIDE, from its convenio: a user (a DNI), the institution's RUC and a
// password. off by default; the credentials come from the environment (application.yml, README)
@ConfigurationProperties(prefix = "srtm.pide.reniec")
data class PideReniecProperties(
    val enabled: Boolean = false,
    val url: String = "https://ws2.pide.gob.pe/Rest/RENIEC/Consultar",
    val dniUsuario: String = "",
    val rucUsuario: String = "",
    val password: String = "",
    // the clerk waits on it, leaving the DNI: past this, the data is typed by hand
    val timeout: Duration = Duration.ofSeconds(5),
    // how long a consulta backs a save with fuente PIDE RENIEC (ConsultasReniec)
    val vigencia: Duration = Duration.ofMinutes(30)
) {
    val completa: Boolean get() = listOf(url, dniUsuario, rucUsuario, password).none { it.isBlank() }

    // never the password: this text may end up in a log
    override fun toString() =
        "PideReniecProperties(enabled=$enabled, url=$url, dniUsuario=$dniUsuario, rucUsuario=$rucUsuario, timeout=$timeout, vigencia=$vigencia)"
}

// RENIEC's rest service on the PIDE ("Consultar", out=json): the request is {PIDE: {nuDniConsulta, nuDniUsuario,
// nuRucUsuario, password}}; the answer consultarResponse.return has coResultado ("0000" when found), deResultado and
// datosPersona {apPrimer, apSegundo, prenombres, estadoCivil, direccion, ubigeo, restriccion, foto}. taken from the
// PIDE's published examples: check it against the convenio's documentation before turning it on. posted, so the
// password is never in a url (nor in a log)
class PideReniec(
    private val properties: PideReniecProperties
) : ConsultaDocumento {
    private val log = LoggerFactory.getLogger(javaClass)

    // own builder: boot 4 hands out no WebClient.Builder bean
    private val client = WebClient.builder().build()

    override suspend fun consultar(
        tipo: String,
        numero: String
    ): DatosPersona? {
        if (tipo != DNI) return null
        val respuesta =
            try {
                val body =
                    client
                        .post()
                        .uri(
                            UriComponentsBuilder
                                .fromUriString(properties.url)
                                .queryParam("out", "json")
                                .build()
                                .toUri()
                        ).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(pedido(numero))
                        .retrieve()
                        .bodyToMono(String::class.java)
                        .timeout(properties.timeout)
                        .awaitSingleOrNull()
                leerRespuestaReniec(numero, body.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the reason only: the request carries the password, and the answer a person's data
                val estado = (e as? WebClientResponseException)?.statusCode?.let { " $it" }.orEmpty()
                log.warn("PIDE RENIEC: la consulta falló ({}{})", e.javaClass.simpleName, estado)
                return null
            }
        if (respuesta.datos == null) log.warn("PIDE RENIEC: sin datos (coResultado {}: {})", respuesta.codigo, respuesta.mensaje)
        return respuesta.datos
    }

    private fun pedido(numero: String) =
        mapOf(
            "PIDE" to
                mapOf(
                    "nuDniConsulta" to numero,
                    "nuDniUsuario" to properties.dniUsuario,
                    "nuRucUsuario" to properties.rucUsuario,
                    "password" to properties.password
                )
        )
}

// what RENIEC answered: its result code and message, and the person when found
data class RespuestaReniec(
    val codigo: String?,
    val mensaje: String?,
    val datos: DatosPersona?
)

const val RENIEC_ENCONTRADO = "0000"

private val JSON = JsonMapper.builder().build()

fun leerRespuestaReniec(
    numero: String,
    body: String
): RespuestaReniec {
    val resultado = runCatching { JSON.readTree(body) }.getOrNull()?.path("consultarResponse")?.path("return")
    val codigo = resultado?.texto("coResultado")
    val persona = resultado?.path("datosPersona")?.takeIf { codigo == RENIEC_ENCONTRADO && it.isObject }
    val datos =
        persona?.let {
            DatosPersona(
                tipoDocumento = DNI,
                numeroDocumento = numero,
                apellidoPaterno = it.texto("apPrimer"),
                apellidoMaterno = it.texto("apSegundo"),
                nombres = it.texto("prenombres"),
                estadoCivil = it.texto("estadoCivil"),
                direccion = it.texto("direccion"),
                ubigeo = it.texto("ubigeo")
            )
        }
    return RespuestaReniec(codigo, resultado?.texto("deResultado"), datos)
}

private fun JsonNode.texto(campo: String): String? =
    path(campo)
        .takeIf { it.isValueNode && !it.isNull }
        ?.asString()
        ?.trim()
        ?.ifEmpty { null }
