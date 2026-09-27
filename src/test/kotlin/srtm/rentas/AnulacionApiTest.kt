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
import java.time.LocalDate
import java.util.UUID

// a declaración annulled (a descargo) leaves its condominio and the fichas' totales but stays listed and read-only;
// predio, contribuyente and declaración are deleted only while nothing hangs from them (setup as in RentasApiTest)
class AnulacionApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `an annulled declaration leaves its condominio and the totales, and stays listed`() {
        val predio = predio()
        val a = inscribir()
        val b = inscribir()
        val deA = declarar(a, predio)["id"].asString()
        val deB = declarar(b, predio, "porcentaje_condominio" to 40)["id"].asString()

        rejected("POST", "/api/srtm/declaraciones/$deB/anular", mapOf("motivo_anulacion" to " "), "motivo_anulacion")
        val anulada = tree(send("POST", "/api/srtm/declaraciones/$deB/anular", mapOf("motivo_anulacion" to "Declarada dos veces"), HttpStatus.OK))
        assertEquals("ANULADA", anulada["estado"].asString())
        assertEquals("DESCARGO", anulada["motivo"].asString())
        assertEquals("Declarada dos veces", anulada["motivo_anulacion"].asString())
        assertEquals(LocalDate.now().toString(), anulada["fecha_anulacion"].asString())

        // the titular left holds the predio alone again
        val restante = declaracion(deA)
        assertEquals("PROPIETARIO UNICO", restante["condicion_propiedad"].asString())
        assertDecimal("100", restante["porcentaje_condominio"])
        assertDecimal("10000.50", restante["valor_condominio"])

        val fichaB = tree(send("GET", "/api/srtm/contribuyentes/$b?anio=2026", null, HttpStatus.OK))
        assertEquals(0, fichaB["predios"].asInt())
        assertEquals(0, fichaB["totales"]["declaraciones"].asInt())
        assertDecimal("0", fichaB["totales"]["autoavaluo"])
        val fichaPredio = tree(send("GET", "/api/srtm/predios/$predio?anio=2026", null, HttpStatus.OK))
        assertEquals(1, fichaPredio["titulares"].asInt())
        assertDecimal("10000.50", fichaPredio["totales"]["autoavaluo"])
        assertDecimal("9000.50", fichaPredio["totales"]["valor_afecto"])

        // the history keeps it
        val deContribuyente = tree(send("GET", "/api/srtm/contribuyentes/$b/declaraciones", null, HttpStatus.OK))
        assertEquals("ANULADA", deContribuyente.single()["declaracion"]["estado"].asString())
        assertEquals(2, tree(send("GET", "/api/srtm/predios/$predio/declaraciones?anio=2026", null, HttpStatus.OK)).size())
    }

    @Test
    fun `an annulled declaration is read-only`() {
        val d = declarar(inscribir(), predio())
        val id = d["id"].asString()
        val frente = post("/api/srtm/declaraciones/$id/frentes", mapOf("tipo_via" to "AVENIDA", "via" to "MARGINAL", "frontis" to 7))
        send("POST", "/api/srtm/declaraciones/$id/anular", mapOf("motivo_anulacion" to "Error de digitación"), HttpStatus.OK)
        val stored = declaracion(id)

        rejected("PUT", "/api/srtm/declaraciones/$id", fields(stored) + ("uso" to "COMERCIAL"), "estado")
        rejected("POST", "/api/srtm/declaraciones/$id/anular", mapOf("motivo_anulacion" to "Otra vez"), "estado")
        rejected("POST", "/api/srtm/declaraciones/$id/niveles", nivel(), "estado")
        rejected("PUT", "/api/srtm/frentes/${frente["id"].asString()}", fields(frente) + ("frontis" to 9), "estado")
        rejected("DELETE", "/api/srtm/frentes/${frente["id"].asString()}", null, "estado")
        rejected("POST", "/api/srtm/declaraciones/$id/condominos", mapOf("contribuyente" to inscribir(), "porcentaje_condominio" to 10), "estado")
        assertEquals("Error de digitación", declaracion(id)["motivo_anulacion"].asString())
    }

    @Test
    fun `once annulled, the contribuyente may declare the predio, year and secuencia again`() {
        val contribuyente = inscribir()
        val predio = predio()
        val primera = declarar(contribuyente, predio)["id"].asString()
        send("POST", "/api/srtm/declaraciones/$primera/anular", mapOf("motivo_anulacion" to "Área mal declarada"), HttpStatus.OK)

        val otra = declarar(contribuyente, predio)
        assertEquals("VIGENTE", otra["estado"].asString())
        assertEquals("PROPIETARIO UNICO", otra["condicion_propiedad"].asString())
        assertDecimal("100", otra["porcentaje_condominio"])
    }

    @Test
    fun `a new declaration is vigente, and an edit neither annuls it nor loses its estado`() {
        val d = declarar(inscribir(), predio())
        assertEquals("VIGENTE", d["estado"].asString())
        val id = d["id"].asString()

        val editada = put("/api/srtm/declaraciones/$id", fields(d) + mapOf("estado" to "ANULADA", "motivo_anulacion" to "por el cuerpo", "uso" to "COMERCIAL"))
        assertEquals("VIGENTE", editada["estado"].asString())
        assertTrue(editada["motivo_anulacion"] == null || editada["motivo_anulacion"].isNull)
        assertEquals("VIGENTE", put("/api/srtm/declaraciones/$id", fields(editada) - "estado")["estado"].asString())
    }

    @Test
    fun `a predio with declarations is not deleted, one without is`() {
        val predio = predio()
        val d = declarar(inscribir(), predio)["id"].asString()
        send("POST", "/api/srtm/declaraciones/$d/anular", mapOf("motivo_anulacion" to "Duplicada"), HttpStatus.OK)
        // annulled or not, it is still its history
        assertEquals("El predio tiene 1 declaración jurada: no se puede eliminar", conflict("/api/srtm/predios/$predio"))
        send("GET", "/api/srtm/predios/$predio", null, HttpStatus.OK)

        val libre = predio()
        send("DELETE", "/api/srtm/predios/$libre", null, HttpStatus.NO_CONTENT)
        send("GET", "/api/srtm/predios/$libre", null, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a contribuyente with declarations is not deleted, one without goes with its lists`() {
        val contribuyente = inscribir()
        declarar(contribuyente, predio())
        declarar(contribuyente, predio())
        assertEquals("El contribuyente tiene 2 declaraciones juradas: no se puede eliminar", conflict("/api/srtm/contribuyentes/$contribuyente"))

        val libre = inscribir()
        post("/api/srtm/contribuyentes/$libre/domicilios", domicilio())
        post("/api/srtm/contribuyentes/$libre/medios-contacto", mapOf("tipo" to "TELEFONO CELULAR", "valor" to "987654321", "principal" to true))
        send("DELETE", "/api/srtm/contribuyentes/$libre", null, HttpStatus.NO_CONTENT)
        send("GET", "/api/srtm/contribuyentes/$libre", null, HttpStatus.NOT_FOUND)
        assertEquals(0, tree(send("GET", "/api/srtm/contribuyentes/$libre/domicilios", null, HttpStatus.OK)).size())
        assertEquals(0, tree(send("GET", "/api/srtm/contribuyentes/$libre/medios-contacto", null, HttpStatus.OK)).size())
    }

    @Test
    fun `a declaration with lists is annulled instead of deleted`() {
        val id = declarar(inscribir(), predio())["id"].asString()
        post("/api/srtm/declaraciones/$id/niveles", nivel())
        assertEquals("La declaración tiene niveles de construcción: anúlela en lugar de eliminarla", conflict("/api/srtm/declaraciones/$id"))
        declaracion(id)

        val vacia = declarar(inscribir(), predio())["id"].asString()
        send("DELETE", "/api/srtm/declaraciones/$vacia", null, HttpStatus.NO_CONTENT)
        send("GET", "/api/srtm/declaraciones/$vacia", null, HttpStatus.NOT_FOUND)
    }

    private fun conflict(path: String): String = tree(send("DELETE", path, null, HttpStatus.CONFLICT))["detail"].asString()

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

    private fun domicilio() =
        mapOf(
            "tipo_domicilio" to "FISCAL",
            "tipo_predio" to "PREDIO URBANO",
            "ubigeo" to "120302",
            "departamento" to "JUNIN",
            "provincia" to "CHANCHAMAYO",
            "distrito" to "PERENE",
            "tipo_via" to "AVENIDA",
            "via" to "MARGINAL",
            "numero" to "234"
        )

    private fun nivel() =
        mapOf(
            "tipo_nivel" to "PISO",
            "numero_piso" to 1,
            "anio_construccion" to 2023,
            "mes_construccion" to 1,
            "material" to "LADRILLO",
            "estado_conservacion" to "BUENO",
            "area_construida" to 200
        )

    // a predio of 10000.50, with a deducción of 1000
    private fun declarar(
        contribuyente: String,
        predio: String,
        vararg extra: Pair<String, Any?>
    ): JsonNode =
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "valor_autoavaluo" to 10000.50,
                "deduccion" to 1000
            ) + extra
        )

    private fun declaracion(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]

    // a response as a request body: every field it came with
    private fun fields(node: JsonNode): Map<String, Any?> = json.convertValue(node, Map::class.java).entries.associate { it.key.toString() to it.value }

    private fun rejected(
        method: String,
        path: String,
        body: Map<String, Any?>?,
        field: String
    ) {
        val errors = tree(send(method, path, body, HttpStatus.BAD_REQUEST))["errors"]
        assertEquals(field, errors[0]["field"].asString())
    }

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
