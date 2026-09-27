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
import java.math.BigDecimal
import java.util.UUID

// the condominio of a predio, año and secuencia de uso: the backend derives condición, % and values after every
// create, update and delete of a declaración, keeps the parts within 100 % and adds condóminos from a declaración
// (setup as in RentasApiTest)
class CondominioApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `a sole titular holds its predio at 100 percent, with its values derived`() {
        val d = declarar(inscribir(), predio(), "porcentaje_condominio" to 40, "condicion_propiedad" to "CONDOMINO", "valor_afecto" to 1)
        assertEquals("PROPIETARIO UNICO", d["condicion_propiedad"].asString())
        assertDecimal("100", d["porcentaje_condominio"])
        assertDecimal("10000.50", d["valor_condominio"])
        assertDecimal("9000.50", d["valor_afecto"])
    }

    @Test
    fun `two condominos each get their part of the autoavaluo`() {
        val predio = predio()
        val a = declarar(inscribir(), predio)["id"].asString()
        val b = declarar(inscribir(), predio, "porcentaje_condominio" to 40, "deduccion" to null)
        assertEquals("CONDOMINO", b["condicion_propiedad"].asString())
        assertDecimal("40", b["porcentaje_condominio"])
        assertDecimal("4000.20", b["valor_condominio"])
        assertDecimal("4000.20", b["valor_afecto"])
        // the sole titular's 100 % gave the newcomer its part
        val deA = declaracion(a)
        assertEquals("CONDOMINO", deA["condicion_propiedad"].asString())
        assertDecimal("60", deA["porcentaje_condominio"])
        assertDecimal("6000.30", deA["valor_condominio"])
        assertDecimal("5000.30", deA["valor_afecto"])
    }

    @Test
    fun `parts adding up to more than 100 percent are a 400 on porcentaje_condominio`() {
        val predio = predio()
        val a = declarar(inscribir(), predio)["id"].asString()
        declarar(inscribir(), predio, "porcentaje_condominio" to 40)
        val stored = declaracion(a)
        rejected("PUT", "/api/srtm/declaraciones/$a", fields(stored) + ("porcentaje_condominio" to 70), "porcentaje_condominio")
        assertDecimal("60", declaracion(a)["porcentaje_condominio"])
        rejected("POST", "/api/srtm/declaraciones", body(inscribir(), predio) + ("porcentaje_condominio" to 10), "porcentaje_condominio")
        // within 100 % an update is taken
        assertDecimal("50", put("/api/srtm/declaraciones/$a", fields(stored) + ("porcentaje_condominio" to 50))["porcentaje_condominio"])
    }

    @Test
    fun `a condomino added from a declaration declares the same predio, with its caracteristicas`() {
        val predio = predio()
        val origen =
            declarar(inscribir(), predio, "uso" to "COMERCIAL", "area_terreno" to 200, "clase_uso" to "COMERCIAL", "tipo_adquisicion" to "COMPRA")
        val a = origen["id"].asString()
        post(
            "/api/srtm/declaraciones/$a/niveles",
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2023,
                "mes_construccion" to 1,
                "material" to "LADRILLO",
                "estado_conservacion" to "BUENO",
                "area_construida" to 200
            )
        )
        val b = inscribir()

        val nuevo = post("/api/srtm/declaraciones/$a/condominos", mapOf("contribuyente" to b, "porcentaje_condominio" to 25))
        assertEquals(b, nuevo["contribuyente"].asString())
        assertEquals(predio, nuevo["predio"].asString())
        assertEquals(2026, nuevo["anio"].asInt())
        assertEquals("1", nuevo["secuencia_uso"].asString())
        assertEquals("COMERCIAL", nuevo["uso"].asString())
        assertDecimal("200", nuevo["area_terreno"])
        assertTrue(nuevo["tipo_adquisicion"] == null || nuevo["tipo_adquisicion"].isNull)
        assertTrue(nuevo["numero_declaracion"].asInt() > origen["numero_declaracion"].asInt())
        assertEquals("CONDOMINO", nuevo["condicion_propiedad"].asString())
        assertDecimal("25", nuevo["porcentaje_condominio"])
        assertDecimal("2500.13", nuevo["valor_condominio"])
        assertDecimal("200", tree(send("GET", "/api/srtm/declaraciones/${nuevo["id"].asString()}/niveles", null, HttpStatus.OK))[0]["area_construida"])
        assertDecimal("75", declaracion(a)["porcentaje_condominio"])

        // once is enough
        rejected("POST", "/api/srtm/declaraciones/$a/condominos", mapOf("contribuyente" to b, "porcentaje_condominio" to 5), "contribuyente")
    }

    @Test
    fun `when a condomino's declaration goes, the other is propietario unico at 100 percent again`() {
        val predio = predio()
        val a = declarar(inscribir(), predio)["id"].asString()
        val b = declarar(inscribir(), predio, "porcentaje_condominio" to 40)["id"].asString()
        // its lists go with it
        post("/api/srtm/declaraciones/$b/frentes", mapOf("tipo_via" to "AVENIDA", "via" to "MARGINAL", "frontis" to 7))

        delete("/api/srtm/declaraciones/$b")

        val deA = declaracion(a)
        assertEquals("PROPIETARIO UNICO", deA["condicion_propiedad"].asString())
        assertDecimal("100", deA["porcentaje_condominio"])
        assertDecimal("10000.50", deA["valor_condominio"])
        get("/api/srtm/predios/$predio/declaraciones?anio=2026").expectBody().jsonPath("$.length()").isEqualTo(1)
    }

    private fun assertDecimal(
        expected: String,
        actual: JsonNode?
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual!!.decimalValue()), "expected $expected, got $actual")

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

    // a predio of 10000.50, with a deducción of 1000
    private fun body(
        contribuyente: String,
        predio: String
    ) = mapOf(
        "contribuyente" to contribuyente,
        "predio" to predio,
        "anio" to 2026,
        "secuencia_uso" to "1",
        "valor_autoavaluo" to 10000.50,
        "deduccion" to 1000
    )

    private fun declarar(
        contribuyente: String,
        predio: String,
        vararg extra: Pair<String, Any?>
    ): JsonNode = post("/api/srtm/declaraciones", body(contribuyente, predio) + extra)

    private fun declaracion(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]

    // a response as a request body: every field it came with
    private fun fields(node: JsonNode): Map<String, Any?> = json.convertValue(node, Map::class.java).entries.associate { it.key.toString() to it.value }

    private fun rejected(
        method: String,
        path: String,
        body: Map<String, Any?>,
        field: String
    ) {
        val errors = tree(send(method, path, body, HttpStatus.BAD_REQUEST))["errors"]
        assertEquals(field, errors[0]["field"].asString())
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
    ): JsonNode = tree(send("POST", path, body, HttpStatus.CREATED))

    private fun put(
        path: String,
        body: Map<String, Any?>
    ): JsonNode = tree(send("PUT", path, body, HttpStatus.OK))

    private fun delete(path: String) {
        client
            .delete()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent
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
