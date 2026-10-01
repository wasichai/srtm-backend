package srtm.emision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

// /api/srtm/emisiones (wasichai/srtm-backend#41, #53, #54): the masiva of a year, cut in lotes of 2 that this
// context's 2 workers generate and one of them assembles. the HR and the PUs are the real ones, behind a thin double
// of the HR: it fails for the contribuyentes in `fallan` and waits on `puerta` when one is set (only for the ones in
// `retenidos`, if any). every test emits its own year, with its UIT: the test db is shared, and the masiva takes
// every contribuyente with a vigente declaración that year.
//
// the only class whose context runs the bean's workers: @DirtiesContext closes it after the class, so they never take
// the lotes the other classes create to look at
@Import(EmisionMasivaApiTest.Dobles::class)
@TestPropertySource(
    properties = [
        "srtm.emision.dir=build/emisiones-test",
        "srtm.emision.temporales=build/emisiones-test-tmp",
        "srtm.emision.lote=2",
        "srtm.emision.trabajadores=2",
        "srtm.emision.lease=4s",
        "srtm.emision.espera=200ms"
    ]
)
@DirtiesContext
class EmisionMasivaApiTest : ConEscenarioApiTest() {
    @Autowired
    lateinit var servicio: EmisionMasivaService

    @Autowired
    lateinit var almacen: AlmacenEmision

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
            if (retenidos.isEmpty() || contribuyenteId.toString() in retenidos) puerta?.await()
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
        retenidos = emptySet()
        puerta?.complete(Unit)
        puerta = null
    }

    @Test
    fun `a pdf masiva has every hr and pu of the year in one file, in the padron's order`() {
        val anio = anio()
        val e = escenario(anio)

        val (status, cuerpo) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to anio, "formato" to "PDF"))
        assertEquals(HttpStatus.ACCEPTED, status, cuerpo)
        val job = tree(cuerpo)
        assertEquals(anio, job["anio"].asInt())
        assertEquals("PDF", job["formato"].asString())
        val id = job["id"].asString()

        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals(3, terminada["total"].asInt())
        assertEquals(3, terminada["procesados"].asInt())
        assertEquals(0, terminada["errores"].size(), terminada.toString())
        assertEquals("emision-$anio-$id.pdf", terminada["archivo"].asString())
        assertTrue(terminada["tamano"].asLong() > 0)
        assertTrue(terminada["terminado"].isString, terminada.toString())
        // two lotes of 2 and 1
        assertEquals(listOf(1, 2), lotesDe(id).map { it.numero })
        assertTrue(lotesDe(id).all { it.estado == "TERMINADO" }, "${lotesDe(id)}")

        val archivo = descargar(id)
        assertEquals(HttpStatus.OK, archivo.status)
        assertTrue(MediaType.APPLICATION_PDF.isCompatibleWith(archivo.tipo), "${archivo.tipo}")
        assertEquals("attachment; filename=\"emision-$anio-$id.pdf\"", archivo.disposicion)
        assertEquals(terminada["tamano"].asLong(), archivo.cuerpo.size.toLong())
        // the length comes from the almacén, not from reading the file
        assertEquals(terminada["tamano"].asLong(), archivo.longitud)
        // the 3 HR and the 5 PU, as the endpoints emit them one by one: each contribuyente's HR, then its PUs
        esLaConcatenacion(archivo.cuerpo, documentos(e, anio))
        // and no work file is left on disk: polled, since another class's leftover lotes may still be passing through
        hastaQue("quedan temporales en $TEMPORALES") { restos().isEmpty() }
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
    fun `after it ends no parte is left in the almacen`() {
        val anio = anio()
        escenario(anio)

        val id = emitir(anio, "PDF")
        assertEquals("TERMINADA", esperar(id)["estado"].asString())

        val uuid = UUID.fromString(id)
        assertEquals(listOf(claveResultado(uuid, anio, FormatoEmision.PDF)), runBlocking { almacen.listar(prefijoEmision(uuid)) })
    }

    @Test
    fun `the progress is the sum of the lotes and moves during the run`() {
        val anio = anio()
        val e = escenario(anio)
        // the last contribuyente's lote waits; the first lote ends
        retenidos = setOf(e.contribuyentes[2])
        puerta = CompletableDeferred()

        val id = emitir(anio, "PDF")
        val aMedias = esperar(id) { it["procesados"].asInt() > 0 }

        assertEquals("EN_PROCESO", aMedias["estado"].asString(), aMedias.toString())
        assertEquals(3, aMedias["total"].asInt())
        assertEquals(2, aMedias["procesados"].asInt(), aMedias.toString())
        puerta!!.complete(Unit)
        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals(3, terminada["procesados"].asInt())
    }

    @Test
    fun `a second masiva of the same year while one runs is a 409, its file too, and deleting it cancels it`() {
        val anio = anio()
        escenario(anio)
        puerta = CompletableDeferred()

        val id = emitir(anio, "PDF")
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }

        val (segunda, problema) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to anio, "formato" to "ZIP"))
        assertEquals(HttpStatus.CONFLICT, segunda, problema)
        assertEquals("Ya hay una emisión masiva de $anio en curso: espere a que termine", tree(problema)["detail"].asString(), problema)
        assertEquals(1, tree(send("GET", "/api/srtm/emisiones?anio=$anio", null, HttpStatus.OK)).size())
        assertEquals(HttpStatus.CONFLICT, descargar(id).status)

        send("DELETE", "/api/srtm/emisiones/$id", null, HttpStatus.NO_CONTENT)

        send("GET", "/api/srtm/emisiones/$id", null, HttpStatus.NOT_FOUND)
        assertEquals(emptyList<FilaLote>(), lotesDe(id))
        // its workers lose their lotes, and whatever they write after goes
        puerta!!.complete(Unit)
        val prefijo = prefijoEmision(UUID.fromString(id))
        val nueva = emitir(anio, "PDF")
        assertEquals("TERMINADA", esperar(nueva)["estado"].asString())
        hastaQue("quedan claves de la emisión borrada") { runBlocking { almacen.listar(prefijo) }.isEmpty() }
    }

    @Test
    fun `two organizations emit at the same time`() {
        val anio = anio()
        val e = escenario(anio)
        val otra = otraOrganizacion()
        // the first organization's masiva waits in its first contribuyente
        retenidos = setOf(e.contribuyentes[0])
        puerta = CompletableDeferred()

        val primera = emitir(anio, "PDF")
        esperar(primera) { it["estado"].asString() == "EN_PROCESO" }
        // the other one has no padrón that year: an empty file, while the first one still runs
        val segunda = emitir(anio, "PDF", otra)
        val vacia = esperar(segunda, otra)
        assertEquals("TERMINADA", vacia["estado"].asString(), vacia.toString())
        assertEquals(0, vacia["total"].asInt())
        assertEquals("EN_PROCESO", tree(send("GET", "/api/srtm/emisiones/$primera", null, HttpStatus.OK))["estado"].asString())
        assertEquals(0, paginas(descargar(segunda, otra).cuerpo))
        // and neither sees the other's
        send("GET", "/api/srtm/emisiones/$segunda", null, HttpStatus.NOT_FOUND)

        puerta!!.complete(Unit)
        assertEquals("TERMINADA", esperar(primera)["estado"].asString())
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
    fun `a job of the previous version is failed at startup, and the audit log says so`() {
        val atrapada = atrapada(anio())

        runBlocking { servicio.recuperar() }

        val job = tree(send("GET", "/api/srtm/emisiones/$atrapada", null, HttpStatus.OK))
        assertEquals("FALLIDA", job["estado"].asString(), job.toString())
        assertEquals("interrumpida: el proceso que la corría ya no está", job["mensaje"].asString())
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
        val funcionario = funcionario(listOf(permiso(null, "READ"), permiso(EMISION_MASIVA, "CREATE"), permiso(EMISION_LOTE, "CREATE")))

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
    fun `a caller who may not create its lotes is refused before any job exists`() {
        val anio = anio()
        val funcionario = funcionario(listOf(permiso(null, "READ"), permiso(EMISION_MASIVA, "CREATE"), permiso(EMISION_MASIVA, "UPDATE")))

        val (status, problema) = exchange("POST", "/api/srtm/emisiones", mapOf("anio" to anio, "formato" to "PDF"), funcionario)

        assertEquals(HttpStatus.FORBIDDEN, status, problema)
        assertTrue(tree(problema)["detail"].asString().contains(EMISION_LOTE), problema)
        assertEquals(0, tree(send("GET", "/api/srtm/emisiones?anio=$anio", null, HttpStatus.OK)).size())
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
        assertFalse(existe(anio, viejas[0]))
        val descarga = descargar(viejas[0])
        assertEquals(HttpStatus.GONE, descarga.status)
        assertTrue(tree(String(descarga.cuerpo))["detail"].asString().isNotBlank())
        viejas.drop(1).forEach {
            assertTrue(existe(anio, it), it)
            assertEquals(HttpStatus.OK, descargar(it).status)
        }
        assertEquals(HttpStatus.OK, descargar(nueva["id"].asString()).status)
    }

    @Test
    fun `the file of a finished masiva that the almacen no longer has is a 404`() {
        val anio = anio()
        val id = terminadaConArchivo(anio, "2020-01-01T00:00:00Z")
        runBlocking { almacen.borrar(claveResultado(UUID.fromString(id), anio, FormatoEmision.PDF)) }

        val descarga = descargar(id)

        assertEquals(HttpStatus.NOT_FOUND, descarga.status, String(descarga.cuerpo))
        assertEquals("El archivo de la emisión ya no está en el servidor", tree(String(descarga.cuerpo))["detail"].asString())
    }

    @Test
    fun `deleting a finished masiva removes its job, its file and its lotes`() {
        val anio = anio()
        escenario(anio)
        val id = emitir(anio, "PDF")
        esperar(id)
        assertTrue(existe(anio, id))
        assertEquals(2, lotesDe(id).size)

        send("DELETE", "/api/srtm/emisiones/$id", null, HttpStatus.NO_CONTENT)

        assertFalse(existe(anio, id))
        assertEquals(emptyList<String>(), runBlocking { almacen.listar(prefijoEmision(UUID.fromString(id))) })
        assertEquals(emptyList<FilaLote>(), lotesDe(id))
        send("GET", "/api/srtm/emisiones/$id", null, HttpStatus.NOT_FOUND)
    }

    // a job EN_PROCESO of the version before the lotes, the way a restart left it: no latido, no lotes. its id
    private fun atrapada(anio: Int): String =
        post(
            "/api/objects/emision_masiva/records",
            mapOf("attributes" to mapOf("anio" to anio, "formato" to "PDF", "estado" to "EN_PROCESO", "total" to 10, "procesados" to 2))
        )["id"].asString()

    // a TERMINADA job of `terminado`, with its file in the almacén: its id
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
        val archivo = Files.writeString(Files.createTempFile("emision-test", ".pdf"), "pdf")
        runBlocking { almacen.guardar(claveResultado(UUID.fromString(id), anio, FormatoEmision.PDF), archivo) }
        return id
    }

    // what is in the workers' work dir
    private fun restos(): List<String> =
        if (Files.isDirectory(TEMPORALES)) Files.list(TEMPORALES).use { l -> l.map { it.fileName.toString() }.toList() } else emptyList()

    // the pdf of the job in the almacén
    private fun existe(
        anio: Int,
        id: String
    ): Boolean = runBlocking { almacen.existe(claveResultado(UUID.fromString(id), anio, FormatoEmision.PDF)) }

    // a new organization with the model applied, as core provisions one: its admin's token
    private fun otraOrganizacion(): String {
        val slug = uniqueName("org").lowercase()
        val email = "admin@$slug.test"
        post("/api/organizations", mapOf("name" to slug, "slug" to slug, "adminEmail" to email, "adminPassword" to CLAVE))
        val suyo = bearer(email, CLAVE)
        aplicarModelo(suyo)
        return suyo
    }

    private companion object {
        val TEMPORALES: Path = Path.of("build/emisiones-test-tmp")

        const val CLAVE = "clave-de-la-otra-organizacion"

        @Volatile
        var fallan: Set<String> = emptySet()

        @Volatile
        var retenidos: Set<String> = emptySet()

        @Volatile
        var puerta: CompletableDeferred<Unit>? = null

        // a year of its own per test, far from the padrón's
        private val anios = AtomicInteger(2900 + (0..90).random() * 10)

        fun anio() = anios.getAndIncrement()
    }
}
