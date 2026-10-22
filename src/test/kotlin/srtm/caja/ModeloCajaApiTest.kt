package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import srtm.rentas.Registros
import srtm.rentas.SrtmApiTest
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// the model of the integration with the caja (model/model.json): the four objects exist, and only srtm's service writes them
class ModeloCajaApiTest : SrtmApiTest() {
    @Autowired
    lateinit var registros: Registros

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

    // only from the service, not never: with srtm's mark the orden is written, and its DATETIME fields come back as
    // the same Instants
    @Test
    fun `elServicioSiEscribeCaja`() {
        val c = inscribir()
        val tomado = Instant.parse("2026-10-22T15:04:05.123456Z")
        val enviada = Instant.parse("2026-10-22T15:04:06Z")
        val referencia = "T-${UUID.randomUUID()}"
        val (creada, leida) =
            comoAdmin {
                val creada =
                    registros.create(
                        ObjetosCaja.ORDEN_CAJA,
                        OrdenCaja::class.java,
                        mapOf(
                            "referencia" to referencia,
                            "tipo" to TipoOrdenCaja.PREDIAL,
                            "ranura" to "PREDIAL|$c|2026|1",
                            "anio" to 2026,
                            "importe" to BigDecimal("10.50"),
                            "concepto" to "Impuesto predial 2026 cuota 1",
                            "fecha_exigibilidad" to LocalDate.of(2026, 1, 1).toString(),
                            "estado" to EstadoOrdenCaja.ENVIADA,
                            "intentos" to 1,
                            "tomado_hasta" to tomado.toString(),
                            "enviada_en" to enviada.toString(),
                            "contribuyente" to c
                        )
                    )
                creada to registros.get(ObjetosCaja.ORDEN_CAJA, OrdenCaja::class.java, UUID.fromString(creada.id))
            }
        assertEquals(referencia, leida.referencia)
        assertEquals(tomado, leida.tomadoHasta)
        assertEquals(enviada, leida.enviadaEn)
        assertEquals(creada.tomadoHasta, leida.tomadoHasta)
        assertEquals(0, BigDecimal("10.50").compareTo(leida.importe))
        assertEquals(c, leida.contribuyente)
    }
}
