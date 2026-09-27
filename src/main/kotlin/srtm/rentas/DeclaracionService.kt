package srtm.rentas

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import wasichai.core.common.ValidationException
import java.time.LocalDate
import java.util.UUID

// the srtm's declaración jurada predial: datos del predio, ubicación (the predio's), transferentes, características
// (niveles de construcción, obras complementarias), condóminos (the other declarations of the predio) and otros frentes.
// every write of a declaración recomputes its condominio (Condominio.kt)
@Service
class DeclaracionService(
    private val registros: Registros,
    private val listas: Listas,
    private val contribuyentes: ContribuyenteService
) {
    suspend fun ficha(id: UUID): DeclaracionJurada {
        val (declaracion, actualizado) = registros.getConFecha(DECLARACION, Declaracion::class.java, id)
        return DeclaracionJurada(
            declaracion = declaracion,
            predio = registros.get(PREDIO, Predio::class.java, UUID.fromString(declaracion.predio)),
            contribuyente = contribuyentes.get(UUID.fromString(declaracion.contribuyente)),
            actualizado = actualizado
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
            val declaracion = registrar(nueva(body.declaracion, contribuyente, predioId))
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
        return registrar(nueva(body, UUID.fromString(contribuyente), UUID.fromString(predio)))
    }

    // a declaración that moves to another predio, year or secuencia joins that condominio and leaves its own
    suspend fun actualizar(
        id: UUID,
        body: Declaracion
    ): Declaracion {
        val stored = registros.get(DECLARACION, Declaracion::class.java, id)
        val next = body.copy(numeroDeclaracion = stored.numeroDeclaracion, secuenciaUso = secuenciaUso(body.secuenciaUso))
        val seUne = grupoDe(next) != grupoDe(stored)
        val otros = titulares(next).filter { it.id != id.toString() }
        val grupo = condominioCon(next, otros, seUne)
        val saved = registros.replace(DECLARACION, Declaracion::class.java, id, Records.attributes(grupo.first()))
        guardarDerivados(grupo.drop(1), otros)
        if (seUne) recalcular(stored)
        return saved
    }

    // with its own lists; the condóminos left are recomputed
    suspend fun borrarDeclaracion(id: UUID) {
        val stored = registros.get(DECLARACION, Declaracion::class.java, id)
        for (lista in listOf(TRANSFERENTE, NIVEL_CONSTRUCCION, OBRA_COMPLEMENTARIA, OTRO_FRENTE)) {
            listas.listar(lista, Map::class.java, PARENT, id).forEach { registros.delete(lista, UUID.fromString(it["id"].toString())) }
        }
        registros.delete(DECLARACION, id)
        recalcular(stored)
    }

    // "datos de los condóminos": another contribuyente's declaración of the same predio, year and secuencia, with what
    // the source declares of the predio and its niveles, obras and otros frentes
    suspend fun agregarCondomino(
        id: UUID,
        body: NuevoCondomino
    ): Declaracion {
        val origen = registros.get(DECLARACION, Declaracion::class.java, id)
        val contribuyente =
            body.contribuyente?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ValidationException("Falta el contribuyente", "contribuyente", "elige un contribuyente")
        contribuyentes.get(contribuyente)
        val creada = registrar(nueva(delPredio(origen).copy(porcentajeCondominio = body.porcentajeCondominio), contribuyente, UUID.fromString(origen.predio)))
        val nuevaId = UUID.fromString(creada.id)
        niveles(id).forEach { agregarNivel(nuevaId, it.copy(id = null, declaracion = null)) }
        obras(id).forEach { agregarObra(nuevaId, it.copy(id = null, declaracion = null)) }
        frentes(id).forEach { agregarFrente(nuevaId, it.copy(id = null, declaracion = null)) }
        return creada
    }

    // a predio of the srtm: its code comes from sector and manzana when blank, its direccion from its ubicación
    suspend fun registrarPredio(body: Predio): Predio {
        val codigo = body.codigo?.ifBlank { null } ?: codigoPredio(body)
        return conReintento {
            val numero = (registros.highest(PREDIO, "numero_registro")?.toIntOrNull() ?: 0) + 1
            registros.create(
                PREDIO,
                Predio::class.java,
                Records.attributes(body.copy(codigo = codigo, numeroRegistro = numero, direccion = describirUbicacion(body)))
            )
        }
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
            transferente(body).copy(codigo = codigo, estado = body.estado ?: Listas.ACTIVO)
        )
    }

    suspend fun actualizarTransferente(
        id: UUID,
        body: Transferente
    ): Transferente {
        val stored = registros.get(TRANSFERENTE, Transferente::class.java, id)
        return listas.cambiar(TRANSFERENTE, Transferente::class.java, PARENT, id, transferente(body).copy(codigo = stored.codigo))
    }

    private fun transferente(body: Transferente): Transferente {
        validarNombre(body.tipoDocumento, body.razonSocial, body.nombres)
        return body.copy(fuenteInformacion = body.fuenteInformacion ?: FUENTE_MANUAL)
    }

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
            fechaPresentacion = presentacion
        )
    }

    // a new titular joins its predio's condominio: checked and derived before it is stored, the others after
    private suspend fun registrar(declaracion: Declaracion): Declaracion {
        val otros = titulares(declaracion)
        val grupo = condominioCon(declaracion, otros, seUne = true)
        val creada = numerar(grupo.first())
        guardarDerivados(grupo.drop(1), otros)
        return creada
    }

    // the declaraciones of a condominio: same predio, year and secuencia de uso. the secuencia is compared here, not in
    // the query: one stored as "1" before model/normalizar_padron.py ran is the same as "001"
    private suspend fun titulares(d: Declaracion): List<Declaracion> {
        val (predio, anio, secuencia) = grupoDe(d)
        if (predio == null || anio == null || secuencia == null) return emptyList()
        return registros
            .all(DECLARACION, Declaracion::class.java, filters = mapOf("predio" to predio, "anio" to anio.toString()))
            .filter { grupoDe(it) == grupoDe(d) }
    }

    // what is left of a condominio a declaración went away from
    private suspend fun recalcular(fuera: Declaracion) {
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
            registros.create(DECLARACION, Declaracion::class.java, Records.attributes(declaracion.copy(numeroDeclaracion = numero)))
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
