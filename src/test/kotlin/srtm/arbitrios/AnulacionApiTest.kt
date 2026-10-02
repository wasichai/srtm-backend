package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

// decision 5 of the plan: a cuota is corrected by an anulación added to it, never by an update. the annulled cuota stops
// counting, and the next determination of its predio writes it again with the next version of its clave
class AnulacionApiTest : ConArbitriosApiTest() {
    private fun anulacion(
        motivo: String? = "Uso mal declarado",
        observacion: String? = "Informe de fiscalización de prueba"
    ) = mapOf("motivo" to motivo, "observacion" to observacion)

    @Test
    fun `an annulled cuota stops counting, and the next determination writes its next version`() {
        val e = Escenario()
        val cuota = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))[0]
        val id = cuota["id"].asString()
        val a = tree(send("POST", "/api/srtm/arbitrios/cuotas/$id/anulacion", anulacion(), HttpStatus.CREATED))
        assertEquals(id, a["cuota"].asString())
        assertEquals(e.predio, a["predio"].asString())

        val m = matriz(e)
        assertEquals(153.0 - cuota["monto"].asDouble(), m["total"].asDouble())
        assertEquals(1, m["pendientes"].asInt())

        val otra = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))
        assertEquals(1, otra.size())
        assertEquals(cuota["clave"].asString().removeSuffix("|1") + "|2", otra[0]["clave"].asString())
        assertEquals(153.0, matriz(e)["total"].asDouble())
    }

    @Test
    fun `a cuota is annulled once, with why, and its anulación is never edited nor deleted`() {
        val e = Escenario()
        val id = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))[0]["id"].asString()
        rejected("POST", "/api/srtm/arbitrios/cuotas/$id/anulacion", anulacion(motivo = " "), "motivo")
        rejected("POST", "/api/srtm/arbitrios/cuotas/$id/anulacion", anulacion(observacion = "ok"), "observacion")
        val a = tree(send("POST", "/api/srtm/arbitrios/cuotas/$id/anulacion", anulacion(), HttpStatus.CREATED))
        send("POST", "/api/srtm/arbitrios/cuotas/$id/anulacion", anulacion(), HttpStatus.CONFLICT)

        val registro = "/api/objects/$ANULACION_CUOTA_ARBITRIO/records/${a["id"].asString()}"
        send("PUT", registro, mapOf("attributes" to (fields(a) - "id") + ("motivo" to "otro")), HttpStatus.CONFLICT)
        send("DELETE", registro, null, HttpStatus.CONFLICT)
    }

    @Test
    fun `who may not create anulaciones gets a 403`() {
        val e = Escenario()
        val id = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))[0]["id"].asString()
        val lector = funcionario(listOf(permiso(null, "READ")))
        send("POST", "/api/srtm/arbitrios/cuotas/$id/anulacion", anulacion(), HttpStatus.FORBIDDEN, lector)
    }
}
