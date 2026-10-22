package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest

// the model of the integration with the caja (model/model.json): the four objects exist, and only srtm's service writes them
class ModeloCajaApiTest : SrtmApiTest() {
    @Test
    fun `elModeloSeAplica`() {
        val objetos =
            tree(send("GET", "/api/objects", null, HttpStatus.OK))
                .iterator()
                .asSequence()
                .map { it["name"].asString() }
                .toSet()
        assertTrue(objetos.containsAll(ObjetosCaja.TODOS), objetos.toString())
    }

    @Test
    fun `laApiGenericaNoEscribeCaja`() {
        val c = inscribir()
        val orden =
            mapOf(
                "referencia" to "T-${uniqueDocumento()}",
                "tipo" to "PREDIAL",
                "ranura" to "2026-1",
                "anio" to 2026,
                "importe" to 10,
                "concepto" to "Impuesto predial 2026",
                "estado" to "POR_ENVIAR",
                "contribuyente" to c
            )
        val problema = tree(send("POST", "/api/objects/${ObjetosCaja.ORDEN_CAJA}/records", mapOf("attributes" to orden), HttpStatus.FORBIDDEN))
        assertTrue(problema["detail"].asString().contains("«orden_caja»"), problema.toString())
        assertEquals(0, tree(send("GET", "/api/objects/${ObjetosCaja.ORDEN_CAJA}/records?size=1", null, HttpStatus.OK))["totalElements"].asLong())
    }
}
