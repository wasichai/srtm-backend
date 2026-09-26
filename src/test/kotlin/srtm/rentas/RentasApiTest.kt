package srtm.rentas

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.UUID

// the portal api against the real model (model/model.json, loaded the way model/apply.py does it):
// search, fichas, declarations with their other side, catalogs, validation and auth
class RentasApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `a contribuyente, its predio and its declaration, seen from both sides`() {
        val documento = uniqueDocumento()
        val contribuyente =
            post(
                "/api/srtm/contribuyentes",
                mapOf(
                    "tipo_persona" to "NATURAL",
                    "tipo_documento" to "DNI",
                    "numero_documento" to documento,
                    "nombre_completo" to "QUISPE MAMANI JUAN $documento"
                )
            )
        val codigo = "T-$documento"
        val predio = post("/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))
        val contribuyenteId = contribuyente["id"].asString()
        val predioId = predio["id"].asString()
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyenteId,
                "predio" to predioId,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "valor_autoavaluo" to 10080.45,
                "valor_afecto" to 8000
            )
        )

        get("/api/srtm/contribuyentes?q=$documento")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].numero_documento")
            .isEqualTo(documento)
        get("/api/srtm/contribuyentes/$contribuyenteId?anio=2026")
            .expectBody()
            .jsonPath("$.contribuyente.id")
            .isEqualTo(contribuyenteId)
            .jsonPath("$.predios")
            .isEqualTo(1)
            .jsonPath("$.totales.autoavaluo")
            .isEqualTo(10080.45)
        get("/api/srtm/contribuyentes/$contribuyenteId/declaraciones")
            .expectBody()
            .jsonPath("$[0].declaracion.anio")
            .isEqualTo(2026)
            .jsonPath("$[0].predio.codigo")
            .isEqualTo(codigo)
        get("/api/srtm/predios/$predioId/declaraciones?anio=2026")
            .expectBody()
            .jsonPath("$[0].contribuyente.numero_documento")
            .isEqualTo(documento)
        get("/api/srtm/predios/$predioId")
            .expectBody()
            .jsonPath("$.titulares")
            .isEqualTo(1)
    }

    @Test
    fun `an update replaces the record, so a field sent as null is cleared`() {
        val documento = uniqueDocumento()
        val body =
            mapOf(
                "tipo_persona" to "NATURAL",
                "tipo_documento" to "DNI",
                "numero_documento" to documento,
                "nombre_completo" to "ROJAS $documento",
                "domicilio_fiscal" to "AV. PERU 1"
            )
        val id = post("/api/srtm/contribuyentes", body)["id"].asString()
        client
            .put()
            .uri("/api/srtm/contribuyentes/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body + ("domicilio_fiscal" to null))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.domicilio_fiscal")
            .doesNotExist()
    }

    @Test
    fun `catalogs list the enum options of the model`() {
        get("/api/srtm/catalogos")
            .expectBody()
            .jsonPath("$.contribuyente.tipo_documento[1]")
            .isEqualTo("DNI")
            .jsonPath("$.predio.condicion[0]")
            .isEqualTo("URBANO")
    }

    @Test
    fun `a missing required field is a 400 naming that field`() {
        client
            .post()
            .uri("/api/srtm/contribuyentes")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("tipo_persona" to "NATURAL", "tipo_documento" to "DNI", "nombre_completo" to "SIN DOCUMENTO"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("numero_documento")
    }

    @Test
    fun `no token, no portal`() {
        client
            .get()
            .uri("/api/srtm/resumen")
            .exchange()
            .expectStatus()
            .isUnauthorized
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
    ): JsonNode =
        tree(
            client
                .post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        )

    // model/apply.py in kotlin: what is missing gets created, what exists is left alone (the test db is shared)
    private fun applyModel() {
        val model = json.readTree(File("model/model.json"))
        val existing: Set<String> = tree(send("GET", "/api/objects", null, HttpStatus.OK)).names()
        for (obj in model["objects"]) {
            if (obj["name"].asString() in existing) continue
            val fields =
                obj["fields"].iterator().asSequence().toList().map { f ->
                    buildMap {
                        put("name", f["name"].asString())
                        put("label", f["label"].asString())
                        put("type", f["type"].asString())
                        put("required", f["required"]?.asBoolean() ?: false)
                        put("unique", f["unique"]?.asBoolean() ?: false)
                        if (f["type"].asString() ==
                            "ENUM"
                        ) {
                            put(
                                "enumOptions",
                                model["enums"][f["enum"].asString()]
                                    .iterator()
                                    .asSequence()
                                    .map { it.asString() }
                                    .toList()
                            )
                        }
                    }
                }
            send(
                "POST",
                "/api/objects",
                mapOf(
                    "name" to obj["name"].asString(),
                    "label" to obj["label"].asString(),
                    "pluralLabel" to obj["pluralLabel"].asString(),
                    "fields" to fields
                ),
                HttpStatus.CREATED
            )
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

    private fun uniqueDocumento(): String =
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
