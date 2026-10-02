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
    }

    @Test
    fun `what is missing is a 422 that names it, and nothing is written`() {
        val e = Escenario()
        val sinZona = "S${uniqueDocumento()}"
        val otro = e.predioDeclarado(inscribir(), sinZona)
        val problema = tree(send("POST", "/api/srtm/predios/$otro/arbitrios", pedido(e.anio), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals(listOf("ARBITRIO_ZONA $sinZona ${e.anio}"), lista(problema["faltan"]) { it.asString() })
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

        val clave = "${e.predio}|${e.servicios[0]}|${e.anio}|13|1"
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
