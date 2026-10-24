package srtm.rentas

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import srtm.arbitrios.ANULACION_CUOTA_ARBITRIO
import srtm.arbitrios.ArbitriosService
import srtm.arbitrios.AvisoDeArbitrios
import srtm.arbitrios.CUOTA_ARBITRIO
import srtm.arbitrios.Redeterminacion
import srtm.caja.CajaService
import srtm.caja.CambioDeDeuda
import srtm.iguales
import srtm.normativa.ArancelesService
import srtm.pide.ConsultasReniec
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

// #164: why a change of a declaración moved its contribuyentes' debt in caja: the observacion of the altas and the
// motivo of the retiros it causes. a declaración imported without número is the padrón's
internal fun motivoEnCaja(
    antes: Declaracion?,
    despues: Declaracion?
): String {
    val d = despues ?: antes
    val cual = d?.numeroDeclaracion?.let { "n° $it" } ?: "del padrón"
    return when {
        despues?.estado == ANULADA && antes?.estado != ANULADA -> "Anulación de la DJ $cual"
        d?.motivo == RECTIFICACION -> "Rectificatoria $cual"
        else -> "Redeterminación por la DJ $cual"
    }
}

// the srtm's declaración jurada predial: datos del predio, ubicación (the predio's), transferentes, características
// (niveles de construcción, obras complementarias), condóminos (the other declarations of the predio) and otros frentes.
// every write of a declaración, or of its niveles, obras and otros frentes, recomputes what it derives of them and its
// autoavalúo (recalcular, Derivados.kt), then its condominio (Condominio.kt). what a condómino declares of the predio
// and those lists is the whole condominio's: it is replicated in the others (#157). last, its predio's arbitrios of
// the year when what they read changed, and its contribuyentes' ordenes in caja (despuesDeCambiar, #158, #164). reniec:
// as ContribuyenteService's
@Service
class DeclaracionService(
    private val registros: Registros,
    private val listas: Listas,
    private val contribuyentes: ContribuyenteService,
    private val autoavaluos: AutoavaluoSugeridoService,
    private val arbitrios: ArbitriosService,
    private val caja: CajaService,
    private val aranceles: ArancelesService,
    private val reniec: ConsultasReniec = ConsultasReniec(Duration.ZERO)
) {
    suspend fun ficha(id: UUID): DeclaracionJurada {
        val (declaracion, actualizado) = registros.getConFecha(DECLARACION, Declaracion::class.java, id)
        return DeclaracionJurada(
            declaracion = declaracion,
            predio = registros.get(PREDIO, Predio::class.java, UUID.fromString(declaracion.predio)),
            contribuyente = contribuyentes.get(UUID.fromString(declaracion.contribuyente)),
            actualizado = actualizado,
            autoavaluoManual = autoavaluos.manual()
        )
    }

    // the stored records as maps (copiarPadronExterno's way): core checks the caller's object and field permissions on
    // each, as it does for ficha
    suspend fun valuacionOrigen(id: UUID): ValuacionOrigen {
        val declaracion = registros.get(DECLARACION, Map::class.java, id)
        val predio = declaracion["predio"]?.toString()?.let { registros.get(PREDIO, Map::class.java, UUID.fromString(it)) }.orEmpty()
        val niveles =
            listas
                .listar(NIVEL_CONSTRUCCION, Map::class.java, PARENT, id)
                .filter(::conValuacionDelPadron)
                .associate { it["id"].toString() to conValor(it, PADRON_EXTERNO_NIVEL) }
        return ValuacionOrigen(
            predio = conValor(predio, PADRON_EXTERNO_PREDIO),
            declaracion = padronExterno(declaracion),
            niveles = niveles,
            modificada = declaracion[ORIGEN_MODIFICADO] == true
        )
    }

    // a new declaration of a contribuyente, on an existing predio or on one it registers. numbered by the backend,
    // with the srtm's defaults. a predio registered here is removed again if the declaration is refused. desde:
    // another year's declaración to start from (declararDesde)
    suspend fun presentar(
        contribuyente: UUID,
        body: NuevaDeclaracion
    ): DeclaracionJurada {
        contribuyentes.get(contribuyente)
        body.desde?.let { return declararDesde(contribuyente, body, it) }
        val existente = body.predioId?.let { registros.get(PREDIO, Predio::class.java, UUID.fromString(it)) }
        val predio = existente ?: registrarPredio(body.predio ?: throw ValidationException("Falta el predio", "predio_id", "elige un predio o registra uno"))
        val predioId = UUID.fromString(predio.id)
        val declaracion =
            try {
                registrar(conArancel(nueva(delCliente(body.declaracion), contribuyente, predioId)))
            } catch (e: Exception) {
                if (existente == null) registros.delete(PREDIO, predioId)
                throw e
            }
        // stored: its predio stays whatever recalcular does
        despuesDeCambiar(null, recalcular(UUID.fromString(declaracion.id)).despues)
        return ficha(UUID.fromString(declaracion.id))
    }

    // another year's declaración of the same contribuyente and predio as the starting point: its copy (characteristics,
    // lists, autoavalúo, titular's data), with the body's year and medios, an inscripción
    private suspend fun declararDesde(
        contribuyente: UUID,
        body: NuevaDeclaracion,
        desde: String
    ): DeclaracionJurada {
        val id = runCatching { UUID.fromString(desde) }.getOrNull() ?: throw ValidationException("Declaración de partida inválida", "desde", "no es un id")
        val origen = registros.get(DECLARACION, Declaracion::class.java, id)
        val d = body.declaracion
        val anio = d.anio ?: (d.fechaPresentacion ?: LocalDate.now()).year
        // a predio registered with the body is another one
        desdeOtroAnio(origen, contribuyente.toString(), if (body.predio != null) null else body.predioId ?: origen.predio, anio)
        val creada =
            copiar(origen) {
                it.copy(
                    anio = anio,
                    motivo = "INSCRIPCION",
                    medioDeterminacion = d.medioDeterminacion ?: it.medioDeterminacion,
                    medioPresentacion = d.medioPresentacion,
                    fechaPresentacion = d.fechaPresentacion
                )
            }
        despuesDeCambiar(null, creada)
        return ficha(UUID.fromString(creada.id))
    }

    // a declaration from a form that already names both sides (the predio's ficha): numbered like any other
    suspend fun crear(body: Declaracion): Declaracion {
        val contribuyente = body.contribuyente ?: throw ValidationException("Falta el contribuyente", "contribuyente", "es obligatorio")
        val predio = body.predio ?: throw ValidationException("Falta el predio", "predio", "es obligatorio")
        val creada = registrar(conArancel(nueva(delCliente(body), UUID.fromString(contribuyente), UUID.fromString(predio))))
        return recalcular(UUID.fromString(creada.id)).despues.also { despuesDeCambiar(null, it) }
    }

    // what a client sends of a new declaración: the derived fields are recalcular's, and the autoavalúo too while its
    // organization has a catastro. srtm-ui before #156 sends longitud_frente alone: that is its frontis principal
    private suspend fun delCliente(d: Declaracion): Declaracion =
        d.copy(
            frontisPrincipal = d.frontisPrincipal ?: d.longitudFrente,
            areaConstruida = null,
            areaComunConstruida = null,
            longitudFrente = null,
            valorAutoavaluo = if (autoavaluos.manual()) d.valorAutoavaluo else null,
            valorObras = null,
            autoavaluoPorRevisar = null,
            autoavaluoMotivo = null
        )

    // #188: the tramo de vía a client names (id_aranc, blank is none) has a VIGENTE arancel in the DJ's year, or it is a
    // 400 on id_aranc. `stored`: an edit that keeps its id_aranc and year is not asked again (an arancel annulled
    // since leaves the DJ as it is). its valor_m2 is written by recalcular, which follows every write
    private suspend fun conArancel(
        d: Declaracion,
        stored: Declaracion? = null
    ): Declaracion {
        val idAranc = d.idAranc?.trim()?.ifEmpty { null } ?: return d.copy(idAranc = null)
        val anio = d.anio
        if (anio != null && (stored == null || stored.idAranc != idAranc || stored.anio != anio)) aranceles.deLaDeclaracion(idAranc, anio)
        return d.copy(idAranc = idAranc)
    }

    // a declaración that moves to another predio, year or secuencia joins that condominio and leaves its own. only a
    // vigente one changes, and its estado is anular's and its sustitución rectificar's. a rectificatoria stays in
    // its place, and one with an external padrón's valuation in its year. what it derives is recalcular's: the
    // body's areas, longitud de frente and mark are not taken, nor its autoavalúo while there is a catastro. what it
    // declares of the predio reaches its condóminos (replicarPredio); one that moves takes nothing to either group
    suspend fun actualizar(
        id: UUID,
        body: Declaracion
    ): Declaracion {
        val stored = registros.get(DECLARACION, Declaracion::class.java, id)
        modificable(stored)
        val next =
            conArancel(
                body.copy(
                    numeroDeclaracion = stored.numeroDeclaracion,
                    secuenciaUso = secuenciaUso(body.secuenciaUso),
                    motivo = motivoAlEditar(stored.motivo),
                    estado = stored.estado,
                    motivoAnulacion = stored.motivoAnulacion,
                    fechaAnulacion = stored.fechaAnulacion,
                    sustituye = stored.sustituye,
                    fechaSustitucion = stored.fechaSustitucion,
                    frontisPrincipal = frontisAlEditar(body, stored),
                    areaConstruida = stored.areaConstruida,
                    areaComunConstruida = stored.areaComunConstruida,
                    longitudFrente = stored.longitudFrente,
                    valorAutoavaluo = if (autoavaluos.manual()) body.valorAutoavaluo else stored.valorAutoavaluo,
                    valorObras = stored.valorObras,
                    autoavaluoPorRevisar = stored.autoavaluoPorRevisar,
                    autoavaluoMotivo = stored.autoavaluoMotivo
                ),
                stored
            )
        enSuLugar(stored, next)
        if (next.anio != stored.anio) conservaAnio(id)
        val seUne = grupoDe(next) != grupoDe(stored)
        val otros = titulares(next).filter { it.id != id.toString() }
        val grupo = condominioCon(next, otros, seUne)
        val pisaPadron = pisaArancelDePadron(id, next.idAranc)
        registros.replace(DECLARACION, Declaracion::class.java, id, escritura(grupo.first()))
        guardarDerivados(grupo.drop(1), otros)
        if (seUne) recalcularResto(stored)
        marcarOrigenModificado(id, pisaPadron)
        val recalculo = recalcular(id)
        if (!seUne) replicarPredio(next, otros)
        return recalculo.despues.also { despuesDeCambiar(stored, it) }
    }

    // #157: what `origen` declares of the predio (delPredioCambiado), written in each condómino that declares it
    // otherwise, only those fields, and then marked and recomputed as a write of its own
    private suspend fun replicarPredio(
        origen: Declaracion,
        condominos: List<Declaracion>
    ) {
        val manual = autoavaluos.manual()
        val campos = escritura(origen)
        for (otro in condominos) {
            val cambios = delPredioCambiado(origen, otro, manual)
            if (cambios.isEmpty()) continue
            val otroId = UUID.fromString(otro.id)
            val pisaPadron = "id_aranc" in cambios && pisaArancelDePadron(otroId, origen.idAranc)
            registros.replace(DECLARACION, Declaracion::class.java, otroId, campos.filterKeys { it in cambios })
            escrita(otroId, pisaPadron)
        }
    }

    // #188: a write that names a tramo de vía (idAranc) where the stored declaración had none and an external padrón's
    // arancel: recalcular replaces that arancel with Normativa's, and once id_aranc is stored padronExterno no longer
    // sees it. read before the write, for marcarOrigenModificado
    private suspend fun pisaArancelDePadron(
        id: UUID,
        idAranc: String?
    ): Boolean {
        if (idAranc.isNullOrBlank()) return false
        return ARANCEL in padronExterno(registros.get(DECLARACION, Map::class.java, id))
    }

    // a frontis principal the body does not send is the stored one: srtm-ui before #156 never sends it, and 0 clears it.
    // a declaración model/migrar_derivados.py did not reach has none: its longitud_frente is, as on create (delCliente)
    private fun frontisAlEditar(
        body: Declaracion,
        stored: Declaracion
    ) = body.frontisPrincipal ?: stored.frontisPrincipal ?: body.longitudFrente

    // #156: what the declaración derives of its lists (Derivados.totales) and its autoavalúo (catastro's, or the stored
    // one marked with why not, or the user's without a catastro), then its condominio's values, its own and the other
    // titulares'. only what changed is written. a rectificatoria not yet in its place leaves out the one it replaces
    // (registrar's rule). the condominio is recomputed, not checked: a nivel's write is never refused for the % of an
    // imported condominio. what changed is returned to the hooks that follow (Recalculo)
    suspend fun recalcular(id: UUID): Recalculo {
        val stored = resolverArancel(id, registros.get(DECLARACION, Declaracion::class.java, id))
        val totales = Derivados.totales(niveles(id), frentes(id), stored.frontisPrincipal)
        val next = Derivados.aplicar(stored, totales, autoavaluos.deLaDeclaracion(id))
        val otros = if (vigente(stored)) titulares(stored).filter { it.id != stored.id && it.id != stored.sustituye } else emptyList()
        val grupo = condominio(listOf(next) + otros)
        val despues = grupo.first()
        val cambiados = Derivados.cambiados(stored, despues)
        if (cambiados.isNotEmpty()) registros.replace(DECLARACION, Declaracion::class.java, id, Derivados.campos(despues).filterKeys { it in cambiados })
        guardarDerivados(grupo.drop(1), otros)
        return Recalculo(stored, despues)
    }

    // #188: the arancel of a DJ that names its tramo de vía is the valor_m2 of that tramo's VIGENTE arancel in its year,
    // written when it differs, before the autoavalúo reads it. none VIGENTE (not yet verified, or annulled without a
    // replacement): the arancel stored stays, and nothing fails. `d` with the arancel it has now
    private suspend fun resolverArancel(
        id: UUID,
        d: Declaracion
    ): Declaracion {
        val idAranc = d.idAranc?.trim()?.ifEmpty { null } ?: return d
        val valor = aranceles.alRecalcular(idAranc, d.anio ?: return d)?.valorM2 ?: return d
        if (iguales(d.arancel, valor)) return d
        registros.replace(DECLARACION, Map::class.java, id, mapOf(ARANCEL to valor))
        return d.copy(arancel = valor)
    }

    // #195: a declaración's attributes as a write sends them: without its arancel, which the dto carries read-only.
    // only resolverArancel, copiarPadronExterno and an external padrón's import write it
    private fun escritura(d: Declaracion): Map<String, Any?> = Records.attributes(d) - ARANCEL

    // the portal's «Recalcular»: a vigente declaración valued again without a change (catastro answers now)
    suspend fun recalcularDeclaracion(id: UUID): Declaracion = recalcular(existe(id)).also { despuesDeCambiar(it.antes, it.despues) }.despues

    // the external padrón's valuation is of its year: that year's declaración keeps it, another year is declared
    // from it (declararDesde) without it
    private suspend fun conservaAnio(id: UUID) {
        val origen = valuacionOrigen(id)
        if (origen.declaracion.isEmpty() && origen.niveles.isEmpty()) return
        throw ValidationException("La declaración trae la valuación de un padrón externo", "anio", "no cambia de año: declara el año nuevo desde esta DJ")
    }

    // the rectificatoria: a new declaración, numbered, with what `id` declares and its lists, in its place in the
    // condominio. `id` stays SUSTITUIDA, read-only, once the copy has it all: until then it is the year's. the
    // rectificatoria's ficha, as presentar's
    suspend fun rectificar(
        id: UUID,
        body: Rectificacion
    ): DeclaracionJurada {
        val origen = registros.get(DECLARACION, Declaracion::class.java, id)
        val reemplazada = sustituida(origen, LocalDate.now())
        val creada =
            copiar(origen) {
                it.copy(
                    motivo = RECTIFICACION,
                    medioPresentacion = body.medioPresentacion,
                    fechaPresentacion = body.fechaPresentacion,
                    sustituye = id.toString()
                )
            }
        try {
            registros.replace(DECLARACION, Declaracion::class.java, id, escritura(reemplazada))
        } catch (e: Exception) {
            deshacer(creada)
            throw e
        }
        despuesDeCambiar(null, creada)
        return ficha(UUID.fromString(creada.id))
    }

    // the srtm's descargo: the declaración stays, read-only, out of its condominio (the rest is recomputed) and out of
    // the fichas' totales
    suspend fun anular(
        id: UUID,
        body: Anulacion
    ): Declaracion {
        val stored = registros.get(DECLARACION, Declaracion::class.java, id)
        val saved = registros.replace(DECLARACION, Declaracion::class.java, id, escritura(anulada(stored, body.motivoAnulacion, LocalDate.now())))
        recalcularResto(stored)
        despuesDeCambiar(stored, saved)
        return saved
    }

    // only while none of its lists has a row: one with content is annulled instead. the condóminos left are recomputed
    suspend fun borrarDeclaracion(id: UUID) {
        val stored = registros.get(DECLARACION, Declaracion::class.java, id)
        bajaDeRectificacion(stored)?.let { throw ConflictException(it) }
        bajaDeDeclaracion(LISTAS.filter { listas.contar(it, PARENT, id) > 0 })?.let { throw ConflictException(it) }
        registros.delete(DECLARACION, id)
        recalcularResto(stored)
        despuesDeCambiar(stored, null)
    }

    // "datos de los condóminos": another contribuyente's declaración of the same predio, year and secuencia, with what
    // the source declares of the predio and its niveles, obras and otros frentes, each row with the source's fila (one
    // without it takes one now, on both). recomputed once, at the end
    suspend fun agregarCondomino(
        id: UUID,
        body: NuevoCondomino
    ): Declaracion {
        val origen = registros.get(DECLARACION, Declaracion::class.java, id)
        modificable(origen)
        val contribuyente =
            body.contribuyente?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ValidationException("Falta el contribuyente", "contribuyente", "elige un contribuyente")
        contribuyentes.get(contribuyente)
        val creada = registrar(nueva(delPredio(origen).copy(porcentajeCondominio = body.porcentajeCondominio), contribuyente, UUID.fromString(origen.predio)))
        val nuevaId = UUID.fromString(creada.id)
        // and its mark: the copy is as faithful to the padrón as its source. its rows are no edit of it
        copiarPadronExterno(DECLARACION, id, nuevaId, PADRON_EXTERNO_DECLARACION + ORIGEN_MODIFICADO)
        niveles(id).forEach { nivel ->
            val copia = nuevoNivel(nuevaId, nivel.copy(id = null, declaracion = null, fila = conFila(NIVEL_CONSTRUCCION, nivel.id, nivel.fila)))
            copiarPadronExterno(NIVEL_CONSTRUCCION, UUID.fromString(nivel.id), UUID.fromString(copia.id), PADRON_EXTERNO_NIVEL)
        }
        obras(id).forEach { nuevaObra(nuevaId, it.copy(id = null, declaracion = null, fila = conFila(OBRA_COMPLEMENTARIA, it.id, it.fila))) }
        frentes(id).forEach { nuevoFrente(nuevaId, it.copy(id = null, declaracion = null, fila = conFila(OTRO_FRENTE, it.id, it.fila))) }
        return recalcular(nuevaId).despues.also { despuesDeCambiar(null, it) }
    }

    // a source row's fila: its own, or one it takes now (a row from before #156 that model/migrar_derivados.py did
    // not reach), so that its copy is the same row
    private suspend fun conFila(
        objectName: String,
        id: String?,
        fila: String?
    ): String {
        if (fila != null) return fila
        val nueva = UUID.randomUUID().toString()
        registros.replace(objectName, Map::class.java, UUID.fromString(id), mapOf(FILA to nueva))
        return nueva
    }

    // a declaración copied with its lists, the same contribuyente's on the same predio: copiaDe with `cambios` over it,
    // numbered as a new one. its transferentes keep their code, its rows their fila. the external padrón's valuation
    // of the declaración stays in origen (the condómino's copy takes it: copiarPadronExterno); its niveles' goes as
    // nivelesDelPadron says. recomputed once, with all its rows. one that fails halfway is removed with what it got
    private suspend fun copiar(
        origen: Declaracion,
        cambios: (Declaracion) -> Declaracion
    ): Declaracion {
        val copia = cambios(copiaDe(origen))
        val creada = registrar(nueva(copia, UUID.fromString(origen.contribuyente), UUID.fromString(origen.predio)).copy(sustituye = copia.sustituye))
        val id = UUID.fromString(origen.id)
        val nuevaId = UUID.fromString(creada.id)
        try {
            // #188: a DJ that names its tramo de vía carries its arancel to the copy, which recalcular resolves again
            // in the copy's year (and keeps, without a VIGENTE one there)
            if (!origen.idAranc.isNullOrBlank()) copiarPadronExterno(DECLARACION, id, nuevaId, listOf(ARANCEL))
            transferentes(id).forEach { listas.agregar(TRANSFERENTE, Transferente::class.java, PARENT, nuevaId, it.copy(id = null, declaracion = null)) }
            // nuevo*, not agregar*: a copy is no edit, and it has no valuation de origen to mark
            val delPadron = nivelesDelPadron(origen, copia)
            niveles(id).forEach { nivel ->
                val nuevo = nuevoNivel(nuevaId, nivel.copy(id = null, declaracion = null))
                copiarPadronExterno(NIVEL_CONSTRUCCION, UUID.fromString(nivel.id), UUID.fromString(nuevo.id), delPadron)
            }
            // an obra's and a frente's dtos carry all their fields: nothing of a padrón is left behind
            obras(id).forEach { nuevaObra(nuevaId, it.copy(id = null, declaracion = null)) }
            frentes(id).forEach { nuevoFrente(nuevaId, it.copy(id = null, declaracion = null)) }
            return recalcular(nuevaId).despues
        } catch (e: Exception) {
            deshacer(creada)
            throw e
        }
    }

    // #180: what an external padrón brought of each nivel, which its dto does not carry. a rectificatoria (the same
    // year) keeps it all, as a condómino's copy; another year keeps its clasificación (an insumo of the construction,
    // which the autoavalúo sugerido reads), not the figures of the source year
    private fun nivelesDelPadron(
        origen: Declaracion,
        copia: Declaracion
    ) = if (copia.anio == origen.anio) PADRON_EXTERNO_NIVEL else listOf(CLASIFICACION)

    // a copy that failed: its lists, itself, and what its condominio derived from it
    private suspend fun deshacer(creada: Declaracion) {
        val id = UUID.fromString(creada.id)
        for (lista in LISTAS) {
            listas.listar(lista, Map::class.java, PARENT, id).forEach { listas.borrar(lista, UUID.fromString(it["id"].toString())) }
        }
        registros.delete(DECLARACION, id)
        recalcularResto(creada)
    }

    // what an external padrón (Ica) brings and the dtos do not know: copied from the stored record, when it has any,
    // merged over the copy
    private suspend fun copiarPadronExterno(
        objeto: String,
        de: UUID,
        a: UUID,
        campos: List<String>
    ) {
        val valores = conValor(registros.get(objeto, Map::class.java, de), campos)
        if (valores.isNotEmpty()) registros.replace(objeto, Map::class.java, a, valores)
    }

    // a write of a declaración, or of its niveles, obras or frentes, after an external padrón's valuation was imported
    // (its own fields or a nivel's): that valuation no longer describes it. marked once, on the stored record (the dtos
    // do not carry it); the motivo stays the portal's. `quitado`: a nivel with a valuation, already removed
    private suspend fun marcarOrigenModificado(
        id: UUID,
        quitado: Boolean = false
    ) {
        val declaracion = registros.get(DECLARACION, Map::class.java, id)
        if (declaracion[ORIGEN_MODIFICADO] == true) return
        val conOrigen =
            quitado ||
                padronExterno(declaracion).isNotEmpty() ||
                listas.listar(NIVEL_CONSTRUCCION, Map::class.java, PARENT, id).any(::conValuacionDelPadron)
        if (conOrigen) registros.replace(DECLARACION, Map::class.java, id, mapOf(ORIGEN_MODIFICADO to true))
    }

    // a predio of the srtm: its code comes from codigoPredio when blank, its direccion from its ubicación
    suspend fun registrarPredio(body: Predio): Predio =
        conReintento {
            // on every attempt: a code another clerk took meanwhile (same manzana, same lote) is computed again, and
            // one the client sent turns into a 400
            val codigo = body.codigo?.ifBlank { null }?.also { codigoLibre(it) } ?: codigoPredio(body)
            val numero = (registros.highest(PREDIO, "numero_registro")?.toIntOrNull() ?: 0) + 1
            registros.create(
                PREDIO,
                Predio::class.java,
                Records.attributes(body.copy(codigo = codigo, numeroRegistro = numero, direccion = describirUbicacion(body)))
            )
        }

    // code and registration number stay what they are
    suspend fun actualizarPredio(
        id: UUID,
        body: Predio
    ): Predio {
        val stored = registros.get(PREDIO, Predio::class.java, id)
        val next = body.copy(codigo = stored.codigo, numeroRegistro = stored.numeroRegistro, direccion = describirUbicacion(body))
        return registros.replace(PREDIO, Predio::class.java, id, Records.attributes(next))
    }

    // only while no declaración, vigente or annulled, and no cuota de arbitrio or anulación of one names it. one
    // delete: nothing else goes with the predio
    suspend fun borrarPredio(id: UUID) {
        registros.get(PREDIO, Map::class.java, id)
        bajaConDeclaraciones("El predio", listas.contar(DECLARACION, "predio", id))?.let { throw ConflictException(it) }
        val cuotas = listas.contar(CUOTA_ARBITRIO, "predio", id)
        // an anulación names its cuota's predio, so it is counted only when no cuota is
        val anulaciones = if (cuotas > 0) 0 else listas.contar(ANULACION_CUOTA_ARBITRIO, "predio", id)
        bajaConCuotas("El predio", cuotas, anulaciones)?.let { throw ConflictException(it) }
        registros.delete(PREDIO, id)
    }

    // the lists of the declaration

    suspend fun transferentes(id: UUID) = listas.listar(TRANSFERENTE, Transferente::class.java, PARENT, id)

    // coded by the backend under their declaración, named by razón social (RUC) or by names
    suspend fun agregarTransferente(
        id: UUID,
        body: Transferente
    ): Transferente {
        val codigo = siguienteCodigoLista(transferentes(existe(id)).map { it.codigo })
        return listas.agregar(
            TRANSFERENTE,
            Transferente::class.java,
            PARENT,
            id,
            transferente(body, null).copy(codigo = codigo, estado = body.estado ?: Listas.ACTIVO)
        )
    }

    suspend fun actualizarTransferente(
        id: UUID,
        body: Transferente
    ): Transferente {
        deVigente(TRANSFERENTE, id)
        val stored = registros.get(TRANSFERENTE, Transferente::class.java, id)
        return listas.cambiar(TRANSFERENTE, Transferente::class.java, PARENT, id, transferente(body, stored).copy(codigo = stored.codigo))
    }

    private fun transferente(
        body: Transferente,
        stored: Transferente?
    ): Transferente {
        validarNombre(body.tipoDocumento, body.razonSocial, body.nombres)
        reniec.respaldar(body.persona(), stored?.persona())
        return body.copy(fuenteInformacion = body.fuenteInformacion ?: FUENTE_MANUAL)
    }

    suspend fun niveles(id: UUID) = listas.listar(NIVEL_CONSTRUCCION, NivelConstruccion::class.java, PARENT, id)

    // a row added or changed by the portal: its fila is the backend's, and its declaración is recomputed. its
    // condóminos get the same row (replicar)
    suspend fun agregarNivel(
        id: UUID,
        body: NivelConstruccion
    ) = nuevoNivel(id, body.copy(fila = null)).also { nivel ->
        escrita(id)
        replicar(NIVEL_CONSTRUCCION, id, null) { otra, _ -> nuevoNivel(otra, nivel.copy(id = null, declaracion = null)) }
    }

    // a copy's row keeps the fila it brings; a new one gets its own
    private suspend fun nuevoNivel(
        id: UUID,
        body: NivelConstruccion
    ) = listas.agregar(
        NIVEL_CONSTRUCCION,
        NivelConstruccion::class.java,
        PARENT,
        existe(id),
        body.copy(estado = body.estado ?: Listas.ACTIVO, fila = body.fila ?: UUID.randomUUID().toString())
    )

    suspend fun actualizarNivel(
        id: UUID,
        body: NivelConstruccion
    ): NivelConstruccion {
        val fila = deVigente(NIVEL_CONSTRUCCION, id)
        val nivel = body.copy(id = null, declaracion = null, fila = fila.fila)
        val saved = listas.cambiar(NIVEL_CONSTRUCCION, NivelConstruccion::class.java, PARENT, id, nivel)
        fila.declaracion?.let {
            escrita(it)
            replicar(NIVEL_CONSTRUCCION, it, fila) { otra, pareja ->
                if (pareja == null) nuevoNivel(otra, nivel) else listas.cambiar(NIVEL_CONSTRUCCION, NivelConstruccion::class.java, PARENT, pareja, nivel)
            }
        }
        return saved
    }

    suspend fun obras(id: UUID) = listas.listar(OBRA_COMPLEMENTARIA, ObraComplementaria::class.java, PARENT, id)

    suspend fun agregarObra(
        id: UUID,
        body: ObraComplementaria
    ) = nuevaObra(id, body.copy(fila = null)).also { obra ->
        escrita(id)
        replicar(OBRA_COMPLEMENTARIA, id, null) { otra, _ -> nuevaObra(otra, obra.copy(id = null, declaracion = null)) }
    }

    private suspend fun nuevaObra(
        id: UUID,
        body: ObraComplementaria
    ) = listas.agregar(
        OBRA_COMPLEMENTARIA,
        ObraComplementaria::class.java,
        PARENT,
        existe(id),
        obra(body).copy(fila = body.fila ?: UUID.randomUUID().toString())
    )

    suspend fun actualizarObra(
        id: UUID,
        body: ObraComplementaria
    ): ObraComplementaria {
        val fila = deVigente(OBRA_COMPLEMENTARIA, id)
        val obra = obra(body).copy(id = null, declaracion = null, fila = fila.fila)
        val saved = listas.cambiar(OBRA_COMPLEMENTARIA, ObraComplementaria::class.java, PARENT, id, obra)
        fila.declaracion?.let {
            escrita(it)
            replicar(OBRA_COMPLEMENTARIA, it, fila) { otra, pareja ->
                if (pareja == null) nuevaObra(otra, obra) else listas.cambiar(OBRA_COMPLEMENTARIA, ObraComplementaria::class.java, PARENT, pareja, obra)
            }
        }
        return saved
    }

    suspend fun frentes(id: UUID) = listas.listar(OTRO_FRENTE, OtroFrente::class.java, PARENT, id)

    suspend fun agregarFrente(
        id: UUID,
        body: OtroFrente
    ) = nuevoFrente(id, body.copy(fila = null)).also { frente ->
        escrita(id)
        replicar(OTRO_FRENTE, id, null) { otra, _ -> nuevoFrente(otra, frente.copy(id = null, declaracion = null)) }
    }

    private suspend fun nuevoFrente(
        id: UUID,
        body: OtroFrente
    ) = listas.agregar(
        OTRO_FRENTE,
        OtroFrente::class.java,
        PARENT,
        existe(id),
        body.copy(estado = body.estado ?: Listas.ACTIVO, fila = body.fila ?: UUID.randomUUID().toString())
    )

    suspend fun actualizarFrente(
        id: UUID,
        body: OtroFrente
    ): OtroFrente {
        val fila = deVigente(OTRO_FRENTE, id)
        val frente = body.copy(id = null, declaracion = null, fila = fila.fila)
        val saved = listas.cambiar(OTRO_FRENTE, OtroFrente::class.java, PARENT, id, frente)
        fila.declaracion?.let {
            escrita(it)
            replicar(OTRO_FRENTE, it, fila) { otra, pareja ->
                if (pareja == null) nuevoFrente(otra, frente) else listas.cambiar(OTRO_FRENTE, OtroFrente::class.java, PARENT, pareja, frente)
            }
        }
        return saved
    }

    // a transferente is no part of the valuation, nor of the condominio. a nivel removed with its own is read before
    // it goes. the same row goes from the condóminos (replicar); one of them without it has nothing to remove
    suspend fun borrar(
        objectName: String,
        id: UUID
    ) {
        val fila = deVigente(objectName, id)
        listas.borrar(objectName, id)
        if (objectName !in VALUADAS) return
        val declaracion = fila.declaracion ?: return
        escrita(declaracion, conValorDeOrigen(objectName, fila.registro))
        replicar(objectName, declaracion, fila, borra = true) { _, pareja -> pareja?.let { listas.borrar(objectName, it) } }
    }

    private fun conValorDeOrigen(
        objectName: String,
        registro: Map<*, *>
    ) = objectName == NIVEL_CONSTRUCCION && conValuacionDelPadron(registro)

    // #157: a row written in `declaracion` is the same row in the other vigente declaraciones of its condominio, so
    // each one gets the same write: `escribir` makes it there, on its pareja (null: it has none). the group is read
    // once. a replica goes through nuevo* and Listas, never back here: it is not replicated again. each declaración
    // written is then marked (marcarOrigenModificado) and recomputed, its condominio with its own %, and the
    // condominio's ordenes in caja are synced once, at the end (#164). `fila`: the row changed or removed (null: one
    // added, a new row everywhere)
    private suspend fun replicar(
        objectName: String,
        declaracion: UUID,
        fila: Fila?,
        borra: Boolean = false,
        escribir: suspend (otra: UUID, pareja: UUID?) -> Unit
    ) {
        for (otra in condominos(declaracion)) {
            val pareja = fila?.let { pareja(objectName, otra, it) }
            if (pareja is Pareja.Dudosa) {
                log.warn("{} {}: {} filas de su llave sin fila en la declaración {}, no se replica", objectName, fila.fila, pareja.cuantas, otra)
                continue
            }
            val registro = (pareja as? Pareja.Esta)?.registro
            if (borra && registro == null) continue
            escribir(otra, registro?.let { UUID.fromString(it["id"].toString()) })
            escrita(otra, borra && conValorDeOrigen(objectName, registro.orEmpty()))
        }
        caja.despuesDeEscribir { cambioDeDeuda(null, registros.get(DECLARACION, Declaracion::class.java, declaracion)) }
    }

    // the same row in `otra`: the one with its fila; else, for rows from before #156 that model/migrar_derivados.py
    // did not reach, the one without a fila of the same natural key (the migration's), while there is only one. two
    // or more of that key: which one is not known (Dudosa)
    private suspend fun pareja(
        objectName: String,
        otra: UUID,
        escrita: Fila
    ): Pareja {
        val filas = listas.listar(objectName, Map::class.java, PARENT, otra)
        filas.firstOrNull { it[FILA]?.toString() == escrita.fila }?.let { return Pareja.Esta(it) }
        val llave = llave(objectName, escrita.registro)
        val sueltas = filas.filter { it[FILA]?.toString().isNullOrBlank() && llave(objectName, it) == llave }
        return when (sueltas.size) {
            0 -> Pareja.Ninguna
            1 -> Pareja.Esta(sueltas.single())
            else -> Pareja.Dudosa(sueltas.size)
        }
    }

    // a row's natural key (model/migrar_derivados.py's LLAVES), trimmed and in capitals
    private fun llave(
        objectName: String,
        registro: Map<*, *>
    ) = LLAVES.getValue(objectName).map { campo ->
        registro[campo]
            ?.toString()
            ?.trim()
            ?.uppercase()
            .orEmpty()
    }

    // the other vigente declaraciones of `id`'s condominio. a rectificatoria not yet in its place leaves out the one
    // it replaces (recalcular's rule)
    private suspend fun condominos(id: UUID): List<UUID> {
        val d = registros.get(DECLARACION, Declaracion::class.java, id)
        return titulares(d).filter { it.id != d.id && it.id != d.sustituye }.map { UUID.fromString(it.id) }
    }

    // a row of the valuation written by the portal: the external padrón's valuation is marked, the declaración
    // recomputed
    private suspend fun escrita(
        declaracion: UUID,
        quitado: Boolean = false
    ) {
        marcarOrigenModificado(declaracion, quitado)
        // its caller syncs the condominio with caja once, at the end (replicar, actualizar)
        recalcular(declaracion).let { despuesDeCambiar(it.antes, it.despues, conCaja = false) }
    }

    // #158: a write that changes what the arbitrios read (Redeterminacion.cambia; null: a declaración created or
    // deleted) redetermines the year of the predio it was on and of the one it is on (ArbitriosService.redeterminarPredio),
    // as the caller. what it did goes to the response's header (AvisoDeArbitrios): a redeterminación that cannot be
    // done is said there, and the write of the declaración stands. then, with or without a change of the arbitrios (the
    // predial reads the valor afecto), the ordenes in caja of those contribuyentes (#164, aCaja). conCaja false: the
    // write of a replica, whose caller syncs the condominio once at the end
    private suspend fun despuesDeCambiar(
        antes: Declaracion?,
        despues: Declaracion?,
        conCaja: Boolean = true
    ) {
        if (Redeterminacion.cambia(antes, despues)) {
            val numero = (despues ?: antes)?.numeroDeclaracion
            val observacion = "Redeterminación por cambio de la DJ " + (numero?.let { "n° $it" } ?: "del padrón")
            listOfNotNull(antes, despues)
                .mapNotNull { d -> d.anio?.let { anio -> d.predio?.let { UUID.fromString(it) to anio } } }
                .distinct()
                .forEach { (predio, anio) -> arbitrios.redeterminarPredio(predio, anio, observacion)?.let { AvisoDeArbitrios.anotar(it) } }
        }
        if (conCaja) aCaja(antes, despues)
    }

    // #164: the contribuyentes whose debt of the year a change of these declaraciones may have moved, synced with caja
    // (CajaService.despuesDeEscribir, which never throws): theirs, and those of every declaración of their predio's year
    // that the arbitrios read (the annulled ones too: they may be charged the months before; a sustituida is its
    // rectificatoria's). read only when the organization has a caja
    private suspend fun aCaja(
        antes: Declaracion?,
        despues: Declaracion?
    ) = caja.despuesDeEscribir { cambioDeDeuda(antes, despues) }

    private suspend fun cambioDeDeuda(
        antes: Declaracion?,
        despues: Declaracion?
    ): CambioDeDeuda {
        val cambiadas = listOfNotNull(antes, despues)
        val porAnio = mutableMapOf<Int, MutableSet<UUID>>()
        for (d in cambiadas) {
            val anio = d.anio ?: continue
            d.contribuyente?.let { porAnio.getOrPut(anio) { mutableSetOf() } += UUID.fromString(it) }
        }
        for ((predio, anio) in cambiadas.mapNotNull { d -> d.anio?.let { anio -> d.predio?.let { it to anio } } }.distinct()) {
            registros
                .all(DECLARACION, Declaracion::class.java, filters = mapOf("predio" to predio, "anio" to "$anio"))
                .filter { it.estado != SUSTITUIDA }
                .mapNotNull { it.contribuyente }
                .forEach { porAnio.getOrPut(anio) { mutableSetOf() } += UUID.fromString(it) }
        }
        return CambioDeDeuda(motivoEnCaja(antes, despues), porAnio)
    }

    // the srtm's defaults. condición and % are the condominio's (registrar)
    private fun nueva(
        body: Declaracion,
        contribuyente: UUID,
        predio: UUID
    ): Declaracion {
        val presentacion = body.fechaPresentacion ?: LocalDate.now()
        return body.copy(
            contribuyente = contribuyente.toString(),
            predio = predio.toString(),
            anio = body.anio ?: presentacion.year,
            secuenciaUso = secuenciaUso(body.secuenciaUso),
            motivo = body.motivo ?: "INSCRIPCION",
            medioDeterminacion = body.medioDeterminacion ?: "DECLARACION JURADA",
            medioPresentacion = body.medioPresentacion ?: "FISICO",
            fechaPresentacion = presentacion,
            estado = VIGENTE,
            motivoAnulacion = null,
            fechaAnulacion = null,
            sustituye = null,
            fechaSustitucion = null
        )
    }

    // a new titular joins its predio's condominio: checked and derived before it is stored, the others after. a
    // rectificatoria takes the place of the one it replaces (vigente until rectificar marks it): that one is not
    // counted, and the others keep their % as in an edit
    private suspend fun registrar(declaracion: Declaracion): Declaracion {
        val otros = titulares(declaracion).filter { it.id != declaracion.sustituye }
        val grupo = condominioCon(declaracion, otros, seUne = declaracion.sustituye == null)
        val creada = numerar(grupo.first())
        guardarDerivados(grupo.drop(1), otros)
        return creada
    }

    // the declaraciones of a condominio: same predio, year and secuencia de uso. the secuencia is compared here, not in
    // the query: one stored as "1" before model/normalizar_padron.py ran is the same as "001". an annulled or
    // sustituida one is out
    private suspend fun titulares(d: Declaracion): List<Declaracion> {
        val (predio, anio, secuencia) = grupoDe(d)
        if (predio == null || anio == null || secuencia == null) return emptyList()
        return registros
            .all(DECLARACION, Declaracion::class.java, filters = mapOf("predio" to predio, "anio" to anio.toString()))
            .filter { grupoDe(it) == grupoDe(d) && vigente(it) }
    }

    // what is left of a condominio a declaración went away from
    private suspend fun recalcularResto(fuera: Declaracion) {
        val resto = titulares(fuera).filter { it.id != fuera.id }
        guardarDerivados(condominio(resto), resto)
    }

    // only the derived fields, and only where they changed from what is stored
    private suspend fun guardarDerivados(
        grupo: List<Declaracion>,
        stored: List<Declaracion>
    ) {
        val antes = stored.associateBy { it.id }
        for (d in grupo) {
            if (derivados(d) == antes[d.id]?.let(::derivados)) continue
            registros.replace(
                DECLARACION,
                Declaracion::class.java,
                UUID.fromString(d.id),
                mapOf(
                    "condicion_propiedad" to d.condicionPropiedad,
                    "porcentaje_condominio" to d.porcentajeCondominio,
                    "valor_condominio" to d.valorCondominio,
                    "valor_afecto" to d.valorAfecto
                )
            )
        }
    }

    // compared by value: 100 and 100.00 are the same %
    private fun derivados(d: Declaracion) =
        listOf(d.condicionPropiedad, d.porcentajeCondominio?.stripTrailingZeros(), d.valorCondominio?.stripTrailingZeros(), d.valorAfecto?.stripTrailingZeros())

    // numero_declaracion is unique: two clerks presenting at once may pick the same one. read it again and retry
    private suspend fun numerar(declaracion: Declaracion): Declaracion =
        conReintento {
            val numero = (registros.highest(DECLARACION, "numero_declaracion")?.toIntOrNull() ?: 0) + 1
            registros.create(DECLARACION, Declaracion::class.java, escritura(declaracion.copy(numeroDeclaracion = numero)))
        }

    // a numbered insert: when the number was taken meanwhile (the database refuses the duplicate), number it again
    private suspend fun <T> conReintento(insert: suspend () -> T): T {
        repeat(ATTEMPTS - 1) {
            try {
                return insert()
            } catch (_: DataIntegrityViolationException) {
                // taken meanwhile
            }
        }
        return insert()
    }

    // the next of its sector and manzana (SS-MM-NNNN) when it has both; else the municipal code of its catastro lote,
    // unless a predio already has it; else the portal's own series (P-NNNNNN)
    private suspend fun codigoPredio(body: Predio): String {
        val sector = body.sectorCatastral?.ifBlank { null }
        val manzana = body.manzanaCatastral?.ifBlank { null }
        if (sector != null && manzana != null) {
            val prefijo = prefijoPredio(sector, manzana)
            return siguienteCodigoPredio(prefijo, registros.highest(PREDIO, "codigo", prefijo))
        }
        return codigoDelLote(body.codigoCpu) ?: siguienteCodigoPropio(registros.highest(PREDIO, "codigo", PREFIJO_PROPIO))
    }

    private suspend fun codigoDelLote(codigoCpu: String?): String? {
        val cpu = codigoCpu?.trim()?.ifEmpty { null } ?: return null
        val codigo =
            registros
                .all(CATASTRO_FISCAL, CatastroFiscal::class.java, filters = mapOf("codigo_cpu" to cpu))
                .firstOrNull()
                ?.codigoPredioMunicipal
                ?.trim()
                ?.ifEmpty { null } ?: return null
        return codigo.takeIf { registros.all(PREDIO, Predio::class.java, filters = mapOf("codigo" to it)).isEmpty() }
    }

    // codigo is unique, and the database would answer a duplicate with a 500 after the retries: say it on the field
    private suspend fun codigoLibre(codigo: String) {
        val otro = registros.all(PREDIO, Predio::class.java, filters = mapOf("codigo" to codigo)).firstOrNull() ?: return
        throw ValidationException("Código repetido", "codigo", listOfNotNull("ya es de otro predio", otro.direccion?.ifBlank { null }).joinToString(": "))
    }

    // a list is added to only while its declaración is vigente
    private suspend fun existe(id: UUID): UUID {
        modificable(registros.get(DECLARACION, Declaracion::class.java, id))
        return id
    }

    // and a row of it changed or removed: its declaración, the row's fila (one from before #156 takes its own) and
    // the row as stored
    private suspend fun deVigente(
        objectName: String,
        id: UUID
    ): Fila {
        val row = registros.get(objectName, Map::class.java, id)
        val fila = row[FILA]?.toString()?.ifBlank { null } ?: UUID.randomUUID().toString()
        val declaracion = row[PARENT]?.toString()?.let(UUID::fromString) ?: return Fila(null, fila, row)
        modificable(registros.get(DECLARACION, Declaracion::class.java, declaracion))
        return Fila(declaracion, fila, row)
    }

    private data class Fila(
        val declaracion: UUID?,
        val fila: String,
        val registro: Map<*, *>
    )

    // a written row's pareja in a condómino's declaración (pareja)
    private sealed interface Pareja {
        data class Esta(
            val registro: Map<*, *>
        ) : Pareja

        data object Ninguna : Pareja

        data class Dudosa(
            val cuantas: Int
        ) : Pareja
    }

    private fun obra(body: ObraComplementaria) = body.copy(estado = body.estado ?: Listas.ACTIVO, totalMetrado = totalMetrado(body))

    private companion object {
        const val PARENT = "declaracion"
        const val FILA = "fila"
        const val ATTEMPTS = 3
        const val ARANCEL = "arancel"
        val LISTAS = listOf(TRANSFERENTE, NIVEL_CONSTRUCCION, OBRA_COMPLEMENTARIA, OTRO_FRENTE)

        // the lists a write of which marks an external padrón's valuation as modified (marcarOrigenModificado)
        val VALUADAS = listOf(NIVEL_CONSTRUCCION, OBRA_COMPLEMENTARIA, OTRO_FRENTE)

        // their natural key, to pair a row without fila (model/migrar_derivados.py's LLAVES)
        val LLAVES =
            mapOf(
                NIVEL_CONSTRUCCION to listOf("tipo_nivel", "numero_piso"),
                OBRA_COMPLEMENTARIA to listOf("tipo_obra", "numero_piso", "categoria"),
                OTRO_FRENTE to listOf("via", "numero")
            )

        val log = LoggerFactory.getLogger(DeclaracionService::class.java)
    }
}
