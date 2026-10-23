package srtm.caja

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.JsonNode

// #168: before caja collects an orden it asks POST /api/srtm/caja/ordenes/vigencia, as the service account (role CAJA),
// whether srtm's orden still stands: sent (ENVIADA), its cuota not annulled nor replaced, and not paid. the answer has
// each referencia asked, in order, with srtm's importe
class VigenciaApiTest : ConPagosApiTest() {
    @Test
    fun `vigente`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)

        val (estado, cuerpo) = preguntar(listOf(referencia(cuota) to importe(cuota["monto"])))

        assertEquals(HttpStatus.OK, estado, cuerpo)
        val o = tree(cuerpo)["ordenes"].single()
        assertEquals(referencia(cuota), o["referencia_externa"].asString())
        assertTrue(o["vigente"].asBoolean(), cuerpo)
        assertEquals(importe(cuota["monto"]), o["importe"].asString())
        assertTrue(o["motivo"].isNull, cuerpo)
    }

    // the same referencias in the same order, and a repeated one answered each time
    @Test
    fun `contestaCadaReferenciaEnSuOrden`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        val ref = referencia(cuota)

        val ordenes = tree(preguntar(listOf(ref to "1.00", "ARB-no-esta" to "1.00", ref to "1.00")).second)["ordenes"]

        assertEquals(listOf(ref, "ARB-no-esta", ref), lista(ordenes) { it["referencia_externa"].asString() })
        assertEquals(listOf(true, false, true), lista(ordenes) { it["vigente"].asBoolean() })
    }

    @Test
    fun `anulada`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        send(
            "POST",
            "/api/srtm/arbitrios/cuotas/${cuota["id"].asString()}/anulacion",
            mapOf("motivo" to "Uso mal declarado", "observacion" to "Informe de fiscalización de prueba"),
            HttpStatus.CREATED
        )

        val o = una(referencia(cuota), importe(cuota["monto"]))

        assertEquals(false, o["vigente"].asBoolean())
        assertEquals("anulada: Uso mal declarado", o["motivo"].asString())
        assertEquals(importe(cuota["monto"]), o["importe"].asString())
    }

    @Test
    fun `versionSuperada`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v1"
        val importe = importe(ordenes(e.contribuyente, e.anio).getValue(v1)["importe"])
        cambiarAutoavaluo(dj, 80000)
        val v2 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v2"

        val viejo = una(v1, importe)
        val nuevo = una(v2, importe)

        assertEquals(false, viejo["vigente"].asBoolean())
        assertEquals("sustituida por $v2", viejo["motivo"].asString())
        assertTrue(nuevo["vigente"].asBoolean(), nuevo.toString())
    }

    @Test
    fun `importeDistintoDevuelveElDeSrtm`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)

        val o = una(referencia(cuota), "1.00")

        assertTrue(o["vigente"].asBoolean())
        assertEquals(importe(cuota["monto"]), o["importe"].asString())
    }

    @Test
    fun `referenciaDesconocida`() {
        val o = una("ARB-no-existe", "1.00")

        assertEquals(false, o["vigente"].asBoolean())
        assertTrue(o["importe"].isNull)
        assertEquals("no existe", o["motivo"].asString())
    }

    // caja must not charge an orden already paid
    @Test
    fun `yaPagadaNoEsVigente`() {
        val e = Escenario()
        val cuota = determinadaYEnviada(e)
        assertEquals(HttpStatus.OK, pagar(registrado(listOf(referencia(cuota) to importe(cuota["monto"])), numero = "001-0000099")).first)

        val o = una(referencia(cuota), importe(cuota["monto"]))

        assertEquals(false, o["vigente"].asBoolean())
        assertEquals("ya pagada con el recibo 001-0000099", o["motivo"].asString())
    }

    // the retiro of the orden is refused (caja says it was paid there): PAGADA_EN_CAJA. srtm has no recibo of it (a pago
    // that comes after goes a cuenta, and one applied before is never withdrawn), so it says it was paid in caja. with
    // the recibo it names it: decidir's rule, in ConciliacionTest
    @Test
    fun `pagadaEnCajaSinReciboLoDice`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = "PREDIAL-${e.contribuyente}-${e.anio}-C1-v1"
        val importe = importe(ordenes(e.contribuyente, e.anio).getValue(v1)["importe"])
        respuesta = { if (!it.alta && it.referencia == v1) 409 to """{"detail":"Pagada con el recibo 001-0000123"}""" else null }
        escribir("POST", "/api/srtm/declaraciones/$dj/anular", mapOf("motivo_anulacion" to "Declarada dos veces"))
        assertEquals(EstadoOrdenCaja.PAGADA_EN_CAJA, ordenes(e.contribuyente, e.anio).getValue(v1)["estado"].asString())

        val o = una(v1, importe)

        assertEquals(false, o["vigente"].asBoolean())
        assertEquals("ya pagada en caja", o["motivo"].asString())
    }

    // the queries do not grow with the referencias: 50 ordenes of 50 predios, all with their cuota annulled (the reasons
    // are read too), under a second
    @Test
    fun `cincuentaAnuladasDeCincuentaPrediosEnMenosDeUnSegundo`() {
        val referencias =
            (1..50).map {
                val e = Escenario()
                val cuota = determinadaYEnviada(e)
                send(
                    "POST",
                    "/api/srtm/arbitrios/cuotas/${cuota["id"].asString()}/anulacion",
                    mapOf("motivo" to "Uso mal declarado", "observacion" to "Informe de fiscalización de prueba"),
                    HttpStatus.CREATED
                )
                referencia(cuota) to importe(cuota["monto"])
            }
        preguntar(referencias)

        val desde = System.nanoTime()
        val (estado, cuerpo) = preguntar(referencias)
        val milisegundos = (System.nanoTime() - desde) / 1_000_000

        assertEquals(HttpStatus.OK, estado, cuerpo)
        val ordenes = tree(cuerpo)["ordenes"]
        assertEquals(50, ordenes.count { !it["vigente"].asBoolean() && it["motivo"].asString() == "anulada: Uso mal declarado" }, cuerpo)
        assertTrue(milisegundos < 1000, "tardó $milisegundos ms")
    }

    @Test
    fun `sinTokenEs401`() {
        assertEquals(HttpStatus.UNAUTHORIZED, preguntar(listOf("ARB-x" to "1.00"), null).first)
    }

    @Test
    fun `usuarioSinPermisoEs403`() {
        val sin = funcionario(listOf(permiso(ObjetosCaja.PAGO_CAJA, "READ")))

        assertEquals(HttpStatus.FORBIDDEN, preguntar(listOf("ARB-x" to "1.00"), sin).first)
    }

    @Test
    fun `sinCajaEs503`() {
        val (estado, cuerpo) = preguntar(listOf("ARB-x" to "1.00"), otraOrganizacion())

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, estado, cuerpo)
    }

    @Test
    fun `masDe50Es400`() {
        assertEquals(HttpStatus.OK, preguntar((1..50).map { "ARB-$it" to "1.00" }).first)
        assertEquals(HttpStatus.BAD_REQUEST, preguntar((1..51).map { "ARB-$it" to "1.00" }).first)
        assertEquals(HttpStatus.BAD_REQUEST, preguntar(emptyList()).first)
        assertEquals(HttpStatus.BAD_REQUEST, preguntarJson("{").first)
        assertEquals(HttpStatus.BAD_REQUEST, preguntarJson("""{"ordenes":[{"importe":"1.00"}]}""").first)
    }

    // 50 real ordenes, read in a few queries and no reliquidation: under a second
    @Test
    fun `cincuentaReferenciasEnMenosDeUnSegundo`() {
        val referencias =
            (1..3)
                .flatMap {
                    cuotasEnviadas(Escenario()).map { c -> referencia(c) to importe(c["monto"]) }
                }.take(50)
        assertEquals(50, referencias.size)
        preguntar(referencias)

        val desde = System.nanoTime()
        val (estado, cuerpo) = preguntar(referencias)
        val milisegundos = (System.nanoTime() - desde) / 1_000_000

        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertEquals(50, tree(cuerpo)["ordenes"].count { it["vigente"].asBoolean() }, cuerpo)
        assertTrue(milisegundos < 1000, "tardó $milisegundos ms")
    }

    private fun una(
        referencia: String,
        importe: String
    ): JsonNode {
        val (estado, cuerpo) = preguntar(listOf(referencia to importe))
        assertEquals(HttpStatus.OK, estado, cuerpo)
        assertNull(tree(cuerpo)["ordenes"][1])
        return tree(cuerpo)["ordenes"][0]
    }

    private fun preguntar(
        ordenes: List<Pair<String, String>>,
        quien: String? = caja
    ) = preguntarJson(
        """{"ordenes":[${ordenes.joinToString(",") { (r, i) -> """{"referencia_externa":"$r","importe":"$i"}""" }}]}""",
        quien
    )

    private fun preguntarJson(
        json: String,
        quien: String? = caja
    ): Pair<HttpStatus, String> {
        val spec =
            client
                .post()
                .uri("/api/srtm/caja/ordenes/vigencia")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(json)
        val result =
            (quien?.let { spec.header(HttpHeaders.AUTHORIZATION, it) } ?: spec)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to (result.responseBody ?: "")
    }
}
