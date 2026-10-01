package srtm.emision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.context.TestPropertySource
import srtm.impuesto.ParametroTributario
import wasichai.core.platform.WasichaiSchemas
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText

// the masiva's workers (wasichai/srtm-backend#53, #54): two instances played by two GrupoTrabajadores of this context,
// each with its own instancia and work dir, and the leftovers of a dead one. the context runs no worker of its own
// (srtm.emision.trabajadores=0): only the test's groups take lotes. lotes of 1 contribuyente and a short lease
@TestPropertySource(
    properties = [
        "srtm.emision.dir=build/emisiones-test",
        "srtm.emision.trabajadores=0",
        "srtm.emision.lote=1",
        "srtm.emision.lease=3s",
        "srtm.emision.espera=200ms"
    ]
)
class TrabajadoresEmisionApiTest : ConEscenarioApiTest() {
    @Autowired
    lateinit var trabajadores: TrabajadoresEmision

    @Autowired
    lateinit var documentosPrediales: DocumentosPrediales

    @Autowired
    lateinit var almacen: AlmacenEmision

    @Autowired
    lateinit var lotes: LotesEmision

    @Autowired
    lateinit var identidad: IdentidadEmision

    @Autowired
    lateinit var schemas: WasichaiSchemas

    @Autowired
    lateinit var merger: PdfMerger

    private val grupos = mutableListOf<GrupoTrabajadores>()

    // the real documents; armed, the first HR asked for waits on `puerta`
    private val primera = AtomicBoolean(false)

    @Volatile
    private var puerta: CompletableDeferred<Unit>? = null

    private val documentos =
        object : DocumentosDeEmision {
            override suspend fun hr(
                contribuyenteId: UUID,
                anio: Int,
                parametros: List<ParametroTributario>?
            ): Documento {
                if (primera.compareAndSet(false, true)) puerta?.await()
                return documentosPrediales.hr(contribuyenteId, anio, parametros)
            }

            override suspend fun pu(
                predioId: UUID,
                contribuyenteId: UUID?,
                anio: Int
            ) = documentosPrediales.pu(predioId, contribuyenteId, anio)
        }

    // nothing of another class is left for the groups to take or assemble: only this test's emission runs
    @BeforeEach
    fun soloLaDelTest() = soloLasDelTest()

    @AfterEach
    fun detener() {
        puerta?.complete(Unit)
        runBlocking { grupos.forEach { it.detener() } }
        grupos.clear()
    }

    @Test
    fun `two instances process distinct lotes of one emission, never the same one`() {
        val anio = anio()
        val e = escenario(anio)
        puerta = CompletableDeferred()
        val id = emitir(anio, "PDF")
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }

        grupo("instancia-a")
        grupo("instancia-b")
        // the first lote taken waits in its HR until the other instance has taken one too
        hastaQue("una sola instancia toma lotes") { lotesDe(id).mapNotNull { it.tomadoPor }.toSet().size == 2 }
        puerta!!.complete(Unit)

        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        val filas = lotesDe(id)
        assertEquals(listOf(1, 2, 3), filas.map { it.numero })
        assertTrue(filas.all { it.estado == "TERMINADO" && it.intentos == 1 }, "$filas")
        assertEquals(setOf("instancia-a", "instancia-b"), filas.map { it.tomadoPor }.toSet(), "$filas")
        esLaConcatenacion(descargar(id).cuerpo, documentos(e, anio))
        assertEquals(listOf(resultado(id, anio)), runBlocking { almacen.listar(prefijoEmision(UUID.fromString(id))) })
    }

    @Test
    fun `a lote left by a dead instance is taken when its lease expires and nothing is repeated or lost`() {
        val anio = anio()
        val e = escenario(anio)
        val id = emitir(anio, "PDF")
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        // the dead one had taken lote 2 an hour ago, and left half a parte
        val muerto = lotesDe(id)[1]
        lote(muerto.id, "EN_PROCESO", "muerta", 1, "1 hour")
        parte(id, 2, "a medias")

        grupo("viva")

        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        val retomado = lotesDe(id)[1]
        assertEquals("TERMINADO", retomado.estado)
        assertEquals("viva", retomado.tomadoPor)
        assertEquals(2, retomado.intentos)
        esLaConcatenacion(descargar(id).cuerpo, documentos(e, anio))
    }

    @Test
    fun `a lote beyond its attempts fails and its contribuyentes are in the errores of the emission`() {
        val anio = anio()
        val e = escenario(anio)
        val id = emitir(anio, "PDF")
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        val agotado = lotesDe(id)[1]
        lote(agotado.id, "EN_PROCESO", "muerta", 3, "1 hour")

        grupo("viva")

        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals("FALLIDO", lotesDe(id)[1].estado)
        // its contribuyente counts as processed: it is among the errores
        assertEquals(3, terminada["procesados"].asInt())
        val errores = terminada["errores"].iterator().asSequence().toList()
        assertEquals(1, errores.size, terminada.toString())
        assertEquals(e.codigos.getValue(e.contribuyentes[1]), errores[0]["contribuyente"].asString())
        assertEquals("lote fallido tras 3 intentos", errores[0]["mensaje"].asString())
        esLaConcatenacion(descargar(id).cuerpo, documentos(e, anio, listOf(e.contribuyentes[0], e.contribuyentes[2])))
    }

    @Test
    fun `an assembly left by a dead instance is retaken when its lease expires`() {
        val anio = anio()
        val e = escenario(anio)
        val id = emitir(anio, "PDF")
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        val uuid = UUID.fromString(id)
        // the dead instance generated every lote, claimed the assembly, stored the final file and deleted a parte
        val muerta = runBlocking { identidad.de(admin()) }!!
        val partes =
            runBlocking {
                withContext(ReactiveSecurityContextHolder.withAuthentication(muerta).asCoroutineContext()) {
                    (1..3).map {
                        val lote = lotes.tomar("muerta", Duration.ofMinutes(2))!!
                        val archivo = Files.createTempFile("parte", ".pdf")
                        Files.delete(archivo)
                        val hecho = GeneradorEmision(documentos, merger).generar(anio, FormatoEmision.PDF, lote.contribuyentes, null, archivo) { _, _ -> }
                        val clave = claveParte(uuid, lote.numero, FormatoEmision.PDF)
                        almacen.guardar(clave, archivo)
                        assertTrue(lotes.terminar(lote, "muerta", lote.contribuyentes.size, hecho.documentos, hecho.errores, clave))
                        clave
                    }
                }
            }
        val trabajo = Files.createTempDirectory("ensamblado-muerta")
        runBlocking {
            EnsambladorEmision(almacen, merger).ensamblar(FormatoEmision.PDF, partes, resultado(id, anio), trabajo)
            almacen.borrar(partes[0])
        }
        ensamblando(uuid, "1 hour")

        grupo("viva")

        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals(3, terminada["procesados"].asInt())
        esLaConcatenacion(descargar(id).cuerpo, documentos(e, anio))
        assertEquals(listOf(resultado(id, anio)), runBlocking { almacen.listar(prefijoEmision(uuid)) })
    }

    @Test
    fun `a lote of a disabled user fails without retries`() {
        val anio = anio()
        val e = escenario(anio)
        val funcionario =
            funcionario(
                listOf(permiso(null, "READ"), permiso(EMISION_MASIVA, "CREATE"), permiso(EMISION_MASIVA, "UPDATE"), permiso(EMISION_LOTE, "CREATE"))
            )
        val id = emitir(anio, "PDF", funcionario)
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        val usuario = sujeto(funcionario)
        assertNotNull(runBlocking { identidad.de(usuario) })
        deshabilitar(usuario)

        grupo("viva")

        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        val errores = terminada["errores"].iterator().asSequence().toList()
        assertEquals(e.contribuyentes.map { e.codigos.getValue(it) }, errores.map { it["contribuyente"].asString() })
        assertTrue(
            errores.all { it["mensaje"].asString() == "el usuario que lanzó la emisión ya no existe o está deshabilitado" },
            terminada.toString()
        )
        assertTrue(lotesDe(id).all { it.estado == "FALLIDO" && it.intentos == 1 }, "${lotesDe(id)}")
        assertEquals(3, terminada["procesados"].asInt())
    }

    @Test
    fun `the identity of a lote is its creator's as the jwt filter would build it, and none for an unknown user`() {
        val admin = admin()

        val autenticacion = runBlocking { identidad.de(admin) }

        val jwt = (autenticacion as JwtAuthenticationToken).principal as Jwt
        assertEquals(admin.toString(), jwt.subject)
        assertEquals(organizacionDe(admin).toString(), jwt.getClaimAsString("org"))
        assertEquals("admin@wasichai.local", jwt.getClaimAsString("email"))
        assertTrue("ADMIN" in jwt.getClaimAsStringList("roles").orEmpty(), "${jwt.claims}")
        assertNull(runBlocking { identidad.de(UUID.randomUUID()) })
    }

    @Test
    fun `with one worker and one lote the order is the one of today`() {
        val anio = anio()
        val e = escenario(anio)
        // a single lote with every contribuyente of the padrón, as lote >= total cuts it
        val id =
            post(
                "/api/objects/emision_masiva/records",
                mapOf(
                    "attributes" to
                        mapOf(
                            "anio" to anio,
                            "formato" to "PDF",
                            "estado" to "EN_PROCESO",
                            "total" to 3,
                            "procesados" to 0,
                            "latido" to Instant.now().toString()
                        )
                )
            )["id"].asString()
        post(
            "/api/objects/emision_lote/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "emision" to id,
                        "numero" to 1,
                        "contribuyentes" to contribuyentesJson(aEmitir(e)),
                        "estado" to "PENDIENTE",
                        "intentos" to 0,
                        "procesados" to 0
                    )
            )
        )

        grupo("sola", cantidad = 1)

        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals(3, terminada["procesados"].asInt())
        esLaConcatenacion(descargar(id).cuerpo, documentos(e, anio))
    }

    // a started group of this context playing an instance, with its own work dir
    private fun grupo(
        instancia: String,
        cantidad: Int = 1
    ): GrupoTrabajadores =
        trabajadores
            .grupo(instancia, cantidad, Path.of("build/emisiones-test-grupos", instancia), documentos)
            .also {
                grupos += it
                it.iniciar()
            }

    private fun resultado(
        id: String,
        anio: Int
    ) = claveResultado(UUID.fromString(id), anio, FormatoEmision.PDF)

    // something under a lote's parte key, as a dead instance may leave it
    private fun parte(
        id: String,
        numero: Int,
        contenido: String
    ) {
        val archivo = Files.createTempFile("parte", ".pdf").also { it.writeText(contenido) }
        runBlocking { almacen.guardar(claveParte(UUID.fromString(id), numero, FormatoEmision.PDF), archivo) }
    }

    // the emission ENSAMBLANDO by an instance that beat `hace` ago, straight on its table
    private fun ensamblando(
        id: UUID,
        hace: String
    ) {
        runBlocking {
            for (t in tablas.de(EMISION_MASIVA, listOf("estado", "latido"))) {
                db
                    .sql(
                        "UPDATE ${t.tabla} SET ${t.columna("estado")} = 'ENSAMBLANDO', ${t.columna("latido")} = now() - CAST(:hace AS interval) WHERE id = :id"
                    ).bind("hace", hace)
                    .bind("id", id)
                    .fetch()
                    .awaitRowsUpdated()
            }
        }
    }

    private fun admin(): UUID = sujeto(token)

    // the user of a bearer token: its jwt's subject
    private fun sujeto(bearer: String): UUID {
        val carga = String(Base64.getUrlDecoder().decode(bearer.removePrefix("Bearer ").split('.')[1]))
        return UUID.fromString(tree(carga)["sub"].asString())
    }

    private fun organizacionDe(usuario: UUID): UUID =
        runBlocking {
            db
                .sql("SELECT organization_id FROM ${schemas.metadata}.users WHERE id = :id")
                .bind("id", usuario)
                .map { row, _ -> row.get("organization_id", UUID::class.java)!! }
                .one()
                .awaitSingle()
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
        // a year of its own per test, far from the padrón's and from the other classes'
        private val anios = AtomicInteger(4900 + (0..90).random() * 10)

        fun anio() = anios.getAndIncrement()
    }
}
