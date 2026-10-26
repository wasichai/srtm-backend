package srtm.brecha

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.arbitrios.ArbitriosService
import srtm.arbitrios.ContextoArbitrios
import srtm.arbitrios.INAFECTACION_ARBITRIO
import srtm.arbitrios.InafectacionArbitrio
import srtm.emision.TablasCore
import srtm.impuesto.ParametrosTributarios
import srtm.rentas.CONTRIBUYENTE
import srtm.rentas.Contribuyente
import srtm.rentas.DECLARACION
import srtm.rentas.Declaracion
import srtm.rentas.PREDIO
import srtm.rentas.Predio
import srtm.rentas.Records
import srtm.rentas.Registros
import srtm.sanciones.Multas
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.UnauthorizedException
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import wasichai.core.data.RecordService
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.organization.OrganizationRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// the corridas de brecha (DeterminacionMasivaService's shape, without its lotes): a POST checks what it can, leaves a
// PENDIENTE corrida and returns; in the background, as the caller, one worker reads catastro-backend's valuations of
// the year and srtm's padrón, measures them (Brecha.medir) and writes the brecha_predio rows in batches, counting them
// in procesados, until the corrida is TERMINADA (or FALLIDA, with mensaje). ~4 000 predios are a minute or two of one
// worker: the masiva's lotes (several instances, leases per lote) would buy nothing here. one active corrida per
// organization and year, under Candado.BRECHA; one whose latido is older than LEASE (its instance died) no longer
// counts as active and is failed when the next one starts, or when it is read (lista, get). each organization reads
// its own catastro-backend (srtm.catastro.organizaciones, Catastros): one without it is refused
@Service
class BrechaService(
    private val registros: Registros,
    private val metadata: MetadataService,
    private val currentUser: CurrentUser,
    private val candados: Candados,
    private val catastros: Catastros,
    private val arbitrios: ArbitriosService,
    private val parametrosTributarios: ParametrosTributarios,
    private val db: DatabaseClient,
    private val organizaciones: OrganizationRepository,
    private val records: RecordService,
    private val tablas: TablasCore,
    transacciones: ReactiveTransactionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transaccion = TransactionalOperator.create(transacciones)

    // 202: the corrida PENDIENTE. before anything exists: 403 without the permissions its run uses, 400 for the year,
    // 409 when catastro is not configured (or not for the caller's organization) or a corrida of the year is running.
    // the corrida reads the catastro chosen here
    suspend fun lanzar(pedido: PedidoCorrida): Corrida {
        val llamante =
            ReactiveSecurityContextHolder.getContext().awaitFirstOrNull()?.authentication
                ?: throw UnauthorizedException("Authentication required")
        val usuario = exigirPermisos()
        val anio = pedido.anio ?: LocalDate.now().year
        if (anio !in ArbitriosService.ANIOS) {
            throw ValidationException("El año no es válido", "anio", "de ${ArbitriosService.ANIOS.first} a ${ArbitriosService.ANIOS.last}")
        }
        if (!catastros.configurado) throw ConflictException(NO_CONFIGURADO)
        val catastro = catastroDe(usuario)
        val creada = crearCorrida(usuario.organizationId, anio, OrigenCorrida.MANUAL)
        val id = UUID.fromString(creada.id)
        scope.launch(ReactiveSecurityContextHolder.withAuthentication(llamante).asCoroutineContext()) { correr(id, anio, catastro) }
        return corridaDe(creada)
    }

    // #203: the automatic corrida (Seguimiento's schedule picks origen and catastro, already inside asPlatform for
    // organizacion). no permission is checked: a background job, never a request, reaches here. same candado and
    // active check as lanzar; it runs as the platform too, since the launched coroutine starts its own context.
    // #223: alCrear (the sincronizacion's ultima_corrida and pendientes) is written in the corrida's own transaction:
    // when it fails the corrida is rolled back with it and never runs; the run starts only once both are committed
    suspend fun lanzarAutomatica(
        organizacion: UUID,
        catastro: Catastro,
        anio: Int,
        origen: OrigenCorrida,
        alCrear: suspend (RegistroCorrida) -> Unit
    ): Corrida {
        val creada = crearCorrida(organizacion, anio, origen, alCrear)
        val id = UUID.fromString(creada.id)
        scope.launch { records.asPlatform(organizacion, "srtm:brecha-automatica") { correr(id, anio, catastro) } }
        return corridaDe(creada)
    }

    // lanzar's and lanzarAutomatica's transaction: the candado, abandoning a dead active corrida (409 when one is
    // still alive), the PENDIENTE row with its origen and then alCrear's writes, all or none (#223). the caller
    // launches the run after it returns, so a rolled back corrida never runs
    private suspend fun crearCorrida(
        organizacion: UUID,
        anio: Int,
        origen: OrigenCorrida,
        alCrear: suspend (RegistroCorrida) -> Unit = {}
    ): RegistroCorrida =
        transaccion.executeAndAwait {
            candados.bloquear(Candado.BRECHA, Candados.clave(organizacion, "$anio"))
            val ahora = ahora()
            for (activa in activas(anio)) {
                if (viva(activa, ahora)) throw ConflictException("Ya hay una corrida de la brecha de $anio en curso: espere a que termine")
                actualizar(UUID.fromString(activa.id), abandono(activa, ahora))
            }
            registros
                .create(
                    BRECHA_CORRIDA,
                    RegistroCorrida::class.java,
                    mapOf(
                        "anio" to anio,
                        "estado" to PENDIENTE,
                        "total_predios" to 0,
                        "procesados" to 0,
                        "iniciado" to ahora.toString(),
                        "latido" to ahora.toString(),
                        "origen" to origen.name
                    )
                ).also { alCrear(it) }
        }

    suspend fun lista(anio: Int?): List<Corrida> {
        val corridas =
            registros.all(
                BRECHA_CORRIDA,
                RegistroCorrida::class.java,
                filters = anio?.let { mapOf("anio" to it.toString()) } ?: emptyMap(),
                sort = "created_at",
                descending = true
            )
        if (corridas.none { it.estado in ACTIVAS }) return corridas.map(::corridaDe)
        val ahora = ahora()
        return corridas.map { corridaDe(vencida(it, ahora)) }
    }

    suspend fun get(id: UUID): Corrida {
        val r = registros.get(BRECHA_CORRIDA, RegistroCorrida::class.java, id)
        return corridaDe(if (r.estado in ACTIVAS) vencida(r, ahora()) else r)
    }

    // a corrida still active whose latido is older than LEASE (lanzar's rule, by the db's clock) is abandoned: read, it
    // is failed too, so whoever waits on it (the portal polls) stops and may start another. written when the reader may
    // update corridas; a reader who may only read gets it answered FALLIDA, and the next writer's read or POST stores it
    private suspend fun vencida(
        r: RegistroCorrida,
        ahora: Instant
    ): RegistroCorrida {
        if (r.estado !in ACTIVAS || viva(r, ahora)) return r
        val campos = abandono(r, ahora)
        return try {
            registros.replace(BRECHA_CORRIDA, RegistroCorrida::class.java, UUID.fromString(r.id), campos)
        } catch (_: ForbiddenException) {
            r.copy(estado = FALLIDA, mensaje = campos["mensaje"] as String, terminado = ahora.toString())
        }
    }

    private fun abandono(
        r: RegistroCorrida,
        ahora: Instant
    ) = mapOf(
        "estado" to FALLIDA,
        "mensaje" to "Abandonada: sin latido desde ${r.latido ?: r.iniciado ?: "su inicio"}",
        "terminado" to ahora.toString()
    )

    // the catastro of the caller's organization: a corrida reads its own, never another's (it would measure its padrón
    // against someone else's catastro). an organization without one is refused; its slug is read only when some key is
    // a slug
    private suspend fun catastroDe(usuario: AuthenticatedUser): Catastro =
        catastros.delUsuario(usuario, organizaciones) ?: throw ConflictException(SIN_CATASTRO_CONFIGURADO)

    // the corrida a query reads: the one given (404 unless it is of the year), else the latest TERMINADA of the year
    // (404 when there is none)
    suspend fun corridaDelAnio(
        anio: Int,
        corrida: UUID?
    ): RegistroCorrida {
        if (corrida != null) {
            val r = registros.get(BRECHA_CORRIDA, RegistroCorrida::class.java, corrida)
            if (r.anio != anio) throw NotFoundException("La corrida $corrida no es de $anio")
            return r
        }
        return registros
            .all(BRECHA_CORRIDA, RegistroCorrida::class.java, filters = mapOf("anio" to "$anio", "estado" to TERMINADA), sort = "created_at", descending = true)
            .firstOrNull()
            ?: throw NotFoundException("No hay una corrida terminada de la brecha de $anio")
    }

    // every row of a corrida
    suspend fun filas(corrida: String): List<FilaBrecha> = registros.all(BRECHA_PREDIO, FilaBrecha::class.java, filters = mapOf("corrida" to corrida))

    // GET /{anio}/cambios (ruling #203, amended by controller ruling R13): the corrida and its filas are read as
    // the caller (its own permission on brecha_corrida and brecha_predio). cambio_catastro has no role grant, and
    // `asPlatform` refuses inside a request (ADR-039: relaxing that tripwire needs a new ADR, so in-request code
    // must not escape it) - it is read straight from its physical table instead, scoped to the caller's own
    // organization, the way EnvioACaja reads caja's tables with no user (TablasCore)
    suspend fun cambios(
        anio: Int,
        corrida: UUID?,
        desde: LocalDate?,
        objeto: String?
    ): List<CambioDelPredio> {
        val usuario = currentUser.require()
        val r = corridaDelAnio(anio, corrida)
        val filasDeLaCorrida = filas(r.id!!)
        val resultado = Multas.uit(parametrosTributarios.todos(), LocalDate.of(anio, 1, 1))
        val fila = resultado.valor ?: throw FaltanBrecha(resultado.faltan)
        val uit = fila.valorNumerico ?: throw FaltanBrecha(resultado.faltan)
        val desdeInstante = (desde ?: LocalDate.of(anio, 1, 1)).atStartOfDay(Seguimiento.LIMA).toInstant()
        val cambios = cambiosDe(usuario.organizationId, desdeInstante, objeto)
        return Seguimiento.aviso(cambios, filasDeLaCorrida, uit)
    }

    // cambio_catastro's rows of `organizacion` since `desde`, `objeto` filtered when given: no permission of its
    // own (ruling #203), read directly off its table (never through RecordService), scoped by the organization's
    // own physical table, never another's
    private suspend fun cambiosDe(
        organizacion: UUID,
        desde: Instant,
        objeto: String?
    ): List<RegistroCambio> {
        val t = tablas.de(CAMBIO_CATASTRO, CAMPOS_CAMBIO).firstOrNull { it.organizacion == organizacion } ?: return emptyList()
        val c = { campo: String -> t.columna(campo) }
        var spec =
            db
                .sql(
                    "SELECT ${c("codigo_municipal")} AS codigo_municipal, ${c("objeto")} AS objeto, ${c("operacion")} AS operacion, " +
                        "${c("campos")} AS campos, ${c("en")} AS en, ${c("entrega")} AS entrega FROM ${t.tabla} WHERE ${c("en")} >= :desde" +
                        (if (objeto != null) " AND ${c("objeto")} = :objeto" else "")
                ).bind("desde", desde)
        if (objeto != null) spec = spec.bind("objeto", objeto)
        return spec
            .map { row, _ ->
                RegistroCambio(
                    codigoMunicipal = row.get("codigo_municipal", String::class.java),
                    objeto = row.get("objeto", String::class.java),
                    operacion = row.get("operacion", String::class.java),
                    campos = row.get("campos", String::class.java),
                    // as core's record path writes it: utc, with its seconds
                    en = row.get("en", OffsetDateTime::class.java)?.toInstant()?.toString(),
                    entrega = row.get("entrega", String::class.java)
                )
            }.all()
            .asFlow()
            .toList()
    }

    // a page of a corrida's rows, by categoría, sector and confianza when given, in their orden (Brecha.ordenadas: the
    // padrón's code, then the catastro's; unique, so pages never shuffle)
    suspend fun pagina(
        corrida: String,
        categoria: Categoria?,
        sector: String?,
        confianza: Confianza?,
        page: Int,
        size: Int
    ): PageResponse<PredioBrecha> {
        val filtros =
            buildMap {
                put("corrida", corrida)
                if (categoria != null) put("categoria", categoria.name)
                if (sector != null) put("sector", sector)
                if (confianza != null) put("confianza", confianza.name)
            }
        return registros
            .page(BRECHA_PREDIO, FilaBrecha::class.java, RecordQuery(page = PageRequest.of(page, size), sort = "orden", filters = filtros))
            .map(::predioBrechaDe)
    }

    // in the background, as the caller: catastro's valuations and titulares, the padrón, the measure and its rows. its
    // latido beats after every page of catastro and between the long steps; before writing its rows, after each batch
    // and before ending it reads its estado again and stops if it is no longer EN_CURSO (another POST found it stale and
    // failed it: an abandoned run never overwrites a newer one). what goes wrong leaves it FALLIDA with why, but a
    // catastro without GET /titulares (404, an older one; 403, no read on titular_catastral): the confianza compares no
    // titular, and mensaje says so
    private suspend fun correr(
        id: UUID,
        anio: Int,
        catastro: Catastro
    ) {
        try {
            actualizar(id, mapOf("estado" to EN_CURSO, "latido" to ahora().toString()))
            val parametros = parametrosTributarios.todos()
            val valuaciones = catastro.valuaciones(anio) { latir(id) }
            latir(id, mapOf("version_catastro" to valuaciones.version))
            val (titulares, sinTitulares) =
                try {
                    catastro.titulares { latir(id) } to null
                } catch (e: CatastroException) {
                    if (e.estado != 403 && e.estado != 404) throw e
                    emptyList<TitularCatastral>() to
                        "El catastro no da titulares (GET /api/catastro/titulares respondió ${e.estado}): la confianza no compara titulares"
                }
            val (contexto, sinArbitrios) =
                try {
                    arbitrios.contexto(anio) to null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null to (e.message ?: e.javaClass.simpleName)
                }
            val padron = padron(anio, contexto)
            latir(id)
            Brecha.sinValuaciones(anio, valuaciones.items, padron)?.let {
                fallar(id, it)
                return
            }
            val medicion =
                Brecha.medir(anio, valuaciones.items, padron, parametros, contexto, LocalDate.now(), sinArbitrios, titulares, sinTitulares)
            if (medicion.faltan.isNotEmpty()) {
                fallar(id, "Faltan parámetros del impuesto predial: ${medicion.faltan.joinToString("; ")}")
                return
            }
            latir(id, mapOf("total_predios" to medicion.filas.size))
            var procesados = 0
            for (lote in Brecha.ordenadas(medicion.filas).chunked(LOTE)) {
                exigirEnCurso(id)
                transaccion.executeAndAwait {
                    registros.createAll(BRECHA_PREDIO, FilaBrecha::class.java, lote.map { Records.attributes(it.copy(corrida = id.toString())) })
                }
                procesados += lote.size
                latir(id, mapOf("procesados" to procesados))
            }
            val corrida = exigirEnCurso(id)
            val panel = Brecha.panel(corrida, medicion.filas)
            val kpis = panel.kpis
            val porConfianza = panel.porConfianza
            val fin = ahora().toString()
            actualizar(
                id,
                mapOf(
                    "estado" to TERMINADA,
                    "terminado" to fin,
                    "latido" to fin,
                    "declarado" to kpis.autoavaluoDeclarado,
                    "catastro" to kpis.autoavaluoCatastro,
                    "delta_impuesto" to kpis.deltaImpuesto,
                    "delta_arbitrios" to kpis.deltaArbitrios,
                    "predios_confiables" to porConfianza.getValue(Confianza.CONFIABLE.name).predios,
                    "predios_dudosos" to porConfianza.getValue(Confianza.DUDOSO.name).predios,
                    "predios_sin_levantar" to porConfianza.getValue(Confianza.SIN_LEVANTAR.name).predios,
                    "delta_impuesto_confiable" to porConfianza.getValue(Confianza.CONFIABLE.name).deltaImpuesto,
                    "delta_impuesto_dudoso" to porConfianza.getValue(Confianza.DUDOSO.name).deltaImpuesto,
                    "delta_impuesto_sin_levantar" to porConfianza.getValue(Confianza.SIN_LEVANTAR.name).deltaImpuesto,
                    "mensaje" to medicion.avisos.joinToString("\n").ifEmpty { null }
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Abandonada) {
            log.warn("la corrida de brecha {} dejó de estar EN_CURSO ({}): se detiene sin escribir más", id, e.message)
        } catch (e: Throwable) {
            log.error("la corrida de brecha {} falló", id, e)
            runCatching { fallar(id, e.message ?: e.javaClass.simpleName) }
        }
    }

    // the run stops: its corrida is no longer EN_CURSO
    private class Abandonada(
        estado: String?
    ) : RuntimeException(estado)

    // the corrida as stored, while it is still EN_CURSO
    private suspend fun exigirEnCurso(id: UUID): RegistroCorrida {
        val r = registros.get(BRECHA_CORRIDA, RegistroCorrida::class.java, id)
        if (r.estado != EN_CURSO) throw Abandonada(r.estado)
        return r
    }

    // the database's clock, the one every instance shares (DeterminacionMasiva's leases compare with now() too)
    private suspend fun ahora(): Instant =
        db
            .sql("SELECT now() AS ahora")
            .map { row, _ -> row.get("ahora", OffsetDateTime::class.java)!!.toInstant() }
            .one()
            .awaitSingle()

    // what Brecha.medir reads of srtm: every predio not de baja (the candidates of the cruce) and declaración of the
    // year, with the otras instalaciones of an external padrón (#208, which the dto does not read: one read of the
    // records), and the declaraciones' contribuyentes (their documento and nombre for the confianza, #204); for the
    // arbitrios, when their context was read, the contribuyentes' codes and the inafectaciones
    @Suppress("UNCHECKED_CAST")
    private suspend fun padron(
        anio: Int,
        contexto: ContextoArbitrios?
    ): Padron {
        // #246: a predio de baja (fecha_baja, #219) is no candidate: a migrated code's ficha crosses its derived predios
        // by codigo_origen, not the original, and the original is no row either. the dto does not read fecha_baja
        val predios =
            registros
                .all(PREDIO, Map::class.java)
                .map { it as Map<String, Any?> }
                .filter { it["fecha_baja"]?.toString().isNullOrBlank() }
                .map { Records.read(Predio::class.java, it["id"].toString(), it) }
        val registrosDj = registros.all(DECLARACION, Map::class.java, filters = mapOf("anio" to "$anio")).map { it as Map<String, Any?> }
        val declaraciones = registrosDj.map { Records.read(Declaracion::class.java, it["id"].toString(), it) }
        val obrasDelPadron =
            registrosDj
                .mapNotNull { r ->
                    val monto = r["valor_otras_instalaciones"]?.toString()?.trim()?.toBigDecimalOrNull() ?: return@mapNotNull null
                    if (monto.signum() > 0) r["id"].toString() to monto else null
                }.toMap()
        val contribuyentes = registros.byIds(CONTRIBUYENTE, Contribuyente::class.java, declaraciones.mapNotNull { it.contribuyente })
        if (contexto == null) return Padron(predios, declaraciones, obrasDelPadron = obrasDelPadron, contribuyentes = contribuyentes)
        return Padron(
            predios,
            declaraciones,
            contribuyentes.mapValues { it.value.codigo },
            registros.all(INAFECTACION_ARBITRIO, InafectacionArbitrio::class.java),
            obrasDelPadron,
            contribuyentes
        )
    }

    private suspend fun activas(anio: Int) =
        registros
            .all(BRECHA_CORRIDA, RegistroCorrida::class.java, filters = mapOf("anio" to "$anio"))
            .filter { it.estado in ACTIVAS }

    // its instance still beats: its latido is within LEASE
    private fun viva(
        r: RegistroCorrida,
        ahora: Instant
    ): Boolean {
        val latido = (r.latido ?: r.iniciado)?.let(::instante) ?: return false
        return latido.plus(LEASE).isAfter(ahora)
    }

    // the latido by the database's clock, while the corrida is EN_CURSO
    private suspend fun latir(
        id: UUID,
        campos: Map<String, Any?> = emptyMap()
    ) {
        exigirEnCurso(id)
        actualizar(id, campos + ("latido" to ahora().toString()))
    }

    private suspend fun fallar(
        id: UUID,
        mensaje: String
    ) = actualizar(id, mapOf("estado" to FALLIDA, "mensaje" to mensaje, "terminado" to ahora().toString()))

    private suspend fun actualizar(
        id: UUID,
        campos: Map<String, Any?>
    ) = registros.replace(BRECHA_CORRIDA, RegistroCorrida::class.java, id, campos)

    // the run writes the corrida (its progress) and its rows as the caller: a caller who may not do it is refused first
    private suspend fun exigirPermisos(): AuthenticatedUser {
        val usuario = currentUser.require()
        try {
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(BRECHA_CORRIDA).obj.id)
            currentUser.requirePermission(usuario, Actions.UPDATE, metadata.definitionOf(BRECHA_CORRIDA).obj.id)
            currentUser.requirePermission(usuario, Actions.CREATE, metadata.definitionOf(BRECHA_PREDIO).obj.id)
        } catch (_: ForbiddenException) {
            throw ForbiddenException(
                "Lanzar una corrida de la brecha exige permiso de creación y de edición sobre $BRECHA_CORRIDA, y de creación sobre " +
                    "$BRECHA_PREDIO: la corrida guarda su avance y escribe sus predios"
            )
        }
        return usuario
    }

    @PreDestroy
    fun cerrar() = scope.cancel()

    private companion object {
        val log = LoggerFactory.getLogger(BrechaService::class.java)

        // rows per transaction, and per update of procesados
        const val LOTE = 200

        // a corrida whose latido is older is abandoned (its instance died): generous, the read of catastro beats once
        val LEASE: Duration = Duration.ofMinutes(10)

        // cambiosDe's columns: cambio_catastro's own fields, read off its table directly (R13)
        val CAMPOS_CAMBIO = listOf("codigo_municipal", "objeto", "operacion", "campos", "en", "entrega")
    }
}

// a DATETIME as core reads it back: an instant, with an offset, or a utc local time
internal fun instante(texto: String): Instant? =
    runCatching { Instant.parse(texto) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(texto).toInstant() }.getOrNull()
        ?: runCatching {
            java.time.LocalDateTime
                .parse(texto)
                .toInstant(java.time.ZoneOffset.UTC)
        }.getOrNull()
