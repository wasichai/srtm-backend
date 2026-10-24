package srtm

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import wasichai.core.identity.CurrentUser
import java.util.UUID

// srtm's kinds of lock, each with its fixed number: postgres' two-integer form, pg_advisory_xact_lock(<clase>,
// hashtext(<clave>)), keeps their key spaces apart. the numbers carry "SR" (0x5352) in their high 16 bits, so they are
// not mistaken for another app's on the same database (caja-backend's Candados carry "CA"). a module adds its own
// kind here
enum class Candado(
    val clase: Int
) {
    // the correlativo of a numbered serie in a year: AN-AAAA-NNNNNN (organización|AN|anio, see bloquearEnOrganizacion)
    SERIE(0x5352_0001),

    // the versions of one CUIS code: closing the one in force and adding the next are one step (familia|codigo)
    CODIGO_INFRACCION(0x5352_0002),

    // a notificación previa: its subsanación and the acta that names it do not cross (the notificación's id)
    NOTIFICACION(0x5352_0003),

    // an acta: the acts that end it (its anulación, a resolución that leaves it without effect) do not cross (its id)
    ACTA(0x5352_0004),

    // the correlativo of a resolución's serie in a year: RIS-AAAA-NNNNNN and RGR-AAAA-NNNNNN (organización|tipo|anio, see bloquearEnOrganizacion)
    RESOLUCION(0x5352_0005),

    // a resolución's notificaciones: each intento is the count so far + 1 (the resolución's id)
    NOTIFICACION_RESOLUCION(0x5352_0006),

    // the corrida de brecha of a year: one active per organization and year (organización|anio)
    BRECHA(0x5352_0007),

    // the ordenes de caja of a contribuyente: a sincronización reads what was queued and writes its plan as one step
    // (organización|contribuyente, see bloquearEnOrganizacion)
    CAJA_CONTRIBUYENTE(0x5352_0008),

    // a pago of caja (#165): the buzón's retries of one pagoId do not cross, and a PAGO_ANULADO waits for its original
    // (organización|pagoId, see bloquearEnOrganizacion).
    // THE ORDER OF THE CAJA'S LOCKS, in every transaction that takes more than one: first CAJA_PAGO (the pago's own
    // pagoId, then its original's), then CAJA_CONTRIBUYENTE, by the contribuyentes' ids ascending. a pago
    // (PagosService), a sincronización (CajaService, one contribuyente) and a write of cuotas de arbitrio (anular,
    // redeterminar: the contribuyentes only) all follow it, so none waits on another in a cycle
    CAJA_PAGO(0x5352_0009),

    // the parámetros of one tipo and clave (organización|tipo|clave, see bloquearEnOrganizacion): registering one and
    // verifying one read the others first, and two verifications never leave two VIGENTE rows that overlap
    PARAMETRO_TRIBUTARIO(0x5352_000A),

    // the aranceles of one clave (organización|id_aranc|anio, see bloquearEnOrganizacion): registering, correcting,
    // verifying, annulling, deleting and importing one read the others of its clave first, so two never leave two
    // alive (or two VIGENTE) with it. an importación takes those of its rows, sorted, lote by lote
    ARANCEL(0x5352_000B)
}

// postgres' transaction advisory locks, by kind and key (caja-backend's Candados). wasichai has no row lock nor
// compound unique: what two requests must not do at once is ordered here. the lock belongs to the running transaction
// and is released on its commit or rollback, never before. outside a transaction it fails: in autocommit it would be
// taken and released by the same statement, and protect nothing. hashtext gives an int4: two keys of the same kind may
// fall on the same lock, which only orders a bit more than needed
@Component
class Candados(
    private val db: DatabaseClient,
    private val currentUser: CurrentUser
) {
    // the lock of what is ordered inside the caller's organization: its id leads the key, so two organizations do not
    // wait on each other (their series are their own)
    suspend fun bloquearEnOrganizacion(
        candado: Candado,
        clave: String
    ) = bloquear(candado, Companion.clave(currentUser.require().organizationId, clave))

    suspend fun bloquear(
        candado: Candado,
        clave: String
    ) {
        check(enTransaccion()) { "El candado ${candado.name} '$clave' se toma dentro de una transacción: fuera de ella no protege nada" }
        db
            .sql("SELECT pg_advisory_xact_lock(:clase, hashtext(:clave))")
            .bind("clase", candado.clase)
            .bind("clave", clave)
            .fetch()
            .one()
            .awaitSingle()
    }

    // the reactive transaction lives in reactor's context, which the coroutine carries
    private suspend fun enTransaccion(): Boolean =
        try {
            TransactionSynchronizationManager.forCurrentTransaction().awaitSingle().isActualTransactionActive
        } catch (_: NoTransactionException) {
            false
        }

    companion object {
        fun clave(
            organizacion: UUID,
            clave: String
        ): String = "$organizacion|$clave"
    }
}
