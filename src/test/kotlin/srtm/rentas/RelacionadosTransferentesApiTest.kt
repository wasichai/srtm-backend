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

// relacionados and transferentes (pages 8 and 15): a code per parent, the razón social of a company (RUC) or the
// names of anyone else, and the fuente de información. same setup as RentasApiTest
class RelacionadosTransferentesApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `relacionados are coded per contribuyente, and an update keeps the code`() {
        val id = inscribir()
        val first = post("/api/srtm/contribuyentes/$id/relacionados", conyuge())
        val second = post("/api/srtm/contribuyentes/$id/relacionados", conyuge() + ("codigo" to "777"))
        assertEquals("001", first["codigo"].asString())
        assertEquals("002", second["codigo"].asString())
        assertEquals(FUENTE_MANUAL, first["fuente_informacion"].asString())
        assertEquals("001", post("/api/srtm/contribuyentes/${inscribir()}/relacionados", conyuge())["codigo"].asString())

        val changed = put("/api/srtm/relacionados/${first["id"].asString()}", fields(first) + mapOf("codigo" to "999", "nombres" to "DUBERLI JOSE"))
        assertEquals("001", changed["codigo"].asString())
        assertEquals("DUBERLI JOSE", changed["nombres"].asString())
        get("/api/srtm/contribuyentes/$id/relacionados")
            .expectBody()
            .jsonPath("$[0].codigo")
            .isEqualTo("001")
            .jsonPath("$[1].codigo")
            .isEqualTo("002")
    }

    @Test
    fun `a relacionado with RUC is named by its razon social, anyone else by names`() {
        val id = inscribir()
        val apoderado = mapOf("tipo_relacionado" to "APODERADO", "tipo_documento" to "RUC", "numero_documento" to "20123456789")
        assertEquals("razon_social", rechazo("POST", "/api/srtm/contribuyentes/$id/relacionados", apoderado))
        val empresa = post("/api/srtm/contribuyentes/$id/relacionados", apoderado + ("razon_social" to "INVERSIONES PERENE SAC"))
        assertEquals("INVERSIONES PERENE SAC", empresa["razon_social"].asString())
        assertTrue(empresa["nombres"] == null || empresa["nombres"].isNull)

        assertEquals("razon_social", rechazo("PUT", "/api/srtm/relacionados/${empresa["id"].asString()}", fields(empresa) + ("razon_social" to " ")))
        assertEquals("nombres", rechazo("POST", "/api/srtm/contribuyentes/$id/relacionados", conyuge() - "nombres"))
    }

    @Test
    fun `transferentes are coded per declaracion, and one with RUC needs its razon social`() {
        val declaracion = declaracion()
        val persona = post("/api/srtm/declaraciones/$declaracion/transferentes", transferente())
        assertEquals("001", persona["codigo"].asString())
        assertEquals(FUENTE_MANUAL, persona["fuente_informacion"].asString())

        val ruc = transferente() - listOf("apellido_paterno", "nombres") + mapOf("tipo_documento" to "RUC", "numero_documento" to "20123456789")
        assertEquals("razon_social", rechazo("POST", "/api/srtm/declaraciones/$declaracion/transferentes", ruc))
        val empresa = post("/api/srtm/declaraciones/$declaracion/transferentes", ruc + ("razon_social" to "INVERSIONES PERENE SAC"))
        assertEquals("002", empresa["codigo"].asString())
        assertEquals("INVERSIONES PERENE SAC", empresa["razon_social"].asString())
        assertEquals("001", post("/api/srtm/declaraciones/${declaracion()}/transferentes", transferente())["codigo"].asString())

        val otra = fields(empresa) + mapOf("codigo" to null, "fuente_informacion" to "PIDE SUNAT")
        val changed = put("/api/srtm/transferentes/${empresa["id"].asString()}", otra)
        assertEquals("002", changed["codigo"].asString())
        assertEquals("PIDE SUNAT", changed["fuente_informacion"].asString())
        assertEquals("nombres", rechazo("PUT", "/api/srtm/transferentes/${persona["id"].asString()}", fields(persona) + ("nombres" to null)))
    }

    private fun conyuge() = mapOf("tipo_relacionado" to "CONYUGE", "tipo_documento" to "DNI", "numero_documento" to "43434352", "nombres" to "DUBERLI")

    private fun transferente() =
        mapOf(
            "porcentaje_transferido" to 50,
            "tipo_documento" to "DNI",
            "numero_documento" to "43434352",
            "apellido_paterno" to "NEIRA",
            "nombres" to "DUBERLI",
            "departamento" to "JUNIN",
            "provincia" to "CHANCHAMAYO",
            "distrito" to "PERENE",
            "descripcion_domicilio" to "JR. LIMA 123"
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

    // a declaration of a new contribuyente on a new predio: its id
    private fun declaracion(): String {
        val predio = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))
        return post(
            "/api/srtm/declaraciones",
            mapOf("contribuyente" to inscribir(), "predio" to predio["id"].asString(), "anio" to 2026, "secuencia_uso" to "1")
        )["id"].asString()
    }

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

    // the field a 400 names
    private fun rechazo(
        method: String,
        path: String,
        body: Map<String, Any?>
    ): String = tree(send(method, path, body, HttpStatus.BAD_REQUEST))["errors"][0]["field"].asString()

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
