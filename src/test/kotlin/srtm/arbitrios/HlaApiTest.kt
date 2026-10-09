package srtm.arbitrios

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.TestPropertySource
import srtm.emision.EMISION_LOTE
import srtm.emision.EMISION_MASIVA
import srtm.emision.GrupoTrabajadores
import srtm.emision.TablasCore
import srtm.emision.TrabajadoresEmision
import srtm.emision.texto
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Duration
import java.util.zip.ZipInputStream

// the HLA (hoja de liquidación de arbitrios): one contribuyente's, inline, and in the masiva as a third document. a
// year of its own with its ordinance, its tasas and every month's due date; FICTITIOUS figures. lotes of 1
// contribuyente and a short lease, and no worker of the context's own: the test starts its group
@TestPropertySource(
    properties = [
        "srtm.emision.dir=build/emisiones-test",
        "srtm.emision.trabajadores=0",
        "srtm.emision.lote=1",
        "srtm.emision.lease=3s",
        "srtm.emision.espera=200ms"
    ]
)
class HlaApiTest : ConArbitriosApiTest() {
    @Autowired
    lateinit var trabajadores: TrabajadoresEmision

    @Autowired
    lateinit var tablas: TablasCore

    private val grupos = mutableListOf<GrupoTrabajadores>()

    // nothing of another class is left for the group to take: only this test's emission runs
    @BeforeEach
    fun soloLasDelTest() {
        runBlocking {
            for (t in tablas.de(EMISION_LOTE, listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDO' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO')")
                    .fetch()
                    .awaitRowsUpdated()
            }
            for (t in tablas.de(EMISION_MASIVA, listOf("estado"))) {
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
    fun `a contribuyente's HLA - its cuotas by month, the total, the due dates and the ordinance`() {
        val e = Escenario(vencimientos = true)
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        val codigo = codigoDe(e.contribuyente)
        val r = bajar("/api/srtm/contribuyentes/${e.contribuyente}/hla?anio=${e.anio}")
        assertEquals(HttpStatus.OK, r.first, String(r.third))
        assertEquals(MediaType.APPLICATION_PDF, r.second.contentType)
        assertTrue(r.second.getFirst(HttpHeaders.CONTENT_DISPOSITION)!!.contains("HLA-$codigo-${e.anio}.pdf"))
        val t = texto(r.third)
        assertTrue(t.contains("HOJA DE LIQUIDACIÓN DE ARBITRIOS"), t)
        assertTrue(t.contains("S/ 153.00"), t) // 12 x (8.50 + 4.25)
        assertTrue(t.contains("28/12/${e.anio}"), t)
        assertTrue(t.contains("000-${e.anio} (ficticia)"), t)
    }

    @Test
    fun `an HLA cannot be made before its cuotas are determined, nor without each month's due date`() {
        val e = Escenario(vencimientos = true)
        val antes = tree(send("GET", "/api/srtm/contribuyentes/${e.contribuyente}/hla?anio=${e.anio}", null, HttpStatus.UNPROCESSABLE_CONTENT))
        assertTrue(lista(antes["faltan"]) { it.asString() }.single().endsWith(": 24 cuotas de arbitrios por determinar"), antes.toString())

        val sinVencimientos = Escenario()
        send("POST", sinVencimientos.determinar(), pedido(sinVencimientos.anio), HttpStatus.CREATED)
        val problema =
            tree(
                send(
                    "GET",
                    "/api/srtm/contribuyentes/${sinVencimientos.contribuyente}/hla?anio=${sinVencimientos.anio}",
                    null,
                    HttpStatus.UNPROCESSABLE_CONTENT
                )
            )
        assertEquals((1..12).map { "ARBITRIO_VENCIMIENTO $it ${sinVencimientos.anio}" }, lista(problema["faltan"]) { it.asString() })
    }

    @Test
    fun `a condómino charged nothing has no HLA - 404, not an error`() {
        val e = Escenario(vencimientos = true)
        val menor = inscribir()
        crear(
            "declaracion_predial",
            mapOf(
                "contribuyente" to menor,
                "predio" to e.predio,
                "anio" to e.anio,
                "secuencia_uso" to "1",
                "porcentaje_condominio" to 30,
                "clase_uso" to "RESIDENCIAL",
                "sub_clase_uso" to "UNIFAMILIAR",
                "uso" to "CASA HABITACIÓN"
            )
        )
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        send("GET", "/api/srtm/contribuyentes/$menor/hla?anio=${e.anio}", null, HttpStatus.NOT_FOUND)
        send("GET", "/api/srtm/contribuyentes/${e.contribuyente}/hla?anio=${e.anio}", null, HttpStatus.OK)
    }

    @Test
    fun `a masiva that asks for the HLA is refused when the year lacks what it needs, and a name it does not know is a 400`() {
        val e = Escenario()
        val problema =
            tree(send("POST", "/api/srtm/emisiones", mapOf("anio" to e.anio, "documentos" to listOf("HR", "PU", "HLA")), HttpStatus.UNPROCESSABLE_CONTENT))
        assertEquals((1..12).map { "ARBITRIO_VENCIMIENTO $it ${e.anio}" }, lista(problema["faltan"]) { it.asString() })
        rejected("POST", "/api/srtm/emisiones", mapOf("anio" to e.anio, "documentos" to listOf("RECIBO")), "documentos")
    }

    @Test
    fun `the masiva emits each contribuyente's HLA, and one that cannot be made is an error`() {
        val e = Escenario(vencimientos = true)
        send("POST", e.determinar(), pedido(e.anio), HttpStatus.CREATED)
        // a second contribuyente whose predio is not determined yet
        val otro = inscribir()
        e.predioDeclarado(otro)
        val codigos = listOf(codigoDe(e.contribuyente), codigoDe(otro))

        val job = tree(send("POST", "/api/srtm/emisiones", mapOf("anio" to e.anio, "formato" to "ZIP", "documentos" to listOf("HLA")), HttpStatus.ACCEPTED))
        assertEquals(listOf("HLA"), lista(job["documentos"]) { it.asString() })
        val id = job["id"].asString()
        trabajadores.grupo("instancia-hla", 2, Files.createTempDirectory("hla")).also {
            it.iniciar()
            grupos += it
        }
        val fin = esperar(id)
        assertEquals("TERMINADA", fin["estado"].asString(), fin.toString())
        assertEquals(listOf(codigos[1]), lista(fin["errores"]) { it["contribuyente"].asString() })
        assertTrue(fin["errores"][0]["mensaje"].asString().startsWith("HLA: "), fin.toString())

        val archivo = bajar("/api/srtm/emisiones/$id/archivo")
        val entradas = ZipInputStream(ByteArrayInputStream(archivo.third)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertEquals(1, entradas.size, entradas.toString())
        assertTrue(entradas.single().startsWith("${codigos[0]}-") && entradas.single().endsWith("/HLA-${e.anio}.pdf"), entradas.toString())
    }

    private fun codigoDe(contribuyente: String) =
        tree(send("GET", "/api/objects/contribuyente/records/$contribuyente", null, HttpStatus.OK))["attributes"]["codigo"].asString()

    // the emission once it ended, within a minute and a half (the test db may be far)
    private fun esperar(id: String): tools.jackson.databind.JsonNode {
        val hasta = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        while (true) {
            val job = tree(send("GET", "/api/srtm/emisiones/$id", null, HttpStatus.OK))
            if (job["estado"].asString() in setOf("TERMINADA", "FALLIDA")) return job
            check(System.nanoTime() < hasta) { "la emisión no terminó: $job" }
            Thread.sleep(200)
        }
    }

    // status, headers and body of a GET that answers bytes
    private fun bajar(path: String): Triple<HttpStatus, HttpHeaders, ByteArray> {
        val result =
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(ByteArray::class.java)
                .returnResult()
        return Triple(HttpStatus.valueOf(result.status.value()), result.responseHeaders, result.responseBody ?: ByteArray(0))
    }
}
