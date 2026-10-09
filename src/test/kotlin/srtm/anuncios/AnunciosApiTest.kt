package srtm.anuncios

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import srtm.rentas.SrtmApiTest
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// the anuncios' api on the real model and PostGIS (SPEC §7, Anuncios): the alta with its tasa and number, the
// Idempotency-Key, the three acts, the padrón at a cut-off, the guard and the permissions. every tasa is FICTITIOUS,
// loaded in a past year no other test uses (an act is never dated in the future)
class AnunciosApiTest : SrtmApiTest() {
    @Test
    fun `an anuncio is registered with its authorization, which accrues the clase's tasa of the year`() {
        val e = Escenario()
        // a tasa in the body is ignored: the backend reads it
        val (status, cuerpo) = alta(e, mapOf("tasa" to 999))
        assertEquals(HttpStatus.CREATED, status, cuerpo.toString())
        assertFalse(cuerpo["ya_existia"].asBoolean())
        val anuncio = cuerpo["anuncio"]
        val id = anuncio["id"].asString()
        assertEquals(e.anio, anuncio["anio"].asInt())
        assertEquals(numeroDeAnuncio(e.anio, anuncio["correlativo"].asInt()), anuncio["numero"].asString())
        assertEquals("JR. LIMA 123", anuncio["direccion"].asString())
        assertEquals("FACHADA", anuncio["emplazamiento"].asString())
        assertEquals(e.contribuyente, anuncio["contribuyente"].asString())
        assertEquals(e.predio, anuncio["predio"].asString())
        val m = cuerpo["movimiento"]
        assertEquals(AUTORIZACION, m["tipo"].asString())
        assertEquals("${e.anio}-03-02", m["fecha"].asString())
        assertEquals(e.anio, m["anio"].asInt())
        assertEquals("ANUNCIO-$id-${e.anio}", m["referencia_cargo"].asString())
        assertEquals("$id|AUTORIZACION", m["clave"].asString())
        assertDinero("12.50", m["tasa"])
        assertEquals(e.panel, m["parametro"].asString())
        assertEquals(id, m["anuncio"].asString())

        // the ficha: today's estado (its vigencia ended), what it accrued, and the acts it admits
        val hoy = LocalDate.now().toString()
        val ficha = ficha(id)
        assertEquals(VENCIDO, ficha["estado"].asString())
        assertEquals("${e.anio}-12-31", ficha["vigencia_hasta_vigente"].asString())
        assertEquals(hoy, ficha["al_dia"].asString())
        assertDinero("12.50", ficha["devengado"]["importe"])
        assertEquals(hoy, ficha["devengado"]["al_dia"].asString())
        assertEquals(1, ficha["movimientos"].size())
        assertTrue(ficha["acciones"]["renovacion"]["permitida"].asBoolean())
        assertTrue(ficha["acciones"]["renovacion"]["motivo"].isNull)
        assertTrue(ficha["acciones"]["cese"]["permitida"].asBoolean())
        assertFalse(ficha["acciones"]["retiro"]["permitida"].asBoolean())
        assertTrue(ficha["acciones"]["retiro"]["motivo"].asString().contains("cese"))

        // the titular's and the predio's
        for (de in listOf("contribuyentes/${e.contribuyente}", "predios/${e.predio}")) {
            val suyos = tree(send("GET", "/api/srtm/$de/anuncios", null, HttpStatus.OK))
            assertEquals(hoy, suyos["al_dia"].asString())
            assertEquals(listOf(id), lista(suyos["anuncios"]) { it["id"].asString() })
            assertEquals(VENCIDO, suyos["anuncios"][0]["estado"].asString())
            assertEquals(anuncio["numero"].asString(), suyos["anuncios"][0]["numero"].asString())
        }
        send("GET", "/api/srtm/contribuyentes/${UUID.randomUUID()}/anuncios", null, HttpStatus.NOT_FOUND)
        send("GET", "/api/srtm/anuncios/${UUID.randomUUID()}", null, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a clase without tasa that year is a 422 that names it, and nothing is written`() {
        val e = Escenario()
        val antes = cuantos(ANUNCIO, "anio=${e.anio}")
        val (status, problema) = alta(e, mapOf("clase" to "TOLDO"), clave = "k-${UUID.randomUUID()}")
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, status, problema.toString())
        assertEquals(listOf(Llaves.falta("TOLDO", e.anio)), lista(problema["faltan"]) { it.asString() })
        assertEquals(antes, cuantos(ANUNCIO, "anio=${e.anio}"))
        assertEquals(0, tree(send("GET", "/api/srtm/contribuyentes/${e.contribuyente}/anuncios", null, HttpStatus.OK))["anuncios"].size())
    }

    @Test
    fun `the alta refuses what it cannot register - each field named`() {
        val e = Escenario()
        val (malo, problema) = alta(e, mapOf("area" to 0, "fecha_autorizacion" to "${e.anio}-13-01", "clase" to "VALLA", "observacion" to "x"))
        assertEquals(HttpStatus.BAD_REQUEST, malo, problema.toString())
        assertEquals(setOf("area", "fecha_autorizacion", "clase", "observacion"), lista(problema["errors"]) { it["field"].asString() }.toSet())
        assertEquals(HttpStatus.NOT_FOUND, alta(e, mapOf("contribuyente" to UUID.randomUUID().toString())).first)
        assertEquals(HttpStatus.NOT_FOUND, alta(e, mapOf("predio" to UUID.randomUUID().toString())).first)
        // never dated in the future
        val (futura, porQue) = alta(e, mapOf("fecha_autorizacion" to LocalDate.now().plusDays(1).toString(), "vigencia_hasta" to null))
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, futura, porQue.toString())
        assertEquals(0, cuantos(ANUNCIO, "contribuyente=${e.contribuyente}"))
    }

    @Test
    fun `the same Idempotency-Key answers the first registration - 200, nothing accrued again`() {
        val e = Escenario()
        val clave = "k-${UUID.randomUUID()}"
        val (primera, uno) = alta(e, clave = clave)
        assertEquals(HttpStatus.CREATED, primera)
        // the resubmission, even with another body
        val (segunda, dos) = alta(e, mapOf("denominacion" to "OTRA"), clave = clave)
        assertEquals(HttpStatus.OK, segunda, dos.toString())
        assertTrue(dos["ya_existia"].asBoolean())
        assertEquals(uno["anuncio"]["id"].asString(), dos["anuncio"]["id"].asString())
        assertEquals(uno["movimiento"]["id"].asString(), dos["movimiento"]["id"].asString())
        assertEquals(clave, dos["anuncio"]["clave_idempotencia"].asString())
        assertEquals(1, cuantos(ANUNCIO, "contribuyente=${e.contribuyente}"))
        assertEquals(1, ficha(uno["anuncio"]["id"].asString())["movimientos"].size())
        // without a key, each POST is another anuncio
        assertEquals(HttpStatus.CREATED, alta(e).first)
        assertEquals(HttpStatus.CREATED, alta(e).first)
        assertEquals(3, cuantos(ANUNCIO, "contribuyente=${e.contribuyente}"))
    }

    @Test
    fun `ten registrations at once take ten numbers of the year, one after the other`() {
        val e = Escenario()
        val respuestas = aLaVez(10) { alta(e, clave = "k-${UUID.randomUUID()}") }
        respuestas.forEach { (status, cuerpo) -> assertEquals(HttpStatus.CREATED, status, cuerpo.toString()) }
        val correlativos = respuestas.map { it.second["anuncio"]["correlativo"].asInt() }.sorted()
        assertEquals((1..10).toList(), correlativos, "the year is this test's: its numbering starts at 1")
        assertEquals(10, respuestas.map { it.second["anuncio"]["numero"].asString() }.toSet().size)
    }

    @Test
    fun `the same key sent twice at once registers one anuncio`() {
        val e = Escenario()
        val clave = "k-${UUID.randomUUID()}"
        val respuestas = aLaVez(2) { alta(e, clave = clave) }
        assertEquals(setOf(HttpStatus.CREATED, HttpStatus.OK), respuestas.map { it.first }.toSet(), respuestas.toString())
        assertEquals(1, respuestas.map { it.second["anuncio"]["id"].asString() }.toSet().size)
        assertEquals(1, cuantos(ANUNCIO, "contribuyente=${e.contribuyente}"))
    }

    @Test
    fun `a renewal accrues the ejercicio it renews, with that year's tasa - once`() {
        val e = Escenario()
        val id = registrado(e)
        val renovacion = mapOf("fecha" to "${e.anio}-12-15", "vigencia_hasta" to "${e.anio + 1}-12-31", "observacion" to "Renovación anticipada")
        val m = post("/api/srtm/anuncios/$id/renovacion", renovacion)
        assertEquals(RENOVACION, m["tipo"].asString())
        assertEquals(e.anio + 1, m["anio"].asInt())
        assertEquals("ANUNCIO-$id-${e.anio + 1}", m["referencia_cargo"].asString())
        assertEquals("$id|RENOVACION|${e.anio + 1}", m["clave"].asString())
        assertDinero("15.00", m["tasa"])
        assertEquals(e.panelSiguiente, m["parametro"].asString())
        assertEquals("${e.anio + 1}-12-31", m["vigencia_hasta"].asString())

        // that ejercicio is accrued already
        send("POST", "/api/srtm/anuncios/$id/renovacion", renovacion + ("fecha" to "${e.anio}-12-20"), HttpStatus.CONFLICT)
        val ficha = ficha(id)
        assertEquals(2, ficha["movimientos"].size())
        assertDinero("27.50", ficha["devengado"]["importe"])
        assertEquals("${e.anio + 1}-12-31", ficha["vigencia_hasta_vigente"].asString())

        // several ejercicios, a vigencia that goes back, before the authorization, in the future: 422
        val otro = registrado(e)
        val ruta = "/api/srtm/anuncios/$otro/renovacion"
        val porQue = "Renovación de prueba"
        // the dates of a 422 read dd/MM/yyyy
        val varios =
            tree(
                send(
                    "POST",
                    ruta,
                    mapOf("fecha" to "${e.anio}-12-15", "vigencia_hasta" to "${e.anio + 2}-12-31", "observacion" to porQue),
                    HttpStatus.UNPROCESSABLE_CONTENT
                )
            )
        assertTrue(varios["detail"].asString().contains("al 31/12/${e.anio + 2}"), varios.toString())
        val atras =
            tree(
                send(
                    "POST",
                    ruta,
                    mapOf("fecha" to "${e.anio}-12-15", "vigencia_hasta" to "${e.anio}-12-01", "observacion" to porQue),
                    HttpStatus.UNPROCESSABLE_CONTENT
                )
            )
        assertTrue(atras["detail"].asString().contains("del 15/12/${e.anio} no puede vencer el 01/12/${e.anio}"), atras.toString())
        send("POST", ruta, mapOf("fecha" to "${e.anio}-03-01", "observacion" to porQue), HttpStatus.UNPROCESSABLE_CONTENT)
        send("POST", ruta, mapOf("fecha" to LocalDate.now().plusDays(1).toString(), "observacion" to porQue), HttpStatus.UNPROCESSABLE_CONTENT)
        tree(send("POST", ruta, mapOf("fecha" to "${e.anio}-12-15", "observacion" to "x"), HttpStatus.BAD_REQUEST))
        send("POST", "/api/srtm/anuncios/${UUID.randomUUID()}/renovacion", mapOf("observacion" to porQue), HttpStatus.NOT_FOUND)
        assertEquals(1, ficha(otro)["movimientos"].size())

        // a year without the clase's tasa: 422 that names it, nothing written
        val letrero = registrado(e, mapOf("clase" to "LETRERO"))
        val problema =
            tree(
                send(
                    "POST",
                    "/api/srtm/anuncios/$letrero/renovacion",
                    mapOf("fecha" to "${e.anio}-12-15", "vigencia_hasta" to "${e.anio + 1}-12-31", "observacion" to porQue),
                    HttpStatus.UNPROCESSABLE_CONTENT
                )
            )
        assertEquals(listOf(Llaves.falta("LETRERO", e.anio + 1)), lista(problema["faltan"]) { it.asString() })
        assertEquals(1, ficha(letrero)["movimientos"].size())
    }

    @Test
    fun `two renewals of the same ejercicio at once - one is written`() {
        val e = Escenario()
        val id = registrado(e)
        val renovacion = mapOf("fecha" to "${e.anio}-12-15", "vigencia_hasta" to "${e.anio + 1}-12-31", "observacion" to "Renovación a la vez")
        val respuestas = aLaVez(2) { exchange("POST", "/api/srtm/anuncios/$id/renovacion", renovacion) }
        assertEquals(listOf(201, 409), respuestas.map { it.first.value() }.sorted(), respuestas.toString())
        assertTrue(respuestas.single { it.first == HttpStatus.CONFLICT }.second.contains("ya está devengado"), respuestas.toString())
        assertEquals(1, lista(ficha(id)["movimientos"]) { it["tipo"].asString() }.count { it == RENOVACION })
    }

    @Test
    fun `the cese and the retiro - once each, the retiro after the cese, in order`() {
        val e = Escenario()
        val id = registrado(e)
        val ruta = "/api/srtm/anuncios/$id"

        fun baja(fecha: String) = mapOf("fecha" to fecha, "motivo" to "Cerró el local", "observacion" to "Baja de prueba")

        send("POST", "$ruta/retiro", baja("${e.anio}-07-01"), HttpStatus.UNPROCESSABLE_CONTENT)
        send("POST", "$ruta/cese", baja("${e.anio}-03-01"), HttpStatus.UNPROCESSABLE_CONTENT)
        send("POST", "$ruta/cese", baja(LocalDate.now().plusDays(1).toString()), HttpStatus.UNPROCESSABLE_CONTENT)
        rejected("POST", "$ruta/cese", baja("${e.anio}-06-30") - "motivo", "motivo")

        val cese = post("$ruta/cese", baja("${e.anio}-06-30"))
        assertEquals(CESE, cese["tipo"].asString())
        assertEquals("Cerró el local", cese["motivo"].asString())
        assertEquals("$id|CESE", cese["clave"].asString())
        assertTrue(cese["referencia_cargo"].isNull && cese["tasa"].isNull && cese["vigencia_hasta"].isNull, cese.toString())
        send("POST", "$ruta/cese", baja("${e.anio}-07-01"), HttpStatus.CONFLICT)
        // a cesado anuncio is not renewed: the cese stops the debt to come
        send("POST", "$ruta/renovacion", mapOf("fecha" to "${e.anio}-12-15", "observacion" to "Renovación de prueba"), HttpStatus.UNPROCESSABLE_CONTENT)

        val cesado = ficha(id)
        assertEquals(CESADO, cesado["estado"].asString())
        assertFalse(cesado["acciones"]["renovacion"]["permitida"].asBoolean())
        assertFalse(cesado["acciones"]["cese"]["permitida"].asBoolean())
        assertTrue(cesado["acciones"]["retiro"]["permitida"].asBoolean())
        // what it accrued is not undone
        assertDinero("12.50", cesado["devengado"]["importe"])

        // the retiro, not before the cese
        send("POST", "$ruta/retiro", baja("${e.anio}-06-29"), HttpStatus.UNPROCESSABLE_CONTENT)
        val retiro = post("$ruta/retiro", baja("${e.anio}-07-15"))
        assertEquals(RETIRO, retiro["tipo"].asString())
        send("POST", "$ruta/retiro", baja("${e.anio}-07-16"), HttpStatus.CONFLICT)
        val retirado = ficha(id)
        assertEquals(RETIRADO, retirado["estado"].asString())
        assertEquals(listOf(AUTORIZACION, CESE, RETIRO), lista(retirado["movimientos"]) { it["tipo"].asString() })
        listOf("renovacion", "cese", "retiro").forEach { assertFalse(retirado["acciones"][it]["permitida"].asBoolean(), it) }
    }

    @Test
    fun `the padron says each anuncio's estado at its cut-off - one authorized later did not exist yet`() {
        val e = Escenario()
        val cesado = registrado(e, mapOf("denominacion" to "CESADO ${e.anio}"))
        post("/api/srtm/anuncios/$cesado/cese", mapOf("fecha" to "${e.anio}-06-30", "motivo" to "Cerró el local", "observacion" to "Baja de prueba"))
        val tardio = registrado(e, mapOf("fecha_autorizacion" to "${e.anio}-08-01"))
        val sinPlazo = registrado(e, mapOf("fecha_autorizacion" to "${e.anio}-03-05", "vigencia_hasta" to null))

        fun padron(consulta: String): Map<String, JsonNode> {
            val pagina = tree(send("GET", "/api/srtm/anuncios?contribuyente=${e.contribuyente}&$consulta", null, HttpStatus.OK))
            return lista(pagina["content"]) { it }.associateBy { it["id"].asString() }
        }

        val mayo = padron("vigentes_a=${e.anio}-05-01")
        assertEquals(setOf(cesado, sinPlazo), mayo.keys)
        assertEquals(VIGENTE, mayo.getValue(cesado)["estado"].asString())
        assertEquals("${e.anio}-05-01", mayo.getValue(cesado)["vigentes_a"].asString())
        assertEquals("FLORES OTINIANO JUNIOR", mayo.getValue(cesado)["contribuyente_nombre"].asString())

        val julio = padron("vigentes_a=${e.anio}-07-01")
        assertEquals(CESADO, julio.getValue(cesado)["estado"].asString())
        assertEquals(VIGENTE, julio.getValue(sinPlazo)["estado"].asString())

        val enero = padron("vigentes_a=${e.anio + 1}-01-15")
        assertEquals(setOf(cesado, tardio, sinPlazo), enero.keys)
        assertEquals(VENCIDO, enero.getValue(tardio)["estado"].asString())
        assertEquals("${e.anio}-12-31", enero.getValue(tardio)["vigencia_hasta_vigente"].asString())
        assertEquals(VIGENTE, enero.getValue(sinPlazo)["estado"].asString())
        assertTrue(enero.getValue(sinPlazo)["vigencia_hasta_vigente"].isNull)

        // filtered by its estado, its clase, its text
        assertEquals(setOf(tardio), padron("vigentes_a=${e.anio + 1}-01-15&estado=VENCIDO").keys)
        assertEquals(setOf(cesado), padron("vigentes_a=${e.anio + 1}-01-15&estado=CESADO&page=0&size=5").keys)
        assertEquals(setOf(cesado), padron("q=cesado ${e.anio}").keys)
        assertEquals(emptySet<String>(), padron("clase=LETRERO").keys)

        // what it cannot read is a 422 that names it
        for ((consulta, campo) in listOf("estado=ANULADO" to "estado", "clase=VALLA" to "clase", "vigentes_a=ayer" to "vigentes_a", "fase=X" to "fase")) {
            val problema = tree(send("GET", "/api/srtm/anuncios?$consulta", null, HttpStatus.UNPROCESSABLE_CONTENT))
            assertEquals(campo, problema["errors"][0]["field"].asString(), problema.toString())
        }
    }

    @Test
    fun `the tasas of a year, and the clases without one`() {
        val e = Escenario()
        val tasas = tree(send("GET", "/api/srtm/anuncios/tasas?anio=${e.anio}", null, HttpStatus.OK))
        assertEquals(e.anio, tasas["anio"].asInt())
        val porClase = lista(tasas["tasas"]) { it }.associateBy { it["clase"].asString() }
        assertEquals(setOf("PANEL", "LETRERO"), porClase.keys)
        assertDinero("12.50", porClase.getValue("PANEL")["tasa"])
        assertEquals(e.panel, porClase.getValue("PANEL")["parametro_id"].asString())
        assertEquals("${e.anio}-01-01", porClase.getValue("PANEL")["vigencia_desde"].asString())
        assertEquals((CLASES - setOf("PANEL", "LETRERO")).map { Llaves.falta(it, e.anio) }, lista(tasas["faltan"]) { it.asString() })
        send("GET", "/api/srtm/anuncios/tasas?anio=dos", null, HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `an anuncio and its movimientos are not written, edited nor deleted through the generic api`() {
        val e = Escenario()
        val (_, cuerpo) = alta(e)
        for ((objeto, registro) in listOf(ANUNCIO to cuerpo["anuncio"], MOVIMIENTO_ANUNCIO to cuerpo["movimiento"])) {
            val ruta = "/api/objects/$objeto/records/${registro["id"].asString()}"
            val antes = tree(send("GET", ruta, null, HttpStatus.OK))
            val atributos = fields(antes["attributes"])
            send("PUT", ruta, mapOf("attributes" to atributos + ("observacion" to "Editado por la API")), HttpStatus.CONFLICT)
            send("DELETE", ruta, null, HttpStatus.CONFLICT)
            assertEquals(antes, tree(send("GET", ruta, null, HttpStatus.OK)))
            send("POST", "/api/objects/$objeto/records", mapOf("attributes" to atributos), HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `who may only read gets a 403 that names the object, and nothing is written`() {
        val e = Escenario()
        val id = registrado(e)
        val lector = funcionario(listOf(permiso(null, "READ")))
        val (status, problema) = alta(e, token = lector)
        assertEquals(HttpStatus.FORBIDDEN, status)
        assertTrue(problema["detail"].asString().contains(ANUNCIO), problema.toString())
        val renovacion = mapOf("fecha" to "${e.anio}-12-15", "vigencia_hasta" to "${e.anio + 1}-12-31", "observacion" to "Renovación sin permiso")
        val sinPermiso = tree(send("POST", "/api/srtm/anuncios/$id/renovacion", renovacion, HttpStatus.FORBIDDEN, lector))
        assertTrue(sinPermiso["detail"].asString().contains(MOVIMIENTO_ANUNCIO), sinPermiso.toString())
        send("POST", "/api/srtm/anuncios/$id/cese", mapOf("motivo" to "Cerró", "observacion" to "Cese sin permiso"), HttpStatus.FORBIDDEN, lector)
        // reading it, it may
        assertEquals(1, tree(send("GET", "/api/srtm/anuncios/$id", null, HttpStatus.OK, lector))["movimientos"].size())
        assertEquals(1, cuantos(ANUNCIO, "contribuyente=${e.contribuyente}"))
    }

    // a past year of this test's own, with FICTITIOUS tasas: PANEL in it and the next one, LETRERO only in it, no other
    // clase. a contribuyente and a predio to hang the anuncios from
    private inner class Escenario {
        val anio = anioLibre()
        val panel = tasa("PANEL", anio, "12.50")
        val panelSiguiente = tasa("PANEL", anio + 1, "15.00")
        val letrero = tasa("LETRERO", anio, "7.00")
        val contribuyente = inscribir()
        val predio = predio()

        fun cuerpo(cambios: Map<String, Any?>): Map<String, Any?> =
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "clase" to "PANEL",
                "tipo" to "AVISO_LUMINOSO",
                "emplazamiento" to "FACHADA",
                "forma" to "RECTANGULAR",
                "denominacion" to "BODEGA DE PRUEBA",
                "direccion" to "JR. LIMA 123",
                "area" to 2.5,
                "lados" to 1,
                "cantidad" to 1,
                "fecha_autorizacion" to "$anio-03-02",
                "vigencia_hasta" to "$anio-12-31",
                "expediente" to "EXP-0003",
                "fecha_expediente" to "$anio-02-27",
                "licencia_texto" to "LF-0001",
                "observacion" to "Alta de prueba"
            ) + cambios
    }

    // POST /anuncios with its Idempotency-Key: status and body
    private fun alta(
        e: Escenario,
        cambios: Map<String, Any?> = emptyMap(),
        clave: String? = null,
        token: String = this.token
    ): Pair<HttpStatus, JsonNode> {
        val result =
            client
                .post()
                .uri("/api/srtm/anuncios")
                .header(HttpHeaders.AUTHORIZATION, token)
                .headers { h -> clave?.let { h.set("Idempotency-Key", it) } }
                .bodyValue(e.cuerpo(cambios))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return HttpStatus.valueOf(result.status.value()) to tree(result.responseBody ?: "{}")
    }

    // a registered anuncio: its id
    private fun registrado(
        e: Escenario,
        cambios: Map<String, Any?> = emptyMap()
    ): String {
        val (status, cuerpo) = alta(e, cambios)
        assertEquals(HttpStatus.CREATED, status, cuerpo.toString())
        return cuerpo["anuncio"]["id"].asString()
    }

    private fun ficha(id: String) = tree(send("GET", "/api/srtm/anuncios/$id", null, HttpStatus.OK))

    private fun cuantos(
        objeto: String,
        filtro: String
    ) = tree(send("GET", "/api/objects/$objeto/records?$filtro&size=1", null, HttpStatus.OK))["totalElements"].asInt()

    // n calls released together, each on its thread
    private fun <T> aLaVez(
        n: Int,
        llamada: (Int) -> T
    ): List<T> {
        val hilos = Executors.newFixedThreadPool(n)
        val salida = CountDownLatch(1)
        try {
            val futuros =
                (0 until n).map { i ->
                    hilos.submit(
                        Callable {
                            salida.await()
                            llamada(i)
                        }
                    )
                }
            salida.countDown()
            return futuros.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            hilos.shutdownNow()
        }
    }

    // a year before today that no TASA_ANUNCIO row nor anuncio of the shared db has, nor its next one
    private fun anioLibre(): Int {
        while (true) {
            val anio = 1901 + Random.nextInt(90)
            val libre =
                (anio..anio + 1).all { cuantos("parametro_tributario", "tipo=${Llaves.TASA_ANUNCIO}&vigencia_desde=$it-01-01") == 0 } &&
                    cuantos(ANUNCIO, "anio=$anio") == 0
            if (libre) return anio
        }
    }

    private fun tasa(
        clase: String,
        anio: Int,
        valor: String
    ): String =
        post(
            "/api/objects/parametro_tributario/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "tipo" to Llaves.TASA_ANUNCIO,
                        "clave" to clase,
                        "vigencia_desde" to "$anio-01-01",
                        "vigencia_hasta" to "$anio-12-31",
                        "valor_numerico" to valor,
                        "norma" to "Ordenanza ficticia de prueba",
                        "fuente" to "AnunciosApiTest",
                        "transcribio" to "TEST A",
                        "verifico" to "TEST B"
                    )
            )
        )["id"].asString()

    private fun <T> lista(
        nodo: JsonNode,
        f: (JsonNode) -> T
    ): List<T> =
        nodo
            .iterator()
            .asSequence()
            .map(f)
            .toList()

    private fun assertDinero(
        esperado: String,
        nodo: JsonNode
    ) = assertEquals(0, BigDecimal(esperado).compareTo(nodo.decimalValue()), "$esperado != $nodo")
}
