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

// the contribuyente's document against the real model: the number its tipo takes (a 400 on numero_documento
// otherwise), and none at all for SIN DOCUMENTO, which the unique constraint allows as many times as needed
class DocumentoApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        contribuyenteDelModelo()
    }

    @Test
    fun `sin documento is inscribed without a number, as many times as needed`() {
        val primero = inscribir(persona("SIN DOCUMENTO", null))
        // a number sent anyway is not kept
        val segundo = inscribir(persona("SIN DOCUMENTO", "00012"))
        assertTrue(sinNumero(primero))
        assertTrue(sinNumero(segundo))
    }

    @Test
    fun `a number that does not fit its tipo is a 400 on numero_documento`() {
        rechazado(persona("DNI", "4355456"), "El DNI tiene 8 dígitos")
        rechazado(persona("DNI", null), "Este dato es obligatorio")
        rechazado(empresa("20131312954"), "El dígito verificador del RUC no es válido")
        rechazado(persona("PASAPORTE", "AB-1234"), "Hasta 12 letras o dígitos")
    }

    @Test
    fun `a ruc with its check digit is inscribed, trimmed`() {
        val base = "20" + digits(8)
        val ruc = (0..9).map { base + it }.first { errorDocumento("RUC", it) == null }
        assertEquals(ruc, inscribir(empresa(" $ruc "))["numero_documento"].asString())
    }

    @Test
    fun `changing to sin documento clears the number`() {
        val inscrito = inscribir(persona("DNI", digits(8)))
        val body = fields(inscrito) + ("tipo_documento" to "SIN DOCUMENTO")
        assertTrue(sinNumero(put("/api/srtm/contribuyentes/${inscrito["id"].asString()}", body)))
    }

    private fun persona(
        tipo: String,
        numero: String?
    ) = mapOf(
        "tipo_contribuyente" to "PERSONA NATURAL",
        "tipo_documento" to tipo,
        "numero_documento" to numero,
        "apellido_paterno" to "FLORES",
        "nombres" to "JUNIOR",
        "sexo" to "HOMBRE",
        "estado_civil" to "SOLTERO"
    )

    private fun empresa(ruc: String) =
        mapOf(
            "tipo_contribuyente" to "PERSONA JURIDICA",
            "tipo_documento" to "RUC",
            "numero_documento" to ruc,
            "razon_social" to "ASOCIACION AGRARIA PERENE"
        )

    private fun sinNumero(node: JsonNode) = node["numero_documento"] == null || node["numero_documento"].isNull

    private fun inscribir(body: Map<String, Any?>): JsonNode = tree(send("POST", "/api/srtm/contribuyentes", body, HttpStatus.CREATED))

    private fun put(
        path: String,
        body: Map<String, Any?>
    ): JsonNode = tree(send("PUT", path, body, HttpStatus.OK))

    private fun rechazado(
        body: Map<String, Any?>,
        message: String
    ) {
        client
            .post()
            .uri("/api/srtm/contribuyentes")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("numero_documento")
            .jsonPath("$.errors[0].message")
            .isEqualTo(message)
    }

    // contribuyente as model/model.json has it, the way model/apply.py leaves it: created when missing, missing fields
    // added, and numero_documento optional even where an older model made it required (the test db may be shared)
    private fun contribuyenteDelModelo() {
        val model = json.readTree(File("model/model.json"))
        val obj = items(model["objects"]).first { it["name"].asString() == CONTRIBUYENTE }
        val fields = items(obj["fields"])

        fun payload(field: JsonNode) =
            buildMap {
                put("name", field["name"].asString())
                put("label", field["label"].asString())
                put("type", field["type"].asString())
                put("required", field["required"]?.asBoolean() ?: false)
                put("unique", field["unique"]?.asBoolean() ?: false)
                field["enum"]?.let { put("enumOptions", items(model["enums"][it.asString()]).map { o -> o.asString() }) }
            }

        val objects = items(tree(send("GET", "/api/objects", null, HttpStatus.OK))).map { it["name"].asString() }
        if (CONTRIBUYENTE !in objects) {
            val body =
                mapOf(
                    "name" to CONTRIBUYENTE,
                    "label" to obj["label"].asString(),
                    "pluralLabel" to obj["pluralLabel"].asString(),
                    "fields" to fields.map(::payload)
                )
            send("POST", "/api/objects", body, HttpStatus.CREATED)
            return
        }
        val path = "/api/metadata/objects/$CONTRIBUYENTE/fields"
        val stored = items(tree(send("GET", path, null, HttpStatus.OK))).associateBy { it["name"].asString() }
        for (field in fields) {
            val current = stored[field["name"].asString()]
            if (current == null) {
                send("POST", path, payload(field), HttpStatus.CREATED)
            } else if (current["required"].asBoolean() && field["required"]?.asBoolean() != true) {
                send("PUT", "$path/${field["name"].asString()}", mapOf("required" to false), HttpStatus.OK)
            }
        }
    }

    private fun items(node: JsonNode): List<JsonNode> = node.iterator().asSequence().toList()

    // a response as a request body: every field it came with
    private fun fields(node: JsonNode): Map<String, Any?> = json.convertValue(node, Map::class.java).entries.associate { it.key.toString() to it.value }

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

    private fun tree(body: String): JsonNode = json.readValue(body, JsonNode::class.java)

    // random digits: the test db is shared, and a document number is unique
    private fun digits(n: Int): String =
        generateSequence { UUID.randomUUID().toString().filter { it.isDigit() } }
            .flatMap { it.asSequence() }
            .take(n)
            .joinToString("")

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
