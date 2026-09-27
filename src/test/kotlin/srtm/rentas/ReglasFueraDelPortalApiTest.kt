package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
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

// records saved through core's own record api, as the admin does, get the portal's single-record rules
// (setup as in RentasApiTest)
class ReglasFueraDelPortalApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `an admin edit of a surname rebuilds nombre_completo`() {
        val id = inscribir()
        guardar(CONTRIBUYENTE, id, "apellido_paterno" to "RAMOS")
        assertEquals("RAMOS OTINIANO JUNIOR", registro(CONTRIBUYENTE, id)["nombre_completo"].asString())
    }

    @Test
    fun `an admin edit of nombre_completo alone is kept`() {
        val id = inscribir()
        guardar(CONTRIBUYENTE, id, "nombre_completo" to "FLORES . OTINIANO JUNIOR")
        assertEquals("FLORES . OTINIANO JUNIOR", registro(CONTRIBUYENTE, id)["nombre_completo"].asString())
    }

    @Test
    fun `a domicilio saved in the admin is described`() {
        val domicilio =
            crear(
                DOMICILIO,
                mapOf(
                    "contribuyente" to inscribir(),
                    "tipo_domicilio" to "FISCAL",
                    "tipo_predio" to "PREDIO URBANO",
                    "departamento" to "JUNIN",
                    "provincia" to "CHANCHAMAYO",
                    "distrito" to "PERENE",
                    "tipo_via" to "AVENIDA",
                    "via" to "MARGINAL",
                    "numero" to "234"
                )
            )
        assertEquals("AV. MARGINAL, N° 234, JUNIN-CHANCHAMAYO-PERENE", registro(DOMICILIO, domicilio)["descripcion"].asString())
        guardar(DOMICILIO, domicilio, "numero" to "240")
        assertEquals("AV. MARGINAL, N° 240, JUNIN-CHANCHAMAYO-PERENE", registro(DOMICILIO, domicilio)["descripcion"].asString())
    }

    @Test
    fun `an obra saved in the admin gets its total metrado`() {
        val obra =
            crear(
                OBRA_COMPLEMENTARIA,
                mapOf(
                    "declaracion" to declaracion(),
                    "ingreso" to "POR CATEGORIAS",
                    "material" to "LADRILLO",
                    "tipo_obra" to "MUROS PERIMETRICOS O CERCOS",
                    "estado_conservacion" to "BUENO",
                    "anio_construccion" to 2024,
                    "mes_construccion" to 2,
                    "numero_piso" to 1,
                    "cantidad" to 2,
                    "metrado" to 3.5
                )
            )
        assertEquals(7.0, registro(OBRA_COMPLEMENTARIA, obra)["total_metrado"].asDouble())
        guardar(OBRA_COMPLEMENTARIA, obra, "cantidad" to 3)
        assertEquals(10.5, registro(OBRA_COMPLEMENTARIA, obra)["total_metrado"].asDouble())
    }

    // core's record api: a create names what it has, an update replaces every field (so it sends them all)
    private fun crear(
        objeto: String,
        attributes: Map<String, Any?>
    ): String = tree(send("POST", "/api/objects/$objeto/records", mapOf("attributes" to attributes), HttpStatus.CREATED))["id"].asString()

    private fun guardar(
        objeto: String,
        id: String,
        cambio: Pair<String, Any?>
    ) {
        val stored = json.convertValue(registro(objeto, id), Map::class.java).entries.associate { it.key.toString() to it.value }
        send("PUT", "/api/objects/$objeto/records/$id", mapOf("attributes" to stored + cambio), HttpStatus.OK)
    }

    private fun registro(
        objeto: String,
        id: String
    ): JsonNode = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))["attributes"]

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

    private fun declaracion(): String {
        val predio = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))
        return post(
            "/api/srtm/declaraciones",
            mapOf("contribuyente" to inscribir(), "predio" to predio["id"].asString(), "anio" to 2026, "secuencia_uso" to "1")
        )["id"].asString()
    }

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
