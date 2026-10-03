package srtm.sanciones

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonUnwrapped
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.Observacion
import srtm.impuesto.ParametrosTributarios
import srtm.legible
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Records
import srtm.rentas.Registros
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

// POST /infracciones/actas: codigo is the CUIS code's text; the version is the one in force on fecha_infraccion
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoActa(
    val numero: String? = null,
    val fechaInfraccion: LocalDate? = null,
    val horaInfraccion: String? = null,
    val lugar: String? = null,
    val codigo: String? = null,
    val reincidencia: String? = null,
    val obligado: String? = null,
    val contribuyente: String? = null,
    val predio: String? = null,
    val notificacionPrevia: String? = null,
    val expediente: String? = null,
    val inspector: String? = null,
    val descripcionHecho: String? = null,
    val observacion: String? = null
)

// POST /infracciones/actas/{id}/anulacion: fecha today when not given
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoAnulacionActa(
    val motivo: String? = null,
    val fecha: LocalDate? = null,
    val observacion: String? = null
)

// the six amounts as frozen in the acta, with the day they were computed
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DesgloseDelActa(
    @get:JsonUnwrapped val desglose: Desglose,
    val fechaCalculo: LocalDate
)

// its 201: the acta, flat, its stable reference (PAPELETA-<id>) and its multa
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ActaRegistrada(
    @get:JsonUnwrapped val acta: Papeleta,
    val referencia: String,
    val desglose: DesgloseDelActa
)

// a row of the grid of actas (and of a contribuyente's or predio's): the acta with its administrado (the obligado), the
// code and description of the CUIS version it used, its frozen importe, and its fase and estado de la deuda at
// fase_al_dia: two vocabularies with two names (#397)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FilaDeActa(
    val id: String,
    val numero: String,
    val fechaInfraccion: LocalDate,
    val administrado: String?,
    val documento: String?,
    val codigo: String?,
    val descripcionInfraccion: String?,
    val porcentajeInfraccion: BigDecimal,
    @param:JsonProperty("importe_a_pagar") @get:JsonProperty("importe_a_pagar") val importeAPagar: BigDecimal,
    val fechaCalculo: LocalDate,
    val medidaComplementaria: String?,
    val fase: String?,
    val faseAlDia: LocalDate,
    val estadoDeLaDeuda: String
)

// GET /contribuyentes/{id}/infracciones and /predios/{id}/infracciones
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class InfraccionesDe(
    val alDia: LocalDate,
    val actas: List<FilaDeActa>
)

// one act of an expediente: its place in the legal order, what it is, its day, its document and what it says now
data class ActoDelExpediente(
    val orden: Int,
    val acto: String,
    val fecha: LocalDate,
    val documento: String?,
    val id: String,
    val detalle: String?
)

// whether the rules allow an act now, and why not (the text the ficha shows)
data class AccionDelActa(
    val permitida: Boolean,
    val motivo: String?
)

data class AccionesDelActa(
    val descargo: AccionDelActa,
    val resolucion: AccionDelActa,
    val anulacion: AccionDelActa
)

// what the rules allow on a resolución now: its notificación
data class AccionesDeLaResolucion(
    val notificacion: AccionDelActa
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ResolucionConNotificaciones(
    @get:JsonUnwrapped val resolucion: ResolucionGerencia,
    val notificaciones: List<NotificacionResolucion>,
    val acciones: AccionesDeLaResolucion
)

// the parties of an expediente as the ficha names them: the obligado with the domicilio fiscal a resolución is
// notified at, the contribuyente and the predio the acta names (null when it names none). a field the reader may not
// see, or that is blank, is null
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ObligadoDelActa(
    val id: String,
    val nombre: String?,
    val documento: String?,
    val domicilioFiscal: String?
)

data class ContribuyenteDelActa(
    val id: String,
    val nombre: String?,
    val documento: String?
)

data class PredioDelActa(
    val id: String,
    val codigo: String?,
    val direccion: String?
)

data class PartesDelActa(
    val obligado: ObligadoDelActa,
    val contribuyente: ContribuyenteDelActa?,
    val predio: PredioDelActa?
)

// GET /infracciones/actas/{id}: the acta with every act recorded about it, in legal order
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ExpedienteDelActa(
    val acta: Papeleta,
    val referencia: String,
    // the version in force on the infracción's day, the one the multa was computed with (closed since, maybe)
    val codigoInfraccion: CodigoInfraccion,
    val notificacionPrevia: NotificacionAdministrativa?,
    val partes: PartesDelActa,
    val actos: List<ActoDelExpediente>,
    val descargos: List<DescargoPapeleta>,
    val resoluciones: List<ResolucionConNotificaciones>,
    val anulacion: AnulacionPapeleta?,
    val fase: String?,
    val faseAlDia: LocalDate,
    val estadoDeLaDeuda: String,
    val acciones: AccionesDelActa
)

// the filters of the grid of actas. administrado: a part of the obligado's documento or nombre; codigo: the CUIS code
data class FiltrosDeActas(
    val numero: String? = null,
    val administrado: String? = null,
    val codigo: String? = null,
    val fase: String? = null,
    val desde: LocalDate? = null,
    val hasta: LocalDate? = null
)

// the actas (rentas' RegistrarPapeleta.registrarAdministrativa, AnularPapeleta and the procedimiento sancionador's
// queries). an acta's multa is computed once, with the CUIS version and the UIT in force on the infracción's day, and
// frozen in its row with the relations to what was read: a later UIT or CUIS version never changes it. nothing here is
// edited: an acta is corrected by adding its anulación
@Service
class ActasService(
    private val registros: Registros,
    private val parametros: ParametrosTributarios,
    private val permisos: Permisos,
    private val candados: Candados,
    private val notificaciones: NotificacionesService,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)

    // POST /infracciones/actas. 400 by field; 422 when the code does not rule that day, when the UIT or the grado's %
    // is missing (faltan, every one at once), when the previa is subsanada or the dates are out of order; 404 for an
    // obligado, contribuyente, predio or previa that does not exist; 409 for a número already written. under the
    // previa's lock (the subsanación takes the same one) and in one transaction
    suspend fun registrar(
        pedido: PedidoActa,
        hoy: LocalDate
    ): ActaRegistrada {
        permisos.exigirCrear(PAPELETA, "registrar un acta")
        val datos = datosDelActa(pedido)
        val numero = datos.numero!!
        val fecha = datos.fechaInfraccion!!
        val acta = "el acta $numero"
        OrdenDeLosActos.exigir(acta, fecha, hoy, campo = FECHA_INFRACCION)
        val version = versionDelDia(Cuis.normalizar(pedido.codigo!!), fecha)
        val uit = Multas.uit(parametros.todos(), fecha)
        // without a UIT the grado's % is still asked: the 422 names everything missing at once
        val calculo = Multas.calcular(version, uit.valor?.valorNumerico ?: BigDecimal.ONE, datos.reincidencia!!)
        val faltan = uit.faltan + calculo.faltan
        if (faltan.isNotEmpty()) throw FaltanSanciones("registrar $acta", faltan)
        val desglose = calculo.valor!!
        for ((objeto, id) in listOf(CONTRIBUYENTE to datos.obligado, CONTRIBUYENTE to datos.contribuyente)) {
            id?.let { registros.get(objeto, Contribuyente::class.java, UUID.fromString(it)) }
        }
        datos.predio?.let { registros.get(PREDIO, Predio::class.java, UUID.fromString(it)) }
        val papeleta =
            datos.copy(
                medidaComplementaria = version.medidaComplementaria,
                baseImponible = desglose.baseImponible,
                porcentajeInfraccion = desglose.porcentajeInfraccion,
                importeInfraccion = desglose.importeInfraccion,
                porcentajeACobrar = desglose.porcentajeACobrar,
                importeAPagar = desglose.importeAPagar,
                importeConBeneficio = desglose.importeConBeneficio,
                fechaCalculo = hoy,
                codigoInfraccion = version.id,
                uit = uit.valor!!.id,
                observacion = Observacion.de(datos.observacion)
            )
        val malas = invariantes(papeleta)
        if (malas.isNotEmpty()) throw ValidationException("El acta no es válida", malas)
        val repetida = ConflictException("El acta $numero ya está registrada: el número no se repite")
        val escrita =
            try {
                transaccion.executeAndAwait {
                    papeleta.notificacionPrevia?.let { previa ->
                        candados.bloquear(Candado.NOTIFICACION, previa)
                        // read again under the lock: a subsanación that got there first stands
                        val n = registros.get(NOTIFICACION_ADMINISTRATIVA, NotificacionAdministrativa::class.java, UUID.fromString(previa))
                        Notificaciones.exigirQueOrigineActa(n, n.id in notificaciones.hechos(listOf(n)).subsanaciones)
                        OrdenDeLosActos.exigir(acta, fecha, hoy, ActoPrevio("la notificación previa ${n.numero}", n.fecha!!), campo = FECHA_INFRACCION)
                    }
                    if (registros.all(PAPELETA, Papeleta::class.java, filters = mapOf("clave" to papeleta.clave!!)).isNotEmpty()) throw repetida
                    registros.create(PAPELETA, Papeleta::class.java, Records.attributes(papeleta))
                }
            } catch (_: DuplicateKeyException) {
                throw repetida
            }
        return ActaRegistrada(escrita, referenciaDePapeleta(escrita.id!!), desgloseDe(escrita))
    }

    // GET /infracciones/actas: the newest infracción first. numero, codigo, administrado and the dates filter in the
    // database. the fase is derived from the acts at `corte`, never stored, so a fase filter reads every acta the other
    // filters leave, derives each one's fase (the same Procedimiento.fase the column shows) and cuts the page after
    suspend fun pagina(
        filtros: FiltrosDeActas,
        corte: LocalDate,
        page: Int,
        size: Int
    ): PageResponse<FilaDeActa> {
        val vacia = PageResponse.of(emptyList<FilaDeActa>(), page, size, 0)
        val criterios = mutableListOf<RecordCriterion>()
        filtros.codigo?.let { texto ->
            val versiones =
                registros.all(CODIGO_INFRACCION, CodigoInfraccion::class.java, filters = mapOf("familia" to ADMINISTRATIVA, "codigo" to Cuis.normalizar(texto)))
            if (versiones.isEmpty()) return vacia
            criterios += Filtros.entre("codigo_infraccion", versiones.mapNotNull { it.id })
        }
        filtros.administrado?.let { texto ->
            // a contribuyente whose documento or nombre contains it
            val criterio = Filtros.contiene(texto, "numero_documento", "nombre_completo")
            val ids = registros.donde(CONTRIBUYENTE, Contribuyente::class.java, listOf(criterio)).mapNotNull { it.id }
            if (ids.isEmpty()) return vacia
            criterios += Filtros.entre("obligado", ids)
        }
        Filtros.entre(FECHA_INFRACCION, filtros.desde, filtros.hasta)?.let { criterios += it }
        val filtrosDeCampo = listOfNotNull(filtros.numero?.let { "numero" to Cuis.normalizar(it) }).toMap()
        if (filtros.fase == null) {
            val pagina =
                registros.page(
                    PAPELETA,
                    Papeleta::class.java,
                    RecordQuery(
                        page = PageRequest.of(page, size),
                        sort = FECHA_INFRACCION,
                        descending = true,
                        filters = filtrosDeCampo,
                        criteria = criterios
                    )
                )
            return PageResponse(filas(pagina.content, corte), pagina.page, pagina.size, pagina.totalElements, pagina.totalPages)
        }
        val conEsaFase = filas(registros.donde(PAPELETA, Papeleta::class.java, criterios, filtrosDeCampo), corte).filter { it.fase == filtros.fase }
        return PageResponse.of(ordenadas(conEsaFase).drop(page * size).take(size), page, size, conEsaFase.size.toLong())
    }

    // GET /contribuyentes/{id}/infracciones: every acta where it is the obligado or the contribuyente (404 when it does
    // not exist)
    suspend fun delContribuyente(
        id: UUID,
        hoy: LocalDate
    ): InfraccionesDe {
        registros.get(CONTRIBUYENTE, Contribuyente::class.java, id)
        val actas =
            listOf("obligado", "contribuyente")
                .flatMap { campo -> registros.donde(PAPELETA, Papeleta::class.java, emptyList(), mapOf(campo to id.toString())) }
                .distinctBy { it.id }
        return InfraccionesDe(hoy, ordenadas(filas(actas, hoy)))
    }

    // GET /predios/{id}/infracciones: every acta that names the predio (404 when it does not exist)
    suspend fun delPredio(
        id: UUID,
        hoy: LocalDate
    ): InfraccionesDe {
        registros.get(PREDIO, Predio::class.java, id)
        val actas = registros.donde(PAPELETA, Papeleta::class.java, emptyList(), mapOf("predio" to id.toString()))
        return InfraccionesDe(hoy, ordenadas(filas(actas, hoy)))
    }

    // GET /infracciones/actas/{id}: the acta, the CUIS version it used, its acts in legal order, its fase and estado at
    // `hoy`, and what the rules allow now
    suspend fun expediente(
        id: UUID,
        hoy: LocalDate
    ): ExpedienteDelActa {
        val acta = registros.get(PAPELETA, Papeleta::class.java, id)
        val codigo = registros.get(CODIGO_INFRACCION, CodigoInfraccion::class.java, UUID.fromString(acta.codigoInfraccion))
        val h = hechos(listOf(acta)).getValue(acta.id!!)
        val descargos =
            en(DESCARGO_PAPELETA, DescargoPapeleta::class.java, "papeleta", listOf(acta.id)).sortedWith(compareBy({ it.fecha }, { it.numeroExpediente }))
        val resoluciones = h.resoluciones.sortedWith(compareBy({ it.fecha }, { it.tipo }, { it.correlativo }))
        val notificacionesDe =
            en(NOTIFICACION_RESOLUCION, NotificacionResolucion::class.java, "resolucion", resoluciones.mapNotNull { it.id })
                .groupBy { it.resolucion!! }
                .mapValues { (_, ns) -> ns.sortedBy { it.intento } }
        // whether each resolución is notified now, with the same text its 422 gives
        val conNotificaciones =
            resoluciones.map { r ->
                val notificacion = AccionesDeLaResolucion(accion(Procedimiento.impedimentoDeNotificar(h, r)))
                ResolucionConNotificaciones(r, notificacionesDe[r.id].orEmpty(), notificacion)
            }
        return ExpedienteDelActa(
            acta = acta,
            referencia = referenciaDePapeleta(acta.id),
            codigoInfraccion = codigo,
            notificacionPrevia = h.previa,
            partes = partes(acta),
            actos = Expedientes.actos(acta, codigo, h, descargos, conNotificaciones, hoy),
            descargos = descargos,
            resoluciones = conNotificaciones,
            anulacion = h.anulacion,
            fase = Procedimiento.fase(h, hoy),
            faseAlDia = hoy,
            estadoDeLaDeuda = Procedimiento.estadoDeLaDeuda(h),
            acciones =
                AccionesDelActa(
                    descargo = accion(Procedimiento.impedimento("impugnar", h)),
                    resolucion = accion(Resoluciones.impedimento(h, descargos)),
                    anulacion = accion(Procedimiento.impedimentoDeAnular(h))
                )
        )
    }

    // POST /infracciones/actas/{id}/anulacion: once (409), not of an acta a resolución left without effect (422), not
    // before the infracción nor after today (422). under the acta's lock, in one transaction
    suspend fun anular(
        id: UUID,
        pedido: PedidoAnulacionActa,
        hoy: LocalDate
    ): AnulacionPapeleta {
        permisos.exigirCrear(ANULACION_PAPELETA, "anular un acta")
        val acta = registros.get(PAPELETA, Papeleta::class.java, id)
        val anulacion =
            AnulacionPapeleta(
                fecha = pedido.fecha ?: hoy,
                motivo = pedido.motivo?.trim()?.ifEmpty { null },
                observacion = pedido.observacion?.trim(),
                clave = acta.id,
                papeleta = acta.id
            )
        val malas = invariantes(anulacion)
        if (malas.isNotEmpty()) throw ValidationException("La anulación no es válida", malas)
        OrdenDeLosActos.exigir(
            "la anulación del acta ${acta.numero}",
            anulacion.fecha!!,
            hoy,
            ActoPrevio("la infracción del acta ${acta.numero}", acta.fechaInfraccion!!)
        )
        return try {
            transaccion.executeAndAwait {
                candados.bloquear(Candado.ACTA, acta.id!!)
                Procedimiento.exigirAnulable(hechos(listOf(acta)).getValue(acta.id))
                registros.create(
                    ANULACION_PAPELETA,
                    AnulacionPapeleta::class.java,
                    Records.attributes(anulacion.copy(observacion = Observacion.de(anulacion.observacion)))
                )
            }
        } catch (_: DuplicateKeyException) {
            throw ConflictException("El acta ${acta.numero} ya está anulada")
        }
    }

    // what was recorded about each acta (its previa and that one's subsanación, its anulación, its resoluciones), one
    // query per kind: what Procedimiento derives the fase and the estado from
    suspend fun hechos(actas: List<Papeleta>): Map<String, HechosDelActa> {
        val ids = actas.mapNotNull { it.id }
        if (ids.isEmpty()) return emptyMap()
        val previas = registros.byIds(NOTIFICACION_ADMINISTRATIVA, NotificacionAdministrativa::class.java, actas.mapNotNull { it.notificacionPrevia })
        val subsanaciones =
            en(SUBSANACION_NOTIFICACION, SubsanacionNotificacion::class.java, "notificacion", previas.keys).associateBy { it.notificacion!! }
        val anulaciones = en(ANULACION_PAPELETA, AnulacionPapeleta::class.java, "papeleta", ids).associateBy { it.papeleta!! }
        val resoluciones = en(RESOLUCION_GERENCIA, ResolucionGerencia::class.java, "papeleta", ids).groupBy { it.papeleta!! }
        return actas.associate { a ->
            val previa = a.notificacionPrevia?.let { previas[it] }
            a.id!! to HechosDelActa(previa, previa?.let { subsanaciones[it.id] }, anulaciones[a.id], resoluciones[a.id].orEmpty())
        }
    }

    // the rows of these actas at `corte`, in their order: their hechos, obligados and CUIS versions read once
    suspend fun filas(
        actas: List<Papeleta>,
        corte: LocalDate
    ): List<FilaDeActa> {
        val hechos = hechos(actas)
        val obligados = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, actas.mapNotNull { it.obligado })
        val codigos = registros.byIds(CODIGO_INFRACCION, CodigoInfraccion::class.java, actas.mapNotNull { it.codigoInfraccion })
        return actas.map { fila(it, hechos.getValue(it.id!!), obligados[it.obligado], codigos[it.codigoInfraccion], corte) }
    }

    // the obligado, the contribuyente and the predio the acta names, read once each
    private suspend fun partes(acta: Papeleta): PartesDelActa {
        val personas = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, listOfNotNull(acta.obligado, acta.contribuyente))
        val predio = acta.predio?.let { registros.byIds(PREDIO, Predio::class.java, listOf(it))[it] }
        return Partes.de(acta, personas, predio)
    }

    // the version of `codigo` in force on `fecha`, or a 422 that names both
    private suspend fun versionDelDia(
        codigo: String,
        fecha: LocalDate
    ): CodigoInfraccion {
        val versiones = registros.all(CODIGO_INFRACCION, CodigoInfraccion::class.java, filters = mapOf("familia" to ADMINISTRATIVA, "codigo" to codigo))
        Cuis.vigenteA(versiones, fecha)?.let { return it }
        val porque =
            if (versiones.isEmpty()) {
                "no está en el CUIS"
            } else {
                "sus versiones rigen " +
                    versiones.sortedBy { it.vigenciaDesde }.joinToString(", ") { v ->
                        "desde el ${v.vigenciaDesde!!.legible()}" +
                            (v.vigenciaHasta?.let { " hasta el ${it.legible()}" } ?: "")
                    }
            }
        throw NoProcede("El código $codigo no rige el ${fecha.legible()}: $porque", listOf(FieldViolation("codigo", "no rige el ${fecha.legible()}")))
    }

    // the acta as asked, every field it is given checked before anything is read: a 400 that names each one
    private fun datosDelActa(p: PedidoActa): Papeleta {
        val numero = p.numero?.let(Cuis::normalizar)?.ifEmpty { null }
        val datos =
            Papeleta(
                familia = ADMINISTRATIVA,
                numero = numero,
                clave = numero?.let { claveDePapeleta(ADMINISTRATIVA, it) },
                fechaInfraccion = p.fechaInfraccion,
                horaInfraccion = p.horaInfraccion.limpio(),
                lugar = p.lugar.limpio(),
                expediente = p.expediente.limpio(),
                inspector = p.inspector.limpio(),
                descripcionHecho = p.descripcionHecho.limpio(),
                reincidencia = p.reincidencia.limpio()?.uppercase(Locale.ROOT),
                observacion = p.observacion?.trim(),
                obligado = relacion("obligado", p.obligado),
                contribuyente = relacion("contribuyente", p.contribuyente),
                predio = relacion("predio", p.predio),
                notificacionPrevia = relacion("notificacion_previa", p.notificacionPrevia)
            )
        val malas = invariantes(datos).filter { it.field !in CALCULADOS }.toMutableList()
        if (p.codigo.limpio() == null) malas += FieldViolation("codigo", "es obligatorio: el código del CUIS")
        if (datos.reincidencia != null && datos.reincidencia !in Multas.GRADOS) {
            malas += FieldViolation("reincidencia", "es ${Multas.GRADOS.joinToString(", ")}")
        }
        if (malas.isNotEmpty()) throw ValidationException("El acta no es válida", malas)
        return datos
    }

    private suspend fun <T : Any> en(
        objeto: String,
        type: Class<T>,
        campo: String,
        ids: Collection<String>
    ): List<T> = ids.distinct().chunked(PageRequest.MAX_SIZE).flatMap { registros.donde(objeto, type, listOf(Filtros.entre(campo, it))) }

    private fun String?.limpio() = this?.trim()?.ifEmpty { null }

    companion object {
        const val FECHA_INFRACCION = "fecha_infraccion"

        // what the service computes, not the request: left out of the request's 400
        private val CALCULADOS =
            setOf(
                "base_imponible",
                "porcentaje_infraccion",
                "importe_infraccion",
                "porcentaje_a_cobrar",
                "importe_a_pagar",
                "fecha_calculo",
                "codigo_infraccion",
                "uit"
            )

        // the desglose as frozen in the row
        fun desgloseDe(p: Papeleta) =
            DesgloseDelActa(
                Desglose(
                    baseImponible = p.baseImponible!!,
                    porcentajeInfraccion = p.porcentajeInfraccion!!,
                    importeInfraccion = p.importeInfraccion!!,
                    porcentajeACobrar = p.porcentajeACobrar!!,
                    importeAPagar = p.importeAPagar!!,
                    importeConBeneficio = p.importeConBeneficio
                ),
                p.fechaCalculo!!
            )

        // one row of the grid: the obligado and the CUIS version as read (null when the reader may not see them)
        fun fila(
            p: Papeleta,
            h: HechosDelActa,
            obligado: Contribuyente?,
            codigo: CodigoInfraccion?,
            corte: LocalDate
        ) = FilaDeActa(
            id = p.id!!,
            numero = p.numero!!,
            fechaInfraccion = p.fechaInfraccion!!,
            administrado = obligado?.nombreCompleto,
            documento = obligado?.numeroDocumento,
            codigo = codigo?.codigo,
            descripcionInfraccion = codigo?.descripcion,
            porcentajeInfraccion = p.porcentajeInfraccion!!,
            importeAPagar = p.importeAPagar!!,
            fechaCalculo = p.fechaCalculo!!,
            medidaComplementaria = p.medidaComplementaria,
            fase = Procedimiento.fase(h, corte),
            faseAlDia = corte,
            estadoDeLaDeuda = Procedimiento.estadoDeLaDeuda(h)
        )

        // the newest infracción first, then by número
        fun ordenadas(filas: List<FilaDeActa>) = filas.sortedWith(compareByDescending<FilaDeActa> { it.fechaInfraccion }.thenBy { it.numero })

        private fun accion(impedimento: String?) = AccionDelActa(impedimento == null, impedimento)
    }
}
