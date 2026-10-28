package srtm.visor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.util.ResourceLeakDetector
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.netty.DisposableServer
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import srtm.Permisos
import srtm.brecha.Catastros
import srtm.brecha.ClienteCatastro
import srtm.brecha.CuentaCatastro
import srtm.brecha.ImagenWms
import srtm.rentas.Registros
import srtm.reportes.ReportesService
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

// #264: the visor's WMS relay (ClienteCatastro.wms and VisorController /wms) over real netty on both sides, with netty's
// leak detector in PARANOID: a stub of catastro-ui's nginx streams each image in many chunks, srtm relays it, and a
// client reads many of them, some whole and some cancelled half way (a map panned before its tiles arrive). no
// IllegalReferenceCountException nor LEAK may reach the log, and a whole image comes as it went
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReenvioWmsTest {
    private val avisos = CopyOnWriteArrayList<String>()
    private val raiz = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val captura =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(e: ILoggingEvent) {
                val texto = e.formattedMessage + (e.throwableProxy?.let { "\n" + ThrowableProxyUtil.asString(it) } ?: "")
                if (e.level.isGreaterOrEqual(Level.WARN) || "LEAK" in texto || "IllegalReferenceCount" in texto) avisos += texto
            }
        }
    private lateinit var nivel: ResourceLeakDetector.Level
    private lateinit var catastro: DisposableServer
    private lateinit var srtm: DisposableServer
    private lateinit var contexto: AnnotationConfigApplicationContext

    @BeforeAll
    fun arrancar() {
        nivel = ResourceLeakDetector.getLevel()
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
        captura.start()
        raiz.addAppender(captura)
        catastro =
            HttpServer
                .create()
                .host("127.0.0.1")
                .port(0)
                .route { r ->
                    r.post("/api/auth/token") { req, res ->
                        req.receive().then(res.header("Content-Type", "application/json").sendString(Mono.just(TOKEN)).then())
                    }
                    r.get("/geoserver/catastro/wms") { req, res ->
                        val modo = req.uri().substringAfter("modo=").substringBefore("&")
                        val trozos =
                            Flux
                                .range(0, TROZOS)
                                .delayElements(Duration.ofMillis(1))
                                .map { Unpooled.wrappedBuffer(trozo(it)) }
                        if (modo == "corta") {
                            // catastro drops the connection half way
                            res
                                .header("Content-Type", "image/png")
                                .header("Content-Length", "${TROZOS * 4096}")
                                .send(trozos.take(5))
                                .then(Mono.fromRunnable<Void> { res.withConnection { it.dispose() } })
                        } else if (modo == "entera") {
                            res.header("Content-Type", "image/png").header("Content-Length", "${TROZOS * 4096}").send(trozos)
                        } else {
                            res.header("Content-Type", "image/png").send(trozos)
                        }
                    }
                }.bindNow()
        val cliente =
            ClienteCatastro(
                CuentaCatastro(
                    url = "http://127.0.0.1:${catastro.port()}/",
                    clientId = "cid",
                    clientSecret = "secreto",
                    wmsUrl = "http://127.0.0.1:${catastro.port()}/geoserver/catastro/wms"
                )
            )
        contexto = AnnotationConfigApplicationContext()
        contexto.registerBean(ClienteCatastro::class.java, Supplier { cliente })
        contexto.register(Web::class.java)
        contexto.refresh()
        srtm =
            HttpServer
                .create()
                .host("127.0.0.1")
                .port(0)
                .handle(ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(contexto).build()))
                .bindNow()
    }

    @AfterAll
    fun parar() {
        srtm.disposeNow()
        catastro.disposeNow()
        contexto.close()
        raiz.detachAppender(captura)
        ResourceLeakDetector.setLevel(nivel)
    }

    private val http by lazy { HttpClient.create().baseUrl("http://127.0.0.1:${srtm.port()}") }
    private val esperado = TROZOS * 4096

    // PEDIDOS GetMaps of `modo` (catastro's answer: entera, with its length; sin_largo, chunked; corta, dropped half
    // way), 16 at a time, each read by `leer`. a leak may be reported in a later test: the class passes or fails whole
    private fun reenviar(
        modo: String,
        leer: (HttpClient.ResponseReceiver<*>, Int) -> Mono<Int>
    ): List<Int> {
        val leidos =
            Flux
                .range(0, PEDIDOS)
                .flatMap({ i -> leer(http.get().uri("/api/srtm/visor/wms?modo=$modo&n=$i"), i) }, 16)
                .collectList()
                .block(Duration.ofSeconds(60))!!
        forzarInformeDeFugas()
        assertTrue(
            avisos.none {
                "IllegalReferenceCount" in it || "LEAK" in it
            },
            avisos.filter { "IllegalReferenceCount" in it || "LEAK" in it }.joinToString("\n---\n")
        )
        return leidos
    }

    private fun entera(r: HttpClient.ResponseReceiver<*>): Mono<Int> =
        r
            .responseContent()
            .aggregate()
            .asByteArray()
            .map { it.size }

    @Test
    fun `whole images come as they went`() {
        assertTrue(reenviar("entera") { r, _ -> entera(r) }.all { it == esperado })
        assertTrue(reenviar("sin_largo") { r, _ -> entera(r) }.all { it == esperado })
    }

    @Test
    fun `a map that moves on closes its requests half way`() {
        reenviar("entera") { r, i -> r.responseContent().take((i % 6).toLong()).then(Mono.just(0)) }
    }

    @Test
    fun `a request closed before a byte came`() {
        reenviar("entera") { _, i ->
            Mono
                .fromCallable {
                    // a bare socket: only srtm's side of the relay is netty's
                    Socket("127.0.0.1", srtm.port()).use { s ->
                        if (i % 2 == 0) s.setSoLinger(true, 0)
                        s.getOutputStream().write("GET /api/srtm/visor/wms?modo=entera&n=$i HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                        s.getOutputStream().flush()
                        s.getInputStream().readNBytes(i % 5 * 20000 + 1)
                    }
                    0
                }.subscribeOn(Schedulers.boundedElastic())
        }
    }

    @Test
    fun `catastro drops the connection half way`() {
        reenviar("corta") { r, _ -> r.responseContent().then(Mono.just(0)).onErrorReturn(0) }
    }

    // netty reports a leak when a tracked buffer is collected and a later one is allocated: collect, then allocate
    private fun forzarInformeDeFugas() {
        repeat(10) {
            System.gc()
            Thread.sleep(200)
            repeat(200) { ByteBufAllocator.DEFAULT.buffer(1024).release() }
        }
    }

    @Configuration
    @EnableWebFlux
    open class Web {
        @Bean
        open fun visorController(cliente: ClienteCatastro): VisorController =
            VisorController(
                object : VisorService(
                    mock(ReportesService::class.java),
                    mock(Permisos::class.java),
                    mock(Registros::class.java),
                    mock(Catastros::class.java),
                    mock(CurrentUser::class.java),
                    mock(OrganizationRepository::class.java)
                ) {
                    // VisorService.wms once the GetMap is checked: catastro's image as ClienteCatastro.wms gives it
                    override suspend fun wms(parametros: Map<String, List<String>>): ImagenWms =
                        cliente.wms(
                            listOf("modo" to parametros.getValue("modo").single())
                        )
                }
            )
    }

    private companion object {
        const val TOKEN = """{"token":"t1","expiresAt":"2999-01-01T00:00:00Z"}"""
        const val TROZOS = 40
        const val PEDIDOS = 300

        fun trozo(i: Int): ByteArray = ByteArray(4096) { (i + it).toByte() }
    }
}
