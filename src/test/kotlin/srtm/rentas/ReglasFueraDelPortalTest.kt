package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

// what a save outside the portal (the admin, core's own api) leaves for the portal's rules to complete
class ReglasFueraDelPortalTest {
    private val persona =
        mapOf(
            "tipo_persona" to "NATURAL",
            "tipo_contribuyente" to "PERSONA NATURAL",
            "apellido_paterno" to "FLORES",
            "apellido_materno" to "OTINIANO",
            "nombres" to "JUNIOR",
            "nombre_completo" to "FLORES OTINIANO JUNIOR",
            "observacion" to null
        )

    private val domicilio =
        mapOf(
            "tipo_via" to "AVENIDA",
            "via" to "MARGINAL",
            "numero" to "234",
            "departamento" to "JUNIN",
            "provincia" to "CHANCHAMAYO",
            "distrito" to "PERENE",
            "descripcion" to "AVENIDA MARGINAL, N° 234, JUNIN-CHANCHAMAYO-PERENE"
        )

    @Test
    fun `an edited name part rebuilds nombre_completo`() {
        val despues = persona + ("apellido_paterno" to "RAMOS")
        assertEquals(mapOf("nombre_completo" to "RAMOS OTINIANO JUNIOR"), completar(CONTRIBUYENTE, persona, despues))
    }

    @Test
    fun `an edited tipo de contribuyente rebuilds tipo_persona`() {
        val despues = persona + mapOf("tipo_contribuyente" to "SUCESION INDIVISA")
        assertEquals("SUCESION", completar(CONTRIBUYENTE, persona, despues)["tipo_persona"])
    }

    @Test
    fun `a derived field the same write sets by hand is kept`() {
        val despues = persona + mapOf("apellido_paterno" to "RAMOS", "nombre_completo" to "RAMOS O. JUNIOR")
        assertEquals(emptyMap<String, Any?>(), completar(CONTRIBUYENTE, persona, despues))
    }

    // the padrón's name stays until someone edits what it is built from (import_predios.py keeps the original)
    @Test
    fun `an edit that leaves the name parts alone keeps an imported name`() {
        val importado = persona + ("nombre_completo" to "FLORES . OTINIANO JUNIOR")
        val despues = importado + ("observacion" to "VISITADO")
        assertEquals(emptyMap<String, Any?>(), completar(CONTRIBUYENTE, importado, despues))
    }

    @Test
    fun `a new record keeps the values it names and gets the blank ones`() {
        assertEquals(emptyMap<String, Any?>(), completar(CONTRIBUYENTE, null, persona + ("nombre_completo" to "FLORES . OTINIANO JUNIOR")))
        assertEquals(mapOf("nombre_completo" to "FLORES OTINIANO JUNIOR"), completar(CONTRIBUYENTE, null, persona + ("nombre_completo" to " ")))
    }

    @Test
    fun `a new domicilio is described, and an edited one described again`() {
        assertEquals(mapOf("descripcion" to domicilio["descripcion"]), completar(DOMICILIO, null, domicilio - "descripcion"))
        assertEquals(
            mapOf("descripcion" to "AVENIDA MARGINAL, N° 240, JUNIN-CHANCHAMAYO-PERENE"),
            completar(DOMICILIO, domicilio, domicilio + ("numero" to "240"))
        )
    }

    @Test
    fun `an obra's total is cantidad x metrado, whatever its scale`() {
        val obra = mapOf("cantidad" to BigDecimal("2"), "metrado" to BigDecimal("3.5"), "total_metrado" to null)
        assertEquals(0, BigDecimal("7.0").compareTo(completar(OBRA_COMPLEMENTARIA, null, obra)["total_metrado"] as BigDecimal))
        val guardada = obra + ("total_metrado" to BigDecimal("7.000"))
        assertEquals(emptyMap<String, Any?>(), completar(OBRA_COMPLEMENTARIA, obra, guardada))
        assertEquals(
            0,
            BigDecimal("10.5").compareTo(completar(OBRA_COMPLEMENTARIA, guardada, guardada + ("cantidad" to BigDecimal("3")))["total_metrado"] as BigDecimal)
        )
    }

    @Test
    fun `a predio with the srtm's ubicacion gets its direccion, an imported one keeps the padron's`() {
        val importado = mapOf("direccion" to "JR. LIMA 123", "via" to "LIMA", "numero" to "123", "tipo_via" to null)
        assertEquals(emptyMap<String, Any?>(), completar(PREDIO, importado, importado + ("numero" to "125")))
        val ubicado = importado + mapOf("tipo_via" to "JIRON", "distrito" to "PERENE")
        assertEquals(mapOf("direccion" to "JIRON LIMA, N° 123, PERENE"), completar(PREDIO, importado, ubicado))
    }

    // the portal already sets them, and the listener's own write only changes derived fields: neither asks for more
    @Test
    fun `a write whose derived values are already right completes nothing`() {
        assertEquals(emptyMap<String, Any?>(), completar(CONTRIBUYENTE, null, persona))
        assertEquals(emptyMap<String, Any?>(), completar(DOMICILIO, domicilio + ("descripcion" to "VIEJA"), domicilio))
        assertEquals(emptyMap<String, Any?>(), completar(DOMICILIO, domicilio, domicilio + ("referencia" to "")))
    }

    @Test
    fun `a blank value and none are the same`() {
        val vacio = mapOf("tipo_via" to null, "descripcion" to "")
        assertEquals(emptyMap<String, Any?>(), completar(DOMICILIO, null, vacio))
    }

    @Test
    fun `objects without portal rules are left alone`() {
        assertEquals(emptyMap<String, Any?>(), completar(DECLARACION, null, mapOf("valor_afecto" to BigDecimal.ONE)))
    }
}
