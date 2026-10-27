package srtm.brecha

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import srtm.impuesto.ImpuestoPredial
import srtm.impuesto.Parametros
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// the brecha end to end against two stubs of catastro-backend (the jdk's http server, on the contract of task 5):
// predios and declaraciones through the portal's api, a corrida started by POST and followed until TERMINADA, and the
// panel, the rows and the lotes it answers. a catastro per organization: SLUG's by the single properties of before
// (srtm.catastro.organizacion), SLUG_ICA's by an entry of srtm.catastro.organizaciones; the tests share them, each
// created once with its slug, and keep apart by year. an organization without a catastro is refused
class BrechaApiTest : SrtmApiTest() {
    // the organization of the catastro, with the model and the verified predial parameters: its admin's token
    private fun organizacion(): String = organizacionDelCatastro ?: crearOrganizacion(SLUG).also { organizacionDelCatastro = it }

    // the organization of the other catastro (the map's entry)
    private fun organizacionIca(): String = organizacionDeIca ?: crearOrganizacion(SLUG_ICA).also { organizacionDeIca = it }

    private fun crearOrganizacion(slug: String): String {
        val email = "admin@$slug.test"
        val clave = "clave-de-la-organizacion-del-catastro"
        post("/api/organizations", mapOf("name" to slug, "slug" to slug, "adminEmail" to email, "adminPassword" to clave))
        val suyo = bearer(email, clave)
        aplicarModelo(suyo)
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
            post("/api/objects/parametro_tributario/records", mapOf("attributes" to attributes), suyo)
        }
        return suyo
    }

    private fun predio(
        org: String,
        codigo: String,
        cpu: String? = null
    ): String =
        post(
            "/api/srtm/predios",
            mapOf("codigo" to codigo, "codigo_cpu" to cpu, "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to "01"),
            org
        )["id"].asString()

    // the declared autoavalúo as an imported declaración has it: in an organization with its catastro the portal's
    // writes value it there (#156), and this stub values no declaración
    private fun declarar(
        org: String,
        contribuyente: String,
        predio: String,
        autoavaluo: String,
        anio: Int = 2026,
        delPadron: Map<String, Any?> = emptyMap()
    ): JsonNode {
        val id =
            post(
                "/api/srtm/declaraciones",
                mapOf("contribuyente" to contribuyente, "predio" to predio, "anio" to anio, "secuencia_uso" to "1"),
                org
            )["id"].asString()
        val guardada = tree(send("GET", "/api/objects/declaracion_predial/records/$id", null, HttpStatus.OK, org))["attributes"]
        val valores =
            mapOf("valor_autoavaluo" to BigDecimal(autoavaluo), "valor_condominio" to BigDecimal(autoavaluo), "valor_afecto" to BigDecimal(autoavaluo)) +
                delPadron
        return tree(send("PUT", "/api/objects/declaracion_predial/records/$id", mapOf("attributes" to fields(guardada) + valores), HttpStatus.OK, org))
    }

    private fun contribuyente(org: String) = post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento()), org)["id"].asString()

    // POST, then GET until it ends
    private fun corrida(
        org: String,
        anio: Int
    ): JsonNode {
        val lanzada = tree(send("POST", "/api/srtm/brecha/corridas", mapOf("anio" to anio), HttpStatus.ACCEPTED, org))
        assertEquals("PENDIENTE", lanzada["estado"].asString())
        val id = lanzada["id"].asString()
        repeat(300) {
            val c = tree(send("GET", "/api/srtm/brecha/corridas/$id", null, HttpStatus.OK, org))
            if (c["estado"].asString() in setOf(TERMINADA, FALLIDA)) return c
            Thread.sleep(100)
        }
        error("la corrida $id no terminó")
    }

    private fun liquidar(base: String) = ImpuestoPredial.liquidar(2026, BigDecimal(base), Parametros.predial).impuestoAnual!!

    private fun dec(n: JsonNode) = n.decimalValue().setScale(2, RoundingMode.HALF_UP)

    @Test
    fun `a corrida crosses the padron with the catastro and the panel adds it up`() {
        val org = organizacion()
        val sufijo = uniqueDocumento()
        val c1 = contribuyente(org)
        val c2 = contribuyente(org)
        val a = predio(org, "A-$sufijo")
        val b = predio(org, "B-$sufijo")
        val c = predio(org, "C-$sufijo")
        val d = predio(org, "D-$sufijo", cpu = "CPU-$sufijo")
        declarar(org, c1, a, "100000.00")
        declarar(org, c1, b, "80000.00")
        declarar(org, c2, c, "50000.00")
        declarar(org, c2, d, "20000.00")
        ITEMS.set(
            listOf(
                item("A-$sufijo", "150000.00"),
                item("B-$sufijo", "80000.00"),
                // D by its CPU, not valued
                """{"codigoMunicipal":"X-$sufijo","codigoCpu":"CPU-$sufijo","sector":"01","manzana":"A","lote":"4","uso":"010101",
                "areaTerreno":100,"areaConstruida":0,"longitudFrente":null,"terreno":null,"construccion":null,"obras":null,
                "autoavaluo":null,"motivo":"Sin arancel","llave":"ARANCEL_FALTANTE","loteGeom":null}""",
                item("NUEVO-$sufijo", "40000.00", geometria = CUADRADO)
            )
        )

        val corrida = corrida(org, 2026)
        assertEquals(TERMINADA, corrida["estado"].asString(), corrida.toString())
        assertEquals("MANUAL", corrida["origen"].asString())
        assertEquals("valuacion-v1", corrida["versionCatastro"].asString())
        assertEquals(5, corrida["totalPredios"].asInt())
        assertEquals(5, corrida["procesados"].asInt())
        // no ordinance in this organization: no Δ arbitrios, and the corrida says why
        assertTrue(corrida["mensaje"].asString().startsWith("Δ arbitrios no calculado"), corrida.toString())
        assertTrue(corrida["deltaArbitrios"].isNull)

        val panel = tree(send("GET", "/api/srtm/brecha/2026/panel", null, HttpStatus.OK, org))
        assertEquals(corrida["id"].asString(), panel["corrida"]["id"].asString())
        val k = panel["kpis"]
        assertEquals(
            listOf(4, 4, 1, 1, 0, 1, 1, 1),
            listOf("prediosCatastro", "prediosPadron", "omisos", "subvaluados", "sobrevaluados", "coinciden", "sinCatastro", "sinValor").map { k[it].asInt() }
        )
        // the compared rows (A, B) and the omiso: 180000 + 90000 = 270000. C (SIN_CATASTRO) and D (SIN_VALOR) only in
        // the totals
        assertEquals(BigDecimal("180000.00"), dec(k["autoavaluoDeclarado"]))
        assertEquals(BigDecimal("270000.00"), dec(k["autoavaluoCatastro"]))
        assertEquals(BigDecimal("90000.00"), dec(k["deltaAutoavaluo"]))
        assertEquals(dec(k["autoavaluoCatastro"]), dec(k["autoavaluoDeclarado"]) + dec(k["deltaAutoavaluo"]))
        assertEquals(BigDecimal("250000.00"), dec(k["autoavaluoDeclaradoTotal"]))
        assertEquals(BigDecimal("270000.00"), dec(k["autoavaluoCatastroTotal"]))
        assertEquals(liquidar("40000.00"), dec(k["deltaImpuestoOmisosEstimado"]))
        assertEquals(BigDecimal("180000.00"), dec(corrida["declarado"]))
        assertEquals(BigDecimal("270000.00"), dec(corrida["catastro"]))
        // c1 goes from 180000 to 230000 of base; the omiso is estimated alone; c2 does not move
        val esperado = liquidar("230000.00") - liquidar("180000.00") + liquidar("40000.00")
        assertEquals(esperado, dec(k["deltaImpuesto"]))
        assertTrue(k["deltaArbitrios"].isNull)
        assertEquals(esperado, dec(corrida["deltaImpuesto"]))
        assertEquals(Categoria.entries.size, panel["porCategoria"].size())
        assertEquals("01", panel["porSector"][0]["sector"].asString())

        val omisos = tree(send("GET", "/api/srtm/brecha/2026/predios?categoria=OMISO", null, HttpStatus.OK, org))
        assertEquals(1, omisos["totalElements"].asInt())
        val omiso = omisos["content"][0]
        assertEquals("NUEVO-$sufijo", omiso["codigoCatastro"].asString())
        assertEquals(true, omiso["estimado"].asBoolean())
        assertEquals(liquidar("40000.00"), dec(omiso["deltaImpuesto"]))
        val sinValor = tree(send("GET", "/api/srtm/brecha/2026/predios?categoria=SIN_VALOR", null, HttpStatus.OK, org))["content"][0]
        assertEquals("D-$sufijo", sinValor["codigoPredio"].asString())
        assertEquals("Sin arancel", sinValor["motivo"].asString())
        send("GET", "/api/srtm/brecha/2026/predios?categoria=NINGUNA", null, HttpStatus.UNPROCESSABLE_CONTENT, org)

        val lotes = tree(send("GET", "/api/srtm/brecha/2026/lotes", null, HttpStatus.OK, org))
        assertEquals("FeatureCollection", lotes["type"].asString())
        val feature = lotes["features"].single()
        assertEquals("OMISO", feature["properties"]["categoria"].asString())
        assertEquals("NUEVO-$sufijo", feature["properties"]["codigo"].asString())
        assertEquals("Polygon", feature["geometry"]["type"].asString())
        assertEquals(-75.22, feature["geometry"]["coordinates"][0][0][0].asDouble(), 1e-6)

        assertEquals(1, tree(send("GET", "/api/srtm/brecha/corridas?anio=2026", null, HttpStatus.OK, org)).size())

        // #204: this catastro has none of the new keys nor /titulares: every row is DUDOSO, and the corrida says so
        assertTrue(SIN_TITULARES in corrida["mensaje"].asString(), corrida.toString())
        val filas = tree(send("GET", "/api/srtm/brecha/2026/predios", null, HttpStatus.OK, org))["content"]
        assertEquals(List(5) { "DUDOSO" }, filas.mapNotNull { it["confianza"]?.asString() })
        assertEquals(listOf(0, 5, 0), listOf("prediosConfiables", "prediosDudosos", "prediosSinLevantar").map { corrida[it].asInt() })
        assertEquals(esperado, dec(corrida["deltaImpuestoDudoso"]))
        assertEquals(5, panel["porConfianza"]["DUDOSO"]["predios"].asInt())
        assertEquals(0, k["sinLevantar"].asInt())
    }

    @Test
    fun `the corrida stores its confianza counts and the predios filter by it`() {
        val org = organizacion()
        val sufijo = uniqueDocumento()
        val c1 = contribuyente(org)
        declarar(org, c1, predio(org, "A-$sufijo"), "100000.00", anio = 2023)
        declarar(org, c1, predio(org, "B-$sufijo"), "100000.00", anio = 2023)
        ITEMS.set(
            listOf(
                item("A-$sufijo", "150000.00", llavesNuevas = mapOf("vinculoMetodo" to CODIGO_RENTAS)),
                item("B-$sufijo", "100000.00", llavesNuevas = mapOf("vinculoMetodo" to null))
            )
        )

        // the stub serves no /titulares (404): no failure
        val corrida = corrida(org, 2023)
        assertEquals(TERMINADA, corrida["estado"].asString(), corrida.toString())
        assertTrue(SIN_TITULARES in corrida["mensaje"].asString(), corrida.toString())
        assertEquals(listOf(1, 1, 0), listOf("prediosConfiables", "prediosDudosos", "prediosSinLevantar").map { corrida[it].asInt() })
        // c1's whole difference falls on A, the CONFIABLE one
        assertEquals(dec(corrida["deltaImpuesto"]), dec(corrida["deltaImpuestoConfiable"]))
        assertEquals(BigDecimal("0.00"), dec(corrida["deltaImpuestoDudoso"]))
        assertEquals(BigDecimal("0.00"), dec(corrida["deltaImpuestoSinLevantar"]))

        val panel = tree(send("GET", "/api/srtm/brecha/2023/panel", null, HttpStatus.OK, org))["porConfianza"]
        assertEquals(listOf("CONFIABLE", "DUDOSO", "SIN_LEVANTAR"), panel.propertyNames().toList())
        assertEquals(1, panel["CONFIABLE"]["predios"].asInt())
        assertEquals(dec(corrida["deltaImpuestoConfiable"]), dec(panel["CONFIABLE"]["deltaImpuesto"]))
        assertEquals(BigDecimal("50000.00"), dec(panel["CONFIABLE"]["deltaAutoavaluo"]))

        val confiables = tree(send("GET", "/api/srtm/brecha/2023/predios?confianza=CONFIABLE", null, HttpStatus.OK, org))
        assertEquals(1, confiables["totalElements"].asInt())
        val a = confiables["content"][0]
        assertEquals("A-$sufijo", a["codigoPredio"].asString())
        assertEquals("Vínculo por código de rentas", a["confianzaMotivo"].asString())
        val dudosos = tree(send("GET", "/api/srtm/brecha/2023/predios?confianza=dudoso", null, HttpStatus.OK, org))["content"]
        assertEquals(listOf("B-$sufijo"), dudosos.mapNotNull { it["codigoPredio"]?.asString() })
        send("GET", "/api/srtm/brecha/2023/predios?confianza=X", null, HttpStatus.UNPROCESSABLE_CONTENT, org)
    }

    // #208: the ficha surveys no obras; the DJ's come from the external padrón's otras instalaciones, read off the records
    @Test
    fun `a ficha without obras takes the DJ's obras del padron, and the row keeps them`() {
        val org = organizacion()
        val sufijo = uniqueDocumento()
        declarar(org, contribuyente(org), predio(org, "O-$sufijo"), "100000.00", anio = 2024, delPadron = mapOf("valor_otras_instalaciones" to 10000))
        ITEMS.set(listOf(item("O-$sufijo", "90000.00")))

        val corrida = corrida(org, 2024)
        assertEquals(TERMINADA, corrida["estado"].asString(), corrida.toString())
        assertTrue("1 predios sin obras en la ficha: se sumaron S/ 10000.00 de obras de sus DJ" in corrida["mensaje"].asString(), corrida.toString())
        val fila = tree(send("GET", "/api/srtm/brecha/2024/predios", null, HttpStatus.OK, org))["content"].single()
        assertEquals("COINCIDE", fila["categoria"].asString())
        assertEquals(BigDecimal("100000.00"), dec(fila["autoavaluoCatastro"]))
        assertEquals(BigDecimal("10000.00"), dec(fila["obrasDj"]))
    }

    // #246: a code shared by distinct predios, migrated (#219): the original is de baja and its DJ went to <codigo>-1 and
    // <codigo>-2, which keep the SAT code as codigo_origen. the ficha of that code crosses a derived predio, not the
    // original: a predio de baja is no candidate of the cruce, nor a row of the brecha
    @Test
    fun `a ficha of a migrated code crosses its derived predios by codigo_origen, never the predio de baja`() {
        val org = organizacion()
        val sufijo = uniqueDocumento()
        val codigo = "M-$sufijo"

        fun registro(atributos: Map<String, Any?>) =
            post(
                "/api/objects/predio/records",
                mapOf("attributes" to mapOf("direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to "01") + atributos),
                org
            )["id"].asString()
        registro(mapOf("codigo" to codigo, "fecha_baja" to "2026-10-25", "motivo_baja" to "#207: sus DJ pasan a $codigo-1 y $codigo-2"))
        val c = contribuyente(org)
        declarar(org, c, registro(mapOf("codigo" to "$codigo-1", "codigo_origen" to codigo)), "100000.00", anio = 2022)
        declarar(org, c, registro(mapOf("codigo" to "$codigo-2", "codigo_origen" to codigo)), "50000.00", anio = 2022)
        ITEMS.set(listOf(item(codigo, "100000.00")))

        val corrida = corrida(org, 2022)
        assertEquals(TERMINADA, corrida["estado"].asString(), corrida.toString())
        val filas = tree(send("GET", "/api/srtm/brecha/2022/predios", null, HttpStatus.OK, org))["content"]
        val porPredio = filas.associate { it["codigoPredio"]?.asString() to it["categoria"].asString() }
        assertEquals(mapOf("$codigo-1" to "COINCIDE", "$codigo-2" to "SIN_CATASTRO"), porPredio, filas.toString())
        assertEquals(codigo, filas.single { it["codigoPredio"]?.asString() == "$codigo-1" }["codigoCatastro"].asString())
    }

    // #247: the DJ's niveles against the ficha's construcciones (catastro-backend#71). the row keeps both sides' areas
    // and what its niveles sum; each piso is a brecha_nivel of the corrida and the row. an item without construcciones
    // (an older catastro) compares no nivel
    @Test
    fun `a corrida keeps the areas of both sides and pairs the niveles by piso`() {
        // the year of no other test: Ica's 2026 (each year with predial parameters has its test in the other organization)
        val org = organizacionIca()
        val sufijo = uniqueDocumento()
        val conNiveles = predio(org, "N-$sufijo")
        val sinNiveles = predio(org, "S-$sufijo")
        val dj = declarar(org, contribuyente(org), conNiveles, "100000.00", anio = 2026, delPadron = mapOf("area_terreno" to 200, "area_construida" to 150))
        declarar(org, contribuyente(org), sinNiveles, "100000.00", anio = 2026, delPadron = mapOf("area_terreno" to 90))
        val id = dj["id"].asString()
        val nivel =
            mapOf(
                "declaracion" to id,
                "tipo_nivel" to "PISO",
                "anio_construccion" to 2010,
                "material" to "CONCRETO",
                "estado_conservacion" to "BUENO"
            )
        post("/api/objects/nivel_construccion/records", mapOf("attributes" to nivel + mapOf("numero_piso" to 1, "area_construida" to 100)), org)
        post("/api/objects/nivel_construccion/records", mapOf("attributes" to nivel + mapOf("numero_piso" to 2, "area_construida" to 50)), org)
        post("/api/objects/nivel_construccion/records", mapOf("attributes" to nivel + mapOf("numero_piso" to 3, "area_construida" to 20)), org)
        val construcciones =
            """[{"piso":null,"seccion":1,"areaConstruida":15},{"piso":1,"seccion":1,"areaConstruida":70},""" +
                """{"piso":1,"seccion":2,"areaConstruida":50},{"piso":2,"seccion":1,"areaConstruida":40}]"""
        ITEMS_ICA.set(listOf(item("N-$sufijo", "100000.00", llavesCrudas = mapOf("construcciones" to construcciones)), item("S-$sufijo", "100000.00")))

        val corrida = corrida(org, 2026)
        assertEquals(TERMINADA, corrida["estado"].asString(), corrida.toString())
        val filas = tree(send("GET", "/api/srtm/brecha/2026/predios", null, HttpStatus.OK, org))["content"].associateBy { it["codigoPredio"].asString() }
        val fila = filas.getValue("N-$sufijo")
        assertEquals(BigDecimal("200.00"), dec(fila["areaTerrenoDeclarada"]))
        assertEquals(BigDecimal("150.00"), dec(fila["areaConstruidaDeclarada"]))
        assertEquals(BigDecimal("200.00"), dec(fila["areaTerrenoCatastro"]))
        assertEquals(BigDecimal("100.00"), dec(fila["areaConstruidaCatastro"]))
        assertEquals(2, fila["nivelesComparados"].asInt())
        assertEquals(1, fila["nivelesCatastroMayor"].asInt())
        assertEquals(BigDecimal("20.00"), dec(fila["deltaAreaNiveles"]))

        val respuesta = tree(send("GET", "/api/objects/brecha_nivel/records?size=50&brecha_predio=${fila["id"].asString()}", null, HttpStatus.OK, org))
        val niveles = respuesta.path("content").values().map { it.path("attributes") }
        assertEquals(setOf(corrida.path("id").asString()), niveles.map { it.path("corrida").asString() }.toSet(), respuesta.toString())

        fun area(
            n: JsonNode,
            campo: String
        ) = n.path(campo).takeUnless { it.isNull || it.isMissingNode }?.let(::dec)
        assertEquals(
            setOf(
                listOf("1", "100.00", "120.00", "PAR"),
                listOf("2", "50.00", "40.00", "PAR"),
                listOf("3", "20.00", null, "SOLO_RENTAS"),
                listOf(null, null, "15.00", "AMBIGUO")
            ),
            niveles
                .map {
                    listOf(
                        it.path("piso").takeUnless { p -> p.isNull || p.isMissingNode }?.asString(),
                        area(it, "area_rentas")?.toPlainString(),
                        area(it, "area_catastro")?.toPlainString(),
                        it.path("estado").asString()
                    )
                }.toSet()
        )

        val sinConstrucciones = filas.getValue("S-$sufijo")
        assertEquals(BigDecimal("90.00"), dec(sinConstrucciones["areaTerrenoDeclarada"]))
        assertTrue(sinConstrucciones.path("nivelesComparados").let { it.isNull || it.isMissingNode }, sinConstrucciones.toString())
        val deLaOtra = tree(send("GET", "/api/objects/brecha_nivel/records?brecha_predio=${sinConstrucciones["id"].asString()}", null, HttpStatus.OK, org))
        assertEquals(0, deLaOtra.path("content").size(), deLaOtra.toString())
    }

    @Test
    fun `a year without its parameters fails the corrida and names them, and there is no panel`() {
        val org = organizacion()
        ITEMS.set(listOf(item("Z-${uniqueDocumento()}", "1000.00")))
        val corrida = corrida(org, 2099)
        assertEquals(FALLIDA, corrida["estado"].asString())
        assertEquals("Faltan parámetros del impuesto predial: UIT 2099", corrida["mensaje"].asString())
        send("GET", "/api/srtm/brecha/2099/panel", null, HttpStatus.NOT_FOUND, org)
    }

    @Test
    fun `a run failed midway (abandoned) neither ends TERMINADA nor writes its rows`() {
        val org = organizacion()
        val sufijo = uniqueDocumento()
        declarar(org, contribuyente(org), predio(org, "Q-$sufijo"), "1000.00", anio = 2027)
        ITEMS.set(listOf(item("Q-$sufijo", "2000.00")))
        val llego = CountDownLatch(1)
        val sigue = CountDownLatch(1)
        BLOQUEO.set(llego to sigue)
        val id: String
        try {
            id = tree(send("POST", "/api/srtm/brecha/corridas", mapOf("anio" to 2027), HttpStatus.ACCEPTED, org))["id"].asString()
            assertTrue(llego.await(30, TimeUnit.SECONDS), "the run never asked catastro")
            // what a later POST does to a stale one: FALLIDA, through the generic api (a full replace)
            val guardada = tree(send("GET", "/api/objects/brecha_corrida/records/$id", null, HttpStatus.OK, org))["attributes"]
            val attributes = fields(guardada) + mapOf("estado" to FALLIDA, "mensaje" to "Abandonada en la prueba")
            send("PUT", "/api/objects/brecha_corrida/records/$id", mapOf("attributes" to attributes), HttpStatus.OK, org)
        } finally {
            BLOQUEO.set(null)
            sigue.countDown()
        }
        // the run reads catastro's answer, beats, finds its corrida no longer EN_CURSO and stops
        repeat(20) {
            Thread.sleep(150)
            val c = tree(send("GET", "/api/srtm/brecha/corridas/$id", null, HttpStatus.OK, org))
            assertEquals(FALLIDA, c["estado"].asString(), c.toString())
            assertEquals("Abandonada en la prueba", c["mensaje"].asString())
            assertEquals(0, c["procesados"].asInt())
        }
        val filas = tree(send("GET", "/api/srtm/brecha/2027/predios?corrida=$id", null, HttpStatus.OK, org))
        assertEquals(0, filas["totalElements"].asInt())
    }

    // a corrida EN_CURSO whose latido is older than the lease, as a dead instance leaves it
    private fun colgada(
        org: String,
        anio: Int
    ): String =
        post(
            "/api/objects/brecha_corrida/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "anio" to anio,
                        "estado" to EN_CURSO,
                        "total_predios" to 0,
                        "procesados" to 0,
                        "iniciado" to "2020-01-01T00:00:00Z",
                        "latido" to "2020-01-01T00:00:00Z"
                    )
            ),
            org
        )["id"].asString()

    private fun guardada(
        id: String,
        org: String
    ) = tree(send("GET", "/api/objects/brecha_corrida/records/$id", null, HttpStatus.OK, org))["attributes"]

    @Test
    fun `a corrida whose instance stopped beating is read FALLIDA, so nobody waits on it`() {
        val org = organizacion()
        val id = colgada(org, 2028)
        val c = tree(send("GET", "/api/srtm/brecha/corridas/$id", null, HttpStatus.OK, org))
        assertEquals(FALLIDA, c["estado"].asString(), c.toString())
        assertTrue(c["mensaje"].asString().startsWith("Abandonada: sin latido desde 2020-01-01"), c.toString())
        assertTrue(!c["terminado"].isNull)
        // stored so by an admin's read
        assertEquals(FALLIDA, guardada(id, org)["estado"].asString())
        assertEquals(FALLIDA, tree(send("GET", "/api/srtm/brecha/corridas?anio=2028", null, HttpStatus.OK, org))[0]["estado"].asString())

        // a reader who may only read gets it FALLIDA too, without writing it; the next admin's read stores it
        val otra = colgada(token, 2028)
        val lector = funcionario(listOf(permiso(BRECHA_CORRIDA, "READ")))
        val vista = tree(send("GET", "/api/srtm/brecha/corridas", null, HttpStatus.OK, lector)).first { it["id"].asString() == otra }
        assertEquals(FALLIDA, vista["estado"].asString(), vista.toString())
        assertEquals(EN_CURSO, guardada(otra, token)["estado"].asString())
        assertEquals(FALLIDA, tree(send("GET", "/api/srtm/brecha/corridas/$otra", null, HttpStatus.OK))["estado"].asString())
        assertEquals(FALLIDA, guardada(otra, token)["estado"].asString())
    }

    @Test
    fun `an organization without its catastro is refused`() {
        val otra = otraOrganizacion()
        val problema = tree(send("POST", "/api/srtm/brecha/corridas", mapOf("anio" to 2026), HttpStatus.CONFLICT, otra))
        assertEquals(SIN_CATASTRO_CONFIGURADO, problema["detail"].asString(), problema.toString())
        assertEquals(0, tree(send("GET", "/api/srtm/brecha/corridas", null, HttpStatus.OK, otra)).size())
    }

    @Test
    fun `two organizations, each its corrida reads its own catastro only`() {
        val perene = organizacion()
        val ica = organizacionIca()
        val sufijo = uniqueDocumento()
        declarar(perene, contribuyente(perene), predio(perene, "P-$sufijo"), "1000.00", anio = 2025)
        declarar(ica, contribuyente(ica), predio(ica, "I-$sufijo"), "1000.00", anio = 2025)
        ITEMS.set(listOf(item("P-$sufijo", "2000.00")))
        ITEMS_ICA.set(listOf(item("I-$sufijo", "3000.00"), item("I2-$sufijo", "500.00")))
        CUENTAS.clear()
        CUENTAS_ICA.clear()

        val dePerene = corrida(perene, 2025)
        val deIca = corrida(ica, 2025)
        assertEquals(TERMINADA, dePerene["estado"].asString(), dePerene.toString())
        assertEquals(TERMINADA, deIca["estado"].asString(), deIca.toString())
        assertEquals("valuacion-v1", dePerene["versionCatastro"].asString())
        assertEquals("valuacion-ica", deIca["versionCatastro"].asString())
        val filasPerene = tree(send("GET", "/api/srtm/brecha/2025/predios", null, HttpStatus.OK, perene))["content"]
        val filasIca = tree(send("GET", "/api/srtm/brecha/2025/predios", null, HttpStatus.OK, ica))["content"]
        assertEquals(setOf("P-$sufijo"), filasPerene.mapNotNull { it["codigoCatastro"]?.asString() }.toSet())
        assertEquals(setOf("I-$sufijo", "I2-$sufijo"), filasIca.mapNotNull { it["codigoCatastro"]?.asString() }.toSet())
        assertEquals(BigDecimal("3500.00"), dec(deIca["catastro"]))
        // each catastro saw its own service account only (its token is checked by the stub: another's is a 401). either
        // token may be cached since an earlier request: the declaración's write already asks its catastro (#156)
        assertTrue(CUENTAS.all { it == "srtm-perene" }, "$CUENTAS")
        assertTrue(CUENTAS_ICA.all { it == "srtm-ica" }, "$CUENTAS_ICA")
    }

    @Test
    fun `a catastro that answers no item while the padron has predios fails the corrida`() {
        val org = organizacion()
        declarar(org, contribuyente(org), predio(org, "V-${uniqueDocumento()}"), "1000.00", anio = 2029)
        ITEMS.set(emptyList())
        val corrida = corrida(org, 2029)
        assertEquals(FALLIDA, corrida["estado"].asString(), corrida.toString())
        assertTrue(corrida["mensaje"].asString().startsWith("El catastro no devolvió valuaciones de 2029"), corrida.toString())
        assertEquals(0, corrida["totalPredios"].asInt())
    }

    @Test
    fun `starting needs to write the corrida and its predios, reading needs to read the corrida`() {
        val lector = funcionario(listOf(permiso("predio", "READ")))
        send("POST", "/api/srtm/brecha/corridas", mapOf("anio" to 2026), HttpStatus.FORBIDDEN, lector)
        send("GET", "/api/srtm/brecha/2026/panel", null, HttpStatus.FORBIDDEN, lector)
        send("GET", "/api/srtm/brecha/corridas", null, HttpStatus.FORBIDDEN, lector)
        // #203: the aviso de cambios asks the same read, no role needs a new permission over cambio_catastro
        send("GET", "/api/srtm/brecha/2026/cambios", null, HttpStatus.FORBIDDEN, lector)
    }

    // an item as catastro-backend answered before #204; llavesNuevas adds its keys (string values, or null),
    // llavesCrudas its keys with their json as given
    private fun item(
        codigo: String,
        autoavaluo: String,
        geometria: String = "null",
        llavesNuevas: Map<String, String?> = emptyMap(),
        llavesCrudas: Map<String, String> = emptyMap()
    ): String {
        val nuevas =
            llavesNuevas.entries.joinToString("") { (k, v) -> ",\"$k\":" + (v?.let { "\"$it\"" } ?: "null") } +
                llavesCrudas.entries.joinToString("") { (k, v) -> ",\"$k\":$v" }
        return """{"codigoMunicipal":"$codigo","codigoCpu":null,"sector":"01","manzana":"A","lote":"1","uso":"010101",
        "areaTerreno":200,"areaConstruida":100,"longitudFrente":10.5,"terreno":1.00,"construccion":1.00,"obras":0.00,
        "autoavaluo":$autoavaluo,"motivo":null,"llave":null,"loteGeom":$geometria$nuevas}"""
    }

    companion object {
        private val ITEMS = AtomicReference<List<String>>(emptyList())
        private val ITEMS_ICA = AtomicReference<List<String>>(emptyList())

        // the client-id of each token asked of each catastro
        private val CUENTAS = CopyOnWriteArrayList<String>()
        private val CUENTAS_ICA = CopyOnWriteArrayList<String>()

        // the organization srtm.catastro.organizacion names, by its slug: created by the first test that needs it
        private val SLUG = "brecha-${java.util.UUID.randomUUID().toString().take(8)}"
        private var organizacionDelCatastro: String? = null

        // the organization of the map's entry (keyed "ica", its organizacion this slug)
        private val SLUG_ICA = "ica-${java.util.UUID.randomUUID().toString().take(8)}"
        private var organizacionDeIca: String? = null

        // while set, the valuaciones say they were asked (first) and wait (second) before answering
        private val BLOQUEO = AtomicReference<Pair<CountDownLatch, CountDownLatch>?>(null)

        // the aviso of a catastro without GET /titulares (the stubs serve none: 404)
        private const val SIN_TITULARES =
            "El catastro no da titulares (GET /api/catastro/titulares respondió 404): la confianza no compara titulares"

        private const val CUADRADO =
            """{"type":"Polygon","coordinates":[[[-75.22,-10.95],[-75.2195,-10.95],[-75.2195,-10.9495],[-75.22,-10.9495],[-75.22,-10.95]]]}"""

        // catastro-backend's two endpoints: a token (its own, noting the client-id asked), and the items in one page
        // for that token only (another's: 401)
        private fun catastro(
            token: String,
            version: String,
            items: AtomicReference<List<String>>,
            cuentas: MutableList<String>
        ): HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/auth/token") { ex ->
                    cuentas += Regex("\"clientId\":\"([^\"]*)\"").find(ex.requestBody.readAllBytes().decodeToString())!!.groupValues[1]
                    responder(ex, 200, """{"token":"$token","expiresAt":"2999-01-01T00:00:00Z","serviceAccount":"srtm"}""")
                }
                createContext("/api/catastro/valuaciones") { ex ->
                    if (ex.requestHeaders.getFirst("Authorization") != "Bearer $token") {
                        responder(ex, 401, "{}")
                        return@createContext
                    }
                    val anio = Regex("anio=(\\d+)").find(ex.requestURI.query)!!.groupValues[1]
                    BLOQUEO.get()?.let { (llego, sigue) ->
                        llego.countDown()
                        sigue.await(30, TimeUnit.SECONDS)
                    }
                    val todos = items.get()
                    responder(ex, 200, """{"anio":$anio,"version":"$version","page":0,"size":500,"total":${todos.size},"items":[${todos.joinToString(",")}]}""")
                }
                start()
            }

        private val CATASTRO = catastro("t-perene", "valuacion-v1", ITEMS, CUENTAS)
        private val CATASTRO_ICA = catastro("t-ica", "valuacion-ica", ITEMS_ICA, CUENTAS_ICA)

        private fun responder(
            ex: com.sun.net.httpserver.HttpExchange,
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
            registro.add("srtm.catastro.client-id") { "srtm-perene" }
            registro.add("srtm.catastro.client-secret") { "secreto-de-prueba" }
            registro.add("srtm.catastro.organizacion") { SLUG }
            // the other catastro, an entry of the map under a name, its organization by its slug
            registro.add("srtm.catastro.organizaciones.ica.organizacion") { SLUG_ICA }
            registro.add("srtm.catastro.organizaciones.ica.url") { "http://127.0.0.1:${CATASTRO_ICA.address.port}" }
            registro.add("srtm.catastro.organizaciones.ica.client-id") { "srtm-ica" }
            registro.add("srtm.catastro.organizaciones.ica.client-secret") { "otro-secreto-de-prueba" }
        }

        @JvmStatic
        @AfterAll
        fun parar() {
            CATASTRO.stop(0)
            CATASTRO_ICA.stop(0)
        }
    }
}
