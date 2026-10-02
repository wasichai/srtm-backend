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
import srtm.emision.TablasCore
import srtm.emision.TrabajadoresEmision
import tools.jackson.databind.JsonNode
import wasichai.core.platform.WasichaiSchemas
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
    lateinit var trabajadores: TrabajadoresEmision

    @Autowired
    lateinit var maquina: MaquinaDeterminacion

    @Autowired
    lateinit var tablas: TablasCore

    @Autowired
    lateinit var schemas: WasichaiSchemas

    private val grupos = mutableListOf<GrupoTrabajadores>()

    // nothing of another class is left for the groups to take: only this test's masivas run
    @BeforeEach
    fun soloLasDelTest() {
        runBlocking {
            for (t in tablas.de(DETERMINACION_LOTE, listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDO' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO')")
                    .fetch()
                    .awaitRowsUpdated()
            }
            for (t in tablas.de(DETERMINACION_MASIVA, listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDA' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO', 'ENSAMBLANDO')")
                    .fetch()
                    .awaitRowsUpdated()
            }
        }
    }

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
        val g = trabajadores.grupo(nombre, 2, Files.createTempDirectory("determinacion-$nombre"))
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
