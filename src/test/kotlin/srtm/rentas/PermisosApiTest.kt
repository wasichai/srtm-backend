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

// the portal api for a clerk whose role is not ADMIN: core's object and field permissions narrow what the
// catalogs answer and what a save sends, without failing the whole answer or the whole save. same model setup
// as RentasApiTest
class PermisosApiTest : WasichaiIntegrationTest() {
    private lateinit var admin: String

    @BeforeEach
    fun model() {
        admin = bearer()
        applyModel()
    }

    @Test
    fun `a role that cannot read one catalog object still gets the other catalogs`() {
        // a permission grants, none denies: READ on every object of the model but via
        val token = funcionario(objetosDelModelo().filter { it != VIA }.map { permiso(it, "READ") })
        client
            .get()
            .uri("/api/srtm/catalogos")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.contribuyente.tipo_documento")
            .isNotEmpty
            .jsonPath("$.predio.condicion")
            .isNotEmpty
            .jsonPath("$.via")
            .doesNotExist()
    }

    @Test
    fun `a role that cannot write a field still saves the record, and the field keeps its stored value`() {
        val token =
            funcionario(
                listOf("READ", "CREATE", "UPDATE").map { permiso(null, it) },
                bloqueado = CONTRIBUYENTE to "observacion"
            )
        val inscrito = post(admin, "/api/srtm/contribuyentes", persona(uniqueDocumento()) + ("observacion" to "PRIMERA VISITA"))
        val id = inscrito["id"].asString()

        // the whole form, the locked field changed too: it is not sent, so core keeps what it has
        val body = fields(inscrito) + mapOf("apellido_paterno" to "RAMOS", "observacion" to "OTRA")
        val updated = put(token, "/api/srtm/contribuyentes/$id", body)
        assertEquals("RAMOS OTINIANO JUNIOR", updated["nombre_completo"].asString())
        assertEquals("PRIMERA VISITA", updated["observacion"].asString())

        // a new one is saved too, without the field the role may not write
        val nuevo = post(token, "/api/srtm/contribuyentes", persona(uniqueDocumento()) + ("observacion" to "NO VA"))
        assertTrue(nuevo["observacion"] == null || nuevo["observacion"].isNull)
    }

    // a clerk with a role of their own: what it may do, and the one field it may read but not write
    private fun funcionario(
        permisos: List<Map<String, Any?>>,
        bloqueado: Pair<String, String>? = null
    ): String {
        val rol = uniqueName("ROL").uppercase()
        send(admin, "POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED)
        send(admin, "PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
        if (bloqueado != null) {
            val (objeto, campo) = bloqueado
            send(
                admin,
                "PUT",
                "/api/roles/$rol/field-permissions",
                mapOf("fields" to listOf(mapOf("objectName" to objeto, "fieldName" to campo, "read" to true, "write" to false))),
                HttpStatus.OK
            )
        }
        val email = "${rol.lowercase()}@srtm.test"
        send(admin, "POST", "/api/users", mapOf("email" to email, "displayName" to rol, "password" to CLAVE, "roles" to listOf(rol)), HttpStatus.CREATED)
        return bearer(email, CLAVE)
    }

    // objectName null: every object of the organization
    private fun permiso(
        objeto: String?,
        accion: String
    ) = mapOf("objectName" to objeto, "action" to accion)

    private fun objetosDelModelo(): List<String> =
        modelo()["objects"]
            .iterator()
            .asSequence()
            .map { it["name"].asString() }
            .toList()

    private fun modelo(): JsonNode = json.readTree(File("model/model.json"))

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

    // model/apply.py in kotlin, as in RentasApiTest: what is missing gets created (objects, fields, enum options,
    // relationships), what exists is left alone (the test db is shared)
    private fun applyModel() {
        val model = modelo()
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

        val existing: Set<String> = tree(send(admin, "GET", "/api/objects", null, HttpStatus.OK)).names()
        for (obj in model["objects"]) {
            val name = obj["name"].asString()
            val fields = obj["fields"].iterator().asSequence().toList()
            if (name !in existing) {
                send(
                    admin,
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
                    send(admin, "GET", "/api/metadata/objects/$name/fields", null, HttpStatus.OK)
                ).iterator().asSequence().associateBy { it["name"].asString() }
            for (field in fields) {
                val current = stored[field["name"].asString()]
                if (current == null) {
                    send(admin, "POST", "/api/metadata/objects/$name/fields", payload(field), HttpStatus.CREATED)
                } else if (field["type"].asString() == "ENUM") {
                    val have =
                        current["enumOptions"]
                            .iterator()
                            .asSequence()
                            .map { it.asString() }
                            .toList()
                    val missing = options(field) - have.toSet()
                    if (missing.isNotEmpty()) {
                        send(
                            admin,
                            "PUT",
                            "/api/metadata/objects/$name/fields/${field["name"].asString()}",
                            mapOf("enumOptions" to have + missing),
                            HttpStatus.OK
                        )
                    }
                }
            }
        }
        val relationships: Set<String> = tree(send(admin, "GET", "/api/relationships", null, HttpStatus.OK)).names()
        for (rel in model["relationships"]) {
            if (rel["name"].asString() in relationships) continue
            send(
                admin,
                "POST",
                "/api/relationships",
                listOf("name", "label", "inverseLabel", "source", "target", "fieldName").associateWith { rel[it].asString() } +
                    ("type" to "MANY_TO_ONE"),
                HttpStatus.CREATED
            )
            send(
                admin,
                "PUT",
                "/api/metadata/objects/${rel["source"].asString()}/fields/${rel["fieldName"].asString()}",
                mapOf("required" to true),
                HttpStatus.OK
            )
        }
    }

    private fun post(
        token: String,
        path: String,
        body: Map<String, Any?>
    ): JsonNode = tree(send(token, "POST", path, body, HttpStatus.CREATED))

    private fun put(
        token: String,
        path: String,
        body: Map<String, Any?>
    ): JsonNode = tree(send(token, "PUT", path, body, HttpStatus.OK))

    private fun send(
        token: String,
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

        // Core wants at least 8 characters
        const val CLAVE = "clave-del-funcionario"
    }
}
