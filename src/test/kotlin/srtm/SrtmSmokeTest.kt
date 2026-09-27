package srtm

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// the app itself (SrtmApplication) on PostGIS: the modules it installs answer, the ones it leaves out do not, and
// the shape model/model.json relies on (enum, unique text, required many-to-one) works end to end
class SrtmSmokeTest : WasichaiIntegrationTest() {
    @Test
    fun `health is up`() {
        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("UP")
    }

    @Test
    fun `installed modules answer, the ones left out do not`() {
        val token = bearer()
        val name = uniqueName("predio")
        createObject(token, name, listOf(mapOf("name" to "codigo", "label" to "Codigo", "type" to "TEXT")))
        // a workflow-less object has no transitions: 200 and [], so this proves the route exists
        listOf(
            "/api/objects/$name/views",
            "/api/objects/$name/forms",
            "/api/pages",
            "/api/objects/$name/document-types",
            "/api/objects/$name/records/${UUID.randomUUID()}/transitions",
            "/api/gis/layers"
        ).forEach { path ->
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
        }
        listOf("/api/automation-runs", "/api/agent/status").forEach { path ->
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isNotFound
        }
    }

    @Test
    fun `a declaration points at its contributor through a required relation`() {
        val token = bearer()
        val contribuyente = uniqueName("contrib")
        val declaracion = uniqueName("decl")
        createObject(
            token,
            contribuyente,
            listOf(
                mapOf("name" to "numero_documento", "label" to "Numero", "type" to "TEXT", "required" to true, "unique" to true),
                mapOf("name" to "tipo_documento", "label" to "Tipo", "type" to "ENUM", "enumOptions" to listOf("DNI", "RUC"))
            )
        )
        createObject(token, declaracion, listOf(mapOf("name" to "valor_afecto", "label" to "Valor afecto", "type" to "DECIMAL")))
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(
                mapOf(
                    "name" to "${declaracion}_c",
                    "label" to "Contribuyente",
                    "inverseLabel" to "Declaraciones",
                    "type" to "MANY_TO_ONE",
                    "source" to declaracion,
                    "target" to contribuyente,
                    "fieldName" to "contribuyente"
                )
            ).exchange()
            .expectStatus()
            .isCreated
        // model/apply.py marks a relation required the same way
        client
            .put()
            .uri("/api/metadata/objects/$declaracion/fields/contribuyente")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("required" to true))
            .exchange()
            .expectStatus()
            .isOk

        val contribuyenteId = createRecord(token, contribuyente, mapOf("numero_documento" to "20529936", "tipo_documento" to "DNI"))
        client
            .post()
            .uri("/api/objects/$declaracion/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("valor_afecto" to "10080.45")))
            .exchange()
            .expectStatus()
            .isBadRequest
        val declaracionId = createRecord(token, declaracion, mapOf("valor_afecto" to "10080.45", "contribuyente" to contribuyenteId))
        client
            .get()
            .uri("/api/objects/$declaracion/records/$declaracionId")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.attributes.contribuyente")
            .isEqualTo(contribuyenteId)
            .jsonPath("$.attributes.valor_afecto")
            .isEqualTo(10080.45)
    }

    private fun createObject(
        token: String,
        name: String,
        fields: List<Map<String, Any>>
    ) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to name, "pluralLabel" to name, "fields" to fields))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun createRecord(
        token: String,
        objectName: String,
        attributes: Map<String, Any>
    ): String =
        client
            .post()
            .uri("/api/objects/$objectName/records")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to attributes))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!["id"] as String
}
