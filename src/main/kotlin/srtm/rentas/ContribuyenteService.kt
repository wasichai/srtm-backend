package srtm.rentas

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import wasichai.core.common.PageRequest
import wasichai.core.common.PageResponse
import wasichai.core.common.ValidationException
import wasichai.core.data.RecordQuery
import java.time.LocalDate
import java.util.UUID

// the srtm's "registro de contribuyente": the contribuyente itself (datos de la declaración, identificación,
// datos personales) and its four lists (domicilios, relacionados, medios de contacto, sustento)
@Service
class ContribuyenteService(
    private val registros: Registros,
    private val listas: Listas
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
        val next =
            derivar(body, stored).copy(
                // the backend's, and the fiscal domicilio's (kept in step by the domicilios below)
                codigo = stored.codigo,
                numeroDeclaracion = stored.numeroDeclaracion,
                fechaRegistro = stored.fechaRegistro,
                domicilioFiscal = stored.domicilioFiscal,
                domicilioDistrito = stored.domicilioDistrito,
                domicilioProvincia = stored.domicilioProvincia,
                domicilioDepartamento = stored.domicilioDepartamento
            )
        return registros.replace(CONTRIBUYENTE, Contribuyente::class.java, id, Records.attributes(next))
    }

    // domicilios: the description is always rebuilt; an active fiscal one is copied to the contribuyente

    suspend fun domicilios(contribuyente: UUID): List<Domicilio> = hijos(DOMICILIO, Domicilio::class.java, contribuyente)

    suspend fun agregarDomicilio(
        contribuyente: UUID,
        body: Domicilio
    ): Domicilio {
        get(contribuyente)
        val saved = agregar(DOMICILIO, Domicilio::class.java, contribuyente, domicilio(body))
        sincronizarFiscal(contribuyente)
        return saved
    }

    suspend fun actualizarDomicilio(
        id: UUID,
        body: Domicilio
    ): Domicilio {
        val saved = cambiar(DOMICILIO, Domicilio::class.java, id, domicilio(body))
        saved.contribuyente?.let { sincronizarFiscal(UUID.fromString(it)) }
        return saved
    }

    suspend fun borrarDomicilio(id: UUID) {
        val stored = registros.get(DOMICILIO, Domicilio::class.java, id)
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
        return agregar(RELACIONADO, Relacionado::class.java, contribuyente, relacionado(body).copy(codigo = codigo, estado = body.estado ?: ACTIVO))
    }

    suspend fun actualizarRelacionado(
        id: UUID,
        body: Relacionado
    ): Relacionado {
        val stored = registros.get(RELACIONADO, Relacionado::class.java, id)
        return cambiar(RELACIONADO, Relacionado::class.java, id, relacionado(body).copy(codigo = stored.codigo))
    }

    private fun relacionado(body: Relacionado): Relacionado {
        validarNombre(body.tipoDocumento, body.razonSocial, body.nombres)
        return body.copy(fuenteInformacion = body.fuenteInformacion ?: FUENTE_MANUAL)
    }

    suspend fun mediosContacto(contribuyente: UUID): List<MedioContacto> = hijos(MEDIO_CONTACTO, MedioContacto::class.java, contribuyente)

    suspend fun agregarMedioContacto(
        contribuyente: UUID,
        body: MedioContacto
    ): MedioContacto {
        get(contribuyente)
        return agregar(MEDIO_CONTACTO, MedioContacto::class.java, contribuyente, body.copy(estado = body.estado ?: ACTIVO))
    }

    suspend fun actualizarMedioContacto(
        id: UUID,
        body: MedioContacto
    ): MedioContacto = cambiar(MEDIO_CONTACTO, MedioContacto::class.java, id, body)

    suspend fun sustentos(contribuyente: UUID): List<Sustento> = hijos(SUSTENTO, Sustento::class.java, contribuyente)

    suspend fun agregarSustento(
        contribuyente: UUID,
        body: Sustento
    ): Sustento {
        get(contribuyente)
        return agregar(SUSTENTO, Sustento::class.java, contribuyente, body.copy(estado = body.estado ?: ACTIVO))
    }

    suspend fun actualizarSustento(
        id: UUID,
        body: Sustento
    ): Sustento = cambiar(SUSTENTO, Sustento::class.java, id, body)

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

    // the newest active fiscal domicilio is the contribuyente's domicilio_fiscal. with none left, the last one stays:
    // the lists and the padrón keep showing where it was
    private suspend fun sincronizarFiscal(contribuyente: UUID) {
        val fiscal = domicilios(contribuyente).lastOrNull(::esFiscalActivo) ?: return
        val stored = get(contribuyente)
        registros.replace(
            CONTRIBUYENTE,
            Contribuyente::class.java,
            contribuyente,
            Records.attributes(
                stored.copy(
                    domicilioFiscal = fiscal.descripcion,
                    domicilioDistrito = fiscal.distrito,
                    domicilioProvincia = fiscal.provincia,
                    domicilioDepartamento = fiscal.departamento
                )
            )
        )
    }

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
