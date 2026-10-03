package srtm.sanciones

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.emision.CabeceraDocumento
import srtm.emision.Documento
import srtm.emision.PdfRenderer
import srtm.impuesto.ParametrosTributarios
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.Records
import srtm.rentas.Registros
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.PageRequest
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

// POST /infracciones/actas/{id}/descargos
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoDescargo(
    val numeroExpediente: String? = null,
    val tipoRecurso: String? = null,
    val fecha: LocalDate? = null,
    val sustento: String? = null,
    val observacion: String? = null
)

// POST /infracciones/actas/{id}/resoluciones: descargo, sentido, efecto and sancion_accesoria null when they do not
// apply; fecha today when not given
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoResolucion(
    val tipo: String? = null,
    val descargo: String? = null,
    val sentido: String? = null,
    val efecto: String? = null,
    val fecha: LocalDate? = null,
    val sustento: String? = null,
    val sancionAccesoria: String? = null,
    val observacion: String? = null
)

// POST /infracciones/resoluciones/{id}/notificacion: fecha_diligencia today when not given; without direccion, the
// obligado's domicilio fiscal
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoNotificacionResolucion(
    val fechaDiligencia: LocalDate? = null,
    val modalidad: String? = null,
    val resultado: String? = null,
    val notificador: String? = null,
    val direccion: String? = null,
    val receptor: String? = null,
    val documentoReceptor: String? = null,
    val vinculo: String? = null,
    val acuse: String? = null,
    val observacion: String? = null
)

// the acts that answer an acta (rentas' RegistrarDescargo, ResolverConResolucionDeGerencia and
// NotificarResolucionDeGerencia): the descargo with its plazo copied, the RIS or RGR with its número and the plazo it
// grants copied, and each intento of a resolución's notificación with its exigibilidad. each one is only added, and
// copies what it read (the PLAZO and FERIADOS rows) so recounting it never moves it. the descargo and the resolución
// take the acta's lock (the anulación's), so none of them crosses an anulación
@Service
class ResolucionesService(
    private val registros: Registros,
    private val parametros: ParametrosTributarios,
    private val permisos: Permisos,
    private val candados: Candados,
    private val actas: ActasService,
    private val cabeceras: CabeceraDocumento,
    private val renderer: PdfRenderer,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)

    // POST /infracciones/actas/{id}/descargos. 400 by field; 404 for an acta that does not exist; 422 when nothing is
    // left to impugnar (ANULADA, DEJADA_SIN_EFECTO), when the fecha is before the infracción or after today, or with
    // `faltan` (PLAZO DESCARGO_PAPELETA, FERIADOS of the infracción's years); 409 for an expediente already written. a
    // late one is recorded (en_plazo false). under the acta's lock, in one transaction
    suspend fun registrarDescargo(
        actaId: UUID,
        pedido: PedidoDescargo,
        hoy: LocalDate
    ): DescargoPapeleta {
        permisos.exigirCrear(DESCARGO_PAPELETA, "registrar un descargo")
        val acta = registros.get(PAPELETA, Papeleta::class.java, actaId)
        val numero = pedido.numeroExpediente?.let(Cuis::normalizar)?.ifEmpty { null }
        val datos =
            DescargoPapeleta(
                numeroExpediente = numero,
                tipoRecurso = pedido.tipoRecurso.limpio()?.uppercase(Locale.ROOT),
                fecha = pedido.fecha,
                sustento = pedido.sustento.limpio(),
                observacion = pedido.observacion?.trim(),
                papeleta = acta.id
            )
        val malas = invariantes(datos).filter { it.field !in PLAZO_DEL_DESCARGO }.toMutableList()
        malas += opcion("tipo_recurso", datos.tipoRecurso, Opciones.TIPOS_RECURSO)
        if (malas.isNotEmpty()) throw ValidationException("El descargo no es válido", malas)
        val fecha = datos.fecha!!
        val descargo = "el descargo $numero"
        Procedimiento.exigirQueQuedeAlgoQue("impugnar", hechos(acta))
        OrdenDeLosActos.exigir(descargo, fecha, hoy, infraccion(acta))
        val plazo = Descargos.registrar(acta.fechaInfraccion!!, fecha, parametros.todos()).exigir("registrar $descargo")
        val nuevo =
            datos.copy(presentadoHasta = plazo.presentadoHasta, enPlazo = plazo.enPlazo, plazoTexto = plazo.plazoTexto, plazo = plazo.plazo)
        val invalido = invariantes(nuevo)
        if (invalido.isNotEmpty()) throw ValidationException("El descargo no es válido", invalido)
        val repetido = ConflictException("El expediente $numero ya está registrado: el número no se repite")
        return try {
            transaccion.executeAndAwait {
                candados.bloquear(Candado.ACTA, acta.id!!)
                // read again under the lock: an anulación or a resolución that got there first stands
                Procedimiento.exigirQueQuedeAlgoQue("impugnar", hechos(acta))
                if (registros.all(DESCARGO_PAPELETA, DescargoPapeleta::class.java, filters = mapOf("numero_expediente" to numero!!)).isNotEmpty()) {
                    throw repetido
                }
                registros.create(DESCARGO_PAPELETA, DescargoPapeleta::class.java, Records.attributes(nuevo))
            }
        } catch (_: DuplicateKeyException) {
            throw repetido
        }
    }

    // POST /infracciones/actas/{id}/resoluciones. 400 by field; 404 for an acta or a descargo that does not exist; 422
    // for SE_REDUCE, a RECURSO without its descargo, a fallo without a descargo (or the other way round), a descargo of
    // another acta, an acta with nothing left to resolve, a fecha before the infracción or the descargo's or after
    // today, or with `faltan` (PLAZO RG_RECURSO of the fecha's year); 409 for a second RIS or a second resolución of
    // a descargo. the paper is drawn BEFORE the transaction, from the data that will be stored (with the correlativo
    // expected then): if it cannot be drawn, nothing is written. the correlativo is the serie's (tipo and the fecha's
    // year) highest + 1, under its lock, in the same transaction as the insert; the acta's lock keeps the resolución
    // and an anulación from crossing
    suspend fun dictar(
        actaId: UUID,
        pedido: PedidoResolucion,
        hoy: LocalDate
    ): ResolucionGerencia {
        permisos.exigirCrear(RESOLUCION_GERENCIA, "dictar una resolución")
        val acta = registros.get(PAPELETA, Papeleta::class.java, actaId)
        val tipo = pedido.tipo.limpio()?.uppercase(Locale.ROOT)
        val fecha = pedido.fecha ?: hoy
        val descargoId = relacion("descargo", pedido.descargo)
        val datos =
            ResolucionGerencia(
                tipo = tipo,
                anio = fecha.year,
                fecha = fecha,
                sentido = pedido.sentido.limpio()?.uppercase(Locale.ROOT),
                efecto = pedido.efecto.limpio()?.uppercase(Locale.ROOT),
                sancionAccesoria = pedido.sancionAccesoria.limpio(),
                sustento = pedido.sustento.limpio(),
                claveRis = acta.id.takeIf { tipo == RESOLUCION_ADMINISTRATIVA },
                claveDescargo = descargoId,
                observacion = pedido.observacion?.trim(),
                papeleta = acta.id,
                descargo = descargoId
            )
        // the rules of the fallo are Resoluciones.validar's 422s; the número and the plazo are the service's
        val malas = invariantes(datos).filter { it.field !in DE_LA_RESOLUCION }.toMutableList()
        malas += opcion("sentido", datos.sentido, Opciones.SENTIDOS)
        malas += opcion("efecto", datos.efecto, Opciones.EFECTOS)
        if (malas.isNotEmpty()) throw ValidationException("La resolución no es válida", malas)
        val descargo = descargoId?.let { registros.get(DESCARGO_PAPELETA, DescargoPapeleta::class.java, UUID.fromString(it)) }
        Resoluciones.validar(acta.id!!, tipo!!, descargo, datos.sentido, datos.efecto, hechos(acta))
        val previos =
            listOfNotNull(infraccion(acta), descargo?.let { ActoPrevio("la presentación del descargo ${it.numeroExpediente}", it.fecha!!) })
        OrdenDeLosActos.exigir("la resolución del acta ${acta.numero}", fecha, hoy, previos)
        val plazo = Resoluciones.plazo(tipo, fecha, parametros.todos()).exigir("dictar la resolución del acta ${acta.numero}")
        val borrador = datos.copy(plazoTexto = plazo.texto, plazo = plazo.parametro)

        // the paper first, out of any transaction and lock: the bytes are not kept (GET …/pdf draws them again from
        // the row), what matters is that the row can be drawn
        val esperada = numerada(borrador, ultimoCorrelativo(tipo, fecha.year) + 1)
        val invalida = invariantes(esperada)
        if (invalida.isNotEmpty()) throw ValidationException("La resolución no es válida", invalida)
        dibujar(esperada, acta, descargo)

        return try {
            transaccion.executeAndAwait {
                candados.bloquear(Candado.ACTA, acta.id)
                candados.bloquear(Candado.RESOLUCION, "$tipo|${fecha.year}")
                // read again under the locks: a RIS, a resolución of the descargo or an anulación that got there first
                Resoluciones.validar(acta.id, tipo, descargo, datos.sentido, datos.efecto, hechos(acta))
                val resolucion = numerada(borrador, ultimoCorrelativo(tipo, fecha.year) + 1)
                registros.create(RESOLUCION_GERENCIA, ResolucionGerencia::class.java, Records.attributes(resolucion))
            }
        } catch (_: DuplicateKeyException) {
            // the uniques' net (clave_ris, clave_descargo, numero) behind the locks: the transaction was rolled back whole.
            // the 409 does not carry it as its cause: srtm.Duplicados would answer it by the cause, with its generic message
            throw ConflictException("La resolución chocó con otra que se confirmó a la vez y no se registró nada: vuelva a intentarlo")
        }
    }

    // GET /infracciones/resoluciones/{id}/pdf: the paper drawn again from the frozen rows (404 unknown, 403 without read)
    suspend fun pdf(id: UUID): Documento {
        val resolucion = registros.get(RESOLUCION_GERENCIA, ResolucionGerencia::class.java, id)
        val acta = registros.get(PAPELETA, Papeleta::class.java, UUID.fromString(resolucion.papeleta))
        val descargo = resolucion.descargo?.let { registros.get(DESCARGO_PAPELETA, DescargoPapeleta::class.java, UUID.fromString(it)) }
        return Documento("${resolucion.numero}.pdf", dibujar(resolucion, acta, descargo))
    }

    // POST /infracciones/resoluciones/{id}/notificacion. 400 by field; 404 for a resolución that does not exist; 422 when
    // nothing is left to notify (the acta ANULADA or DEJADA_SIN_EFECTO), when the diligencia is before the resolución or
    // after today, without a direccion (none given and the obligado has no domicilio fiscal), or with `faltan` (PLAZO
    // RG_RECURSO, FERIADOS) when it takes effect. a NO_UBICADO makes nothing exigible: another intento follows. under
    // the acta's lock (so it does not cross an anulación) and then the resolución's, where the intento is the count + 1
    suspend fun notificar(
        resolucionId: UUID,
        pedido: PedidoNotificacionResolucion,
        hoy: LocalDate
    ): NotificacionResolucion {
        permisos.exigirCrear(NOTIFICACION_RESOLUCION, "notificar una resolución")
        val resolucion = registros.get(RESOLUCION_GERENCIA, ResolucionGerencia::class.java, resolucionId)
        val numero = resolucion.numero
        val fecha = pedido.fechaDiligencia ?: hoy
        val datos =
            NotificacionResolucion(
                fechaDiligencia = fecha,
                modalidad = pedido.modalidad.limpio()?.uppercase(Locale.ROOT),
                resultado = pedido.resultado.limpio()?.uppercase(Locale.ROOT),
                notificador = pedido.notificador.limpio(),
                direccion = pedido.direccion.limpio(),
                receptor = pedido.receptor.limpio(),
                documentoReceptor = pedido.documentoReceptor.limpio(),
                vinculo = pedido.vinculo.limpio(),
                acuse = pedido.acuse.limpio(),
                observacion = pedido.observacion?.trim(),
                resolucion = resolucion.id
            )
        val derivados = if (datos.direccion == null) DE_LA_NOTIFICACION + "direccion" else DE_LA_NOTIFICACION
        val malas = invariantes(datos).filter { it.field !in derivados }.toMutableList()
        malas += opcion("modalidad", datos.modalidad, Opciones.MODALIDADES)
        malas += opcion("resultado", datos.resultado, Opciones.RESULTADOS)
        if (malas.isNotEmpty()) throw ValidationException("La notificación no es válida", malas)
        val acta = registros.get(PAPELETA, Papeleta::class.java, UUID.fromString(resolucion.papeleta))
        Procedimiento.exigirQueQuedeAlgoQue("notificar", hechos(acta))
        val acto = "la notificación de la resolución $numero"
        OrdenDeLosActos.exigir(acto, fecha, hoy, ActoPrevio("la resolución $numero", resolucion.fecha!!), campo = "fecha_diligencia")
        val direccion = datos.direccion ?: domicilioDelObligado(resolucion, acta)
        val exigibilidad =
            NotificacionesDeResolucion.exigibilidad(resolucion.tipo!!, datos.resultado!!, fecha, parametros.todos()).exigir("registrar $acto")
        return try {
            transaccion.executeAndAwait {
                candados.bloquear(Candado.ACTA, acta.id!!)
                // read again under the lock: an anulación or a resolución that left the multa without effect stands
                Procedimiento.exigirQueQuedeAlgoQue("notificar", hechos(acta))
                candados.bloquear(Candado.NOTIFICACION_RESOLUCION, resolucion.id!!)
                val intento =
                    registros.all(NOTIFICACION_RESOLUCION, NotificacionResolucion::class.java, filters = mapOf("resolucion" to resolucion.id)).size + 1
                val notificacion =
                    datos.copy(
                        intento = intento,
                        clave = claveDeNotificacionResolucion(resolucion.id, intento),
                        direccion = direccion,
                        exigibleDesde = exigibilidad.exigibleDesde,
                        plazoTexto = exigibilidad.plazo?.texto,
                        plazo = exigibilidad.plazo?.parametro
                    )
                val invalida = invariantes(notificacion)
                if (invalida.isNotEmpty()) throw ValidationException("La notificación no es válida", invalida)
                registros.create(NOTIFICACION_RESOLUCION, NotificacionResolucion::class.java, Records.attributes(notificacion))
            }
        } catch (_: DuplicateKeyException) {
            throw ConflictException("La notificación chocó con otra de la misma resolución y no se registró nada: vuelva a intentarlo")
        }
    }

    // the obligado's domicilio fiscal (rentas' direccionDe: srtm keeps no history of domicilios, so the one in force on
    // the diligencia is the active one), or a 422 that says there is no address to notify at
    private suspend fun domicilioDelObligado(
        resolucion: ResolucionGerencia,
        acta: Papeleta
    ): String {
        val obligado = registros.get(CONTRIBUYENTE, Contribuyente::class.java, UUID.fromString(acta.obligado))
        return domicilioFiscalDe(obligado)
            ?: throw NoProcede(
                "No se puede notificar la resolución ${resolucion.numero}: no se dio la dirección y el obligado " +
                    "${obligado.nombreCompleto ?: obligado.numeroDocumento ?: ""} no tiene domicilio fiscal",
                listOf(FieldViolation("direccion", "es obligatoria: el obligado no tiene domicilio fiscal"))
            )
    }

    // the paper of `resolucion` (stored, or about to be), off the request's thread
    private suspend fun dibujar(
        resolucion: ResolucionGerencia,
        acta: Papeleta,
        descargo: DescargoPapeleta?
    ): ByteArray {
        val codigo = registros.get(CODIGO_INFRACCION, CodigoInfraccion::class.java, UUID.fromString(acta.codigoInfraccion))
        val obligado = registros.get(CONTRIBUYENTE, Contribuyente::class.java, UUID.fromString(acta.obligado))
        val hoja = hojaResolucion(cabeceras.actual(), resolucion, acta, codigo, obligado, descargo)
        return withContext(Dispatchers.Default) { renderer.render("resolucion", mapOf("r" to hoja)) }
    }

    private suspend fun hechos(acta: Papeleta): HechosDelActa = actas.hechos(listOf(acta)).getValue(acta.id!!)

    // the serie's highest correlativo in the year (0 when none): RIS and RGR count apart
    private suspend fun ultimoCorrelativo(
        tipo: String,
        anio: Int
    ): Int =
        registros
            .page(
                RESOLUCION_GERENCIA,
                ResolucionGerencia::class.java,
                RecordQuery(
                    page = PageRequest.of(0, 1),
                    sort = "correlativo",
                    descending = true,
                    filters = mapOf("tipo" to tipo, "anio" to "$anio")
                )
            ).content
            .firstOrNull()
            ?.correlativo ?: 0

    private fun String?.limpio() = this?.trim()?.ifEmpty { null }

    private companion object {
        // what Descargos.registrar gives a descargo, not the request
        val PLAZO_DEL_DESCARGO = setOf("presentado_hasta", "en_plazo", "plazo_texto", "plazo")

        // what the service gives a resolución (its número and plazo), and the fallo's rules, which Resoluciones.validar
        // answers with a 422
        val DE_LA_RESOLUCION = setOf("correlativo", "numero", "plazo_texto", "plazo", "sentido", "descargo", "clave_ris", "clave_descargo")

        // what the service gives a notificación: its intento and key, and its exigibilidad
        val DE_LA_NOTIFICACION = setOf("intento", "clave", "exigible_desde", "plazo_texto")

        fun infraccion(acta: Papeleta) = ActoPrevio("la infracción del acta ${acta.numero}", acta.fechaInfraccion!!)

        fun numerada(
            r: ResolucionGerencia,
            correlativo: Int
        ) = r.copy(correlativo = correlativo, numero = numeroDeResolucion(r.tipo!!, r.anio!!, correlativo))

        // an enum's value that is not one of its options
        fun opcion(
            campo: String,
            valor: String?,
            opciones: List<String>
        ): List<FieldViolation> = if (valor != null && valor !in opciones) listOf(FieldViolation(campo, "es ${opciones.joinToString(", ")}")) else emptyList()
    }
}
