package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal

class MappingTest {
    @Test
    fun `a contribuyente goes to attributes and back`() {
        val contribuyente =
            Contribuyente(
                tipoPersona = "NATURAL",
                tipoDocumento = "DNI",
                numeroDocumento = "20529936",
                nombreCompleto = "QUISPE MAMANI JUAN",
                apellidoPaterno = "QUISPE",
                apellidoMaterno = "MAMANI",
                nombres = "JUAN"
            )
        val attributes = contribuyente.attributes()
        assertEquals(contribuyente.copy(id = "c1"), toContribuyente("c1", attributes))
    }

    @Test
    fun `writes send every field, null included, so a field can be cleared`() {
        val attributes = Predio(codigo = "01-01-0001", direccion = "JR. LIMA").attributes()
        assertEquals(11, attributes.size)
        assertNull(attributes["lote"])
        assert(attributes.containsKey("lote"))
    }

    @Test
    fun `declaration numbers read back from core's types`() {
        val declaracion =
            toDeclaracion(
                "d1",
                mapOf(
                    "anio" to 2026L,
                    "contribuyente" to "c1",
                    "valor_autoavaluo" to BigDecimal("10080.45"),
                    "area_terreno" to 120.5,
                    "numero_habitantes" to "4"
                )
            )
        assertEquals(2026, declaracion.anio)
        assertEquals("c1", declaracion.contribuyente)
        assertEquals(BigDecimal("10080.45"), declaracion.valorAutoavaluo)
        assertEquals(BigDecimal("120.5"), declaracion.areaTerreno)
        assertEquals(4, declaracion.numeroHabitantes)
        assertNull(declaracion.valorAfecto)
    }

    @Test
    fun `totales count a missing value as zero`() {
        val t =
            totales(
                listOf(
                    Declaracion(valorAutoavaluo = BigDecimal("100.50"), valorAfecto = BigDecimal("50")),
                    Declaracion(valorAutoavaluo = BigDecimal("20"))
                )
            )
        assertEquals(2, t.declaraciones)
        assertEquals(BigDecimal("120.50"), t.autoavaluo)
        assertEquals(BigDecimal("50"), t.valorAfecto)
    }

    @Test
    fun `json keys are the model's field names`() {
        val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val tree = json.readTree(json.writeValueAsString(Declaracion(anio = 2026, valorAfecto = BigDecimal("1"))))
        assertEquals(2026, tree["anio"].asInt())
        assertEquals("1", tree["valor_afecto"].asString())
        val back = json.readValue("""{"secuencia_uso":"1","area_terreno":12.5}""", Declaracion::class.java)
        assertEquals("1", back.secuenciaUso)
        assertEquals(BigDecimal("12.5"), back.areaTerreno)
    }
}
