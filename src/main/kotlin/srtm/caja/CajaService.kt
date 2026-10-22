package srtm.caja

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.arbitrios.ANULACION_CUOTA_ARBITRIO
import srtm.arbitrios.AnulacionCuotaArbitrio
import srtm.arbitrios.Arbitrios
import srtm.arbitrios.CUOTA_ARBITRIO
import srtm.arbitrios.CuotaArbitrio
import srtm.arbitrios.SERVICIO_ARBITRIO
import srtm.arbitrios.ServicioArbitrio
import srtm.emision.TablaCore
import srtm.emision.TablasCore
import srtm.impuesto.Cuota
import srtm.impuesto.LiquidacionService
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Records
import srtm.rentas.Registros
import srtm.rentas.SIN_DOCUMENTO
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
import wasichai.core.common.WasichaiException
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// what a sincronización did: the ordenes it queued, what caja took (enviadas, and the ones those replaced), withdrew,
// had paid or refused, what is left to the loop, and a contribuyente it does not send (SIN DOCUMENTO, D8). error: the
// last thing caja said that was not a yes
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ResumenCaja(
    val encoladas: Int = 0,
    val enviadas: Int = 0,
    val sustituidas: Int = 0,
    val retiradas: Int = 0,
    val pagadasEnCaja: Int = 0,
    val conflictos: Int = 0,
    val pendientes: Int = 0,
    val sinDocumento: Int = 0,
    val error: String? = null
) {
    operator fun plus(otro: ResumenCaja) =
        ResumenCaja(
            encoladas + otro.encoladas,
            enviadas + otro.enviadas,
            sustituidas + otro.sustituidas,
            retiradas + otro.retiradas,
            pagadasEnCaja + otro.pagadasEnCaja,
            conflictos + otro.conflictos,
            pendientes + otro.pendientes,
            sinDocumento + otro.sinDocumento,
            otro.error ?: error
        )
}

// what a write of srtm changed (#164): why (the observacion of the altas and the motivo of the retiros it causes), and
// the contribuyentes of each year whose debt it may have moved
data class CambioDeDeuda(
    val motivo: String,
    val contribuyentes: Map<Int, Set<UUID>>
)

// an orden of the contribuyente as GET /caja shows it
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrdenEnCaja(
    val id: String?,
    val referencia: String?,
    val tipo: String?,
    val ranura: String?,
    val importe: BigDecimal?,
    val concepto: String?,
    val estado: String?,
    val pagada: Boolean,
    val ultimoError: String?,
    val enviadaEn: Instant?,
    val retiradaEn: Instant?
)

// saldo_a_favor: what caja collected over the debt (0 until the pagos arrive)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class EstadoCaja(
    val ordenes: List<OrdenEnCaja>,
    // snake case would say saldo_afavor: the a is a word of its own
    @get:JsonProperty("saldo_a_favor")
    val saldoAFavor: BigDecimal
)

// a cuota of the liquidación and the cuota_predial that stands for it: the vigente one (the highest revision of its
// numero) while its monto is the same, else the next revision, still to write (escrita null)
data class PredialDeseada(
    val cuota: Cuota,
    val revision: Int,
    val escrita: CuotaPredial?
)

// D4: the cuotas with something to collect (monto > 0), each by its vigente cuota_predial or its next revision
fun prediales(
    cuotas: List<Cuota>,
    escritas: List<CuotaPredial>
): List<PredialDeseada> {
    val vigentes = escritas.filter { it.numero != null && it.revision != null }.groupBy { it.numero!! }.mapValues { (_, cs) -> cs.maxBy { it.revision!! } }
    return cuotas.filter { it.monto.signum() > 0 }.map { cuota ->
        val vigente = vigentes[cuota.numero]
        if (vigente != null && vigente.monto?.compareTo(cuota.monto) == 0) {
            PredialDeseada(cuota, vigente.revision!!, vigente)
        } else {
            PredialDeseada(cuota, (vigente?.revision ?: 0) + 1, null)
        }
    }
}

// D5: what caja shows of each orden
fun conceptoPredial(
    anio: Int,
    numero: Int
) = "Impuesto predial $anio cuota $numero"

fun conceptoArbitrio(
    servicio: String,
    anio: Int,
    periodo: Int
) = "$servicio $anio-${periodo.toString().padStart(2, '0')}"

private fun unaLinea(texto: String) = texto.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

// caja's observacion: one line of 5 to 500 characters
fun observacion(motivo: String): String = unaLinea(motivo).takeIf { it.length >= 5 }?.take(500) ?: "Enviado desde srtm"

// a retiro's motivo: one line of 1 to 200 characters
fun motivoDeRetiro(motivo: String): String = unaLinea(motivo).ifEmpty { "Retirada desde srtm" }.take(200)

const val MOTIVO_A_PEDIDO = "Enviado a pedido desde srtm"

// why an orden is withdrawn when the sync is the POST's or the masiva's
const val MOTIVO_SIN_DEUDA = "La deuda ya no está vigente en srtm"

fun motivoDeEmision(anio: Int) = "Emisión masiva $anio"

private val LIMA: ZoneId = ZoneId.of("America/Lima")

private val POR_MANDAR = setOf(EstadoOrdenCaja.POR_ENVIAR, EstadoOrdenCaja.POR_RETIRAR)

// #163: a contribuyente's year as ordenes de cobro of its organization's caja. what srtm wants (the cuotas de predial of
// its liquidación and its vigentes cuotas de arbitrio) is compared with what was queued (planificar), and the plan is
// written under the contribuyente's lock, in one transaction: the altas through Registros (srtm's mark), the
// cancelaciones and retiros by compare-and-set on the table. then the ordenes it touched are sent, for up to
// srtm.caja.plazo-en-linea; the rest is the loop's (EnvioACaja). reads and creates are the caller's
@Service
class CajaService(
    private val registros: Registros,
    private val liquidaciones: LiquidacionService,
    private val cajas: Cajas,
    private val config: CajaProperties,
    private val currentUser: CurrentUser,
    private val organizaciones: OrganizationRepository,
    private val candados: Candados,
    private val tablas: TablasCore,
    private val db: DatabaseClient,
    private val envio: EnvioACaja,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)
    private val log = LoggerFactory.getLogger(javaClass)

    // the caller's organization's caja, or null. one configured twice: 409
    suspend fun cajaDelLlamante(): Caja? {
        if (!cajas.configurado) return null
        val usuario = currentUser.require()
        val slug = if (cajas.necesitaSlug) organizaciones.findById(usuario.organizationId)?.slug else null
        return cajas.de(usuario.organizationId, slug)
    }

    // motivo: the observacion of its altas; motivoDeRetiro: why its retiros and cancelaciones go. enLinea false: only
    // queued, the loop sends them (the masiva: thousands of contribuyentes do not wait on caja)
    suspend fun sincronizar(
        contribuyente: UUID,
        anio: Int,
        motivo: String,
        motivoDeRetiro: String = MOTIVO_SIN_DEUDA,
        enLinea: Boolean = true
    ): ResumenCaja {
        cajaDelLlamante() ?: throw ConflictException(SIN_CAJA_CONFIGURADA)
        val (resumen, porEnviar) = transaccion.executeAndAwait { planificarYEscribir(contribuyente, anio, motivo, motivoDeRetiro) }
        if (!enLinea) return resumen + ResumenCaja(pendientes = porEnviar.size)
        return resumen + envio.enviar(porEnviar, config.plazoEnLinea)
    }

    // the hooks of srtm's writes (#164): only the contribuyentes that already have ordenes of the year. null: none has,
    // or the organization has no caja
    suspend fun sincronizarSiEnviado(
        contribuyentes: Set<UUID>,
        anio: Int,
        motivo: String,
        motivoDeRetiro: String = MOTIVO_SIN_DEUDA
    ): ResumenCaja? {
        val caja =
            try {
                cajaDelLlamante()
            } catch (_: ConflictException) {
                null
            }
        if (caja == null) return null
        val enviados = contribuyentes.filter { ordenes(it, anio).isNotEmpty() }
        if (enviados.isEmpty()) return null
        return enviados.fold(ResumenCaja()) { suma, c -> suma + sincronizar(c, anio, motivo, motivoDeRetiro) }
    }

    // #164: after a write of srtm that changed a debt (a declaración, a cuota de arbitrio): the ordenes of each year's
    // contribuyentes follow it, for those that already have ordenes (sincronizarSiEnviado), each on its own. the
    // cambio is read only when the organization has a caja: without one nothing more is read. what it did goes to the
    // response's header (AvisoDeCaja). it never throws: the write stands, and what kept a contribuyente from being
    // synced is the header's error (logged by its class only: no data of the contribuyente)
    suspend fun despuesDeEscribir(cambio: suspend () -> CambioDeDeuda) {
        val (motivo, porAnio) =
            try {
                if (cajaDelLlamante() == null) return
                cambio()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ConflictException) {
                // a caja configured twice: nothing is sent, as sincronizarSiEnviado
                return
            } catch (e: Exception) {
                AvisoDeCaja.anotar(fallo(e))
                return
            }
        var total: ResumenCaja? = null
        for ((anio, suyos) in porAnio) {
            for (c in suyos) {
                val resumen =
                    try {
                        sincronizarSiEnviado(setOf(c), anio, motivo, motivo)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fallo(e)
                    }
                resumen?.let { total = total?.plus(it) ?: it }
            }
        }
        total?.let { AvisoDeCaja.anotar(it) }
    }

    private fun fallo(e: Exception): ResumenCaja {
        log.warn("caja: no se pudo sincronizar tras la escritura ({})", e.javaClass.simpleName)
        return ResumenCaja(error = (e as? WasichaiException)?.message ?: "No se pudo sincronizar con caja")
    }

    // GET /caja: the contribuyente's ordenes of the year
    suspend fun estado(
        contribuyente: UUID,
        anio: Int
    ): EstadoCaja {
        cajaDelLlamante() ?: throw ConflictException(SIN_CAJA_CONFIGURADA)
        registros.get(CONTRIBUYENTE, Contribuyente::class.java, contribuyente)
        val ordenes =
            ordenes(contribuyente, anio).sortedWith(compareBy({ it.ranura }, { it.referencia })).map {
                OrdenEnCaja(
                    it.id,
                    it.referencia,
                    it.tipo,
                    it.ranura,
                    it.importe,
                    it.concepto,
                    it.estado,
                    it.estado == EstadoOrdenCaja.PAGADA_EN_CAJA,
                    it.ultimoError,
                    it.enviadaEn,
                    it.retiradaEn
                )
            }
        return EstadoCaja(ordenes, BigDecimal.ZERO)
    }

    // inside the transaction: the plan written. what it did, and the ordenes to send now (the new ones, the retiros,
    // and the ones still waiting from before). the cancelaciones go first: one a take got to meanwhile defers its
    // ranura, and its new alta is not written (caja may hold the one it would replace)
    private suspend fun planificarYEscribir(
        id: UUID,
        anio: Int,
        motivo: String,
        motivoDeRetiro: String
    ): Pair<ResumenCaja, List<UUID>> {
        candados.bloquearEnOrganizacion(Candado.CAJA_CONTRIBUYENTE, id.toString())
        val persona = registros.get(CONTRIBUYENTE, Contribuyente::class.java, id)
        if (persona.tipoDocumento == SIN_DOCUMENTO) return ResumenCaja(sinDocumento = 1) to emptyList()
        val actuales = ordenes(id, anio)
        val plan = planificar(deseadasPredial(id, anio) + deseadasArbitrio(id, anio), actuales)
        val tabla = if (plan.cancelar.isEmpty() && plan.retiros.isEmpty()) null else tabla()
        val ranuraDe = actuales.filter { it.id != null }.associate { UUID.fromString(it.id) to it.ranura }
        val (canceladas, tomadas) = plan.cancelar.partition { cancelar(tabla!!, it, motivoDeRetiro) }
        val diferidas = tomadas.mapNotNull { ranuraDe[it] }.toSet()
        val altas =
            registros.createAll(
                ObjetosCaja.ORDEN_CAJA,
                OrdenCaja::class.java,
                plan.altas.filter { it.deseada.ranura !in diferidas }.map { atributos(it, id, anio, persona, motivo) }
            )
        val retiradas =
            plan.retiros
                .filter { mover(tabla!!, it.ordenCaja, EstadoOrdenCaja.ENVIADA, EstadoOrdenCaja.POR_RETIRAR, motivoDeRetiro) }
                .map { it.ordenCaja }
        val esperando =
            actuales
                .filter { it.estado in POR_MANDAR && it.id != null }
                .map { UUID.fromString(it.id) }
                .filter { it !in canceladas }
        val porEnviar = (altas.mapNotNull { it.id }.map(UUID::fromString) + retiradas + esperando).distinct()
        return ResumenCaja(encoladas = altas.size, pendientes = plan.diferidas + diferidas.size) to porEnviar
    }

    private fun atributos(
        alta: Alta,
        contribuyente: UUID,
        anio: Int,
        persona: Contribuyente,
        motivo: String
    ): Map<String, Any?> {
        val d = alta.deseada
        return Records
            .attributes(
                OrdenCaja(
                    referencia = d.referencia,
                    tipo = d.tipo,
                    ranura = d.ranura,
                    anio = anio,
                    importe = d.importe,
                    concepto = d.concepto,
                    detalle = d.detalle,
                    fechaExigibilidad = LocalDate.of(anio, 1, 1),
                    pagadorDocumento = persona.numeroDocumento,
                    pagadorNombre = persona.nombreCompleto,
                    sustituyeA = alta.sustituyeA,
                    motivo = motivo,
                    estado = EstadoOrdenCaja.POR_ENVIAR,
                    intentos = 0,
                    contribuyente = contribuyente.toString(),
                    cuotaArbitrio = d.cuotaArbitrio?.toString(),
                    cuotaPredial = d.cuotaPredial?.toString()
                )
            ).filterValues { it != null }
    }

    // D4: the liquidación's cuotas with something to collect, each by its cuota_predial (written when its monto
    // changed). a liquidación that lacks a parámetro has none
    private suspend fun deseadasPredial(
        id: UUID,
        anio: Int
    ): List<OrdenDeseada> {
        val liquidacion = liquidaciones.determinar(id, anio).liquidacion
        if (liquidacion.faltan.isNotEmpty()) return emptyList()
        val escritas = registros.all(ObjetosCaja.CUOTA_PREDIAL, CuotaPredial::class.java, filters = mapOf("contribuyente" to "$id", "anio" to "$anio"))
        val hoy = LocalDate.now(LIMA)
        return prediales(liquidacion.cuotas, escritas).map { p ->
            val n = p.cuota.numero
            val cuota =
                p.escrita ?: registros.create(
                    ObjetosCaja.CUOTA_PREDIAL,
                    CuotaPredial::class.java,
                    Records
                        .attributes(
                            CuotaPredial(
                                anio = anio,
                                numero = n,
                                revision = p.revision,
                                monto = p.cuota.monto,
                                vencimiento = p.cuota.vencimiento,
                                valorAfecto = liquidacion.base,
                                fechaCalculo = hoy,
                                clave = "$id|$anio|$n|${p.revision}",
                                contribuyente = id.toString()
                            )
                        ).filterValues { it != null }
                )
            OrdenDeseada(
                referencia = referenciaPredial(id, anio, n, p.revision),
                ranura = ranuraPredial(id, anio, n),
                tipo = TipoOrdenCaja.PREDIAL,
                importe = p.cuota.monto,
                concepto = conceptoPredial(anio, n),
                detalle = null,
                cuotaArbitrio = null,
                cuotaPredial = cuota.id?.let(UUID::fromString),
                pagada = false
            )
        }
    }

    // the contribuyente's vigentes cuotas de arbitrio of the year with something to collect. vigente is read over each
    // predio's year: a correction may have charged the month to someone else
    private suspend fun deseadasArbitrio(
        id: UUID,
        anio: Int
    ): List<OrdenDeseada> {
        val suyas = registros.all(CUOTA_ARBITRIO, CuotaArbitrio::class.java, filters = mapOf("contribuyente" to "$id", "anio" to "$anio"))
        if (suyas.isEmpty()) return emptyList()
        val predios = suyas.mapNotNull { it.predio }.distinct()
        val vigentes =
            predios
                .flatMap { predio ->
                    val delAnio = mapOf("predio" to predio, "anio" to "$anio")
                    val anuladas =
                        registros
                            .all(ANULACION_CUOTA_ARBITRIO, AnulacionCuotaArbitrio::class.java, filters = delAnio)
                            .mapNotNull { it.cuota }
                            .toSet()
                    Arbitrios.vigentes(registros.all(CUOTA_ARBITRIO, CuotaArbitrio::class.java, filters = delAnio), anuladas)
                }.filter { it.contribuyente == "$id" && it.clave != null && it.id != null && it.periodo != null && (it.monto?.signum() ?: 0) > 0 }
        val servicios = registros.all(SERVICIO_ARBITRIO, ServicioArbitrio::class.java).associateBy { it.id }
        val codigos = registros.byIds(PREDIO, Predio::class.java, predios).mapValues { it.value.codigo }
        return vigentes.map { q ->
            val servicio = servicios[q.servicio]
            OrdenDeseada(
                referencia = referenciaArbitrio(q.clave!!),
                ranura = ranuraArbitrio(q.clave),
                tipo = TipoOrdenCaja.ARBITRIO,
                importe = q.monto!!,
                concepto = conceptoArbitrio(servicio?.nombre ?: servicio?.codigo ?: "Arbitrios", anio, q.periodo!!),
                detalle = codigos[q.predio],
                cuotaArbitrio = UUID.fromString(q.id),
                cuotaPredial = null,
                pagada = false
            )
        }
    }

    private suspend fun ordenes(
        contribuyente: UUID,
        anio: Int
    ): List<OrdenCaja> = registros.all(ObjetosCaja.ORDEN_CAJA, OrdenCaja::class.java, filters = mapOf("contribuyente" to "$contribuyente", "anio" to "$anio"))

    // the caller's organization's orden_caja table
    private suspend fun tabla(): TablaCore {
        val organizacion = currentUser.require().organizationId
        return tablas.de(ObjetosCaja.ORDEN_CAJA, CAMPOS_ORDEN).firstOrNull { it.organizacion == organizacion }
            ?: throw IllegalStateException("La organización $organizacion: orden_caja no tiene los campos de caja: aplique el modelo")
    }

    // an orden POR_ENVIAR never tried and that nobody holds, CANCELADA. false: a take got to it, and caja may hold it
    private suspend fun cancelar(
        t: TablaCore,
        orden: UUID,
        motivo: String
    ): Boolean =
        db
            .sql(
                "UPDATE ${t.tabla} SET ${t.columna("estado")} = :cancelada, ${t.columna("motivo")} = :motivo, updated_at = now() " +
                    "WHERE id = :id AND ${t.columna("estado")} = :por_enviar AND coalesce(${t.columna("intentos")}, 0) = 0 " +
                    "AND (${t.columna("tomado_hasta")} IS NULL OR ${t.columna("tomado_hasta")} < now())"
            ).bind("cancelada", EstadoOrdenCaja.CANCELADA)
            .bind("motivo", motivo)
            .bind("id", orden)
            .bind("por_enviar", EstadoOrdenCaja.POR_ENVIAR)
            .fetch()
            .awaitRowsUpdated() > 0

    // an orden from `previo` to `nuevo`, with the sincronización's motivo, only if it still is in `previo`
    private suspend fun mover(
        t: TablaCore,
        orden: UUID,
        previo: String,
        nuevo: String,
        motivo: String
    ): Boolean =
        db
            .sql(
                "UPDATE ${t.tabla} SET ${t.columna("estado")} = :nuevo, ${t.columna("motivo")} = :motivo, updated_at = now() " +
                    "WHERE id = :id AND ${t.columna("estado")} = :previo"
            ).bind("nuevo", nuevo)
            .bind("motivo", motivo)
            .bind("id", orden)
            .bind("previo", previo)
            .fetch()
            .awaitRowsUpdated() > 0
}
