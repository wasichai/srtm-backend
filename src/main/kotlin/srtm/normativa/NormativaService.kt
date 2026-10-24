package srtm.normativa

import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.ParametroInvalido
import srtm.Permisos
import srtm.impuesto.PARAMETRO_TRIBUTARIO
import srtm.impuesto.ParametroTributario
import srtm.impuesto.vigenteEl
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
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import wasichai.core.common.ConflictException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// POST /normativa/parametros/verificar: the rows to verify, at once
data class PedidoVerificacion(
    val ids: List<String>? = null
)

// POST /normativa/parametros/{id}/anular
data class PedidoAnulacion(
    val motivo: String? = null
)

// POST /normativa/parametros/{id}/cerrar
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PedidoCierre(
    val vigenciaHasta: LocalDate? = null
)

data class RechazoDeVerificacion(
    val id: String,
    val motivo: String
)

// its 200: what was verified, and what not and why. one rechazo does not stop the others
data class ResultadoVerificacion(
    val verificados: List<String>,
    val rechazados: List<RechazoDeVerificacion>
)

// Normativa's parámetros (#187): the double signature over parametro_tributario. a row is registered POR_VERIFICAR by
// the user (transcribio), corrected or deleted while so, verified by another user (VIGENTE) and, if wrong, annulled
// and registered again. ReglaDeNormativa holds the same on every other door; here what it cannot see: who the user is,
// the other rows of the key (no two alive with one key, no two VIGENTE that overlap) and the catalog's shape of the
// tipo. each act on a tipo and clave runs under its lock (Candado.PARAMETRO_TRIBUTARIO)
@Service
class NormativaService(
    private val registros: Registros,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val candados: Candados,
    transacciones: ReactiveTransactionManager
) {
    private val transaccion = TransactionalOperator.create(transacciones)

    suspend fun tipos(): List<TipoDeParametro> {
        permisos.exigirLeer(PARAMETRO_TRIBUTARIO, "consultar los tipos de parámetro")
        return TiposDeParametro.TODOS
    }

    // the rows by tipo, clave and vigencia_desde (the latest first), an empty estado as VIGENTE. vigenteEn: whose
    // vigencia holds that day, whatever their estado (unless estado says it)
    suspend fun parametros(
        tipo: String?,
        estado: String?,
        vigenteEn: LocalDate?
    ): List<ParametroTributario> {
        permisos.exigirLeer(PARAMETRO_TRIBUTARIO, "consultar los parámetros")
        if (estado != null && estado !in EstadoParametro.TODOS) throw ParametroInvalido("estado", "es ${EstadoParametro.TODOS.joinToString(", ")}: '$estado'")
        return registros
            .all(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, filters = tipo?.let { mapOf("tipo" to it) }.orEmpty())
            .map { it.copy(estado = EstadoParametro.de(it.estado)) }
            .filter { estado == null || it.estado == estado }
            .filter { vigenteEn == null || it.vigenteEl(vigenteEn) }
            .sortedWith(compareBy<ParametroTributario>({ it.tipo }, { it.clave.orEmpty() }).thenByDescending { it.vigenciaDesde })
    }

    // a new row POR_VERIFICAR, transcribed by the user: 201. one alive with its key (tipo, clave, vigencia_desde) is a 409
    suspend fun registrar(cuerpo: Map<String, Any?>): ParametroTributario {
        permisos.exigirCrear(PARAMETRO_TRIBUTARIO, "registrar un parámetro")
        val p = DatosDeParametro.validar(DatosDeParametro.de(cuerpo))
        val usuario = firma()
        return transaccion.executeAndAwait {
            bloquear(p)
            sinOtroConSuLlave(p, null)
            registros.create(
                PARAMETRO_TRIBUTARIO,
                ParametroTributario::class.java,
                DatosDeParametro.atributos(p) + mapOf(ESTADO to POR_VERIFICAR, TRANSCRIBIO to usuario, VERIFICO to null)
            )
        }
    }

    // a POR_VERIFICAR row with the data of the body over its own (a field left out keeps its value). whoever corrects it
    // is its transcriber now: the verifier signs what this user wrote
    suspend fun corregir(
        id: UUID,
        cuerpo: Map<String, Any?>
    ): ParametroTributario {
        permisos.exigirActualizar(PARAMETRO_TRIBUTARIO, "corregir un parámetro")
        val cambios = DatosDeParametro.de(cuerpo)
        val usuario = firma()
        return transaccion.executeAndAwait {
            val guardado = porVerificar(id, "se corrige")
            val p = DatosDeParametro.validar(DatosDeParametro.atributos(guardado) + cambios)
            bloquear(p)
            sinOtroConSuLlave(p, guardado.id)
            registros.replace(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, id, DatosDeParametro.atributos(p) + (TRANSCRIBIO to usuario))
        }
    }

    // a POR_VERIFICAR row is deleted: it never ruled. a VIGENTE one is annulled instead
    suspend fun borrar(id: UUID) {
        permisos.exigirBorrar(PARAMETRO_TRIBUTARIO, "borrar un parámetro")
        transaccion.executeAndAwait {
            // under the lock a verificación takes: the row read again is still POR_VERIFICAR, or nothing is deleted
            bloquear(leer(id))
            porVerificar(id, "se borra")
            registros.delete(PARAMETRO_TRIBUTARIO, id)
        }
    }

    // each row on its own: POR_VERIFICAR, transcribed by someone else, and without a VIGENTE of its tipo and clave whose
    // vigencia it shares a day with. the user signs it (verifico) and it is VIGENTE
    suspend fun verificar(pedido: PedidoVerificacion): ResultadoVerificacion {
        permisos.exigirActualizar(PARAMETRO_TRIBUTARIO, "verificar parámetros")
        val usuario = firma()
        return verificarCada(pedido, "parámetros") { id -> transaccion.executeAndAwait { verificarUno(id, usuario) } }
    }

    private suspend fun verificarUno(
        id: UUID,
        usuario: String
    ) {
        bloquear(leer(id))
        val p = leer(id)
        val estado = EstadoParametro.de(p.estado)
        if (estado != POR_VERIFICAR) throw ConflictException("está $estado: solo se verifica uno POR_VERIFICAR")
        if (firmante(p.transcribio) == firmante(usuario)) throw ConflictException("no puede verificar lo que transcribió: la doble firma pide otra persona")
        hermanos(p).firstOrNull { it.id != p.id && it.rige() && seSolapan(it, p) }?.let { throw ConflictException(PlanDeImportacion.seSolapaCon(it)) }
        registros.replace(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, id, mapOf(ESTADO to VIGENTE, VERIFICO to usuario))
    }

    // a VIGENTE row is ANULADO: its motivo, the user and now. the correct value is registered as a new row
    suspend fun anular(
        id: UUID,
        pedido: PedidoAnulacion
    ): ParametroTributario {
        permisos.exigirActualizar(PARAMETRO_TRIBUTARIO, "anular un parámetro")
        val motivo = pedido.motivo?.trim()
        if (motivo.isNullOrEmpty()) throw ValidationException("Anular un parámetro exige su motivo", "motivo", "es obligatorio")
        val usuario = firma()
        return transaccion.executeAndAwait {
            val p = leer(id)
            bloquear(p)
            val estado = EstadoParametro.de(leer(id).estado)
            if (estado != VIGENTE) throw ConflictException("Solo se anula un parámetro VIGENTE: este está $estado")
            registros.replace(
                PARAMETRO_TRIBUTARIO,
                ParametroTributario::class.java,
                id,
                mapOf(
                    ESTADO to ANULADO,
                    MOTIVO_ANULACION to motivo,
                    ANULADO_POR to usuario,
                    ANULADO_EN to OffsetDateTime.now(ZoneOffset.UTC).toString()
                )
            )
        }
    }

    // an open VIGENTE row (vigencia_hasta empty) rules until vigencia_hasta, from its vigencia_desde on, and nothing else
    // changes: the only edition of a VIGENTE row, so a later one of its tipo and clave can be verified after it
    suspend fun cerrar(
        id: UUID,
        pedido: PedidoCierre
    ): ParametroTributario {
        permisos.exigirActualizar(PARAMETRO_TRIBUTARIO, "cerrar la vigencia de un parámetro")
        val hasta = pedido.vigenciaHasta ?: throw ValidationException("Cerrar la vigencia exige su fecha", "vigencia_hasta", "es obligatoria")
        return transaccion.executeAndAwait {
            bloquear(leer(id))
            val p = leer(id)
            val estado = EstadoParametro.de(p.estado)
            if (estado != VIGENTE) throw ConflictException("Solo se cierra la vigencia de un parámetro VIGENTE: este está $estado")
            if (p.vigenciaHasta != null) throw ConflictException("La vigencia del parámetro ya está cerrada (${p.vigenciaHasta}): no se cambia")
            if (p.vigenciaDesde != null && hasta.isBefore(p.vigenciaDesde)) {
                throw ValidationException("La vigencia no se cierra antes de empezar", "vigencia_hasta", "no es anterior a vigencia_desde (${p.vigenciaDesde})")
            }
            registros.replace(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, id, mapOf("vigencia_hasta" to hasta.toString()))
        }
    }

    // the csv as model/data/parametros-predial.csv (PlanDeImportacion): with dryRun what it would do, without it the
    // same written in one transaction, under the lock of every tipo and clave it names
    suspend fun importar(
        csv: String,
        dryRun: Boolean
    ): ResultadoImportacion {
        permisos.exigirCrear(PARAMETRO_TRIBUTARIO, "importar parámetros")
        permisos.exigirActualizar(PARAMETRO_TRIBUTARIO, "importar parámetros")
        val filas = CsvDeParametros.leer(csv)
        val usuario = firma()
        val plan =
            if (dryRun) {
                planear(filas, usuario)
            } else {
                transaccion.executeAndAwait {
                    filas
                        .map { "${it.valores["tipo"].orEmpty()}|${it.valores["clave"].orEmpty()}" }
                        .distinct()
                        .sorted()
                        .forEach { candados.bloquearEnOrganizacion(Candado.PARAMETRO_TRIBUTARIO, it) }
                    planear(filas, usuario).also { escribir(it) }
                }
            }
        return ResultadoImportacion(dryRun, plan.crear.map(FilaImportada::de), plan.actualizar.map(FilaImportada::de), plan.iguales, plan.rechazos)
    }

    private suspend fun planear(
        filas: List<FilaCsv>,
        usuario: String
    ) = PlanDeImportacion.planear(filas, registros.all(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java), usuario)

    private suspend fun escribir(plan: Plan) {
        plan.crear.forEach {
            val firmas = mapOf(ESTADO to it.estado, TRANSCRIBIO to it.transcribio, VERIFICO to it.verifico)
            registros.create(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, DatosDeParametro.atributos(it.parametro) + firmas)
        }
        // a correction stays POR_VERIFICAR, and with two signatures it is verified next: two writes, as the rule asks
        plan.actualizar.forEach {
            val id = UUID.fromString(it.id)
            val datos = DatosDeParametro.atributos(it.parametro) + mapOf(ESTADO to POR_VERIFICAR, TRANSCRIBIO to it.transcribio, VERIFICO to null)
            registros.replace(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, id, datos)
            if (it.estado ==
                VIGENTE
            ) {
                registros.replace(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, id, mapOf(ESTADO to VIGENTE, VERIFICO to it.verifico))
            }
        }
    }

    private suspend fun leer(id: UUID): ParametroTributario = registros.get(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, id)

    private suspend fun porVerificar(
        id: UUID,
        acto: String
    ): ParametroTributario {
        val p = leer(id)
        val estado = EstadoParametro.de(p.estado)
        if (estado != POR_VERIFICAR) {
            throw ConflictException("Solo $acto un parámetro POR_VERIFICAR: este está $estado. Uno VIGENTE se anula y se registra el valor correcto")
        }
        return p
    }

    private suspend fun bloquear(p: ParametroTributario) =
        candados.bloquearEnOrganizacion(Candado.PARAMETRO_TRIBUTARIO, "${p.tipo.orEmpty()}|${p.clave.orEmpty()}")

    // the rows of p's tipo and clave
    private suspend fun hermanos(p: ParametroTributario): List<ParametroTributario> =
        registros
            .all(PARAMETRO_TRIBUTARIO, ParametroTributario::class.java, filters = mapOf("tipo" to p.tipo.orEmpty()))
            .filter { it.clave.orEmpty() == p.clave.orEmpty() }

    private suspend fun sinOtroConSuLlave(
        p: ParametroTributario,
        excepto: String?
    ) {
        val otro =
            hermanos(p).firstOrNull {
                it.id != excepto && it.vigenciaDesde == p.vigenciaDesde && EstadoParametro.de(it.estado) != ANULADO
            } ?: return
        val estado = EstadoParametro.de(otro.estado)
        val que = if (estado == VIGENTE) "anúlelo antes de registrar otro" else "corríjalo o bórrelo"
        throw ConflictException("Ya hay un parámetro $estado con esa llave, ${nombreDe(otro)} (${otro.id}): $que")
    }

    private suspend fun firma(): String = currentUser.firma()
}
