package srtm.normativa

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import srtm.Candado
import srtm.Candados
import srtm.Permisos
import srtm.brecha.ArancelCatastral
import srtm.brecha.Catastro
import srtm.brecha.CatastroException
import srtm.brecha.Catastros
import srtm.brecha.delUsuario
import srtm.normativa.CamposArancel.CLAVE
import srtm.normativa.CamposArancel.ERROR_SINCRONIZACION
import srtm.normativa.CamposArancel.SINCRONIZADO_EN
import srtm.normativa.CamposParametro.ESTADO
import srtm.rentas.Registros
import wasichai.core.common.ConflictException
import wasichai.core.data.RecordCriterion
import wasichai.core.identity.CurrentUser
import wasichai.core.organization.OrganizationRepository
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// what a copy to catastro did: the aranceles of srtm it left synchronized, and each one it could not (its id, clave
// and catastro's error, never a token nor a secret). the field sincronizacion of verificar, anular and importar, and
// the answer of POST /normativa/aranceles/sincronizar
data class Sincronizacion(
    val sincronizados: Int,
    val errores: List<ErrorDeSincronizacion>
)

data class ErrorDeSincronizacion(
    val id: String,
    val clave: String,
    val error: String
)

// Normativa's copy of the aranceles to catastro-backend (#189): srtm owns them, and catastro keeps the VIGENTE one of
// each clave, which it values with. each clave is copied as it is now in srtm: its VIGENTE one (the one the DJ reads,
// ArancelesService.vigente: the lowest id) is upserted in catastro; with none (annulled, not yet verified again) catastro's
// is deleted. it runs after the write of Normativa is committed, outside its transaction: catastro failing never undoes
// a verificación, an anulación or an importación. the result is written on each arancel (Registros.replace, which the
// rule lets through in any estado): sincronizado_en now and no error, or the error and sincronizado_en as it was. an
// organization without its catastro copies nothing and fails nothing (null). CONCURRENCIA claves at a time, in all
@Service
class SincronizacionAranceles(
    private val registros: Registros,
    private val permisos: Permisos,
    private val currentUser: CurrentUser,
    private val catastros: Catastros,
    private val organizaciones: OrganizationRepository,
    private val candados: Candados,
    transacciones: ReactiveTransactionManager
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaccion = TransactionalOperator.create(transacciones)

    // the copies in course, of every request of this instance: each holds a connection of the shared pool and its
    // clave's lock while catastro answers (COPIA_MAXIMA at most), so a catastro that hangs holds CONCURRENCIA of them,
    // however many verify, annul, import or synchronize at once. no caller holds a permit while it waits for another
    internal val enCurso = Semaphore(CONCURRENCIA)

    // after a verificación, anulación or importación: the claves of these aranceles. null without catastro
    suspend fun despuesDe(aranceles: Collection<Arancel>): Sincronizacion? {
        val catastro = catastro() ?: return null
        return copiar(catastro, aranceles)
    }

    // POST /normativa/aranceles/sincronizar: every arancel of the year (any year without it) whose copy is pending, a
    // VIGENTE or ANULADO one without sincronizado_en or with an error. a POR_VERIFICAR one is never copied. the explicit
    // act says why nothing is copied when two entries name the organization: their 409 (Catastros.de)
    suspend fun pendientes(anio: Int?): Sincronizacion {
        permisos.exigirActualizar(ARANCEL, "sincronizar los aranceles con catastro")
        val catastro = catastros.delUsuario(currentUser.require(), organizaciones) ?: return Sincronizacion(0, emptyList())
        val pendientes =
            registros.donde(
                ARANCEL,
                Arancel::class.java,
                listOf(PENDIENTE),
                filters = anio?.let { mapOf(CamposArancel.ANIO to "$it") }.orEmpty()
            )
        return copiar(catastro, pendientes)
    }

    private suspend fun catastro(): Catastro? =
        try {
            catastros.delUsuario(currentUser.require(), organizaciones)
        } catch (e: ConflictException) {
            // two entries name the organization: nothing is copied until one is left (the log says so at startup)
            log.warn("normativa: no se copian los aranceles a catastro: {}", e.message)
            null
        }

    private suspend fun copiar(
        catastro: Catastro,
        aranceles: Collection<Arancel>
    ): Sincronizacion {
        val marcas =
            coroutineScope {
                aranceles
                    .filter { !it.id.isNullOrEmpty() }
                    .groupBy { it.clave.orEmpty() }
                    .map { (clave, suyos) -> async { enCurso.withPermit { copiarClave(catastro, clave, suyos) } } }
                    .awaitAll()
            }.flatten()
        return Sincronizacion(marcas.count { it.error == null }, marcas.mapNotNull { m -> m.error?.let { ErrorDeSincronizacion(m.id, m.clave, it) } })
    }

    // the clave as it is now, under its lock (Candado.ARANCEL, the one every act on it takes), in one transaction:
    // its VIGENTE one to catastro, or catastro's deleted, and the marks. so the copies of a clave and its acts run one
    // after another, and the last copy to reach catastro is of srtm's state when it ran: an older copy that runs after
    // a newer act pushes that act's state, never its own. the lock and a connection are held while catastro answers,
    // COPIA_MAXIMA at most (CONCURRENCIA claves at a time). marked: the aranceles asked and the VIGENTE one copied
    private suspend fun copiarClave(
        catastro: Catastro,
        clave: String,
        suyos: List<Arancel>
    ): List<Marca> {
        val pedidos = suyos.mapNotNull { it.id }
        return try {
            transaccion.executeAndAwait {
                candados.bloquearEnOrganizacion(Candado.ARANCEL, clave)
                val rige =
                    registros
                        .all(ARANCEL, Arancel::class.java, filters = mapOf(CLAVE to clave))
                        .filter { it.rige() }
                        .minByOrNull { it.id.orEmpty() }
                val error = copia(catastro, clave, rige)
                (pedidos + listOfNotNull(rige?.id)).distinct().map { marcar(it, clave, error) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the marks did not commit: each arancel asked keeps its copy pending (sincronizar takes it again)
            log.warn("normativa: no se guardó el resultado de la copia del arancel {}", clave, e)
            pedidos.map { Marca(it, clave, "no se guardó el resultado de la copia: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // catastro's arancel of the clave becomes rige (or goes, without one): null, or what failed
    private suspend fun copia(
        catastro: Catastro,
        clave: String,
        rige: Arancel?
    ): String? =
        try {
            withTimeout(COPIA_MAXIMA.toMillis()) {
                if (rige != null) catastro.guardarArancel(catastral(rige)) else catastro.borrarArancel(clave)
            }
            null
        } catch (_: TimeoutCancellationException) {
            "catastro-backend no respondió a tiempo (${COPIA_MAXIMA.seconds} s) la copia del arancel $clave"
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatastroException) {
            e.message ?: "catastro-backend no respondió"
        } catch (e: Exception) {
            log.warn("normativa: la copia del arancel {} a catastro falló", clave, e)
            "catastro-backend no respondió: ${e.javaClass.simpleName}"
        }

    // the result on the arancel itself, in the copy's transaction
    private suspend fun marcar(
        id: String,
        clave: String,
        error: String?
    ): Marca {
        val cambios =
            if (error == null) {
                mapOf(SINCRONIZADO_EN to OffsetDateTime.now(ZoneOffset.UTC).toString(), ERROR_SINCRONIZACION to null)
            } else {
                mapOf(ERROR_SINCRONIZACION to error)
            }
        registros.replace(ARANCEL, Arancel::class.java, UUID.fromString(id), cambios)
        return Marca(id, clave, error)
    }

    private data class Marca(
        val id: String,
        val clave: String,
        val error: String?
    )

    companion object {
        // claves copied at once by the instance (enCurso), as the importación's lotes: at most this many connections wait on
        // catastro
        internal const val CONCURRENCIA = 4

        // the longest a copy holds its clave's lock and its connection: the GET and the write to catastro (each one
        // bounded by the account's timeout, 60 s by default, and retried once after a 401)
        val COPIA_MAXIMA: Duration = Duration.ofSeconds(90)

        // the VIGENTE one as catastro keeps it
        fun catastral(a: Arancel): ArancelCatastral {
            val idAranc = a.idAranc?.trim()
            val anio = a.anio
            val valor = a.valorM2
            if (idAranc.isNullOrEmpty() || anio == null || valor == null) {
                throw CatastroException("el arancel ${a.clave} no tiene id_aranc, anio o valor_m2: no se copia")
            }
            return ArancelCatastral(claveDeArancel(idAranc, anio), idAranc, anio, valor, a.codVia, a.cuadra, a.lado)
        }

        // a VIGENTE (or without estado) or ANULADO arancel whose copy is pending: no sincronizado_en, or an error
        private val PENDIENTE =
            RecordCriterion { definition, bind ->
                fun columna(campo: String) = "\"${definition.fields.first { it.name == campo }.columnName}\""
                val estado = columna(ESTADO)
                val error = columna(ERROR_SINCRONIZACION)
                "(($estado IS NULL OR $estado <> ${bind(EstadoParametro.POR_VERIFICAR)}) AND " +
                    "(${columna(SINCRONIZADO_EN)} IS NULL OR ($error IS NOT NULL AND $error <> '')))"
            }
    }
}
