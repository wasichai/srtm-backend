package srtm.arbitrios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

// #158: a write of a declaración that changes what its predio's arbitrios are charged on redetermines the whole year:
// the months that come out different are annulled and written again as the next version, the others are not touched.
// the write answers what it did in the Srtm-Arbitrios header, and never fails for it. every figure is FICTITIOUS
class RedeterminacionApiTest : ConArbitriosApiTest() {
    // the escenario's first servicio charged per m² construido (8.50 × 100 m² = 850 a month), its second as is (4.25),
    // and its year determined: 24 cuotas
    private fun porArea(e: Escenario): String {
        e.parametro(Llaves.BASE_ARBITRIO, e.codigos[0], texto = "AREA_CONSTRUIDA_M2")
        val dj = declaracionDe(e)
        piso(dj, 1, 100)
        assertEquals(24, tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)).size())
        assertEquals(12 * 850.0 + 12 * 4.25, matriz(e)["total"].asDouble())
        return dj
    }

    @Test
    fun `a nivel added redetermines the months it changes, as their next version, and annuls the old ones`() {
        val e = Escenario()
        val dj = porArea(e)
        val antes = vigentes(e)

        val (_, aviso) = escribir("POST", "/api/srtm/declaraciones/$dj/niveles", NIVEL + mapOf("numero_piso" to 2, "area_construida" to 20), HttpStatus.CREATED)

        assertEquals(12, aviso!!["redeterminadas"].asInt(), aviso.toString())
        assertEquals(12, aviso["anuladas"].asInt())
        assertTrue(aviso["error"].isNull, aviso.toString())
        assertEquals(12 * 1020.0 + 12 * 4.25, matriz(e)["total"].asDouble())
        val ahora = vigentes(e)
        val porArea = ahora.filter { it["servicio"].asString() == e.servicios[0] }
        assertEquals(List(12) { 2 }, porArea.map { versionDe(it["clave"].asString()) })
        assertEquals(List(12) { 1020.0 }, porArea.map { it["monto"].asDouble() })
        // the other servicio's cuotas are the same ones
        val otras = { cs: List<JsonNode> -> cs.filter { it["servicio"].asString() == e.servicios[1] }.map { it["id"].asString() }.toSet() }
        assertEquals(otras(antes), otras(ahora))

        val anulaciones = tree(send("GET", "/api/objects/$ANULACION_CUOTA_ARBITRIO/records?predio=${e.predio}", null, HttpStatus.OK))["content"]
        assertEquals(12, anulaciones.size())
        // the escenario's declaración came in through core's api, as an import: it has no número
        val una = anulaciones[0]["attributes"]
        assertEquals("REDETERMINACION", una["motivo"].asString())
        assertEquals("Redeterminación por cambio de la DJ del padrón", una["observacion"].asString())
        assertEquals("Redeterminación por cambio de la DJ del padrón", porArea[0]["observacion"].asString())
    }

    // the rectificatoria takes the whole year of the one it replaces (#147), with the same data and its lists
    @Test
    fun `a rectificatoria with the same data changes no cuota, and a nivel of it redetermines with its número`() {
        val e = Escenario()
        val dj = porArea(e)
        val antes = vigentes(e).map { it["id"].asString() }.toSet()

        val (cuerpo, aviso) = escribir("POST", "/api/srtm/declaraciones/$dj/rectificar", null, HttpStatus.CREATED)

        assertEquals(0, aviso!!["redeterminadas"].asInt(), aviso.toString())
        assertEquals(antes, vigentes(e).map { it["id"].asString() }.toSet())
        val rectificatoria = tree(cuerpo)["declaracion"]
        piso(rectificatoria["id"].asString(), 2, 20)
        val numero = rectificatoria["numero_declaracion"].asInt()
        val nuevas = vigentes(e).filter { it["id"].asString() !in antes }
        assertEquals(12, nuevas.size)
        assertEquals(setOf("Redeterminación por cambio de la DJ n° $numero"), nuevas.map { it["observacion"].asString() }.toSet())
    }

    @Test
    fun `a frontis principal changed redetermines a servicio charged per ml`() {
        val e = Escenario()
        e.parametro(Llaves.BASE_ARBITRIO, e.codigos[0], texto = "FRONTIS_ML")
        val dj = declaracionDe(e)
        put("/api/srtm/declaraciones/$dj", fields(declaracion(dj)) + ("frontis_principal" to 10), token)
        assertEquals(24, tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)).size())

        val (_, aviso) = escribir("PUT", "/api/srtm/declaraciones/$dj", fields(declaracion(dj)) + ("frontis_principal" to 12), HttpStatus.OK)

        assertEquals(12, aviso!!["redeterminadas"].asInt(), aviso.toString())
        assertEquals(12 * 102.0 + 12 * 4.25, matriz(e)["total"].asDouble())
    }

    @Test
    fun `a change that leaves every cuota the same writes nothing`() {
        val e = Escenario()
        val dj = porArea(e)
        val antes = vigentes(e).map { it["id"].asString() }.toSet()

        // the habitantes are read by no servicio of the escenario
        val (_, aviso) = escribir("PUT", "/api/srtm/declaraciones/$dj", fields(declaracion(dj)) + ("numero_habitantes" to 4), HttpStatus.OK)

        assertEquals(0, aviso!!["redeterminadas"].asInt(), aviso.toString())
        assertEquals(0, aviso["anuladas"].asInt())
        assertEquals(antes, vigentes(e).map { it["id"].asString() }.toSet())
        assertEquals(24, pagina("anio=${e.anio}&predio=${e.predio}")["totalElements"].asInt())
    }

    @Test
    fun `a predio without cuotas of the year is not determined by a write of its declaración`() {
        val e = Escenario()
        e.parametro(Llaves.BASE_ARBITRIO, e.codigos[0], texto = "AREA_CONSTRUIDA_M2")
        val (_, aviso) = escribir("POST", "/api/srtm/declaraciones/${declaracionDe(e)}/niveles", NIVEL, HttpStatus.CREATED)
        assertNull(aviso)
        assertEquals(0, pagina("anio=${e.anio}&predio=${e.predio}")["totalElements"].asInt())
    }

    // annulled in another year than the escenario's, it covers no month of it: no titular is left
    @Test
    fun `an annulled declaración annuls the cuotas it was charged`() {
        val e = Escenario()
        val dj = porArea(e)

        val (_, aviso) = escribir("POST", "/api/srtm/declaraciones/$dj/anular", mapOf("motivo_anulacion" to "Vendido"), HttpStatus.OK)

        assertEquals(0, aviso!!["redeterminadas"].asInt(), aviso.toString())
        assertEquals(24, aviso["anuladas"].asInt())
        assertEquals(0.0, matriz(e)["total"].asDouble())
        assertTrue(vigentes(e).isEmpty())
    }

    @Test
    fun `the write of the declaración stands when its arbitrios cannot be redetermined`() {
        val e = Escenario()
        val dj = porArea(e)
        val nivel = tree(send("GET", "/api/srtm/declaraciones/$dj/niveles", null, HttpStatus.OK))[0]["id"].asString()
        val antes = vigentes(e).map { it["id"].asString() }.toSet()

        // without niveles there is no área construida to charge
        val (_, aviso) = escribir("DELETE", "/api/srtm/niveles/$nivel", null, HttpStatus.NO_CONTENT)

        assertTrue(aviso!!["error"].asString().contains("Área construida"), aviso.toString())
        assertEquals(0, aviso["redeterminadas"].asInt())
        assertEquals(0, tree(send("GET", "/api/srtm/declaraciones/$dj/niveles", null, HttpStatus.OK)).size())
        assertEquals(antes, vigentes(e).map { it["id"].asString() }.toSet())
    }

    @Test
    fun `who writes declaraciones without arbitrios permissions writes it, and is told why the cuotas did not change`() {
        val e = Escenario()
        val dj = porArea(e)
        val registrador =
            funcionario(
                listOf(permiso(null, "READ")) +
                    listOf("declaracion_predial", "nivel_construccion").flatMap { o -> listOf("CREATE", "UPDATE", "DELETE").map { permiso(o, it) } }
            )

        val (_, aviso) =
            escribir("POST", "/api/srtm/declaraciones/$dj/niveles", NIVEL + mapOf("numero_piso" to 2, "area_construida" to 20), HttpStatus.CREATED, registrador)

        assertTrue(aviso!!["error"].asString().contains(CUOTA_ARBITRIO), aviso.toString())
        assertEquals(12 * 850.0 + 12 * 4.25, matriz(e)["total"].asDouble())
        assertEquals("120", declaracion(dj)["area_construida"].decimalValue().stripTrailingZeros().toPlainString())

        // the arbitrios area redetermines it by hand; who may only read gets a 403
        val redeterminacion = "/api/srtm/predios/${e.predio}/arbitrios/redeterminacion"
        send("POST", redeterminacion, pedido(e.anio), HttpStatus.FORBIDDEN, funcionario(listOf(permiso(null, "READ"))))
        val hecha = tree(send("POST", redeterminacion, pedido(e.anio), HttpStatus.OK))
        assertEquals(12, hecha["redeterminadas"].asInt(), hecha.toString())
        assertEquals(12, hecha["anuladas"].asInt())
        assertEquals(12 * 1020.0 + 12 * 4.25, matriz(e)["total"].asDouble())
        assertEquals(0, tree(send("POST", redeterminacion, pedido(e.anio), HttpStatus.OK))["redeterminadas"].asInt())
    }

    @Test
    fun `a redeterminación by hand of a year that cannot be determined is a 422 that names what is missing`() {
        val e = Escenario()
        val dj = porArea(e)
        send(
            "DELETE",
            "/api/srtm/niveles/${tree(send("GET", "/api/srtm/declaraciones/$dj/niveles", null, HttpStatus.OK))[0]["id"].asString()}",
            null,
            HttpStatus.NO_CONTENT
        )
        val problema = tree(send("POST", "/api/srtm/predios/${e.predio}/arbitrios/redeterminacion", pedido(e.anio), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(problema["faltan"][0].asString().contains("Área construida"), problema.toString())
    }

    // the escenario's declaración
    private fun declaracionDe(e: Escenario): String =
        tree(send("GET", "/api/objects/declaracion_predial/records?predio=${e.predio}", null, HttpStatus.OK))["content"].single()["id"].asString()

    private fun declaracion(id: String): JsonNode = tree(send("GET", "/api/srtm/declaraciones/$id", null, HttpStatus.OK))["declaracion"]

    private fun piso(
        dj: String,
        numero: Int,
        area: Int
    ) = post("/api/srtm/declaraciones/$dj/niveles", NIVEL + mapOf("numero_piso" to numero, "area_construida" to area))

    // the vigente cuotas of the escenario's year: not annulled, the latest version of each
    private fun vigentes(e: Escenario): List<JsonNode> {
        val anuladas =
            lista(tree(send("GET", "/api/objects/$ANULACION_CUOTA_ARBITRIO/records?predio=${e.predio}&size=200", null, HttpStatus.OK))["content"]) {
                it["attributes"]["cuota"].asString()
            }.toSet()
        return lista(pagina("anio=${e.anio}&predio=${e.predio}&size=200")["content"]) { it }
            .filter { it["id"].asString() !in anuladas }
            .groupBy { listOf(it["servicio"].asString(), it["periodo"].asInt()) }
            .values
            .map { cs -> cs.maxBy { versionDe(it["clave"].asString()) ?: 0 } }
    }

    // the body of a write that must answer `status`, and its Srtm-Arbitrios header (null without one)
    private fun escribir(
        method: String,
        path: String,
        body: Any?,
        status: HttpStatus,
        token: String = this.token
    ): Pair<String, JsonNode?> {
        val spec =
            when (method) {
                "DELETE" -> client.delete().uri(path)
                "PUT" -> client.put().uri(path).bodyValue(body!!)
                else -> client.post().uri(path).let { post -> body?.let { post.bodyValue(it) } ?: post }
            }
        val result =
            spec
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        val cuerpo = result.responseBody ?: ""
        assertEquals(status.value(), result.status.value(), "$method $path: $cuerpo")
        return cuerpo to result.responseHeaders.getFirst(AvisoDeArbitrios.ENCABEZADO)?.let(::tree)
    }

    private companion object {
        val NIVEL =
            mapOf(
                "tipo_nivel" to "PISO",
                "numero_piso" to 1,
                "anio_construccion" to 2016,
                "mes_construccion" to 1,
                "material" to "CONCRETO",
                "estado_conservacion" to "BUENO",
                "area_construida" to 100,
                "muros_columnas" to "C",
                "techos" to "D",
                "puertas_ventanas" to "E"
            )
    }
}
