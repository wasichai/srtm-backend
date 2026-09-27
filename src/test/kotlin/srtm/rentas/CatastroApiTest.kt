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

// a lote of the catastro fiscal kept from the portal's lote editor: read and replaced by its id, polygon included
// (setup as in RentasApiTest)
class CatastroApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `a lote is read by its id, with its polygon`() {
        val cpu = "CPU-${uniqueDocumento()}"
        val id = crearLote(cpu)["id"].asString()

        val lote = tree(send("GET", "/api/srtm/catastro/$id", null, HttpStatus.OK))
        assertEquals(cpu, lote["codigo_cpu"].asString())
        assertEquals("5243", lote["codigo_predio_municipal"].asString())
        assertEquals("120302", lote["ubigeo"].asString())
        assertEquals("ANDRES AVELINO CACERES", lote["via"].asString())
        assertEquals("Polygon", lote["lote_geom"]["type"].asString())
        assertEquals(-75.2250, lote["lote_geom"]["coordinates"][0][0][0].asDouble(), 1e-6)
    }

    @Test
    fun `a lote is replaced by its id, its fields and its polygon`() {
        val cpu = "CPU-${uniqueDocumento()}"
        val stored = crearLote(cpu)
        val id = stored["id"].asString()

        val body = fields(stored) + mapOf("via" to "LOS PINOS", "numero" to "350", "partida_registral" to null, "lote_geom" to MOVED)
        val updated = put("/api/srtm/catastro/$id", body)
        assertEquals("LOS PINOS", updated["via"].asString())
        assertEquals(-75.2260, updated["lote_geom"]["coordinates"][0][0][0].asDouble(), 1e-6)

        val lote = tree(send("GET", "/api/srtm/catastro/$id", null, HttpStatus.OK))
        assertEquals(cpu, lote["codigo_cpu"].asString())
        assertEquals("LOS PINOS", lote["via"].asString())
        assertEquals("350", lote["numero"].asString())
        // a field sent null is cleared: core's update replaces every field
        assertTrue(lote["partida_registral"] == null || lote["partida_registral"].isNull)
        assertEquals(-75.2260, lote["lote_geom"]["coordinates"][0][0][0].asDouble(), 1e-6)
    }

    @Test
    fun `a lote replaced without a polygon keeps the one it had`() {
        val stored = crearLote("CPU-${uniqueDocumento()}")
        val id = stored["id"].asString()

        put("/api/srtm/catastro/$id", fields(stored) + mapOf("manzana" to "D", "lote_geom" to null))

        val lote = tree(send("GET", "/api/srtm/catastro/$id", null, HttpStatus.OK))
        assertEquals("D", lote["manzana"].asString())
        assertEquals("Polygon", lote["lote_geom"]["type"].asString())
        assertEquals(-75.2250, lote["lote_geom"]["coordinates"][0][0][0].asDouble(), 1e-6)
    }

    @Test
    fun `an unknown lote is a 404`() {
        send("GET", "/api/srtm/catastro/${UUID.randomUUID()}", null, HttpStatus.NOT_FOUND)
        send("PUT", "/api/srtm/catastro/${UUID.randomUUID()}", mapOf("codigo_cpu" to "CPU-${uniqueDocumento()}"), HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a codigo cpu already taken is a 400 on that field, on create and on update`() {
        val cpu = "CPU-${uniqueDocumento()}"
        crearLote(cpu)
        val otro = crearLote("CPU-${uniqueDocumento()}")

        val alta = tree(send("POST", "/api/srtm/catastro", mapOf("codigo_cpu" to cpu), HttpStatus.BAD_REQUEST))
        assertEquals("codigo_cpu", alta["errors"][0]["field"].asString())
        val cambio = tree(send("PUT", "/api/srtm/catastro/${otro["id"].asString()}", fields(otro) + ("codigo_cpu" to cpu), HttpStatus.BAD_REQUEST))
        assertEquals("codigo_cpu", cambio["errors"][0]["field"].asString())
        // its own cpu is no clash
        put("/api/srtm/catastro/${otro["id"].asString()}", fields(otro) + ("manzana" to "E"))
    }

    private fun crearLote(cpu: String): JsonNode =
        post(
            "/api/srtm/catastro",
            mapOf(
                "codigo_cpu" to cpu,
                "codigo_predio_municipal" to "5243",
                "partida_registral" to "11002233",
                "tipo_predio" to "PREDIO URBANO",
                "ubigeo" to "120302",
                "tipo_via" to "AVENIDA",
                "via" to "ANDRES AVELINO CACERES",
                "tipo_zona" to "URBANIZACION",
                "zona" to "SOL DE LA ALAMEDA",
                "manzana" to "C",
                "lote" to "19",
                "lote_geom" to SQUARE
            )
        )

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

        private fun square(west: Double) =
            mapOf(
                "type" to "Polygon",
                "coordinates" to
                    listOf(
                        listOf(
                            listOf(west, -10.9480),
                            listOf(west + 0.0005, -10.9480),
                            listOf(west + 0.0005, -10.9475),
                            listOf(west, -10.9475),
                            listOf(west, -10.9480)
                        )
                    )
            )

        // a small lote in Perené, GeoJSON in EPSG:4326, and the same one a little to the west
        val SQUARE = square(-75.2250)
        val MOVED = square(-75.2260)
    }
}
