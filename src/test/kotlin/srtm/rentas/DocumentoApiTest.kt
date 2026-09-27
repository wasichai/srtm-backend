package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.util.UUID

// the contribuyente's document against the real model: the number its tipo takes (a 400 on numero_documento
// otherwise), and none for a new SIN DOCUMENTO, which the unique constraint allows as many times as needed. one
// from the padrón keeps its number: import_predios.py knows it by it
class DocumentoApiTest : SrtmApiTest() {
    @Test
    fun `sin documento is inscribed without a number, as many times as needed`() {
        val primero = inscribir(persona("SIN DOCUMENTO", null))
        // a number sent anyway is not kept
        val segundo = inscribir(persona("SIN DOCUMENTO", "00012"))
        assertTrue(sinNumero(primero))
        assertTrue(sinNumero(segundo))
    }

    @Test
    fun `a number that does not fit its tipo is a 400 on numero_documento`() {
        rechazado(persona("DNI", "4355456"), "El DNI tiene 8 dígitos")
        rechazado(persona("DNI", null), "Este dato es obligatorio")
        rechazado(empresa("20131312954"), "El dígito verificador del RUC no es válido")
        rechazado(persona("PASAPORTE", "AB-1234"), "Hasta 12 letras o dígitos")
    }

    @Test
    fun `a ruc with its check digit is inscribed, trimmed`() {
        val base = "20" + digits(8)
        val ruc = (0..9).map { base + it }.first { errorDocumento("RUC", it) == null }
        assertEquals(ruc, inscribir(empresa(" $ruc "))["numero_documento"].asString())
    }

    @Test
    fun `changing to sin documento clears the number`() {
        val inscrito = inscribir(persona("DNI", digits(8)))
        val body = fields(inscrito) + ("tipo_documento" to "SIN DOCUMENTO")
        assertTrue(sinNumero(put("/api/srtm/contribuyentes/${inscrito["id"].asString()}", body)))
    }

    @Test
    fun `a sin documento from the padron keeps its number after an edit`() {
        // as import_predios.py loads it: straight into core, with the padrón's number, not checked
        val numero = "SD-" + digits(6)
        val importado =
            mapOf(
                "tipo_persona" to "NATURAL",
                "tipo_documento" to "SIN DOCUMENTO",
                "numero_documento" to numero,
                "nombre_completo" to "MENDOZA TAYPE TEODOCIO"
            )
        val id = tree(send("POST", "/api/objects/$CONTRIBUYENTE/records", mapOf("attributes" to importado), HttpStatus.CREATED))["id"].asString()
        // whatever the body says of the number
        val editado = put("/api/srtm/contribuyentes/$id", persona("SIN DOCUMENTO", "00099") + ("observacion" to "EDITADO"))
        assertEquals(numero, editado["numero_documento"].asString())
        assertEquals("EDITADO", editado["observacion"].asString())
    }

    private fun persona(
        tipo: String,
        numero: String?
    ) = mapOf(
        "tipo_contribuyente" to "PERSONA NATURAL",
        "tipo_documento" to tipo,
        "numero_documento" to numero,
        "apellido_paterno" to "FLORES",
        "nombres" to "JUNIOR",
        "sexo" to "HOMBRE",
        "estado_civil" to "SOLTERO"
    )

    private fun empresa(ruc: String) =
        mapOf(
            "tipo_contribuyente" to "PERSONA JURIDICA",
            "tipo_documento" to "RUC",
            "numero_documento" to ruc,
            "razon_social" to "ASOCIACION AGRARIA PERENE"
        )

    private fun sinNumero(node: JsonNode) = node["numero_documento"] == null || node["numero_documento"].isNull

    private fun inscribir(body: Map<String, Any?>): JsonNode = tree(send("POST", "/api/srtm/contribuyentes", body, HttpStatus.CREATED))

    private fun rechazado(
        body: Map<String, Any?>,
        message: String
    ) {
        client
            .post()
            .uri("/api/srtm/contribuyentes")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.errors[0].field")
            .isEqualTo("numero_documento")
            .jsonPath("$.errors[0].message")
            .isEqualTo(message)
    }

    // random digits: the test db is shared, and a document number is unique
    private fun digits(n: Int): String =
        generateSequence { UUID.randomUUID().toString().filter { it.isDigit() } }
            .flatMap { it.asSequence() }
            .take(n)
            .joinToString("")
}
