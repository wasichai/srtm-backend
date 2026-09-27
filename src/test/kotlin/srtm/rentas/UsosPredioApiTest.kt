package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

// the srtm's usos del predio (clase -> sub clase -> uso), the catalog the portal cascades over, and what a
// declaración stores of it (setup as in RentasApiTest)
class UsosPredioApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `the catalog of usos is served by code, with its clase and sub clase`() {
        // the test db may be shared: codes of this run only
        val prefijo = uniqueDocumento()
        post(
            "/api/objects/uso_predio/records",
            mapOf("attributes" to mapOf("codigo" to "${prefijo}2", "clase" to "BIENES COMUNES", "sub_clase" to "RESIDENCIAL", "uso" to "CASA HABITACIÓN"))
        )
        post(
            "/api/objects/uso_predio/records",
            mapOf("attributes" to mapOf("codigo" to "${prefijo}1", "clase" to "RESIDENCIAL", "sub_clase" to "UNIFAMILIAR", "uso" to "CASA HABITACIÓN"))
        )

        val usos = tree(send("GET", "/api/srtm/usos-predio", null, HttpStatus.OK)).iterator().asSequence().toList()
        val nuestros = usos.filter { it["codigo"].asString().startsWith(prefijo) }
        assertEquals(listOf("${prefijo}1", "${prefijo}2"), nuestros.map { it["codigo"].asString() })
        assertEquals("RESIDENCIAL", nuestros[0]["clase"].asString())
        assertEquals("UNIFAMILIAR", nuestros[0]["sub_clase"].asString())
        assertEquals("CASA HABITACIÓN", nuestros[0]["uso"].asString())
        val codigos = usos.map { it["codigo"].asString() }
        assertEquals(codigos.sorted(), codigos)
    }

    @Test
    fun `a declaration stores the srtm's clase, sub clase and uso, or the padron's uso alone`() {
        val predio = predio()
        val srtm =
            post(
                "/api/srtm/declaraciones",
                body(inscribir(), predio) + mapOf("clase_uso" to "RESIDENCIAL", "sub_clase_uso" to "UNIFAMILIAR", "uso" to "CASA HABITACIÓN")
            )
        assertEquals("RESIDENCIAL", srtm["clase_uso"].asString())
        assertEquals("UNIFAMILIAR", srtm["sub_clase_uso"].asString())
        assertEquals("CASA HABITACIÓN", srtm["uso"].asString())

        val padron = post("/api/srtm/declaraciones", body(inscribir(), predio()) + ("uso" to "RESIDENCIAL - CASA HABITACION"))
        assertEquals("RESIDENCIAL - CASA HABITACION", padron["uso"].asString())
        assertTrue(padron["clase_uso"] == null || padron["clase_uso"].isNull)
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

    private fun body(
        contribuyente: String,
        predio: String
    ) = mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to 2026, "secuencia_uso" to "1")

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
