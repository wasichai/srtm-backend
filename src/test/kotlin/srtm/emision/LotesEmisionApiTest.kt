package srtm.emision

import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import srtm.rentas.SrtmApiTest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// the lotes of a masiva and their coordination through postgres (wasichai/srtm-backend#53, #54): which instance takes
// a lote, who assembles an emission, and what is left of a dead one. the records are created through core, the
// transitions are the beans'. every test emits its own year: the test db is shared
class LotesEmisionApiTest : SrtmApiTest() {
    @Autowired
    lateinit var lotes: LotesEmision

    @Autowired
    lateinit var estados: EstadoEmisiones

    @Autowired
    lateinit var tablas: TablasCore

    @Autowired
    lateinit var db: DatabaseClient

    @Autowired
    lateinit var conexiones: ConnectionFactory

    private val lease = Duration.ofMinutes(2)

    // a lote left takeable by another test would be taken by this one's: none is
    @BeforeEach
    fun sinLotesPendientes() {
        runBlocking {
            for (t in tablas.de("emision_lote", listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDO' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO')")
                    .fetch()
                    .awaitRowsUpdated()
            }
        }
    }

    @Test
    fun `two takers never take the same lote`() {
        val emision = emision(anio(), "EN_PROCESO", latido = Instant.now())
        val creados = (1..3).map { lote(emision, it) }

        val tomados =
            runBlocking {
                (1..6)
                    .map { i -> async(Dispatchers.IO) { lotes.tomar(if (i % 2 == 0) "A" else "B", lease) } }
                    .awaitAll()
            }

        val ids = tomados.filterNotNull().map { it.id.toString() }
        assertEquals(3, ids.size, "$tomados")
        assertEquals(creados.toSet(), ids.toSet())
        assertEquals(3, tomados.count { it == null })
        val primero = tomados.filterNotNull().first { it.numero == 1 }
        assertEquals(UUID.fromString(emision), primero.emision)
        assertEquals(1, primero.intentos)
        assertEquals(FormatoEmision.PDF, primero.formato)
        assertEquals(listOf("C001"), primero.contribuyentes.map { it.codigo })
        assertNotNull(primero.creadoPor)
    }

    @Test
    fun `a lote another taker holds is skipped, not waited for`() {
        val emision = emision(anio(), "EN_PROCESO", latido = Instant.now())
        val primero = lote(emision, 1)
        val segundo = lote(emision, 2)
        val t = runBlocking { tablas.de("emision_lote", listOf("estado")) }.first { it.organizacion == organizacion(emision) }
        // another taker's transaction, mid-way: lote 1's row locked on a connection of its own
        val ajena = runBlocking { conexiones.create().awaitSingle() }
        try {
            runBlocking {
                ajena.beginTransaction().awaitFirstOrNull()
                ajena
                    .createStatement("SELECT id FROM ${t.tabla} WHERE id = $1 FOR UPDATE")
                    .bind("$1", UUID.fromString(primero))
                    .execute()
                    .awaitSingle()
                    .rowsUpdated
                    .awaitFirstOrNull()
            }

            val tomado = runBlocking { withTimeout(10_000) { lotes.tomar("A", lease) } }

            assertEquals(segundo, tomado?.id?.toString())
        } finally {
            runBlocking {
                ajena.rollbackTransaction().awaitFirstOrNull()
                ajena.close().awaitFirstOrNull()
            }
        }
    }

    @Test
    fun `a lote whose latido expired is taken again and counts one more attempt`() {
        val anio = anio()
        val emision = emision(anio, "EN_PROCESO", latido = Instant.now())
        val muerto =
            lote(
                emision,
                1,
                "estado" to "EN_PROCESO",
                "tomado_por" to "muerta",
                "intentos" to 1,
                "latido" to Instant.now().minus(1, ChronoUnit.HOURS).toString()
            )

        val tomado = runBlocking { lotes.tomar("viva", lease) }

        assertNotNull(tomado)
        assertEquals(muerto, tomado!!.id.toString())
        assertEquals(2, tomado.intentos)
        assertEquals(anio, tomado.anio)
        // the dead one's lote is not its own any more
        assertFalse(runBlocking { lotes.latir(tomado, "muerta") })
        assertTrue(runBlocking { lotes.latir(tomado, "viva") })
        assertNull(runBlocking { lotes.tomar("otra", lease) })
    }

    @Test
    fun `a released lote is taken again with one more attempt`() {
        val emision = emision(anio(), "EN_PROCESO", latido = Instant.now())
        lote(emision, 1)
        val tomado = runBlocking { lotes.tomar("A", lease) }!!

        assertTrue(runBlocking { lotes.liberar(tomado, "A") })

        val otra = runBlocking { lotes.tomar("B", lease) }!!
        assertEquals(tomado.id, otra.id)
        assertEquals(2, otra.intentos)
        assertFalse(runBlocking { lotes.latir(tomado, "A") })
    }

    @Test
    fun `lotes of an emission not en proceso are not taken`() {
        val emision = emision(anio(), "PENDIENTE", latido = Instant.now())
        lote(emision, 1)

        assertNull(runBlocking { lotes.tomar("A", lease) })
    }

    @Test
    fun `a lote no longer ours cannot beat nor end`() {
        val emision = emision(anio(), "EN_PROCESO", latido = Instant.now())
        lote(emision, 1)
        lote(emision, 2)
        val tomado = runBlocking { lotes.tomar("A", lease) }!!
        assertTrue(runBlocking { lotes.latir(tomado, "A") })

        val organizacion = tomado.organizacion
        assertEquals(2, runBlocking { lotes.cancelar(organizacion, tomado.emision) })

        assertFalse(runBlocking { lotes.latir(tomado, "A") })
        assertFalse(runBlocking { lotes.avanzar(tomado, "A", 1, emptyList()) })
        assertFalse(runBlocking { lotes.terminar(tomado, "A", 1, 2, emptyList(), "emision-x/parte-00001.pdf") })
        assertEquals("FALLIDO", runBlocking { lotes.estado(tomado) })
        assertNull(runBlocking { lotes.tomar("A", lease) })
        // and once its rows are gone, it is not there at all
        assertEquals(2, runBlocking { lotes.borrar(organizacion, tomado.emision) })
        assertNull(runBlocking { lotes.estado(tomado) })
    }

    @Test
    fun `an ended lote keeps its part, its count and its errores`() {
        val emision = emision(anio(), "EN_PROCESO", latido = Instant.now())
        lote(emision, 2)
        lote(emision, 1)
        val tomado = runBlocking { lotes.tomar("A", lease) }!!
        val errores = listOf(ErrorEmision("C001", "sin parámetros"))

        assertTrue(runBlocking { lotes.terminar(tomado, "A", 1, 3, errores, "emision-$emision/parte-00001.pdf") })

        val partes = runBlocking { lotes.partes(tomado.organizacion, tomado.emision) }
        assertEquals(listOf(1, 2), partes.map { it.numero })
        assertEquals("TERMINADO", partes[0].estado)
        assertEquals(1, partes[0].procesados)
        assertEquals(3, partes[0].documentos)
        assertEquals(errores, partes[0].errores)
        assertEquals("emision-$emision/parte-00001.pdf", partes[0].parte)
        assertEquals("PENDIENTE", partes[1].estado)
        assertNull(partes[1].parte)
        // an ended lote is no longer anybody's
        assertFalse(runBlocking { lotes.fallar(tomado, "A", errores) })
    }

    @Test
    fun `the assembly is claimed exactly once`() {
        val emision = emision(anio(), "EN_PROCESO", latido = Instant.now())
        val id = UUID.fromString(emision)
        lote(emision, 1, "estado" to "TERMINADO")
        val pendiente = lote(emision, 2)
        val organizacion = organizacion(emision)

        assertNull(runBlocking { estados.reclamarEnsamblado(lease, organizacion, id) })

        terminado(pendiente)
        val reclamos =
            runBlocking {
                (1..8)
                    .map { async(Dispatchers.IO) { estados.reclamarEnsamblado(lease, organizacion, id) } }
                    .awaitAll()
            }

        val ganador = reclamos.filterNotNull()
        assertEquals(1, ganador.size, "$reclamos")
        assertEquals(id, ganador[0].id)
        assertEquals(FormatoEmision.PDF, ganador[0].formato)
        assertEquals("ENSAMBLANDO", job(emision)["estado"].asString())
        val estado = cambio(emision, "estado")
        assertEquals("EN_PROCESO", estado["before"].asString())
        assertEquals("ENSAMBLANDO", estado["after"].asString())
    }

    @Test
    fun `a stale assembly is claimed again and the old owner loses it`() {
        val emision = emision(anio(), "ENSAMBLANDO", latido = Instant.now().minus(1, ChronoUnit.HOURS))
        val id = UUID.fromString(emision)
        lote(emision, 1, "estado" to "TERMINADO")
        val organizacion = organizacion(emision)
        val viejo = EmisionAEnsamblar(id, organizacion, 0, FormatoEmision.PDF, latido(organizacion, id))

        val nuevo = runBlocking { estados.reclamarEnsamblado(lease, organizacion, id) }

        assertNotNull(nuevo)
        assertNull(runBlocking { estados.latirEnsamblado(viejo) })
        assertFalse(runBlocking { estados.terminar(viejo, "emision.pdf", 3, 1, emptyList()) })
        // the new one's token round-trips: its beat and its end are its own
        val latido = runBlocking { estados.latirEnsamblado(nuevo!!) }
        assertNotNull(latido)
        assertNull(runBlocking { estados.latirEnsamblado(nuevo!!) })
        val errores = listOf(ErrorEmision("C001", "sin parámetros"))
        assertTrue(runBlocking { estados.terminar(latido!!, "emision-${nuevo!!.anio}-$id.pdf", 3, 1, errores) })
        val job = job(emision)
        assertEquals("TERMINADA", job["estado"].asString(), job.toString())
        assertEquals(1, job["procesados"].asInt())
        assertEquals(3, job["tamano"].asLong())
        assertEquals("C001", job["errores"][0]["contribuyente"].asString())
        assertTrue(job["terminado"].isString, job.toString())
        assertNull(runBlocking { estados.reclamarEnsamblado(lease, organizacion, id) })
    }

    @Test
    fun `a job of the previous version is failed, never assembled`() {
        val emision = emision(anio(), "EN_PROCESO", latido = null)
        val id = UUID.fromString(emision)
        val organizacion = organizacion(emision)

        assertNull(runBlocking { estados.reclamarEnsamblado(lease, organizacion, id) })
        val fallidas = runBlocking { estados.fallarAbandonadas(lease) }

        assertTrue(organizacion to id in fallidas, "$fallidas")
        val job = job(emision)
        assertEquals("FALLIDA", job["estado"].asString(), job.toString())
        assertEquals("interrumpida: el proceso que la corría ya no está", job["mensaje"].asString())
        assertTrue(job["terminado"].isString, job.toString())
        val sistema = actualizacion(emision)
        assertTrue(sistema["userEmail"] == null || sistema["userEmail"].isNull, sistema.toString())
        val estado = cambio(emision, "estado")
        assertEquals("EN_PROCESO", estado["before"].asString())
        assertEquals("FALLIDA", estado["after"].asString())
    }

    @Test
    fun `a pending emission whose preparer died is failed with its lotes, a live one is not`() {
        val anio = anio()
        val muerta = emision(anio, "PENDIENTE", latido = Instant.now().minus(1, ChronoUnit.HOURS))
        val viva = emision(anio, "PENDIENTE", latido = Instant.now())
        val lote = lote(muerta, 1)
        val organizacion = organizacion(muerta)

        val fallidas = runBlocking { estados.fallarAbandonadas(lease) }

        assertTrue(organizacion to UUID.fromString(muerta) in fallidas, "$fallidas")
        assertEquals("FALLIDA", job(muerta)["estado"].asString())
        assertEquals("PENDIENTE", job(viva)["estado"].asString())
        assertEquals("FALLIDO", registro("emision_lote", lote)["estado"].asString())
        assertEquals(listOf(UUID.fromString(viva)), runBlocking { estados.activas(organizacion, anio) }.map { it.first })
    }

    @Test
    fun `an emission goes from pendiente to en proceso once, and fails from any active state`() {
        val anio = anio()
        val emision = emision(anio, "PENDIENTE", latido = null)
        val id = UUID.fromString(emision)
        val organizacion = organizacion(emision)

        assertTrue(runBlocking { estados.latirPreparacion(organizacion, id) })
        assertTrue(runBlocking { estados.iniciar(organizacion, id, 7, null) })
        assertFalse(runBlocking { estados.iniciar(organizacion, id, 7, null) })
        assertFalse(runBlocking { estados.latirPreparacion(organizacion, id) })
        val job = job(emision)
        assertEquals("EN_PROCESO", job["estado"].asString())
        assertEquals(7, job["total"].asInt())
        assertNotNull(latido(organizacion, id))
        assertEquals(listOf(id), runBlocking { estados.activas(organizacion, anio) }.map { it.first })

        assertTrue(runBlocking { estados.fallar(organizacion, id, "sin padrón") })
        assertFalse(runBlocking { estados.fallar(organizacion, id, "otra vez") })
        assertEquals("sin padrón", job(emision)["mensaje"].asString())
        assertEquals(emptyList<Pair<UUID, Instant>>(), runBlocking { estados.activas(organizacion, anio) })
    }

    @Test
    fun `the progress is the sum of the lotes`() {
        val emision = emision(anio(), "EN_PROCESO", latido = Instant.now())
        lote(emision, 1)
        lote(emision, 2)
        val a = runBlocking { lotes.tomar("A", lease) }!!
        val b = runBlocking { lotes.tomar("B", lease) }!!

        assertTrue(runBlocking { lotes.avanzar(a, "A", 2, emptyList()) })
        assertTrue(runBlocking { lotes.avanzar(b, "B", 3, listOf(ErrorEmision("C002", "sin parámetros"))) })

        assertEquals(5, job(emision)["procesados"].asInt())
    }

    // an emission as core keeps it, in `estado`, with its latido: its id
    private fun emision(
        anio: Int,
        estado: String,
        latido: Instant?
    ): String =
        post(
            "/api/objects/emision_masiva/records",
            mapOf(
                "attributes" to
                    buildMap {
                        put("anio", anio)
                        put("formato", "PDF")
                        put("estado", estado)
                        put("total", 0)
                        put("procesados", 0)
                        if (latido != null) put("latido", latido.toString())
                    }
            )
        )["id"].asString()

    // a lote of `emision` with one contribuyente, PENDIENTE unless `extra` says otherwise: its id
    private fun lote(
        emision: String,
        numero: Int,
        vararg extra: Pair<String, Any?>
    ): String {
        val contribuyente = ContribuyenteAEmitir(UUID.randomUUID(), "C%03d".format(numero), "CONTRIBUYENTE $numero", listOf(UUID.randomUUID()))
        return post(
            "/api/objects/emision_lote/records",
            mapOf(
                "attributes" to
                    mapOf(
                        "emision" to emision,
                        "numero" to numero,
                        "contribuyentes" to contribuyentesJson(listOf(contribuyente)),
                        "estado" to "PENDIENTE"
                    ) + extra
            )
        )["id"].asString()
    }

    private fun job(id: String) = tree(send("GET", "/api/srtm/emisiones/$id", null, HttpStatus.OK))

    private fun registro(
        objeto: String,
        id: String
    ) = tree(send("GET", "/api/objects/$objeto/records/$id", null, HttpStatus.OK)).let { it["attributes"] ?: it }

    // the emission's organization: the one whose table has it
    private fun organizacion(emision: String): UUID =
        runBlocking {
            tablas
                .de(EMISION_MASIVA, listOf("estado"))
                .first { t ->
                    db
                        .sql("SELECT count(*) AS n FROM ${t.tabla} WHERE id = :id")
                        .bind("id", UUID.fromString(emision))
                        .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
                        .one()
                        .awaitSingle() > 0
                }.organizacion
        }

    // the emission's latido as its row has it
    private fun latido(
        organizacion: UUID,
        id: UUID
    ): OffsetDateTime =
        runBlocking {
            val t = tablas.de(EMISION_MASIVA, listOf("latido")).first { it.organizacion == organizacion }
            db
                .sql("SELECT ${t.columna("latido")} AS latido FROM ${t.tabla} WHERE id = :id")
                .bind("id", id)
                .map { row, _ -> row.get("latido", OffsetDateTime::class.java)!! }
                .one()
                .awaitSingle()
        }

    // a lote TERMINADO, straight on its table: core's PUT replaces the whole record
    private fun terminado(lote: String) {
        runBlocking {
            for (t in tablas.de("emision_lote", listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'TERMINADO' WHERE id = :id")
                    .bind("id", UUID.fromString(lote))
                    .fetch()
                    .awaitRowsUpdated()
            }
        }
    }

    // the last system UPDATE of the emission in core's audit log
    private fun actualizacion(id: String) =
        tree(send("GET", "/api/objects/emision_masiva/records/$id/history", null, HttpStatus.OK))
            .iterator()
            .asSequence()
            .first { it["operation"].asString() == "UPDATE" }

    private fun cambio(
        id: String,
        campo: String
    ) = actualizacion(id)["changes"].iterator().asSequence().first { it["field"].asString() == campo }

    private companion object {
        // a year of its own per test, far from the padrón's and from EmisionMasivaApiTest's
        private val anios = AtomicInteger(3900 + (0..90).random() * 10)

        fun anio() = anios.getAndIncrement()
    }
}
