package srtm.rentas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import wasichai.core.common.PageRequest

// Registros past one page: the keyset pass (cursor, no COUNT) must return every row once, in the order asked
class RegistrosApiTest : SrtmApiTest() {
    @Autowired
    private lateinit var registros: Registros

    @Test
    fun `all reads every row past one page, once each, in order`() {
        val ubigeo = uniqueDocumento().takeLast(6)
        val nombres = (1..PageRequest.MAX_SIZE + 1).map { "V%04d".format(it) }
        nombres.forEach { crearVia(ubigeo, it) }
        val leidas = comoAdmin { registros.all(VIA, Via::class.java, filters = mapOf("ubigeo" to ubigeo), sort = "nombre") }
        assertEquals(nombres, leidas.map { it.nombre })
        val alReves = comoAdmin { registros.all(VIA, Via::class.java, filters = mapOf("ubigeo" to ubigeo), sort = "nombre", descending = true) }
        assertEquals(nombres.reversed(), alReves.map { it.nombre })
    }

    private fun crearVia(
        ubigeo: String,
        nombre: String
    ) {
        send(
            "POST",
            "/api/objects/$VIA/records",
            mapOf("attributes" to mapOf("tipo_via" to "CALLE", "nombre" to nombre, "ubigeo" to ubigeo)),
            HttpStatus.CREATED
        )
    }
}
