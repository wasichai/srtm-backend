package srtm.emision

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

private const val TERMINADO = "TERMINADO"
private const val FALLIDO = "FALLIDO"

internal const val SIN_USUARIO = "el usuario que lanzó la emisión ya no existe o está deshabilitado"

// how long a stop waits for the workers to let their lotes go
private val DETENER: Duration = Duration.ofSeconds(30)

// a worker lost its lote, its assembly or its preparation while working on it: another take has it now, or it was
// cancelled or deleted. a cancellation of the work only, never of the worker
internal class Perdido : CancellationException("el trabajo ya no es de este trabajador")

private val logLatido = LoggerFactory.getLogger("srtm.emision.Latido")

// `trabajo` while `latir` renews its lease every `cada`; null if a beat (or the work itself, throwing Perdido) found
// it lost, and then the work is cancelled. a beat that fails (the db, a moment) is not a loss: the next one tries
// again, and the lease covers four. no beat is in flight when it returns, so what the caller writes next (with the
// token a beat renewed) never races one
internal suspend fun <T> conLatido(
    cada: Duration,
    latir: suspend () -> Boolean,
    trabajo: suspend () -> T
): T? =
    coroutineScope {
        val turno = Mutex()
        var fin = false
        val hecho = async { trabajo() }
        val latido =
            launch {
                while (true) {
                    delay(cada.toMillis().coerceAtLeast(1))
                    val sigue =
                        turno.withLock {
                            if (fin) return@launch
                            try {
                                latir()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                logLatido.warn("no se pudo renovar un lease de la emisión masiva", e)
                                true
                            }
                        }
                    if (!sigue) {
                        hecho.cancel(Perdido())
                        return@launch
                    }
                }
            }
        try {
            hecho.await()
        } catch (_: Perdido) {
            null
        } finally {
            withContext(NonCancellable) { turno.withLock { fin = true } }
            latido.cancel()
        }
    }

// the workers of one instance (wasichai/srtm-backend#53, #54): `cantidad` coroutines that take the lotes of any
// emission of any organization, generate their partes and assemble the emissions whose lotes all ended, coordinated
// with the other instances' only through postgres (LotesEmision, EstadoEmisiones) and the almacén. `instancia` is
// what the lotes they take say in tomado_por; `temporales` is this group's own work dir, local and disposable.
//
// a cycle: the emissions nobody works on any more are failed; one to assemble is claimed and assembled; else a lote
// is taken and generated; with nothing done the worker waits `espera`, or until despertar(). a lote is generated as
// the user who created it (IdentidadEmision); the system's writes need nobody. every write of a lote is guarded by
// its take and every write of an assembly by its token: one that lost it (lease expired and retaken, or the emission
// cancelled or deleted) stops and touches nothing of the new owner's
class GrupoTrabajadores(
    val instancia: String,
    private val cantidad: Int,
    private val temporales: Path,
    private val config: EmisionProperties,
    private val lotes: LotesEmision,
    private val estado: EstadoEmisiones,
    private val almacen: AlmacenEmision,
    documentos: DocumentosDeEmision,
    merger: PdfMerger,
    private val identidad: IdentidadEmision,
    private val retencion: RetencionEmision
) {
    private val generador = GeneradorEmision(documentos, merger)
    private val ensamblador = EnsambladorEmision(almacen, merger)
    private val lease = config.lease
    private val cadaLatido = lease.dividedBy(4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("emision-$instancia"))

    // one per worker: despertar rings them all, and a worker busy when it rang looks again as soon as it is done
    private val timbres = List(cantidad) { Channel<Unit>(Channel.CONFLATED) }

    // the leftovers of a worker of this dir that died go first: none of the group's runs yet
    fun iniciar() {
        val restos = limpiarTemporales(temporales)
        if (restos > 0) log.info("{} temporales de emisiones masivas borrados de {}", restos, temporales)
        timbres.forEach { timbre -> scope.launch { trabajar(timbre) } }
    }

    fun despertar() {
        timbres.forEach { it.trySend(Unit) }
    }

    // a lote mid-way is let go for the next taker; an assembly mid-way stays ENSAMBLANDO until its lease expires
    suspend fun detener() {
        withTimeoutOrNull(DETENER.toMillis()) { scope.coroutineContext.job.cancelAndJoin() }
        scope.cancel()
    }

    private suspend fun trabajar(timbre: Channel<Unit>) {
        while (currentCoroutineContext().isActive) {
            val hizo =
                try {
                    ciclo()
                } catch (e: CancellationException) {
                    // the group stopping ends the loop; a cancellation of something else is one more failure
                    currentCoroutineContext().ensureActive()
                    log.error("un trabajador de emisiones masivas de {} falló", instancia, e)
                    false
                } catch (e: Throwable) {
                    log.error("un trabajador de emisiones masivas de {} falló", instancia, e)
                    false
                }
            if (!hizo) withTimeoutOrNull(config.espera.toMillis()) { timbre.receive() }
        }
    }

    // true if it did something: then it looks again right away
    private suspend fun ciclo(): Boolean {
        for ((_, id) in estado.fallarAbandonadas(lease)) log.warn("la emisión masiva {} no tenía quien la corriera: FALLIDA", id)
        estado.reclamarEnsamblado(lease)?.let {
            ensamblar(it)
            return true
        }
        lotes.tomar(instancia, lease)?.let {
            procesar(it)
            return true
        }
        return false
    }

    private suspend fun procesar(lote: LoteTomado) {
        if (lote.intentos > config.intentos) {
            fallar(lote, "lote fallido tras ${lote.intentos - 1} intentos")
            return
        }
        val autenticacion = lote.creadoPor?.let { identidad.de(it) }
        if (autenticacion == null) {
            fallar(lote, SIN_USUARIO)
            return
        }
        val clave = claveParte(lote.emision, lote.numero, lote.formato)
        // the take in the name: a take of the same lote by another worker of this group never shares it
        val parcial = temporales.resolve("lote-${lote.id}-${lote.intentos}.part")
        val inicio = System.nanoTime()
        try {
            val resultado =
                withContext(ReactiveSecurityContextHolder.withAuthentication(autenticacion).asCoroutineContext()) {
                    conLatido(cadaLatido, { lotes.latir(lote, instancia) }) {
                        withContext(Dispatchers.IO) { Files.createDirectories(temporales) }
                        val hecho =
                            generador.generar(lote.anio, lote.formato, lote.contribuyentes, parcial) { procesados, errores ->
                                if (!lotes.avanzar(lote, instancia, procesados, errores)) throw Perdido()
                            }
                        almacen.guardar(clave, parcial)
                        hecho
                    }
                }
            if (resultado == null || !lotes.terminar(lote, instancia, lote.contribuyentes.size, resultado.documentos, resultado.errores, clave)) {
                soltarParte(lote, clave)
                return
            }
        } catch (e: CancellationException) {
            // the group stops: the next taker starts it over without waiting for the lease
            withContext(NonCancellable) { runCatching { lotes.liberar(lote, instancia) } }
            throw e
        } catch (e: Throwable) {
            // the next taker tries again, up to srtm.emision.intentos
            log.error("el lote {} de la emisión masiva {} falló en el intento {}", lote.numero, lote.emision, lote.intentos, e)
            lotes.liberar(lote, instancia)
            return
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(parcial) }
        }
        log.info(
            "lote {} de la emisión masiva {}: {} contribuyentes en {} ms",
            lote.numero,
            lote.emision,
            lote.contribuyentes.size,
            Duration.ofNanos(System.nanoTime() - inicio).toMillis()
        )
        reclamar(lote)
    }

    // the lote could not be generated: FALLIDO, with all its contribuyentes among its errores
    private suspend fun fallar(
        lote: LoteTomado,
        mensaje: String
    ) {
        if (!lotes.fallar(lote, instancia, lote.contribuyentes.map { ErrorEmision(it.codigo, mensaje) })) return
        log.warn("el lote {} de la emisión masiva {} queda FALLIDO: {}", lote.numero, lote.emision, mensaje)
        reclamar(lote)
    }

    // a lote that is not ours any more. its parte stays for a take that may still use it (another one generates it
    // again, or ended it with its own); a lote cancelled or deleted has none that counts
    private suspend fun soltarParte(
        lote: LoteTomado,
        clave: String
    ) {
        val ahora = lotes.estado(lote)
        if (ahora == null || ahora == FALLIDO) almacen.borrar(clave)
    }

    // the lote is in a terminal state: its emission is assembled now if it was the last one. a statement of its own,
    // after the lote's: whoever ends the last lote claims it, and the cycle's claim is there if this one fails
    private suspend fun reclamar(lote: LoteTomado) {
        estado.reclamarEnsamblado(lease, lote.organizacion, lote.emision)?.let { ensamblar(it) }
    }

    private suspend fun ensamblar(reclamada: EmisionAEnsamblar) {
        val e = reclamada
        val token = AtomicReference(reclamada)
        val clave = claveResultado(e.id, e.anio, e.formato)
        val inicio = System.nanoTime()
        try {
            val partes =
                conLatido(cadaLatido, { estado.latirEnsamblado(token.get())?.also { token.set(it) } != null }) {
                    val partes = lotes.partes(e.organizacion, e.id)
                    ensamblador.ensamblar(e.formato, partes.filter { it.estado == TERMINADO }.mapNotNull { it.parte }, clave, temporales)
                    // the final file is stored: the partes and anything else of the emission go
                    almacen.listar(prefijoEmision(e.id)).filter { it != clave }.forEach { almacen.borrar(it) }
                    partes
                }
            if (partes == null) {
                // another instance assembles it now; if it was deleted instead, nobody else cleans up after it
                if (!estado.existe(e.organizacion, e.id)) borrarTodo(e)
                return
            }
            // a lote FALLIDO counts its contribuyentes as processed: they are among the errores
            val procesados = partes.sumOf { if (it.estado == FALLIDO) it.errores.size else it.procesados }
            val terminada =
                estado.terminar(
                    token.get(),
                    nombreArchivo(e.anio, e.id.toString(), e.formato),
                    almacen.tamano(clave),
                    procesados,
                    partes.flatMap { it.errores }
                )
            if (!terminada) {
                if (!estado.existe(e.organizacion, e.id)) borrarTodo(e)
                return
            }
        } catch (ex: CancellationException) {
            // the group stops: the emission stays ENSAMBLANDO and another instance takes it over when its lease expires
            throw ex
        } catch (ex: Throwable) {
            log.error("no se pudo ensamblar la emisión masiva {}", e.id, ex)
            // only if it is still ours: an instance that took it over is not failed under its feet
            if (estado.latirEnsamblado(token.get()) != null) {
                estado.fallar(e.organizacion, e.id, ex.message ?: ex.javaClass.simpleName)
                borrarTodo(e)
            } else if (!estado.existe(e.organizacion, e.id)) {
                borrarTodo(e)
            }
            return
        }
        log.info("emisión masiva {} ensamblada en {} ms", e.id, Duration.ofNanos(System.nanoTime() - inicio).toMillis())
        try {
            retencion.depurar()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            log.error("no se pudieron depurar los archivos de emisiones", ex)
        }
    }

    private suspend fun borrarTodo(e: EmisionAEnsamblar) {
        almacen.listar(prefijoEmision(e.id)).forEach { almacen.borrar(it) }
    }

    private companion object {
        val log = LoggerFactory.getLogger(GrupoTrabajadores::class.java)
    }
}

// this instance's workers: a group started once the application is ready, with instancia "<host>-<uuid of this
// start>" and as many workers as srtm.emision.trabajadores says (cuantosTrabajadores), stopped with the context. a
// POST of this instance wakes them (despertar); the other instances' find its lotes within srtm.emision.espera
@Component
class TrabajadoresEmision(
    private val config: EmisionProperties,
    private val lotes: LotesEmision,
    private val estado: EstadoEmisiones,
    private val almacen: AlmacenEmision,
    private val documentos: DocumentosDeEmision,
    private val merger: PdfMerger,
    private val identidad: IdentidadEmision,
    private val retencion: RetencionEmision,
    private val entorno: Environment
) {
    @Volatile
    private var propio: GrupoTrabajadores? = null

    // a group with these beans: the instance's own, or another one a test starts to play a second instance
    fun grupo(
        instancia: String,
        cantidad: Int,
        temporales: Path,
        documentos: DocumentosDeEmision = this.documentos
    ) = GrupoTrabajadores(instancia, cantidad, temporales, config, lotes, estado, almacen, documentos, merger, identidad, retencion)

    @EventListener(ApplicationReadyEvent::class)
    fun arrancar() {
        val pool = entorno.getProperty("spring.r2dbc.pool.max-size", Int::class.java, POOL)
        val runtime = Runtime.getRuntime()
        val cantidad = cuantosTrabajadores(config.trabajadores, runtime.availableProcessors(), runtime.maxMemory(), pool)
        config.trabajadores.trim().toIntOrNull()?.takeIf { it > cantidad }?.let {
            log.warn("srtm.emision.trabajadores={} no cabe en el pool de conexiones ({}): corren {}", it, pool, cantidad)
        }
        val instancia = "${host()}-${UUID.randomUUID()}"
        log.info("emisión masiva: la instancia {} corre {} trabajadores (srtm.emision.trabajadores={})", instancia, cantidad, config.trabajadores)
        if (cantidad > 0) propio = grupo(instancia, cantidad, Path.of(config.temporales)).also { it.iniciar() }
    }

    fun despertar() {
        propio?.despertar()
    }

    @PreDestroy
    fun detener() {
        runBlocking { propio?.detener() }
    }

    private fun host(): String =
        System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: "srtm"

    private companion object {
        // r2dbc-pool's default, if nothing sets it
        const val POOL = 10

        val log = LoggerFactory.getLogger(TrabajadoresEmision::class.java)
    }
}
