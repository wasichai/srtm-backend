package srtm.visor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.impuesto.Parametros
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

// the visor de brechas (#260) end to end: a TERMINADA corrida, its rows and niveles written through core's records,
// and a stub of catastro-backend (a token, decision_brecha, the vías con arancel, the account's organization) and of
// catastro-ui's nginx in front of GeoServer (one layer served, the other refused). one organization for the class
class VisorApiTest : SrtmApiTest() {
    private fun datos(): Datos = datosDeLaClase ?: sembrar().also { datosDeLaClase = it }

    @AfterEach
    fun catastroResponde() {
        FALLA.set(false)
        RECHAZA_WMS.set(false)
    }

    private class Datos(
        val org: String,
        val corrida: String,
        val sufijo: String,
        val a: String,
        val b: String
    )

    private fun sembrar(): Datos {
        val email = "admin@$SLUG.test"
        val clave = "clave-de-la-organizacion-del-visor"
        post("/api/organizations", mapOf("name" to SLUG, "slug" to SLUG, "adminEmail" to email, "adminPassword" to clave))
        val org = bearer(email, clave)
        aplicarModelo(org)
        for (p in Parametros.predial) {
            val attributes =
                mapOf(
                    "tipo" to p.tipo,
                    "clave" to p.clave,
                    "vigencia_desde" to p.vigenciaDesde.toString(),
                    "vigencia_hasta" to p.vigenciaHasta?.toString(),
                    "valor_numerico" to p.valorNumerico,
                    "texto" to p.texto,
                    "norma" to p.norma,
                    "fuente" to p.fuente,
                    "transcribio" to p.transcribio,
                    "verifico" to p.verifico
                ).filterValues { it != null }
            post("/api/objects/parametro_tributario/records", mapOf("attributes" to attributes), org)
        }
        val sufijo = uniqueDocumento()
        val corrida =
            post(
                "/api/objects/brecha_corrida/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "anio" to 2026,
                            "estado" to "TERMINADA",
                            "version_catastro" to "valuacion-test",
                            "iniciado" to "2026-10-20T14:00:00Z",
                            "terminado" to "2026-10-20T15:00:00Z",
                            "total_predios" to 4,
                            "procesados" to 4
                        )
                ),
                org
            )["id"].asString()

        fun fila(
            orden: Int,
            categoria: String,
            atributos: Map<String, Any?>,
            lote: Map<String, Any?>? = null
        ) = post(
            "/api/objects/brecha_predio/records",
            mapOf("attributes" to mapOf("corrida" to corrida, "categoria" to categoria, "orden" to orden, "estimado" to false) + atributos) +
                (lote?.let { mapOf("geometries" to mapOf("lote_geom" to it)) } ?: emptyMap()),
            org
        )["id"].asString()
        val a =
            fila(
                0,
                "SUBVALUADO",
                mapOf(
                    "codigo_predio" to "A-$sufijo",
                    "codigo_catastro" to "A-$sufijo",
                    "sector" to "01",
                    "manzana" to "A",
                    "lote" to "1",
                    "confianza" to "CONFIABLE",
                    "delta_autoavaluo" to 50000,
                    "delta_impuesto" to 300
                ),
                cuadrado(-75.700)
            )
        val b =
            fila(
                1,
                "SUBVALUADO",
                mapOf(
                    "codigo_predio" to "B-$sufijo",
                    "codigo_catastro" to "B-$sufijo",
                    "sector" to "02",
                    "manzana" to "B",
                    "lote" to "2",
                    "confianza" to "DUDOSO",
                    "delta_autoavaluo" to 20000,
                    "delta_impuesto" to 100
                ),
                cuadrado(-75.701)
            )
        fila(
            2,
            "OMISO",
            mapOf(
                "codigo_catastro" to "O-$sufijo",
                "sector" to "01",
                "manzana" to "C",
                "lote" to "3",
                "confianza" to "DUDOSO",
                "delta_autoavaluo" to 40000,
                "delta_impuesto" to 200,
                "estimado" to true
            )
        )
        fila(3, "SIN_CATASTRO", mapOf("codigo_predio" to "D-$sufijo", "sector" to "01", "confianza" to "DUDOSO"))
        for ((piso, rentas, catastro, estado) in listOf(Nivel(2, 80, 95, "PAR"), Nivel(1, 100, 100, "PAR"), Nivel(null, 10, null, "AMBIGUO"))) {
            post(
                "/api/objects/brecha_nivel/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "corrida" to corrida,
                            "brecha_predio" to a,
                            "piso" to piso,
                            "area_rentas" to rentas,
                            "area_catastro" to catastro,
                            "estado" to estado
                        )
                ),
                org
            )
        }
        decisionesDelStub =
            listOf("""{"id":"r1","attributes":{"clave":"A-$sufijo|2026","codigo_municipal":"A-$sufijo","anio":2026,"estado":"A_INSPECCIONAR"}}""")
        return Datos(org, corrida, sufijo, a, b)
    }

    private data class Nivel(
        val piso: Int?,
        val rentas: Int?,
        val catastro: Int?,
        val estado: String
    )

    private fun leer(
        ruta: String,
        token: String = datos().org
    ): JsonNode = tree(send("GET", "/api/srtm/visor$ruta", null, HttpStatus.OK, token))

    private fun estado(
        ruta: String,
        esperado: HttpStatus,
        token: String = datos().org
    ) = send("GET", "/api/srtm/visor$ruta", null, esperado, token)

    private fun dec(n: JsonNode) = n.decimalValue()

    // a row's codigo_catastro, else its codigo_predio
    private fun codigos(n: JsonNode) =
        n["content"].values().map { f -> listOf("codigo_catastro", "codigo_predio").firstNotNullOfOrNull { f[it]?.takeUnless { c -> c.isNull }?.asString() } }

    @Test
    fun `the panel adds up the latest TERMINADA corrida as catastro's panel does`() {
        val d = datos()
        val p = leer("/panel?anio=2026")
        assertEquals(d.corrida, p["corrida"]["id"].asString())
        assertEquals("valuacion-test", p["corrida"]["version_catastro"].asString())
        val k = p["kpis"]
        assertEquals(4, k["fichas"].asInt())
        assertEquals(0, BigDecimal(600).compareTo(dec(k["delta_impuesto"])))
        assertEquals(0, BigDecimal(300).compareTo(dec(k["delta_impuesto_confiable"])))
        assertEquals(0, BigDecimal(200).compareTo(dec(k["omisos_estimado"])))
        // 5 UIT of 2026 is between 20 000 and 40 000: A and the omiso
        assertEquals(2, k["supera_5_uit"].asInt())
        // B and the omiso: A is decided, the SIN_CATASTRO row has no ficha
        assertEquals(2, k["pendientes"].asInt())
        assertEquals(1, k["sin_ficha"].asInt())
        assertEquals(2, k["con_geometria"].asInt())
        assertEquals(2, p["por_categoria"].single { it["clave"].asString() == "SUBVALUADO" }["fichas"].asInt())
        assertEquals(1, p["por_estado"].single { it["clave"].asString() == "A_INSPECCIONAR" }["fichas"].asInt())
        assertEquals(listOf("01", "02"), p["top_sectores"].values().map { it["sector"].asString() })
        assertEquals(d.corrida, leer("/panel?anio=2026&corrida=${d.corrida}")["corrida"]["id"].asString())
        estado("/panel?anio=2031", HttpStatus.NOT_FOUND)
    }

    @Test
    fun `the predios page, filter and sort as catastro's fichas`() {
        val d = datos()
        val todos = leer("/predios?anio=2026")
        assertEquals(4, todos["totalElements"].asInt())
        assertEquals(listOf("A-${d.sufijo}", "O-${d.sufijo}", "B-${d.sufijo}", "D-${d.sufijo}"), codigos(todos))
        val a = todos["content"][0]
        assertEquals("A_INSPECCIONAR", a["estado"].asString())
        assertEquals(true, a["supera_5_uit"].asBoolean())
        assertEquals(true, a["con_geometria"].asBoolean())
        assertEquals(d.a, a["id"].asString())
        assertEquals("PENDIENTE", todos["content"][2]["estado"].asString())
        val segunda = leer("/predios?anio=2026&page=1&size=1&orden=delta_asc")
        assertEquals(listOf("O-${d.sufijo}"), codigos(segunda))
        assertEquals(4, segunda["totalElements"].asInt())
        assertEquals(listOf("A-${d.sufijo}", "B-${d.sufijo}", "O-${d.sufijo}", "D-${d.sufijo}"), codigos(leer("/predios?anio=2026&orden=codigo")))
        assertEquals(listOf("A-${d.sufijo}", "O-${d.sufijo}"), codigos(leer("/predios?anio=2026&categoria=SUBVALUADO,OMISO&min_delta=150")))
        assertEquals(listOf("O-${d.sufijo}", "B-${d.sufijo}", "D-${d.sufijo}"), codigos(leer("/predios?anio=2026&confianza=DUDOSO&estado=PENDIENTE")))
        assertEquals(listOf("A-${d.sufijo}", "O-${d.sufijo}"), codigos(leer("/predios?anio=2026&supera_5_uit=true")))
        assertEquals(listOf("B-${d.sufijo}"), codigos(leer("/predios?anio=2026&sector=2")))
        for (malo in listOf("categoria=TODAS", "estado=x", "orden=nada", "supera_5_uit=si", "min_delta=-1", "size=0", "page=-1", "otro=1", "corrida=x")) {
            estado("/predios?anio=2026&$malo", HttpStatus.UNPROCESSABLE_CONTENT)
        }
        estado("/predios", HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `the niveles of a predio by piso, with their delta`() {
        val d = datos()
        val n = tree(send("GET", "/api/srtm/visor/predios/${d.a}/niveles", null, HttpStatus.OK, d.org))
        assertEquals(listOf("1", "2", "null"), n.values().map { it["piso"].toString() })
        assertEquals(0, BigDecimal(15).compareTo(dec(n[1]["delta"])))
        assertTrue(n[2]["delta"].isNull)
        assertEquals("AMBIGUO", n[2]["estado"].asString())
        assertEquals(0, tree(send("GET", "/api/srtm/visor/predios/${d.b}/niveles", null, HttpStatus.OK, d.org)).size())
        send("GET", "/api/srtm/visor/predios/${UUID.randomUUID()}/niveles", null, HttpStatus.NOT_FOUND, d.org)
    }

    @Test
    fun `the lotes are the rows that match with a lote, with their estado`() {
        val d = datos()
        val fc = leer("/lotes?anio=2026&orden=codigo")
        assertEquals("FeatureCollection", fc["type"].asString())
        val f = fc["features"].values().associateBy { it["id"].asString() }
        assertEquals(setOf(d.a, d.b), f.keys)
        val a = f.getValue(d.a)
        assertEquals("Polygon", a["geometry"]["type"].asString())
        assertEquals(-75.700, a["geometry"]["coordinates"][0][0][0].asDouble(), 1e-6)
        assertEquals("A_INSPECCIONAR", a["properties"]["estado"].asString())
        assertEquals("CONFIABLE", a["properties"]["confianza"].asString())
        assertEquals(true, a["properties"]["supera_5_uit"].asBoolean())
        assertEquals(0, BigDecimal(300).compareTo(dec(a["properties"]["delta_impuesto"])))
        assertEquals(listOf(d.b), leer("/lotes?anio=2026&estado=PENDIENTE")["features"].values().map { it["id"].asString() })
    }

    @Test
    fun `the csv takes the filters and every row`() {
        val d = datos()
        val r =
            client
                .get()
                .uri("/api/srtm/visor/predios.csv?anio=2026&categoria=SUBVALUADO&orden=codigo")
                .header(HttpHeaders.AUTHORIZATION, d.org)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
        assertEquals("visor-brechas-2026.csv", r.responseHeaders.contentDisposition.filename)
        val lineas = String(r.responseBody!!, Charsets.UTF_8).removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertTrue(lineas[0].startsWith("codigo_catastro;codigo_predio;sector"), lineas[0])
        assertEquals(3, lineas.size)
        assertTrue(lineas[1].startsWith("A-${d.sufijo};"), lineas[1])
        assertTrue(lineas[1].contains(";A_INSPECCIONAR;"), lineas[1])
    }

    @Test
    fun `the vias and the layers come from catastro as its service account`() {
        datos()
        val vias = leer("/vias?anio=2026")
        assertEquals("FeatureCollection", vias["type"].asString())
        assertEquals("V1", vias["features"][0]["properties"]["cod_via_eje"].asString())
        assertTrue(PEDIDOS.any { it == "vias anio=2026 Bearer t-visor" }, PEDIDOS.toString())
        // catastro serves predio_catastral, and refuses tramo_via: only the first is offered
        val capas = leer("/capas")
        assertEquals(listOf(PREDIOS), capas.values().map { it["layerName"].asString() })
        assertEquals("Predios catastrales", capas[0]["label"].asString())
    }

    @Test
    fun `a GetMap is relayed and its image streamed with its type`() {
        val d = datos()
        val r =
            client
                .get()
                .uri("/api/srtm/visor/wms?${getMap(PREDIOS)}&exceptions=application/vnd.ogc.se_xml")
                .header(HttpHeaders.AUTHORIZATION, d.org)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
        assertEquals("image/png", r.responseHeaders.contentType.toString())
        assertTrue(
            r.responseHeaders.cacheControl
                .orEmpty()
                .contains("max-age=60"),
            r.responseHeaders.cacheControl
        )
        assertArrayEquals(PNG, r.responseBody)
        val pedido = PEDIDOS.last { it.startsWith("wms ") }
        assertTrue(pedido.contains("layers=$PREDIOS") && pedido.endsWith("Bearer t-visor"), pedido)
        assertTrue(!pedido.contains("exceptions"), pedido)
    }

    @Test
    fun `a GetMap srtm cannot serve never reaches catastro`() {
        datos()
        val antes = PEDIDOS.count { it.startsWith("wms ") }
        estado("/wms?${getMap("predio_catastral__949ce38d__lote_geom")}", HttpStatus.FORBIDDEN)
        estado("/wms?${getMap("brecha_ficha__7c45fb26__lote_geom")}", HttpStatus.FORBIDDEN)
        estado("/wms?${getMap(PREDIOS).replace("format=image/png", "format=text/html")}", HttpStatus.UNPROCESSABLE_CONTENT)
        estado("/wms?${getMap(PREDIOS).replace("request=GetMap", "request=GetFeatureInfo")}", HttpStatus.UNPROCESSABLE_CONTENT)
        estado("/wms?${getMap(PREDIOS)}&cql_filter=INCLUDE", HttpStatus.UNPROCESSABLE_CONTENT)
        estado("/wms?${getMap(PREDIOS).replace("width=256", "width=4096")}", HttpStatus.UNPROCESSABLE_CONTENT)
        assertEquals(antes, PEDIDOS.count { it.startsWith("wms ") })
    }

    @Test
    fun `catastro refusing is a 403, catastro failing a 502, and the panel goes on`() {
        datos()
        RECHAZA_WMS.set(true)
        estado("/wms?${getMap(PREDIOS)}", HttpStatus.FORBIDDEN)
        RECHAZA_WMS.set(false)
        FALLA.set(true)
        estado("/vias?anio=2026", HttpStatus.BAD_GATEWAY)
        estado("/wms?${getMap(PREDIOS)}", HttpStatus.BAD_GATEWAY)
        val p = leer("/panel?anio=2026")
        assertEquals(4, p["kpis"]["fichas"].asInt())
        assertTrue(p["kpis"]["pendientes"].isNull, p["kpis"].toString())
        assertTrue(leer("/lotes?anio=2026")["features"].all { it["properties"]["estado"].isNull })
        assertTrue(p["avisos"].any { it.asString().startsWith("Sin las decisiones de catastro") }, p["avisos"].toString())
    }

    @Test
    fun `an organization without catastro gets a 409 from what catastro serves`() {
        val otra = otraOrganizacion()
        for (ruta in listOf("/vias?anio=2026", "/capas", "/wms?${getMap(PREDIOS)}")) estado(ruta, HttpStatus.CONFLICT, otra)
    }

    @Test
    fun `the visor needs to read the corrida and its rows`() {
        datos()
        val sinFilas = funcionario(listOf(permiso("brecha_corrida", "READ")))
        for (ruta in listOf("/panel?anio=2026", "/predios?anio=2026", "/lotes?anio=2026", "/vias?anio=2026", "/capas", "/wms?${getMap(PREDIOS)}")) {
            estado(ruta, HttpStatus.FORBIDDEN, sinFilas)
        }
        send("GET", "/api/srtm/visor/predios/${UUID.randomUUID()}/niveles", null, HttpStatus.FORBIDDEN, sinFilas)
    }

    private fun getMap(capa: String) =
        "service=WMS&version=1.1.1&request=GetMap&layers=$capa&styles=&srs=EPSG:3857" +
            "&bbox=-8430000,-1240000,-8420000,-1230000&width=256&height=256&format=image/png&transparent=true"

    companion object {
        private val SLUG = "visor-${UUID.randomUUID().toString().take(8)}"
        private var datosDeLaClase: Datos? = null
        private const val ORGANIZACION_CATASTRAL = "7c45fb26-0000-4000-8000-000000000001"
        private const val PREDIOS = "predio_catastral__7c45fb26__lote_geom"
        private val PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13)
        private val PEDIDOS = CopyOnWriteArrayList<String>()

        @Volatile
        private var decisionesDelStub: List<String> = emptyList()
        private val FALLA = AtomicBoolean(false)
        private val RECHAZA_WMS = AtomicBoolean(false)

        private const val VIAS =
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[-75.7,-14.0],[-75.71,-14.01]]},""" +
                """"properties":{"cod_via_eje":"V1","tip_via":"AV","nom_via":"PRUEBA","cuadra":"1","lado":"I","id_aranc":"A1","asignacion":"EJE","anio":2026,"valor_m2":120.5}}]}"""

        private fun cuadrado(oeste: Double) =
            mapOf(
                "type" to "Polygon",
                "coordinates" to
                    listOf(
                        listOf(
                            listOf(oeste, -14.0),
                            listOf(oeste + 0.0005, -14.0),
                            listOf(oeste + 0.0005, -13.9995),
                            listOf(oeste, -13.9995),
                            listOf(oeste, -14.0)
                        )
                    )
            )

        private fun autorizado(ex: HttpExchange): Boolean {
            if (ex.requestHeaders.getFirst("Authorization") == "Bearer t-visor") return true
            responder(ex, 401, "{}".toByteArray(), "application/json")
            return false
        }

        // catastro-backend and catastro-ui's nginx: a token, decision_brecha, the vías, the account's organization and
        // the WMS (a 500 everywhere while FALLA; the WMS a 403 while RECHAZA_WMS, and for any layer but PREDIOS)
        private val CATASTRO =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext(
                    "/api/auth/token"
                ) { ex -> responder(ex, 200, """{"token":"t-visor","expiresAt":"2999-01-01T00:00:00Z"}""".toByteArray(), "application/json") }
                createContext("/api/objects/decision_brecha/records") { ex ->
                    if (!autorizado(ex)) return@createContext
                    if (FALLA.get()) return@createContext responder(ex, 500, "{}".toByteArray(), "application/json")
                    val todas = decisionesDelStub
                    val cuerpo = """{"content":[${todas.joinToString(",")}],"page":0,"size":200,"totalElements":${todas.size},"totalPages":1}"""
                    responder(ex, 200, cuerpo.toByteArray(), "application/json")
                }
                createContext("/api/catastro/vias/aranceles") { ex ->
                    PEDIDOS += "vias ${ex.requestURI.query} ${ex.requestHeaders.getFirst("Authorization")}"
                    if (!autorizado(ex)) return@createContext
                    if (FALLA.get()) return@createContext responder(ex, 500, "{}".toByteArray(), "application/json")
                    responder(ex, 200, VIAS.toByteArray(), "application/json")
                }
                createContext("/api/organizations/current") { ex ->
                    if (!autorizado(ex)) return@createContext
                    responder(ex, 200, """{"id":"$ORGANIZACION_CATASTRAL","name":"Catastro de prueba"}""".toByteArray(), "application/json")
                }
                createContext("/geoserver/catastro/wms") { ex ->
                    PEDIDOS += "wms ${ex.requestURI.query} ${ex.requestHeaders.getFirst("Authorization")}"
                    if (!autorizado(ex)) return@createContext
                    val capa = Regex("layers=([^&]*)").find(ex.requestURI.query.orEmpty())?.groupValues?.get(1)
                    when {
                        FALLA.get() -> responder(ex, 500, "{}".toByteArray(), "application/json")
                        RECHAZA_WMS.get() || capa != PREDIOS ->
                            responder(
                                ex,
                                403,
                                """{"message":"La capa no se sirve por WMS a este usuario: tiene un alcance de lectura"}""".toByteArray(),
                                "application/json"
                            )
                        else -> responder(ex, 200, PNG, "image/png")
                    }
                }
                start()
            }

        private fun responder(
            ex: HttpExchange,
            estado: Int,
            cuerpo: ByteArray,
            tipo: String
        ) {
            ex.responseHeaders.add("Content-Type", tipo)
            ex.sendResponseHeaders(estado, cuerpo.size.toLong())
            ex.responseBody.use { it.write(cuerpo) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun catastro(registro: DynamicPropertyRegistry) {
            registro.add("srtm.catastro.enabled") { true }
            registro.add("srtm.catastro.url") { "http://127.0.0.1:${CATASTRO.address.port}" }
            registro.add("srtm.catastro.client-id") { "srtm-visor" }
            registro.add("srtm.catastro.client-secret") { "secreto-de-prueba" }
            registro.add("srtm.catastro.organizacion") { SLUG }
            registro.add("srtm.catastro.wms-url") { "http://127.0.0.1:${CATASTRO.address.port}/geoserver/catastro/wms" }
            registro.add("srtm.reportes.cache-decisiones") { "0s" }
            registro.add("srtm.reportes.cache-fallo-decisiones") { "0s" }
        }

        @JvmStatic
        @AfterAll
        fun parar() = CATASTRO.stop(0)
    }
}
