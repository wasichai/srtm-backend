package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// the srtm's usos del predio (clase -> sub clase -> uso), the catalog the portal cascades over, and what a
// declaración stores of it
class UsosPredioApiTest : SrtmApiTest() {
    @Test
    fun `the catalog of usos is served by code, with its clase and sub clase`() {
        // the test db may be shared: codes of this run only
        val prefijo = uniqueDocumento()
        post(
            "/api/objects/uso_predio/records",
            mapOf("attributes" to mapOf("codigo" to "${prefijo}2", "clase" to "BIENES COMUNES", "sub_clase" to "RESIDENCIAL", "uso" to "CASA HABITACIÓN"))
        )
        post(
            "/api/objects/uso_predio/records",
            mapOf("attributes" to mapOf("codigo" to "${prefijo}1", "clase" to "RESIDENCIAL", "sub_clase" to "UNIFAMILIAR", "uso" to "CASA HABITACIÓN"))
        )

        val usos = tree(send("GET", "/api/srtm/usos-predio", null, HttpStatus.OK)).iterator().asSequence().toList()
        val nuestros = usos.filter { it["codigo"].asString().startsWith(prefijo) }
        assertEquals(listOf("${prefijo}1", "${prefijo}2"), nuestros.map { it["codigo"].asString() })
        assertEquals("RESIDENCIAL", nuestros[0]["clase"].asString())
        assertEquals("UNIFAMILIAR", nuestros[0]["sub_clase"].asString())
        assertEquals("CASA HABITACIÓN", nuestros[0]["uso"].asString())
        val codigos = usos.map { it["codigo"].asString() }
        assertEquals(codigos.sorted(), codigos)
    }

    @Test
    fun `a declaration stores the srtm's clase, sub clase and uso, or the padron's uso alone`() {
        val predio = predio()
        val srtm =
            post(
                "/api/srtm/declaraciones",
                body(inscribir(), predio) + mapOf("clase_uso" to "RESIDENCIAL", "sub_clase_uso" to "UNIFAMILIAR", "uso" to "CASA HABITACIÓN")
            )
        assertEquals("RESIDENCIAL", srtm["clase_uso"].asString())
        assertEquals("UNIFAMILIAR", srtm["sub_clase_uso"].asString())
        assertEquals("CASA HABITACIÓN", srtm["uso"].asString())

        val padron = post("/api/srtm/declaraciones", body(inscribir(), predio()) + ("uso" to "RESIDENCIAL - CASA HABITACION"))
        assertEquals("RESIDENCIAL - CASA HABITACION", padron["uso"].asString())
        assertTrue(padron["clase_uso"] == null || padron["clase_uso"].isNull)
    }

    private fun body(
        contribuyente: String,
        predio: String
    ) = mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to 2026, "secuencia_uso" to "1")
}
