package srtm.rentas

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.UUID

// the fichas' totals with two condóminos of one predio: each contribuyente counts its part, the predio its
// autoavalúo once (setup as in RentasApiTest)
class TotalesApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `two condominos of a predio, each with its part, and the predio counted once`() {
        val a = inscribir()
        val b = inscribir()
        val compartido = predio()
        val propio = predio()
        // a predio of 10000.50: 6000.25 is a's, 4000.25 b's
        declarar(a, compartido, "CONDOMINO", autoavaluo = 10000.50, condominio = 6000.25, afecto = 6000.25)
        declarar(b, compartido, "CONDOMINO", autoavaluo = 10000.50, condominio = 4000.25, afecto = 4000.25)
        // a's own predio: no condominio, the whole autoavalúo is a's
        declarar(a, propio, "PROPIETARIO UNICO", autoavaluo = 5000.50, condominio = null, afecto = 5000.50)

        get("/api/srtm/contribuyentes/$a?anio=2026")
            .expectBody()
            .jsonPath("$.predios")
            .isEqualTo(2)
            .jsonPath("$.totales.declaraciones")
            .isEqualTo(2)
            .jsonPath("$.totales.autoavaluo")
            .isEqualTo(11000.75)
            .jsonPath("$.totales.valor_afecto")
            .isEqualTo(11000.75)
        get("/api/srtm/contribuyentes/$b?anio=2026")
            .expectBody()
            .jsonPath("$.totales.autoavaluo")
            .isEqualTo(4000.25)
            .jsonPath("$.totales.valor_afecto")
            .isEqualTo(4000.25)
        get("/api/srtm/predios/$compartido?anio=2026")
            .expectBody()
            .jsonPath("$.titulares")
            .isEqualTo(2)
            .jsonPath("$.totales.declaraciones")
            .isEqualTo(2)
            .jsonPath("$.totales.autoavaluo")
            .isEqualTo(10000.50)
            .jsonPath("$.totales.valor_afecto")
            .isEqualTo(10000.50)
    }

    private fun inscribir(): String =
        post(
            "/api/srtm/contribuyentes",
            mapOf(
                "tipo_contribuyente" to "PERSONA NATURAL",
                "tipo_documento" to "DNI",
                "numero_documento" to uniqueDocumento(),
                "apellido_paterno" to "FLORES",
                "apellido_materno" to "OTINIANO",
                "nombres" to "JUNIOR",
                "sexo" to "HOMBRE",
                "estado_civil" to "SOLTERO"
            )
        )["id"].asString()

    private fun predio(): String =
        post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))["id"].asString()

    private fun declarar(
        contribuyente: String,
        predio: String,
        condicion: String,
        autoavaluo: Double,
        condominio: Double?,
        afecto: Double
    ) {
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "condicion_propiedad" to condicion,
                "valor_autoavaluo" to autoavaluo,
                "valor_condominio" to condominio,
                "valor_afecto" to afecto
            )
        )
    }

    private fun get(path: String) =
        client
            .get()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk

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
                tree(
                    send("GET", "/api/metadata/objects/$name/fields", null, HttpStatus.OK)
                ).iterator().asSequence().associateBy { it["name"].asString() }
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
                listOf("name", "label", "inverseLabel", "source", "target", "fieldName").associateWith { rel[it].asString() } +
                    ("type" to "MANY_TO_ONE"),
                HttpStatus.CREATED
            )
            send(
                "PUT",
                "/api/metadata/objects/${rel["source"].asString()}/fields/${rel["fieldName"].asString()}",
                mapOf("required" to true),
                HttpStatus.OK
            )
        }
    }

    private fun send(
        method: String,
        path: String,
        body: Any?,
        status: HttpStatus
    ): String {
        val spec =
            when (method) {
                "GET" -> client.get().uri(path)
                "PUT" -> client.put().uri(path).bodyValue(body!!)
                else -> client.post().uri(path).bodyValue(body!!)
            }
        return spec
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isEqualTo(status)
            .expectBody(String::class.java)
            .returnResult()
            .responseBody ?: ""
    }

    private fun JsonNode.names(): Set<String> = iterator().asSequence().map { it["name"].asString() }.toSet()

    private fun tree(body: String): JsonNode = json.readValue(body, JsonNode::class.java)

    private fun uniqueDocumento(): String {
        val digits =
            UUID
                .randomUUID()
                .toString()
                .filter { it.isDigit() }
                .padEnd(8, '0')
                .take(8)
        assertNotEquals("", digits)
        return digits
    }

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
