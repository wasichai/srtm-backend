package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.util.UUID

// what the portal reads around its fichas (the padrón's resumen, the predios' search, a declaración jurada with both
// its sides) and the saves the backend refuses: another predio's code, another contribuyente's document, a record
// that does not exist
class FichasApiTest : SrtmApiTest() {
    @Test
    fun `the resumen counts contribuyentes, predios and declaraciones, in the current year`() {
        val antes = resumen()
        nuevaDeclaracion()

        val despues = resumen()
        assertEquals(LocalDate.now().year, despues["anio"].asInt())
        assertEquals(antes["contribuyentes"].asLong() + 1, despues["contribuyentes"].asLong())
        assertEquals(antes["predios"].asLong() + 1, despues["predios"].asLong())
        assertEquals(antes["declaraciones"].asLong() + 1, despues["declaraciones"].asLong())
    }

    @Test
    fun `a declaracion jurada is read with its predio, its contribuyente and when it was saved`() {
        val codigo = "T-${uniqueDocumento()}"
        val predio = post("/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))["id"].asString()
        val contribuyente = inscribir()
        val creada =
            post(
                "/api/srtm/declaraciones",
                mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to 2026, "secuencia_uso" to "1", "uso" to "COMERCIAL")
            )

        val dj = tree(send("GET", "/api/srtm/declaraciones/${creada["id"].asString()}", null, HttpStatus.OK))
        assertEquals(creada["numero_declaracion"].asInt(), dj["declaracion"]["numero_declaracion"].asInt())
        assertEquals("COMERCIAL", dj["declaracion"]["uso"].asString())
        assertEquals(codigo, dj["predio"]["codigo"].asString())
        assertEquals(contribuyente, dj["contribuyente"]["id"].asString())
        assertEquals("FLORES OTINIANO JUNIOR", dj["contribuyente"]["nombre_completo"].asString())
        assertFalse(dj["actualizado"] == null || dj["actualizado"].isNull)

        send("GET", "/api/srtm/declaraciones/${UUID.randomUUID()}", null, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `predios are searched by any of their texts, a page at a time, in the order of their codes`() {
        val marca = uniqueDocumento()
        post("/api/srtm/predios", mapOf("codigo" to "T-$marca-2", "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))
        post("/api/srtm/predios", mapOf("codigo" to "T-$marca-1", "direccion" to "JR. LIMA 123", "condicion" to "URBANO"))

        get("/api/srtm/predios?q=$marca&size=1")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(2)
            .jsonPath("$.content.length()")
            .isEqualTo(1)
            .jsonPath("$.content[0].codigo")
            .isEqualTo("T-$marca-1")
        get("/api/srtm/predios?q=$marca-2")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
            .jsonPath("$.content[0].codigo")
            .isEqualTo("T-$marca-2")
        get("/api/srtm/predios?q=NINGUNO-$marca")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(0)
    }

    @Test
    fun `a predio registered with another predio's code is a 400 on codigo`() {
        val codigo = "T-${uniqueDocumento()}"
        post("/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "S/N"))
        rejected("POST", "/api/srtm/predios", mapOf("codigo" to codigo, "direccion" to "JR. LIMA 123"), "codigo")
        get("/api/srtm/predios?q=$codigo")
            .expectBody()
            .jsonPath("$.totalElements")
            .isEqualTo(1)
    }

    @Test
    fun `an edit to another contribuyente's document is a 400 on numero_documento`() {
        val tomado = uniqueDocumento()
        val otro = post("/api/srtm/contribuyentes", personaNatural(tomado))
        val inscrito = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()))
        val id = inscrito["id"].asString()

        val problem = rejected("PUT", "/api/srtm/contribuyentes/$id", fields(inscrito) + ("numero_documento" to tomado), "numero_documento")
        assertEquals("ya está inscrito: ${otro["codigo"].asString()} FLORES OTINIANO JUNIOR", problem["errors"][0]["message"].asString())
        get("/api/srtm/contribuyentes/$id")
            .expectBody()
            .jsonPath("$.contribuyente.numero_documento")
            .isEqualTo(inscrito["numero_documento"].asString())
    }

    @Test
    fun `a contribuyente, predio or declaracion that does not exist is a 404 to every edit`() {
        val nadie = UUID.randomUUID()
        send("PUT", "/api/srtm/contribuyentes/$nadie", personaNatural(uniqueDocumento()), HttpStatus.NOT_FOUND)
        send("DELETE", "/api/srtm/contribuyentes/$nadie", null, HttpStatus.NOT_FOUND)
        send("PUT", "/api/srtm/predios/$nadie", mapOf("direccion" to "S/N"), HttpStatus.NOT_FOUND)
        send("DELETE", "/api/srtm/predios/$nadie", null, HttpStatus.NOT_FOUND)
        send("PUT", "/api/srtm/declaraciones/$nadie", mapOf("anio" to 2026, "secuencia_uso" to "1"), HttpStatus.NOT_FOUND)
        send("DELETE", "/api/srtm/declaraciones/$nadie", null, HttpStatus.NOT_FOUND)
        send("POST", "/api/srtm/declaraciones/$nadie/anular", mapOf("motivo_anulacion" to "Duplicada"), HttpStatus.NOT_FOUND)
        send("POST", "/api/srtm/declaraciones/$nadie/condominos", mapOf("contribuyente" to inscribir(), "porcentaje_condominio" to 10), HttpStatus.NOT_FOUND)
    }
}
