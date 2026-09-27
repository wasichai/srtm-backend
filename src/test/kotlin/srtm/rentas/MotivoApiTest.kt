package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// the motivo (pages 2 and 11): INSCRIPCION when the portal registers a contribuyente or a declaración, ACTUALIZACION
// once the portal edits it. the rows of their lists and a condómino's recomputed values are no edit of theirs
class MotivoApiTest : SrtmApiTest() {
    @Test
    fun `an edited contribuyente is an actualizacion, whatever the body says, and its domicilios are not an edit`() {
        val inscrito = post("/api/srtm/contribuyentes", persona())
        val id = inscrito["id"].asString()
        assertEquals("INSCRIPCION", inscrito["motivo"].asString())
        // the wizard's next step: the fiscal domicilio, copied to the contribuyente
        post("/api/srtm/contribuyentes/$id/domicilios", fiscal())
        assertEquals("INSCRIPCION", contribuyente(id)["motivo"].asString())

        val editado = put("/api/srtm/contribuyentes/$id", fields(contribuyente(id)) + mapOf("motivo" to "INSCRIPCION", "observacion" to "EDITADO"))
        assertEquals("ACTUALIZACION", editado["motivo"].asString())
        assertEquals("EDITADO", editado["observacion"].asString())
        // the rest of the datos de la declaración stay
        assertEquals(inscrito["codigo"].asString(), editado["codigo"].asString())
        assertEquals(inscrito["numero_declaracion"].asInt(), editado["numero_declaracion"].asInt())
        assertEquals("DECLARACION JURADA", editado["medio_determinacion"].asString())
        assertEquals("ACTUALIZACION", contribuyente(id)["motivo"].asString())
    }

    @Test
    fun `a contribuyente of the padron, edited, is an actualizacion that gets no code`() {
        // as import_predios.py loads it: straight into core, without motivo or code
        val importado =
            mapOf(
                "tipo_persona" to "NATURAL",
                "tipo_documento" to "DNI",
                "numero_documento" to uniqueDocumento(),
                "nombre_completo" to "MENDOZA TAYPE"
            )
        val id = core(CONTRIBUYENTE, importado)
        val editado = put("/api/srtm/contribuyentes/$id", persona(importado["numero_documento"]) + ("observacion" to "EDITADO"))
        assertEquals("ACTUALIZACION", editado["motivo"].asString())
        assertTrue(vacio(editado["codigo"]))
        assertTrue(vacio(editado["numero_declaracion"]))
    }

    @Test
    fun `an edited declaracion is an actualizacion, and neither its niveles nor its condominos make one`() {
        val predio = predio()
        val creada = post("/api/srtm/declaraciones", declaracion(inscribir(), predio))
        val a = creada["id"].asString()
        assertEquals("INSCRIPCION", creada["motivo"].asString())
        post("/api/srtm/declaraciones/$a/niveles", nivel())
        // a condómino joins: a's condición and % are recomputed
        val b = post("/api/srtm/declaraciones", declaracion(inscribir(), predio) + ("porcentaje_condominio" to 40))["id"].asString()
        assertEquals("CONDOMINO", declaracionDe(a)["condicion_propiedad"].asString())
        assertEquals("INSCRIPCION", declaracionDe(a)["motivo"].asString())

        val editada = put("/api/srtm/declaraciones/$a", fields(declaracionDe(a)) + mapOf("motivo" to "INSCRIPCION", "otros_datos" to "EDITADA"))
        assertEquals("ACTUALIZACION", editada["motivo"].asString())
        assertEquals("EDITADA", editada["otros_datos"].asString())
        // no new number, and the rest of the datos del predio stay
        assertEquals(creada["numero_declaracion"].asInt(), editada["numero_declaracion"].asInt())
        assertEquals("DECLARACION JURADA", editada["medio_determinacion"].asString())
        assertEquals("ACTUALIZACION", declaracionDe(a)["motivo"].asString())
        // nor is its condómino
        assertEquals("INSCRIPCION", declaracionDe(b)["motivo"].asString())
    }

    @Test
    fun `a declaracion of the padron, edited, is an actualizacion that gets no number`() {
        val contribuyente =
            core(
                CONTRIBUYENTE,
                mapOf("tipo_persona" to "NATURAL", "tipo_documento" to "DNI", "numero_documento" to uniqueDocumento(), "nombre_completo" to "MENDOZA TAYPE")
            )
        val importada = core(DECLARACION, mapOf("contribuyente" to contribuyente, "predio" to predio(), "anio" to 2026, "secuencia_uso" to "001"))
        val editada = put("/api/srtm/declaraciones/$importada", fields(declaracionDe(importada)) + ("otros_datos" to "EDITADA"))
        assertEquals("ACTUALIZACION", editada["motivo"].asString())
        assertEquals("EDITADA", editada["otros_datos"].asString())
        assertTrue(vacio(editada["numero_declaracion"]))
    }

    private fun persona(numero: String? = uniqueDocumento()) =
        mapOf(
            "tipo_contribuyente" to "PERSONA NATURAL",
            "tipo_documento" to "DNI",
            "numero_documento" to numero,
            "apellido_paterno" to "FLORES",
            "nombres" to "JUNIOR",
            "sexo" to "HOMBRE",
            "estado_civil" to "SOLTERO"
        )

    private fun fiscal() =
        mapOf(
            "tipo_domicilio" to "FISCAL",
            "tipo_predio" to "PREDIO URBANO",
            "ubigeo" to "120302",
            "departamento" to "JUNIN",
            "provincia" to "CHANCHAMAYO",
            "distrito" to "PERENE",
            "tipo_via" to "AVENIDA",
            "via" to "MARGINAL",
            "numero" to "234"
        )

    private fun nivel() =
        mapOf(
            "tipo_nivel" to "PISO",
            "numero_piso" to 1,
            "anio_construccion" to 2023,
            "mes_construccion" to 1,
            "material" to "LADRILLO",
            "estado_conservacion" to "BUENO",
            "area_construida" to 120
        )

    private fun declaracion(
        contribuyente: String,
        predio: String
    ) = mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to 2026, "secuencia_uso" to "1", "valor_autoavaluo" to 10000)

    private fun contribuyente(id: String): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$id", null, HttpStatus.OK))["contribuyente"]

    private fun declaracionDe(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]

    // a record written straight into core, as the importers do: its id
    private fun core(
        objectName: String,
        attributes: Map<String, Any?>
    ): String = post("/api/objects/$objectName/records", mapOf("attributes" to attributes))["id"].asString()

    private fun vacio(node: JsonNode?) = node == null || node.isNull
}
