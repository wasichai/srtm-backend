package srtm

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronizationManager

// srtm's kinds of lock, each with its fixed number: postgres' two-integer form, pg_advisory_xact_lock(<clase>,
// hashtext(<clave>)), keeps their key spaces apart. the numbers carry "SR" (0x5352) in their high 16 bits, so they are
// not mistaken for another app's on the same database (caja-backend's Candados carry "CA"). a module adds its own
// kind here
enum class Candado(
    val clase: Int
) {
    // the correlativo of a numbered serie in a year: AN-AAAA-NNNNNN
    SERIE(0x5352_0001)
}

// postgres' transaction advisory locks, by kind and key (caja-backend's Candados). wasichai has no row lock nor
// compound unique: what two requests must not do at once is ordered here. the lock belongs to the running transaction
// and is released on its commit or rollback, never before. outside a transaction it fails: in autocommit it would be
// taken and released by the same statement, and protect nothing. hashtext gives an int4: two keys of the same kind may
// fall on the same lock, which only orders a bit more than needed
@Component
class Candados(
    private val db: DatabaseClient
) {
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
}
