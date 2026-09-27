package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.UUID

// the srtm's "(*) registrar al menos 1 domicilio fiscal": once a contribuyente has domicilios, exactly one of them
// is its active fiscal one, and the contribuyente's domicilio_fiscal follows it
class DomicilioFiscalApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `the first domicilio must be the active fiscal one`() {
        val id = inscribir()
        rejected("POST", "/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL"), "tipo_domicilio")
        rejected("POST", "/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL") + ("estado" to "INACTIVO"), "estado")
        assertEquals(0, domicilios(id).size())
    }

    @Test
    fun `a second active fiscal domicilio is refused, whether added or changed into`() {
        val id = inscribir()
        post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        val problem = rejected("POST", "/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL", numero = "300"), "tipo_domicilio")
        assertEquals("ya tiene otro domicilio fiscal activo: actualice ese domicilio", problem["errors"][0]["message"].asString())

        val real = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", numero = "300"))
        rejected("PUT", "/api/srtm/domicilios/${real["id"].asString()}", fields(real) + ("tipo_domicilio" to "FISCAL"), "tipo_domicilio")
        // an inactive one is history, not a second fiscal
        post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL", numero = "400") + ("estado" to "INACTIVO"))
        assertEquals(1, domicilios(id).iterator().asSequence().count { it["tipo_domicilio"].asString() == "FISCAL" && it["estado"].asString() == "ACTIVO" })
    }

    @Test
    fun `the only active fiscal domicilio cannot be removed, inactivated or turned into another kind`() {
        val id = inscribir()
        val fiscal = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        val fiscalId = fiscal["id"].asString()

        val problem = rejected("DELETE", "/api/srtm/domicilios/$fiscalId", null, "tipo_domicilio")
        assertEquals("No se puede eliminar el único domicilio fiscal activo", problem["detail"].asString())
        rejected("PUT", "/api/srtm/domicilios/$fiscalId", fields(fiscal) + ("estado" to "INACTIVO"), "estado")
        rejected("PUT", "/api/srtm/domicilios/$fiscalId", fields(fiscal) + ("tipo_domicilio" to "REAL"), "tipo_domicilio")

        val stored = domicilios(id)
        assertEquals(1, stored.size())
        assertEquals("FISCAL", stored[0]["tipo_domicilio"].asString())
        assertEquals("ACTIVO", stored[0]["estado"].asString())
        assertEquals(DESCRIPCION, contribuyente(id)["domicilio_fiscal"].asString())
    }

    @Test
    fun `other domicilios come and go freely, and only the fiscal one moves the domicilio fiscal`() {
        val id = inscribir()
        val fiscal = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        val real = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", numero = "300"))
        val before = core("contribuyente", id)["updatedAt"].asString()

        put("/api/srtm/domicilios/${real["id"].asString()}", fields(real) + ("numero" to "310"))
        put("/api/srtm/domicilios/${real["id"].asString()}", fields(real) + ("estado" to "INACTIVO"))
        // nothing of the fiscal one changed: the contribuyente is left alone
        assertEquals(before, core("contribuyente", id)["updatedAt"].asString())
        delete("/api/srtm/domicilios/${real["id"].asString()}")
        assertEquals(1, domicilios(id).size())

        put("/api/srtm/domicilios/${fiscal["id"].asString()}", fields(fiscal) + mapOf("numero" to "240", "distrito" to "SAN RAMON"))
        val moved = contribuyente(id)
        assertEquals("AV. MARGINAL, N° 240, JUNIN-CHANCHAMAYO-SAN RAMON", moved["domicilio_fiscal"].asString())
        assertEquals("SAN RAMON", moved["domicilio_distrito"].asString())
    }

    @Test
    fun `with two active fiscal ones from before, one steps down and the other becomes the domicilio fiscal`() {
        val id = inscribir()
        val first = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        // written before the rule, straight into core
        send(
            "POST",
            "/api/objects/domicilio/records",
            mapOf(
                "attributes" to
                    domicilio("FISCAL", numero = "500") +
                    mapOf("contribuyente" to id, "estado" to "ACTIVO", "descripcion" to "AV. MARGINAL, N° 500, JUNIN-CHANCHAMAYO-PERENE")
            ),
            HttpStatus.CREATED
        )
        // still two: this one cannot stay fiscal as it is
        rejected("PUT", "/api/srtm/domicilios/${first["id"].asString()}", fields(first) + ("numero" to "250"), "tipo_domicilio")

        put("/api/srtm/domicilios/${first["id"].asString()}", fields(first) + ("tipo_domicilio" to "REAL"))
        assertEquals("AV. MARGINAL, N° 500, JUNIN-CHANCHAMAYO-PERENE", contribuyente(id)["domicilio_fiscal"].asString())
    }

    private fun domicilio(
        tipo: String,
        numero: String = "234"
    ) = mapOf(
        "tipo_domicilio" to tipo,
        "tipo_predio" to "PREDIO URBANO",
        "ubigeo" to "120302",
        "departamento" to "JUNIN",
        "provincia" to "CHANCHAMAYO",
        "distrito" to "PERENE",
        "tipo_via" to "AVENIDA",
        "via" to "MARGINAL",
        "numero" to numero
    )

    private fun inscribir(): String =
        post(
            "/api/srtm/contribuyentes",
            mapOf(
                "tipo_contribuyente" to "PERSONA NATURAL",
                "tipo_documento" to "DNI",
                "numero_documento" to uniqueDocumento(),
                "apellido_paterno" to "FLORES",
                "nombres" to "JUNIOR",
                "sexo" to "HOMBRE",
                "estado_civil" to "SOLTERO"
            )
        )["id"].asString()

    private fun domicilios(id: String): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$id/domicilios", null, HttpStatus.OK))

    private fun contribuyente(id: String): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$id", null, HttpStatus.OK))["contribuyente"]

    private fun core(
        objectName: String,
        id: String
    ): JsonNode = tree(send("GET", "/api/objects/$objectName/records/$id", null, HttpStatus.OK))

    // a 400 naming that field; the problem, to read its messages
    private fun rejected(
        method: String,
        path: String,
        body: Map<String, Any?>?,
        field: String
    ): JsonNode {
        val problem = tree(send(method, path, body, HttpStatus.BAD_REQUEST))
        assertEquals(field, problem["errors"][0]["field"].asString())
        return problem
    }

    // a response as a request body: every field it came with
    private fun fields(node: JsonNode): Map<String, Any?> = json.convertValue(node, Map::class.java).entries.associate { it.key.toString() to it.value }

    private fun post(
        path: String,
        body: Map<String, Any?>
    ): JsonNode = tree(send("POST", path, body, HttpStatus.CREATED))

    private fun put(
        path: String,
        body: Map<String, Any?>
    ): JsonNode = tree(send("PUT", path, body, HttpStatus.OK))

    private fun delete(path: String) {
        send("DELETE", path, null, HttpStatus.NO_CONTENT)
    }

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
                "DELETE" -> client.delete().uri(path)
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
        const val DESCRIPCION = "AV. MARGINAL, N° 234, JUNIN-CHANCHAMAYO-PERENE"
    }
}
