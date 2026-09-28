package srtm.emision

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

// the one masiva running in the whole deployment (wasichai/srtm-backend#47): whoever holds it runs the worker, or
// the maintenance that assumes no worker runs (failing the orphaned jobs, cleaning up files)
interface CerrojoEmision {
    // null: another worker (in this instance or another) holds it
    suspend fun tomar(): Cerrojo?
}

fun interface Cerrojo {
    suspend fun soltar()
}

// a postgres session advisory lock, on a connection of its own held while the lock is: postgres lets it go when that
// session ends, so an instance that dies does not keep it. the connection is the pool's, and goes back to it unlocked
@Component
class CerrojoPostgres(
    private val conexiones: ConnectionFactory
) : CerrojoEmision {
    override suspend fun tomar(): Cerrojo? {
        val conexion = conexiones.create().awaitSingle()
        val tomado =
            try {
                consultar(conexion, "SELECT pg_try_advisory_lock(\$1)")
            } catch (e: Throwable) {
                cerrar(conexion)
                throw e
            }
        if (!tomado) {
            cerrar(conexion)
            return null
        }
        return Cerrojo {
            withContext(NonCancellable) {
                try {
                    consultar(conexion, "SELECT pg_advisory_unlock(\$1)")
                } finally {
                    cerrar(conexion)
                }
            }
        }
    }

    private suspend fun consultar(
        conexion: Connection,
        sql: String
    ): Boolean =
        conexion
            .createStatement(sql)
            .bind("\$1", CLAVE)
            .execute()
            .awaitSingle()
            .map { row, _ -> row.get(0, Boolean::class.javaObjectType) }
            .awaitSingle() == true

    private suspend fun cerrar(conexion: Connection) {
        conexion.close().awaitFirstOrNull()
    }

    private companion object {
        // "SRTMEMIS": the masiva's key among the advisory locks of the database
        const val CLAVE = 0x5352544D454D4953L
    }
}
