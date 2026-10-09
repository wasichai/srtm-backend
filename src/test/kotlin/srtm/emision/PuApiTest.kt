package srtm.emision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode

// GET /api/srtm/predios/{id}/pu: the PU of a predio and a titular, as the epic's contract says: an inline pdf, 404
// without a vigente declaración that year, 409 with the titulares when there are several and none was chosen
class PuApiTest : SrtmApiTest() {
    @Test
    fun `the pu is an inline pdf with the predio, its titular, a nivel and the autoavaluo`() {
        val titular = contribuyente("JUNIOR")
        val predio = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"))
        val d = declarar(titular["id"].asString(), predio["id"].asString())
        post("/api/srtm/declaraciones/$d/niveles", NIVEL)

        val pu = pu(predio["id"].asString(), "anio=2026")

        assertEquals(HttpStatus.OK, pu.status)
        assertTrue(MediaType.APPLICATION_PDF.isCompatibleWith(pu.tipo), "${pu.tipo}")
        val codigo = predio["codigo"].asString()
        assertEquals("inline; filename=\"PU-$codigo-2026.pdf\"", pu.disposicion)
        val texto = texto(pu.cuerpo)
        assertTrue(codigo in texto, texto)
        assertTrue(titular["nombre_completo"].asString() in texto, texto)
        assertTrue("ADOBE" in texto, texto)
        assertTrue("S/ 10,000.50" in texto, texto)
    }

    // the header is the organization's municipalidad record, edited in the admin: the shared test db may already
    // have one, so the test writes its own values over it
    @Test
    fun `the pu opens with the organization's municipalidad record`() {
        val marca = uniqueDocumento()
        val datos =
            mapOf(
                "nombre" to "MUNICIPALIDAD DISTRITAL DE PRUEBA",
                "oficina" to "OFICINA DE TESORERIA",
                "ruc" to "20195238961",
                "gerencia" to "GERENCIA DE ADMINISTRACION TRIBUTARIA",
                "direccion" to "JR. PRUEBA $marca"
            )
        val existente = tree(send("GET", "/api/objects/municipalidad/records?sort=created_at", null, HttpStatus.OK))["content"].firstOrNull()
        if (existente == null) {
            post("/api/objects/municipalidad/records", mapOf("attributes" to datos))
        } else {
            put("/api/objects/municipalidad/records/${existente["id"].asString()}", mapOf("attributes" to datos))
        }
        val predio = post("/api/srtm/predios", mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO"))
        declarar(contribuyente("CABECERA")["id"].asString(), predio["id"].asString())

        val texto = texto(pu(predio["id"].asString(), "anio=2026").cuerpo)

        assertTrue("MUNICIPALIDAD DISTRITAL DE PRUEBA" in texto, texto)
        assertTrue("OFICINA DE TESORERIA" in texto, texto)
        assertTrue("RUC: 20195238961" in texto, texto)
        assertTrue("GERENCIA DE ADMINISTRACION TRIBUTARIA" in texto, texto)
        assertTrue("JR. PRUEBA $marca" in texto, texto)
        assertTrue(Regex("Fecha: \\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}").containsMatchIn(texto), texto)
    }

    @Test
    fun `a predio without a declaracion that year is a 404`() {
        val predio = predio()
        declarar(inscribir(), predio)
        val problema = problema(pu(predio, "anio=2025"), HttpStatus.NOT_FOUND)
        assertTrue("2025" in problema["detail"].asString(), problema.toString())
    }

    @Test
    fun `an annulled declaracion is not emitted`() {
        val predio = predio()
        val d = declarar(inscribir(), predio)
        send("POST", "/api/srtm/declaraciones/$d/anular", mapOf("motivo_anulacion" to "Error de digitación"), HttpStatus.OK)
        problema(pu(predio, "anio=2026"), HttpStatus.NOT_FOUND)
    }

    @Test
    fun `two condominos without contribuyente are a 409 with the titulares`() {
        val predio = predio()
        val a = contribuyente("ANA")
        val b = contribuyente("BETO")
        declarar(a["id"].asString(), predio)
        declarar(b["id"].asString(), predio, "porcentaje_condominio" to 40)

        val problema = problema(pu(predio, "anio=2026"), HttpStatus.CONFLICT)

        val titulares = problema["titulares"].iterator().asSequence().toList()
        assertEquals(setOf(a["id"].asString(), b["id"].asString()), titulares.map { it["id"].asString() }.toSet(), problema.toString())
        val deA = titulares.first { it["id"].asString() == a["id"].asString() }
        assertEquals(a["nombre_completo"].asString(), deA["nombre"].asString())
        assertEquals("DNI ${a["numero_documento"].asString()}", deA["documento"].asString())
    }

    @Test
    fun `a chosen condomino gets its own pu`() {
        val predio = predio()
        val a = contribuyente("ANA")
        val b = contribuyente("BETO")
        declarar(a["id"].asString(), predio)
        declarar(b["id"].asString(), predio, "porcentaje_condominio" to 40)

        val pu = pu(predio, "anio=2026&contribuyente=${b["id"].asString()}")

        assertEquals(HttpStatus.OK, pu.status)
        val texto = texto(pu.cuerpo)
        assertTrue(b["nombre_completo"].asString() in texto, texto)
        assertTrue(a["nombre_completo"].asString() !in texto, texto)
        assertTrue("40.00 %" in texto, texto)
    }

    @Test
    fun `a contribuyente that is no titular of the predio is a 404`() {
        val predio = predio()
        declarar(inscribir(), predio)
        problema(pu(predio, "anio=2026&contribuyente=${inscribir()}"), HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a predio with two usos prints a section per uso`() {
        val predio = predio()
        val titular = inscribir()
        declarar(titular, predio)
        declarar(titular, predio, "secuencia_uso" to "2")

        val texto = texto(pu(predio, "anio=2026").cuerpo)

        assertEquals(2, Regex("USO N\\.° 00[12]").findAll(texto).count(), texto)
    }

    @Test
    fun `a hundred pus in a row`() {
        val predio = predio()
        val d = declarar(inscribir(), predio)
        repeat(3) { post("/api/srtm/declaraciones/$d/niveles", NIVEL) }
        // warm-up: the first one loads the template and the fonts
        assertEquals(HttpStatus.OK, pu(predio, "anio=2026").status)
        val inicio = System.nanoTime()
        repeat(100) { assertEquals(HttpStatus.OK, pu(predio, "anio=2026").status) }
        val ms = (System.nanoTime() - inicio) / 1_000_000.0
        // integrationTest shows stderr: the measure the issue asks for, reads from core included
        System.err.println("PU por la API: 100 en %.0f ms, %.1f ms/PU".format(ms, ms / 100))
        assertTrue(ms / 100 < 500, "%.1f ms/PU".format(ms / 100))
    }

    private class Respuesta(
        val status: HttpStatus,
        val tipo: MediaType?,
        val disposicion: String?,
        val cuerpo: ByteArray
    )

    private fun pu(
        predio: String,
        query: String
    ): Respuesta {
        val result =
            client
                .get()
                .uri("/api/srtm/predios/$predio/pu?$query")
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

    // a new persona natural named FLORES OTINIANO `nombres`: the record
    private fun contribuyente(nombres: String): JsonNode = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()) + ("nombres" to nombres))

    // a 2026 declaración, uso 1, of 10000.50: its id
    private fun declarar(
        contribuyente: String,
        predio: String,
        vararg extra: Pair<String, Any?>
    ): String =
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to 2026,
                "secuencia_uso" to "1",
                "valor_autoavaluo" to 10000.50,
                "deduccion" to 0
            ) + extra
        )["id"].asString()

    private companion object {
        val NIVEL =
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2015,
                "mes_construccion" to 3,
                "material" to "ADOBE",
                "estado_conservacion" to "BUENO",
                "area_construida" to 120
            )
    }
}
