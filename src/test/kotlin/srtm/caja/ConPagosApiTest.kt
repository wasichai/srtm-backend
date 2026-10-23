package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID
import kotlin.random.Random

// what the tests of the endpoints caja calls share (#165 pagos, #166 conciliación, #168 vigencia): caja signs in as the
// organization's service account (role CAJA), and delivers events with the contract's shape (caja-backend's
// cuerpoPagoRegistrado and cuerpoPagoAnulado)
abstract class ConPagosApiTest : ConCajaApiTest() {
    // the service account's token, in the caja's organization
    protected lateinit var caja: String

    @BeforeEach
    fun comoLaCuentaDeCaja() {
        caja = tokenDeCaja()
    }

    // the escenario's year determined and sent to caja: its cuotas, and the first one
    protected fun cuotasEnviadas(e: Escenario): List<JsonNode> {
        val cuotas = lista(tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))) { it }
        enviar(e.contribuyente, e.anio)
        return cuotas
    }

    protected fun determinadaYEnviada(e: Escenario): JsonNode = cuotasEnviadas(e)[0]

    protected fun referencia(cuota: JsonNode) = "ARB-${cuota["clave"].asString()}"

    // an event as caja-backend writes it: its pagoId and its json
    class Evento(
        val id: String,
        val json: String
    )

    protected fun registrado(
        ordenes: List<Pair<String, String>>,
        numero: String = "001-${Random.nextInt(1_000_000, 9_999_999)}",
        fecha: String = "2026-10-22"
    ): Evento {
        val id = UUID.randomUUID().toString()
        val total = ordenes.sumOf { it.second.toBigDecimalOrNull() ?: BigDecimal.ZERO }.toPlainString()
        val lineas =
            ordenes.joinToString(",") { (ref, importe) ->
                """{"ordenId":"${UUID.randomUUID()}","referenciaExterna":"$ref","importe":"$importe","actualizadoA":"2026-10-22"}"""
            }
        return Evento(
            id,
            """{"pagoId":"$id","tipo":"PAGO_REGISTRADO","sistemaOrigen":"rentas",""" +
                """"recibo":{"numero":"$numero","serie":"001","fechaDePago":"$fecha","cajero":"ana@muni.gob.pe","formaDePago":"EFECTIVO"},""" +
                """"pagador":{"documento":"12345678","nombre":"FLORES OTINIANO JUNIOR","idExterno":null},""" +
                """"total":"$total","actualizadoA":"2026-10-22","ordenes":[$lineas]}"""
        )
    }

    protected fun anulado(original: Evento): Evento {
        val id = UUID.randomUUID().toString()
        val o = tree(original.json)
        return Evento(
            id,
            """{"pagoId":"$id","tipo":"PAGO_ANULADO","pagoOriginalId":"${original.id}","recibo":${o["recibo"]},""" +
                """"motivo":"Error de digitación","fecha":"2026-10-22","total":"${o["total"].asString()}"}"""
        )
    }

    protected fun pagar(
        evento: Evento,
        quien: String? = caja
    ) = pagar(evento.json, quien)

    // caja's POST {url}/pagos: the status and the body as they came
    protected fun pagar(
        json: String,
        quien: String? = caja
    ): Pair<HttpStatus, String> {
        val spec =
            client
                .post()
                .uri("/api/srtm/caja/pagos")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
        val result =
            (quien?.let { spec.header(HttpHeaders.AUTHORIZATION, it) } ?: spec)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to (result.responseBody ?: "")
    }

    // docs/develop/alta-organizacion.md: the role CAJA and the service account caja of the organization, created once
    // per run; a token of its own for each test (it lives 15 minutes)
    protected fun tokenDeCaja(): String {
        val (id, secreto) = credencial ?: cuentaDeCaja(token).also { credencial = it }
        return tokenDe(id, secreto)
    }

    // the role CAJA and a service account with it in the organization of `admin`: a token of it
    protected fun cuentaDeServicio(admin: String): String {
        val (id, secreto) = cuentaDeCaja(admin)
        return tokenDe(id, secreto)
    }

    private fun cuentaDeCaja(admin: String): Pair<String, String> {
        val rol = "CAJA_${uniqueDocumento()}"
        send("POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED, admin)
        val crear = listOf(ObjetosCaja.PAGO_CAJA, ObjetosCaja.PAGO_CAJA_DETALLE)
        val leer = listOf(ObjetosCaja.ORDEN_CAJA, "cuota_arbitrio", "anulacion_cuota_arbitrio", ObjetosCaja.CUOTA_PREDIAL, "contribuyente")
        val permisos = crear.flatMap { listOf(permiso(it, "CREATE"), permiso(it, "READ")) } + leer.map { permiso(it, "READ") }
        send("PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK, admin)
        val cuenta = post("/api/service-accounts", mapOf("name" to "caja-${uniqueDocumento()}", "roles" to listOf(rol)), admin)
        return cuenta["clientId"].asString() to cuenta["clientSecret"].asString()
    }

    private fun tokenDe(
        id: String,
        secreto: String
    ): String {
        val respuesta =
            client
                .post()
                .uri("/api/auth/token")
                .bodyValue(mapOf("clientId" to id, "clientSecret" to secreto))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        return "Bearer ${tree(respuesta)["token"].asString()}"
    }

    private companion object {
        var credencial: Pair<String, String>? = null
    }
}
