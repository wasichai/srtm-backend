package srtm.normativa

import org.junit.jupiter.api.BeforeEach
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import srtm.impuesto.PARAMETRO_TRIBUTARIO
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

// what Normativa's api tests share (#187): an organization of their own (no other test's parámetros overlap theirs),
// with two clerks of the NORMATIVA profile (READ, CREATE, UPDATE and DELETE on parametro_tributario) for the double
// signature: ana transcribes and beto verifies. each test takes years of its own (anio()): FICTITIOUS figures
abstract class ConNormativaApiTest : SrtmApiTest() {
    protected lateinit var ana: String
    protected lateinit var beto: String

    @BeforeEach
    fun enSuOrganizacion() {
        token = organizacion ?: otraOrganizacion().also { organizacion = it }
        val (a, b) = firmantes ?: Pair(funcionario(normativa), funcionario(normativa)).also { firmantes = it }
        ana = a
        beto = b
    }

    // docs/manuales/demo/perfiles.json's NORMATIVA
    protected val normativa get() = listOf("READ", "CREATE", "UPDATE", "DELETE").map { permiso(PARAMETRO_TRIBUTARIO, it) }

    // a year no other test of the organization uses (a declaración of it is liquidated: before 2100, as LiquidacionApiTest's)
    protected fun anio(): Int = ANIOS.getAndIncrement()

    // the email a token signs with
    protected fun email(token: String): String {
        val carga = String(Base64.getUrlDecoder().decode(token.removePrefix("Bearer ").split('.')[1]))
        return tree(carga)["email"].asString()
    }

    // a UIT of the whole `anio`, as the body of POST /normativa/parametros
    protected fun uit(
        anio: Int,
        valor: String = "9000.00"
    ) = mapOf(
        "tipo" to "UIT",
        "vigencia_desde" to "$anio-01-01",
        "vigencia_hasta" to "$anio-12-31",
        "valor_numerico" to valor,
        "norma" to "Norma ficticia de prueba",
        "fuente" to "NormativaApiTest"
    )

    // registered by `quien`: the row, POR_VERIFICAR
    protected fun registrar(
        cuerpo: Map<String, Any?>,
        quien: String = ana
    ): JsonNode = post(PARAMETROS, cuerpo, quien)

    protected fun verificar(
        ids: List<String>,
        quien: String = beto
    ): JsonNode = tree(send("POST", "$PARAMETROS/verificar", mapOf("ids" to ids), HttpStatus.OK, quien))

    // registered by ana and verified by beto: its id
    protected fun vigente(cuerpo: Map<String, Any?>): String {
        val id = registrar(cuerpo)["id"].asString()
        check(verificar(listOf(id))["verificados"].size() == 1)
        return id
    }

    protected fun parametros(
        consulta: String,
        quien: String = ana
    ): List<JsonNode> =
        tree(send("GET", "$PARAMETROS?$consulta", null, HttpStatus.OK, quien))
            .iterator()
            .asSequence()
            .toList()

    // the csv as the body (text/csv): status and body
    protected fun importar(
        csv: String,
        dryRun: Boolean?,
        quien: String = ana
    ): Pair<HttpStatus, JsonNode?> {
        val uri = "$PARAMETROS/importar" + (dryRun?.let { "?dry_run=$it" } ?: "")
        val result =
            client
                .post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, quien)
                .contentType(MediaType("text", "csv"))
                .bodyValue(csv)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to result.responseBody?.let(::tree)
    }

    // a json array's elements
    protected fun JsonNode.elementos(): List<JsonNode> = iterator().asSequence().toList()

    protected companion object {
        const val PARAMETROS = "/api/srtm/normativa/parametros"

        private var organizacion: String? = null
        private var firmantes: Pair<String, String>? = null
        private val ANIOS = AtomicInteger(2061)
    }
}
