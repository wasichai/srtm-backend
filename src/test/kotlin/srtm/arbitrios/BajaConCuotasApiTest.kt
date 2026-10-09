package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// a predio or contribuyente with cuotas de arbitrio is not deleted, even once its declarations are gone: a cuota is
// appendOnly (model/model.json), and core would refuse its predio's or contribuyente's delete only after the
// contribuyente's lists were already gone. srtm refuses first, in Spanish, and touches nothing
class BajaConCuotasApiTest : ConArbitriosApiTest() {
    // the escenario's cuotas determined, and then its only declaración deleted (an empty one may go)
    private fun sinDeclaracion(e: Escenario) {
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val declaracion = tree(send("GET", "/api/objects/declaracion_predial/records?predio=${e.predio}", null, HttpStatus.OK))["content"][0]["id"]
        send("DELETE", "/api/srtm/declaraciones/${declaracion.asString()}", null, HttpStatus.NO_CONTENT)
    }

    private fun conflict(path: String): String = tree(send("DELETE", path, null, HttpStatus.CONFLICT))["detail"].asString()

    private fun lista(
        contribuyente: String,
        nombre: String
    ) = tree(send("GET", "/api/srtm/contribuyentes/$contribuyente/$nombre", null, HttpStatus.OK)).size()

    @Test
    fun `a contribuyente with cuotas and no declaration is not deleted, and keeps its lists`() {
        val e = Escenario()
        post("/api/srtm/contribuyentes/${e.contribuyente}/medios-contacto", mapOf("tipo" to "TELEFONO CELULAR", "valor" to "987654321", "principal" to true))
        sinDeclaracion(e)

        assertEquals(
            "El contribuyente tiene 24 cuotas de arbitrio: no se puede eliminar, una cuota no se borra nunca",
            conflict("/api/srtm/contribuyentes/${e.contribuyente}")
        )
        send("GET", "/api/srtm/contribuyentes/${e.contribuyente}", null, HttpStatus.OK)
        assertEquals(1, lista(e.contribuyente, "medios-contacto"))
    }

    @Test
    fun `a predio with cuotas and no declaration is not deleted`() {
        val e = Escenario()
        sinDeclaracion(e)

        assertEquals("El predio tiene 24 cuotas de arbitrio: no se puede eliminar, una cuota no se borra nunca", conflict("/api/srtm/predios/${e.predio}"))
        send("GET", "/api/srtm/predios/${e.predio}", null, HttpStatus.OK)
    }
}
