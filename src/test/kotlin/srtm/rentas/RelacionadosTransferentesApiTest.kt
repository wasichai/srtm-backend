package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// relacionados and transferentes (pages 8 and 15): a code per parent, the razón social of a company (RUC) or the
// names of anyone else, and the fuente de información
class RelacionadosTransferentesApiTest : SrtmApiTest() {
    @Test
    fun `relacionados are coded per contribuyente, and an update keeps the code`() {
        val id = inscribir()
        val first = post("/api/srtm/contribuyentes/$id/relacionados", conyuge())
        val second = post("/api/srtm/contribuyentes/$id/relacionados", conyuge() + ("codigo" to "777"))
        assertEquals("001", first["codigo"].asString())
        assertEquals("002", second["codigo"].asString())
        assertEquals(FUENTE_MANUAL, first["fuente_informacion"].asString())
        assertEquals("001", post("/api/srtm/contribuyentes/${inscribir()}/relacionados", conyuge())["codigo"].asString())

        val changed = put("/api/srtm/relacionados/${first["id"].asString()}", fields(first) + mapOf("codigo" to "999", "nombres" to "DUBERLI JOSE"))
        assertEquals("001", changed["codigo"].asString())
        assertEquals("DUBERLI JOSE", changed["nombres"].asString())
        get("/api/srtm/contribuyentes/$id/relacionados")
            .expectBody()
            .jsonPath("$[0].codigo")
            .isEqualTo("001")
            .jsonPath("$[1].codigo")
            .isEqualTo("002")
    }

    @Test
    fun `a relacionado with RUC is named by its razon social, anyone else by names`() {
        val id = inscribir()
        val apoderado = mapOf("tipo_relacionado" to "APODERADO", "tipo_documento" to "RUC", "numero_documento" to "20123456789")
        rejected("POST", "/api/srtm/contribuyentes/$id/relacionados", apoderado, "razon_social")
        val empresa = post("/api/srtm/contribuyentes/$id/relacionados", apoderado + ("razon_social" to "INVERSIONES PERENE SAC"))
        assertEquals("INVERSIONES PERENE SAC", empresa["razon_social"].asString())
        assertTrue(empresa["nombres"] == null || empresa["nombres"].isNull)

        rejected("PUT", "/api/srtm/relacionados/${empresa["id"].asString()}", fields(empresa) + ("razon_social" to " "), "razon_social")
        rejected("POST", "/api/srtm/contribuyentes/$id/relacionados", conyuge() - "nombres", "nombres")
    }

    @Test
    fun `transferentes are coded per declaracion, and one with RUC needs its razon social`() {
        val declaracion = nuevaDeclaracion()
        val persona = post("/api/srtm/declaraciones/$declaracion/transferentes", transferente())
        assertEquals("001", persona["codigo"].asString())
        assertEquals(FUENTE_MANUAL, persona["fuente_informacion"].asString())

        val ruc = transferente() - listOf("apellido_paterno", "nombres") + mapOf("tipo_documento" to "RUC", "numero_documento" to "20123456789")
        rejected("POST", "/api/srtm/declaraciones/$declaracion/transferentes", ruc, "razon_social")
        val empresa = post("/api/srtm/declaraciones/$declaracion/transferentes", ruc + ("razon_social" to "INVERSIONES PERENE SAC"))
        assertEquals("002", empresa["codigo"].asString())
        assertEquals("INVERSIONES PERENE SAC", empresa["razon_social"].asString())
        assertEquals("001", post("/api/srtm/declaraciones/${nuevaDeclaracion()}/transferentes", transferente())["codigo"].asString())

        val otra = fields(empresa) + mapOf("codigo" to null, "fuente_informacion" to "PIDE SUNAT")
        val changed = put("/api/srtm/transferentes/${empresa["id"].asString()}", otra)
        assertEquals("002", changed["codigo"].asString())
        assertEquals("PIDE SUNAT", changed["fuente_informacion"].asString())
        rejected("PUT", "/api/srtm/transferentes/${persona["id"].asString()}", fields(persona) + ("nombres" to null), "nombres")
    }

    private fun conyuge() = mapOf("tipo_relacionado" to "CONYUGE", "tipo_documento" to "DNI", "numero_documento" to "43434352", "nombres" to "DUBERLI")

    private fun transferente() =
        mapOf(
            "porcentaje_transferido" to 50,
            "tipo_documento" to "DNI",
            "numero_documento" to "43434352",
            "apellido_paterno" to "NEIRA",
            "nombres" to "DUBERLI",
            "departamento" to "JUNIN",
            "provincia" to "CHANCHAMAYO",
            "distrito" to "PERENE",
            "descripcion_domicilio" to "JR. LIMA 123"
        )
}
