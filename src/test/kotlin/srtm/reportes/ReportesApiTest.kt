package srtm.reportes

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.impuesto.Parametros
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// the reportes de brechas (#248) end to end: a TERMINADA corrida and its rows written through core's records (no
// corrida is run: BrechaApiTest covers that), the padrón's contribuyentes and DJs through the portal's api, and a stub of
// catastro-backend that serves decision_brecha's records, or fails. one organization for the class, made once
class ReportesApiTest : SrtmApiTest() {
    private fun datos(): Datos = datosDeLaClase ?: sembrar().also { datosDeLaClase = it }

    @AfterEach
    fun catastroResponde() = FALLA.set(false)

    private class Datos(
        val org: String,
        val corrida: String,
        val sufijo: String,
        val c1: String,
        val c2: String,
        val codigoC1: String
    )

    private fun sembrar(): Datos {
        val email = "admin@$SLUG.test"
        val clave = "clave-de-la-organizacion-de-reportes"
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
        val enums = modelo()["enums"]
        post(
            "/api/objects/uso_predio/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "codigo" to "010101",
                        "clase" to enums["clase_uso"][0].asString(),
                        "sub_clase" to enums["sub_clase_uso"][0].asString(),
                        "uso" to "CASA HABITACIÓN"
                    )
            ),
            org
        )
        val sufijo = uniqueDocumento()
        val uno = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)
        val c1 = uno["id"].asString()
        val c2 = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)["id"].asString()
        val predios =
            listOf("A", "B", "C", "D", "E").associateWith { p ->
                post(
                    "/api/srtm/predios",
                    mapOf("codigo" to "$p-$sufijo", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to "01"),
                    org
                )["id"].asString()
            }
        for ((p, contribuyente) in listOf("A" to c1, "B" to c1, "E" to c1, "C" to c2, "D" to c2)) {
            post(
                "/api/srtm/declaraciones",
                mapOf("contribuyente" to contribuyente, "predio" to predios.getValue(p), "anio" to 2026, "secuencia_uso" to "1"),
                org
            )
        }
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
                            "total_predios" to 6,
                            "procesados" to 6,
                            "mensaje" to "Δ arbitrios no calculado: sin ordenanza"
                        )
                ),
                org
            )["id"].asString()

        fun fila(
            orden: Int,
            categoria: String,
            atributos: Map<String, Any?>
        ) = post(
            "/api/objects/brecha_predio/records",
            mapOf("attributes" to mapOf("corrida" to corrida, "categoria" to categoria, "orden" to orden, "estimado" to false) + atributos),
            org
        )

        fun predio(p: String) = mapOf("predio" to predios.getValue(p), "codigo_predio" to "$p-$sufijo", "codigo_catastro" to "$p-$sufijo")
        fila(
            0,
            "SUBVALUADO",
            predio("A") +
                mapOf(
                    "sector" to "01",
                    "manzana" to "A",
                    "lote" to "1",
                    "uso" to "010101",
                    "confianza" to "CONFIABLE",
                    "autoavaluo_declarado" to 100000,
                    "autoavaluo_catastro" to 150000,
                    "delta_autoavaluo" to 50000,
                    "impuesto_declarado" to 330,
                    "impuesto_catastro" to 630,
                    "delta_impuesto" to 300,
                    "area_terreno_declarada" to 200,
                    "area_terreno_catastro" to 240,
                    "area_construida_declarada" to 100,
                    "area_construida_catastro" to 100,
                    "niveles_comparados" to 2,
                    "niveles_catastro_mayor" to 1,
                    "delta_area_niveles" to 12.5
                )
        )
        fila(
            1,
            "SUBVALUADO",
            predio("B") +
                mapOf(
                    "sector" to "02",
                    "manzana" to "B",
                    "lote" to "2",
                    "uso" to "020101",
                    "confianza" to "DUDOSO",
                    "delta_autoavaluo" to 30000,
                    "delta_impuesto" to 100,
                    "area_terreno_declarada" to 150,
                    "area_terreno_catastro" to 150
                )
        )
        fila(
            2,
            "SOBREVALUADO",
            predio("C") +
                mapOf("sector" to "01", "manzana" to "A", "lote" to "3", "confianza" to "CONFIABLE", "delta_autoavaluo" to -20000, "delta_impuesto" to -50)
        )
        fila(
            3,
            "OMISO",
            mapOf(
                "codigo_catastro" to "O-$sufijo",
                "sector" to "01",
                "manzana" to "C",
                "lote" to "4",
                "confianza" to "DUDOSO",
                "delta_autoavaluo" to 40000,
                "delta_impuesto" to 200,
                "estimado" to true
            )
        )
        fila(4, "SIN_CATASTRO", mapOf("predio" to predios.getValue("D"), "codigo_predio" to "D-$sufijo", "sector" to "01", "confianza" to "DUDOSO"))
        fila(
            5,
            "SUBVALUADO",
            predio("E") +
                mapOf("sector" to "03", "manzana" to "E", "lote" to "5", "confianza" to "CONFIABLE", "delta_autoavaluo" to 100000, "delta_impuesto" to 900)
        )
        val codigoC1 = uno["codigo"].asString()
        decisionesDelStub =
            listOf(
                """{"id":"r1","attributes":{"clave":"A-$sufijo|2026","codigo_municipal":"A-$sufijo","anio":2026,"estado":"A_INSPECCIONAR"}}""",
                """{"id":"r2","attributes":{"clave":"O-$sufijo|2026","codigo_municipal":"O-$sufijo","anio":2026,"estado":"A_FISCALIZAR"}}"""
            )
        return Datos(org, corrida, sufijo, c1, c2, codigoC1)
    }

    private fun leer(
        ruta: String,
        token: String = datos().org
    ): JsonNode = tree(send("GET", "/api/srtm/reportes$ruta", null, HttpStatus.OK, token))

    private fun lotes(n: JsonNode) = n["items"].map { it["lote"]?.takeUnless { l -> l.isNull }?.asString() }

    private fun dec(n: JsonNode) = n.decimalValue()

    @Test
    fun `the resumen adds up the latest TERMINADA corrida with catastro's decisions`() {
        val d = datos()
        val r = leer("/resumen?anio=2026")
        assertEquals(d.corrida, r["corrida"]["id"].asString())
        assertEquals("valuacion-test", r["corrida"]["versionCatastro"].asString())
        assertEquals(3, r["kpis"]["subvaluados"].asInt())
        assertEquals(1, r["kpis"]["omisos"].asInt())
        val sub = r["porCategoria"].single { it["categoria"].asString() == "SUBVALUADO" }
        assertEquals(3, sub["predios"].asInt())
        assertEquals(0, BigDecimal(1300).compareTo(dec(sub["deltaImpuesto"])))
        assertEquals(listOf("CONFIABLE", "DUDOSO", "SIN_LEVANTAR"), r["porConfianza"].map { it["confianza"].asString() })
        assertEquals(listOf("01", "02", "03"), r["porSector"].map { it["sector"].asString() })
        val casa = r["porUso"].single { it["uso"].takeUnless { u -> u.isNull }?.asString() == "010101" }
        assertEquals("CASA HABITACIÓN", casa["descripcion"].asString())
        assertEquals("03", r["porManzana"][0]["sector"].asString())
        assertEquals(r["histogramaDeltaAutoavaluo"].sumOf { it["predios"].asInt() }, 5)
        assertEquals(1, r["areas"]["terrenoConDiferencia"].asInt())
        assertEquals(0, BigDecimal(40).compareTo(dec(r["areas"]["deltaM2Terreno"])))
        assertEquals(2, r["areas"]["nivelesComparados"].asInt())
        assertEquals(1, r["areas"]["prediosConNivelMayor"].asInt())
        assertEquals(1, r["decisiones"]["A_INSPECCIONAR"].asInt())
        assertEquals(1, r["decisiones"]["A_FISCALIZAR"].asInt())
        assertEquals(0, r["decisiones"]["PENDIENTE"].asInt())
        assertTrue(r["avisos"].any { it.asString() == "Δ arbitrios no calculado: sin ordenanza" }, r["avisos"].toString())
        // the same corrida asked by its id; none of another year: 404
        assertEquals(d.corrida, leer("/resumen?anio=2026&corrida=${d.corrida}")["corrida"]["id"].asString())
        send("GET", "/api/srtm/reportes/resumen?anio=2031", null, HttpStatus.NOT_FOUND, d.org)
        send("GET", "/api/srtm/reportes/resumen?anio=2025&corrida=${d.corrida}", null, HttpStatus.NOT_FOUND, d.org)
    }

    @Test
    fun `the catalog lists every report with its count`() {
        val c = leer("?anio=2026")
        assertEquals(
            listOf(
                "subvaluados",
                "sobrevaluados",
                "omisos",
                "sin-catastro",
                "sin-levantar",
                "sin-valor",
                "terreno",
                "construccion",
                "contribuyentes",
                "mayores-5-uit"
            ),
            c.map { it["clave"].asString() }
        )
        val n = c.associate { it["clave"].asString() to it["predios"].asInt() }
        assertEquals(3, n["subvaluados"])
        assertEquals(1, n["sin-catastro"])
        assertEquals(1, n["terreno"])
        assertEquals(1, n["construccion"])
        assertEquals(2, n["contribuyentes"])
        assertEquals(3, n["mayores-5-uit"])
        assertTrue(c[0]["titulo"].asString().isNotBlank())
    }

    @Test
    fun `a report pages, sorts and filters its predios`() {
        val d = datos()
        val sub = leer("/subvaluados?anio=2026")
        assertEquals(listOf(3, 0, 25), listOf(sub["total"].asInt(), sub["page"].asInt(), sub["size"].asInt()))
        // by Δ impuesto, the largest first
        assertEquals(listOf("5", "1", "2"), lotes(sub))
        val a = sub["items"][1]
        assertEquals(
            listOf("A-${d.sufijo}", d.c1, d.codigoC1),
            listOf(a["codigoPredio"].asString(), a["contribuyente"].asString(), a["codigoContribuyente"].asString())
        )
        assertTrue(a["nombreContribuyente"].asString().isNotBlank())
        assertTrue(a["declaracion"].asString().isNotBlank())
        assertTrue(a["predio"].asString().isNotBlank())
        assertTrue(a["id"].asString().isNotBlank())
        assertEquals("CASA HABITACIÓN", a["usoDescripcion"].asString())
        assertEquals("A_INSPECCIONAR", a["decision"].asString())
        assertEquals(0, BigDecimal(240).compareTo(dec(a["areaTerrenoCatastro"])))
        assertTrue(sub["items"][0]["decision"].isNull)

        val pagina = leer("/subvaluados?anio=2026&page=1&size=2")
        assertEquals(listOf("2"), lotes(pagina))
        assertEquals(3, pagina["total"].asInt())
        assertEquals(listOf("1", "2", "5"), lotes(leer("/subvaluados?anio=2026&orden=codigo&dir=asc")))
        assertEquals(listOf("2", "1", "5"), lotes(leer("/subvaluados?anio=2026&orden=deltaImpuesto&dir=asc")))
        assertEquals(listOf("1"), lotes(leer("/subvaluados?anio=2026&sector=1")))
        assertEquals(listOf("1"), lotes(leer("/subvaluados?anio=2026&manzana=a")))
        assertEquals(listOf("2"), lotes(leer("/subvaluados?anio=2026&uso=02")))
        assertEquals(listOf("2"), lotes(leer("/subvaluados?anio=2026&confianza=DUDOSO")))
        assertEquals(listOf("2"), lotes(leer("/subvaluados?anio=2026&q=b-${d.sufijo}")))
        assertEquals(listOf("5", "1", "2"), lotes(leer("/subvaluados?anio=2026&q=${d.codigoC1}")))
        assertEquals(listOf("3"), lotes(leer("/sobrevaluados?anio=2026")))
        assertEquals(listOf("4"), lotes(leer("/omisos?anio=2026")))
        assertEquals("A_FISCALIZAR", leer("/omisos?anio=2026")["items"][0]["decision"].asString())
        assertEquals(listOf(null), lotes(leer("/sin-catastro?anio=2026")))
        assertEquals(listOf("1"), lotes(leer("/terreno?anio=2026")))
        assertEquals(listOf("1"), lotes(leer("/construccion?anio=2026")))
        // above 5 UIT of 2026: A, E and the omiso
        assertTrue(Parametros.uit(2026).multiply(BigDecimal(5)) < BigDecimal(40000))
        assertEquals(listOf("5", "1", "4"), lotes(leer("/mayores-5-uit?anio=2026")))
    }

    @Test
    fun `contribuyentes sums the delta of each one's predios`() {
        val d = datos()
        val c = leer("/contribuyentes?anio=2026")
        assertEquals(2, c["total"].asInt())
        val uno = c["items"][0]
        assertEquals(d.c1, uno["contribuyente"].asString())
        assertEquals(3, uno["predios"].asInt())
        assertEquals(0, BigDecimal(1300).compareTo(dec(uno["deltaImpuesto"])))
        assertEquals(0, BigDecimal(180000).compareTo(dec(uno["deltaAutoavaluo"])))
        val dos = c["items"][1]
        assertEquals(d.c2, dos["contribuyente"].asString())
        assertEquals(1, dos["predios"].asInt())
        assertEquals(listOf(d.c2, d.c1), leer("/contribuyentes?anio=2026&dir=asc")["items"].map { it["contribuyente"].asString() })
        assertEquals(listOf(d.c1), leer("/contribuyentes?anio=2026&q=${d.codigoC1}")["items"].map { it["contribuyente"].asString() })
        assertEquals(
            listOf(d.c2),
            leer("/contribuyentes?anio=2026&sector=01&manzana=A&confianza=CONFIABLE&dir=asc&size=1")["items"].map {
                it["contribuyente"].asString()
            }
        )
        send("GET", "/api/srtm/reportes/contribuyentes?anio=2026&orden=areaTerreno", null, HttpStatus.UNPROCESSABLE_CONTENT, d.org)
    }

    @Test
    fun `a parameter it cannot read is a 422, a report it does not have a 404`() {
        val org = datos().org
        for (malo in listOf("orden=nada", "dir=arriba", "confianza=TOTAL", "size=0", "size=201", "page=-1")) {
            send("GET", "/api/srtm/reportes/subvaluados?anio=2026&$malo", null, HttpStatus.UNPROCESSABLE_CONTENT, org)
        }
        send("GET", "/api/srtm/reportes/ninguno?anio=2026", null, HttpStatus.NOT_FOUND, org)
        send("GET", "/api/srtm/reportes/ninguno.csv?anio=2026", null, HttpStatus.NOT_FOUND, org)
    }

    @Test
    fun `the xlsx export takes the filters and every row, with Resumen, Detalle and Notas`() {
        val d = datos()
        val respuesta = descargar("/subvaluados.xlsx?anio=2026&confianza=CONFIABLE&page=0&size=1", d.org)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", respuesta.first.contentType.toString())
        val disposicion = respuesta.first.contentDisposition
        assertEquals("attachment", disposicion.type)
        assertEquals("brecha-subvaluados-2026.xlsx", disposicion.filename)
        ReadableWorkbook(ByteArrayInputStream(respuesta.second)).use { libro ->
            val hojas = libro.sheets.toList()
            assertEquals(listOf("Resumen", "Detalle", "Notas"), hojas.map { it.name })
            val resumen = hojas[0].read().flatMap { r -> (0 until r.cellCount).map { r.getCellText(it) } }
            assertTrue(d.corrida in resumen && "CONFIABLE" in resumen, resumen.toString())
            val detalle = hojas[1].read()
            // no page: both CONFIABLE subvaluados, by Δ impuesto
            assertEquals(3, detalle.size)
            val cabecera = (0 until detalle[0].cellCount).map { detalle[0].getCellText(it) }
            assertEquals(listOf("E-${d.sufijo}", "A-${d.sufijo}"), detalle.drop(1).map { it.getCellText(cabecera.indexOf("Código del predio")) })
            assertEquals("A_INSPECCIONAR", detalle[2].getCellText(cabecera.indexOf("Decisión de catastro")))
        }
    }

    @Test
    fun `the csv export is utf-8 with a BOM and semicolons`() {
        val d = datos()
        val (cabeceras, bytes) = descargar("/contribuyentes.csv?anio=2026", d.org)
        assertTrue(cabeceras.contentType.toString().startsWith("text/csv"), cabeceras.contentType.toString())
        assertEquals("brecha-contribuyentes-2026.csv", cabeceras.contentDisposition.filename)
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        val lineas = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals("codigoContribuyente;nombreContribuyente;predios;deltaAutoavaluo;deltaImpuesto", lineas[0])
        assertEquals(3, lineas.size)
        assertTrue(lineas[1].startsWith("${d.codigoC1};"), lineas[1])
        val predios =
            descargar("/omisos.csv?anio=2026", d.org)
                .second
                .toString(Charsets.UTF_8)
                .trimEnd()
                .split("\r\n")
        assertEquals(2, predios.size)
        assertTrue("O-${d.sufijo}" in predios[1], predios[1])
    }

    @Test
    fun `without catastro the reports go on, decisions null and an aviso says why`() {
        val org = datos().org
        FALLA.set(true)
        val r = leer("/resumen?anio=2026", org)
        assertTrue(r["decisiones"].isNull, r["decisiones"].toString())
        assertTrue(r["avisos"].any { it.asString().startsWith("Sin las decisiones de catastro") }, r["avisos"].toString())
        val sub = leer("/subvaluados?anio=2026", org)
        assertTrue(sub["items"].all { it["decision"].isNull })
        assertEquals(3, sub["total"].asInt())
    }

    @Test
    fun `reading the reports needs to read the corrida and its rows, contribuyentes the padron too`() {
        val sinCorrida = funcionario(listOf(permiso("predio", "READ")))
        send("GET", "/api/srtm/reportes/resumen?anio=2026", null, HttpStatus.FORBIDDEN, sinCorrida)
        send("GET", "/api/srtm/reportes?anio=2026", null, HttpStatus.FORBIDDEN, sinCorrida)
        val sinFilas = funcionario(listOf(permiso("brecha_corrida", "READ")))
        send("GET", "/api/srtm/reportes/subvaluados?anio=2026", null, HttpStatus.FORBIDDEN, sinFilas)
        send("GET", "/api/srtm/reportes/subvaluados.xlsx?anio=2026", null, HttpStatus.FORBIDDEN, sinFilas)
        val sinPadron = funcionario(listOf(permiso("brecha_corrida", "READ"), permiso("brecha_predio", "READ")))
        send("GET", "/api/srtm/reportes/contribuyentes?anio=2026", null, HttpStatus.FORBIDDEN, sinPadron)
        send("GET", "/api/srtm/reportes/contribuyentes.csv?anio=2026", null, HttpStatus.FORBIDDEN, sinPadron)
    }

    // the headers and the bytes of a download
    private fun descargar(
        ruta: String,
        token: String
    ): Pair<HttpHeaders, ByteArray> {
        val r =
            client
                .get()
                .uri("/api/srtm/reportes$ruta")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
        return r.responseHeaders to r.responseBody!!
    }

    companion object {
        private val SLUG = "reportes-${UUID.randomUUID().toString().take(8)}"
        private var datosDeLaClase: Datos? = null

        @Volatile
        private var decisionesDelStub: List<String> = emptyList()
        private val FALLA = AtomicBoolean(false)

        // catastro-backend: a token, and decision_brecha's records in one page (a 500 while FALLA)
        private val CATASTRO =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/auth/token") { ex -> responder(ex, 200, """{"token":"t-reportes","expiresAt":"2999-01-01T00:00:00Z"}""") }
                createContext("/api/objects/decision_brecha/records") { ex ->
                    when {
                        ex.requestHeaders.getFirst("Authorization") != "Bearer t-reportes" -> responder(ex, 401, "{}")
                        FALLA.get() -> responder(ex, 500, "{}")
                        else -> {
                            val todas = decisionesDelStub
                            responder(ex, 200, """{"content":[${todas.joinToString(",")}],"page":0,"size":200,"totalElements":${todas.size},"totalPages":1}""")
                        }
                    }
                }
                start()
            }

        private fun responder(
            ex: HttpExchange,
            estado: Int,
            cuerpo: String
        ) {
            val bytes = cuerpo.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(estado, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun catastro(registro: DynamicPropertyRegistry) {
            registro.add("srtm.catastro.enabled") { true }
            registro.add("srtm.catastro.url") { "http://127.0.0.1:${CATASTRO.address.port}" }
            registro.add("srtm.catastro.client-id") { "srtm-reportes" }
            registro.add("srtm.catastro.client-secret") { "secreto-de-prueba" }
            registro.add("srtm.catastro.organizacion") { SLUG }
            // the decisions are read on every request: FALLA shows at once
            registro.add("srtm.reportes.cache-decisiones") { "0s" }
        }

        @JvmStatic
        @AfterAll
        fun parar() = CATASTRO.stop(0)
    }
}
