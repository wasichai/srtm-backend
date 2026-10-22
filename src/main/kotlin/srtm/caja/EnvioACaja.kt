package srtm.caja

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Component
import srtm.emision.TablaCore
import srtm.emision.TablasCore
import wasichai.core.common.ConflictException
import wasichai.core.organization.OrganizationRepository
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// the fields of orden_caja the queue reads and writes
internal val CAMPOS_ORDEN =
    listOf(
        "referencia",
        "anio",
        "concepto",
        "detalle",
        "importe",
        "fecha_exigibilidad",
        "pagador_documento",
        "pagador_nombre",
        "sustituye_a",
        "motivo",
        "estado",
        "intentos",
        "tomado_hasta",
        "ultimo_error",
        "orden_id",
        "enviada_en",
        "retirada_en"
    )

private val POR_MANDAR = listOf(EstadoOrdenCaja.POR_ENVIAR, EstadoOrdenCaja.POR_RETIRAR)

// how long a take holds an orden: more than a call to caja can last (its timeout, a 401 and its retry, the token):
// 2 minutes, or 3 times the longest timeout configured
internal fun lease(config: CajaProperties): Duration {
    val timeout = config.cuentas().values.maxOfOrNull { it.timeout ?: ClienteCaja.TIMEOUT } ?: ClienteCaja.TIMEOUT
    return maxOf(Duration.ofMinutes(2), timeout.multipliedBy(3))
}

// the longest an orden caja did not answer waits before its next try
private val ESPERA_MAXIMA: Duration = Duration.ofHours(1)

// how long an orden caja did not answer waits before its next take: srtm.caja.espera-reintento doubled on each try,
// up to an hour
fun esperaTrasNoContesta(
    base: Duration,
    intentos: Int
): Duration {
    if (base.isZero || base.isNegative) return Duration.ZERO
    val veces = (intentos - 1).coerceIn(0, 20)
    return minOf(base.multipliedBy(1L shl veces), ESPERA_MAXIMA)
}

// how long a stop waits for the loop to let its orden go
private val DETENER: Duration = Duration.ofSeconds(30)

private val LIMA: ZoneId = ZoneId.of("America/Lima")

// an orden a take got: what is sent of it, and the estado and intentos its writes compare with
private data class Tomada(
    val id: UUID,
    val referencia: String,
    val anio: Int,
    val concepto: String,
    val detalle: String?,
    val importe: BigDecimal,
    val fechaExigibilidad: LocalDate?,
    val pagadorDocumento: String?,
    val pagadorNombre: String?,
    val sustituyeA: String?,
    val motivo: String?,
    val estado: String,
    val intentos: Int
)

// the queue of orden_caja (D3): an orden POR_ENVIAR is sent to its organization's caja as an alta, one POR_RETIRAR as a
// retiro, and what caja answers moves its estado. an orden is taken by compare-and-set on tomado_hasta (a lease), so
// two senders (a request and the loop, two instances) never send it at once; every write of its result compares with
// the estado and the intentos of that take. the system's writes go straight to the table (TablasCore): no user, no
// RecordService. the loop runs every srtm.caja.intervalo once the application is ready (zero: never) and takes up to
// srtm.caja.lote ordenes of each organization with caja, the oldest first
@Component
class EnvioACaja(
    private val db: DatabaseClient,
    private val tablas: TablasCore,
    private val cajas: Cajas,
    private val config: CajaProperties,
    private val organizaciones: OrganizationRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("envio-a-caja"))
    private val lease = lease(config)

    @EventListener(ApplicationReadyEvent::class)
    fun arrancar() {
        if (!cajas.configurado || config.intervalo.isZero || config.intervalo.isNegative) return
        log.info("caja: las órdenes pendientes se envían cada {}", config.intervalo)
        scope.launch {
            while (isActive) {
                try {
                    vuelta()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.error("una vuelta del envío a caja falló", e)
                }
                delay(config.intervalo.toMillis())
            }
        }
    }

    @PreDestroy
    fun detener() {
        runBlocking { withTimeoutOrNull(DETENER.toMillis()) { scope.coroutineContext.job.cancelAndJoin() } }
        scope.cancel()
    }

    // one pass of the loop: how many ordenes it took
    suspend fun vuelta(): Int {
        if (!cajas.configurado) return 0
        var tomadas = 0
        for (t in tablas.de(ObjetosCaja.ORDEN_CAJA, CAMPOS_ORDEN)) {
            val caja = cajaDe(t.organizacion) ?: continue
            for (id in libres(t, config.lote)) {
                if (procesar(t, caja, id) != null) tomadas++
            }
        }
        return tomadas
    }

    // these ordenes sent now, one after another, while `plazo` lasts: the ones it did not reach (or another sender
    // has) are the loop's. what was done, and how many of them are still to send
    suspend fun enviar(
        ids: List<UUID>,
        plazo: Duration
    ): ResumenCaja {
        if (ids.isEmpty() || !cajas.configurado) return ResumenCaja()
        val fin = System.nanoTime() + plazo.toNanos()
        var resumen = ResumenCaja()
        val suyas = mutableListOf<Pair<TablaCore, List<UUID>>>()
        for (t in tablas.de(ObjetosCaja.ORDEN_CAJA, CAMPOS_ORDEN)) {
            val deEsta = existentes(t, ids)
            if (deEsta.isNotEmpty()) suyas += t to deEsta
        }
        for ((t, deEsta) in suyas) {
            val caja = cajaDe(t.organizacion) ?: continue
            for (id in deEsta) {
                val queda = Duration.ofNanos(fin - System.nanoTime())
                if (queda.isNegative || queda.isZero) break
                withTimeoutOrNull(queda.toMillis()) { procesar(t, caja, id) }?.let { resumen += it }
            }
        }
        return resumen + ResumenCaja(pendientes = suyas.sumOf { (t, deEsta) -> porMandar(t, deEsta) })
    }

    // the organization's caja; none (or one configured twice) is no sending
    private suspend fun cajaDe(organizacion: UUID): Caja? =
        try {
            val slug = if (cajas.necesitaSlug) organizaciones.findById(organizacion)?.slug else null
            cajas.de(organizacion, slug)
        } catch (_: ConflictException) {
            null
        }

    // the orden taken, sent, and its result written. null: another sender has it, or it is not to send any more
    private suspend fun procesar(
        t: TablaCore,
        caja: Caja,
        id: UUID
    ): ResumenCaja? {
        val o = tomar(t, id) ?: return null
        val resultado =
            try {
                if (o.estado == EstadoOrdenCaja.POR_ENVIAR) caja.alta(alta(o)) else caja.retiro(o.referencia, motivoDeRetiro(o.motivo.orEmpty()))
            } catch (e: CancellationException) {
                // a plazo or a stop: the next sender sends it again (caja knows it by its referencia)
                withContext(
                    NonCancellable
                ) { runCatching { escribir(t, o, o.estado, "${t.columna("ultimo_error")} = :error", "error" to "envío interrumpido") } }
                throw e
            } catch (e: Throwable) {
                ResultadoCaja.NoContesta("no se pudo enviar: ${e.javaClass.simpleName}")
            }
        return withContext(NonCancellable) { resolver(t, o, resultado) }
    }

    // D6: what caja answered, as the orden's new estado. the lease is let go, after a NoContesta for a while
    private suspend fun resolver(
        t: TablaCore,
        o: Tomada,
        r: ResultadoCaja
    ): ResumenCaja {
        val alta = o.estado == EstadoOrdenCaja.POR_ENVIAR
        val c = { campo: String -> t.columna(campo) }
        return when (r) {
            is ResultadoCaja.Hecho if alta -> {
                val escrita =
                    escribir(
                        t,
                        o,
                        EstadoOrdenCaja.ENVIADA,
                        "${c("orden_id")} = :orden, ${c("enviada_en")} = now(), ${c("ultimo_error")} = NULL",
                        "orden" to r.ordenId
                    )
                // caja withdrew the one it replaces when it took this one
                val sustituida = escrita && o.sustituyeA != null && retirarSustituida(t, o.sustituyeA)
                log.debug("caja: {} ENVIADA", o.referencia)
                ResumenCaja(enviadas = if (escrita) 1 else 0, sustituidas = if (sustituida) 1 else 0)
            }
            is ResultadoCaja.Hecho, ResultadoCaja.NoExiste -> {
                if (alta) {
                    // an alta caja answers 404 to: nothing caja will take as sent
                    escribir(t, o, EstadoOrdenCaja.ERROR, "${c("ultimo_error")} = :error", "error" to "caja-backend respondió 404")
                    return ResumenCaja(error = "caja-backend respondió 404")
                }
                val escrita = escribir(t, o, EstadoOrdenCaja.RETIRADA, "${c("retirada_en")} = now(), ${c("ultimo_error")} = NULL")
                log.debug("caja: {} RETIRADA", o.referencia)
                ResumenCaja(retiradas = if (escrita) 1 else 0)
            }
            is ResultadoCaja.Conflicto -> {
                // an alta caja holds otherwise (a PAGADA it replaces, another debt): CONFLICTO, never retried. a retiro
                // of a PAGADA: it was paid in caja, the pago comes by the buzón
                val estado = if (alta) EstadoOrdenCaja.CONFLICTO else EstadoOrdenCaja.PAGADA_EN_CAJA
                val escrita = escribir(t, o, estado, "${c("ultimo_error")} = :error", "error" to r.detalle.take(500))
                log.warn("caja: {} {}", o.referencia, estado)
                if (alta) {
                    ResumenCaja(conflictos = if (escrita) 1 else 0, error = r.detalle)
                } else {
                    ResumenCaja(pagadasEnCaja = if (escrita) 1 else 0)
                }
            }
            is ResultadoCaja.Rechazo -> {
                escribir(t, o, EstadoOrdenCaja.ERROR, "${c("ultimo_error")} = :error", "error" to r.detalle.take(500))
                log.warn("caja: {} ERROR (rechazada)", o.referencia)
                ResumenCaja(error = r.detalle)
            }
            is ResultadoCaja.NoContesta -> {
                val agotada = o.intentos >= config.intentos
                // the next try waits: a caja down is not asked again on every vuelta
                val espera = if (agotada) Duration.ZERO else esperaTrasNoContesta(config.esperaReintento, o.intentos)
                escribir(
                    t,
                    o,
                    if (agotada) EstadoOrdenCaja.ERROR else o.estado,
                    "${c("ultimo_error")} = :error",
                    "error" to r.causa.take(500),
                    espera = espera
                )
                if (agotada) log.warn("caja: {} ERROR tras {} intentos", o.referencia, o.intentos)
                ResumenCaja(error = r.causa)
            }
        }
    }

    private fun alta(o: Tomada) =
        AltaDeOrden(
            referencia = o.referencia,
            concepto = o.concepto,
            detalle = o.detalle,
            importe = o.importe,
            fechaExigibilidad = o.fechaExigibilidad ?: LocalDate.of(o.anio, 1, 1),
            actualizadoA = LocalDate.now(LIMA),
            pagadorDocumento = o.pagadorDocumento,
            pagadorNombre = o.pagadorNombre,
            observacion = observacion(o.motivo.orEmpty()),
            sustituyeA = o.sustituyeA
        )

    // the take: the lease and one more intento, only on an orden to send that nobody holds
    private suspend fun tomar(
        t: TablaCore,
        id: UUID
    ): Tomada? {
        val c = { campo: String -> t.columna(campo) }
        val leidos =
            listOf(
                "referencia",
                "anio",
                "concepto",
                "detalle",
                "importe",
                "fecha_exigibilidad",
                "pagador_documento",
                "pagador_nombre",
                "sustituye_a",
                "motivo",
                "estado",
                "intentos"
            )
        return db
            .sql(
                "UPDATE ${t.tabla} SET ${c("tomado_hasta")} = now() + make_interval(secs => :lease), " +
                    "${c("intentos")} = coalesce(${c("intentos")}, 0) + 1, updated_at = now() " +
                    "WHERE id = :id AND ${c("estado")} IN (:por_enviar, :por_retirar) AND (${c("tomado_hasta")} IS NULL OR ${c("tomado_hasta")} < now()) " +
                    "RETURNING id, " + leidos.joinToString(", ") { "${c(it)} AS $it" }
            ).bind("lease", lease.toMillis() / 1000.0)
            .bind("id", id)
            .bind("por_enviar", EstadoOrdenCaja.POR_ENVIAR)
            .bind("por_retirar", EstadoOrdenCaja.POR_RETIRAR)
            .map { row, _ ->
                Tomada(
                    id = row.get("id", UUID::class.java)!!,
                    referencia = row.get("referencia", String::class.java)!!,
                    anio = (row.get("anio") as Number).toInt(),
                    concepto = row.get("concepto", String::class.java)!!,
                    detalle = row.get("detalle", String::class.java),
                    importe = BigDecimal(row.get("importe").toString()),
                    fechaExigibilidad = row.get("fecha_exigibilidad")?.let { LocalDate.parse(it.toString().take(10)) },
                    pagadorDocumento = row.get("pagador_documento", String::class.java),
                    pagadorNombre = row.get("pagador_nombre", String::class.java),
                    sustituyeA = row.get("sustituye_a", String::class.java),
                    motivo = row.get("motivo", String::class.java),
                    estado = row.get("estado", String::class.java)!!,
                    intentos = (row.get("intentos") as Number).toInt()
                )
            }.one()
            .awaitFirstOrNull()
    }

    // a write of the take that got the orden: only while it is in the estado and the intentos of that take. the lease
    // goes with it, or stays `espera` from now: no take before then
    private suspend fun escribir(
        t: TablaCore,
        o: Tomada,
        nuevo: String,
        asignaciones: String,
        vararg valores: Pair<String, Any>,
        espera: Duration = Duration.ZERO
    ): Boolean {
        val hasta = if (espera.isZero) "NULL" else "now() + make_interval(secs => :espera)"
        var spec =
            db
                .sql(
                    "UPDATE ${t.tabla} SET ${t.columna("estado")} = :nuevo, $asignaciones, ${t.columna("tomado_hasta")} = $hasta, updated_at = now() " +
                        "WHERE id = :id AND ${t.columna("estado")} = :previo AND ${t.columna("intentos")} = :intentos"
                ).bind("nuevo", nuevo)
                .bind("id", o.id)
                .bind("previo", o.estado)
                .bind("intentos", o.intentos)
        if (!espera.isZero) spec = spec.bind("espera", espera.toMillis() / 1000.0)
        valores.forEach { (nombre, valor) -> spec = spec.bind(nombre, valor) }
        return spec.fetch().awaitRowsUpdated() > 0
    }

    private suspend fun retirarSustituida(
        t: TablaCore,
        referencia: String
    ): Boolean =
        db
            .sql(
                "UPDATE ${t.tabla} SET ${t.columna("estado")} = :retirada, ${t.columna("retirada_en")} = now(), updated_at = now() " +
                    "WHERE ${t.columna("referencia")} = :referencia AND ${t.columna("estado")} IN (:enviada, :por_retirar)"
            ).bind("retirada", EstadoOrdenCaja.RETIRADA)
            .bind("referencia", referencia)
            .bind("enviada", EstadoOrdenCaja.ENVIADA)
            .bind("por_retirar", EstadoOrdenCaja.POR_RETIRAR)
            .fetch()
            .awaitRowsUpdated() > 0

    // the oldest ordenes to send that nobody holds
    private suspend fun libres(
        t: TablaCore,
        cuantas: Int
    ): List<UUID> =
        db
            .sql(
                "SELECT id FROM ${t.tabla} WHERE ${t.columna("estado")} IN (:por_enviar, :por_retirar) " +
                    "AND (${t.columna("tomado_hasta")} IS NULL OR ${t.columna("tomado_hasta")} < now()) ORDER BY created_at, id LIMIT :lote"
            ).bind("por_enviar", POR_MANDAR[0])
            .bind("por_retirar", POR_MANDAR[1])
            .bind("lote", cuantas)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .all()
            .asFlow()
            .toList()

    // which of `ids` are this table's, in their order
    private suspend fun existentes(
        t: TablaCore,
        ids: List<UUID>
    ): List<UUID> {
        val suyas =
            db
                .sql("SELECT id FROM ${t.tabla} WHERE id IN (:ids)")
                .bind("ids", ids)
                .map { row, _ -> row.get("id", UUID::class.java)!! }
                .all()
                .asFlow()
                .toList()
                .toSet()
        return ids.filter { it in suyas }
    }

    private suspend fun porMandar(
        t: TablaCore,
        ids: List<UUID>
    ): Int =
        db
            .sql("SELECT count(*) AS n FROM ${t.tabla} WHERE id IN (:ids) AND ${t.columna("estado")} IN (:por_enviar, :por_retirar)")
            .bind("ids", ids)
            .bind("por_enviar", POR_MANDAR[0])
            .bind("por_retirar", POR_MANDAR[1])
            .map { row, _ -> (row.get("n") as Number).toInt() }
            .one()
            .awaitFirstOrNull() ?: 0

    private companion object {
        val log = LoggerFactory.getLogger(EnvioACaja::class.java)
    }
}
