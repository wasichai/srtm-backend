package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.util.UUID

class BusquedaPrediosTest {
    @Test
    fun `the page 13 filters map to the padron's fields and to the catastro's`() {
        val filtros = FiltrosPredio.of(mapOf("tipo_predio" to "PREDIO URBANO", "via" to "CACERES", "zona" to "ALAMEDA", "codigo" to "01-", "lote" to " "))
        assertEquals(
            listOf(
                Condicion("condicion", "URBANO", exact = true),
                Condicion("codigo", "01-", exact = false),
                Condicion("via", "CACERES", exact = false),
                Condicion("habilitacion_urbana", "ALAMEDA", exact = false)
            ),
            condicionesPredio(filtros)
        )
        assertEquals(
            listOf("tipo_predio", "codigo_predio_municipal", "via", "zona"),
            condicionesCatastro(filtros).map { it.field }
        )
    }

    @Test
    fun `typed wildcards are literal`() {
        assertEquals("%50\\% OFF\\_A%", contiene(" 50% OFF_A "))
    }

    @Test
    fun `the conditions become one bound criterion`() {
        val bound = mutableListOf<Any>()
        val sql =
            criterio(
                listOf(Condicion("via", "caceres", exact = false), Condicion("tipo_via", "AVENIDA", exact = true), Condicion("borrado", "x", exact = true))
            )!!.condition(definition("via", "tipo_via")) {
                bound += it
                "$${bound.size}"
            }
        assertEquals("c_via ILIKE $1 AND c_tipo_via = $2", sql)
        assertEquals(listOf<Any>("%caceres%", "AVENIDA"), bound)
        assertNull(criterio(emptyList()))
    }

    @Test
    fun `geometries leave the attributes for their own section, a null one is kept as stored`() {
        val (plain, geometries) = Registros.separarGeometrias(mapOf("codigo" to "A", "lote_geom" to mapOf("type" to "Polygon"), "ubicacion" to null))
        assertEquals(mapOf("codigo" to "A"), plain)
        assertEquals(setOf("lote_geom"), geometries.keys)
    }

    private fun definition(vararg names: String): ObjectDefinition {
        val objectId = UUID.randomUUID()
        val obj = CustomObject(objectId, UUID.randomUUID(), "predio", "Predio", "Predios", null, true, "t_predio", null, null)
        val fields =
            names.mapIndexed { i, name ->
                CustomField(UUID.randomUUID(), objectId, name, name, FieldType.TEXT, "c_$name", false, false, null, null, i, null, null, emptyMap(), true, true)
            }
        return ObjectDefinition(obj, fields)
    }
}
