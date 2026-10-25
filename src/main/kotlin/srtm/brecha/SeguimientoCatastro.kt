package srtm.brecha

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.rentas.Registros
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.TenantDirectory
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// the seguimiento of catastro (#203): every srtm.catastro.seguimiento.minutos (0: never) each organization with a
// catastro and the model's sincronizacion_catastro reads its GET /api/catastro/cambios from its cursor, keeps them in
// cambio_catastro and, by Seguimiento.disparo, starts its automatic corrida of the day. one instance per organization
// (the ClusterLock of its key: another holding it skips it), as the platform (asPlatform: no user). each page of
// cambios is written with its cursor in one transaction, so a crash never repeats nor skips a page; a failed read or
// write of a page (or one that does not move the cursor) writes error and that pass decides nothing
@Component
class SeguimientoCatastro(
    private val registros: Registros,
    private val records: RecordService,
    private val candado: ClusterLock,
    private val organizaciones: TenantDirectory,
    private val catastros: Catastros,
    private val brechas: BrechaService,
    private val config: CatastroProperties,
    transacciones: ReactiveTransactionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("seguimiento-catastro"))
    private val transaccion = TransactionalOperator.create(transacciones)

    @EventListener(ApplicationReadyEvent::class)
    fun arrancar() {
        val minutos = config.seguimiento.minutos
        if (minutos <= 0) return
        if (!catastros.configurado) {
            log.warn("catastro: srtm.catastro.seguimiento.minutos es {}, pero no hay un catastro configurado; sus cambios no se leen", minutos)
            return
        }
        log.info("catastro: sus cambios se leen cada {} minutos; la corrida automática, desde las {} h", minutos, config.seguimiento.hora)
        scope.launch {
            while (isActive) {
                try {
                    vuelta()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.error("una vuelta del seguimiento del catastro falló", e)
                }
                delay(Duration.ofMinutes(minutos).toMillis())
            }
        }
    }

    @PreDestroy
    fun detener() {
        runBlocking { withTimeoutOrNull(DETENER.toMillis()) { scope.coroutineContext.job.cancelAndJoin() } }
        scope.cancel()
    }

    // one pass of the loop: how many organizations it went over (the ones another instance holds are not counted). one
    // that fails is logged and the others go on
    suspend fun vuelta(
        ahora: ZonedDateTime = ZonedDateTime.now(Seguimiento.LIMA),
        hora: Int = config.seguimiento.hora
    ): Int {
        if (!catastros.configurado) return 0
        var hechas = 0
        for (org in organizaciones.organizationsWithObject(SINCRONIZACION_CATASTRO)) {
            val catastro =
                try {
                    catastros.de(org.id, org.slug)
                } catch (_: ConflictException) {
                    null
                } ?: continue
            try {
                val tomada =
                    candado.tryLock(clave(org.id))?.use {
                        records.asPlatform(org.id, "srtm:seguimiento-catastro") { seguir(org.id, catastro, ahora, hora) }
                        true
                    } ?: false
                if (tomada) hechas++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.error("el seguimiento del catastro de {} falló", org.slug, e)
            }
        }
        return hechas
    }

    // one organization's pass, as the platform under its lock: its cambios, page after page, then the decision
    private suspend fun seguir(
        organizacion: UUID,
        catastro: Catastro,
        ahora: ZonedDateTime,
        hora: Int
    ) {
        var s =
            registros.all(SINCRONIZACION_CATASTRO, RegistroSincronizacion::class.java).firstOrNull()
                ?: registros.create(SINCRONIZACION_CATASTRO, RegistroSincronizacion::class.java, mapOf("pendientes" to 0))
        try {
            do {
                val pagina = catastro.cambios(s.cursor)
                s = guardar(s, pagina)
            } while (pagina.hayMas)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // the page rolled back, the cursor stays: the next pass reads it again. its class and message only, never
            // a token nor a secret (catastro's own errors already say what failed)
            val error = if (e is CatastroException) e.message else "${e.javaClass.simpleName}: ${e.message}".take(ERROR_MAXIMO)
            log.warn("el seguimiento del catastro de {} no leyó o no guardó sus cambios: {}", organizacion, error)
            registros.replace(SINCRONIZACION_CATASTRO, RegistroSincronizacion::class.java, UUID.fromString(s.id), mapOf("error" to error))
            return
        }
        disparar(organizacion, catastro, s, ahora, hora)
    }

    // a page's cambio_catastro rows and the sincronizacion that follows them (cursor, ultimo_cambio_en, pendientes, no
    // error), in one transaction. a page with nothing new, and nothing to clear, writes nothing. one with cambios, or
    // with more to come, must move the cursor: else its rows would be written again, or the pass would never end
    private suspend fun guardar(
        s: RegistroSincronizacion,
        pagina: PaginaDeCambios
    ): RegistroSincronizacion {
        if ((pagina.items.isNotEmpty() || pagina.hayMas) && (pagina.cursor == null || pagina.cursor == s.cursor)) {
            throw CatastroException(
                "catastro devolvió una página de cambios que no avanza el cursor (${pagina.items.size} cambios, " +
                    "cursor ${pagina.cursor ?: "vacío"}, hayMas ${pagina.hayMas})"
            )
        }
        val cursor = pagina.cursor ?: s.cursor
        if (pagina.items.isEmpty() && cursor == s.cursor && s.error == null) return s
        val ultimo =
            pagina.items
                .mapNotNull { c -> c.en?.let { en -> instante(en)?.let { it to en } } }
                .maxByOrNull { it.first }
                ?.second
        return transaccion.executeAndAwait {
            registros.createAll(
                CAMBIO_CATASTRO,
                RegistroCambio::class.java,
                pagina.items.map { c ->
                    mapOf(
                        "codigo_municipal" to c.codigoMunicipal,
                        "objeto" to c.objeto,
                        "operacion" to c.operacion,
                        "campos" to c.campos.joinToString(", "),
                        "en" to c.en,
                        "entrega" to c.entrega
                    )
                }
            )
            registros.replace(
                SINCRONIZACION_CATASTRO,
                RegistroSincronizacion::class.java,
                UUID.fromString(s.id),
                mapOf(
                    "cursor" to cursor,
                    "ultimo_cambio_en" to (ultimo ?: s.ultimoCambioEn),
                    "pendientes" to (s.pendientes ?: 0) + pagina.items.size,
                    "error" to null
                )
            )
        }
    }

    // Seguimiento.disparo on the last automatic corrida: when it says so, the corrida of the year (Lima's) starts and
    // pendientes go back to 0. one still alive (409) leaves everything as it is: the next pass decides again
    private suspend fun disparar(
        organizacion: UUID,
        catastro: Catastro,
        s: RegistroSincronizacion,
        ahora: ZonedDateTime,
        hora: Int
    ) {
        val ultima =
            s.ultimaCorrida?.let {
                try {
                    registros.get(BRECHA_CORRIDA, RegistroCorrida::class.java, UUID.fromString(it))
                } catch (_: NotFoundException) {
                    null
                }
            }
        val disparo = Seguimiento.disparo(ahora, hora, s.pendientes ?: 0, ultima?.iniciado?.let(::instante), ultima?.estado == FALLIDA)
        if (disparo == Disparo.NINGUNO) return
        val corrida =
            try {
                brechas.lanzarAutomatica(organizacion, catastro, ahora.withZoneSameInstant(Seguimiento.LIMA).year, OrigenCorrida.valueOf(disparo.name))
            } catch (e: ConflictException) {
                log.info("el seguimiento del catastro de {} no lanzó su corrida: {}", organizacion, e.message)
                return
            }
        registros.replace(
            SINCRONIZACION_CATASTRO,
            RegistroSincronizacion::class.java,
            UUID.fromString(s.id),
            mapOf("ultima_corrida" to corrida.id, "pendientes" to 0)
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(SeguimientoCatastro::class.java)

        // how long a stop waits for the pass in course
        val DETENER: Duration = Duration.ofSeconds(30)

        fun clave(organizacion: UUID) = "srtm:seguimiento-catastro:$organizacion"

        // how much of an unexpected failure's message error keeps
        const val ERROR_MAXIMO = 500
    }
}
