package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

// the condominio of a predio, año and secuencia de uso: the backend derives condición, % and values after every
// create, update and delete of a declaración, keeps the parts within 100 % and adds condóminos from a declaración
class CondominioApiTest : SrtmApiTest() {
    @Test
    fun `a sole titular holds its predio at 100 percent, with its values derived`() {
        val d = declarar(inscribir(), predio(), "porcentaje_condominio" to 40, "condicion_propiedad" to "CONDOMINO", "valor_afecto" to 1)
        assertEquals("PROPIETARIO UNICO", d["condicion_propiedad"].asString())
        assertDecimal("100", d["porcentaje_condominio"])
        assertDecimal("10000.50", d["valor_condominio"])
        assertDecimal("9000.50", d["valor_afecto"])
    }

    @Test
    fun `two condominos each get their part of the autoavaluo`() {
        val predio = predio()
        val a = declarar(inscribir(), predio)["id"].asString()
        val b = declarar(inscribir(), predio, "porcentaje_condominio" to 40, "deduccion" to null)
        assertEquals("CONDOMINO", b["condicion_propiedad"].asString())
        assertDecimal("40", b["porcentaje_condominio"])
        assertDecimal("4000.20", b["valor_condominio"])
        assertDecimal("4000.20", b["valor_afecto"])
        // the sole titular's 100 % gave the newcomer its part
        val deA = declaracion(a)
        assertEquals("CONDOMINO", deA["condicion_propiedad"].asString())
        assertDecimal("60", deA["porcentaje_condominio"])
        assertDecimal("6000.30", deA["valor_condominio"])
        assertDecimal("5000.30", deA["valor_afecto"])
    }

    @Test
    fun `parts adding up to more than 100 percent are a 400 on porcentaje_condominio`() {
        val predio = predio()
        val a = declarar(inscribir(), predio)["id"].asString()
        declarar(inscribir(), predio, "porcentaje_condominio" to 40)
        val stored = declaracion(a)
        rejected("PUT", "/api/srtm/declaraciones/$a", fields(stored) + ("porcentaje_condominio" to 70), "porcentaje_condominio")
        assertDecimal("60", declaracion(a)["porcentaje_condominio"])
        rejected("POST", "/api/srtm/declaraciones", body(inscribir(), predio) + ("porcentaje_condominio" to 10), "porcentaje_condominio")
        // within 100 % an update is taken
        assertDecimal("50", put("/api/srtm/declaraciones/$a", fields(stored) + ("porcentaje_condominio" to 50))["porcentaje_condominio"])
    }

    @Test
    fun `a condomino added from a declaration declares the same predio, with its caracteristicas`() {
        val predio = predio()
        val origen =
            declarar(inscribir(), predio, "uso" to "COMERCIAL", "area_terreno" to 200, "clase_uso" to "COMERCIAL", "tipo_adquisicion" to "COMPRA")
        val a = origen["id"].asString()
        post(
            "/api/srtm/declaraciones/$a/niveles",
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2023,
                "mes_construccion" to 1,
                "material" to "LADRILLO",
                "estado_conservacion" to "BUENO",
                "area_construida" to 200
            )
        )
        val b = inscribir()

        val nuevo = post("/api/srtm/declaraciones/$a/condominos", mapOf("contribuyente" to b, "porcentaje_condominio" to 25))
        assertEquals(b, nuevo["contribuyente"].asString())
        assertEquals(predio, nuevo["predio"].asString())
        assertEquals(2026, nuevo["anio"].asInt())
        assertEquals("001", nuevo["secuencia_uso"].asString())
        assertEquals("COMERCIAL", nuevo["uso"].asString())
        assertDecimal("200", nuevo["area_terreno"])
        assertTrue(nuevo["tipo_adquisicion"] == null || nuevo["tipo_adquisicion"].isNull)
        assertTrue(nuevo["numero_declaracion"].asInt() > origen["numero_declaracion"].asInt())
        assertEquals("CONDOMINO", nuevo["condicion_propiedad"].asString())
        assertDecimal("25", nuevo["porcentaje_condominio"])
        assertDecimal("2500.13", nuevo["valor_condominio"])
        assertDecimal("200", tree(send("GET", "/api/srtm/declaraciones/${nuevo["id"].asString()}/niveles", null, HttpStatus.OK))[0]["area_construida"])
        assertDecimal("75", declaracion(a)["porcentaje_condominio"])

        // once is enough
        rejected("POST", "/api/srtm/declaraciones/$a/condominos", mapOf("contribuyente" to b, "porcentaje_condominio" to 5), "contribuyente")
    }

    @Test
    fun `when a condomino's declaration goes, the other is propietario unico at 100 percent again`() {
        val predio = predio()
        val a = declarar(inscribir(), predio)["id"].asString()
        // one without lists: one with them is annulled instead (AnulacionApiTest)
        val b = declarar(inscribir(), predio, "porcentaje_condominio" to 40)["id"].asString()

        delete("/api/srtm/declaraciones/$b")

        val deA = declaracion(a)
        assertEquals("PROPIETARIO UNICO", deA["condicion_propiedad"].asString())
        assertDecimal("100", deA["porcentaje_condominio"])
        assertDecimal("10000.50", deA["valor_condominio"])
        get("/api/srtm/predios/$predio/declaraciones?anio=2026").expectBody().jsonPath("$.length()").isEqualTo(1)
    }

    private fun assertDecimal(
        expected: String,
        actual: JsonNode?
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual!!.decimalValue()), "expected $expected, got $actual")

    // a predio of 10000.50, with a deducción of 1000
    private fun body(
        contribuyente: String,
        predio: String
    ) = mapOf(
        "contribuyente" to contribuyente,
        "predio" to predio,
        "anio" to 2026,
        "secuencia_uso" to "1",
        "valor_autoavaluo" to 10000.50,
        "deduccion" to 1000
    )

    private fun declarar(
        contribuyente: String,
        predio: String,
        vararg extra: Pair<String, Any?>
    ): JsonNode = post("/api/srtm/declaraciones", body(contribuyente, predio) + extra)

    private fun declaracion(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]
}
