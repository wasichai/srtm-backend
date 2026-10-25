package srtm.emision

import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationContext
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import srtm.arbitrios.ArbitriosService
import srtm.arbitrios.MaquinaDeterminacion
import srtm.arbitrios.TIPO_DETERMINACION
import srtm.arbitrios.TrabajoDeterminacion
import srtm.caja.CajaService
import srtm.impuesto.ParametrosTributarios
import wasichai.core.audit.AuditService
import wasichai.core.platform.WasichaiSchemas
import java.nio.file.Path
import java.util.UUID

// core's tables of `organizaciones` only: what a test's own group of workers sees
class TablasDeOrganizaciones(
    private val organizaciones: Set<UUID>,
    db: DatabaseClient,
    schemas: WasichaiSchemas
) : TablasCore(db, schemas) {
    override suspend fun de(
        objeto: String,
        campos: List<String>
    ): List<TablaCore> = super.de(objeto, campos).filter { it.organizacion in organizaciones }
}

// a test's lotes and its groups of workers, limited to its organizations (#173). the test db is shared by every class
// of a run, one after the other, and maybe by another run: a test fails what is still to run of its organizations
// only, and its groups take, assemble and fail the abandoned jobs of those organizations only, of every kind (the
// emission's and the determination's). another organization's pending lote is left as it is. the group's other
// collaborators are the context's: the caja and the parámetros act on the organization of the lote taken, and the
// retention sweeps the emissions past their retention of every organization, as in production
//
// two runs at once on the same db still share the seeded organization (and ConCajaApiTest's, of a fixed slug), and the
// context's own workers (EmisionMasivaApiTest's) take any organization's lotes: each run keeps a db of its own
class LotesDelTest(
    private val contexto: ApplicationContext
) {
    private inline fun <reified T : Any> bean(): T = contexto.getBean(T::class.java)

    // every lote and job still to run of `organizaciones` is failed: the test's groups then take only what it creates
    fun soloLasDelTest(organizaciones: Set<UUID>) {
        val db = bean<DatabaseClient>()
        val tablas = TablasDeOrganizaciones(organizaciones, db, bean())
        runBlocking {
            for (tipo in listOf(TIPO_EMISION, TIPO_DETERMINACION)) {
                for (t in tablas.lotes(tipo)) {
                    val l = t.lotes.columna("estado")
                    db
                        .sql("UPDATE ${t.lotes.tabla} SET $l = 'FALLIDO' WHERE $l IN ('PENDIENTE', 'EN_PROCESO')")
                        .fetch()
                        .awaitRowsUpdated()
                    val e = t.trabajos.columna("estado")
                    db
                        .sql("UPDATE ${t.trabajos.tabla} SET $e = 'FALLIDA' WHERE $e IN (:activos)")
                        .bind("activos", TRABAJOS_ACTIVOS)
                        .fetch()
                        .awaitRowsUpdated()
                }
            }
        }
    }

    // a group like TrabajadoresEmision.grupo's, on the tables of `organizaciones` only. not started
    fun grupo(
        instancia: String,
        cantidad: Int,
        temporales: Path,
        organizaciones: Set<UUID>,
        documentos: DocumentosDeEmision = bean()
    ): GrupoTrabajadores {
        val db = bean<DatabaseClient>()
        val auditoria = bean<AuditService>()
        val tablas = TablasDeOrganizaciones(organizaciones, db, bean())
        val config = bean<EmisionProperties>()
        val identidad = bean<IdentidadEmision>()
        // the other kinds the context's groups take: each one rebuilt on the same tables
        val otros = contexto.getBeanProvider(TrabajoPorLotes::class.java).orderedStream().toList()
        check(otros.size == 1 && otros[0] is TrabajoDeterminacion) {
            "LotesDelTest builds only TrabajoDeterminacion on the test's tables: ${otros.map { it.javaClass.simpleName }}"
        }
        return GrupoTrabajadores(
            instancia,
            cantidad,
            temporales,
            config,
            LotesEmision(db, tablas),
            EstadoEmisiones(db, tablas, auditoria),
            bean(),
            documentos,
            bean(),
            identidad,
            bean(),
            bean<ParametrosTributarios>(),
            listOf(TrabajoDeterminacion(MaquinaDeterminacion(db, tablas, auditoria), bean<ArbitriosService>(), identidad, config, db)),
            bean<CajaService>()
        )
    }
}
