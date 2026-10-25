package srtm.arbitrios

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.TestPropertySource
import srtm.emision.GrupoTrabajadores
import tools.jackson.databind.JsonNode
import wasichai.core.platform.WasichaiSchemas
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.UUID

// the masiva de arbitrios on the emission's machinery: its POST's checks, its lotes taken by the workers of one or two
// instances (GrupoTrabajadores of this context: it runs none of its own), a predio that cannot be determined, a lote
// whose worker died, a creator disabled, and its deletion. lotes of 1 predio and a short lease, as the emission's tests
@TestPropertySource(
    properties = [
        "srtm.emision.dir=build/emisiones-test",
        "srtm.emision.trabajadores=0",
        "srtm.emision.lote=1",
        "srtm.emision.lease=3s",
        "srtm.emision.espera=200ms"
    ]
)
class DeterminacionMasivaApiTest : ConArbitriosApiTest() {
    @Autowired
    lateinit var maquina: MaquinaDeterminacion

    @Autowired
    lateinit var schemas: WasichaiSchemas

    private val grupos = mutableListOf<GrupoTrabajadores>()

    // nothing of the test's organization is left for the groups to take: only this test's masivas run
    @BeforeEach
    fun sinLotesAjenos() = soloLasDelTest()

    @AfterEach
    fun detener() {
        runBlocking { grupos.forEach { it.detener() } }
        grupos.clear()
    }

    @Test
    fun `a masiva determines every predio of its year, lote by lote, and ends TERMINADA`() {
        val e = Escenario()
        repeat(2) { e.predioDeclarado(inscribir()) }
        val lanzada = tree(send("POST", MASIVAS, pedido(e.anio), HttpStatus.ACCEPTED))
        assertEquals("PENDIENTE", lanzada["estado"].asString())
        val id = lanzada["id"].asString()
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }

        grupo("instancia-a")
        val fin = esperar(id) { it["estado"].asString() == "TERMINADA" }
        assertEquals(3, fin["total"].asInt())
        assertEquals(3, fin["procesados"].asInt())
        assertEquals(72, fin["generadas"].asInt()) // 3 predios x 2 servicios x 12 months
        assertEquals(0, fin["errores"].size())
        assertEquals("Determinación de prueba", fin["observacion"].asString())
        assertEquals(72, pagina("anio=${e.anio}&size=200")["totalElements"].asInt())
        assertEquals(24, pagina("anio=${e.anio}&predio=${e.predio}")["totalElements"].asInt())
    }

    @Test
    fun `a predio that cannot be determined is an error by its code, and the others are determined`() {
        val e = Escenario()
        val sinZona = "S${uniqueDocumento()}"
        val otro = e.predioDeclarado(inscribir(), sinZona)
        val codigo = matriz(e.anio, otro)["predio"]["codigo"].asString()
        val id = lanzar(e.anio)
        grupo("instancia-a")
        val fin = esperar(id) { it["estado"].asString() == "TERMINADA" }
        assertEquals(2, fin["procesados"].asInt())
        assertEquals(24, fin["generadas"].asInt())
        assertEquals(listOf(codigo), lista(fin["errores"]) { it["predio"].asString() })
        assertEquals("ARBITRIO_ZONA $sinZona ${e.anio}", fin["errores"][0]["mensaje"].asString())
    }

    // the masiva and the POST of one predio determine the same (#84): three predios, each made twice, the first of
    // each pair determined by its POST and the second by the masiva - the same cuotas, servicio by servicio, month by
    // month and secuencia by secuencia, with the same monto, parametro_aplicado and desglose. one is the example of
    // the manual SRTM web M02 «Determinación de Deuda», págs. 17-18 (global-constraints.md: 40.44 + 108.00 + 150.30 +
    // 6.61 = 305.35 a month, recolección by AREA_CONSTRUIDA_M2 with habitantes); one has two usos (a casa and a
    // tienda, secuencias 001 and 002); one is a casa without habitantes declared. the comercio's tasas, the other
    // predios' figures and every name of zona, uso, influencia and afluencia are fictitious
    @Test
    fun `the masiva determines each predio exactly as its POST does - one of two usos and the manual M02 example`() {
        val anio = anioLibre()
        val sector = "S${uniqueDocumento()}"
        val ordenanza =
            crear(
                ORDENANZA_ARBITRIO,
                mapOf(
                    "anio" to anio,
                    "numero" to "000-$anio (ficticia)",
                    "acuerdo_ratificacion" to "Acuerdo 000 (ficticio)",
                    "fecha_ratificacion" to "$anio-01-01"
                )
            )
        val (barrido, recoleccion, parques, serenazgo) = List(4) { "M${uniqueDocumento()}" }
        listOf(barrido, recoleccion, parques, serenazgo).forEachIndexed { i, codigo ->
            crear(
                SERVICIO_ARBITRIO,
                mapOf(
                    "codigo" to codigo,
                    "nombre" to "Servicio $codigo",
                    "orden" to i + 1,
                    "vigencia_desde" to "$anio-01-01",
                    "vigencia_hasta" to "$anio-12-31",
                    "ordenanza" to ordenanza
                )
            )
        }

        fun parametro(
            tipo: String,
            clave: String?,
            valor: String? = null,
            texto: String? = null
        ) = crear(
            "parametro_tributario",
            mapOf(
                "tipo" to tipo,
                "clave" to clave,
                "vigencia_desde" to "$anio-01-01",
                "vigencia_hasta" to "$anio-12-31",
                "valor_numerico" to valor,
                "texto" to texto,
                "norma" to "Ordenanza ficticia de prueba",
                "fuente" to "DeterminacionMasivaApiTest",
                "transcribio" to "TEST A",
                "verifico" to "TEST B"
            ).filterValues { it != null }
        )
        usoCasa()
        usoTienda()
        parametro(Llaves.ARBITRIO_ZONA, sector, texto = "Z1")
        parametro(Llaves.ARBITRIO_USO, "0101", texto = "CASA")
        parametro(Llaves.ARBITRIO_USO, "02", texto = "COMERCIO")
        parametro(Llaves.BASE_ARBITRIO, barrido, texto = "FRONTIS_ML")
        parametro(Llaves.BASE_ARBITRIO, recoleccion, texto = "AREA_CONSTRUIDA_M2")
        parametro(Llaves.HABITANTES_PROMEDIO, "$recoleccion:Z1:CASA", valor = "1")
        parametro(Llaves.HABITANTES_VARIACION, "$recoleccion:Z1:CASA", valor = "1")
        parametro(Llaves.DIMENSIONES_ARBITRIO, parques, texto = "ZONA,INFLUENCIA,AFLUENCIA")
        parametro(Llaves.ARBITRIO_ZONA, "$parques:$sector", texto = "Periferia")
        parametro(Llaves.ARBITRIO_INFLUENCIA, "FRENTE A PARQUE", texto = "Parque")
        parametro(Llaves.ARBITRIO_AFLUENCIA, "01", texto = "Baja")
        parametro(Llaves.ARBITRIO_AFLUENCIA, "02", texto = "Alta")
        // the manual M02's
        parametro(Llaves.TASA_ARBITRIO, "$barrido:Z1:CASA", valor = "3.37")
        parametro(Llaves.TASA_ARBITRIO, "$recoleccion:Z1:CASA", valor = "0.12")
        parametro(Llaves.TASA_ARBITRIO, "$parques:Periferia:Parque:Baja", valor = "150.30")
        parametro(Llaves.TASA_ARBITRIO, "$serenazgo:Z1:CASA", valor = "6.61")
        // the comercio's, fictitious
        parametro(Llaves.TASA_ARBITRIO, "$barrido:Z1:COMERCIO", valor = "5.00")
        parametro(Llaves.TASA_ARBITRIO, "$recoleccion:Z1:COMERCIO", valor = "0.30")
        parametro(Llaves.TASA_ARBITRIO, "$parques:Periferia:Parque:Alta", valor = "200.00")
        parametro(Llaves.TASA_ARBITRIO, "$serenazgo:Z1:COMERCIO", valor = "9.00")
        // the year pays one PU per uso (#80): without it, the predio of two usos would pay its casa's alone
        parametro(Llaves.ARBITRIO_PU_POR_USO, null, valor = "1")

        val contribuyente = inscribir()

        fun predio(vararg usos: Map<String, Any>): String {
            val id =
                post(
                    "/api/srtm/predios",
                    mapOf(
                        "codigo" to "T-${uniqueDocumento()}",
                        "direccion" to "JR. LIMA 123",
                        "tipo_predio" to "PREDIO URBANO",
                        "sector_catastral" to sector,
                        "ubicacion_area_verde" to "FRENTE A PARQUE"
                    )
                )["id"].asString()
            for (uso in usos) {
                crear("declaracion_predial", mapOf("contribuyente" to contribuyente, "predio" to id, "anio" to anio, "porcentaje_condominio" to 100) + uso)
            }
            return id
        }
        val casa = mapOf("clase_uso" to "RESIDENCIAL", "sub_clase_uso" to "UNIFAMILIAR", "uso" to "CASA HABITACIÓN")
        val m02 = casa + mapOf("secuencia_uso" to "1", "longitud_frente" to 12, "area_construida" to 180, "numero_habitantes" to 5)
        val dosUsos =
            listOf(
                casa + mapOf("secuencia_uso" to "1", "longitud_frente" to 8, "area_construida" to 90, "numero_habitantes" to 3),
                mapOf(
                    "secuencia_uso" to "2",
                    "clase_uso" to "COMERCIAL",
                    "sub_clase_uso" to "TIENDAS",
                    "uso" to "TIENDA MÚLTIPLE DE DIFERENTE RUBRO",
                    "longitud_frente" to 4,
                    "area_construida" to 40
                )
            )
        val sinHabitantes = casa + mapOf("secuencia_uso" to "1", "longitud_frente" to 10, "area_construida" to 100)
        val casos = listOf(listOf(m02), dosUsos, listOf(sinHabitantes))

        // the first of each pair by its POST, before the masiva: the masiva adds nothing to it
        val porPost = casos.map { usos -> predio(*usos.toTypedArray()) }
        porPost.forEach { send("POST", "/api/srtm/predios/$it/arbitrios", pedido(anio), HttpStatus.CREATED) }
        val porMasiva = casos.map { usos -> predio(*usos.toTypedArray()) }
        grupo("instancia-a")
        val fin = esperar(lanzar(anio)) { it["estado"].asString() == "TERMINADA" }
        assertEquals(0, fin["errores"].size(), fin.toString())
        assertEquals(6, fin["procesados"].asInt())
        assertEquals(48 + 96 + 48, fin["generadas"].asInt()) // the masiva's three: 4 servicios x 12 months, x 2 usos

        // each cuota by servicio, month and secuencia: what it was determined with, with no id, predio or clave
        fun cuotas(predio: String): Map<List<String>, List<String?>> =
            lista(pagina("anio=$anio&predio=$predio&size=200")["content"]) { it }.associate { q ->
                fun texto(campo: String) = q[campo]?.takeUnless { it.isNull }?.asString()

                fun numero(campo: String) =
                    q[campo]
                        ?.takeUnless { it.isNull }
                        ?.decimalValue()
                        ?.stripTrailingZeros()
                        ?.toPlainString()

                fun logico(campo: String) = q[campo]?.takeUnless { it.isNull }?.asBoolean()?.toString()
                listOf(texto("servicio")!!, numero("periodo")!!, texto("secuencia_uso")!!) to
                    listOf(numero("monto"), texto("parametro_aplicado"), texto("parametro"), texto("zona"), texto("uso_arbitrio")) +
                    listOf(texto("base"), numero("cantidad_base"), numero("tasa_unitaria"), numero("habitantes"), logico("habitantes_presuntos")) +
                    listOf(numero("promedio_habitantes"), numero("variacion_habitante"), texto("influencia"), texto("afluencia"), texto("redondeo"))
            }
        for ((post, masiva) in porPost.zip(porMasiva)) {
            val esperadas = cuotas(post)
            assertTrue(esperadas.isNotEmpty())
            assertEquals(esperadas, cuotas(masiva), "predio $masiva (masiva) frente a $post (POST)")
        }
        val (m02Post, dosUsosPost) = porPost.map(::cuotas)
        assertEquals(48, m02Post.size)
        val enero = m02Post.filterKeys { it[1] == "1" }.values.map { BigDecimal(it[0]) }
        assertEquals(listOf("6.61", "40.44", "108", "150.3"), enero.sorted().map { it.toPlainString() }) // serenazgo, barrido, recolección, parques
        assertEquals(0, BigDecimal("305.35").compareTo(enero.fold(BigDecimal.ZERO, BigDecimal::add)))
        assertEquals(96, dosUsosPost.size)
        assertEquals(setOf("001", "002"), dosUsosPost.keys.map { it[2] }.toSet())
    }

    @Test
    fun `the POST checks before creating anything - 400, 422, 403 and 409`() {
        val e = Escenario()
        rejected("POST", MASIVAS, mapOf("anio" to e.anio, "observacion" to "ok"), "observacion")

        val vacio = anioLibre()
        val problema = tree(send("POST", MASIVAS, pedido(vacio), HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(lista(problema["faltan"]) { it.asString() }.contains("Ordenanza de arbitrios $vacio"), problema.toString())

        val lector = funcionario(listOf(permiso(null, "READ"), permiso(DETERMINACION_MASIVA, "CREATE")))
        send("POST", MASIVAS, pedido(e.anio), HttpStatus.FORBIDDEN, lector)
        assertEquals(0, tree(send("GET", "$MASIVAS?anio=${e.anio}", null, HttpStatus.OK)).size())

        // no group runs: the first one stays EN_PROCESO
        val primera = lanzar(e.anio)
        esperar(primera) { it["estado"].asString() == "EN_PROCESO" }
        send("POST", MASIVAS, pedido(e.anio), HttpStatus.CONFLICT)
    }

    @Test
    fun `a masiva of a year already determined adds nothing`() {
        val e = Escenario()
        grupo("instancia-a")
        esperar(lanzar(e.anio)) { it["estado"].asString() == "TERMINADA" }
        val segunda = esperar(lanzar(e.anio)) { it["estado"].asString() == "TERMINADA" }
        assertEquals(1, segunda["procesados"].asInt())
        assertEquals(0, segunda["generadas"].asInt())
        assertEquals(24, pagina("anio=${e.anio}&predio=${e.predio}")["totalElements"].asInt())
    }

    @Test
    fun `two instances determine distinct lotes, and each cuota once`() {
        val e = Escenario()
        repeat(3) { e.predioDeclarado(inscribir()) }
        val id = lanzar(e.anio)
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        grupo("instancia-a")
        grupo("instancia-b")
        val fin = esperar(id) { it["estado"].asString() == "TERMINADA" }
        assertEquals(96, fin["generadas"].asInt())
        assertEquals(96, pagina("anio=${e.anio}&size=200")["totalElements"].asInt())
        assertTrue(lotesDe(id).all { it["attributes"]["estado"].asString() == "TERMINADO" })
    }

    @Test
    fun `a lote whose worker died is taken again once its lease expires`() {
        val e = Escenario()
        e.predioDeclarado(inscribir())
        val id = lanzar(e.anio)
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        // a worker of another instance takes a lote and dies with it
        val muerto = runBlocking { maquina.lotes.tomar("instancia-muerta", Duration.ofSeconds(3)) }!!
        grupo("instancia-a")
        val fin = esperar(id) { it["estado"].asString() == "TERMINADA" }
        assertEquals(48, fin["generadas"].asInt())
        val retomado = lotesDe(id).single { it["id"].asString() == muerto.id.toString() }
        assertEquals(2, retomado["attributes"]["intentos"].asInt())
        assertTrue(retomado["attributes"]["tomado_por"].asString() != "instancia-muerta")
    }

    @Test
    fun `a masiva whose creator was disabled fails its lotes, and says why`() {
        val e = Escenario()
        val funcionario =
            funcionario(
                listOf(
                    permiso(null, "READ"),
                    permiso(DETERMINACION_MASIVA, "CREATE"),
                    permiso(DETERMINACION_MASIVA, "UPDATE"),
                    permiso(DETERMINACION_LOTE, "CREATE"),
                    permiso(CUOTA_ARBITRIO, "CREATE")
                )
            )
        val id = tree(send("POST", MASIVAS, pedido(e.anio), HttpStatus.ACCEPTED, funcionario))["id"].asString()
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        deshabilitar(sujeto(funcionario))
        grupo("instancia-a")
        val fin = esperar(id) { it["estado"].asString() == "TERMINADA" }
        assertEquals(0, fin["generadas"].asInt())
        assertEquals(listOf(SIN_USUARIO_DETERMINACION), lista(fin["errores"]) { it["mensaje"].asString() })
        assertEquals(0, pagina("anio=${e.anio}&predio=${e.predio}")["totalElements"].asInt())
    }

    @Test
    fun `deleting a running masiva cancels it and removes its lotes`() {
        val e = Escenario()
        val id = lanzar(e.anio)
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        send("DELETE", "$MASIVAS/$id", null, HttpStatus.NO_CONTENT)
        send("GET", "$MASIVAS/$id", null, HttpStatus.NOT_FOUND)
        assertEquals(0, lotesDe(id).size)
    }

    // the srtm's tienda múltiple, a uso of the clase COMERCIAL, once in the shared db
    private fun usoTienda() {
        if (tree(send("GET", "/api/objects/uso_predio/records?codigo=020326", null, HttpStatus.OK))["totalElements"].asInt() > 0) return
        crear("uso_predio", mapOf("codigo" to "020326", "clase" to "COMERCIAL", "sub_clase" to "TIENDAS", "uso" to "TIENDA MÚLTIPLE DE DIFERENTE RUBRO"))
    }

    private fun lanzar(anio: Int): String = tree(send("POST", MASIVAS, pedido(anio), HttpStatus.ACCEPTED))["id"].asString()

    private fun matriz(
        anio: Int,
        predio: String
    ) = tree(send("GET", "/api/srtm/predios/$predio/arbitrios?anio=$anio", null, HttpStatus.OK))

    // the job once `listo` holds, within a minute (the test db may be far)
    private fun esperar(
        id: String,
        listo: (JsonNode) -> Boolean
    ): JsonNode {
        val hasta = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (true) {
            val job = tree(send("GET", "$MASIVAS/$id", null, HttpStatus.OK))
            if (listo(job)) return job
            check(System.nanoTime() < hasta) { "la determinación masiva no llegó a lo esperado: $job" }
            Thread.sleep(200)
        }
    }

    // the job's lote records: id and attributes
    private fun lotesDe(id: String): List<JsonNode> =
        lista(tree(send("GET", "/api/objects/$DETERMINACION_LOTE/records?determinacion=$id&size=200", null, HttpStatus.OK))["content"]) { it }

    // an instance of `nombre`, with two workers and a work dir of its own
    private fun grupo(nombre: String) {
        val g = grupoDelTest(nombre, 2, Files.createTempDirectory("determinacion-$nombre"))
        g.iniciar()
        grupos += g
    }

    // the user of a bearer token: its jwt's subject
    private fun sujeto(bearer: String): UUID {
        val carga = String(Base64.getUrlDecoder().decode(bearer.removePrefix("Bearer ").split('.')[1]))
        return UUID.fromString(tree(carga)["sub"].asString())
    }

    private fun deshabilitar(usuario: UUID) {
        runBlocking {
            db
                .sql("UPDATE ${schemas.metadata}.users SET enabled = false WHERE id = :id")
                .bind("id", usuario)
                .fetch()
                .awaitRowsUpdated()
        }
    }

    private companion object {
        const val MASIVAS = "/api/srtm/arbitrios/determinaciones"
    }
}
