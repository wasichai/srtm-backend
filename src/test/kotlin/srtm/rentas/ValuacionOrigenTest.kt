package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

// #132: of a stored record, what an external padrón brought. a field with no value is left out, so the portal draws
// only what the padrón has
class ValuacionOrigenTest {
    @Test
    fun `only the fields with a value come, in the list's order`() {
        val registro =
            mapOf(
                "id" to "d1",
                "valor_construccion" to BigDecimal("48250.75"),
                "arancel" to BigDecimal("35.50"),
                "valor_terreno" to null,
                "valor_autoavaluo" to BigDecimal("56550.75")
            )

        val valores = conValor(registro, PADRON_EXTERNO_DECLARACION)

        assertEquals(listOf("arancel", "valor_construccion"), valores.keys.toList())
        assertEquals(BigDecimal("35.50"), valores["arancel"])
        assertEquals(BigDecimal("48250.75"), valores["valor_construccion"])
    }

    @Test
    fun `a blank text is no value`() {
        val registro = mapOf("codigo_via" to "V0123", "codigo_unidad_urbana" to "", "codigo_hu_armonizacion" to "  ")

        assertEquals(mapOf("codigo_via" to "V0123"), conValor(registro, PADRON_EXTERNO_PREDIO))
    }

    @Test
    fun `a record without the padron's fields has nothing`() {
        assertEquals(emptyMap<String, Any>(), conValor(mapOf("id" to "n1", "area_construida" to 120), PADRON_EXTERNO_NIVEL))
    }

    // #207: codigo_origen joined the four codes, at the end
    @Test
    fun `the predio's list holds the codes of the external padron and its codigo_origen`() {
        assertEquals(
            listOf("codigo_via", "codigo_unidad_urbana", "codigo_hu_armonizacion", "codigo_via_armonizacion", "codigo_origen"),
            PADRON_EXTERNO_PREDIO
        )
    }

    // #237: as a nivel's clasificación, the otras instalaciones (which a copy to another year keeps) are no valuation
    // alone; with the padrón's figures they come with them. an arancel is no padrón's when the DJ names its tramo
    @Test
    fun `the otras instalaciones alone are no valuation de origen, with the padron's figures they are`() {
        val otras = BigDecimal("3000")
        assertEquals(emptyMap<String, Any>(), padronExterno(mapOf("valor_otras_instalaciones" to otras)))
        assertEquals(emptyMap<String, Any>(), padronExterno(mapOf("valor_otras_instalaciones" to otras, "arancel" to 35, "id_aranc" to "T1")))
        assertEquals(
            mapOf("valor_terreno" to BigDecimal("7100"), "valor_otras_instalaciones" to otras),
            padronExterno(mapOf("valor_terreno" to BigDecimal("7100"), "valor_otras_instalaciones" to otras))
        )
        assertEquals(mapOf("arancel" to 35), padronExterno(mapOf("arancel" to 35)))
    }
}
