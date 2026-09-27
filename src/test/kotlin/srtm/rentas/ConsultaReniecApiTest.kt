package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import srtm.pide.ConsultaDocumento
import srtm.pide.DatosPersona
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.io.File
import java.util.UUID

// PIDE RENIEC (pages 3, 8 and 15): the portal asks a DNI's names, and a contribuyente, relacionado or transferente
// saved with fuente PIDE RENIEC needs that consulta, with the same names. RENIEC is a double here: never the real
// PIDE. same setup as RentasApiTest
class ConsultaReniecApiTest : WasichaiIntegrationTest() {
    // knows every DNI but DESCONOCIDO, all by the same names
    @TestConfiguration
    class Doble {
        @Bean
        @Primary
        fun reniecDoble(): ConsultaDocumento =
            object : ConsultaDocumento {
                override suspend fun consultar(
                    tipo: String,
                    numero: String
                ): DatosPersona? =
                    if (tipo != "DNI" ||
                        numero == DESCONOCIDO
                    ) {
                        null
                    } else {
                        DatosPersona(tipo, numero, "FLORES", "OTINIANO", "JUNIOR PAOLO", "SOLTERO")
                    }
            }
    }

    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `a DNI answers RENIEC's names, and another document or one RENIEC does not know is a 404`() {
        val dni = uniqueDocumento()
        val datos = tree(send("GET", "/api/srtm/documentos/DNI/$dni", null, HttpStatus.OK))
        assertEquals("FLORES", datos["apellido_paterno"].asString())
        assertEquals("OTINIANO", datos["apellido_materno"].asString())
        assertEquals("JUNIOR PAOLO", datos["nombres"].asString())
        assertEquals("SOLTERO", datos["estado_civil"].asString())
        assertEquals(dni, datos["numero_documento"].asString())
        assertEquals("PIDE RENIEC", datos["fuente_informacion"].asString())

        send("GET", "/api/srtm/documentos/DNI/$DESCONOCIDO", null, HttpStatus.NOT_FOUND)
        send("GET", "/api/srtm/documentos/DNI/4355456", null, HttpStatus.NOT_FOUND)
        send("GET", "/api/srtm/documentos/RUC/20131312955", null, HttpStatus.NOT_FOUND)
        client
            .get()
            .uri("/api/srtm/documentos/DNI/$dni")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a contribuyente with PIDE RENIEC needs the consulta of its DNI, with the same names`() {
        val dni = uniqueDocumento()
        val nuevo = contribuyente(dni) + ("fuente_informacion" to "PIDE RENIEC")
        assertEquals("fuente_informacion", rechazo("POST", "/api/srtm/contribuyentes", nuevo))

        consultar(dni)
        assertEquals("fuente_informacion", rechazo("POST", "/api/srtm/contribuyentes", nuevo + ("nombres" to "JUNIOR")))
        val inscrito = post("/api/srtm/contribuyentes", nuevo)
        assertEquals("PIDE RENIEC", inscrito["fuente_informacion"].asString())
        assertEquals("FLORES OTINIANO JUNIOR PAOLO", inscrito["nombre_completo"].asString())

        // its names stay RENIEC's while nobody changes them; changed, they are the clerk's
        val id = inscrito["id"].asString()
        put("/api/srtm/contribuyentes/$id", fields(inscrito) + ("observacion" to "SIN CAMBIOS EN LOS NOMBRES"))
        assertEquals("fuente_informacion", rechazo("PUT", "/api/srtm/contribuyentes/$id", fields(inscrito) + ("nombres" to "PAOLO")))
        val manual = put("/api/srtm/contribuyentes/$id", fields(inscrito) + mapOf("nombres" to "PAOLO", "fuente_informacion" to "MANUAL"))
        assertEquals("PAOLO", manual["nombres"].asString())
    }

    @Test
    fun `relacionados and transferentes with PIDE RENIEC need the consulta too`() {
        val contribuyente = post("/api/srtm/contribuyentes", contribuyente(uniqueDocumento()))["id"].asString()
        val dni = uniqueDocumento()
        val conyuge = persona(dni) + mapOf("tipo_relacionado" to "CONYUGE", "fuente_informacion" to "PIDE RENIEC")
        assertEquals("fuente_informacion", rechazo("POST", "/api/srtm/contribuyentes/$contribuyente/relacionados", conyuge))
        val transferente =
            persona(dni) +
                mapOf(
                    "fuente_informacion" to "PIDE RENIEC",
                    "porcentaje_transferido" to 50,
                    "departamento" to "JUNIN",
                    "provincia" to "CHANCHAMAYO",
                    "distrito" to "PERENE",
                    "descripcion_domicilio" to "JR. LIMA 123"
                )
        val declaracion = declaracion(contribuyente)
        assertEquals("fuente_informacion", rechazo("POST", "/api/srtm/declaraciones/$declaracion/transferentes", transferente))

        consultar(dni)
        val relacionado = post("/api/srtm/contribuyentes/$contribuyente/relacionados", conyuge)
        assertEquals("PIDE RENIEC", relacionado["fuente_informacion"].asString())
        val otro = fields(relacionado) + ("apellido_paterno" to "FLOREZ")
        assertEquals("fuente_informacion", rechazo("PUT", "/api/srtm/relacionados/${relacionado["id"].asString()}", otro))
        assertEquals("PIDE RENIEC", post("/api/srtm/declaraciones/$declaracion/transferentes", transferente)["fuente_informacion"].asString())
    }

    private fun consultar(dni: String) = send("GET", "/api/srtm/documentos/DNI/$dni", null, HttpStatus.OK)

    // RENIEC's names for the double's every DNI
    private fun persona(dni: String) =
        mapOf(
            "tipo_documento" to "DNI",
            "numero_documento" to dni,
            "apellido_paterno" to "FLORES",
            "apellido_materno" to "OTINIANO",
            "nombres" to "JUNIOR PAOLO"
        )

    private fun contribuyente(dni: String) = persona(dni) + mapOf("tipo_contribuyente" to "PERSONA NATURAL", "sexo" to "HOMBRE", "estado_civil" to "SOLTERO")

    // a declaration of the contribuyente on a new predio: its id
    private fun declaracion(contribuyente: String): String {
        val predio = post("/api/srtm/predios", mapOf("codigo" to "R-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))
        return post(
            "/api/srtm/declaraciones",
            mapOf("contribuyente" to contribuyente, "predio" to predio["id"].asString(), "anio" to 2026, "secuencia_uso" to "1")
        )["id"].asString()
    }

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
        const val DESCONOCIDO = "99999999"
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
