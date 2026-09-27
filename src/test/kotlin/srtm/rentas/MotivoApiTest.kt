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

// the motivo (pages 2 and 11): INSCRIPCION when the portal registers a contribuyente or a declaración, ACTUALIZACION
// once the portal edits it. the rows of their lists and a condómino's recomputed values are no edit of theirs. same
// setup as RentasApiTest
class MotivoApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `an edited contribuyente is an actualizacion, whatever the body says, and its domicilios are not an edit`() {
        val inscrito = post("/api/srtm/contribuyentes", persona())
        val id = inscrito["id"].asString()
        assertEquals("INSCRIPCION", inscrito["motivo"].asString())
        // the wizard's next step: the fiscal domicilio, copied to the contribuyente
        post("/api/srtm/contribuyentes/$id/domicilios", fiscal())
        assertEquals("INSCRIPCION", contribuyente(id)["motivo"].asString())

        val editado = put("/api/srtm/contribuyentes/$id", fields(contribuyente(id)) + mapOf("motivo" to "INSCRIPCION", "observacion" to "EDITADO"))
        assertEquals("ACTUALIZACION", editado["motivo"].asString())
        assertEquals("EDITADO", editado["observacion"].asString())
        // the rest of the datos de la declaración stay
        assertEquals(inscrito["codigo"].asString(), editado["codigo"].asString())
        assertEquals(inscrito["numero_declaracion"].asInt(), editado["numero_declaracion"].asInt())
        assertEquals("DECLARACION JURADA", editado["medio_determinacion"].asString())
        assertEquals("ACTUALIZACION", contribuyente(id)["motivo"].asString())
    }

    @Test
    fun `a contribuyente of the padron, edited, is an actualizacion that gets no code`() {
        // as import_predios.py loads it: straight into core, without motivo or code
        val importado =
            mapOf(
                "tipo_persona" to "NATURAL",
                "tipo_documento" to "DNI",
                "numero_documento" to uniqueDocumento(),
                "nombre_completo" to "MENDOZA TAYPE"
            )
        val id = core(CONTRIBUYENTE, importado)
        val editado = put("/api/srtm/contribuyentes/$id", persona(importado["numero_documento"]) + ("observacion" to "EDITADO"))
        assertEquals("ACTUALIZACION", editado["motivo"].asString())
        assertTrue(vacio(editado["codigo"]))
        assertTrue(vacio(editado["numero_declaracion"]))
    }

    @Test
    fun `an edited declaracion is an actualizacion, and neither its niveles nor its condominos make one`() {
        val predio = predio()
        val creada = post("/api/srtm/declaraciones", declaracion(inscribir(), predio))
        val a = creada["id"].asString()
        assertEquals("INSCRIPCION", creada["motivo"].asString())
        post("/api/srtm/declaraciones/$a/niveles", nivel())
        // a condómino joins: a's condición and % are recomputed
        val b = post("/api/srtm/declaraciones", declaracion(inscribir(), predio) + ("porcentaje_condominio" to 40))["id"].asString()
        assertEquals("CONDOMINO", declaracionDe(a)["condicion_propiedad"].asString())
        assertEquals("INSCRIPCION", declaracionDe(a)["motivo"].asString())

        val editada = put("/api/srtm/declaraciones/$a", fields(declaracionDe(a)) + mapOf("motivo" to "INSCRIPCION", "otros_datos" to "EDITADA"))
        assertEquals("ACTUALIZACION", editada["motivo"].asString())
        assertEquals("EDITADA", editada["otros_datos"].asString())
        // no new number, and the rest of the datos del predio stay
        assertEquals(creada["numero_declaracion"].asInt(), editada["numero_declaracion"].asInt())
        assertEquals("DECLARACION JURADA", editada["medio_determinacion"].asString())
        assertEquals("ACTUALIZACION", declaracionDe(a)["motivo"].asString())
        // nor is its condómino
        assertEquals("INSCRIPCION", declaracionDe(b)["motivo"].asString())
    }

    @Test
    fun `a declaracion of the padron, edited, is an actualizacion that gets no number`() {
        val contribuyente =
            core(
                CONTRIBUYENTE,
                mapOf("tipo_persona" to "NATURAL", "tipo_documento" to "DNI", "numero_documento" to uniqueDocumento(), "nombre_completo" to "MENDOZA TAYPE")
            )
        val importada = core(DECLARACION, mapOf("contribuyente" to contribuyente, "predio" to predio(), "anio" to 2026, "secuencia_uso" to "001"))
        val editada = put("/api/srtm/declaraciones/$importada", fields(declaracionDe(importada)) + ("otros_datos" to "EDITADA"))
        assertEquals("ACTUALIZACION", editada["motivo"].asString())
        assertEquals("EDITADA", editada["otros_datos"].asString())
        assertTrue(vacio(editada["numero_declaracion"]))
    }

    private fun persona(numero: String? = uniqueDocumento()) =
        mapOf(
            "tipo_contribuyente" to "PERSONA NATURAL",
            "tipo_documento" to "DNI",
            "numero_documento" to numero,
            "apellido_paterno" to "FLORES",
            "nombres" to "JUNIOR",
            "sexo" to "HOMBRE",
            "estado_civil" to "SOLTERO"
        )

    private fun fiscal() =
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
            "area_construida" to 120
        )

    private fun inscribir(): String = post("/api/srtm/contribuyentes", persona())["id"].asString()

    private fun predio(): String =
        post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))["id"].asString()

    private fun declaracion(
        contribuyente: String,
        predio: String
    ) = mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to 2026, "secuencia_uso" to "1", "valor_autoavaluo" to 10000)

    private fun contribuyente(id: String): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$id", null, HttpStatus.OK))["contribuyente"]

    private fun declaracionDe(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]

    // a record written straight into core, as the importers do: its id
    private fun core(
        objectName: String,
        attributes: Map<String, Any?>
    ): String = post("/api/objects/$objectName/records", mapOf("attributes" to attributes))["id"].asString()

    private fun vacio(node: JsonNode?) = node == null || node.isNull

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
