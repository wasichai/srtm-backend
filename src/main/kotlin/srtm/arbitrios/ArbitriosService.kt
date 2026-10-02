package srtm.arbitrios

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.impuesto.ParametrosTributarios
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Records
import srtm.rentas.Registros
import srtm.rentas.USO_PREDIO
import srtm.rentas.UsoPredio
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.Actions
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

// what keeps a year or a predio from being determined: a 422 that names each missing thing
class FaltanArbitrios(
    anio: Int,
    val faltan: List<String>
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
    private val indices: IndicesArbitrios,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)

    // the year's ordinance, servicios, parameters and uso catalog: read once per determination
    suspend fun contexto(anio: Int) =
        ContextoArbitrios(
            anio,
            registros.all(ORDENANZA_ARBITRIO, OrdenanzaArbitrio::class.java, filters = mapOf("anio" to "$anio")).firstOrNull(),
            registros.all(SERVICIO_ARBITRIO, ServicioArbitrio::class.java),
            parametrosTributarios.todos(),
            registros.all(USO_PREDIO, UsoPredio::class.java)
        )

    // a predio's declarations of the year, its contribuyentes' codes, its inafectaciones and its cuotas of the year
    suspend fun delPredio(
        predio: Predio,
        anio: Int
    ): PredioArbitrios {
        val id = requireNotNull(predio.id)
        val declaraciones = registros.all(DECLARACION, Declaracion::class.java, filters = mapOf("predio" to id, "anio" to "$anio"))
        val codigos = personas(declaraciones.mapNotNull { it.contribuyente }).mapValues { it.value.codigo }
        val inafectaciones = registros.all(INAFECTACION_ARBITRIO, InafectacionArbitrio::class.java, filters = mapOf("predio" to id))
        return PredioArbitrios(predio, declaraciones, codigos, inafectaciones, cuotas(mapOf("predio" to id, "anio" to "$anio")))
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
        val calculadas = predios.map { it to Arbitrios.determinar(contexto, delPredio(it, anio), observacion, hoy) }
        val faltan = calculadas.flatMap { (p, d) -> d.faltan.map { f -> if (p.codigo != null && p.codigo in f) f else "Predio ${p.codigo}: $f" } }.distinct()
        if (faltan.isNotEmpty()) throw FaltanArbitrios(anio, faltan)
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
        anio: Int
    ): ArbitriosContribuyente {
        val contribuyente = registros.get(CONTRIBUYENTE, Contribuyente::class.java, id)
        val contexto = contexto(anio)
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
    ): PageResponse<CuotaArbitrio> {
        indices.asegurar(currentUser.require().organizationId)
        return registros.page(CUOTA_ARBITRIO, CuotaArbitrio::class.java, RecordQuery(page = PageRequest.of(page, size), sort = "periodo", filters = filtros))
    }

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
        val primera = calculada ?: Arbitrios.determinar(contexto, delPredio(predio, contexto.anio), observacion, hoy)
        return try {
            guardar(contexto.anio, primera)
        } catch (_: DuplicateKeyException) {
            guardar(contexto.anio, Arbitrios.determinar(contexto, delPredio(predio, contexto.anio), observacion, hoy))
        }
    }

    // internal: ArbitriosApiTest proves that a cuota refused midway leaves none of the predio's written
    internal suspend fun guardar(
        anio: Int,
        d: Determinacion
    ): List<CuotaArbitrio> {
        if (d.faltan.isNotEmpty()) throw FaltanArbitrios(anio, d.faltan)
        if (d.cuotas.isEmpty()) return emptyList()
        return transaccion.executeAndAwait { d.cuotas.map { registros.create(CUOTA_ARBITRIO, CuotaArbitrio::class.java, Records.attributes(it)) } }
    }

    private suspend fun cuotas(filtros: Map<String, String>): List<CuotaArbitrio> {
        indices.asegurar(currentUser.require().organizationId)
        return registros.all(CUOTA_ARBITRIO, CuotaArbitrio::class.java, filters = filtros)
    }

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

    companion object {
        val ANIOS = 1900..9999
    }
}
