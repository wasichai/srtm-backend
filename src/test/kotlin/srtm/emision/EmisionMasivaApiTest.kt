package srtm.emision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import srtm.impuesto.ConParametrosApiTest
import srtm.impuesto.Parametros
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

// /api/srtm/emisiones (wasichai/srtm-backend#41): the masiva of a year in the background, as the epic's contract says.
// the HR and the PUs are the real ones, behind a thin double of the HR: it fails for the contribuyentes in `fallan`
// and waits on `puerta` when one is set. every test emits its own year, with its UIT: the test db is shared, and the
// masiva takes every contribuyente with a vigente declaración that year
@Import(EmisionMasivaApiTest.Dobles::class)
@TestPropertySource(properties = ["srtm.emision.dir=build/emisiones-test"])
class EmisionMasivaApiTest : ConParametrosApiTest() {
    @Autowired
    lateinit var servicio: EmisionMasivaService

    @Autowired
    lateinit var cerrojo: CerrojoEmision

    @TestConfiguration
    class Dobles {
        @Bean
        @Primary
        fun documentosDeEmision(documentos: DocumentosPrediales): DocumentosDeEmision = HrDoble(documentos)
    }

    class HrDoble(
        private val documentos: DocumentosPrediales
    ) : DocumentosDeEmision {
        override suspend fun hr(
            contribuyenteId: UUID,
            anio: Int
        ): Documento {
            puerta?.await()
            if (contribuyenteId.toString() in fallan) throw IllegalStateException("Faltan parámetros del año $anio")
            return documentos.hr(contribuyenteId, anio)
        }

        override suspend fun pu(
            predioId: UUID,
            contribuyenteId: UUID?,
            anio: Int
        ) = documentos.pu(predioId, contribuyenteId, anio)
    }

    @AfterEach
    fun sinDobles() {
        fallan = emptySet()
        puerta?.complete(Unit)
        puerta = null
    }

    @Test
    fun `a pdf masiva has every hr and pu of the year in one file`() {
        val anio = anio()
        val e = escenario(anio)

        val (status, cuerpo) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to anio, "formato" to "PDF"))
        assertEquals(HttpStatus.ACCEPTED, status, cuerpo)
        val job = tree(cuerpo)
        assertEquals(anio, job["anio"].asInt())
        assertEquals("PDF", job["formato"].asString())

        val terminada = esperar(job["id"].asString())
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals(3, terminada["total"].asInt())
        assertEquals(3, terminada["procesados"].asInt())
        assertEquals(0, terminada["errores"].size(), terminada.toString())
        assertEquals("emision-$anio-${job["id"].asString()}.pdf", terminada["archivo"].asString())
        assertTrue(terminada["tamano"].asLong() > 0)
        assertTrue(terminada["terminado"].isString, terminada.toString())

        val archivo = descargar(job["id"].asString())
        assertEquals(HttpStatus.OK, archivo.status)
        assertTrue(MediaType.APPLICATION_PDF.isCompatibleWith(archivo.tipo), "${archivo.tipo}")
        assertEquals("attachment; filename=\"emision-$anio-${job["id"].asString()}.pdf\"", archivo.disposicion)
        assertEquals(terminada["tamano"].asLong(), archivo.cuerpo.size.toLong())
        // the 3 HR and the 5 PU, as the endpoints emit them one by one
        val hrs = e.contribuyentes.sumOf { paginas(documento("/api/srtm/contribuyentes/$it/hr?anio=$anio")) }
        val pus = e.pus.sumOf { (predio, titular) -> paginas(documento("/api/srtm/predios/$predio/pu?anio=$anio&contribuyente=$titular")) }
        assertEquals(hrs + pus, paginas(archivo.cuerpo))
    }

    @Test
    fun `a zip masiva has a folder per contribuyente with its hr and a pu per predio`() {
        val anio = anio()
        escenario(anio)

        val id = emitir(anio, "ZIP")
        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())

        val archivo = descargar(id)
        assertEquals("application/zip", archivo.tipo.toString())
        assertEquals("attachment; filename=\"emision-$anio-$id.zip\"", archivo.disposicion)
        val entradas =
            ZipInputStream(ByteArrayInputStream(archivo.cuerpo)).use { zip ->
                generateSequence { zip.nextEntry }.associate { it.name to zip.readAllBytes() }
            }
        assertEquals(
            3,
            entradas.keys
                .map { it.substringBefore('/') }
                .toSet()
                .size,
            "${entradas.keys}"
        )
        assertEquals(3, entradas.keys.count { it.substringAfter('/') == "HR-$anio.pdf" }, "${entradas.keys}")
        assertEquals(5, entradas.keys.count { it.substringAfter('/').startsWith("PU-") }, "${entradas.keys}")
        entradas.forEach { (nombre, pdf) -> assertTrue(paginas(pdf) > 0, nombre) }
    }

    @Test
    fun `a second masiva while one is running is a 409, and so is its file`() {
        val anio = anio()
        escenario(anio)
        puerta = CompletableDeferred()

        val id = emitir(anio, "PDF")
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }

        val (segunda, problema) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to anio, "formato" to "ZIP"))
        assertEquals(HttpStatus.CONFLICT, segunda, problema)
        assertTrue(tree(problema)["detail"].asString().isNotBlank(), problema)
        assertEquals(HttpStatus.CONFLICT, descargar(id).status)
        val (borrar, porque) = exchange("DELETE", "/api/srtm/emisiones/$id", null)
        assertEquals(HttpStatus.CONFLICT, borrar, porque)

        puerta!!.complete(Unit)
        assertEquals("TERMINADA", esperar(id)["estado"].asString())
        assertEquals(HttpStatus.OK, descargar(id).status)
    }

    @Test
    fun `a contribuyente whose hr fails is listed in errores and the masiva still ends`() {
        val anio = anio()
        val e = escenario(anio)
        fallan = setOf(e.contribuyentes[1])

        val terminada = esperar(emitir(anio, "PDF"))

        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals(3, terminada["procesados"].asInt())
        val errores = terminada["errores"].iterator().asSequence().toList()
        assertEquals(1, errores.size, terminada.toString())
        val codigo = e.codigos.getValue(e.contribuyentes[1])
        assertEquals(codigo, errores[0]["contribuyente"].asString())
        assertEquals("Faltan parámetros del año $anio", errores[0]["mensaje"].asString())
    }

    @Test
    fun `the list is of the year, the most recent first`() {
        val anio = anio()
        escenario(anio)
        val primera = emitir(anio, "PDF")
        esperar(primera)
        val segunda = emitir(anio, "ZIP")
        esperar(segunda)

        val lista = tree(send("GET", "/api/srtm/emisiones?anio=$anio", null, HttpStatus.OK)).iterator().asSequence().toList()

        assertEquals(listOf(segunda, primera), lista.map { it["id"].asString() })
        assertTrue(lista.all { it["errores"].isArray }, lista.toString())
    }

    @Test
    fun `a job left running by a restart is failed at startup, and the audit log says so`() {
        val atrapada = atrapada(anio())

        runBlocking { servicio.recuperar() }

        val job = tree(send("GET", "/api/srtm/emisiones/$atrapada", null, HttpStatus.OK))
        assertEquals("FALLIDA", job["estado"].asString(), job.toString())
        assertEquals("interrumpida por reinicio", job["mensaje"].asString())
        assertTrue(job["terminado"].isString, job.toString())
        // the system's write, without a user, in core's audit log
        val historia = tree(send("GET", "/api/objects/emision_masiva/records/$atrapada/history", null, HttpStatus.OK))
        val sistema = historia.iterator().asSequence().first { it["operation"].asString() == "UPDATE" }
        assertTrue(sistema["userEmail"] == null || sistema["userEmail"].isNull, sistema.toString())
        val estado = sistema["changes"].iterator().asSequence().first { it["field"].asString() == "estado" }
        assertEquals("EN_PROCESO", estado["before"].asString(), sistema.toString())
        assertEquals("FALLIDA", estado["after"].asString(), sistema.toString())
    }

    @Test
    fun `a caller who may create a masiva but not update it is refused before any job exists`() {
        val anio = anio()
        val funcionario = funcionario(listOf(permiso(null, "READ"), permiso(EMISION_MASIVA, "CREATE")))

        val result =
            client
                .post()
                .uri("/api/srtm/emisiones")
                .header(HttpHeaders.AUTHORIZATION, funcionario)
                .bodyValue(mapOf("anio" to anio, "formato" to "PDF"))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(HttpStatus.FORBIDDEN.value(), result.status.value(), result.responseBody)
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(result.responseHeaders.contentType), "${result.responseHeaders.contentType}")
        assertTrue(tree(result.responseBody!!)["detail"].asString().contains("edición"), result.responseBody)

        assertEquals(0, tree(send("GET", "/api/srtm/emisiones?anio=$anio", null, HttpStatus.OK)).size())
        // and nothing is left to block the next one
        assertEquals("TERMINADA", esperar(emitir(anio, "PDF"))["estado"].asString())
    }

    @Test
    fun `a job whose worker is gone does not block the next masiva, which fails it`() {
        val anio = anio()
        val huerfana = atrapada(anio)

        val nueva = esperar(emitir(anio, "ZIP"))

        assertEquals("TERMINADA", nueva["estado"].asString(), nueva.toString())
        val job = tree(send("GET", "/api/srtm/emisiones/$huerfana", null, HttpStatus.OK))
        assertEquals("FALLIDA", job["estado"].asString(), job.toString())
        assertTrue(job["mensaje"].asString().startsWith("interrumpida"), job.toString())
    }

    @Test
    fun `while another instance runs a masiva, a post is a 409 and startup leaves its job alone`() {
        val anio = anio()
        val atrapada = atrapada(anio)
        // another instance's worker: the same postgres lock, on a connection of its own
        val ajeno = runBlocking { cerrojo.tomar() }!!
        try {
            val (status, problema) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to anio, "formato" to "PDF"))
            assertEquals(HttpStatus.CONFLICT, status, problema)
            assertEquals(1, tree(send("GET", "/api/srtm/emisiones?anio=$anio", null, HttpStatus.OK)).size())

            assertNull(runBlocking { servicio.recuperar() })
            assertEquals("EN_PROCESO", tree(send("GET", "/api/srtm/emisiones/$atrapada", null, HttpStatus.OK))["estado"].asString())
            val (borrar, porque) = exchange("DELETE", "/api/srtm/emisiones/$atrapada", null)
            assertEquals(HttpStatus.CONFLICT, borrar, porque)
        } finally {
            runBlocking { ajeno.soltar() }
        }

        runBlocking { servicio.recuperar() }
        assertEquals("FALLIDA", tree(send("GET", "/api/srtm/emisiones/$atrapada", null, HttpStatus.OK))["estado"].asString())
    }

    @Test
    fun `after a masiva ends, the files beyond the last five of the year are purged`() {
        val anio = anio()
        // five older ones of the year, oldest first, with their files on disk
        val viejas = (1..5).map { terminadaConArchivo(anio, "2020-01-0${it}T00:00:00Z") }

        val nueva = esperar(emitir(anio, "PDF"))
        assertEquals("TERMINADA", nueva["estado"].asString(), nueva.toString())

        val depurada = esperar(viejas[0]) { it["archivo"] == null || it["archivo"].isNull }
        assertEquals("archivo depurado", depurada["mensaje"].asString(), depurada.toString())
        assertFalse(Files.exists(archivoDe(anio, viejas[0])))
        val descarga = descargar(viejas[0])
        assertEquals(HttpStatus.GONE, descarga.status)
        assertTrue(tree(String(descarga.cuerpo))["detail"].asString().isNotBlank())
        viejas.drop(1).forEach {
            assertTrue(Files.exists(archivoDe(anio, it)), it)
            assertEquals(HttpStatus.OK, descargar(it).status)
        }
        assertEquals(HttpStatus.OK, descargar(nueva["id"].asString()).status)
    }

    @Test
    fun `deleting a finished masiva removes its job and its file`() {
        val anio = anio()
        val id = emitir(anio, "PDF")
        esperar(id)
        assertTrue(Files.exists(archivoDe(anio, id)))

        send("DELETE", "/api/srtm/emisiones/$id", null, HttpStatus.NO_CONTENT)

        assertFalse(Files.exists(archivoDe(anio, id)))
        send("GET", "/api/srtm/emisiones/$id", null, HttpStatus.NOT_FOUND)
    }

    // a job EN_PROCESO that no worker runs, the way a crash leaves it: its id
    private fun atrapada(anio: Int): String =
        post(
            "/api/objects/emision_masiva/records",
            mapOf("attributes" to mapOf("anio" to anio, "formato" to "PDF", "estado" to "EN_PROCESO", "total" to 10, "procesados" to 2))
        )["id"].asString()

    // a TERMINADA job of `terminado`, with a file where the service looks for it: its id
    private fun terminadaConArchivo(
        anio: Int,
        terminado: String
    ): String {
        val id =
            post(
                "/api/objects/emision_masiva/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "anio" to anio,
                            "formato" to "PDF",
                            "estado" to "TERMINADA",
                            "total" to 0,
                            "procesados" to 0,
                            "archivo" to "emision-$anio.pdf",
                            "tamano" to 3,
                            "terminado" to terminado
                        )
                )
            )["id"].asString()
        Files.createDirectories(DIR)
        Files.writeString(archivoDe(anio, id), "pdf")
        return id
    }

    private fun archivoDe(
        anio: Int,
        id: String
    ): Path = DIR.resolve("emision-$anio-$id.pdf")

    // 3 contribuyentes and 4 predios: A declares P1 and P2, B declares P3, and B and C share P4 (condominio)
    private class Escenario(
        val contribuyentes: List<String>,
        // each contribuyente's codigo, by id
        val codigos: Map<String, String>,
        // (predio, titular): one PU each
        val pus: List<Pair<String, String>>
    )

    private fun escenario(anio: Int): Escenario {
        uit(anio)
        val inscritos = List(3) { post("/api/srtm/contribuyentes", personaNatural(uniqueDocumento())) }
        val (a, b, c) = inscritos.map { it["id"].asString() }
        val (p1, p2, p3, p4) = List(4) { predio() }
        declarar(a, p1, anio)
        declarar(a, p2, anio)
        declarar(b, p3, anio)
        declarar(b, p4, anio)
        declarar(c, p4, anio, "porcentaje_condominio" to 40)
        return Escenario(
            listOf(a, b, c),
            inscritos.associate { it["id"].asString() to it["codigo"].asString() },
            listOf(
                p1 to a,
                p2 to a,
                p3 to b,
                p4 to b,
                p4 to c
            )
        )
    }

    private fun declarar(
        contribuyente: String,
        predio: String,
        anio: Int,
        vararg extra: Pair<String, Any?>
    ) {
        post(
            "/api/srtm/declaraciones",
            mapOf(
                "contribuyente" to contribuyente,
                "predio" to predio,
                "anio" to anio,
                "secuencia_uso" to "1",
                "valor_autoavaluo" to 10000.50,
                "deduccion" to 0
            ) + extra
        )
    }

    // a 202: the job's id
    private fun emitir(
        anio: Int,
        formato: String
    ): String {
        val (status, cuerpo) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to anio, "formato" to formato))
        assertEquals(HttpStatus.ACCEPTED, status, cuerpo)
        return tree(cuerpo)["id"].asString()
    }

    // polls the job until `listo` (by default, until it ends)
    private fun esperar(
        id: String,
        listo: (JsonNode) -> Boolean = { it["estado"].asString() in setOf("TERMINADA", "FALLIDA") }
    ): JsonNode {
        val limite = System.nanoTime() + 60_000_000_000L
        while (true) {
            val job = tree(send("GET", "/api/srtm/emisiones/$id", null, HttpStatus.OK))
            if (listo(job)) return job
            assertTrue(System.nanoTime() < limite, "la emisión no avanza: $job")
            Thread.sleep(200)
        }
    }

    private class Respuesta(
        val status: HttpStatus,
        val tipo: MediaType?,
        val disposicion: String?,
        val cuerpo: ByteArray
    )

    private fun descargar(id: String): Respuesta = bajar("/api/srtm/emisiones/$id/archivo")

    // the csv's UIT of 2026 for the test's year: the HR liquidates with the UIT in force on 1 january. the tramos and
    // the mínimo have no end date, and ConParametrosApiTest loads them
    private fun uit(anio: Int) {
        post(
            "/api/objects/parametro_tributario/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "tipo" to "UIT",
                        "vigencia_desde" to "$anio-01-01",
                        "vigencia_hasta" to "$anio-12-31",
                        "valor_numerico" to Parametros.uit(2026),
                        "transcribio" to "TEST",
                        "verifico" to "TEST"
                    )
            )
        )
    }

    // a pdf the endpoints emit one by one
    private fun documento(path: String): ByteArray {
        val r = bajar(path)
        assertEquals(HttpStatus.OK, r.status, String(r.cuerpo))
        return r.cuerpo
    }

    private fun bajar(path: String): Respuesta {
        val result =
            client
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange()
                .expectBody(ByteArray::class.java)
                .returnResult()
        return Respuesta(
            HttpStatus.valueOf(result.status.value()),
            result.responseHeaders.contentType,
            result.responseHeaders.getFirst(HttpHeaders.CONTENT_DISPOSITION),
            result.responseBody ?: ByteArray(0)
        )
    }

    private companion object {
        val DIR: Path = Path.of("build/emisiones-test")

        @Volatile
        var fallan: Set<String> = emptySet()

        @Volatile
        var puerta: CompletableDeferred<Unit>? = null

        // a year of its own per test, far from the padrón's
        private val anios = AtomicInteger(2900 + (0..90).random() * 10)

        fun anio() = anios.getAndIncrement()
    }
}
