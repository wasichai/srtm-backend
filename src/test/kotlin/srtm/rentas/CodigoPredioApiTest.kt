package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// the code of a predio registered in the portal without sector or manzana catastral (the srtm asks for neither,
// page 14), and two clerks registering in the same manzana at once. same setup as RentasApiTest
class CodigoPredioApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `a declaracion jurada on a new predio without sector or manzana gives it a code of the portal's own series`() {
        val contribuyente = inscribir()
        val predio =
            mapOf(
                "condicion" to "URBANO",
                "tipo_via" to "AVENIDA",
                "via" to "MARGINAL",
                "departamento" to "JUNIN",
                "provincia" to "CHANCHAMAYO",
                "distrito" to "PERENE"
            )
        val first = post("/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas", mapOf("predio" to predio))
        val second = post("/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas", mapOf("predio" to predio))
        val codigo = first["predio"]["codigo"].asString()
        assertTrue(Regex("P-\\d{6}").matches(codigo), codigo)
        assertEquals(siguienteCodigoPropio(codigo), second["predio"]["codigo"].asString())
        assertTrue(first["declaracion"]["numero_declaracion"].asInt() > 0)
    }

    @Test
    fun `a new predio on a lote of the catastro takes the lote's municipal code, while no predio has it`() {
        val cpu = "CPU-${unique()}"
        val municipal = "M-${unique()}"
        post("/api/srtm/catastro", mapOf("codigo_cpu" to cpu, "codigo_predio_municipal" to municipal, "tipo_predio" to "PREDIO URBANO"))
        val first = post("/api/srtm/predios", mapOf("codigo_cpu" to cpu, "direccion" to "S/N"))
        assertEquals(municipal, first["codigo"].asString())
        // a second predio on that lote: the code is taken
        val second = post("/api/srtm/predios", mapOf("codigo_cpu" to cpu, "direccion" to "S/N"))
        assertTrue(second["codigo"].asString().startsWith(PREFIJO_PROPIO), second["codigo"].asString())
    }

    @Test
    fun `a code the client sends that another predio has is a 400 on codigo`() {
        val codigo = "T-${unique()}"
        post("/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "S/N"))
        val (status, body) =
            exchange(
                "POST",
                "/api/srtm/contribuyentes/${inscribir()}/declaraciones-juradas",
                mapOf("predio" to mapOf("codigo" to codigo, "direccion" to "S/N"))
            )
        assertEquals(HttpStatus.BAD_REQUEST, status, body)
        assertEquals("codigo", tree(body)["errors"][0]["field"].asString())
    }

    @Test
    fun `two clerks registering predios in the same manzana at once both get a code`() {
        val sector = unique().take(4)
        val manzana = unique().take(2)
        val barrier = CyclicBarrier(2)
        val clerks = Executors.newFixedThreadPool(2)
        val results =
            try {
                List(2) {
                    CompletableFuture.supplyAsync({
                        barrier.await(30, TimeUnit.SECONDS)
                        exchange("POST", "/api/srtm/predios", mapOf("sector_catastral" to sector, "manzana_catastral" to manzana, "direccion" to "S/N"))
                    }, clerks)
                }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                clerks.shutdownNow()
            }
        assertEquals(listOf(HttpStatus.CREATED, HttpStatus.CREATED), results.map { it.first }, results.toString())
        assertEquals(setOf("$sector-$manzana-0001", "$sector-$manzana-0002"), results.map { tree(it.second)["codigo"].asString() }.toSet())
    }

    private fun inscribir(): String =
        post(
            "/api/srtm/contribuyentes",
            mapOf(
                "tipo_contribuyente" to "PERSONA NATURAL",
                "tipo_documento" to "DNI",
                "numero_documento" to unique(),
                "apellido_paterno" to "FLORES",
                "apellido_materno" to "OTINIANO",
                "nombres" to "JUNIOR",
                "sexo" to "HOMBRE",
                "estado_civil" to "SOLTERO"
            )
        )["id"].asString()

    private fun post(
        path: String,
        body: Map<String, Any?>
    ): JsonNode = tree(send("POST", path, body, HttpStatus.CREATED))

    // model/apply.py in kotlin, as RentasApiTest does it: what is missing gets created, what exists is left alone
    private fun applyModel() {
        val model = json.readTree(File("model/model.json"))
        val enums = model["enums"]

        fun options(field: JsonNode) =
            enums[field["enum"].asString()]
                .iterator()
                .asSequence()
                .map { it.asString() }
                .toList()

        fun payload(field: JsonNode) =
            buildMap {
                put("name", field["name"].asString())
                put("label", field["label"].asString())
                put("type", field["type"].asString())
                put("required", field["required"]?.asBoolean() ?: false)
                put("unique", field["unique"]?.asBoolean() ?: false)
                if (field["type"].asString() == "ENUM") put("enumOptions", options(field))
                if (field["type"].asString() == "GEOMETRY") {
                    put("geometryType", field["geometryType"].asString())
                    put("srid", field["srid"]?.asInt() ?: 4326)
                }
            }

        val existing: Set<String> = tree(send("GET", "/api/objects", null, HttpStatus.OK)).names()
        for (obj in model["objects"]) {
            val name = obj["name"].asString()
            val fields = obj["fields"].iterator().asSequence().toList()
            if (name !in existing) {
                send(
                    "POST",
                    "/api/objects",
                    mapOf(
                        "name" to name,
                        "label" to obj["label"].asString(),
                        "pluralLabel" to obj["pluralLabel"].asString(),
                        "fields" to fields.map(::payload)
                    ),
                    HttpStatus.CREATED
                )
                continue
            }
            val stored =
                tree(send("GET", "/api/metadata/objects/$name/fields", null, HttpStatus.OK)).iterator().asSequence().associateBy { it["name"].asString() }
            for (field in fields) {
                val current = stored[field["name"].asString()]
                if (current == null) {
                    send("POST", "/api/metadata/objects/$name/fields", payload(field), HttpStatus.CREATED)
                } else if (field["type"].asString() == "ENUM") {
                    val have =
                        current["enumOptions"]
                            .iterator()
                            .asSequence()
                            .map { it.asString() }
                            .toList()
                    val missing = options(field) - have.toSet()
                    if (missing.isNotEmpty()) {
                        send("PUT", "/api/metadata/objects/$name/fields/${field["name"].asString()}", mapOf("enumOptions" to have + missing), HttpStatus.OK)
                    }
                }
            }
        }
        val relationships: Set<String> = tree(send("GET", "/api/relationships", null, HttpStatus.OK)).names()
        for (rel in model["relationships"]) {
            if (rel["name"].asString() in relationships) continue
            send(
                "POST",
                "/api/relationships",
                listOf("name", "label", "inverseLabel", "source", "target", "fieldName").associateWith { rel[it].asString() } + ("type" to "MANY_TO_ONE"),
                HttpStatus.CREATED
            )
            send("PUT", "/api/metadata/objects/${rel["source"].asString()}/fields/${rel["fieldName"].asString()}", mapOf("required" to true), HttpStatus.OK)
        }
    }

    private fun send(
        method: String,
        path: String,
        body: Any?,
        status: HttpStatus
    ): String {
        val (actual, response) = exchange(method, path, body)
        assertEquals(status, actual, "$method $path: $response")
        return response
    }

    // status and body, without asserting: safe off the test's thread
    private fun exchange(
        method: String,
        path: String,
        body: Any?
    ): Pair<HttpStatus, String> {
        val spec =
            when (method) {
                "GET" -> client.get().uri(path)
                "PUT" -> client.put().uri(path).bodyValue(body!!)
                else -> client.post().uri(path).bodyValue(body!!)
            }
        val result =
            spec
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to (result.responseBody ?: "")
    }

    private fun JsonNode.names(): Set<String> = iterator().asSequence().map { it["name"].asString() }.toSet()

    private fun tree(body: String): JsonNode = json.readValue(body, JsonNode::class.java)

    private fun unique(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isDigit() }
            .padEnd(8, '0')
            .take(8)

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
