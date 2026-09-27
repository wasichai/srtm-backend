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

// the padrón as model/import_predios.py imports it (and model/normalizar_padron.py leaves it), through the portal:
// buscar en tributario by its types and kilómetro, its ubicación saved with the srtm's abbreviations, and the
// padrón's format of the secuencia de uso
class PadronNormalizadoApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `buscar en tributario finds a padron predio by its tipo de via, zona and kilometro`() {
        val via = "LIMA ${unique()}"
        val codigo = post("/api/objects/predio/records", mapOf("attributes" to padron(via)))["attributes"]["codigo"].asString()

        get("/api/srtm/predios/buscar?tipo_via=JIRON&via=$via&tipo_zona=CERCADO&kilometro=23.5")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].codigo")
            .isEqualTo(codigo)
        get("/api/srtm/predios/buscar?tipo_via=CALLE&via=$via")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
        get("/api/srtm/predios/buscar?tipo_zona=URBANIZACION&via=$via")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
    }

    @Test
    fun `a padron predio's ubicacion saved in the portal reads as the srtm writes it, its type once`() {
        val via = "LIMA ${unique()}"
        val id = post("/api/objects/predio/records", mapOf("attributes" to padron(via)))["id"].asString()

        val saved = put("/api/srtm/predios/$id", padron(via) + PERENE)
        assertEquals("JR. $via, N° 12, MZ. A, LT. 5, KM. 23.5, CERCADO II MESETA, JUNIN-CHANCHAMAYO-PERENE", saved["direccion"].asString())

        // one saved before normalizar_padron.py ran: the vía still carries its type
        val antes = put("/api/srtm/predios/$id", padron(via) + PERENE + ("via" to "JIRON $via"))
        assertEquals("JIRON $via, N° 12, MZ. A, LT. 5, KM. 23.5, CERCADO II MESETA, JUNIN-CHANCHAMAYO-PERENE", antes["direccion"].asString())
    }

    @Test
    fun `a declaration's secuencia de uso has the padron's three digits`() {
        val contribuyente = post("/api/srtm/contribuyentes", persona(unique()))["id"].asString()
        val dj =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf(
                    "declaracion" to mapOf("tipo_adquisicion" to "COMPRA", "fecha_adquisicion" to "2024-09-04", "fecha_presentacion" to "2026-09-24"),
                    "predio" to
                        mapOf(
                            "sector_catastral" to unique().take(2),
                            "manzana_catastral" to unique().take(2),
                            "condicion" to "URBANO",
                            "tipo_via" to "AVENIDA",
                            "via" to "MARGINAL"
                        ) + PERENE
                )
            )
        val declaracion = dj["declaracion"]
        assertEquals("001", declaracion["secuencia_uso"].asString())

        val edited = put("/api/srtm/declaraciones/${declaracion["id"].asString()}", fields(declaracion) + ("secuencia_uso" to "2"))
        assertEquals("002", edited["secuencia_uso"].asString())
    }

    // a predio as model/import_predios.py creates it from "JIRON <via> Nro.: 12 Mz.: A Lt.: 5 Km.: 23.5 CERCADO II MESETA"
    private fun padron(via: String): Map<String, Any?> =
        mapOf(
            "codigo" to "P-${unique()}",
            "condicion" to "URBANO",
            "direccion" to "JIRON $via Nro.: 12 Mz.: A Lt.: 5 Km.: 23.5 CERCADO II MESETA",
            "tipo_via" to "JIRON",
            "via" to via,
            "numero" to "12",
            "manzana" to "A",
            "lote" to "5",
            "kilometro" to "23.5",
            "tipo_zona" to "CERCADO",
            "habilitacion_urbana" to "II MESETA"
        )

    private fun persona(documento: String) =
        mapOf(
            "tipo_contribuyente" to "PERSONA NATURAL",
            "tipo_documento" to "DNI",
            "numero_documento" to documento,
            "apellido_paterno" to "FLORES",
            "apellido_materno" to "OTINIANO",
            "nombres" to "JUNIOR",
            "sexo" to "HOMBRE",
            "estado_civil" to "SOLTERO"
        )

    // a response as a request body: every field it came with
    private fun fields(node: JsonNode): Map<String, Any?> = json.convertValue(node, Map::class.java).entries.associate { it.key.toString() to it.value }

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

    // eight digits, unique enough for a shared test db: a document number, a code, a name
    private fun unique(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isDigit() }
            .padEnd(8, '0')
            .take(8)

    private companion object {
        val json: JsonMapper = JsonMapper.builder().build()

        val PERENE = mapOf("departamento" to "JUNIN", "provincia" to "CHANCHAMAYO", "distrito" to "PERENE")
    }
}
