package srtm.arbitrios

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Observacion
import srtm.impuesto.ParametrosTributarios
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Records
import srtm.rentas.Registros
import srtm.rentas.SUSTITUIDA
import srtm.rentas.USO_PREDIO
import srtm.rentas.UsoPredio
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import wasichai.core.data.RecordQuery
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import java.time.LocalDate
import java.util.UUID

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoDeterminacion(
    val anio: Int? = null,
    val observacion: String? = null
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoAnulacion(
    val motivo: String? = null,
    val observacion: String? = null
)

// what keeps a year or a predio from being determined: a 422 that names each missing thing. detalle is the same,
// each with its predio, its declaración and where it gets fixed (Falta.enlace, what the portal links to); empty
// for a caller that has none (DocumentosArbitrios, DeterminacionMasivaService), same as faltan would be without this
class FaltanArbitrios(
    anio: Int,
    val faltan: List<String>,
    val detalle: List<Falta> = emptyList()
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, "No se pueden determinar los arbitrios de $anio: ${faltan.joinToString("; ")}")

// a query parameter the endpoint cannot serve, or one it cannot read: a 422 that names it (rentas#541), never a filter
// silently dropped
class ParametroInvalido(
    nombre: String,
    razon: String
) : WasichaiException(HttpStatus.UNPROCESSABLE_CONTENT, "El parámetro '$nombre' $razon", listOf(FieldViolation(nombre, razon)))

// the arbitrios of the portal. reads and writes go through Registros as the caller, so core applies their permissions.
// the pure rules are Arbitrios (what to determine) and Consultas (what to show)
@Service
class ArbitriosService(
    private val registros: Registros,
    private val parametrosTributarios: ParametrosTributarios,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)
    private val log = LoggerFactory.getLogger(javaClass)

    // the year's ordinance, servicios, parameters, uso catalog and zona_arbitrio_ubicacion rows: read once per
    // determination
    suspend fun contexto(anio: Int) =
        ContextoArbitrios(
            anio,
            registros.all(ORDENANZA_ARBITRIO, OrdenanzaArbitrio::class.java, filters = mapOf("anio" to "$anio")).firstOrNull(),
            registros.all(SERVICIO_ARBITRIO, ServicioArbitrio::class.java),
            parametrosTributarios.todos(),
            registros.all(USO_PREDIO, UsoPredio::class.java),
            registros.all(ZONA_ARBITRIO_UBICACION, ZonaUbicacion::class.java)
        )

    // a predio's declarations of the year, its contribuyentes' codes, its inafectaciones and its cuotas of the year;
    // with conAnteriores (Arbitrios.conTope: the year has a TOPE_ARBITRIO), its vigente cuotas of the year before and
    // the date it was registered (Arbitrios.registrado, of its declaraciones of any year: the earliest
    // fecha_presentacion and the lowest anio of one without it, one row each) too
    suspend fun delPredio(
        predio: Predio,
        anio: Int,
        conAnteriores: Boolean = false
    ): PredioArbitrios {
        val id = requireNotNull(predio.id)
        // the annulled ones too (cubre: up to their fecha_anulacion), not a sustituida: its rectificatoria replaces it
        val declaraciones =
            registros
                .all(DECLARACION, Declaracion::class.java, filters = mapOf("predio" to id, "anio" to "$anio"))
                .filter { it.estado != SUSTITUIDA }
        val codigos = personas(declaraciones.mapNotNull { it.contribuyente }).mapValues { it.value.codigo }
        val inafectaciones = registros.all(INAFECTACION_ARBITRIO, InafectacionArbitrio::class.java, filters = mapOf("predio" to id))
        val (existentes, anuladas) = delAnio(id, anio)
        if (!conAnteriores) return PredioArbitrios(predio, declaraciones, codigos, inafectaciones, existentes, anuladas)
        val anteriores = delAnio(id, anio - 1).let { (cs, anuladasAntes) -> Arbitrios.vigentes(cs, anuladasAntes) }
        val delPredio = mapOf("predio" to id)
        val registrado =
            Arbitrios.registrado(
                registros.lowest(DECLARACION, "fecha_presentacion", delPredio)?.let { LocalDate.parse(it.take(10)) },
                registros.lowest(DECLARACION, "anio", delPredio, vacio = "fecha_presentacion")?.toBigDecimalOrNull()?.toInt()
            )
        return PredioArbitrios(predio, declaraciones, codigos, inafectaciones, existentes, anuladas, anteriores, registrado)
    }

    // a predio's cuotas of a year and the ids of the annulled ones
    private suspend fun delAnio(
        predio: String,
        anio: Int
    ): Pair<List<CuotaArbitrio>, Set<String>> {
        val anuladas =
            registros
                .all(ANULACION_CUOTA_ARBITRIO, AnulacionCuotaArbitrio::class.java, filters = mapOf("predio" to predio, "anio" to "$anio"))
                .mapNotNull { it.cuota }
                .toSet()
        return cuotas(mapOf("predio" to predio, "anio" to "$anio")) to anuladas
    }

    // POST /predios/{id}/arbitrios: the cuotas it wrote; none when nothing was pending
    suspend fun determinarPredio(
        id: UUID,
        pedido: PedidoDeterminacion
    ): List<CuotaArbitrio> {
        exigirDeterminar()
        val (anio, observacion) = validar(pedido)
        val predio = registros.get(PREDIO, Predio::class.java, id)
        return escribir(contexto(anio), predio, observacion, LocalDate.now())
    }

    // POST /contribuyentes/{id}/arbitrios: every predio of its declarations of the year (an annulled one too: it may
    // cover the months before a sale). all of them are computed before any is written: one that cannot be determined
    // stops them all, and is named
    suspend fun determinarContribuyente(
        id: UUID,
        pedido: PedidoDeterminacion
    ): List<CuotaArbitrio> {
        exigirDeterminar()
        val (anio, observacion) = validar(pedido)
        registros.get(CONTRIBUYENTE, Contribuyente::class.java, id)
        val contexto = contexto(anio)
        val hoy = LocalDate.now()
        val predios = prediosDe(id, anio, conCuotas = false)
        val conTope = Arbitrios.conTope(contexto)
        val calculadas = predios.map { it to Arbitrios.determinar(contexto, delPredio(it, anio, conTope), observacion, hoy) }
        val detalle =
            calculadas
                .flatMap { (p, d) ->
                    d.detalle.map { f ->
                        if (p.codigo != null &&
                            p.codigo in f.mensaje
                        ) {
                            f
                        } else {
                            f.copy(mensaje = "Predio ${p.codigo}: ${f.mensaje}")
                        }
                    }
                }.distinctBy { it.mensaje }
        if (detalle.isNotEmpty()) throw FaltanArbitrios(anio, detalle.map { it.mensaje }, detalle)
        return calculadas.flatMap { (p, d) -> escribir(contexto, p, observacion, hoy, d) }
    }

    // one predio of a masiva's lote, with the lote's contexto and the job's observación: what it wrote. FaltanArbitrios
    // when it cannot be determined. as the lote's user, like a POST of the portal
    suspend fun determinarEnLote(
        contexto: ContextoArbitrios,
        predio: UUID,
        observacion: String,
        hoy: LocalDate
    ): List<CuotaArbitrio> = escribir(contexto, registros.get(PREDIO, Predio::class.java, predio), observacion, hoy)

    // POST /arbitrios/cuotas/{id}/anulacion: the cuota stops counting, and the next determination of its predio writes
    // it again with the next version of its clave. a cuota is annulled once (409); motivo and observación say why (400)
    suspend fun anular(
        id: UUID,
        pedido: PedidoAnulacion
    ): AnulacionCuotaArbitrio {
        val usuario = currentUser.require()
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(ANULACION_CUOTA_ARBITRIO).obj.id)
        } catch (_: ForbiddenException) {
            throw ForbiddenException("Anular una cuota exige permiso de creación sobre $ANULACION_CUOTA_ARBITRIO")
        }
        val motivo = pedido.motivo?.trim()?.ifEmpty { null } ?: throw ValidationException("Falta el motivo", "motivo", "indica por qué se anula")
        val observacion = Observacion.de(pedido.observacion)
        val cuota = registros.get(CUOTA_ARBITRIO, CuotaArbitrio::class.java, id)
        return try {
            registros.create(
                ANULACION_CUOTA_ARBITRIO,
                AnulacionCuotaArbitrio::class.java,
                Records.attributes(
                    AnulacionCuotaArbitrio(
                        cuota = cuota.id,
                        predio = cuota.predio,
                        anio = cuota.anio,
                        motivo = motivo,
                        observacion = observacion,
                        fecha = LocalDate.now(),
                        clave = cuota.id
                    )
                )
            )
        } catch (_: DuplicateKeyException) {
            throw ConflictException("La cuota ya está anulada")
        }
    }

    // #158: a predio's year after a change of its declaraciones (DeclaracionService): the twelve months computed again
    // and only the ones that come out different written, each annulled (motivo REDETERMINACION, the observación says
    // which declaración) and written again as its next version (Redeterminacion). with the vigente cuotas and the
    // anulaciones in one transaction: all of the predio's or none. nothing when the predio has no vigente cuota of the
    // year: the first determination is the portal's or the masiva's. as the caller, like every write of srtm (#76):
    // who may not create cuotas and anulaciones gets the reason, not a 403. it never throws: what kept it from being
    // done is the result's error, and the cuotas stay as they were. a determination of the same predio meanwhile (the
    // clave and the anulación's clave are unique) makes it read and compute again, once, as escribir does
    suspend fun redeterminarPredio(
        predio: UUID,
        anio: Int,
        observacion: String
    ): ResultadoRedeterminacion? =
        try {
            conReintento(predio, anio, observacion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WasichaiException) {
            ResultadoRedeterminacion(error = e.message)
        } catch (e: Exception) {
            log.error("No se pudieron redeterminar los arbitrios {} del predio {}", anio, predio, e)
            ResultadoRedeterminacion(error = "No se pudieron redeterminar los arbitrios de $anio del predio")
        }

    // POST /predios/{id}/arbitrios/redeterminacion: the same by hand, for the arbitrios area (a redeterminación that the
    // write of a declaración could not do, or a year whose ordinance changed). 403 before anything is read, 422 with
    // what is missing, as determinarPredio
    suspend fun redeterminar(
        id: UUID,
        pedido: PedidoDeterminacion
    ): ResultadoRedeterminacion {
        exigirRedeterminar()
        val (anio, observacion) = validar(pedido)
        registros.get(PREDIO, Predio::class.java, id)
        return conReintento(id, anio, observacion) ?: ResultadoRedeterminacion()
    }

    private suspend fun conReintento(
        predio: UUID,
        anio: Int,
        observacion: String
    ) = try {
        redeterminar(predio, anio, observacion)
    } catch (_: DuplicateKeyException) {
        redeterminar(predio, anio, observacion)
    }

    private suspend fun redeterminar(
        id: UUID,
        anio: Int,
        observacion: String
    ): ResultadoRedeterminacion? {
        // one row says whether the predio has cuotas of the year, before the year's ordinance is read
        val alguna = RecordQuery(page = PageRequest.of(0, 1), filters = mapOf("predio" to "$id", "anio" to "$anio"), count = false)
        if (registros.page(CUOTA_ARBITRIO, CuotaArbitrio::class.java, alguna).content.isEmpty()) return null
        val contexto = contexto(anio)
        val datos = delPredio(registros.get(PREDIO, Predio::class.java, id), anio, Arbitrios.conTope(contexto))
        val vigentes = Arbitrios.vigentes(datos.existentes, datos.anuladas)
        if (vigentes.isEmpty()) return null
        val hoy = LocalDate.now()
        val d = Redeterminacion.completa(contexto, datos, observacion, hoy)
        if (d.faltan.isNotEmpty()) throw FaltanArbitrios(anio, d.faltan, d.detalle)
        val cambios = Redeterminacion.comparar(vigentes, d.cuotas)
        if (cambios.escribir.isEmpty() && cambios.anular.isEmpty()) return ResultadoRedeterminacion()
        exigirRedeterminar()
        val anulaciones =
            cambios.anular.map {
                Records.attributes(
                    AnulacionCuotaArbitrio(
                        cuota = it.id,
                        predio = it.predio,
                        anio = it.anio,
                        motivo = REDETERMINACION,
                        observacion = observacion,
                        fecha = hoy,
                        clave = it.id
                    )
                )
            }
        transaccion.executeAndAwait {
            registros.createAll(ANULACION_CUOTA_ARBITRIO, AnulacionCuotaArbitrio::class.java, anulaciones)
            registros.createAll(CUOTA_ARBITRIO, CuotaArbitrio::class.java, cambios.escribir.map(Records::attributes))
        }
        return ResultadoRedeterminacion(cambios.escribir.size, cambios.anular.size)
    }

    // GET /predios/{id}/arbitrios
    suspend fun matrizDelPredio(
        id: UUID,
        anio: Int
    ): MatrizArbitrios {
        val predio = registros.get(PREDIO, Predio::class.java, id)
        val contexto = contexto(anio)
        val datos = delPredio(predio, anio)
        val personas = personas(datos.declaraciones.mapNotNull { it.contribuyente } + datos.existentes.mapNotNull { it.contribuyente })
        return Consultas.matriz(contexto, datos, personas, LocalDate.now())
    }

    // GET /contribuyentes/{id}/arbitrios: the predios of its declarations of the year and those it has cuotas of
    suspend fun delContribuyente(
        id: UUID,
        anio: Int,
        delAnio: ContextoArbitrios? = null
    ): ArbitriosContribuyente {
        val contribuyente = registros.get(CONTRIBUYENTE, Contribuyente::class.java, id)
        val contexto = delAnio ?: contexto(anio)
        val hoy = LocalDate.now()
        val matrices =
            prediosDe(id, anio, conCuotas = true).map { predio ->
                val datos = delPredio(predio, anio)
                val personas = personas(datos.declaraciones.mapNotNull { it.contribuyente } + datos.existentes.mapNotNull { it.contribuyente })
                Consultas.matriz(contexto, datos, personas, hoy, soloDe = id.toString())
            }
        return ArbitriosContribuyente(
            anio,
            Consultas.persona(id.toString(), contribuyente),
            matrices,
            Consultas.suma(matrices.map { it.total }),
            matrices.mapNotNull { it.fechaCalculo }.maxOrNull()
        )
    }

    // GET /arbitrios/servicios: the ones in force some day of the year
    suspend fun servicios(anio: Int): List<ServicioArbitrio> {
        val todos = registros.all(SERVICIO_ARBITRIO, ServicioArbitrio::class.java)
        return Periodo.MESES
            .flatMap { Arbitrios.serviciosDelDia(todos, Periodo.atribucion(anio, it)) }
            .distinctBy { it.id }
            .sortedWith(compareBy(nullsLast()) { it: ServicioArbitrio -> it.orden }.thenBy { it.codigo })
    }

    // GET /arbitrios/parametros: the ordinance, the servicios and the rows of the ordinance in force some day of the
    // year, and what the year lacks. always a 200, like /liquidacion
    suspend fun parametros(anio: Int): ParametrosArbitrios {
        val contexto = contexto(anio)
        val inicio = LocalDate.of(anio, 1, 1)
        val fin = LocalDate.of(anio, 12, 31)
        val delAnio =
            contexto.parametros
                .filter {
                    it.tipo in Llaves.TIPOS && it.vigenciaDesde != null && !it.vigenciaDesde.isAfter(fin) &&
                        (it.vigenciaHasta == null || !it.vigenciaHasta.isBefore(inicio))
                }.sortedWith(compareBy({ Llaves.TIPOS.indexOf(it.tipo) }, { it.clave }, { it.vigenciaDesde }))
        val sinFilas = listOf(Llaves.TASA_ARBITRIO, Llaves.ARBITRIO_ZONA, Llaves.ARBITRIO_USO).filter { t -> delAnio.none { it.tipo == t } }.map { "$it $anio" }
        return ParametrosArbitrios(anio, contexto.ordenanza?.takeIf { it.anio == anio }, servicios(anio), delAnio, Arbitrios.faltanDelAnio(contexto) + sinFilas)
    }

    // GET /arbitrios: a page of cuotas, by the filters the controller read
    suspend fun pagina(
        filtros: Map<String, String>,
        page: Int,
        size: Int
    ): PageResponse<CuotaArbitrio> =
        registros.page(CUOTA_ARBITRIO, CuotaArbitrio::class.java, RecordQuery(page = PageRequest.of(page, size), sort = "periodo", filters = filtros))

    // the cuotas to write, written in one transaction: all of the predio's or none. another determination of the same
    // predio may write some of them between our read and our write (the clave is unique): then nothing of ours stayed,
    // and it is read and computed again, once. what the other wrote now exists and is not recomputed
    private suspend fun escribir(
        contexto: ContextoArbitrios,
        predio: Predio,
        observacion: String,
        hoy: LocalDate,
        calculada: Determinacion? = null
    ): List<CuotaArbitrio> {
        val conTope = Arbitrios.conTope(contexto)
        val primera = calculada ?: Arbitrios.determinar(contexto, delPredio(predio, contexto.anio, conTope), observacion, hoy)
        return try {
            guardar(contexto.anio, primera)
        } catch (_: DuplicateKeyException) {
            guardar(contexto.anio, Arbitrios.determinar(contexto, delPredio(predio, contexto.anio, conTope), observacion, hoy))
        }
    }

    // internal: ArbitriosApiTest proves that a cuota refused midway leaves none of the predio's written
    internal suspend fun guardar(
        anio: Int,
        d: Determinacion
    ): List<CuotaArbitrio> {
        if (d.faltan.isNotEmpty()) throw FaltanArbitrios(anio, d.faltan, d.detalle)
        if (d.cuotas.isEmpty()) return emptyList()
        return transaccion.executeAndAwait { registros.createAll(CUOTA_ARBITRIO, CuotaArbitrio::class.java, d.cuotas.map(Records::attributes)) }
    }

    // the cuotas of the year charged to a contribuyente
    internal suspend fun cuotasDe(
        contribuyente: UUID,
        anio: Int
    ) = cuotas(mapOf("contribuyente" to "$contribuyente", "anio" to "$anio"))

    private suspend fun cuotas(filtros: Map<String, String>): List<CuotaArbitrio> = registros.all(CUOTA_ARBITRIO, CuotaArbitrio::class.java, filters = filtros)

    // the predios of a contribuyente's declarations of the year, and (conCuotas) of the cuotas charged to it, by code
    private suspend fun prediosDe(
        id: UUID,
        anio: Int,
        conCuotas: Boolean
    ): List<Predio> {
        val porDeclaracion =
            registros
                .all(
                    DECLARACION,
                    Declaracion::class.java,
                    filters = mapOf("contribuyente" to "$id", "anio" to "$anio")
                ).mapNotNull { it.predio }
        val porCuota = if (conCuotas) cuotas(mapOf("contribuyente" to "$id", "anio" to "$anio")).mapNotNull { it.predio } else emptyList()
        return registros
            .byIds(PREDIO, Predio::class.java, porDeclaracion + porCuota)
            .values
            .sortedWith(compareBy(nullsLast()) { it: Predio -> it.codigo })
    }

    private suspend fun personas(ids: List<String>): Map<String, Contribuyente> = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, ids)

    // the year (the current one by default, a plausible one when given) and the observación (5 to 500 characters)
    private fun validar(pedido: PedidoDeterminacion): Pair<Int, String> {
        val anio = pedido.anio ?: LocalDate.now().year
        if (anio !in ANIOS) throw ValidationException("El año no es válido", "anio", "de ${ANIOS.first} a ${ANIOS.last}")
        return anio to Observacion.de(pedido.observacion)
    }

    // before anything is read: who may not create cuotas gets a 403 that says so, not a computation it cannot write
    private suspend fun exigirDeterminar() {
        val usuario = currentUser.require()
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(CUOTA_ARBITRIO).obj.id)
        } catch (_: ForbiddenException) {
            throw ForbiddenException("Determinar arbitrios exige permiso de creación sobre $CUOTA_ARBITRIO")
        }
    }

    // what a redeterminación writes: cuotas and their anulaciones. said before anything is written
    private suspend fun exigirRedeterminar() {
        val usuario = currentUser.require()
        try {
            for (objeto in listOf(CUOTA_ARBITRIO, ANULACION_CUOTA_ARBITRIO)) {
                currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(objeto).obj.id)
            }
        } catch (_: ForbiddenException) {
            throw ForbiddenException("Redeterminar arbitrios exige permiso de creación sobre $CUOTA_ARBITRIO y $ANULACION_CUOTA_ARBITRIO")
        }
    }

    companion object {
        val ANIOS = 1900..9999

        // the motivo of the anulaciones a redeterminación writes (redeterminarPredio)
        const val REDETERMINACION = "REDETERMINACION"
    }
}
