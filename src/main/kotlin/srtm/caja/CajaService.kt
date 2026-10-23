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

// an orden of the contribuyente as GET /caja shows it. recibo: serie-numero of the pago of caja applied to it, not
// annulled (#175), else null (also a PAGADA_EN_CAJA whose pago has not come)
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
    val recibo: String?,
    val ultimoError: String?,
    val enviadaEn: Instant?,
    val retiradaEn: Instant?
)

// saldo_a_favor: what caja collected that srtm could not apply to a debt (#165, Pagadas.saldoAFavor)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class EstadoCaja(
    val ordenes: List<OrdenEnCaja>,
    // snake case would say saldo_afavor: the a is a word of its own
    @get:JsonProperty("saldo_a_favor")
    val saldoAFavor: BigDecimal
)

// a cuota of the liquidación and the cuota_predial that stands for it: the vigente one (the highest revision of its
// numero) while its monto is the same, else the next revision, still to write (escrita null). pagada: its vigente one
// was paid in caja, and stands whatever the liquidación says now
data class PredialDeseada(
    val cuota: Cuota,
    val revision: Int,
    val escrita: CuotaPredial?,
    val pagada: Boolean = false
)

// the vigente cuota_predial of each numero: its highest revision
fun vigentesPredial(escritas: List<CuotaPredial>): Map<Int, CuotaPredial> =
    escritas.filter { it.numero != null && it.revision != null }.groupBy { it.numero!! }.mapValues { (_, cs) -> cs.maxBy { it.revision!! } }

// D4: the cuotas with something to collect (monto > 0), each by its vigente cuota_predial or its next revision. D7: a
// numero whose vigente cuota_predial is among `pagadas` (paid in caja) keeps it, with its monto, even when the
// liquidación no longer has that cuota: a paid cuota never gets a new revision
fun prediales(
    cuotas: List<Cuota>,
    escritas: List<CuotaPredial>,
    pagadas: Set<String> = emptySet()
): List<PredialDeseada> {
    val vigentes = vigentesPredial(escritas)
    val pagados =
        vigentes.values
            .filter { it.id in pagadas && it.monto != null && it.vencimiento != null }
            .associate { it.numero!! to PredialDeseada(Cuota(it.numero, it.monto!!, it.vencimiento!!), it.revision!!, it, pagada = true) }
    val porCobrar =
        cuotas.filter { it.monto.signum() > 0 && it.numero !in pagados }.map { cuota ->
            val vigente = vigentes[cuota.numero]
            if (vigente != null && vigente.monto?.compareTo(cuota.monto) == 0) {
                PredialDeseada(cuota, vigente.revision!!, vigente)
            } else {
                PredialDeseada(cuota, (vigente?.revision ?: 0) + 1, null)
            }
        }
    return (porCobrar + pagados.values).sortedBy { it.cuota.numero }
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

// the observacion of the altas a PAGO_ANULADO's sincronización sends (#175)
const val MOTIVO_PAGO_ANULADO = "Recibo anulado en caja: la deuda vuelve con su importe vigente"

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
    private val pagadas: Pagadas,
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
    // queued, the loop sends them (the masiva: thousands of contribuyentes do not wait on caja). aPedido: the POST's,
    // which queues its ordenes in ERROR again (reintentosAPedido)
    suspend fun sincronizar(
        contribuyente: UUID,
        anio: Int,
        motivo: String,
        motivoDeRetiro: String = MOTIVO_SIN_DEUDA,
        enLinea: Boolean = true,
        aPedido: Boolean = false
    ): ResumenCaja {
        cajaDelLlamante() ?: throw ConflictException(SIN_CAJA_CONFIGURADA)
        val (resumen, porEnviar) = transaccion.executeAndAwait { planificarYEscribir(contribuyente, anio, motivo, motivoDeRetiro, aPedido) }
        if (!enLinea) return resumen + ResumenCaja(pendientes = porEnviar.size)
        return resumen + envio.enviar(porEnviar, config.plazoEnLinea)
    }

    // the hooks of srtm's writes (#164): only the contribuyentes that already have ordenes of the year. null: none has,
    // or the organization has no caja. enLinea false: only queued (a masiva's job)
    suspend fun sincronizarSiEnviado(
        contribuyentes: Set<UUID>,
        anio: Int,
        motivo: String,
        motivoDeRetiro: String = MOTIVO_SIN_DEUDA,
        enLinea: Boolean = true
    ): ResumenCaja? {
        if (!hayCaja()) return null
        return siEnviados(mapOf(anio to contribuyentes), motivo, motivoDeRetiro, enLinea)
    }

    // each contribuyente with ordenes of its year planned and written on its own (one that fails is the result's error,
    // the others go on), then everything they queued sent at once, for one plazo: a write never waits on caja once per
    // contribuyente. null: none had ordenes
    private suspend fun siEnviados(
        porAnio: Map<Int, Set<UUID>>,
        motivo: String,
        motivoDeRetiro: String,
        enLinea: Boolean
    ): ResumenCaja? {
        var total: ResumenCaja? = null
        val porEnviar = mutableListOf<UUID>()
        for ((anio, suyos) in porAnio) {
            for (c in suyos) {
                val resumen =
                    try {
                        if (ordenes(c, anio).isEmpty()) continue
                        val (hecho, ids) = transaccion.executeAndAwait { planificarYEscribir(c, anio, motivo, motivoDeRetiro) }
                        porEnviar += ids
                        hecho
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fallo(e)
                    }
                total = total?.plus(resumen) ?: resumen
            }
        }
        if (total == null) return null
        val ids = porEnviar.distinct()
        if (!enLinea) return total + ResumenCaja(pendientes = ids.size)
        return total +
            try {
                envio.enviar(ids, config.plazoEnLinea)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallo(e).copy(pendientes = ids.size)
            }
    }

    // #164: after a write of srtm that changed a debt (a declaración, a cuota de arbitrio): the ordenes of each year's
    // contribuyentes follow it, for those that already have ordenes (siEnviados). the cambio is read only when the
    // organization has a caja: without one nothing more is read. what it did goes to the response's header
    // (AvisoDeCaja). enLinea false: only queued, the loop sends them (a masiva's job). it never throws: the write
    // stands, and what kept a contribuyente from being synced is the header's error (logged by its class only: no
    // data of the contribuyente)
    suspend fun despuesDeEscribir(
        enLinea: Boolean = true,
        cambio: suspend () -> CambioDeDeuda
    ) {
        try {
            if (!hayCaja()) return
            val (motivo, porAnio) = cambio()
            siEnviados(porAnio, motivo, motivo, enLinea)?.let { AvisoDeCaja.anotar(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AvisoDeCaja.anotar(fallo(e))
        }
    }

    // #165: ordenes caja holds as PENDIENTE again (its PAGO_ANULADO returned them) whose debt srtm no longer has:
    // queued as retiros, by compare-and-set from what they were (ENVIADA, or PAGADA_EN_CAJA after a retiro caja
    // refused), with a try count of their own, and sent now for the plazo; the rest is the loop's
    suspend fun retirarDevueltas(ids: List<UUID>): ResumenCaja {
        if (ids.isEmpty()) return ResumenCaja()
        val t = tabla()
        val movidas =
            ids.filter { id ->
                db
                    .sql(
                        "UPDATE ${t.tabla} SET ${t.columna("estado")} = :por_retirar, ${t.columna("motivo")} = :motivo, ${t.columna("intentos")} = 0, " +
                            "${t.columna("ultimo_error")} = NULL, updated_at = now() WHERE id = :id AND ${t.columna("estado")} IN (:enviada, :pagada)"
                    ).bind("por_retirar", EstadoOrdenCaja.POR_RETIRAR)
                    .bind("motivo", MOTIVO_SIN_DEUDA)
                    .bind("id", id)
                    .bind("enviada", EstadoOrdenCaja.ENVIADA)
                    .bind("pagada", EstadoOrdenCaja.PAGADA_EN_CAJA)
                    .fetch()
                    .awaitRowsUpdated() > 0
            }
        if (movidas.isEmpty()) return ResumenCaja()
        return envio.enviar(movidas, config.plazoEnLinea)
    }

    // a caja configured twice is none for the hooks: nothing is sent
    private suspend fun hayCaja(): Boolean =
        try {
            cajaDelLlamante() != null
        } catch (_: ConflictException) {
            false
        }

    private fun fallo(e: Exception): ResumenCaja {
        log.warn("caja: no se pudo sincronizar tras la escritura ({})", e.javaClass.simpleName)
        return ResumenCaja(error = (e as? WasichaiException)?.message ?: "No se pudo sincronizar con caja")
    }

    // GET /caja: the contribuyente's ordenes of the year, each paid when a pago of caja was applied to it (or caja said
    // so to its retiro, while its pago has not come: Ruling 28), and its saldo a favor
    suspend fun estado(
        contribuyente: UUID,
        anio: Int
    ): EstadoCaja {
        cajaDelLlamante() ?: throw ConflictException(SIN_CAJA_CONFIGURADA)
        registros.get(CONTRIBUYENTE, Contribuyente::class.java, contribuyente)
        val recibos = pagadas.deContribuyentes(listOf("$contribuyente")).associate { it.orden to it.recibo }
        val suyas = ordenes(contribuyente, anio)
        val conPago = conPago(suyas)
        val ordenes =
            suyas.sortedWith(compareBy({ it.ranura }, { it.referencia })).map {
                OrdenEnCaja(
                    it.id,
                    it.referencia,
                    it.tipo,
                    it.ranura,
                    it.importe,
                    it.concepto,
                    it.estado,
                    // PAGADA_EN_CAJA too: caja refused its retiro because it is paid there, until its pago arrives
                    (it.estado == EstadoOrdenCaja.PAGADA_EN_CAJA && it.id !in conPago) || it.id in recibos,
                    recibos[it.id],
                    it.ultimoError,
                    it.enviadaEn,
                    it.retiradaEn
                )
            }
        return EstadoCaja(ordenes, pagadas.saldoAFavor("$contribuyente"))
    }

    // inside the transaction: the plan written. what it did, and the ordenes to send now (the new ones, the retiros,
    // and the ones still waiting from before). the cancelaciones go first: one a take got to meanwhile defers its
    // ranura, and its new alta is not written (caja may hold the one it would replace)
    private suspend fun planificarYEscribir(
        id: UUID,
        anio: Int,
        motivo: String,
        motivoDeRetiro: String,
        aPedido: Boolean = false
    ): Pair<ResumenCaja, List<UUID>> {
        candados.bloquearEnOrganizacion(Candado.CAJA_CONTRIBUYENTE, id.toString())
        val persona = registros.get(CONTRIBUYENTE, Contribuyente::class.java, id)
        if (persona.tipoDocumento == SIN_DOCUMENTO) return ResumenCaja(sinDocumento = 1) to emptyList()
        val leidas = ordenes(id, anio)
        // under the contribuyente's lock, as a pago that arrives (PagosService): a cuota paid is no alta nor retiro
        val suyas = pagadas.deContribuyentes(listOf("$id"))
        val deseadas =
            deseadasPredial(id, anio, suyas.mapNotNull { it.cuotaPredial }.toSet()) +
                deseadasArbitrio(id, anio, suyas.mapNotNull { it.cuotaArbitrio }.toSet())
        val conPago = conPago(leidas)
        val actuales = if (aPedido) reintentar(leidas, reintentosAPedido(deseadas, leidas, conPago), motivoDeRetiro) else leidas
        val plan = planificar(deseadas, actuales, conPago)
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
    // changed). a liquidación that lacks a parámetro has none. D7: the cuotas_predial among `pagadas` (paid in caja)
    // stand as they were paid, with or without a liquidación
    private suspend fun deseadasPredial(
        id: UUID,
        anio: Int,
        pagadas: Set<String>
    ): List<OrdenDeseada> {
        val liquidacion = liquidaciones.determinar(id, anio).liquidacion
        if (liquidacion.faltan.isNotEmpty() && pagadas.isEmpty()) return emptyList()
        val cuotas = if (liquidacion.faltan.isEmpty()) liquidacion.cuotas else emptyList()
        val escritas = registros.all(ObjetosCaja.CUOTA_PREDIAL, CuotaPredial::class.java, filters = mapOf("contribuyente" to "$id", "anio" to "$anio"))
        val hoy = LocalDate.now(LIMA)
        return prediales(cuotas, escritas, pagadas).map { p ->
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
                pagada = p.pagada
            )
        }
    }

    // the contribuyente's vigentes cuotas de arbitrio of the year with something to collect. vigente is read over each
    // predio's year: a correction may have charged the month to someone else. pagadas: the ones paid in caja
    private suspend fun deseadasArbitrio(
        id: UUID,
        anio: Int,
        pagadas: Set<String>
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
                pagada = q.id in pagadas
            )
        }
    }

    // which PAGADA_EN_CAJA among these a pago of caja came for (Ruling 28): nothing read when there is none
    private suspend fun conPago(ordenes: List<OrdenCaja>): Set<String> =
        pagadas.conPago(ordenes.filter { it.estado == EstadoOrdenCaja.PAGADA_EN_CAJA }.mapNotNull { it.id })

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

    // Ruling 27: the ordenes in ERROR queued again with their new estado, by compare-and-set from ERROR: tried (caja
    // may hold them, so no cancel), and free to take now. one withdrawn goes with the sincronización's motivo. the
    // ordenes as they are now, for the plan (and the ones it sends)
    private suspend fun reintentar(
        actuales: List<OrdenCaja>,
        nuevos: Map<UUID, String>,
        motivoDeRetiro: String
    ): List<OrdenCaja> {
        if (nuevos.isEmpty()) return actuales
        val t = tabla()
        val c = { campo: String -> t.columna(campo) }
        val movidas =
            nuevos.filter { (orden, estado) ->
                val motivo = if (estado == EstadoOrdenCaja.POR_RETIRAR) ", ${c("motivo")} = :motivo" else ""
                var spec =
                    db
                        .sql(
                            "UPDATE ${t.tabla} SET ${c("estado")} = :nuevo, ${c("intentos")} = greatest(coalesce(${c("intentos")}, 0), 1), " +
                                "${c("tomado_hasta")} = NULL$motivo, updated_at = now() WHERE id = :id AND ${c("estado")} = :error"
                        ).bind("nuevo", estado)
                        .bind("id", orden)
                        .bind("error", EstadoOrdenCaja.ERROR)
                if (motivo.isNotEmpty()) spec = spec.bind("motivo", motivoDeRetiro)
                spec.fetch().awaitRowsUpdated() > 0
            }
        return actuales.map { o ->
            val nuevo = o.id?.let { movidas[UUID.fromString(it)] } ?: return@map o
            o.copy(estado = nuevo, intentos = maxOf(o.intentos ?: 0, 1))
        }
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
