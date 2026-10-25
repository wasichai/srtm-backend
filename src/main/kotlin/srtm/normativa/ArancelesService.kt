package srtm.normativa

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.ParametroInvalido
import srtm.Permisos
import srtm.normativa.CamposArancel.CLAVE
import srtm.normativa.CamposParametro.ANULADO_EN
import srtm.normativa.CamposParametro.ANULADO_POR
import srtm.normativa.CamposParametro.ESTADO
import srtm.normativa.CamposParametro.MOTIVO_ANULACION
import srtm.normativa.CamposParametro.TRANSCRIBIO
import srtm.normativa.CamposParametro.VERIFICO
import srtm.normativa.EstadoParametro.ANULADO
import srtm.normativa.EstadoParametro.POR_VERIFICAR
import srtm.normativa.EstadoParametro.VIGENTE
import srtm.rentas.Registros
import wasichai.core.common.ConflictException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordCriterion
import wasichai.core.data.RecordQuery
import wasichai.core.identity.CurrentUser
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

// Normativa's aranceles (#188): the double signature over arancel, as NormativaService's over the parámetros. an
// arancel is registered POR_VERIFICAR by the user (transcribio), corrected or deleted while so, verified by another
// user (VIGENTE) and, if wrong, annulled and registered again. one alive (not ANULADO) per clave (id_aranc|anio), and
// so one VIGENTE: what the DJ reads (vigente). ReglaDeArancel holds the same on every other door. each act on a clave
// runs under its lock (Candado.ARANCEL). what verificar, anular and importar leave VIGENTE or ANULADO is copied to
// catastro-backend afterwards (SincronizacionAranceles), outside their transactions: their answers say how it went
@Service
class ArancelesService(
    private val registros: Registros,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val candados: Candados,
    private val sincronizacion: SincronizacionAranceles,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)
    private val log = LoggerFactory.getLogger(javaClass)

    // a page by id_aranc. q: the start of id_aranc or cod_via, in any case. an empty estado is VIGENTE
    suspend fun aranceles(
        anio: Int?,
        q: String?,
        estado: String?,
        page: Int,
        size: Int
    ): PageResponse<Arancel> {
        permisos.exigirLeer(ARANCEL, "consultar los aranceles")
        if (estado != null && estado !in EstadoParametro.TODOS) throw ParametroInvalido("estado", "es ${EstadoParametro.TODOS.joinToString(", ")}: '$estado'")
        val pagina =
            registros.page(
                ARANCEL,
                Arancel::class.java,
                RecordQuery(
                    page = PageRequest.of(page, size),
                    // core takes one sort field and breaks its ties by id (ORDER BY id_aranc, id): the pages of one
                    // id_aranc in several years, or of its ANULADO and VIGENTE ones, neither repeat nor skip
                    sort = CamposArancel.ID_ARANC,
                    filters = anio?.let { mapOf(CamposArancel.ANIO to "$it") }.orEmpty(),
                    criteria = listOfNotNull(q?.let(::empiezaCon), estado?.let(::enEstado))
                )
            )
        return PageResponse(pagina.content.map(::conEstado), pagina.page, pagina.size, pagina.totalElements, pagina.totalPages)
    }

    // the arancel the DJ reads: the VIGENTE one of id_aranc in anio, or null. Normativa keeps one VIGENTE per clave, but
    // the importers' door (a VIGENTE insert by /api/objects) may let in a second: then the one of the lowest id, always
    // the same, so a DJ does not flip between them from one recalculation to the next
    suspend fun vigente(
        idAranc: String,
        anio: Int
    ): Arancel? =
        deLaClave(claveDeArancel(idAranc, anio))
            .filter { it.rige() }
            .minByOrNull { it.id.orEmpty() }

    // the arancel a DJ names (DeclaracionService): its VIGENTE one in the DJ's year, or a 400 on id_aranc
    suspend fun deLaDeclaracion(
        idAranc: String,
        anio: Int
    ): Arancel =
        vigente(idAranc, anio)
            ?: throw ValidationException(
                "No hay un arancel VIGENTE para ese tramo de vía",
                CamposArancel.ID_ARANC,
                "no hay un arancel VIGENTE $idAranc para $anio"
            )

    // what a recalculation of a DJ takes: its VIGENTE one, or null (none, or the caller may not read aranceles: the DJ
    // keeps the arancel it has, and nothing fails)
    suspend fun alRecalcular(
        idAranc: String,
        anio: Int
    ): Arancel? = if (permisos.puedeLeer(ARANCEL)) vigente(idAranc, anio) else null

    // whether the caller may read the aranceles: a recalculation asks before it reads anything for them (#226)
    suspend fun legibles(): Boolean = permisos.puedeLeer(ARANCEL)

    // #226: the arancel of a predio's vía (its código de vía, Ica's SAT code) for a recalculation of a DJ without
    // id_aranc: the VIGENTE one of that cod_via in anio, never another year's. several (cuadras or lados) count only
    // with one valor_m2: then the one of the lowest id_aranc (and id), always the same. different values are no
    // arancel, and are logged. null as alRecalcular's: none, or the caller may not read aranceles
    suspend fun deLaVia(
        codVia: String,
        anio: Int
    ): Arancel? {
        if (!legibles()) return null
        val vigentes =
            registros
                .all(ARANCEL, Arancel::class.java, filters = mapOf(COD_VIA to codVia, CamposArancel.ANIO to "$anio"))
                .filter { it.rige() }
        if (vigentes.distinctBy { it.valorM2?.stripTrailingZeros() }.size > 1) {
            log.warn("la vía {} tiene {} aranceles VIGENTE de {} con valores distintos: la DJ no toma ninguno", codVia, vigentes.size, anio)
            return null
        }
        return vigentes.minWithOrNull(compareBy<Arancel>({ it.idAranc.orEmpty() }, { it.id.orEmpty() }))
    }

    // a new arancel POR_VERIFICAR, transcribed by the user: 201. one alive with its clave is a 409
    suspend fun registrar(cuerpo: Map<String, Any?>): Arancel {
        permisos.exigirCrear(ARANCEL, "registrar un arancel")
        val a = DatosDeArancel.validar(DatosDeArancel.de(cuerpo))
        val usuario = firma()
        return transaccion.executeAndAwait {
            bloquear(a.clave.orEmpty())
            sinOtroConSuClave(a, null)
            conEstado(
                registros.create(
                    ARANCEL,
                    Arancel::class.java,
                    DatosDeArancel.atributos(a) + mapOf(ESTADO to POR_VERIFICAR, TRANSCRIBIO to usuario, VERIFICO to null)
                )
            )
        }
    }

    // a POR_VERIFICAR arancel with the body's data over its own: a new id_aranc or anio is a new clave, which must be
    // free. whoever corrects it is its transcriber now. under the lock of both claves, in order
    suspend fun corregir(
        id: UUID,
        cuerpo: Map<String, Any?>
    ): Arancel {
        permisos.exigirActualizar(ARANCEL, "corregir un arancel")
        val cambios = DatosDeArancel.de(cuerpo)
        val usuario = firma()
        return transaccion.executeAndAwait {
            val leido = porVerificar(id, "se corrige")
            val antes = DatosDeArancel.validar(DatosDeArancel.atributos(leido) + cambios)
            // every lock of the transaction at once, sorted (the one order every path takes them in)
            val bloqueadas = listOf(leido.clave.orEmpty(), antes.clave.orEmpty()).distinct().sorted()
            bloqueadas.forEach { bloquear(it) }
            // read again under the locks: still POR_VERIFICAR, and with the same clave. one another correction moved
            // meanwhile would need a lock out of order: a 409, to try again, never a lock taken late
            val guardado = porVerificar(id, "se corrige")
            if (guardado.clave != leido.clave) {
                throw ConflictException("El arancel cambió de clave mientras se corregía (${leido.clave} → ${guardado.clave}): vuelva a intentarlo")
            }
            val a = DatosDeArancel.validar(DatosDeArancel.atributos(guardado) + cambios)
            sinOtroConSuClave(a, guardado.id)
            conEstado(registros.replace(ARANCEL, Arancel::class.java, id, DatosDeArancel.atributos(a) + (TRANSCRIBIO to usuario)))
        }
    }

    // a POR_VERIFICAR arancel is deleted: it never ruled. a VIGENTE one is annulled instead
    suspend fun borrar(id: UUID) {
        permisos.exigirBorrar(ARANCEL, "borrar un arancel")
        transaccion.executeAndAwait {
            // under the lock a verificación takes: the arancel read again is still POR_VERIFICAR, or nothing is deleted
            bloquear(leer(id).clave.orEmpty())
            porVerificar(id, "se borra")
            registros.delete(ARANCEL, id)
        }
    }

    // each arancel on its own: POR_VERIFICAR, transcribed by someone else, and no other VIGENTE of its clave. the user
    // signs it (verifico) and it is VIGENTE
    suspend fun verificar(pedido: PedidoVerificacion): ResultadoVerificacionAranceles {
        permisos.exigirActualizar(ARANCEL, "verificar aranceles")
        val usuario = firma()
        val vigentes = mutableListOf<Arancel>()
        val resultado = verificarCada(pedido, "aranceles") { id -> vigentes += transaccion.executeAndAwait { verificarUno(id, usuario) } }
        return ResultadoVerificacionAranceles(resultado.verificados, resultado.rechazados, sincronizacion.despuesDe(vigentes))
    }

    private suspend fun verificarUno(
        id: UUID,
        usuario: String
    ): Arancel {
        bloquear(leer(id).clave.orEmpty())
        val a = leer(id)
        val estado = EstadoParametro.de(a.estado)
        if (estado != POR_VERIFICAR) throw ConflictException("está $estado: solo se verifica uno POR_VERIFICAR")
        if (firmante(a.transcribio) == firmante(usuario)) throw ConflictException("no puede verificar lo que transcribió: la doble firma pide otra persona")
        deLaClave(a.clave.orEmpty()).firstOrNull { it.id != a.id && it.rige() }?.let {
            throw ConflictException("ya hay un arancel VIGENTE ${it.nombre()} (${it.id}): anúlelo antes")
        }
        return registros.replace(ARANCEL, Arancel::class.java, id, mapOf(ESTADO to VIGENTE, VERIFICO to usuario, CamposArancel.SINCRONIZADO_EN to null))
    }

    // a VIGENTE arancel is ANULADO: its motivo, the user and now. the correct value is registered as a new one. catastro
    // loses its copy (or takes the clave's other VIGENTE one); the arancel answered is read after that copy
    suspend fun anular(
        id: UUID,
        pedido: PedidoAnulacion
    ): ArancelAnulado {
        permisos.exigirActualizar(ARANCEL, "anular un arancel")
        val motivo = pedido.motivo?.trim()
        if (motivo.isNullOrEmpty()) throw ValidationException("Anular un arancel exige su motivo", "motivo", "es obligatorio")
        val usuario = firma()
        val anulado =
            transaccion.executeAndAwait {
                bloquear(leer(id).clave.orEmpty())
                val estado = EstadoParametro.de(leer(id).estado)
                if (estado != VIGENTE) throw ConflictException("Solo se anula un arancel VIGENTE: este está $estado")
                registros.replace(
                    ARANCEL,
                    Arancel::class.java,
                    id,
                    mapOf(
                        ESTADO to ANULADO,
                        MOTIVO_ANULACION to motivo,
                        ANULADO_POR to usuario,
                        ANULADO_EN to OffsetDateTime.now(ZoneOffset.UTC).toString(),
                        // what catastro holds changes: pending until its copy runs (sincronizar takes it if it never does)
                        CamposArancel.SINCRONIZADO_EN to null
                    )
                )
            }
        val copia = sincronizacion.despuesDe(listOf(anulado)) ?: return ArancelAnulado(anulado, null)
        return ArancelAnulado(leer(id), copia)
    }

    // POST /normativa/aranceles/sincronizar: the copies still pending (or failed) of the year, again
    suspend fun sincronizar(anio: Int?): Sincronizacion = sincronizacion.pendientes(anio)

    // the csv (CsvDeAranceles, PlanDeAranceles): with dryRun what it would do, without it the same written lote by
    // lote. the whole file is planned first (its rechazos, the claves it repeats); then each lote of the rows to write
    // (LOTE) is planned again in its own transaction, under the locks of its claves (sorted), against what is stored of
    // them now, and written, CONCURRENCIA lotes at a time: their claves are disjoint (the file repeats none), so no
    // lote waits on another. a lote that fails leaves the others that finished written, and the 409 says so
    // (PlanDeAranceles.parcial): the importación is idempotent, run it again. a Perené or Ica tariff (~1 500 rows) is
    // some fifteen lotes, and no transaction holds them all
    suspend fun importar(
        csv: String,
        dryRun: Boolean
    ): ResultadoImportacionAranceles {
        permisos.exigirCrear(ARANCEL, "importar aranceles")
        permisos.exigirActualizar(ARANCEL, "importar aranceles")
        val filas = CsvDeAranceles.leer(csv)
        val usuario = firma()
        val plan = PlanDeAranceles.planear(filas, registros.all(ARANCEL, Arancel::class.java), usuario)
        if (dryRun) return resultado(true, plan.crear, plan.actualizar, plan.iguales, plan.rechazos)
        val clavePorFila = (plan.crear + plan.actualizar).associate { it.fila to it.arancel.clave.orEmpty() }
        val enCurso = Semaphore(CONCURRENCIA)
        val porLote = filas.filter { it.linea in clavePorFila }.chunked(LOTE)
        val escritos = AtomicInteger()
        val filasEscritas = AtomicInteger()
        val lotes =
            try {
                coroutineScope {
                    porLote
                        .map { lote ->
                            async {
                                enCurso.withPermit {
                                    transaccion
                                        .executeAndAwait {
                                            val claves = lote.map { clavePorFila.getValue(it.linea) }.distinct().sorted()
                                            claves.forEach { bloquear(it) }
                                            val guardados = registros.donde(ARANCEL, Arancel::class.java, listOf(unoDe(CLAVE, claves)))
                                            PlanDeAranceles.planear(lote, guardados, usuario).also { escribir(it) }
                                        }.also {
                                            escritos.incrementAndGet()
                                            filasEscritas.addAndGet(lote.size)
                                        }
                                }
                            }
                        }.awaitAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // nothing written: the error as it is. some lotes in: say so, and that running it again is safe
                if (escritos.get() == 0) throw e
                throw PlanDeAranceles.parcial(escritos.get(), porLote.size, filasEscritas.get(), e.message ?: e.javaClass.simpleName)
            }
        // the rows left VIGENTE, to catastro: read again by their claves (a new one's id is core's)
        val vigentes =
            (lotes.flatMap { it.crear } + lotes.flatMap { it.actualizar })
                .filter { it.estado == VIGENTE }
                .map { it.arancel.clave.orEmpty() }
                .distinct()
                .chunked(PageRequest.MAX_SIZE)
                .flatMap { claves -> registros.donde(ARANCEL, Arancel::class.java, listOf(unoDe(CLAVE, claves))) }
                .filter { it.rige() }
        return resultado(
            false,
            lotes.flatMap { it.crear },
            lotes.flatMap { it.actualizar },
            plan.iguales + lotes.sumOf { it.iguales },
            (plan.rechazos + lotes.flatMap { it.rechazos }).sortedBy { it.fila },
            sincronizacion.despuesDe(vigentes)
        )
    }

    private fun resultado(
        dryRun: Boolean,
        crear: List<EscrituraDeArancel>,
        actualizar: List<EscrituraDeArancel>,
        iguales: Int,
        rechazos: List<RechazoDeFila>,
        copia: Sincronizacion? = null
    ) = ResultadoImportacionAranceles(dryRun, crear.map(FilaDeArancel::de), actualizar.map(FilaDeArancel::de), iguales, rechazos, copia)

    private suspend fun escribir(plan: PlanDeArancel) {
        registros.createAll(
            ARANCEL,
            Arancel::class.java,
            plan.crear.map { DatosDeArancel.atributos(it.arancel) + mapOf(ESTADO to it.estado, TRANSCRIBIO to it.transcribio, VERIFICO to it.verifico) }
        )
        // a correction stays POR_VERIFICAR, and with two signatures it is verified next: two writes, as the rule asks
        plan.actualizar.forEach {
            val id = UUID.fromString(it.id)
            val datos = DatosDeArancel.atributos(it.arancel) + mapOf(ESTADO to POR_VERIFICAR, TRANSCRIBIO to it.transcribio, VERIFICO to null)
            registros.replace(ARANCEL, Arancel::class.java, id, datos)
            if (it.estado == VIGENTE) registros.replace(ARANCEL, Arancel::class.java, id, mapOf(ESTADO to VIGENTE, VERIFICO to it.verifico))
        }
    }

    private suspend fun leer(id: UUID): Arancel = registros.get(ARANCEL, Arancel::class.java, id)

    private suspend fun porVerificar(
        id: UUID,
        acto: String
    ): Arancel {
        val a = leer(id)
        val estado = EstadoParametro.de(a.estado)
        if (estado != POR_VERIFICAR) {
            throw ConflictException("Solo $acto un arancel POR_VERIFICAR: este está $estado. Uno VIGENTE se anula y se registra el valor correcto")
        }
        return a
    }

    private suspend fun bloquear(clave: String) = candados.bloquearEnOrganizacion(Candado.ARANCEL, clave)

    private suspend fun deLaClave(clave: String): List<Arancel> = registros.all(ARANCEL, Arancel::class.java, filters = mapOf(CLAVE to clave))

    private suspend fun sinOtroConSuClave(
        a: Arancel,
        excepto: String?
    ) {
        val otro = deLaClave(a.clave.orEmpty()).firstOrNull { it.id != excepto && EstadoParametro.de(it.estado) != ANULADO } ?: return
        val estado = EstadoParametro.de(otro.estado)
        val que = if (estado == VIGENTE) "anúlelo antes de registrar otro" else "corríjalo o bórrelo"
        throw ConflictException("Ya hay un arancel $estado con esa clave, ${otro.nombre()} (${otro.id}): $que")
    }

    private suspend fun firma(): String = currentUser.firma()

    private fun conEstado(a: Arancel) = a.copy(estado = EstadoParametro.de(a.estado))

    // q at the start of id_aranc or cod_via, in any case: LIKE's wildcards in q are taken literally
    private fun empiezaCon(q: String) =
        RecordCriterion { definition, bind ->
            val patron = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            listOf(CamposArancel.ID_ARANC, "cod_via").joinToString(" OR ", "(", ")") { campo ->
                "\"${definition.fields.first { it.name == campo }.columnName}\" ILIKE ${bind(patron)}"
            }
        }

    // an empty estado is VIGENTE
    private fun enEstado(estado: String) =
        RecordCriterion { definition, bind ->
            val columna = "\"${definition.fields.first { it.name == ESTADO }.columnName}\""
            if (estado == VIGENTE) "($columna = ${bind(estado)} OR $columna IS NULL)" else "$columna = ${bind(estado)}"
        }

    private companion object {
        // rows per transaction of an importación, and transactions at once (each holds a connection of the shared pool)
        const val LOTE = 100
        const val CONCURRENCIA = 4
        const val COD_VIA = "cod_via"
    }
}
