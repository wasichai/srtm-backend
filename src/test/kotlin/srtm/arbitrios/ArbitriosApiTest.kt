package srtm.arbitrios

import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import srtm.emision.IdentidadEmision
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

// the arbitrios through the portal's api, on the real model: determination, queries, immutability, the 409 of a
// repeated clave, permissions, the rollback of a predio and the app's indexes. every figure and code of an ordinance is
// FICTITIOUS. the test db is shared and an ordinance is one per year: each test takes a year of its own, and its
// servicios and parameters rule only in it
class ArbitriosApiTest : ConArbitriosApiTest() {
    @Test
    fun `a predio is determined once - 201 with its cuotas, then 200 with none`() {
        val e = Escenario()
        val cuotas = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))
        assertEquals(24, cuotas.size()) // 2 servicios x 12 months
        val enero = cuotas[0]
        assertEquals(e.predio, enero["predio"].asString())
        assertEquals(e.contribuyente, enero["contribuyente"].asString())
        assertEquals(1, enero["periodo"].asInt())
        assertEquals(8.5, enero["monto"].asDouble())
        assertEquals("TASA_ARBITRIO:${e.codigos[0]}:Z1:CASA", enero["parametro_aplicado"].asString())
        assertEquals("Z1", enero["zona"].asString())
        assertEquals("CASA", enero["uso_arbitrio"].asString())
        assertEquals(LocalDate.now().toString(), enero["fecha_calculo"].asString())
        assertEquals("Determinación de prueba", enero["observacion"].asString())

        assertEquals(0, tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.OK)).size())
        assertEquals(24, pagina("anio=${e.anio}&predio=${e.predio}")["totalElements"].asInt())
    }

    @Test
    fun `a predio's year - servicio by month, each month's titular, totals from the backend`() {
        val e = Escenario()
        val antes = matriz(e)
        assertEquals(24, antes["pendientes"].asInt())
        assertEquals(0.0, antes["total"].asDouble())
        assertTrue(antes["fecha_calculo"].isNull)

        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val m = matriz(e)
        assertEquals(e.codigos, lista(m["filas"]) { it["servicio"]["codigo"].asString() })
        assertEquals(listOf(102.0, 51.0), lista(m["filas"]) { it["total"].asDouble() })
        assertEquals(List(12) { 12.75 }, lista(m["totales_por_mes"]) { it.asDouble() })
        assertEquals(153.0, m["total"].asDouble())
        assertEquals(LocalDate.now().toString(), m["fecha_calculo"].asString())
        assertEquals(0, m["pendientes"].asInt())
        assertEquals(List(12) { e.contribuyente }, lista(m["titulares"]) { it["titular"]["id"].asString() })
        // the cell's desglose: one entry, base PREDIO (no BASE_ARBITRIO row), no redondeo - the formula does not
        // repeat the monto (Calculo.formula)
        val enero = m["filas"][0]["meses"][0]
        assertEquals(1, enero["desglose"].size())
        assertEquals("8.50 S/ por predio", enero["desglose"][0]["formula"].asString())
    }

    @Test
    fun `what is missing is a 422 that names it, and nothing is written`() {
        val e = Escenario()
        val sinZona = "S${uniqueDocumento()}"
        val otro = e.predioDeclarado(inscribir(), sinZona)
        val problema = tree(send("POST", "/api/srtm/predios/$otro/arbitrios", pedido(e.anio), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("ARBITRIO_ZONA $sinZona ${e.anio}"), lista(problema["faltan"]) { it.asString() })
        assertEquals(1, problema["faltan_detalle"].size())
        assertEquals("ARBITRIO_ZONA $sinZona ${e.anio}", problema["faltan_detalle"][0]["mensaje"].asString())
        assertEquals(otro, problema["faltan_detalle"][0]["predio"].asString())
        assertEquals(0, pagina("anio=${e.anio}&predio=$otro")["totalElements"].asInt())
    }

    @Test
    fun `an ordinance not ratified determines nothing (D-02b)`() {
        val e = Escenario(ratificada = false)
        val problema = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("Ratificación de la ordenanza de arbitrios ${e.anio} (acuerdo y fecha)"), lista(problema["faltan"]) { it.asString() })
        assertTrue(tree(send("GET", "/api/srtm/arbitrios/parametros?anio=${e.anio}", null, HttpStatus.OK))["faltan"].size() == 1)
    }

    @Test
    fun `the observación says why, 5 to 500 characters`() {
        val e = Escenario()
        rejected("POST", e.determinar(), mapOf("anio" to e.anio, "observacion" to " ok "), "observacion")
        rejected("POST", e.determinar(), mapOf("anio" to e.anio), "observacion")
    }

    @Test
    fun `who may not create cuotas gets a 403 before anything is computed, and may still read`() {
        val e = Escenario()
        val lector = funcionario(listOf(permiso(null, "READ")))
        val problema = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.FORBIDDEN, lector))
        assertTrue(problema["detail"].asString().contains(CUOTA_ARBITRIO), problema.toString())
        assertEquals(24, tree(send("GET", "/api/srtm/predios/${e.predio}/arbitrios?anio=${e.anio}", null, HttpStatus.OK, lector))["pendientes"].asInt())
    }

    @Test
    fun `a cuota is never edited nor deleted, not even by an ADMIN through core's api`() {
        val e = Escenario()
        val cuota = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))[0]
        val id = cuota["id"].asString()
        val cambio = mapOf("attributes" to atributos(cuota) + ("monto" to 0))
        send("PUT", "/api/objects/$CUOTA_ARBITRIO/records/$id", cambio, HttpStatus.CONFLICT)
        send("DELETE", "/api/objects/$CUOTA_ARBITRIO/records/$id", null, HttpStatus.CONFLICT)
        val guardada = tree(send("GET", "/api/objects/$CUOTA_ARBITRIO/records/$id", null, HttpStatus.OK))
        assertEquals(8.5, guardada["attributes"]["monto"].asDouble())
    }

    @Test
    fun `a repeated cuota is a 409, not a 500, and one that breaks its invariants a 400`() {
        val e = Escenario()
        val cuota = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))[0]
        send("POST", "/api/objects/$CUOTA_ARBITRIO/records", mapOf("attributes" to atributos(cuota)), HttpStatus.CONFLICT)

        val clave = claveDeCuota(e.predio, e.servicios[0], e.anio, 13, "001")
        rejected("POST", "/api/objects/$CUOTA_ARBITRIO/records", mapOf("attributes" to atributos(cuota) + mapOf("periodo" to 13, "clave" to clave)), "periodo")
    }

    @Test
    fun `the query names what it cannot serve, and serves its filters`() {
        val e = Escenario()
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        for ((consulta, campo) in listOf("zona=Z1" to "zona", "anio=dos" to "anio", "predio=abc" to "predio", "size=0" to "size", "size=201" to "size")) {
            val problema = tree(send("GET", "/api/srtm/arbitrios?$consulta", null, HttpStatus.UNPROCESSABLE_CONTENT))
            assertEquals(campo, problema["errors"][0]["field"].asString(), consulta)
        }
        assertEquals(12, pagina("anio=${e.anio}&predio=${e.predio}&servicio=${e.servicios[1]}")["totalElements"].asInt())
        assertEquals(24, pagina("anio=${e.anio}&contribuyente=${e.contribuyente}")["totalElements"].asInt())
        assertEquals(0, pagina("anio=${e.anio + 1}&predio=${e.predio}")["totalElements"].asInt())
        send("GET", "/api/srtm/predios/${e.predio}/arbitrios?anio=${e.anio}&zona=Z1", null, HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `two determinations of a predio at once write each cuota once`() {
        val e = Escenario()
        val respuestas =
            (1..2)
                .map { CompletableFuture.supplyAsync { exchange("POST", e.determinar(), pedido(e.anio)) } }
                .map { it.get() }
        assertTrue(respuestas.all { it.first in setOf(HttpStatus.CREATED, HttpStatus.OK) }, respuestas.toString())
        assertEquals(24, respuestas.sumOf { tree(it.second).size() })
        assertEquals(24, pagina("anio=${e.anio}&predio=${e.predio}")["totalElements"].asInt())
    }

    @Test
    fun `a contribuyente is determined predio by predio, and its view adds them up`() {
        val e = Escenario()
        val segundo = e.predioDeclarado(e.contribuyente)
        val cuotas = tree(send("POST", "/api/srtm/contribuyentes/${e.contribuyente}/arbitrios", pedido(e.anio), HttpStatus.CREATED))
        assertEquals(48, cuotas.size())
        val vista = tree(send("GET", "/api/srtm/contribuyentes/${e.contribuyente}/arbitrios?anio=${e.anio}", null, HttpStatus.OK))
        assertEquals(setOf(e.predio, segundo), lista(vista["predios"]) { it["predio"]["id"].asString() }.toSet())
        assertEquals(306.0, vista["total"].asDouble())
        assertEquals(LocalDate.now().toString(), vista["fecha_calculo"].asString())
    }

    @Test
    fun `a contribuyente with a predio that cannot be determined writes none, and the predio is named`() {
        val e = Escenario()
        val otro = e.predioDeclarado(e.contribuyente, "S${uniqueDocumento()}")
        val codigo = tree(send("GET", "/api/srtm/predios/$otro/arbitrios?anio=${e.anio}", null, HttpStatus.OK))["predio"]["codigo"].asString()
        val problema = tree(send("POST", "/api/srtm/contribuyentes/${e.contribuyente}/arbitrios", pedido(e.anio), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(lista(problema["faltan"]) { it.asString() }.all { it.startsWith("Predio $codigo: ") }, problema.toString())
        // faltan_detalle: the same messages, each still naming its own predio (not the contribuyente's)
        assertEquals(lista(problema["faltan"]) { it.asString() }, lista(problema["faltan_detalle"]) { it["mensaje"].asString() })
        assertTrue(lista(problema["faltan_detalle"]) { it["predio"].asString() }.all { it == otro }, problema.toString())
        assertEquals(0, pagina("anio=${e.anio}&contribuyente=${e.contribuyente}")["totalElements"].asInt())
    }

    @Test
    fun `the year's servicios and parameters`() {
        val e = Escenario()
        assertEquals(e.codigos, lista(tree(send("GET", "/api/srtm/arbitrios/servicios?anio=${e.anio}", null, HttpStatus.OK))) { it["codigo"].asString() })
        val p = tree(send("GET", "/api/srtm/arbitrios/parametros?anio=${e.anio}", null, HttpStatus.OK))
        assertEquals("000-${e.anio} (ficticia)", p["ordenanza"]["numero"].asString())
        assertEquals(listOf("TASA_ARBITRIO", "TASA_ARBITRIO", "ARBITRIO_ZONA", "ARBITRIO_USO"), lista(p["parametros"]) { it["tipo"].asString() })
        assertEquals(0, p["faltan"].size())

        val vacio = anioLibre()
        val nada = tree(send("GET", "/api/srtm/arbitrios/parametros?anio=$vacio", null, HttpStatus.OK))
        assertTrue(lista(nada["faltan"]) { it.asString() }.containsAll(listOf("Ordenanza de arbitrios $vacio", "TASA_ARBITRIO $vacio")), nada.toString())
    }

    @Test
    fun `a cuota refused midway leaves none of the predio's written`() {
        val e = Escenario()
        val escritas =
            comoAdmin {
                val contexto = arbitrios.contexto(e.anio)
                val predio = arbitrios.delPredio(srtm.rentas.Predio(id = e.predio, codigo = "X", sectorCatastral = e.sector), e.anio)
                val d = Arbitrios.determinar(contexto, predio, "Prueba del rollback", LocalDate.now())
                // the store refuses the fourth: the three before it were inserted in the same transaction
                val rota = d.copy(cuotas = d.cuotas.take(3) + d.cuotas[3].copy(periodo = 13))
                runCatching { arbitrios.guardar(e.anio, rota) }
            }
        assertTrue(escritas.exceptionOrNull() is ValidationException, escritas.toString())
        assertEquals(0, pagina("anio=${e.anio}&predio=${e.predio}")["totalElements"].asInt())
    }

    @Test
    fun `each servicio's zona can come from its own zona_arbitrio_ubicacion row, one row per servicio`() {
        val e = Escenario()
        val via = "JR UBICACION ${uniqueDocumento()}"
        val predio = e.predioDeclarado(e.contribuyente, via = via, numero = "100")
        val zonas = listOf("ZU1", "ZU2")
        val montos = listOf("7.00", "3.00")
        e.codigos.forEachIndexed { i, codigo ->
            // the tasa of each servicio's own zona: the escenario only has Z1's, the sector's
            e.parametro("TASA_ARBITRIO", "$codigo:${zonas[i]}:CASA", valor = montos[i])
            crear(
                ZONA_ARBITRIO_UBICACION,
                mapOf(
                    "servicio" to codigo,
                    "via" to via,
                    "numero_inicial" to 1,
                    "numero_final" to 999,
                    "paridad" to "AMBOS",
                    "zona" to zonas[i],
                    "vigencia_desde" to "${e.anio}-01-01",
                    "vigencia_hasta" to "${e.anio}-12-31",
                    "clave" to "ZU-${uniqueDocumento()}"
                )
            )
        }
        val cuotas = tree(send("POST", "/api/srtm/predios/$predio/arbitrios", pedido(e.anio), HttpStatus.CREATED))
        val porServicio = lista(cuotas) { it }.groupBy { it["servicio"].asString() }
        for (i in e.servicios.indices) {
            val delServicio = porServicio[e.servicios[i]]!!
            assertEquals(12, delServicio.size, porServicio.toString())
            assertTrue(delServicio.all { it["zona"].asString() == zonas[i] }, porServicio.toString())
            assertTrue(delServicio.all { it["monto"].asDouble() == montos[i].toDouble() }, porServicio.toString())
        }
    }

    // Core stores a DECIMAL exactly, with no implicit rounding: tasa_unitaria (4 decimales) × cantidad_base (2
    // decimales) leaves monto with 6, and CuotasInmutables' invariantes (which recompute the monto on insert) accept
    // it unchanged - the write guard's recomputation must not force a scale the ordinance never asked for
    @Test
    fun `without REDONDEO_ARBITRIO, monto equals tasa times área exactly - Core keeps every decimal`() {
        val e = Escenario()
        val sector = "S${uniqueDocumento()}"
        val servicio = e.codigos[0]
        e.parametro("ARBITRIO_ZONA", sector, texto = "ZD")
        e.parametro(Llaves.BASE_ARBITRIO, servicio, texto = "AREA_CONSTRUIDA_M2")
        e.parametro("TASA_ARBITRIO", "$servicio:ZD:CASA", valor = "0.1234")
        val predio =
            post(
                "/api/srtm/predios",
                mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to sector)
            )["id"].asString()
        crear(
            INAFECTACION_ARBITRIO,
            mapOf(
                "predio" to predio,
                "servicio" to e.servicios[1],
                "vigencia_desde" to "${e.anio}-01-01",
                "motivo" to "SIN SERVICIO",
                "observacion" to "De prueba"
            )
        )
        crear(
            "declaracion_predial",
            mapOf(
                "contribuyente" to e.contribuyente,
                "predio" to predio,
                "anio" to e.anio,
                "secuencia_uso" to "1",
                "porcentaje_condominio" to 100,
                "clase_uso" to "RESIDENCIAL",
                "sub_clase_uso" to "UNIFAMILIAR",
                "uso" to "CASA HABITACIÓN",
                "area_construida" to 12.34
            )
        )
        val cuotas = tree(send("POST", "/api/srtm/predios/$predio/arbitrios", pedido(e.anio), HttpStatus.CREATED))
        assertEquals(12, cuotas.size()) // one servicio alone (the other is inafecto here), 12 months
        val enero = tree(send("GET", "/api/objects/$CUOTA_ARBITRIO/records/${cuotas[0]["id"].asString()}", null, HttpStatus.OK))["attributes"]
        assertEquals(0, BigDecimal("1.522756").compareTo(enero["monto"].decimalValue())) // 0.1234 x 12.34, unrounded
        assertEquals("AREA_CONSTRUIDA_M2", enero["base"].asString())
        assertEquals(0, BigDecimal("12.34").compareTo(enero["cantidad_base"].decimalValue()))
        assertEquals(0, BigDecimal("0.1234").compareTo(enero["tasa_unitaria"].decimalValue()))
    }

    // TOPE_ARBITRIO (#87), fictitious 10 %: 120.00 paid the year before caps the escenario's 153.00 at 132.00. a predio
    // is registered when its first declaración (of any year) was presented, not when this year's was
    @Test
    fun `TOPE_ARBITRIO caps the year at what was paid the year before, by the predio's first declaración`() {
        val e = Escenario()
        val tope = e.parametro(Llaves.TOPE_ARBITRIO, "0101", valor = "10", texto = "${e.anio - 1}-12-31")

        // registered two years before, paid 120.00 last year, and its DJ of this year presented after the cut
        val antiguo = predioConTope(e, tope, primeraDj = e.anio - 2)
        val topadas = tree(send("POST", "/api/srtm/predios/$antiguo/arbitrios", pedido(e.anio), HttpStatus.CREATED))
        assertEquals(24, topadas.size())
        assertEquals(0, BigDecimal("132.00").compareTo(lista(topadas) { it["monto"].decimalValue() }.fold(BigDecimal.ZERO, BigDecimal::add)))
        assertEquals(0, BigDecimal("153.00").compareTo(lista(topadas) { it["monto_sin_tope"].decimalValue() }.fold(BigDecimal.ZERO, BigDecimal::add)))
        assertTrue(lista(topadas) { it["tope_aplicado"].decimalValue() }.all { it.compareTo(BigDecimal("10")) == 0 }, topadas.toString())
        // 8.50 and 4.25 × 132 / 153, rounded down (7.33 and 3.66), and the 0.12 left on december's last
        assertEquals(0, BigDecimal("3.78").compareTo(topadas[23]["monto"].decimalValue()), topadas[23].toString())

        // first registered this year, after the cut: no tope, though it too paid the year before
        val nuevo = predioConTope(e, tope, primeraDj = e.anio)
        val enteras = tree(send("POST", "/api/srtm/predios/$nuevo/arbitrios", pedido(e.anio), HttpStatus.CREATED))
        assertEquals(0, BigDecimal("153.00").compareTo(lista(enteras) { it["monto"].decimalValue() }.fold(BigDecimal.ZERO, BigDecimal::add)))
        assertTrue(lista(enteras) { it["tope_aplicado"] }.all { it == null || it.isNull }, enteras.toString())
    }

    // a predio of the escenario's sector with its DJ of the year presented on 1 february, one more DJ of primeraDj
    // (presented on its 10 march) when that is an earlier year, and 12 cuotas of 10.00 the year before
    private fun predioConTope(
        e: Escenario,
        parametro: String,
        primeraDj: Int
    ): String {
        val predio =
            post(
                "/api/srtm/predios",
                mapOf("codigo" to "T-${uniqueDocumento()}", "direccion" to "JR. LIMA 123", "tipo_predio" to "PREDIO URBANO", "sector_catastral" to e.sector)
            )["id"].asString()
        val djs = if (primeraDj < e.anio) listOf(primeraDj to "$primeraDj-03-10", e.anio to "${e.anio}-02-01") else listOf(e.anio to "${e.anio}-02-01")
        for ((anio, presentada) in djs) {
            crear(
                "declaracion_predial",
                mapOf(
                    "contribuyente" to e.contribuyente,
                    "predio" to predio,
                    "anio" to anio,
                    "secuencia_uso" to "1",
                    "porcentaje_condominio" to 100,
                    "clase_uso" to "RESIDENCIAL",
                    "sub_clase_uso" to "UNIFAMILIAR",
                    "uso" to "CASA HABITACIÓN",
                    "fecha_presentacion" to presentada
                )
            )
        }
        val antes = e.anio - 1
        for (mes in 1..12) {
            crear(
                CUOTA_ARBITRIO,
                mapOf(
                    "predio" to predio,
                    "contribuyente" to e.contribuyente,
                    "servicio" to e.servicios[0],
                    "parametro" to parametro,
                    "anio" to antes,
                    "periodo" to mes,
                    "monto" to "10.00",
                    "parametro_aplicado" to "TASA_ARBITRIO:${e.codigos[0]}:Z1:CASA",
                    "secuencia_uso" to "001",
                    "fecha_calculo" to "$antes-01-10",
                    "observacion" to "Determinado el año anterior",
                    "clave" to claveDeCuota(predio, e.servicios[0], antes, mes, "001")
                )
            )
        }
        return predio
    }

    @Test
    fun `the app indexes its cuotas by year and predio, and by year and contribuyente`() {
        val e = Escenario()
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val nombres =
            runBlocking {
                db
                    .sql("SELECT string_agg(indexname, ',') AS n FROM pg_indexes WHERE indexname LIKE 'cuota_arbitrio_%'")
                    .map { row, _ -> row.get("n", String::class.java) ?: "" }
                    .awaitSingle()
            }.split(",")
        assertTrue(nombres.any { it.endsWith("_predio") } && nombres.any { it.endsWith("_contribuyente") }, nombres.toString())
    }
}
