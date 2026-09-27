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

// the srtm's lists number their rows (pp. 4, 7, 9: código, número): domicilios, medios de contacto and documentos
// sustento get a code under their contribuyente when added, kept by every update and never shifted by a removal.
// same setup as RentasApiTest
class CodigoListasApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `domicilios are coded per contribuyente, and a removal shifts no code`() {
        val id = inscribir()
        val fiscal = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        // a code in the body is the backend's to ignore
        val segundo = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", "300") + ("codigo" to "777"))
        post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", "400"))
        assertEquals("001", fiscal["codigo"].asString())
        assertEquals("002", segundo["codigo"].asString())

        delete("/api/srtm/domicilios/${segundo["id"].asString()}")
        assertEquals(listOf("001", "003"), codigos("/api/srtm/contribuyentes/$id/domicilios"))
        assertEquals("004", post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", "500"))["codigo"].asString())

        val changed = put("/api/srtm/domicilios/${fiscal["id"].asString()}", fields(fiscal) + mapOf("codigo" to "999", "numero" to "240"))
        assertEquals("001", changed["codigo"].asString())
        assertEquals("240", changed["numero"].asString())
        assertEquals("001", post("/api/srtm/contribuyentes/${inscribir()}/domicilios", domicilio("FISCAL"))["codigo"].asString())
    }

    @Test
    fun `medios de contacto are coded per contribuyente, and an update keeps the code`() {
        val id = inscribir()
        val celular = post("/api/srtm/contribuyentes/$id/medios-contacto", medio("987654321"))
        val correo = post("/api/srtm/contribuyentes/$id/medios-contacto", medio("064123456") + ("codigo" to "777"))
        assertEquals("001", celular["codigo"].asString())
        assertEquals("002", correo["codigo"].asString())

        val changed = put("/api/srtm/medios-contacto/${correo["id"].asString()}", fields(correo) + mapOf("codigo" to null, "valor" to "064654321"))
        assertEquals("002", changed["codigo"].asString())
        assertEquals("064654321", changed["valor"].asString())
        assertEquals(listOf("001", "002"), codigos("/api/srtm/contribuyentes/$id/medios-contacto"))
        assertEquals("001", post("/api/srtm/contribuyentes/${inscribir()}/medios-contacto", medio("987654321"))["codigo"].asString())
    }

    @Test
    fun `documentos sustento are numbered per contribuyente, and an update keeps the number`() {
        val id = inscribir()
        val poder = post("/api/srtm/contribuyentes/$id/sustentos", sustento("PE-1"))
        val partida = post("/api/srtm/contribuyentes/$id/sustentos", sustento("PE-2"))
        assertEquals("001", poder["codigo"].asString())
        assertEquals("002", partida["codigo"].asString())

        val changed = put("/api/srtm/sustentos/${poder["id"].asString()}", fields(poder) + mapOf("codigo" to "050", "folios" to 3))
        assertEquals("001", changed["codigo"].asString())
        assertEquals(3, changed["folios"].asInt())
        delete("/api/srtm/sustentos/${poder["id"].asString()}")
        assertEquals(listOf("002"), codigos("/api/srtm/contribuyentes/$id/sustentos"))
        assertEquals("003", post("/api/srtm/contribuyentes/$id/sustentos", sustento("PE-3"))["codigo"].asString())
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

    private fun medio(valor: String) = mapOf("tipo" to "TELEFONO CELULAR", "valor" to valor)

    private fun sustento(numero: String) = mapOf("documento" to "PODER ESPECIAL", "numero_documento" to numero, "tipo_presentacion" to "ORIGINAL")

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

    // a list's codes, in its order
    private fun codigos(path: String): List<String> =
        tree(send("GET", path, null, HttpStatus.OK))
            .iterator()
            .asSequence()
            .map { it["codigo"].asString() }
            .toList()

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

    // model/apply.py in kotlin: what is missing gets created (objects, fields, enum options, relationships), a field
    // model.json no longer requires is relaxed, the rest is left alone (the test db may be shared)
    private fun applyModel() {
        val model = json.readTree(File("model/model.json"))
        val enums = model["enums"]

        fun options(field: JsonNode) =
            enums[field["enum"].asString()]
                .iterator()
                .asSequence()
                .map { it.asString() }
                .toList()

        fun required(field: JsonNode) = field["required"]?.asBoolean() ?: false

        fun payload(field: JsonNode) =
            buildMap {
                put("name", field["name"].asString())
                put("label", field["label"].asString())
                put("type", field["type"].asString())
                put("required", required(field))
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
                    continue
                }
                val change = mutableMapOf<String, Any>()
                if (field["type"].asString() == "ENUM") {
                    val have =
                        current["enumOptions"]
                            .iterator()
                            .asSequence()
                            .map { it.asString() }
                            .toList()
                    val missing = options(field) - have.toSet()
                    if (missing.isNotEmpty()) change["enumOptions"] = have + missing
                }
                if (current["required"].asBoolean() && !required(field)) change["required"] = false
                if (change.isNotEmpty()) send("PUT", "/api/metadata/objects/$name/fields/${field["name"].asString()}", change, HttpStatus.OK)
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
                "DELETE" -> client.delete().uri(path)
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
