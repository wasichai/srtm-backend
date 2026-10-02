package srtm.emision

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.TestPropertySource
import srtm.impuesto.ParametroTributario
import srtm.impuesto.ParametrosTributarios
import srtm.rentas.Registros
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

// wasichai/srtm-backend#55: the masiva reads the year's parámetros once per lote and hands them to every HR, while the
// individual HR and the liquidación keep reading them on each call. a double of ParametrosTributarios counts the reads;
// lotes of 2, and no worker of the context's own: only this test's group takes lotes
@Import(ParametrosPorLoteApiTest.Dobles::class)
@TestPropertySource(
    properties = [
        "srtm.emision.dir=build/emisiones-test",
        "srtm.emision.trabajadores=0",
        "srtm.emision.lote=2",
        "srtm.emision.espera=200ms"
    ]
)
class ParametrosPorLoteApiTest : ConEscenarioApiTest() {
    @Autowired
    lateinit var trabajadores: TrabajadoresEmision

    private val grupos = mutableListOf<GrupoTrabajadores>()

    @TestConfiguration
    class Dobles {
        @Bean
        @Primary
        fun parametrosTributarios(registros: Registros): ParametrosTributarios = Contador(registros)
    }

    class Contador(
        registros: Registros
    ) : ParametrosTributarios(registros) {
        override suspend fun todos(): List<ParametroTributario> {
            lecturas.incrementAndGet()
            return super.todos()
        }
    }

    // nothing of another class is left for the group to take: only this test's emission runs
    @BeforeEach
    fun soloLaDelTest() {
        runBlocking {
            for (t in tablas.de(EMISION_LOTE, listOf("estado"))) {
                db
                    .sql("UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDO' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO')")
                    .fetch()
                    .awaitRowsUpdated()
            }
            for (t in tablas.de(EMISION_MASIVA, listOf("estado"))) {
                db
                    .sql(
                        "UPDATE ${t.tabla} SET ${t.columna("estado")} = 'FALLIDA' WHERE ${t.columna("estado")} IN ('PENDIENTE', 'EN_PROCESO', 'ENSAMBLANDO')"
                    ).fetch()
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
    fun `a masiva reads the parametros once per lote, not once per contribuyente`() {
        val anio = anio()
        val e = escenario(anio)
        val id = emitir(anio, "PDF")
        esperar(id) { it["estado"].asString() == "EN_PROCESO" }
        lecturas.set(0)

        val grupo = trabajadores.grupo("parametros", 1, Path.of("build/emisiones-test-grupos", "parametros"))
        grupos += grupo
        grupo.iniciar()

        val terminada = esperar(id)
        assertEquals("TERMINADA", terminada["estado"].asString(), terminada.toString())
        assertEquals(0, terminada["errores"].size(), terminada.toString())
        // 3 contribuyentes in lotes of 2
        assertEquals(2, lotesDe(id).size)
        assertEquals(2, lecturas.get())
        // and the figures are the individual endpoint's
        esLaConcatenacion(descargar(id).cuerpo, documentos(e, anio))
    }

    @Test
    fun `the individual hr and the liquidacion still read them and still answer 422 with faltan`() {
        val anio = anio()
        val e = escenario(anio)
        val a = e.contribuyentes.first()
        lecturas.set(0)

        documento("/api/srtm/contribuyentes/$a/hr?anio=$anio")
        assertEquals(1, lecturas.get())
        send("GET", "/api/srtm/contribuyentes/$a/liquidacion?anio=$anio", null, HttpStatus.OK)
        assertEquals(2, lecturas.get())

        // a year with a declaración and no UIT
        val sinUit = anio()
        declarar(a, e.predios.getValue(a).first(), sinUit)
        val antes = lecturas.get()
        val (status, cuerpo) = exchange("GET", "/api/srtm/contribuyentes/$a/hr?anio=$sinUit", null)
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, status, cuerpo)
        assertTrue(tree(cuerpo)["faltan"].any { it.asString() == "UIT $sinUit" }, cuerpo)
        assertEquals(antes + 1, lecturas.get())
        // the liquidación answers 200 with its faltan
        val liquidacion = tree(send("GET", "/api/srtm/contribuyentes/$a/liquidacion?anio=$sinUit", null, HttpStatus.OK))
        assertTrue(liquidacion["faltan"].any { it.asString() == "UIT $sinUit" }, liquidacion.toString())
        assertEquals(antes + 2, lecturas.get())
    }

    private companion object {
        val lecturas = AtomicInteger()

        // a year of its own per test, far from the padrón's and from the other classes'
        private val anios = AtomicInteger(5900 + (0..90).random() * 10)

        fun anio() = anios.getAndIncrement()
    }
}
