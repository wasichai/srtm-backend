package srtm.rentas

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import wasichai.core.common.ValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

// the srtm's declaración jurada predial: datos del predio, ubicación (the predio's), transferentes, características
// (niveles de construcción, obras complementarias), condóminos (the other declarations of the predio) and otros frentes
@Service
class DeclaracionService(
    private val registros: Registros,
    private val listas: Listas,
    private val contribuyentes: ContribuyenteService
) {
    suspend fun ficha(id: UUID): DeclaracionJurada {
        val declaracion = registros.get(DECLARACION, Declaracion::class.java, id)
        return DeclaracionJurada(
            declaracion = declaracion,
            predio = registros.get(PREDIO, Predio::class.java, UUID.fromString(declaracion.predio)),
            contribuyente = contribuyentes.get(UUID.fromString(declaracion.contribuyente))
        )
    }

    // a new declaration of a contribuyente, on an existing predio or on one it registers. numbered by the backend,
    // with the srtm's defaults. a predio registered here is removed again if the declaration is refused
    suspend fun presentar(
        contribuyente: UUID,
        body: NuevaDeclaracion
    ): DeclaracionJurada {
        contribuyentes.get(contribuyente)
        val existente = body.predioId?.let { registros.get(PREDIO, Predio::class.java, UUID.fromString(it)) }
        val predio = existente ?: registrarPredio(body.predio ?: throw ValidationException("Falta el predio", "predio_id", "elige un predio o registra uno"))
        val predioId = UUID.fromString(predio.id)
        try {
            val declaracion = numerar { nueva(body.declaracion, contribuyente, predioId) }
            return ficha(UUID.fromString(declaracion.id))
        } catch (e: Exception) {
            if (existente == null) registros.delete(PREDIO, predioId)
            throw e
        }
    }

    // a declaration from a form that already names both sides (the predio's ficha): numbered like any other
    suspend fun crear(body: Declaracion): Declaracion {
        val contribuyente = body.contribuyente ?: throw ValidationException("Falta el contribuyente", "contribuyente", "es obligatorio")
        val predio = body.predio ?: throw ValidationException("Falta el predio", "predio", "es obligatorio")
        return numerar { nueva(body, UUID.fromString(contribuyente), UUID.fromString(predio)) }
    }

    suspend fun actualizar(
        id: UUID,
        body: Declaracion
    ): Declaracion {
        val stored = registros.get(DECLARACION, Declaracion::class.java, id)
        val next = body.copy(numeroDeclaracion = stored.numeroDeclaracion)
        return registros.replace(DECLARACION, Declaracion::class.java, id, Records.attributes(next))
    }

    // a predio of the srtm: its code comes from sector and manzana when blank, its direccion from its ubicación
    suspend fun registrarPredio(body: Predio): Predio {
        val codigo = body.codigo?.ifBlank { null } ?: codigoPredio(body)
        return registros.create(PREDIO, Predio::class.java, Records.attributes(body.copy(codigo = codigo, direccion = describirUbicacion(body))))
    }

    suspend fun actualizarPredio(
        id: UUID,
        body: Predio
    ): Predio = registros.replace(PREDIO, Predio::class.java, id, Records.attributes(body.copy(direccion = describirUbicacion(body))))

    // the lists of the declaration

    suspend fun transferentes(id: UUID) = listas.listar(TRANSFERENTE, Transferente::class.java, PARENT, id)

    suspend fun agregarTransferente(
        id: UUID,
        body: Transferente
    ) = listas.agregar(TRANSFERENTE, Transferente::class.java, PARENT, existe(id), body.copy(estado = body.estado ?: Listas.ACTIVO))

    suspend fun actualizarTransferente(
        id: UUID,
        body: Transferente
    ) = listas.cambiar(TRANSFERENTE, Transferente::class.java, PARENT, id, body)

    suspend fun niveles(id: UUID) = listas.listar(NIVEL_CONSTRUCCION, NivelConstruccion::class.java, PARENT, id)

    suspend fun agregarNivel(
        id: UUID,
        body: NivelConstruccion
    ) = listas.agregar(NIVEL_CONSTRUCCION, NivelConstruccion::class.java, PARENT, existe(id), body.copy(estado = body.estado ?: Listas.ACTIVO))

    suspend fun actualizarNivel(
        id: UUID,
        body: NivelConstruccion
    ) = listas.cambiar(NIVEL_CONSTRUCCION, NivelConstruccion::class.java, PARENT, id, body)

    suspend fun obras(id: UUID) = listas.listar(OBRA_COMPLEMENTARIA, ObraComplementaria::class.java, PARENT, id)

    suspend fun agregarObra(
        id: UUID,
        body: ObraComplementaria
    ) = listas.agregar(OBRA_COMPLEMENTARIA, ObraComplementaria::class.java, PARENT, existe(id), obra(body))

    suspend fun actualizarObra(
        id: UUID,
        body: ObraComplementaria
    ) = listas.cambiar(OBRA_COMPLEMENTARIA, ObraComplementaria::class.java, PARENT, id, obra(body))

    suspend fun frentes(id: UUID) = listas.listar(OTRO_FRENTE, OtroFrente::class.java, PARENT, id)

    suspend fun agregarFrente(
        id: UUID,
        body: OtroFrente
    ) = listas.agregar(OTRO_FRENTE, OtroFrente::class.java, PARENT, existe(id), body.copy(estado = body.estado ?: Listas.ACTIVO))

    suspend fun actualizarFrente(
        id: UUID,
        body: OtroFrente
    ) = listas.cambiar(OTRO_FRENTE, OtroFrente::class.java, PARENT, id, body)

    suspend fun borrar(
        objectName: String,
        id: UUID
    ) = listas.borrar(objectName, id)

    private suspend fun nueva(
        body: Declaracion,
        contribuyente: UUID,
        predio: UUID
    ): Declaracion {
        val presentacion = body.fechaPresentacion ?: LocalDate.now()
        val condicion = body.condicionPropiedad ?: "PROPIETARIO UNICO"
        return body.copy(
            contribuyente = contribuyente.toString(),
            predio = predio.toString(),
            numeroDeclaracion = (registros.highest(DECLARACION, "numero_declaracion")?.toIntOrNull() ?: 0) + 1,
            anio = body.anio ?: presentacion.year,
            secuenciaUso = body.secuenciaUso?.ifBlank { null } ?: "1",
            motivo = body.motivo ?: "INSCRIPCION",
            medioDeterminacion = body.medioDeterminacion ?: "DECLARACION JURADA",
            medioPresentacion = body.medioPresentacion ?: "FISICO",
            fechaPresentacion = presentacion,
            condicionPropiedad = condicion,
            porcentajeCondominio = body.porcentajeCondominio ?: if (condicion == "PROPIETARIO UNICO") BigDecimal(100) else null
        )
    }

    // numero_declaracion is unique: two clerks presenting at once may pick the same one. read it again and retry
    private suspend fun numerar(build: suspend () -> Declaracion): Declaracion {
        repeat(ATTEMPTS - 1) {
            try {
                return registros.create(DECLARACION, Declaracion::class.java, Records.attributes(build()))
            } catch (_: DataIntegrityViolationException) {
                // taken meanwhile
            }
        }
        return registros.create(DECLARACION, Declaracion::class.java, Records.attributes(build()))
    }

    private suspend fun codigoPredio(body: Predio): String {
        val sector = body.sectorCatastral?.ifBlank { null } ?: throw ValidationException("Falta el sector", "sector_catastral", "sin código, el sector lo arma")
        val manzana =
            body.manzanaCatastral?.ifBlank { null } ?: throw ValidationException("Falta la manzana", "manzana_catastral", "sin código, la manzana lo arma")
        val prefijo = prefijoPredio(sector, manzana)
        return siguienteCodigoPredio(prefijo, registros.highest(PREDIO, "codigo", prefijo))
    }

    private suspend fun existe(id: UUID): UUID {
        registros.get(DECLARACION, Map::class.java, id)
        return id
    }

    private fun obra(body: ObraComplementaria) = body.copy(estado = body.estado ?: Listas.ACTIVO, totalMetrado = totalMetrado(body))

    private companion object {
        const val PARENT = "declaracion"
        const val ATTEMPTS = 3
    }
}
