package srtm.arbitrios

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import srtm.emision.TablasCore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// core indexes only organization_id, the state and the unique fields (wasichai 0.2.0), and a year brings 540 000 to
// 900 000 cuotas: a predio's or a contribuyente's would be a scan of the table. the app adds its own two, on each
// organization's table, once: at startup, and on the first use of an organization whose model came later
@Component
class IndicesArbitrios(
    private val db: DatabaseClient,
    private val tablas: TablasCore
) {
    private val hechas = ConcurrentHashMap.newKeySet<UUID>()

    @EventListener(ApplicationReadyEvent::class)
    fun alArrancar() {
        runBlocking { runCatching { asegurar() }.onFailure { log.error("no se pudieron crear los índices de {}", CUOTA_ARBITRIO, it) } }
    }

    // organizacion null: every organization's
    suspend fun asegurar(organizacion: UUID? = null) {
        if (organizacion != null && organizacion in hechas) return
        for (t in tablas.de(CUOTA_ARBITRIO, listOf("anio", "predio", "contribuyente"))) {
            if (organizacion != null && t.organizacion != organizacion) continue
            if (t.organizacion in hechas) continue
            val prefijo = "${CUOTA_ARBITRIO}_${t.organizacion.toString().replace("-", "")}"
            for (campo in listOf("predio", "contribuyente")) {
                db.sql("CREATE INDEX IF NOT EXISTS ${prefijo}_$campo ON ${t.tabla} (${t.columna("anio")}, ${t.columna(campo)})").then().awaitFirstOrNull()
            }
            hechas += t.organizacion
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(IndicesArbitrios::class.java)
    }
}
