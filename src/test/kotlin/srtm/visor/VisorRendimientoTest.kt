package srtm.visor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import srtm.Permisos
import srtm.brecha.BrechaService
import srtm.brecha.Catastro
import srtm.brecha.Catastros
import srtm.brecha.Categoria
import srtm.brecha.DecisionCatastral
import srtm.brecha.FilaBrecha
import srtm.brecha.RegistroCorrida
import srtm.impuesto.ParametroTributario
import srtm.impuesto.ParametrosTributarios
import srtm.impuesto.UIT
import srtm.rentas.Contribuyente
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.Registros
import srtm.rentas.USO_PREDIO
import srtm.rentas.UsoPredio
import srtm.reportes.ReportesService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.measureTimeMillis

// #263: the visor over a district of ~30 000 predios, ReportesService and VisorService as the app builds them, over
// fakes of what they read (the rows, the padrón, the catalog, the UIT, catastro's decisions): what a request costs in
// memory once its cache is full, step by step, and that such a request reads neither catastro nor the rows again.
// prints its times on stderr; the bounds are loose, a ci machine is slower
class VisorRendimientoTest {
    private val n = 30_000
    private val organizacion = UUID.randomUUID()
    private val corrida = RegistroCorrida(id = "c1", anio = 2026, estado = "TERMINADA", terminado = "2026-10-20T15:00:00Z")
    private val lecturasDeFilas = AtomicInteger()
    private val lecturasDeDecisiones = AtomicInteger()

    @Volatile
    private var demoraDeCatastro = 0L

    // made anew on every read, as the db would: what the caches keep is theirs only
    private fun brecha(): List<FilaBrecha> {
        val r = Random(263)
        val categorias = Categoria.entries
        return (0 until n).map { i ->
            val delta = BigDecimal(r.nextInt(-200_000, 400_000)).setScale(2)
            val x = -75.7 + (i % 300) * 0.0005
            val y = -14.0 + (i / 300) * 0.0005
            FilaBrecha(
                id = "b$i",
                corrida = "c1",
                predio = "p$i",
                codigoPredio = "%02d-%03d-%04d".format(i % 12, i % 300, i),
                codigoCatastro = "CM-$i",
                categoria = categorias[r.nextInt(categorias.size)].name,
                sector = "%02d".format(i % 12),
                manzana = "M${i % 300}",
                lote = "${i % 40}",
                uso = listOf("010101", "020101", "030201")[i % 3],
                deltaAutoavaluo = delta,
                deltaImpuesto = delta.movePointLeft(2),
                confianza = listOf("CONFIABLE", "DUDOSO", "SIN_LEVANTAR")[i % 3],
                nivelesComparados = 2,
                // a lote of a dozen vertices, as catastro draws them
                loteGeom =
                    mapOf(
                        "type" to "Polygon",
                        "coordinates" to listOf((0..12).map { k -> listOf(x + 0.0001 * Math.cos(k * 0.5), y + 0.0001 * Math.sin(k * 0.5)) })
                    ),
                orden = i
            )
        }
    }

    private fun <T> respuesta(
        inv: InvocationOnMock,
        valores: Map<String, (InvocationOnMock) -> Any?>
    ): Any? = valores[inv.method.name]?.invoke(inv) ?: if (inv.method.returnType == Boolean::class.javaPrimitiveType) false else null

    private fun mockDe(
        tipo: Class<*>,
        valores: Map<String, (InvocationOnMock) -> Any?>
    ) = mock(tipo) { respuesta<Any>(it, valores) }

    private val catastro =
        mockDe(
            Catastro::class.java,
            mapOf(
                "decisiones" to { inv ->
                    lecturasDeDecisiones.incrementAndGet()
                    // catastro's pages of decision_brecha take their time
                    Thread.sleep(demoraDeCatastro)
                    (0 until n step 7).map { DecisionCatastral("CM-$it", inv.arguments[0] as Int, "A_INSPECCIONAR") }
                }
            )
        ) as Catastro

    private fun servicios(vigenciaDecisiones: Duration = Duration.ofMinutes(2)): Pair<ReportesService, VisorService> {
        val usuario = AuthenticatedUser(UUID.randomUUID(), organizacion, "visor@prueba.local", listOf("ADMIN"), null)
        val currentUser = mockDe(CurrentUser::class.java, mapOf("require" to { _ -> usuario })) as CurrentUser
        val permisos = mockDe(Permisos::class.java, mapOf("puedeLeer" to { _ -> true }, "exigirLeer" to { _ -> Unit })) as Permisos
        val brechas =
            mockDe(
                BrechaService::class.java,
                mapOf(
                    "corridaDelAnio" to { _ -> corrida },
                    "filas" to { _ ->
                        lecturasDeFilas.incrementAndGet()
                        brecha()
                    }
                )
            ) as BrechaService
        val declaraciones =
            (0 until n).map { i -> Declaracion(id = "d$i", contribuyente = "k${i / 2}", predio = "p$i", anio = 2026, porcentajeCondominio = BigDecimal(100)) }
        val registros =
            mockDe(
                Registros::class.java,
                mapOf(
                    "all" to { inv ->
                        when (inv.arguments[0]) {
                            DECLARACION -> declaraciones
                            USO_PREDIO -> listOf(UsoPredio(codigo = "010101", uso = "CASA HABITACIÓN"))
                            else -> emptyList<Any>()
                        }
                    },
                    "byIds" to { inv ->
                        @Suppress("UNCHECKED_CAST")
                        (inv.arguments[2] as Collection<String>).associateWith { Contribuyente(id = it, nombreCompleto = "CONTRIBUYENTE $it") }
                    }
                )
            ) as Registros
        val parametros =
            mockDe(
                ParametrosTributarios::class.java,
                mapOf("todos" to { _ -> listOf(ParametroTributario(tipo = UIT, vigenciaDesde = LocalDate.of(2026, 1, 1), valorNumerico = BigDecimal(5500))) })
            ) as ParametrosTributarios
        val organizaciones = mock(OrganizationRepository::class.java)
        val catastros = Catastros(mapOf(organizacion.toString() to catastro))
        val reportes =
            ReportesService(
                brechas,
                registros,
                permisos,
                currentUser,
                catastros,
                organizaciones,
                parametros,
                Duration.ofMinutes(10),
                8,
                vigenciaDecisiones,
                Duration.ofSeconds(30)
            )
        return reportes to VisorService(reportes, permisos, registros, catastros, currentUser, organizaciones)
    }

    private val hilos = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun usada(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(100)
        }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    // the bytes every thread allocated while `bloque` ran: what a request leaves to the gc
    private suspend fun asignado(bloque: suspend () -> Unit): Long {
        val antes = hilos.totalThreadAllocatedBytes
        bloque()
        return hilos.totalThreadAllocatedBytes - antes
    }

    @Test
    fun `the panel and a page of predios of 30 000 rows, with the cache full, within a request and without reading again`() {
        val base = usada()
        val (reportes, visor) = servicios()
        val tiempos = linkedMapOf<String, Long>()
        val memoria = linkedMapOf<String, Long>()
        runBlocking {
            tiempos["panel en frío"] = measureTimeMillis { visor.panel(2026, null) }
            val filas = lecturasDeFilas.get()
            val decisiones = lecturasDeDecisiones.get()
            // what a warm panel does, step by step
            lateinit var l: srtm.reportes.Lectura
            tiempos["leer (reportes)"] = measureTimeMillis { l = reportes.leer(2026, null) }
            lateinit var deVisor: List<FilaVisor>
            tiempos["filas del visor"] = measureTimeMillis { deVisor = Visor.filas(l.filas, l.conDecisiones, l.umbral) }
            tiempos["agregados"] = measureTimeMillis { Visor.panel(l.corrida, deVisor, l.conDecisiones, l.avisos) }
            repeat(3) { tiempos["panel en caliente ${it + 1}"] = measureTimeMillis { visor.panel(2026, null) } }
            repeat(3) {
                tiempos["predios en caliente ${it + 1}"] =
                    measureTimeMillis { visor.predios(2026, null, FiltroVisor(), OrdenVisor.DELTA_DESC) }
            }
            tiempos["resumen de reportes en caliente"] = measureTimeMillis { reportes.resumen(2026, null) }
            // the rows, the titulares, the lotes (read with the rows) and what is made of them
            memoria["caché"] = usada() - base
            memoria["asignado por panel"] = asignado { visor.panel(2026, null) }
            memoria["asignado por predios"] = asignado { visor.predios(2026, null, FiltroVisor(), OrdenVisor.DELTA_DESC) }
            memoria["asignado por resumen"] = asignado { reportes.resumen(2026, null) }
            System.err.println("visor, $n filas: $tiempos ms; ${memoria.mapValues { "${it.value / (1024 * 1024)} MiB" }}")
            assertEquals(filas, lecturasDeFilas.get(), "un pedido con caché volvió a leer las filas")
            assertEquals(decisiones, lecturasDeDecisiones.get(), "un pedido con caché volvió a leer catastro")
        }
        assertTrue((1..3).all { tiempos.getValue("panel en caliente $it") < 2_000 }, "$tiempos")
        assertTrue((1..3).all { tiempos.getValue("predios en caliente $it") < 1_000 }, "$tiempos")
    }

    // the visor's page asks for the panel, the predios and the lotes at once: with catastro's decisions expired, the
    // three wait for one read of them
    @Test
    fun `three requests at once with the decisions expired read catastro once`() {
        val (_, visor) = servicios(vigenciaDecisiones = Duration.ofMillis(300))
        runBlocking {
            visor.panel(2026, null)
            Thread.sleep(400)
            demoraDeCatastro = 300
            val antes = lecturasDeDecisiones.get()
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { visor.panel(2026, null) },
                    async(Dispatchers.Default) { visor.predios(2026, null, FiltroVisor(), OrdenVisor.DELTA_DESC) },
                    async(Dispatchers.Default) { visor.lotes(2026, null, FiltroVisor()) }
                ).awaitAll()
            }
            assertEquals(antes + 1, lecturasDeDecisiones.get())
        }
    }
}
