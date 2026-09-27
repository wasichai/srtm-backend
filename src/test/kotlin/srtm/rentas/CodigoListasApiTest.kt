package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// the srtm's lists number their rows (pp. 4, 7, 9: código, número): domicilios, medios de contacto and documentos
// sustento get a code under their contribuyente when added, kept by every update and never shifted by a removal
class CodigoListasApiTest : SrtmApiTest() {
    @Test
    fun `domicilios are coded per contribuyente, and a removal shifts no code`() {
        val id = inscribir()
        val fiscal = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("FISCAL"))
        // a code in the body is the backend's to ignore
        val segundo = post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", "300") + ("codigo" to "777"))
        post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", "400"))
        assertEquals("001", fiscal["codigo"].asString())
        assertEquals("002", segundo["codigo"].asString())

        delete("/api/srtm/domicilios/${segundo["id"].asString()}")
        assertEquals(listOf("001", "003"), codigos("/api/srtm/contribuyentes/$id/domicilios"))
        assertEquals("004", post("/api/srtm/contribuyentes/$id/domicilios", domicilio("REAL", "500"))["codigo"].asString())

        val changed = put("/api/srtm/domicilios/${fiscal["id"].asString()}", fields(fiscal) + mapOf("codigo" to "999", "numero" to "240"))
        assertEquals("001", changed["codigo"].asString())
        assertEquals("240", changed["numero"].asString())
        assertEquals("001", post("/api/srtm/contribuyentes/${inscribir()}/domicilios", domicilio("FISCAL"))["codigo"].asString())
    }

    @Test
    fun `medios de contacto are coded per contribuyente, and an update keeps the code`() {
        val id = inscribir()
        val celular = post("/api/srtm/contribuyentes/$id/medios-contacto", medio("987654321"))
        val correo = post("/api/srtm/contribuyentes/$id/medios-contacto", medio("064123456") + ("codigo" to "777"))
        assertEquals("001", celular["codigo"].asString())
        assertEquals("002", correo["codigo"].asString())

        val changed = put("/api/srtm/medios-contacto/${correo["id"].asString()}", fields(correo) + mapOf("codigo" to null, "valor" to "064654321"))
        assertEquals("002", changed["codigo"].asString())
        assertEquals("064654321", changed["valor"].asString())
        assertEquals(listOf("001", "002"), codigos("/api/srtm/contribuyentes/$id/medios-contacto"))
        assertEquals("001", post("/api/srtm/contribuyentes/${inscribir()}/medios-contacto", medio("987654321"))["codigo"].asString())
    }

    @Test
    fun `documentos sustento are numbered per contribuyente, and an update keeps the number`() {
        val id = inscribir()
        val poder = post("/api/srtm/contribuyentes/$id/sustentos", sustento("PE-1"))
        val partida = post("/api/srtm/contribuyentes/$id/sustentos", sustento("PE-2"))
        assertEquals("001", poder["codigo"].asString())
        assertEquals("002", partida["codigo"].asString())

        val changed = put("/api/srtm/sustentos/${poder["id"].asString()}", fields(poder) + mapOf("codigo" to "050", "folios" to 3))
        assertEquals("001", changed["codigo"].asString())
        assertEquals(3, changed["folios"].asInt())
        delete("/api/srtm/sustentos/${poder["id"].asString()}")
        assertEquals(listOf("002"), codigos("/api/srtm/contribuyentes/$id/sustentos"))
        assertEquals("003", post("/api/srtm/contribuyentes/$id/sustentos", sustento("PE-3"))["codigo"].asString())
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

    private fun medio(valor: String) = mapOf("tipo" to "TELEFONO CELULAR", "valor" to valor)

    private fun sustento(numero: String) = mapOf("documento" to "PODER ESPECIAL", "numero_documento" to numero, "tipo_presentacion" to "ORIGINAL")

    // a list's codes, in its order
    private fun codigos(path: String): List<String> =
        tree(send("GET", path, null, HttpStatus.OK))
            .iterator()
            .asSequence()
            .map { it["codigo"].asString() }
            .toList()
}
