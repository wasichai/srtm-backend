package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// the srtm's "(*) registrar al menos 1 domicilio fiscal": once a contribuyente has domicilios, exactly one of them
// is its active fiscal one, and the contribuyente's domicilio_fiscal follows it
class DomicilioFiscalApiTest : SrtmApiTest() {
    @Test
    fun `the first domicilio must be the active fiscal one`() {
        val id = inscribir()
        rejected("POST", "/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL"), "tipo_domicilio")
        rejected("POST", "/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL") + ("estado" to "INACTIVO"), "estado")
        assertEquals(0, domicilios(id).size())
    }

    @Test
    fun `a second active fiscal domicilio is refused, whether added or changed into`() {
        val id = inscribir()
        post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        val problem = rejected("POST", "/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL", numero = "300"), "tipo_domicilio")
        assertEquals("ya tiene otro domicilio fiscal activo: actualice ese domicilio", problem["errors"][0]["message"].asString())

        val real = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", numero = "300"))
        rejected("PUT", "/api/srtm/domicilios/${real["id"].asString()}", fields(real) + ("tipo_domicilio" to "FISCAL"), "tipo_domicilio")
        // an inactive one is history, not a second fiscal
        post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL", numero = "400") + ("estado" to "INACTIVO"))
        assertEquals(1, domicilios(id).iterator().asSequence().count { it["tipo_domicilio"].asString() == "FISCAL" && it["estado"].asString() == "ACTIVO" })
    }

    @Test
    fun `the only active fiscal domicilio cannot be removed, inactivated or turned into another kind`() {
        val id = inscribir()
        val fiscal = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        val fiscalId = fiscal["id"].asString()

        val problem = rejected("DELETE", "/api/srtm/domicilios/$fiscalId", null, "tipo_domicilio")
        assertEquals("No se puede eliminar el único domicilio fiscal activo", problem["detail"].asString())
        rejected("PUT", "/api/srtm/domicilios/$fiscalId", fields(fiscal) + ("estado" to "INACTIVO"), "estado")
        rejected("PUT", "/api/srtm/domicilios/$fiscalId", fields(fiscal) + ("tipo_domicilio" to "REAL"), "tipo_domicilio")

        val stored = domicilios(id)
        assertEquals(1, stored.size())
        assertEquals("FISCAL", stored[0]["tipo_domicilio"].asString())
        assertEquals("ACTIVO", stored[0]["estado"].asString())
        assertEquals(DESCRIPCION, contribuyente(id)["domicilio_fiscal"].asString())
    }

    @Test
    fun `other domicilios come and go freely, and only the fiscal one moves the domicilio fiscal`() {
        val id = inscribir()
        val fiscal = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        val real = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", numero = "300"))
        val before = core("contribuyente", id)["updatedAt"].asString()

        put("/api/srtm/domicilios/${real["id"].asString()}", fields(real) + ("numero" to "310"))
        put("/api/srtm/domicilios/${real["id"].asString()}", fields(real) + ("estado" to "INACTIVO"))
        // nothing of the fiscal one changed: the contribuyente is left alone
        assertEquals(before, core("contribuyente", id)["updatedAt"].asString())
        delete("/api/srtm/domicilios/${real["id"].asString()}")
        assertEquals(1, domicilios(id).size())

        put("/api/srtm/domicilios/${fiscal["id"].asString()}", fields(fiscal) + mapOf("numero" to "240", "distrito" to "SAN RAMON"))
        val moved = contribuyente(id)
        assertEquals("AV. MARGINAL, N° 240, JUNIN-CHANCHAMAYO-SAN RAMON", moved["domicilio_fiscal"].asString())
        assertEquals("SAN RAMON", moved["domicilio_distrito"].asString())
    }

    @Test
    fun `with two active fiscal ones from before, one steps down and the other becomes the domicilio fiscal`() {
        val id = inscribir()
        val first = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        // written before the rule, straight into core
        send(
            "POST",
            "/api/objects/domicilio/records",
            mapOf(
                "attributes" to
                    domicilio("FISCAL", numero = "500") +
                    mapOf("contribuyente" to id, "estado" to "ACTIVO", "descripcion" to "AV. MARGINAL, N° 500, JUNIN-CHANCHAMAYO-PERENE")
            ),
            HttpStatus.CREATED
        )
        // still two: this one cannot stay fiscal as it is
        rejected("PUT", "/api/srtm/domicilios/${first["id"].asString()}", fields(first) + ("numero" to "250"), "tipo_domicilio")

        put("/api/srtm/domicilios/${first["id"].asString()}", fields(first) + ("tipo_domicilio" to "REAL"))
        assertEquals("AV. MARGINAL, N° 500, JUNIN-CHANCHAMAYO-PERENE", contribuyente(id)["domicilio_fiscal"].asString())
    }

    private fun domicilio(
        tipo: String,
        numero: String = "234"
    ) = mapOf(
        "tipo_domicilio" to tipo,
        "tipo_predio" to "PREDIO URBANO",
        "ubigeo" to "120302",
        "departamento" to "JUNIN",
        "provincia" to "CHANCHAMAYO",
        "distrito" to "PERENE",
        "tipo_via" to "AVENIDA",
        "via" to "MARGINAL",
        "numero" to numero
    )

    private fun domicilios(id: String): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$id/domicilios", null, HttpStatus.OK))

    private fun contribuyente(id: String): JsonNode = tree(send("GET", "/api/srtm/contribuyentes/$id", null, HttpStatus.OK))["contribuyente"]

    private fun core(
        objectName: String,
        id: String
    ): JsonNode = tree(send("GET", "/api/objects/$objectName/records/$id", null, HttpStatus.OK))

    private companion object {
        const val DESCRIPCION = "AV. MARGINAL, N° 234, JUNIN-CHANCHAMAYO-PERENE"
    }
}
