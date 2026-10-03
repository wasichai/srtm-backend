package srtm.anuncios

import com.fasterxml.jackson.annotation.JsonUnwrapped
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.Observacion
import srtm.Violaciones
import srtm.impuesto.PARAMETRO_TRIBUTARIO
import srtm.impuesto.ParametroTributario
import srtm.legible
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Records
import srtm.rentas.Registros
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

// POST /anuncios: the anuncio as declared. never a tasa: the backend reads it (one sent is ignored). dates as text, so
// a bad one is a 400 that names its field
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoAnuncio(
    val contribuyente: String? = null,
    val predio: String? = null,
    val clase: String? = null,
    val tipo: String? = null,
    val emplazamiento: String? = null,
    val forma: String? = null,
    val denominacion: String? = null,
    val direccion: String? = null,
    val area: BigDecimal? = null,
    val lados: Int? = null,
    val cantidad: Int? = null,
    val fechaAutorizacion: String? = null,
    val vigenciaHasta: String? = null,
    val expediente: String? = null,
    val fechaExpediente: String? = null,
    val licenciaTexto: String? = null,
    val observacion: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoRenovacion(
    val fecha: String? = null,
    val vigenciaHasta: String? = null,
    val observacion: String? = null
)

// the cese or the retiro
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoBaja(
    val fecha: String? = null,
    val motivo: String? = null,
    val observacion: String? = null
)

// 201 with the anuncio and its AUTORIZACION; 200 and ya_existia on a resubmission of the same Idempotency-Key
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnuncioRegistrado(
    val anuncio: Anuncio,
    val movimiento: MovimientoAnuncio,
    val yaExistia: Boolean
)

// an anuncio with what derives of it on a day: its estado and the vigencia in force (null: without term)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnuncioAlDia(
    @get:JsonUnwrapped val anuncio: Anuncio,
    val estado: String,
    val vigenciaHastaVigente: LocalDate?
)

// a row of the padrón, at vigentes_a
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnuncioEnPadron(
    @get:JsonUnwrapped val anuncio: Anuncio,
    val contribuyenteNombre: String?,
    val estado: String,
    val vigenciaHastaVigente: LocalDate?,
    val vigentesA: LocalDate
)

// the tasas accrued up to al_dia: what the acts determined, not a debt
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Devengado(
    val importe: BigDecimal,
    val alDia: LocalDate
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FichaAnuncio(
    val anuncio: Anuncio,
    val movimientos: List<MovimientoAnuncio>,
    val estado: String,
    val vigenciaHastaVigente: LocalDate?,
    val alDia: LocalDate,
    val devengado: Devengado,
    val acciones: Acciones
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TasaDeClase(
    val clase: String,
    val tasa: BigDecimal,
    val parametroId: String,
    val vigenciaDesde: LocalDate
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TasasAnuncios(
    val anio: Int,
    val tasas: List<TasaDeClase>,
    val faltan: List<String>
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AnunciosDe(
    val alDia: LocalDate,
    val anuncios: List<AnuncioAlDia>
)

// the padrón's filters, already read by the controller
data class FiltrosPadron(
    val contribuyente: UUID? = null,
    val clase: String? = null,
    val estado: String? = null,
    val vigentesA: LocalDate,
    val q: String? = null
)

// the tasa de anuncios y propaganda: the anuncio, its numbering and its acts. reads and writes go through Registros as
// the caller (core applies their permissions); writes carry srtm's mark, which ReglaDeAnuncios asks for. the pure rules
// are Anuncios. every act reads the TASA_ANUNCIO it accrues and copies it, with the row as its procedencia
@Service
class AnunciosService(
    private val registros: Registros,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser,
    private val candados: Candados,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)

    // POST /anuncios. one transaction: the correlativo of the year under its lock, the anuncio and its AUTORIZACION.
    // the same Idempotency-Key answers the first one, without accruing again (its unique is the guarantee; the reads
    // before and under the lock give the useful answer)
    suspend fun registrar(
        pedido: PedidoAnuncio,
        idempotencia: String?
    ): AnuncioRegistrado {
        exigirCrear(ANUNCIO, "Registrar un anuncio")
        exigirCrear(MOVIMIENTO_ANUNCIO, "Registrar un anuncio")
        val clave = idempotencia?.trim()?.ifEmpty { null }
        if (clave != null && clave.length > Largos.IDEMPOTENCIA) {
            throw ValidationException("La clave de idempotencia es demasiado larga", "Idempotency-Key", "de 1 a ${Largos.IDEMPOTENCIA} caracteres")
        }
        clave?.let { yaRegistrado(it) }?.let { return it }

        val hoy = LocalDate.now()
        val borrador = leer(pedido, hoy)
        val fecha = borrador.fechaAutorizacion!!
        registros.get(CONTRIBUYENTE, Contribuyente::class.java, UUID.fromString(borrador.contribuyente))
        borrador.predio?.let { registros.get(PREDIO, Predio::class.java, UUID.fromString(it)) }
        Anuncios.exigirOrden("la autorización", fecha, hoy, emptyList())
        val anio = fecha.year
        val tasa = tasaDe(borrador.clase!!, fecha) ?: throw FaltanAnuncios(listOf(Llaves.falta(borrador.clase, anio)))

        return try {
            transaccion.executeAndAwait {
                candados.bloquear(Candado.SERIE, "AN|$anio")
                // two first requests with the same key, ordered by the lock: the second answers the first
                clave?.let { yaRegistrado(it) }?.let { return@executeAndAwait it }
                val correlativo = ultimoCorrelativo(anio) + 1
                val anuncio =
                    registros.create(
                        ANUNCIO,
                        Anuncio::class.java,
                        Records.attributes(borrador.copy(correlativo = correlativo, numero = numeroDeAnuncio(anio, correlativo), claveIdempotencia = clave))
                    )
                val autorizacion =
                    Anuncios.devengo(anuncio.id!!, AUTORIZACION, fecha, anio, tasa, borrador.vigenciaHasta, borrador.observacion!!)
                AnuncioRegistrado(anuncio, registros.create(MOVIMIENTO_ANUNCIO, MovimientoAnuncio::class.java, Records.attributes(autorizacion)), false)
            }
        } catch (_: DuplicateKeyException) {
            // a unique's net: the same key confirmed meanwhile (the first one's answer), or a number taken despite the lock.
            // the transaction was rolled back whole. the 409 does not carry it as its cause: srtm.Duplicados would answer
            // it by the cause, with its generic message
            clave?.let { yaRegistrado(it) }
                ?: throw ConflictException("El alta chocó con otra que se confirmó a la vez y no se registró nada: vuelva a intentarlo")
        }
    }

    // POST /anuncios/{id}/renovacion: the ejercicio it renews, with that ejercicio's tasa (read on its January 1)
    suspend fun renovar(
        id: UUID,
        pedido: PedidoRenovacion
    ): MovimientoAnuncio {
        exigirCrear(MOVIMIENTO_ANUNCIO, "Renovar un anuncio")
        val v = Violaciones()
        val fecha = fecha(v, "fecha", pedido.fecha)
        val vigencia = fecha(v, "vigencia_hasta", pedido.vigenciaHasta)
        v.observacion(pedido.observacion)
        v.lanzar("El pedido de renovación")
        val hoy = LocalDate.now()
        val dia = fecha ?: hoy
        val anuncio = registros.get(ANUNCIO, Anuncio::class.java, id)
        val movimientos = movimientosDe(id)
        val numero = anuncio.numero
        val estado = Anuncios.estado(movimientos, maxOf(dia, hoy))
        if (!Anuncios.admiteRenovacion(estado)) {
            throw ActoNoAdmitido("El anuncio $numero está $estado: un anuncio cesado no se renueva, y no devenga más tasa. La ya devengada no se toca")
        }
        Anuncios.exigirOrden("la renovación de $numero", dia, hoy, previos(anuncio, movimientos))
        if (vigencia != null && vigencia < dia) {
            throw ActoNoAdmitido(
                "La renovación de $numero del ${dia.legible()} no puede vencer el ${vigencia.legible()}: una prórroga que termina antes de empezar cobra una tasa por nada"
            )
        }
        val anio = Anuncios.ejercicioQueRenueva(Anuncios.vigencia(movimientos, dia), dia, vigencia)
        val repetido = ConflictException("El ejercicio $anio de $numero ya está devengado (${referenciaDeCargo(id.toString(), anio)}): una tasa por ejercicio")
        if (movimientos.any { it.tipo in DEVENGAN && it.anio == anio }) throw repetido
        val tasa = tasaDe(anuncio.clase!!, LocalDate.of(anio, 1, 1)) ?: throw FaltanAnuncios(listOf(Llaves.falta(anuncio.clase, anio)))
        val renovacion = Anuncios.devengo(id.toString(), RENOVACION, dia, anio, tasa, vigencia, Observacion.de(pedido.observacion))
        return try {
            registros.create(MOVIMIENTO_ANUNCIO, MovimientoAnuncio::class.java, Records.attributes(renovacion))
        } catch (_: DuplicateKeyException) {
            // not as its cause: srtm.Duplicados would take it by the cause, with its generic message
            throw repetido
        }
    }

    // POST /anuncios/{id}/cese and /retiro: once each (409), the retiro after the cese (422), in order (422)
    suspend fun baja(
        id: UUID,
        tipo: String,
        pedido: PedidoBaja
    ): MovimientoAnuncio {
        val nombre = if (tipo == CESE) "el cese" else "el retiro"
        exigirCrear(MOVIMIENTO_ANUNCIO, if (tipo == CESE) "Cesar un anuncio" else "Retirar un anuncio")
        val v = Violaciones()
        val fecha = fecha(v, "fecha", pedido.fecha)
        val motivo = pedido.motivo?.trim()?.ifEmpty { null }
        v.texto("motivo", motivo, Largos.MOTIVO, obligatorio = true)
        v.observacion(pedido.observacion)
        v.lanzar(if (tipo == CESE) "El pedido de cese" else "El pedido de retiro")
        val hoy = LocalDate.now()
        val dia = fecha ?: hoy
        val anuncio = registros.get(ANUNCIO, Anuncio::class.java, id)
        val movimientos = movimientosDe(id)
        val numero = anuncio.numero
        val repetido = ConflictException(if (tipo == CESE) "El anuncio $numero ya está cesado" else "El anuncio $numero ya está retirado")
        if (movimientos.any { it.tipo == tipo }) throw repetido
        if (tipo == RETIRO && movimientos.none { it.tipo == CESE }) {
            throw ActoNoAdmitido(
                "El anuncio $numero no está cesado: primero se cesa la autorización y después se constata el retiro del elemento"
            )
        }
        Anuncios.exigirOrden("$nombre de $numero", dia, hoy, previos(anuncio, movimientos))
        val baja = Anuncios.baja(id.toString(), tipo, dia, motivo!!, Observacion.de(pedido.observacion))
        return try {
            registros.create(MOVIMIENTO_ANUNCIO, MovimientoAnuncio::class.java, Records.attributes(baja))
        } catch (_: DuplicateKeyException) {
            throw repetido
        }
    }

    // GET /anuncios/{id}: today's estado, vigencia, tasas accrued and acts left
    suspend fun ficha(id: UUID): FichaAnuncio {
        val anuncio = registros.get(ANUNCIO, Anuncio::class.java, id)
        val movimientos = movimientosDe(id)
        val hoy = LocalDate.now()
        return FichaAnuncio(
            anuncio,
            movimientos,
            Anuncios.estado(movimientos, hoy),
            Anuncios.vigencia(movimientos, hoy),
            hoy,
            Devengado(Anuncios.devengado(movimientos, hoy), hoy),
            Anuncios.acciones(movimientos, hoy)
        )
    }

    // GET /anuncios: the anuncios authorized by vigentes_a (one authorized later did not exist that day), each with its
    // estado on that day. the estado derives from the movimientos: filtered by it, the page is cut here
    suspend fun padron(
        filtros: FiltrosPadron,
        page: Int,
        size: Int
    ): PageResponse<AnuncioEnPadron> {
        val campos =
            listOfNotNull(filtros.contribuyente?.let { "contribuyente" to it.toString() }, filtros.clase?.let { "clase" to it }).toMap()
        val criterios =
            listOfNotNull(
                RecordCriterion { d, bind -> "${columna(d, "fecha_autorizacion")} <= ${bind(filtros.vigentesA)}" },
                filtros.q?.trim()?.ifEmpty { null }?.let { q ->
                    RecordCriterion { d, bind ->
                        val patron = bind("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                        BUSCA_EN.joinToString(" OR ") { "${columna(d, it)} ILIKE $patron" }
                    }
                }
            )
        if (filtros.estado == null) {
            val pagina = registros.page(ANUNCIO, Anuncio::class.java, consulta(page, size, campos, criterios))
            return PageResponse(enPadron(pagina.content, filtros.vigentesA), pagina.page, pagina.size, pagina.totalElements, pagina.totalPages)
        }
        val filas = enPadron(todos(ANUNCIO, Anuncio::class.java, campos, criterios), filtros.vigentesA).filter { it.estado == filtros.estado }
        val pedida = PageRequest.of(page, size)
        return PageResponse.of(filas.drop(pedida.offset.toInt()).take(pedida.size), pedida.page, pedida.size, filas.size.toLong())
    }

    // GET /anuncios/tasas: each clase's tasa of the year, and the clases without one (never a 0)
    suspend fun tasas(anio: Int): TasasAnuncios {
        val filas = tasasLeidas()
        val porClase = CLASES.associateWith { Anuncios.tasaDelEjercicio(filas, it, anio) }
        return TasasAnuncios(
            anio,
            porClase.mapNotNull { (clase, p) -> p?.let { TasaDeClase(clase, it.valorNumerico!!, it.id!!, it.vigenciaDesde!!) } },
            porClase.filterValues { it == null }.keys.map { Llaves.falta(it, anio) }
        )
    }

    // GET /contribuyentes/{id}/anuncios: the titular's
    suspend fun delContribuyente(id: UUID): AnunciosDe {
        registros.get(CONTRIBUYENTE, Contribuyente::class.java, id)
        return alDia(mapOf("contribuyente" to id.toString()))
    }

    // GET /predios/{id}/anuncios: the ones installed on it
    suspend fun delPredio(id: UUID): AnunciosDe {
        registros.get(PREDIO, Predio::class.java, id)
        return alDia(mapOf("predio" to id.toString()))
    }

    private suspend fun alDia(filtros: Map<String, String>): AnunciosDe {
        val hoy = LocalDate.now()
        val anuncios = todos(ANUNCIO, Anuncio::class.java, filtros, emptyList())
        val movimientos = movimientosDe(anuncios.mapNotNull { it.id })
        return AnunciosDe(
            hoy,
            anuncios.map { a ->
                val suyos = movimientos[a.id].orEmpty()
                AnuncioAlDia(a, Anuncios.estado(suyos, hoy), Anuncios.vigencia(suyos, hoy))
            }
        )
    }

    private suspend fun enPadron(
        anuncios: List<Anuncio>,
        vigentesA: LocalDate
    ): List<AnuncioEnPadron> {
        val movimientos = movimientosDe(anuncios.mapNotNull { it.id })
        val titulares = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, anuncios.mapNotNull { it.contribuyente })
        return anuncios.map { a ->
            val suyos = movimientos[a.id].orEmpty()
            AnuncioEnPadron(a, titulares[a.contribuyente]?.nombreCompleto, Anuncios.estado(suyos, vigentesA), Anuncios.vigencia(suyos, vigentesA), vigentesA)
        }
    }

    // the first registration of an Idempotency-Key, if any, with its AUTORIZACION
    private suspend fun yaRegistrado(clave: String): AnuncioRegistrado? {
        val anuncio = registros.all(ANUNCIO, Anuncio::class.java, filters = mapOf("clave_idempotencia" to clave)).firstOrNull() ?: return null
        val autorizacion =
            registros.all(MOVIMIENTO_ANUNCIO, MovimientoAnuncio::class.java, filters = mapOf("anuncio" to anuncio.id!!, "tipo" to AUTORIZACION)).single()
        return AnuncioRegistrado(anuncio, autorizacion, true)
    }

    // the year's last correlativo, under the serie's lock
    private suspend fun ultimoCorrelativo(anio: Int): Int =
        registros
            .page(
                ANUNCIO,
                Anuncio::class.java,
                RecordQuery(
                    page = PageRequest.of(0, 1),
                    sort = "correlativo",
                    descending = true,
                    filters =
                        mapOf("anio" to "$anio")
                )
            ).content
            .firstOrNull()
            ?.correlativo ?: 0

    // an anuncio's movimientos by date; the same day's, in the order they were written
    private suspend fun movimientosDe(anuncio: UUID): List<MovimientoAnuncio> =
        registros.all(MOVIMIENTO_ANUNCIO, MovimientoAnuncio::class.java, filters = mapOf("anuncio" to anuncio.toString())).sortedBy { it.fecha }

    private suspend fun movimientosDe(anuncios: List<String>): Map<String?, List<MovimientoAnuncio>> =
        anuncios
            .distinct()
            .chunked(PageRequest.MAX_SIZE)
            .flatMap { ids ->
                val uuids = ids.map(UUID::fromString).toTypedArray()
                todos(
                    MOVIMIENTO_ANUNCIO,
                    MovimientoAnuncio::class.java,
                    emptyMap(),
                    listOf(
                        RecordCriterion {
                            d,
                            bind
                            ->
                            "${columna(d, "anuncio")} = ANY(${bind(uuids)})"
                        }
                    ),
                    orden = null
                )
            }.sortedBy { it.fecha }
            .groupBy { it.anuncio }

    // the acts an act must not be dated before: the authorization, and the anuncio's last movimiento
    private fun previos(
        anuncio: Anuncio,
        movimientos: List<MovimientoAnuncio>
    ): List<ActoPrevio> =
        listOfNotNull(
            anuncio.fechaAutorizacion?.let { ActoPrevio("la autorización ${anuncio.numero}", it) },
            movimientos.filter { it.fecha != null }.maxByOrNull { it.fecha!! }?.let {
                ActoPrevio("el último movimiento de ${anuncio.numero} (${it.tipo})", it.fecha!!)
            }
        )

    private suspend fun tasasLeidas(): List<ParametroTributario> =
        registros.all(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, filters = mapOf("tipo" to Llaves.TASA_ANUNCIO))

    private suspend fun tasaDe(
        clase: String,
        dia: LocalDate
    ) = Anuncios.tasa(tasasLeidas(), clase, dia)

    // every record that matches, page by page
    private suspend fun <T : Any> todos(
        objeto: String,
        type: Class<T>,
        filtros: Map<String, String>,
        criterios: List<RecordCriterion>,
        orden: String? = "numero"
    ): List<T> {
        val filas = mutableListOf<T>()
        var page = 0
        do {
            val pagina = registros.page(objeto, type, consulta(page, PageRequest.MAX_SIZE, filtros, criterios, orden))
            filas += pagina.content
            page++
        } while (page < pagina.totalPages)
        return filas
    }

    // anuncios by number; movimientos in the order they were written
    private fun consulta(
        page: Int,
        size: Int,
        filtros: Map<String, String>,
        criterios: List<RecordCriterion>,
        orden: String? = "numero"
    ) = RecordQuery(page = PageRequest.of(page, size), sort = orden, filters = filtros, criteria = criterios)

    // the anuncio a POST declares, as it would be written (its correlativo and numero still to come): every field
    // that is wrong in one 400. today when fecha_autorizacion is not given; lados and cantidad 1
    private fun leer(
        p: PedidoAnuncio,
        hoy: LocalDate
    ): Anuncio {
        val v = Violaciones()
        val fecha = fecha(v, "fecha_autorizacion", p.fechaAutorizacion) ?: hoy
        val vigencia = fecha(v, "vigencia_hasta", p.vigenciaHasta)
        val fechaExpediente = fecha(v, "fecha_expediente", p.fechaExpediente)
        val contribuyente = p.contribuyente?.trim()?.ifEmpty { null }
        if (contribuyente != null && runCatching { UUID.fromString(contribuyente) }.isFailure) v.mal("contribuyente", "no es un id")
        val predio = p.predio?.trim()?.ifEmpty { null }
        if (predio != null && runCatching { UUID.fromString(predio) }.isFailure) v.mal("predio", "no es un id")
        if (p.clase != null && p.clase !in CLASES) v.mal("clase", "es una de ${CLASES.joinToString(", ")}")
        if (p.tipo != null && p.tipo !in TIPOS_DE_ANUNCIO) v.mal("tipo", "es uno de ${TIPOS_DE_ANUNCIO.joinToString(", ")}")
        val borrador =
            Anuncio(
                anio = fecha.year,
                correlativo = 1,
                numero = numeroDeAnuncio(fecha.year, 1),
                clase = p.clase,
                tipo = p.tipo,
                emplazamiento = limpio(p.emplazamiento),
                forma = limpio(p.forma),
                denominacion = limpio(p.denominacion),
                direccion = limpio(p.direccion),
                area = p.area,
                lados = p.lados ?: 1,
                cantidad = p.cantidad ?: 1,
                fechaAutorizacion = fecha,
                vigenciaHasta = vigencia,
                expediente = limpio(p.expediente),
                fechaExpediente = fechaExpediente,
                licenciaTexto = limpio(p.licenciaTexto),
                observacion = p.observacion?.trim(),
                contribuyente = contribuyente,
                predio = predio
            )
        // a fecha_autorizacion that cannot be read says so, and nothing is compared with it
        val sinFecha = v.todas.any { it.field == "fecha_autorizacion" }
        val todas = (v.todas + invariantes(borrador).filterNot { sinFecha && it.field == "vigencia_hasta" }).distinctBy { it.field }
        if (todas.isNotEmpty()) throw ValidationException("El anuncio no es válido", todas)
        return borrador
    }

    private fun limpio(texto: String?) = texto?.trim()?.ifEmpty { null }

    // an iso date of the request, or a violation that names its field
    private fun fecha(
        v: Violaciones,
        campo: String,
        texto: String?
    ): LocalDate? {
        val limpio = limpio(texto) ?: return null
        return try {
            LocalDate.parse(limpio)
        } catch (_: DateTimeParseException) {
            v.mal(campo, "no es una fecha AAAA-MM-DD: '$limpio'")
            null
        }
    }

    // before anything is read: who may not create the act's object gets a 403 that names it
    private suspend fun exigirCrear(
        objeto: String,
        acto: String
    ) {
        val usuario = currentUser.require()
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(objeto).obj.id)
        } catch (_: ForbiddenException) {
            throw ForbiddenException("$acto exige permiso de creación sobre $objeto")
        }
    }

    private companion object {
        // q looks for the number, the denominación and the address
        val BUSCA_EN = listOf("numero", "denominacion", "direccion")

        fun columna(
            d: ObjectDefinition,
            campo: String
        ) = "\"" + d.fields.first { it.name == campo }.columnName + "\""
    }
}
