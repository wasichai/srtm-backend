package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.time.LocalDate

class RecordsTest {
    @Test
    fun `a contribuyente goes to attributes and back`() {
        val contribuyente =
            Contribuyente(
                tipoPersona = "NATURAL",
                tipoDocumento = "DNI",
                numeroDocumento = "20529936",
                nombreCompleto = "QUISPE MAMANI JUAN",
                codigo = "000012",
                numeroDeclaracion = 12,
                fechaNacimiento = LocalDate.of(2005, 9, 7),
                sexo = "HOMBRE"
            )
        val attributes = Records.attributes(contribuyente)
        assertEquals("2005-09-07", attributes["fecha_nacimiento"])
        assertEquals(contribuyente.copy(id = "c1"), Records.read<Contribuyente>("c1", attributes))
    }

    @Test
    fun `writes send every field, null included, because core's update is a full replace`() {
        val attributes = Records.attributes(Predio(codigo = "01-01-0001", direccion = "JR. LIMA"))
        // every constructor property but the id
        assertEquals(Predio::class.java.declaredFields.size - 1, attributes.size)
        assertTrue(attributes.containsKey("lote"))
        assertNull(attributes["lote"])
        assertTrue("id" !in attributes)
    }

    @Test
    fun `reads core's types, and ignores fields the dto does not know`() {
        val declaracion =
            Records.read<Declaracion>(
                "d1",
                mapOf(
                    "anio" to 2026L,
                    "contribuyente" to "c1",
                    "valor_autoavaluo" to BigDecimal("10080.45"),
                    "area_terreno" to 120.5,
                    "numero_habitantes" to 4L,
                    "campo_del_admin" to "x"
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
    fun `json keys are the model's field names`() {
        val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val tree = json.readTree(json.writeValueAsString(Domicilio(tipoDomicilio = "FISCAL", subLote = "A")))
        assertEquals("FISCAL", tree["tipo_domicilio"].asString())
        assertEquals("A", tree["sub_lote"].asString())
        val back = json.readValue("""{"secuencia_uso":"1","area_terreno":12.5}""", Declaracion::class.java)
        assertEquals("1", back.secuenciaUso)
        assertEquals(BigDecimal("12.5"), back.areaTerreno)
    }
}
