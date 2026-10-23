package srtm.caja

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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
import srtm.rentas.Records
import srtm.rentas.Registros
import tools.jackson.databind.json.JsonMapper
import wasichai.core.data.RecordCriterion
import java.util.UUID

// what srtm answers caja's buzón: the status decides the delivery (caja-backend's clasificar), the body is exact
data class RespuestaACaja(
    val estado: HttpStatus,
    val cuerpo: String
)

// #165: the pagos caja's buzón delivers, as the organization's service account (the role CAJA). each pagoId is
// received once: its pago_caja keeps the body srtm answered, and the buzón's retry of it gets that body again with a
// 409 (delivered, for caja; a 202's a_cuenta is not lost). a PAGO_REGISTRADO has each orden applied to its cuota or
// kept a cuenta (Imputacion); a PAGO_ANULADO undoes its original. pago and detalles are written in one transaction,
// through Registros (srtm's mark), under the pagoId's lock and the contribuyentes' (Candados: the caja's locks, in
// their order): a sincronización and a write of cuotas read what is paid under the same ones. a PAGO_ANULADO locks
// only its original's contribuyente (the pago's, its first applied orden's): one whose other ordenes are another
// contribuyente's does not wait for that one's sincronización, which at worst still reads them paid and holds back an
// alta caja already holds again as PENDIENTE; the next sincronización reads them unpaid. caja waits 10 s for the
// answer
@Service
class PagosService(
    private val registros: Registros,
    private val caja: CajaService,
    private val pagadas: Pagadas,
    private val candados: Candados,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun recibir(cuerpo: String?): RespuestaACaja {
        val evento = leerEvento(cuerpo)
        // a 409 would be caja's «delivered», and the pago lost: an organization without caja is asked again later
        if (pagadas.organizacionConCaja() == null) return RespuestaACaja(HttpStatus.SERVICE_UNAVAILABLE, motivo(SIN_CAJA_CONFIGURADA))
        return when (evento) {
            is PagoRegistrado -> transaccion.executeAndAwait { registrar(evento) }
            is PagoAnulado -> {
                val (respuesta, ordenes) = transaccion.executeAndAwait { anular(evento) }
                // after the commit, never inside it: what it does with caja never changes the answer
                retirar(ordenes)
                respuesta
            }
        }
    }

    private suspend fun registrar(e: PagoRegistrado): RespuestaACaja {
        candados.bloquearEnOrganizacion(Candado.CAJA_PAGO, e.pagoId)
        recibido(e.pagoId)?.let { return repetido(it) }
        val enSrtm =
            registros
                .donde(ObjetosCaja.ORDEN_CAJA, OrdenCaja::class.java, listOf(entre("referencia", e.ordenes.map { it.referencia })))
                .associateBy { it.referencia }
        for (c in enSrtm.values
            .mapNotNull { it.contribuyente }
            .distinct()
            .sorted()) {
            candados.bloquearEnOrganizacion(Candado.CAJA_CONTRIBUYENTE, c)
        }
        val ya = pagadas.deOrdenes(enSrtm.values.mapNotNull { it.id }).associate { it.orden to it.recibo }
        val vigentes = vigentes(enSrtm.values)
        val detalles =
            e.ordenes.map { o ->
                val suya = enSrtm[o.referencia]
                val vigente = suya?.id in vigentes
                Imputacion.clasificar(o, suya, vigente, suya?.id?.let(ya::get), if (suya != null && !vigente) sustituta(suya) else null)
            }
        val contribuyente = (detalles.filter { it.aplicado } + detalles).firstNotNullOfOrNull { it.contribuyente }
        val aplicado = detalles.all { it.aplicado }
        val motivo = if (aplicado) null else Imputacion.motivo(detalles)
        val cuerpo = Imputacion.respuesta(aplicado, !aplicado, motivo)
        val pago =
            registros.create(
                ObjetosCaja.PAGO_CAJA,
                PagoCaja::class.java,
                atributos(
                    PagoCaja(
                        pagoId = e.pagoId,
                        tipo = TipoPagoCaja.PAGO_REGISTRADO,
                        reciboNumero = e.recibo.numero,
                        reciboSerie = e.recibo.serie,
                        fechaPago = e.recibo.fechaDePago,
                        cajero = e.recibo.cajero,
                        formaPago = e.recibo.formaDePago,
                        pagadorDocumento = e.pagador?.documento,
                        pagadorNombre = e.pagador?.nombre,
                        total = e.total,
                        motivo = motivo,
                        aplicado = aplicado,
                        aCuenta = !aplicado,
                        respuesta = cuerpo,
                        contribuyente = contribuyente
                    )
                )
            )
        registros.createAll(
            ObjetosCaja.PAGO_CAJA_DETALLE,
            PagoCajaDetalle::class.java,
            detalles.map {
                atributos(
                    PagoCajaDetalle(
                        referencia = it.referencia,
                        importe = it.importe,
                        aplicado = it.aplicado,
                        motivo = it.motivo,
                        clave = "${e.pagoId}|${it.referencia}",
                        pago = pago.id,
                        orden = it.orden,
                        contribuyente = it.contribuyente ?: contribuyente
                    )
                )
            }
        )
        return RespuestaACaja(if (aplicado) HttpStatus.OK else HttpStatus.ACCEPTED, cuerpo)
    }

    // the anulación written, and the ordenes of its original (to withdraw after the commit, retirar)
    private suspend fun anular(e: PagoAnulado): Pair<RespuestaACaja, List<String>> {
        candados.bloquearEnOrganizacion(Candado.CAJA_PAGO, e.pagoId)
        recibido(e.pagoId)?.let { return repetido(it) to emptyList() }
        if (e.pagoOriginalId != e.pagoId) candados.bloquearEnOrganizacion(Candado.CAJA_PAGO, e.pagoOriginalId)
        // caja never sends it before its original: one srtm does not know is asked again (a 409 would lose it)
        val original =
            recibido(e.pagoOriginalId)?.takeIf { it.tipo == TipoPagoCaja.PAGO_REGISTRADO }
                ?: return RespuestaACaja(HttpStatus.SERVICE_UNAVAILABLE, motivo(SIN_ORIGINAL)) to emptyList()
        original.contribuyente?.let { candados.bloquearEnOrganizacion(Candado.CAJA_CONTRIBUYENTE, it) }
        // an anulación is no payment: never a cuenta, whatever its original was (caja reads a_cuenta from the 409 of a
        // retry, and would mark the anulación itself)
        val cuerpo = Imputacion.respuesta(true, false, null)
        registros.create(
            ObjetosCaja.PAGO_CAJA,
            PagoCaja::class.java,
            atributos(
                PagoCaja(
                    pagoId = e.pagoId,
                    tipo = TipoPagoCaja.PAGO_ANULADO,
                    reciboNumero = e.recibo.numero,
                    reciboSerie = e.recibo.serie,
                    fechaPago = e.fecha,
                    cajero = e.recibo.cajero,
                    formaPago = e.recibo.formaDePago,
                    pagadorDocumento = original.pagadorDocumento,
                    pagadorNombre = original.pagadorNombre,
                    total = e.total,
                    pagoOriginal = e.pagoOriginalId,
                    motivo = e.motivo,
                    aplicado = true,
                    aCuenta = false,
                    respuesta = cuerpo,
                    contribuyente = original.contribuyente
                )
            )
        )
        val ordenes =
            registros
                .all(ObjetosCaja.PAGO_CAJA_DETALLE, PagoCajaDetalle::class.java, filters = mapOf("pago" to original.id!!))
                .mapNotNull { it.orden }
                .distinct()
        return RespuestaACaja(HttpStatus.OK, cuerpo) to ordenes
    }

    // #164: caja returned the anulled pago's ordenes to PENDIENTE. the ones whose debt srtm no longer has are
    // withdrawn now; the vigentes are debt again as they were. a failure here is logged by its class only (no data of
    // the contribuyente) and leaves them to the next sincronización
    private suspend fun retirar(ordenes: List<String>) {
        if (ordenes.isEmpty()) return
        try {
            val suyas = registros.byIds(ObjetosCaja.ORDEN_CAJA, OrdenCaja::class.java, ordenes).values
            val vigentes = vigentes(suyas)
            val sinDeuda = suyas.filter { it.id != null && it.id !in vigentes }.map { UUID.fromString(it.id) }
            caja.retirarDevueltas(sinDeuda)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("caja: no se pudieron retirar las órdenes de un pago anulado ({})", e.javaClass.simpleName)
        }
    }

    private suspend fun recibido(pagoId: String): PagoCaja? =
        registros.all(ObjetosCaja.PAGO_CAJA, PagoCaja::class.java, filters = mapOf("pago_id" to pagoId)).firstOrNull()

    // the same pagoId again: what was answered then, byte for byte
    private fun repetido(p: PagoCaja) =
        RespuestaACaja(HttpStatus.CONFLICT, p.respuesta ?: Imputacion.respuesta(p.aplicado == true, p.aCuenta == true, p.motivo))

    // the ids of the ordenes whose debt srtm still has: their cuota is vigente (de arbitrio: not annulled and the
    // latest version, Arbitrios.vigentes; de predial: the highest revision of its numero), and srtm did not withdraw
    // them (a retiro queued or done, a cancelación, or a retiro caja refused because it was paid)
    private suspend fun vigentes(ordenes: Collection<OrdenCaja>): Set<String> {
        val vivas = ordenes.filter { it.id != null && it.estado !in RETIRADAS }
        val arbitrios = vivas.mapNotNull { it.cuotaArbitrio }
        val prediales = vivas.mapNotNull { it.cuotaPredial }
        val cuotasVigentes = mutableSetOf<String>()
        if (arbitrios.isNotEmpty()) {
            val cuotas = registros.byIds(CUOTA_ARBITRIO, CuotaArbitrio::class.java, arbitrios).values
            // a cuota without predio or anio has no year to be vigente in: never a filter of "null" (a 400 kills the delivery)
            val anios = cuotas.mapNotNull { q -> q.anio?.let { anio -> q.predio?.let { it to anio } } }.distinct()
            for ((predio, anio) in anios) {
                val delAnio = mapOf("predio" to predio, "anio" to "$anio")
                val anuladas = registros.all(ANULACION_CUOTA_ARBITRIO, AnulacionCuotaArbitrio::class.java, filters = delAnio).mapNotNull { it.cuota }.toSet()
                cuotasVigentes += Arbitrios.vigentes(registros.all(CUOTA_ARBITRIO, CuotaArbitrio::class.java, filters = delAnio), anuladas).mapNotNull { it.id }
            }
        }
        if (prediales.isNotEmpty()) {
            val cuotas = registros.byIds(ObjetosCaja.CUOTA_PREDIAL, CuotaPredial::class.java, prediales).values
            val anios = cuotas.mapNotNull { q -> q.anio?.let { anio -> q.contribuyente?.let { it to anio } } }.distinct()
            for ((contribuyente, anio) in anios) {
                val escritas =
                    registros.all(
                        ObjetosCaja.CUOTA_PREDIAL,
                        CuotaPredial::class.java,
                        filters =
                            mapOf(
                                "contribuyente" to contribuyente,
                                "anio" to "$anio"
                            )
                    )
                cuotasVigentes += vigentesPredial(escritas).values.mapNotNull { it.id }
            }
        }
        return vivas.filter { (it.cuotaArbitrio ?: it.cuotaPredial) in cuotasVigentes }.mapNotNull { it.id }.toSet()
    }

    // the orden that replaced one srtm no longer collects (its alta's sustituye_a): its referencia
    private suspend fun sustituta(o: OrdenCaja): String? =
        registros
            .all(ObjetosCaja.ORDEN_CAJA, OrdenCaja::class.java, filters = mapOf("sustituye_a" to o.referencia.orEmpty()))
            .firstOrNull { it.estado != EstadoOrdenCaja.CANCELADA }
            ?.referencia

    private fun atributos(dto: Any): Map<String, Any?> = Records.attributes(dto).filterValues { it != null }

    private fun motivo(texto: String) = JSON.writeValueAsString(mapOf("motivo" to texto))

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()

        const val SIN_ORIGINAL = "no conozco el pago original"

        val RETIRADAS = setOf(EstadoOrdenCaja.POR_RETIRAR, EstadoOrdenCaja.RETIRADA, EstadoOrdenCaja.CANCELADA, EstadoOrdenCaja.PAGADA_EN_CAJA)

        // a text field among `valores`: core binds them as one array
        fun entre(
            campo: String,
            valores: Collection<String>
        ) = RecordCriterion { definition, bind ->
            val columna = "\"${definition.fields.first { it.name == campo }.columnName}\""
            "$columna = ANY(${bind(valores.toTypedArray())})"
        }
    }
}
