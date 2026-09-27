package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.io.File

// the tipos de unidad urbana are the catastro fiscal's TIPO_UU (model/data/tipos_unidad_urbana.csv) in page 5's order,
// and an address writes each with its ABREV_UU (wasichai/srtm-backend#34)
class TiposUnidadUrbanaApiTest : SrtmApiTest() {
    private val tipos = File("model/data/tipos_unidad_urbana.csv").readLines().drop(1).map { it.split(",") }

    @Test
    fun `every field of a unidad urbana offers the 43 types of TIPO_UU, sorted as the srtm lists them`() {
        val nombres = tipos.map { it[1] }
        assertEquals(43, nombres.size)
        assertEquals(nombres.sorted(), nombres)
        val catalogos = tree(send("GET", "/api/srtm/catalogos", null, HttpStatus.OK))
        for ((objeto, campo) in listOf(
            "domicilio" to "tipo_unidad_urbana",
            "predio" to "tipo_zona",
            "unidad_urbana" to "tipo_unidad_urbana",
            "catastro_fiscal" to "tipo_zona"
        )) {
            assertEquals(nombres, catalogos[objeto][campo].map { it.asString() }, "$objeto.$campo")
        }
    }

    @Test
    fun `a domicilio writes its unidad urbana with the ABREV_UU of its type`() {
        val id = inscribir()
        post(
            "/api/srtm/contribuyentes/$id/domicilios",
            mapOf(
                "tipo_domicilio" to "FISCAL",
                "tipo_predio" to "PREDIO URBANO",
                "ubigeo" to "120302",
                "departamento" to "JUNIN",
                "provincia" to "CHANCHAMAYO",
                "distrito" to "PERENE",
                "tipo_via" to "AVENIDA",
                "via" to "MARGINAL",
                "numero" to "234",
                "tipo_unidad_urbana" to "ASOCIACION PRO VIVIENDA",
                "unidad_urbana" to "LOS PINOS"
            )
        )
        val contribuyente = tree(send("GET", "/api/srtm/contribuyentes/$id", null, HttpStatus.OK))["contribuyente"]
        assertEquals("AV. MARGINAL, N° 234, A.P.V. LOS PINOS, JUNIN-CHANCHAMAYO-PERENE", contribuyente["domicilio_fiscal"].asString())
    }
}
