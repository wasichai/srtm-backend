package srtm.rentas

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import srtm.pide.ConsultasReniec
import wasichai.core.common.ConflictException
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

// the srtm's "registro de contribuyente": the contribuyente itself (datos de la declaración, identificación,
// datos personales) and its four lists (domicilios, relacionados, medios de contacto, sustento). reniec: the recent
// consultas that back a fuente PIDE RENIEC (srtm.pide); built by hand (unit tests), none, so PIDE RENIEC is refused
@Service
class ContribuyenteService(
    private val registros: Registros,
    private val listas: Listas,
    private val reniec: ConsultasReniec = ConsultasReniec(Duration.ZERO)
) {
    suspend fun buscar(
        q: String?,
        page: Int?,
        size: Int?
    ): PageResponse<Contribuyente> =
        registros.page(
            CONTRIBUYENTE,
            Contribuyente::class.java,
            RecordQuery(page = PageRequest.of(page, size), sort = "nombre_completo", search = q?.trim()?.ifBlank { null })
        )

    suspend fun get(id: UUID): Contribuyente = registros.get(CONTRIBUYENTE, Contribuyente::class.java, id)

    // inscripción: the backend numbers it and dates it; the form's choices are kept, with the srtm's defaults
    suspend fun inscribir(body: Contribuyente): Contribuyente {
        documentoLibre(body, except = null)
        reniec.respaldar(body.persona(), anterior = null)
        repeat(CODE_ATTEMPTS - 1) {
            try {
                return registros.create(CONTRIBUYENTE, Contribuyente::class.java, Records.attributes(nuevo(body)))
            } catch (_: DataIntegrityViolationException) {
                // another clerk took the same code between our read and our insert (codigo is unique): read again.
                // core lets the database refuse a duplicate, it does not check first
            }
        }
        return registros.create(CONTRIBUYENTE, Contribuyente::class.java, Records.attributes(nuevo(body)))
    }

    suspend fun actualizar(
        id: UUID,
        body: Contribuyente
    ): Contribuyente {
        val stored = get(id)
        documentoLibre(body, except = id)
        reniec.respaldar(body.persona(), stored.persona())
        val next =
            derivar(body, stored).copy(
                // the backend's, and the fiscal domicilio's (kept in step by the domicilios below)
                codigo = stored.codigo,
                motivo = motivoAlEditar(stored.motivo),
                numeroDeclaracion = stored.numeroDeclaracion,
                fechaRegistro = stored.fechaRegistro,
                domicilioFiscal = stored.domicilioFiscal,
                domicilioDistrito = stored.domicilioDistrito,
                domicilioProvincia = stored.domicilioProvincia,
                domicilioDepartamento = stored.domicilioDepartamento
            )
        return registros.replace(CONTRIBUYENTE, Contribuyente::class.java, id, Records.attributes(next))
    }

    // only while it declares nothing, vigente or annulled. its four lists go with it, straight: borrarDomicilio would
    // keep the last fiscal domicilio in step with a contribuyente that is going away
    suspend fun borrarContribuyente(id: UUID) {
        get(id)
        bajaConDeclaraciones("El contribuyente", listas.contar(DECLARACION, PARENT, id))?.let { throw ConflictException(it) }
        for (lista in listOf(DOMICILIO, RELACIONADO, MEDIO_CONTACTO, SUSTENTO)) {
            hijos(lista, Map::class.java, id).forEach { registros.delete(lista, UUID.fromString(it["id"].toString())) }
        }
        registros.delete(CONTRIBUYENTE, id)
    }

    // domicilios: coded under their contribuyente, the description always rebuilt; an active fiscal one is copied to
    // the contribuyente

    suspend fun domicilios(contribuyente: UUID): List<Domicilio> = hijos(DOMICILIO, Domicilio::class.java, contribuyente)

    suspend fun agregarDomicilio(
        contribuyente: UUID,
        body: Domicilio
    ): Domicilio {
        get(contribuyente)
        val otros = domicilios(contribuyente)
        val nuevo = domicilio(body).copy(codigo = siguienteCodigoLista(otros.map { it.codigo }))
        exigirFiscal(otros, antes = null, despues = nuevo)
        val saved = agregar(DOMICILIO, Domicilio::class.java, contribuyente, nuevo)
        sincronizarFiscal(contribuyente)
        return saved
    }

    suspend fun actualizarDomicilio(
        id: UUID,
        body: Domicilio
    ): Domicilio {
        val stored = registros.get(DOMICILIO, Domicilio::class.java, id)
        val next = domicilio(body).copy(codigo = stored.codigo)
        exigirFiscal(otrosDomicilios(stored), antes = stored, despues = next)
        val saved = cambiar(DOMICILIO, Domicilio::class.java, id, next)
        saved.contribuyente?.let { sincronizarFiscal(UUID.fromString(it)) }
        return saved
    }

    suspend fun borrarDomicilio(id: UUID) {
        val stored = registros.get(DOMICILIO, Domicilio::class.java, id)
        exigirFiscal(otrosDomicilios(stored), antes = stored, despues = null)
        registros.delete(DOMICILIO, id)
        stored.contribuyente?.let { sincronizarFiscal(UUID.fromString(it)) }
    }

    suspend fun relacionados(contribuyente: UUID): List<Relacionado> = hijos(RELACIONADO, Relacionado::class.java, contribuyente)

    // relacionados: coded by the backend under their contribuyente, named by razón social (RUC) or by names

    suspend fun agregarRelacionado(
        contribuyente: UUID,
        body: Relacionado
    ): Relacionado {
        get(contribuyente)
        val codigo = siguienteCodigoLista(relacionados(contribuyente).map { it.codigo })
        return agregar(RELACIONADO, Relacionado::class.java, contribuyente, relacionado(body, null).copy(codigo = codigo, estado = body.estado ?: ACTIVO))
    }

    suspend fun actualizarRelacionado(
        id: UUID,
        body: Relacionado
    ): Relacionado {
        val stored = registros.get(RELACIONADO, Relacionado::class.java, id)
        return cambiar(RELACIONADO, Relacionado::class.java, id, relacionado(body, stored).copy(codigo = stored.codigo))
    }

    private fun relacionado(
        body: Relacionado,
        stored: Relacionado?
    ): Relacionado {
        validarNombre(body.tipoDocumento, body.razonSocial, body.nombres)
        reniec.respaldar(body.persona(), stored?.persona())
        return body.copy(fuenteInformacion = body.fuenteInformacion ?: FUENTE_MANUAL)
    }

    // medios de contacto and documentos sustento: coded by the backend under their contribuyente, like the rest

    suspend fun mediosContacto(contribuyente: UUID): List<MedioContacto> = hijos(MEDIO_CONTACTO, MedioContacto::class.java, contribuyente)

    suspend fun agregarMedioContacto(
        contribuyente: UUID,
        body: MedioContacto
    ): MedioContacto {
        get(contribuyente)
        val codigo = siguienteCodigoLista(mediosContacto(contribuyente).map { it.codigo })
        return agregar(MEDIO_CONTACTO, MedioContacto::class.java, contribuyente, body.copy(codigo = codigo, estado = body.estado ?: ACTIVO))
    }

    suspend fun actualizarMedioContacto(
        id: UUID,
        body: MedioContacto
    ): MedioContacto {
        val stored = registros.get(MEDIO_CONTACTO, MedioContacto::class.java, id)
        return cambiar(MEDIO_CONTACTO, MedioContacto::class.java, id, body.copy(codigo = stored.codigo))
    }

    suspend fun sustentos(contribuyente: UUID): List<Sustento> = hijos(SUSTENTO, Sustento::class.java, contribuyente)

    suspend fun agregarSustento(
        contribuyente: UUID,
        body: Sustento
    ): Sustento {
        get(contribuyente)
        val codigo = siguienteCodigoLista(sustentos(contribuyente).map { it.codigo })
        return agregar(SUSTENTO, Sustento::class.java, contribuyente, body.copy(codigo = codigo, estado = body.estado ?: ACTIVO))
    }

    suspend fun actualizarSustento(
        id: UUID,
        body: Sustento
    ): Sustento {
        val stored = registros.get(SUSTENTO, Sustento::class.java, id)
        return cambiar(SUSTENTO, Sustento::class.java, id, body.copy(codigo = stored.codigo))
    }

    suspend fun borrar(
        objectName: String,
        id: UUID
    ) = listas.borrar(objectName, id)

    // the number its tipo takes (none for SIN DOCUMENTO), and nobody else's: numero_documento is unique, and the
    // database would answer a duplicate with a 500. both said on the field instead
    private suspend fun documentoLibre(
        body: Contribuyente,
        except: UUID?
    ) {
        errorDocumento(body.tipoDocumento, body.numeroDocumento)?.let { throw ValidationException("Documento inválido", "numero_documento", it) }
        val numero = numeroDocumento(body.tipoDocumento, body.numeroDocumento) ?: return
        val otro =
            registros
                .all(CONTRIBUYENTE, Contribuyente::class.java, filters = mapOf("numero_documento" to numero))
                .firstOrNull { it.id != except?.toString() }
                ?: return
        throw ValidationException(
            "Documento repetido",
            "numero_documento",
            "ya está inscrito: ${otro.codigo ?: ""} ${otro.nombreCompleto ?: ""}".replace("  ", " ").trim()
        )
    }

    private suspend fun nuevo(body: Contribuyente): Contribuyente {
        val hoy = LocalDate.now()
        return derivar(body).copy(
            codigo = siguienteCodigo(registros.highest(CONTRIBUYENTE, "codigo")),
            numeroDeclaracion = (registros.highest(CONTRIBUYENTE, "numero_declaracion")?.toIntOrNull() ?: 0) + 1,
            fechaRegistro = hoy,
            motivo = body.motivo ?: "INSCRIPCION",
            medioDeterminacion = body.medioDeterminacion ?: "DECLARACION JURADA",
            medioPresentacion = body.medioPresentacion ?: "FISICO",
            fechaPresentacion = body.fechaPresentacion ?: hoy,
            fuenteInformacion = body.fuenteInformacion ?: "MANUAL",
            // a new contribuyente has no domicilio yet: the fiscal one fills these
            domicilioFiscal = null,
            domicilioDistrito = null,
            domicilioProvincia = null,
            domicilioDepartamento = null
        )
    }

    private fun derivar(
        body: Contribuyente,
        stored: Contribuyente? = null
    ): Contribuyente =
        body.copy(
            tipoPersona = tipoPersona(body.tipoContribuyente) ?: body.tipoPersona,
            numeroDocumento = numeroDocumento(body.tipoDocumento, body.numeroDocumento, stored),
            nombreCompleto = nombreCompleto(body)
        )

    private fun domicilio(body: Domicilio): Domicilio = body.copy(estado = body.estado ?: ACTIVO).let { it.copy(descripcion = describir(it)) }

    // the active fiscal domicilio (the newest, if two are left from before the rule) is the contribuyente's
    // domicilio_fiscal, written only when it changed. none yet (a contribuyente of the padrón): the padrón's stays
    private suspend fun sincronizarFiscal(contribuyente: UUID) {
        val fiscal = domicilios(contribuyente).lastOrNull(::esFiscalActivo) ?: return
        val stored = get(contribuyente)
        val next =
            stored.copy(
                domicilioFiscal = fiscal.descripcion,
                domicilioDistrito = fiscal.distrito,
                domicilioProvincia = fiscal.provincia,
                domicilioDepartamento = fiscal.departamento
            )
        if (next == stored) return
        registros.replace(CONTRIBUYENTE, Contribuyente::class.java, contribuyente, Records.attributes(next))
    }

    // the domicilio's siblings: what the fiscal rule weighs it against
    private suspend fun otrosDomicilios(domicilio: Domicilio): List<Domicilio> =
        domicilio.contribuyente
            ?.let { domicilios(UUID.fromString(it)) }
            .orEmpty()
            .filter { it.id != domicilio.id }

    private suspend fun <T : Any> hijos(
        objectName: String,
        type: Class<T>,
        contribuyente: UUID
    ): List<T> = listas.listar(objectName, type, PARENT, contribuyente)

    private suspend fun <T : Any> agregar(
        objectName: String,
        type: Class<T>,
        contribuyente: UUID,
        body: T
    ): T = listas.agregar(objectName, type, PARENT, contribuyente, body)

    private suspend fun <T : Any> cambiar(
        objectName: String,
        type: Class<T>,
        id: UUID,
        body: T
    ): T = listas.cambiar(objectName, type, PARENT, id, body)

    private companion object {
        const val ACTIVO = Listas.ACTIVO
        const val PARENT = "contribuyente"
        const val CODE_ATTEMPTS = 3
    }
}

// the srtm's "(*) registrar al menos 1 domicilio fiscal": a contribuyente's first domicilio is its active fiscal one
// and then its only one: it stays fiscal, active and there (its address changes freely), and no second one joins it.
// otros: the contribuyente's other domicilios; antes: the stored one (null when adding); despues: what it becomes
// (null when removing). a contribuyente left without one from before the rule keeps what it has until it adds it
fun exigirFiscal(
    otros: List<Domicilio>,
    antes: Domicilio?,
    despues: Domicilio?
) {
    val otroFiscal = otros.any(::esFiscalActivo)
    if (despues != null && esFiscalActivo(despues) && otroFiscal) {
        throw ValidationException("Ya hay un domicilio fiscal activo", "tipo_domicilio", "ya tiene otro domicilio fiscal activo: actualice ese domicilio")
    }
    if (otroFiscal || (despues != null && esFiscalActivo(despues))) return
    // no active fiscal one would be left
    val campo = if (despues?.tipoDomicilio == "FISCAL") "estado" else "tipo_domicilio"
    when {
        antes == null -> throw ValidationException("Falta el domicilio fiscal", campo, "el primer domicilio debe ser el fiscal, activo")
        !esFiscalActivo(antes) -> return
        despues == null ->
            throw ValidationException("No se puede eliminar el único domicilio fiscal activo", "tipo_domicilio", "es el único domicilio fiscal activo")
        campo == "estado" -> throw ValidationException("Falta el domicilio fiscal", "estado", "es el único domicilio fiscal activo: no se puede inactivar")
        else -> throw ValidationException("Falta el domicilio fiscal", "tipo_domicilio", "es el único domicilio fiscal activo: debe seguir siendo FISCAL")
    }
}
