package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import srtm.impuesto.ConParametrosApiTest
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID

// GET /api/srtm/contribuyentes/{id}/hr: the HR of a contribuyente, as the epic's contract says: an inline pdf whose
// amounts are the ones of GET /liquidacion, 422 with faltan without the year's parameters, 404 without a vigente
// declaración that year
class HrApiTest : ConParametrosApiTest() {
    @Test
    fun `the hr of two predios, one in condominio, has the amounts of the liquidacion`() {
        val a = contribuyente()
        val propio = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "AV. MARGINAL 10", "tipo_predio" to "PREDIO URBANO"))
        val compartido = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"))
        declarar(a["id"].asString(), propio["id"].asString(), "60000.00")
        declarar(a["id"].asString(), compartido["id"].asString(), "100000.00")
        // a second condómino takes 50 % out of a's 100 %
        declarar(inscribir(), compartido["id"].asString(), "100000.00", porcentaje = 50)

        val hr = hr(a["id"].asString(), "anio=2026")

        assertEquals(HttpStatus.OK, hr.status)
        assertTrue(MediaType.APPLICATION_PDF.isCompatibleWith(hr.tipo), "${hr.tipo}")
        assertEquals("inline; filename=\"HR-${a["codigo"].asString()}-2026.pdf\"", hr.disposicion)
        val texto = texto(hr.cuerpo)
        assertTrue(propio["codigo"].asString() in texto, texto)
        assertTrue(compartido["codigo"].asString() in texto, texto)
        assertTrue("50.00 %" in texto, texto)
        val l = liquidacion(a["id"].asString())
        assertEquals(BigDecimal("110000.00"), dec(l["base"]))
        assertTrue(soles(l["base"]) in texto, texto)
        assertTrue(soles(l["uit"]) in texto, texto)
        assertTrue(soles(l["impuestoAnual"]) in texto, texto)
        for (t in lista(l["tramos"])) assertTrue(soles(t["impuesto"]) in texto, "tramo ${t["tramo"]}: $texto")
        for (c in lista(l["cuotas"])) assertTrue(soles(c["monto"]) in texto, "cuota ${c["numero"]}: $texto")
        for (fecha in listOf("27/02/2026", "29/05/2026", "31/08/2026", "30/11/2026")) assertTrue(fecha in texto, "$fecha: $texto")
        assertTrue("Al contado: ${soles(l["impuestoAnual"])} hasta el 27/02/2026" in texto, texto)
        assertFalse("Se aplica el mínimo" in texto, texto)
    }

    @Test
    fun `a small base says the minimo applies`() {
        val a = contribuyente()
        declarar(a["id"].asString(), predio(), "1000.00")

        val texto = texto(hr(a["id"].asString(), "anio=2026").cuerpo)

        val l = liquidacion(a["id"].asString())
        assertTrue(l["minimoAplicado"].asBoolean(), l.toString())
        assertTrue("Se aplica el mínimo" in texto, texto)
        assertTrue(soles(l["minimo"]) in texto, texto)
    }

    @Test
    fun `a year without its parameters is a 422 with faltan`() {
        val a = contribuyente()
        declarar(a["id"].asString(), predio(), "60000.00")

        val problema = problema(hr(a["id"].asString(), "anio=2031"), HttpStatus.UNPROCESSABLE_CONTENT)

        val faltan = lista(problema["faltan"]).map { it.asString() }
        assertTrue("UIT 2031" in faltan, problema.toString())
    }

    @Test
    fun `a contribuyente without a vigente declaracion that year is a 404`() {
        val a = contribuyente()
        val anulada = declarar(a["id"].asString(), predio(), "60000.00")
        send("POST", "/api/srtm/declaraciones/$anulada/anular", mapOf("motivo_anulacion" to "Error de digitación"), HttpStatus.OK)

        val problema = problema(hr(a["id"].asString(), "anio=2026"), HttpStatus.NOT_FOUND)

        assertTrue("2026" in problema["detail"].asString(), problema.toString())
    }

    @Test
    fun `a contribuyente that does not exist is a 404`() {
        problema(hr(UUID.randomUUID().toString(), "anio=2026"), HttpStatus.NOT_FOUND)
    }

    private class Respuesta(
        val status: HttpStatus,
        val tipo: MediaType?,
        val disposicion: String?,
        val cuerpo: ByteArray
    )

    private fun hr(
        contribuyente: String,
        query: String
    ): Respuesta {
        val result =
            client
                .get()
                .uri("/api/srtm/contribuyentes/$contribuyente/hr?$query")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(ByteArray::class.java)
                .returnResult()
        return Respuesta(
            HttpStatus.valueOf(result.status.value()),
            result.responseHeaders.contentType,
            result.responseHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION),
            result.responseBody ?: ByteArray(0)
        )
    }

    private fun liquidacion(contribuyente: String): JsonNode =
        tree(send("GET", "/api/srtm/contribuyentes/$contribuyente/liquidacion?anio=2026", null, HttpStatus.OK))

    // a problem+json with that status
    private fun problema(
        respuesta: Respuesta,
        status: HttpStatus
    ): JsonNode {
        val cuerpo = String(respuesta.cuerpo)
        assertEquals(status, respuesta.status, cuerpo)
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(respuesta.tipo), "${respuesta.tipo}")
        return tree(cuerpo)
    }

    private fun contribuyente(): JsonNode = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()))

    // a 2026 declaración, uso 1: its id
    private fun declarar(
        contribuyente: String,
        predio: String,
        autoavaluo: String,
        porcentaje: Int? = null
    ): String =
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "porcentaje_condominio" to porcentaje,
                "valor_autoavaluo" to BigDecimal(autoavaluo),
                "deduccion" to 0
            )
        )["id"].asString()

    private fun dec(node: JsonNode): BigDecimal = node.decimalValue().setScale(2, RoundingMode.HALF_UP)

    // an amount of the liquidación as the pdf prints it
    private fun soles(node: JsonNode): String = "S/ " + DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(dec(node))

    private fun lista(node: JsonNode): List<JsonNode> = node.iterator().asSequence().toList()
}
