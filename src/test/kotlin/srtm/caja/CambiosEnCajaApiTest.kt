package srtm.caja

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.awaitRowsUpdated
import srtm.arbitrios.DETERMINACION_LOTE
import srtm.arbitrios.DETERMINACION_MASIVA
import srtm.arbitrios.Llaves
import srtm.emision.TablasCore
import srtm.emision.TrabajadoresEmision
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.nio.file.Files

// #164: a write of srtm that changes a debt already sent to caja replaces its ordenes there (an alta with sustituye_a)
// or withdraws them, at once, and says what it did in the Srtm-Caja header. a contribuyente without ordenes of the year
// is not synced, and the write never fails for caja
class CambiosEnCajaApiTest : ConCajaApiTest() {
    @Autowired
    lateinit var tablas: TablasCore

    @Autowired
    lateinit var trabajadores: TrabajadoresEmision

    // with the escenario's first servicio charged per m² construido and a piso of 100 m², a nivel of 20 m² more
    // changes its twelve months: only those are replaced, the other servicio's are not touched
    @Test
    fun `pisoNuevoSustituyeSoloLosMesesQueCambian`() {
        val e = Escenario()
        e.parametro(Llaves.BASE_ARBITRIO, e.codigos[0], texto = "AREA_CONSTRUIDA_M2")
        val dj = declaracionDe(e)
        escribir("POST", "/api/srtm/declaraciones/$dj/niveles", NIVEL, HttpStatus.CREATED)
        val cuotas = lista(tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))) { it }
        enviar(e.contribuyente, e.anio)
        val referencia = { q: JsonNode -> "ARB-${q["clave"].asString()}" }
        val porArea = cuotas.filter { it["servicio"].asString() == e.servicios[0] }.map(referencia)
        val otras = cuotas.filter { it["servicio"].asString() == e.servicios[1] }.map(referencia)
        PEDIDOS.clear()

        val (_, aviso) = escribir("POST", "/api/srtm/declaraciones/$dj/niveles", NIVEL + mapOf("numero_piso" to 2, "area_construida" to 20), HttpStatus.CREATED)

        val altas = PEDIDOS.filter { it.alta }
        assertEquals(porArea.toSet(), altas.map { it.cuerpo["sustituye_a"] }.toSet())
        assertEquals(12, altas.size)
        // the escenario's declaración came in through core's api, as an import: it has no número
        assertTrue(altas.all { it.cuerpo["observacion"] == "Redeterminación por la DJ del padrón" }, altas.first().cuerpo.toString())
        // the months that stay the same, the predial (the autoavalúo is the user's) and retiros: nothing
        assertTrue(PEDIDOS.none { !it.alta || it.referencia.startsWith("PREDIAL-") || it.cuerpo["sustituye_a"] in otras })
        assertEquals(CLAVES, aviso!!.propertyNames().toList())
        assertEquals(listOf(12, 12, 12, 0, 0, 0, 0), numeros(aviso), aviso.toString())
        assertTrue(aviso["error"].isNull, aviso.toString())
        val o = ordenes(e.contribuyente, e.anio)
        assertEquals(List(12) { EstadoOrdenCaja.RETIRADA }, porArea.map { o.getValue(it)["estado"].asString() })
        assertEquals(List(12) { EstadoOrdenCaja.ENVIADA }, otras.map { o.getValue(it)["estado"].asString() })
    }

    // what the predial reads changed and no base of the arbitrios: its 4 cuotas are replaced all the same
    @Test
    fun `autoavaluoNuevoSustituyeLas4DePredial`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        PEDIDOS.clear()

        val aviso = cambiarAutoavaluo(dj, 80000)

        val altas = PEDIDOS.filter { it.alta }.sortedBy { it.referencia }
        assertEquals(referencias(e.contribuyente, e.anio, 2), altas.map { it.referencia })
        assertEquals(referencias(e.contribuyente, e.anio, 1), altas.map { it.cuerpo["sustituye_a"] })
        assertTrue(altas.all { it.cuerpo["observacion"] == "Redeterminación por la DJ n° ${numeroDe(dj)}" }, altas.first().cuerpo.toString())
        assertEquals(listOf(4, 4, 4, 0, 0, 0, 0), numeros(aviso!!), aviso.toString())
    }

    // the rectificatoria with the same data changes no cuota: caja gets nothing. its next edit replaces the year's
    // predial, as the rectificatoria's
    @Test
    fun `rectificatoriaSinCambiosNoEnviaYSuEdicionSustituyeElPredial`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        PEDIDOS.clear()

        val (ficha, igual) = escribir("POST", "/api/srtm/declaraciones/$dj/rectificar", emptyMap<String, Any>(), HttpStatus.CREATED)

        assertTrue(PEDIDOS.isEmpty())
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0), numeros(igual!!), igual.toString())
        val r = tree(ficha)["declaracion"]["id"].asString()

        val aviso = cambiarAutoavaluo(r, 80000)

        val altas = PEDIDOS.filter { it.alta }.sortedBy { it.referencia }
        assertEquals(referencias(e.contribuyente, e.anio, 2), altas.map { it.referencia })
        assertEquals(referencias(e.contribuyente, e.anio, 1), altas.map { it.cuerpo["sustituye_a"] })
        assertTrue(altas.all { it.cuerpo["observacion"] == "Rectificatoria n° ${numeroDe(r)}" }, altas.first().cuerpo.toString())
        assertEquals(4, aviso!!["sustituidas"].asInt(), aviso.toString())
    }

    // without the declaración there is no predial: its ordenes are withdrawn, and nothing is sent in their place
    @Test
    fun `anularLaDjRetiraYNoDaDeAlta`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        PEDIDOS.clear()

        val (_, aviso) = escribir("POST", "/api/srtm/declaraciones/$dj/anular", mapOf("motivo_anulacion" to "Declarada dos veces"))

        assertTrue(PEDIDOS.none { it.alta })
        val v1 = referencias(e.contribuyente, e.anio, 1)
        assertEquals(v1, PEDIDOS.map { it.referencia }.sorted())
        assertTrue(PEDIDOS.all { it.cuerpo["motivo"] == "Anulación de la DJ n° ${numeroDe(dj)}" }, PEDIDOS.first().cuerpo.toString())
        assertEquals(listOf(0, 0, 0, 4, 0, 0, 0), numeros(aviso!!), aviso.toString())
        val o = ordenes(e.contribuyente, e.anio)
        assertEquals(List(4) { EstadoOrdenCaja.RETIRADA }, v1.map { o.getValue(it)["estado"].asString() })
    }

    @Test
    fun `anularUnaCuotaRetiraSuOrden`() {
        val e = Escenario()
        val cuota = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))[0]
        enviar(e.contribuyente, e.anio)
        PEDIDOS.clear()

        val (_, aviso) =
            escribir(
                "POST",
                "/api/srtm/arbitrios/cuotas/${cuota["id"].asString()}/anulacion",
                mapOf("motivo" to "Uso mal declarado", "observacion" to "Informe de fiscalización de prueba"),
                HttpStatus.CREATED
            )

        val referencia = "ARB-${cuota["clave"].asString()}"
        assertEquals(listOf(referencia), PEDIDOS.map { it.referencia })
        assertTrue(!PEDIDOS.single().alta)
        assertEquals("Anulación de la cuota de arbitrios", PEDIDOS.single().cuerpo["motivo"])
        assertEquals(1, aviso!!["retiradas"].asInt(), aviso.toString())
        assertEquals(EstadoOrdenCaja.RETIRADA, ordenes(e.contribuyente, e.anio).getValue(referencia)["estado"].asString())
    }

    // a predio's year determined after its contribuyente's ordenes were sent: its cuotas go to caja with it
    @Test
    fun `determinarArbitriosLosDaDeAlta`() {
        val e = Escenario()
        conPredial(e)
        enviar(e.contribuyente, e.anio)
        PEDIDOS.clear()

        val (cuerpo, aviso) = escribir("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)

        val cuotas = lista(tree(cuerpo)) { "ARB-${it["clave"].asString()}" }
        assertEquals(cuotas.toSet(), PEDIDOS.filter { it.alta }.map { it.referencia }.toSet())
        assertTrue(PEDIDOS.all { it.cuerpo["observacion"] == "Determinación de arbitrios" && it.cuerpo["sustituye_a"] == null })
        assertEquals(listOf(24, 24, 0, 0, 0, 0, 0), numeros(aviso!!), aviso.toString())
    }

    // a masiva's determination only queues (the masiva does not wait on caja): the loop sends them
    @Test
    fun `determinacionMasivaSoloEncola`() {
        val e = Escenario()
        conPredial(e)
        enviar(e.contribuyente, e.anio)
        soloLaDelTest()
        PEDIDOS.clear()

        val id = tree(send("POST", MASIVAS, pedido(e.anio), HttpStatus.ACCEPTED))["id"].asString()
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        val grupo = trabajadores.grupo("caja-masiva", 1, Files.createTempDirectory("caja-masiva"))
        grupo.iniciar()
        try {
            esperar(id) { it["estado"].asString() == "TERMINADA" }
        } finally {
            runBlocking { grupo.detener() }
        }

        assertTrue(PEDIDOS.isEmpty(), PEDIDOS.map { it.referencia }.toString())
        val arbitrios = registrosDe(e.contribuyente).filter { it["referencia"].asString().startsWith("ARB-") }
        assertEquals(List(24) { EstadoOrdenCaja.POR_ENVIAR }, arbitrios.map { it["estado"].asString() })
        vueltas()
        val altas = PEDIDOS.filter { it.alta && it.referencia.startsWith("ARB-") }
        assertEquals(24, altas.size)
        assertTrue(altas.all { it.cuerpo["observacion"] == "Determinación de arbitrios" })
    }

    // D6: caja answers 409 to a retiro of an orden that was paid there
    @Test
    fun `retiroDeOrdenPagadaQuedaPagadaEnCaja`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = referencias(e.contribuyente, e.anio, 1)
        respuesta = { if (!it.alta && it.referencia == v1[0]) 409 to """{"detail":"Pagada con el recibo 001-000123"}""" else null }

        val (_, aviso) = escribir("POST", "/api/srtm/declaraciones/$dj/anular", mapOf("motivo_anulacion" to "Declarada dos veces"))

        assertEquals(1, aviso!!["pagadas_en_caja"].asInt(), aviso.toString())
        assertEquals(3, aviso["retiradas"].asInt(), aviso.toString())
        val o = ordenes(e.contribuyente, e.anio).getValue(v1[0])
        assertEquals(EstadoOrdenCaja.PAGADA_EN_CAJA, o["estado"].asString())
        assertTrue(o["pagada"].asBoolean())
    }

    // D6: caja answers 409 to an alta that replaces an orden paid there
    @Test
    fun `sustitucionDeOrdenPagadaQuedaEnConflicto`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        val v1 = referencias(e.contribuyente, e.anio, 1)
        respuesta = { if (it.alta && it.cuerpo["sustituye_a"] == v1[0]) 409 to """{"detail":"La orden que sustituye ya está pagada"}""" else null }

        val aviso = cambiarAutoavaluo(dj, 80000)

        assertEquals(1, aviso!!["conflictos"].asInt(), aviso.toString())
        assertEquals(3, aviso["sustituidas"].asInt(), aviso.toString())
        assertEquals("La orden que sustituye ya está pagada", aviso["error"].asString())
        val o = ordenes(e.contribuyente, e.anio)
        assertEquals(EstadoOrdenCaja.CONFLICTO, o.getValue(referencias(e.contribuyente, e.anio, 2)[0])["estado"].asString())
        assertEquals(EstadoOrdenCaja.ENVIADA, o.getValue(v1[0])["estado"].asString())
    }

    // a condómino's autoavalúo is the whole condominio's (#157): the predial of both is replaced, in one write. a piso
    // does it where catastro values the predio; here the autoavalúo is the user's
    @Test
    fun `pisoEnCondominioSustituyeElPredialDeAmbos`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        val otro = inscribir()
        post("/api/srtm/declaraciones/$dj/condominos", mapOf("contribuyente" to otro, "porcentaje_condominio" to 40))
        enviar(e.contribuyente, e.anio)
        enviar(otro, e.anio)
        PEDIDOS.clear()

        val aviso = cambiarAutoavaluo(dj, 80000)

        for (c in listOf(e.contribuyente, otro)) {
            val altas = altasDe(documentoDe(c)).sortedBy { it.referencia }
            assertEquals(referencias(c, e.anio, 2), altas.map { it.referencia }, c)
            assertEquals(referencias(c, e.anio, 1), altas.map { it.cuerpo["sustituye_a"] }, c)
        }
        assertEquals(8, aviso!!["sustituidas"].asInt(), aviso.toString())
    }

    @Test
    fun `sinOrdenesEnviadasNoLlamaACaja`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        val cuota = tree(send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED))[0]

        assertNull(cambiarAutoavaluo(dj, 80000))
        val (_, anulada) =
            escribir(
                "POST",
                "/api/srtm/arbitrios/cuotas/${cuota["id"].asString()}/anulacion",
                mapOf("motivo" to "Uso mal declarado", "observacion" to "Informe de fiscalización de prueba"),
                HttpStatus.CREATED
            )

        assertNull(anulada)
        assertTrue(PEDIDOS.isEmpty())
        assertTrue(registrosDe(e.contribuyente).isEmpty())
    }

    @Test
    fun `cajaCaidaNoFallaLaDj`() {
        val e = Escenario()
        val dj = conPredial(e)!!
        enviar(e.contribuyente, e.anio)
        respuesta = { if (it.alta) 503 to """{"detail":"caída"}""" else null }

        val aviso = cambiarAutoavaluo(dj, 80000)

        assertTrue(aviso!!["pendientes"].asInt() > 0, aviso.toString())
        assertTrue(aviso["error"].asString().contains("503"), aviso.toString())
        val guardada = tree(send("GET", "/api/srtm/declaraciones/$dj", null, HttpStatus.OK))["declaracion"]
        assertEquals(0, guardada["valor_autoavaluo"].decimalValue().compareTo(BigDecimal(80000)), guardada.toString())

        respuesta = { null }
        vueltas()

        val o = ordenes(e.contribuyente, e.anio)
        assertEquals(List(4) { EstadoOrdenCaja.ENVIADA }, referencias(e.contribuyente, e.anio, 2).map { o.getValue(it)["estado"].asString() })
    }

    // nothing of another class or run is left for this test's group to take: only its masiva runs
    private fun soloLaDelTest() {
        runBlocking {
            for (t in tablas.de(DETERMINACION_LOTE, listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDO' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO')")
                    .fetch()
                    .awaitRowsUpdated()
            }
            for (t in tablas.de(DETERMINACION_MASIVA, listOf("estado"))) {
                db
                    .sql(
                        "UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDA' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO', 'ENSAMBLANDO')"
                    ).fetch()
                    .awaitRowsUpdated()
            }
        }
    }

    private fun esperar(
        id: String,
        listo: (JsonNode) -> Boolean
    ): JsonNode {
        val limite = System.nanoTime() + 90_000_000_000L
        while (true) {
            val job = tree(send("GET", "$MASIVAS/$id", null, HttpStatus.OK))
            if (listo(job)) return job
            assertTrue(System.nanoTime() < limite, "la determinación masiva no avanza: $job")
            Thread.sleep(200)
        }
    }

    // the escenario's declaración
    private fun declaracionDe(e: Escenario): String =
        tree(send("GET", "/api/objects/declaracion_predial/records?predio=${e.predio}", null, HttpStatus.OK))["content"].single()["id"].asString()

    private fun numeroDe(dj: String) = tree(send("GET", "/api/srtm/declaraciones/$dj", null, HttpStatus.OK))["declaracion"]["numero_declaracion"].asString()

    private fun referencias(
        c: String,
        anio: Int,
        version: Int
    ) = (1..4).map { "PREDIAL-$c-$anio-C$it-v$version" }

    // the header's counts, in its order
    private fun numeros(aviso: JsonNode) = CLAVES.dropLast(1).map { aviso[it].asInt() }

    private companion object {
        const val MASIVAS = "/api/srtm/arbitrios/determinaciones"

        val CLAVES = listOf("encoladas", "enviadas", "sustituidas", "retiradas", "pagadas_en_caja", "conflictos", "pendientes", "error")

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
