package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// records saved through core's own record api, as the admin does, get the portal's single-record rules
class ReglasFueraDelPortalApiTest : SrtmApiTest() {
    @Test
    fun `an admin edit of a surname rebuilds nombre_completo`() {
        val id = inscribir()
        guardar(CONTRIBUYENTE, id, "apellido_paterno" to "RAMOS")
        assertEquals("RAMOS OTINIANO JUNIOR", registro(CONTRIBUYENTE, id)["nombre_completo"].asString())
    }

    @Test
    fun `an admin edit of nombre_completo alone is kept`() {
        val id = inscribir()
        guardar(CONTRIBUYENTE, id, "nombre_completo" to "FLORES . OTINIANO JUNIOR")
        assertEquals("FLORES . OTINIANO JUNIOR", registro(CONTRIBUYENTE, id)["nombre_completo"].asString())
    }

    @Test
    fun `a domicilio saved in the admin is described`() {
        val domicilio =
            crear(
                DOMICILIO,
                mapOf(
                    "contribuyente" to inscribir(),
                    "tipo_domicilio" to "FISCAL",
                    "tipo_predio" to "PREDIO URBANO",
                    "departamento" to "JUNIN",
                    "provincia" to "CHANCHAMAYO",
                    "distrito" to "PERENE",
                    "tipo_via" to "AVENIDA",
                    "via" to "MARGINAL",
                    "numero" to "234"
                )
            )
        assertEquals("AV. MARGINAL, N° 234, JUNIN-CHANCHAMAYO-PERENE", registro(DOMICILIO, domicilio)["descripcion"].asString())
        guardar(DOMICILIO, domicilio, "numero" to "240")
        assertEquals("AV. MARGINAL, N° 240, JUNIN-CHANCHAMAYO-PERENE", registro(DOMICILIO, domicilio)["descripcion"].asString())
    }

    @Test
    fun `an obra saved in the admin gets its total metrado`() {
        val obra =
            crear(
                OBRA_COMPLEMENTARIA,
                mapOf(
                    "declaracion" to nuevaDeclaracion(),
                    "ingreso" to "POR CATEGORIAS",
                    "material" to "LADRILLO",
                    "tipo_obra" to "MUROS PERIMETRICOS O CERCOS",
                    "estado_conservacion" to "BUENO",
                    "anio_construccion" to 2024,
                    "mes_construccion" to 2,
                    "numero_piso" to 1,
                    "cantidad" to 2,
                    "metrado" to 3.5
                )
            )
        assertEquals(7.0, registro(OBRA_COMPLEMENTARIA, obra)["total_metrado"].asDouble())
        guardar(OBRA_COMPLEMENTARIA, obra, "cantidad" to 3)
        assertEquals(10.5, registro(OBRA_COMPLEMENTARIA, obra)["total_metrado"].asDouble())
    }

    // core's record api: a create names what it has, an update replaces every field (so it sends them all)
    private fun crear(
        objeto: String,
        attributes: Map<String, Any?>
    ): String = tree(send("POST", "/api/objects/$objeto/records", mapOf("attributes" to attributes), HttpStatus.CREATED))["id"].asString()

    private fun guardar(
        objeto: String,
        id: String,
        cambio: Pair<String, Any?>
    ) {
        send("PUT", "/api/objects/$objeto/records/$id", mapOf("attributes" to fields(registro(objeto, id)) + cambio), HttpStatus.OK)
    }

    private fun registro(
        objeto: String,
        id: String
    ): JsonNode = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK))["attributes"]
}
