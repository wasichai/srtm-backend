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
import java.time.LocalDate
import java.util.UUID

// the portal api against the real model (model/model.json, applied the way model/apply.py does it):
// search, fichas, declarations with their other side, the registro de contribuyente with its lists,
// catalogs, validation and auth
class RentasApiTest : WasichaiIntegrationTest() {
    private lateinit var token: String

    @BeforeEach
    fun model() {
        token = bearer()
        applyModel()
    }

    @Test
    fun `a contribuyente, its predio and its declaration, seen from both sides`() {
        val documento = uniqueDocumento()
        val contribuyente = inscribir(documento)
        val codigo = "T-$documento"
        val predio = post("/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))
        val contribuyenteId = contribuyente["id"].asString()
        val predioId = predio["id"].asString()
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyenteId,
                "predio" to predioId,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "valor_autoavaluo" to 10080.45,
                "valor_afecto" to 8000
            )
        )

        get("/api/srtm/contribuyentes?q=$documento")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].numero_documento")
            .isEqualTo(documento)
        get("/api/srtm/contribuyentes/$contribuyenteId?anio=2026")
            .expectBody()
            .jsonPath("$.contribuyente.id")
            .isEqualTo(contribuyenteId)
            .jsonPath("$.predios")
            .isEqualTo(1)
            .jsonPath("$.totales.autoavaluo")
            .isEqualTo(10080.45)
        get("/api/srtm/contribuyentes/$contribuyenteId/declaraciones")
            .expectBody()
            .jsonPath("$[0].declaracion.anio")
            .isEqualTo(2026)
            .jsonPath("$[0].predio.codigo")
            .isEqualTo(codigo)
        get("/api/srtm/predios/$predioId/declaraciones?anio=2026")
            .expectBody()
            .jsonPath("$[0].contribuyente.numero_documento")
            .isEqualTo(documento)
        get("/api/srtm/predios/$predioId")
            .expectBody()
            .jsonPath("$.titulares")
            .isEqualTo(1)
    }

    @Test
    fun `an inscription is numbered, dated and named by the backend`() {
        val first = inscribir(uniqueDocumento())
        val second = inscribir(uniqueDocumento())
        assertTrue(Regex("\\d{6}").matches(first["codigo"].asString()))
        assertEquals(first["codigo"].asString().toInt() + 1, second["codigo"].asString().toInt())
        assertEquals(first["numero_declaracion"].asInt() + 1, second["numero_declaracion"].asInt())
        assertEquals(LocalDate.now().toString(), first["fecha_registro"].asString())
        assertEquals("INSCRIPCION", first["motivo"].asString())
        assertEquals("MANUAL", first["fuente_informacion"].asString())
        // derived from tipo_contribuyente and the name parts
        assertEquals("NATURAL", first["tipo_persona"].asString())
        assertEquals("FLORES OTINIANO JUNIOR", first["nombre_completo"].asString())
    }

    @Test
    fun `an update keeps what the backend owns, and a field sent as null is cleared`() {
        val inscrito = inscribir(uniqueDocumento(), "observacion" to "PRIMERA VISITA")
        val id = inscrito["id"].asString()
        val body =
            fields(inscrito) +
                mapOf("observacion" to null, "codigo" to "999999", "apellido_paterno" to "RAMOS")
        val updated = put("/api/srtm/contribuyentes/$id", body)
        assertTrue(updated["observacion"] == null || updated["observacion"].isNull)
        assertEquals(inscrito["codigo"].asString(), updated["codigo"].asString())
        assertEquals("RAMOS OTINIANO JUNIOR", updated["nombre_completo"].asString())
    }

    @Test
    fun `a repeated document is a 400 on that field`() {
        val documento = uniqueDocumento()
        inscribir(documento)
        client
            .post()
            .uri("/api/srtm/contribuyentes")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(persona(documento))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("numero_documento")
    }

    @Test
    fun `an active fiscal domicilio is described and becomes the contribuyente's domicilio fiscal`() {
        val id = inscribir(uniqueDocumento())["id"].asString()
        val domicilio =
            post(
                "/api/srtm/contribuyentes/$id/domicilios",
                mapOf(
                    "tipo_domicilio" to "FISCAL",
                    "tipo_predio" to "PREDIO URBANO",
                    "ubigeo" to "120302",
                    "departamento" to "JUNIN",
                    "provincia" to "CHANCHAMAYO",
                    "distrito" to "PERENE",
                    "tipo_via" to "AVENIDA",
                    "via" to "MARGINAL",
                    "numero" to "234",
                    "descripcion" to "lo que diga el cliente no cuenta"
                )
            )
        val descripcion = "AVENIDA MARGINAL, N° 234, JUNIN-CHANCHAMAYO-PERENE"
        assertEquals(descripcion, domicilio["descripcion"].asString())
        assertEquals("ACTIVO", domicilio["estado"].asString())
        get("/api/srtm/contribuyentes/$id")
            .expectBody()
            .jsonPath("$.contribuyente.domicilio_fiscal")
            .isEqualTo(descripcion)
            .jsonPath("$.contribuyente.domicilio_distrito")
            .isEqualTo("PERENE")

        val domicilioId = domicilio["id"].asString()
        val moved = put("/api/srtm/domicilios/$domicilioId", fields(domicilio) + mapOf("numero" to "240", "contribuyente" to UUID.randomUUID().toString()))
        assertEquals(id, moved["contribuyente"].asString())
        get("/api/srtm/contribuyentes/$id")
            .expectBody()
            .jsonPath("$.contribuyente.domicilio_fiscal")
            .isEqualTo("AVENIDA MARGINAL, N° 240, JUNIN-CHANCHAMAYO-PERENE")

        delete("/api/srtm/domicilios/$domicilioId")
        get("/api/srtm/contribuyentes/$id/domicilios")
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(0)
    }

    @Test
    fun `relacionados, medios de contacto and sustentos hang from their contribuyente`() {
        val id = inscribir(uniqueDocumento())["id"].asString()
        val relacionado =
            post(
                "/api/srtm/contribuyentes/$id/relacionados",
                mapOf("tipo_relacionado" to "CONYUGE", "tipo_documento" to "DNI", "numero_documento" to "43434352", "nombres" to "DUBERLI")
            )
        post("/api/srtm/contribuyentes/$id/medios-contacto", mapOf("tipo" to "TELEFONO CELULAR", "valor" to "987654321", "principal" to true))
        post(
            "/api/srtm/contribuyentes/$id/sustentos",
            mapOf("documento" to "PARTIDA REGISTRAL", "numero_documento" to "11002233", "tipo_presentacion" to "COPIA SIMPLE")
        )
        get("/api/srtm/contribuyentes/$id/relacionados")
            .expectBody()
            .jsonPath("$[0].tipo_relacionado")
            .isEqualTo("CONYUGE")
            .jsonPath("$[0].estado")
            .isEqualTo("ACTIVO")
        get("/api/srtm/contribuyentes/$id/medios-contacto")
            .expectBody()
            .jsonPath("$[0].principal")
            .isEqualTo(true)
        get("/api/srtm/contribuyentes/$id/sustentos")
            .expectBody()
            .jsonPath("$[0].documento")
            .isEqualTo("PARTIDA REGISTRAL")

        val relacionadoId = relacionado["id"].asString()
        val changed = put("/api/srtm/relacionados/$relacionadoId", fields(relacionado) + ("tipo_relacionado" to "APODERADO"))
        assertEquals("APODERADO", changed["tipo_relacionado"].asString())
        delete("/api/srtm/relacionados/$relacionadoId")
        get("/api/srtm/contribuyentes/$id/relacionados")
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(0)
    }

    @Test
    fun `catalogs list the model's enum options, the ubigeo and the vias`() {
        get("/api/srtm/catalogos")
            .expectBody()
            .jsonPath("$.contribuyente.tipo_documento[1]")
            .isEqualTo("DNI")
            .jsonPath("$.predio.condicion[0]")
            .isEqualTo("URBANO")
            .jsonPath("$.domicilio.tipo_domicilio[0]")
            .isEqualTo("FISCAL")

        val nombre = "MARGINAL ${uniqueDocumento()}"
        post(
            "/api/objects/ubigeo/records",
            mapOf("attributes" to mapOf("codigo" to uniqueDocumento().take(6), "departamento" to "JUNIN", "provincia" to "CHANCHAMAYO", "distrito" to "PERENE"))
        )
        post("/api/objects/via/records", mapOf("attributes" to mapOf("tipo_via" to "AVENIDA", "nombre" to nombre, "ubigeo" to "120302")))
        get("/api/srtm/ubigeos")
            .expectBody()
            .jsonPath("$[0].distrito")
            .exists()
        get("/api/srtm/vias?q=$nombre&tipo=AVENIDA")
            .expectBody()
            .jsonPath("$.content[0].nombre")
            .isEqualTo(nombre)
        get("/api/srtm/vias?q=$nombre&tipo=CALLE")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
    }

    @Test
    fun `a declaracion jurada on a new predio is numbered and coded, with its lists`() {
        val contribuyente = inscribir(uniqueDocumento())["id"].asString()
        val sector = uniqueDocumento().take(2)
        val manzana = uniqueDocumento().take(2)
        val dj =
            post(
                "/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas",
                mapOf(
                    "declaracion" to mapOf("tipo_adquisicion" to "COMPRA", "fecha_adquisicion" to "2024-09-04", "fecha_presentacion" to "2026-09-24"),
                    "predio" to
                        mapOf(
                            "sector_catastral" to sector,
                            "manzana_catastral" to manzana,
                            "condicion" to "URBANO",
                            "tipo_via" to "AVENIDA",
                            "via" to "MARGINAL",
                            "lote" to "19",
                            "departamento" to "JUNIN",
                            "provincia" to "CHANCHAMAYO",
                            "distrito" to "PERENE"
                        )
                )
            )
        val declaracion = dj["declaracion"]
        assertEquals("$sector-$manzana-0001", dj["predio"]["codigo"].asString())
        assertEquals("AVENIDA MARGINAL, LT. 19, JUNIN-CHANCHAMAYO-PERENE", dj["predio"]["direccion"].asString())
        assertEquals(2026, declaracion["anio"].asInt())
        assertEquals("1", declaracion["secuencia_uso"].asString())
        assertEquals("PROPIETARIO UNICO", declaracion["condicion_propiedad"].asString())
        assertEquals(100, declaracion["porcentaje_condominio"].asInt())
        assertEquals(contribuyente, dj["contribuyente"]["id"].asString())
        assertTrue(declaracion["numero_declaracion"].asInt() > 0)

        val id = declaracion["id"].asString()
        post(
            "/api/srtm/declaraciones/$id/niveles",
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2023,
                "mes_construccion" to 1,
                "material" to "LADRILLO",
                "estado_conservacion" to "BUENO",
                "area_construida" to 200,
                "muros_columnas" to "C",
                "techos" to "C",
                "puertas_ventanas" to "D"
            )
        )
        val obra =
            post(
                "/api/srtm/declaraciones/$id/obras",
                mapOf(
                    "ingreso" to "POR CATEGORIAS",
                    "material" to "LADRILLO",
                    "tipo_obra" to "MUROS PERIMETRICOS O CERCOS",
                    "estado_conservacion" to "BUENO",
                    "anio_construccion" to 2024,
                    "mes_construccion" to 2,
                    "numero_piso" to 2,
                    "cantidad" to 2,
                    "metrado" to 50
                )
            )
        assertEquals(100, obra["total_metrado"].asInt())
        post("/api/srtm/declaraciones/$id/frentes", mapOf("tipo_via" to "AVENIDA", "via" to "ANDRES AVELINO CACERES", "frontis" to 7, "lado" to "IMPAR"))
        post(
            "/api/srtm/declaraciones/$id/transferentes",
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
        )
        get("/api/srtm/declaraciones/$id/niveles").expectBody().jsonPath("$[0].muros_columnas").isEqualTo("C")
        get("/api/srtm/declaraciones/$id/frentes").expectBody().jsonPath("$[0].lado").isEqualTo("IMPAR")
        get("/api/srtm/declaraciones/$id/transferentes").expectBody().jsonPath("$[0].estado").isEqualTo("ACTIVO")

        // an update keeps the number, whatever the body says
        val updated = put("/api/srtm/declaraciones/$id", fields(declaracion) + mapOf("numero_declaracion" to 1, "clase_uso" to "RESIDENCIAL"))
        assertEquals(declaracion["numero_declaracion"].asInt(), updated["numero_declaracion"].asInt())
        assertEquals("RESIDENCIAL", updated["clase_uso"].asString())

        // the next predio of that manzana, and a second declaration on the first predio
        val otro = inscribir(uniqueDocumento())["id"].asString()
        val second =
            post(
                "/api/srtm/contribuyentes/$otro/declaraciones-juradas",
                mapOf("declaracion" to mapOf("condicion_propiedad" to "CONDOMINO", "porcentaje_condominio" to 50), "predio_id" to dj["predio"]["id"].asString())
            )
        assertEquals(dj["predio"]["id"].asString(), second["predio"]["id"].asString())
        assertEquals(declaracion["numero_declaracion"].asInt() + 1, second["declaracion"]["numero_declaracion"].asInt())
        val third =
            post(
                "/api/srtm/contribuyentes/$otro/declaraciones-juradas",
                mapOf("predio" to mapOf("sector_catastral" to sector, "manzana_catastral" to manzana, "direccion" to "S/N"))
            )
        assertEquals("$sector-$manzana-0002", third["predio"]["codigo"].asString())
    }

    @Test
    fun `a new predio without code, sector or manzana is a 400`() {
        val contribuyente = inscribir(uniqueDocumento())["id"].asString()
        client
            .post()
            .uri("/api/srtm/contribuyentes/$contribuyente/declaraciones-juradas")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("predio" to mapOf("direccion" to "S/N")))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("sector_catastral")
    }

    @Test
    fun `the official unit-value categories are served column by column`() {
        post(
            "/api/objects/categoria_valor/records",
            mapOf("attributes" to mapOf("columna" to 1, "categoria" to "MUROS Y COLUMNAS", "letra" to "C", "descripcion" to "PLACAS DE CONCRETO"))
        )
        get("/api/srtm/categorias-valor")
            .expectBody()
            .jsonPath("$[0].columna")
            .isEqualTo(1)
    }

    @Test
    fun `a lote of the catastro fiscal is found by its filters and on the map`() {
        val cpu = "CPU-${uniqueDocumento()}"
        val via = "CACERES ${uniqueDocumento()}"
        val lote =
            post(
                "/api/srtm/catastro",
                mapOf(
                    "codigo_cpu" to cpu,
                    "codigo_predio_municipal" to "01-02-0019",
                    "tipo_predio" to "PREDIO URBANO",
                    "tipo_via" to "AVENIDA",
                    "via" to via,
                    "manzana" to "C",
                    "lote" to "19",
                    "lote_geom" to SQUARE
                )
            )
        assertEquals("Polygon", lote["lote_geom"]["type"].asString())
        get("/api/srtm/catastro?via=${via.takeLast(8)}&tipo_via=AVENIDA&tipo_predio=PREDIO URBANO")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].codigo_cpu")
            .isEqualTo(cpu)
            .jsonPath("$.content[0].lote_geom.type")
            .isEqualTo("Polygon")
        get("/api/srtm/catastro?via=${via.takeLast(8)}&tipo_via=CALLE")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
        // the map reads wasichai-gis's features, by bbox
        get("/api/gis/objects/catastro_fiscal/features?bbox=-75.23,-10.95,-75.22,-10.94&geometry=lote_geom&codigo_cpu=$cpu")
            .expectBody()
            .jsonPath("$.features[0].properties.codigo_cpu")
            .isEqualTo(cpu)
        get("/api/gis/objects/catastro_fiscal/features?bbox=-70.1,-10.1,-70.0,-10.0&geometry=lote_geom&codigo_cpu=$cpu")
            .expectBody()
            .jsonPath("$.features.length()")
            .isEqualTo(0)
    }

    @Test
    fun `a predio registered in the portal gets a registration number and keeps its lote`() {
        val sector = uniqueDocumento().take(2)
        val first = post("/api/srtm/predios", mapOf("sector_catastral" to sector, "manzana_catastral" to "01", "direccion" to "S/N", "lote_geom" to SQUARE))
        val second = post("/api/srtm/predios", mapOf("sector_catastral" to sector, "manzana_catastral" to "01", "direccion" to "S/N"))
        assertEquals(first["numero_registro"].asInt() + 1, second["numero_registro"].asInt())
        assertEquals("Polygon", first["lote_geom"]["type"].asString())
        get("/api/srtm/predios/buscar?codigo=${first["codigo"].asString()}")
            .expectBody()
            .jsonPath("$.content[0].numero_registro")
            .isEqualTo(first["numero_registro"].asInt())
        // an update keeps code, number, and a lote it sends as null (a form without a map)
        val id = first["id"].asString()
        val kept = put("/api/srtm/predios/$id", fields(first) + mapOf("codigo" to "X", "numero_registro" to 1, "lote_geom" to null))
        assertEquals(first["codigo"].asString(), kept["codigo"].asString())
        assertEquals(first["numero_registro"].asInt(), kept["numero_registro"].asInt())
        assertEquals("Polygon", kept["lote_geom"]["type"].asString())
    }

    @Test
    fun `a domicilio is located on the map`() {
        val id = inscribir(uniqueDocumento())["id"].asString()
        val domicilio =
            post(
                "/api/srtm/contribuyentes/$id/domicilios",
                mapOf(
                    "tipo_domicilio" to "FISCAL",
                    "tipo_predio" to "PREDIO URBANO",
                    "departamento" to "JUNIN",
                    "provincia" to "CHANCHAMAYO",
                    "distrito" to "PERENE",
                    "ubicacion" to mapOf("type" to "Point", "coordinates" to listOf(-75.2247, -10.9475))
                )
            )
        assertEquals("Point", domicilio["ubicacion"]["type"].asString())
        get("/api/srtm/contribuyentes/$id/domicilios")
            .expectBody()
            .jsonPath("$[0].ubicacion.coordinates[0]")
            .isEqualTo(-75.2247)
    }

    @Test
    fun `a missing required field is a 400 naming that field`() {
        client
            .post()
            .uri("/api/srtm/contribuyentes")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("tipo_contribuyente" to "PERSONA NATURAL", "tipo_documento" to "DNI", "nombres" to "SIN DOCUMENTO"))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("numero_documento")
    }

    @Test
    fun `no token, no portal`() {
        client
            .get()
            .uri("/api/srtm/resumen")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

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

    private fun inscribir(
        documento: String,
        vararg extra: Pair<String, Any?>
    ): JsonNode = post("/api/srtm/contribuyentes", persona(documento) + extra)

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

    private fun delete(path: String) {
        client
            .delete()
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    // model/apply.py in kotlin: what is missing gets created (objects, fields, enum options, relationships),
    // what exists is left alone (the test db may be shared)
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

        // a small lote in Perené, GeoJSON in EPSG:4326
        val SQUARE =
            mapOf(
                "type" to "Polygon",
                "coordinates" to
                    listOf(
                        listOf(
                            listOf(-75.2250, -10.9480),
                            listOf(-75.2245, -10.9480),
                            listOf(-75.2245, -10.9475),
                            listOf(-75.2250, -10.9475),
                            listOf(-75.2250, -10.9480)
                        )
                    )
            )
    }
}
