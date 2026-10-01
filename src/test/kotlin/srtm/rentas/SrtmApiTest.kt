package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.UUID

// the base of the portal api's integration tests: the real model (model/model.json, applied the way model/apply.py
// does it) before every test, the seeded admin's token, and the calls the tests make. the test db is shared by every
// class of the suite: records carry unique documents and codes.
//
// srtm.emision.trabajadores=0: no context of the suite runs the masiva's workers unless its class asks for them
// (EmisionMasivaApiTest). a context stays cached, workers and all, while the other classes run: its workers would
// take the lotes those classes create to look at
@TestPropertySource(properties = ["srtm.emision.trabajadores=0"])
abstract class SrtmApiTest : WasichaiIntegrationTest() {
    // the seeded admin's; a call takes another one where a test needs it
    protected lateinit var token: String

    protected val json: JsonMapper get() = JSON

    @BeforeEach
    fun adminYModelo() {
        token = bearer()
        aplicarModelo()
    }

    protected fun modelo(): JsonNode = json.readTree(File("model/model.json"))

    // model/apply.py in kotlin: what is missing gets created (objects, fields with their geometry, enum options,
    // required relationships), a field model.json no longer requires is relaxed, the rest is left alone. in the
    // organization of `token`'s user: the seeded admin's by default
    protected fun aplicarModelo(token: String = this.token) {
        val model = modelo()
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

        val existing: Set<String> = tree(send("GET", "/api/objects", null, HttpStatus.OK, token)).names()
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
                    HttpStatus.CREATED,
                    token
                )
                continue
            }
            val stored =
                tree(
                    send("GET", "/api/metadata/objects/$name/fields", null, HttpStatus.OK, token)
                ).iterator().asSequence().associateBy { it["name"].asString() }
            for (field in fields) {
                val current = stored[field["name"].asString()]
                if (current == null) {
                    send("POST", "/api/metadata/objects/$name/fields", payload(field), HttpStatus.CREATED, token)
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
                if (change.isNotEmpty()) send("PUT", "/api/metadata/objects/$name/fields/${field["name"].asString()}", change, HttpStatus.OK, token)
            }
        }
        val relationships: Set<String> = tree(send("GET", "/api/relationships", null, HttpStatus.OK, token)).names()
        for (rel in model["relationships"]) {
            if (rel["name"].asString() in relationships) continue
            send(
                "POST",
                "/api/relationships",
                listOf("name", "label", "inverseLabel", "source", "target", "fieldName").associateWith { rel[it].asString() } +
                    ("type" to "MANY_TO_ONE"),
                HttpStatus.CREATED,
                token
            )
            send(
                "PUT",
                "/api/metadata/objects/${rel["source"].asString()}/fields/${rel["fieldName"].asString()}",
                mapOf("required" to true),
                HttpStatus.OK,
                token
            )
        }
    }

    // calls

    // the body of a call that must answer `status`
    protected fun send(
        method: String,
        path: String,
        body: Any?,
        status: HttpStatus,
        token: String = this.token
    ): String {
        val (actual, response) = exchange(method, path, body, token)
        assertEquals(status, actual, "$method $path: $response")
        return response
    }

    // status and body, without asserting: safe off the test's thread
    protected fun exchange(
        method: String,
        path: String,
        body: Any?,
        token: String = this.token
    ): Pair<HttpStatus, String> {
        val spec =
            when (method) {
                "GET" -> client.get().uri(path)
                "DELETE" -> client.delete().uri(path)
                "PUT" -> client.put().uri(path).bodyValue(body!!)
                else -> client.post().uri(path).bodyValue(body!!)
            }
        val result =
            spec
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to (result.responseBody ?: "")
    }

    // a 200, to read with jsonPath
    protected fun get(
        path: String,
        token: String = this.token
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk

    protected fun post(
        path: String,
        body: Map<String, Any?>,
        token: String = this.token
    ): JsonNode = tree(send("POST", path, body, HttpStatus.CREATED, token))

    protected fun put(
        path: String,
        body: Map<String, Any?>,
        token: String = this.token
    ): JsonNode = tree(send("PUT", path, body, HttpStatus.OK, token))

    protected fun delete(path: String) {
        send("DELETE", path, null, HttpStatus.NO_CONTENT)
    }

    // a 400 whose first error names `field`: the problem, to read its messages
    protected fun rejected(
        method: String,
        path: String,
        body: Map<String, Any?>?,
        field: String
    ): JsonNode {
        val problem = tree(send(method, path, body, HttpStatus.BAD_REQUEST))
        assertEquals(field, problem["errors"][0]["field"].asString(), problem.toString())
        return problem
    }

    protected fun tree(body: String): JsonNode = json.readValue(body, JsonNode::class.java)

    // a response as a request body: every field it came with
    protected fun fields(node: JsonNode): Map<String, Any?> = json.convertValue(node, Map::class.java).entries.associate { it.key.toString() to it.value }

    // a clerk (not ADMIN) with a role of their own: what it may do, and the one field it may read but not write
    protected fun funcionario(
        permisos: List<Map<String, Any?>>,
        bloqueado: Pair<String, String>? = null
    ): String {
        val rol = uniqueName("ROL").uppercase()
        send("POST", "/api/roles", mapOf("name" to rol, "label" to rol), HttpStatus.CREATED)
        send("PUT", "/api/roles/$rol/permissions", mapOf("permissions" to permisos), HttpStatus.OK)
        if (bloqueado != null) {
            val (objeto, campo) = bloqueado
            send(
                "PUT",
                "/api/roles/$rol/field-permissions",
                mapOf("fields" to listOf(mapOf("objectName" to objeto, "fieldName" to campo, "read" to true, "write" to false))),
                HttpStatus.OK
            )
        }
        val email = "${rol.lowercase()}@srtm.test"
        send("POST", "/api/users", mapOf("email" to email, "displayName" to rol, "password" to CLAVE, "roles" to listOf(rol)), HttpStatus.CREATED)
        return bearer(email, CLAVE)
    }

    // objectName null: every object of the organization
    protected fun permiso(
        objeto: String?,
        accion: String
    ) = mapOf("objectName" to objeto, "action" to accion)

    // records

    // eight digits, unique enough for the shared test db: a document number, a code, a name
    protected fun uniqueDocumento(): String =
        UUID
            .randomUUID()
            .toString()
            .filter { it.isDigit() }
            .padEnd(8, '0')
            .take(8)

    // FLORES OTINIANO JUNIOR, with that DNI
    protected fun personaNatural(documento: String) =
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

    // a new contribuyente: its id
    protected fun inscribir(): String = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()))["id"].asString()

    // a new predio of the padrón's kind: its id
    protected fun predio(): String =
        post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"))["id"].asString()

    // a declaration of a new contribuyente on a new predio: its id
    protected fun nuevaDeclaracion(): String =
        post("/api/srtm/declaraciones", mapOf("contribuyente" to inscribir(), "predio" to predio(), "anio" to 2026, "secuencia_uso" to "1"))["id"].asString()

    // the padrón's counts
    protected fun resumen(): JsonNode = tree(send("GET", "/api/srtm/resumen", null, HttpStatus.OK))

    private fun JsonNode.names(): Set<String> = iterator().asSequence().map { it["name"].asString() }.toSet()

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()

        // Core wants at least 8 characters
        const val CLAVE = "clave-del-funcionario"
    }
}
