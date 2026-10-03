package srtm.sanciones

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonUnwrapped
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.Observacion
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Records
import srtm.rentas.Registros
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import java.time.LocalDate
import java.util.UUID

// POST /infracciones/notificaciones
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoNotificacion(
    val numero: String? = null,
    val fecha: LocalDate? = null,
    val contribuyente: String? = null,
    val predio: String? = null,
    val direccion: String? = null,
    val motivo: String? = null,
    val plazoDias: Int? = null,
    val observacion: String? = null
)

// POST /infracciones/notificaciones/{id}/subsanacion: fecha today when not given
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoSubsanacion(
    val fecha: LocalDate? = null,
    val observacion: String? = null
)

data class Subsanada(
    val fecha: LocalDate
)

data class ActaDeLaNotificacion(
    val id: String,
    val numero: String
)

// a notificación previa as the portal shows it: the record, flat, and what is derived from it at vencidas_a (never
// stored: Notificaciones.vencimiento and vencida are the one definition). corte travels only in the padrón of vencidas
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class NotificacionPrevia(
    @get:JsonUnwrapped val registro: NotificacionAdministrativa,
    val vencimiento: LocalDate?,
    val vencida: Boolean,
    val vencidasA: LocalDate,
    val subsanada: Subsanada?,
    val acta: ActaDeLaNotificacion?,
    val contribuyenteNombre: String?,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val corte: LocalDate? = null
)

// what a page of notificaciones needs to be derived: their subsanaciones, the actas that name them and their
// contribuyentes, one query each
data class HechosDeNotificaciones(
    val subsanaciones: Map<String, SubsanacionNotificacion>,
    val actas: Map<String, Papeleta>,
    val contribuyentes: Map<String, Contribuyente>
)

// the notificaciones previas (rentas' NotificacionAdministrativa) and their subsanación: only added, never edited. the
// portal reads them with what is derived at a day: vencimiento, vencida, the subsanación and the acta
@Service
class NotificacionesService(
    private val registros: Registros,
    private val permisos: Permisos,
    private val candados: Candados,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)

    // GET /infracciones/notificaciones: the newest first. numero is the whole number; desde and hasta bound fecha
    suspend fun pagina(
        numero: String?,
        contribuyente: UUID?,
        desde: LocalDate?,
        hasta: LocalDate?,
        vencidasA: LocalDate,
        page: Int,
        size: Int
    ): PageResponse<NotificacionPrevia> {
        val filtros =
            listOfNotNull(numero?.let { "numero" to Cuis.normalizar(it) }, contribuyente?.let { "contribuyente" to it.toString() }).toMap()
        val pagina =
            registros.page(
                NOTIFICACION_ADMINISTRATIVA,
                NotificacionAdministrativa::class.java,
                RecordQuery(
                    page = PageRequest.of(page, size),
                    sort = "fecha",
                    descending = true,
                    filters = filtros,
                    criteria = listOfNotNull(Filtros.entre("fecha", desde, hasta))
                )
            )
        val hechos = hechos(pagina.content)
        return PageResponse(pagina.content.map { derivar(it, vencidasA, hechos) }, pagina.page, pagina.size, pagina.totalElements, pagina.totalPages)
    }

    // GET /infracciones/notificaciones/vencidas: the ones neither subsanadas nor with an acta, vencidas at `corte`, the
    // oldest vencimiento first. vencida is Notificaciones.vencida, here as everywhere: the candidates are read (only
    // those with a plazo, from before corte: a vencida has both) and the page is cut after deriving them
    suspend fun vencidas(
        corte: LocalDate,
        page: Int,
        size: Int
    ): PageResponse<NotificacionPrevia> {
        val candidatas =
            registros.donde(NOTIFICACION_ADMINISTRATIVA, NotificacionAdministrativa::class.java, listOf(conPlazoAntesDe(corte)))
        val hechos = hechos(candidatas)
        val vencidas =
            candidatas
                .filter { Notificaciones.vencida(it, corte) && it.id !in hechos.subsanaciones && it.id !in hechos.actas }
                .sortedWith(compareBy({ Notificaciones.vencimiento(it.fecha!!, it.plazoDias) }, { it.numero }))
        val filas = vencidas.drop(page * size).take(size).map { derivar(it, corte, hechos).copy(corte = corte) }
        return PageResponse.of(filas, page, size, vencidas.size.toLong())
    }

    // GET /infracciones/notificaciones/por-contribuyente: every one of the contribuyente (404 when it does not exist)
    suspend fun delContribuyente(
        contribuyente: UUID,
        hoy: LocalDate,
        page: Int,
        size: Int
    ): PageResponse<NotificacionPrevia> {
        registros.get(CONTRIBUYENTE, Contribuyente::class.java, contribuyente)
        return pagina(null, contribuyente, null, null, hoy, page, size)
    }

    // POST /infracciones/notificaciones: 201 with the record and its derivados at `hoy`. a contribuyente or predio
    // that does not exist is a 404; a número already written, a 409; a fecha after today, a 422
    suspend fun registrar(
        pedido: PedidoNotificacion,
        hoy: LocalDate
    ): NotificacionPrevia {
        permisos.exigirCrear(NOTIFICACION_ADMINISTRATIVA, "registrar una notificación previa")
        val n =
            NotificacionAdministrativa(
                numero = pedido.numero?.let(Cuis::normalizar)?.ifEmpty { null },
                fecha = pedido.fecha,
                direccion = pedido.direccion?.trim()?.ifEmpty { null },
                motivo = pedido.motivo?.trim()?.ifEmpty { null },
                plazoDias = pedido.plazoDias,
                observacion = pedido.observacion?.trim(),
                contribuyente = relacion("contribuyente", pedido.contribuyente),
                predio = relacion("predio", pedido.predio)
            )
        val malas = invariantes(n)
        if (malas.isNotEmpty()) throw ValidationException("La notificación no es válida", malas)
        OrdenDeLosActos.exigir("la notificación ${n.numero}", n.fecha!!, hoy)
        val contribuyente = n.contribuyente?.let { registros.get(CONTRIBUYENTE, Contribuyente::class.java, UUID.fromString(it)) }
        n.predio?.let { registros.get(PREDIO, Predio::class.java, UUID.fromString(it)) }
        val repetida = ConflictException("La notificación ${n.numero} ya está registrada: el número no se repite")
        if (registros.all(NOTIFICACION_ADMINISTRATIVA, NotificacionAdministrativa::class.java, filters = mapOf("numero" to n.numero!!)).isNotEmpty()) {
            throw repetida
        }
        val escrita =
            try {
                registros.create(
                    NOTIFICACION_ADMINISTRATIVA,
                    NotificacionAdministrativa::class.java,
                    Records.attributes(n.copy(observacion = Observacion.de(n.observacion)))
                )
            } catch (_: DuplicateKeyException) {
                throw repetida
            }
        return derivar(escrita, hoy, HechosDeNotificaciones(emptyMap(), emptyMap(), listOfNotNull(contribuyente).associateBy { it.id!! }))
    }

    // POST /infracciones/notificaciones/{id}/subsanacion (decision 5): once (409); not after its plazo, nor of one that
    // already originated an acta, nor dated after today or before the notificación (422). under the notificación's
    // lock: the acta that names it (srtm.sanciones' acta service) takes the same one, so they do not cross
    suspend fun subsanar(
        id: UUID,
        pedido: PedidoSubsanacion,
        hoy: LocalDate
    ): SubsanacionNotificacion {
        permisos.exigirCrear(SUBSANACION_NOTIFICACION, "subsanar una notificación previa")
        val observacion = Observacion.de(pedido.observacion)
        val n = registros.get(NOTIFICACION_ADMINISTRATIVA, NotificacionAdministrativa::class.java, id)
        val fecha = pedido.fecha ?: hoy
        OrdenDeLosActos.exigir("la subsanación de la notificación ${n.numero}", fecha, hoy, ActoPrevio("la notificación ${n.numero}", n.fecha!!))
        val yaSubsanada = ConflictException("La notificación ${n.numero} ya está subsanada")
        return try {
            transaccion.executeAndAwait {
                candados.bloquear(Candado.NOTIFICACION, n.id!!)
                val hechos = hechos(listOf(n))
                Notificaciones.exigirSubsanable(n, n.id in hechos.subsanaciones, n.id in hechos.actas, fecha)
                registros.create(
                    SUBSANACION_NOTIFICACION,
                    SubsanacionNotificacion::class.java,
                    Records.attributes(SubsanacionNotificacion(fecha = fecha, observacion = observacion, clave = n.id, notificacion = n.id))
                )
            }
        } catch (_: DuplicateKeyException) {
            throw yaSubsanada
        }
    }

    // the subsanaciones, actas and contribuyentes of these notificaciones
    suspend fun hechos(notificaciones: List<NotificacionAdministrativa>): HechosDeNotificaciones {
        val ids = notificaciones.mapNotNull { it.id }
        if (ids.isEmpty()) return HechosDeNotificaciones(emptyMap(), emptyMap(), emptyMap())
        val subsanaciones =
            ids.chunked(PageRequest.MAX_SIZE).flatMap {
                registros.donde(SUBSANACION_NOTIFICACION, SubsanacionNotificacion::class.java, listOf(Filtros.entre("notificacion", it)))
            }
        // two actas naming one notificación: the first by number stands for it
        val actas =
            ids.chunked(PageRequest.MAX_SIZE).flatMap {
                registros.donde(PAPELETA, Papeleta::class.java, listOf(Filtros.entre("notificacion_previa", it)), sort = "numero")
            }
        return HechosDeNotificaciones(
            subsanaciones.associateBy { it.notificacion!! },
            actas.groupBy { it.notificacionPrevia!! }.mapValues { it.value.first() },
            registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, notificaciones.mapNotNull { it.contribuyente })
        )
    }

    // one notificación with what is derived at `al`
    fun derivar(
        n: NotificacionAdministrativa,
        al: LocalDate,
        hechos: HechosDeNotificaciones
    ) = NotificacionPrevia(
        registro = n,
        vencimiento = Notificaciones.vencimiento(n.fecha!!, n.plazoDias),
        vencida = Notificaciones.vencida(n, al),
        vencidasA = al,
        subsanada = hechos.subsanaciones[n.id]?.let { Subsanada(it.fecha!!) },
        acta = hechos.actas[n.id]?.let { ActaDeLaNotificacion(it.id!!, it.numero!!) },
        contribuyenteNombre = n.contribuyente?.let { hechos.contribuyentes[it]?.nombreCompleto }
    )

    // a notificación with a plazo, dated before `corte`: every vencida is one (plazo_dias is at least 1)
    private fun conPlazoAntesDe(corte: LocalDate) =
        RecordCriterion { definition, bind ->
            fun columna(campo: String) = "\"${definition.fields.first { it.name == campo }.columnName}\""
            "${columna("plazo_dias")} IS NOT NULL AND ${columna("fecha")} < ${bind(corte)}"
        }
}
